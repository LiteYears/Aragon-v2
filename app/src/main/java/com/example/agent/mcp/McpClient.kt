package com.example.agent.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Production-ready MCP client implementation compliant with Model Context Protocol (2024-11-05).
 * Encapsulates initialization handshake, capability negotiation, tool/resource/prompt discovery,
 * dynamic change notifications, reconnect logic, and tool invocation.
 */
class McpClient(
  val config: McpServerConfig,
  private val workspaceDir: java.io.File? = null,
  private val onLogMessage: ((McpLogMessage) -> Unit)? = null,
  private val onToolsChanged: (suspend (McpClient) -> Unit)? = null
) {
  private val scope = CoroutineScope(Dispatchers.IO)
  private val mutex = Mutex()

  private val _status = MutableStateFlow(if (config.enabled) McpServerStatus.DISCONNECTED else McpServerStatus.DISABLED)
  val status: StateFlow<McpServerStatus> = _status.asStateFlow()

  private val _serverInfo = MutableStateFlow<McpServerInfo?>(null)
  val serverInfo: StateFlow<McpServerInfo?> = _serverInfo.asStateFlow()

  private val _tools = MutableStateFlow<List<McpToolDefinition>>(emptyList())
  val tools: StateFlow<List<McpToolDefinition>> = _tools.asStateFlow()

  private val _resources = MutableStateFlow<List<McpResourceDefinition>>(emptyList())
  val resources: StateFlow<List<McpResourceDefinition>> = _resources.asStateFlow()

  private val _prompts = MutableStateFlow<List<McpPromptDefinition>>(emptyList())
  val prompts: StateFlow<List<McpPromptDefinition>> = _prompts.asStateFlow()

  private val _lastError = MutableStateFlow<String?>(null)
  val lastError: StateFlow<String?> = _lastError.asStateFlow()

  private var transport: McpTransport? = null
  private var session: McpJsonRpcSession? = null
  private var reconnectJob: Job? = null
  private var reconnectAttempts = 0

  /**
   * Connects to the server, completes initialize handshake, and discovers capabilities.
   */
  suspend fun connect(): Result<Unit> = mutex.withLock {
    withContext(Dispatchers.IO) {
      if (!config.enabled) {
        _status.value = McpServerStatus.DISABLED
        return@withContext Result.failure(IllegalStateException("Server '${config.id}' is disabled"))
      }

      cleanupInternal()
      _status.value = McpServerStatus.CONNECTING
      _lastError.value = null

      try {
        val t = when (config.transport) {
          McpTransportType.HTTP -> HttpMcpTransport(config)
          McpTransportType.SSE -> SseMcpTransport(config)
          McpTransportType.STDIO -> StdioMcpTransport(config)
          McpTransportType.INTERNAL -> InternalMcpTransport(config, workspaceDir ?: java.io.File("."))
        }
        transport = t

        val s = McpJsonRpcSession(config.id) { messageJson ->
          t.send(messageJson)
        }
        session = s

        // Setup notification listeners
        s.onNotification("notifications/tools/list_changed") {
          scope.launch {
            try {
              refreshToolsInternal()
              onToolsChanged?.invoke(this@McpClient)
            } catch (_: Exception) {}
          }
        }

        s.onNotification("notifications/resources/list_changed") {
          scope.launch {
            try { refreshResourcesInternal() } catch (_: Exception) {}
          }
        }

        s.onNotification("notifications/prompts/list_changed") {
          scope.launch {
            try { refreshPromptsInternal() } catch (_: Exception) {}
          }
        }

        s.onNotification("notifications/message") { params ->
          if (params != null) {
            val level = McpLogLevel.fromString(params.optString("level"))
            val logger = params.optString("logger").ifBlank { null }
            val data = params.optString("data", params.toString())
            val logMsg = McpLogMessage(
              serverId = config.id,
              level = level,
              logger = logger,
              data = data
            )
            onLogMessage?.invoke(logMsg)
          }
        }

        // Start transport
        t.start(
          onMessage = { msg -> s.handleIncoming(msg) },
          onClose = { err -> handleTransportClosed(err) }
        )

        _status.value = McpServerStatus.INITIALIZING

        // Perform MCP Initialize Handshake
        val initParams = JSONObject().apply {
          put("protocolVersion", "2024-11-05")
          val caps = JSONObject().apply {
            put("tools", JSONObject().apply { put("listChanged", true) })
            put("resources", JSONObject().apply {
              put("subscribe", true)
              put("listChanged", true)
            })
            put("prompts", JSONObject().apply { put("listChanged", true) })
            put("logging", JSONObject())
          }
          put("capabilities", caps)
          val clientInfo = JSONObject().apply {
            put("name", "Aragon Agent MCP Client")
            put("version", "2.0.0")
          }
          put("clientInfo", clientInfo)
        }

        val initResult = s.request("initialize", initParams, timeoutMs = config.timeoutSeconds * 1000L)
        val protoVer = initResult.optString("protocolVersion", "2024-11-05")
        val sInfoObj = initResult.optJSONObject("serverInfo")
        val serverName = sInfoObj?.optString("name", config.name) ?: config.name
        val serverVer = sInfoObj?.optString("version", "1.0.0") ?: "1.0.0"

        val sCapsObj = initResult.optJSONObject("capabilities") ?: JSONObject()
        val capabilities = McpServerCapabilities(
          hasTools = sCapsObj.has("tools"),
          toolsListChanged = sCapsObj.optJSONObject("tools")?.optBoolean("listChanged") ?: false,
          hasResources = sCapsObj.has("resources"),
          resourcesSubscribe = sCapsObj.optJSONObject("resources")?.optBoolean("subscribe") ?: false,
          resourcesListChanged = sCapsObj.optJSONObject("resources")?.optBoolean("listChanged") ?: false,
          hasPrompts = sCapsObj.has("prompts"),
          promptsListChanged = sCapsObj.optJSONObject("prompts")?.optBoolean("listChanged") ?: false,
          hasLogging = sCapsObj.has("logging")
        )

        _serverInfo.value = McpServerInfo(
          name = serverName,
          version = serverVer,
          protocolVersion = protoVer,
          capabilities = capabilities
        )

        // Confirm initialization
        s.notify("notifications/initialized")

        // Discover tools, resources, and prompts gracefully
        try {
          refreshToolsInternal()
        } catch (e: Exception) {
          onLogMessage?.invoke(McpLogMessage(config.id, McpLogLevel.WARNING, "discovery", "Tools discovery note: ${e.message}"))
        }

        try {
          if (capabilities.hasResources) {
            refreshResourcesInternal()
          }
        } catch (_: Exception) {}

        try {
          if (capabilities.hasPrompts) {
            refreshPromptsInternal()
          }
        } catch (_: Exception) {}

        _status.value = McpServerStatus.CONNECTED
        reconnectAttempts = 0
        Result.success(Unit)
      } catch (e: Exception) {
        val errMsg = "Failed to connect to MCP server '${config.id}': ${e.message}"
        _lastError.value = errMsg
        _status.value = McpServerStatus.ERROR
        cleanupInternal()
        scheduleReconnectIfAllowed()
        Result.failure(e)
      }
    }
  }

  /**
   * Disconnects the MCP client gracefully.
   */
  suspend fun disconnect() = mutex.withLock {
    reconnectJob?.cancel()
    reconnectJob = null
    cleanupInternal()
    _status.value = if (config.enabled) McpServerStatus.DISCONNECTED else McpServerStatus.DISABLED
  }

  private fun handleTransportClosed(error: Throwable?) {
    scope.launch {
      if (_status.value != McpServerStatus.DISABLED && _status.value != McpServerStatus.DISCONNECTED) {
        if (error != null) {
          _lastError.value = "Connection lost: ${error.message}"
          _status.value = McpServerStatus.ERROR
        } else {
          _status.value = McpServerStatus.DISCONNECTED
        }
        cleanupInternal()
        scheduleReconnectIfAllowed()
      }
    }
  }

  private fun scheduleReconnectIfAllowed() {
    if (!config.enabled || !config.autoReconnect) return
    if (reconnectJob?.isActive == true) return

    val backoffDelay = (1000L * (1L shl reconnectAttempts.coerceAtMost(5))).coerceAtMost(30000L)
    reconnectAttempts++

    reconnectJob = scope.launch {
      delay(backoffDelay)
      if (config.enabled && _status.value != McpServerStatus.CONNECTED) {
        connect()
      }
    }
  }

  private suspend fun cleanupInternal() {
    try {
      session?.close()
    } catch (_: Exception) {}
    session = null

    try {
      transport?.close()
    } catch (_: Exception) {}
    transport = null
  }

  /**
   * Refreshes tools from the server using tools/list.
   */
  suspend fun refreshTools(): List<McpToolDefinition> = mutex.withLock {
    refreshToolsInternal()
  }

  private suspend fun refreshToolsInternal(): List<McpToolDefinition> {
    val s = session ?: return emptyList()
    val toolList = mutableListOf<McpToolDefinition>()
    var cursor: String? = null

    do {
      val params = JSONObject()
      if (!cursor.isNullOrBlank()) {
        params.put("cursor", cursor)
      }
      val res = s.request("tools/list", params, timeoutMs = config.timeoutSeconds * 1000L)
      val toolsArr = res.optJSONArray("tools") ?: JSONArray()
      cursor = res.optString("nextCursor").ifBlank { null }

      for (i in 0 until toolsArr.length()) {
        val item = toolsArr.optJSONObject(i) ?: continue
        val name = item.optString("name")
        if (name.isBlank()) continue

        val desc = item.optString("description").ifBlank { null }
        val schema = item.optJSONObject("inputSchema") ?: JSONObject().apply {
          put("type", "object")
          put("properties", JSONObject())
        }
        val nsName = "${config.effectiveNamespace}__$name"

        toolList.add(
          McpToolDefinition(
            name = name,
            description = desc,
            inputSchema = schema,
            serverId = config.id,
            namespacedName = nsName
          )
        )
      }
    } while (!cursor.isNullOrBlank())

    _tools.value = toolList
    return toolList
  }

  /**
   * Refreshes resources from the server using resources/list.
   */
  suspend fun refreshResources(): List<McpResourceDefinition> = mutex.withLock {
    refreshResourcesInternal()
  }

  private suspend fun refreshResourcesInternal(): List<McpResourceDefinition> {
    val s = session ?: return emptyList()
    val resList = mutableListOf<McpResourceDefinition>()
    var cursor: String? = null

    do {
      val params = JSONObject()
      if (!cursor.isNullOrBlank()) {
        params.put("cursor", cursor)
      }
      val res = s.request("resources/list", params, timeoutMs = config.timeoutSeconds * 1000L)
      val arr = res.optJSONArray("resources") ?: JSONArray()
      cursor = res.optString("nextCursor").ifBlank { null }

      for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val uri = item.optString("uri")
        if (uri.isBlank()) continue

        val name = item.optString("name", uri)
        val desc = item.optString("description").ifBlank { null }
        val mimeType = item.optString("mimeType").ifBlank { null }

        resList.add(
          McpResourceDefinition(
            uri = uri,
            name = name,
            description = desc,
            mimeType = mimeType,
            serverId = config.id
          )
        )
      }
    } while (!cursor.isNullOrBlank())

    _resources.value = resList
    return resList
  }

  /**
   * Refreshes prompts from the server using prompts/list.
   */
  suspend fun refreshPrompts(): List<McpPromptDefinition> = mutex.withLock {
    refreshPromptsInternal()
  }

  private suspend fun refreshPromptsInternal(): List<McpPromptDefinition> {
    val s = session ?: return emptyList()
    val promptList = mutableListOf<McpPromptDefinition>()
    var cursor: String? = null

    do {
      val params = JSONObject()
      if (!cursor.isNullOrBlank()) {
        params.put("cursor", cursor)
      }
      val res = s.request("prompts/list", params, timeoutMs = config.timeoutSeconds * 1000L)
      val arr = res.optJSONArray("prompts") ?: JSONArray()
      cursor = res.optString("nextCursor").ifBlank { null }

      for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val name = item.optString("name")
        if (name.isBlank()) continue

        val desc = item.optString("description").ifBlank { null }
        val argsArr = item.optJSONArray("arguments")
        val args = mutableListOf<McpPromptArgDefinition>()
        if (argsArr != null) {
          for (j in 0 until argsArr.length()) {
            val a = argsArr.optJSONObject(j) ?: continue
            val aName = a.optString("name")
            if (aName.isNotBlank()) {
              args.add(
                McpPromptArgDefinition(
                  name = aName,
                  description = a.optString("description").ifBlank { null },
                  required = a.optBoolean("required", false)
                )
              )
            }
          }
        }

        promptList.add(
          McpPromptDefinition(
            name = name,
            description = desc,
            arguments = args,
            serverId = config.id
          )
        )
      }
    } while (!cursor.isNullOrBlank())

    _prompts.value = promptList
    return promptList
  }

  /**
   * Calls a tool exposed by this MCP server using tools/call.
   */
  suspend fun callTool(name: String, arguments: Map<String, Any?>): Result<McpToolCallResult> = withContext(Dispatchers.IO) {
    val s = session ?: return@withContext Result.failure(IOException("Server '${config.id}' is not connected"))

    try {
      val argsObj = JSONObject()
      for ((k, v) in arguments) {
        argsObj.put(k, kotlinToJsonValue(v))
      }

      val params = JSONObject().apply {
        put("name", name)
        put("arguments", argsObj)
      }

      val resultObj = s.request("tools/call", params, timeoutMs = config.timeoutSeconds * 1000L)
      val isError = resultObj.optBoolean("isError", false)
      val contentArr = resultObj.optJSONArray("content") ?: JSONArray()

      val items = mutableListOf<McpContentItem>()
      val sb = StringBuilder()

      for (i in 0 until contentArr.length()) {
        val item = contentArr.optJSONObject(i) ?: continue
        val type = item.optString("type")
        when (type) {
          "text" -> {
            val txt = item.optString("text", "")
            items.add(McpContentItem.Text(txt))
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(txt)
          }
          "image" -> {
            val data = item.optString("data", "")
            val mime = item.optString("mimeType", "image/png")
            items.add(McpContentItem.Image(data, mime))
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("[Image: $mime, ${data.length} bytes base64]")
          }
          "resource" -> {
            val resObj = item.optJSONObject("resource")
            val uri = resObj?.optString("uri", "") ?: ""
            val mime = resObj?.optString("mimeType")
            val txt = resObj?.optString("text")
            items.add(McpContentItem.Resource(uri, mime, txt))
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("[Resource: $uri (${mime ?: "unknown"})]")
          }
        }
      }

      Result.success(
        McpToolCallResult(
          isError = isError,
          content = items,
          rawText = sb.toString()
        )
      )
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * Reads a resource by URI using resources/read.
   */
  suspend fun readResource(uri: String): Result<McpResourceContent> = withContext(Dispatchers.IO) {
    val s = session ?: return@withContext Result.failure(IOException("Server '${config.id}' is not connected"))

    try {
      val params = JSONObject().apply {
        put("uri", uri)
      }
      val res = s.request("resources/read", params, timeoutMs = config.timeoutSeconds * 1000L)
      val contentsArr = res.optJSONArray("contents")
      val first = contentsArr?.optJSONObject(0)
        ?: return@withContext Result.failure(IOException("No resource contents returned for $uri"))

      Result.success(
        McpResourceContent(
          uri = first.optString("uri", uri),
          mimeType = first.optString("mimeType").ifBlank { null },
          text = first.optString("text").ifBlank { null },
          blob = first.optString("blob").ifBlank { null }
        )
      )
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * Retrieves a prompt template by name using prompts/get.
   */
  suspend fun getPrompt(name: String, arguments: Map<String, String>): Result<List<McpPromptMessage>> = withContext(Dispatchers.IO) {
    val s = session ?: return@withContext Result.failure(IOException("Server '${config.id}' is not connected"))

    try {
      val argsObj = JSONObject()
      arguments.forEach { (k, v) -> argsObj.put(k, v) }

      val params = JSONObject().apply {
        put("name", name)
        put("arguments", argsObj)
      }

      val res = s.request("prompts/get", params, timeoutMs = config.timeoutSeconds * 1000L)
      val msgsArr = res.optJSONArray("messages") ?: JSONArray()
      val list = mutableListOf<McpPromptMessage>()

      for (i in 0 until msgsArr.length()) {
        val m = msgsArr.optJSONObject(i) ?: continue
        val role = m.optString("role", "user")
        val contentObj = m.optJSONObject("content")
        val text = contentObj?.optString("text") ?: m.optString("content")
        list.add(McpPromptMessage(role, text))
      }

      Result.success(list)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * Pings the MCP server and returns the round-trip latency in milliseconds.
   */
  suspend fun ping(): Result<Long> = withContext(Dispatchers.IO) {
    val s = session ?: return@withContext Result.failure(IOException("Server '${config.id}' is not connected"))
    val startTime = System.currentTimeMillis()
    try {
      s.request("ping", JSONObject(), timeoutMs = 5000L)
      val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
      Result.success(latency)
    } catch (_: Exception) {
      try {
        val fbStart = System.currentTimeMillis()
        s.request("tools/list", JSONObject(), timeoutMs = 5000L)
        val latency = (System.currentTimeMillis() - fbStart).coerceAtLeast(1L)
        Result.success(latency)
      } catch (e2: Exception) {
        Result.failure(e2)
      }
    }
  }

  /**
   * Forces a clean reconnect cycle for this MCP server.
   */
  suspend fun reconnect(): Result<Unit> {
    disconnect()
    return connect()
  }

  private fun kotlinToJsonValue(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> {
      val obj = JSONObject()
      for ((k, v) in value) {
        if (k != null) obj.put(k.toString(), kotlinToJsonValue(v))
      }
      obj
    }
    is Collection<*> -> {
      val arr = JSONArray()
      for (item in value) {
        arr.put(kotlinToJsonValue(item))
      }
      arr
    }
    else -> value
  }
}
