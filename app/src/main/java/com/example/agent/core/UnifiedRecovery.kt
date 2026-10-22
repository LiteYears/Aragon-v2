package com.example.agent.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Actionable failure taxonomy for agent actions and tool executions.
 * Eliminates heuristic guessing and enables deterministic recovery dispatch.
 */
enum class ActionFailureCategory(val code: String, val description: String) {
  TIMEOUT("TIMEOUT", "Operation or network deadline exceeded"),
  TRANSIENT_NETWORK_FAILURE("TRANSIENT_NETWORK_FAILURE", "Transient network glitch, DNS failure, or connection drop"),
  RATE_LIMIT("RATE_LIMIT", "HTTP 429 or provider rate limit/quota threshold reached"),
  INVALID_ARGUMENTS("INVALID_ARGUMENTS", "Parameters missing, invalid schema, or incorrect path arguments"),
  MISSING_DEPENDENCY("MISSING_DEPENDENCY", "Target file, executable binary, package, or system library is missing"),
  PERMISSION_ERROR("PERMISSION_ERROR", "Permission denied, read-only restriction, or approval policy rejection"),
  MALFORMED_OUTPUT("MALFORMED_OUTPUT", "Empty, corrupted, or unparseable output where valid structure was required"),
  FAILED_POSTCONDITION("FAILED_POSTCONDITION", "Execution completed without error but required postcondition was not satisfied"),
  REPEATED_ACTION("REPEATED_ACTION", "Repetitive action or cyclic loop detected with zero measurable progress"),
  UNRECOVERABLE_FAILURE("UNRECOVERABLE_FAILURE", "Fatal environment corruption, exhausted execution budget, or unrecoverable system crash")
}

/**
 * Concrete recovery decisions for failed or stagnant actions.
 */
enum class RecoveryStrategy(val code: String, val description: String) {
  RETRY_WITH_BACKOFF("RETRY_WITH_BACKOFF", "Retry the action with bounded exponential backoff"),
  CHANGE_PARAMETERS("CHANGE_PARAMETERS", "Adjust parameters, correct path formatting, or provide missing arguments"),
  SWITCH_EQUIVALENT_TOOL("SWITCH_EQUIVALENT_TOOL", "Switch to a functionally equivalent alternative tool"),
  INSPECT_PARTIAL_RESULTS("INSPECT_PARTIAL_RESULTS", "Inspect truncated output or spillover artifact via pagination/search"),
  REVISE_PLAN("REVISE_PLAN", "Mark current plan step blocked and request model replanning"),
  STOP_WITH_EXPLANATION("STOP_WITH_EXPLANATION", "Terminate execution with a clear diagnostic explanation")
}

/**
 * Canonical structural signature of an action for loop and duplicate detection.
 * Does not rely on raw text comparison; canonicalizes tool name and argument keys.
 */
data class ActionSignature(
  val toolName: String,
  val canonicalArgs: String,
  val targetResource: String?,
  val callHash: String
) {
  companion object {
    fun compute(toolName: String, arguments: Map<String, Any?>): ActionSignature {
      val normTool = toolName.trim().lowercase(Locale.US)
      val sortedEntries = arguments.entries
        .filter { it.key != "call_id" && it.key != "callId" && it.key != "sessionId" && it.key != "taskId" }
        .sortedBy { it.key }
        .map { (k, v) ->
          val valStr = when (v) {
            is Number, is Boolean -> v.toString()
            is String -> v.trim()
            null -> "null"
            else -> v.toString().trim()
          }
          "$k=$valStr"
        }
      val canonicalArgs = sortedEntries.joinToString(";")

      val targetResource = arguments["path"]?.toString()
        ?: arguments["file"]?.toString()
        ?: arguments["target"]?.toString()
        ?: arguments["url"]?.toString()
        ?: arguments["command"]?.toString()?.split(" ")?.firstOrNull()

      val rawToHash = "$normTool($canonicalArgs)"
      val md = MessageDigest.getInstance("SHA-256")
      val hashBytes = md.digest(rawToHash.toByteArray(StandardCharsets.UTF_8))
      val callHash = hashBytes.take(10).joinToString("") { "%02x".format(it) }

      return ActionSignature(
        toolName = normTool,
        canonicalArgs = canonicalArgs,
        targetResource = targetResource,
        callHash = callHash
      )
    }
  }
}

/**
 * Authoritative evaluation conclusion emitted after an action completes.
 * Strictly separated from raw tool observations.
 */
