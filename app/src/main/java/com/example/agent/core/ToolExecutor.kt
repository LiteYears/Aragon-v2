package com.example.agent.core

import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Authoritatively executes tool calls against the registered tools.
 * Handles timing, schema validation, exception safety, and produces structured ToolResults.
 */
class ToolExecutor(
  private val registry: ToolRegistry,
  private val workspace: WorkspaceManager
) {

  /**
   * Authoritatively execute a single tool call.
   */
  suspend fun execute(toolCall: ToolCall): ToolResult {
    val startTime = System.currentTimeMillis()

    // 1. Schema Validation
    val validationError = registry.validate(toolCall)
    if (validationError != null) {
      return ToolResult(
        callId = toolCall.callId,
        toolName = toolCall.toolName,
        status = ToolStatus.FAILED,
        arguments = toolCall.arguments,
        output = null,
        error = "Validation error: $validationError",
        duration = System.currentTimeMillis() - startTime,
        stdout = null,
        stderr = "Validation error: $validationError",
        exitCode = 1
      )
    }

    val tool = registry.getTool(toolCall.toolName)!!

    return try {
      tool.execute(toolCall.callId, toolCall.arguments)
    } catch (ce: CancellationException) {
      ToolResult(
        callId = toolCall.callId,
        toolName = toolCall.toolName,
        status = ToolStatus.CANCELLED,
        arguments = toolCall.arguments,
        output = null,
        error = "Tool execution cancelled by user or timeout.",
        duration = System.currentTimeMillis() - startTime,
        stdout = null,
        stderr = "Cancelled by user or timeout",
        exitCode = -1
      )
    } catch (e: Exception) {
      ToolResult(
        callId = toolCall.callId,
        toolName = toolCall.toolName,
        status = ToolStatus.FAILED,
        arguments = toolCall.arguments,
        output = null,
        error = "Tool failed with exception: ${e.javaClass.simpleName}: ${e.message}",
        duration = System.currentTimeMillis() - startTime,
        stdout = null,
        stderr = "${e.javaClass.simpleName}: ${e.message}",
        exitCode = 1
      )
    }
  }

  /**
   * Converts an authoritative ToolResult into an authoritative Observation.
   * Explicitly details TOOL, CALL ID, ARGUMENTS, STATUS, EXIT CODE, STDOUT, STDERR, ERROR, ARTIFACTS.
   */
  fun createObservation(result: ToolResult): Observation {
    val isSuccess = result.status == ToolStatus.SUCCEEDED
    val summary = if (isSuccess) {
      "Tool '${result.toolName}' succeeded in ${result.duration}ms."
    } else {
      "Tool '${result.toolName}' failed (${result.status}): ${result.error ?: "Unknown error"}"
    }

    val obs = Observation(
      id = UUID.randomUUID().toString(),
      callId = result.callId,
      toolName = result.toolName,
      isSuccess = isSuccess,
      summary = summary,
      rawOutput = "", // populated below
      arguments = result.arguments,
      status = result.status,
      exitCode = result.exitCode,
      stdout = result.stdout,
      stderr = result.stderr,
      error = result.error,
      artifacts = result.artifacts,
      timestamp = System.currentTimeMillis()
    )

    return obs.copy(rawOutput = obs.toAuthoritativePromptText())
  }
}
