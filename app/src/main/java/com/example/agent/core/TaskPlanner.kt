package com.example.agent.core

import java.util.Locale

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
  val lastError: String? = null
)

/**
 * Deterministic planner state kept outside the model. The LLM proposes plans, but this
 * object is the source of truth for progress, retries, replanning signals, and blockage.
 */
class TaskPlanner(private val goal: String) {
  private val steps = mutableListOf<PlanStep>()
  private var revision = 0
  private var replanReason: String? = null
  private var consecutiveFailures = 0
  private var lastToolName: String? = null

  fun acceptModelPlan(planText: String?) {
    val proposed = parsePlan(planText)
    if (proposed.isEmpty()) return

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
    revision++
    if (replanReason != null && steps.none { it.status == PlanStepStatus.BLOCKED }) {
      replanReason = null
    }
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
      if (lastToolName == result.toolName) {
        // Repeated successful calls can still indicate that the model is stuck.
        replanReason = replanReason ?: "The same tool was selected repeatedly; reassess the plan."
      }
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
    if (steps.isEmpty()) {
      return "PLANNER: No model plan has been accepted yet. Create a short ordered plan before acting."
    }
    val sb = StringBuilder()
    sb.appendLine("PLANNER STATE (revision $revision; goal: $goal):")
    steps.forEach { step ->
      val attemptText = if (step.attempts > 0) ", attempts=${step.attempts}" else ""
      sb.appendLine("${step.number}. [${step.status.name}] ${step.description}$attemptText")
      if (!step.lastError.isNullOrBlank()) sb.appendLine("   Last error: ${step.lastError}")
    }
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

  companion object {
    const val MAX_PLAN_STEPS = 12
    const val MAX_ATTEMPTS_PER_STEP = 3
  }
}
