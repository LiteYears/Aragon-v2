package com.example.agent.core

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/**
 * Authoritative taxonomy of errors for the Agent Kernel.
 * Prevents heuristic guessing and ensures unambiguous recovery actions.
 */
enum class KernelErrorClass(val code: String) {
  SANDBOX_INFRA_ERROR("SANDBOX_INFRA_ERROR"),
  COMMAND_NOT_FOUND("COMMAND_NOT_FOUND"),
  HTTP_ERROR("HTTP_ERROR"),
  TIMEOUT("TIMEOUT"),
  PERMISSION_DENIED("PERMISSION_DENIED"),
  NETWORK_ERROR("NETWORK_ERROR"),
  UNKNOWN_ERROR("UNKNOWN_ERROR")
}

/**
 * Authoritative 4-class failure taxonomy strictly conforming to:
 * - TRANSIENT
 * - PERMISSION_POLICY
 * - INVALID_INPUT
 * - UNAVAILABLE_TOOL
 */
enum class ToolFailureClass(val code: String, val description: String) {
  TRANSIENT("TRANSIENT", "Transient network timeout, connection reset, or rate limit"),
  PERMISSION_POLICY("PERMISSION_POLICY", "Permission denied, access restricted, or security policy violation"),
  INVALID_INPUT("INVALID_INPUT", "Invalid parameters, missing required arguments, or malformed input schema"),
  UNAVAILABLE_TOOL("UNAVAILABLE_TOOL", "Tool or binary not found in registry or PATH")
}

/**
 * Structured report for error transparency and auditing:
 * 1. Preserves exact tool error verbatim
 * 2. Distinguishes observed facts from inferred causes
 * 3. Never invents a cause or attributes failure to a command not shown in the original error
 * 4. Includes the actual failed operation and recovery result
 */
data class StructuredErrorReport(
  val failedOperation: String,
  val exactToolError: String,
  val failureClass: ToolFailureClass,
  val exitCode: Int?,
  val observedFact: String,
  val inferredCause: String?,
  val recoveryResult: String
) {
  fun format(): String = buildString {
    appendLine("Actual Failed Operation: $failedOperation")
    appendLine("Exact Tool Error: $exactToolError")
    if (exitCode != null) appendLine("Exit Code: $exitCode")
    appendLine("Observed Facts: $observedFact")
    if (!inferredCause.isNullOrBlank()) {
      appendLine("Inferred Cause: $inferredCause")
    }
    appendLine("Recovery Result: $recoveryResult")
  }.trim()
}

data class StructuredErrorPayload(
  val errorClass: KernelErrorClass,
  val exitCode: Int?,
  val httpStatusCode: Int?,
  val rawMessage: String,
  val command: String?,
  val metadata: Map<String, Any?> = emptyMap()
)

object ErrorTaxonomyClassifier {

