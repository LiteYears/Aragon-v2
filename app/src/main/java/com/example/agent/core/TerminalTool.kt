package com.example.agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import org.json.JSONObject
import java.io.File

import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest

/**
 * Authoritative process-execution tool executing commands inside the E2B Cloud MicroVM (/home/user).
 * Captures real stdout, stderr, exit code, and duration without simulation or fabrication.
 * Integrates static command linting, 32KB payload truncation, and structured error classification.
 */
class TerminalTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" },
  var taskIdProvider: () -> String? = { null }
) : Tool {

  private val linter = CommandLinter()
  private val sessionWorkingDirs = java.util.concurrent.ConcurrentHashMap<String, String>()

  companion object {
    /**
     * Environment variables injected into every remote command inside the E2B MicroVM.
     * - PIP_NO_CACHE_DIR: pip otherwise buffers whole wheels in RAM (observed MemoryError on 554MB torch wheel).
     * - DEBIAN_FRONTEND=noninteractive: apt must never block on interactive prompts (no TTY available).
     */
    val REMOTE_COMMAND_ENVS: Map<String, String> = mapOf(
      "PIP_NO_CACHE_DIR" to "1",
      "PIP_DISABLE_PIP_VERSION_CHECK" to "1",
      "PIP_PROGRESS_BAR" to "off",
      "DEBIAN_FRONTEND" to "noninteractive",
      "PYTHONUNBUFFERED" to "1"
    )
  }

  fun getWorkingDirectory(sessionId: String = sessionIdProvider()): String {
    return sessionWorkingDirs.getOrDefault(sessionId, "/home/user")
  }

  fun setWorkingDirectory(sessionId: String, dir: String) {
    sessionWorkingDirs[sessionId] = dir
  }

  fun resetWorkingDirectory(sessionId: String = sessionIdProvider()) {
    sessionWorkingDirs.remove(sessionId)
  }

  override val name: String = "terminal"
  override val description: String =
    "Execute a shell command in the E2B Cloud MicroVM environment (/home/user). Full Linux CLI with Python 3, pip, and outbound network access."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "command",
        type = "string",
        description = "The shell command to run (e.g. 'ls -la', 'head -n 20 data.csv', 'cat report.md').",
        required = true,
        aliases = listOf("cmd", "c")
      ),
      ToolParameter(
        name = "timeoutSeconds",
        type = "number",
        description = "Maximum execution time in seconds (default: 30).",
        required = false,
        defaultValue = 30,
        aliases = listOf("timeout", "timeout_seconds")
      ),
      ToolParameter(
        name = "best_effort",
        type = "boolean",
        description = "If true, allows non-fatal error patterns (like 2>/dev/null) and captures stderr with [SUPPRESSED_STDERR] tag.",
        required = false,
        defaultValue = false,
        aliases = listOf("bestEffort")
      ),
      ToolParameter(
        name = "max_output",
        type = "number",
        description = "Optional byte limit for captured stdout (default: 32768).",
        required = false,
        defaultValue = 32768,
        aliases = listOf("maxOutput")
      ),
      ToolParameter(
        name = "cwd",
        type = "string",
        description = "Optional working directory relative to workspace root (/home/user).",
        required = false,
        aliases = listOf("workingDirectory", "working_dir", "dir")
      ),
      ToolParameter(
        name = "allow_nonzero",
        type = "boolean",
        description = "If true, treats expected nonzero exit codes as successful probe outcomes.",
        required = false,
        defaultValue = false,
        aliases = listOf("allowNonzero", "allowNonZero")
      ),
      ToolParameter(
        name = "reset_cwd",
        type = "boolean",
        description = "If true, resets working directory back to workspace root (/home/user).",
        required = false,
        defaultValue = false,
        aliases = listOf("resetWorkingDirectory", "reset_working_directory")
      )
    )
  )

  override val category: ToolCategory = ToolCategory.PROCESS
  override val target: ToolTarget = ToolTarget.SANDBOX_PROCESS
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.PROCESS_EXECUTION)
  override val defaultTimeoutMs: Long = 30000L
  override val supportsCancellation: Boolean = true

  override fun isAvailable(): Boolean = true

  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD

  override val workspaceRoot: String = "/home/user"

  override suspend fun execute(
    callId: String,
    arguments: Map<String, Any?>,
    context: ToolExecutionContext
  ): ToolResult = executeInternal(callId, arguments, context)

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    executeInternal(callId, arguments, null)

  private suspend fun executeInternal(
    callId: String,
    arguments: Map<String, Any?>,
    context: ToolExecutionContext?
  ): ToolResult = withContext(Dispatchers.IO) {
    val startTime = System.currentTimeMillis()
    val rawCommand = arguments["command"]?.toString()?.trim() ?: ""
    val timeoutSeconds = (arguments["timeoutSeconds"] as? Number)?.toLong() ?: 30L
    val isBestEffort = (arguments["best_effort"] as? Boolean) == true || (arguments["bestEffort"] as? Boolean) == true
    val maxOutputBytes = (arguments["max_output"] as? Number)?.toInt() ?: (arguments["maxOutput"] as? Number)?.toInt()

    if (rawCommand.isBlank()) {
      return@withContext ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.FAILED,
        arguments = arguments,
        output = null,
        error = "Parameter 'command' cannot be blank.",
        errorType = ToolErrorCode.INVALID_ARGUMENTS,
        duration = System.currentTimeMillis() - startTime,
        stdout = "",
        stderr = "Parameter 'command' cannot be blank.",
        exitCode = 1
      )
    }

    // Static command linting pass with tool args
    val lintResult = linter.lint(rawCommand, isBestEffort = isBestEffort, timeoutSeconds = timeoutSeconds.toInt())
    if (!lintResult.isAllowed) {
      return@withContext ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.FAILED,
        arguments = arguments,
        output = null,
        error = "Command lint error [${lintResult.ruleId}]: ${lintResult.message}",
        errorType = ToolErrorCode.PERMISSION_DENIED,
        duration = System.currentTimeMillis() - startTime,
        stdout = "",
        stderr = lintResult.message ?: "Disallowed command",
        exitCode = 1
      )
    }

    val effectiveCommand = lintResult.rewrittenCommand ?: rawCommand
    val currentSession = context?.sessionId ?: sessionIdProvider()
    val currentTaskId = context?.taskId ?: taskIdProvider()

    val resetCwd = (arguments["reset_cwd"] as? Boolean) == true || (arguments["resetWorkingDirectory"] as? Boolean) == true
    if (resetCwd) {
      resetWorkingDirectory(currentSession)
    }
    val explicitCwd = (arguments["cwd"] ?: arguments["workingDirectory"])?.toString()?.trim()
    if (!explicitCwd.isNullOrBlank()) {
      val resolvedCwd = if (explicitCwd.startsWith("/")) explicitCwd else "/home/user/${explicitCwd.removePrefix("./")}"
      sessionWorkingDirs[currentSession] = resolvedCwd
    }

    val provider = sandboxProvider
    if (provider == null) {
      return@withContext executeLocalWorkspaceCommand(
        callId = callId,
        arguments = arguments,
        command = effectiveCommand,
        startTime = startTime,
        isBestEffort = isBestEffort
      )
    }

    try {
      val defaultRemoteDir = "/home/user"
      val remoteWorkingDir = sessionWorkingDirs.getOrDefault(currentSession, defaultRemoteDir)
      val cdPrefix = if (remoteWorkingDir != defaultRemoteDir) "cd '$remoteWorkingDir' && " else ""
      val pathPrefix = "export PATH=\"/home/user/venv/bin:/home/user/.local/bin:\$PATH\"; "
      val wrappedRemoteCmd = "${pathPrefix}set -o pipefail; trap 'echo \"__ARAGON_CWD__:\$PWD\"' EXIT; $cdPrefix$effectiveCommand"

      val req = RunCommandRequest.builder()
        .command(wrappedRemoteCmd)
        .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
        .envs(REMOTE_COMMAND_ENVS)
        .build()

      val execution = provider.executeCommand(currentSession, req)
      val duration = System.currentTimeMillis() - startTime
      var rawStdout = execution.stdoutText()
      if (rawStdout.contains("__ARAGON_CWD__:")) {
        val lines = rawStdout.lines()
        val cwdLine = lines.lastOrNull { it.startsWith("__ARAGON_CWD__:") }
        if (cwdLine != null) {
          val newCwd = cwdLine.substringAfter("__ARAGON_CWD__:").trim()
          if (newCwd.isNotBlank()) {
            sessionWorkingDirs[currentSession] = newCwd
          }
          rawStdout = lines.filter { !it.startsWith("__ARAGON_CWD__:") }.joinToString("\n").trim()
        }
      }
      val envTag = "[env=e2b_cloud]"
      val combinedStdout = if (rawStdout.isBlank()) "" else if (rawStdout.startsWith("[env=")) rawStdout else "$envTag\n$rawStdout"
      val rawStderr = execution.stderrText()
      val exitCode = execution.exitCode ?: 0

      val isSearchCmd = ObservationPayloadPolicy.isSearchOrValidationCommand(effectiveCommand)
      val isValidationCmd = ObservationPayloadPolicy.isValidationCheckCommand(effectiveCommand)
      val isDiffCmd = effectiveCommand.trim().startsWith("diff ")

      val allowNonZero = (arguments["allow_nonzero"] as? Boolean) == true || (arguments["allowNonZero"] as? Boolean) == true
      val expectedCodes = (arguments["expectedExitCodes"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty()
      val isExpectedNonZero = (exitCode in expectedCodes) || (allowNonZero && exitCode != 0) || (isBestEffort && exitCode == 1) ||
        (exitCode == 1 && (isSearchCmd || isValidationCmd || isDiffCmd) && rawStderr.isBlank())
      val isSuccess = (exitCode == 0 || isExpectedNonZero) && execution.error == null

      // Apply structured error taxonomy classification
      val infraCode = if (execution.error != null) {
        val errVal = execution.error?.value.orEmpty()
        when {
          errVal.contains("404") -> 404
          errVal.contains("502") -> 502
          errVal.contains("500") -> 500
          errVal.contains("401") -> 401
          else -> null
        }
      } else null

      val structuredError = if (!isSuccess) {
        ErrorTaxonomyClassifier.classify(
          exitCode = exitCode,
          stdout = combinedStdout,
          stderr = rawStderr,
          command = effectiveCommand,
          infraStatusCode = infraCode,
          errorName = execution.error?.name
        )
      } else null

      val errorType = when {
        isSuccess -> null
        structuredError?.errorClass == KernelErrorClass.SANDBOX_INFRA_ERROR -> ToolErrorCode.SANDBOX_INFRA_ERROR
        structuredError?.errorClass == KernelErrorClass.COMMAND_NOT_FOUND -> ToolErrorCode.COMMAND_NOT_FOUND
        structuredError?.errorClass == KernelErrorClass.TIMEOUT -> ToolErrorCode.TIMEOUT
        structuredError?.errorClass == KernelErrorClass.HTTP_ERROR -> ToolErrorCode.HTTP_ERROR
        structuredError?.errorClass == KernelErrorClass.PERMISSION_DENIED -> ToolErrorCode.PERMISSION_DENIED
        structuredError?.errorClass == KernelErrorClass.NETWORK_ERROR -> ToolErrorCode.NETWORK_ERROR
        execution.error?.name == "E2BStaleSessionError" -> ToolErrorCode.STALE_SESSION
        else -> ToolErrorCode.PROCESS_FAILED
      }

      // Apply Observation Payload Policy (configurable output limits + spillover)
      val formattedObs = ObservationPayloadPolicy.processOutput(
        workspaceDir = workspaceDir,
        stepId = callId,
        rawStdout = rawStdout,
        rawStderr = rawStderr,
        customMaxBytes = maxOutputBytes,
        isSuppressedStderr = lintResult.isSuppressedStderr,
        fileExtension = "txt"
      )

      val outcome = ObservationPayloadPolicy.classifyOutcome(
        toolName = name,
        status = if (isSuccess) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
        exitCode = exitCode,
        stdout = rawStdout,
        stderr = rawStderr,
        command = effectiveCommand,
        isTruncated = formattedObs.isTruncated
      )

      val structuredJson = JSONObject().apply {
        put("success", isSuccess)
        put("exitCode", exitCode)
        put("stdout", formattedObs.contextPayload)
        put("stderr", rawStderr)
        if (execution.error != null) {
          put("error", execution.error?.value ?: "Remote error")
          put("errorName", execution.error?.name ?: "Error")
        }
        if (errorType != null) {
          put("errorType", errorType.code)
          val hint = if (errorType == ToolErrorCode.COMMAND_NOT_FOUND) {
            val missing = structuredError?.metadata?.get("missing_binary") ?: "command"
            "Command '$missing' is not installed in the E2B microVM. Check available tools with 'check_tools' or provision it with 'environment_capability' if supported."
          } else {
            errorType.recoveryHint
          }
          put("recoveryHint", hint)
        }
        if (structuredError != null) {
          put("classifiedError", structuredError.errorClass.code)
        }
        if (formattedObs.isTruncated) {
          put("truncated", true)
          put("fullOutputPath", formattedObs.spilloverPath)
        }
        put("outcome", outcome.code)
        put("backend", provider.name)
        put("environment", "E2B_CLOUD")
        put("sessionId", currentSession)
      }.toString()

      val artifacts = try {
        provider.syncArtifacts(currentSession, workspaceDir, currentTaskId)
        artifactManager.syncWorkspace(currentTaskId)
      } catch (_: Exception) {
        artifactManager.syncWorkspace(currentTaskId)
      }

      val remoteExecContext = ToolExecutionContext(
        environmentType = owningBackend,
        backendName = provider.name,
        sessionId = currentSession,
        taskId = currentTaskId,
        workspaceDir = workspaceDir,
        workingDirectory = sessionWorkingDirs.getOrDefault(currentSession, "/home/user"),
        capabilities = setOf("shell", "process_execution")
      )

      val usefulOutput = when {
        !isSuccess -> structuredJson
        outcome == ToolExecutionOutcome.CONDITION_NOT_FOUND -> {
          when {
            isSearchCmd -> "Condition not found: 0 matching lines found for search in '$effectiveCommand'."
            isValidationCmd -> "Validation condition evaluated to FALSE (exit code $exitCode)."
            else -> formattedObs.contextPayload.ifEmpty { "Condition not found (0 results)." }
          }
        }
        outcome == ToolExecutionOutcome.SUCCESS_EMPTY -> {
          "(command executed successfully with exit code 0, no output produced)"
        }
        else -> formattedObs.contextPayload
      }

      return@withContext ToolResult(
        callId = callId,
        toolName = name,
        status = if (isSuccess) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
        arguments = arguments,
        output = usefulOutput,
        error = if (isSuccess) null else (execution.error?.value ?: rawStderr.ifBlank { "Remote process exited with code $exitCode" }),
        errorType = errorType,
        duration = duration,
        stdout = formattedObs.contextPayload,
        stderr = rawStderr,
        exitCode = exitCode,
        artifacts = artifacts,
        metadata = mapOf(
          "remote" to true,
          "backend" to provider.name,
          "environment" to "E2B_CLOUD",
          "sessionId" to currentSession,
          "isTruncated" to formattedObs.isTruncated,
          "spilloverPath" to (formattedObs.spilloverPath ?: ""),
          "outcome" to outcome.code
        ),
        executionContext = remoteExecContext,
        outcome = outcome,
        truncation = formattedObs.truncationDetails,
        responseMetadata = mapOf(
          "command" to effectiveCommand,
          "exitCode" to exitCode,
          "isSearch" to isSearchCmd,
          "isValidation" to isValidationCmd
        )
      )
    } catch (ce: CancellationException) {
      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.CANCELLED,
        arguments = arguments,
        output = null,
        error = "Command execution was cancelled.",
        errorType = ToolErrorCode.CANCELLED,
        duration = System.currentTimeMillis() - startTime,
        stdout = "",
        stderr = "Cancelled",
        exitCode = -1,
        artifacts = artifactManager.getArtifacts()
      )
    } catch (e: Exception) {
      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.FAILED,
        arguments = arguments,
        output = null,
        error = "Process execution failed: ${e.message}",
        errorType = ToolErrorCode.SANDBOX_ERROR,
        duration = System.currentTimeMillis() - startTime,
        stdout = "",
        stderr = e.message ?: "Execution error",
        exitCode = 1,
        artifacts = artifactManager.getArtifacts()
      )
    }
  }

  private fun executeLocalWorkspaceCommand(
    callId: String,
    arguments: Map<String, Any?>,
    command: String,
    startTime: Long,
    isBestEffort: Boolean
  ): ToolResult {
    val clean = command.trim()
    val parts = clean.split(Regex("\\s+"))
    val baseCmd = parts.firstOrNull()?.lowercase() ?: ""
    val cmdArgs = parts.drop(1)

    var stdout = ""
    var stderr = ""
    var exitCode = 0

    try {
      when (baseCmd) {
        "pwd" -> {
          stdout = "/workspace"
        }
        "date" -> {
          stdout = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        }
        "ls", "dir" -> {
          val files = workspaceDir.listFiles() ?: emptyArray()
          val sb = StringBuilder()
          for (f in files.sortedBy { it.name }) {
            val type = if (f.isDirectory) "d" else "-"
            val size = if (f.isFile) f.length() else 4096
            sb.appendLine(String.format(java.util.Locale.US, "%s %8d  %s", type, size, f.name))
          }
          stdout = sb.toString().trimEnd()
          if (stdout.isEmpty()) stdout = "(empty directory)"
        }
        "cat" -> {
          val targetPath = cmdArgs.firstOrNull() ?: ""
          if (targetPath.isBlank()) {
            stderr = "cat: missing file operand"
            exitCode = 1
          } else {
            val file = PathSecurity.resolveSafe(workspaceDir, targetPath)
            if (!file.exists() || !file.isFile) {
              stderr = "cat: $targetPath: No such file"
              exitCode = 1
            } else {
              stdout = file.readText(java.nio.charset.StandardCharsets.UTF_8)
            }
          }
        }
        "head" -> {
          val nArg = if (cmdArgs.contains("-n")) {
            val idx = cmdArgs.indexOf("-n")
            cmdArgs.getOrNull(idx + 1)?.toIntOrNull() ?: 10
          } else 10
          val targetPath = cmdArgs.lastOrNull { !it.startsWith("-") && it.toIntOrNull() == null } ?: ""
          if (targetPath.isBlank()) {
            stderr = "head: missing file operand"
            exitCode = 1
          } else {
            val file = PathSecurity.resolveSafe(workspaceDir, targetPath)
            if (!file.exists() || !file.isFile) {
              stderr = "head: $targetPath: No such file"
              exitCode = 1
            } else {
              val lines = file.readLines()
              stdout = lines.take(nArg).joinToString("\n")
            }
          }
        }
        "tail" -> {
          val nArg = if (cmdArgs.contains("-n")) {
            val idx = cmdArgs.indexOf("-n")
            cmdArgs.getOrNull(idx + 1)?.toIntOrNull() ?: 10
          } else 10
          val targetPath = cmdArgs.lastOrNull { !it.startsWith("-") && it.toIntOrNull() == null } ?: ""
          if (targetPath.isBlank()) {
            stderr = "tail: missing file operand"
            exitCode = 1
          } else {
            val file = PathSecurity.resolveSafe(workspaceDir, targetPath)
            if (!file.exists() || !file.isFile) {
              stderr = "tail: $targetPath: No such file"
              exitCode = 1
            } else {
              val lines = file.readLines()
              stdout = lines.takeLast(nArg).joinToString("\n")
            }
          }
        }
        "wc" -> {
          val targetPath = cmdArgs.lastOrNull { !it.startsWith("-") } ?: ""
          if (targetPath.isBlank()) {
            stderr = "wc: missing file operand"
            exitCode = 1
          } else {
            val file = PathSecurity.resolveSafe(workspaceDir, targetPath)
            if (!file.exists() || !file.isFile) {
              stderr = "wc: $targetPath: No such file"
              exitCode = 1
            } else {
              val lines = file.readLines()
              val wordCount = lines.sumOf { it.split(Regex("\\s+")).filter { w -> w.isNotBlank() }.size }
              val charCount = file.length()
              stdout = "${lines.size}  $wordCount  $charCount  $targetPath"
            }
          }
        }
        "grep" -> {
          val ignoreCase = cmdArgs.contains("-i")
          val nonFlags = cmdArgs.filter { !it.startsWith("-") }
          val pattern = nonFlags.firstOrNull() ?: ""
          val targetPath = nonFlags.getOrNull(1) ?: "."
          if (pattern.isBlank()) {
            stderr = "grep: missing pattern"
            exitCode = 1
          } else {
            val file = PathSecurity.resolveSafe(workspaceDir, targetPath)
            val regex = Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            val sb = StringBuilder()
            if (file.isFile) {
              file.readLines().forEachIndexed { i, line ->
                if (regex.containsMatchIn(line)) sb.appendLine("${i + 1}:$line")
              }
            } else if (file.isDirectory) {
              file.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(workspaceDir).path
                f.readLines().forEachIndexed { i, line ->
                  if (regex.containsMatchIn(line)) sb.appendLine("$rel:${i + 1}:$line")
                }
              }
            }
            stdout = sb.toString().trimEnd()
            if (stdout.isBlank()) exitCode = 1
          }
        }
        "mkdir" -> {
          val targetPath = cmdArgs.lastOrNull { !it.startsWith("-") } ?: ""
          if (targetPath.isBlank()) {
            stderr = "mkdir: missing operand"
            exitCode = 1
          } else {
            val dir = PathSecurity.resolveSafe(workspaceDir, targetPath)
            dir.mkdirs()
            stdout = "Created directory $targetPath"
          }
        }
        "rm" -> {
          val targetPath = cmdArgs.lastOrNull { !it.startsWith("-") } ?: ""
          if (targetPath.isBlank()) {
            stderr = "rm: missing operand"
            exitCode = 1
          } else {
            val file = PathSecurity.resolveSafe(workspaceDir, targetPath)
            if (file.exists()) {
              if (file.isDirectory) file.deleteRecursively() else file.delete()
              artifactManager.syncWorkspace()
              stdout = "Removed $targetPath"
            } else {
              if (!cmdArgs.contains("-f")) {
                stderr = "rm: cannot remove '$targetPath': No such file"
                exitCode = 1
              }
            }
          }
        }
        "touch" -> {
          val targetPath = cmdArgs.firstOrNull() ?: ""
          if (targetPath.isBlank()) {
            stderr = "touch: missing file operand"
            exitCode = 1
          } else {
            val file = PathSecurity.resolveSafe(workspaceDir, targetPath)
            file.parentFile?.mkdirs()
            if (!file.exists()) file.createNewFile() else file.setLastModified(System.currentTimeMillis())
            artifactManager.syncWorkspace()
            stdout = "Updated timestamp for $targetPath"
          }
        }
        "echo" -> {
          if (clean.contains(">")) {
            val isAppend = clean.contains(">>")
            val sep = if (isAppend) ">>" else ">"
            val textPart = clean.substringBefore(sep).removePrefix("echo").trim().removeSurrounding("\"").removeSurrounding("'")
            val filePart = clean.substringAfter(sep).trim()
            val file = PathSecurity.resolveSafe(workspaceDir, filePart)
            file.parentFile?.mkdirs()
            if (isAppend) file.appendText(textPart + "\n") else file.writeText(textPart + "\n")
            artifactManager.syncWorkspace()
            stdout = ""
          } else {
            stdout = clean.removePrefix("echo").trim().removeSurrounding("\"").removeSurrounding("'")
          }
        }
        else -> {
          stderr = "Command '$baseCmd' requires E2B Cloud MicroVM. Supported local commands: ls, cat, head, tail, wc, grep, mkdir, rm, touch, echo, pwd, date."
          exitCode = 1
        }
      }
    } catch (e: Exception) {
      stderr = e.message ?: "Execution error"
      exitCode = 1
    }

    val isSuccess = exitCode == 0
    val duration = System.currentTimeMillis() - startTime
    val tag = "[env=local_workspace]"
    val combinedStdout = if (stdout.isBlank()) "" else "$tag\n$stdout"

    return ToolResult(
      callId = callId,
      toolName = name,
      status = if (isSuccess || isBestEffort) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
      arguments = arguments,
      output = if (isSuccess) stdout else null,
      error = if (!isSuccess && !isBestEffort) stderr else null,
      duration = duration,
      stdout = combinedStdout,
      stderr = stderr,
      exitCode = exitCode,
      artifacts = artifactManager.getArtifacts(),
      outcome = if (isSuccess) ToolExecutionOutcome.SUCCESS_WITH_RESULTS else ToolExecutionOutcome.EXECUTION_FAILED
    )
  }
}
