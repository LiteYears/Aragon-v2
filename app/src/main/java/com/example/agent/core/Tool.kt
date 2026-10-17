package com.example.agent.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Category classification for agent tools.
 */
enum class ToolCategory {
  PROCESS,
  FILESYSTEM,
  PACKAGE,
  NETWORK,
  WEB,
  UTILITY
}

/**
 * Execution target environment for a tool.
 */
enum class ToolTarget {
  SANDBOX_PROCESS,
  SANDBOX_FILESYSTEM,
  SANDBOX_PACKAGE,
  HOST_NETWORK
}

/**
 * Permissions required to invoke a tool.
 */
enum class ToolPermission {
  NONE,
  READ_ONLY,
  WORKSPACE_WRITE,
  PROCESS_EXECUTION,
  NETWORK_ACCESS,
  PACKAGE_INSTALL
}

/**
 * Explicit execution environment identity for tool routing.
 * The E2B Cloud MicroVM is the only execution environment.
 */
enum class ExecutionEnvironmentType {
  E2B_CLOUD      // Dedicated E2B Cloud Firecracker MicroVM
}

/**
 * Lifecycle state of a Sandbox environment.
 */
enum class SandboxLifecycleState {
  CREATING,
  RUNNING,
  PAUSED,
  EXPIRED,
  TERMINATED,
  FAILED,
  UNINITIALIZED
}

/**
 * Status of an individual tool invocation.
 */
enum class ToolStatus {
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED
}

/**
 * Unified, authoritative error model for tools and command execution.
 */
enum class ToolErrorCode(val code: String, val recoveryHint: String) {
  TOOL_NOT_FOUND(
    "TOOL_NOT_FOUND",
    "Call only registered tools present in the capability manifest. Shell commands must be run via the 'terminal' tool."
  ),
  INVALID_ARGUMENTS(
    "INVALID_ARGUMENTS",
    "Inspect tool schema parameter requirements and pass correct argument types and non-empty values."
  ),
  PERMISSION_DENIED(
    "PERMISSION_DENIED",
    "Operation is not permitted by current sandbox security policy."
  ),
  COMMAND_NOT_FOUND(
    "COMMAND_NOT_FOUND",
    "The requested binary does not exist in the sandbox PATH. Check the capability manifest for available commands or install the binary if supported."
  ),
  PROCESS_FAILED(
    "PROCESS_FAILED",
    "Process exited with non-zero exit code. Inspect stdout/stderr and adjust command arguments."
  ),
  TIMEOUT(
    "TIMEOUT",
    "Execution timed out. Optimize the command/workload or specify a larger timeoutSeconds parameter."
  ),
  CANCELLED(
    "CANCELLED",
    "Execution was cancelled by the user or task lifecycle."
  ),
  NETWORK_ERROR(
    "NETWORK_ERROR",
    "Network communication failed. Check the URL, network connectivity, or retry with backoff."
  ),
  TRANSIENT_NETWORK(
    "TRANSIENT_NETWORK",
    "Transient network communication glitch occurred. Retry with bounded backoff."
  ),
  SANDBOX_ERROR(
    "SANDBOX_ERROR",
    "A sandbox runtime error occurred. Verify sandbox file state and paths."
  ),
  STALE_SESSION(
    "STALE_SESSION",
    "The remote sandbox session has expired or was not found (HTTP 502/404). Re-provisioning fresh sandbox."
  ),
  ENVIRONMENT_MISMATCH(
    "ENVIRONMENT_MISMATCH",
    "File exists in the device-side artifact workspace but not inside the E2B microVM. Use 'transfer_artifact_to_sandbox' to upload it."
  ),
  FILE_NOT_FOUND(
    "FILE_NOT_FOUND",
    "Target file does not exist inside the E2B microVM. Verify the path with 'list_directory' or 'file_exists'."
  ),
  OUTPUT_LIMIT(
    "OUTPUT_LIMIT",
    "Process output exceeded buffer limit. Refine command filters or use pagination parameters."
  ),
  PATH_NOT_ALLOWED(
    "PATH_NOT_ALLOWED",
    "Path traversal denied: path attempts to escape the configured sandbox workspace root."
  ),
  UNSUPPORTED_OPERATION(
    "UNSUPPORTED_OPERATION",
    "The requested operation or action is not supported by the environment."
  ),
  SANDBOX_INFRA_ERROR(
    "SANDBOX_INFRA_ERROR",
    "Underlying sandbox infrastructure or cloud gateway failed. Environment reset/recovery required."
  ),
  HTTP_ERROR(
    "HTTP_ERROR",
    "Target HTTP endpoint returned an error status code. Verify request parameters or endpoint health."
  ),
  CROSS_ENV_PATH_MISUSE(
    "CROSS_ENV_PATH_MISUSE",
    "Path targets a location outside the E2B microVM workspace. Use paths under /home/user (or /tmp scratch)."
  ),
  REPETITION_GUARD(
    "REPETITION_GUARD",
    "Repetitive invocation of identical tool and arguments detected without progress. Use alternative bound tools."
  ),
  PATH_OUTSIDE_WORKSPACE(
    "PATH_OUTSIDE_WORKSPACE",
    "Target path is outside allowed workspace root and /tmp scratch directory."
  ),
  WRITE_INTEGRITY_MISMATCH(
    "WRITE_INTEGRITY_MISMATCH",
    "Written file checksum or byte count differs from requested write payload."
  ),
  SOURCE_TRUNCATED(
    "SOURCE_TRUNCATED",
    "Refused to write truncated observation source directly to file. Page or fetch full source."
  ),
  UNKNOWN_ERROR(
    "UNKNOWN_ERROR",
    "Unclassified execution error. Inspect diagnostic details."
  )
}

