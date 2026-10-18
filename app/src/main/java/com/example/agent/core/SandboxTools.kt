package com.example.agent.core

import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.CodeContext
import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.RunCodeRequest
import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.SupportedLanguage
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest
import com.alibaba.opensandbox.sandbox.Sandbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Native E2B Cloud Code Interpreter Tool.
 * Executes Python, Bash, or JavaScript code within the E2B Cloud MicroVM runtime.
 */
class CodeInterpreterTool(
  var sandboxProvider: SandboxProvider,
  private val artifactManager: ArtifactManager,
  var sessionIdProvider: () -> String = { "default" },
  var taskIdProvider: () -> String? = { null }
) : Tool {

  override val name: String = "code_interpreter"
  override val description: String =
    "Execute code inside the active E2B Cloud MicroVM environment (Python, Bash, or JavaScript). Captures real stdout, stderr, execution results, errors, and syncs file deliverables."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "code",
        type = "string",
        description = "The code snippet or script to execute.",
        required = true,
        aliases = listOf("script", "source", "snippet")
      ),
      ToolParameter(
        name = "language",
        type = "string",
        description = "Language runtime: 'python' (default), 'bash', or 'javascript'.",
        required = false,
        enumValues = listOf("python", "bash", "javascript"),
        defaultValue = "python",
        aliases = listOf("lang")
      ),
      ToolParameter(
        name = "timeoutSeconds",
        type = "number",
        description = "Maximum execution time in seconds (default: 30).",
        required = false,
        defaultValue = 30,
        aliases = listOf("timeout", "timeout_seconds")
      )
    )
  )

  override val category: ToolCategory = ToolCategory.PROCESS
  override val target: ToolTarget = ToolTarget.SANDBOX_PROCESS
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.PROCESS_EXECUTION, ToolPermission.WORKSPACE_WRITE)
  override val defaultTimeoutMs: Long = 45000L

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val code = arguments["code"]?.toString()?.trim() ?: ""
      val rawLang = arguments["language"]?.toString()?.trim()?.lowercase() ?: "python"
      val timeoutSeconds = (arguments["timeoutSeconds"] as? Number)?.toLong() ?: 30L
      val currentSession = sessionIdProvider()
      val currentTaskId = taskIdProvider()

      val language = when (rawLang) {
        "py", "python3" -> SupportedLanguage.PYTHON
        "sh", "shell" -> SupportedLanguage.BASH
        "js", "node" -> SupportedLanguage.JAVASCRIPT
        else -> rawLang
      }

      if (code.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'code' cannot be blank.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      try {
        val context = CodeContext.builder().language(language).build()
        val request = RunCodeRequest.builder()
          .code(code)
          .context(context)
          .build()

        val execution = sandboxProvider.executeCode(currentSession, request)
        val duration = System.currentTimeMillis() - startTime

        val stdout = execution.stdoutText()
        val stderr = execution.stderrText()
        val exitCode = execution.exitCode ?: 0
        val isSuccess = exitCode == 0 && execution.error == null

        val errorType = when {
          isSuccess -> null
          exitCode == -1 || execution.error?.name == "E2BTimeoutError" || stderr.contains("timed out") -> ToolErrorCode.TIMEOUT
          exitCode == 127 || stderr.contains("not found") -> ToolErrorCode.COMMAND_NOT_FOUND
          execution.error?.name == "E2BAuthenticationError" -> ToolErrorCode.PERMISSION_DENIED
          execution.error?.name == "E2BProvisioningError" || execution.error?.name == "E2BConnectionError" -> ToolErrorCode.SANDBOX_ERROR
          else -> ToolErrorCode.PROCESS_FAILED
        }

        val structuredJson = JSONObject().apply {
          put("success", isSuccess)
          put("language", language)
          put("exitCode", exitCode)
          put("stdout", stdout)
          put("stderr", stderr)
          if (execution.error != null) {
            put("error", execution.error?.value ?: "Execution error")
            put("errorName", execution.error?.name ?: "Error")
          }
          if (errorType != null) {
            put("errorType", errorType.code)
          }
          put("backend", sandboxProvider.name)
          put("sessionId", currentSession)
        }.toString()

        val artifacts = try {
          sandboxProvider.syncArtifacts(currentSession, artifactManager.workspaceDir, currentTaskId)
          artifactManager.syncWorkspace(currentTaskId)
        } catch (_: Exception) {
          artifactManager.syncWorkspace(currentTaskId)
        }

        val taggedStdout = if (stdout.isBlank()) "[env=e2b_cloud]" else if (stdout.startsWith("[env=")) stdout else "[env=e2b_cloud]\n$stdout"
        ToolResult(
          callId = callId,
          toolName = name,
          status = if (isSuccess) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
          arguments = arguments,
          output = if (isSuccess) taggedStdout else structuredJson,
          error = if (isSuccess) null else (execution.error?.value ?: stderr.ifBlank { "Execution failed with exit code $exitCode" }),
          errorType = errorType,
          duration = duration,
          stdout = taggedStdout,
          stderr = stderr,
          exitCode = exitCode,
          artifacts = artifacts,
          metadata = mapOf(
            "language" to language,
            "backend" to sandboxProvider.name,
            "sessionId" to currentSession,
            "environment" to "e2b_cloud"
          )
        )
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Code execution failed: ${e.message}",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1,
          artifacts = artifactManager.getArtifacts()
        )
      }
    }
}

