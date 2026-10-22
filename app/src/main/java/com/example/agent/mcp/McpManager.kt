package com.example.agent.mcp

import android.content.Context
import com.example.agent.core.EnvironmentIdentity
import com.example.agent.core.ExecutionEnvironmentType
import com.example.agent.core.Tool
import com.example.agent.core.ToolCategory
import com.example.agent.core.ToolErrorCode
import com.example.agent.core.ToolParameter
import com.example.agent.core.ToolPermission
import com.example.agent.core.ToolRegistry
import com.example.agent.core.ToolResult
import com.example.agent.core.ToolSchema
import com.example.agent.core.ToolStatus
import com.example.agent.core.ToolTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Central orchestrator and registry for Model Context Protocol (MCP) in Aragon.
 *
 * Responsibilities:
 * - Config-driven server lifecycle (STDIO, SSE, and HTTP transports).
 * - Multi-server coordination with namespacing.
 * - Dynamic discovery and registration/unregistration of tools in [ToolRegistry].
 * - Handling resources, prompts, logs, timeouts, and automatic reconnection.
 * - Persistent configuration storage in 'mcp_servers.json'.
 * - Exposing agent-facing tools for dynamic server control and resource reading.
 */
class McpManager(
  private val appContext: Context,
  private val workspaceDir: File,
  private val toolRegistry: ToolRegistry
) {
  private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
  private val clients = ConcurrentHashMap<String, McpClient>()
  private val registeredToolNamesByServer = ConcurrentHashMap<String, MutableSet<String>>()

  private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
  val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

  private val _logs = MutableStateFlow<List<McpLogMessage>>(emptyList())
  val logs: StateFlow<List<McpLogMessage>> = _logs.asStateFlow()

  private val _discoveredTools = MutableStateFlow<List<McpToolDefinition>>(emptyList())
  val discoveredTools: StateFlow<List<McpToolDefinition>> = _discoveredTools.asStateFlow()

  private val _discoveredResources = MutableStateFlow<List<McpResourceDefinition>>(emptyList())
  val discoveredResources: StateFlow<List<McpResourceDefinition>> = _discoveredResources.asStateFlow()

  private val _discoveredPrompts = MutableStateFlow<List<McpPromptDefinition>>(emptyList())
  val discoveredPrompts: StateFlow<List<McpPromptDefinition>> = _discoveredPrompts.asStateFlow()

  private val _isRefreshing = MutableStateFlow(false)
  val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

  private val configFile: File
    get() = File(workspaceDir, "mcp_servers.json").takeIf { it.parentFile?.exists() == true }
      ?: File(appContext.filesDir, "mcp_servers.json")

  init {
    registerMcpManagementTools()
  }

  /**
   * Initializes MCP manager: loads saved configs and establishes connections.
   */
  fun initialize() {
    scope.launch {
      loadConfiguration()
      connectAllEnabledServers()
    }
  }

  /**
   * Shuts down all active MCP clients and unregisters tools.
   */
  fun shutdown() {
    scope.launch {
      clients.values.forEach { it.disconnect() }
      clients.clear()
      registeredToolNamesByServer.values.flatten().forEach { toolRegistry.unregister(it) }
      registeredToolNamesByServer.clear()
    }
  }

  /**
   * Loads server configurations from disk. If no config exists, populates with
   * curated templates (disabled by default so user/agent can enable them).
   */
  suspend fun loadConfiguration() = withContext(Dispatchers.IO) {
    val file = configFile
    val configs = mutableListOf<McpServerConfig>()

    if (file.exists()) {
      try {
        val jsonStr = file.readText()
        val root = JSONObject(jsonStr)
        val serversObj = root.optJSONObject("mcpServers") ?: root
        val keys = serversObj.keys()
        while (keys.hasNext()) {
          val key = keys.next()
          val sObj = serversObj.optJSONObject(key)
          if (sObj != null) {
            if (!sObj.has("id")) sObj.put("id", key)
            configs.add(McpServerConfig.fromJson(sObj))
          }
        }
      } catch (_: Exception) {}
    }

    // Filter out removed DuckDuckGo server
    configs.removeAll { it.id == "duckduckgo" || it.url.contains("duckduckgo") }

    if (configs.isEmpty()) {
      configs.addAll(getStarterTemplates())
      saveConfigurationInternal(configs)
    } else {
      // Ensure all native zero-config internal tools are present and enabled
      val existingIds = configs.map { it.id }.toSet()
      val missingDefaults = getStarterTemplates().filter { it.transport == McpTransportType.INTERNAL && it.id !in existingIds }
      if (missingDefaults.isNotEmpty()) {
        configs.addAll(missingDefaults)
      }
      // Ensure all native internal MCP servers are active
      val normalized = configs.map {
        if (it.transport == McpTransportType.INTERNAL || it.url.startsWith("internal://")) {
          it.copy(enabled = true, isNative = true)
        } else it
      }
      configs.clear()
      configs.addAll(normalized)
      saveConfigurationInternal(configs)
    }

    _servers.value = configs
  }

  /**
   * Saves current server configurations to disk.
   */
  suspend fun saveConfiguration() = withContext(Dispatchers.IO) {
    saveConfigurationInternal(_servers.value)
  }

  private fun saveConfigurationInternal(configs: List<McpServerConfig>) {
    try {
      val root = JSONObject()
      val serversObj = JSONObject()
      for (cfg in configs) {
        serversObj.put(cfg.id, cfg.toJson())
      }
      root.put("mcpServers", serversObj)
      configFile.writeText(root.toString(2))
    } catch (_: Exception) {}
  }

  /**
   * Adds or updates an MCP server configuration.
   */
  suspend fun upsertServer(config: McpServerConfig) = withContext(Dispatchers.IO) {
    val current = _servers.value.toMutableList()
    val idx = current.indexOfFirst { it.id == config.id }
    if (idx >= 0) {
      current[idx] = config
    } else {
      current.add(config)
    }
    _servers.value = current
    saveConfiguration()

    // Reconnect or update client
    val existingClient = clients[config.id]
    if (existingClient != null) {
      existingClient.disconnect()
      clients.remove(config.id)
      unregisterServerTools(config.id)
    }

    if (config.enabled) {
      connectServer(config)
    }
  }

  /**
   * Removes an MCP server by ID.
   */
  suspend fun removeServer(serverId: String) = withContext(Dispatchers.IO) {
    val current = _servers.value.toMutableList()
    current.removeAll { it.id == serverId }
    _servers.value = current
    saveConfiguration()

    val client = clients.remove(serverId)
    client?.disconnect()
    unregisterServerTools(serverId)
    updateAggregatedState()
  }

  /**
   * Toggles the enabled state of an MCP server.
   */
  suspend fun toggleServer(serverId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
    val cfg = _servers.value.find { it.id == serverId } ?: return@withContext
    val updated = cfg.copy(enabled = enabled)
    upsertServer(updated)
  }

  /**
   * Refreshes all active MCP servers and re-discovers capabilities.
   */
  suspend fun refreshAll(): Result<String> = withContext(Dispatchers.IO) {
    _isRefreshing.value = true
    try {
      val connectedCount = clients.values.count { it.status.value == McpServerStatus.CONNECTED }
      for (client in clients.values) {
        if (client.config.enabled) {
          if (client.status.value != McpServerStatus.CONNECTED) {
            client.connect()
          } else {
            client.refreshTools()
            client.refreshResources()
            client.refreshPrompts()
          }
          syncServerToolsToRegistry(client)
        }
      }
      updateAggregatedState()
      val msg = "Refreshed ${clients.size} MCP servers. Discovered ${_discoveredTools.value.size} tools, ${_discoveredResources.value.size} resources, and ${_discoveredPrompts.value.size} prompts."
      Result.success(msg)
    } catch (e: Exception) {
      Result.failure(e)
    } finally {
      _isRefreshing.value = false
    }
  }

  /**
   * Retrieves an MCP client by server ID.
   */
  fun getClient(serverId: String): McpClient? = clients[serverId]

  /**
   * Pings a specific MCP server and returns round-trip latency in milliseconds.
   */
  suspend fun pingServer(serverId: String): Result<Long> {
    val client = clients[serverId] ?: return Result.failure(IllegalStateException("Server '$serverId' not active or connected"))
    return client.ping()
  }

  /**
   * Forces reconnect for a specific MCP server.
   */
  suspend fun reconnectServer(serverId: String): Result<Unit> {
    val config = _servers.value.find { it.id == serverId }
      ?: return Result.failure(IllegalStateException("Server '$serverId' configuration not found"))
    val existing = clients[serverId]
    if (existing != null) {
      existing.disconnect()
      clients.remove(serverId)
      unregisterServerTools(serverId)
    }
    if (config.enabled) {
      connectServer(config)
    }
    return Result.success(Unit)
  }

  /**
   * Clears accumulated MCP protocol and diagnostic logs.
   */
  fun clearLogs() {
    _logs.value = emptyList()
  }

  private suspend fun connectAllEnabledServers() {
    for (config in _servers.value) {
      if (config.enabled) {
        connectServer(config)
      }
    }
  }

  private suspend fun connectServer(config: McpServerConfig) {
    val client = McpClient(
      config = config,
      workspaceDir = workspaceDir,
      onLogMessage = { logMsg ->
        _logs.value = (listOf(logMsg) + _logs.value).take(200)
      },
      onToolsChanged = { updatedClient ->
        syncServerToolsToRegistry(updatedClient)
        updateAggregatedState()
      }
    )

    clients[config.id] = client
    scope.launch {
      val res = client.connect()
      if (res.isSuccess) {
        syncServerToolsToRegistry(client)
      }
      updateAggregatedState()
    }
  }

  private fun syncServerToolsToRegistry(client: McpClient) {
    val serverId = client.config.id
    val registered = registeredToolNamesByServer.getOrPut(serverId) { mutableSetOf() }

    // Remove old registered tools for this server
    registered.forEach { toolRegistry.unregister(it) }
    registered.clear()

    // Register active tools, skipping shadow tools that redundantly duplicate native filesystem tools
    val duplicateNativeToolNames = setOf(
      "filesystem__read_file",
      "filesystem__write_file",
      "filesystem__list_directory",
      "filesystem__edit_file",
      "filesystem__get_file_info"
    )
    for (def in client.tools.value) {
      val bridgedTool = McpBridgedTool(client, def)
      if (bridgedTool.name in duplicateNativeToolNames) {
        continue
      }
      toolRegistry.register(bridgedTool)
      registered.add(bridgedTool.name)
    }
  }

  private fun unregisterServerTools(serverId: String) {
    val registered = registeredToolNamesByServer.remove(serverId) ?: return
    registered.forEach { toolRegistry.unregister(it) }
  }

  private fun updateAggregatedState() {
    val allTools = clients.values.flatMap { it.tools.value }
    val allResources = clients.values.flatMap { it.resources.value }
    val allPrompts = clients.values.flatMap { it.prompts.value }
    _discoveredTools.value = allTools
    _discoveredResources.value = allResources
    _discoveredPrompts.value = allPrompts
  }

  /**
   * Agent-facing built-in tools for MCP dynamic discovery and operation.
   */
  private fun registerMcpManagementTools() {
    // 1. mcp_list_servers
    toolRegistry.register(object : Tool {
      override val name: String = "mcp_list_servers"
      override val description: String = "List all configured MCP servers, connection status, transport type, and tool counts."
      override val schema: ToolSchema = ToolSchema(emptyList())
      override val category: ToolCategory = ToolCategory.UTILITY
      override val target: ToolTarget = ToolTarget.HOST_NETWORK
      override val permissions: Set<ToolPermission> = emptySet()
      override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.MCP_BRIDGE
      override val environmentIdentity: EnvironmentIdentity = EnvironmentIdentity(
        type = owningBackend,
        name = "MCP Subsystem",
        sessionId = "mcp",
        workingDirectory = "/mcp"
      )

      override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
        val serverConfigs = _servers.value
        val list = JSONArray()
        for (cfg in serverConfigs) {
          val client = clients[cfg.id]
          val obj = JSONObject().apply {
            put("id", cfg.id)
            put("name", cfg.name)
            put("transport", cfg.transport.name)
            put("status", client?.status?.value?.name ?: (if (cfg.enabled) "DISCONNECTED" else "DISABLED"))
            put("toolCount", client?.tools?.value?.size ?: 0)
            put("resourceCount", client?.resources?.value?.size ?: 0)
            put("promptCount", client?.prompts?.value?.size ?: 0)
            put("url", cfg.url.ifBlank { cfg.command })
            put("enabled", cfg.enabled)
          }
          list.put(obj)
        }
        val out = list.toString(2)
        return ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = out,
          error = null,
          duration = 5L,
          stdout = out
        )
      }
    })

    // 2. mcp_manage_server
    toolRegistry.register(object : Tool {
      override val name: String = "mcp_manage_server"
      override val description: String = "Add, update, enable, disable, or remove an MCP server configuration."
      override val schema: ToolSchema = ToolSchema(
        listOf(
          ToolParameter("action", "string", "Action to perform: 'add', 'update', 'enable', 'disable', 'delete', 'refresh'", required = true, enumValues = listOf("add", "update", "enable", "disable", "delete", "refresh")),
          ToolParameter("id", "string", "Unique server ID (e.g. 'github', 'postgres', 'sqlite')", required = true),
          ToolParameter("name", "string", "Human readable name for the server", required = false),
          ToolParameter("transport", "string", "Transport type: 'HTTP', 'SSE', or 'STDIO'", required = false, enumValues = listOf("HTTP", "SSE", "STDIO")),
          ToolParameter("url", "string", "Server URL (for HTTP or SSE)", required = false),
          ToolParameter("command", "string", "Process command (for STDIO)", required = false),
          ToolParameter("args", "string", "Comma-separated arguments or JSON array string for STDIO command", required = false),
          ToolParameter("headers", "string", "JSON object string of HTTP headers (e.g. '{\"Authorization\": \"Bearer ...\"}')", required = false)
        )
      )
      override val category: ToolCategory = ToolCategory.UTILITY
      override val target: ToolTarget = ToolTarget.HOST_NETWORK
      override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK_ACCESS)
      override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD
      override val environmentIdentity: EnvironmentIdentity = EnvironmentIdentity(
        type = owningBackend,
        name = "MCP Subsystem",
        sessionId = "mcp",
        workingDirectory = "/mcp"
      )

      override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
        val action = arguments["action"]?.toString()?.lowercase() ?: "refresh"
        val id = arguments["id"]?.toString()?.trim() ?: ""

        if (id.isBlank()) {
          return ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = "Missing required 'id' parameter", errorType = ToolErrorCode.INVALID_ARGUMENTS, duration = 5L)
        }

        return try {
          when (action) {
            "enable" -> {
              toggleServer(id, true)
              ToolResult(callId = callId, toolName = name, status = ToolStatus.SUCCEEDED, arguments = arguments, output = "Server '$id' enabled and connecting.", error = null, duration = 10L)
            }
            "disable" -> {
              toggleServer(id, false)
              ToolResult(callId = callId, toolName = name, status = ToolStatus.SUCCEEDED, arguments = arguments, output = "Server '$id' disabled.", error = null, duration = 10L)
            }
            "delete" -> {
              removeServer(id)
              ToolResult(callId = callId, toolName = name, status = ToolStatus.SUCCEEDED, arguments = arguments, output = "Server '$id' removed.", error = null, duration = 10L)
            }
            "refresh" -> {
              val client = clients[id] ?: return ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = "Server '$id' not found or active", errorType = ToolErrorCode.TOOL_NOT_FOUND, duration = 5L)
              client.refreshTools()
              client.refreshResources()
              client.refreshPrompts()
              syncServerToolsToRegistry(client)
              updateAggregatedState()
              ToolResult(callId = callId, toolName = name, status = ToolStatus.SUCCEEDED, arguments = arguments, output = "Refreshed server '$id' (${client.tools.value.size} tools).", error = null, duration = 10L)
            }
            "add", "update" -> {
              val sName = arguments["name"]?.toString()?.ifBlank { null } ?: id
              val transportStr = arguments["transport"]?.toString()?.uppercase() ?: "HTTP"
              val transport = try { McpTransportType.valueOf(transportStr) } catch (_: Exception) { McpTransportType.HTTP }
              val url = arguments["url"]?.toString() ?: ""
              val command = arguments["command"]?.toString() ?: ""

              val argsList = mutableListOf<String>()
              val rawArgs = arguments["args"]?.toString()
              if (!rawArgs.isNullOrBlank()) {
                if (rawArgs.trim().startsWith("[")) {
                  val arr = JSONArray(rawArgs)
                  for (i in 0 until arr.length()) argsList.add(arr.optString(i))
                } else {
                  rawArgs.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { argsList.add(it) }
                }
              }

              val headersMap = mutableMapOf<String, String>()
              val rawHeaders = arguments["headers"]?.toString()
              if (!rawHeaders.isNullOrBlank() && rawHeaders.trim().startsWith("{")) {
                val hObj = JSONObject(rawHeaders)
                hObj.keys().forEach { k -> headersMap[k] = hObj.optString(k) }
              }

              val config = McpServerConfig(
                id = id,
                name = sName,
                transport = transport,
                url = url,
                command = command,
                args = argsList,
                headers = headersMap,
                enabled = true
              )
              upsertServer(config)
              ToolResult(callId = callId, toolName = name, status = ToolStatus.SUCCEEDED, arguments = arguments, output = "Configured MCP server '$id' ($transportStr).", error = null, duration = 20L)
            }
            else -> ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = "Unknown action '$action'", errorType = ToolErrorCode.INVALID_ARGUMENTS, duration = 5L)
          }
        } catch (e: Exception) {
          ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = e.message, errorType = ToolErrorCode.PROCESS_FAILED, duration = 10L)
        }
      }
    })

    // 3. mcp_list_resources
    toolRegistry.register(object : Tool {
      override val name: String = "mcp_list_resources"
      override val description: String = "List all resources available across connected MCP servers."
      override val schema: ToolSchema = ToolSchema(emptyList())
      override val category: ToolCategory = ToolCategory.UTILITY
      override val target: ToolTarget = ToolTarget.HOST_NETWORK
      override val permissions: Set<ToolPermission> = emptySet()
      override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD
      override val environmentIdentity: EnvironmentIdentity = EnvironmentIdentity(
        type = owningBackend,
        name = "MCP Subsystem",
        sessionId = "mcp",
        workingDirectory = "/mcp"
      )

      override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
        val arr = JSONArray()
        for (res in _discoveredResources.value) {
          val obj = JSONObject().apply {
            put("uri", res.uri)
            put("name", res.name)
            put("description", res.description ?: "")
            put("mimeType", res.mimeType ?: "")
            put("serverId", res.serverId)
          }
          arr.put(obj)
        }
        val out = arr.toString(2)
        return ToolResult(callId = callId, toolName = name, status = ToolStatus.SUCCEEDED, arguments = arguments, output = out, error = null, duration = 5L, stdout = out)
      }
    })

    // 4. mcp_read_resource
    toolRegistry.register(object : Tool {
      override val name: String = "mcp_read_resource"
      override val description: String = "Read content of an MCP resource by URI from its owning server."
      override val schema: ToolSchema = ToolSchema(
        listOf(
          ToolParameter("uri", "string", "The URI of the MCP resource to read (e.g. 'file:///workspace/data.json')", required = true)
        )
      )
      override val category: ToolCategory = ToolCategory.UTILITY
      override val target: ToolTarget = ToolTarget.HOST_NETWORK
      override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK_ACCESS)
      override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD
      override val environmentIdentity: EnvironmentIdentity = EnvironmentIdentity(
        type = owningBackend,
        name = "MCP Subsystem",
        sessionId = "mcp",
        workingDirectory = "/mcp"
      )

      override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
        val uri = arguments["uri"]?.toString()?.trim() ?: ""
        if (uri.isBlank()) {
          return ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = "Missing required 'uri' parameter", errorType = ToolErrorCode.INVALID_ARGUMENTS, duration = 5L)
        }

        // Locate server providing this resource
        val matchingDef = _discoveredResources.value.find { it.uri == uri }
        val targetClient = if (matchingDef != null) {
          clients[matchingDef.serverId]
        } else {
          clients.values.firstOrNull { it.status.value == McpServerStatus.CONNECTED }
        }

        if (targetClient == null) {
          return ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = "No connected MCP server found for URI '$uri'", errorType = ToolErrorCode.FILE_NOT_FOUND, duration = 5L)
        }

        val res = targetClient.readResource(uri)
        return if (res.isSuccess) {
          val content = res.getOrThrow()
          val payload = content.text ?: content.blob ?: "(Empty resource content)"
          ToolResult(callId = callId, toolName = name, status = ToolStatus.SUCCEEDED, arguments = arguments, output = payload, error = null, duration = 15L, stdout = payload)
        } else {
          ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = res.exceptionOrNull()?.message, errorType = ToolErrorCode.NETWORK_ERROR, duration = 15L)
        }
      }
    })

    // 5. mcp_list_prompts
    toolRegistry.register(object : Tool {
      override val name: String = "mcp_list_prompts"
      override val description: String = "List all prompt templates exposed by connected MCP servers."
      override val schema: ToolSchema = ToolSchema(emptyList())
      override val category: ToolCategory = ToolCategory.UTILITY
      override val target: ToolTarget = ToolTarget.HOST_NETWORK
      override val permissions: Set<ToolPermission> = emptySet()
      override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD
      override val environmentIdentity: EnvironmentIdentity = EnvironmentIdentity(
        type = owningBackend,
        name = "MCP Subsystem",
        sessionId = "mcp",
        workingDirectory = "/mcp"
      )

      override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
        val arr = JSONArray()
        for (p in _discoveredPrompts.value) {
          val obj = JSONObject().apply {
            put("name", p.name)
            put("description", p.description ?: "")
            put("serverId", p.serverId)
            val aArr = JSONArray()
            p.arguments.forEach { arg ->
              aArr.put(JSONObject().apply {
                put("name", arg.name)
                put("description", arg.description ?: "")
                put("required", arg.required)
              })
            }
            put("arguments", aArr)
          }
          arr.put(obj)
        }
        val out = arr.toString(2)
        return ToolResult(callId = callId, toolName = name, status = ToolStatus.SUCCEEDED, arguments = arguments, output = out, error = null, duration = 5L, stdout = out)
      }
    })

    // 6. mcp_refresh
    toolRegistry.register(object : Tool {
      override val name: String = "mcp_refresh"
      override val description: String = "Trigger dynamic re-discovery of tools, resources, and prompts across all connected MCP servers."
      override val schema: ToolSchema = ToolSchema(emptyList())
      override val category: ToolCategory = ToolCategory.UTILITY
      override val target: ToolTarget = ToolTarget.HOST_NETWORK
      override val permissions: Set<ToolPermission> = emptySet()
      override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD
      override val environmentIdentity: EnvironmentIdentity = EnvironmentIdentity(
        type = owningBackend,
        name = "MCP Subsystem",
        sessionId = "mcp",
        workingDirectory = "/mcp"
      )

      override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
        val res = refreshAll()
        return if (res.isSuccess) {
          ToolResult(callId = callId, toolName = name, status = ToolStatus.SUCCEEDED, arguments = arguments, output = res.getOrThrow(), error = null, duration = 25L, stdout = res.getOrThrow())
        } else {
          ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = res.exceptionOrNull()?.message, errorType = ToolErrorCode.NETWORK_ERROR, duration = 25L)
        }
      }
    })

    // 7. mcp_call_tool
    toolRegistry.register(object : Tool {
      override val name: String = "mcp_call_tool"
      override val description: String = "Invoke any tool on any connected MCP server by server ID and tool name."
      override val schema: ToolSchema = ToolSchema(
        listOf(
          ToolParameter("server_id", "string", "ID of the target MCP server (e.g. 'sqlite', 'fetch', 'filesystem')", required = true),
          ToolParameter("tool_name", "string", "Name of the tool on the MCP server (e.g. 'query', 'execute', 'fetch_url', 'read_file')", required = true),
          ToolParameter("arguments", "object", "Arguments map or JSON object string for the tool", required = false)
        )
      )
      override val category: ToolCategory = ToolCategory.UTILITY
      override val target: ToolTarget = ToolTarget.HOST_NETWORK
      override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK_ACCESS)
      override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD
      override val environmentIdentity: EnvironmentIdentity = EnvironmentIdentity(
        type = owningBackend,
        name = "MCP Subsystem",
        sessionId = "mcp",
        workingDirectory = "/mcp"
      )

      override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
        val serverId = arguments["server_id"]?.toString()?.trim() ?: ""
        val toolName = arguments["tool_name"]?.toString()?.trim() ?: ""
        if (serverId.isBlank() || toolName.isBlank()) {
          return ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = "Missing required 'server_id' or 'tool_name'", errorType = ToolErrorCode.INVALID_ARGUMENTS, duration = 5L)
        }

        val client = clients[serverId]
          ?: return ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = "MCP server '$serverId' is not active or connected. Use 'mcp_list_servers' or 'mcp_manage_server(action: 'enable', id: '$serverId')'.", errorType = ToolErrorCode.TOOL_NOT_FOUND, duration = 5L)

        val rawArgs = arguments["arguments"]
        val toolArgs: Map<String, Any?> = when (rawArgs) {
          is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            rawArgs as Map<String, Any?>
          }
          is String -> {
            if (rawArgs.trim().startsWith("{")) {
              try {
                val obj = JSONObject(rawArgs)
                val map = mutableMapOf<String, Any?>()
                obj.keys().forEach { k -> map[k] = obj.get(k) }
                map
              } catch (_: Exception) {
                emptyMap()
              }
            } else {
              emptyMap()
            }
          }
          else -> {
            arguments.filterKeys { it != "server_id" && it != "tool_name" }
          }
        }

        val res = client.callTool(toolName, toolArgs)
        return if (res.isSuccess) {
          val callRes = res.getOrThrow()
          if (callRes.isError) {
            ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = callRes.rawText, errorType = ToolErrorCode.PROCESS_FAILED, duration = 15L, stderr = callRes.rawText)
          } else {
            ToolResult(callId = callId, toolName = name, status = ToolStatus.SUCCEEDED, arguments = arguments, output = callRes.rawText, error = null, duration = 15L, stdout = callRes.rawText)
          }
        } else {
          ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = res.exceptionOrNull()?.message, errorType = ToolErrorCode.PROCESS_FAILED, duration = 15L)
        }
      }
    })

    // 8. mcp_get_prompt
    toolRegistry.register(object : Tool {
      override val name: String = "mcp_get_prompt"
      override val description: String = "Retrieve and render a prompt template by name from a connected MCP server."
      override val schema: ToolSchema = ToolSchema(
        listOf(
          ToolParameter("server_id", "string", "ID of the target MCP server", required = true),
          ToolParameter("prompt_name", "string", "Name of the prompt template (e.g. 'deep_code_review', 'data_analysis_summary')", required = true),
          ToolParameter("arguments", "object", "Map or JSON object string of arguments for the prompt template", required = false)
        )
      )
      override val category: ToolCategory = ToolCategory.UTILITY
      override val target: ToolTarget = ToolTarget.HOST_NETWORK
      override val permissions: Set<ToolPermission> = emptySet()
      override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD
      override val environmentIdentity: EnvironmentIdentity = EnvironmentIdentity(
        type = owningBackend,
        name = "MCP Subsystem",
        sessionId = "mcp",
        workingDirectory = "/mcp"
      )

      override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
        val serverId = arguments["server_id"]?.toString()?.trim() ?: ""
        val promptName = arguments["prompt_name"]?.toString()?.trim() ?: ""
        if (serverId.isBlank() || promptName.isBlank()) {
          return ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = "Missing required 'server_id' or 'prompt_name'", errorType = ToolErrorCode.INVALID_ARGUMENTS, duration = 5L)
        }
        val client = clients[serverId]
          ?: return ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = "MCP server '$serverId' not found or active", errorType = ToolErrorCode.TOOL_NOT_FOUND, duration = 5L)

        val argsMap = mutableMapOf<String, String>()
        val raw = arguments["arguments"]
        if (raw is Map<*, *>) {
          raw.forEach { (k, v) -> if (k != null && v != null) argsMap[k.toString()] = v.toString() }
        } else if (raw is String && raw.trim().startsWith("{")) {
          try {
            val obj = JSONObject(raw)
            obj.keys().forEach { k -> argsMap[k] = obj.optString(k) }
          } catch (_: Exception) {}
        }

        val res = client.getPrompt(promptName, argsMap)
        return if (res.isSuccess) {
          val msgs = res.getOrThrow()
          val sb = StringBuilder()
          msgs.forEach { m -> sb.appendLine("[${m.role.uppercase()}]:\n${m.content}\n") }
          val out = sb.toString().trim()
          ToolResult(callId = callId, toolName = name, status = ToolStatus.SUCCEEDED, arguments = arguments, output = out, error = null, duration = 15L, stdout = out)
        } else {
          ToolResult(callId = callId, toolName = name, status = ToolStatus.FAILED, arguments = arguments, output = null, error = res.exceptionOrNull()?.message, errorType = ToolErrorCode.PROCESS_FAILED, duration = 15L)
        }
      }
    })
  }

  /**
   * Generates a concise diagnostic summary of active MCP servers and bridged tools for LLM context.
   */
  fun getActiveServerSummary(): String {
    val serversList = _servers.value
    val toolsList = _discoveredTools.value
    if (serversList.isEmpty()) return "No MCP servers configured."
    val sb = StringBuilder()
    sb.appendLine("Configured MCP Servers (${serversList.count { it.enabled }} active):")
    for (s in serversList) {
      val c = clients[s.id]
      val st = c?.status?.value?.name ?: (if (s.enabled) "CONNECTING" else "DISABLED")
      sb.appendLine("- [${s.id}] '${s.name}' (${s.transport.name}) -> Status: $st, Tools: ${c?.tools?.value?.size ?: 0}")
    }
    if (toolsList.isNotEmpty()) {
      sb.appendLine("Available MCP Tools (${toolsList.size}):")
      toolsList.forEach { t ->
        sb.appendLine("- ${t.namespacedName}: ${t.description ?: t.name}")
      }
    }
    return sb.toString().trimEnd()
  }

  /**
   * Built-in starter templates for popular MCP servers.
   */
  private fun getStarterTemplates(): List<McpServerConfig> = listOf(
    McpServerConfig(
      id = "playwright",
      name = "Playwright Browser MCP",
      description = "Playwright-grade web browser automation, JavaScript DOM evaluation (browser_run_javascript), interactive links, forms submission, and visual wireframing",
      transport = McpTransportType.INTERNAL,
      url = "internal://playwright",
      enabled = true,
      isNative = true
    ),
    McpServerConfig(
      id = "wikipedia",
      name = "Wikipedia Knowledge MCP",
      description = "Free Wikipedia encyclopedia search, summary extraction, and entity facts without API key",
      transport = McpTransportType.INTERNAL,
      url = "internal://wikipedia",
      enabled = true,
      isNative = true
    ),
    McpServerConfig(
      id = "arxiv",
      name = "ArXiv Research Papers MCP",
      description = "Scientific preprints, machine learning papers, and technical research paper search",
      transport = McpTransportType.INTERNAL,
      url = "internal://arxiv",
      enabled = true,
      isNative = true
    ),
    McpServerConfig(
      id = "math",
      name = "Math & Calculator MCP",
      description = "Offline high-precision mathematical expression evaluator, statistics, and unit converter",
      transport = McpTransportType.INTERNAL,
      url = "internal://math",
      enabled = true,
      isNative = true
    ),
    McpServerConfig(
      id = "time",
      name = "World Clock & Timezone MCP",
      description = "Real-time clock timestamps, timezone conversions, and elapsed time calculator",
      transport = McpTransportType.INTERNAL,
      url = "internal://time",
      enabled = true,
      isNative = true
    ),
    McpServerConfig(
      id = "sqlite",
      name = "Workspace SQLite MCP",
      description = "Native high-performance SQLite database query, schema inspection, and data storage MCP server",
      transport = McpTransportType.INTERNAL,
      url = "internal://sqlite",
      enabled = true,
      isNative = true
    ),
    McpServerConfig(
      id = "fetch",
      name = "Web Fetch MCP",
      description = "High-performance web fetch, HTTP header analysis, and markdown document extraction MCP server",
      transport = McpTransportType.INTERNAL,
      url = "internal://fetch",
      enabled = true,
      isNative = true
    ),
    McpServerConfig(
      id = "filesystem",
      name = "Workspace Filesystem MCP",
      description = "MCP reference filesystem server for secure workspace access and file verification",
      transport = McpTransportType.INTERNAL,
      url = "internal://filesystem",
      enabled = true,
      isNative = true
    ),
    McpServerConfig(
      id = "memory",
      name = "Knowledge Graph & Memory MCP",
      description = "Persistent agent knowledge graph, structured facts, and cross-task entity memory MCP server",
      transport = McpTransportType.INTERNAL,
      url = "internal://memory",
      enabled = true,
      isNative = true
    ),
    McpServerConfig(
      id = "office",
      name = "Office & Word/Excel Documents MCP",
      description = "Zero-key authentic Microsoft Word (.docx) and Excel (.xlsx) document generation, styled tables, formulas, and office metadata",
      transport = McpTransportType.INTERNAL,
      url = "internal://office",
      enabled = true,
      isNative = true
    ),
    McpServerConfig(
      id = "data_tools",
      name = "Data & Format Converter MCP",
      description = "Free CSV-to-JSON, JSON-to-CSV, Base64 encoder/decoder, and JSON formatting without auth",
      transport = McpTransportType.INTERNAL,
      url = "internal://data_tools",
      enabled = true,
      isNative = true
    ),
    McpServerConfig(
      id = "markdown",
      name = "Markdown & Content Engine MCP",
      description = "Markdown-to-HTML compilation, ASCII markdown table generator, and reading statistics",
      transport = McpTransportType.INTERNAL,
      url = "internal://markdown",
      enabled = true,
      isNative = true
    ),
    McpServerConfig(
      id = "system",
      name = "System & Diagnostics MCP",
      description = "On-device JVM memory statistics, available CPU cores, Android SDK telemetry, and workspace storage inspection",
      transport = McpTransportType.INTERNAL,
      url = "internal://system",
      enabled = true,
      isNative = true
    ),
    McpServerConfig(
      id = "github",
      name = "GitHub MCP",
      description = "GitHub repository, issues, pull requests, and code search MCP server",
      transport = McpTransportType.HTTP,
      url = "https://api.github.com/mcp",
      headers = mapOf("Authorization" to "Bearer YOUR_GITHUB_TOKEN"),
      enabled = false
    ),
    McpServerConfig(
      id = "weather",
      name = "National Weather Service MCP",
      description = "Live weather forecast and alerts MCP endpoint",
      transport = McpTransportType.SSE,
      url = "https://weather.mcp.example.com/sse",
      enabled = false
    )
  )
}
