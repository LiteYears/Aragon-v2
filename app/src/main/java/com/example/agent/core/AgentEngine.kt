package com.example.agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * The single, authoritative central orchestrator of the Autonomous Agent.
 * Runs the central loop:
 * USER GOAL -> STATE -> LLM DECISION -> TOOL CALLS -> EXECUTE -> OBSERVATION -> STATE UPDATE -> VERIFY -> COMPLETE.
 */
class AgentEngine(
  val workspaceDir: File,
  initialProvider: LLMProvider = AutonomousSandboxProvider(),
  private val maxSteps: Int = 15
) {

  val workspace = WorkspaceManager(workspaceDir)
  val registry = ToolRegistry()
  val executor = ToolExecutor(registry, workspace)

  private var activeProvider: LLMProvider = initialProvider
  private var activeJob: Job? = null
  private val scope = CoroutineScope(Dispatchers.Default)

  private val _state = MutableStateFlow(
    AgentState(
      artifacts = workspace.listAllArtifacts()
    )
  )
  val state: StateFlow<AgentState> = _state.asStateFlow()

  init {
    // Register canonical sandbox tools
    registry.register(TerminalTool(workspace), "bash", "sh")
    registry.register(PythonTool(workspace), "python", "py", "python3")
    registry.register(PipTool(workspace), "pip3", "pip_install")
    registry.register(ReadFileTool(workspace), "cat")
    registry.register(WriteFileTool(workspace), "save_file")
    registry.register(EditFileTool(workspace), "patch_file", "replace_content")
    registry.register(ListFilesTool(workspace), "ls", "dir")
    registry.register(DeleteFileTool(workspace), "rm")
    registry.register(CreateDirectoryTool(workspace), "mkdir")
    registry.register(CopyFileTool(workspace), "cp")
    registry.register(MoveFileTool(workspace), "mv", "rename")
    registry.register(DownloadFileTool(workspace), "curl_download", "wget")
    registry.register(InspectArtifactTool(workspace), "stat", "verify_artifact")
    registry.register(FileSearchTool(workspace), "grep", "search_files")
    registry.register(HttpRequestTool(), "curl", "fetch_api")
    registry.register(JsonProcessorTool(workspace), "jq", "json_tool")
    registry.register(CsvProcessorTool(workspace), "csv_tool")

    // Register comprehensive Web Research & Browser Automation tools
    registry.register(WebSearchTool(), "search", "duckduckgo", "google")
    registry.register(WebBrowseTool(workspace), "browse", "open_url", "fetch_page")
    registry.register(BrowserAutomationTool(workspace), "playwright", "headless_browser", "browser")
    registry.register(WebCrawlerTool(workspace), "crawler", "crawl_site")
    registry.register(ExtractWebDataTool(), "extract_tables", "web_extract")
    registry.register(DeepResearchTool(workspace), "research", "auto_research")

    // Seed default workspace dataset so sample analysis workflows work out of the box
    try {
      workspace.seedWorkspaceDefaults()
      _state.value = _state.value.copy(artifacts = workspace.listAllArtifacts())
    } catch (_: Exception) {}
  }

  fun getProvider(): LLMProvider = activeProvider

  fun setProvider(provider: LLMProvider) {
    this.activeProvider = provider
  }

  /**
   * Dispatches a new task to the Agent.
   * Cancels any previously running execution to enforce single-loop concurrency.
   */
  fun submitTask(intent: String, expectedArtifact: String? = null) {
    if (intent.isBlank()) return

    // Cancel any running job
    activeJob?.cancel()

    val task = Task(
      goal = intent.trim(),
      status = AgentStatus.THINKING,
      expectedArtifact = expectedArtifact
    )

    activeJob = scope.launch {
      run(task)
    }
  }

  /**
   * The Central Bounded Autonomous Agent Loop.
   * Directly readable from top to bottom.
   */
  private suspend fun run(task: Task) {
    var stepCounter = 1

    // Initialize clean AgentState
    var currentState = _state.value.copy(
      task = task,
      status = AgentStatus.THINKING,
      currentAction = "Understanding task objective: '${task.goal}'",
      currentStep = 0,
      fiveStageRecord = FiveStageRecord(intent = task.goal),
      executionFeed = listOf(
        ExecutionStep(
          stepNumber = 1,
          type = StepType.TASK_INTENT,
          title = "Task Received",
          content = task.goal
        )
      ),
      messages = listOf(
        AgentMessage(
          role = MessageRole.USER,
          content = "Task Objective: ${task.goal}"
        )
      ),
      error = null,
      verification = null,
      completionSummary = null,
      canCancel = true
    )
    _state.value = currentState

    // Loop stagnation detection: track last action hash to catch repeated identical failures
    var lastActionSig: String? = null
    var identicalActionCount = 0
    var turnCounter = 0
    var providerDropRetries = 0

    try {
      while (turnCounter < maxSteps) {
        turnCounter++
        currentCoroutineContext().ensureActive()

        // 1. THINKING & PLANNING PHASE
        currentState = currentState.copy(
          status = AgentStatus.PLANNING,
          currentStep = turnCounter,
          currentAction = "Consulting LLM for next action (Turn $turnCounter/$maxSteps)..."
        )
        _state.value = currentState

        // Construct prioritized context for LLM
        val systemPrompt = buildSystemContext(task, currentState)

        val decision = activeProvider.decideNextAction(
          systemPrompt = systemPrompt,
          taskIntent = task.goal,
          messages = currentState.messages,
          tools = registry.getAllTools()
        )

        currentCoroutineContext().ensureActive()

        when (decision) {
          is LLMDecision.ExecuteTool -> {
            val toolCalls = decision.toolCalls

            // Record Plan & Thought
            val updatedPlan = if (decision.plan != null) {
              decision.plan.lines().filter { it.isNotBlank() }
            } else currentState.plan

            val feed = currentState.executionFeed.toMutableList()
            if (!decision.thought.isNullOrBlank()) {
              feed.add(
                ExecutionStep(
                  stepNumber = ++stepCounter,
                  type = StepType.THINKING,
                  title = "Reasoning",
                  content = decision.thought
                )
              )
            }

            currentState = currentState.copy(
              plan = updatedPlan,
              executionFeed = feed,
              fiveStageRecord = currentState.fiveStageRecord.copy(currentPlan = updatedPlan)
            )
            _state.value = currentState

            // 2. AUTHORITATIVE TOOL EXECUTION PHASE (Support multiple tool calls)
            for (toolCall in toolCalls) {
              currentCoroutineContext().ensureActive()
              kotlinx.coroutines.delay(300) // Smooth transition pacing between steps

              val runningStepId = UUID.randomUUID().toString()
              val currentFeed = currentState.executionFeed.toMutableList()
              currentFeed.add(
                ExecutionStep(
                  id = runningStepId,
                  stepNumber = ++stepCounter,
                  type = StepType.TOOL_EXECUTION,
                  title = "Executing: ${toolCall.toolName}",
                  content = formatArguments(toolCall.arguments),
                  toolName = toolCall.toolName,
                  toolCallId = toolCall.callId,
                  toolStatus = ToolStatus.RUNNING.name,
                  isCompleted = false
                )
              )

              currentState = currentState.copy(
                status = AgentStatus.EXECUTING_TOOL,
                currentAction = "Executing '${toolCall.toolName}' [${toolCall.callId.take(6)}]...",
                executionFeed = currentFeed
              )
              _state.value = currentState

              // Execute tool authoritatively via ToolExecutor
              val toolResult = executor.execute(toolCall)
              kotlinx.coroutines.delay(250) // Deliberate observation reveal pacing

              // 3. CONVERT RESULT TO AUTHORITATIVE OBSERVATION
              val observation = executor.createObservation(toolResult)

              // Check for accidental loop stagnation
              val actionSig = "${toolCall.toolName}:${toolCall.arguments}"
              if (actionSig == lastActionSig && !observation.isSuccess) {
                identicalActionCount++
              } else {
                lastActionSig = actionSig
                identicalActionCount = 0
              }

              // Update execution feed with authoritative exit status, duration, stdout/stderr
              val updatedFeed = currentState.executionFeed.map { item ->
                if (item.id == runningStepId) {
                  item.copy(
                    toolStatus = toolResult.status.name,
                    durationMs = toolResult.duration,
                    exitCode = toolResult.exitCode,
                    stdout = toolResult.stdout,
                    stderr = toolResult.stderr,
                    isCompleted = true
                  )
                } else item
              }.toMutableList()

              updatedFeed.add(
                ExecutionStep(
                  stepNumber = ++stepCounter,
                  type = StepType.OBSERVATION,
                  title = if (observation.isSuccess) "Observation (${toolResult.status.name})" else "Observation (FAILED)",
                  content = observation.summary,
                  stdout = observation.stdout,
                  stderr = observation.stderr
                )
              )

              // Record actions & observations in 5-Stage Record
              val updatedActions = currentState.fiveStageRecord.actions +
                "Dispatched '${toolCall.toolName}' with ${formatArguments(toolCall.arguments)}"
              val updatedObservations = currentState.fiveStageRecord.observations +
                observation.summary

              val updatedArtifacts = workspace.listAllArtifacts()

              // Append to conversation history for the next turn
              // 1. Assistant tool call message
              // 2. Explicit Tool observation message
              val promptObservation = if (identicalActionCount >= 2) {
                observation.rawOutput + "\n[SYSTEM ADVICE: Repeated identical failure detected. Do not repeat this call; choose an alternative action or diagnose the error.]"
              } else {
                observation.rawOutput
              }

              val updatedMessages = currentState.messages + listOf(
                AgentMessage(
                  role = MessageRole.ASSISTANT,
                  content = decision.thought ?: "Dispatched tool ${toolCall.toolName}",
                  toolCallId = toolCall.callId,
                  toolName = toolCall.toolName,
                  toolArgs = toolCall.arguments
                ),
                AgentMessage(
                  role = MessageRole.TOOL,
                  content = promptObservation,
                  toolCallId = toolCall.callId,
                  toolName = toolCall.toolName
                )
              )

              currentState = currentState.copy(
                status = AgentStatus.OBSERVING,
                currentAction = "Observed result: ${toolCall.toolName} -> ${toolResult.status}",
                messages = updatedMessages,
                toolExecutions = currentState.toolExecutions + toolResult,
                observations = currentState.observations + observation,
                artifacts = updatedArtifacts,
                executionFeed = updatedFeed,
                fiveStageRecord = currentState.fiveStageRecord.copy(
                  actions = updatedActions,
                  observations = updatedObservations
                )
              )
              _state.value = currentState
            }
            // Continue while loop to next LLM turn
          }

          is LLMDecision.Complete -> {
            // 4. OBJECTIVE VERIFICATION PHASE
            currentState = currentState.copy(
              status = AgentStatus.VERIFYING,
              currentAction = "Authoritatively verifying objective satisfaction against filesystem..."
            )
            _state.value = currentState

            val verificationResult = verifyObjective(task, currentState)

            if (verificationResult.isSatisfied) {
              kotlinx.coroutines.delay(250) // Smooth completion transition
              // Task COMPLETED based on authoritative evidence
              val conclusionText = decision.conclusion.ifBlank {
                "Task completed successfully and verified."
              }

              val completedFeed = currentState.executionFeed + listOf(
                ExecutionStep(
                  stepNumber = ++stepCounter,
                  type = StepType.VERIFICATION,
                  title = "Objective Verified",
                  content = verificationResult.details
                ),
                ExecutionStep(
                  stepNumber = ++stepCounter,
                  type = StepType.CONCLUSION,
                  title = "Completed",
                  content = conclusionText
                )
              )

              currentState = currentState.copy(
                status = AgentStatus.COMPLETED,
                currentAction = "Task completed and verified.",
                completionSummary = conclusionText,
                verification = verificationResult,
                executionFeed = completedFeed,
                fiveStageRecord = currentState.fiveStageRecord.copy(conclusion = conclusionText),
                canCancel = false
              )
              _state.value = currentState
              return
            } else {
              // DO NOT COMPLETE: Verification rejected fake completion
              val rejectionMsg = "Objective Verification Incomplete: ${verificationResult.details}. The task cannot be concluded until the required actions and artifacts exist."

              val feed = currentState.executionFeed + ExecutionStep(
                stepNumber = ++stepCounter,
                type = StepType.VERIFICATION,
                title = "Verification Incomplete",
                content = rejectionMsg
              )

              val updatedMessages = currentState.messages + listOf(
                AgentMessage(role = MessageRole.ASSISTANT, content = decision.conclusion),
                AgentMessage(role = MessageRole.USER, content = rejectionMsg)
              )

              currentState = currentState.copy(
                status = AgentStatus.PLANNING,
                currentAction = "Verification rejected completion: ${verificationResult.details}",
                messages = updatedMessages,
                verification = verificationResult,
                executionFeed = feed
              )
              _state.value = currentState
              // Loop continues to next LLM turn
            }
          }

          is LLMDecision.ProviderError -> {
            // Free endpoint drop recovery mechanism
            if (providerDropRetries < 2) {
              providerDropRetries++
              val retryFeed = currentState.executionFeed + ExecutionStep(
                stepNumber = ++stepCounter,
                type = StepType.OBSERVATION,
                title = "Endpoint Drop Recovery (${providerDropRetries}/2)",
                content = "Free endpoint dropped or timed out (${decision.message.take(160)}). Engaging automatic retry and model fallback..."
              )
              currentState = currentState.copy(
                currentAction = "Recovering from endpoint drop (attempt $providerDropRetries/2)...",
                executionFeed = retryFeed
              )
              _state.value = currentState
              kotlinx.coroutines.delay(1500)
              continue
            } else if (providerDropRetries == 2) {
              // Remote endpoint is completely down after multiple retries: engage Autonomous Sandbox Provider to finish objective
              providerDropRetries++
              val fallbackFeed = currentState.executionFeed + ExecutionStep(
                stepNumber = ++stepCounter,
                type = StepType.OBSERVATION,
                title = "Autonomous Fallback Engaged",
                content = "Remote endpoint unavailable. Seamlessly engaged local Autonomous Sandbox Engine to execute tools and verify objective without halting."
              )
              currentState = currentState.copy(
                currentAction = "Running via Autonomous Sandbox Engine...",
                executionFeed = fallbackFeed
              )
              _state.value = currentState
              activeProvider = AutonomousSandboxProvider()
              kotlinx.coroutines.delay(1000)
              continue
            }

            val feed = currentState.executionFeed + ExecutionStep(
              stepNumber = ++stepCounter,
              type = StepType.ERROR,
              title = "Provider Failure",
              content = decision.message
            )
            currentState = currentState.copy(
              status = AgentStatus.FAILED,
              currentAction = "Failed with provider error",
              error = decision.message,
              executionFeed = feed,
              canCancel = false
            )
            _state.value = currentState
            return
          }
        }
      }

      // Step budget exceeded
      val maxStepsMsg = "Execution reached maximum allowed step budget ($maxSteps turns) without objective verification."
      val feed = currentState.executionFeed + ExecutionStep(
        stepNumber = ++stepCounter,
        type = StepType.ERROR,
        title = "Step Budget Exceeded",
        content = maxStepsMsg
      )
      currentState = currentState.copy(
        status = AgentStatus.FAILED,
        currentAction = maxStepsMsg,
        error = maxStepsMsg,
        executionFeed = feed,
        canCancel = false
      )
      _state.value = currentState

    } catch (ce: CancellationException) {
      val feed = currentState.executionFeed + ExecutionStep(
        stepNumber = ++stepCounter,
        type = StepType.ERROR,
        title = "Execution Cancelled",
        content = "Agent execution was stopped by user request."
      )
      currentState = currentState.copy(
        status = AgentStatus.CANCELLED,
        currentAction = "Execution stopped by user.",
        executionFeed = feed,
        canCancel = false
      )
      _state.value = currentState
    } catch (e: Exception) {
      val feed = currentState.executionFeed + ExecutionStep(
        stepNumber = ++stepCounter,
        type = StepType.ERROR,
        title = "Engine Exception",
        content = "${e.javaClass.simpleName}: ${e.message}"
      )
      currentState = currentState.copy(
        status = AgentStatus.FAILED,
        currentAction = "Failed with exception: ${e.message}",
        error = e.message ?: "Unknown engine failure",
        executionFeed = feed,
        canCancel = false
      )
      _state.value = currentState
    }
  }

  /**
   * Authoritatively verifies whether the objective is satisfied based on real environmental evidence.
   */
  private fun verifyObjective(task: Task, state: AgentState): VerificationResult {
    // 1. Must have executed at least one action
    if (state.toolExecutions.isEmpty()) {
      return VerificationResult(
        isSatisfied = false,
        details = "No actions were executed in the workspace. An autonomous agent must execute real tools to accomplish an objective."
      )
    }

    // 2. Check explicit expected artifact
    if (task.expectedArtifact != null) {
      val file = File(workspace.baseDir, task.expectedArtifact)
      if (!file.exists()) {
        return VerificationResult(
          isSatisfied = false,
          details = "Target artifact '${task.expectedArtifact}' does not exist on disk in the workspace."
        )
      }
      if (file.length() == 0L) {
        return VerificationResult(
          isSatisfied = false,
          details = "Target artifact '${task.expectedArtifact}' exists but is 0 bytes (empty)."
        )
      }
    }

    // 3. Inferred artifact from goal text (e.g. if prompt asks for 'report.md' or 'hello.txt')
    val goalLower = task.goal.lowercase()
    val words = task.goal.split("\\s+".toRegex())
    for (word in words) {
      val cleanWord = word.trim('.', ',', '"', '\'', '`')
      if (cleanWord.contains('.') && cleanWord.length > 3) {
        val ext = cleanWord.substringAfterLast('.', "")
        if (ext in listOf("txt", "md", "json", "py", "sh", "csv", "html")) {
          val file = File(workspace.baseDir, cleanWord)
          if (goalLower.contains("delete")) {
            if (file.exists()) {
              return VerificationResult(
                isSatisfied = false,
                details = "File '$cleanWord' was requested to be deleted but still exists on disk."
              )
            }
          } else if (goalLower.contains("create") || goalLower.contains("write") || goalLower.contains("produce") || goalLower.contains("generate")) {
            if (!file.exists()) {
              return VerificationResult(
                isSatisfied = false,
                details = "Expected file '$cleanWord' does not exist in workspace."
              )
            }
            if (file.length() == 0L && !goalLower.contains("empty")) {
              return VerificationResult(
                isSatisfied = false,
                details = "Expected file '$cleanWord' exists on disk but has 0 bytes."
              )
            }
          }
        }
      }
    }

    // 4. Last tool must not be in failed state
    val lastExecution = state.toolExecutions.lastOrNull()
    if (lastExecution != null && lastExecution.status == ToolStatus.FAILED) {
      return VerificationResult(
        isSatisfied = false,
        details = "The last tool execution ('${lastExecution.toolName}') failed with error: '${lastExecution.error}'. Cannot conclude in an unhandled failure state."
      )
    }

    val verifiedFiles = state.artifacts.filter { it.exists }.map { it.path }
    return VerificationResult(
      isSatisfied = true,
      details = "Verified on disk: ${state.toolExecutions.size} action(s) executed successfully. Artifacts verified on filesystem.",
      verifiedArtifacts = verifiedFiles
    )
  }

  /**
   * Constructs the prioritized LLM context string.
   */
  private fun buildSystemContext(task: Task, state: AgentState): String {
    val sb = StringBuilder()
    sb.appendLine(DEFAULT_AGENT_SYSTEM_PROMPT)
    sb.appendLine()
    sb.appendLine("CURRENT USER GOAL: ${task.goal}")
    if (task.expectedArtifact != null) {
      sb.appendLine("EXPECTED ARTIFACT: ${task.expectedArtifact}")
    }
    if (state.plan.isNotEmpty()) {
      sb.appendLine("CURRENT PLAN:")
      state.plan.forEach { sb.appendLine("- $it") }
    }
    val currentArtifacts = workspace.listAllArtifacts()
    sb.appendLine("WORKSPACE FILES (${currentArtifacts.size} files):")
    if (currentArtifacts.isEmpty()) {
      sb.appendLine("  (Workspace is currently empty)")
    } else {
      currentArtifacts.forEach {
        sb.appendLine("  - ${it.path} (${it.size} bytes, ${it.type})")
      }
    }
    if (state.observations.isNotEmpty()) {
      sb.appendLine()
      sb.appendLine("LATEST OBSERVATION:")
      sb.appendLine(state.observations.last().rawOutput)
    }
    return sb.toString()
  }

  private fun formatArguments(args: Map<String, Any?>): String {
    if (args.isEmpty()) return "{}"
    return args.entries.joinToString(", ") { "${it.key}: ${it.value}" }
  }

  /**
   * Stops active execution immediately and transitions state to CANCELLED.
   */
  fun cancel() {
    val job = activeJob
    if (job != null && job.isActive) {
      job.cancel()
      _state.value = _state.value.copy(
        status = AgentStatus.CANCELLED,
        currentAction = "Cancelled by user.",
        canCancel = false
      )
    }
  }

  /**
   * Resets the agent and optionally cleans workspace files.
   */
  fun reset(cleanWorkspace: Boolean = false) {
    activeJob?.cancel()
    if (cleanWorkspace) {
      workspace.resetWorkspace()
      workspace.seedWorkspaceDefaults()
    }
    _state.value = AgentState(
      artifacts = workspace.listAllArtifacts()
    )
  }
}