/**
 * Single, typed execution context identifying backend, session, workspace, and capabilities.
 */
data class ToolExecutionContext(
  val environmentType: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD,
  val backendName: String = "E2B Cloud MicroVM",
  val sessionId: String = "default",
  val taskId: String? = null,
  val sandboxId: String? = null,
  val workspaceDir: java.io.File? = null,
  val workingDirectory: String = "/home/user",
  val capabilities: Set<String> = emptySet(),
  val lifecycleState: SandboxLifecycleState = SandboxLifecycleState.RUNNING,
  val remoteHost: String? = null
)

/**
 * Explicit identity of an execution environment.
 */
data class EnvironmentIdentity(
  val type: ExecutionEnvironmentType,
  val name: String,
  val sessionId: String,
  val sandboxId: String? = null,
  val workingDirectory: String = ".",
  val lifecycleState: SandboxLifecycleState = SandboxLifecycleState.RUNNING,
  val capabilities: Set<String> = emptySet(),
  val metadata: Map<String, Any?> = emptyMap()
)

/**
 * Schema parameter definition for a tool.
 */
data class ToolParameter(
  val name: String,
  val type: String, // "string", "number", "boolean", "array", "object"
  val description: String,
  val required: Boolean = true,
  val enumValues: List<String>? = null,
  val defaultValue: Any? = null
)

/**
 * Input schema specification exposed to the LLM.
 */
data class ToolSchema(
  val parameters: List<ToolParameter>
) {
  fun toJsonSchemaString(): String {
    val props = parameters.joinToString(",\n      ") { param ->
      val enumPart = if (param.enumValues != null) {
        ", \"enum\": [${param.enumValues.joinToString(", ") { "\"$it\"" }}]"
      } else ""
      val defPart = if (param.defaultValue != null) {
        val defValStr = if (param.defaultValue is String) "\"${param.defaultValue}\"" else "${param.defaultValue}"
        ", \"default\": $defValStr"
      } else ""
      "\"${param.name}\": { \"type\": \"${param.type}\", \"description\": \"${param.description}\"$enumPart$defPart }"
    }
    val requiredFields = parameters.filter { it.required }.joinToString(", ") { "\"${it.name}\"" }
    return """
    {
      "type": "object",
      "properties": {
        $props
      },
      "required": [$requiredFields]
    }
    """.trimIndent()
  }

  fun toJsonObject(): JSONObject {
    val obj = JSONObject()
    obj.put("type", "object")
    val props = JSONObject()
    val req = JSONArray()
    for (param in parameters) {
      val p = JSONObject()
      p.put("type", param.type.lowercase())
      p.put("description", param.description)
      if (!param.enumValues.isNullOrEmpty()) {
        val enums = JSONArray()
        param.enumValues.forEach { enums.put(it) }
        p.put("enum", enums)
      }
      if (param.defaultValue != null) {
        p.put("default", param.defaultValue)
      }
      props.put(param.name, p)
      if (param.required) {
        req.put(param.name)
      }
    }
    obj.put("properties", props)
    obj.put("required", req)
    return obj
  }
}

/**
 * A tool invocation requested by the LLM with an immutable globally unique ID.
 */
data class ToolCall(
  val callId: String,
  val toolName: String,
  val arguments: Map<String, Any?>
) {
  companion object {
    /**
     * Generates a globally unique immutable tool call ID: call_<taskId>_<iteration>_<randomId>.
     */
    fun generateCallId(taskId: String, iteration: Int): String {
      val cleanTaskId = taskId.replace("-", "").take(8)
      val randomSuffix = UUID.randomUUID().toString().replace("-", "").take(8)
      return "call_${cleanTaskId}_${iteration}_$randomSuffix"
    }

    /**
     * Fallback generator when task ID or turn is omitted.
     */
    fun create(toolName: String, arguments: Map<String, Any?>, taskId: String = "task", iteration: Int = 1): ToolCall {
      return ToolCall(
        callId = generateCallId(taskId, iteration),
        toolName = toolName,
        arguments = arguments
      )
    }
  }
}

/**
 * Authoritative result produced ONLY by the application executing the tool.
 * Never fabricated or assumed.
 */
