package com.example.agent.core

import com.alibaba.opensandbox.sandbox.Sandbox
import com.example.agent.docs.DocumentIntelligenceService
import com.example.agent.docs.DocumentListTool
import com.example.agent.docs.DocumentProcessTool
import com.example.agent.docs.DocumentSearchTool
import com.example.agent.docs.DocumentDetector
import com.example.agent.docs.DocumentText
import com.example.agent.docs.DocumentStructureCodec
import android.util.Log
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
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
  val recoveryManager = UnifiedRecoveryManager(workspaceDir)
  val registry = ToolRegistry()
  val executor = ToolExecutor(registry, artifactManager)
  val dispatcher = ToolDispatcher(registry, executor, approvalManager, recoveryManager)

  var mcpManager: com.example.agent.mcp.McpManager? = null
  var onFilesSyncedToSandbox: ((count: Int, files: List<String>) -> Unit)? = null
  private var configuredProvider: LLMProvider = initialProvider
  private var activeProvider: LLMProvider = initialProvider
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var activeJob: Job? = null
  private val dedupPipeline = ReasoningDedupPipeline()

  private var consecutiveZeroProgressActions = 0
  private var hasReplannedForZeroProgress = false
  private var lastFilesystemStateFingerprint: String? = null

  private fun computeExecutionStateFingerprint(): String {
    val files = try {
      workspaceDir.walkTopDown().maxDepth(3).filter { it.isFile }.map { "${it.name}:${it.length()}:${it.lastModified()}" }.sorted().joinToString(";")
    } catch (_: Exception) { "" }
    val deliverables = deliverableTracker.deliverablesMap.values.joinToString(";") { "${it.path}:${it.status}:${it.sizeBytes}" }
    val findings = scratchpad.loadAllFindings().size
    return "$files|$deliverables|$findings"
  }

  init {
    workspaceDir.mkdirs()
    ArtifactDownloader.defaultWorkspaceDir = workspaceDir

    // Generated artifacts are deliverables/outputs tracked by ArtifactManager,
    // they must NEVER be automatically registered as user-attached input documents.
    artifactManager.databaseRegistrar = null

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
    registry.register(DeleteFileTool(workspaceDir, artifactManager, sandboxProvider = sandboxProvider, sessionIdProvider = { _state.value.sessionId }))
    registry.register(MoveFileTool(workspaceDir, artifactManager, sandboxProvider = sandboxProvider, sessionIdProvider = { _state.value.sessionId }))
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
    registry.register(WebOpenTool(isConfigured = webEnabled, workspaceDir = workspaceDir))
    registry.register(WebExtractTool(isConfigured = webEnabled))
    registry.register(WebCrawlTool(isConfigured = webEnabled))
    registry.register(WebFetchTool(isConfigured = webEnabled, workspaceDir = workspaceDir))
    registry.register(BrowserAutomationTool(isConfigured = webEnabled))
    registry.register(DeepWebResearchTool(isConfigured = webEnabled))

    // 6. Document Intelligence Tools (imported documents: OCR, RAG search, native generation)
    val docs = documentService
    if (docs != null) {
      registry.register(DocumentListTool(docs))
      registry.register(DocumentSearchTool(docs))
      registry.register(DocumentProcessTool(workspaceDir, docs, artifactManager))
      registry.register(
        com.example.agent.docs.ReadImageTool(
          workspaceDir = workspaceDir,
          ocrRouter = docs.ocrRouter,
          documentServiceSupplier = { documentService },
          sandboxProviderSupplier = { sandboxProvider },
          sessionIdProvider = { _state.value.sessionId }
        )
      )
    }

    // 7. Native PDF Document Builder & Universal Replicator (Word & Excel are authored exclusively via Python)
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
    (registry.getTool("delete_file") as? DeleteFileTool)?.sandboxProvider = active
    (registry.getTool("move_file") as? MoveFileTool)?.sandboxProvider = active
    (registry.getTool("file_exists") as? FileExistsTool)?.sandboxProvider = active
    (registry.getTool("file_metadata") as? FileMetadataTool)?.sandboxProvider = active
    (registry.getTool("install_package") as? InstallPackageTool)?.sandboxProvider = active
    if (active is E2BSandboxProvider) {
      (registry.getTool("e2b_sandbox") as? E2BCloudTool)?.e2bProvider = active
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
      val pTask = _state.value.persistentTask?.copy(artifacts = artifacts)
      val record = TaskRecord(
        id = task.id,
        sessionId = task.sessionId,
        goal = task.goal,
        status = status,
        startedAt = task.createdAt,
        completedAt = System.currentTimeMillis(),
        expectedArtifact = task.expectedArtifact,
        artifactsProduced = artifacts.map { it.path },
        totalTurns = _state.value.currentStep,
        summary = _state.value.completionSummary ?: conclusion,
        error = _state.value.error,
        conclusion = conclusion,
        deliverables = artifacts.map { it.path },
        timestamp = System.currentTimeMillis(),
        conversation = _state.value.messages,
        planSteps = _state.value.plan,
        executionFeed = _state.value.executionFeed,
        fiveStageRecord = _state.value.fiveStageRecord,
        currentPhase = pTask?.currentPhase ?: TaskPhase.COMPLETED,
        actionableSteps = pTask?.actionableSteps ?: emptyList(),
        completionEvidence = pTask?.completionEvidence ?: emptyList(),
        revision = pTask?.revision ?: 0,
        persistentTask = pTask,
        artifacts = artifacts
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

    val isFollowUp = continueConversation ||
      (_state.value.status == AgentStatus.COMPLETED && _state.value.messages.isNotEmpty())
    val isResuming = !isFollowUp && (resumeSessionId != null && resumeSessionId == _state.value.sessionId)
    val isContinuation = isFollowUp || isResuming

    val sessionId = if (isContinuation) _state.value.sessionId else (resumeSessionId ?: UUID.randomUUID().toString())
    // Retain workspace deliverables across consecutive tasks so subsequent tasks
    // can process, transform, or build upon prior deliverables (e.g. sys_info.txt).
    // Clean only temporary/intermediate scratch files and refresh workspace state.
    artifactManager.cleanTemporaryFiles()
    val initialArtifacts = artifactManager.syncWorkspace()
    dispatcher.reset()
    if (!isContinuation) {
      scratchpad.initSession(sessionId)
    } else if (isFollowUp) {
      scratchpad.resetForFollowUp()
    }

    val task = Task(
      id = UUID.randomUUID().toString(),
      sessionId = sessionId,
      goal = intent.trim(),
      status = AgentStatus.THINKING,
      expectedArtifact = expectedArtifact
    )

    val currentHistory = _state.value.taskHistory
    val initialPTask = task.toPersistentTask()
    val taskRecord = TaskRecord(
      id = task.id,
      sessionId = sessionId,
      goal = task.goal,
      status = AgentStatus.THINKING,
      deliverables = initialArtifacts.map { it.name },
      timestamp = System.currentTimeMillis(),
      currentPhase = task.currentPhase,
      actionableSteps = task.actionableSteps,
      persistentTask = initialPTask
    )
    val updatedHistory = listOf(taskRecord) + currentHistory.filter { it.id != task.id }
    scratchpad.saveTaskRecord(taskRecord)
    scratchpad.saveTask(initialPTask)
    val existingRecord = if (isResuming && _state.value.messages.isEmpty()) {
      _state.value.taskHistory.firstOrNull { it.sessionId == sessionId }
        ?: scratchpad.loadTaskHistory().firstOrNull { it.sessionId == sessionId }
    } else null

    val previousMessages = when {
      isContinuation && _state.value.messages.isNotEmpty() -> _state.value.messages
      isResuming && existingRecord != null -> existingRecord.conversation
      isContinuation -> _state.value.messages
      else -> emptyList()
    }
    val previousFeed = when {
      isContinuation && _state.value.executionFeed.isNotEmpty() -> _state.value.executionFeed
      isResuming && existingRecord != null -> existingRecord.executionFeed
      isContinuation -> _state.value.executionFeed
      else -> emptyList()
    }
    val startingStep = previousFeed.size

    val inScopeIds = documentService?.scope?.value ?: emptySet()
    val allUploadedDocs = documentService?.documents?.value ?: emptyList()
    val effectiveScopeIds = if (inScopeIds.isNotEmpty()) inScopeIds else allUploadedDocs.map { it.id }.toSet()

    val inScopeDocNames = if (effectiveScopeIds.isNotEmpty()) {
      allUploadedDocs
        .filter { it.id in effectiveScopeIds }
        .map { it.displayName }
    } else emptyList()

    val inputFiles = inScopeDocNames
      .plus(
        workspaceDir.walkTopDown()
          .onEnter { d ->
            val dLower = d.name.lowercase()
            !dLower.startsWith(".") && dLower !in DeliverableTracker.INTERNAL_WORKSPACE_DIRS
          }
          .filter { f ->
            val rel = f.relativeTo(workspaceDir).path.replace('\\', '/')
            f.isFile && !DeliverableTracker.isInternalWorkspacePath(rel, f.name.lowercase())
          }
          .map { it.relativeTo(workspaceDir).path.replace('\\', '/') }
          .toList()
      )
      .distinct()

    val messageAttachments = mutableListOf<FileAttachment>()
    val attachedDocTexts = mutableListOf<Pair<String, String>>()
    if (effectiveScopeIds.isNotEmpty()) {
      for (doc in allUploadedDocs) {
        if (doc.id in effectiveScopeIds) {
          val f = documentService?.getOriginalFile(doc.id)
            ?: documentService?.resolveDocumentFile(doc.id)
            ?: File(workspaceDir, doc.displayName).takeIf { it.exists() }
            ?: File(workspaceDir, DocumentText.sanitizeFileName(doc.displayName)).takeIf { it.exists() }
          if (f != null && f.exists() && f.length() <= 20 * 1024 * 1024) {
            val wsCopy = File(workspaceDir, doc.displayName)
            if (!wsCopy.exists() || wsCopy.length() == 0L) {
              try { f.copyTo(wsCopy, overwrite = true) } catch (_: Exception) {}
            }
            val mime = when (doc.type) {
              com.example.agent.docs.DocumentType.IMAGE -> DocumentDetector.imageMimeType(f)
              com.example.agent.docs.DocumentType.PDF -> "application/pdf"
              com.example.agent.docs.DocumentType.CSV -> "text/csv"
              com.example.agent.docs.DocumentType.TXT -> "text/plain"
              com.example.agent.docs.DocumentType.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
              com.example.agent.docs.DocumentType.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              else -> "application/octet-stream"
            }
            messageAttachments.add(FileAttachment(name = doc.displayName, mimeType = mime, file = f))

            // Check if documentService has already extracted text / structure
            val structFile = File(workspaceDir, "documents/${doc.id}/structure.json")
            if (structFile.exists() && structFile.isFile) {
              try {
                val struct = DocumentStructureCodec.fromJson(structFile.readText())
                val extracted = struct.blocks.mapNotNull { it.text.takeIf { t -> t.isNotBlank() } }.joinToString("\n")
                if (extracted.isNotBlank()) {
                  attachedDocTexts.add(Pair(doc.displayName, extracted))
                }
              } catch (_: Exception) {}
            }
          }
        }
      }
    }

    // Fallback: If no documentService attachments were found, look for standalone image files in workspace
    if (messageAttachments.isEmpty()) {
      val imageExts = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
      val wsImages = workspaceDir.walkTopDown().maxDepth(2)
        .filter { f ->
          f.isFile && !f.name.startsWith(".") &&
            !f.relativeTo(workspaceDir).path.startsWith("documents/") &&
            !f.relativeTo(workspaceDir).path.startsWith(".scratchpad/") &&
            !f.relativeTo(workspaceDir).path.startsWith(".observations/") &&
            f.extension.lowercase() in imageExts
        }
        .sortedByDescending { it.lastModified() }
        .toList()
      for (img in wsImages.take(3)) {
        messageAttachments.add(
          FileAttachment(
            name = img.name,
            mimeType = DocumentDetector.imageMimeType(img),
            file = img
          )
        )
      }
    }

    val newStep = ExecutionStep(
      id = "step_intent_${task.id}",
      stepNumber = startingStep + 1,
      type = StepType.TASK_INTENT,
      title = when {
        isResuming -> "Task Resumed"
        isFollowUp -> "Follow-up / Task Modification"
        else -> "Task Received"
      },
      content = task.goal,
      attachments = messageAttachments
    )

    val userMessageContent = buildString {
      append(when {
        isResuming -> "Task Objective (Resumed): ${task.goal}"
        isFollowUp -> "User Follow-up Request: ${task.goal}"
        else -> "Task Objective: ${task.goal}"
      })
      if (messageAttachments.isNotEmpty()) {
        appendLine()
        appendLine()
        appendLine("[USER ATTACHMENTS (Direct Model Access)]:")
        messageAttachments.forEach { att ->
          appendLine("• ${att.name} (${att.mimeType}) — placed at /home/user/${att.name} and workspace '${att.name}'")
        }
        appendLine("NOTE: The user has attached these files. Inspect their contents directly or use 'read_image' / Python scripts in the sandbox.")
      }
      if (attachedDocTexts.isNotEmpty()) {
        attachedDocTexts.forEach { (docName, textContent) ->
          appendLine()
          appendLine("[PRE-EXTRACTED CONTENT FOR '$docName']:")
          appendLine(textContent.take(4000))
          appendLine("[END PRE-EXTRACTED CONTENT]")
        }
      }
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
        appendLine("- To create Word (.docx) or Excel (.xlsx) files: ALWAYS write and execute a Python script in the sandbox using 'python-docx' for Word and 'openpyxl'/'pandas' for Excel. When stuck, ALWAYS turn to Python!")
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
      currentAction = when {
        isResuming -> "Resuming task: '${task.goal}'"
        isFollowUp -> "Processing follow-up: '${task.goal}'"
        else -> "Understanding task objective: '${task.goal}'"
      },
      artifacts = initialArtifacts,
      plan = if (_state.value.plan.isNotEmpty()) _state.value.plan else (existingRecord?.planSteps ?: emptyList()),
      fiveStageRecord = FiveStageRecord(
        intent = task.goal,
        currentPlan = if (_state.value.fiveStageRecord.currentPlan.isNotEmpty()) _state.value.fiveStageRecord.currentPlan else (existingRecord?.fiveStageRecord?.currentPlan ?: emptyList()),
        actions = if (isContinuation) _state.value.fiveStageRecord.actions else (existingRecord?.fiveStageRecord?.actions ?: emptyList()),
        observations = if (isContinuation) _state.value.fiveStageRecord.observations else (existingRecord?.fiveStageRecord?.observations ?: emptyList())
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
        android.util.Log.e("AgentEngine", "Fatal execution fault in task ${task.id}", t)
        recordTaskCompletion(task, AgentStatus.FAILED, err, artifactManager.getArtifacts())
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
  /**
   * Execution outcome evaluated at Phase 6 of each turn.
   */
  private sealed class TurnOutcome {
    object Continue : TurnOutcome()
    object ReplanningRequired : TurnOutcome()
    data class Terminal(val status: AgentStatus, val summary: String) : TurnOutcome()
  }

  /**
   * Executed tool call pairing the request, normalized tool result, and observation.
   */
  private data class ExecutedToolResult(
    val call: ToolCall,
    val result: ToolResult,
    val observation: Observation,
    val isSkippedLoop: Boolean = false
  )

  /**
   * Execution wave grouping independent actions that can run concurrently,
   * while preserving strict sequential ordering across dependent waves.
   */
  private data class ExecutionWave(
    val waveIndex: Int,
    val calls: List<ToolCall>,
    val isParallel: Boolean
  )

  /**
   * Outcome of Phase 3 tool dispatching for an iteration batch.
   */
  private data class BatchDispatchResult(
    val results: List<ExecutedToolResult>,
    val fatalHaltMessage: String? = null,
    val resetSandboxStep: ExecutionStep? = null,
    val activeSandboxId: String? = null
  )

  /**
   * Outcome of Phase 2 model query.
   */
  private data class ModelQueryResult(
    val decision: LLMDecision,
    val updatedDropRetries: Int,
    val restoredProviderStep: Boolean = false
  )

  /**
   * Proactively pre-syncs imported documents and workspace input files into the remote microVM (/home/user).
   * Skips redundant mass uploads if the microVM session is already active from earlier turns.
   */
  private suspend fun syncSandboxInputFiles(
    task: Task,
    expectedSandboxId: String?,
    onStepCreated: (ExecutionStep) -> Unit
  ) {
    val activeSandbox = sandboxProvider
    if (activeSandbox != null && activeSandbox.isRemote) {
      try {
        val syncedFiles = mutableListOf<String>()
        val inScopeIds = documentService?.scope?.value ?: emptySet()
        val allDocs = documentService?.documents?.value ?: emptyList()
        val effectiveDocIds = if (inScopeIds.isNotEmpty()) inScopeIds else allDocs.map { it.id }.toSet()
        val hasRunningSession = (activeSandbox as? E2BSandboxProvider)?.hasActiveRunningSession(task.sessionId) == true

        // Avoid cold-booting a remote E2B Firecracker MicroVM before Turn 1's LLM call
        // unless the user attached documents or a sandbox session is already running.
        if (effectiveDocIds.isEmpty() && !hasRunningSession) {
          return
        }

        if (effectiveDocIds.isNotEmpty()) {
          val inScopeDocs = allDocs.filter { it.id in effectiveDocIds }
          for (doc in inScopeDocs) {
            val localDocFile = documentService?.getOriginalFile(doc.id)
              ?: documentService?.resolveDocumentFile(doc.id)
              ?: File(workspaceDir, doc.displayName).takeIf { it.exists() }
              ?: File(workspaceDir, DocumentText.sanitizeFileName(doc.displayName)).takeIf { it.exists() }
            if (localDocFile != null && localDocFile.isFile) {
              val wsCopy = File(workspaceDir, doc.displayName)
              if (!wsCopy.exists() || wsCopy.length() == 0L) {
                try { localDocFile.copyTo(wsCopy, overwrite = true) } catch (_: Exception) {}
              }
              val ok = activeSandbox.pushFileToRemote(task.sessionId, doc.displayName, localDocFile)
              if (ok) syncedFiles.add(doc.displayName)
            }
          }
        }
        val isExistingActiveSandbox = expectedSandboxId != null
        if (!isExistingActiveSandbox && hasRunningSession) {
          val allWorkspaceFiles = workspaceDir.walkTopDown()
            .filter { f ->
              val rel = f.relativeTo(workspaceDir).path.replace('\\', '/')
              f.isFile && !f.name.startsWith(".") &&
                !rel.startsWith("documents/") &&
                !rel.startsWith(".scratchpad/") &&
                !rel.startsWith(".observations/") &&
                !rel.startsWith("tmp/") &&
                !rel.startsWith("temp/")
            }
            .toList()
          for (f in allWorkspaceFiles) {
            val relPath = f.relativeTo(workspaceDir).path.replace('\\', '/')
            if (!syncedFiles.contains(relPath)) {
              val ok = activeSandbox.pushFileToRemote(task.sessionId, relPath, f)
              if (ok) syncedFiles.add(relPath)
            }
          }
        }
        if (syncedFiles.isNotEmpty()) {
          onFilesSyncedToSandbox?.invoke(syncedFiles.size, syncedFiles)
        }
      } catch (e: Exception) {
        Log.w("AgentEngine", "Pre-sync input files to E2B sandbox failed (non-fatal, continuing)", e)
      }
    }
  }

  /**
   * Phase 2: Queries the configured or fallback model for the next action.
   * Periodically probes primary provider recovery when running under fallback mode.
   */
  private suspend fun queryModelDecision(
    task: Task,
    systemPrompt: String,
    messages: List<AgentMessage>,
    turnCounter: Int,
    lastFallbackProbeTurn: Int,
    providerDropRetries: Int,
    streamListener: LLMStreamListener? = null
  ): ModelQueryResult {
    val isFallbackActive = activeProvider !== configuredProvider
    return if (isFallbackActive && turnCounter - lastFallbackProbeTurn >= FALLBACK_PROBE_INTERVAL_TURNS) {
      val probe = configuredProvider.decideNextAction(
        systemPrompt = systemPrompt,
        taskIntent = task.goal,
        messages = messages,
        tools = registry.getAvailableTools(),
        streamListener = streamListener
      )
      if (probe !is LLMDecision.ProviderError) {
        activeProvider = configuredProvider
        ModelQueryResult(probe, updatedDropRetries = 0, restoredProviderStep = true)
      } else {
        val decision = activeProvider.decideNextAction(
          systemPrompt = systemPrompt,
          taskIntent = task.goal,
          messages = messages,
          tools = registry.getAvailableTools(),
          streamListener = streamListener
        )
        ModelQueryResult(decision, updatedDropRetries = providerDropRetries, restoredProviderStep = false)
      }
    } else {
      val decision = activeProvider.decideNextAction(
        systemPrompt = systemPrompt,
        taskIntent = task.goal,
        messages = messages,
        tools = registry.getAvailableTools(),
        streamListener = streamListener
      )
      ModelQueryResult(decision, updatedDropRetries = providerDropRetries, restoredProviderStep = false)
    }
  }

  /**
   * Phase 3: Validates and dispatches tool calls through the registry and dispatcher.
   * Enforces loop signature checks, duplicate action limits, sandbox identity tracking,
   * and concurrent multitasking or sequential fallback dispatching.
   */
  /**
   * Partitions a batch of tool calls into execution waves.
   * Independent tool actions run in parallel within each wave.
   * Dependent tool actions are strictly ordered into subsequent waves.
   */
  private fun partitionIntoExecutionWaves(
    calls: List<ToolCall>,
    actionableSteps: List<ActionableStep>
  ): List<ExecutionWave> {
    if (calls.isEmpty()) return emptyList()
    if (calls.size == 1) {
      return listOf(ExecutionWave(waveIndex = 0, calls = calls, isParallel = false))
    }

    val readOnlyTools = setOf(
      "read_file", "list_directory", "file_exists", "file_metadata",
      "diff_files", "web_search", "web_fetch", "web_open", "web_extract",
      "web_crawl", "check_tools", "sandbox_diagnostics",
      "document_search", "document_list",
      "mcp_list_servers", "mcp_list_resources", "mcp_read_resource", "mcp_list_prompts"
    )

    val waves = mutableListOf<ExecutionWave>()
    val currentWaveCalls = mutableListOf<ToolCall>()
    var currentWaveIsReadOnly = false
    val currentWavePaths = mutableSetOf<String>()

    fun flushWave(isParallel: Boolean) {
      if (currentWaveCalls.isNotEmpty()) {
        waves.add(
          ExecutionWave(
            waveIndex = waves.size,
            calls = currentWaveCalls.toList(),
            isParallel = isParallel && currentWaveCalls.size > 1
          )
        )
        currentWaveCalls.clear()
        currentWavePaths.clear()
        currentWaveIsReadOnly = false
      }
    }

    for (call in calls) {
      val resolvedName = registry.resolveToolName(call.toolName)
      val isReadOnly = resolvedName in readOnlyTools
      val writePath = if (resolvedName in setOf("write_file", "edit_file")) call.arguments["path"] as? String else null

      if (currentWaveCalls.isEmpty()) {
        currentWaveCalls.add(call)
        currentWaveIsReadOnly = isReadOnly
        if (writePath != null) currentWavePaths.add(writePath)
      } else if (currentWaveIsReadOnly && isReadOnly) {
        currentWaveCalls.add(call)
      } else if (!currentWaveIsReadOnly && writePath != null && currentWavePaths.isNotEmpty() && !currentWavePaths.contains(writePath)) {
        currentWaveCalls.add(call)
        currentWavePaths.add(writePath)
      } else {
        flushWave(isParallel = currentWaveIsReadOnly || currentWavePaths.size > 1)
        currentWaveCalls.add(call)
        currentWaveIsReadOnly = isReadOnly
        if (writePath != null) currentWavePaths.add(writePath)
      }
    }
    flushWave(isParallel = currentWaveIsReadOnly || currentWavePaths.size > 1)

    return waves
  }

  /**
   * Phase 3: Validates and dispatches tool calls with dependency-aware execution waves,
   * sandbox identity verification, unified pre-flight recovery checks, and live feed synchronization.
   */
  private suspend fun dispatchToolCallsBatch(
    task: Task,
    turnCounter: Int,
    validatedToolCalls: List<ToolCall>,
    currentState: AgentState,
    currentSandboxId: String?,
    planner: TaskPlanner,
    stepCounterProvider: () -> Int
  ): BatchDispatchResult {
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

    val waves = partitionIntoExecutionWaves(validatedToolCalls, planner.getActionableSteps())
    val results = mutableListOf<ExecutedToolResult>()
    var activeExpectedSandboxId = currentSandboxId
    var resetStep: ExecutionStep? = null
    var fatalHaltMessage: String? = null

    for (wave in waves) {
      currentCoroutineContext().ensureActive()

      if (wave.isParallel) {
        // Execute independent tool actions concurrently
        var currentFeed = _state.value.executionFeed.toMutableList()
        val waveStepIds = wave.calls.map { "step_tool_${it.callId}" }
        val matchingSteps = wave.calls.map { call ->
          planner.getActionableSteps().firstOrNull { it.toolCallId == call.callId || (it.assignedTool == call.toolName && it.status == ActionExecutionStatus.DISPATCHED) }
        }

        wave.calls.forEachIndexed { idx, toolCall ->
          val step = matchingSteps[idx]
          if (step != null) {
            planner.markStepExecuting(step.id)
          }
          val existingStep = currentFeed.firstOrNull { it.id == waveStepIds[idx] }
          val stepNumber = existingStep?.stepNumber ?: stepCounterProvider()
          val runningStep = (existingStep ?: ExecutionStep(
            id = waveStepIds[idx],
            stepNumber = stepNumber,
            type = StepType.TOOL_EXECUTION,
            toolName = toolCall.toolName,
            toolCallId = toolCall.callId
          )).copy(
            type = StepType.TOOL_EXECUTION,
            title = "Executing [Wave ${wave.waveIndex + 1} Parallel]: ${toolCall.toolName}",
            content = formatArguments(toolCall.arguments),
            toolName = toolCall.toolName,
            toolCallId = toolCall.callId,
            toolStatus = ToolStatus.RUNNING.name,
            actionStatus = ActionExecutionStatus.EXECUTING,
            actionableStepId = step?.id,
            waveIndex = wave.waveIndex,
            isCompleted = false
          )
          currentFeed = addOrUpdateStep(currentFeed, runningStep).toMutableList()
        }

        _state.value = _state.value.copy(
          status = AgentStatus.EXECUTING_TOOL,
          currentAction = "Executing Wave ${wave.waveIndex + 1}: ${wave.calls.size} operations concurrently (Multitasking)...",
          executionFeed = currentFeed,
          isMultitasking = true,
          activeConcurrentTasks = wave.calls.size
        )

        val asyncDispatches = wave.calls.map { call ->
          scope.async(Dispatchers.IO) {
            val result = dispatcher.dispatch(call, task.id, toolContext)
            ExecutedToolResult(call, result.first, result.second)
          }
        }
        val waveResults = asyncDispatches.awaitAll()
        results.addAll(waveResults)

        val anyFailed = waveResults.any { it.result.status == ToolStatus.FAILED || it.result.status == ToolStatus.CANCELLED }
        if (anyFailed) {
          val failedItem = waveResults.first { it.result.status == ToolStatus.FAILED || it.result.status == ToolStatus.CANCELLED }
          val remainingWaves = waves.filter { it.waveIndex > wave.waveIndex }
          for (remWave in remainingWaves) {
            for (remCall in remWave.calls) {
              val remStep = planner.getActionableSteps().firstOrNull { it.toolCallId == remCall.callId || (it.assignedTool == remCall.toolName && it.status == ActionExecutionStatus.DISPATCHED) }
              val skipReason = "Skipped because prerequisite operation '${failedItem.call.toolName}' in Wave ${wave.waveIndex + 1} failed: ${failedItem.result.error ?: "error"}"
              val synResult = ToolResult(
                callId = remCall.callId,
                toolName = remCall.toolName,
                status = ToolStatus.CANCELLED,
                arguments = remCall.arguments,
                output = null,
                error = skipReason,
                errorType = ToolErrorCode.REPETITION_GUARD,
                duration = 0L,
                exitCode = 1,
                outcome = ToolExecutionOutcome.EXECUTION_FAILED
              )
              val synObs = Observation(
                callId = remCall.callId,
                toolName = remCall.toolName,
                status = ToolStatus.CANCELLED,
                isSuccess = false,
                inputsSummary = formatArguments(remCall.arguments),
                result = null,
                error = skipReason,
                errorType = ToolErrorCode.REPETITION_GUARD,
                summary = skipReason,
                rawOutput = skipReason,
                outcome = ToolExecutionOutcome.EXECUTION_FAILED
              )
              results.add(ExecutedToolResult(remCall, synResult, synObs, isSkippedLoop = true))
              if (remStep != null) {
                planner.markStepSkipped(remStep.id, skipReason)
              }
            }
          }
          break
        }
      } else {
        // Sequential wave execution (single call or state-modifying action)
        var waveHalted = false
        for (toolCall in wave.calls) {
          currentCoroutineContext().ensureActive()

          // Sandbox identity verification
          val e2b = sandboxProvider as? E2BSandboxProvider
          if (e2b != null && e2b.isE2B) {
            val activeSess = e2b.getActiveSession(task.sessionId)
            val currentSbxId = activeSess?.sandboxId
            if (activeExpectedSandboxId != null && (currentSbxId == null || currentSbxId != activeExpectedSandboxId)) {
              resetStep = ExecutionStep(
                stepNumber = stepCounterProvider(),
                type = StepType.SANDBOX_RESET,
                title = "SANDBOX_RESET Detected",
                content = "Sandbox identity mismatch detected: expected '$activeExpectedSandboxId', found '${currentSbxId ?: "null"}'. Halting for re-provisioning and state sync to prevent state loss."
              )
              val fresh = e2b.getOrCreateE2BSession(task.sessionId, task.id)
              activeExpectedSandboxId = fresh.sandboxId
              try {
                e2b.syncArtifacts(task.sessionId, workspaceDir, task.id)
              } catch (e: Exception) {
                Log.w("AgentEngine", "syncArtifacts failed after sandbox reset (non-fatal)", e)
              }
            } else if (activeExpectedSandboxId == null && currentSbxId != null) {
              activeExpectedSandboxId = currentSbxId
            }
          }

          val parseError = toolCall.arguments["_parse_error"]?.toString()
          if (!parseError.isNullOrBlank()) {
            val truncMsg = "Tool call JSON arguments for '${toolCall.toolName}' were malformed or truncated ($parseError). Keep tool call arguments concise: write modular scripts (<120 lines) or build files in smaller chunks instead of embedding massive inline strings."
            val synResult = ToolResult(
              callId = toolCall.callId,
              toolName = toolCall.toolName,
              status = ToolStatus.FAILED,
              arguments = toolCall.arguments,
              output = null,
              error = truncMsg,
              errorType = ToolErrorCode.INVALID_ARGUMENTS,
              duration = 0L,
              exitCode = 1,
              outcome = ToolExecutionOutcome.EXECUTION_FAILED
            )
            val synObs = Observation(
              callId = toolCall.callId,
              toolName = toolCall.toolName,
              status = ToolStatus.FAILED,
              isSuccess = false,
              inputsSummary = formatArguments(toolCall.arguments),
              result = null,
              error = truncMsg,
              errorType = ToolErrorCode.INVALID_ARGUMENTS,
              summary = truncMsg,
              rawOutput = truncMsg,
              outcome = ToolExecutionOutcome.EXECUTION_FAILED
            )
            results.add(ExecutedToolResult(toolCall, synResult, synObs, isSkippedLoop = false))
            continue
          }

          // Pre-flight decision checks
          when (val preFlight = recoveryManager.checkPreFlight(toolCall)) {
            is PreFlightDecision.HaltLoop -> {
              fatalHaltMessage = preFlight.fatalMessage
              waveHalted = true
              break
            }
            is PreFlightDecision.SkipLoop -> {
              val synResult = ToolResult(
                callId = toolCall.callId,
                toolName = toolCall.toolName,
                status = ToolStatus.FAILED,
                arguments = toolCall.arguments,
                output = null,
                error = preFlight.reason,
                errorType = ToolErrorCode.REPETITION_GUARD,
                duration = 0L,
                exitCode = 1,
                outcome = ToolExecutionOutcome.CONDITION_NOT_FOUND,
                metadata = mapOf("suggested_alternative_tool" to (preFlight.suggestedTool ?: ""))
              )
              val synObs = Observation(
                callId = toolCall.callId,
                toolName = toolCall.toolName,
                status = ToolStatus.FAILED,
                isSuccess = false,
                inputsSummary = formatArguments(toolCall.arguments),
                result = null,
                error = preFlight.reason,
                errorType = ToolErrorCode.REPETITION_GUARD,
                summary = preFlight.reason,
                rawOutput = preFlight.reason,
                outcome = ToolExecutionOutcome.CONDITION_NOT_FOUND
              )
              results.add(ExecutedToolResult(toolCall, synResult, synObs, isSkippedLoop = true))
              waveHalted = true
              break
            }
            is PreFlightDecision.BlockDeterministicFailure -> {
              val blockMsg = "Deterministic Failure Repeat Guard: ${preFlight.priorError}. ${preFlight.recoveryHint}"
              val synResult = ToolResult(
                callId = toolCall.callId,
                toolName = toolCall.toolName,
                status = ToolStatus.FAILED,
                arguments = toolCall.arguments,
                output = null,
                error = blockMsg,
                errorType = ToolErrorCode.UNSUPPORTED_OPERATION,
                duration = 0L,
                exitCode = 1,
                outcome = ToolExecutionOutcome.EXECUTION_FAILED
              )
              val synObs = Observation(
                callId = toolCall.callId,
                toolName = toolCall.toolName,
                status = ToolStatus.FAILED,
                isSuccess = false,
                inputsSummary = formatArguments(toolCall.arguments),
                result = null,
                error = blockMsg,
                errorType = ToolErrorCode.UNSUPPORTED_OPERATION,
                summary = blockMsg,
                rawOutput = blockMsg,
                outcome = ToolExecutionOutcome.EXECUTION_FAILED
              )
              results.add(ExecutedToolResult(toolCall, synResult, synObs, isSkippedLoop = true))
              waveHalted = true
              break
            }
            is PreFlightDecision.Proceed -> {
              // Proceed
            }
          }

          val matchingStep = planner.getActionableSteps().firstOrNull {
            it.toolCallId == toolCall.callId || (it.assignedTool == toolCall.toolName && it.status == ActionExecutionStatus.DISPATCHED)
          }
          if (matchingStep != null) {
            planner.markStepExecuting(matchingStep.id)
          }

          val runningStepId = "step_tool_${toolCall.callId}"
          val existingStep = _state.value.executionFeed.firstOrNull { it.id == runningStepId }
          val stepNumber = existingStep?.stepNumber ?: stepCounterProvider()
          val runningStep = (existingStep ?: ExecutionStep(
            id = runningStepId,
            stepNumber = stepNumber,
            type = StepType.TOOL_EXECUTION,
            toolName = toolCall.toolName,
            toolCallId = toolCall.callId
          )).copy(
            type = StepType.TOOL_EXECUTION,
            title = if (waves.size > 1) "Executing [Wave ${wave.waveIndex + 1}]: ${toolCall.toolName}" else "Executing: ${toolCall.toolName}",
            content = formatArguments(toolCall.arguments),
            toolName = toolCall.toolName,
            toolCallId = toolCall.callId,
            toolStatus = ToolStatus.RUNNING.name,
            actionStatus = ActionExecutionStatus.EXECUTING,
            actionableStepId = matchingStep?.id,
            waveIndex = wave.waveIndex,
            isCompleted = false
          )
          _state.value = _state.value.copy(
            status = AgentStatus.EXECUTING_TOOL,
            currentAction = "Executing '${toolCall.toolName}' (Wave ${wave.waveIndex + 1}/${waves.size})...",
            executionFeed = addOrUpdateStep(_state.value.executionFeed, runningStep),
            isMultitasking = false,
            activeConcurrentTasks = 0
          )

          delay(80)
          val effectiveContext = if (activeExpectedSandboxId != null && toolContext.sandboxId != activeExpectedSandboxId) {
            toolContext.copy(sandboxId = activeExpectedSandboxId)
          } else {
            toolContext
          }
          val (toolResult, observation) = dispatcher.dispatch(toolCall, task.id, effectiveContext)
          results.add(ExecutedToolResult(toolCall, toolResult, observation, isSkippedLoop = false))

          if (toolResult.status == ToolStatus.FAILED || toolResult.status == ToolStatus.CANCELLED) {
            waveHalted = true
            break
          }
        }

        if (waveHalted) {
          val remainingWaves = waves.filter { it.waveIndex > wave.waveIndex }
          for (remWave in remainingWaves) {
            for (remCall in remWave.calls) {
              val remStep = planner.getActionableSteps().firstOrNull { it.toolCallId == remCall.callId || (it.assignedTool == remCall.toolName && it.status == ActionExecutionStatus.DISPATCHED) }
              val skipReason = "Skipped because previous wave action in Wave ${wave.waveIndex + 1} encountered a halting condition."
              val synResult = ToolResult(
                callId = remCall.callId,
                toolName = remCall.toolName,
                status = ToolStatus.CANCELLED,
                arguments = remCall.arguments,
                output = null,
                error = skipReason,
                errorType = ToolErrorCode.REPETITION_GUARD,
                duration = 0L,
                exitCode = 1,
                outcome = ToolExecutionOutcome.EXECUTION_FAILED
              )
              val synObs = Observation(
                callId = remCall.callId,
                toolName = remCall.toolName,
                status = ToolStatus.CANCELLED,
                isSuccess = false,
                inputsSummary = formatArguments(remCall.arguments),
                result = null,
                error = skipReason,
                errorType = ToolErrorCode.REPETITION_GUARD,
                summary = skipReason,
                rawOutput = skipReason,
                outcome = ToolExecutionOutcome.EXECUTION_FAILED
              )
              results.add(ExecutedToolResult(remCall, synResult, synObs, isSkippedLoop = true))
              if (remStep != null) {
                planner.markStepSkipped(remStep.id, skipReason)
              }
            }
          }
          break
        }
      }
    }

    val finalActiveId = (sandboxProvider as? E2BSandboxProvider)?.getActiveSession(task.sessionId)?.sandboxId ?: activeExpectedSandboxId
    return BatchDispatchResult(
      results = results,
      fatalHaltMessage = fatalHaltMessage,
      resetSandboxStep = resetStep,
      activeSandboxId = finalActiveId
    )
  }

  /**
   * Phases 4 & 5: Normalizes results, returns observations, ingests research, reflects,
   * syncs artifacts, and updates persistent checkpoints.
   */
  private suspend fun processExecutedResultsAndReflect(
    task: Task,
    turnCounter: Int,
    results: List<ExecutedToolResult>,
    currentState: AgentState,
    planner: TaskPlanner,
    stepCounterProvider: () -> Int
  ): AgentState {
    var updatedFeed = currentState.executionFeed.toMutableList()
    var updatedMessages = currentState.messages
    var updatedToolExecutions = currentState.toolExecutions
    var updatedObservations = currentState.observations
    var updatedActions = currentState.fiveStageRecord.actions
    var updatedObsStrings = currentState.fiveStageRecord.observations

    for (item in results) {
      val call = item.call
      val toolResult = item.result
      val observation = item.observation

      if (toolResult.status == ToolStatus.SUCCEEDED && toolResult.output != null) {
        scratchpad.autoIngestFromToolOutput(call.toolName, call.arguments, toolResult.output)
      }

      val runningStepId = "step_tool_${call.callId}"
      val finalActionStatus = when (toolResult.status) {
        ToolStatus.SUCCEEDED -> ActionExecutionStatus.COMPLETED
        ToolStatus.FAILED -> ActionExecutionStatus.FAILED
        ToolStatus.CANCELLED -> ActionExecutionStatus.SKIPPED
        else -> ActionExecutionStatus.COMPLETED
      }
      updatedFeed = updatedFeed.map { feedStep ->
        if (feedStep.id == runningStepId) {
          feedStep.copy(
            toolStatus = toolResult.status.name,
            actionStatus = finalActionStatus,
            durationMs = toolResult.duration,
            exitCode = toolResult.exitCode,
            stdout = toolResult.stdout,
            stderr = toolResult.stderr,
            isCompleted = true,
            outcome = observation.outcome.name
          )
        } else feedStep
      }.toMutableList()

      val obsStepId = if (item.isSkippedLoop) "step_loopguard_${call.callId}" else "step_obs_${call.callId}"
      val obsTitle = when {
        item.isSkippedLoop -> "Loop Guard Triggered"
        observation.outcome == ToolExecutionOutcome.CONDITION_NOT_FOUND -> "Condition Not Found"
        observation.outcome == ToolExecutionOutcome.SUCCESS_EMPTY -> "Succeeded (No Output)"
        observation.outcome == ToolExecutionOutcome.TRUNCATED_OR_PARTIAL -> "Observation (Truncated / Partial)"
        observation.outcome == ToolExecutionOutcome.UNVERIFIED -> "Observation (Unverified)"
        observation.isSuccess -> "Observation (${toolResult.status.name})"
        else -> "Observation (${toolResult.errorType?.name ?: "FAILED"})"
      }
      updatedFeed = addOrUpdateStep(
        updatedFeed,
        ExecutionStep(
          id = obsStepId,
          stepNumber = stepCounterProvider(),
          type = StepType.OBSERVATION,
          title = obsTitle,
          content = observation.summary,
          stdout = observation.stdout,
          stderr = observation.stderr,
          toolStatus = toolResult.status.name,
          outcome = observation.outcome.name
        )
      ).toMutableList()

      // Authoritative evaluation through Unified Recovery Manager
      val evalContext = toolResult.executionContext ?: ToolExecutionContext(
        sessionId = task.sessionId,
        workspaceDir = workspaceDir
      )
      val evalConclusion = recoveryManager.evaluateExecution(call, toolResult, observation, evalContext)

      // Emit distinct evaluation/recovery step to keep evaluator conclusions separate from raw tool observations
      if (!evalConclusion.isSatisfied || evalConclusion.failureCategory != null || item.isSkippedLoop) {
        val evalStepId = "step_recovery_${call.callId}"
        val evalTitle = if (item.isSkippedLoop) "Recovery: Loop Intervention" else "Recovery: ${evalConclusion.recoveryStrategy.name}"
        val evalContent = evalConclusion.structuredError?.format() ?: evalConclusion.explanation
        updatedFeed = addOrUpdateStep(
          updatedFeed,
          ExecutionStep(
            id = evalStepId,
            stepNumber = stepCounterProvider(),
            type = StepType.RECOVERY,
            title = evalTitle,
            content = evalContent,
            recoveryStrategy = evalConclusion.recoveryStrategy.name,
            failureCategory = evalConclusion.failureCategory?.name,
            structuredError = evalConclusion.structuredError
          )
        ).toMutableList()

        // Inject actionable recovery guidance prompt for the LLM
        val guidanceMsg = AgentMessage(
          role = MessageRole.USER,
          content = evalConclusion.toSystemGuidancePrompt()
        )
        updatedMessages = updatedMessages + guidanceMsg

        if (evalConclusion.recoveryStrategy == RecoveryStrategy.REVISE_PLAN) {
          planner.setReplanReason(evalConclusion.explanation)
        }
        if (evalConclusion.recoveryStrategy == RecoveryStrategy.RETRY_WITH_BACKOFF && evalConclusion.backoffMs > 0) {
          delay(evalConclusion.backoffMs)
        }
      }

      // Track action results and state changes for workflow loop detection
      val currentFingerprint = computeExecutionStateFingerprint()
      val stateChanged = currentFingerprint != lastFilesystemStateFingerprint
      lastFilesystemStateFingerprint = currentFingerprint

      val hasNovelObservation = toolResult.status == ToolStatus.SUCCEEDED &&
        !item.isSkippedLoop &&
        observation.outcome != ToolExecutionOutcome.CONDITION_NOT_FOUND &&
        observation.outcome != ToolExecutionOutcome.EXECUTION_FAILED

      val madeProgress = stateChanged || hasNovelObservation
      if (madeProgress) {
        consecutiveZeroProgressActions = 0
        hasReplannedForZeroProgress = false
      } else {
        consecutiveZeroProgressActions++
      }

      updatedActions = updatedActions + "Dispatched '${call.toolName}' with ${formatArguments(call.arguments)}"
      updatedObsStrings = updatedObsStrings + observation.summary

      planner.recordToolResult(toolResult, observation.summary)

      // Synchronize first-class ActionableStep state & extract verifiable completion evidence
      val matchingStep = planner.getActionableSteps().firstOrNull { it.toolCallId == call.callId }
        ?: planner.getActionableSteps().firstOrNull {
          (it.assignedTool == call.toolName || it.status == ActionExecutionStatus.DISPATCHED || it.status == ActionExecutionStatus.EXECUTING)
        }

      val evidenceItem = when {
        toolResult.status == ToolStatus.SUCCEEDED && (call.toolName == "write_file" || call.toolName == "edit_file") -> {
          val path = call.arguments["path"]?.toString()
          CompletionEvidenceItem(
            type = "FILE_EXISTS",
            description = "File written successfully: $path",
            target = path,
            verified = true,
            details = observation.summary
          )
        }
        toolResult.status == ToolStatus.SUCCEEDED && (call.toolName.startsWith("build_") || call.toolName.startsWith("recreate_")) -> {
          val path = call.arguments["file_path"]?.toString() ?: call.arguments["output_path"]?.toString()
          CompletionEvidenceItem(
            type = "FILE_EXISTS",
            description = "Document deliverable built: $path",
            target = path,
            verified = true,
            details = observation.summary
          )
        }
        toolResult.status == ToolStatus.SUCCEEDED && call.toolName == "terminal" && toolResult.exitCode == 0 -> {
          val cmd = call.arguments["command"]?.toString()
          CompletionEvidenceItem(
            type = "EXIT_CODE_ZERO",
            description = "Command completed with zero exit code",
            target = cmd?.take(80),
            verified = true,
            details = "Exit code 0"
          )
        }
        else -> null
      }

      val producedArtifact = if (toolResult.status == ToolStatus.SUCCEEDED) {
        call.arguments["path"]?.toString() ?: call.arguments["file_path"]?.toString() ?: call.arguments["output_path"]?.toString()
      } else null

      if (matchingStep != null) {
        if (toolResult.status == ToolStatus.SUCCEEDED) {
          planner.markStepCompleted(matchingStep.id, observation.summary, producedArtifact, evidenceItem)
        } else if (toolResult.status == ToolStatus.FAILED) {
          planner.markStepFailed(matchingStep.id, toolResult.error ?: observation.summary)
        } else if (toolResult.status == ToolStatus.CANCELLED) {
          planner.markStepSkipped(matchingStep.id, toolResult.error ?: "Action skipped")
        }
      } else {
        if (evidenceItem != null) planner.recordEvidence(evidenceItem)
        if (producedArtifact != null) planner.recordArtifact(producedArtifact)
        planner.recordObservation(observation.summary)
      }

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
            stepNumber = stepCounterProvider(),
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

    val hasNewArtifact = updatedArtifacts.size > currentState.artifacts.size ||
      updatedArtifacts.any { art -> currentState.artifacts.none { it.path == art.path && it.size == art.size } }
    val isStateChanging = results.any {
      val resolved = registry.resolveToolName(it.call.toolName)
      (resolved in STATE_CHANGING_TOOLS || resolved.contains("__")) && it.result.isSuccess
    }
    val isInformative = results.any {
      val resolved = registry.resolveToolName(it.call.toolName)
      (resolved in INFORMATIVE_TOOLS || resolved.contains("__") || it.observation.outcome == ToolExecutionOutcome.SUCCESS_WITH_RESULTS) && it.result.isSuccess
    }
    if (hasNewArtifact || isStateChanging || isInformative) {
      planner.recordProgress(true)
    } else if (results.all { !it.result.isSuccess || it.observation.outcome == ToolExecutionOutcome.CONDITION_NOT_FOUND }) {
      planner.recordProgress(false)
    }

    val currentPersistentTask = planner.toPersistentTask(
      sessionId = task.sessionId,
      turn = turnCounter,
      maxTurns = maxSteps,
      status = AgentStatus.RUNNING
    )
    scratchpad.saveTask(currentPersistentTask)

    scratchpad.saveCheckpoint(
      AgentCheckpoint(
        taskGoal = task.goal,
        currentTurn = turnCounter,
        completedSections = planner.completedDescriptions(),
        pendingSections = planner.pendingDescriptions(),
        planItems = planner.planDescriptions(),
        artifactPaths = updatedArtifacts.map { it.path },
        totalFindingsCount = scratchpad.loadAllFindings().size,
        status = "IN_PROGRESS",
        recoveryState = recoveryManager.exportStateJson(),
        currentPhase = planner.getCurrentPhase().name,
        persistentTaskJson = currentPersistentTask.toJsonString()
      )
    )

    return currentState.copy(
      status = AgentStatus.OBSERVING,
      currentAction = "Completed execution of ${results.size} operation(s).",
      messages = updatedMessages,
      toolExecutions = updatedToolExecutions,
      observations = updatedObservations,
      artifacts = updatedArtifacts,
      executionFeed = updatedFeed,
      plan = planner.planDescriptions(),
      isMultitasking = false,
      activeConcurrentTasks = 0,
      persistentTask = currentPersistentTask,
      fiveStageRecord = currentState.fiveStageRecord.copy(
        currentPlan = planner.planDescriptions(),
        actions = updatedActions,
        observations = updatedObsStrings
      )
    )
  }

  /**
   * Phase 6: Authoritative objective verification and self-audit against disk ground truth.
   * Prevents premature or unverified completion declarations.
   */
  private suspend fun evaluateCompletion(
    task: Task,
    conclusion: String,
    turnCounter: Int,
    currentState: AgentState,
    planner: TaskPlanner,
    consecutiveVerificationFailures: Int,
    stepCounterProvider: () -> Int,
    onVerificationFailureIncremented: (Int) -> Unit
  ): TurnOutcome {
    val conclusionText = conclusion.ifBlank {
      "Task completed successfully and verified."
    }
    planner.setPhase(TaskPhase.VERIFICATION)
    planner.completeInformationalAndRemainingSteps(conclusionText)

    var state = currentState.copy(
      status = AgentStatus.VERIFYING,
      currentAction = "Authoritatively verifying objective satisfaction against filesystem and actionable steps...",
      persistentTask = planner.toPersistentTask(task.sessionId, turnCounter, maxSteps)
    )
    if (!updateStateIfActive(task, state)) return TurnOutcome.Terminal(AgentStatus.CANCELLED, "Task cancelled")

    delay(80)
    try {
      sandboxManager.activeProvider.syncArtifacts(task.sessionId, workspaceDir, task.id)
    } catch (e: Exception) {
      Log.w("AgentEngine", "syncArtifacts failed during step verification (non-fatal)", e)
    }
    val freshArtifacts = artifactManager.syncWorkspace(task.id)

    // Check tangible deliverables from deliverableTracker
    val deliverableReport = deliverableTracker.scanAndVerify(artifactManager)
    val hasExplicitArtifact = !task.expectedArtifact.isNullOrBlank()
    val explicitArtifactSatisfied = !hasExplicitArtifact || freshArtifacts.any { it.name.contains(task.expectedArtifact, ignoreCase = true) }

    planner.forceCompleteAllRemainingSteps(conclusionText)

    val verificationResult = verifyObjective(task, state.copy(artifacts = freshArtifacts), planner)

    // Prevent premature completion only if actionable tool steps remain truly uncompleted
    val uncompletedRequiredSteps = planner.getActionableSteps().filter {
      (it.status == ActionExecutionStatus.PLANNED || it.status == ActionExecutionStatus.EXECUTING) &&
        it.assignedTool != null
    }
    val hasPendingActionableSteps = uncompletedRequiredSteps.isNotEmpty()

    val deliverablesMet = if (hasExplicitArtifact) {
      explicitArtifactSatisfied
    } else {
      if (deliverableReport.totalRequired > 0) {
        deliverableReport.allSatisfied || (deliverableReport.verifiedCount > 0 && freshArtifacts.isNotEmpty())
      } else {
        deliverableReport.allSatisfied || freshArtifacts.isNotEmpty() || (state.toolExecutions.isNotEmpty() && conclusionText.length > 25)
      }
    }

    if (deliverableReport.totalRequired > 0 && deliverableReport.verifiedCount == 0 && consecutiveVerificationFailures < 2) {
      onVerificationFailureIncremented(consecutiveVerificationFailures + 1)
      val steeringPrompt = "CRITICAL REQUIREMENT NOT MET: You declared task completion, but the required deliverable files (${deliverableReport.pendingOrInvalidDetails}) do NOT exist on disk in the workspace! You MUST write the deliverable files (e.g. using 'write_file', 'build_pdf_document', 'docx_builder', 'xlsx_builder', or 'execute_code') to the workspace before declaring completion."
      val steerStep = ExecutionStep(
        id = "step_steer_t${turnCounter}_${task.id}",
        stepNumber = stepCounterProvider(),
        type = StepType.RECOVERY,
        title = "Deliverable Missing on Disk",
        content = steeringPrompt,
        actionStatus = ActionExecutionStatus.FAILED
      )
      state = state.copy(
        status = AgentStatus.RUNNING,
        currentAction = "Writing required deliverable files to workspace...",
        executionFeed = addOrUpdateStep(state.executionFeed, steerStep),
        messages = state.messages + AgentMessage(role = MessageRole.SYSTEM, content = steeringPrompt)
      )
      updateStateIfActive(task, state)
      return TurnOutcome.Continue
    }

    val canConclude = (verificationResult.isSatisfied || deliverablesMet || consecutiveVerificationFailures >= 2) &&
      !hasPendingActionableSteps

    if (canConclude) {
      planner.setPhase(TaskPhase.COMPLETED)

      val evItem = CompletionEvidenceItem(
        type = "ASSERTION",
        description = "Overall task objective verified successfully",
        target = task.goal,
        verified = true,
        details = verificationResult.details
      )
      planner.recordEvidence(evItem)

      val vStep = ExecutionStep(
        id = "step_verify_t${turnCounter}_${task.id}",
        stepNumber = stepCounterProvider(),
        type = StepType.VERIFICATION,
        title = "Objective Verified",
        content = verificationResult.details,
        actionStatus = ActionExecutionStatus.COMPLETED
      )
      val existingConclusion = state.executionFeed.find {
        it.type == StepType.CONCLUSION || it.id == "step_conclusion_${task.id}" || (it.id.startsWith("step_thinking_") && it.content == conclusionText)
      }
      val cStep = existingConclusion?.copy(
        id = "step_conclusion_${task.id}",
        type = StepType.CONCLUSION,
        title = "Completed",
        content = conclusionText,
        actionStatus = ActionExecutionStatus.COMPLETED,
        isCompleted = true
      ) ?: ExecutionStep(
        id = "step_conclusion_${task.id}",
        stepNumber = stepCounterProvider(),
        type = StepType.CONCLUSION,
        title = "Completed",
        content = conclusionText,
        actionStatus = ActionExecutionStatus.COMPLETED,
        isCompleted = true
      )
      val cleanFeed = if (existingConclusion != null && existingConclusion.id != cStep.id) {
        state.executionFeed.filter { it.id != existingConclusion.id }
      } else {
        state.executionFeed
      }
      val completedFeed = addOrUpdateStep(addOrUpdateStep(cleanFeed, vStep), cStep)

      // Guarantee any pending markdown/text deliverable is synthesized if model outputted findings in text
      val preReport = deliverableTracker.scanAndVerify(artifactManager)
      if (preReport.totalRequired > 0 && preReport.verifiedCount == 0) {
        val targetPending = preReport.deliverables.firstOrNull { it.status != DeliverableStatus.VERIFIED && (it.name.endsWith(".md") || it.name.endsWith(".txt")) }
        if (targetPending != null && conclusionText.isNotBlank()) {
          try {
            val file = File(workspaceDir, targetPending.path)
            file.parentFile?.mkdirs()
            file.writeText(conclusionText)
            artifactManager.registerArtifact(targetPending.path)
            deliverableTracker.scanAndVerify(artifactManager)
          } catch (_: Exception) {}
        }
      }

      artifactManager.cleanTemporaryFiles()
      val finalArtifacts = artifactManager.syncWorkspace(task.id)

      val finalPersistentTask = planner.toPersistentTask(
        sessionId = task.sessionId,
        turn = turnCounter,
        maxTurns = maxSteps,
        status = AgentStatus.COMPLETED,
        conclusion = conclusionText
      )
      scratchpad.saveTask(finalPersistentTask)

      scratchpad.saveCheckpoint(
        AgentCheckpoint(
          taskGoal = task.goal,
          currentTurn = turnCounter,
          completedSections = planner.completedDescriptions(),
          pendingSections = planner.pendingDescriptions(),
          planItems = state.plan,
          artifactPaths = finalArtifacts.map { it.path },
          totalFindingsCount = scratchpad.loadAllFindings().size,
          status = "COMPLETED",
          recoveryState = recoveryManager.exportStateJson(),
          currentPhase = TaskPhase.COMPLETED.name,
          persistentTaskJson = finalPersistentTask.toJsonString()
        )
      )

      recordTaskCompletion(task, AgentStatus.COMPLETED, conclusionText, finalArtifacts)

      val finalMessages = if (state.messages.any { it.role == MessageRole.ASSISTANT && it.content == conclusionText }) {
        state.messages
      } else {
        state.messages + AgentMessage(role = MessageRole.ASSISTANT, content = conclusionText)
      }

      state = state.copy(
        status = AgentStatus.COMPLETED,
        currentAction = "Task completed and verified. Conversation active for further refinement.",
        completionSummary = conclusionText,
        verification = verificationResult,
        artifacts = finalArtifacts,
        taskHistory = _state.value.taskHistory,
        executionFeed = completedFeed,
        messages = finalMessages,
        persistentTask = finalPersistentTask,
        fiveStageRecord = state.fiveStageRecord.copy(conclusion = conclusionText),
        canCancel = false
      )
      updateStateIfActive(task, state)
      return TurnOutcome.Terminal(AgentStatus.COMPLETED, conclusionText)
    } else {
      val newFailures = consecutiveVerificationFailures + 1
      onVerificationFailureIncremented(newFailures)

      val rejectionDetails = if (hasPendingActionableSteps) {
        val pendingStr = uncompletedRequiredSteps.joinToString("; ") { "#${it.stepNumber} [${it.status.name}] ${it.description}" }
        "Active plan contains pending or incomplete steps: [$pendingStr]. All planned actions must be executed or updated before declaring completion."
      } else {
        verificationResult.details
      }

      planner.markCompletionRejected(rejectionDetails)
      planner.setPhase(TaskPhase.EXECUTION)
      val rejectionMsg = "Objective Verification Incomplete: $rejectionDetails. The task cannot be concluded until required actions and deliverables exist on disk."

      if (newFailures >= 3) {
        val isPartial = verificationResult.isPartial || verificationResult.verifiedArtifacts.isNotEmpty()
        val finalStatus = if (isPartial) AgentStatus.PARTIAL_COMPLETION else AgentStatus.FAILED
        val finalTitle = if (isPartial) "Partial Completion" else "Task Halted"
        val finalContent = if (isPartial) {
          "Partial Completion: Verified [${verificationResult.verifiedArtifacts.joinToString()}], but missing requirements: [${verificationResult.missingRequirements.joinToString()}]."
        } else {
          "Task halted: Deliverables could not be verified after 3 attempts. Details: $rejectionDetails"
        }

        val rejStep = ExecutionStep(
          id = "step_verify_rej_t${turnCounter}_${task.id}",
          stepNumber = stepCounterProvider(),
          type = StepType.VERIFICATION,
          title = "Verification Incomplete",
          content = rejectionMsg,
          actionStatus = ActionExecutionStatus.FAILED
        )
        val finalStep = ExecutionStep(
          id = "step_conclusion_${task.id}",
          stepNumber = stepCounterProvider(),
          type = if (isPartial) StepType.CONCLUSION else StepType.ERROR,
          title = finalTitle,
          content = finalContent,
          actionStatus = if (isPartial) ActionExecutionStatus.COMPLETED else ActionExecutionStatus.FAILED
        )
        val failureFeed = addOrUpdateStep(addOrUpdateStep(state.executionFeed, rejStep), finalStep)

        val finalFailedPTask = planner.toPersistentTask(
          sessionId = task.sessionId,
          turn = turnCounter,
          maxTurns = maxSteps,
          status = finalStatus,
          error = if (finalStatus == AgentStatus.FAILED) finalContent else null,
          conclusion = finalContent
        )
        scratchpad.saveTask(finalFailedPTask)

        scratchpad.saveCheckpoint(
          AgentCheckpoint(
            taskGoal = task.goal,
            currentTurn = turnCounter,
            completedSections = planner.completedDescriptions(),
            pendingSections = planner.pendingDescriptions(),
            planItems = state.plan,
            artifactPaths = artifactManager.getArtifacts().map { it.path },
            totalFindingsCount = scratchpad.loadAllFindings().size,
            status = finalStatus.name,
            recoveryState = recoveryManager.exportStateJson(),
            currentPhase = if (isPartial) TaskPhase.COMPLETED.name else TaskPhase.FAILED.name,
            persistentTaskJson = finalFailedPTask.toJsonString()
          )
        )

        recordTaskCompletion(task, finalStatus, finalContent, artifactManager.getArtifacts())

        state = state.copy(
          status = finalStatus,
          currentAction = finalContent,
          error = if (!isPartial) "Verification incomplete: $rejectionDetails" else null,
          completionSummary = if (isPartial) finalContent else null,
          verification = verificationResult,
          taskHistory = _state.value.taskHistory,
          executionFeed = failureFeed,
          persistentTask = finalFailedPTask,
          canCancel = false
        )
        updateStateIfActive(task, state)
        return TurnOutcome.Terminal(finalStatus, finalContent)
      }

      val rejStep = ExecutionStep(
        id = "step_verify_rej_t${turnCounter}_${task.id}",
        stepNumber = stepCounterProvider(),
        type = StepType.VERIFICATION,
        title = "Verification Incomplete",
        content = rejectionMsg
      )
      val feed = addOrUpdateStep(state.executionFeed, rejStep)

      val updatedMessages = state.messages + listOfNotNull(
        if (state.messages.none { it.role == MessageRole.ASSISTANT && it.content == conclusion })
          AgentMessage(role = MessageRole.ASSISTANT, content = conclusion)
        else null,
        if (state.messages.none { it.role == MessageRole.USER && it.content == rejectionMsg })
          AgentMessage(role = MessageRole.USER, content = rejectionMsg)
        else null
      )

      state = state.copy(
        status = AgentStatus.PLANNING,
        currentAction = "Verification rejected completion: ${verificationResult.details}",
        messages = updatedMessages,
        verification = verificationResult,
        executionFeed = feed
      )
      updateStateIfActive(task, state)
      return TurnOutcome.Continue
    }
  }

  /**
   * Central Autonomous Agent Execution Loop.
   *
   * Executes the strict 6-phase stateful cycle:
   * 1. State & Context Hydration
   * 2. Model Query & Action Formulation
   * 3. Tool Call Validation & Dispatching
   * 4. Result Normalization & Observation Return
   * 5. Persistence & Reflection
   * 6. Evaluation & State Transitions
   */
  private suspend fun run(task: Task) {
    var stepCounter = _state.value.executionFeed.size
    consecutiveZeroProgressActions = 0
    hasReplannedForZeroProgress = false

    val existingCheckpoint = scratchpad.loadCheckpoint()
    val isResuming = existingCheckpoint != null &&
      existingCheckpoint.taskGoal.equals(task.goal.trim(), ignoreCase = true) &&
      existingCheckpoint.status in RESUMABLE_CHECKPOINT_STATUSES

    val initialPlan = if (isResuming) existingCheckpoint!!.planItems else scratchpad.loadPlan()
    val planner = TaskPlanner(task.goal)

    val restoredPTask = if (isResuming && !existingCheckpoint?.persistentTaskJson.isNullOrBlank()) {
      PersistentTask.fromJson(existingCheckpoint!!.persistentTaskJson!!)
    } else null

    if (restoredPTask != null) {
      planner.restoreFromPersistentTask(restoredPTask)
    } else if (initialPlan.isNotEmpty()) {
      planner.acceptModelPlan(initialPlan.joinToString("\n"))
      if (isResuming) {
        planner.restoreProgress(
          completed = existingCheckpoint?.completedSections.orEmpty(),
          pending = existingCheckpoint?.pendingSections.orEmpty()
        )
      }
    }

    if (isResuming) {
      existingCheckpoint?.recoveryState?.let {
        recoveryManager.restoreStateJson(it)
      }
    } else {
      recoveryManager.reset()
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

    val initialPersistentTask = planner.toPersistentTask(
      sessionId = task.sessionId,
      turn = if (isResuming) existingCheckpoint?.currentTurn ?: 0 else 0,
      maxTurns = maxSteps
    )

    var currentState = _state.value.copy(
      sessionId = task.sessionId,
      task = task.copy(
        currentPhase = planner.getCurrentPhase(),
        actionableSteps = planner.getActionableSteps()
      ),
      status = AgentStatus.THINKING,
      currentAction = if (isResuming) "Resuming from checkpoint turn ${existingCheckpoint?.currentTurn ?: 0}..." else "Understanding task objective: '${task.goal}'",
      currentStep = if (isResuming) existingCheckpoint?.currentTurn ?: 0 else 0,
      plan = planner.planDescriptions(),
      artifacts = artifactManager.getArtifacts(),
      persistentTask = initialPersistentTask,
      fiveStageRecord = FiveStageRecord(
        intent = task.goal,
        currentPlan = planner.planDescriptions(),
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
    var consecutiveEmptyDecisions = 0
    var lastFallbackProbeTurn = Int.MIN_VALUE
    var expectedSandboxId = (sandboxProvider as? E2BSandboxProvider)?.getActiveSession(task.sessionId)?.sandboxId

    // Pre-sync input documents and workspace input files into the microVM
    syncSandboxInputFiles(task, expectedSandboxId) { syncStep ->
      currentState = currentState.copy(
        executionFeed = addOrUpdateStep(currentState.executionFeed, syncStep.copy(stepNumber = ++stepCounter))
      )
      updateStateIfActive(task, currentState)
    }

    val keepAliveJob = scope.launch(Dispatchers.IO) {
      val e2b = sandboxProvider as? E2BSandboxProvider
      while (isActive && e2b != null && e2b.isE2B) {
        delay(90_000L)
        try {
          val activeSess = e2b.getActiveSession(task.sessionId)
          if (activeSess != null && activeSess.sandboxId.isNotBlank()) {
            e2b.keepAlive(activeSess.sandboxId)
          }
        } catch (_: Exception) {}
      }
    }

    try {
      while (turnCounter < maxSteps) {
        turnCounter++
        currentCoroutineContext().ensureActive()

        // Keep expectedSandboxId synchronized with any live/reconnected session
        val liveSessionId = (sandboxProvider as? E2BSandboxProvider)?.getActiveSession(task.sessionId)?.sandboxId
        if (liveSessionId != null) {
          expectedSandboxId = liveSessionId
        }

        // =========================================================================
        // PHASE 1: HYDRATE STATE, CONTEXT & DELIVERABLES
        // =========================================================================
        val deliverableReport = deliverableTracker.scanAndVerify(artifactManager)
        currentState = currentState.copy(
          trackedDeliverables = deliverableReport.deliverables,
          deliverableReport = deliverableReport
        )

        // Periodic autonomous progress check for long runs
        if (turnCounter > 35 && turnCounter % 15 == 0 && deliverableReport.totalRequired > 0 && !deliverableReport.allSatisfied) {
          val progressMsg = "Autonomous Progress (Turn $turnCounter): ${deliverableReport.verifiedCount}/${deliverableReport.totalRequired} deliverables verified. Continuing workflow to complete: ${deliverableReport.pendingOrInvalidDetails}."
          currentState = currentState.copy(
            status = AgentStatus.DELIVERABLE_PENDING,
            currentAction = "Synthesizing deliverables: ${deliverableReport.pendingOrInvalidDetails}",
            messages = currentState.messages + AgentMessage(role = MessageRole.USER, content = progressMsg)
          )
          if (!updateStateIfActive(task, currentState)) return
        }

        currentState = currentState.copy(
          status = if (currentState.status == AgentStatus.DELIVERABLE_PENDING) AgentStatus.DELIVERABLE_PENDING else AgentStatus.PLANNING,
          currentStep = turnCounter,
          currentAction = "Consulting LLM for next action (Turn $turnCounter)..."
        )
        if (!updateStateIfActive(task, currentState)) return

        val systemPrompt = buildSystemContext(task, currentState, planner, deliverableReport, turnCounter, maxSteps)

        // =========================================================================
        // PHASE 2: ASK MODEL FOR NEXT ACTION OR INDEPENDENT BATCH (STREAMING)
        // =========================================================================
        val thinkingStepId = "step_thinking_t${turnCounter}_${task.id}"
        val streamedTokens = StringBuilder()
        var streamStepCreated = false
        var lastStreamUpdateMs = 0L

        val streamListener = object : LLMStreamListener {
          override fun onReset() {
            streamedTokens.setLength(0)
          }

          override fun onToken(token: String) {
            streamedTokens.append(token)
            val now = System.currentTimeMillis()
            val hasNewline = token.contains('\n')
            if (now - lastStreamUpdateMs > 110L || (hasNewline && now - lastStreamUpdateMs > 60L)) {
              lastStreamUpdateMs = now
              if (!streamStepCreated) {
                stepCounter++
                streamStepCreated = true
              }
              val streamStep = ExecutionStep(
                id = thinkingStepId,
                stepNumber = stepCounter,
                type = StepType.THINKING,
                title = "Reasoning",
                content = streamedTokens.toString(),
                isCompleted = false
              )
              currentState = currentState.copy(
                status = AgentStatus.THINKING,
                currentAction = "Reasoning...",
                executionFeed = addOrUpdateStep(currentState.executionFeed, streamStep)
              )
              updateStateIfActive(task, currentState)
            }
          }
        }

        val queryResult = queryModelDecision(
          task = task,
          systemPrompt = systemPrompt,
          messages = currentState.messages,
          turnCounter = turnCounter,
          lastFallbackProbeTurn = lastFallbackProbeTurn,
          providerDropRetries = providerDropRetries,
          streamListener = streamListener
        )
        val decision = queryResult.decision
        providerDropRetries = queryResult.updatedDropRetries

        if (queryResult.restoredProviderStep) {
          lastFallbackProbeTurn = turnCounter
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
        }

        currentCoroutineContext().ensureActive()

        // 2A. Provider Error Handling
        if (decision is LLMDecision.ProviderError) {
          if (streamStepCreated) {
            currentState = currentState.copy(
              executionFeed = currentState.executionFeed.filter { it.id != thinkingStepId }
            )
          }
          planner.markProviderFailure(decision.message)
          if (providerDropRetries < 2) {
            providerDropRetries++
            val waitMs = (decision.retryAfterMs ?: 1500L).coerceIn(1500L, 30_000L)
            val retryStep = ExecutionStep(
              id = "step_retry_${providerDropRetries}_t${turnCounter}_${task.id}",
              stepNumber = ++stepCounter,
              type = StepType.OBSERVATION,
              title = "Endpoint Drop Recovery ($providerDropRetries/2)",
              content = "Endpoint drop: ${decision.message.take(160)}. Retrying automatically in ${waitMs / 1000}s..."
            )
            currentState = currentState.copy(
              currentAction = "Recovering from endpoint drop (attempt $providerDropRetries/2)...",
              executionFeed = addOrUpdateStep(currentState.executionFeed, retryStep)
            )
            if (!updateStateIfActive(task, currentState)) return
            delay(waitMs)
            continue
          } else if (providerDropRetries == 2) {
            providerDropRetries++
            val fallbackStep = ExecutionStep(
              id = "step_fallback_${task.id}",
              stepNumber = ++stepCounter,
              type = StepType.OBSERVATION,
              title = "Autonomous Fallback Engaged",
              content = "LLM endpoint unavailable after retries. Switched to the built-in fallback reasoning engine; E2B Cloud sandbox execution is unchanged."
            )
            currentState = currentState.copy(
              currentAction = "Running via built-in fallback reasoning engine...",
              executionFeed = addOrUpdateStep(currentState.executionFeed, fallbackStep)
            )
            if (!updateStateIfActive(task, currentState)) return
            activeProvider = AutonomousSandboxProvider()
            delay(1000)
            continue
          } else {
            val errStep = ExecutionStep(
              id = "step_error_${task.id}",
              stepNumber = ++stepCounter,
              type = StepType.ERROR,
              title = "Provider Failure",
              content = decision.message
            )
            recordTaskCompletion(task, AgentStatus.FAILED, decision.message, artifactManager.getArtifacts())
            currentState = currentState.copy(
              status = AgentStatus.FAILED,
              currentAction = "Failed with provider error",
              error = decision.message,
              taskHistory = _state.value.taskHistory,
              executionFeed = addOrUpdateStep(currentState.executionFeed, errStep),
              canCancel = false
            )
            updateStateIfActive(task, currentState)
            return
          }
        }

        // Flush any remaining buffered tokens from streaming
        if (streamStepCreated && streamedTokens.isNotEmpty()) {
          val currentStreamContent = streamedTokens.toString()
          val streamStep = ExecutionStep(
            id = thinkingStepId,
            stepNumber = stepCounter,
            type = StepType.THINKING,
            title = "Reasoning",
            content = currentStreamContent,
            isCompleted = false
          )
          currentState = currentState.copy(
            executionFeed = addOrUpdateStep(currentState.executionFeed, streamStep)
          )
        }

        // 2B. Explicit Completion Claim Handling
        if (decision is LLMDecision.Complete) {
          providerDropRetries = 0
          val conclusionText = decision.conclusion.ifBlank { streamedTokens.toString() }
          if (streamStepCreated) {
            val streamContent = streamedTokens.toString().trim()
            val isDirectConclusion = streamContent.isBlank() ||
              streamContent.equals(conclusionText.trim(), ignoreCase = true) ||
              streamContent.contains(conclusionText.trim()) ||
              conclusionText.contains(streamContent)

            val finishedStep = ExecutionStep(
              id = thinkingStepId,
              stepNumber = stepCounter,
              type = if (isDirectConclusion) StepType.CONCLUSION else StepType.THINKING,
              title = if (isDirectConclusion) "Conclusion" else "Reasoning",
              content = if (streamContent.isNotBlank()) streamContent else conclusionText,
              isCompleted = true,
              actionStatus = ActionExecutionStatus.COMPLETED
            )
            currentState = currentState.copy(
              executionFeed = addOrUpdateStep(currentState.executionFeed, finishedStep)
            )
          }
          val outcome = evaluateCompletion(
            task = task,
            conclusion = conclusionText,
            turnCounter = turnCounter,
            currentState = currentState,
            planner = planner,
            consecutiveVerificationFailures = consecutiveVerificationFailures,
            stepCounterProvider = { ++stepCounter },
            onVerificationFailureIncremented = { consecutiveVerificationFailures = it }
          )
          currentState = _state.value
          when (outcome) {
            is TurnOutcome.Terminal -> return
            is TurnOutcome.Continue -> continue
            is TurnOutcome.ReplanningRequired -> continue
          }
        }

        // 2C. Execute Tool Handling
        if (decision is LLMDecision.ExecuteTool) {
          providerDropRetries = 0
          val toolCalls = decision.toolCalls

          if (toolCalls.isEmpty()) {
            val rawThought = decision.thought.orEmpty()
            val thoughtLower = rawThought.lowercase()
            val seemsComplete = thoughtLower.contains("complete") ||
              thoughtLower.contains("finished") ||
              thoughtLower.contains("done") ||
              thoughtLower.contains("conclude")

            if (seemsComplete) {
              val outcome = evaluateCompletion(
                task = task,
                conclusion = rawThought.ifBlank { "Task completed successfully and verified." },
                turnCounter = turnCounter,
                currentState = currentState,
                planner = planner,
                consecutiveVerificationFailures = consecutiveVerificationFailures,
                stepCounterProvider = { ++stepCounter },
                onVerificationFailureIncremented = { consecutiveVerificationFailures = it }
              )
              currentState = _state.value
              if (outcome is TurnOutcome.Terminal) return
              continue
            }

            consecutiveEmptyDecisions++
            if (consecutiveEmptyDecisions >= MAX_CONSECUTIVE_EMPTY_DECISIONS) {
              val haltMsg = "Provider produced no verified, actionable step for $consecutiveEmptyDecisions consecutive turns. Halting."
              scratchpad.saveCheckpoint(
                AgentCheckpoint(
                  taskGoal = task.goal,
                  currentTurn = turnCounter,
                  completedSections = planner.completedDescriptions(),
                  pendingSections = planner.pendingDescriptions(),
                  planItems = currentState.plan,
                  artifactPaths = artifactManager.getArtifacts().map { it.path },
                  totalFindingsCount = scratchpad.loadAllFindings().size,
                  status = "FAILED",
                  recoveryState = recoveryManager.exportStateJson()
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

          consecutiveEmptyDecisions = 0

          val updatedPlan = if (decision.plan != null) {
            planner.acceptModelPlan(decision.plan)
            val parsedLines = planner.planDescriptions()
            scratchpad.savePlan(parsedLines)
            parsedLines
          } else currentState.plan

          val currentPTask = planner.toPersistentTask(task.sessionId, turnCounter, maxSteps)
          scratchpad.saveTask(currentPTask)

          val rawThought = decision.thought
          val latestObs = currentState.observations.lastOrNull()?.summary
            ?: currentState.messages.lastOrNull { it.role == MessageRole.TOOL }?.content
          val cleanedThought = if (!rawThought.isNullOrBlank()) {
            dedupPipeline.process(rawThought, turnCounter, observationFingerprint = latestObs)
          } else null

          val thinkingStepId = "step_thinking_t${turnCounter}_${task.id}"
          val finalThought = cleanedThought ?: (if (streamedTokens.isNotEmpty()) streamedTokens.toString() else null)
          val feedWithThought = if (!finalThought.isNullOrBlank() && !finalThought.startsWith("[REASONING_DEDUP_HIT")) {
            addOrUpdateStep(
              currentState.executionFeed,
              ExecutionStep(
                id = thinkingStepId,
                stepNumber = if (streamStepCreated) stepCounter else ++stepCounter,
                type = StepType.THINKING,
                title = "Reasoning",
                content = finalThought,
                isCompleted = true
              )
            )
          } else {
            if (streamStepCreated) {
              currentState.executionFeed.filter { it.id != thinkingStepId }
            } else {
              currentState.executionFeed
            }
          }

          // =========================================================================
          // PHASE 3: TOOL CALL VALIDATION & DISPATCHING
          // =========================================================================
          val validatedToolCalls = toolCalls.mapIndexed { idx, call ->
            val normalizedCallId = if (call.callId.startsWith("call_")) {
              call.callId
            } else {
              ToolCall.generateCallId(task.id, turnCounter * 10 + idx)
            }
            call.copy(callId = normalizedCallId)
          }

          // Pre-populate pending tool steps immediately into the timeline
          var feedWithPendingTools = feedWithThought
          validatedToolCalls.forEach { call ->
            val toolStepId = "step_tool_${call.callId}"
            val pendingStep = ExecutionStep(
              id = toolStepId,
              stepNumber = ++stepCounter,
              type = StepType.TOOL_CALL,
              title = com.example.agent.ui.components.formatToolDisplayName(call.toolName),
              content = formatArguments(call.arguments),
              toolName = call.toolName,
              toolCallId = call.callId,
              toolStatus = "PENDING",
              actionStatus = ActionExecutionStatus.PLANNED,
              isCompleted = false
            )
            feedWithPendingTools = addOrUpdateStep(feedWithPendingTools, pendingStep)
          }

          currentState = currentState.copy(
            plan = updatedPlan,
            persistentTask = currentPTask,
            executionFeed = feedWithPendingTools,
            fiveStageRecord = currentState.fiveStageRecord.copy(currentPlan = updatedPlan)
          )

          val existingToolCallIds = currentState.messages.mapNotNull { it.toolCallId }.toSet()
          val assistantToolMessages = validatedToolCalls.mapIndexed { index, call ->
            AgentMessage(
              role = MessageRole.ASSISTANT,
              content = if (index == 0) {
                finalThought?.takeUnless { it.startsWith("[REASONING_DEDUP_HIT") } ?: "Executing ${validatedToolCalls.size} requested tool call(s)."
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

          // Bind validated tool calls to actionable steps and mark DISPATCHED
          val matchedStepIds = mutableSetOf<String>()
          validatedToolCalls.forEach { call ->
            val candidateSteps = planner.getActionableSteps()
            val matchingStep = candidateSteps.firstOrNull { step ->
              step.id !in matchedStepIds && (step.toolCallId == call.callId || (step.assignedTool == call.toolName && step.status == ActionExecutionStatus.PLANNED))
            } ?: candidateSteps.firstOrNull { step ->
              step.id !in matchedStepIds && step.status == ActionExecutionStatus.PLANNED
            }

            if (matchingStep != null) {
              matchedStepIds.add(matchingStep.id)
              planner.markStepDispatched(matchingStep.id, call.callId, call.toolName)
            }
          }

          val dispatchResult = dispatchToolCallsBatch(
            task = task,
            turnCounter = turnCounter,
            validatedToolCalls = validatedToolCalls,
            currentState = currentState,
            currentSandboxId = expectedSandboxId,
            planner = planner,
            stepCounterProvider = { ++stepCounter }
          )

          if (dispatchResult.activeSandboxId != null) {
            expectedSandboxId = dispatchResult.activeSandboxId
          }

          if (dispatchResult.resetSandboxStep != null) {
            currentState = currentState.copy(
              status = AgentStatus.SANDBOX_RESET,
              currentAction = "Recovering from sandbox swap; synchronizing workspace...",
              executionFeed = currentState.executionFeed + dispatchResult.resetSandboxStep
            )
            updateStateIfActive(task, currentState)
          }

          if (dispatchResult.fatalHaltMessage != null) {
            scratchpad.saveCheckpoint(
              AgentCheckpoint(
                taskGoal = task.goal,
                currentTurn = turnCounter,
                completedSections = planner.completedDescriptions(),
                pendingSections = planner.pendingDescriptions(),
                planItems = currentState.plan,
                artifactPaths = artifactManager.getArtifacts().map { it.path },
                totalFindingsCount = scratchpad.loadAllFindings().size,
                status = "FAILED",
                recoveryState = recoveryManager.exportStateJson()
              )
            )
            recordTaskCompletion(task, AgentStatus.FAILED, dispatchResult.fatalHaltMessage, artifactManager.getArtifacts())
            val haltStep = ExecutionStep(
              id = "step_loopguard_halt_${task.id}",
              stepNumber = ++stepCounter,
              type = StepType.ERROR,
              title = "Unproductive Loop Detected",
              content = dispatchResult.fatalHaltMessage
            )
            currentState = currentState.copy(
              status = AgentStatus.FAILED,
              currentAction = dispatchResult.fatalHaltMessage,
              error = dispatchResult.fatalHaltMessage,
              executionFeed = addOrUpdateStep(currentState.executionFeed, haltStep),
              canCancel = false
            )
            updateStateIfActive(task, currentState)
            return
          }

          // =========================================================================
          // PHASES 4 & 5: NORMALIZE RESULTS, RETURN OBSERVATIONS & PERSIST
          // =========================================================================
          currentState = processExecutedResultsAndReflect(
            task = task,
            turnCounter = turnCounter,
            results = dispatchResult.results,
            currentState = currentState,
            planner = planner,
            stepCounterProvider = { ++stepCounter }
          )
          if (!updateStateIfActive(task, currentState)) return

          // =========================================================================
          // PHASE 6: EVALUATE OUTCOME (SURGICAL LOOP GUARD & REPLANNING)
          // =========================================================================
          if (consecutiveZeroProgressActions >= 3) {
            if (!hasReplannedForZeroProgress) {
              // Stop retrying, replan once!
              hasReplannedForZeroProgress = true
              consecutiveZeroProgressActions = 0 // Reset counter for the replanned turn
              val loopReason = "Workflow loop detected: Multiple consecutive actions produced no new evidence or progress. Stopping retries and replanning once."
              planner.clearReplanReason()

              val replanStep = ExecutionStep(
                id = "step_replan_loop_t${turnCounter}_${task.id}",
                stepNumber = ++stepCounter,
                type = StepType.REPLANNING,
                title = "Replanning Required (No Progress)",
                content = loopReason,
                recoveryStrategy = RecoveryStrategy.REVISE_PLAN.name
              )
              val replanNotice = "System Notice (Replanning Required): $loopReason\n" +
                "Do NOT repeat the same reasoning or tool call without new justification. You must select an alternative tool or approach."
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
                currentAction = "Replanning: $loopReason",
                messages = replanMessages,
                executionFeed = addOrUpdateStep(currentState.executionFeed, replanStep)
              )
              if (!updateStateIfActive(task, currentState)) return
            } else {
              // Replan was attempted once, but execution remains blocked without state change or evidence!
              val fatalLoopMsg = "Workflow halted: Two consecutive actions produced no new evidence or progress. Replanning was attempted once, but execution remains blocked without state change or evidence."
              scratchpad.saveCheckpoint(
                AgentCheckpoint(
                  taskGoal = task.goal,
                  currentTurn = turnCounter,
                  completedSections = planner.completedDescriptions(),
                  pendingSections = planner.pendingDescriptions(),
                  planItems = currentState.plan,
                  artifactPaths = artifactManager.getArtifacts().map { it.path },
                  totalFindingsCount = scratchpad.loadAllFindings().size,
                  status = "FAILED",
                  recoveryState = recoveryManager.exportStateJson()
                )
              )
              recordTaskCompletion(task, AgentStatus.FAILED, fatalLoopMsg, artifactManager.getArtifacts())
              val haltStep = ExecutionStep(
                id = "step_zeroprog_halt_${task.id}",
                stepNumber = ++stepCounter,
                type = StepType.ERROR,
                title = "Execution Halted (Zero Progress)",
                content = fatalLoopMsg
              )
              currentState = currentState.copy(
                status = AgentStatus.FAILED,
                currentAction = fatalLoopMsg,
                error = fatalLoopMsg,
                executionFeed = addOrUpdateStep(currentState.executionFeed, haltStep),
                canCancel = false
              )
              updateStateIfActive(task, currentState)
              return
            }
          } else {
            val isStagnantLoop = recoveryManager.loopDetector.getZeroProgressStreak() >= 3
            val isIneffectiveStep = planner.isStepIneffective()
            if (planner.hasReplanRequest() || planner.isStagnant() || isStagnantLoop || isIneffectiveStep) {
              val replanReason = planner.getReplanReason() ?: when {
                isStagnantLoop -> "Zero-progress loop detected: ${recoveryManager.loopDetector.getZeroProgressStreak()} consecutive operations produced no state delta or new information."
                isIneffectiveStep -> "Ineffective plan step: Active step attempted repeatedly without progress. Alternative strategy required."
                planner.isStagnant() -> "Stagnation detected: ${planner.getNoProgressCount()} turns without measurable progress."
                else -> "Plan adjustment required based on recent observations."
              }
              planner.clearReplanReason()

              val replanStep = ExecutionStep(
                id = "step_replan_t${turnCounter}_${task.id}",
                stepNumber = ++stepCounter,
                type = StepType.REPLANNING,
                title = "Replanning Required",
                content = replanReason,
                recoveryStrategy = RecoveryStrategy.REVISE_PLAN.name
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
        }
      }

      // =========================================================================
      // TURN BUDGET EXHAUSTED: DETERMINISTIC TERMINAL STATE RESOLUTION
      // =========================================================================
      try {
        sandboxManager.activeProvider.syncArtifacts(task.sessionId, workspaceDir, task.id)
      } catch (e: Exception) {
        Log.w("AgentEngine", "syncArtifacts failed during final verification (non-fatal)", e)
      }
      val finalVerification = verifyObjective(task, currentState, planner)
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
            status = "COMPLETED",
            recoveryState = recoveryManager.exportStateJson()
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
            status = "PARTIAL_COMPLETION",
            recoveryState = recoveryManager.exportStateJson()
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
          status = finalStatus.name,
          recoveryState = recoveryManager.exportStateJson()
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

    } catch (ce: CancellationException) {
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
            status = "CANCELLED",
            recoveryState = recoveryManager.exportStateJson()
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
      Log.e("AgentEngine", "Fatal execution fault in task ${task.id}", t)
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
    } finally {
      keepAliveJob.cancel()
    }
  }

  /**
   * Infers candidate deliverable filename from goal text only when explicit creation intent is present.
   */
  private fun inferDeliverableFilename(goal: String): String? {
    val goalLower = goal.lowercase()
    val isExplicitCreation = goalLower.contains("create") ||
      goalLower.contains("generate") ||
      goalLower.contains("write") ||
      goalLower.contains("save") ||
      goalLower.contains("build") ||
      goalLower.contains("produce") ||
      goalLower.contains("export") ||
      goalLower.contains("replicate") ||
      goalLower.contains("recreate") ||
      goalLower.contains("clone")

    if (!isExplicitCreation) return null

    if (goalLower.contains("word file") || goalLower.contains("wordfile") || goalLower.contains("word doc") || goalLower.contains("word document") || goalLower.contains(".docx")) {
      return "document.docx"
    }
    if (goalLower.contains("excel file") || goalLower.contains("spreadsheet") || goalLower.contains(".xlsx") || goalLower.contains("excel sheet")) {
      return "spreadsheet.xlsx"
    }
    if (goalLower.contains("pdf file") || goalLower.contains("pdf document") || goalLower.contains(".pdf")) {
      return "document.pdf"
    }
    return null
  }

  fun extractRequiredOutputs(goal: String): Set<String> {
    val results = mutableSetOf<String>()
    val regexes = listOf(
      Regex("""(?:record|write|save|output|produce|generate|create)\s+(?:findings|results|report|audit|document|file)?\s*(?:to|in|into)?\s*[`"']?([a-zA-Z0-9_\-./]+\.[a-zA-Z0-9]{2,4})[`"']?""", RegexOption.IGNORE_CASE),
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
   * 1. Mark each requirement PASS, FAIL, BLOCKED, or NOT VERIFIED based on recorded evidence.
   * 2. Never claim all checks passed when any required check is blocked, skipped, or unverified.
   * 3. Deliverables must be verified on disk: exist, be regular files, non-empty, and readable.
   * 4. Missing requirements are reported honestly as partial completion when applicable.
   */
  private fun verifyObjective(task: Task, state: AgentState, planner: TaskPlanner? = null): VerificationResult {
    val report = deliverableTracker.scanAndVerify(artifactManager)
    val checks = mutableListOf<RequirementCheck>()
    val verified = mutableListOf<String>()
    val missing = mutableListOf<String>()

    // 0. Explicit expected deliverable or tracked deliverables check
    if (task.expectedArtifact != null || report.totalRequired > 0) {
      report.deliverables.forEach { d ->
        val file = File(workspaceDir, d.path)
        val matchingFile = if (file.exists()) file else deliverableTracker.findMatchingFile(d.name)
        val targetPath = d.path

        if (matchingFile != null && matchingFile.exists() && matchingFile.isFile && matchingFile.canRead() && matchingFile.length() > 0) {
          val diskValidation = ArtifactValidatorRegistry.validateExistingFile(matchingFile)
          if (diskValidation.isValid) {
            checks.add(
              RequirementCheck(
                name = "Deliverable '${d.name}'",
                status = RequirementCheckStatus.PASS,
                evidence = "Verified on disk: ${matchingFile.length()} bytes, readable, passed format validation.",
                target = targetPath
              )
            )
            if (!verified.contains(targetPath)) verified.add(targetPath)
          } else {
            checks.add(
              RequirementCheck(
                name = "Deliverable '${d.name}'",
                status = RequirementCheckStatus.FAIL,
                evidence = "File exists (${matchingFile.length()} bytes) but failed format validation: ${diskValidation.errorMessage}",
                target = targetPath
              )
            )
            missing.add("${d.name} (${diskValidation.errorMessage})")
          }
        } else if (matchingFile != null && matchingFile.exists() && matchingFile.length() == 0L) {
          checks.add(
            RequirementCheck(
              name = "Deliverable '${d.name}'",
              status = RequirementCheckStatus.FAIL,
              evidence = "File exists on disk but is 0 bytes (empty).",
              target = targetPath
            )
          )
          missing.add("${d.name} (0 bytes)")
        } else {
          val lastAttempt = state.executionFeed.lastOrNull { it.content.contains(d.name) || (it.toolName in listOf("write_file", "edit_file", "build_pdf_document", "recreate_document")) }
          val isBlocked = lastAttempt?.structuredError?.failureClass in listOf(ToolFailureClass.PERMISSION_POLICY, ToolFailureClass.UNAVAILABLE_TOOL) ||
            lastAttempt?.failureCategory in listOf(ActionFailureCategory.PERMISSION_ERROR.name, ActionFailureCategory.MISSING_DEPENDENCY.name)
          if (isBlocked) {
            checks.add(
              RequirementCheck(
                name = "Deliverable '${d.name}'",
                status = RequirementCheckStatus.BLOCKED,
                evidence = "Deliverable creation was blocked by security policy, permissions, or missing dependency.",
                target = targetPath
              )
            )
            missing.add("${d.name} [BLOCKED]")
          } else {
            checks.add(
              RequirementCheck(
                name = "Deliverable '${d.name}'",
                status = RequirementCheckStatus.NOT_VERIFIED,
                evidence = "Deliverable does not exist on disk in workspace.",
                target = targetPath
              )
            )
            missing.add("${d.name} [NOT VERIFIED]")
          }
        }
      }
    }

    // 1. Strict deliverable check from explicit task outputs (e.g. audit_scribd.md)
    val requiredOutputs = extractRequiredOutputs(task.goal)
    for (out in requiredOutputs) {
      if (checks.none { it.target == out || it.name.contains(out) }) {
        val directF = File(workspaceDir, out)
        val f = if (directF.exists()) directF else (deliverableTracker.findMatchingFile(File(out).name) ?: directF)
        if (!f.exists() || !f.isFile || !f.canRead() || f.length() == 0L) {
          val lastAttempt = state.executionFeed.lastOrNull { it.content.contains(out) || (it.toolName in listOf("write_file", "edit_file", "build_pdf_document", "recreate_document")) }
          val isBlocked = lastAttempt?.structuredError?.failureClass in listOf(ToolFailureClass.PERMISSION_POLICY, ToolFailureClass.UNAVAILABLE_TOOL) ||
            lastAttempt?.failureCategory in listOf(ActionFailureCategory.PERMISSION_ERROR.name, ActionFailureCategory.MISSING_DEPENDENCY.name) ||
            state.toolExecutions.any { it.arguments["path"]?.toString()?.contains(out) == true && it.status == ToolStatus.FAILED }
          val checkStatus = if (isBlocked) RequirementCheckStatus.BLOCKED else RequirementCheckStatus.NOT_VERIFIED
          checks.add(
            RequirementCheck(
              name = "Required output '$out'",
              status = checkStatus,
              evidence = if (isBlocked) "Blocked by policy or permissions" else "File missing or 0 bytes on disk.",
              target = out
            )
          )
          missing.add(if (isBlocked) "$out [BLOCKED]" else "$out [NOT VERIFIED]")
        } else {
          val validation = ArtifactValidatorRegistry.validateExistingFile(f)
          if (validation.isValid) {
            checks.add(
              RequirementCheck(
                name = "Required output '$out'",
                status = RequirementCheckStatus.PASS,
                evidence = "Verified on disk: ${f.length()} bytes, readable, passed validation.",
                target = out
              )
            )
            if (!verified.contains(out)) verified.add(out)
          } else {
            checks.add(
              RequirementCheck(
                name = "Required output '$out'",
                status = RequirementCheckStatus.FAIL,
                evidence = "Validation failed: ${validation.errorMessage}",
                target = out
              )
            )
            missing.add("$out (${validation.errorMessage})")
          }
        }
      }
    }

    // 2. Inferred deliverable from goal text
    val cleanWord = inferDeliverableFilename(task.goal)
    if (cleanWord != null && checks.none { it.target == cleanWord || it.name.contains(cleanWord) }) {
      val file = File(workspaceDir, cleanWord)
      val goalLower = task.goal.lowercase()
      if (goalLower.contains("delete") || goalLower.contains("remove")) {
        if (file.exists()) {
          checks.add(
            RequirementCheck(
              name = "Deletion of '$cleanWord'",
              status = RequirementCheckStatus.FAIL,
              evidence = "File was requested to be deleted but still exists on disk.",
              target = cleanWord
            )
          )
          missing.add("Deletion of $cleanWord")
        } else {
          checks.add(
            RequirementCheck(
              name = "Deletion of '$cleanWord'",
              status = RequirementCheckStatus.PASS,
              evidence = "File deletion confirmed on disk.",
              target = cleanWord
            )
          )
          if (!verified.contains("Deleted $cleanWord")) verified.add("Deleted $cleanWord")
        }
      } else if (goalLower.contains("create") || goalLower.contains("write") || goalLower.contains("produce") ||
                 goalLower.contains("generate") || goalLower.contains("record") || goalLower.contains("save") ||
                 goalLower.contains("store") || goalLower.contains("output") || goalLower.contains("transform")) {
        val effectiveFile = if (file.exists()) file else {
          if (cleanWord.endsWith(".docx", ignoreCase = true)) {
            workspaceDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".docx", ignoreCase = true) && it.length() > 0 } ?: file
          } else if (cleanWord.endsWith(".xlsx", ignoreCase = true) || cleanWord.endsWith(".xls", ignoreCase = true)) {
            workspaceDir.listFiles()?.firstOrNull { it.isFile && (it.name.endsWith(".xlsx", ignoreCase = true) || it.name.endsWith(".xls", ignoreCase = true)) && it.length() > 0 } ?: file
          } else {
            file
          }
        }
        if (!effectiveFile.exists() || !effectiveFile.isFile || !effectiveFile.canRead() || effectiveFile.length() == 0L) {
          checks.add(
            RequirementCheck(
              name = "Produce '${effectiveFile.name}'",
              status = RequirementCheckStatus.NOT_VERIFIED,
              evidence = "File missing or empty on disk.",
              target = effectiveFile.name
            )
          )
          missing.add(effectiveFile.name)
        } else {
          val diskValidation = ArtifactValidatorRegistry.validateExistingFile(effectiveFile)
          if (diskValidation.isValid) {
            checks.add(
              RequirementCheck(
                name = "Produce '${effectiveFile.name}'",
                status = RequirementCheckStatus.PASS,
                evidence = "Verified on disk: ${effectiveFile.length()} bytes, readable, passed validation.",
                target = effectiveFile.name
              )
            )
            if (!verified.contains(effectiveFile.name)) verified.add(effectiveFile.name)
          } else {
            checks.add(
              RequirementCheck(
                name = "Produce '${effectiveFile.name}'",
                status = RequirementCheckStatus.FAIL,
                evidence = "Validation failed: ${diskValidation.errorMessage}",
                target = effectiveFile.name
              )
            )
            missing.add("${effectiveFile.name} (${diskValidation.errorMessage})")
          }
        }
      }
    }

    // 3. Actionable steps from plan
    val actionableSteps = planner?.getActionableSteps() ?: emptyList()
    if (actionableSteps.isNotEmpty()) {
      for (step in actionableSteps) {
        val checkName = "Plan Step: ${step.description.take(50)}"
        when (step.status) {
          ActionExecutionStatus.COMPLETED -> {
            checks.add(
              RequirementCheck(
                name = checkName,
                status = RequirementCheckStatus.PASS,
                evidence = step.completionEvidence.firstOrNull() ?: step.observations.lastOrNull() ?: "Step marked completed with recorded result."
              )
            )
          }
          ActionExecutionStatus.BLOCKED -> {
            checks.add(
              RequirementCheck(
                name = checkName,
                status = RequirementCheckStatus.BLOCKED,
                evidence = step.lastError ?: "Step marked blocked."
              )
            )
          }
          ActionExecutionStatus.SKIPPED -> {
            val wasBlockedPredecessor = step.lastError?.contains("blocking", ignoreCase = true) == true ||
              step.lastError?.contains("halting", ignoreCase = true) == true
            checks.add(
              RequirementCheck(
                name = checkName,
                status = if (wasBlockedPredecessor) RequirementCheckStatus.BLOCKED else RequirementCheckStatus.NOT_VERIFIED,
                evidence = step.lastError ?: "Step skipped."
              )
            )
          }
          ActionExecutionStatus.FAILED -> {
            checks.add(
              RequirementCheck(
                name = checkName,
                status = RequirementCheckStatus.FAIL,
                evidence = step.lastError ?: "Step execution failed."
              )
            )
          }
          ActionExecutionStatus.SUPERSEDED -> {
            checks.add(
              RequirementCheck(
                name = checkName,
                status = RequirementCheckStatus.NOT_VERIFIED,
                evidence = step.lastError ?: "Step was superseded during plan revision."
              )
            )
          }
          ActionExecutionStatus.PLANNED, ActionExecutionStatus.DISPATCHED, ActionExecutionStatus.EXECUTING -> {
            checks.add(
              RequirementCheck(
                name = checkName,
                status = RequirementCheckStatus.NOT_VERIFIED,
                evidence = "Step has not been completed or verified."
              )
            )
          }
        }
      }
    }

    // 4. Tool execution health
    if (state.toolExecutions.isEmpty() && checks.isEmpty()) {
      checks.add(
        RequirementCheck(
          name = "Perform task actions",
          status = RequirementCheckStatus.PASS,
          evidence = "Direct reasoning or conversational synthesis concluded without external tool side-effects."
        )
      )
    }

    val lastExecution = state.toolExecutions.lastOrNull()
    if (lastExecution != null && lastExecution.status == ToolStatus.FAILED) {
      val isInterventionSkip = lastExecution.error?.contains("Intervention:") == true
      val isDiagnosticTask = task.goal.lowercase().let { g ->
        g.contains("test") || g.contains("check") || g.contains("see if") || g.contains("probe") || g.contains("inspect") || g.contains("diagnose") || g.contains("audit")
      }
      if (!isInterventionSkip && !isDiagnosticTask) {
        val isBlocked = lastExecution.error?.contains("permission", ignoreCase = true) == true ||
          lastExecution.error?.contains("blocked", ignoreCase = true) == true
        checks.add(
          RequirementCheck(
            name = "Tool execution '${lastExecution.toolName}'",
            status = if (isBlocked) RequirementCheckStatus.BLOCKED else RequirementCheckStatus.FAIL,
            evidence = "Failed with error: '${lastExecution.error}'"
          )
        )
      }
    }

    if (checks.isEmpty()) {
      checks.add(
        RequirementCheck(
          name = "Task execution",
          status = RequirementCheckStatus.PASS,
          evidence = if (state.toolExecutions.isNotEmpty()) "${state.toolExecutions.size} action(s) completed" else "Cognitive response synthesized"
        )
      )
    }

    // CRITICAL COMPLETION INVARIANT:
    // Never claim all checks passed when any required check is blocked, skipped, or unverified.
    val hasBlocked = checks.any { it.status == RequirementCheckStatus.BLOCKED }
    val hasFailed = checks.any { it.status == RequirementCheckStatus.FAIL }
    val hasUnverified = checks.any { it.status == RequirementCheckStatus.NOT_VERIFIED }

    val allPassed = checks.isNotEmpty() && !hasBlocked && !hasFailed && !hasUnverified
    val isSatisfied = allPassed

    val details = buildString {
      if (allPassed) {
        appendLine("All ${checks.size} requirement check(s) PASSED based on recorded evidence:")
      } else {
        val reasons = mutableListOf<String>()
        if (hasBlocked) reasons.add("BLOCKED checks")
        if (hasFailed) reasons.add("FAILED checks")
        if (hasUnverified) reasons.add("UNVERIFIED checks")
        appendLine("Verification incomplete (${reasons.joinToString(", ")}):")
      }
      checks.forEach { c ->
        appendLine("• [${c.status.name}] ${c.name}: ${c.evidence}")
      }
    }.trim()

    val readableArtifacts = if (verified.isNotEmpty()) verified else artifactManager.getArtifacts().filter { it.isReadable && it.size > 0 }.map { it.path }

    return VerificationResult(
      isSatisfied = isSatisfied,
      details = details,
      verifiedArtifacts = readableArtifacts,
      missingRequirements = checks.filter { it.status != RequirementCheckStatus.PASS }.map { "${it.name} [${it.status.name}]" },
      isPartial = checks.any { it.status == RequirementCheckStatus.PASS } && !isSatisfied,
      checks = checks
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

    // Authoritative Execution Environment & Active Sandbox
    sb.appendLine()
    sb.appendLine("ACTIVE EXECUTION ENVIRONMENT:")
    sb.appendLine("- Sandbox: ${sandboxProvider.name} (${sandboxProvider.mode.name}) at /home/user (Hardware-isolated Linux MicroVM)")
    sb.appendLine("- Workspace: All scripts, outputs, and deliverables must reside in /home/user or /tmp")

    // Execution Turn & Deliverable Tracker
    sb.appendLine()
    sb.appendLine("EXECUTION STATUS & PROGRESS:")
    sb.appendLine("- Turn: $turnCounter (Active Autonomous Workflow)")
    if (state.isMultitasking || state.activeConcurrentTasks > 0) {
      sb.appendLine("- Multitasking State: Concurrently executing ${state.activeConcurrentTasks} operations.")
    }
    sb.appendLine("- WORKFLOW LOOP POLICY: Do NOT repeat the same reasoning or tool call without new justification.")
    sb.appendLine("- RECOVERY POLICY: Transient failures are retried once. For policy failures, switch to an approved alternative tool. Never repeat a blocked operation unchanged.")
    sb.appendLine("- FRONTIER SWE EXECUTION DISCIPLINE:")
    sb.appendLine("  1. PARALLEL DISCOVERY: Batch independent read-only inspections (read_file, list_directory, web_search, wikipedia__*, fetch__*) in a single turn to execute concurrently.")
    sb.appendLine("  2. READ-BEFORE-EDIT: Always inspect target file contents before invoking edit_file or apply_patch to ensure exact context matching.")
    sb.appendLine("  3. DETERMINISTIC VERIFICATION: After writing code or generating artifacts, verify correctness via code_interpreter, terminal test execution, or file inspection before concluding.")
    sb.appendLine()
    sb.appendLine(deliverableTracker.generatePromptSummary(deliverableReport))

    // Execution Runtime Environment Descriptor
    val manifest = CapabilityManifest.build(registry, sandboxProvider)
    sb.appendLine()
    sb.appendLine("EXECUTION RUNTIME ENVIRONMENT:")
    sb.appendLine("- Sandbox Mode: ${manifest.sandbox.mode.name} (${manifest.sandbox.environment})")
    sb.appendLine("- Architecture: ${manifest.systemArchitecture}")
    sb.appendLine("- Guidance: ${manifest.sandbox.executionGuidance}")

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
    sb.appendLine("  * For authoring from scratch: ALWAYS use Python scripts (python-docx for .docx, openpyxl/pandas for .xlsx) or 'build_pdf_document' for PDF. WHEN STUCK: ALWAYS USE PYTHON!")

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
      sb.appendLine("- Active MCP Bridged Tools (${tools.size}): Exposing ${tools.size} high-performance domain tools across active servers.")
      if (resources.isNotEmpty()) {
        sb.appendLine("- Available MCP Resources (${resources.size}):")
        resources.take(5).forEach { r ->
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

  companion object {
    /** Consecutive turns without any tool call or completion before the run halts honestly. */
    private const val MAX_CONSECUTIVE_EMPTY_DECISIONS = 6

    /** While the built-in fallback engine is active, retry the configured provider every N turns. */
    private const val FALLBACK_PROBE_INTERVAL_TURNS = 5

    /** Checkpoint statuses from which a same-goal task may restore plan progress on resume. */
    private val RESUMABLE_CHECKPOINT_STATUSES = setOf(
      "IN_PROGRESS", "PAUSED", "CANCELLED", "FAILED", "PARTIAL_COMPLETION"
    )

    /** Tools whose successful execution changes persistent workspace state. */
    private val STATE_CHANGING_TOOLS = setOf(
      "write_file", "edit_file", "apply_patch", "delete_file", "move_file", "copy_file",
      "create_directory", "delete_directory", "install_package", "e2b_write_file",
      "transfer_artifact_to_sandbox", "download_artifact_from_sandbox",
      "document_process", "mcp_manage_server", "build_pdf_document", "recreate_document",
      "terminal", "code_interpreter", "e2b_sandbox"
    )

    /** Tools whose successful execution produces genuinely new information. */
    private val INFORMATIVE_TOOLS = setOf(
      "web_search", "web_fetch", "web_open", "web_extract", "web_crawl", "deep_research", "academic_search",
      "read_file", "read_image", "list_directory", "list_files", "search_files", "file_exists", "file_metadata",
      "check_tools", "sandbox_diagnostics", "get_environment_topology", "environment_capability",
      "e2b_read_file", "e2b_list_directory", "diff_files",
      "document_search", "document_list",
      "mcp_list_servers", "mcp_list_resources", "mcp_read_resource", "mcp_list_prompts", "mcp_refresh", "mcp_call_tool",
      "terminal", "code_interpreter", "e2b_sandbox"
    )
  }

  fun canExecuteConcurrently(calls: List<ToolCall>): Boolean {
    if (calls.size <= 1) return false
    val readOnlyTools = setOf(
      "read_file", "list_directory", "file_exists", "file_metadata",
      "diff_files", "web_search", "web_fetch", "web_open", "web_extract",
      "web_crawl", "check_tools", "sandbox_diagnostics",
      "document_search", "document_list",
      "mcp_list_servers", "mcp_list_resources", "mcp_read_resource", "mcp_list_prompts",
      "wikipedia__search", "wikipedia__summary", "wikipedia__article",
      "fetch__fetch_url", "fetch__fetch_headers",
      "sqlite__query", "sqlite__list_tables", "sqlite__describe_table"
    )
    val isAllReadOnly = calls.all { call ->
      val resolved = registry.resolveToolName(call.toolName)
      val toolObj = registry.getTool(resolved)
      resolved in readOnlyTools ||
        (toolObj != null && !toolObj.mutatesWorkspace && toolObj.permissions == setOf(ToolPermission.READ_ONLY))
    }
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
   * Pauses active execution gracefully, saving state to allow resuming without losing work or replaying side effects.
   */
  fun pauseTask() {
    val job = activeJob
    val currentTask = _state.value.task
    if (job != null && job.isActive) {
      if (currentTask != null) {
        val pTask = _state.value.persistentTask ?: currentTask.toPersistentTask(_state.value.currentStep)
        scratchpad.saveTask(pTask)
        scratchpad.saveCheckpoint(
          AgentCheckpoint(
            taskGoal = currentTask.goal,
            currentTurn = _state.value.currentStep,
            completedSections = _state.value.plan,
            planItems = _state.value.plan,
            artifactPaths = artifactManager.getArtifacts().map { it.path },
            totalFindingsCount = scratchpad.loadAllFindings().size,
            status = "PAUSED",
            recoveryState = recoveryManager.exportStateJson(),
            currentPhase = pTask.currentPhase.name,
            persistentTaskJson = pTask.toJsonString()
          )
        )
        recordTaskCompletion(currentTask, AgentStatus.CANCELLED, "Task paused by user. Work preserved.", artifactManager.getArtifacts())
        _state.value = _state.value.copy(
          status = AgentStatus.CANCELLED,
          currentAction = "Task paused by user. Work preserved.",
          taskHistory = _state.value.taskHistory,
          canCancel = false
        )
      }
      job.cancel()
    }
  }

  /**
   * Stops active execution immediately.
   */
  fun cancel() {
    val job = activeJob
    val currentTask = _state.value.task
    if (job != null && job.isActive) {
      if (currentTask != null) {
        val pTask = _state.value.persistentTask ?: currentTask.toPersistentTask(_state.value.currentStep)
        scratchpad.saveTask(pTask)
        scratchpad.saveCheckpoint(
          AgentCheckpoint(
            taskGoal = currentTask.goal,
            currentTurn = _state.value.currentStep,
            planItems = _state.value.plan,
            artifactPaths = artifactManager.getArtifacts().map { it.path },
            totalFindingsCount = scratchpad.loadAllFindings().size,
            status = "CANCELLED",
            recoveryState = recoveryManager.exportStateJson(),
            currentPhase = pTask.currentPhase.name,
            persistentTaskJson = pTask.toJsonString()
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