data class EvaluationConclusion(
  val callId: String,
  val toolName: String,
  val isSatisfied: Boolean,
  val madeProgress: Boolean,
  val failureCategory: ActionFailureCategory?,
  val recoveryStrategy: RecoveryStrategy,
  val explanation: String,
  val suggestedTool: String? = null,
  val suggestedParams: Map<String, Any?>? = null,
  val backoffMs: Long = 0L,
  val retryAttempt: Int = 0,
  val maxRetries: Int = 1,
  val isDeterministic: Boolean = true,
  val toolFailureClass: ToolFailureClass? = null,
  val structuredError: StructuredErrorReport? = null
) {
  /**
   * Generates high-signal system guidance formatted for LLM instruction injection.
   */
  fun toSystemGuidancePrompt(): String {
    val sb = StringBuilder()
    val failureClassCode = toolFailureClass?.code ?: failureCategory?.code ?: "PROGRESS_CHECK"
    sb.appendLine("RECOVERY GUIDANCE [$failureClassCode]:")
    sb.appendLine("Strategy: ${recoveryStrategy.code} - ${recoveryStrategy.description}")
    sb.appendLine("Diagnosis: $explanation")
    if (structuredError != null) {
      sb.appendLine("Exact Tool Error: ${structuredError.exactToolError}")
      sb.appendLine("Observed Facts: ${structuredError.observedFact}")
      if (!structuredError.inferredCause.isNullOrBlank()) {
        sb.appendLine("Inferred Cause: ${structuredError.inferredCause}")
      }
      sb.appendLine("Recovery Result: ${structuredError.recoveryResult}")
    }
    if (!suggestedTool.isNullOrBlank()) {
      sb.appendLine("Recommended Alternative Tool: '$suggestedTool'")
    }
    if (!suggestedParams.isNullOrEmpty()) {
      val paramsStr = suggestedParams.entries.joinToString(", ") { "${it.key}='${it.value}'" }
      sb.appendLine("Suggested Parameters: $paramsStr")
    }
    when (recoveryStrategy) {
      RecoveryStrategy.CHANGE_PARAMETERS -> {
        sb.appendLine("Action Required: Do NOT repeat the exact same call. Correct invalid arguments or paths before proceeding.")
      }
      RecoveryStrategy.SWITCH_EQUIVALENT_TOOL -> {
        sb.appendLine("Action Required: Use approved alternative tool '$suggestedTool'. Never repeat a blocked operation unchanged.")
      }
      RecoveryStrategy.INSPECT_PARTIAL_RESULTS -> {
        sb.appendLine("Action Required: Examine the captured excerpt or spillover file rather than repeating the full query.")
      }
      RecoveryStrategy.REVISE_PLAN -> {
        sb.appendLine("Action Required: This path is blocked. Mark the current step blocked, update your plan, and pursue an alternative path.")
      }
      RecoveryStrategy.RETRY_WITH_BACKOFF -> {
        sb.appendLine("Notice: Transient issue encountered. Retrying once with automatic backoff (${backoffMs}ms).")
      }
      RecoveryStrategy.STOP_WITH_EXPLANATION -> {
        sb.appendLine("Fatal: Unrecoverable situation or retry limit exhausted. Execution halted.")
      }
    }
    return sb.toString().trim()
  }
}

/**
 * History record of a dispatched recovery decision for persistence and auditability.
 */
data class RecoveryRecord(
  val timestamp: Long = System.currentTimeMillis(),
  val callId: String,
  val toolName: String,
  val actionSignature: String,
  val failureCategory: ActionFailureCategory,
  val recoveryStrategy: RecoveryStrategy,
  val attemptNumber: Int,
  val explanation: String,
  val resolved: Boolean = false
) {
  fun toJson(): JSONObject {
    return JSONObject().apply {
      put("timestamp", timestamp)
      put("callId", callId)
      put("toolName", toolName)
      put("actionSignature", actionSignature)
      put("failureCategory", failureCategory.name)
      put("recoveryStrategy", recoveryStrategy.name)
      put("attemptNumber", attemptNumber)
      put("explanation", explanation)
      put("resolved", resolved)
    }
  }

  companion object {
    fun fromJson(obj: JSONObject): RecoveryRecord? {
      return try {
        RecoveryRecord(
          timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
          callId = obj.optString("callId", ""),
          toolName = obj.optString("toolName", ""),
          actionSignature = obj.optString("actionSignature", ""),
          failureCategory = ActionFailureCategory.valueOf(obj.optString("failureCategory", ActionFailureCategory.UNRECOVERABLE_FAILURE.name)),
          recoveryStrategy = RecoveryStrategy.valueOf(obj.optString("recoveryStrategy", RecoveryStrategy.STOP_WITH_EXPLANATION.name)),
          attemptNumber = obj.optInt("attemptNumber", 1),
          explanation = obj.optString("explanation", ""),
          resolved = obj.optBoolean("resolved", false)
        )
      } catch (_: Exception) {
        null
      }
    }
  }
}

/**
 * Pre-flight decision returned before dispatching an action.
 */
sealed class PreFlightDecision {
  object Proceed : PreFlightDecision()
  data class SkipLoop(val reason: String, val strategy: RecoveryStrategy, val suggestedTool: String?) : PreFlightDecision()
  data class HaltLoop(val fatalMessage: String) : PreFlightDecision()
  data class BlockDeterministicFailure(val priorError: String, val recoveryHint: String) : PreFlightDecision()
}

/**
 * Event recorded in execution history for cycle and stagnation tracking.
 */
data class ExecutionHistoryEvent(
  val signature: ActionSignature,
  val observationHash: String,
  val outcome: ToolExecutionOutcome,
  val failureCategory: ActionFailureCategory?,
  val timestamp: Long = System.currentTimeMillis()
)

/**
 * Unified loop and cycle detector.
 * Detects:
 * 1. Consecutive identical tool calls
 * 2. Ping-pong cycles (A -> B -> A -> B) and 3-cycles (A -> B -> C -> A -> B -> C)
 * 3. Repeated errors of the same category
 * 4. Unchanged observations across multiple attempts
 * 5. Actions with zero measurable progress
 */
class UnifiedLoopDetector(val maxHistorySize: Int = 20) {

  private val history = CopyOnWriteArrayList<ExecutionHistoryEvent>()
  private val consecutiveSignatures = ConcurrentHashMap<String, Int>()
  private var lastSignature: ActionSignature? = null
  private var zeroProgressStreak = 0

  fun reset() {
    history.clear()
    consecutiveSignatures.clear()
    lastSignature = null
    zeroProgressStreak = 0
  }

  /**
   * Records an execution event into the sliding history window.
   */
  fun recordEvent(event: ExecutionHistoryEvent) {
    if (history.size >= maxHistorySize) {
      history.removeAt(0)
    }
    history.add(event)

    val last = lastSignature
    if (last != null && last.callHash == event.signature.callHash) {
      val count = (consecutiveSignatures[last.callHash] ?: 1) + 1
      consecutiveSignatures[last.callHash] = count
    } else {
      consecutiveSignatures[event.signature.callHash] = 1
    }
    lastSignature = event.signature

    if (event.outcome == ToolExecutionOutcome.SUCCESS_EMPTY ||
      event.outcome == ToolExecutionOutcome.CONDITION_NOT_FOUND ||
      event.outcome == ToolExecutionOutcome.EXECUTION_FAILED
    ) {
      zeroProgressStreak++
    } else {
      zeroProgressStreak = 0
    }
  }

