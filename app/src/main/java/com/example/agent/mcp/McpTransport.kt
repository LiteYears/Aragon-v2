package com.example.agent.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transport layer abstraction for Model Context Protocol.
 */
interface McpTransport {
  suspend fun start(
    onMessage: suspend (String) -> Unit,
    onClose: suspend (Throwable?) -> Unit
  )
  suspend fun send(messageJson: String)
  suspend fun close()
  val isConnected: Boolean
}

/**
 * Direct HTTP JSON-RPC 2.0 transport for MCP.
 * Sends JSON-RPC calls over HTTP POST and receives response payloads.
 */
class HttpMcpTransport(
  private val config: McpServerConfig,
  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
    .build()
) : McpTransport {

  private val connected = AtomicBoolean(false)
  private var onMessageCallback: (suspend (String) -> Unit)? = null
  private var onCloseCallback: (suspend (Throwable?) -> Unit)? = null
  @Volatile private var mcpSessionId: String? = null

  override val isConnected: Boolean get() = connected.get()

  override suspend fun start(
    onMessage: suspend (String) -> Unit,
    onClose: suspend (Throwable?) -> Unit
  ) {
    this.onMessageCallback = onMessage
    this.onCloseCallback = onClose
    connected.set(true)
  }

  override suspend fun send(messageJson: String): Unit = withContext(Dispatchers.IO) {
    if (!connected.get()) {
      throw IOException("HTTP MCP Transport for '${config.id}' is closed")
    }

    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = messageJson.toRequestBody(mediaType)
    val reqBuilder = Request.Builder()
      .url(config.url)
      .post(requestBody)
      .addHeader("Accept", "application/json, text/plain, */*")
      .addHeader("Content-Type", "application/json")

    // Attach streamable HTTP MCP session ID if previously negotiated
    mcpSessionId?.let { sid ->
      reqBuilder.addHeader("Mcp-Session-Id", sid)
    }

    // Apply custom auth and headers
    config.headers.forEach { (k, v) ->
      reqBuilder.addHeader(k, v)
    }

    val request = reqBuilder.build()
    try {
      val response = httpClient.newCall(request).execute()
      response.use { resp ->
        // Capture session ID returned by server
        val sid = resp.header("mcp-session-id") ?: resp.header("Mcp-Session-Id")
        if (!sid.isNullOrBlank()) {
          mcpSessionId = sid
        }

        val bodyStr = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
          throw IOException("HTTP error ${resp.code}: ${resp.message}. Body: $bodyStr")
        }
        if (bodyStr.isNotBlank()) {
          onMessageCallback?.invoke(bodyStr)
        }
      }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      throw e
    }
  }

  override suspend fun close() {
    if (connected.compareAndSet(true, false)) {
      onCloseCallback?.invoke(null)
    }
  }
}

/**
 * Server-Sent Events (SSE) transport for Model Context Protocol.
 * Establishes an SSE stream for inbound server notifications and JSON-RPC responses,
 * and uses a separate POST endpoint (discovered via 'endpoint' event) for client messages.
 */
class SseMcpTransport(
  private val config: McpServerConfig,
  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS) // Infinite read timeout for SSE stream
    .writeTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
    .build()
) : McpTransport {

  private val connected = AtomicBoolean(false)
  private var activeCall: Call? = null
  private var sseJob: Job? = null
  private var postEndpointUrl: String? = null
  private var onMessageCallback: (suspend (String) -> Unit)? = null
  private var onCloseCallback: (suspend (Throwable?) -> Unit)? = null

  override val isConnected: Boolean get() = connected.get()

  override suspend fun start(
    onMessage: suspend (String) -> Unit,
    onClose: suspend (Throwable?) -> Unit
  ): Unit = withContext(Dispatchers.IO) {
    this@SseMcpTransport.onMessageCallback = onMessage
    this@SseMcpTransport.onCloseCallback = onClose

    val reqBuilder = Request.Builder()
      .url(config.url)
      .get()
      .addHeader("Accept", "text/event-stream")
      .addHeader("Cache-Control", "no-cache")

    config.headers.forEach { (k, v) ->
      reqBuilder.addHeader(k, v)
    }

    val call = httpClient.newCall(reqBuilder.build())
    activeCall = call

    sseJob = CoroutineScope(Dispatchers.IO).launch {
      try {
        val response = call.execute()
        if (!response.isSuccessful) {
          val err = IOException("SSE connect failed: HTTP ${response.code} ${response.message}")
          response.close()
          connected.set(false)
          onClose(err)
          return@launch
        }

        connected.set(true)
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream(), Charsets.UTF_8))
        var currentEvent = "message"
        val dataBuffer = StringBuilder()

        while (isActive && connected.get()) {
          val line = reader.readLine() ?: break
          val trimmed = line.trim()
          if (trimmed.isEmpty()) {
            // End of SSE event frame
            if (dataBuffer.isNotEmpty()) {
              val dataStr = dataBuffer.toString().trim()
              handleSseEvent(currentEvent, dataStr)
              dataBuffer.clear()
            }
            currentEvent = "message"
          } else if (trimmed.startsWith("event:")) {
            currentEvent = trimmed.substring(6).trim()
          } else if (trimmed.startsWith("data:")) {
            val chunk = trimmed.substring(5).trim()
            if (dataBuffer.isNotEmpty()) dataBuffer.append("\n")
            dataBuffer.append(chunk)
          }
        }
        connected.set(false)
        onClose(null)
      } catch (t: Throwable) {
        if (connected.get()) {
          connected.set(false)
          onClose(t)
        }
      }
    }
  }

  private suspend fun handleSseEvent(event: String, data: String) {
    when (event) {
      "endpoint" -> {
        // Discovered message POST endpoint
        postEndpointUrl = resolveEndpointUrl(config.url, data)
      }
      "message", "" -> {
        onMessageCallback?.invoke(data)
      }
      else -> {
        // Fallback for custom events containing JSON-RPC payloads
        if (data.startsWith("{") || data.startsWith("[")) {
          onMessageCallback?.invoke(data)
        }
      }
    }
  }

  private fun resolveEndpointUrl(baseUrl: String, endpoint: String): String {
    return try {
      val baseUri = URI(baseUrl)
      val resolved = baseUri.resolve(endpoint)
      resolved.toString()
    } catch (_: Exception) {
      endpoint
    }
  }

  override suspend fun send(messageJson: String): Unit = withContext(Dispatchers.IO) {
    val targetUrl = postEndpointUrl ?: config.url
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = messageJson.toRequestBody(mediaType)

    val reqBuilder = Request.Builder()
      .url(targetUrl)
      .post(requestBody)
      .addHeader("Accept", "application/json")
      .addHeader("Content-Type", "application/json")

    config.headers.forEach { (k, v) ->
      reqBuilder.addHeader(k, v)
    }

    val response = httpClient.newCall(reqBuilder.build()).execute()
    response.use { resp ->
      val bodyStr = resp.body?.string() ?: ""
      if (!resp.isSuccessful) {
        throw IOException("SSE POST failed: HTTP ${resp.code} ${resp.message}. Body: $bodyStr")
      }
      if (bodyStr.isNotBlank() && (bodyStr.startsWith("{") || bodyStr.startsWith("["))) {
        onMessageCallback?.invoke(bodyStr)
      }
    }
  }

  override suspend fun close() {
    connected.set(false)
    try {
      activeCall?.cancel()
    } catch (_: Exception) {}
    try {
      sseJob?.cancel()
    } catch (_: Exception) {}
    onCloseCallback?.invoke(null)
  }
}

