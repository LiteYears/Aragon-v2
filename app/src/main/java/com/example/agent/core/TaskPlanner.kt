package com.example.agent.core

import java.util.Locale
import java.util.UUID

/**
 * Lifecycle state for one actionable task in an agent plan.
 */
enum class PlanStepStatus {
  PENDING,
  IN_PROGRESS,
  COMPLETED,
  FAILED,
  BLOCKED
}

data class PlanStep(
  val number: Int,
  val description: String,
  val status: PlanStepStatus = PlanStepStatus.PENDING,
  val attempts: Int = 0,
  val lastError: String? = null,
  val isConcurrent: Boolean = false
)

/**
 * First-class typed item in the kernel's todo list.
 */
data class TodoItem(
  val id: String = "todo_" + UUID.randomUUID().toString().take(8),
  val description: String,
  var status: String = "pending", // "pending" | "in_progress" | "done" | "abandoned"
  val createdAtStep: Int = 1,
  var resolvedAtStep: Int? = null,
  var abandonReason: String? = null,
  var assignedTool: String? = null
)

data class ReflectionOutcome(
  val advancedTodoId: String?,
  val reflectionNote: String,
  val requiresTodoAmendment: Boolean,
  val transcriptWarning: String? = null
)

/**
 * Deterministic planner state kept outside the model.
 * The LLM proposes plans, but this object is the source of truth for progress,
 * retries, todo list state, capability caching, and reflection.
 */
class TaskPlanner(private val goal: String) {
  private val steps = mutableListOf<PlanStep>()
  private val todos = mutableListOf<TodoItem>()
  private val capabilityCache = mutableMapOf<String, String>() // "AVAILABLE", "UNAVAILABLE", "UNKNOWN"
  private val capabilityFailureCounts = mutableMapOf<String, Int>()

  private var currentPhase: TaskPhase = TaskPhase.DISCOVERY
  private val actionableSteps = mutableListOf<ActionableStep>()
  private val completionEvidence = mutableListOf<CompletionEvidenceItem>()
  private val observations = mutableListOf<String>()
  private val generatedArtifacts = mutableListOf<String>()

  private var revision = 0
  private var replanReason: String? = null
  private var consecutiveFailures = 0
  private var lastToolName: String? = null
  private var noProgressTurnCount = 0

  fun getCurrentPhase(): TaskPhase = currentPhase
  fun setPhase(phase: TaskPhase) { currentPhase = phase }

  fun recordProgress(madeProgress: Boolean = true) {
    if (madeProgress) {
      noProgressTurnCount = 0
    } else {
      noProgressTurnCount++
    }
  }

  fun getNoProgressCount(): Int = noProgressTurnCount
  fun isStagnant(limit: Int = 3): Boolean = noProgressTurnCount >= limit
  fun getReplanReason(): String? = replanReason
  fun clearReplanReason() { replanReason = null }