  /**
   * Pre-flight loop check before dispatching a tool call.
   */
  fun checkPreFlight(sig: ActionSignature): PreFlightDecision {
    val currentConsecutive = if (lastSignature?.callHash == sig.callHash) {
      consecutiveSignatures[sig.callHash] ?: 0
    } else {
      0
    }

    // 1. Consecutive Repetition Checks
    if (currentConsecutive >= 5) {
      return PreFlightDecision.HaltLoop(
        "Fatal Stagnation: Tool '${sig.toolName}' was repeated $currentConsecutive times consecutively with identical arguments without progress. Halting."
      )
    }
    if (currentConsecutive >= 2) {
      val matchingRecentEvents = history.takeLast(currentConsecutive).filter { it.signature.callHash == sig.callHash }
      val allObservationsIdentical = matchingRecentEvents.size >= 2 && matchingRecentEvents.map { it.observationHash }.distinct().size <= 1
      val isZeroProgress = matchingRecentEvents.isNotEmpty() && matchingRecentEvents.all {
        it.outcome == ToolExecutionOutcome.SUCCESS_EMPTY ||
        it.outcome == ToolExecutionOutcome.CONDITION_NOT_FOUND ||
        it.outcome == ToolExecutionOutcome.EXECUTION_FAILED
      }

      if (allObservationsIdentical || isZeroProgress || currentConsecutive >= 4) {
        val alt = getAlternativeTool(sig.toolName)
        return PreFlightDecision.SkipLoop(
          reason = "Loop Guard: Tool '${sig.toolName}' with identical arguments was repeated $currentConsecutive times consecutively without new progress. Action skipped to protect execution budget.",
          strategy = RecoveryStrategy.SWITCH_EQUIVALENT_TOOL,
          suggestedTool = alt
        )
      }
    }

    // 2. Cycle Detection (Ping-Pong A -> B -> A -> B or 3-step A -> B -> C -> A -> B -> C)
    val cycleDetection = detectCycles(sig)
    if (cycleDetection != null) {
      return PreFlightDecision.SkipLoop(
        reason = cycleDetection,
        strategy = RecoveryStrategy.REVISE_PLAN,
        suggestedTool = getAlternativeTool(sig.toolName)
      )
    }

    return PreFlightDecision.Proceed
  }

  /**
   * Detects periodic repeating sub-sequences in the recent execution history.
   */
  private fun detectCycles(nextSig: ActionSignature): String? {
    if (history.size < 4) return null
    val hashes = history.map { it.signature.callHash } + nextSig.callHash
    val n = hashes.size

    // Check 2-cycle: A, B, A, B, A
    if (n >= 5) {
      val a = hashes[n - 1]
      val b = hashes[n - 2]
      if (a != b && hashes[n - 3] == a && hashes[n - 4] == b && (n < 6 || hashes[n - 5] == a)) {
        val toolA = nextSig.toolName
        val toolB = history.lastOrNull()?.signature?.toolName ?: "previous_tool"
        return "Cyclic Loop Detected: Alternating between '$toolA' and '$toolB' with no measurable state progress. Step skipped."
      }
    }

    // Check 3-cycle: A, B, C, A, B, C
    if (n >= 7) {
      val a = hashes[n - 1]
      val b = hashes[n - 2]
      val c = hashes[n - 3]
      if (a != b && b != c && a != c && hashes[n - 4] == a && hashes[n - 5] == b && hashes[n - 6] == c) {
        return "Cyclic Loop Detected: 3-step repeating cycle [${nextSig.toolName} -> ...] detected without progress."
      }
    }

    return null
  }

  /**
   * Checks whether the same observation hash has been returned repeatedly for this signature.
   */
  fun checkUnchangedObservations(sig: ActionSignature, currentObsHash: String): Boolean {
    val matches = history.filter { it.signature.callHash == sig.callHash && it.observationHash == currentObsHash }
    return matches.size >= 2
  }

  /**
   * Checks consecutive failure count of the exact same category.
   */
  fun checkConsecutiveCategoryFailures(category: ActionFailureCategory, limit: Int = 3): Boolean {
    val recent = history.takeLast(limit)
    if (recent.size < limit) return false
    return recent.all { it.failureCategory == category }
  }

  fun getZeroProgressStreak(): Int = zeroProgressStreak

