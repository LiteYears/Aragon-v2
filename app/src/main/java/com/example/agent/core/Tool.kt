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
 * Delineates between remote E2B MicroVM, local on-device Android runtime, and external MCP bridges.
 */
enum class ExecutionEnvironmentType {
  E2B_CLOUD,      // Dedicated E2B Cloud Firecracker MicroVM
  ANDROID_NATIVE, // On-device Android runtime (local filesystem, native builders, OCR, web requests)
  MCP_BRIDGE      // Model Context Protocol bridged server
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
  RATE_LIMITED(
    "RATE_LIMITED",
    "API rate limit exceeded. Retry with exponential backoff."
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
 * Detailed error when an argument fails schema validation.
 */
data class ParameterValidationError(
  val parameterName: String,
  val expectedType: String,
  val actualValue: Any?,
  val message: String
)

/**
 * Result of validating arguments against a ToolSchema.
 */
data class SchemaValidationResult(
  val isValid: Boolean,
  val errors: List<ParameterValidationError> = emptyList()
) {
  val errorMessage: String?
    get() = if (isValid) null else errors.joinToString("; ") { it.message }

  companion object {
    val VALID = SchemaValidationResult(isValid = true)
    fun failure(errors: List<ParameterValidationError>): SchemaValidationResult =
      SchemaValidationResult(isValid = false, errors = errors)
    fun failure(error: ParameterValidationError): SchemaValidationResult =
      SchemaValidationResult(isValid = false, errors = listOf(error))
  }
}

/**
 * Schema parameter definition for a tool.
 */
data class ToolParameter(
  val name: String,
  val type: String, // "string", "number", "boolean", "array", "object"
  val description: String,
  val required: Boolean = true,
  val enumValues: List<String>? = null,
  val defaultValue: Any? = null,
  val aliases: List<String> = emptyList()
) {
  /**
   * Validates a supplied parameter value against this parameter's type and constraints.
   */
  fun validateValue(value: Any?): ParameterValidationError? {
    val isNullOrBlank = value == null ||
      value == JSONObject.NULL ||
      (value is String && value.isBlank())

    if (required && isNullOrBlank) {
      return ParameterValidationError(
        parameterName = name,
        expectedType = type,
        actualValue = value,
        message = "Missing required parameter '$name' ($type). $description"
      )
    }

    if (isNullOrBlank) {
      // Optional parameter omitted: valid.
      return null
    }

    // Type checking & validation
    val typeValid = when (type.lowercase()) {
      "string" -> value is CharSequence
      "number", "integer" -> value is Number || (value is String && value.toDoubleOrNull() != null)
      "boolean" -> value is Boolean || (value is String && (value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true)))
      "array" -> value is List<*> || value is JSONArray || value is Array<*> || (value is String && value.startsWith("[") && value.endsWith("]"))
      "object" -> value is Map<*, *> || value is JSONObject || (value is String && value.startsWith("{") && value.endsWith("}"))
      else -> true
    }

    if (!typeValid) {
      val actualTypeName = value?.javaClass?.simpleName ?: "null"
      return ParameterValidationError(
        parameterName = name,
        expectedType = type,
        actualValue = value,
        message = "Invalid type for parameter '$name': expected '$type', got '$actualTypeName' ($value)"
      )
    }

    // Enum constraint checking
    if (enumValues != null && enumValues.isNotEmpty()) {
      val strVal = value.toString().trim()
      if (!enumValues.contains(strVal)) {
        return ParameterValidationError(
          parameterName = name,
          expectedType = type,
          actualValue = value,
          message = "Invalid value '$strVal' for parameter '$name'. Allowed values: ${enumValues.joinToString(", ")}"
        )
      }
    }

    return null
  }
}

/**
 * Input schema specification exposed to the LLM.
 */
data class ToolSchema(
  val parameters: List<ToolParameter>
) {
  /**
   * Validates tool invocation arguments against this schema.
   */
  fun validateArguments(arguments: Map<String, Any?>): SchemaValidationResult {
    val errors = mutableListOf<ParameterValidationError>()
    for (param in parameters) {
      var value = arguments[param.name]
      if (value == null) {
        for (alias in param.aliases) {
          if (arguments.containsKey(alias)) {
            value = arguments[alias]
            break
          }
        }
      }
      val err = param.validateValue(value)
      if (err != null) {
        errors.add(err)
      }
    }
    return if (errors.isEmpty()) SchemaValidationResult.VALID else SchemaValidationResult.failure(errors)
  }

  fun toJsonSchemaString(): String {
    val props = parameters.joinToString(",\n      ") { param ->
      val enumPart = if (param.enumValues != null) {
        ", \"enum\": [${param.enumValues.joinToString(", ") { "\"$it\"" }}]"
      } else ""
      val defPart = if (param.defaultValue != null) {
        val defValStr = if (param.defaultValue is String) "\"${param.defaultValue}\"" else "${param.defaultValue}"
        ", \"default\": $defValStr"
      } else ""
      val aliasPart = if (param.aliases.isNotEmpty()) {
        ", \"aliases\": [${param.aliases.joinToString(", ") { "\"$it\"" }}]"
      } else ""
      "\"${param.name}\": { \"type\": \"${param.type}\", \"description\": \"${param.description}\"$enumPart$defPart$aliasPart }"
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
      if (param.aliases.isNotEmpty()) {
        val aliasArr = JSONArray()
        param.aliases.forEach { aliasArr.put(it) }
        p.put("aliases", aliasArr)
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
 * Structured taxonomy of distinct tool execution outcomes.
 * Prevents command/tool success from being mistaken for a successful search or validation.
 */
enum class ToolExecutionOutcome(val code: String, val description: String) {
  EXECUTION_FAILED("EXECUTION_FAILED", "Execution failed"),
  SUCCESS_EMPTY("SUCCESS_EMPTY", "Execution succeeded with no output"),
  SUCCESS_WITH_RESULTS("SUCCESS_WITH_RESULTS", "Execution succeeded with results"),
  CONDITION_NOT_FOUND("CONDITION_NOT_FOUND", "Execution succeeded but the requested condition was not found"),
  TRUNCATED_OR_PARTIAL("TRUNCATED_OR_PARTIAL", "Output was truncated or partially retrieved"),
  UNVERIFIED("UNVERIFIED", "Result could not be verified")
}

/**
 * Fine-grained truncation and pagination details for large tool outputs.
 */
data class TruncationDetails(
  val isTruncated: Boolean = false,
  val originalBytes: Int = 0,
  val capturedBytes: Int = 0,
  val originalLines: Int = 0,
  val capturedLines: Int = 0,
  val omittedLines: Int = 0,
  val omittedBytes: Int = 0,
  val spilloverPath: String? = null,
  val artifactRef: Artifact? = null,
  val inspectionHint: String? = null
)

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
  val executionContext: ToolExecutionContext? = null,
  val outcome: ToolExecutionOutcome = if (status == ToolStatus.SUCCEEDED) ToolExecutionOutcome.SUCCESS_WITH_RESULTS else ToolExecutionOutcome.EXECUTION_FAILED,
  val truncation: TruncationDetails = TruncationDetails(),
  val responseMetadata: Map<String, Any?> = emptyMap()
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
  val outcome: ToolExecutionOutcome = if (status == ToolStatus.SUCCEEDED) ToolExecutionOutcome.SUCCESS_WITH_RESULTS else ToolExecutionOutcome.EXECUTION_FAILED,
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
  val executionContext: ToolExecutionContext? = null,
  val truncation: TruncationDetails = TruncationDetails(),
  val responseMetadata: Map<String, Any?> = emptyMap()
) {
  /**
   * The explicit, unambiguous plaintext representation provided to the LLM.
   */
  fun toAuthoritativePromptText(): String {
    val stdoutText = when {
      !stdout.isNullOrBlank() -> stdout.trim()
      !result.isNullOrBlank() -> result.trim()
      outcome == ToolExecutionOutcome.SUCCESS_EMPTY -> "(command completed successfully with no output, 0 bytes)"
      outcome == ToolExecutionOutcome.CONDITION_NOT_FOUND -> "(requested condition, search pattern, or resource was not found)"
      else -> "empty (0 bytes)"
    }
    val stderrText = if (stderr.isNullOrBlank()) "empty (0 bytes)" else stderr.trim()
    val errorText = if (error.isNullOrBlank()) "none" else error.trim()
    val errorTypeStr = errorType?.code ?: if (isSuccess) "NONE" else "UNKNOWN"
    val recoveryStr = recoverability ?: errorType?.recoveryHint ?: "none"
    val artifactsText = if (artifacts.isEmpty()) "none" else artifacts.joinToString("; ") { "${it.path} (${it.size} bytes, ${it.type})" }

    val truncationBlock = if (truncation.isTruncated) {
      val spilloverInfo = if (!truncation.spilloverPath.isNullOrBlank()) "\nFULL OUTPUT PERSISTED AT: ${truncation.spilloverPath}" else ""
      val hintInfo = if (!truncation.inspectionHint.isNullOrBlank()) "\nINSPECTION HINT: ${truncation.inspectionHint}" else ""
      """
    TRUNCATION: Captured ${truncation.capturedBytes} bytes (${truncation.capturedLines} lines) of ${truncation.originalBytes} bytes (${truncation.originalLines} lines). ${truncation.omittedLines} lines (${truncation.omittedBytes} bytes) omitted.$spilloverInfo$hintInfo
      """.trimIndent() + "\n"
    } else ""

    val responseMetaBlock = if (responseMetadata.isNotEmpty()) {
      """
    RESPONSE METADATA: ${responseMetadata.entries.joinToString(", ") { "${it.key}=${it.value}" }}
      """.trimIndent() + "\n"
    } else ""

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
    OUTCOME: ${outcome.code} (${outcome.description})
    $envHeader$responseMetaBlock$truncationBlock${""}INPUTS: $inputsSummary
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
 * Fully consolidated metadata representation of a Tool contract.
 * Exposes operational constraints, timeout, resources, environment, and schemas.
 */
data class ToolMetadata(
  val name: String,
  val description: String,
  val category: ToolCategory,
  val target: ToolTarget,
  val permissions: Set<ToolPermission>,
  val owningBackend: ExecutionEnvironmentType,
  val workspaceRoot: String,
  val workingDirectoryRequirement: String,
  val defaultTimeoutMs: Long,
  val supportsCancellation: Boolean,
  val mutatesWorkspace: Boolean,
  val requiresNetwork: Boolean,
  val requiresSandbox: Boolean,
  val maxOutputBytes: Int,
  val schema: ToolSchema
)

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
  val workingDirectoryRequirement: String get() = workspaceRoot
  val defaultTimeoutMs: Long get() = 30000L
  val supportsCancellation: Boolean get() = true
  val mutatesWorkspace: Boolean get() = permissions.contains(ToolPermission.WORKSPACE_WRITE)
  val requiresNetwork: Boolean get() = permissions.contains(ToolPermission.NETWORK_ACCESS) || target == ToolTarget.HOST_NETWORK
  val requiresSandbox: Boolean get() = owningBackend == ExecutionEnvironmentType.E2B_CLOUD
  val maxOutputBytes: Int get() = 32768

  val environmentIdentity: EnvironmentIdentity get() = EnvironmentIdentity(
    type = owningBackend,
    name = when (owningBackend) {
      ExecutionEnvironmentType.E2B_CLOUD -> "E2B Cloud MicroVM"
      ExecutionEnvironmentType.ANDROID_NATIVE -> "Android Native Runtime"
      ExecutionEnvironmentType.MCP_BRIDGE -> "MCP Subsystem"
    },
    sessionId = "default",
    workingDirectory = workingDirectoryRequirement,
    capabilities = requiredCapabilities
  )

  /**
   * Returns a complete, consolidated metadata snapshot of this tool contract.
   */
  fun getMetadata(): ToolMetadata = ToolMetadata(
    name = name,
    description = description,
    category = category,
    target = target,
    permissions = permissions,
    owningBackend = owningBackend,
    workspaceRoot = workspaceRoot,
    workingDirectoryRequirement = workingDirectoryRequirement,
    defaultTimeoutMs = defaultTimeoutMs,
    supportsCancellation = supportsCancellation,
    mutatesWorkspace = mutatesWorkspace,
    requiresNetwork = requiresNetwork,
    requiresSandbox = requiresSandbox,
    maxOutputBytes = maxOutputBytes,
    schema = schema
  )

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
