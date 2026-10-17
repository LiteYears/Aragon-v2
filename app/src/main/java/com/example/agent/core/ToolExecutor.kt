package com.example.agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritatively executes tool calls according to the deterministic 8-step pipeline:
 * 1. Tool Call Identity & Idempotency check
 * 2. Tool Registry Lookup
 * 3. Availability Check
 * 4. Schema Validation
 * 5. Permission Check
 * 6. Argument Normalization
 * 7. Execution within Timeout & Cancellation Scope
 * 8. Real Result -> Observation
 *
 * Never silently substitutes tools, fakes execution, or downgrades failed tools.
 */
class ToolExecutor(
  val registry: ToolRegistry,
  val artifactManager: ArtifactManager,
  val allowedPermissions: Set<ToolPermission> = ToolPermission.values().toSet()
) {
  constructor(registry: ToolRegistry, workspaceDir: java.io.File, artifactManager: ArtifactManager) : this(registry, artifactManager)

  // Idempotency cache preventing duplicate tool invocation for identical callIds
  private val executionHistory = ConcurrentHashMap<String, ToolResult>()

  /**
   * Resets execution history when starting an isolated new session.
   */
  fun resetHistory() {
    executionHistory.clear()
  }

  /**
   * Executes a tool invocation following the strict deterministic pipeline.
   */
  suspend fun execute(toolCall: ToolCall, context: ToolExecutionContext? = null): ToolResult {
    val startTime = System.currentTimeMillis()
    val normalizedCall = registry.normalizeToolCall(toolCall)

    // Step 1: Tool Call Identity & Idempotency check
    // An identical callId may only ever be executed once with identical tool name and arguments.
    executionHistory[normalizedCall.callId]?.let { cached ->
      if (cached.toolName == normalizedCall.toolName && cached.arguments == normalizedCall.arguments) {
        return cached
      }
    }

    // Step 2: Tool Registry Lookup
    val tool = registry.getTool(normalizedCall.toolName)

    val resolvedContext = context ?: ToolExecutionContext(
      environmentType = tool?.owningBackend ?: ExecutionEnvironmentType.E2B_CLOUD,
      backendName = when (tool?.owningBackend) {
        ExecutionEnvironmentType.E2B_CLOUD -> "E2B Cloud MicroVM"
        else -> "E2B Cloud MicroVM"
      },
      workspaceDir = artifactManager.workspaceDir,
      workingDirectory = when (tool?.owningBackend) {
        ExecutionEnvironmentType.E2B_CLOUD -> "/home/user"
        else -> "/home/user"
      }
    )

    if (tool == null) {
      val availableNames = registry.getAvailableTools().joinToString(", ") { it.name }
      val result = ToolResult(
        callId = normalizedCall.callId,
        toolName = normalizedCall.toolName,
        status = ToolStatus.FAILED,
        arguments = normalizedCall.arguments,
        output = null,
        error = "Tool '${normalizedCall.toolName}' was not found in the canonical ToolRegistry. Available tools: $availableNames",
        errorType = ToolErrorCode.TOOL_NOT_FOUND,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1,
        artifacts = artifactManager.getArtifacts(),
        executionContext = resolvedContext
      )
      executionHistory[normalizedCall.callId] = result
      return result
    }

    // Step 3: Availability Check
    if (!tool.isAvailable()) {
      val result = ToolResult(
        callId = normalizedCall.callId,
        toolName = tool.name,
        status = ToolStatus.FAILED,
        arguments = normalizedCall.arguments,
        output = null,
        error = "Tool '${tool.name}' is registered but currently unavailable in this runtime environment.",
        errorType = ToolErrorCode.UNSUPPORTED_OPERATION,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1,
        artifacts = artifactManager.getArtifacts(),
        executionContext = resolvedContext
      )
      executionHistory[normalizedCall.callId] = result
      return result
    }

    // Step 4: Schema Validation
    val validationError = registry.validate(normalizedCall)
    if (validationError != null) {
      val result = ToolResult(
        callId = normalizedCall.callId,
        toolName = tool.name,
        status = ToolStatus.FAILED,
        arguments = normalizedCall.arguments,
        output = null,
        error = "Argument validation error: $validationError",
        errorType = ToolErrorCode.INVALID_ARGUMENTS,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1,
        artifacts = artifactManager.getArtifacts(),
        executionContext = resolvedContext
      )
      executionHistory[normalizedCall.callId] = result
      return result
    }

    // Step 5: Permission Check
    val missingPermissions = tool.permissions - allowedPermissions
    if (missingPermissions.isNotEmpty()) {
      val result = ToolResult(
        callId = normalizedCall.callId,
        toolName = tool.name,
        status = ToolStatus.FAILED,
        arguments = normalizedCall.arguments,
        output = null,
        error = "Permission denied: tool '${tool.name}' requires permissions ${missingPermissions.joinToString()}, which are not granted.",
        errorType = ToolErrorCode.PERMISSION_DENIED,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1,
        artifacts = artifactManager.getArtifacts(),
        executionContext = resolvedContext
      )
      executionHistory[normalizedCall.callId] = result
      return result
    }

    // Step 6: Argument Normalization
    val normalizedArgs = normalizeArguments(normalizedCall.arguments)

    // Step 7: Execution within Timeout & Cancellation Scope
    val result = try {
      withTimeout(tool.defaultTimeoutMs) {
        val execRes = tool.execute(normalizedCall.callId, normalizedArgs, resolvedContext)
        if (execRes.executionContext == null) {
          execRes.copy(executionContext = resolvedContext)
        } else execRes
      }
    } catch (tce: TimeoutCancellationException) {
      ToolResult(
        callId = normalizedCall.callId,
        toolName = tool.name,
        status = ToolStatus.FAILED,
        arguments = normalizedArgs,
        output = null,
        error = "Tool execution timed out after ${tool.defaultTimeoutMs}ms.",
        errorType = ToolErrorCode.TIMEOUT,
        duration = System.currentTimeMillis() - startTime,
        exitCode = -1,
        artifacts = artifactManager.getArtifacts(),
        executionContext = resolvedContext
      )
    } catch (ce: CancellationException) {
      ToolResult(
        callId = normalizedCall.callId,
        toolName = tool.name,
        status = ToolStatus.CANCELLED,
        arguments = normalizedArgs,
        output = null,
        error = "Tool execution was cancelled.",
        errorType = ToolErrorCode.CANCELLED,
        duration = System.currentTimeMillis() - startTime,
        exitCode = -1,
        artifacts = artifactManager.getArtifacts(),
        executionContext = resolvedContext
      )
    } catch (se: SecurityException) {
      val isCrossEnv = se.message?.contains("Cross-environment", ignoreCase = true) == true
      ToolResult(
        callId = normalizedCall.callId,
        toolName = tool.name,
        status = ToolStatus.FAILED,
        arguments = normalizedArgs,
        output = null,
        error = se.message ?: "Path traversal security error",
        errorType = if (isCrossEnv) ToolErrorCode.CROSS_ENV_PATH_MISUSE else ToolErrorCode.PATH_NOT_ALLOWED,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1,
        artifacts = artifactManager.getArtifacts(),
        executionContext = resolvedContext
      )
    } catch (t: Throwable) {
      if (t is CancellationException) throw t
      ToolResult(
        callId = normalizedCall.callId,
        toolName = tool.name,
        status = ToolStatus.FAILED,
        arguments = normalizedArgs,
        output = null,
        error = "Tool failed with exception: ${t.javaClass.simpleName}: ${t.message}",
        errorType = ToolErrorCode.SANDBOX_ERROR,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1,
        artifacts = artifactManager.getArtifacts(),
        executionContext = resolvedContext
      )
    }

    executionHistory[normalizedCall.callId] = result
    return result
  }

  /**
   * Normalizes argument map: strips extraneous whitespaces from string values.
   */
  private fun normalizeArguments(args: Map<String, Any?>): Map<String, Any?> {
    return args.mapValues { (key, value) ->
      if (value is String && key != "content" && key != "replacementContent" && key != "targetContent") value.trim() else value
    }
  }

  /**
   * Converts an authoritative ToolResult into an authoritative Observation.
   * Delivers the actual useful output, stderr, exit code, artifacts, duration,
   * and recoverability to the LLM.
   */
  fun createObservation(result: ToolResult): Observation {
    val inputsSummary = if (result.arguments.isEmpty()) "{}" else {
      result.arguments.entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value}" }
    }

    val usefulOutput = when {
      !result.output.isNullOrBlank() -> result.output
      !result.stdout.isNullOrBlank() -> result.stdout
      else -> null
    }

    val summary = if (result.isSuccess) {
      val outPreview = usefulOutput?.lines()?.firstOrNull()?.take(80) ?: "ok"
      "Tool '${result.toolName}' succeeded in ${result.duration}ms: $outPreview"
    } else {
      val errText = result.error ?: result.stderr ?: "Unknown failure"
      "Tool '${result.toolName}' failed (${result.errorType?.name ?: "FAILED"}): ${errText.take(120)}"
    }

    val obs = Observation(
      id = UUID.randomUUID().toString(),
      callId = result.callId,
      toolName = result.toolName,
      status = result.status,
      isSuccess = result.isSuccess,
      inputsSummary = inputsSummary,
      result = usefulOutput,
      error = result.error,
      errorType = result.errorType,
      exitCode = result.exitCode,
      stdout = result.stdout,
      stderr = result.stderr,
      durationMs = result.duration,
      artifacts = result.artifacts,
      recoverability = result.errorType?.recoveryHint,
      summary = summary,
      executionContext = result.executionContext
    )

    return obs.copy(rawOutput = obs.toAuthoritativePromptText())
  }
}