  companion object {
    fun getAlternativeTool(toolName: String): String? {
      return when (toolName.lowercase(Locale.US)) {
        "read_file" -> "terminal"
        "write_file" -> "terminal"
        "edit_file" -> "write_file"
        "list_directory" -> "terminal"
        "web_fetch" -> "terminal"
        "web_search" -> "web_open"
        "document_search" -> "read_file"
        "terminal" -> "read_file"
        else -> null
      }
    }

    fun computeObservationHash(text: String?): String {
      if (text.isNullOrBlank()) return "empty_0"
      val trimmed = text.trim()
      val md = MessageDigest.getInstance("SHA-256")
      val bytes = md.digest(trimmed.toByteArray(StandardCharsets.UTF_8))
      return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
  }

  fun exportHistoryJson(): JSONArray {
    val arr = JSONArray()
    history.forEach { ev ->
      val obj = JSONObject().apply {
        put("toolName", ev.signature.toolName)
        put("canonicalArgs", ev.signature.canonicalArgs)
        put("targetResource", ev.signature.targetResource)
        put("callHash", ev.signature.callHash)
        put("obsHash", ev.observationHash)
        put("outcome", ev.outcome.name)
        put("failureCategory", ev.failureCategory?.name)
        put("timestamp", ev.timestamp)
      }
      arr.put(obj)
    }
    return arr
  }

  fun restoreHistoryJson(arr: JSONArray?) {
    if (arr == null) return
    history.clear()
    for (i in 0 until arr.length()) {
      val obj = arr.optJSONObject(i) ?: continue
      val sig = ActionSignature(
        toolName = obj.optString("toolName", ""),
        canonicalArgs = obj.optString("canonicalArgs", ""),
        targetResource = obj.optString("targetResource").takeIf { it.isNotEmpty() },
        callHash = obj.optString("callHash", "")
      )
      val catStr = obj.optString("failureCategory")
      val cat = if (catStr.isNotEmpty()) {
        try { ActionFailureCategory.valueOf(catStr) } catch (_: Exception) { null }
      } else null
      val outcomeStr = obj.optString("outcome", ToolExecutionOutcome.SUCCESS_WITH_RESULTS.name)
      val outcome = try { ToolExecutionOutcome.valueOf(outcomeStr) } catch (_: Exception) { ToolExecutionOutcome.SUCCESS_WITH_RESULTS }

      val event = ExecutionHistoryEvent(
        signature = sig,
        observationHash = obj.optString("obsHash", "empty_0"),
        outcome = outcome,
        failureCategory = cat,
        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
      )
      history.add(event)
    }
    lastSignature = history.lastOrNull()?.signature
  }
}

/**
 * Deterministic and semantic evaluator for tool execution results.
 * Emits typed EvaluationConclusions without mutating raw observations.
 */
class ActionEvaluator {

  /**
   * Evaluates a completed tool execution against deterministic criteria and execution history.
   */
  fun evaluate(
    toolCall: ToolCall,
    toolResult: ToolResult,
    observation: Observation,
    context: ToolExecutionContext?,
    loopDetector: UnifiedLoopDetector,
    priorAttempts: Int
  ): EvaluationConclusion {
    val callId = toolCall.callId
    val toolName = toolCall.toolName
    val isSuccess = toolResult.isSuccess
    val exitCode = toolResult.exitCode
    val stdout = toolResult.stdout ?: toolResult.output.orEmpty()
    val stderr = toolResult.stderr.orEmpty()
    val rawErr = toolResult.error.orEmpty()
    val outcome = observation.outcome

    // 1. SUCCESS_WITH_RESULTS & SUCCESS_EMPTY (Deterministic positive evaluation)
    if (isSuccess && outcome != ToolExecutionOutcome.EXECUTION_FAILED && outcome != ToolExecutionOutcome.CONDITION_NOT_FOUND) {
      // Check postconditions for state-mutating tools
      val postconditionFailure = checkPostconditions(toolCall, toolResult, context)
      if (postconditionFailure != null) {
        return EvaluationConclusion(
          callId = callId,
          toolName = toolName,
          isSatisfied = false,
          madeProgress = false,
          failureCategory = ActionFailureCategory.FAILED_POSTCONDITION,
          recoveryStrategy = RecoveryStrategy.CHANGE_PARAMETERS,
          explanation = postconditionFailure,
          suggestedTool = UnifiedLoopDetector.getAlternativeTool(toolName),
          retryAttempt = priorAttempts + 1,
          isDeterministic = true
        )
      }

      // Check unchanged observation stagnation
      val obsHash = UnifiedLoopDetector.computeObservationHash(stdout)
      val sig = ActionSignature.compute(toolName, toolCall.arguments)
      val isUnchanged = loopDetector.checkUnchangedObservations(sig, obsHash)
      if (isUnchanged) {
        return EvaluationConclusion(
          callId = callId,
          toolName = toolName,
          isSatisfied = false,
          madeProgress = false,
          failureCategory = ActionFailureCategory.REPEATED_ACTION,
          recoveryStrategy = RecoveryStrategy.SWITCH_EQUIVALENT_TOOL,
          explanation = "Unchanged observation: Tool '$toolName' produced identical output across multiple invocations without state delta.",
          suggestedTool = UnifiedLoopDetector.getAlternativeTool(toolName),
          isDeterministic = true
        )
      }

      val isTrunc = observation.truncation.isTruncated || outcome == ToolExecutionOutcome.TRUNCATED_OR_PARTIAL
      val strategy = if (isTrunc) RecoveryStrategy.INSPECT_PARTIAL_RESULTS else RecoveryStrategy.RETRY_WITH_BACKOFF
      return EvaluationConclusion(
        callId = callId,
        toolName = toolName,
        isSatisfied = true,
        madeProgress = true,
        failureCategory = if (isTrunc) ActionFailureCategory.MALFORMED_OUTPUT else null,
        recoveryStrategy = strategy,
        explanation = if (isTrunc) "Action succeeded with partial/truncated output. Inspect spillover if complete data is required." else "Action executed successfully.",
        isDeterministic = true
      )
    }

    // 2. CONDITION_NOT_FOUND (Search / Grep / Validation check produced 0 hits)
    if (outcome == ToolExecutionOutcome.CONDITION_NOT_FOUND) {
      val isSearch = ObservationPayloadPolicy.isSearchOrValidationCommand(toolCall.arguments["command"]?.toString().orEmpty()) ||
        toolName in listOf("web_search", "document_search", "file_exists")
      val explanation = if (isSearch) {
        "Search or validation condition evaluated with 0 hits: '${stdout.take(120).ifBlank { "Pattern or target not found." }}'."
      } else {
        "Target condition was not satisfied: ${rawErr.ifBlank { "0 matches found." }}"
      }
      return EvaluationConclusion(
        callId = callId,
        toolName = toolName,
        isSatisfied = false,
        madeProgress = false,
        failureCategory = ActionFailureCategory.MISSING_DEPENDENCY,
        recoveryStrategy = RecoveryStrategy.CHANGE_PARAMETERS,
        explanation = explanation,
        suggestedTool = UnifiedLoopDetector.getAlternativeTool(toolName),
        retryAttempt = priorAttempts + 1,
        isDeterministic = true
      )
    }

    // 3. FAILURE CLASSIFICATION & DETERMINISTIC RECOVERY
    val toolFailureClass = ErrorTaxonomyClassifier.classifyToolFailure(
      exitCode = exitCode,
      stdout = stdout,
      stderr = stderr,
      rawErr = rawErr,
      errorType = toolResult.errorType
    )

    // Legacy ActionFailureCategory for backward compatibility
    val category = when (toolFailureClass) {
      ToolFailureClass.TRANSIENT -> ActionFailureCategory.TRANSIENT_NETWORK_FAILURE
      ToolFailureClass.PERMISSION_POLICY -> ActionFailureCategory.PERMISSION_ERROR
      ToolFailureClass.INVALID_INPUT -> ActionFailureCategory.INVALID_ARGUMENTS
      ToolFailureClass.UNAVAILABLE_TOOL -> ActionFailureCategory.MISSING_DEPENDENCY
    }

    val (strategy, backoffMs, maxRetries, altTool, recoveryResult) = when (toolFailureClass) {
      ToolFailureClass.TRANSIENT -> {
        // Retry transient failures once
        if (priorAttempts == 0) {
          Tuple5(RecoveryStrategy.RETRY_WITH_BACKOFF, 1500L, 1, null, "Transient failure encountered. Retrying transient failure once with backoff.")
        } else {
          Tuple5(RecoveryStrategy.REVISE_PLAN, 0L, 1, null, "Transient retry limit reached (1 retry attempted). Halting retries and revising plan.")
        }
      }
      ToolFailureClass.PERMISSION_POLICY -> {
        // For policy failures, switch to an approved alternative. Never repeat a blocked operation unchanged.
        val alt = getApprovedPolicyAlternative(toolName, toolCall.arguments)
        Tuple5(RecoveryStrategy.SWITCH_EQUIVALENT_TOOL, 0L, 0, alt, "Permission/policy failure encountered. Switched to approved alternative tool '$alt'. Blocked operation must not be repeated unchanged.")
      }
      ToolFailureClass.INVALID_INPUT -> {
        // Never repeat a blocked operation unchanged. Preserve the original error.
        Tuple5(RecoveryStrategy.CHANGE_PARAMETERS, 0L, 0, null, "Invalid input or schema rejection. Parameters must be changed before retrying; cannot repeat unchanged.")
      }
      ToolFailureClass.UNAVAILABLE_TOOL -> {
        val alt = getApprovedPolicyAlternative(toolName, toolCall.arguments)
        Tuple5(RecoveryStrategy.SWITCH_EQUIVALENT_TOOL, 0L, 0, alt, "Tool or binary is unavailable. Switched to alternative tool '$alt'. Never repeat unchanged.")
      }
    }

    val sig = ActionSignature.compute(toolName, toolCall.arguments)
    val exactToolError = (rawErr.ifBlank { stderr.ifBlank { stdout } }).ifBlank { "Tool execution failed" }.trim()
    val structuredReport = ErrorTaxonomyClassifier.createStructuredErrorReport(
      failedOperation = "${toolCall.toolName}(${sig.canonicalArgs})",
      exactToolError = exactToolError,
      failureClass = toolFailureClass,
      exitCode = exitCode,
      stdout = stdout,
      stderr = stderr,
      recoveryResult = recoveryResult
    )

    val diagExplanation = buildDiagnosticExplanation(category, toolResult, stdout, stderr, exactToolError)

    return EvaluationConclusion(
      callId = callId,
      toolName = toolName,
      isSatisfied = false,
      madeProgress = false,
      failureCategory = category,
      recoveryStrategy = strategy,
      explanation = diagExplanation,
      suggestedTool = altTool,
      backoffMs = backoffMs,
      retryAttempt = priorAttempts + 1,
      maxRetries = maxRetries,
      isDeterministic = true,
      toolFailureClass = toolFailureClass,
      structuredError = structuredReport
    )
  }