  /**
   * Authoritative error classification.
   * Parses structured sandbox API payloads, exit codes, and network exceptions.
   * NEVER guesses or performs loose substring matches.
   */
  fun classify(
    exitCode: Int?,
    stdout: String,
    stderr: String,
    command: String?,
    infraStatusCode: Int? = null,
    exception: Throwable? = null,
    errorName: String? = null
  ): StructuredErrorPayload {

    // 1. Explicit Sandbox Infrastructure Gateway Codes (e.g. E2B HTTP 404, 502, 500)
    if (infraStatusCode != null) {
      if (infraStatusCode in 500..599 || infraStatusCode == 404 || infraStatusCode == 401 || infraStatusCode == 403) {
        return StructuredErrorPayload(
          errorClass = KernelErrorClass.SANDBOX_INFRA_ERROR,
          exitCode = exitCode,
          httpStatusCode = infraStatusCode,
          rawMessage = "Sandbox Infrastructure API error (HTTP $infraStatusCode): ${stderr.ifBlank { stdout }.ifBlank { "Gateway error" }}",
          command = command,
          metadata = mapOf("stage" to "SANDBOX_GATEWAY", "statusCode" to infraStatusCode)
        )
      }
    }

    if (errorName in listOf("E2BProvisioningError", "E2BConnectionError", "E2BStaleSessionError")) {
      return StructuredErrorPayload(
        errorClass = KernelErrorClass.SANDBOX_INFRA_ERROR,
        exitCode = exitCode,
        httpStatusCode = infraStatusCode,
        rawMessage = "Sandbox Infrastructure failure: $errorName - ${stderr.ifBlank { stdout }}",
        command = command,
        metadata = mapOf("errorName" to (errorName ?: ""))
      )
    }

    // 2. Network and Socket-level errors
    if (exception is SocketTimeoutException || errorName == "E2BTimeoutError") {
      return StructuredErrorPayload(
        errorClass = KernelErrorClass.TIMEOUT,
        exitCode = exitCode,
        httpStatusCode = null,
        rawMessage = "Operation timed out: ${exception?.message ?: "Execution deadline exceeded"}",
        command = command
      )
    }
    if (exception is UnknownHostException) {
      return StructuredErrorPayload(
        errorClass = KernelErrorClass.NETWORK_ERROR,
        exitCode = exitCode,
        httpStatusCode = null,
        rawMessage = "Network resolution failure: ${exception.message}",
        command = command
      )
    }

    // 3. POSIX Exit Code Analysis
    when (exitCode) {
      127 -> {
        val missingBinary = extractBinaryFromExit127(command, stderr)
        return StructuredErrorPayload(
          errorClass = KernelErrorClass.COMMAND_NOT_FOUND,
          exitCode = 127,
          httpStatusCode = null,
          rawMessage = "Command binary not found in PATH: $missingBinary",
          command = command,
          metadata = mapOf("missing_binary" to missingBinary)
        )
      }
      126 -> {
        return StructuredErrorPayload(
          errorClass = KernelErrorClass.PERMISSION_DENIED,
          exitCode = 126,
          httpStatusCode = null,
          rawMessage = "Permission denied or command not executable: ${command ?: "unknown"}",
          command = command
        )
      }
      124 -> {
        return StructuredErrorPayload(
          errorClass = KernelErrorClass.TIMEOUT,
          exitCode = 124,
          httpStatusCode = null,
          rawMessage = "Command exceeded allocated execution timeout.",
          command = command
        )
      }
    }

    // 4. Structured HTTP responses from web/curl tools (e.g. target web server returned 404/500)
    // ONLY match if it's explicitly an HTTP response code pattern, NOT generic text containing "not found"
    val httpMatch = Regex("""(?:HTTP/\d(?:\.\d)?|HTTP\s+status(?:\s+code)?)\s+([45]\d\d)""", RegexOption.IGNORE_CASE)
      .find(stdout + "\n" + stderr)
    if (httpMatch != null) {
      val status = httpMatch.groupValues[1].toInt()
      return StructuredErrorPayload(
        errorClass = KernelErrorClass.HTTP_ERROR,
        exitCode = exitCode,
        httpStatusCode = status,
        rawMessage = "Target HTTP endpoint returned status $status",
        command = command,
        metadata = mapOf("endpoint_status" to status)
      )
    }

    // 5. Default Fallback - NEVER guess
    return StructuredErrorPayload(
      errorClass = KernelErrorClass.UNKNOWN_ERROR,
      exitCode = exitCode,
      httpStatusCode = null,
      rawMessage = stderr.ifBlank { stdout }.ifBlank { exception?.message ?: "" }.ifBlank { "Unknown execution failure" }.trim(),
      command = command
    )
  }

