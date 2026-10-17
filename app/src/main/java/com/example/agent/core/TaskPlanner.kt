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
  val envIndex = EnvironmentStateIndex()
  private val steps = mutableListOf<PlanStep>()
  private val todos = mutableListOf<TodoItem>()
  private val capabilityCache = mutableMapOf<String, String>() // "AVAILABLE", "UNAVAILABLE", "UNKNOWN"
  private val capabilityFailureCounts = mutableMapOf<String, Int>()

  private var revision = 0
  private var replanReason: String? = null
  private var consecutiveFailures = 0
  private var lastToolName: String? = null
  private var noProgressTurnCount = 0

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
          summaryLower.contains("verified")
        )
        val isDeliverableAction = summaryLower.contains("written to") ||
                                  summaryLower.contains("saved '") ||
                                  summaryLower.contains("successfully wrote") ||
                                  summaryLower.contains("successfully saved")

        val isDone = isExplicitlyCompleted || isDeliverableAction || (
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
    if (steps.isNotEmpty()) {
      sb.appendLine("EXECUTION PLAN (revision $revision; goal: $goal):")
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

    // Only output additional dynamic todos if they exist and are not already represented in steps
    val stepNorms = steps.map { normalize(it.description) }.toSet()
    val extraTodos = todos.filter { normalize(it.description) !in stepNorms }
    if (steps.isNotEmpty() && extraTodos.isNotEmpty()) {
      sb.appendLine("ADDITIONAL DYNAMIC TODOS:")
      extraTodos.forEach { todo ->
        val reasonStr = if (todo.abandonReason != null) " (Abandoned reason: ${todo.abandonReason})" else ""
        sb.appendLine("- [${todo.status.uppercase()}] ${todo.id}: ${todo.description}$reasonStr")
      }
    }

    sb.appendLine(capabilityCacheSummary())

    if (replanReason != null) {
      sb.appendLine("REPLAN REQUIRED: $replanReason")
      sb.appendLine("Do not repeat the failed action blindly. Choose a concrete recovery step or a new path.")
    }
    return sb.toString().trim()
  }

  fun planDescriptions(): List<String> = steps.map { it.description }

  fun completedDescriptions(): List<String> = steps.filter { it.status == PlanStepStatus.COMPLETED }.map { it.description }

  fun pendingDescriptions(): List<String> = steps.filter {
    it.status == PlanStepStatus.PENDING || it.status == PlanStepStatus.IN_PROGRESS || it.status == PlanStepStatus.FAILED
  }.map { it.description }

  fun isBlocked(): Boolean = steps.any { it.status == PlanStepStatus.BLOCKED }

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

  fun parseCrossEnvIntent(planText: String?): String? {
    if (planText == null) return null
    val regex = Regex("""cross_env\s*[:=]\s*\{?[^}]*action\s*[:=]\s*["']?(transfer|stay_in)["']?""", RegexOption.IGNORE_CASE)
    val match = regex.find(planText)
    if (match != null) return match.groupValues[1].lowercase()
    if (planText.contains("\"action\": \"transfer\"", ignoreCase = true) || planText.contains("action: transfer", ignoreCase = true)) return "transfer"
    if (planText.contains("\"action\": \"stay_in\"", ignoreCase = true) || planText.contains("action: stay_in", ignoreCase = true)) return "stay_in"
    return null
  }

  fun validateCrossEnvPlan(
    planText: String?,
    toolCalls: List<ToolCall>,
    workspaceDir: java.io.File,
    isRemoteSandbox: Boolean
  ): String? {
    // E2B Cloud is the sole execution sandbox; files created via write_file/edit_file
    // are automatically pushed to E2B /home/user, so cross-env declarations are unnecessary.
    return null
  }

  companion object {
    const val MAX_PLAN_STEPS = 12
    const val MAX_ATTEMPTS_PER_STEP = 3
  }
}