  companion object {
    fun getApprovedPolicyAlternative(toolName: String, args: Map<String, Any?> = emptyMap()): String {
      return when (toolName.lowercase(Locale.US)) {
        "write_file", "edit_file", "append_file" -> "terminal"
        "read_file" -> "terminal"
        "list_directory" -> "terminal"
        "web_fetch", "web_crawl" -> "web_search"
        "browser_automation" -> "web_open"
        "terminal" -> {
          val cmd = args["command"]?.toString()?.lowercase(Locale.US) ?: ""
          if (cmd.startsWith("cat ") || cmd.startsWith("head ") || cmd.startsWith("tail ")) "read_file"
          else if (cmd.startsWith("echo ") || cmd.startsWith("tee ")) "write_file"
          else if (cmd.startsWith("ls ")) "list_directory"
          else "code_interpreter"
        }
        else -> "terminal"
      }
    }
  }

  private data class Tuple5<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

  private fun checkPostconditions(
    toolCall: ToolCall,
    toolResult: ToolResult,
    context: ToolExecutionContext?
  ): String? {
    val name = toolCall.toolName.lowercase(Locale.US)
    val path = toolCall.arguments["path"]?.toString() ?: toolCall.arguments["target"]?.toString()

    if (name in listOf("write_file", "edit_file", "append_file") && !path.isNullOrBlank()) {
      val localFile = context?.workspaceDir?.let { File(it, path.removePrefix("/home/user/").removePrefix("./")) }
      if (localFile != null && localFile.exists() && localFile.length() == 0L) {
        val requestedContent = toolCall.arguments["content"]?.toString().orEmpty()
        if (requestedContent.isNotBlank()) {
          return "Postcondition Failed: Written file '$path' exists on disk but contains 0 bytes (expected ${requestedContent.length} chars)."
        }
      }
    }
    return null
  }