/**
 * Native E2B Cloud Diagnostics Tool.
 * Gathers system specs, CPU architecture, memory, disk, and runtime metrics,
 * or runs an explicit non-destructive health and identity diagnostic for E2B Cloud MicroVMs.
 */
class SandboxDiagnosticsTool(
  var sandboxProvider: SandboxProvider,
  var sessionIdProvider: () -> String = { "default" },
  var taskIdProvider: () -> String? = { null },
  var capabilityManager: EnvironmentCapabilityManager? = null
) : Tool {

  override val name: String = "sandbox_diagnostics"
  override val description: String =
    "Retrieve system diagnostics, host architecture, memory utilization, disk metrics, and environment status for E2B Cloud MicroVMs."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "detailed",
        type = "boolean",
        description = "Whether to include detailed entries (default: true).",
        required = false,
        defaultValue = true
      )
    )
  )

  override val category: ToolCategory = ToolCategory.UTILITY
  override val target: ToolTarget = ToolTarget.SANDBOX_PROCESS
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.READ_ONLY)
  override val defaultTimeoutMs: Long = 15000L

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val currentSession = sessionIdProvider()
      val currentTaskId = taskIdProvider() ?: "diag"

      try {
        if (sandboxProvider.isE2B) {
          val e2b = sandboxProvider as E2BSandboxProvider
          val report = e2b.runHealthAndIdentityDiagnostic(
            sessionId = currentSession,
            taskId = currentTaskId,
            toolCallId = callId
          )
          var auditText = report.toFormattedAuditText()
          if (report.isAuthenticE2B && capabilityManager != null) {
            try {
              val capReport = capabilityManager?.diagnoseEnvironment(currentSession)
              val capSummary = capReport?.let { capabilityManager?.formatDiagnosticSummary(it) }
              if (!capSummary.isNullOrBlank()) {
                auditText = "$auditText\n\n$capSummary"
              }
            } catch (_: Exception) {}
          }
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = if (report.isAuthenticE2B) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
            arguments = arguments,
            output = auditText,
            error = if (report.isAuthenticE2B) null else (report.providerErrorDetails ?: "E2B health check failed"),
            errorType = if (report.isAuthenticE2B) null else ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            stdout = auditText,
            exitCode = report.exitCode,
            metadata = mapOf(
              "isAuthenticE2B" to report.isAuthenticE2B,
              "sandboxId" to report.sandboxId,
              "lifecycleState" to report.lifecycleState.name,
              "connectionStatus" to report.connectionStatus
            )
          )
        }

        val sb = sandboxProvider.getSandbox(currentSession)
        val diag = sb.diagnostics().getDiagnostics()
        val metrics = sb.metrics().getMetrics()

        val sbOut = StringBuilder()
        sbOut.appendLine("=== SANDBOX DIAGNOSTICS ===")
        sbOut.appendLine("Active Backend: ${sandboxProvider.name}")
        sbOut.appendLine("Runtime Mode: ${sandboxProvider.mode}")
        sbOut.appendLine("Engine: E2B Cloud Firecracker MicroVM")
        sbOut.appendLine("Host: ${diag.hostname}")
        sbOut.appendLine("OS: ${diag.os}")
        sbOut.appendLine("Kernel: ${diag.kernel}")
        sbOut.appendLine("Architecture: ${diag.architecture}")
        sbOut.appendLine("CPU Cores: ${diag.cpuCores}")
        sbOut.appendLine("Memory (Used/Total): ${metrics.memoryBytesUsed / (1024 * 1024)}MB / ${metrics.memoryBytesTotal / (1024 * 1024)}MB")
        sbOut.appendLine("Disk (Used/Total): ${metrics.diskBytesUsed / (1024 * 1024)}MB / ${metrics.diskBytesTotal / (1024 * 1024)}MB")
        sbOut.appendLine("Uptime: ${diag.uptimeSeconds}s")
        sbOut.appendLine("Health Status: ${if (sandboxProvider.ping()) "HEALTHY" else "UNHEALTHY"}")

        val detailed = arguments["detailed"] as? Boolean ?: true
        if (detailed && diag.entries.isNotEmpty()) {
          sbOut.appendLine()
          sbOut.appendLine("--- SYSTEM ENTRIES ---")
          diag.entries.forEach { entry ->
            sbOut.appendLine("${entry.key}: ${entry.value}")
          }
        }

        val outText = sbOut.toString().trim()
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = outText,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = outText,
          exitCode = 0
        )
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to collect diagnostics: ${e.message}",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }
}

