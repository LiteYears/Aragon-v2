package com.example.agent.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative ToolDispatcher coordinating tool execution.
 *
 * Guarantees:
 * 1. Single-dispatch & idempotency: executes exactly once per unique tool call ID
 * 2. Pre-flight validation & security approval via ApprovalManager
 * 3. Unified recovery & repetition gating via UnifiedRecoveryManager
 * 4. Ground-truth Observation generation delivered to the reasoning loop
 */
class ToolDispatcher(
  val registry: ToolRegistry,
  val executor: ToolExecutor,
  val approvalManager: ApprovalManager = ApprovalManager(),
  val recoveryManager: UnifiedRecoveryManager = UnifiedRecoveryManager()
) {

  // Idempotency cache preventing duplicate invocation for identical callIds
  private val dispatchedCalls = ConcurrentHashMap<String, ToolResult>()

  /**
   * Resets execution and dispatch history when starting an isolated session.
   */
  fun reset() {
    dispatchedCalls.clear()
    recoveryManager.reset()
    executor.resetHistory()
  }

  /**
   * Dispatches a tool call through the strict validation and execution pipeline.
   * Returns both the authoritative ToolResult and the structured Observation for the LLM.
   */
  suspend fun dispatch(
    toolCall: ToolCall,
    taskId: String,
    context: ToolExecutionContext? = null
  ): Pair<ToolResult, Observation> {
    // 0. Canonical normalization before any lookup or idempotency check
    val normalizedCall = registry.normalizeToolCall(toolCall)
    val callId = normalizedCall.callId
    val tool = registry.getTool(normalizedCall.toolName)
    val isReadOnly = tool?.permissions?.contains(ToolPermission.READ_ONLY) == true && tool.permissions.size == 1

    // 1. Task-scoped Idempotency & single-dispatch check
    val cacheKey = "$taskId:$callId"
    if (!isReadOnly) {
      dispatchedCalls[cacheKey]?.let { cached ->
        if (cached.toolName == normalizedCall.toolName && cached.arguments == normalizedCall.arguments) {
          val obs = executor.createObservation(cached)
          return Pair(cached, obs)
        }
      }
    }

    // 2. Security & approval check on normalized call
    val approval = approvalManager.evaluate(normalizedCall, tool)
    if (!approval.isApproved) {
      val deniedResult = ToolResult(
        callId = callId,
        toolName = normalizedCall.toolName,
        status = ToolStatus.FAILED,
        arguments = normalizedCall.arguments,
        output = null,
        error = "Security Policy Violation: ${approval.reason}",
        errorType = ToolErrorCode.PERMISSION_DENIED,
        duration = 1L,
        exitCode = 1,
        artifacts = executor.artifactManager.getArtifacts(),
        executionContext = context,
        outcome = ToolExecutionOutcome.EXECUTION_FAILED
      )
      if (!isReadOnly) {
        dispatchedCalls[cacheKey] = deniedResult
      }
      return Pair(deniedResult, executor.createObservation(deniedResult))
    }

    // 3. Unified Pre-flight Gate (Deterministic Failure Repeat Guard & Loop Detection)
    when (val preFlight = recoveryManager.checkPreFlight(normalizedCall)) {
      is PreFlightDecision.BlockDeterministicFailure -> {
        val guardResult = ToolResult(
          callId = callId,
          toolName = normalizedCall.toolName,
          status = ToolStatus.FAILED,
          arguments = normalizedCall.arguments,
          output = null,
          error = "Deterministic Failure Repeat Guard: ${preFlight.priorError}. ${preFlight.recoveryHint}",
          errorType = ToolErrorCode.UNSUPPORTED_OPERATION,
          duration = 1L,
          exitCode = 1,
          artifacts = executor.artifactManager.getArtifacts(),
          executionContext = context,
          outcome = ToolExecutionOutcome.EXECUTION_FAILED
        )
        if (!isReadOnly) {
          dispatchedCalls[cacheKey] = guardResult
        }
        return Pair(guardResult, executor.createObservation(guardResult))
      }
      is PreFlightDecision.SkipLoop -> {
        val altList = preFlight.suggestedTool?.let { listOf(it) } ?: emptyList()
        val interventionResult = ToolResult(
          callId = callId,
          toolName = normalizedCall.toolName,
          status = ToolStatus.FAILED,
          arguments = normalizedCall.arguments,
          output = null,
          error = preFlight.reason,
          errorType = ToolErrorCode.REPETITION_GUARD,
          duration = 1L,
          exitCode = 1,
          artifacts = executor.artifactManager.getArtifacts(),
          executionContext = context,
          outcome = ToolExecutionOutcome.CONDITION_NOT_FOUND,
          metadata = mapOf("suggested_alternative_tools" to altList)
        )
        if (!isReadOnly) {
          dispatchedCalls[cacheKey] = interventionResult
        }
        return Pair(interventionResult, executor.createObservation(interventionResult))
      }
      is PreFlightDecision.HaltLoop -> {
        val haltResult = ToolResult(
          callId = callId,
          toolName = normalizedCall.toolName,
          status = ToolStatus.FAILED,
          arguments = normalizedCall.arguments,
          output = null,
          error = preFlight.fatalMessage,
          errorType = ToolErrorCode.REPETITION_GUARD,
          duration = 1L,
          exitCode = 1,
          artifacts = executor.artifactManager.getArtifacts(),
          executionContext = context,
          outcome = ToolExecutionOutcome.EXECUTION_FAILED
        )
        if (!isReadOnly) {
          dispatchedCalls[cacheKey] = haltResult
        }
        return Pair(haltResult, executor.createObservation(haltResult))
      }
      is PreFlightDecision.Proceed -> {
        // Proceed with normal execution
      }
    }

    // 4. Authoritative execution within timeout and cancellation scope
    val result = executor.execute(normalizedCall, context)

    // 5. Create structured observation
    val observation = executor.createObservation(result)

    // 6. Record execution in Unified Recovery Manager
    recoveryManager.evaluateExecution(normalizedCall, result, observation, context)

    if (!isReadOnly) {
      dispatchedCalls[cacheKey] = result
    }

    return Pair(result, observation)
  }
}
