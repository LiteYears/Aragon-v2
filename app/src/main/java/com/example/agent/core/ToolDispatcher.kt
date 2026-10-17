package com.example.agent.core

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Record of a dispatched action signature and its observation hash for sliding window repetition tracking.
 */
private data class ActionRecord(
  val callSig: String,
  val obsHash: String
)

/**
 * Authoritative ToolDispatcher coordinating tool execution.
 *
 * Guarantees:
 * 1. Single-dispatch & idempotency: executes exactly once per unique tool call ID
 * 2. Pre-flight validation & security approval via ApprovalManager
 * 3. Anti-stagnation & destructive command retry prevention (FIX-01: canonical argument & observation hashing)
 * 4. Ground-truth Observation generation delivered to the reasoning loop
 */
class ToolDispatcher(
  val registry: ToolRegistry,
  val executor: ToolExecutor,
  val approvalManager: ApprovalManager = ApprovalManager()
) {

  // Idempotency cache preventing duplicate invocation for identical callIds
  private val dispatchedCalls = ConcurrentHashMap<String, ToolResult>()
  private val actionHistory = CopyOnWriteArrayList<ActionRecord>()

  // Tracks execution outcomes by canonical call signature to stop impossible retries on deterministic failures
  private val lastResultByAction = ConcurrentHashMap<String, ToolResult>()

  // Tracks the latest observation hash by canonical call signature to detect zero-progress loops
  private val lastObsByCallSig = ConcurrentHashMap<String, String>()

  /**
   * Resets execution and dispatch history when starting an isolated session.
   */
  fun reset() {
    dispatchedCalls.clear()
    actionHistory.clear()
    lastResultByAction.clear()
    lastObsByCallSig.clear()
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
        // Guarantee duplicate-call protection: Only return cached result if it matches tool name and arguments
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
        executionContext = context
      )
      if (!isReadOnly) {
        dispatchedCalls[cacheKey] = deniedResult
      }
      return Pair(deniedResult, executor.createObservation(deniedResult))
    }

    // 2b. Pre-flight Environment Compatibility Check (E2B Cloud MicroVM is the unified sandbox environment)
    // No artificial environment mismatch gating.

    // 3. Anti-stagnation & destructive retry guard (FIX-01: canonical argument hashing)
    val callSig = computeCallSignature(normalizedCall.toolName, normalizedCall.arguments)

    // 3a. Deterministic failure repeat guard:
    // If the immediate previous call for this exact command failed deterministically
    // (e.g. file not found, command not found, or environment mismatch),
    // retrying the exact same call without changing inputs or transferring the file is guaranteed to fail.
    val priorResult = lastResultByAction[callSig]
    if (priorResult != null && !priorResult.isSuccess) {
      val deterministicErrors = setOf(
        ToolErrorCode.FILE_NOT_FOUND,
        ToolErrorCode.COMMAND_NOT_FOUND,
        ToolErrorCode.INVALID_ARGUMENTS,
        ToolErrorCode.ENVIRONMENT_MISMATCH,
        ToolErrorCode.PATH_NOT_ALLOWED,
        ToolErrorCode.CROSS_ENV_PATH_MISUSE
      )
      if (priorResult.errorType in deterministicErrors) {
        val guardMsg = "Deterministic Failure Repeat Guard: Repeated execution of '${normalizedCall.toolName}' blocked. " +
          "The previous identical invocation failed with ${priorResult.errorType?.code}: '${priorResult.error}'. " +
          "You must upload the missing artifact (via 'transfer_artifact_to_sandbox'), create it inside the E2B microVM, or adjust arguments before retrying."
        val guardResult = ToolResult(
          callId = callId,
          toolName = normalizedCall.toolName,
          status = ToolStatus.FAILED,
          arguments = normalizedCall.arguments,
          output = null,
          error = guardMsg,
          errorType = ToolErrorCode.UNSUPPORTED_OPERATION,
          duration = 1L,
          exitCode = 1,
          artifacts = executor.artifactManager.getArtifacts(),
          executionContext = context
        )
        if (!isReadOnly) {
          dispatchedCalls[cacheKey] = guardResult
        }
        return Pair(guardResult, executor.createObservation(guardResult))
      }
    }

    // 3b. Repetition Guard (FIX-01: argument & observation hashing across sliding window N=12)
    val slidingWindow = actionHistory.takeLast(12)
    val callSigOccurrences = slidingWindow.count { it.callSig == callSig }
    val priorObsHash = lastObsByCallSig[callSig]
    val sameObsOccurrences = if (priorObsHash != null) {
      slidingWindow.count { it.callSig == callSig && it.obsHash == priorObsHash }
    } else 0

    // Trip ONLY IF the exact same call_sig appears >= 3 times AND obs_sig appears >= 2 times without progress
    val shouldTrip = callSigOccurrences >= 3 && sameObsOccurrences >= 2

    if (shouldTrip) {
      val altTools = listOf("read_file", "write_file", "terminal", "http_request", "code_interpreter").filter { it != normalizedCall.toolName }
      val interventionMsg = "Repetition guard tripped: '${normalizedCall.toolName}' invoked $callSigOccurrences times with identical arguments and observations without progress. Suggested alternative tools: ${altTools.joinToString()}."
      val interventionResult = ToolResult(
        callId = callId,
        toolName = normalizedCall.toolName,
        status = ToolStatus.FAILED,
        arguments = normalizedCall.arguments,
        output = null,
        error = interventionMsg,
        errorType = ToolErrorCode.REPETITION_GUARD,
        duration = 1L,
        exitCode = 1,
        artifacts = executor.artifactManager.getArtifacts(),
        executionContext = context,
        metadata = mapOf("suggested_alternative_tools" to altTools)
      )
      if (!isReadOnly) {
        dispatchedCalls[cacheKey] = interventionResult
      }
      return Pair(interventionResult, executor.createObservation(interventionResult))
    }

    // 4. Authoritative execution within timeout and cancellation scope
    val result = executor.execute(normalizedCall, context)
    val obsHash = computeObservationHash(result)

    lastResultByAction[callSig] = result
    lastObsByCallSig[callSig] = obsHash
    actionHistory.add(ActionRecord(callSig, obsHash))

    if (!isReadOnly) {
      dispatchedCalls[cacheKey] = result
    }

    // 5. Create structured observation
    val observation = executor.createObservation(result)
    return Pair(result, observation)
  }

  private fun computeCallSignature(toolName: String, args: Map<String, Any?>): String {
    if (args.isEmpty()) return "$toolName::empty"
    val canonicalArgs = args.entries
      .sortedBy { it.key }
      .joinToString(",") { (k, v) -> "$k=${v?.toString() ?: "null"}" }
    val hash = MessageDigest.getInstance("SHA-256")
      .digest(canonicalArgs.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
    return "$toolName::$hash"
  }

  private fun computeObservationHash(result: ToolResult): String {
    val content = result.output ?: result.stdout ?: result.error ?: result.stderr ?: ""
    val hash = MessageDigest.getInstance("SHA-256")
      .digest(content.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
    return hash
  }

  private fun formatArguments(args: Map<String, Any?>): String {
    if (args.isEmpty()) return "none"
    return args.entries.sortedBy { it.key }.joinToString(", ") { (k, v) ->
      val s = v?.toString() ?: "null"
      val preview = if (s.length > 60) s.take(57) + "..." else s
      "$k: $preview"
    }
  }
}
