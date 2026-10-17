package com.example.agent.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Uploads a file from the device-side artifact workspace (imported documents, prior downloads)
 * INTO the active E2B Cloud MicroVM filesystem at /home/user. The E2B Cloud MicroVM is the only
 * execution environment; the device workspace is a transfer source only.
 */
class TransferArtifactToSandboxTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" }
) : Tool {
  override val name: String = "transfer_artifact_to_sandbox"
  override val description: String =
    "Upload a file from the device-side artifact workspace into the active E2B Cloud MicroVM filesystem at /home/user. Use when a file exists on the device (e.g. an imported document) but not yet inside the sandbox. Validates presence, computes SHA-256 checksum, transfers bytes, and verifies receipt."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "localPath",
        type = "string",
        description = "Path to the source file relative to the device-side artifact workspace (upload source).",
        required = true
      ),
      ToolParameter(
        name = "remotePath",
        type = "string",
        description = "Target destination path inside the E2B microVM (e.g. 'script.py' or '/home/user/script.py'). Defaults to same name as localPath.",
        required = false
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.WORKSPACE_WRITE)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD
  override fun isAvailable(): Boolean = sandboxProvider?.isRemote == true

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val localPath = arguments["localPath"]?.toString()?.trim() ?: ""
      val remotePath = arguments["remotePath"]?.toString()?.trim()?.ifBlank { null } ?: localPath
      val provider = sandboxProvider
      val sessionId = sessionIdProvider()

      if (localPath.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'localPath' cannot be blank.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      var localFile = PathSecurity.resolveSafe(workspaceDir, localPath)
      if (!localFile.exists() || !localFile.isFile) {
        val targetName = File(localPath).name
        val matched = workspaceDir.walkTopDown().maxDepth(4).firstOrNull {
          it.isFile && (it.name.equals(targetName, ignoreCase = true) || it.name.equals(localPath, ignoreCase = true))
        }
        if (matched != null) {
          localFile = matched
        }
      }
      if (!localFile.exists() || !localFile.isFile) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "File '$localPath' was not found in the device-side artifact workspace. Check the path or create the file first (write_file writes directly inside the E2B microVM).",
          errorType = ToolErrorCode.FILE_NOT_FOUND,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      if (provider == null || !provider.isRemote) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "E2B Cloud Sandbox is not configured or offline. Configure your E2B API Key in Settings.",
          errorType = ToolErrorCode.SANDBOX_INFRA_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      // Compute local SHA-256 checksum
      val bytes = localFile.readBytes()
      val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

      val pushed = provider.pushFileToRemote(sessionId, remotePath, localFile)
      if (!pushed) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to push file '$localPath' to remote sandbox '$remotePath' on ${provider.name}.",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      val outJson = JSONObject().apply {
        put("success", true)
        put("localPath", localPath)
        put("remotePath", remotePath)
        put("sizeBytes", bytes.size)
        put("sha256", sha256)
        put("targetBackend", provider.name)
        put("targetEnvironment", "E2B_CLOUD (/home/user)")
      }.toString(2)

      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = outJson,
        error = null,
        duration = System.currentTimeMillis() - startTime,
        stdout = outJson,
        exitCode = 0,
        artifacts = artifactManager.getArtifacts()
      )
    }
}

/**
 * Downloads a generated deliverable file from the E2B Cloud MicroVM filesystem
 * (/home/user) into the device-side artifact workspace for user access.
 */
class DownloadArtifactFromSandboxTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" }
) : Tool {
  override val name: String = "download_artifact_from_sandbox"
  override val description: String =
    "Download a generated deliverable file from the E2B Cloud MicroVM filesystem (/home/user) into the device-side artifact workspace for user access. Verifies byte transfer and registers the file in the authoritative artifact system with full provenance."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "remotePath",
        type = "string",
        description = "Path to the file inside the E2B microVM (e.g. 'output.csv', 'report.pdf', '/home/user/chart.png').",
        required = true
      ),
      ToolParameter(
        name = "localPath",
        type = "string",
        description = "Destination path in the device-side artifact workspace (e.g. 'downloads/output.csv'). Defaults to base name of remotePath.",
        required = false
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.WORKSPACE_WRITE)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD
  override fun isAvailable(): Boolean = sandboxProvider?.isRemote == true

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val remotePath = arguments["remotePath"]?.toString()?.trim() ?: ""
      val localPath = arguments["localPath"]?.toString()?.trim()?.ifBlank { null }
        ?: File(remotePath).name
      val provider = sandboxProvider
      val sessionId = sessionIdProvider()

      if (remotePath.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'remotePath' cannot be blank.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      if (provider == null || !provider.isRemote) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "E2B Cloud Sandbox is not configured or offline. Configure your E2B API Key in Settings.",
          errorType = ToolErrorCode.SANDBOX_INFRA_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      val destFile = PathSecurity.resolveSafe(workspaceDir, localPath)
      destFile.parentFile?.mkdirs()

      val pulled = provider.pullFileFromRemote(sessionId, remotePath, destFile)
      if (!pulled || !destFile.exists()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to pull remote file '$remotePath' from ${provider.name}. Verify the file exists in the remote sandbox.",
          errorType = ToolErrorCode.FILE_NOT_FOUND,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      val bytes = destFile.readBytes()
      val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
      val artifact = artifactManager.registerArtifact(
        relativePath = localPath,
        createdByCallId = callId,
        originatingEnvironment = provider.name,
        checksum = sha256
      )

      val outJson = JSONObject().apply {
        put("success", true)
        put("remotePath", remotePath)
        put("localPath", localPath)
        put("sizeBytes", destFile.length())
        put("sha256", sha256)
        put("artifactId", artifact.id)
        put("mimeType", artifact.mimeType)
        put("originatingEnvironment", provider.name)
      }.toString(2)

      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = outJson,
        error = null,
        duration = System.currentTimeMillis() - startTime,
        stdout = outJson,
        exitCode = 0,
        artifacts = artifactManager.getArtifacts()
      )
    }
}

