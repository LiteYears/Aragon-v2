package com.example.agent.core

import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
      rawMessage = stderr.ifBlank { stdout }.ifBlank { exception?.message ?: "Unclassified error" },
      command = command
    )
  }

  private fun extractBinaryFromExit127(command: String?, stderr: String): String {
    val regex = Regex("""(?:line \d+:\s+)?([^:\s]+):\s+(?:command\s+)?not found""", RegexOption.IGNORE_CASE)
    val match = regex.find(stderr)
    return match?.groupValues?.get(1) ?: command?.split(" ")?.firstOrNull() ?: "unknown"
  }
}