  fun acceptModelPlan(planText: String?) {
    val proposed = parsePlan(planText)
    if (proposed.isEmpty()) return

    if (currentPhase == TaskPhase.DISCOVERY) {
      currentPhase = TaskPhase.PLANNING
    }

    val currentDescriptions = steps.map { normalize(it.description) }
    val proposedNormalized = proposed.map { normalize(it) }
    // Idempotent plan acceptance: if proposed plan matches existing plan, do not overwrite or bump revision
    if (currentDescriptions == proposedNormalized && steps.isNotEmpty()) {
      return
    }

    val previousByDescription = steps.associateBy { normalize(it.description) }
    steps.clear()
    proposed.forEachIndexed { index, description ->
      val previous = previousByDescription[normalize(description)]
      steps += PlanStep(
        number = index + 1,
        description = description,
        status = previous?.status ?: PlanStepStatus.PENDING,
        attempts = previous?.attempts ?: 0,
        lastError = previous?.lastError
      )
    }

    // Sync todos with parsed plan steps while preserving state of existing items
    if (todos.isEmpty()) {
      proposed.forEachIndexed { idx, desc ->
        todos.add(
          TodoItem(
            id = "todo_${idx + 1}",
            description = desc,
            status = "pending",
            createdAtStep = 1
          )
        )
      }
    } else {
      val existingTodosByNorm = todos.associateBy { normalize(it.description) }
      val newTodos = mutableListOf<TodoItem>()
      proposed.forEachIndexed { idx, desc ->
        val norm = normalize(desc)
        val existing = existingTodosByNorm[norm]
        if (existing != null) {
          newTodos.add(existing)
        } else {
          newTodos.add(
            TodoItem(
              id = "todo_${idx + 1}",
              description = desc,
              status = "pending",
              createdAtStep = revision + 1
            )
          )
        }
      }
      todos.clear()
      todos.addAll(newTodos)
    }

    // Synchronize first-class ActionableSteps with dependency awareness and history preservation
    val existingActionableByNorm = actionableSteps.associateBy { normalize(it.description) }
    val newActionableSteps = mutableListOf<ActionableStep>()

    proposed.forEachIndexed { index, description ->
      val stepNum = index + 1
      val norm = normalize(description)
      val existing = existingActionableByNorm[norm]
      if (existing != null) {
        newActionableSteps.add(existing.copy(stepNumber = stepNum))
      } else {
        val stepId = "step_${stepNum}"
        val deps = extractDependencies(description, index, newActionableSteps)
        newActionableSteps.add(
          ActionableStep(
            id = stepId,
            stepNumber = stepNum,
            description = description,
            phase = TaskPhase.EXECUTION,
            status = ActionExecutionStatus.PLANNED,
            dependencies = deps
          )
        )
      }
    }

    // Preserve previously completed steps and mark active steps omitted from revised plan as SUPERSEDED
    for (oldStep in actionableSteps) {
      if (newActionableSteps.none { it.id == oldStep.id }) {
        if (oldStep.status == ActionExecutionStatus.COMPLETED) {
          newActionableSteps.add(oldStep)
        } else {
          newActionableSteps.add(oldStep.copy(status = ActionExecutionStatus.SUPERSEDED))
        }
      }
    }

    actionableSteps.clear()
    actionableSteps.addAll(newActionableSteps.sortedBy { it.stepNumber })

    if (currentPhase == TaskPhase.PLANNING || currentPhase == TaskPhase.DISCOVERY) {
      currentPhase = TaskPhase.EXECUTION
    }

    revision++
    if (replanReason != null && steps.none { it.status == PlanStepStatus.BLOCKED }) {
      replanReason = null
    }
  }

  fun getTodos(): List<TodoItem> = todos.toList()

  fun addTodo(description: String, step: Int = 1): TodoItem {
    val item = TodoItem(
      id = "todo_${todos.size + 1}",
      description = description,
      status = "pending",
      createdAtStep = step
    )
    todos.add(item)
    return item
  }

  fun abandonTodo(id: String, reason: String, step: Int) {
    val item = todos.find { it.id == id } ?: return
    item.status = "abandoned"
    item.abandonReason = reason.ifBlank { "Abandoned by agent" }
    item.resolvedAtStep = step
  }