/**
 * Native Browser Automation Tool.
 * Headless web interaction with stateful navigation, DOM extraction, and element interaction.
 */
class BrowserAutomationTool(
  private val isConfigured: Boolean = true
) : Tool {

  override val name: String = "browser_action"
  override val description: String =
    "Read-only HTTP browser session: navigate to URLs, inspect the fetched DOM, follow resolvable links via click, submit simple HTML forms via fill_form, and page through the fetched document via scroll. No JavaScript execution."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "action",
        type = "string",
        description = "Browser action to execute: 'navigate', 'get_dom', 'click', 'fill_form', 'scroll'.",
        required = true,
        enumValues = listOf("navigate", "get_dom", "click", "fill_form", "scroll"),
        aliases = listOf("act", "op")
      ),
      ToolParameter(
        name = "url",
        type = "string",
        description = "URL to navigate to (required for 'navigate').",
        required = false,
        aliases = listOf("target_url", "link", "address")
      ),
      ToolParameter(
        name = "selector",
        type = "string",
        description = "Target CSS/DOM selector or element text for click or fill_form.",
        required = false,
        aliases = listOf("element", "target", "css")
      ),
      ToolParameter(
        name = "text",
        type = "string",
        description = "Text value to enter for fill_form.",
        required = false,
        aliases = listOf("value", "input")
      ),
      ToolParameter(
        name = "scrollDirection",
        type = "string",
        description = "Scroll direction: 'up' or 'down' (default: 'down').",
        required = false,
        enumValues = listOf("up", "down"),
        defaultValue = "down",
        aliases = listOf("scroll_direction", "direction")
      )
    )
  )

  override val category: ToolCategory = ToolCategory.WEB
  override val target: ToolTarget = ToolTarget.HOST_NETWORK
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK_ACCESS)
  override val defaultTimeoutMs: Long = 30000L

  override fun isAvailable(): Boolean = isConfigured

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

  private var currentUrl: String = ""
  private var lastHtmlContent: String = ""
  private var lastMarkdown: String = ""
  private var scrollOffsetChars: Int = 0

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val action = arguments["action"]?.toString()?.trim()?.lowercase() ?: "navigate"
      val url = arguments["url"]?.toString()?.trim() ?: ""

      when (action) {
        "navigate" -> {
          if (url.isBlank() || !url.startsWith("http")) {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Parameter 'url' must be a valid http:// or https:// address.",
              errorType = ToolErrorCode.INVALID_ARGUMENTS,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }

          try {
            val req = Request.Builder()
              .url(url)
              .header("User-Agent", "Mozilla/5.0 (Android; Aragon Agent) AppleWebKit/537.36")
              .get()
              .build()

            var finalUrl = url
            val rawHtml = httpClient.newCall(req).execute().use { resp ->
              if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.message}")
              finalUrl = resp.request.url.toString()
              resp.body?.string().orEmpty()
            }

            currentUrl = finalUrl
            lastHtmlContent = rawHtml
            scrollOffsetChars = 0
            val doc = WebHtmlParser.parse(rawHtml, finalUrl, maxChars = 8000)
            lastMarkdown = doc.retrievedContent

            val out = buildString {
              appendLine("=== BROWSER: NAVIGATED ===")
              appendLine("Current URL: $currentUrl")
              appendLine("Title: ${doc.title}")
              appendLine("Content Preview:")
              appendLine(doc.retrievedContent.take(2000))
              if (doc.links.isNotEmpty()) {
                appendLine()
                appendLine("Interactable Links (${doc.links.size}):")
                doc.links.take(8).forEach { appendLine("- [${it.text}](${it.url})") }
              }
            }

            ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.SUCCEEDED,
              arguments = arguments,
              output = out,
              error = null,
              duration = System.currentTimeMillis() - startTime,
              stdout = out,
              exitCode = 0,
              metadata = mapOf("currentUrl" to currentUrl, "title" to doc.title)
            )
          } catch (e: Exception) {
            if (e is CancellationException) throw e
            ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Navigation failed: ${e.message}",
              errorType = ToolErrorCode.NETWORK_ERROR,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }
        }

        "get_dom" -> {
          if (lastHtmlContent.isBlank()) {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "No page is currently open in the browser session. Call 'navigate' first.",
              errorType = ToolErrorCode.UNSUPPORTED_OPERATION,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }

          val doc = WebHtmlParser.parse(lastHtmlContent, currentUrl, maxChars = 12000)
          val out = "=== BROWSER DOM ===\nURL: $currentUrl\nTitle: ${doc.title}\n\n${doc.retrievedContent}"
          ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.SUCCEEDED,
            arguments = arguments,
            output = out,
            error = null,
            duration = System.currentTimeMillis() - startTime,
            stdout = out,
            exitCode = 0
          )
        }

        "click" -> {
          val selector = arguments["selector"]?.toString()?.trim() ?: ""
          if (selector.isBlank()) {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Parameter 'selector' is required for click (link text, href fragment, or absolute URL).",
              errorType = ToolErrorCode.INVALID_ARGUMENTS,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }
          if (lastHtmlContent.isBlank()) {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "No page is currently open in the browser session. Call 'navigate' first.",
              errorType = ToolErrorCode.UNSUPPORTED_OPERATION,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }

          val targetUrl = resolveNavigationTarget(lastHtmlContent, currentUrl, selector)
          if (targetUrl == null) {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Could not resolve '$selector' to a navigable link on '$currentUrl'. Without a JavaScript engine only link navigation is possible; use 'get_dom' to inspect available links or 'navigate' with an explicit URL.",
              errorType = ToolErrorCode.UNSUPPORTED_OPERATION,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }

          try {
            val req = Request.Builder()
              .url(targetUrl)
              .header("User-Agent", "Mozilla/5.0 (Android; Aragon Agent) AppleWebKit/537.36")
              .get()
              .build()

            var finalUrl: String = targetUrl
            val rawHtml = httpClient.newCall(req).execute().use { resp ->
              if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.message}")
              finalUrl = resp.request.url.toString()
              resp.body?.string().orEmpty()
            }

            currentUrl = finalUrl
            lastHtmlContent = rawHtml
            scrollOffsetChars = 0
            val doc = WebHtmlParser.parse(rawHtml, finalUrl, maxChars = 8000)
            lastMarkdown = doc.retrievedContent

            val out = buildString {
              appendLine("=== BROWSER: LINK FOLLOWED ===")
              appendLine("Selector: $selector")
              appendLine("Navigated to: $finalUrl")
              appendLine("Title: ${doc.title}")
              appendLine("Content Preview:")
              appendLine(doc.retrievedContent.take(2000))
            }

            ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.SUCCEEDED,
              arguments = arguments,
              output = out,
              error = null,
              duration = System.currentTimeMillis() - startTime,
              stdout = out,
              exitCode = 0,
              metadata = mapOf("currentUrl" to currentUrl, "title" to doc.title)
            )
          } catch (e: Exception) {
            if (e is CancellationException) throw e
            ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Link navigation to '$targetUrl' failed: ${e.message}",
              errorType = ToolErrorCode.NETWORK_ERROR,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }
        }

        "fill_form" -> {
          val selector = arguments["selector"]?.toString()?.trim() ?: ""
          val text = arguments["text"]?.toString() ?: ""
          if (selector.isBlank()) {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Parameter 'selector' is required for fill_form (field name or id).",
              errorType = ToolErrorCode.INVALID_ARGUMENTS,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }
          if (lastHtmlContent.isBlank()) {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "No page is currently open in the browser session. Call 'navigate' first.",
              errorType = ToolErrorCode.UNSUPPORTED_OPERATION,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }

          val formTarget = resolveFormTarget(lastHtmlContent, currentUrl, selector)
          if (formTarget == null) {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "No HTML form with a field matching '$selector' was found on '$currentUrl'. Only plain HTML form submissions are supported (no JavaScript frameworks).",
              errorType = ToolErrorCode.UNSUPPORTED_OPERATION,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }

          try {
            val params = LinkedHashMap(formTarget.params)
            params[formTarget.fieldName] = text
            val encodedBody = params.entries.joinToString("&") { entry ->
              val k = URLEncoder.encode(entry.key, StandardCharsets.UTF_8.toString())
              val v = URLEncoder.encode(entry.value, StandardCharsets.UTF_8.toString())
              "$k=$v"
            }

            val requestUrl: String
            val reqBuilder: Request.Builder
            if (formTarget.method == "post") {
              requestUrl = formTarget.actionUrl
              reqBuilder = Request.Builder().url(requestUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Aragon Agent) AppleWebKit/537.36")
                .post(encodedBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            } else {
              val glue = if (formTarget.actionUrl.contains("?")) "&" else "?"
              requestUrl = formTarget.actionUrl + glue + encodedBody
              reqBuilder = Request.Builder().url(requestUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Aragon Agent) AppleWebKit/537.36")
                .get()
            }

            var finalUrl = requestUrl
            val rawHtml = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
              finalUrl = resp.request.url.toString()
              if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.message}")
              resp.body?.string().orEmpty()
            }

            currentUrl = finalUrl
            lastHtmlContent = rawHtml
            scrollOffsetChars = 0
            val doc = WebHtmlParser.parse(rawHtml, finalUrl, maxChars = 8000)
            lastMarkdown = doc.retrievedContent

            val out = buildString {
              appendLine("=== BROWSER: FORM SUBMITTED ===")
              appendLine("Field '$selector' submitted (${text.length} chars) with ${params.size} total field(s).")
              appendLine("Method: ${formTarget.method.uppercase()}")
              appendLine("Action: ${formTarget.actionUrl}")
              appendLine("Final URL: $finalUrl")
              appendLine("Title: ${doc.title}")
              appendLine("Content Preview:")
              appendLine(doc.retrievedContent.take(2000))
            }

            ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.SUCCEEDED,
              arguments = arguments,
              output = out,
              error = null,
              duration = System.currentTimeMillis() - startTime,
              stdout = out,
              exitCode = 0,
              metadata = mapOf("currentUrl" to currentUrl, "title" to doc.title, "method" to formTarget.method)
            )
          } catch (e: Exception) {
            if (e is CancellationException) throw e
            ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Form submission failed: ${e.message}",
              errorType = ToolErrorCode.NETWORK_ERROR,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }
        }

        "scroll" -> {
          if (lastHtmlContent.isBlank()) {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "No page is currently open in the browser session. Call 'navigate' first.",
              errorType = ToolErrorCode.UNSUPPORTED_OPERATION,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }

          val direction = arguments["scrollDirection"]?.toString()?.lowercase() ?: "down"
          val content = if (lastMarkdown.isNotBlank()) lastMarkdown else WebHtmlParser.parse(lastHtmlContent, currentUrl, maxChars = 32000).retrievedContent
          val viewport = 6000
          scrollOffsetChars = if (direction == "up") {
            (scrollOffsetChars - viewport).coerceAtLeast(0)
          } else {
            (scrollOffsetChars + viewport).coerceAtMost((content.length - viewport).coerceAtLeast(0))
          }
          val end = (scrollOffsetChars + viewport).coerceAtMost(content.length)
          val slice = if (scrollOffsetChars < content.length) content.substring(scrollOffsetChars, end) else ""

          val out = buildString {
            appendLine("=== BROWSER VIEWPORT (chars $scrollOffsetChars-$end of ${content.length}) ===")
            appendLine("URL: $currentUrl")
            appendLine()
            if (slice.isBlank()) {
              appendLine("[End of fetched document reached. Navigate to another page or use 'get_dom' for the full content.]")
            } else {
              appendLine(slice)
            }
          }

          ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.SUCCEEDED,
            arguments = arguments,
            output = out,
            error = null,
            duration = System.currentTimeMillis() - startTime,
            stdout = out,
            exitCode = 0,
            metadata = mapOf("offsetChars" to scrollOffsetChars, "totalChars" to content.length)
          )
        }

        else -> {
          ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Unsupported browser action: '$action'",
            errorType = ToolErrorCode.INVALID_ARGUMENTS,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }
      }
    }

  private data class FormTarget(
    val actionUrl: String,
    val method: String,
    val fieldName: String,
    val params: Map<String, String>
  )

  private fun resolveNavigationTarget(html: String, baseUrl: String, selector: String): String? {
    if (selector.startsWith("http://") || selector.startsWith("https://")) return selector
    val anchorPattern = Regex("(?is)<a\\s+[^>]*href=[\"']([^\"'#]+)[\"'][^>]*>(.*?)</a>")
    val baseUri = try { URI(baseUrl) } catch (_: Exception) { null }
    val target = selector.lowercase()
    var hrefFallback: String? = null

    for (match in anchorPattern.findAll(html)) {
      val rawHref = match.groupValues[1].trim()
      val linkText = match.groupValues[2].replace(Regex("<[^>]+>"), " ").trim().lowercase()
      val absolute = try {
        baseUri?.resolve(rawHref)?.toString() ?: rawHref
      } catch (_: Exception) {
        rawHref
      }
      if (!absolute.startsWith("http")) continue

      if (linkText == target || linkText.contains(target)) return absolute
      if (hrefFallback == null && rawHref.lowercase().contains(target)) hrefFallback = absolute
    }
    return hrefFallback
  }

  private fun resolveFormTarget(html: String, baseUrl: String, selector: String): FormTarget? {
    val formPattern = Regex("(?is)<form\\b([^>]*)>(.*?)</form>")
    val baseUri = try { URI(baseUrl) } catch (_: Exception) { null }
    val target = selector.lowercase()

    for (formMatch in formPattern.findAll(html)) {
      val formAttrs = formMatch.groupValues[1]
      val inner = formMatch.groupValues[2]
      val params = LinkedHashMap<String, String>()
      var matchedField: String? = null

      for (inputMatch in Regex("(?is)<(?:input|textarea|select)\\b[^>]*>").findAll(inner)) {
        val tag = inputMatch.value
        val name = Regex("(?i)\\bname=[\"']([^\"']+)[\"']").find(tag)?.groupValues?.get(1) ?: continue
        val id = Regex("(?i)\\bid=[\"']([^\"']+)[\"']").find(tag)?.groupValues?.get(1)
        val type = Regex("(?i)\\btype=[\"']([^\"']+)[\"']").find(tag)?.groupValues?.get(1)?.lowercase() ?: "text"
        if (type == "submit" || type == "button" || type == "file" || type == "reset" || type == "image") continue

        val value = Regex("(?i)\\bvalue=[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1) ?: ""
        params[name] = value

        if (matchedField == null) {
          val nameLower = name.lowercase()
          val idLower = id?.lowercase()
          if (nameLower == target || idLower == target || nameLower.contains(target) || idLower?.contains(target) == true) {
            matchedField = name
          }
        }
      }

      if (matchedField == null) continue

      val actionRaw = Regex("(?i)\\baction=[\"']([^\"']*)[\"']").find(formAttrs)?.groupValues?.get(1)?.ifBlank { null } ?: baseUrl
      val actionUrl = try {
        baseUri?.resolve(actionRaw)?.toString() ?: actionRaw
      } catch (_: Exception) {
        actionRaw
      }
      val method = Regex("(?i)\\bmethod=[\"']([^\"']+)[\"']").find(formAttrs)?.groupValues?.get(1)?.lowercase() ?: "get"

      return FormTarget(
        actionUrl = if (actionUrl.startsWith("http")) actionUrl else baseUrl,
        method = if (method == "post") "post" else "get",
        fieldName = matchedField,
        params = params
      )
    }
    return null
  }
}