  private fun classifyFailure(
    result: ToolResult,
    stdout: String,
    stderr: String,
    rawErr: String
  ): ActionFailureCategory {
    val exitCode = result.exitCode
    val fullErr = "$rawErr\n$stderr\n$stdout".lowercase(Locale.US)

    // A. Timeout
    if (result.errorType == ToolErrorCode.TIMEOUT || exitCode == 124 || fullErr.contains("timed out") || fullErr.contains("deadline exceeded")) {
      return ActionFailureCategory.TIMEOUT
    }

    // B. Rate Limit
    if (result.errorType == ToolErrorCode.RATE_LIMITED || fullErr.contains("429") || fullErr.contains("rate limit") || fullErr.contains("quota exceeded") || fullErr.contains("too many requests")) {
      return ActionFailureCategory.RATE_LIMIT
    }

    // C. Transient Network
    if (result.errorType == ToolErrorCode.NETWORK_ERROR || fullErr.contains("connection reset") || fullErr.contains("network unreachable") || fullErr.contains("socket closed") || fullErr.contains("temporary failure in name resolution")) {
      return ActionFailureCategory.TRANSIENT_NETWORK_FAILURE
    }

    // D. Missing Dependency / Binary / File
    if (exitCode == 127 || result.errorType == ToolErrorCode.COMMAND_NOT_FOUND || result.errorType == ToolErrorCode.FILE_NOT_FOUND ||
      fullErr.contains("command not found") || fullErr.contains("no such file or directory") || fullErr.contains("cannot find module") ||
      fullErr.contains("modulenotfounderror") || fullErr.contains("package not installed")
    ) {
      return ActionFailureCategory.MISSING_DEPENDENCY
    }

    // E. Permission Error
    if (exitCode == 126 || result.errorType == ToolErrorCode.PERMISSION_DENIED || fullErr.contains("permission denied") || fullErr.contains("eacces") || fullErr.contains("operation not permitted")) {
      return ActionFailureCategory.PERMISSION_ERROR
    }

    // F. Invalid Arguments
    if (result.errorType == ToolErrorCode.INVALID_ARGUMENTS || fullErr.contains("invalid argument") || fullErr.contains("unrecognized option") || fullErr.contains("missing required parameter") || fullErr.contains("illegal option")) {
      return ActionFailureCategory.INVALID_ARGUMENTS
    }

    // G. Malformed Output
    if (result.errorType == ToolErrorCode.SOURCE_TRUNCATED || fullErr.contains("malformed json") || fullErr.contains("unexpected end of json")) {
      return ActionFailureCategory.MALFORMED_OUTPUT
    }

    // H. Unrecoverable
    if (result.errorType == ToolErrorCode.SANDBOX_ERROR || fullErr.contains("out of memory") || fullErr.contains("no space left on device")) {
      return ActionFailureCategory.UNRECOVERABLE_FAILURE
    }

    return ActionFailureCategory.UNRECOVERABLE_FAILURE
  }

  private fun selectRecoveryStrategy(
    category: ActionFailureCategory,
    priorAttempts: Int,
    toolName: String,
    args: Map<String, Any?>
  ): RecoveryStrategy {
    val maxRetries = maxRetriesForCategory(category)
    if (priorAttempts >= maxRetries) {
      return when (category) {
        ActionFailureCategory.TIMEOUT -> RecoveryStrategy.REVISE_PLAN
        ActionFailureCategory.RATE_LIMIT -> RecoveryStrategy.REVISE_PLAN
        ActionFailureCategory.TRANSIENT_NETWORK_FAILURE -> RecoveryStrategy.SWITCH_EQUIVALENT_TOOL
        ActionFailureCategory.MISSING_DEPENDENCY -> RecoveryStrategy.REVISE_PLAN
        ActionFailureCategory.PERMISSION_ERROR -> RecoveryStrategy.REVISE_PLAN
        ActionFailureCategory.INVALID_ARGUMENTS -> RecoveryStrategy.REVISE_PLAN
        ActionFailureCategory.REPEATED_ACTION -> RecoveryStrategy.STOP_WITH_EXPLANATION
        ActionFailureCategory.UNRECOVERABLE_FAILURE -> RecoveryStrategy.STOP_WITH_EXPLANATION
        else -> RecoveryStrategy.REVISE_PLAN
      }
    }

    return when (category) {
      ActionFailureCategory.TRANSIENT_NETWORK_FAILURE -> RecoveryStrategy.RETRY_WITH_BACKOFF
      ActionFailureCategory.RATE_LIMIT -> RecoveryStrategy.RETRY_WITH_BACKOFF
      ActionFailureCategory.TIMEOUT -> RecoveryStrategy.CHANGE_PARAMETERS
      ActionFailureCategory.INVALID_ARGUMENTS -> RecoveryStrategy.CHANGE_PARAMETERS
      ActionFailureCategory.MISSING_DEPENDENCY -> {
        val alt = UnifiedLoopDetector.getAlternativeTool(toolName)
        if (alt != null) RecoveryStrategy.SWITCH_EQUIVALENT_TOOL else RecoveryStrategy.CHANGE_PARAMETERS
      }
      ActionFailureCategory.PERMISSION_ERROR -> RecoveryStrategy.CHANGE_PARAMETERS
      ActionFailureCategory.MALFORMED_OUTPUT -> RecoveryStrategy.INSPECT_PARTIAL_RESULTS
      ActionFailureCategory.FAILED_POSTCONDITION -> RecoveryStrategy.CHANGE_PARAMETERS
      ActionFailureCategory.REPEATED_ACTION -> RecoveryStrategy.SWITCH_EQUIVALENT_TOOL
      ActionFailureCategory.UNRECOVERABLE_FAILURE -> RecoveryStrategy.STOP_WITH_EXPLANATION
    }
  }