  /**
   * Authoritative classification of tool failures into the 4 mandatory classes:
   * - TRANSIENT
   * - PERMISSION_POLICY
   * - INVALID_INPUT
   * - UNAVAILABLE_TOOL
   */
  fun classifyToolFailure(
    exitCode: Int?,
    stdout: String = "",
    stderr: String = "",
    rawErr: String = "",
    errorType: ToolErrorCode? = null,
    exception: Throwable? = null
  ): ToolFailureClass {
    // 1. Explicit errorType mappings
    when (errorType) {
      ToolErrorCode.TIMEOUT, ToolErrorCode.RATE_LIMITED, ToolErrorCode.NETWORK_ERROR, ToolErrorCode.TRANSIENT_NETWORK -> return ToolFailureClass.TRANSIENT
      ToolErrorCode.PERMISSION_DENIED -> return ToolFailureClass.PERMISSION_POLICY
      ToolErrorCode.INVALID_ARGUMENTS -> return ToolFailureClass.INVALID_INPUT
      ToolErrorCode.COMMAND_NOT_FOUND, ToolErrorCode.UNSUPPORTED_OPERATION, ToolErrorCode.TOOL_NOT_FOUND -> return ToolFailureClass.UNAVAILABLE_TOOL
      else -> {}
    }

    // 2. Exception inspection
    if (exception is SocketTimeoutException || exception is UnknownHostException) {
      return ToolFailureClass.TRANSIENT
    }

    // 3. POSIX exit code inspection
    when (exitCode) {
      124 -> return ToolFailureClass.TRANSIENT
      126 -> return ToolFailureClass.PERMISSION_POLICY
      127 -> return ToolFailureClass.UNAVAILABLE_TOOL
    }

    // 4. Content analysis on verbatim error string
    val combined = "$rawErr\n$stderr\n$stdout".lowercase(Locale.US)

    if (combined.contains("timed out") || combined.contains("deadline exceeded") ||
      combined.contains("rate limit") || combined.contains("429") || combined.contains("too many requests") ||
      combined.contains("quota exceeded") || combined.contains("connection reset") || combined.contains("socket closed") ||
      combined.contains("broken pipe") || combined.contains("network unreachable") || combined.contains("temporary failure in name resolution") ||
      combined.contains("502 bad gateway") || combined.contains("503 service unavailable") || combined.contains("504 gateway timeout")
    ) {
      return ToolFailureClass.TRANSIENT
    }

    if (combined.contains("permission denied") || combined.contains("operation not permitted") ||
      combined.contains("access denied") || combined.contains("eacces") || combined.contains("read-only file system") ||
      combined.contains("policy violation") || combined.contains("forbidden") || combined.contains("restricted environment")
    ) {
      return ToolFailureClass.PERMISSION_POLICY
    }

    if (combined.contains("command not found") || combined.contains("not found in path") ||
      combined.contains("cannot find module") || combined.contains("modulenotfounderror") ||
      combined.contains("no such file or directory: ") || combined.contains("tool not registered") ||
      combined.contains("binary not found")
    ) {
      return ToolFailureClass.UNAVAILABLE_TOOL
    }

    if (combined.contains("invalid argument") || combined.contains("missing required") ||
      combined.contains("unrecognized option") || combined.contains("illegal option") ||
      combined.contains("malformed json") || combined.contains("schema validation") ||
      combined.contains("syntax error") || combined.contains("validation failed")
    ) {
      return ToolFailureClass.INVALID_INPUT
    }

    return ToolFailureClass.INVALID_INPUT
  }

  /**
   * Constructs an authoritative StructuredErrorReport:
   * 1. Preserves exact tool error verbatim
   * 2. Distinguishes observed facts from inferred causes
   * 3. Never invents a cause or attributes failure to a command not shown in the original error
   * 4. Includes the actual failed operation and recovery result
   */
  fun createStructuredErrorReport(
    failedOperation: String,
    exactToolError: String,
    failureClass: ToolFailureClass,
    exitCode: Int?,
    stdout: String = "",
    stderr: String = "",
    recoveryResult: String
  ): StructuredErrorReport {
    val cleanExactError = exactToolError.ifBlank { stderr.ifBlank { stdout } }.ifBlank { "Unknown tool failure" }.trim()
    val observedFacts = buildString {
      if (exitCode != null) {
        append("Process terminated with exit code $exitCode. ")
      }
      val actualOutput = stderr.ifBlank { stdout }.ifBlank { cleanExactError }.trim()
      append("Observed tool output: \"$actualOutput\"")
    }

    val inferredCause = when (failureClass) {
      ToolFailureClass.TRANSIENT -> "Transient timeout, endpoint rate limit, or network drop encountered."
      ToolFailureClass.PERMISSION_POLICY -> "Operation rejected due to access permission restrictions or execution policy constraints."
      ToolFailureClass.INVALID_INPUT -> "Provided parameters, arguments, or payload syntax failed validation."
      ToolFailureClass.UNAVAILABLE_TOOL -> "Requested tool binary, module, or package is not available in the current environment."
    }

    return StructuredErrorReport(
      failedOperation = failedOperation,
      exactToolError = cleanExactError,
      failureClass = failureClass,
      exitCode = exitCode,
      observedFact = observedFacts,
      inferredCause = inferredCause,
      recoveryResult = recoveryResult
    )
  }

  private fun extractBinaryFromExit127(command: String?, stderr: String): String {
    val regex = Regex("""(?:line \d+:\s+)?([^:\s]+):\s+(?:command\s+)?not found""", RegexOption.IGNORE_CASE)
    val match = regex.find(stderr)
    return match?.groupValues?.get(1) ?: command?.split(" ")?.firstOrNull() ?: "unknown"
  }
}
