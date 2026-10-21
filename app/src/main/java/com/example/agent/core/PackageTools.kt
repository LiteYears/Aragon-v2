package com.example.agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Duration

import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest

/**
 * Authentic package installation tool.
 * Enables autonomous capability expansion by installing Python modules (pip), system packages
 * (apt-get), or direct-URL binaries INSIDE the active E2B Cloud MicroVM environment, where the
 * agent actually executes its commands. There is no local provisioning path: if the E2B Cloud
 * Sandbox is not configured, the tool fails with actionable setup guidance.
 * Never fabricates or fakes package existence: success is reported only after the install command
 * exits 0 with a verification marker.
 */
class InstallPackageTool(
  private val artifactManager: ArtifactManager,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" },
  var capabilityManager: EnvironmentCapabilityManager? = null
) : Tool {

  override val name: String = "install_package"
  override val description: String =
    "Install a Python module (pip), system package (apt-get), or direct-URL binary inside the active E2B Cloud MicroVM. Use for packages like 'pytesseract', 'tesseract-ocr', 'python-docx', 'requests', 'jq'. Multi-GB ML stacks (torch, tensorflow, easyocr, paddleocr) are refused; use the remediation guidance returned instead."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "name",
        type = "string",
        description = "Package/module name to install (e.g. 'pytesseract', 'tesseract-ocr', 'python-docx', 'requests').",
        required = true,
        aliases = listOf("package", "pkg", "module", "package_name")
      ),
      ToolParameter(
        name = "sourceUrl",
        type = "string",
        description = "Optional direct HTTP/HTTPS URL to a prebuilt executable binary (not an archive).",
        required = false,
        aliases = listOf("source_url", "url")
      )
    )
  )

  override val category: ToolCategory = ToolCategory.PACKAGE
  override val target: ToolTarget = ToolTarget.SANDBOX_PACKAGE
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.PACKAGE_INSTALL, ToolPermission.WORKSPACE_WRITE)
  override val defaultTimeoutMs: Long = 300_000L
  override val supportsCancellation: Boolean = true

  private val safeNameRegex = Regex("""^[A-Za-z0-9][A-Za-z0-9._+-]*$""")
  private val archiveUrlRegex = Regex("""(?i)\.(tar\.gz|tgz|tar\.xz|txz|tar\.bz2|zip|whl|apk|deb)$""")

  private data class InstallOutcome(
    val success: Boolean,
    val manager: String? = null,
    val version: String? = null,
    val path: String? = null,
    val message: String = "",
    val stdout: String = "",
    val stderr: String = ""
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val nameInput = arguments["name"]?.toString()?.trim() ?: ""
      val sourceUrl = arguments["sourceUrl"]?.toString()?.trim()

      if (nameInput.isBlank()) {
        return@withContext failure(callId, arguments, startTime, "Parameter 'name' cannot be blank.", ToolErrorCode.INVALID_ARGUMENTS)
      }
      if (!safeNameRegex.matches(nameInput)) {
        return@withContext failure(
          callId, arguments, startTime,
          "Invalid package name '$nameInput'. Use a plain package name matching [A-Za-z0-9._+-] (no shell characters, spaces, or paths).",
          ToolErrorCode.INVALID_ARGUMENTS
        )
      }

      try {
        val provider = sandboxProvider
          ?: return@withContext failure(
            callId, arguments, startTime,
            "E2B Cloud Sandbox is not configured or offline. Package installation requires an active E2B Cloud MicroVM. Configure your E2B API Key in Settings.",
            ToolErrorCode.SANDBOX_INFRA_ERROR
          )

        val currentSession = sessionIdProvider()
        if (sourceUrl.isNullOrBlank() && capabilityManager != null) {
          val capResult = capabilityManager!!.installPackageGeneric(currentSession, nameInput)
          val duration = System.currentTimeMillis() - startTime
          val resultJson = capResult.toJsonObject().toString(2)
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = if (capResult.success) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
            arguments = arguments,
            output = if (capResult.success) resultJson else null,
            error = if (capResult.success) null else capResult.message,
            errorType = if (capResult.success) null else ToolErrorCode.SANDBOX_ERROR,
            duration = duration,
            stdout = capResult.stdout.ifBlank { resultJson },
            stderr = capResult.stderr,
            exitCode = if (capResult.success) 0 else 1,
            artifacts = artifactManager.getArtifacts()
          )
        }

        val outcome: InstallOutcome = if (!sourceUrl.isNullOrBlank()) {
          installFromUrlRemote(provider, currentSession, nameInput, sourceUrl)
        } else {
          installFromRegistryRemote(provider, currentSession, nameInput)
        }

        val duration = System.currentTimeMillis() - startTime
        if (!outcome.success) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = outcome.message.ifBlank { "Failed to install package '$nameInput'." },
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = duration,
            stdout = outcome.stdout,
            stderr = outcome.stderr,
            exitCode = 1
          )
        }

        val resultJson = JSONObject().apply {
          put("success", true)
          put("name", nameInput)
          if (!outcome.manager.isNullOrBlank()) put("manager", outcome.manager)
          if (!outcome.version.isNullOrBlank()) put("version", outcome.version)
          if (!outcome.path.isNullOrBlank()) put("path", outcome.path)
          if (outcome.message.isNotBlank()) put("message", outcome.message)
          put("environment", "e2b_cloud")
          put("sessionId", sessionIdProvider())
        }.toString(2)

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = resultJson,
          error = null,
          duration = duration,
          stdout = resultJson,
          stderr = outcome.stderr,
          exitCode = 0,
          artifacts = artifactManager.getArtifacts()
        )
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        failure(callId, arguments, startTime, "Package installation failed: ${e.message}", ToolErrorCode.SANDBOX_ERROR)
      }
    }

  /**
   * Installs inside the E2B MicroVM: pip first, apt-get fallback. Reports success only after
   * the remote command exits with the __ARAGON_PKG_OK__ verification marker.
   */
  private suspend fun installFromRegistryRemote(
    provider: SandboxProvider,
    sessionId: String,
    packageName: String
  ): InstallOutcome {
    val normalized = packageName.lowercase().replace('_', '-')
    val guidance = SandboxPackagePolicy.HEAVY_PACKAGES[normalized]
    if (guidance != null) {
      return InstallOutcome(
        success = false,
        message = "Refusing to install '$packageName' into the E2B MicroVM: $guidance"
      )
    }

    val pyModule = capabilityManager?.toPythonImportName(packageName) ?: packageName.replace('-', '_')
    val cmd =
      "(sudo -n true 2>/dev/null && SUDO=\"sudo\") || SUDO=\"\"; " +
        "if (python3 -c \"import $pyModule\" 2>/dev/null && ([ ! -x /home/user/venv/bin/python3 ] || /home/user/venv/bin/python3 -c \"import $pyModule\" 2>/dev/null)); then " +
        "V=\$(([ -x /home/user/venv/bin/python3 ] && /home/user/venv/bin/python3 -c \"import importlib.metadata as m; print(m.version('$packageName'))\" 2>/dev/null) || python3 -c \"import importlib.metadata as m; print(m.version('$packageName'))\" 2>/dev/null || true); " +
        "echo \"__ARAGON_PKG_OK__ pip \$V\"; " +
        "elif (python3 -m pip install --no-cache-dir --disable-pip-version-check --quiet '$packageName' 2>/dev/null || python3 -m pip install --break-system-packages --no-cache-dir --disable-pip-version-check --quiet '$packageName' 2>/dev/null) || ([ -x /home/user/venv/bin/pip ] && /home/user/venv/bin/pip install --no-cache-dir --disable-pip-version-check --quiet '$packageName'); then " +
        "([ -x /home/user/venv/bin/pip ] && /home/user/venv/bin/pip install --no-cache-dir --disable-pip-version-check --quiet '$packageName' 2>/dev/null || true); " +
        "V=\$(([ -x /home/user/venv/bin/python3 ] && /home/user/venv/bin/python3 -c \"import importlib.metadata as m; print(m.version('$packageName'))\" 2>/dev/null) || python3 -c \"import importlib.metadata as m; print(m.version('$packageName'))\" 2>/dev/null || true); " +
        "echo \"__ARAGON_PKG_OK__ pip \$V\"; " +
        "elif \$SUDO apt-get install -y -qq --no-install-recommends '$packageName' 2>/dev/null || (\$SUDO apt-get update -qq && \$SUDO apt-get install -y -qq --no-install-recommends '$packageName'); then " +
        "V=\$(dpkg -s '$packageName' 2>/dev/null | awk '/^Version:/{print \$2}'); " +
        "echo \"__ARAGON_PKG_OK__ apt \$V\"; " +
        "else echo \"__ARAGON_PKG_FAIL__ pip and apt installation both failed for '$packageName'\"; fi"

    val exec = provider.executeCommand(
      sessionId,
      RunCommandRequest.builder()
        .command(cmd)
        .timeout(Duration.ofSeconds(240))
        .envs(TerminalTool.REMOTE_COMMAND_ENVS)
        .build()
    )

    val stdout = exec.stdoutText()
    val stderr = exec.stderrText()

    val okLine = stdout.lines().lastOrNull { it.contains("__ARAGON_PKG_OK__") }
    if (okLine != null) {
      val payload = okLine.substringAfter("__ARAGON_PKG_OK__").trim()
      val manager = payload.substringBefore(' ').trim().ifBlank { "pip" }
      val version = payload.substringAfter(' ', "").trim().ifBlank { null }
      val message = when (manager) {
        "pip" -> "Installed Python package '$packageName'${version?.let { " (v$it)" } ?: ""} inside the E2B MicroVM via pip."
        "apt" -> "Installed system package '$packageName'${version?.let { " ($it)" } ?: ""} inside the E2B MicroVM via apt-get."
        else -> "Installed '$packageName' inside the E2B MicroVM."
      }
      return InstallOutcome(success = true, manager = manager, version = version, message = message, stdout = stdout, stderr = stderr)
    }

    val detail = buildString {
      if (exec.error != null && !exec.error?.value.isNullOrBlank()) {
        append("Sandbox error: ${exec.error?.value}\n")
      }
      append(stderr.lines().filter { it.isNotBlank() }.takeLast(12).joinToString("\n").take(1500))
    }.trim()

    return InstallOutcome(
      success = false,
      message = "Could not install '$packageName' inside the E2B MicroVM (pip and apt both failed)." +
        if (detail.isNotBlank()) "\n$detail" else "",
      stdout = stdout,
      stderr = stderr
    )
  }

  /**
   * Downloads a direct-URL executable inside the E2B MicroVM and installs it to /usr/local/bin
   * (or /home/user/bin when sudo is unavailable).
   */
  private suspend fun installFromUrlRemote(
    provider: SandboxProvider,
    sessionId: String,
    packageName: String,
    sourceUrl: String
  ): InstallOutcome {
    if (!sourceUrl.startsWith("http://") && !sourceUrl.startsWith("https://")) {
      return InstallOutcome(success = false, message = "sourceUrl must be a direct http:// or https:// URL.")
    }
    if (sourceUrl.contains('\'') || sourceUrl.contains('\n') || sourceUrl.contains('\r') || sourceUrl.contains(' ')) {
      return InstallOutcome(success = false, message = "sourceUrl contains unsupported characters (quotes, whitespace, or newlines).")
    }
    if (archiveUrlRegex.containsMatchIn(sourceUrl)) {
      return InstallOutcome(
        success = false,
        message = "sourceUrl points to an archive, which remote install does not unpack. " +
          "Download and extract it manually in the microVM, e.g.: " +
          "curl -fsSL --max-time 60 -o /tmp/$packageName.dl '$sourceUrl' && mkdir -p /home/user/bin && " +
          "(tar -xzf /tmp/$packageName.dl -C /home/user/bin || unzip -o /tmp/$packageName.dl -d /home/user/bin) && chmod +x /home/user/bin/$packageName"
      )
    }

    val cmd =
      "(sudo -n true 2>/dev/null && SUDO=\"sudo\") || SUDO=\"\"; " +
        "if curl -fsSL --max-time 180 -o /tmp/$packageName.aragon '$sourceUrl'; then " +
        "chmod +x /tmp/$packageName.aragon; " +
        "if \$SUDO install -m 0755 /tmp/$packageName.aragon /usr/local/bin/$packageName 2>/dev/null; then " +
        "echo \"__ARAGON_PKG_OK__ url /usr/local/bin/$packageName\"; " +
        "else mkdir -p /home/user/bin && cp /tmp/$packageName.aragon /home/user/bin/$packageName && " +
        "echo \"__ARAGON_PKG_OK__ url /home/user/bin/$packageName\"; fi; " +
        "else echo \"__ARAGON_PKG_FAIL__ download failed for '$packageName'\"; fi"

    val exec = provider.executeCommand(
      sessionId,
      RunCommandRequest.builder()
        .command(cmd)
        .timeout(Duration.ofSeconds(200))
        .envs(TerminalTool.REMOTE_COMMAND_ENVS)
        .build()
    )

    val stdout = exec.stdoutText()
    val stderr = exec.stderrText()
    val okLine = stdout.lines().lastOrNull { it.contains("__ARAGON_PKG_OK__") }
    if (okLine != null) {
      val path = okLine.substringAfter("__ARAGON_PKG_OK__").trim().substringAfter("url", "").trim().ifBlank { "/usr/local/bin/$packageName" }
      return InstallOutcome(
        success = true,
        manager = "url",
        path = path,
        message = "Installed executable '$packageName' from sourceUrl at $path inside the E2B MicroVM.",
        stdout = stdout,
        stderr = stderr
      )
    }

    return InstallOutcome(
      success = false,
      message = "Could not download/install '$packageName' from '$sourceUrl' inside the E2B MicroVM." +
        if (stderr.isNotBlank()) "\n${stderr.lines().filter { it.isNotBlank() }.takeLast(8).joinToString("\n").take(1000)}" else "",
      stdout = stdout,
      stderr = stderr
    )
  }

  private fun failure(
    callId: String,
    arguments: Map<String, Any?>,
    startTime: Long,
    message: String,
    errorCode: ToolErrorCode
  ): ToolResult = ToolResult(
    callId = callId,
    toolName = name,
    status = ToolStatus.FAILED,
    arguments = arguments,
    output = null,
    error = message,
    errorType = errorCode,
    duration = System.currentTimeMillis() - startTime,
    stdout = "",
    stderr = message,
    exitCode = 1
  )
}