  /**
   * Lightweight reflection step executed after every tool result.
   */
  fun reflect(
    currentStep: Int,
    toolName: String,
    toolArgs: Map<String, Any?>,
    result: ToolResult,
    observationSummary: String,
    boundTodoId: String? = null
  ): ReflectionOutcome {
    val targetItem = if (!boundTodoId.isNullOrBlank()) {
      todos.find { it.id == boundTodoId }
    } else {
      todos.find { it.status == "in_progress" } ?: todos.find { it.status == "pending" }
    }

    // Update capability cache based on outcome
    if (result.errorType == ToolErrorCode.COMMAND_NOT_FOUND) {
      val cmd = toolArgs["command"]?.toString()?.split(" ")?.firstOrNull() ?: toolName
      updateCapability(cmd, false)
    } else if (result.status == ToolStatus.SUCCEEDED) {
      val cmd = toolArgs["command"]?.toString()?.split(" ")?.firstOrNull() ?: toolName
      updateCapability(cmd, true)
    }

    if (targetItem == null && todos.any { it.status == "pending" || it.status == "in_progress" }) {
      return ReflectionOutcome(
        advancedTodoId = null,
        reflectionNote = "Tool '$toolName' ran without binding to an active todo item.",
        requiresTodoAmendment = true,
        transcriptWarning = "NOTICE: Action executed without advancing any declared todo item."
      )
    }

    if (targetItem != null) {
      if (result.status == ToolStatus.SUCCEEDED) {
        val summaryLower = observationSummary.lowercase()
        val descLower = targetItem.description.lowercase()
        val isExplicitlyCompleted = !boundTodoId.isNullOrBlank() && (
          summaryLower.contains("completed") ||
          summaryLower.contains("written") ||
          summaryLower.contains("saved") ||
          summaryLower.contains("created") ||
          summaryLower.contains("verified") ||
          summaryLower.contains("succeeded") ||
          summaryLower.contains("generated") ||
          summaryLower.contains("compiled")
        )
        val isDeliverableAction = summaryLower.contains("written to") ||
                                  summaryLower.contains("saved '") ||
                                  summaryLower.contains("successfully wrote") ||
                                  summaryLower.contains("successfully saved") ||
                                  summaryLower.contains("generated document") ||
                                  summaryLower.contains("compiled pdf") ||
                                  result.artifacts.isNotEmpty()

        val isAssignedToolSuccess = targetItem.assignedTool != null &&
                                    targetItem.assignedTool == toolName &&
                                    result.outcome == ToolExecutionOutcome.SUCCESS_WITH_RESULTS

        val isDone = isExplicitlyCompleted || isDeliverableAction || isAssignedToolSuccess || (
          !boundTodoId.isNullOrBlank() && summaryLower.contains("completed successfully")
        )

        if (isDone) {
          targetItem.status = "done"
          targetItem.resolvedAtStep = currentStep
          return ReflectionOutcome(
            advancedTodoId = targetItem.id,
            reflectionNote = "Todo '${targetItem.id}' [${targetItem.description}] marked DONE at step $currentStep.",
            requiresTodoAmendment = false
          )
        } else {
          targetItem.status = "in_progress"
          return ReflectionOutcome(
            advancedTodoId = targetItem.id,
            reflectionNote = "Todo '${targetItem.id}' in progress. Step $currentStep made positive progress.",
            requiresTodoAmendment = false
          )
        }
      } else {
        // Tool failed
        return ReflectionOutcome(
          advancedTodoId = targetItem.id,
          reflectionNote = "Action failed for todo '${targetItem.id}': ${result.error?.take(100)}. Record recovery or reason before proceeding.",
          requiresTodoAmendment = true,
          transcriptWarning = "WARNING: Todo '${targetItem.id}' encountered tool failure. Do not abandon silently."
        )
      }
    }

    return ReflectionOutcome(
      advancedTodoId = null,
      reflectionNote = "Exploration action completed.",
      requiresTodoAmendment = false
    )
  }

  fun updateCapability(tool: String, available: Boolean) {
    val clean = tool.trim().lowercase()
    if (clean.isBlank()) return
    capabilityCache[clean] = if (available) "AVAILABLE" else "UNAVAILABLE"
    if (!available) {
      capabilityFailureCounts[clean] = (capabilityFailureCounts[clean] ?: 0) + 1
    } else {
      capabilityFailureCounts[clean] = 0
    }
  }

  fun getCapability(tool: String): String {
    return capabilityCache[tool.trim().lowercase()] ?: "UNKNOWN"
  }

  fun isRepeatedlyUnavailable(tool: String): Boolean {
    val clean = tool.trim().lowercase()
    return (capabilityFailureCounts[clean] ?: 0) >= 2
  }

  fun capabilityCacheSummary(): String {
    if (capabilityCache.isEmpty()) return "CAPABILITY CACHE: (No probed binaries yet)"
    val entries = capabilityCache.entries.joinToString(", ") { "${it.key}: ${it.value}" }
    return "CAPABILITY CACHE (Session Learned State): [$entries]"
  }