/**
 * Writes or appends content to a file directly inside the E2B Cloud MicroVM (/home/user).
 */
class E2BWriteFileTool(
  var e2bProvider: E2BSandboxProvider,
  private val artifactManager: ArtifactManager,
  var sessionIdProvider: () -> String = { "default" }
) : Tool {
  override val name: String = "e2b_write_file"
  override val description: String =
    "Write, overwrite, or append content to a file directly inside the E2B Cloud MicroVM filesystem (/home/user). Does NOT write to the local Android device."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path inside the E2B microVM (e.g. 'script.py', 'data.json', '/home/user/app.py').",
        required = true
      ),
      ToolParameter(
        name = "content",
        type = "string",
        description = "Text content to write.",
        required = true
      ),
      ToolParameter(
        name = "append",
        type = "boolean",
        description = "Whether to append to existing file (default: false).",
        required = false,
        defaultValue = false
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.WORKSPACE_WRITE)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD
  override fun isAvailable(): Boolean = e2bProvider.apiKey.isNotBlank()

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val pathStr = arguments["path"]?.toString()?.trim() ?: ""
      val content = arguments["content"]?.toString() ?: ""
      val append = arguments["append"] as? Boolean ?: false
      val sessionId = sessionIdProvider()

      if (pathStr.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'path' cannot be blank.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      val mode = if (append) "ab" else "wb"
      val b64 = java.util.Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
      val cleanPath = pathStr.removePrefix("./")
      val cmd = "python3 -c \"import os, base64; p = '$cleanPath'; os.makedirs(os.path.dirname(p) or '.', exist_ok=True); open(p, '$mode').write(base64.b64decode('$b64'))\""

      val req = com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest.builder()
        .command(cmd)
        .timeout(java.time.Duration.ofSeconds(15))
        .build()

      val exec = e2bProvider.executeCommand(sessionId, req)
      val duration = System.currentTimeMillis() - startTime
      val isSuccess = (exec.exitCode ?: 0) == 0 && exec.error == null

      if (!isSuccess) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = exec.stderrText().ifBlank { exec.error?.value ?: "Failed to write file in E2B microVM" },
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = duration,
          exitCode = exec.exitCode ?: 1
        )
      }

      val out = "Successfully wrote ${content.length} characters to '$pathStr' in E2B microVM (/home/user)."
      try {
        val cleanRel = cleanPath.removePrefix("/home/user/").removePrefix("/")
        val localFile = PathSecurity.resolveSafe(artifactManager.workspaceDir, cleanRel)
        localFile.parentFile?.mkdirs()
        if (append && localFile.exists()) {
          localFile.appendText(content, java.nio.charset.StandardCharsets.UTF_8)
        } else {
          localFile.writeText(content, java.nio.charset.StandardCharsets.UTF_8)
        }
        artifactManager.registerArtifact(cleanRel, createdByCallId = callId)
      } catch (_: Exception) {}

      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = out,
        error = null,
        duration = duration,
        stdout = out,
        exitCode = 0,
        artifacts = artifactManager.getArtifacts()
      )
    }
}

/**
 * Reads text content from a file located inside the E2B Cloud MicroVM (/home/user).
 */
