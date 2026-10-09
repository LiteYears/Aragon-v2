package com.example.agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
 * Unbounded iteration budget (up to 10000 steps), anti-stagnation cycle prevention,
 * scratchpad-as-files memory persistence, capability-aware routing, and continuous artifact organization.
 */
class AgentEngine(
  val workspaceDir: File,
  initialProvider: LLMProvider = AutonomousSandboxProvider(),
  private val maxSteps: Int = 10000
) {

  val workspace = WorkspaceManager(workspaceDir)
  val scratchpad = ScratchpadMemoryManager(workspace)
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
   * Starts an isolated new session for a task, removing all previous artifacts and memory.
   */
  fun startNewSession(newSessionId: String = UUID.randomUUID().toString()): String {
    activeJob?.cancel()
    workspace.cleanAllArtifacts()
    scratchpad.initSession(newSessionId)
    scratchpad.clearSessionMemory()
    _state.value = AgentState(
      sessionId = newSessionId,
      task = null,
      status = AgentStatus.IDLE,
      currentAction = null,
      artifacts = emptyList(),
      executionFeed = emptyList(),
      messages = emptyList(),
      plan = emptyList(),
      fiveStageRecord = FiveStageRecord(),
      canCancel = false
    )
    return newSessionId
  }

  /**
   * Dispatches a new task to the Agent in an isolated separate session.
   * Cancels any previously running execution and removes old artifacts to start clean.
   */
  fun submitTask(intent: String, expectedArtifact: String? = null) {
    if (intent.isBlank()) return

    // Cancel any running job
    activeJob?.cancel()

    // Isolated new session per task to prevent state/scratchpad mix-up
    val sessionId = UUID.randomUUID().toString()
    workspace.cleanAllArtifacts()
    scratchpad.initSession(sessionId)

    val task = Task(
      id = UUID.randomUUID().toString(),
      sessionId = sessionId,
      goal = intent.trim(),
      status = AgentStatus.THINKING,
      expectedArtifact = expectedArtifact
    )

    _state.value = AgentState(
      sessionId = sessionId,
      task = task,
      status = AgentStatus.THINKING,
      currentAction = "Understanding task objective: '${task.goal}'",
      artifacts = emptyList(),
      plan = emptyList(),
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

    activeJob = scope.launch {
      run(task)
    }
  }

  /**
   * The Central Autonomous Agent Loop.
   * Unbounded iterations (up to maxSteps = 10000).
   * Finishes as soon as verified deliverables exist, whether in 2 turns or 100.
   */
  private suspend fun run(task: Task) {
    var stepCounter = 1

    // Layer 0.2: Check for existing checkpoint on disk to resume rather than restarting blindly
    val existingCheckpoint = scratchpad.loadCheckpoint()
    val isResuming = existingCheckpoint != null &&
      existingCheckpoint.taskGoal.equals(task.goal.trim(), ignoreCase = true) &&
      existingCheckpoint.status == "IN_PROGRESS"

    val initialPlan = if (isResuming) existingCheckpoint!!.planItems else scratchpad.loadPlan()

    // Initialize clean AgentState
    var currentState = _state.value.copy(
      sessionId = task.sessionId,
      task = task,
      status = AgentStatus.THINKING,
      currentAction = if (isResuming) "Resuming from checkpoint turn ${existingCheckpoint?.currentTurn ?: 0}..." else "Understanding task objective: '${task.goal}'",
      currentStep = if (isResuming) existingCheckpoint?.currentTurn ?: 0 else 0,
      plan = initialPlan,
      fiveStageRecord = FiveStageRecord(intent = task.goal, currentPlan = initialPlan),
      executionFeed = listOf(
        ExecutionStep(
          stepNumber = 1,
          type = StepType.TASK_INTENT,
          title = if (isResuming) "Task Resumed from Checkpoint" else "Task Received",
          content = if (isResuming) "${task.goal} (Resuming from Turn ${existingCheckpoint?.currentTurn ?: 0} with ${scratchpad.loadAllFindings().size} recorded findings)" else task.goal
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

    // Loop stagnation & anti-repetition tracking:
    // Tracks history of tool signatures to ensure identical commands are never executed > 2 times.
    val actionSignatureHistory = mutableListOf<String>()
    var turnCounter = if (isResuming) existingCheckpoint?.currentTurn ?: 0 else 0
    var providerDropRetries = 0

    try {
      while (turnCounter < maxSteps) {
        turnCounter++
        currentCoroutineContext().ensureActive()

        // 1. THINKING & PLANNING PHASE
        currentState = currentState.copy(
          status = AgentStatus.PLANNING,
          currentStep = turnCounter,
          currentAction = "Consulting LLM for next action (Turn $turnCounter)..."
        )
        _state.value = currentState

        // Layer 0.3: Context budget accounting — inject pinned user goal, scratchpad findings, and plan
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

            // Layer 1.2: Null reasoning circuit breaker
            if (decision.thought.isNullOrBlank() && toolCalls.isNotEmpty()) {
              // Circuit breaker: do not execute blind tool calls without thought
              currentState = currentState.copy(
                currentAction = "Circuit breaker: Re-reading plan and scratchpad context..."
              )
              _state.value = currentState
              continue
            }

            // Record Plan & Thought
            val updatedPlan = if (decision.plan != null) {
              val parsedLines = decision.plan.lines().filter { it.isNotBlank() }
              scratchpad.savePlan(parsedLines)
              parsedLines
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

            // Organic, smooth fluid pacing after model reasoning
            delay(120)

            // 2. AUTHORITATIVE TOOL EXECUTION PHASE
            for (toolCall in toolCalls) {
              currentCoroutineContext().ensureActive()

              val actionSig = "${toolCall.toolName}::${formatArguments(toolCall.arguments)}"
              val recentIdenticalCount = actionSignatureHistory.takeLast(6).count { it == actionSig }

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
                currentAction = "Executing '${toolCall.toolName}'...",
                executionFeed = currentFeed
              )
              _state.value = currentState

              // Pacing between tool display and execution
              delay(100)

              val (toolResult, observation) = if (recentIdenticalCount >= 2) {
                // Anti-stagnation safeguard: Do not execute identical tool call more than twice!
                val skipMsg = "Intervention: '${toolCall.toolName}' with identical arguments was already executed $recentIdenticalCount times. Duplicate execution skipped to prevent redundant looping. Synthesize existing results from notes/research.jsonl, write the verified deliverable, or conclude objective."
                val fakeResult = ToolResult(
                  callId = toolCall.callId,
                  toolName = toolCall.toolName,
                  status = ToolStatus.SUCCEEDED,
                  arguments = toolCall.arguments,
                  output = skipMsg,
                  error = null,
                  duration = 10L,
                  exitCode = 0
                )
                val obs = Observation(
                  callId = toolCall.callId,
                  toolName = toolCall.toolName,
                  isSuccess = true,
                  summary = skipMsg,
                  rawOutput = skipMsg
                )
                Pair(fakeResult, obs)
              } else {
                actionSignatureHistory.add(actionSig)
                val res = executor.execute(toolCall)
                val obs = executor.createObservation(res)

                // Layer 0.1: Externalized working memory — auto-ingest tool research data to research.jsonl
                if (res.status == ToolStatus.SUCCEEDED && res.output != null) {
                  scratchpad.autoIngestFromToolOutput(toolCall.toolName, toolCall.arguments, res.output)
                }

                Pair(res, obs)
              }

              // Deliberate observation reveal pacing
              delay(100)

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

              // Continuously scan and organize artifacts on filesystem
              val updatedArtifacts = workspace.listAllArtifacts()

              // Layer 0.2: Auto-save checkpoint at step boundary
              scratchpad.saveCheckpoint(
                AgentCheckpoint(
                  taskGoal = task.goal,
                  currentTurn = turnCounter,
                  planItems = currentState.plan,
                  artifactPaths = updatedArtifacts.map { it.path },
                  totalFindingsCount = scratchpad.loadAllFindings().size,
                  status = "IN_PROGRESS"
                )
              )

              // Append to conversation history for the next turn
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
                  content = observation.rawOutput,
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
          }

          is LLMDecision.Complete -> {
            // 4. OBJECTIVE VERIFICATION & SELF-AUDIT PHASE
            currentState = currentState.copy(
              status = AgentStatus.VERIFYING,
              currentAction = "Authoritatively verifying objective satisfaction against filesystem..."
            )
            _state.value = currentState

            delay(100)
            val verificationResult = verifyObjective(task, currentState)

            if (verificationResult.isSatisfied) {
              delay(100)
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

              // Clean up temporary scratch files (.tmp, partial files) and update deliverables state
              workspace.cleanTemporaryFiles()
              val finalArtifacts = workspace.listAllArtifacts().filter { it.exists }

              // Final checkpoint update
              scratchpad.saveCheckpoint(
                AgentCheckpoint(
                  taskGoal = task.goal,
                  currentTurn = turnCounter,
                  planItems = currentState.plan,
                  artifactPaths = finalArtifacts.map { it.path },
                  totalFindingsCount = scratchpad.loadAllFindings().size,
                  status = "COMPLETED"
                )
              )

              currentState = currentState.copy(
                status = AgentStatus.COMPLETED,
                currentAction = "Task completed and verified.",
                completionSummary = conclusionText,
                verification = verificationResult,
                artifacts = finalArtifacts,
                executionFeed = completedFeed,
                fiveStageRecord = currentState.fiveStageRecord.copy(conclusion = conclusionText),
                canCancel = false
              )
              _state.value = currentState
              return
            } else {
              // DO NOT COMPLETE: Verification rejected premature or unverified completion
              val rejectionMsg = "Objective Verification Incomplete: ${verificationResult.details}. The task cannot be concluded until the required deliverables exist on disk."

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
            }
          }

          is LLMDecision.ProviderError -> {
            if (providerDropRetries < 2) {
              providerDropRetries++
              val retryFeed = currentState.executionFeed + ExecutionStep(
                stepNumber = ++stepCounter,
                type = StepType.OBSERVATION,
                title = "Endpoint Drop Recovery ($providerDropRetries/2)",
                content = "Endpoint dropped (${decision.message.take(160)}). Retrying automatically..."
              )
              currentState = currentState.copy(
                currentAction = "Recovering from endpoint drop (attempt $providerDropRetries/2)...",
                executionFeed = retryFeed
              )
              _state.value = currentState
              delay(1500)
              continue
            } else if (providerDropRetries == 2) {
              providerDropRetries++
              val fallbackFeed = currentState.executionFeed + ExecutionStep(
                stepNumber = ++stepCounter,
                type = StepType.OBSERVATION,
                title = "Autonomous Fallback Engaged",
                content = "Engaged local Autonomous Sandbox Engine to execute tools and verify deliverables without halting."
              )
              currentState = currentState.copy(
                currentAction = "Running via Autonomous Sandbox Engine...",
                executionFeed = fallbackFeed
              )
              _state.value = currentState
              activeProvider = AutonomousSandboxProvider()
              delay(1000)
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

      val maxStepsMsg = "Execution reached turn limit ($maxSteps turns) without objective verification."
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
      // Layer 0.2: Graceful cancellation snapshot
      scratchpad.saveCheckpoint(
        AgentCheckpoint(
          taskGoal = task.goal,
          currentTurn = turnCounter,
          planItems = currentState.plan,
          artifactPaths = workspace.listAllArtifacts().map { it.path },
          totalFindingsCount = scratchpad.loadAllFindings().size,
          status = "CANCELLED"
        )
      )

      val feed = currentState.executionFeed + ExecutionStep(
        stepNumber = ++stepCounter,
        type = StepType.ERROR,
        title = "Execution Cancelled",
        content = "Agent execution was stopped by user request. Workspace checkpoint and research notes saved."
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
      val diskValidation = ArtifactValidatorRegistry.validateExistingFile(file)
      if (!diskValidation.isValid) {
        return VerificationResult(
          isSatisfied = false,
          details = "Target artifact '${task.expectedArtifact}' failed disk validation: ${diskValidation.errorMessage}"
        )
      }
    }

    // 3. Inferred artifact from goal text
    val goalLower = task.goal.lowercase()
    val words = task.goal.split("\\s+".toRegex())
    for (word in words) {
      val cleanWord = word.trim('.', ',', '"', '\'', '`')
      if (cleanWord.contains('.') && cleanWord.length > 3) {
        val ext = cleanWord.substringAfterLast('.', "")
        if (ext in listOf("txt", "md", "json", "py", "sh", "csv", "html", "docx")) {
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
                details = "Expected deliverable '$cleanWord' does not exist in workspace."
              )
            }
            val diskValidation = ArtifactValidatorRegistry.validateExistingFile(file)
            if (!diskValidation.isValid) {
              return VerificationResult(
                isSatisfied = false,
                details = "Expected deliverable '$cleanWord' failed validation: ${diskValidation.errorMessage}"
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
      details = "Verified on disk: ${state.toolExecutions.size} action(s) executed successfully. Deliverables verified on filesystem.",
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
      sb.appendLine("EXPECTED DELIVERABLE: ${task.expectedArtifact}")
    }
    if (state.plan.isNotEmpty()) {
      sb.appendLine("CURRENT PLAN (from plan.md):")
      state.plan.forEach { sb.appendLine("- $it") }
    }

    // Layer 0.1 & 0.3: Inject externalized research memory so model never acts blind
    val researchSummary = scratchpad.getStructuredResearchSummary(maxFindings = 8)
    sb.appendLine()
    sb.appendLine(researchSummary)

    val currentArtifacts = workspace.listAllArtifacts()
    sb.appendLine()
    sb.appendLine("WORKSPACE DELIVERABLES (${currentArtifacts.size} files):")
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
      sb.appendLine(state.observations.last().rawOutput.take(1500))
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
      // Snapshot state to checkpoint before cancel
      scratchpad.saveCheckpoint(
        AgentCheckpoint(
          taskGoal = _state.value.task?.goal ?: "Unknown",
          currentTurn = _state.value.currentStep,
          planItems = _state.value.plan,
          artifactPaths = workspace.listAllArtifacts().map { it.path },
          totalFindingsCount = scratchpad.loadAllFindings().size,
          status = "CANCELLED"
        )
      )
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
  fun reset(cleanWorkspace: Boolean = true) {
    activeJob?.cancel()
    if (cleanWorkspace) {
      workspace.cleanAllArtifacts()
    }
    startNewSession()
  }
}
