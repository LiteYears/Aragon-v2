package com.example.agent.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Exception representing an error returned by the MCP JSON-RPC 2.0 server.
 */
class McpRpcException(
  val code: Int,
  override val message: String,
  val data: Any? = null
) : IOException("MCP RPC Error [$code]: $message")

/**
 * Robust JSON-RPC 2.0 protocol session for Model Context Protocol.
 * Handles request correlation, notifications, timeouts, and error parsing.
 */
class McpJsonRpcSession(
  private val serverId: String,
  private val sendRaw: suspend (String) -> Unit
) {
  private val idCounter = AtomicLong(1)
  private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
  private val notificationHandlers = ConcurrentHashMap<String, suspend (JSONObject?) -> Unit>()

  fun onNotification(method: String, handler: suspend (JSONObject?) -> Unit) {
    notificationHandlers[method] = handler
  }

  fun removeNotificationHandler(method: String) {
    notificationHandlers.remove(method)
  }

  /**
   * Sends a JSON-RPC 2.0 request and awaits the result with timeout.
   */
  suspend fun request(
    method: String,
    params: JSONObject? = null,
    timeoutMs: Long = 30000L
  ): JSONObject {
    val id = idCounter.getAndIncrement()
    val deferred = CompletableDeferred<JSONObject>()
    pendingRequests[id] = deferred

    val reqObj = JSONObject().apply {
      put("jsonrpc", "2.0")
      put("id", id)
      put("method", method)
      if (params != null) {
        put("params", params)
      } else {
        put("params", JSONObject())
      }
    }

    try {
      sendRaw(reqObj.toString())
      return withTimeout(timeoutMs) {
        deferred.await()
      }
    } finally {
      pendingRequests.remove(id)
    }
  }

  /**
   * Sends a JSON-RPC 2.0 notification (fire-and-forget, no ID).
   */
  suspend fun notify(method: String, params: JSONObject? = null) {
    val notifObj = JSONObject().apply {
      put("jsonrpc", "2.0")
      put("method", method)
      if (params != null) {
        put("params", params)
      }
    }
    sendRaw(notifObj.toString())
  }

  /**
   * Processes an incoming raw JSON-RPC 2.0 string received from transport.
   */
  suspend fun handleIncoming(rawJson: String) {
    val trimmed = rawJson.trim()
    if (trimmed.isEmpty()) return

    try {
      if (trimmed.startsWith("[")) {
        // Batch response
        val arr = JSONArray(trimmed)
        for (i in 0 until arr.length()) {
          val item = arr.optJSONObject(i)
          if (item != null) processSingleMessage(item)
        }
      } else {
        val obj = JSONObject(trimmed)
        processSingleMessage(obj)
      }
    } catch (_: Exception) {
      // Discard invalid JSON-RPC frames
    }
  }

  private suspend fun processSingleMessage(obj: JSONObject) {
    // 1. Is it a Response to a pending request?
    if (obj.has("id") && !obj.isNull("id")) {
      val id = obj.optLong("id", -1L)
      val deferred = pendingRequests.remove(id)
      if (deferred != null) {
        if (obj.has("error")) {
          val errObj = obj.optJSONObject("error")
          val code = errObj?.optInt("code", -32603) ?: -32603
          val message = errObj?.optString("message", "Unknown error") ?: "Unknown error"
          val data = errObj?.opt("data")
          deferred.completeExceptionally(McpRpcException(code, message, data))
        } else {
          val result = obj.optJSONObject("result") ?: JSONObject()
          deferred.complete(result)
        }
      }
      return
    }

    // 2. Is it a Notification?
    if (obj.has("method")) {
      val method = obj.optString("method")
      val params = obj.optJSONObject("params")
      val handler = notificationHandlers[method]
      if (handler != null) {
        try {
          handler(params)
        } catch (_: Exception) {
        }
      }
    }
  }

  /**
   * Cancels all pending requests, e.g. when transport closes or errors.
   */
  fun close(reason: Throwable? = null) {
    val ex = reason ?: IOException("MCP JSON-RPC session closed")
    for ((_, deferred) in pendingRequests) {
      deferred.completeExceptionally(ex)
    }
    pendingRequests.clear()
    notificationHandlers.clear()
  }

  companion object {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
  }
}