data class ToolResult(
  val callId: String,
  val toolName: String,
  val status: ToolStatus,
  val arguments: Map<String, Any?>,
  val output: String?,
  val error: String?,
  val errorType: ToolErrorCode? = null,
  val duration: Long,
  val stdout: String? = null,
  val stderr: String? = null,
  val exitCode: Int? = null,
  val artifacts: List<Artifact> = emptyList(),
  val metadata: Map<String, Any?> = emptyMap(),
  val executionContext: ToolExecutionContext? = null
) {
  val durationMs: Long get() = duration
  val isSuccess: Boolean get() = status == ToolStatus.SUCCEEDED
}

/**
 * Structured observation created from an authoritative ToolResult.
 * Represents what actually happened in the environment.
 */
data class Observation(
  val id: String = UUID.randomUUID().toString(),
  val callId: String,
  val toolName: String,
  val status: ToolStatus,
  val isSuccess: Boolean = status == ToolStatus.SUCCEEDED,
  val inputsSummary: String,
  val result: String?,
  val error: String?,
  val errorType: ToolErrorCode? = null,
  val exitCode: Int? = null,
  val stdout: String? = null,
  val stderr: String? = null,
  val durationMs: Long = 0L,
  val artifacts: List<Artifact> = emptyList(),
  val recoverability: String? = null,
  val summary: String = "",
  val rawOutput: String = "",
  val timestamp: Long = System.currentTimeMillis(),
  val executionContext: ToolExecutionContext? = null
) {
  /**
   * The explicit, unambiguous plaintext representation provided to the LLM.
   */
  fun toAuthoritativePromptText(): String {
    val stdoutText = when {
      !stdout.isNullOrBlank() -> stdout.trim()
      !result.isNullOrBlank() -> result.trim()
      else -> "empty (0 bytes)"
    }
    val stderrText = if (stderr.isNullOrBlank()) "empty (0 bytes)" else stderr.trim()
    val errorText = if (error.isNullOrBlank()) "none" else error.trim()
    val errorTypeStr = errorType?.code ?: if (isSuccess) "NONE" else "UNKNOWN"
    val recoveryStr = recoverability ?: errorType?.recoveryHint ?: "none"
    val artifactsText = if (artifacts.isEmpty()) "none" else artifacts.joinToString("; ") { "${it.path} (${it.size} bytes, ${it.type})" }

    val envHeader = if (executionContext != null) {
      val sbxPart = if (!executionContext.sandboxId.isNullOrBlank()) " [Sandbox ID: ${executionContext.sandboxId}]" else ""
      """
    ENVIRONMENT: ${executionContext.environmentType.name} (${executionContext.backendName})$sbxPart
    WORKING DIRECTORY: ${executionContext.workingDirectory}
    SESSION ID: ${executionContext.sessionId}
      """.trimIndent() + "\n"
    } else ""

    return """
    === OBSERVATION ===
    TOOL: $toolName
    CALL ID: $callId
    STATUS: ${status.name}
    $envHeader${""}INPUTS: $inputsSummary
    EXIT CODE: ${exitCode ?: (if (isSuccess) 0 else 1)}
    DURATION: ${durationMs}ms
    ERROR TYPE: $errorTypeStr
    STDOUT: $stdoutText
    STDERR: $stderrText
    ERROR: $errorText
    RECOVERY HINT: $recoveryStr
    ARTIFACTS: $artifactsText
    ===================
    """.trimIndent()
  }
}

/**
 * The canonical, authoritative Tool contract.
 */
interface Tool {
  val name: String
  val description: String
  val schema: ToolSchema
  val category: ToolCategory get() = ToolCategory.UTILITY
  val target: ToolTarget get() = ToolTarget.SANDBOX_PROCESS
  val permissions: Set<ToolPermission> get() = emptySet()
  val owningBackend: ExecutionEnvironmentType get() = ExecutionEnvironmentType.E2B_CLOUD
  val requiredCapabilities: Set<String> get() = emptySet()
  val workspaceRoot: String get() = "/home/user"
  val environmentIdentity: EnvironmentIdentity get() = EnvironmentIdentity(
    type = owningBackend,
    name = "E2B Cloud MicroVM",
    sessionId = "default",
    workingDirectory = workspaceRoot,
    capabilities = requiredCapabilities
  )
  val defaultTimeoutMs: Long get() = 30000L
  val supportsCancellation: Boolean get() = true

  /**
   * Reports whether this tool is available and executable in the current runtime environment.
   */
  fun isAvailable(): Boolean = true

  /**
   * Authoritatively execute the tool with validated arguments.
   */
  suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult

  /**
   * Authoritatively execute the tool with validated arguments and execution context.
   * Default implementation delegates to execute(callId, arguments).
   */
  suspend fun execute(
    callId: String,
    arguments: Map<String, Any?>,
    context: ToolExecutionContext
  ): ToolResult = execute(callId, arguments)
}