/**
 * Standard I/O (stdio) process transport for Model Context Protocol.
 * Spawns a local or containerized command and communicates via newline-delimited JSON-RPC 2.0.
 */
class StdioMcpTransport(
  private val config: McpServerConfig
) : McpTransport {

  private val connected = AtomicBoolean(false)
  private var process: Process? = null
  private var writer: BufferedWriter? = null
  private var readerJob: Job? = null
  private var stderrJob: Job? = null
  private var onMessageCallback: (suspend (String) -> Unit)? = null
  private var onCloseCallback: (suspend (Throwable?) -> Unit)? = null

  override val isConnected: Boolean get() = connected.get() && (process?.isAlive ?: false)

  override suspend fun start(
    onMessage: suspend (String) -> Unit,
    onClose: suspend (Throwable?) -> Unit
  ): Unit = withContext(Dispatchers.IO) {
    this@StdioMcpTransport.onMessageCallback = onMessage
    this@StdioMcpTransport.onCloseCallback = onClose

    val cmdList = mutableListOf<String>()
    if (config.command.isNotBlank()) {
      cmdList.add(config.command)
    }
    cmdList.addAll(config.args)

    if (cmdList.isEmpty()) {
      throw IllegalArgumentException("Stdio MCP transport for '${config.id}' requires a valid command")
    }

    val pb = ProcessBuilder(cmdList)
    val env = pb.environment()
    config.env.forEach { (k, v) -> env[k] = v }

    val proc = try {
      pb.start()
    } catch (e: Exception) {
      throw IOException("Failed to start stdio MCP process '${cmdList.first()}': ${e.message}", e)
    }
    this@StdioMcpTransport.process = proc
    this@StdioMcpTransport.writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
    connected.set(true)

    // Reading stdout lines
    readerJob = CoroutineScope(Dispatchers.IO).launch {
      val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
      try {
        while (isActive && connected.get()) {
          val line = reader.readLine() ?: break
          val trimmed = line.trim()
          if (trimmed.isNotEmpty()) {
            onMessage(trimmed)
          }
        }
      } catch (t: Throwable) {
        if (connected.get()) {
          connected.set(false)
          onClose(t)
        }
      } finally {
        if (connected.compareAndSet(true, false)) {
          onClose(null)
        }
      }
    }

    // Reading stderr lines for diagnostics
    stderrJob = CoroutineScope(Dispatchers.IO).launch {
      val errReader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))
      try {
        while (isActive && connected.get()) {
          val line = errReader.readLine() ?: break
          // Keep stderr available in logging if needed
        }
      } catch (_: Exception) {}
    }
  }

  override suspend fun send(messageJson: String): Unit = withContext(Dispatchers.IO) {
    val w = writer ?: throw IOException("Stdio writer is not initialized")
    if (!isConnected) throw IOException("Stdio MCP process is not running")

    synchronized(this@StdioMcpTransport) {
      w.write(messageJson)
      w.newLine()
      w.flush()
    }
  }

  override suspend fun close(): Unit = withContext(Dispatchers.IO) {
    connected.set(false)
    try {
      writer?.close()
    } catch (_: Exception) {}
    try {
      readerJob?.cancel()
    } catch (_: Exception) {}
    try {
      stderrJob?.cancel()
    } catch (_: Exception) {}
    try {
      process?.destroy()
    } catch (_: Exception) {}
    onCloseCallback?.invoke(null)
  }
}