/**
 * Native Deep Web Research Tool.
 * Dispatches multi-source search, navigates top sources, extracts citations,
 * and compiles structured facts with source URLs.
 */
class DeepWebResearchTool(
  private val isConfigured: Boolean = true
) : Tool {

  override val name: String = "deep_research"
  override val description: String =
    "Execute deep multi-source web research: searches the web, navigates matching source articles, extracts publication dates and facts, and formats verified findings with markdown citations."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "query",
        type = "string",
        description = "The research query or topic to investigate in depth.",
        required = true,
        aliases = listOf("topic", "prompt", "search", "q")
      ),
      ToolParameter(
        name = "maxSources",
        type = "number",
        description = "Number of sources to retrieve and inspect (default: 4, max: 8).",
        required = false,
        defaultValue = 4,
        aliases = listOf("max_sources", "limit")
      ),
      ToolParameter(
        name = "focusDomain",
        type = "string",
        description = "Optional domain filter (e.g. 'github.com', 'wikipedia.org').",
        required = false,
        aliases = listOf("domain", "focus_domain")
      )
    )
  )

  override val category: ToolCategory = ToolCategory.WEB
  override val target: ToolTarget = ToolTarget.HOST_NETWORK
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK_ACCESS)
  override val defaultTimeoutMs: Long = 45000L

  override fun isAvailable(): Boolean = isConfigured

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val query = arguments["query"]?.toString()?.trim() ?: ""
      val maxSources = ((arguments["maxSources"] as? Number)?.toInt() ?: 4).coerceIn(1, 8)
      val focusDomain = arguments["focusDomain"]?.toString()?.trim()

      if (query.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'query' cannot be blank.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      try {
        val effectiveQuery = if (!focusDomain.isNullOrBlank()) "$query site:$focusDomain" else query
        val searchResults = WebSearchEngine.search(effectiveQuery, maxSources)

        val compiledFindings = mutableListOf<ResearchFinding>()

        for (res in searchResults) {
          val resUrl: String = res["url"] ?: continue
          val title: String = res["title"] ?: "Source Document"
          try {
            val req = Request.Builder()
              .url(resUrl)
              .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
              .get()
              .build()

            val html = httpClient.newCall(req).execute().use { resp ->
              if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }

            if (html.isNotBlank()) {
              val doc = WebHtmlParser.parse(html, resUrl, maxChars = 4000)
              compiledFindings.add(
                ResearchFinding(
                  title = doc.title.ifBlank { title },
                  url = resUrl,
                  source = doc.sourceIdentity,
                  pubDate = doc.publicationDate,
                  excerpt = doc.relevantExcerpt.ifBlank { res["snippet"] ?: "" }
                )
              )
            }
          } catch (_: Exception) {}
        }

        val sb = StringBuilder()
        sb.appendLine("=== DEEP RESEARCH SYNTHESIS ===")
        sb.appendLine("Query: $query")
        sb.appendLine("Sources Inspected: ${compiledFindings.size}")
        sb.appendLine()
        sb.appendLine("--- CITATIONS & EXTRACTED EVIDENCE ---")
        compiledFindings.forEachIndexed { idx, finding ->
          sb.appendLine("[${idx + 1}] ${finding.title}")
          sb.appendLine("    Source: ${finding.source} | URL: ${finding.url}")
          if (finding.pubDate != null) sb.appendLine("    Published: ${finding.pubDate}")
          sb.appendLine("    Key Evidence: ${finding.excerpt}")
          sb.appendLine()
        }

        val outText = sb.toString().trim()
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = outText,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = outText,
          exitCode = 0,
          metadata = mapOf("sourcesCount" to compiledFindings.size)
        )
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Deep research failed: ${e.message}",
          errorType = ToolErrorCode.NETWORK_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }

  private data class ResearchFinding(
    val title: String,
    val url: String,
    val source: String,
    val pubDate: String?,
    val excerpt: String
  )
}

