package com.example.agent.core

import java.util.UUID

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
 * Schema parameter definition for a tool.
 */
data class ToolParameter(
  val name: String,
  val type: String, // "string", "number", "boolean", "array", "object"
  val description: String,
  val required: Boolean = true,
  val enumValues: List<String>? = null
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
      "\"${param.name}\": { \"type\": \"${param.type}\", \"description\": \"${param.description}\"$enumPart }"
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
}

/**
 * A tool invocation requested by the LLM.
 */
data class ToolCall(
  val callId: String = UUID.randomUUID().toString(),
  val toolName: String,
  val arguments: Map<String, Any?>
)

/**
 * The authoritative result produced ONLY by the application executing the tool.
 */
data class ToolResult(
  val callId: String,
  val toolName: String,
  val status: ToolStatus,
  val arguments: Map<String, Any?>,
  val output: String?,
  val error: String?,
  val artifacts: List<Artifact> = emptyList(),
  val duration: Long,
  val stdout: String? = null,
  val stderr: String? = null,
  val exitCode: Int? = null
) {
  // Compatibility alias for duration
  val durationMs: Long get() = duration
}

/**
 * Structured observation created from an authoritative ToolResult.
 * Represents what actually happened, never what the LLM claimed happened.
 */
data class Observation(
  val id: String = UUID.randomUUID().toString(),
  val callId: String,
  val toolName: String,
  val isSuccess: Boolean,
  val summary: String,
  val rawOutput: String,
  val arguments: Map<String, Any?> = emptyMap(),
  val status: ToolStatus = if (isSuccess) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
  val exitCode: Int? = null,
  val stdout: String? = null,
  val stderr: String? = null,
  val error: String? = null,
  val artifacts: List<Artifact> = emptyList(),
  val timestamp: Long = System.currentTimeMillis()
) {
  /**
   * The explicit, unambiguous plaintext representation provided to the next LLM turn.
   */
  fun toAuthoritativePromptText(): String {
    val stdoutText = if (stdout.isNullOrBlank()) "empty (0 bytes)" else stdout.trim()
    val stderrText = if (stderr.isNullOrBlank()) "empty (0 bytes)" else stderr.trim()
    val errorText = if (error.isNullOrBlank()) "none" else error.trim()
    val artifactsText = if (artifacts.isEmpty()) "none" else artifacts.joinToString { "${it.path} (${it.size} bytes)" }
    val argsText = if (arguments.isEmpty()) "{}" else arguments.entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value}" }

    return """
    === OBSERVATION ===
    TOOL: $toolName
    CALL ID: $callId
    ARGUMENTS: $argsText
    STATUS: ${status.name}
    EXIT CODE: ${exitCode ?: (if (isSuccess) 0 else 1)}
    STDOUT: $stdoutText
    STDERR: $stderrText
    ERROR: $errorText
    ARTIFACTS: $artifactsText
    ===================
    """.trimIndent()
  }
}

/**
 * The simple, clean Tool contract.
 */
interface Tool {
  val name: String
  val description: String
  val schema: ToolSchema

  /**
   * Authoritatively execute the tool with validated arguments.
   */
  suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult
}