class E2BReadFileTool(
  var e2bProvider: E2BSandboxProvider,
  private val artifactManager: ArtifactManager,
  var sessionIdProvider: () -> String = { "default" }
) : Tool {
  override val name: String = "e2b_read_file"
  override val description: String =
    "Read the text content of a file located inside the E2B Cloud MicroVM filesystem (/home/user). Supports line offset and limit."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path to the file inside the E2B microVM.",
        required = true
      ),
      ToolParameter(
        name = "offset",
        type = "number",
        description = "Starting line number (1-based, default: 1).",
        required = false,
        defaultValue = 1
      ),
      ToolParameter(
        name = "limit",
        type = "number",
        description = "Maximum number of lines to read (default: 500).",
        required = false,
        defaultValue = 500
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.READ_ONLY)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD
  override fun isAvailable(): Boolean = e2bProvider.apiKey.isNotBlank()

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val pathStr = arguments["path"]?.toString()?.trim() ?: ""
      val offset = (arguments["offset"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
      val limit = (arguments["limit"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 500
      val sessionId = sessionIdProvider()

      if (pathStr.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'path' cannot be blank.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      val cleanPath = pathStr.removePrefix("./")
      val cmd = "python3 -c \"import os, sys; p = '$cleanPath'; sys.exit(2) if not os.path.exists(p) else None; lines = open(p, 'r', errors='replace').readlines(); start = max(0, $offset - 1); [sys.stdout.write(f'{i}: {line}') for i, line in enumerate(lines[start:start+$limit], start=start+1)]\""

      val req = com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest.builder()
        .command(cmd)
        .timeout(java.time.Duration.ofSeconds(15))
        .build()

      val exec = e2bProvider.executeCommand(sessionId, req)
      val duration = System.currentTimeMillis() - startTime
      val exitCode = exec.exitCode ?: 0

      if (exitCode == 2 || exec.stderrText().contains("File not found")) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "File '$pathStr' does not exist in E2B microVM (/home/user).",
          errorType = ToolErrorCode.FILE_NOT_FOUND,
          duration = duration,
          exitCode = 2
        )
      }

      val isSuccess = exitCode == 0 && exec.error == null
      if (isSuccess && exec.stdoutText().isNotBlank()) {
        try {
          val cleanRel = cleanPath.removePrefix("/home/user/").removePrefix("/")
          val localFile = PathSecurity.resolveSafe(artifactManager.workspaceDir, cleanRel)
          if (!localFile.exists()) {
            localFile.parentFile?.mkdirs()
            localFile.writeText(exec.stdoutText(), java.nio.charset.StandardCharsets.UTF_8)
            artifactManager.syncWorkspace()
          }
        } catch (_: Exception) {}
      }

      ToolResult(
        callId = callId,
        toolName = name,
        status = if (isSuccess) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
        arguments = arguments,
        output = if (isSuccess) exec.stdoutText().ifBlank { "(empty file)" } else null,
        error = if (isSuccess) null else exec.stderrText().ifBlank { "Failed to read file in E2B" },
        errorType = if (isSuccess) null else ToolErrorCode.PROCESS_FAILED,
        duration = duration,
        stdout = exec.stdoutText(),
        stderr = exec.stderrText(),
        exitCode = exitCode,
        artifacts = artifactManager.getArtifacts()
      )
    }
}

/**
 * Lists files and directories inside the E2B Cloud MicroVM (/home/user).
 */
class E2BListDirectoryTool(
  var e2bProvider: E2BSandboxProvider,
  private val artifactManager: ArtifactManager,
  var sessionIdProvider: () -> String = { "default" }
) : Tool {
  override val name: String = "e2b_list_directory"
  override val description: String =
    "List files and directories inside the E2B Cloud MicroVM filesystem (/home/user). Returns paths, file sizes, and directory status directly from the cloud environment."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Directory path relative to /home/user (default: '.').",
        required = false,
        defaultValue = "."
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.READ_ONLY)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD
  override fun isAvailable(): Boolean = e2bProvider.apiKey.isNotBlank()

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val pathStr = arguments["path"]?.toString()?.trim()?.ifBlank { "." } ?: "."
      val sessionId = sessionIdProvider()

      val cmd = "python3 -c \"import os, json; target = '$pathStr'; (print(json.dumps({'error': 'Directory not found', 'exists': False})) if not os.path.exists(target) else print(json.dumps({'exists': True, 'path': target, 'entries': [{'name': f, 'isDirectory': os.path.isdir(os.path.join(target, f)), 'sizeBytes': os.path.getsize(os.path.join(target, f)) if os.path.isfile(os.path.join(target, f)) else 0} for f in os.listdir(target)]})))\""

      val req = com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest.builder()
        .command(cmd)
        .timeout(java.time.Duration.ofSeconds(15))
        .build()

      val exec = e2bProvider.executeCommand(sessionId, req)
      val duration = System.currentTimeMillis() - startTime
      val stdout = exec.stdoutText().trim()

      if (exec.exitCode != 0 || stdout.isEmpty()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = exec.stderrText().ifBlank { "Failed to list directory in E2B microVM" },
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = duration,
          exitCode = exec.exitCode ?: 1
        )
      }

      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = stdout,
        error = null,
        duration = duration,
        stdout = stdout,
        exitCode = 0,
        artifacts = artifactManager.getArtifacts()
      )
    }
}