  private fun maxRetriesForCategory(category: ActionFailureCategory): Int {
    return when (category) {
      ActionFailureCategory.TRANSIENT_NETWORK_FAILURE -> 2
      ActionFailureCategory.RATE_LIMIT -> 2
      ActionFailureCategory.TIMEOUT -> 1
      ActionFailureCategory.INVALID_ARGUMENTS -> 1
      ActionFailureCategory.MISSING_DEPENDENCY -> 1
      ActionFailureCategory.PERMISSION_ERROR -> 1
      ActionFailureCategory.MALFORMED_OUTPUT -> 1
      ActionFailureCategory.FAILED_POSTCONDITION -> 1
      ActionFailureCategory.REPEATED_ACTION -> 0
      ActionFailureCategory.UNRECOVERABLE_FAILURE -> 0
    }
  }

  private fun calculateBackoffMs(category: ActionFailureCategory, priorAttempts: Int): Long {
    val attempt = (priorAttempts + 1).coerceAtLeast(1)
    return when (category) {
      ActionFailureCategory.RATE_LIMIT -> (2000L * (1L shl (attempt - 1))).coerceAtMost(15000L)
      ActionFailureCategory.TRANSIENT_NETWORK_FAILURE -> (1000L * (1L shl (attempt - 1))).coerceAtMost(8000L)
      ActionFailureCategory.TIMEOUT -> 1500L
      else -> 0L
    }
  }