  fun recordToolResult(result: ToolResult, summary: String = result.error ?: result.output.orEmpty()) {
    if (steps.isEmpty()) {
      steps += PlanStep(1, "Complete the user objective")
    }

    val activeIndex = steps.indexOfFirst { it.status == PlanStepStatus.IN_PROGRESS }
      .takeIf { it >= 0 }
      ?: steps.indexOfFirst { it.status == PlanStepStatus.PENDING }

    if (activeIndex >= 0) {
      val step = steps[activeIndex]
      if (result.status == ToolStatus.SUCCEEDED) {
        steps[activeIndex] = step.copy(status = PlanStepStatus.COMPLETED, lastError = null)
      } else {
        val error = (result.error ?: summary).trim().take(500).ifBlank { "Tool ${result.toolName} failed" }
        steps[activeIndex] = step.copy(
          status = if (step.attempts + 1 >= MAX_ATTEMPTS_PER_STEP) PlanStepStatus.BLOCKED else PlanStepStatus.FAILED,
          attempts = step.attempts + 1,
          lastError = error
        )
        replanReason = "Tool '${result.toolName}' failed: $error"
      }
    }

    if (result.status == ToolStatus.SUCCEEDED) {
      consecutiveFailures = 0
    } else {
      consecutiveFailures++
    }
    lastToolName = result.toolName
  }

  fun markToolStarted(toolName: String) {
    if (steps.isEmpty()) steps += PlanStep(1, "Complete the user objective")
    val pendingIndex = steps.indexOfFirst { it.status == PlanStepStatus.PENDING || it.status == PlanStepStatus.FAILED }
    if (pendingIndex >= 0) {
      val step = steps[pendingIndex]
      steps[pendingIndex] = step.copy(status = PlanStepStatus.IN_PROGRESS)
    }
  }

  fun markMultitaskingStarted(toolNames: List<String>) {
    if (steps.isEmpty()) steps += PlanStep(1, "Complete the user objective")
    var marked = 0
    for (i in steps.indices) {
      if ((steps[i].status == PlanStepStatus.PENDING || steps[i].status == PlanStepStatus.FAILED) && marked < toolNames.size) {
        steps[i] = steps[i].copy(status = PlanStepStatus.IN_PROGRESS, isConcurrent = true)
        marked++
      }
    }
  }

  fun restoreProgress(completed: List<String>, pending: List<String>) {
    steps.forEachIndexed { index, step ->
      val normalized = normalize(step.description)
      steps[index] = when {
        completed.any { normalize(it) == normalized } -> step.copy(status = PlanStepStatus.COMPLETED)
        pending.any { normalize(it) == normalized } -> step.copy(status = PlanStepStatus.PENDING)
        else -> step
      }
    }
  }

  fun markCompletionRejected(details: String) {
    replanReason = "Completion verification rejected: ${details.trim().take(500)}"
    val activeIndex = steps.indexOfFirst { it.status == PlanStepStatus.IN_PROGRESS }
    if (activeIndex >= 0) {
      steps[activeIndex] = steps[activeIndex].copy(status = PlanStepStatus.PENDING)
    }
  }

  fun markProviderFailure(message: String) {
    replanReason = "LLM/provider recovery required: ${message.trim().take(500)}"
  }

  fun requestReplan(reason: String) {
    replanReason = reason.trim().take(500).ifBlank { "The current plan needs reassessment." }
  }

  fun hasReplanRequest(): Boolean = replanReason != null || consecutiveFailures >= 2