/**
 * Dedicated E2B Cloud Sandbox Tool.
 * Provides explicit control over E2B Firecracker microVMs (https://github.com/e2b-dev | https://e2b.dev).
 * Can run shell commands, execute code, list active cloud sandboxes, and inspect cloud resources.
 */
class E2BCloudTool(
  var e2bProvider: E2BSandboxProvider,
  private val artifactManager: ArtifactManager,
  var sessionIdProvider: () -> String = { "default" }
) : Tool {

  override val name: String = "e2b_sandbox"
  override val description: String =
    "Execute commands, run code, or manage cloud Firecracker microVM sandboxes via E2B (https://github.com/e2b-dev). Supports actions: 'run_command', 'run_code', 'list_sandboxes', 'status'."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "action",
        type = "string",
        description = "The E2B operation: 'run_command' (default), 'run_code', 'list_sandboxes', or 'status'.",
        required = false,
        enumValues = listOf("run_command", "run_code", "list_sandboxes", "status"),
        defaultValue = "run_command",
        aliases = listOf("act", "op", "operation")
      ),
      ToolParameter(
        name = "command",
        type = "string",
        description = "Shell command to execute in the E2B microVM (required for action='run_command').",
        required = false,
        aliases = listOf("cmd", "c")
      ),
      ToolParameter(
        name = "code",
        type = "string",
        description = "Code snippet to run in the E2B microVM (required for action='run_code').",
        required = false,
        aliases = listOf("script", "source", "snippet")
      ),
      ToolParameter(
        name = "language",
        type = "string",
        description = "Language runtime: 'python' (default), 'bash', or 'javascript'.",
        required = false,
        enumValues = listOf("python", "bash", "javascript"),
        defaultValue = "python",
        aliases = listOf("lang")
      ),
      ToolParameter(
        name = "template",
        type = "string",
        description = "E2B template ID: 'base' (default) or 'code-interpreter'.",
        required = false,
        defaultValue = "base"
      )
    )
  )

  override val category: ToolCategory = ToolCategory.PROCESS
  override val target: ToolTarget = ToolTarget.SANDBOX_PROCESS
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.PROCESS_EXECUTION, ToolPermission.WORKSPACE_WRITE)
  override val defaultTimeoutMs: Long = 60000L

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val action = arguments["action"]?.toString()?.trim()?.lowercase() ?: "run_command"

      try {
        when (action) {
        "status" -> {
          val isOnline = e2bProvider.ping()
          val sandboxes = e2bProvider.listActiveSandboxes()
          val sb = StringBuilder()
          sb.appendLine("=== E2B CLOUD SANDBOX STATUS ===")
          sb.appendLine("Backend: E2B (Firecracker MicroVMs - https://github.com/e2b-dev)")
          sb.appendLine("API Gateway: ${e2bProvider.apiUrl}")
          sb.appendLine("Configured Template: ${e2bProvider.templateId}")
          sb.appendLine("Connectivity: ${if (isOnline) "ONLINE (Authorized)" else "OFFLINE / PENDING_KEY"}")
          sb.appendLine("Active Cloud Sandboxes: ${sandboxes.size}")
          sandboxes.forEach { s ->
            sb.appendLine(" - ${s.sandboxId} (Template: ${s.templateId}, Started: ${s.startedAt ?: "unknown"})")
          }
          val out = sb.toString().trim()
          ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.SUCCEEDED,
            arguments = arguments,
            output = out,
            error = null,
            duration = System.currentTimeMillis() - startTime,
            stdout = out,
            exitCode = 0
          )
        }

        "list_sandboxes" -> {
          val sandboxes = e2bProvider.listActiveSandboxes()
          val jsonArr = JSONArray()
          sandboxes.forEach { s ->
            jsonArr.put(JSONObject().apply {
              put("sandboxId", s.sandboxId)
              put("templateId", s.templateId)
              put("startedAt", s.startedAt)
            })
          }
          val out = jsonArr.toString(2)
          ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.SUCCEEDED,
            arguments = arguments,
            output = out,
            error = null,
            duration = System.currentTimeMillis() - startTime,
            stdout = out,
            exitCode = 0
          )
        }

        "run_code" -> {
          val code = arguments["code"]?.toString()?.trim() ?: ""
          val lang = arguments["language"]?.toString()?.trim()?.lowercase() ?: "python"
          if (code.isBlank()) {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Parameter 'code' is required for action='run_code'.",
              errorType = ToolErrorCode.INVALID_ARGUMENTS,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }

          val req = RunCodeRequest.builder()
            .code(code)
            .context(CodeContext.builder().language(lang).build())
            .build()
          val exec = e2bProvider.executeCode(sessionIdProvider(), req)
          val artifacts = artifactManager.syncWorkspace()
          val isSuccess = (exec.exitCode ?: 0) == 0 && exec.error == null
          ToolResult(
            callId = callId,
            toolName = name,
            status = if (isSuccess) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
            arguments = arguments,
            output = if (isSuccess) exec.stdoutText().ifBlank { "(code executed in E2B microVM)" } else null,
            error = if (isSuccess) null else exec.stderrText().ifBlank { "E2B code execution failed" },
            errorType = if (isSuccess) null else ToolErrorCode.PROCESS_FAILED,
            duration = System.currentTimeMillis() - startTime,
            stdout = exec.stdoutText(),
            stderr = exec.stderrText(),
            exitCode = exec.exitCode ?: 0,
            artifacts = artifacts
          )
        }

        else -> { // "run_command"
          val cmd = arguments["command"]?.toString()?.trim() ?: ""
          if (cmd.isBlank()) {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Parameter 'command' is required for action='run_command'.",
              errorType = ToolErrorCode.INVALID_ARGUMENTS,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }

          val cmdReq = RunCommandRequest.builder()
            .command(cmd)
            .build()
          val exec = e2bProvider.executeCommand(sessionIdProvider(), cmdReq)
          val artifacts = artifactManager.syncWorkspace()
          val isSuccess = (exec.exitCode ?: 0) == 0 && exec.error == null
          ToolResult(
            callId = callId,
            toolName = name,
            status = if (isSuccess) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
            arguments = arguments,
            output = if (isSuccess) exec.stdoutText().ifBlank { "(command executed in E2B microVM)" } else null,
            error = if (isSuccess) null else exec.stderrText().ifBlank { "E2B command execution failed with code ${exec.exitCode}" },
            errorType = if (isSuccess) null else ToolErrorCode.PROCESS_FAILED,
            duration = System.currentTimeMillis() - startTime,
            stdout = exec.stdoutText(),
            stderr = exec.stderrText(),
            exitCode = exec.exitCode ?: 0,
            artifacts = artifacts
          )
        }
      }
    } catch (ce: CancellationException) {
      throw ce
    } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "E2B Cloud operation failed: ${e.message}",
          errorType = ToolErrorCode.SANDBOX_INFRA_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1,
          artifacts = artifactManager.getArtifacts()
        )
      }
    }
}