  private fun buildDiagnosticExplanation(
    category: ActionFailureCategory,
    result: ToolResult,
    stdout: String,
    stderr: String,
    rawErr: String
  ): String {
    val cleanErr = stderr.ifBlank { rawErr }.ifBlank { stdout }.trim().lines().firstOrNull()?.take(160) ?: "Unknown failure"
    return when (category) {
      ActionFailureCategory.TIMEOUT -> "Operation exceeded execution deadline (${result.duration}ms). Error: $cleanErr"
      ActionFailureCategory.RATE_LIMIT -> "Remote endpoint or sandbox hit rate limit/quota threshold: $cleanErr"
      ActionFailureCategory.TRANSIENT_NETWORK_FAILURE -> "Transient network communication drop: $cleanErr"
      ActionFailureCategory.MISSING_DEPENDENCY -> "Required binary, file, or package dependency is missing: $cleanErr"
      ActionFailureCategory.PERMISSION_ERROR -> "Access denied or operation restricted: $cleanErr"
      ActionFailureCategory.INVALID_ARGUMENTS -> "Parameter validation failed: $cleanErr"
      ActionFailureCategory.MALFORMED_OUTPUT -> "Output format corrupted or incomplete: $cleanErr"
      ActionFailureCategory.FAILED_POSTCONDITION -> "Postcondition validation failed: $cleanErr"
      ActionFailureCategory.REPEATED_ACTION -> "Repetitive action signature executed without progress."
      ActionFailureCategory.UNRECOVERABLE_FAILURE -> "Fatal failure: $cleanErr"
    }
  }
}

/**
 * Unified Recovery Manager orchestrating loop detection, failure classification,
 * bounded retries, and persistence across task lifecycles.
 */
class UnifiedRecoveryManager(
  val workspaceDir: File? = null
) {
  val loopDetector = UnifiedLoopDetector(maxHistorySize = 25)
  val evaluator = ActionEvaluator()

  private val retryCountsBySignature = ConcurrentHashMap<String, Int>()
  private val recoveryRecords = CopyOnWriteArrayList<RecoveryRecord>()
  private val deterministicFailures = ConcurrentHashMap<String, String>() // callHash -> errorMsg
  private val blockedOperations = ConcurrentHashMap<String, StructuredErrorReport>() // callHash -> StructuredErrorReport
  private var consecutiveFailures = 0

  /**
   * Resets all recovery and loop detection state for a fresh task.
   */
  fun reset() {
    loopDetector.reset()
    retryCountsBySignature.clear()
    recoveryRecords.clear()
    deterministicFailures.clear()
    blockedOperations.clear()
    consecutiveFailures = 0
  }

  /**
   * Invalidates cached process and deterministic failures when environment mutations occur.
   */
  fun invalidateProcessFailures() {
    deterministicFailures.clear()
    blockedOperations.clear()
  }

  /**
   * Authoritative pre-flight gate. Checks whether an action should proceed, be skipped,
   * blocked due to a deterministic failure, or halted due to an infinite loop.
   */
  fun checkPreFlight(toolCall: ToolCall): PreFlightDecision {
    val sig = ActionSignature.compute(toolCall.toolName, toolCall.arguments)

    // 1. Blocked Operation Repeat Guard (Never repeat a blocked operation unchanged. Preserve the original error.)
    val blocked = blockedOperations[sig.callHash]
    if (blocked != null) {
      return PreFlightDecision.BlockDeterministicFailure(
        priorError = blocked.exactToolError,
        recoveryHint = "Blocked operation: '${blocked.failedOperation}' failed with ${blocked.failureClass.code}. Original Error: \"${blocked.exactToolError}\". Recovery: ${blocked.recoveryResult}. Never repeat a blocked operation unchanged!"
      )
    }

    val priorErr = deterministicFailures[sig.callHash]
    if (priorErr != null) {
      return PreFlightDecision.BlockDeterministicFailure(
        priorError = priorErr,
        recoveryHint = "The previous identical invocation failed deterministically with '$priorErr'. Change arguments or upload the missing dependency before retrying."
      )
    }

    // 2. Loop Detector Pre-flight
    return loopDetector.checkPreFlight(sig)
  }

  /**
   * Evaluates a completed tool execution and registers the outcome in history.
   * Returns a complete, typed EvaluationConclusion.
   */
  fun evaluateExecution(
    toolCall: ToolCall,
    toolResult: ToolResult,
    observation: Observation,
    context: ToolExecutionContext?
  ): EvaluationConclusion {
    val sig = ActionSignature.compute(toolCall.toolName, toolCall.arguments)
    val priorAttempts = retryCountsBySignature[sig.callHash] ?: 0

    val conclusion = evaluator.evaluate(
      toolCall = toolCall,
      toolResult = toolResult,
      observation = observation,
      context = context,
      loopDetector = loopDetector,
      priorAttempts = priorAttempts
    )

    // Record into history
    val obsHash = UnifiedLoopDetector.computeObservationHash(toolResult.stdout ?: toolResult.output)
    loopDetector.recordEvent(
      ExecutionHistoryEvent(
        signature = sig,
        observationHash = obsHash,
        outcome = observation.outcome,
        failureCategory = conclusion.failureCategory,
        timestamp = System.currentTimeMillis()
      )
    )

    val toolNameLower = toolCall.toolName.lowercase(Locale.US)
    val mutatesEnv = toolNameLower in listOf(
      "terminal", "install_package", "write_file", "edit_file", "apply_patch", "move_file", "delete_file"
    )

    if (conclusion.madeProgress && conclusion.isSatisfied) {
      retryCountsBySignature.remove(sig.callHash)
      deterministicFailures.remove(sig.callHash)
      blockedOperations.remove(sig.callHash)
      consecutiveFailures = 0
      if (mutatesEnv) {
        deterministicFailures.clear()
        blockedOperations.clear()
      }
    } else {
      if (conclusion.madeProgress && mutatesEnv) {
        deterministicFailures.clear()
        blockedOperations.clear()
      }
      consecutiveFailures++
      retryCountsBySignature[sig.callHash] = priorAttempts + 1

      // Block repeat of blocked operations unchanged, preserving exact error
      // Note: code_interpreter runs arbitrary dynamic scripts and should not be permanently locked out if dependencies change
      val isBlockedOperation = (conclusion.toolFailureClass in listOf(
        ToolFailureClass.PERMISSION_POLICY,
        ToolFailureClass.INVALID_INPUT,
        ToolFailureClass.UNAVAILABLE_TOOL
      ) || (conclusion.toolFailureClass == ToolFailureClass.TRANSIENT && priorAttempts >= 1)) &&
        toolNameLower != "code_interpreter"

      if (isBlockedOperation && conclusion.structuredError != null) {
        blockedOperations[sig.callHash] = conclusion.structuredError
        deterministicFailures[sig.callHash] = conclusion.structuredError.exactToolError
      } else if (conclusion.isDeterministic && toolNameLower != "code_interpreter" && conclusion.failureCategory in listOf(
          ActionFailureCategory.MISSING_DEPENDENCY,
          ActionFailureCategory.PERMISSION_ERROR,
          ActionFailureCategory.INVALID_ARGUMENTS
        )
      ) {
        deterministicFailures[sig.callHash] = conclusion.explanation
      }

      val record = RecoveryRecord(
        callId = toolCall.callId,
        toolName = toolCall.toolName,
        actionSignature = "${sig.toolName}(${sig.canonicalArgs})",
        failureCategory = conclusion.failureCategory ?: ActionFailureCategory.UNRECOVERABLE_FAILURE,
        recoveryStrategy = conclusion.recoveryStrategy,
        attemptNumber = priorAttempts + 1,
        explanation = conclusion.explanation,
        resolved = false
      )
      if (recoveryRecords.size >= 50) {
        recoveryRecords.removeAt(0)
      }
      recoveryRecords.add(record)
    }

    return conclusion
  }

  fun getConsecutiveFailures(): Int = consecutiveFailures

  fun getRecentRecoveryRecords(): List<RecoveryRecord> = recoveryRecords.toList()

  /**
   * Exports full recovery state to a JSON string for checkpoint persistence.
   */
  fun exportStateJson(): String {
    val root = JSONObject()
    root.put("consecutiveFailures", consecutiveFailures)

    val retriesObj = JSONObject()
    retryCountsBySignature.forEach { (k, v) -> retriesObj.put(k, v) }
    root.put("retries", retriesObj)

    val detFailObj = JSONObject()
    deterministicFailures.forEach { (k, v) -> detFailObj.put(k, v) }
    root.put("deterministicFailures", detFailObj)

    val recordsArr = JSONArray()
    recoveryRecords.forEach { recordsArr.put(it.toJson()) }
    root.put("records", recordsArr)

    root.put("history", loopDetector.exportHistoryJson())

    return root.toString()
  }

  /**
   * Restores full recovery state from a checkpoint JSON string.
   */
  fun restoreStateJson(jsonStr: String?) {
    if (jsonStr.isNullOrBlank()) return
    try {
      val root = JSONObject(jsonStr)
      consecutiveFailures = root.optInt("consecutiveFailures", 0)

      retryCountsBySignature.clear()
      val retriesObj = root.optJSONObject("retries")
      if (retriesObj != null) {
        val keys = retriesObj.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          retryCountsBySignature[k] = retriesObj.optInt(k, 0)
        }
      }

      deterministicFailures.clear()
      val detFailObj = root.optJSONObject("deterministicFailures")
      if (detFailObj != null) {
        val keys = detFailObj.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          deterministicFailures[k] = detFailObj.optString(k, "")
        }
      }

      recoveryRecords.clear()
      val recordsArr = root.optJSONArray("records")
      if (recordsArr != null) {
        for (i in 0 until recordsArr.length()) {
          val obj = recordsArr.optJSONObject(i) ?: continue
          RecoveryRecord.fromJson(obj)?.let { recoveryRecords.add(it) }
        }
      }

      val historyArr = root.optJSONArray("history")
      loopDetector.restoreHistoryJson(historyArr)
    } catch (e: Exception) {
      Log.w("UnifiedRecoveryManager", "Failed to restore recovery state: ${e.message}")
    }
  }
}
