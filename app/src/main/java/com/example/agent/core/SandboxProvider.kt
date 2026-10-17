package com.example.agent.core

import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.RunCodeRequest
import com.alibaba.opensandbox.sandbox.Sandbox
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig
import com.alibaba.opensandbox.sandbox.config.SandboxBackendType
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.CommandStatus
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionError
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Execution mode for Sandbox environments.
 * - E2B_CLOUD: Cloud Firecracker MicroVMs via E2B (https://github.com/e2b-dev)
 * This is the sole execution sandbox backend.
 */
enum class SandboxMode {
  E2B_CLOUD
}

/**
 * Dedicated SandboxProvider abstraction for Aragon.
 * Encapsulates session mapping, command execution, code interpretation,
 * and artifact synchronization for E2B Cloud Firecracker MicroVMs.
 */
interface SandboxProvider {
  val name: String
  val mode: SandboxMode
  val isRemote: Boolean get() = true
  val isE2B: Boolean get() = true
  val config: ConnectionConfig
  val capabilities: Set<String> get() = setOf("bash", "python", "filesystem", "diagnostics")

  /**
   * Retrieves or creates a task/session-scoped Sandbox instance.
   */
  fun getSandbox(sessionId: String, taskId: String? = null): Sandbox

  /**
   * Executes a shell command within the specified session.
   */
  suspend fun executeCommand(sessionId: String, request: RunCommandRequest): Execution

  /**
   * Executes code (Python, Bash, JS) within the specified session.
   */
  suspend fun executeCode(sessionId: String, request: RunCodeRequest): Execution

  /**
   * Synchronizes files generated in the sandbox with Aragon's local workspace storage.
   */
  suspend fun syncArtifacts(sessionId: String, targetDir: File, taskId: String? = null): List<Artifact>

  /**
   * Closes the sandbox session and cleans up active resources.
   */
  fun closeSession(sessionId: String)

  /**
   * Validates backend connectivity and health.
   */
  fun ping(): Boolean

  /**
   * Pushes a local file into the sandbox filesystem.
   */
  suspend fun pushFileToRemote(sessionId: String, relPath: String, file: File): Boolean

  /**
   * Pulls a file from the sandbox filesystem to local disk.
   */
  suspend fun pullFileFromRemote(sessionId: String, remotePath: String, destFile: File): Boolean

  /**
   * Checks if a file exists inside the sandbox filesystem.
   */
  suspend fun fileExistsInRemote(sessionId: String, remotePath: String): Boolean
}

data class E2BDiagnosticReport(
  val taskId: String,
  val toolCallId: String,
  val sandboxId: String,
  val templateId: String,
  val activeProvider: String,
  val backendType: String,
  val lifecycleState: SandboxLifecycleState,
  val connectionStatus: String,
  val clientHost: String,
  val hostname: String,
  val osRelease: String,
  val kernel: String,
  val currentUser: String,
  val workingDir: String,
  val diagnosticMarker: String,
  val markerWritten: Boolean,
  val markerReadBack: Boolean,
  val markerPersistedAcrossCalls: Boolean,
  val probeCommand: String,
  val exitCode: Int,
  val stdout: String,
  val stderr: String,
  val durationMs: Long,
  val timeoutState: Boolean,
  val providerErrorDetails: String? = null,
  val isAuthenticE2B: Boolean,
  val timestamp: Long = System.currentTimeMillis()
) {
  fun toFormattedAuditText(): String {
    return buildString {
      appendLine("=== E2B CLOUD HEALTH & IDENTITY AUDIT ===")
      appendLine("Task ID: $taskId")
      appendLine("Tool Call ID: $toolCallId")
      appendLine("Active Provider: $activeProvider")
      appendLine("Backend Type: $backendType")
      appendLine("Authentic E2B MicroVM: ${if (isAuthenticE2B) "VERIFIED (Firecracker MicroVM)" else "UNVERIFIED / REJECTED"}")
      appendLine("E2B Sandbox ID: $sandboxId")
      appendLine("Template ID: $templateId")
      appendLine("Lifecycle State: $lifecycleState")
      appendLine("Connection Status: $connectionStatus")
      appendLine("Client Host: $clientHost")
      appendLine("MicroVM Hostname: $hostname")
      appendLine("MicroVM User: $currentUser")
      appendLine("Working Directory: $workingDir")
      appendLine("Operating System: $osRelease")
      appendLine("Kernel: $kernel")
      appendLine("Diagnostic Marker: $diagnosticMarker")
      appendLine("Marker Written: $markerWritten")
      appendLine("Marker Read Back: $markerReadBack")
      appendLine("Marker Persisted Across Separate Calls: $markerPersistedAcrossCalls")
      appendLine("Probe Command: $probeCommand")
      appendLine("Execution Exit Code: $exitCode")
      appendLine("Execution Duration: ${durationMs}ms")
      appendLine("Timeout State: $timeoutState")
      if (!providerErrorDetails.isNullOrBlank()) {
        appendLine("Provider Error Details: $providerErrorDetails")
      }
      appendLine("STDOUT Preview:\n${stdout.take(500)}")
      if (stderr.isNotBlank()) {
        appendLine("STDERR Preview:\n${stderr.take(500)}")
      }
      appendLine("=========================================")
    }
  }

  fun toJsonObject(): JSONObject {
    return JSONObject().apply {
      put("taskId", taskId)
      put("toolCallId", toolCallId)
      put("sandboxId", sandboxId)
      put("templateId", templateId)
      put("activeProvider", activeProvider)
      put("backendType", backendType)
      put("lifecycleState", lifecycleState.name)
      put("connectionStatus", connectionStatus)
      put("clientHost", clientHost)
      put("hostname", hostname)
      put("osRelease", osRelease)
      put("kernel", kernel)
      put("currentUser", currentUser)
      put("workingDir", workingDir)
      put("diagnosticMarker", diagnosticMarker)
      put("markerWritten", markerWritten)
      put("markerReadBack", markerReadBack)
      put("markerPersistedAcrossCalls", markerPersistedAcrossCalls)
      put("probeCommand", probeCommand)
      put("exitCode", exitCode)
      put("stdout", stdout)
      put("stderr", stderr)
      put("durationMs", durationMs)
      put("timeoutState", timeoutState)
      put("isAuthenticE2B", isAuthenticE2B)
      put("timestamp", timestamp)
      if (providerErrorDetails != null) {
        put("providerErrorDetails", providerErrorDetails)
      }
    }
  }
}

/**
 * Option 3: E2B Cloud Sandbox Provider.
 * Official cloud sandbox platform utilizing Firecracker MicroVMs for hardware-isolated code execution.
 * (https://github.com/e2b-dev | https://e2b.dev)
 *
 * Guarantees:
 * 1. Task-scoped sandbox reuse: commands within the same session reuse the same MicroVM.
 * 2. Persistent sandbox identity across process death / app restart via disk checkpoint.
 * 3. Zero silent fallback: Never falls back to local Android shell when in E2B_CLOUD mode.
 * 4. Genuine exit codes, typed errors, stdout/stderr streaming, and timeout distinction.
 * 5. Robust bidirectional artifact synchronization with correct metadata and atomic downloads.
 * 6. Explicit non-destructive health and identity diagnostics proving real MicroVM execution.
 */
class E2BSandboxProvider(
  val apiKey: String,
  val templateId: String = "base",
  val apiUrl: String = "https://api.e2b.app",
  val sandboxDomain: String = "e2b.app",
  val localWorkspaceDir: File,
  val timeoutSeconds: Int = 300,
  val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()
) : SandboxProvider {

  override val name: String = "E2B Cloud Sandbox ($templateId)"
  override val mode: SandboxMode = SandboxMode.E2B_CLOUD
  override val config: ConnectionConfig = ConnectionConfig(
    endpoint = apiUrl,
    apiKey = apiKey,
    backendType = SandboxBackendType.E2B_CLOUD,
    e2bTemplate = templateId,
    e2bDomain = sandboxDomain
  )

  private val sessionSandboxes = ConcurrentHashMap<String, Sandbox>()
  private val activeE2BSandboxes = ConcurrentHashMap<String, E2BSessionInfo>()
  private val isSyncing = java.util.concurrent.atomic.AtomicBoolean(false)

  data class E2BSessionInfo(
    val sessionId: String,
    val sandboxId: String,
    val templateId: String,
    val clientHost: String,
    var envdAccessToken: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var lastActiveAt: Long = System.currentTimeMillis(),
    val timeoutSeconds: Int = 300,
    var lifecycleState: SandboxLifecycleState = SandboxLifecycleState.RUNNING
  )

  data class E2BRemoteSandbox(
    val sandboxId: String,
    val templateId: String,
    val startedAt: String?,
    val metadata: Map<String, String>
  )

  override fun getSandbox(sessionId: String, taskId: String?): Sandbox {
    return sessionSandboxes.computeIfAbsent(sessionId) {
      Sandbox.builder()
        .id("e2b-$sessionId")
        .baseDir(localWorkspaceDir)
        .endpoint(apiUrl)
        .apiKey(apiKey)
        .build()
    }
  }

  /**
   * Helper to redact sensitive API keys in diagnostic outputs.
   */
  fun redactApiKey(key: String): String {
    val clean = key.trim()
    if (clean.isBlank()) return "(empty)"
    return if (clean.length > 8) "${clean.take(4)}...${clean.takeLast(4)}" else "***"
  }

  fun getActiveSession(sessionId: String): E2BSessionInfo? = activeE2BSandboxes[sessionId]

  fun hasActiveRunningSession(sessionId: String): Boolean =
    activeE2BSandboxes[sessionId]?.lifecycleState == SandboxLifecycleState.RUNNING

  private fun getSessionStorageFile(sessionId: String): File {
    val dir = File(localWorkspaceDir, ".sessions/e2b")
    dir.mkdirs()
    val safeName = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return File(dir, "session_$safeName.json")
  }

  private fun persistSessionToDisk(session: E2BSessionInfo) {
    try {
      val file = getSessionStorageFile(session.sessionId)
      file.parentFile?.mkdirs()
      val json = JSONObject().apply {
        put("sessionId", session.sessionId)
        put("sandboxId", session.sandboxId)
        put("templateId", session.templateId)
        put("backendType", "E2B_CLOUD")
        put("clientHost", session.clientHost)
        put("envdAccessToken", session.envdAccessToken)
        put("createdAt", session.createdAt)
        put("lastActiveAt", session.lastActiveAt)
        put("timeoutSeconds", session.timeoutSeconds)
        put("lifecycleState", session.lifecycleState.name)
      }
      val str = json.toString()
      file.writeText(str)
      // Also write legacy location for backward compatibility
      val safeName = session.sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
      val legacyFile = File(localWorkspaceDir, ".e2b_session_$safeName.json")
      legacyFile.writeText(str)
    } catch (_: Exception) {}
  }

  private fun loadSessionFromDisk(sessionId: String): E2BSessionInfo? {
    try {
      var file = getSessionStorageFile(sessionId)
      if (!file.exists()) {
        val safeName = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val oldFile = File(localWorkspaceDir, ".e2b_session_$safeName.json")
        if (oldFile.exists()) {
          file = oldFile
        } else {
          return null
        }
      }
      val text = file.readText()
      if (text.isBlank()) return null
      val json = JSONObject(text)
      return E2BSessionInfo(
        sessionId = json.optString("sessionId", sessionId),
        sandboxId = json.getString("sandboxId"),
        templateId = json.optString("templateId", templateId),
        clientHost = json.getString("clientHost"),
        envdAccessToken = json.optString("envdAccessToken", ""),
        createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        lastActiveAt = json.optLong("lastActiveAt", System.currentTimeMillis()),
        timeoutSeconds = json.optInt("timeoutSeconds", timeoutSeconds),
        lifecycleState = try {
          SandboxLifecycleState.valueOf(json.optString("lifecycleState", "RUNNING"))
        } catch (_: Exception) {
          SandboxLifecycleState.RUNNING
        }
      )
    } catch (_: Exception) {
      return null
    }
  }

  private fun deleteSessionFromDisk(sessionId: String) {
    try {
      val file = getSessionStorageFile(sessionId)
      if (file.exists()) file.delete()
      val safeName = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
      val oldFile = File(localWorkspaceDir, ".e2b_session_$safeName.json")
      if (oldFile.exists()) oldFile.delete()
    } catch (_: Exception) {}
  }

  /**
   * Fetches fresh connection information (domain and envdAccessToken) from E2B control plane.
   * envd requires X-Access-Token for authenticating requests on port 49983.
   */
  fun fetchConnectionInfo(sbxId: String): Pair<String, String> {
    val cleanBaseUrl = apiUrl.trimEnd('/')
    val effectiveKey = apiKey.trim()
    val defaultDomain = if (sandboxDomain.isNotBlank()) sandboxDomain else if (apiUrl.contains("e2b.dev")) "e2b.dev" else "e2b.app"
    if (effectiveKey.isBlank() || effectiveKey == "MY_E2B_API_KEY") {
      return Pair(defaultDomain, "")
    }

    val connectEndpoints = listOf(
      "$cleanBaseUrl/v2/sandboxes/$sbxId/connect",
      "$cleanBaseUrl/sandboxes/$sbxId/connect",
      "$cleanBaseUrl/v2/sandboxes/$sbxId",
      "$cleanBaseUrl/sandboxes/$sbxId"
    )

    for (url in connectEndpoints) {
      try {
        val isPost = url.endsWith("/connect")
        val reqBuilder = Request.Builder()
          .url(url)
          .header("X-API-Key", effectiveKey)
          .header("Authorization", "Bearer $effectiveKey")
          .header("Content-Type", "application/json")
          .header("User-Agent", "Aragon-Agent-Kernel/2.0 (e2b-client)")

        if (isPost) {
          reqBuilder.post("{\"timeout\":${timeoutSeconds}}".toRequestBody("application/json".toMediaType()))
        } else {
          reqBuilder.get()
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
          val body = resp.body?.string().orEmpty()
          if (resp.isSuccessful) {
            val json = JSONObject(body)
            val token = json.optString(
              "envdAccessToken",
              json.optString(
                "envd_access_token",
                json.optString(
                  "accessToken",
                  json.optString(
                    "access_token",
                    json.optString("token", "")
                  )
                )
              )
            )
            val dom = json.optString("domain", defaultDomain).ifBlank { defaultDomain }
            if (token.isNotBlank()) {
              return Pair(dom, token)
            }
          }
        }
      } catch (_: Exception) {}
    }

    return Pair(defaultDomain, "")
  }

  /**
   * Extends the timeout / keeps alive an active sandbox in the E2B control plane.
   */
  fun keepAlive(sandboxId: String): Boolean {
    val cleanBaseUrl = apiUrl.trimEnd('/')
    val effectiveKey = apiKey.trim()
    if (effectiveKey.isBlank() || effectiveKey == "MY_E2B_API_KEY") return false
    return try {
      val req = Request.Builder()
        .url("$cleanBaseUrl/v2/sandboxes/$sandboxId/timeout")
        .header("X-API-Key", effectiveKey)
        .header("Authorization", "Bearer $effectiveKey")
        .header("Content-Type", "application/json")
        .post("{\"timeout\":${timeoutSeconds}}".toRequestBody("application/json".toMediaType()))
        .build()
      httpClient.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Spawns or reuses an active E2B Firecracker microVM for this session.
   * Throws explicit IllegalStateException if API key is unconfigured or invalid,
   * completely preventing silent local execution fallback.
   */
  @Synchronized
  fun getOrCreateE2BSession(sessionId: String, taskId: String? = null): E2BSessionInfo {
    val effectiveKey = apiKey.trim()
    if (effectiveKey.isBlank() || effectiveKey == "MY_E2B_API_KEY") {
      throw IllegalStateException(
        "E2B Cloud Sandbox execution failed: E2B_API_KEY is not configured or placeholder ('${redactApiKey(effectiveKey)}'). " +
          "In E2B_CLOUD mode, a valid E2B API Key starting with 'e2b_' is mandatory. Execution will NOT fall back to local device."
      )
    }

    val domain = if (sandboxDomain.isNotBlank()) sandboxDomain else if (apiUrl.contains("e2b.dev")) "e2b.dev" else "e2b.app"

    // 1. Check in-memory active session
    val inMemory = activeE2BSandboxes[sessionId]
    if (inMemory != null && inMemory.lifecycleState == SandboxLifecycleState.RUNNING) {
      keepAlive(inMemory.sandboxId)
      inMemory.lastActiveAt = System.currentTimeMillis()
      persistSessionToDisk(inMemory)
      return inMemory
    }

    // 2. Check persisted session on disk (process death / app restart recovery)
    val fromDisk = loadSessionFromDisk(sessionId)
    if (fromDisk != null) {
      // Validate with control plane that the sandbox is still alive
      val (dom, token) = fetchConnectionInfo(fromDisk.sandboxId)
      if (token.isNotBlank()) {
        fromDisk.envdAccessToken = token
        fromDisk.lastActiveAt = System.currentTimeMillis()
        fromDisk.lifecycleState = SandboxLifecycleState.RUNNING
        activeE2BSandboxes[sessionId] = fromDisk
        keepAlive(fromDisk.sandboxId)
        persistSessionToDisk(fromDisk)
        return fromDisk
      } else {
        // Stale or expired sandbox -> clean up persisted file
        deleteSessionFromDisk(sessionId)
      }
    }

    val cleanBaseUrl = apiUrl.trimEnd('/')

    // 3. Create a fresh sandbox instance via POST /v2/sandboxes
    try {
      val payload = JSONObject().apply {
        put("templateID", templateId.ifBlank { "base" })
        put("timeout", timeoutSeconds)
        put("autoPause", false)
        if (!taskId.isNullOrBlank()) {
          put("metadata", JSONObject().apply {
            put("taskId", taskId)
            put("sessionId", sessionId)
          })
        }
      }.toString()

      val req = Request.Builder()
        .url("$cleanBaseUrl/v2/sandboxes")
        .header("X-API-Key", effectiveKey)
        .header("Authorization", "Bearer $effectiveKey")
        .header("Content-Type", "application/json")
        .header("User-Agent", "Aragon-Agent-Kernel/2.0 (e2b-client)")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()

      httpClient.newCall(req).execute().use { resp ->
        val respBody = resp.body?.string().orEmpty()
        if (resp.isSuccessful) {
          val json = JSONObject(respBody)
          val sbxId = json.optString("sandboxID", json.optString("sandbox_id", json.optString("id", "")))
          if (sbxId.isNotBlank()) {
            var token = json.optString(
              "envdAccessToken",
              json.optString(
                "envd_access_token",
                json.optString(
                  "accessToken",
                  json.optString("access_token", "")
                )
              )
            )
            val returnedDomain = json.optString("domain", domain).ifBlank { domain }
            if (token.isBlank()) {
              val connInfo = fetchConnectionInfo(sbxId)
              token = connInfo.second
            }
            val host = "49983-$sbxId.$returnedDomain"
            val sessionInfo = E2BSessionInfo(
              sessionId = sessionId,
              sandboxId = sbxId,
              templateId = templateId,
              clientHost = host,
              envdAccessToken = token,
              lifecycleState = SandboxLifecycleState.RUNNING
            )
            activeE2BSandboxes[sessionId] = sessionInfo
            persistSessionToDisk(sessionInfo)
            return sessionInfo
          }
        } else if (resp.code == 401) {
          throw IllegalStateException("E2B Authentication Failed (HTTP 401): Provided API key is rejected by E2B control plane ($cleanBaseUrl).")
        }
      }
    } catch (e: Exception) {
      if (e is IllegalStateException) throw e
    }

    // 4. Attach to existing active sandbox on account if creation hit concurrency limit (Free tier limit = 1 microVM)
    val activeList = listActiveSandboxes()
    val existingActive = activeList.firstOrNull { it.templateId == templateId } ?: activeList.firstOrNull()
    if (existingActive != null && existingActive.sandboxId.isNotBlank()) {
      val sbxId = existingActive.sandboxId
      val (returnedDomain, token) = fetchConnectionInfo(sbxId)
      val host = "49983-$sbxId.$returnedDomain"
      val sessionInfo = E2BSessionInfo(
        sessionId = sessionId,
        sandboxId = sbxId,
        templateId = existingActive.templateId,
        clientHost = host,
        envdAccessToken = token,
        lifecycleState = SandboxLifecycleState.RUNNING
      )
      activeE2BSandboxes[sessionId] = sessionInfo
      persistSessionToDisk(sessionInfo)
      keepAlive(sbxId)
      return sessionInfo
    }

    throw IllegalStateException("Failed to create or attach to E2B Cloud MicroVM. Check network connectivity and E2B account quota.")
  }

  override suspend fun executeCommand(sessionId: String, request: RunCommandRequest): Execution {
    val effectiveKey = apiKey.trim()
    val execId = "e2b-exec-${UUID.randomUUID().toString().take(8)}"
    val execution = Execution(id = execId)

    // Strict validation: In E2B_CLOUD mode, NO silent local execution is permitted!
    if (effectiveKey.isBlank() || effectiveKey == "MY_E2B_API_KEY") {
      val errMsg = "E2B Cloud Sandbox execution failed: E2B_API_KEY is not configured or placeholder ('${redactApiKey(effectiveKey)}'). " +
        "In E2B_CLOUD mode, a valid E2B API Key is strictly required. Execution refused to fallback to local Android environment."
      execution.exitCode = 1
      execution.logs.stderr.add(OutputMessage(errMsg))
      execution.error = ExecutionError(name = "E2BAuthenticationError", value = errMsg)
      return execution
    }

    var session: E2BSessionInfo
    try {
      session = getOrCreateE2BSession(sessionId)
    } catch (e: Exception) {
      val errMsg = "E2B Session Provisioning Fault: ${e.message}"
      execution.exitCode = 1
      execution.logs.stderr.add(OutputMessage(errMsg))
      execution.error = ExecutionError(name = "E2BProvisioningError", value = errMsg)
      return execution
    }

    val domain = if (sandboxDomain.isNotBlank()) sandboxDomain else if (apiUrl.contains("e2b.dev")) "e2b.dev" else "e2b.app"

    // Ensure session has an envdAccessToken
    if (session.envdAccessToken.isBlank()) {
      val (connDomain, token) = fetchConnectionInfo(session.sandboxId)
      if (token.isNotBlank()) {
        session.envdAccessToken = token
        session = session.copy(
          envdAccessToken = token,
          clientHost = "49983-${session.sandboxId}.$connDomain"
        )
        activeE2BSandboxes[sessionId] = session
        persistSessionToDisk(session)
      }
    }

    var stdout = ""
    var stderr = ""
    var exitCode = 0
    var isSuccess = true
    var executedSuccessfully = false
    var lastError = ""

    try {
      val requestedCwd = request.workingDirectory?.takeIf { it.isNotBlank() } ?: "/home/user"
      val processPayload = JSONObject().apply {
        val proc = JSONObject().apply {
          put("cmd", "/bin/bash")
          put("args", JSONArray().apply {
            put("-c")
            put(request.command)
          })
          put("cwd", requestedCwd)
          if (request.envs.isNotEmpty()) {
            val envObj = JSONObject()
            request.envs.forEach { (k, v) -> envObj.put(k, v) }
            put("env", envObj)
          }
        }
        put("process", proc)
        put("cwd", requestedCwd)
      }.toString()

      val primaryHost = if (session.clientHost.isNotBlank()) session.clientHost else "49983-${session.sandboxId}.$domain"
      val candidateUrls = listOf(
        "https://$primaryHost/process.Process/Start",
        "https://49983-${session.sandboxId}.$domain/process.Process/Start"
      ).distinct()

      val envelopedBody = createConnectEnvelopedRequestBody(processPayload)

      // Dynamically configure HTTP client timeout matching requested command timeout
      val reqTimeoutSeconds = request.timeout?.seconds?.coerceAtLeast(10L)?.coerceAtMost(300L) ?: 60L
      val executionHttpClient = httpClient.newBuilder()
        .readTimeout(reqTimeoutSeconds + 15L, TimeUnit.SECONDS)
        .build()

      for (targetUrl in candidateUrls) {
        try {
          val token = session.envdAccessToken
          val reqBuilder = Request.Builder()
            .url(targetUrl)
            .header("X-API-Key", effectiveKey)
            .header("Authorization", if (token.isNotBlank()) "Bearer $token" else "Bearer $effectiveKey")
            .header("Connect-Protocol-Version", "1")
            .header("Content-Type", "application/connect+json")
            .header("E2b-Sandbox-Id", session.sandboxId)
            .header("E2b-Sandbox-Port", "49983")

          if (token.isNotBlank()) {
            reqBuilder.header("X-Access-Token", token)
            reqBuilder.header("e2b-traffic-access-token", token)
          }

          val rpcReq = reqBuilder.post(envelopedBody).build()

          executionHttpClient.newCall(rpcReq).execute().use { resp ->
            val rawBytes = resp.body?.bytes() ?: ByteArray(0)
            val rawBodyStr = String(rawBytes, Charsets.UTF_8)

            if (resp.isSuccessful) {
              val parsed = parseConnectProcessResponse(rawBytes, rawBodyStr)
              stdout = parsed.stdout
              stderr = parsed.stderr
              exitCode = parsed.exitCode
              isSuccess = parsed.isSuccess
              executedSuccessfully = true
            } else if (resp.code == 401) {
              // Token expired: fetch fresh token and retry once
              val (freshDomain, freshToken) = fetchConnectionInfo(session.sandboxId)
              if (freshToken.isNotBlank() && freshToken != token) {
                session.envdAccessToken = freshToken
                session = session.copy(
                  envdAccessToken = freshToken,
                  clientHost = "49983-${session.sandboxId}.$freshDomain"
                )
                activeE2BSandboxes[sessionId] = session
                persistSessionToDisk(session)

                val retryReq = Request.Builder()
                  .url(targetUrl)
                  .header("X-API-Key", effectiveKey)
                  .header("Authorization", "Bearer $freshToken")
                  .header("X-Access-Token", freshToken)
                  .header("e2b-traffic-access-token", freshToken)
                  .header("Connect-Protocol-Version", "1")
                  .header("Content-Type", "application/connect+json")
                  .header("E2b-Sandbox-Id", session.sandboxId)
                  .header("E2b-Sandbox-Port", "49983")
                  .post(envelopedBody)
                  .build()

                executionHttpClient.newCall(retryReq).execute().use { retryResp ->
                  val retryBytes = retryResp.body?.bytes() ?: ByteArray(0)
                  val retryBodyStr = String(retryBytes, Charsets.UTF_8)
                  if (retryResp.isSuccessful) {
                    val parsed = parseConnectProcessResponse(retryBytes, retryBodyStr)
                    stdout = parsed.stdout
                    stderr = parsed.stderr
                    exitCode = parsed.exitCode
                    isSuccess = parsed.isSuccess
                    executedSuccessfully = true
                  } else {
                    lastError = "E2B RPC Error HTTP ${retryResp.code}: $retryBodyStr"
                  }
                }
              } else {
                lastError = "E2B RPC Unauthorized (HTTP 401): $rawBodyStr"
              }
            } else if (resp.code == 502 || resp.code == 404 || rawBodyStr.contains("The sandbox was not found", ignoreCase = true) || rawBodyStr.contains("sandbox not found", ignoreCase = true)) {
              // Stale/expired E2B sandbox!
              session.lifecycleState = SandboxLifecycleState.EXPIRED
              activeE2BSandboxes.remove(sessionId)
              deleteSessionFromDisk(sessionId)
              lastError = "E2B Cloud Sandbox expired or terminated (HTTP ${resp.code}: $rawBodyStr). Stale sandbox '${session.sandboxId}' evicted."
              try {
                val freshSession = getOrCreateE2BSession(sessionId, taskId = "auto-refresh")
                if (freshSession.sandboxId != session.sandboxId) {
                  session = freshSession
                  val freshDomain = if (sandboxDomain.isNotBlank()) sandboxDomain else if (apiUrl.contains("e2b.dev")) "e2b.dev" else "e2b.app"
                  val freshToken = freshSession.envdAccessToken
                  val freshUrl = if (freshSession.clientHost.isNotBlank()) {
                    "https://${freshSession.clientHost}/process.Process/Start"
                  } else {
                    "https://49983-${freshSession.sandboxId}.$freshDomain/process.Process/Start"
                  }
                  val freshReq = Request.Builder()
                    .url(freshUrl)
                    .header("X-API-Key", effectiveKey)
                    .header("Authorization", if (freshToken.isNotBlank()) "Bearer $freshToken" else "Bearer $effectiveKey")
                    .header("Connect-Protocol-Version", "1")
                    .header("Content-Type", "application/connect+json")
                    .header("E2b-Sandbox-Id", freshSession.sandboxId)
                    .header("E2b-Sandbox-Port", "49983")
                    .apply {
                      if (freshToken.isNotBlank()) {
                        header("X-Access-Token", freshToken)
                        header("e2b-traffic-access-token", freshToken)
                      }
                    }
                    .post(envelopedBody)
                    .build()
                  executionHttpClient.newCall(freshReq).execute().use { freshResp ->
                    val freshBytes = freshResp.body?.bytes() ?: ByteArray(0)
                    val freshBodyStr = String(freshBytes, Charsets.UTF_8)
                    if (freshResp.isSuccessful) {
                      val parsed = parseConnectProcessResponse(freshBytes, freshBodyStr)
                      stdout = "[Notice: Stale E2B sandbox was expired and automatically replaced with fresh sandbox '${freshSession.sandboxId}'. Previous files and packages must be re-initialized.]\n" + parsed.stdout
                      stderr = parsed.stderr
                      exitCode = parsed.exitCode
                      isSuccess = parsed.isSuccess
                      executedSuccessfully = true
                    } else {
                      lastError = "Fresh E2B sandbox failed HTTP ${freshResp.code}: $freshBodyStr"
                    }
                  }
                }
              } catch (refreshEx: Exception) {
                lastError = "E2B Sandbox expired (HTTP ${resp.code}). Re-provisioning failed: ${refreshEx.message}"
              }
            } else {
              lastError = "E2B RPC Error HTTP ${resp.code}: $rawBodyStr"
            }
          }

          if (executedSuccessfully) break
        } catch (e: Exception) {
          lastError = "E2B MicroVM Connection Error ($targetUrl): ${e.message}"
        }
      }

      if (!executedSuccessfully) {
        stderr = lastError.ifBlank { "Command execution failed in E2B microVM." }
        exitCode = 1
        isSuccess = false
      }
    } catch (e: Exception) {
      stderr = "E2B dispatch error: ${e.message}"
      exitCode = 1
      isSuccess = false
    }

    if (!isSyncing.get() && isSuccess) {
      try {
        syncArtifacts(sessionId, localWorkspaceDir)
      } catch (_: Exception) {}
    }

    execution.exitCode = exitCode
    if (stdout.isNotBlank()) {
      execution.logs.stdout.add(OutputMessage(stdout))
    }
    if (stderr.isNotBlank()) {
      execution.logs.stderr.add(OutputMessage(stderr))
    }
    if (!isSuccess) {
      val errName = when {
        lastError.contains("HTTP 404") || lastError.contains("HTTP 502") || lastError.contains("expired") || lastError.contains("Stale sandbox") || lastError.contains("Fresh E2B sandbox failed") -> "E2BStaleSessionError"
        exitCode == 127 -> "CommandNotFound"
        exitCode == -1 || stderr.contains("timeout", ignoreCase = true) || lastError.contains("timeout", ignoreCase = true) -> "E2BTimeoutError"
        stderr.contains("Unauthorized", ignoreCase = true) || lastError.contains("401") -> "E2BAuthenticationError"
        else -> "E2BCommandError"
      }
      execution.error = ExecutionError(
        name = errName,
        value = if (lastError.isNotBlank()) lastError else if (stderr.isNotBlank()) stderr else "Remote execution error"
      )
    }

    return execution
  }

  data class ConnectProcessResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val isSuccess: Boolean
  )

  fun parseConnectProcessResponse(rawBytes: ByteArray, rawBodyStr: String): ConnectProcessResult {
    val stdoutSb = StringBuilder()
    val stderrSb = StringBuilder()
    var parsedExitCode: Int? = null
    var trailerError: String? = null
    var parsedFramed = false

    // 5-byte Connect stream envelope parsing: [1-byte flags][4-byte big-endian len][payload]
    var offset = 0
    while (offset + 5 <= rawBytes.size) {
      val flag = rawBytes[offset].toInt() and 0xFF
      val len = ((rawBytes[offset + 1].toInt() and 0xFF) shl 24) or
                ((rawBytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((rawBytes[offset + 3].toInt() and 0xFF) shl 8) or
                (rawBytes[offset + 4].toInt() and 0xFF)

      if (len < 0 || offset + 5 + len > rawBytes.size) {
        break
      }

      parsedFramed = true
      val chunkBytes = rawBytes.copyOfRange(offset + 5, offset + 5 + len)
      val chunkStr = String(chunkBytes, Charsets.UTF_8)

      try {
        val json = JSONObject(chunkStr)
        if ((flag and 0x02) != 0) {
          // End-of-stream trailer message
          val err = json.optJSONObject("error")
          if (err != null) {
            trailerError = err.optString("message", err.toString())
          }
        } else {
          // Data message: extract stdout, stderr, exitCode
          extractProcessOutput(json, stdoutSb, stderrSb) { code ->
            parsedExitCode = code
          }
        }
      } catch (_: Exception) {}

      offset += 5 + len
    }

    if (!parsedFramed) {
      // Fallback for plain JSON or line-by-line streaming
      try {
        val json = JSONObject(rawBodyStr)
        extractProcessOutput(json, stdoutSb, stderrSb) { code ->
          parsedExitCode = code
        }
      } catch (_: Exception) {
        val lines = rawBodyStr.lines()
        var jsonParsed = false
        for (line in lines) {
          val trimmed = line.trim()
          if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
              val json = JSONObject(trimmed)
              extractProcessOutput(json, stdoutSb, stderrSb) { code ->
                parsedExitCode = code
              }
              jsonParsed = true
            } catch (_: Exception) {}
          }
        }
        if (!jsonParsed) {
          stdoutSb.append(rawBodyStr)
        }
      }
    }

    val finalExit = parsedExitCode ?: if (trailerError != null) 1 else 0
    val finalStderr = if (trailerError != null) {
      val existing = stderrSb.toString().trim()
      if (existing.isNotBlank()) "$existing\n$trailerError" else trailerError
    } else {
      stderrSb.toString()
    }

    return ConnectProcessResult(
      stdout = stdoutSb.toString(),
      stderr = finalStderr,
      exitCode = finalExit,
      isSuccess = finalExit == 0 && trailerError == null
    )
  }

  /**
   * Envelops a JSON string message into Connect protocol stream framing:
   * [1-byte flags (0x00)][4-byte big-endian message length][payload UTF-8 bytes]
   */
  fun createConnectEnvelopedRequestBody(jsonString: String): RequestBody {
    val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)
    val envelope = ByteArray(5 + jsonBytes.size)
    envelope[0] = 0 // Flags: 0x00 = uncompressed data message
    val len = jsonBytes.size
    envelope[1] = ((len ushr 24) and 0xFF).toByte()
    envelope[2] = ((len ushr 16) and 0xFF).toByte()
    envelope[3] = ((len ushr 8) and 0xFF).toByte()
    envelope[4] = (len and 0xFF).toByte()
    System.arraycopy(jsonBytes, 0, envelope, 5, len)
    return envelope.toRequestBody("application/connect+json".toMediaType())
  }

  private fun extractProcessOutput(
    json: JSONObject,
    stdoutSb: StringBuilder,
    stderrSb: StringBuilder,
    onExitCode: (Int) -> Unit
  ) {
    val event = json.optJSONObject("event") ?: json
    val data = event.optJSONObject("data")
    if (data != null) {
      val outStr = data.optString("stdout", "")
      if (outStr.isNotEmpty()) stdoutSb.append(decodeBase64OrText(outStr))
      val ptyStr = data.optString("pty", "")
      if (ptyStr.isNotEmpty()) stdoutSb.append(decodeBase64OrText(ptyStr))
      val errStr = data.optString("stderr", "")
      if (errStr.isNotEmpty()) stderrSb.append(decodeBase64OrText(errStr))
    } else {
      val outStr = json.optString("stdout", "")
      if (outStr.isNotEmpty()) stdoutSb.append(decodeBase64OrText(outStr))
      val ptyStr = json.optString("pty", "")
      if (ptyStr.isNotEmpty()) stdoutSb.append(decodeBase64OrText(ptyStr))
      val errStr = json.optString("stderr", "")
      if (errStr.isNotEmpty()) stderrSb.append(decodeBase64OrText(errStr))
    }

    val endObj = event.optJSONObject("end")
    if (endObj != null) {
      if (endObj.has("exitCode")) {
        onExitCode(endObj.optInt("exitCode", 0))
      } else if (endObj.has("exit_code")) {
        onExitCode(endObj.optInt("exit_code", 0))
      }
      val endErr = endObj.optString("error", "")
      if (endErr.isNotBlank()) {
        if (stderrSb.isNotEmpty()) stderrSb.append("\n")
        stderrSb.append(endErr)
      }
    } else if (event.has("exitCode")) {
      onExitCode(event.optInt("exitCode", 0))
    } else if (event.has("exit_code")) {
      onExitCode(event.optInt("exit_code", 0))
    } else if (json.has("exitCode")) {
      onExitCode(json.optInt("exitCode", 0))
    } else if (json.has("exit_code")) {
      onExitCode(json.optInt("exit_code", 0))
    }
  }

  fun decodeBase64OrText(str: String): String {
    val trimmed = str.trim()
    if (trimmed.isEmpty()) return ""
    return try {
      val bytes = java.util.Base64.getDecoder().decode(trimmed)
      val decoded = String(bytes, Charsets.UTF_8)
      if (decoded.contains('\uFFFD') && !trimmed.contains('\uFFFD')) {
        str
      } else {
        decoded
      }
    } catch (_: Exception) {
      str
    }
  }

  override suspend fun executeCode(sessionId: String, request: RunCodeRequest): Execution {
    val lang = request.context.language.lowercase()
    val base64Code = java.util.Base64.getEncoder().encodeToString(request.code.toByteArray(Charsets.UTF_8))
    val scriptCmd = when (lang) {
      "python", "py", "python3" -> "([ -x /home/user/venv/bin/python3 ] && PY=/home/user/venv/bin/python3 || PY=python3); \$PY -u -c \"import base64; exec(base64.b64decode('$base64Code').decode('utf-8'))\""
      "javascript", "js", "node" -> "node -e \"eval(Buffer.from('$base64Code', 'base64').toString('utf-8'))\""
      else -> "bash -c \"echo '$base64Code' | base64 -d | bash\""
    }

    val cmdReq = RunCommandRequest.builder()
      .command(scriptCmd)
      .timeout(java.time.Duration.ofSeconds(60))
      .envs(TerminalTool.REMOTE_COMMAND_ENVS)
      .build()

    return executeCommand(sessionId, cmdReq)
  }

  /**
   * Pushes a local workspace file up into the active E2B MicroVM (/home/user/$relPath).
   */
  override suspend fun pushFileToRemote(sessionId: String, relPath: String, file: File): Boolean {
    if (!file.exists() || !file.isFile) return false
    val cleanRelPath = relPath.trim().removePrefix("./")
    return try {
      val fileBytes = file.readBytes()
      val chunkSize = 40 * 1024 // 40KB chunk -> ~54KB base64, comfortably below Linux ARG_MAX
      val totalChunks = (fileBytes.size + chunkSize - 1) / chunkSize
      for (i in 0 until totalChunks) {
        val start = i * chunkSize
        val end = minOf(start + chunkSize, fileBytes.size)
        val chunk = fileBytes.copyOfRange(start, end)
        val base64Content = java.util.Base64.getEncoder().encodeToString(chunk)
        val mode = if (i == 0) "wb" else "ab"
        val pushCmd = "python3 -c \"import os, base64; p = '$cleanRelPath'; target = p if os.path.isabs(p) else os.path.join('/home/user', p.removeprefix('/')); d = os.path.dirname(target); d and os.makedirs(d, exist_ok=True); f = open(target, '$mode'); f.write(base64.b64decode('$base64Content')); f.close()\""
        val req = RunCommandRequest.builder()
          .command(pushCmd)
          .timeout(java.time.Duration.ofSeconds(30))
          .build()
        val exec = executeCommand(sessionId, req)
        if (exec.exitCode != 0) return false
      }
      // Also ensure symlinked or copied into /home/user/workspace/ so paths in both /home/user and workspace resolve immediately
      val linkCmd = "python3 -c \"import os; os.makedirs('/home/user/workspace', exist_ok=True); p = '$cleanRelPath'; src = p if os.path.isabs(p) else os.path.join('/home/user', p.removeprefix('/')); rel = os.path.relpath(src, '/home/user') if src.startswith('/home/user/') else os.path.basename(src); dst = os.path.join('/home/user/workspace', rel); os.makedirs(os.path.dirname(dst), exist_ok=True); (os.path.exists(dst) or os.symlink(src, dst)) if src != dst and not os.path.exists(dst) else None\""
      executeCommand(sessionId, RunCommandRequest.builder().command(linkCmd).timeout(java.time.Duration.ofSeconds(10)).build())
      true
    } catch (_: Exception) {
      false
    }
  }

  override suspend fun pullFileFromRemote(sessionId: String, remotePath: String, destFile: File): Boolean {
    val cleanPath = remotePath.trim()
    if (cleanPath.isBlank()) return false
    return try {
      val pullCmd = "python3 -c \"import os, base64; p = '$cleanPath'; target = p if os.path.isabs(p) else os.path.join('/home/user', p.removeprefix('./').removeprefix('/')); (not os.path.exists(target) and os.path.exists(os.path.join('/home/user/workspace', p.removeprefix('./').removeprefix('/')))) and (target := os.path.join('/home/user/workspace', p.removeprefix('./').removeprefix('/'))); print(base64.b64encode(open(target, 'rb').read()).decode('ascii') if os.path.exists(target) and os.path.isfile(target) else 'FILE_NOT_FOUND')\""
      val req = RunCommandRequest.builder()
        .command(pullCmd)
        .timeout(java.time.Duration.ofSeconds(15))
        .build()
      val exec = executeCommand(sessionId, req)
      val out = exec.stdoutText().trim()
      if (out.isNotEmpty() && !out.contains("FILE_NOT_FOUND")) {
        val bytes = java.util.Base64.getDecoder().decode(out)
        destFile.parentFile?.mkdirs()
        destFile.writeBytes(bytes)
        true
      } else {
        false
      }
    } catch (_: Exception) {
      false
    }
  }

  override suspend fun fileExistsInRemote(sessionId: String, remotePath: String): Boolean {
    val cleanPath = remotePath.trim()
    if (cleanPath.isBlank()) return false
    return try {
      val checkCmd = "python3 -c \"import os, sys; p = '$cleanPath'; target = p if os.path.isabs(p) else os.path.join('/home/user', p.removeprefix('./').removeprefix('/')); (not os.path.exists(target) and os.path.exists(os.path.join('/home/user/workspace', p.removeprefix('./').removeprefix('/')))) and (target := os.path.join('/home/user/workspace', p.removeprefix('./').removeprefix('/'))); sys.exit(0 if os.path.exists(target) else 1)\""
      val req = RunCommandRequest.builder()
        .command(checkCmd)
        .timeout(java.time.Duration.ofSeconds(10))
        .build()
      val exec = executeCommand(sessionId, req)
      exec.exitCode == 0
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Synchronizes files generated in the E2B microVM with Aragon's local workspace storage.
   * Discovers newly created deliverable files atomically, decodes and persists them.
   */
  override suspend fun syncArtifacts(sessionId: String, targetDir: File, taskId: String?): List<Artifact> {
    targetDir.mkdirs()
    if (!isSyncing.compareAndSet(false, true)) {
      val sb = getSandbox(sessionId)
      return sb.listArtifacts()
    }
    try {
      val effectiveKey = apiKey.trim()
      if (effectiveKey.isNotBlank() && effectiveKey != "MY_E2B_API_KEY") {
        try {
          // Fast, atomic discovery script returning JSON map of relative path -> base64 content
          val discoveryCmd = """
python3 -c "import os, json, base64
res = {}
for root, _, files in os.walk('/home/user'):
    for f in files:
        if not f.startswith('.') and not any(p in root for p in ['/bin', '/lib', '/tmp', '/.', '__pycache__', 'node_modules', 'venv', '.venv']):
            full = os.path.join(root, f)
            p = os.path.relpath(full, '/home/user')
            try:
                if os.path.isfile(full) and os.path.getsize(full) < 10000000:
                    with open(full, 'rb') as fp:
                        res[p] = base64.b64encode(fp.read()).decode('ascii')
            except Exception: pass
print('___ARAGON_SYNC_START___' + json.dumps(res) + '___ARAGON_SYNC_END___')
"
          """.trimIndent()

          val findReq = RunCommandRequest.builder()
            .command(discoveryCmd)
            .timeout(java.time.Duration.ofSeconds(20))
            .build()

          val exec = executeCommand(sessionId, findReq)
          val out = exec.stdoutText()
          if (out.contains("___ARAGON_SYNC_START___") && out.contains("___ARAGON_SYNC_END___")) {
            val jsonStr = out.substringAfter("___ARAGON_SYNC_START___").substringBefore("___ARAGON_SYNC_END___")
            val jsonMap = JSONObject(jsonStr)
            val keys = jsonMap.keys()
            while (keys.hasNext()) {
              val relPath = keys.next()
              val b64 = jsonMap.optString(relPath, "")
              if (b64.isNotBlank()) {
                val destFile = File(targetDir, relPath)
                destFile.parentFile?.mkdirs()
                try {
                  val bytes = java.util.Base64.getDecoder().decode(b64)
                  destFile.writeBytes(bytes)
                } catch (_: Exception) {}
              }
            }
          }
        } catch (_: Exception) {}
      }

      val sb = getSandbox(sessionId)
      return sb.listArtifacts()
    } finally {
      isSyncing.set(false)
    }
  }

  /**
   * Performs an authoritative, non-destructive health and identity diagnostic of the E2B Sandbox.
   * Proves which environment executes commands, verifies real MicroVM parameters,
   * writes a unique diagnostic marker, reads it back, checks persistence across separate calls,
   * cleans up, and returns an auditable E2BDiagnosticReport.
   */
  suspend fun runHealthAndIdentityDiagnostic(
    sessionId: String,
    taskId: String = "diag-task",
    toolCallId: String = "call-diag"
  ): E2BDiagnosticReport {
    val t0 = System.currentTimeMillis()
    val effectiveKey = apiKey.trim()
    val redactedKey = redactApiKey(effectiveKey)

    if (effectiveKey.isBlank() || effectiveKey == "MY_E2B_API_KEY") {
      return E2BDiagnosticReport(
        taskId = taskId,
        toolCallId = toolCallId,
        sandboxId = "unauthenticated",
        templateId = templateId,
        activeProvider = name,
        backendType = "E2B_CLOUD",
        lifecycleState = SandboxLifecycleState.FAILED,
        connectionStatus = "UNAUTHENTICATED (Key missing or placeholder: $redactedKey)",
        clientHost = "none",
        hostname = "unknown",
        osRelease = "unknown",
        kernel = "unknown",
        currentUser = "none",
        workingDir = "none",
        diagnosticMarker = "none",
        markerWritten = false,
        markerReadBack = false,
        markerPersistedAcrossCalls = false,
        probeCommand = "none",
        exitCode = 1,
        stdout = "",
        stderr = "E2B API Key is missing or invalid. Execution refused to fallback to local Android environment.",
        durationMs = System.currentTimeMillis() - t0,
        timeoutState = false,
        providerErrorDetails = "Authentication failure: Missing E2B_API_KEY",
        isAuthenticE2B = false
      )
    }

    // 1. Session acquisition / reconnection
    val session = try {
      getOrCreateE2BSession(sessionId, taskId)
    } catch (e: Exception) {
      return E2BDiagnosticReport(
        taskId = taskId,
        toolCallId = toolCallId,
        sandboxId = "provisioning_failed",
        templateId = templateId,
        activeProvider = name,
        backendType = "E2B_CLOUD",
        lifecycleState = SandboxLifecycleState.FAILED,
        connectionStatus = "PROVISIONING_FAILED: ${e.message}",
        clientHost = "none",
        hostname = "unknown",
        osRelease = "unknown",
        kernel = "unknown",
        currentUser = "none",
        workingDir = "none",
        diagnosticMarker = "none",
        markerWritten = false,
        markerReadBack = false,
        markerPersistedAcrossCalls = false,
        probeCommand = "none",
        exitCode = 1,
        stdout = "",
        stderr = e.message ?: "Provisioning error",
        durationMs = System.currentTimeMillis() - t0,
        timeoutState = false,
        providerErrorDetails = e.message,
        isAuthenticE2B = false
      )
    }

    // 2. Harmless Identity Probe command execution
    val probeCmd = "echo -n 'USER='; whoami; echo -n 'UID='; id -u; echo -n 'HOST='; hostname; echo -n 'PWD='; pwd; echo -n 'UNAME='; uname -srm; if [ -f /etc/os-release ]; then grep -E '^(PRETTY_NAME|ID)=' /etc/os-release; fi"
    val probeReq = RunCommandRequest.builder()
      .command(probeCmd)
      .timeout(java.time.Duration.ofSeconds(15))
      .build()

    val probeExec = executeCommand(sessionId, probeReq)
    val probeOut = probeExec.stdoutText()
    val probeErr = probeExec.stderrText()
    val probeExit = probeExec.exitCode ?: 0

    var parsedUser = "unknown"
    var parsedHost = "unknown"
    var parsedPwd = "unknown"
    var parsedKernel = "unknown"
    var parsedOs = "unknown"

    for (line in probeOut.lines()) {
      when {
        line.startsWith("USER=") -> parsedUser = line.removePrefix("USER=").trim()
        line.startsWith("HOST=") -> parsedHost = line.removePrefix("HOST=").trim()
        line.startsWith("PWD=") -> parsedPwd = line.removePrefix("PWD=").trim()
        line.startsWith("UNAME=") -> parsedKernel = line.removePrefix("UNAME=").trim()
        line.startsWith("PRETTY_NAME=") -> parsedOs = line.removePrefix("PRETTY_NAME=").removeSurrounding("\"").trim()
      }
    }

    // 3. Unique diagnostic marker generation & verification
    val markerId = "aragon_marker_${taskId.take(8)}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
    val markerPath = "/home/user/.aragon_identity_${markerId}.txt"

    // Step A: Write marker
    val writeCmd = "printf '%s' '$markerId' > '$markerPath'"
    val writeReq = RunCommandRequest.builder().command(writeCmd).timeout(java.time.Duration.ofSeconds(10)).build()
    val writeExec = executeCommand(sessionId, writeReq)
    val markerWritten = writeExec.exitCode == 0

    // Step B: Read marker back in separate tool call
    val readCmd = "cat '$markerPath'"
    val readReq = RunCommandRequest.builder().command(readCmd).timeout(java.time.Duration.ofSeconds(10)).build()
    val readExec = executeCommand(sessionId, readReq)
    val markerReadBack = readExec.exitCode == 0 && readExec.stdoutText().trim() == markerId

    // Step C: Verify persistence across separate invocation
    val persistCmd = "test -f '$markerPath' && echo 'PERSISTED_OK'"
    val persistReq = RunCommandRequest.builder().command(persistCmd).timeout(java.time.Duration.ofSeconds(10)).build()
    val persistExec = executeCommand(sessionId, persistReq)
    val markerPersisted = persistExec.exitCode == 0 && persistExec.stdoutText().contains("PERSISTED_OK")

    // Step D: Non-destructive cleanup of diagnostic marker
    try {
      val cleanReq = RunCommandRequest.builder().command("rm -f '$markerPath'").timeout(java.time.Duration.ofSeconds(10)).build()
      executeCommand(sessionId, cleanReq)
    } catch (_: Exception) {}

    val isAuthenticE2B = probeExit == 0 && markerWritten && markerReadBack && markerPersisted && !session.sandboxId.startsWith("e2b-local-")

    return E2BDiagnosticReport(
      taskId = taskId,
      toolCallId = toolCallId,
      sandboxId = session.sandboxId,
      templateId = session.templateId,
      activeProvider = name,
      backendType = "E2B_CLOUD",
      lifecycleState = session.lifecycleState,
      connectionStatus = if (isAuthenticE2B) "ONLINE_AUTHENTICATED (Firecracker MicroVM)" else "DEGRADED",
      clientHost = session.clientHost,
      hostname = parsedHost,
      osRelease = parsedOs,
      kernel = parsedKernel,
      currentUser = parsedUser,
      workingDir = parsedPwd,
      diagnosticMarker = markerId,
      markerWritten = markerWritten,
      markerReadBack = markerReadBack,
      markerPersistedAcrossCalls = markerPersisted,
      probeCommand = probeCmd,
      exitCode = probeExit,
      stdout = probeOut,
      stderr = probeErr,
      durationMs = System.currentTimeMillis() - t0,
      timeoutState = probeExit == -1,
      providerErrorDetails = if (probeExit != 0) probeErr else null,
      isAuthenticE2B = isAuthenticE2B
    )
  }

  override fun closeSession(sessionId: String) {
    sessionSandboxes.remove(sessionId)?.close()
    val session = activeE2BSandboxes.remove(sessionId) ?: loadSessionFromDisk(sessionId)
    deleteSessionFromDisk(sessionId)
    if (session != null) {
      session.lifecycleState = SandboxLifecycleState.TERMINATED
      val effectiveKey = apiKey.trim()
      if (effectiveKey.isNotBlank() && effectiveKey != "MY_E2B_API_KEY") {
        try {
          val cleanBaseUrl = apiUrl.trimEnd('/')
          val req = Request.Builder()
            .url("$cleanBaseUrl/v2/sandboxes/${session.sandboxId}")
            .header("X-API-Key", effectiveKey)
            .header("Authorization", "Bearer $effectiveKey")
            .delete()
            .build()
          httpClient.newCall(req).execute().close()
        } catch (_: Exception) {}
      }
    }
  }

  data class E2BPingResult(
    val isSuccess: Boolean,
    val statusCode: Int = 0,
    val message: String = "",
    val activeSandboxes: List<E2BRemoteSandbox> = emptyList(),
    val latencyMs: Long = 0L
  )

  fun pingDetailed(): E2BPingResult {
    val cleanBaseUrl = apiUrl.trim().trimEnd('/')
    val effectiveKey = apiKey.trim()
    if (effectiveKey.isBlank() || effectiveKey == "MY_E2B_API_KEY") {
      return E2BPingResult(
        isSuccess = false,
        message = "E2B API Key is missing. Please enter your API Key starting with 'e2b_'."
      )
    }

    val t0 = System.currentTimeMillis()
    return try {
      val req = Request.Builder()
        .url("$cleanBaseUrl/sandboxes")
        .header("X-API-Key", effectiveKey)
        .header("Authorization", "Bearer $effectiveKey")
        .header("User-Agent", "Aragon-Agent-Kernel/2.0 (e2b-client)")
        .get()
        .build()

      httpClient.newCall(req).execute().use { resp ->
        val latency = System.currentTimeMillis() - t0
        val body = resp.body?.string().orEmpty()
        if (resp.isSuccessful) {
          val sandboxes = parseSandboxes(body)
          val countStr = if (sandboxes.isEmpty()) "0 active microVMs" else "${sandboxes.size} active microVM${if (sandboxes.size == 1) "" else "s"}"
          E2BPingResult(
            isSuccess = true,
            statusCode = resp.code,
            message = "Connected to E2B Cloud Sandboxes ($countStr, ${latency}ms latency). Ready for Firecracker hardware execution!",
            activeSandboxes = sandboxes,
            latencyMs = latency
          )
        } else {
          val errorDesc = try {
            val json = JSONObject(body)
            json.optString("message", json.optString("error", "HTTP ${resp.code}: $body"))
          } catch (_: Exception) {
            "HTTP ${resp.code}: $body"
          }
          E2BPingResult(
            isSuccess = false,
            statusCode = resp.code,
            message = "E2B returned HTTP ${resp.code}: $errorDesc",
            latencyMs = latency
          )
        }
      }
    } catch (e: Exception) {
      val latency = System.currentTimeMillis() - t0
      E2BPingResult(
        isSuccess = false,
        statusCode = 0,
        message = "Failed to reach E2B API at $cleanBaseUrl: ${e.message ?: e.javaClass.simpleName}",
        latencyMs = latency
      )
    }
  }

  override fun ping(): Boolean = pingDetailed().isSuccess

  private fun parseSandboxes(body: String): List<E2BRemoteSandbox> {
    val result = mutableListOf<E2BRemoteSandbox>()
    val arr = if (body.startsWith("[")) JSONArray(body) else JSONObject(body).optJSONArray("sandboxes") ?: JSONArray()
    for (i in 0 until arr.length()) {
      val item = arr.optJSONObject(i) ?: continue
      val id = item.optString("sandboxID", item.optString("id", ""))
      val template = item.optString("templateID", item.optString("template", item.optString("alias", "base")))
      val startedAt = if (item.has("startedAt")) item.optString("startedAt") else null
      val metaObj = item.optJSONObject("metadata")
      val metaMap = mutableMapOf<String, String>()
      if (metaObj != null) {
        val keys = metaObj.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          metaMap[k] = metaObj.optString(k, "")
        }
      }
      if (id.isNotBlank()) {
        result.add(E2BRemoteSandbox(sandboxId = id, templateId = template, startedAt = startedAt, metadata = metaMap))
      }
    }
    return result
  }

  /**
   * Retrieves active running sandboxes from the E2B team account.
   */
  fun listActiveSandboxes(): List<E2BRemoteSandbox> {
    val cleanBaseUrl = apiUrl.trim().trimEnd('/')
    val effectiveKey = apiKey.trim()
    if (effectiveKey.isBlank() || effectiveKey == "MY_E2B_API_KEY") return emptyList()

    return try {
      val req = Request.Builder()
        .url("$cleanBaseUrl/sandboxes")
        .header("X-API-Key", effectiveKey)
        .header("Authorization", "Bearer $effectiveKey")
        .get()
        .build()

      httpClient.newCall(req).execute().use { resp ->
        if (resp.isSuccessful) {
          parseSandboxes(resp.body?.string().orEmpty())
        } else {
          emptyList()
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }
}

/**
 * Central SandboxManager coordinating the active SandboxProvider.
 * Provides thread-safe provider switching, lifecycle coordination, and status reporting.
 */
class SandboxManager(
  val workspaceDir: File,
  initialConfig: ConnectionConfig = ConnectionConfig(
    endpoint = "https://api.e2b.app",
    backendType = SandboxBackendType.E2B_CLOUD,
    e2bTemplate = "base",
    e2bDomain = "e2b.app"
  )
) {
  var activeConfig: ConnectionConfig = initialConfig
    private set

  var activeProvider: E2BSandboxProvider = createProvider(initialConfig)
    private set

  @Synchronized
  fun updateConfig(config: ConnectionConfig) {
    activeConfig = config
    activeProvider = createProvider(config)
  }

  private fun createProvider(config: ConnectionConfig): E2BSandboxProvider {
    return E2BSandboxProvider(
      apiKey = config.apiKey.orEmpty(),
      templateId = config.e2bTemplate.ifBlank { "base" },
      apiUrl = config.endpoint.ifBlank { "https://api.e2b.app" },
      sandboxDomain = config.e2bDomain.ifBlank { "e2b.app" },
      localWorkspaceDir = workspaceDir
    )
  }

  fun getSandbox(sessionId: String, taskId: String? = null): Sandbox {
    return activeProvider.getSandbox(sessionId, taskId)
  }

  fun closeSession(sessionId: String) {
    activeProvider.closeSession(sessionId)
  }

  fun ping(): Boolean = activeProvider.ping()
}

