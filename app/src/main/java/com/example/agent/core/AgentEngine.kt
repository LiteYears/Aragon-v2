package com.example.agent.core

import com.alibaba.opensandbox.sandbox.Sandbox
import com.example.agent.docs.DocumentIntelligenceService
import com.example.agent.docs.DocumentListTool
import com.example.agent.docs.DocumentProcessTool
import com.example.agent.docs.DocumentSearchTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * Authoritative Central Orchestrator of the Aragon Agent Kernel.
 *
 * Enforces the strict tool execution pipeline:
 * LLM Tool Call -> Schema Validation -> Tool Registry Lookup -> Permission Check ->
 * Argument Normalization -> Execution -> Real Result -> Observation -> Next Agent Turn.
 *
 * Maintains a single canonical source of truth for tools, filesystem state,
 * command resolution, and deliverables verification.
 */
class AgentEngine(
  val workspaceDir: File,
  initialProvider: LLMProvider = AutonomousSandboxProvider(),
  initialSandboxConfig: com.alibaba.opensandbox.sandbox.config.ConnectionConfig = com.alibaba.opensandbox.sandbox.config.ConnectionConfig.DEFAULT_E2B,
  val maxSteps: Int = 1000,
  private val webEnabled: Boolean = true,
  private val documentService: DocumentIntelligenceService? = null
) {

  private val _state = MutableStateFlow(
    AgentState(
      artifacts = emptyList()
    )
  )
  val state: StateFlow<AgentState> = _state.asStateFlow()

  val sandboxManager: SandboxManager = SandboxManager(workspaceDir, initialSandboxConfig)
  val sandboxProvider: SandboxProvider get() = sandboxManager.activeProvider
  val sandbox: Sandbox get() = sandboxManager.getSandbox(_state.value.sessionId)
  val workspace: Sandbox get() = sandbox
  val scratchpad = ScratchpadMemoryManager(workspaceDir)

  val capabilityManager = EnvironmentCapabilityManager { sandboxProvider }
  val artifactManager = ArtifactManager(workspaceDir)
  val deliverableTracker = DeliverableTracker(workspaceDir)
  val approvalManager = ApprovalManager()
  val registry = ToolRegistry()
  val executor = ToolExecutor(registry, artifactManager)
  val dispatcher = ToolDispatcher(registry, executor, approvalManager)

  var mcpManager: com.example.agent.mcp.McpManager? = null
  private var configuredProvider: LLMProvider = initialProvider
  private var activeProvider: LLMProvider = initialProvider
  private var activeJob: Job? = null
  private val scope = CoroutineScope(Dispatchers.Default)
  private val dedupPipeline = ReasoningDedupPipeline()

  init {
    workspaceDir.mkdirs()

    // 1. Process Tool (powered by E2B Cloud Firecracker MicroVM execution engine)
    val terminalTool = TerminalTool(
      workspaceDir = workspaceDir,
      artifactManager = artifactManager,
      sandboxProvider = sandboxProvider
    )
    registry.register(terminalTool)

    // Dedicated E2B Cloud Sandbox Tool (https://github.com/e2b-dev)
    val initialE2BProvider = sandboxProvider as E2BSandboxProvider
    registry.register(E2BCloudTool(initialE2BProvider, artifactManager, sessionIdProvider = { _state.value.sessionId }))
    registry.register(CheckToolsTool(workspaceDir, sandboxProvider = sandboxProvider, sessionIdProvider = { _state.value.sessionId }))
    registry.register(E2BWriteFileTool(initialE2BProvider, artifactManager, sessionIdProvider = { _state.value.sessionId }))
    registry.register(E2BReadFileTool(initialE2BProvider, artifactManager, sessionIdProvider = { _state.value.sessionId }))
    registry.register(E2BListDirectoryTool(initialE2BProvider, artifactManager, sessionIdProvider = { _state.value.sessionId }))

    // Code Interpreter & Diagnostics & Capabilities
    val codeInterpreterTool = CodeInterpreterTool(
      sandboxProvider = sandboxProvider,
      artifactManager = artifactManager,
      sessionIdProvider = { _state.value.sessionId },
      taskIdProvider = { _state.value.task?.id }
    )
    registry.register(codeInterpreterTool)

    val sandboxDiagnosticsTool = SandboxDiagnosticsTool(
      sandboxProvider = sandboxProvider,
      sessionIdProvider = { _state.value.sessionId },
      taskIdProvider = { _state.value.task?.id },
      capabilityManager = capabilityManager
    )
    registry.register(sandboxDiagnosticsTool)

    val capabilityTool = EnvironmentCapabilityTool(
      capabilityManager = capabilityManager,
      sandboxProvider = sandboxProvider,
      sessionIdProvider = { _state.value.sessionId }
    )
    registry.register(capabilityTool)

    // Cross-Environment Artifact Transfer & E2B Cloud MicroVM Filesystem Tools
    terminalTool.sessionIdProvider = { _state.value.sessionId }
    terminalTool.taskIdProvider = { _state.value.task?.id }
    registry.register(TransferArtifactToSandboxTool(workspaceDir, artifactManager, sandboxProvider, sessionIdProvider = { _state.value.sessionId }))
    registry.register(DownloadArtifactFromSandboxTool(workspaceDir, artifactManager, sandboxProvider, sessionIdProvider = { _state.value.sessionId }))
    registry.register(GetEnvironmentTopologyTool { EnvironmentTopology.createDefault() })

    // 3. Native Filesystem Tools (routed to E2B Cloud MicroVM)
    val listDirTool = ListDirectoryTool(
      workspaceDir = workspaceDir,
      artifactManager = artifactManager,
      sandboxProvider = sandboxProvider,
      sessionIdProvider = { _state.value.sessionId },
      sandboxProviderSupplier = { sandboxProvider }
    )
    registry.register(listDirTool)
    registry.register(ListFilesTool(listDirTool))

    val readFileTool = ReadFileTool(
      workspaceDir = workspaceDir,
      artifactManager = artifactManager,
      sandboxProvider = sandboxProvider,
      sessionIdProvider = { _state.value.sessionId },
      sandboxProviderSupplier = { sandboxProvider }
    )
    registry.register(readFileTool)

    val writeFileTool = WriteFileTool(
      workspaceDir = workspaceDir,
      artifactManager = artifactManager,
      sandboxProvider = sandboxProvider,
      sessionIdProvider = { _state.value.sessionId },
      sandboxProviderSupplier = { sandboxProvider }
    )
    registry.register(writeFileTool)
    registry.register(EditFileTool(workspaceDir, artifactManager, sandboxProvider = sandboxProvider, sessionIdProvider = { _state.value.sessionId }))
    registry.register(ApplyPatchTool(workspaceDir, artifactManager, sandboxProvider = sandboxProvider, sessionIdProvider = { _state.value.sessionId }))
    registry.register(DiffFilesTool(workspaceDir, artifactManager))
    registry.register(DeleteFileTool(workspaceDir, artifactManager))
    registry.register(MoveFileTool(workspaceDir, artifactManager))
    registry.register(CopyFileTool(workspaceDir, artifactManager))
    registry.register(CreateDirectoryTool(workspaceDir, artifactManager))
    registry.register(DeleteDirectoryTool(workspaceDir, artifactManager))
    registry.register(FileExistsTool(workspaceDir, artifactManager, sandboxProvider = sandboxProvider, sessionIdProvider = { _state.value.sessionId }))
    registry.register(FileMetadataTool(workspaceDir, artifactManager, sandboxProvider = sandboxProvider, sessionIdProvider = { _state.value.sessionId }))

    // 4. Package Tool (apt/pip installation inside the E2B Cloud MicroVM)
    registry.register(
      InstallPackageTool(
        artifactManager = artifactManager,
        sandboxProvider = sandboxProvider,
        sessionIdProvider = { _state.value.sessionId },
        capabilityManager = capabilityManager
      )
    )

    // 5. Web & Browser Subsystem Tools
    registry.register(WebSearchTool(isConfigured = webEnabled))
    registry.register(WebOpenTool(isConfigured = webEnabled))
    registry.register(WebExtractTool(isConfigured = webEnabled))
    registry.register(WebCrawlTool(isConfigured = webEnabled))
    registry.register(WebFetchTool(isConfigured = webEnabled))
    registry.register(BrowserAutomationTool(isConfigured = webEnabled))
    registry.register(DeepWebResearchTool(isConfigured = webEnabled))

    // 6. Document Intelligence Tools (imported documents: OCR, RAG search, native generation)
    val docs = documentService
    if (docs != null) {
      registry.register(DocumentListTool(docs))
      registry.register(DocumentSearchTool(docs))
      registry.register(DocumentProcessTool(workspaceDir, docs, artifactManager))
      registry.register(com.example.agent.docs.ReadImageTool(workspaceDir, docs.ocrRouter))
    }

    // 7. Native Microsoft Office & PDF Document Builders & Universal Replicator
    registry.register(BuildWordDocumentTool(workspaceDir, artifactManager))
    registry.register(BuildExcelSpreadsheetTool(workspaceDir, artifactManager))
    registry.register(BuildPdfDocumentTool(workspaceDir, artifactManager))
    registry.register(RecreateDocumentTool(workspaceDir, artifactManager, docs?.ocrRouter, docs))

    // Initial filesystem sync
    artifactManager.syncWorkspace()
    val initialHistory = scratchpad.loadTaskHistory()
    _state.value = _state.value.copy(taskHistory = initialHistory)
  }

  fun setSandboxConfig(config: com.alibaba.opensandbox.sandbox.config.ConnectionConfig) {
    sandboxManager.updateConfig(config)
    val active = sandboxManager.activeProvider
    (registry.getTool("terminal") as? TerminalTool)?.sandboxProvider = active
    (registry.getTool("code_interpreter") as? CodeInterpreterTool)?.sandboxProvider = active
    (registry.getTool("sandbox_diagnostics") as? SandboxDiagnosticsTool)?.sandboxProvider = active
    (registry.getTool("environment_capability") as? EnvironmentCapabilityTool)?.sandboxProvider = active
    (registry.getTool("check_tools") as? CheckToolsTool)?.sandboxProvider = active
    (registry.getTool("transfer_artifact_to_sandbox") as? TransferArtifactToSandboxTool)?.sandboxProvider = active
    (registry.getTool("download_artifact_from_sandbox") as? DownloadArtifactFromSandboxTool)?.sandboxProvider = active
    (registry.getTool("write_file") as? WriteFileTool)?.sandboxProvider = active
    (registry.getTool("read_file") as? ReadFileTool)?.sandboxProvider = active
    (registry.getTool("list_directory") as? ListDirectoryTool)?.sandboxProvider = active
    (registry.getTool("edit_file") as? EditFileTool)?.sandboxProvider = active
    (registry.getTool("apply_patch") as? ApplyPatchTool)?.sandboxProvider = active
    (registry.getTool("file_exists") as? FileExistsTool)?.sandboxProvider = active
    (registry.getTool("file_metadata") as? FileMetadataTool)?.sandboxProvider = active
    (registry.getTool("install_package") as? InstallPackageTool)?.sandboxProvider = active
    if (active is E2BSandboxProvider) {
      (registry.getTool("e2b_sandbox") as? E2BCloudTool)?.e2bProvider = active
      (registry.getTool("e2b_write_file") as? E2BWriteFileTool)?.e2bProvider = active
      (registry.getTool("e2b_read_file") as? E2BReadFileTool)?.e2bProvider = active
      (registry.getTool("e2b_list_directory") as? E2BListDirectoryTool)?.e2bProvider = active
    }
  }

  fun getProvider(): LLMProvider = activeProvider

  fun setProvider(provider: LLMProvider) {
    this.configuredProvider = provider
    this.activeProvider = provider
  }

  /**
   * Updates state atomically only if the current active task matches [task].
   * If a subsequent task has been submitted or execution cancelled, returns false.
   */
  private fun updateStateIfActive(task: Task, newState: AgentState): Boolean {
    synchronized(this) {
      if (_state.value.task?.id == task.id) {
        val currentStatus = _state.value.status
        val isCurrentTerminal = currentStatus == AgentStatus.CANCELLED ||
          currentStatus == AgentStatus.FAILED ||
          currentStatus == AgentStatus.COMPLETED ||
          currentStatus == AgentStatus.PARTIAL_COMPLETION

        if (isCurrentTerminal) {
          // If task is cancelled, never overwrite with any other state or delayed callback
          if (currentStatus == AgentStatus.CANCELLED) {
            return false
          }
          // If terminal, reject any transition back to non-terminal operational states
          val isNewTerminal = newState.status == AgentStatus.FAILED ||
            newState.status == AgentStatus.COMPLETED ||
            newState.status == AgentStatus.PARTIAL_COMPLETION ||
            newState.status == AgentStatus.CANCELLED
          if (!isNewTerminal) {
            return false
          }
        }

        _state.value = newState
        return true
      }
      return false
    }
  }

  /**
   * Idempotently appends or updates an execution step in the feed based on stable step ID.
   * Prevents duplicate entries caused by retries, streaming callbacks, or session restorations.
   */
  private fun addOrUpdateStep(
    feed: List<ExecutionStep>,
    step: ExecutionStep
  ): List<ExecutionStep> {
    val existingIdx = feed.indexOfFirst { it.id == step.id }
    return if (existingIdx >= 0) {
      val mutable = feed.toMutableList()
      mutable[existingIdx] = step.copy(stepNumber = feed[existingIdx].stepNumber)
      mutable
    } else {
      feed + step
    }
  }

  /**
   * Records task completion, failure, or cancellation in the persistent task history.
   * Completely preserves conversation, planning steps, execution feed, deliverables, and 5-stage records.
   */
  private fun recordTaskCompletion(
    task: Task,
    status: AgentStatus,
    conclusion: String?,
    artifacts: List<Artifact> = artifactManager.getArtifacts()
  ) {
    synchronized(this) {
      val existing = _state.value.taskHistory.firstOrNull { it.id == task.id }
      if (existing != null && existing.status == status && existing.conclusion == conclusion && existing.executionFeed.isNotEmpty()) {
        return
      }
      val record = TaskRecord(
        id = task.id,
        sessionId = task.sessionId,
        goal = task.goal,
        status = status,
        startedAt = task.createdAt,
        completedAt = System.currentTimeMillis(),
        expectedArtifact = task.expectedArtifact,
        artifactsProduced = artifacts.map { it.name },
        totalTurns = _state.value.currentStep,
        summary = _state.value.completionSummary ?: conclusion,
        error = _state.value.error,
        conclusion = conclusion,
        deliverables = artifacts.map { it.name },
        timestamp = System.currentTimeMillis(),
        conversation = _state.value.messages,
        planSteps = _state.value.plan,
        executionFeed = _state.value.executionFeed,
        fiveStageRecord = _state.value.fiveStageRecord
      )
      scratchpad.saveTaskRecord(record)
      val history = listOf(record) + _state.value.taskHistory.filter { it.id != task.id }
      _state.value = _state.value.copy(taskHistory = history)
    }
  }

  /**
   * Restores a previously executed task from history into the active UI state.
   * Enables the user to inspect the full conversation, planning stages, and execution feed.
   */
  fun restoreTaskFromHistory(record: TaskRecord) {
    synchronized(this) {
      val restoredTask = Task(
        id = record.id,
        sessionId = record.sessionId,
        goal = record.goal,
        status = record.status,
        createdAt = record.startedAt,
        expectedArtifact = record.expectedArtifact
      )
      _state.value = _state.value.copy(
        task = restoredTask,
        sessionId = record.sessionId,
        status = record.status,
        plan = record.planSteps,
        messages = record.conversation,
        executionFeed = record.executionFeed,
        fiveStageRecord = record.fiveStageRecord ?: FiveStageRecord(
          intent = record.goal,
          currentPlan = record.planSteps,
          conclusion = record.conclusion
        ),
        completionSummary = record.summary ?: record.conclusion,
        error = record.error,
        currentStep = record.totalTurns
      )
    }
  }

  /**
   * Clears the persistent task history.
   */
  fun clearTaskHistory() {
    scratchpad.clearTaskHistory()
    _state.value = _state.value.copy(taskHistory = emptyList())
  }

  /**
   * Starts an isolated new session for a task, wiping previous deliverables and memory.
   */
  fun startNewSession(newSessionId: String = UUID.randomUUID().toString()): String {
    activeJob?.cancel()
    sandboxManager.closeSession(_state.value.sessionId)
    dispatcher.reset()
    artifactManager.cleanAll()
    scratchpad.initSession(newSessionId)
    scratchpad.clearSessionMemory()
    dedupPipeline.clear()

    _state.value = AgentState(
      sessionId = newSessionId,
      task = null,
      status = AgentStatus.IDLE,
      currentAction = null,
      artifacts = emptyList(),
      taskHistory = _state.value.taskHistory,
      executionFeed = emptyList(),
      messages = emptyList(),
      plan = emptyList(),
      fiveStageRecord = FiveStageRecord(),
      canCancel = false
    )
    return newSessionId
  }

  /**
   * Dispatches a new task or continues an ongoing conversation in the agent session.
   */
  fun submitTask(
    intent: String,
    expectedArtifact: String? = null,
    resumeSessionId: String? = null,
    continueConversation: Boolean = false
  ) {
    if (intent.isBlank()) return

    activeJob?.cancel()
    activeProvider = configuredProvider

    val isContinuation = continueConversation ||
      (resumeSessionId != null && resumeSessionId == _state.value.sessionId) ||
      (_state.value.status == AgentStatus.COMPLETED && _state.value.messages.isNotEmpty())

    val sessionId = if (isContinuation) _state.value.sessionId else (resumeSessionId ?: UUID.randomUUID().toString())
    // Retain workspace deliverables across consecutive tasks so subsequent tasks
    // can process, transform, or build upon prior deliverables (e.g. sys_info.txt).
    // Clean only temporary/intermediate scratch files and refresh workspace state.
    artifactManager.cleanTemporaryFiles()
    val initialArtifacts = artifactManager.syncWorkspace()
    dispatcher.reset()
    if (!isContinuation) {
      scratchpad.initSession(sessionId)
    }

    val task = Task(
      id = UUID.randomUUID().toString(),
      sessionId = sessionId,
      goal = intent.trim(),
      status = AgentStatus.THINKING,
      expectedArtifact = expectedArtifact
    )

    val currentHistory = _state.value.taskHistory
    val taskRecord = TaskRecord(
      id = task.id,
      sessionId = sessionId,
      goal = task.goal,
      status = AgentStatus.THINKING,
      deliverables = initialArtifacts.map { it.name },
      timestamp = System.currentTimeMillis()
    )
    scratchpad.saveTaskRecord(taskRecord)
    val updatedHistory = listOf(taskRecord) + currentHistory.filter { it.id != task.id }

    val previousMessages = if (isContinuation) _state.value.messages else emptyList()
    val previousFeed = if (isContinuation) _state.value.executionFeed else emptyList()
    val startingStep = previousFeed.size

    val newStep = ExecutionStep(
      id = "step_intent_${task.id}",
      stepNumber = startingStep + 1,
      type = StepType.TASK_INTENT,
      title = if (isContinuation) "Follow-up / Task Modification" else "Task Received",
      content = task.goal
    )

    val inputFiles = (documentService?.documents?.value?.map { it.displayName } ?: emptyList())
      .plus(
        workspaceDir.walkTopDown()
          .filter { f ->
            f.isFile && !f.name.startsWith(".") &&
              !f.relativeTo(workspaceDir).path.startsWith("documents/") &&
              !f.relativeTo(workspaceDir).path.startsWith(".scratchpad/")
          }
          .map { it.relativeTo(workspaceDir).path.replace('\\', '/') }
          .toList()
      )
      .distinct()

    val userMessageContent = buildString {
      append(if (isContinuation) "User Follow-up Request: ${task.goal}" else "Task Objective: ${task.goal}")
      if (inputFiles.isNotEmpty()) {
        appendLine()
        appendLine()
        appendLine("[INPUT FILES & FOLDERS AVAILABLE IN E2B SANDBOX (/home/user & /home/user/workspace)]:")
        inputFiles.forEach { filePath ->
          val localF = File(workspaceDir, filePath)
          val sizeBytes = if (localF.exists()) localF.length() else 0L
          val ext = filePath.substringAfterLast('.', "").uppercase()
          appendLine("• /home/user/$filePath (workspace: '$filePath', $sizeBytes bytes, type: $ext)")
        }
        appendLine()
        appendLine("DIRECTIVE FOR INPUT FILES & DOCUMENT RECREATION:")
        appendLine("- All input files and uploaded folders are placed live in the E2B Cloud Sandbox at /home/user and /home/user/workspace.")
        appendLine("- If the task asks to REPLICATE, RECREATE, or CLONE a document, image, or screenshot (e.g. replicating a Word document, spreadsheet, or PDF from a photo or screenshot, or modifying its title, author, and date):")
        appendLine("  * Call 'recreate_document(source: \"<filename>\", target_format: \"docx\"|\"xlsx\"|\"pdf\", title: \"...\", author: \"...\", date: \"today\")'.")
        appendLine("  * It automatically executes high-precision OCR on pictures/screenshots, extracts sections/headings/tables, substitutes the title, author name, and date to the current date, and builds a verified deliverable.")
        appendLine("- You can also use 'build_word_document', 'build_excel_spreadsheet', 'build_pdf_document', 'read_image_text', or run Python automation directly in the sandbox.")
      }
    }

    val messageAttachments = mutableListOf<FileAttachment>()
    val inScopeIds = documentService?.scope?.value ?: emptySet()
    val allDocs = documentService?.documents?.value ?: emptyList()
    for (doc in allDocs) {
      if (inScopeIds.isEmpty() || doc.id in inScopeIds) {
        val f = documentService?.getOriginalFile(doc.id)
        if (f != null && f.exists() && f.length() <= 10 * 1024 * 1024) {
          val mime = when (doc.type) {
            com.example.agent.docs.DocumentType.IMAGE -> {
              when (f.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
              }
            }
            com.example.agent.docs.DocumentType.PDF -> "application/pdf"
            com.example.agent.docs.DocumentType.CSV -> "text/csv"
            com.example.agent.docs.DocumentType.TXT -> "text/plain"
            com.example.agent.docs.DocumentType.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            com.example.agent.docs.DocumentType.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> "text/plain"
          }
          messageAttachments.add(FileAttachment(name = doc.displayName, mimeType = mime, file = f))
        }
      }
    }

    val newMsg = AgentMessage(
      role = MessageRole.USER,
      content = userMessageContent,
      attachments = messageAttachments
    )

    deliverableTracker.initializeForTask(task)
    val initialDeliverableReport = deliverableTracker.scanAndVerify(artifactManager)

    _state.value = AgentState(
      sessionId = sessionId,
      task = task,
      status = AgentStatus.THINKING,
      currentAction = if (isContinuation) "Processing follow-up: '${task.goal}'" else "Understanding task objective: '${task.goal}'",
      artifacts = initialArtifacts,
      plan = if (isContinuation) _state.value.plan else emptyList(),
      fiveStageRecord = FiveStageRecord(
        intent = task.goal,
        currentPlan = if (isContinuation) _state.value.fiveStageRecord.currentPlan else emptyList(),
        actions = if (isContinuation) _state.value.fiveStageRecord.actions else emptyList(),
        observations = if (isContinuation) _state.value.fiveStageRecord.observations else emptyList()
      ),
      taskHistory = updatedHistory,
      executionFeed = addOrUpdateStep(previousFeed, newStep),
      messages = previousMessages + newMsg,
      error = null,
      verification = null,
      completionSummary = null,
      canCancel = true,
      trackedDeliverables = initialDeliverableReport.deliverables,
      deliverableReport = initialDeliverableReport
    )

    activeJob = scope.launch {
      try {
        run(task)
      } catch (ce: CancellationException) {
        // Normal cancellation
      } catch (t: Throwable) {
        val err = "Fatal execution fault: ${t.message ?: t.javaClass.simpleName}"
        _state.value = _state.value.copy(
          status = AgentStatus.FAILED,
          error = err,
          currentAction = err,
          canCancel = false
        )
      }
    }
  }

  /**
   * Central Autonomous Agent Execution Loop.
   */
  private suspend fun run(task: Task) {
    var stepCounter = _state.value.executionFeed.size

    val existingCheckpoint = scratchpad.loadCheckpoint()
    val isResuming = existingCheckpoint != null &&
      existingCheckpoint.taskGoal.equals(task.goal.trim(), ignoreCase = true) &&
      existingCheckpoint.status in RESUMABLE_CHECKPOINT_STATUSES

    val initialPlan = if (isResuming) existingCheckpoint!!.planItems else scratchpad.loadPlan()
    val planner = TaskPlanner(task.goal)
    if (initialPlan.isNotEmpty()) {
      planner.acceptModelPlan(initialPlan.joinToString("\n"))
      if (isResuming) {
        planner.restoreProgress(
          completed = existingCheckpoint?.completedSections.orEmpty(),
          pending = existingCheckpoint?.pendingSections.orEmpty()
        )
      }
    }

    val baseExecutionFeed = if (_state.value.executionFeed.isNotEmpty()) {
      _state.value.executionFeed
    } else {
      listOf(
        ExecutionStep(
          id = "step_intent_${task.id}",
          stepNumber = 1,
          type = StepType.TASK_INTENT,
          title = if (isResuming) "Task Resumed from Checkpoint" else "Task Received",
          content = if (isResuming) "${task.goal} (Resuming from Turn ${existingCheckpoint?.currentTurn ?: 0})" else task.goal
        )
      )
    }

    val baseMessages = if (_state.value.messages.isNotEmpty()) {
      _state.value.messages
    } else {
      listOf(
        AgentMessage(
          role = MessageRole.USER,
          content = "Task Objective: ${task.goal}"
        )
      )
    }

    var currentState = _state.value.copy(
      sessionId = task.sessionId,
      task = task,
      status = AgentStatus.THINKING,
      currentAction = if (isResuming) "Resuming from checkpoint turn ${existingCheckpoint?.currentTurn ?: 0}..." else "Understanding task objective: '${task.goal}'",
      currentStep = if (isResuming) existingCheckpoint?.currentTurn ?: 0 else 0,
      plan = initialPlan,
      artifacts = artifactManager.getArtifacts(),
      fiveStageRecord = FiveStageRecord(
        intent = task.goal,
        currentPlan = initialPlan,
        actions = _state.value.fiveStageRecord.actions,
        observations = _state.value.fiveStageRecord.observations
      ),
      executionFeed = baseExecutionFeed,
      messages = baseMessages,
      error = null,
      verification = null,
      completionSummary = null,
      canCancel = true
    )
    if (!updateStateIfActive(task, currentState)) return

    var turnCounter = if (isResuming) existingCheckpoint?.currentTurn ?: 0 else 0
    var providerDropRetries = 0
    var consecutiveVerificationFailures = 0
    // Loop guards: identical consecutive actions, empty decisions, fallback recovery probing
    var lastActionSignature: String? = null
    var consecutiveIdenticalActions = 0
    var consecutiveEmptyDecisions = 0
    var lastFallbackProbeTurn = Int.MIN_VALUE
    val requiredOutputs = extractRequiredOutputs(task.goal)
    var expectedSandboxId = (sandboxProvider as? E2BSandboxProvider)?.getActiveSession(task.sessionId)?.sandboxId

    // Proactively pre-sync imported documents and workspace input files into the microVM at /home/user/<displayName>
    val activeSandbox = sandboxProvider
    if (activeSandbox != null && activeSandbox.isRemote) {
      try {
        val syncedFiles = mutableListOf<String>()
        val allDocs = documentService?.documents?.value ?: emptyList()
        for (doc in allDocs) {
          val localDocFile = documentService?.resolveDocumentFile(doc.id)
            ?: File(workspaceDir, doc.displayName).takeIf { it.exists() }
          if (localDocFile != null && localDocFile.isFile) {
            val ok = activeSandbox.pushFileToRemote(task.sessionId, doc.displayName, localDocFile)
            if (ok) syncedFiles.add(doc.displayName)
          }
        }
        val allWorkspaceFiles = workspaceDir.walkTopDown()
          .filter { f ->
            f.isFile && !f.name.startsWith(".") &&
              !f.relativeTo(workspaceDir).path.startsWith("documents/") &&
              !f.relativeTo(workspaceDir).path.startsWith(".scratchpad/")
          }
          .toList()
        for (f in allWorkspaceFiles) {
          val relPath = f.relativeTo(workspaceDir).path.replace('\\', '/')
          if (!syncedFiles.contains(relPath)) {
            val ok = activeSandbox.pushFileToRemote(task.sessionId, relPath, f)
            if (ok) syncedFiles.add(relPath)
          }
        }
        if (syncedFiles.isNotEmpty()) {
          val syncStep = ExecutionStep(
            id = "step_sync_files_${task.id}",
            stepNumber = ++stepCounter,
            type = StepType.TOOL_EXECUTION,
            title = "Placed Input Files in E2B Sandbox",
            content = "Successfully uploaded ${syncedFiles.size} input file(s) into E2B Cloud Sandbox (/home/user):\n" +
              syncedFiles.joinToString("\n") { "• /home/user/$it" }
          )
          currentState = currentState.copy(
            executionFeed = addOrUpdateStep(currentState.executionFeed, syncStep)
          )
          updateStateIfActive(task, currentState)
        }
      } catch (_: Exception) {
        // Best-effort pre-sync
      }
    }

    try {
      while (turnCounter < maxSteps) {
        turnCounter++
        currentCoroutineContext().ensureActive()

        // Continuous Deliverable Verification & Feedback
        val deliverableReport = deliverableTracker.scanAndVerify(artifactManager)
        currentState = currentState.copy(
          trackedDeliverables = deliverableReport.deliverables,
          deliverableReport = deliverableReport
        )

        // Periodic autonomous progress check (only for long runs with pending deliverables, without breaking execution)
        if (turnCounter > 35 && turnCounter % 15 == 0 && deliverableReport.totalRequired > 0 && !deliverableReport.allSatisfied) {
          val progressMsg = "Autonomous Progress (Turn $turnCounter): ${deliverableReport.verifiedCount}/${deliverableReport.totalRequired} deliverables verified. Continuing workflow to complete: ${deliverableReport.pendingOrInvalidDetails}."
          currentState = currentState.copy(
            status = AgentStatus.DELIVERABLE_PENDING,
            currentAction = "Synthesizing deliverables: ${deliverableReport.pendingOrInvalidDetails}",
            messages = currentState.messages + AgentMessage(role = MessageRole.USER, content = progressMsg)
          )
          if (!updateStateIfActive(task, currentState)) return
        }

        // 1. PLANNING & REASONING PHASE
        currentState = currentState.copy(
          status = if (currentState.status == AgentStatus.DELIVERABLE_PENDING) AgentStatus.DELIVERABLE_PENDING else AgentStatus.PLANNING,
          currentStep = turnCounter,
          currentAction = "Consulting LLM for next action (Turn $turnCounter)..."
        )
        if (!updateStateIfActive(task, currentState)) return

        val systemPrompt = buildSystemContext(task, currentState, planner, deliverableReport, turnCounter, maxSteps)

        // While the built-in fallback engine is active, periodically probe the configured
        // provider again and automatically restore it once it responds. This prevents a
        // transient network blip from downgrading the remainder of a long task.
        val isFallbackActive = activeProvider !== configuredProvider
        val decision = if (isFallbackActive && turnCounter - lastFallbackProbeTurn >= FALLBACK_PROBE_INTERVAL_TURNS) {
          lastFallbackProbeTurn = turnCounter
          val probe = configuredProvider.decideNextAction(
            systemPrompt = systemPrompt,
            taskIntent = task.goal,
            messages = currentState.messages,
            tools = registry.getAvailableTools()
          )
          if (probe !is LLMDecision.ProviderError) {
            activeProvider = configuredProvider
            providerDropRetries = 0
            val restoredStep = ExecutionStep(
              id = "step_provider_restored_${task.id}",
              stepNumber = ++stepCounter,
              type = StepType.OBSERVATION,
              title = "Provider Connection Restored",
              content = "Primary LLM endpoint recovered. Resuming configured provider '${configuredProvider.providerName}'."
            )
            currentState = currentState.copy(
              currentAction = "Primary LLM endpoint recovered. Resuming configured provider...",
              executionFeed = addOrUpdateStep(currentState.executionFeed, restoredStep)
            )
            if (!updateStateIfActive(task, currentState)) return
            probe
          } else {
            activeProvider.decideNextAction(
              systemPrompt = systemPrompt,
              taskIntent = task.goal,
              messages = currentState.messages,
              tools = registry.getAvailableTools()
            )
          }
        } else {
          activeProvider.decideNextAction(
            systemPrompt = systemPrompt,
            taskIntent = task.goal,
            messages = currentState.messages,
            tools = registry.getAvailableTools()
          )
        }

        currentCoroutineContext().ensureActive()

        when (decision) {
          is LLMDecision.ExecuteTool -> {
            // A successful decision resets consecutive provider-drop tracking.
            providerDropRetries = 0
            consecutiveEmptyDecisions = 0
            val toolCalls = decision.toolCalls

            if (toolCalls.isEmpty()) {
              val rawThought = decision.thought.orEmpty()
              val thoughtLower = rawThought.lowercase()
              val seemsComplete = thoughtLower.contains("complete") ||
                thoughtLower.contains("finished") ||
                thoughtLower.contains("done") ||
                thoughtLower.contains("conclude")

              if (seemsComplete) {
                // If thought explicitly conveys completion, verify objective
                try {
                  sandboxManager.activeProvider.syncArtifacts(task.sessionId, workspaceDir, task.id)
                } catch (_: Exception) {}
                val freshArtifacts = artifactManager.syncWorkspace(task.id)
                val verificationResult = verifyObjective(task, currentState.copy(artifacts = freshArtifacts))
                if (verificationResult.isSatisfied) {
                  val conclusionText = rawThought.ifBlank { "Task completed successfully and verified." }
                  val vStep = ExecutionStep(
                    id = "step_verify_t${turnCounter}_${task.id}",
                    stepNumber = ++stepCounter,
                    type = StepType.VERIFICATION,
                    title = "Objective Verified",
                    content = verificationResult.details
                  )
                  val cStep = ExecutionStep(
                    id = "step_conclusion_${task.id}",
                    stepNumber = ++stepCounter,
                    type = StepType.CONCLUSION,
                    title = "Completed",
                    content = conclusionText
                  )
                  val completedFeed = addOrUpdateStep(addOrUpdateStep(currentState.executionFeed, vStep), cStep)
                  artifactManager.cleanTemporaryFiles()
                  val finalArtifacts = artifactManager.syncWorkspace(task.id)
                  scratchpad.saveCheckpoint(
                    AgentCheckpoint(
                      taskGoal = task.goal,
                      currentTurn = turnCounter,
                      completedSections = planner.completedDescriptions(),
                      pendingSections = planner.pendingDescriptions(),
                      planItems = currentState.plan,
                      artifactPaths = finalArtifacts.map { it.path },
                      totalFindingsCount = scratchpad.loadAllFindings().size,
                      status = "COMPLETED"
                    )
                  )
                  recordTaskCompletion(task, AgentStatus.COMPLETED, conclusionText, finalArtifacts)
                  val finalMessages = if (currentState.messages.any { it.role == MessageRole.ASSISTANT && it.content == conclusionText }) {
                    currentState.messages
                  } else {
                    currentState.messages + AgentMessage(role = MessageRole.ASSISTANT, content = conclusionText)
                  }
                  currentState = currentState.copy(
                    status = AgentStatus.COMPLETED,
                    currentAction = "Task completed and verified.",
                    completionSummary = conclusionText,
                    verification = verificationResult,
                    artifacts = finalArtifacts,
                    executionFeed = completedFeed,
                    messages = finalMessages,
                    fiveStageRecord = currentState.fiveStageRecord.copy(conclusion = conclusionText),
                    canCancel = false
                  )
                  updateStateIfActive(task, currentState)
                  return
                }
              }

              consecutiveEmptyDecisions++
              if (consecutiveEmptyDecisions >= MAX_CONSECUTIVE_EMPTY_DECISIONS) {
                val haltMsg = "Provider produced no verified, actionable step for $consecutiveEmptyDecisions consecutive turns " +
                  "(repeated responses without tool calls or with unverified completion claims). Halting to avoid an unproductive loop."
                scratchpad.saveCheckpoint(
                  AgentCheckpoint(
                    taskGoal = task.goal,
                    currentTurn = turnCounter,
                    completedSections = planner.completedDescriptions(),
                    pendingSections = planner.pendingDescriptions(),
                    planItems = currentState.plan,
                    artifactPaths = artifactManager.getArtifacts().map { it.path },
                    totalFindingsCount = scratchpad.loadAllFindings().size,
                    status = "FAILED"
                  )
                )
                recordTaskCompletion(task, AgentStatus.FAILED, haltMsg, artifactManager.getArtifacts())
                val haltStep = ExecutionStep(
                  id = "step_empty_decisions_${task.id}",
                  stepNumber = ++stepCounter,
                  type = StepType.ERROR,
                  title = "Unproductive Provider Loop",
                  content = haltMsg
                )
                currentState = currentState.copy(
                  status = AgentStatus.FAILED,
                  currentAction = haltMsg,
                  error = haltMsg,
                  executionFeed = addOrUpdateStep(currentState.executionFeed, haltStep),
                  canCancel = false
                )
                updateStateIfActive(task, currentState)
                return
              }

              val guidance = "System Notice: The previous turn did not specify any tool calls. If your objective is achieved, conclude in plain text. Otherwise, invoke the next required tool call to make progress."
              val guidanceMsg = AgentMessage(role = MessageRole.USER, content = guidance)
              val guidanceMessages = if (currentState.messages.lastOrNull()?.content == guidance) {
                currentState.messages
              } else {
                currentState.messages + guidanceMsg
              }
              currentState = currentState.copy(
                status = AgentStatus.PLANNING,
                currentAction = "Requesting next actionable step from model...",
                messages = guidanceMessages
              )
              if (!updateStateIfActive(task, currentState)) return
              continue
            }

            // Fix stale PLANNING status: immediately transition out of PLANNING into EXECUTING_TOOL
            currentState = currentState.copy(
              status = AgentStatus.EXECUTING_TOOL,
              currentAction = "Preparing to execute ${toolCalls.size} tool call(s)..."
            )
            if (!updateStateIfActive(task, currentState)) return

            // Update plan from model if provided
            val updatedPlan = if (decision.plan != null) {
              planner.acceptModelPlan(decision.plan)
              val parsedLines = planner.planDescriptions()
              scratchpad.savePlan(parsedLines)
              parsedLines
            } else currentState.plan

            val rawThought = decision.thought
            val latestObs = currentState.observations.lastOrNull()?.summary
              ?: currentState.messages.lastOrNull { it.role == MessageRole.TOOL }?.content
            val cleanedThought = if (!rawThought.isNullOrBlank()) {
              dedupPipeline.process(rawThought, turnCounter, observationFingerprint = latestObs)
            } else null

            val thinkingStepId = "step_thinking_t${turnCounter}_${task.id}"
            val feedWithThought = if (!cleanedThought.isNullOrBlank() && !cleanedThought.startsWith("[REASONING_DEDUP_HIT")) {
              addOrUpdateStep(
                currentState.executionFeed,
                ExecutionStep(
                  id = thinkingStepId,
                  stepNumber = ++stepCounter,
                  type = StepType.THINKING,
                  title = "Reasoning",
                  content = cleanedThought
                )
              )
            } else currentState.executionFeed

            currentState = currentState.copy(
              plan = updatedPlan,
              executionFeed = feedWithThought,
              fiveStageRecord = currentState.fiveStageRecord.copy(currentPlan = updatedPlan)
            )

            // Validate explicit cross-environment intent declaration
            val crossEnvPlanError = planner.validateCrossEnvPlan(
              planText = decision.plan ?: decision.thought,
              toolCalls = toolCalls,
              workspaceDir = workspaceDir,
              isRemoteSandbox = sandboxProvider.isRemote
            )
            if (crossEnvPlanError != null) {
              val errorMsg = AgentMessage(
                role = MessageRole.USER,
                content = "$crossEnvPlanError\n\nPlease re-submit your plan with explicit plan.cross_env declaration: { action: 'transfer' | 'stay_in' }."
              )
              currentState = currentState.copy(
                messages = currentState.messages + errorMsg,
                currentAction = "Waiting for explicit cross-env intent declaration"
              )
              if (!updateStateIfActive(task, currentState)) return
              continue
            }

            // Ensure immutable globally unique tool-call IDs: call_<taskId>_<iteration>_<randomId>
            val validatedToolCalls = toolCalls.mapIndexed { idx, call ->
              val normalizedCallId = if (call.callId.startsWith("call_")) {
                call.callId
              } else {
                ToolCall.generateCallId(task.id, turnCounter * 10 + idx)
              }
              call.copy(callId = normalizedCallId)
            }

            // Append assistant messages for the batch, avoiding duplicate toolCallIds
            val existingToolCallIds = currentState.messages.mapNotNull { it.toolCallId }.toSet()
            val assistantToolMessages = validatedToolCalls.mapIndexed { index, call ->
              AgentMessage(
                role = MessageRole.ASSISTANT,
                content = if (index == 0) {
                  decision.thought ?: "Executing ${validatedToolCalls.size} requested tool call(s)."
                } else "",
                toolCallId = call.callId,
                toolName = call.toolName,
                toolArgs = call.arguments
              )
            }.filter { it.toolCallId == null || !existingToolCallIds.contains(it.toolCallId) }

            if (assistantToolMessages.isNotEmpty()) {
              currentState = currentState.copy(
                messages = currentState.messages + assistantToolMessages
              )
              if (!updateStateIfActive(task, currentState)) return
            }

            delay(100)

            // 2. DETERMINISTIC TOOL EXECUTION PHASE (With Multitasking & Concurrent Dispatch)
            val isMultitasking = canExecuteConcurrently(validatedToolCalls)
            if (isMultitasking) {
              val runningStepIds = validatedToolCalls.map { "step_tool_${it.callId}" }
              val currentFeed = currentState.executionFeed.toMutableList()

              validatedToolCalls.forEachIndexed { idx, toolCall ->
                currentFeed.add(
                  ExecutionStep(
                    id = runningStepIds[idx],
                    stepNumber = ++stepCounter,
                    type = StepType.TOOL_EXECUTION,
                    title = "Executing [Concurrent]: ${toolCall.toolName}",
                    content = formatArguments(toolCall.arguments),
                    toolName = toolCall.toolName,
                    toolCallId = toolCall.callId,
                    toolStatus = ToolStatus.RUNNING.name,
                    isCompleted = false
                  )
                )
              }

              currentState = currentState.copy(
                status = AgentStatus.EXECUTING_TOOL,
                currentAction = "Executing ${validatedToolCalls.size} operations concurrently (Multitasking)...",
                executionFeed = currentFeed,
                isMultitasking = true,
                activeConcurrentTasks = validatedToolCalls.size
              )
              if (!updateStateIfActive(task, currentState)) return

              planner.markMultitaskingStarted(validatedToolCalls.map { it.toolName })

              val toolContext = ToolExecutionContext(
                environmentType = ExecutionEnvironmentType.E2B_CLOUD,
                backendName = sandboxProvider.name,
                sessionId = task.sessionId,
                taskId = task.id,
                sandboxId = (sandboxProvider as? E2BSandboxProvider)?.getActiveSession(task.sessionId)?.sandboxId,
                workspaceDir = workspaceDir,
                workingDirectory = (registry.getTool("terminal") as? TerminalTool)?.getWorkingDirectory(task.sessionId) ?: "/home/user",
                capabilities = setOf("shell", "process_execution")
              )

              val asyncDispatches = validatedToolCalls.map { call ->
                scope.async(Dispatchers.IO) {
                  val result = dispatcher.dispatch(call, task.id, toolContext)
                  Pair(call, result)
                }
              }
              val results = asyncDispatches.awaitAll()

              var updatedFeed = currentState.executionFeed.toMutableList()
              var updatedMessages = currentState.messages
              var updatedToolExecutions = currentState.toolExecutions
              var updatedObservations = currentState.observations
              var updatedActions = currentState.fiveStageRecord.actions
              var updatedObsStrings = currentState.fiveStageRecord.observations

              results.forEachIndexed { idx, (call, pair) ->
                val (toolResult, observation) = pair
                if (toolResult.status == ToolStatus.SUCCEEDED && toolResult.output != null) {
                  scratchpad.autoIngestFromToolOutput(call.toolName, call.arguments, toolResult.output)
                }

                updatedFeed = updatedFeed.map { item ->
                  if (item.id == runningStepIds[idx]) {
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

                val obsStepId = "step_obs_${call.callId}"
                updatedFeed = addOrUpdateStep(
                  updatedFeed,
                  ExecutionStep(
                    id = obsStepId,
                    stepNumber = ++stepCounter,
                    type = StepType.OBSERVATION,
                    title = if (observation.isSuccess) "Observation (${toolResult.status.name})" else "Observation (${toolResult.errorType?.name ?: "FAILED"})",
                    content = observation.summary,
                    stdout = observation.stdout,
                    stderr = observation.stderr
                  )
                ).toMutableList()

                updatedActions = updatedActions + "Dispatched [Concurrent] '${call.toolName}' with ${formatArguments(call.arguments)}"
                updatedObsStrings = updatedObsStrings + observation.summary
                planner.recordToolResult(toolResult, observation.summary)

                val reflection = planner.reflect(
                  currentStep = turnCounter,
                  toolName = call.toolName,
                  toolArgs = call.arguments,
                  result = toolResult,
                  observationSummary = observation.summary,
                  boundTodoId = call.arguments["todo_id"]?.toString()
                )
                if (reflection.transcriptWarning != null) {
                  val refStepId = "step_reflect_${call.callId}"
                  updatedFeed = addOrUpdateStep(
                    updatedFeed,
                    ExecutionStep(
                      id = refStepId,
                      stepNumber = ++stepCounter,
                      type = StepType.OBSERVATION,
                      title = "Planner Reflection",
                      content = reflection.transcriptWarning
                    )
                  ).toMutableList()
                }

                val hasToolMsg = updatedMessages.any { it.role == MessageRole.TOOL && it.toolCallId == call.callId }
                updatedMessages = if (hasToolMsg) {
                  updatedMessages.map {
                    if (it.role == MessageRole.TOOL && it.toolCallId == call.callId) {
                      it.copy(content = observation.rawOutput)
                    } else it
                  }
                } else {
                  updatedMessages + AgentMessage(
                    role = MessageRole.TOOL,
                    content = observation.rawOutput,
                    toolCallId = call.callId,
                    toolName = call.toolName
                  )
                }

                updatedToolExecutions = updatedToolExecutions + toolResult
                updatedObservations = updatedObservations + observation
              }

              val updatedArtifacts = artifactManager.syncWorkspace(task.id)

              // Progress tracking for anti-stagnation
              val hasNewArtifact = updatedArtifacts.size > currentState.artifacts.size ||
                updatedArtifacts.any { art -> currentState.artifacts.none { it.path == art.path && it.size == art.size } }
              val isStateChanging = results.any { (call, pair) ->
                registry.resolveToolName(call.toolName) in STATE_CHANGING_TOOLS && pair.first.isSuccess
              }
              val isInformative = results.any { (call, pair) ->
                registry.resolveToolName(call.toolName) in INFORMATIVE_TOOLS && pair.first.isSuccess
              }
              if (hasNewArtifact || isStateChanging || isInformative) {
                planner.recordProgress()
                consecutiveVerificationFailures = 0
              }

              scratchpad.saveCheckpoint(
                AgentCheckpoint(
                  taskGoal = task.goal,
                  currentTurn = turnCounter,
                  completedSections = planner.completedDescriptions(),
                  pendingSections = planner.pendingDescriptions(),
                  planItems = planner.planDescriptions(),
                  artifactPaths = updatedArtifacts.map { it.path },
                  totalFindingsCount = scratchpad.loadAllFindings().size,
                  status = "IN_PROGRESS"
                )
              )

              currentState = currentState.copy(
                status = AgentStatus.OBSERVING,
                currentAction = "Completed concurrent execution of ${validatedToolCalls.size} operations.",
                messages = updatedMessages,
                toolExecutions = updatedToolExecutions,
                observations = updatedObservations,
                artifacts = updatedArtifacts,
                executionFeed = updatedFeed,
                plan = planner.planDescriptions(),
                isMultitasking = false,
                activeConcurrentTasks = 0,
                fiveStageRecord = currentState.fiveStageRecord.copy(
                  currentPlan = planner.planDescriptions(),
                  actions = updatedActions,
                  observations = updatedObsStrings
                )
              )
              if (!updateStateIfActive(task, currentState)) return
            } else {
              // Standard sequential execution loop
              for (toolCall in validatedToolCalls) {
                currentCoroutineContext().ensureActive()

                // Pre-execution sandbox identity verification
                val e2b = sandboxProvider as? E2BSandboxProvider
                if (e2b != null && e2b.isE2B) {
                  val activeSess = e2b.getActiveSession(task.sessionId)
                  val currentSbxId = activeSess?.sandboxId
                  if (expectedSandboxId != null && (currentSbxId == null || currentSbxId != expectedSandboxId)) {
                    val resetStep = ExecutionStep(
                      stepNumber = ++stepCounter,
                      type = StepType.SANDBOX_RESET,
                      title = "SANDBOX_RESET Detected",
                      content = "Sandbox identity mismatch detected: expected '$expectedSandboxId', found '${currentSbxId ?: "null"}'. Halting for re-provisioning and state sync to prevent state loss."
                    )
                    currentState = currentState.copy(
                      status = AgentStatus.SANDBOX_RESET,
                      currentAction = "Recovering from sandbox swap; synchronizing workspace...",
                      executionFeed = currentState.executionFeed + resetStep
                    )
                    updateStateIfActive(task, currentState)

                    val fresh = e2b.getOrCreateE2BSession(task.sessionId, task.id)
                    expectedSandboxId = fresh.sandboxId
                    try {
                      e2b.syncArtifacts(task.sessionId, workspaceDir, task.id)
                    } catch (_: Exception) {}
                  } else if (expectedSandboxId == null && currentSbxId != null) {
                    expectedSandboxId = currentSbxId
                  }
                }

                // Deterministic loop guard: the 3rd+ consecutive identical action
                // (same resolved tool + identical arguments) is skipped, and the model
                // is told to pick a different path instead of looping.
                val signature = actionSignature(toolCall)
                if (signature == lastActionSignature) {
                  consecutiveIdenticalActions++
                } else {
                  lastActionSignature = signature
                  consecutiveIdenticalActions = 1
                }
                if (consecutiveIdenticalActions >= MAX_CONSECUTIVE_IDENTICAL_ACTIONS) {
                  val loopMsg = "Loop guard: '${toolCall.toolName}' with identical arguments was repeated " +
                    "$consecutiveIdenticalActions times consecutively and was skipped. " +
                    "Choose a different action, different arguments, or conclude the task."
                  planner.requestReplan(loopMsg)

                  // If the model keeps repeating the same skipped action even after the
                  // replan request, halt honestly instead of burning the whole turn budget.
                  if (consecutiveIdenticalActions >= MAX_CONSECUTIVE_IDENTICAL_ACTIONS * 2) {
                    val haltMsg = "Unproductive loop: '${toolCall.toolName}' with identical arguments was repeated " +
                      "$consecutiveIdenticalActions times consecutively despite repeated skip notices. Halting."
                    scratchpad.saveCheckpoint(
                      AgentCheckpoint(
                        taskGoal = task.goal,
                        currentTurn = turnCounter,
                        completedSections = planner.completedDescriptions(),
                        pendingSections = planner.pendingDescriptions(),
                        planItems = currentState.plan,
                        artifactPaths = artifactManager.getArtifacts().map { it.path },
                        totalFindingsCount = scratchpad.loadAllFindings().size,
                        status = "FAILED"
                      )
                    )
                    recordTaskCompletion(task, AgentStatus.FAILED, haltMsg, artifactManager.getArtifacts())
                    val haltStep = ExecutionStep(
                      id = "step_loopguard_halt_${task.id}",
                      stepNumber = ++stepCounter,
                      type = StepType.ERROR,
                      title = "Unproductive Loop Detected",
                      content = haltMsg
                    )
                    currentState = currentState.copy(
                      status = AgentStatus.FAILED,
                      currentAction = haltMsg,
                      error = haltMsg,
                      executionFeed = addOrUpdateStep(currentState.executionFeed, haltStep),
                      canCancel = false
                    )
                    updateStateIfActive(task, currentState)
                    return
                  }

                  val loopStep = ExecutionStep(
                    id = "step_loopguard_${toolCall.callId}",
                    stepNumber = ++stepCounter,
                    type = StepType.OBSERVATION,
                    title = "Loop Guard Triggered",
                    content = loopMsg
                  )
                  val syntheticResult = ToolResult(
                    callId = toolCall.callId,
                    toolName = toolCall.toolName,
                    status = ToolStatus.FAILED,
                    arguments = toolCall.arguments,
                    output = null,
                    error = loopMsg,
                    errorType = ToolErrorCode.INVALID_ARGUMENTS,
                    duration = 0L
                  )
                  val syntheticObservation = Observation(
                    callId = toolCall.callId,
                    toolName = toolCall.toolName,
                    status = ToolStatus.FAILED,
                    isSuccess = false,
                    inputsSummary = formatArguments(toolCall.arguments),
                    result = null,
                    error = loopMsg,
                    errorType = ToolErrorCode.INVALID_ARGUMENTS,
                    summary = loopMsg,
                    rawOutput = loopMsg,
                    executionContext = null
                  )
                  val loopMessages = if (currentState.messages.lastOrNull()?.content == loopMsg) {
                    currentState.messages
                  } else {
                    currentState.messages + AgentMessage(role = MessageRole.USER, content = loopMsg)
                  }
                  currentState = currentState.copy(
                    status = AgentStatus.OBSERVING,
                    currentAction = "Loop guard: skipped repeated identical action '${toolCall.toolName}'.",
                    messages = loopMessages,
                    toolExecutions = currentState.toolExecutions + syntheticResult,
                    observations = currentState.observations + syntheticObservation,
                    executionFeed = addOrUpdateStep(currentState.executionFeed, loopStep)
                  )
                  if (!updateStateIfActive(task, currentState)) return
                  break
                }

                val runningStepId = "step_tool_${toolCall.callId}"
                val runningStep = ExecutionStep(
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

                currentState = currentState.copy(
                  status = AgentStatus.EXECUTING_TOOL,
                  currentAction = "Executing '${toolCall.toolName}'...",
                  executionFeed = addOrUpdateStep(currentState.executionFeed, runningStep)
                )
                if (!updateStateIfActive(task, currentState)) return

                delay(80)
                planner.markToolStarted(toolCall.toolName)

                val toolContext = ToolExecutionContext(
                  environmentType = ExecutionEnvironmentType.E2B_CLOUD,
                  backendName = sandboxProvider.name,
                  sessionId = task.sessionId,
                  taskId = task.id,
                  sandboxId = (sandboxProvider as? E2BSandboxProvider)?.getActiveSession(task.sessionId)?.sandboxId,
                  workspaceDir = workspaceDir,
                  workingDirectory = (registry.getTool("terminal") as? TerminalTool)?.getWorkingDirectory(task.sessionId) ?: "/home/user",
                  capabilities = setOf("shell", "process_execution")
                )

                val (toolResult, observation) = dispatcher.dispatch(toolCall, task.id, toolContext)

                if (toolResult.status == ToolStatus.SUCCEEDED && toolResult.output != null) {
                  scratchpad.autoIngestFromToolOutput(toolCall.toolName, toolCall.arguments, toolResult.output)
                }

                delay(80)

                // Update execution feed step with authoritative execution outcomes
                val feedWithResult = currentState.executionFeed.map { item ->
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
                }

                val obsStepId = "step_obs_${toolCall.callId}"
                val obsStep = ExecutionStep(
                  id = obsStepId,
                  stepNumber = ++stepCounter,
                  type = StepType.OBSERVATION,
                  title = if (observation.isSuccess) "Observation (${toolResult.status.name})" else "Observation (${toolResult.errorType?.name ?: "FAILED"})",
                  content = observation.summary,
                  stdout = observation.stdout,
                  stderr = observation.stderr
                )
                val updatedFeed = addOrUpdateStep(feedWithResult, obsStep)

                val updatedActions = currentState.fiveStageRecord.actions +
                  "Dispatched '${toolCall.toolName}' with ${formatArguments(toolCall.arguments)}"
                val updatedObservations = currentState.fiveStageRecord.observations +
                  observation.summary

                planner.recordToolResult(toolResult, observation.summary)

                // Post-tool reflection
                val reflection = planner.reflect(
                  currentStep = turnCounter,
                  toolName = toolCall.toolName,
                  toolArgs = toolCall.arguments,
                  result = toolResult,
                  observationSummary = observation.summary,
                  boundTodoId = toolCall.arguments["todo_id"]?.toString()
                )
                val finalFeed = if (reflection.transcriptWarning != null) {
                  val refStepId = "step_reflect_${toolCall.callId}"
                  addOrUpdateStep(
                    updatedFeed,
                    ExecutionStep(
                      id = refStepId,
                      stepNumber = ++stepCounter,
                      type = StepType.OBSERVATION,
                      title = "Planner Reflection",
                      content = reflection.transcriptWarning
                    )
                  )
                } else updatedFeed

                // Real filesystem artifact sync
                val updatedArtifacts = artifactManager.syncWorkspace(task.id)

                // Progress tracking for anti-stagnation
                val hasNewArtifact = updatedArtifacts.size > currentState.artifacts.size ||
                  updatedArtifacts.any { art -> currentState.artifacts.none { it.path == art.path && it.size == art.size } }
                val resolvedToolName = registry.resolveToolName(toolCall.toolName)
                val isStateChanging = resolvedToolName in STATE_CHANGING_TOOLS
                val isInformative = resolvedToolName in INFORMATIVE_TOOLS
                if (toolResult.isSuccess && (hasNewArtifact || isStateChanging || isInformative)) {
                  planner.recordProgress()
                  consecutiveVerificationFailures = 0
                }

                // Checkpoint persistence
                scratchpad.saveCheckpoint(
                  AgentCheckpoint(
                    taskGoal = task.goal,
                    currentTurn = turnCounter,
                    completedSections = planner.completedDescriptions(),
                    pendingSections = planner.pendingDescriptions(),
                    planItems = planner.planDescriptions(),
                    artifactPaths = updatedArtifacts.map { it.path },
                    totalFindingsCount = scratchpad.loadAllFindings().size,
                    status = "IN_PROGRESS"
                  )
                )

                val hasToolMsg = currentState.messages.any { it.role == MessageRole.TOOL && it.toolCallId == toolCall.callId }
                val updatedMessages = if (hasToolMsg) {
                  currentState.messages.map {
                    if (it.role == MessageRole.TOOL && it.toolCallId == toolCall.callId) {
                      it.copy(content = observation.rawOutput)
                    } else it
                  }
                } else {
                  currentState.messages + AgentMessage(
                    role = MessageRole.TOOL,
                    content = observation.rawOutput,
                    toolCallId = toolCall.callId,
                    toolName = toolCall.toolName
                  )
                }

                currentState = currentState.copy(
                  status = AgentStatus.OBSERVING,
                  currentAction = "Observed result: ${toolCall.toolName} -> ${toolResult.status}",
                  messages = updatedMessages,
                  toolExecutions = currentState.toolExecutions + toolResult,
                  observations = currentState.observations + observation,
                  artifacts = updatedArtifacts,
                  executionFeed = finalFeed,
                  plan = planner.planDescriptions(),
                  fiveStageRecord = currentState.fiveStageRecord.copy(
                    currentPlan = planner.planDescriptions(),
                    actions = updatedActions,
                    observations = updatedObservations
                  )
                )
                if (!updateStateIfActive(task, currentState)) return

                if (toolResult.status == ToolStatus.FAILED || toolResult.status == ToolStatus.CANCELLED) {
                  // Halt batch on failure or cancellation to allow model/agent to react immediately
                  break
                }
              }
            }

            // Post-batch: Check if replanning is required due to planner request or stagnation
            if (planner.hasReplanRequest() || planner.isStagnant()) {
              val replanReason = planner.getReplanReason() ?: if (planner.isStagnant()) {
                "Stagnation detected: ${planner.getNoProgressCount()} turns without measurable progress."
              } else "Plan adjustment required based on recent observations."
              planner.clearReplanReason()

              val replanStepId = "step_replan_t${turnCounter}_${task.id}"
              val replanStep = ExecutionStep(
                id = replanStepId,
                stepNumber = ++stepCounter,
                type = StepType.REPLANNING,
                title = "Replanning Required",
                content = replanReason
              )
              val replanNotice = "System Notice (Replanning Required): $replanReason\n" +
                "Please reconsider your approach, update the plan, and select a different action to make progress."
              val replanMsg = AgentMessage(
                role = MessageRole.USER,
                content = replanNotice
              )
              val replanMessages = if (currentState.messages.lastOrNull()?.content == replanNotice) {
                currentState.messages
              } else {
                currentState.messages + replanMsg
              }
              currentState = currentState.copy(
                status = AgentStatus.REPLANNING,
                currentAction = "Replanning: $replanReason",
                messages = replanMessages,
                executionFeed = addOrUpdateStep(currentState.executionFeed, replanStep)
              )
              if (!updateStateIfActive(task, currentState)) return
            }
          }

          is LLMDecision.Complete -> {
            providerDropRetries = 0
            // 3. OBJECTIVE VERIFICATION & SELF-AUDIT
            currentState = currentState.copy(
              status = AgentStatus.VERIFYING,
              currentAction = "Authoritatively verifying objective satisfaction against filesystem..."
            )
            if (!updateStateIfActive(task, currentState)) return

            delay(80)
            try {
              sandboxManager.activeProvider.syncArtifacts(task.sessionId, workspaceDir, task.id)
            } catch (_: Exception) {}
            val freshArtifacts = artifactManager.syncWorkspace(task.id)
            val verificationResult = verifyObjective(task, currentState.copy(artifacts = freshArtifacts))

            if (verificationResult.isSatisfied) {
              val conclusionText = decision.conclusion.ifBlank {
                "Task completed successfully and verified."
              }

              val vStep = ExecutionStep(
                id = "step_verify_t${turnCounter}_${task.id}",
                stepNumber = ++stepCounter,
                type = StepType.VERIFICATION,
                title = "Objective Verified",
                content = verificationResult.details
              )
              val cStep = ExecutionStep(
                id = "step_conclusion_${task.id}",
                stepNumber = ++stepCounter,
                type = StepType.CONCLUSION,
                title = "Completed",
                content = conclusionText
              )
              val completedFeed = addOrUpdateStep(addOrUpdateStep(currentState.executionFeed, vStep), cStep)

              artifactManager.cleanTemporaryFiles()
              val finalArtifacts = artifactManager.syncWorkspace(task.id)

              scratchpad.saveCheckpoint(
                AgentCheckpoint(
                  taskGoal = task.goal,
                  currentTurn = turnCounter,
                  completedSections = planner.completedDescriptions(),
                  pendingSections = planner.pendingDescriptions(),
                  planItems = currentState.plan,
                  artifactPaths = finalArtifacts.map { it.path },
                  totalFindingsCount = scratchpad.loadAllFindings().size,
                  status = "COMPLETED"
                )
              )

              recordTaskCompletion(task, AgentStatus.COMPLETED, conclusionText, finalArtifacts)

              val finalMessages = if (currentState.messages.any { it.role == MessageRole.ASSISTANT && it.content == conclusionText }) {
                currentState.messages
              } else {
                currentState.messages + AgentMessage(
                  role = MessageRole.ASSISTANT,
                  content = conclusionText
                )
              }

              currentState = currentState.copy(
                status = AgentStatus.COMPLETED,
                currentAction = "Task completed and verified. Conversation active for further refinement.",
                completionSummary = conclusionText,
                verification = verificationResult,
                artifacts = finalArtifacts,
                taskHistory = _state.value.taskHistory,
                executionFeed = completedFeed,
                messages = finalMessages,
                fiveStageRecord = currentState.fiveStageRecord.copy(conclusion = conclusionText),
                canCancel = false
              )
              updateStateIfActive(task, currentState)
              return
            } else {
              consecutiveVerificationFailures++
              planner.markCompletionRejected(verificationResult.details)
              val rejectionMsg = "Objective Verification Incomplete: ${verificationResult.details}. The task cannot be concluded until the required deliverables exist on disk."

              if (consecutiveVerificationFailures >= 3) {
                val isPartial = verificationResult.isPartial || verificationResult.verifiedArtifacts.isNotEmpty()
                val finalStatus = if (isPartial) AgentStatus.PARTIAL_COMPLETION else AgentStatus.FAILED
                val finalTitle = if (isPartial) "Partial Completion" else "Task Halted"
                val finalContent = if (isPartial) {
                  "Partial Completion: Verified [${verificationResult.verifiedArtifacts.joinToString()}], but missing requirements: [${verificationResult.missingRequirements.joinToString()}]."
                } else {
                  "Task halted: Deliverables could not be verified after 3 attempts. Details: ${verificationResult.details}"
                }

                val rejStep = ExecutionStep(
                  id = "step_verify_rej_t${turnCounter}_${task.id}",
                  stepNumber = ++stepCounter,
                  type = StepType.VERIFICATION,
                  title = "Verification Incomplete",
                  content = rejectionMsg
                )
                val finalStep = ExecutionStep(
                  id = "step_conclusion_${task.id}",
                  stepNumber = ++stepCounter,
                  type = if (isPartial) StepType.CONCLUSION else StepType.ERROR,
                  title = finalTitle,
                  content = finalContent
                )
                val failureFeed = addOrUpdateStep(addOrUpdateStep(currentState.executionFeed, rejStep), finalStep)

                recordTaskCompletion(task, finalStatus, finalContent, artifactManager.getArtifacts())

                currentState = currentState.copy(
                  status = finalStatus,
                  currentAction = finalContent,
                  error = if (!isPartial) "Verification incomplete: ${verificationResult.details}" else null,
                  completionSummary = if (isPartial) finalContent else null,
                  verification = verificationResult,
                  taskHistory = _state.value.taskHistory,
                  executionFeed = failureFeed,
                  canCancel = false
                )
                updateStateIfActive(task, currentState)
                return
              }

              val rejStep = ExecutionStep(
                id = "step_verify_rej_t${turnCounter}_${task.id}",
                stepNumber = ++stepCounter,
                type = StepType.VERIFICATION,
                title = "Verification Incomplete",
                content = rejectionMsg
              )
              val feed = addOrUpdateStep(currentState.executionFeed, rejStep)

              val updatedMessages = currentState.messages + listOfNotNull(
                if (currentState.messages.none { it.role == MessageRole.ASSISTANT && it.content == decision.conclusion })
                  AgentMessage(role = MessageRole.ASSISTANT, content = decision.conclusion)
                else null,
                if (currentState.messages.none { it.role == MessageRole.USER && it.content == rejectionMsg })
                  AgentMessage(role = MessageRole.USER, content = rejectionMsg)
                else null
              )

              currentState = currentState.copy(
                status = AgentStatus.PLANNING,
                currentAction = "Verification rejected completion: ${verificationResult.details}",
                messages = updatedMessages,
                verification = verificationResult,
                executionFeed = feed
              )
              if (!updateStateIfActive(task, currentState)) return
            }
          }

          is LLMDecision.ProviderError -> {
            planner.markProviderFailure(decision.message)
            if (providerDropRetries < 2) {
              providerDropRetries++
              // Honor provider-requested cooldowns (e.g. HTTP 429 Retry-After) within a sane cap.
              val waitMs = (decision.retryAfterMs ?: 1500L).coerceIn(1500L, 30_000L)
              val retryStepId = "step_retry_${providerDropRetries}_t${turnCounter}_${task.id}"
              val retryStep = ExecutionStep(
                id = retryStepId,
                stepNumber = ++stepCounter,
                type = StepType.OBSERVATION,
                title = "Endpoint Drop Recovery ($providerDropRetries/2)",
                content = "Endpoint drop: ${decision.message.take(160)}. Retrying automatically in ${waitMs / 1000}s..."
              )
              val retryFeed = addOrUpdateStep(currentState.executionFeed, retryStep)
              currentState = currentState.copy(
                currentAction = "Recovering from endpoint drop (attempt $providerDropRetries/2)...",
                executionFeed = retryFeed
              )
              if (!updateStateIfActive(task, currentState)) return
              delay(waitMs)
              continue
            } else if (providerDropRetries == 2) {
              providerDropRetries++
              val fallbackStepId = "step_fallback_${task.id}"
              val fallbackStep = ExecutionStep(
                id = fallbackStepId,
                stepNumber = ++stepCounter,
                type = StepType.OBSERVATION,
                title = "Autonomous Fallback Engaged",
                content = "LLM endpoint unavailable after retries. Switched to the built-in fallback reasoning engine; E2B Cloud sandbox execution is unchanged."
              )
              val fallbackFeed = addOrUpdateStep(currentState.executionFeed, fallbackStep)
              currentState = currentState.copy(
                currentAction = "Running via built-in fallback reasoning engine...",
                executionFeed = fallbackFeed
              )
              if (!updateStateIfActive(task, currentState)) return
              activeProvider = AutonomousSandboxProvider()
              delay(1000)
              continue
            }

            val errorStepId = "step_error_${task.id}"
            val errStep = ExecutionStep(
              id = errorStepId,
              stepNumber = ++stepCounter,
              type = StepType.ERROR,
              title = "Provider Failure",
              content = decision.message
            )
            val feed = addOrUpdateStep(currentState.executionFeed, errStep)
            recordTaskCompletion(task, AgentStatus.FAILED, decision.message, artifactManager.getArtifacts())
            currentState = currentState.copy(
              status = AgentStatus.FAILED,
              currentAction = "Failed with provider error",
              error = decision.message,
              taskHistory = _state.value.taskHistory,
              executionFeed = feed,
              canCancel = false
            )
            updateStateIfActive(task, currentState)
            return
          }
        }
      }

      // Before failing unconditionally, authoritatively check if the target deliverable exists and is valid on disk
      try {
        sandboxManager.activeProvider.syncArtifacts(task.sessionId, workspaceDir, task.id)
      } catch (_: Exception) {}
      val finalVerification = verifyObjective(task, currentState)
      if (finalVerification.isSatisfied) {
        val autoConclusion = "Objective authoritatively verified on disk (${finalVerification.verifiedArtifacts.joinToString()}) upon reaching step budget."
        artifactManager.cleanTemporaryFiles()
        val finalArtifacts = artifactManager.syncWorkspace(task.id)

        scratchpad.saveCheckpoint(
          AgentCheckpoint(
            taskGoal = task.goal,
            currentTurn = turnCounter,
            completedSections = planner.completedDescriptions(),
            pendingSections = planner.pendingDescriptions(),
            planItems = currentState.plan,
            artifactPaths = finalArtifacts.map { it.path },
            totalFindingsCount = scratchpad.loadAllFindings().size,
            status = "COMPLETED"
          )
        )

        recordTaskCompletion(task, AgentStatus.COMPLETED, autoConclusion, finalArtifacts)

        val vStep = ExecutionStep(
          id = "step_verify_disk_${task.id}",
          stepNumber = ++stepCounter,
          type = StepType.VERIFICATION,
          title = "Objective Verified on Disk",
          content = finalVerification.details
        )
        val cStep = ExecutionStep(
          id = "step_conclusion_${task.id}",
          stepNumber = ++stepCounter,
          type = StepType.CONCLUSION,
          title = "Completed",
          content = autoConclusion
        )
        val completedFeed = addOrUpdateStep(addOrUpdateStep(currentState.executionFeed, vStep), cStep)

        val finalMessages = if (currentState.messages.any { it.role == MessageRole.ASSISTANT && it.content == autoConclusion }) {
          currentState.messages
        } else {
          currentState.messages + AgentMessage(
            role = MessageRole.ASSISTANT,
            content = autoConclusion
          )
        }

        currentState = currentState.copy(
          status = AgentStatus.COMPLETED,
          currentAction = "Task completed and verified on disk. Conversation active for further refinement.",
          completionSummary = autoConclusion,
          verification = finalVerification,
          artifacts = finalArtifacts,
          taskHistory = _state.value.taskHistory,
          executionFeed = completedFeed,
          messages = finalMessages,
          fiveStageRecord = currentState.fiveStageRecord.copy(conclusion = autoConclusion),
          canCancel = false
        )
        updateStateIfActive(task, currentState)
        return
      } else if (finalVerification.isPartial || finalVerification.verifiedArtifacts.isNotEmpty()) {
        val partialConclusion = "Partial completion reached at turn limit ($maxSteps turns): " +
          "Verified deliverables: [${finalVerification.verifiedArtifacts.joinToString()}]. " +
          (if (finalVerification.missingRequirements.isNotEmpty()) "Missing requirements: [${finalVerification.missingRequirements.joinToString()}]." else "")

        artifactManager.cleanTemporaryFiles()
        val finalArtifacts = artifactManager.syncWorkspace(task.id)

        scratchpad.saveCheckpoint(
          AgentCheckpoint(
            taskGoal = task.goal,
            currentTurn = turnCounter,
            completedSections = planner.completedDescriptions(),
            pendingSections = planner.pendingDescriptions(),
            planItems = currentState.plan,
            artifactPaths = finalArtifacts.map { it.path },
            totalFindingsCount = scratchpad.loadAllFindings().size,
            status = "PARTIAL_COMPLETION"
          )
        )

        recordTaskCompletion(task, AgentStatus.PARTIAL_COMPLETION, partialConclusion, finalArtifacts)

        val vStep = ExecutionStep(
          id = "step_verify_partial_${task.id}",
          stepNumber = ++stepCounter,
          type = StepType.VERIFICATION,
          title = "Partial Verification",
          content = finalVerification.details
        )
        val cStep = ExecutionStep(
          id = "step_conclusion_${task.id}",
          stepNumber = ++stepCounter,
          type = StepType.CONCLUSION,
          title = "Partial Completion",
          content = partialConclusion
        )
        val feed = addOrUpdateStep(addOrUpdateStep(currentState.executionFeed, vStep), cStep)

        val finalMessages = if (currentState.messages.any { it.role == MessageRole.ASSISTANT && it.content == partialConclusion }) {
          currentState.messages
        } else {
          currentState.messages + AgentMessage(role = MessageRole.ASSISTANT, content = partialConclusion)
        }

        currentState = currentState.copy(
          status = AgentStatus.PARTIAL_COMPLETION,
          currentAction = partialConclusion,
          completionSummary = partialConclusion,
          verification = finalVerification,
          artifacts = finalArtifacts,
          taskHistory = _state.value.taskHistory,
          executionFeed = feed,
          messages = finalMessages,
          fiveStageRecord = currentState.fiveStageRecord.copy(conclusion = partialConclusion),
          canCancel = false
        )
        updateStateIfActive(task, currentState)
        return
      }

      val finalReport = deliverableTracker.scanAndVerify(artifactManager)
      if (maxSteps < 500) {
        // Explicitly bounded test harness budget reached
        val budgetMsg = "Execution cycle completed ($turnCounter turns). Status: ${finalReport.pendingOrInvalidDetails}"
        val feed = addOrUpdateStep(
          currentState.executionFeed,
          ExecutionStep(
            id = "step_turn_limit_${task.id}",
            stepNumber = ++stepCounter,
            type = StepType.OBSERVATION,
            title = "Execution Budget Reached",
            content = budgetMsg
          )
        )
        recordTaskCompletion(task, AgentStatus.FAILED, budgetMsg, artifactManager.getArtifacts())
        currentState = currentState.copy(
          status = AgentStatus.FAILED,
          currentAction = budgetMsg,
          error = budgetMsg,
          taskHistory = _state.value.taskHistory,
          executionFeed = feed,
          canCancel = false
        )
        updateStateIfActive(task, currentState)
      } else {
        // Hard turn budget exhausted: conclude honestly in a real terminal state.
        // (Previously this reported DELIVERABLE_PENDING with canCancel=true while the
        // run loop had already ended, leaving the UI and foreground service waiting forever.)
        val exhaustedMsg = when {
          finalReport.allSatisfied ->
            "Objective authoritatively verified on disk (${finalReport.verifiedNames.joinToString()})."
          finalReport.verifiedCount > 0 ->
            "Turn budget exhausted (${turnCounter} turns) with verified progress " +
              "${finalReport.verifiedCount}/${finalReport.totalRequired}. Pending: ${finalReport.pendingOrInvalidDetails}"
          else ->
            "Turn budget exhausted (${turnCounter} turns) without verified deliverables. Pending: ${finalReport.pendingOrInvalidDetails}"
        }
        val finalStatus = when {
          finalReport.allSatisfied -> AgentStatus.COMPLETED
          finalReport.verifiedCount > 0 -> AgentStatus.PARTIAL_COMPLETION
          else -> AgentStatus.FAILED
        }
        val finalTitle = when (finalStatus) {
          AgentStatus.COMPLETED -> "Completed"
          AgentStatus.PARTIAL_COMPLETION -> "Partial Completion"
          else -> "Turn Budget Exhausted"
        }
        val finalArtifacts = artifactManager.getArtifacts()

        scratchpad.saveCheckpoint(
          AgentCheckpoint(
            taskGoal = task.goal,
            currentTurn = turnCounter,
            completedSections = planner.completedDescriptions(),
            pendingSections = planner.pendingDescriptions(),
            planItems = currentState.plan,
            artifactPaths = finalArtifacts.map { it.path },
            totalFindingsCount = scratchpad.loadAllFindings().size,
            status = finalStatus.name
          )
        )

        recordTaskCompletion(task, finalStatus, exhaustedMsg, finalArtifacts)

        val budgetStep = ExecutionStep(
          id = "step_turn_budget_${task.id}",
          stepNumber = ++stepCounter,
          type = if (finalStatus == AgentStatus.COMPLETED) StepType.CONCLUSION else StepType.ERROR,
          title = finalTitle,
          content = exhaustedMsg
        )
        val finalMessages = if (currentState.messages.any { it.role == MessageRole.ASSISTANT && it.content == exhaustedMsg }) {
          currentState.messages
        } else {
          currentState.messages + AgentMessage(role = MessageRole.ASSISTANT, content = exhaustedMsg)
        }
        currentState = currentState.copy(
          status = finalStatus,
          currentAction = exhaustedMsg,
          error = if (finalStatus == AgentStatus.FAILED) exhaustedMsg else null,
          completionSummary = if (finalStatus != AgentStatus.FAILED) exhaustedMsg else null,
          verification = finalVerification,
          artifacts = finalArtifacts,
          taskHistory = _state.value.taskHistory,
          executionFeed = addOrUpdateStep(currentState.executionFeed, budgetStep),
          messages = finalMessages,
          canCancel = false
        )
        updateStateIfActive(task, currentState)
      }

    } catch (ce: CancellationException) {
      // Cancellation may arrive after the user already started another task or after the
      // task reached a terminal state. Only a run that still owns the authoritative state
      // may record the cancellation, so a stale callback can never overwrite newer state
      // or persist a CANCELLED checkpoint over a fresh session's progress.
      val stillOwnsState = synchronized(this) { _state.value.task?.id == task.id }
      if (stillOwnsState) {
        scratchpad.saveCheckpoint(
          AgentCheckpoint(
            taskGoal = task.goal,
            currentTurn = turnCounter,
            completedSections = planner.completedDescriptions(),
            pendingSections = planner.pendingDescriptions(),
            planItems = currentState.plan,
            artifactPaths = artifactManager.getArtifacts().map { it.path },
            totalFindingsCount = scratchpad.loadAllFindings().size,
            status = "CANCELLED"
          )
        )

        if (_state.value.taskHistory.none { it.id == task.id && it.status == AgentStatus.CANCELLED }) {
          recordTaskCompletion(task, AgentStatus.CANCELLED, "Execution stopped by user.", artifactManager.getArtifacts())
        }

        val latest = _state.value
        val isAlreadyTerminal = latest.status == AgentStatus.COMPLETED ||
          latest.status == AgentStatus.PARTIAL_COMPLETION ||
          latest.status == AgentStatus.FAILED ||
          latest.status == AgentStatus.CANCELLED
        if (!isAlreadyTerminal || latest.status == AgentStatus.CANCELLED) {
          val cancelStep = ExecutionStep(
            id = "step_cancelled_${task.id}",
            stepNumber = (latest.executionFeed.maxOfOrNull { it.stepNumber } ?: 0) + 1,
            type = StepType.ERROR,
            title = "Execution Cancelled",
            content = "Agent execution stopped by user."
          )
          _state.value = latest.copy(
            status = AgentStatus.CANCELLED,
            currentAction = "Execution stopped by user.",
            taskHistory = _state.value.taskHistory,
            executionFeed = addOrUpdateStep(latest.executionFeed, cancelStep),
            canCancel = false
          )
        }
      }
    } catch (t: Throwable) {
      if (t is CancellationException) throw t
      val errorMsg = t.message ?: t.javaClass.simpleName
      val feed = currentState.executionFeed + ExecutionStep(
        stepNumber = ++stepCounter,
        type = StepType.ERROR,
        title = "Execution Error",
        content = "Unexpected exception: $errorMsg"
      )
      recordTaskCompletion(task, AgentStatus.FAILED, "Unexpected exception: $errorMsg", artifactManager.getArtifacts())
      currentState = currentState.copy(
        status = AgentStatus.FAILED,
        currentAction = "Execution error: $errorMsg",
        error = errorMsg,
        taskHistory = _state.value.taskHistory,
        executionFeed = feed,
        canCancel = false
      )
      updateStateIfActive(task, currentState)
    }
  }

  /**
   * Infers candidate deliverable filename from goal text.
   */
  private fun inferDeliverableFilename(goal: String): String? {
    val words = goal.split("\\s+".toRegex())
    for (word in words) {
      val cleanWord = word.trim('.', ',', '"', '\'', '`')
      if (cleanWord.contains('.') && cleanWord.length > 3) {
        val ext = cleanWord.substringAfterLast('.', "").lowercase()
        if (ext in listOf("txt", "md", "json", "py", "sh", "csv", "html", "docx", "xlsx", "xls")) {
          return cleanWord
        }
      }
    }
    val goalLower = goal.lowercase()
    if (goalLower.contains("word file") || goalLower.contains("word doc") || goalLower.contains("word document")) {
      return "document.docx"
    }
    if (goalLower.contains("excel") || goalLower.contains("spreadsheet") || goalLower.contains("xlsx") || goalLower.contains("sheet")) {
      return "spreadsheet.xlsx"
    }
    return null
  }

  fun extractRequiredOutputs(goal: String): Set<String> {
    val results = mutableSetOf<String>()
    val regexes = listOf(
      Regex("""(?:record|write|save|output|produce|generate|create)\s+(?:findings|results|report|audit|document)?\s*(?:to|in|into)?\s+[`"']?([a-zA-Z0-9_\-./]+\.[a-zA-Z0-9]{2,4})[`"']?""", RegexOption.IGNORE_CASE),
      Regex("""(?:deliverable|artifact|report):\s*[`"']?([a-zA-Z0-9_\-./]+\.[a-zA-Z0-9]{2,4})[`"']?""", RegexOption.IGNORE_CASE)
    )
    for (regex in regexes) {
      regex.findAll(goal).forEach { match ->
        val candidate = match.groupValues[1].trim()
        val name = File(candidate).name
        if (name !in listOf("scribd.com", "example.com", "google.com", "github.com", "index.html")) {
          results.add(name)
        }
      }
    }
    return results
  }

  /**
   * Authoritatively verifies whether the objective is satisfied on the filesystem.
   *
   * Crucial invariants:
   * 1. A successful tool call does NOT imply task completion.
   * 2. Deliverables must be verified on disk: exist, be regular files, non-empty, and readable.
   * 3. Acceptance checks must verify all requested deliverables.
   * 4. Missing requirements are reported honestly as partial completion when applicable.
   */
  private fun verifyObjective(task: Task, state: AgentState): VerificationResult {
    val report = deliverableTracker.scanAndVerify(artifactManager)

    // 0. Explicit expected deliverable or tracked deliverables check
    if (task.expectedArtifact != null || report.totalRequired > 0) {
      if (report.allSatisfied) {
        return VerificationResult(
          isSatisfied = true,
          details = "All ${report.verifiedCount} required deliverable(s) authoritatively verified on disk: [${report.verifiedNames.joinToString()}].",
          verifiedArtifacts = report.verifiedNames
        )
      } else {
        return VerificationResult(
          isSatisfied = false,
          details = "Required deliverable verification pending: ${report.pendingOrInvalidDetails}"
        )
      }
    }

    val verified = mutableListOf<String>()
    val missing = mutableListOf<String>()

    // 1. Strict deliverable check from explicit task outputs (e.g. audit_scribd.md)
    val requiredOutputs = extractRequiredOutputs(task.goal)
    for (out in requiredOutputs) {
      val f = File(workspaceDir, out)
      if (!f.exists() || !f.isFile || !f.canRead() || f.length() == 0L) {
        missing.add(out)
      } else {
        val validation = ArtifactValidatorRegistry.validateExistingFile(f)
        if (validation.isValid) {
          if (!verified.contains(out)) verified.add(out)
        } else {
          missing.add("$out (${validation.errorMessage})")
        }
      }
    }

    // 2. Inferred deliverable from goal text
    val cleanWord = inferDeliverableFilename(task.goal)
    if (cleanWord != null) {
      val file = File(workspaceDir, cleanWord)
      val goalLower = task.goal.lowercase()
      if (goalLower.contains("delete") || goalLower.contains("remove")) {
        if (file.exists()) {
          return VerificationResult(
            isSatisfied = false,
            details = "File '$cleanWord' was requested to be deleted but still exists on disk.",
            missingRequirements = listOf("Deletion of $cleanWord")
          )
        } else {
          if (!verified.contains("Deleted $cleanWord")) verified.add("Deleted $cleanWord")
        }
      } else if (goalLower.contains("create") || goalLower.contains("write") || goalLower.contains("produce") ||
                 goalLower.contains("generate") || goalLower.contains("record") || goalLower.contains("save") ||
                 goalLower.contains("store") || goalLower.contains("output") || goalLower.contains("transform")) {
        val effectiveFile = if (file.exists()) file else {
          // If cleanWord is a .docx or .xlsx, check if any non-empty matching file exists in workspace
          if (cleanWord.endsWith(".docx", ignoreCase = true)) {
            workspaceDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".docx", ignoreCase = true) && it.length() > 0 } ?: file
          } else if (cleanWord.endsWith(".xlsx", ignoreCase = true) || cleanWord.endsWith(".xls", ignoreCase = true)) {
            workspaceDir.listFiles()?.firstOrNull { it.isFile && (it.name.endsWith(".xlsx", ignoreCase = true) || it.name.endsWith(".xls", ignoreCase = true)) && it.length() > 0 } ?: file
          } else {
            file
          }
        }
        if (!effectiveFile.exists() || !effectiveFile.isFile || !effectiveFile.canRead() || effectiveFile.length() == 0L) {
          if (!missing.contains(effectiveFile.name)) missing.add(effectiveFile.name)
        } else {
          val diskValidation = ArtifactValidatorRegistry.validateExistingFile(effectiveFile)
          if (diskValidation.isValid) {
            if (!verified.contains(effectiveFile.name)) verified.add(effectiveFile.name)
          } else {
            val desc = "${effectiveFile.name} (${diskValidation.errorMessage})"
            if (!missing.contains(desc)) missing.add(desc)
          }
        }
      }
    }

    // If explicit deliverables were requested and any are missing:
    if (missing.isNotEmpty()) {
      return VerificationResult(
        isSatisfied = false,
        details = "Required deliverable(s) [${missing.joinToString()}] have not been verified on disk or are empty/unreadable.",
        verifiedArtifacts = verified,
        missingRequirements = missing,
        isPartial = verified.isNotEmpty()
      )
    }

    // 3. Last tool must not be in a failed state (unless it was an anti-stagnation intervention skip or diagnostic evaluation)
    val lastExecution = state.toolExecutions.lastOrNull()
    if (lastExecution != null && lastExecution.status == ToolStatus.FAILED) {
      val isInterventionSkip = lastExecution.error?.contains("Intervention:") == true
      val isDiagnosticTask = task.goal.lowercase().let { g ->
        g.contains("test") || g.contains("check") || g.contains("see if") || g.contains("probe") || g.contains("inspect") || g.contains("diagnose") || g.contains("audit")
      }
      if (!isInterventionSkip && !isDiagnosticTask) {
        return VerificationResult(
          isSatisfied = false,
          details = "The last tool execution ('${lastExecution.toolName}') failed with error: '${lastExecution.error}'. Cannot conclude in an unhandled failure state.",
          missingRequirements = listOf("Resolve failure in ${lastExecution.toolName}"),
          verifiedArtifacts = verified
        )
      }
    }

    // 4. If deliverables were requested and all were verified:
    if (verified.isNotEmpty()) {
      return VerificationResult(
        isSatisfied = true,
        details = "Deliverables verified on disk: [${verified.joinToString()}].",
        verifiedArtifacts = verified
      )
    }

    // 5. Informational / Diagnostic task (no explicit file deliverables requested)
    // A successful tool call alone does NOT imply task completion if the plan has pending actions!
    val hasPendingPlanActions = state.plan.any { it.contains("[ ]") || it.contains("pending", ignoreCase = true) }
    if (hasPendingPlanActions && state.toolExecutions.isNotEmpty()) {
      val pendingItems = state.plan.filter { it.contains("[ ]") || it.contains("pending", ignoreCase = true) }
      return VerificationResult(
        isSatisfied = false,
        details = "Plan has pending actions that have not been executed: ${pendingItems.joinToString("; ")}.",
        missingRequirements = pendingItems,
        isPartial = true
      )
    }

    if (state.toolExecutions.isEmpty()) {
      return VerificationResult(
        isSatisfied = false,
        details = "No actions or diagnostic verifications have been performed.",
        missingRequirements = listOf("Perform required task actions")
      )
    }

    val readableArtifacts = artifactManager.getArtifacts().filter { it.isReadable && it.size > 0 }.map { it.path }
    return VerificationResult(
      isSatisfied = true,
      details = "Task objective verified: ${state.toolExecutions.size} action(s) completed without unmet deliverables.",
      verifiedArtifacts = readableArtifacts
    )
  }

  /**
   * Constructs the authoritative system context string with the capability manifest,
   * continuous turn execution, and dynamic deliverable tracker.
   */
  private fun buildSystemContext(
    task: Task,
    state: AgentState,
    planner: TaskPlanner,
    deliverableReport: DeliverableReport? = null,
    turnCounter: Int = 1,
    maxSteps: Int = 1000
  ): String {
    val sb = StringBuilder()
    sb.appendLine(DEFAULT_AGENT_SYSTEM_PROMPT)
    sb.appendLine()
    sb.appendLine("CURRENT USER GOAL: ${task.goal}")
    if (task.expectedArtifact != null) {
      sb.appendLine("EXPECTED DELIVERABLE: ${task.expectedArtifact}")
    }

    // Immutable Workspace Environment
    sb.appendLine()
    sb.appendLine("IMMUTABLE WORKSPACE ENVIRONMENT:")
    sb.appendLine("- EXECUTION ENVIRONMENT: E2B Cloud MicroVM is the ONLY execution environment. There is no local execution sandbox.")
    sb.appendLine("- \$WORKSPACE PATH (inside the E2B microVM): /home/user")
    sb.appendLine("- WORKSPACE LOCK: All created scripts, recon outputs, and deliverables must reside in \$WORKSPACE (/home/user) or /tmp scratch. Writing outside will be rejected.")

    // Execution Turn & Deliverable Tracker
    sb.appendLine()
    sb.appendLine("EXECUTION STATUS & PROGRESS:")
    sb.appendLine("- Turn: $turnCounter (Active Autonomous Workflow)")
    if (state.isMultitasking || state.activeConcurrentTasks > 0) {
      sb.appendLine("- Multitasking State: Concurrently executing ${state.activeConcurrentTasks} operations.")
    }
    sb.appendLine()
    sb.appendLine(deliverableTracker.generatePromptSummary(deliverableReport))

    // Prominently highlight active execution sandbox environment
    sb.appendLine()
    sb.appendLine("ACTIVE EXECUTION SANDBOX:")
    sb.appendLine("- Sandbox Mode: ${sandboxProvider.mode.name}")
    sb.appendLine("- Provider Name: ${sandboxProvider.name}")
    val boundaryDesc = "Dedicated E2B Cloud Firecracker MicroVM (Hardware-isolated Linux with Python3, Bash, pip, and outbound network access)"
    sb.appendLine("- Execution Boundary: $boundaryDesc")
    sb.appendLine("- Workflow Directive: All tool calls execute inside the E2B Cloud MicroVM; adapt scripting to this sandbox, never assume a local device shell.")

    // Inject compact machine-readable capability manifest generated from reality
    val manifest = CapabilityManifest.build(registry, sandboxProvider)
    sb.appendLine()
    sb.appendLine("CAPABILITY MANIFEST (Ground Truth from Actual Runtime):")
    sb.appendLine(manifest.toJsonString())

    // Execution Plan with Todo tracking
    sb.appendLine()
    sb.appendLine(planner.context())

    val researchSummary = scratchpad.getStructuredResearchSummary(maxFindings = 8)
    sb.appendLine()
    sb.appendLine(researchSummary)

    val currentArtifacts = artifactManager.getArtifacts()
    sb.appendLine()
    sb.appendLine("WORKSPACE DELIVERABLES ON DISK (${currentArtifacts.size} files):")
    if (currentArtifacts.isEmpty()) {
      sb.appendLine("  (Workspace is currently empty)")
    } else {
      currentArtifacts.forEach {
        sb.appendLine("  - ${it.path} (${it.size} bytes, ${it.type})")
      }
    }

    val documentContext = documentService?.snapshotForPrompt()
    if (documentContext != null) {
      sb.appendLine()
      sb.appendLine(documentContext.trimEnd())
    }

    val todayDate = java.time.LocalDate.now()
    val formattedDate = java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.US).format(todayDate)
    sb.appendLine()
    sb.appendLine("SYSTEM CALENDAR & TEMPORAL CONTEXT:")
    sb.appendLine("- CURRENT LOCAL DATE: $todayDate ($formattedDate)")
    sb.appendLine()
    sb.appendLine("DOCUMENT REPLICATION, CREATION & TRANSFORMATION DIRECTIVES:")
    sb.appendLine("- When the user asks to replicate, recreate, or modify a document or screenshot (e.g., 'see this picture of a word file, replicate it and change title, author, and date to current date'):")
    sb.appendLine("  * Call 'recreate_document(source: \"<filename>\", target_format: \"docx|xlsx|pdf\", output_path: \"...\", title: \"...\", author: \"...\", date: \"$formattedDate\", replacements: {...})'.")
    sb.appendLine("  * It automatically analyzes the image/screenshot via OCR, transcribes structure, replaces title/author/date, and builds the target file!")
    sb.appendLine("  * For authoring from scratch: use 'build_word_document', 'build_excel_spreadsheet', or 'build_pdf_document'.")

    // Model Context Protocol (MCP) Subsystem Status & Guidance
    val mcp = mcpManager
    if (mcp != null) {
      val servers = mcp.servers.value
      val tools = mcp.discoveredTools.value
      val resources = mcp.discoveredResources.value
      sb.appendLine()
      sb.appendLine("MODEL CONTEXT PROTOCOL (MCP) SUBSYSTEM STATUS:")
      sb.appendLine("- Configured MCP Servers (${servers.size}):")
      for (s in servers) {
        val client = mcp.getClient(s.id)
        val status = client?.status?.value?.name ?: (if (s.enabled) "CONNECTING" else "DISABLED")
        val count = client?.tools?.value?.size ?: 0
        sb.appendLine("  * [${s.id}] '${s.name}' (${s.transport.name}) -> Status: $status, Tools: $count")
      }
      sb.appendLine("- Active MCP Bridged Tools (${tools.size}):")
      if (tools.isEmpty()) {
        sb.appendLine("  (None connected yet - enable via 'mcp_manage_server' or configure in settings)")
      } else {
        tools.forEach { t ->
          sb.appendLine("  * ${t.namespacedName}: ${t.description ?: t.name}")
        }
      }
      if (resources.isNotEmpty()) {
        sb.appendLine("- Available MCP Resources (${resources.size}):")
        resources.forEach { r ->
          sb.appendLine("  * ${r.uri} (${r.name})")
        }
      }
      sb.appendLine("- MCP Agentic Directives:")
      sb.appendLine("  * For database queries and table operations, call 'sqlite__query', 'sqlite__execute', 'sqlite__list_tables', or 'sqlite__describe_table'.")
      sb.appendLine("  * For clean web fetching and header inspection, call 'fetch__fetch_url' or 'fetch__fetch_headers'.")
      sb.appendLine("  * You can invoke ANY tool on ANY MCP server directly via 'mcp_call_tool(server_id: \"...\", tool_name: \"...\", arguments: {...})'.")
      sb.appendLine("  * Discover or enable additional MCP servers using 'mcp_list_servers' and 'mcp_manage_server(action: \"enable\", id: \"...\")'.")
      sb.appendLine("  * Read server-exposed resources using 'mcp_list_resources' and 'mcp_read_resource(uri: \"...\")'.")
    }

    return sb.toString()
  }

  private fun formatArguments(args: Map<String, Any?>): String {
    if (args.isEmpty()) return "{}"
    return args.entries.joinToString(", ") { "${it.key}: ${it.value}" }
  }

  /**
   * Stable identity of an action for loop detection: resolved tool name plus
   * deterministically ordered arguments.
   */
  private fun actionSignature(call: ToolCall): String {
    val resolvedName = registry.resolveToolName(call.toolName)
    val args = call.arguments.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }
    return "$resolvedName($args)"
  }

  companion object {
    /** The 3rd+ consecutive identical action is skipped by the loop guard. */
    private const val MAX_CONSECUTIVE_IDENTICAL_ACTIONS = 3

    /** Consecutive turns without any tool call or completion before the run halts honestly. */
    private const val MAX_CONSECUTIVE_EMPTY_DECISIONS = 6

    /** While the built-in fallback engine is active, retry the configured provider every N turns. */
    private const val FALLBACK_PROBE_INTERVAL_TURNS = 5

    /** Checkpoint statuses from which a same-goal task may restore plan progress on resume. */
    private val RESUMABLE_CHECKPOINT_STATUSES = setOf(
      "IN_PROGRESS", "CANCELLED", "FAILED", "PARTIAL_COMPLETION"
    )

    /** Tools whose successful execution changes persistent workspace state. */
    private val STATE_CHANGING_TOOLS = setOf(
      "write_file", "edit_file", "apply_patch", "delete_file", "move_file", "copy_file",
      "create_directory", "delete_directory", "install_package", "e2b_write_file",
      "transfer_artifact_to_sandbox", "download_artifact_from_sandbox",
      "document_process", "mcp_manage_server"
    )

    /** Tools whose successful execution produces genuinely new information. */
    private val INFORMATIVE_TOOLS = setOf(
      "web_search", "web_fetch", "web_open", "web_extract", "web_crawl", "deep_research",
      "read_file", "list_directory", "list_files", "file_exists", "file_metadata",
      "check_tools", "sandbox_diagnostics", "e2b_read_file", "e2b_list_directory", "diff_files",
      "document_search", "document_list",
      "mcp_list_servers", "mcp_list_resources", "mcp_read_resource", "mcp_list_prompts", "mcp_refresh", "mcp_call_tool"
    )
  }

  fun canExecuteConcurrently(calls: List<ToolCall>): Boolean {
    if (calls.size <= 1) return false
    val readOnlyTools = setOf(
      "read_file", "list_directory", "file_exists", "file_metadata",
      "diff_files", "web_search", "web_fetch", "web_open", "web_extract",
      "web_crawl", "check_tools", "sandbox_diagnostics",
      "document_search", "document_list",
      "mcp_list_servers", "mcp_list_resources", "mcp_read_resource", "mcp_list_prompts"
    )
    val isAllReadOnly = calls.all { registry.resolveToolName(it.toolName) in readOnlyTools }
    if (isAllReadOnly) return true

    // Independent writes to distinct file paths
    val isAllDistinctWrites = calls.all {
      registry.resolveToolName(it.toolName) in setOf("write_file", "edit_file") &&
        it.arguments["path"] is String
    }
    if (isAllDistinctWrites) {
      val paths = calls.mapNotNull { it.arguments["path"] as? String }
      return paths.size == calls.size && paths.distinct().size == paths.size
    }

    return false
  }

  /**
   * Stops active execution immediately.
   */
  fun cancel() {
    val job = activeJob
    val currentTask = _state.value.task
    if (job != null && job.isActive) {
      if (currentTask != null) {
        scratchpad.saveCheckpoint(
          AgentCheckpoint(
            taskGoal = currentTask.goal,
            currentTurn = _state.value.currentStep,
            planItems = _state.value.plan,
            artifactPaths = artifactManager.getArtifacts().map { it.path },
            totalFindingsCount = scratchpad.loadAllFindings().size,
            status = "CANCELLED"
          )
        )
        recordTaskCompletion(currentTask, AgentStatus.CANCELLED, "Execution stopped by user.", artifactManager.getArtifacts())
        _state.value = _state.value.copy(
          status = AgentStatus.CANCELLED,
          currentAction = "Cancelled by user.",
          taskHistory = _state.value.taskHistory,
          canCancel = false
        )
      }
      job.cancel()
    }
  }

  /**
   * Resets the agent and workspace.
   */
  fun reset(cleanWorkspace: Boolean = true) {
    activeJob?.cancel()
    if (cleanWorkspace) {
      artifactManager.cleanAll()
    }
    startNewSession()
  }
}
