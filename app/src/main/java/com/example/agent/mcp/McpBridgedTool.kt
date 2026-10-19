package com.example.agent.mcp

import com.example.agent.core.EnvironmentIdentity
import com.example.agent.core.ExecutionEnvironmentType
import com.example.agent.core.Tool
import com.example.agent.core.ToolCategory
import com.example.agent.core.ToolErrorCode
import com.example.agent.core.ToolExecutionContext
import com.example.agent.core.ToolParameter
import com.example.agent.core.ToolPermission
import com.example.agent.core.ToolResult
import com.example.agent.core.ToolSchema
import com.example.agent.core.ToolStatus
import com.example.agent.core.ToolTarget
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * Adapter bridging an external MCP tool definition into Aragon's native [Tool] contract.
 *
 * Preserves namespacing (e.g. 'github__create_issue' or 'sqlite__query'), converts
 * JSON Schema parameters into Aragon's [ToolSchema], routes calls to the matching [McpClient],
 * and converts structured MCP content results into authoritative [ToolResult] records.
 */
class McpBridgedTool(
  val client: McpClient,
  val definition: McpToolDefinition
) : Tool {

  override val name: String = sanitizeToolName(definition.namespacedName)

  override val description: String = buildString {
    append("[MCP: ${client.config.name}] ")
    val d = definition.description
    if (!d.isNullOrBlank()) {
      append(d.trim())
    } else {
      append("Executes '${definition.name}' provided by MCP server '${client.config.name}'")
    }
  }

  override val schema: ToolSchema = parseJsonSchema(definition.inputSchema)

  override val category: ToolCategory = ToolCategory.UTILITY

  override val target: ToolTarget = ToolTarget.HOST_NETWORK

  override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK_ACCESS)

  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.MCP_BRIDGE

  override val defaultTimeoutMs: Long = client.config.timeoutSeconds * 1000L

  override val environmentIdentity: EnvironmentIdentity = EnvironmentIdentity(
    type = owningBackend,
    name = "MCP Server: ${client.config.name} (${client.config.transport.name})",
    sessionId = client.config.id,
    workingDirectory = "/mcp/${client.config.effectiveNamespace}"
  )

  override fun isAvailable(): Boolean {
    return client.status.value == McpServerStatus.CONNECTED
  }

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
    val startTime = System.currentTimeMillis()

    if (!isAvailable()) {
      val state = client.status.value.name
      val err = "MCP server '${client.config.name}' is not connected (current state: $state). Please verify the server connection in MCP settings."
      return ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.FAILED,
        arguments = arguments,
        output = null,
        error = err,
        errorType = ToolErrorCode.NETWORK_ERROR,
        duration = System.currentTimeMillis() - startTime,
        metadata = mapOf("serverId" to client.config.id, "mcpTool" to definition.name)
      )
    }

    val coercedArgs = coerceArguments(definition.inputSchema, arguments)
    val res = client.callTool(definition.name, coercedArgs)
    val duration = System.currentTimeMillis() - startTime

    if (res.isSuccess) {
      val callResult = res.getOrThrow()
      if (callResult.isError) {
        val errorText = callResult.rawText.ifBlank { "MCP tool reported execution error (isError=true)" }
        return ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = errorText,
          errorType = ToolErrorCode.PROCESS_FAILED,
          duration = duration,
          stderr = errorText,
          exitCode = 1,
          metadata = mapOf(
            "serverId" to client.config.id,
            "mcpTool" to definition.name,
            "contentCount" to callResult.content.size
          )
        )
      } else {
        val outputText = callResult.rawText.ifBlank { "Tool executed successfully (no output returned)" }
        return ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = outputText,
          error = null,
          duration = duration,
          stdout = outputText,
          exitCode = 0,
          metadata = mapOf(
            "serverId" to client.config.id,
            "mcpTool" to definition.name,
            "contentCount" to callResult.content.size
          )
        )
      }
    } else {
      val ex = res.exceptionOrNull()
      if (ex is kotlinx.coroutines.CancellationException) throw ex
      val errType = when (ex) {
        is TimeoutException, is SocketTimeoutException -> ToolErrorCode.TIMEOUT
        is McpRpcException -> ToolErrorCode.PROCESS_FAILED
        else -> ToolErrorCode.NETWORK_ERROR
      }
      val errMsg = ex?.message ?: "MCP tool call failed with unknown exception"
      return ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.FAILED,
        arguments = arguments,
        output = null,
        error = errMsg,
        errorType = errType,
        duration = duration,
        stderr = errMsg,
        exitCode = 1,
        metadata = mapOf("serverId" to client.config.id, "mcpTool" to definition.name)
      )
    }
  }

  private fun coerceArguments(schemaObj: JSONObject?, rawArgs: Map<String, Any?>): Map<String, Any?> {
    var args = rawArgs
    // If the model wrapped arguments inside a single key 'arguments', 'params', or 'input'
    if (args.size == 1) {
      val firstKey = args.keys.first()
      if (firstKey == "arguments" || firstKey == "params" || firstKey == "input") {
        val firstVal = args.values.first()
        if (firstVal is Map<*, *>) {
          @Suppress("UNCHECKED_CAST")
          args = firstVal as Map<String, Any?>
        } else if (firstVal is String && firstVal.trim().startsWith("{")) {
          try {
            val json = JSONObject(firstVal)
            val map = mutableMapOf<String, Any?>()
            json.keys().forEach { k -> map[k] = json.get(k) }
            args = map
          } catch (_: Exception) {}
        }
      }
    }

    if (schemaObj == null) return args
    val props = schemaObj.optJSONObject("properties") ?: return args
    val coerced = args.toMutableMap()

    props.keys().forEach { key ->
      val propDef = props.optJSONObject(key) ?: return@forEach
      val expectedType = propDef.optString("type", "").lowercase()
      val value = coerced[key] ?: return@forEach

      when (expectedType) {
        "string" -> {
          if (value !is String) {
            coerced[key] = value.toString()
          }
        }
        "number", "integer" -> {
          if (value is String) {
            val longVal = value.toLongOrNull()
            if (longVal != null) {
              coerced[key] = longVal
            } else {
              val doubleVal = value.toDoubleOrNull()
              if (doubleVal != null) coerced[key] = doubleVal
            }
          }
        }
        "boolean" -> {
          if (value is String) {
            coerced[key] = value.equals("true", ignoreCase = true) || value == "1"
          } else if (value is Number) {
            coerced[key] = value.toInt() != 0
          }
        }
        "object" -> {
          if (value is String && value.trim().startsWith("{")) {
            try {
              coerced[key] = JSONObject(value)
            } catch (_: Exception) {}
          }
        }
        "array" -> {
          if (value is String && value.trim().startsWith("[")) {
            try {
              coerced[key] = JSONArray(value)
            } catch (_: Exception) {}
          }
        }
      }
    }
    return coerced
  }

  companion object {
    /**
     * Sanitizes namespaced tool names so they strictly conform to function name rules:
     * ^[a-zA-Z_][a-zA-Z0-9_]*$ and maximum length of 64 characters.
     */
    fun sanitizeToolName(raw: String): String {
      val sanitized = raw.replace(Regex("[^a-zA-Z0-9_]"), "_").replace(Regex("_+"), "_")
      val validStart = if (sanitized.firstOrNull()?.isLetter() == true || sanitized.startsWith("_")) sanitized else "_$sanitized"
      return validStart.take(64)
    }
    /**
     * Converts a standard MCP JSON Schema object into Aragon's [ToolSchema].
     */
    fun parseJsonSchema(schemaObj: JSONObject?): ToolSchema {
      if (schemaObj == null) {
        return ToolSchema(emptyList())
      }

      val propertiesObj = schemaObj.optJSONObject("properties") ?: JSONObject()
      val requiredArr = schemaObj.optJSONArray("required") ?: JSONArray()
      val requiredSet = mutableSetOf<String>()
      for (i in 0 until requiredArr.length()) {
        val field = requiredArr.optString(i)
        if (field.isNotBlank()) requiredSet.add(field)
      }

      val params = mutableListOf<ToolParameter>()
      val keys = propertiesObj.keys()
      while (keys.hasNext()) {
        val propName = keys.next()
        val propObj = propertiesObj.optJSONObject(propName) ?: JSONObject()

        val type = propObj.optString("type", "string").lowercase()
        val desc = propObj.optString("description", "Parameter '$propName'")
        val isRequired = requiredSet.contains(propName)

        val enumArr = propObj.optJSONArray("enum")
        val enumVals = if (enumArr != null && enumArr.length() > 0) {
          val list = mutableListOf<String>()
          for (j in 0 until enumArr.length()) {
            list.add(enumArr.optString(j))
          }
          list
        } else null

        val defVal = if (propObj.has("default")) propObj.opt("default") else null

        params.add(
          ToolParameter(
            name = propName,
            type = if (type == "integer") "number" else type,
            description = desc,
            required = isRequired,
            enumValues = enumVals,
            defaultValue = defVal
          )
        )
      }

      return ToolSchema(params)
    }
  }
}