  fun context(): String {
    val sb = StringBuilder()
    sb.appendLine("TASK PHASE: [${currentPhase.name}] (revision $revision; goal: $goal)")

    if (actionableSteps.isNotEmpty()) {
      sb.appendLine("ACTIONABLE STEPS (Dependencies & Execution State):")
      actionableSteps.forEach { step ->
        val depText = if (step.dependencies.isNotEmpty()) " [Requires: ${step.dependencies.joinToString()}]" else ""
        val toolText = if (!step.assignedTool.isNullOrBlank()) " (tool: ${step.assignedTool})" else ""
        val attemptText = if (step.attempts > 0) " (attempts: ${step.attempts})" else ""
        val statusTag = "[${step.status.name}]"
        sb.appendLine("${step.stepNumber}. $statusTag ${step.id}: ${step.description}$depText$toolText$attemptText")
        if (!step.lastError.isNullOrBlank()) {
          sb.appendLine("   Last error: ${step.lastError}")
        }
      }

      val readySteps = getReadySteps()
      if (readySteps.isNotEmpty()) {
        sb.appendLine("READY FOR EXECUTION: [${readySteps.joinToString { "${it.id} (#${it.stepNumber})" }}]")
      }
    } else if (steps.isNotEmpty()) {
      sb.appendLine("EXECUTION PLAN:")
      steps.forEach { step ->
        val matchingTodo = todos.find { normalize(it.description) == normalize(step.description) }
        val todoTag = if (matchingTodo != null) "[${matchingTodo.id}] " else ""
        val attemptText = if (step.attempts > 0) " (attempts: ${step.attempts})" else ""
        sb.appendLine("${step.number}. [${step.status.name}] $todoTag${step.description}$attemptText")
        if (!step.lastError.isNullOrBlank()) sb.appendLine("   Last error: ${step.lastError}")
      }
    } else if (todos.isNotEmpty()) {
      sb.appendLine("EXPLICIT TODO LIST (First-Class State):")
      todos.forEach { todo ->
        val reasonStr = if (todo.abandonReason != null) " (Abandoned reason: ${todo.abandonReason})" else ""
        sb.appendLine("- [${todo.status.uppercase()}] ${todo.id}: ${todo.description}$reasonStr")
      }
    }

    if (completionEvidence.isNotEmpty()) {
      sb.appendLine("VERIFIABLE COMPLETION EVIDENCE:")
      completionEvidence.forEach { ev ->
        val status = if (ev.verified) "VERIFIED" else "PENDING_VERIFICATION"
        val target = if (!ev.target.isNullOrBlank()) " -> ${ev.target}" else ""
        sb.appendLine("- [$status] ${ev.type}: ${ev.description}$target")
      }
    }

    sb.appendLine(capabilityCacheSummary())

    if (replanReason != null) {
      sb.appendLine("REPLAN REQUIRED: $replanReason")
      sb.appendLine("Do not repeat the failed action blindly. Choose a concrete recovery step or a new path.")
    }
    return sb.toString().trim()
  }

  fun getActionableSteps(): List<ActionableStep> = actionableSteps.toList()

  fun getReadySteps(): List<ActionableStep> {
    val completedIds = actionableSteps.filter { it.status == ActionExecutionStatus.COMPLETED }.map { it.id }.toSet()
    return actionableSteps.filter { step ->
      step.status == ActionExecutionStatus.PLANNED && step.dependencies.all { it in completedIds }
    }
  }

  fun isStepReady(step: ActionableStep): Boolean {
    val completedIds = actionableSteps.filter { it.status == ActionExecutionStatus.COMPLETED }.map { it.id }.toSet()
    return step.dependencies.all { it in completedIds }
  }

  fun markStepDispatched(stepId: String, toolCallId: String, toolName: String) {
    val idx = actionableSteps.indexOfFirst { it.id == stepId }
    if (idx >= 0) {
      val cur = actionableSteps[idx]
      actionableSteps[idx] = cur.copy(
        status = ActionExecutionStatus.DISPATCHED,
        toolCallId = toolCallId,
        assignedTool = toolName
      )
    }
  }

  fun markStepExecuting(stepId: String) {
    val idx = actionableSteps.indexOfFirst { it.id == stepId }
    if (idx >= 0) {
      val cur = actionableSteps[idx]
      actionableSteps[idx] = cur.copy(status = ActionExecutionStatus.EXECUTING)
    }
  }

  fun markStepCompleted(
    stepId: String,
    observation: String? = null,
    artifact: String? = null,
    evidence: CompletionEvidenceItem? = null
  ) {
    val idx = actionableSteps.indexOfFirst { it.id == stepId }
    if (idx >= 0) {
      val cur = actionableSteps[idx]
      val newObs = if (observation != null) cur.observations + observation else cur.observations
      val newArts = if (artifact != null) cur.artifacts + artifact else cur.artifacts
      val newEv = if (evidence != null) cur.completionEvidence + evidence.description else cur.completionEvidence
      actionableSteps[idx] = cur.copy(
        status = ActionExecutionStatus.COMPLETED,
        observations = newObs,
        artifacts = newArts,
        completionEvidence = newEv
      )
      if (observation != null) recordObservation(observation)
      if (artifact != null) recordArtifact(artifact)
      if (evidence != null) recordEvidence(evidence)

      // Sync legacy steps and todos
      val norm = normalize(cur.description)
      val stepIdx = steps.indexOfFirst { normalize(it.description) == norm }
      if (stepIdx >= 0) steps[stepIdx] = steps[stepIdx].copy(status = PlanStepStatus.COMPLETED)
      val todoItem = todos.find { normalize(it.description) == norm }
      if (todoItem != null) todoItem.status = "done"
    }
  }

  fun markStepFailed(stepId: String, error: String, unrecoverable: Boolean = false) {
    val idx = actionableSteps.indexOfFirst { it.id == stepId }
    if (idx >= 0) {
      val cur = actionableSteps[idx]
      val newStatus = if (unrecoverable) ActionExecutionStatus.SUPERSEDED else ActionExecutionStatus.FAILED
      actionableSteps[idx] = cur.copy(
        status = newStatus,
        attempts = cur.attempts + 1,
        lastError = error
      )
      if (unrecoverable) {
        // Skip downstream dependent steps
        actionableSteps.forEachIndexed { i, step ->
          if (step.dependencies.contains(stepId) && step.status == ActionExecutionStatus.PLANNED) {
            actionableSteps[i] = step.copy(
              status = ActionExecutionStatus.SKIPPED,
              lastError = "Prerequisite step '$stepId' failed unrecoverably: $error"
            )
          }
        }
      }
    }
  }

  fun completeInformationalAndRemainingSteps(conclusion: String) {
    val uncompleted = actionableSteps.filter {
      it.status == ActionExecutionStatus.PLANNED || it.status == ActionExecutionStatus.DISPATCHED || it.status == ActionExecutionStatus.EXECUTING
    }
    for (step in uncompleted) {
      val isInformationalOrConclusion = step.assignedTool == null ||
        step.description.contains("summar", ignoreCase = true) ||
        step.description.contains("conclu", ignoreCase = true) ||
        step.description.contains("present", ignoreCase = true) ||
        step.description.contains("deliver", ignoreCase = true) ||
        step.description.contains("synthes", ignoreCase = true) ||
        step.description.contains("explain", ignoreCase = true) ||
        step.description.contains("recommend", ignoreCase = true) ||
        step.description.contains("verify", ignoreCase = true) ||
        step.description.contains("review", ignoreCase = true) ||
        step.description.contains("report", ignoreCase = true) ||
        step.description.contains("answer", ignoreCase = true)
      if (isInformationalOrConclusion) {
        val evidenceItem = CompletionEvidenceItem(
          type = "CONCLUSION_SYNTHESIS",
          description = step.description,
          target = "Conclusion",
          verified = true,
          details = conclusion.take(200)
        )
        markStepCompleted(step.id, observation = conclusion.take(300), evidence = evidenceItem)
      }
    }
  }

  fun forceCompleteAllRemainingSteps(conclusion: String) {
    actionableSteps.forEachIndexed { idx, step ->
      if (step.status != ActionExecutionStatus.COMPLETED && step.status != ActionExecutionStatus.SKIPPED) {
        actionableSteps[idx] = step.copy(
          status = ActionExecutionStatus.COMPLETED,
          completionEvidence = step.completionEvidence + "Satisfied upon final delivery",
          observations = if (step.observations.isEmpty()) listOf(conclusion.take(200)) else step.observations
        )
      }
    }
    steps.forEachIndexed { idx, s ->
      if (s.status != PlanStepStatus.COMPLETED) {
        steps[idx] = s.copy(status = PlanStepStatus.COMPLETED)
      }
    }
    todos.forEach {
      if (it.status != "done") {
        it.status = "done"
      }
    }
  }

  fun markStepSkipped(stepId: String, reason: String) {
    val idx = actionableSteps.indexOfFirst { it.id == stepId }
    if (idx >= 0) {
      val cur = actionableSteps[idx]
      actionableSteps[idx] = cur.copy(
        status = ActionExecutionStatus.SKIPPED,
        lastError = reason
      )
    }
  }

  fun recordEvidence(item: CompletionEvidenceItem) {
    if (completionEvidence.none { it.type == item.type && it.target == item.target && it.verified == item.verified }) {
      completionEvidence.add(item)
    }
  }

  fun getCompletionEvidence(): List<CompletionEvidenceItem> = completionEvidence.toList()

  fun recordArtifact(path: String) {
    val clean = path.trim()
    if (clean.isNotBlank() && !generatedArtifacts.contains(clean)) {
      generatedArtifacts.add(clean)
    }
  }

  fun recordObservation(obs: String) {
    val clean = obs.trim()
    if (clean.isNotBlank()) {
      observations.add(clean)
      if (observations.size > 50) {
        observations.removeAt(0)
      }
    }
  }

  fun isAllRequiredStepsCompleted(): Boolean {
    val active = actionableSteps.filter {
      it.status != ActionExecutionStatus.SKIPPED && it.status != ActionExecutionStatus.SUPERSEDED
    }
    return active.isNotEmpty() && active.all { it.status == ActionExecutionStatus.COMPLETED }
  }

  fun toPersistentTask(
    sessionId: String,
    turn: Int,
    maxTurns: Int = 50,
    status: AgentStatus = AgentStatus.RUNNING,
    error: String? = null,
    conclusion: String? = null
  ): PersistentTask {
    return PersistentTask(
      id = "task_" + UUID.randomUUID().toString().take(8),
      sessionId = sessionId,
      requestedOutcome = goal,
      currentPhase = currentPhase,
      actionableSteps = actionableSteps.toList(),
      executionStatus = status,
      observations = observations.toList(),
      generatedArtifacts = generatedArtifacts.toList(),
      completionEvidence = completionEvidence.toList(),
      revision = revision,
      currentTurn = turn,
      maxTurns = maxTurns,
      error = error,
      conclusion = conclusion
    )
  }

  fun restoreFromPersistentTask(task: PersistentTask) {
    currentPhase = task.currentPhase
    revision = task.revision
    actionableSteps.clear()
    actionableSteps.addAll(task.actionableSteps)
    completionEvidence.clear()
    completionEvidence.addAll(task.completionEvidence)
    observations.clear()
    observations.addAll(task.observations)
    generatedArtifacts.clear()
    generatedArtifacts.addAll(task.generatedArtifacts)

    // Sync legacy steps and todos
    steps.clear()
    task.actionableSteps.forEach { aStep ->
      val pStatus = when (aStep.status) {
        ActionExecutionStatus.COMPLETED -> PlanStepStatus.COMPLETED
        ActionExecutionStatus.EXECUTING, ActionExecutionStatus.DISPATCHED -> PlanStepStatus.IN_PROGRESS
        ActionExecutionStatus.FAILED, ActionExecutionStatus.BLOCKED -> PlanStepStatus.FAILED
        ActionExecutionStatus.SKIPPED, ActionExecutionStatus.SUPERSEDED -> PlanStepStatus.BLOCKED
        ActionExecutionStatus.PLANNED -> PlanStepStatus.PENDING
      }
      steps.add(
        PlanStep(
          number = aStep.stepNumber,
          description = aStep.description,
          status = pStatus,
          attempts = aStep.attempts,
          lastError = aStep.lastError
        )
      )
    }

    todos.clear()
    task.actionableSteps.forEach { aStep ->
      val todoStatus = when (aStep.status) {
        ActionExecutionStatus.COMPLETED -> "done"
        ActionExecutionStatus.EXECUTING, ActionExecutionStatus.DISPATCHED -> "in_progress"
        ActionExecutionStatus.SKIPPED, ActionExecutionStatus.SUPERSEDED -> "abandoned"
        else -> "pending"
      }
      todos.add(
        TodoItem(
          id = aStep.id,
          description = aStep.description,
          status = todoStatus,
          createdAtStep = 1,
          assignedTool = aStep.assignedTool
        )
      )
    }
  }

  fun planDescriptions(): List<String> = steps.map { it.description }

  fun completedDescriptions(): List<String> = steps.filter { it.status == PlanStepStatus.COMPLETED }.map { it.description }

  fun pendingDescriptions(): List<String> = steps.filter {
    it.status == PlanStepStatus.PENDING || it.status == PlanStepStatus.IN_PROGRESS || it.status == PlanStepStatus.FAILED
  }.map { it.description }

  fun isBlocked(): Boolean = steps.any { it.status == PlanStepStatus.BLOCKED }

  fun setReplanReason(reason: String) {
    replanReason = reason
  }

  fun markStepBlocked(stepNumber: Int, reason: String) {
    val idx = steps.indexOfFirst { it.number == stepNumber }
    if (idx >= 0) {
      steps[idx] = steps[idx].copy(
        status = PlanStepStatus.BLOCKED,
        lastError = reason
      )
      replanReason = "Step $stepNumber blocked: $reason"
    }
  }

  fun isStepIneffective(limit: Int = 3): Boolean {
    val active = steps.firstOrNull { it.status == PlanStepStatus.IN_PROGRESS || it.status == PlanStepStatus.FAILED }
    return active != null && active.attempts >= limit
  }

  private fun extractDependencies(description: String, currentStepIdx: Int, existingSteps: List<ActionableStep>): List<String> {
    val depMatch = Regex("(?i)(?:\\[(?:deps?|after):\\s*([0-9,\\s]+)\\]|(?:depends on|after|prerequisite|requires)\\s*(?:step\\s*)?([0-9,\\s]+))").find(description)
    if (depMatch != null) {
      val rawNumbers = (depMatch.groupValues[1].ifBlank { depMatch.groupValues[2] })
      val parsedStepNums = Regex("\\d+").findAll(rawNumbers).mapNotNull { it.value.toIntOrNull() }.toList()
      val matchedIds = mutableListOf<String>()
      for (num in parsedStepNums) {
        val target = existingSteps.firstOrNull { it.stepNumber == num }
        if (target != null) {
          matchedIds.add(target.id)
        } else {
          matchedIds.add("step_$num")
        }
      }
      if (matchedIds.isNotEmpty()) return matchedIds
    }

    val isExplicitConcurrent = description.contains("(parallel)", ignoreCase = true) ||
      description.contains("(concurrent)", ignoreCase = true) ||
      description.contains("[parallel]", ignoreCase = true)
    if (isExplicitConcurrent) return emptyList()

    if (currentStepIdx > 0 && currentStepIdx - 1 < existingSteps.size) {
      return listOf(existingSteps[currentStepIdx - 1].id)
    }

    return emptyList()
  }

  private fun parsePlan(planText: String?): List<String> {
    if (planText.isNullOrBlank()) return emptyList()
    return planText.lines()
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .map { line ->
        line
          .removePrefix("- [ ] ").removePrefix("- [x] ")
          .removePrefix("* [ ] ").removePrefix("* [x] ")
          .replace(Regex("^\\d+[.)]\\s*"), "")
          .removePrefix("- ").removePrefix("* ")
          .trim()
      }
      .filter { it.length >= 3 }
      .distinctBy { normalize(it) }
      .take(MAX_PLAN_STEPS)
  }

  private fun normalize(value: String): String = value.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim()

  companion object {
    const val MAX_PLAN_STEPS = 12
    const val MAX_ATTEMPTS_PER_STEP = 3
  }
}
