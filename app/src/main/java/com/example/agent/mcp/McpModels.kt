package com.example.agent.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Supported MCP transport types per the Model Context Protocol specification.
 */
enum class McpTransportType {
  HTTP,     // Streamable HTTP / Direct JSON-RPC 2.0
  SSE,      // Server-Sent Events with separate POST endpoint
  STDIO,    // Standard I/O process communication (local or microVM)
  INTERNAL  // Native in-process MCP handler (SQLite, Web Fetch, Filesystem)
}

/**
 * Runtime connection status of an MCP server.
 */
enum class McpServerStatus {
  DISCONNECTED,
  CONNECTING,
  INITIALIZING,
  CONNECTED,
  ERROR,
  DISABLED
}

/**
 * Log levels defined by the MCP logging capability.
 */
enum class McpLogLevel {
  DEBUG,
  INFO,
  NOTICE,
  WARNING,
  ERROR,
  CRITICAL,
  ALERT,
  EMERGENCY;

  companion object {
    fun fromString(str: String?): McpLogLevel {
      return when (str?.trim()?.lowercase()) {
        "debug" -> DEBUG
        "info" -> INFO
        "notice" -> NOTICE
        "warning", "warn" -> WARNING
        "error" -> ERROR
        "critical" -> CRITICAL
        "alert" -> ALERT
        "emergency" -> EMERGENCY
        else -> INFO
      }
    }
  }
}

/**
 * Full configuration for connecting to an MCP server.
 */
data class McpServerConfig(
  val id: String,
  val name: String,
  val description: String = "",
  val transport: McpTransportType = McpTransportType.HTTP,
  val url: String = "",
  val command: String = "",
  val args: List<String> = emptyList(),
  val env: Map<String, String> = emptyMap(),
  val headers: Map<String, String> = emptyMap(),
  val timeoutSeconds: Long = 30L,
  val enabled: Boolean = true,
  val autoReconnect: Boolean = true,
  val namespace: String = "",
  val isNative: Boolean = (transport == McpTransportType.INTERNAL || url.startsWith("internal://"))
) {
  val effectiveNamespace: String get() = namespace.ifBlank { id.replace("-", "_") }

  fun toJson(): JSONObject {
    val obj = JSONObject()
    obj.put("id", id)
    obj.put("name", name)
    obj.put("description", description)
    obj.put("transport", transport.name)
    obj.put("url", url)
    obj.put("command", command)
    obj.put("isNative", isNative)

    val argsArr = JSONArray()
    args.forEach { argsArr.put(it) }
    obj.put("args", argsArr)

    val envObj = JSONObject()
    env.forEach { (k, v) -> envObj.put(k, v) }
    obj.put("env", envObj)

    val headersObj = JSONObject()
    headers.forEach { (k, v) -> headersObj.put(k, v) }
    obj.put("headers", headersObj)

    obj.put("timeoutSeconds", timeoutSeconds)
    obj.put("enabled", enabled)
    obj.put("autoReconnect", autoReconnect)
    obj.put("namespace", namespace)
    return obj
  }

  companion object {
    fun fromJson(obj: JSONObject): McpServerConfig {
      val argsList = mutableListOf<String>()
      obj.optJSONArray("args")?.let { arr ->
        for (i in 0 until arr.length()) {
          argsList.add(arr.optString(i))
        }
      }

      val envMap = mutableMapOf<String, String>()
      obj.optJSONObject("env")?.let { envObj ->
        envObj.keys().forEach { k ->
          envMap[k] = envObj.optString(k)
        }
      }

      val headersMap = mutableMapOf<String, String>()
      obj.optJSONObject("headers")?.let { hObj ->
        hObj.keys().forEach { k ->
          headersMap[k] = hObj.optString(k)
        }
      }

      val transportStr = obj.optString("transport", "HTTP").uppercase()
      val transport = try {
        McpTransportType.valueOf(transportStr)
      } catch (_: Exception) {
        when (transportStr) {
          "INTERNAL", "BUILTIN", "IN_PROCESS" -> McpTransportType.INTERNAL
          "SSE" -> McpTransportType.SSE
          "STDIO" -> McpTransportType.STDIO
          else -> McpTransportType.HTTP
        }
      }

      return McpServerConfig(
        id = obj.optString("id", "server_${System.currentTimeMillis()}"),
        name = obj.optString("name", "MCP Server"),
        description = obj.optString("description", ""),
        transport = transport,
        url = obj.optString("url", ""),
        command = obj.optString("command", ""),
        args = argsList,
        env = envMap,
        headers = headersMap,
        timeoutSeconds = obj.optLong("timeoutSeconds", 30L),
        enabled = obj.optBoolean("enabled", true),
        autoReconnect = obj.optBoolean("autoReconnect", true),
        namespace = obj.optString("namespace", ""),
        isNative = obj.optBoolean("isNative", transport == McpTransportType.INTERNAL || obj.optString("url").startsWith("internal://"))
      )
    }
  }
}

/**
 * Server information and capabilities returned during MCP initialize handshake.
 */
data class McpServerInfo(
  val name: String,
  val version: String,
  val protocolVersion: String = "2024-11-05",
  val capabilities: McpServerCapabilities = McpServerCapabilities()
)

data class McpServerCapabilities(
  val hasTools: Boolean = false,
  val toolsListChanged: Boolean = false,
  val hasResources: Boolean = false,
  val resourcesSubscribe: Boolean = false,
  val resourcesListChanged: Boolean = false,
  val hasPrompts: Boolean = false,
  val promptsListChanged: Boolean = false,
  val hasLogging: Boolean = false
)

/**
 * Discovered tool definition from an MCP server.
 */
data class McpToolDefinition(
  val name: String,
  val description: String?,
  val inputSchema: JSONObject,
  val serverId: String,
  val namespacedName: String
)

/**
 * Discovered resource definition from an MCP server.
 */
data class McpResourceDefinition(
  val uri: String,
  val name: String,
  val description: String?,
  val mimeType: String?,
  val serverId: String
)

/**
 * Resource content read from an MCP server.
 */
data class McpResourceContent(
  val uri: String,
  val mimeType: String?,
  val text: String?,
  val blob: String? // Base64 encoded binary
)

/**
 * Discovered prompt definition from an MCP server.
 */
data class McpPromptDefinition(
  val name: String,
  val description: String?,
  val arguments: List<McpPromptArgDefinition> = emptyList(),
  val serverId: String
)

data class McpPromptArgDefinition(
  val name: String,
  val description: String? = null,
  val required: Boolean = false
)

data class McpPromptMessage(
  val role: String,
  val content: String
)

/**
 * Content item returned by an MCP tool call.
 */
sealed class McpContentItem {
  data class Text(val text: String) : McpContentItem()
  data class Image(val data: String, val mimeType: String) : McpContentItem()
  data class Resource(val uri: String, val mimeType: String?, val text: String?) : McpContentItem()
}

/**
 * Result of executing an MCP tool call.
 */
data class McpToolCallResult(
  val isError: Boolean,
  val content: List<McpContentItem>,
  val rawText: String
)

/**
 * Structured log entry captured from an MCP server via logging notifications.
 */
data class McpLogMessage(
  val serverId: String,
  val level: McpLogLevel,
  val logger: String?,
  val data: String,
  val timestamp: Long = System.currentTimeMillis()
)
