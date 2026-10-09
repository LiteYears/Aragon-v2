package com.example.agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Standard configuration options for NVIDIA NIM OpenAI-compatible hosted API.
 */
data class NvidiaNimConfig(
  val baseUrl: String = DEFAULT_BASE_URL,
  val model: String = DEFAULT_MODEL,
  val apiKey: String = "",
  val temperature: Double? = 0.2,
  val maxTokens: Int? = 4096,
  val topP: Double? = null,
  val timeoutSeconds: Long = 60,
  val enableReasoning: Boolean = true
) {
  companion object {
    const val DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
    const val DEFAULT_MODEL = "z-ai/glm-5.3"
  }
}

/**
 * Comprehensive catalog of free hosted models on build.nvidia.com with native tool calling support.
 */
data class NvidiaModelEntry(
  val id: String,
  val name: String,
  val category: String,
  val description: String,
  val badge: String = ""
)

object NvidiaNimModels {
  const val GLM_5_3 = "z-ai/glm-5.3"
  const val GLM_5_3_FLASH = "z-ai/glm-5.3-flash"
  const val NEMOTRON_70B = "nvidia/llama-3.1-nemotron-70b-instruct"
  const val NEMOTRON_51B = "nvidia/llama-3.1-nemotron-51b-instruct"
  const val NEMOTRON_4_340B = "nvidia/nemotron-4-340b-instruct"
  const val MISTRAL_LARGE_2 = "mistralai/mistral-large-2-instruct"
  const val MIXTRAL_8X22B = "mistralai/mixtral-8x22b-v0.1"
  const val CODESTRAL_22B = "mistralai/codestral-22b-instruct-v0.1"
  const val MISTRAL_7B = "mistralai/mistral-7b-instruct-v0.3"
  const val MISTRAL_NEMO_12B = "nv-mistralai/mistral-nemo-12b-instruct"
  const val CODELLAMA_70B = "meta/codellama-70b"
  const val GPT_OSS_20B = "openai/gpt-oss-20b"
  const val KIMI_K3 = "moonshotai/kimi-k3"
  const val KIMI_K2_6 = "moonshotai/kimi-k2.6"
  const val GRANITE_34B_CODE = "ibm/granite-34b-code-instruct"
  const val GRANITE_8B = "ibm/granite-3.0-8b-instruct"
  const val GEMMA_3_12B = "google/gemma-3-12b-it"
  const val CODEGEMMA_7B = "google/codegemma-7b"
  const val JAMBA_1_5 = "ai21labs/jamba-1.5-large-instruct"
  const val YI_LARGE = "01-ai/yi-large"

  val CATALOG: List<NvidiaModelEntry> = listOf(
    NvidiaModelEntry(
      id = GLM_5_3,
      name = "GLM 5.3",
      category = "Agent & Code",
      description = "Flagship coding and agentic model with verified structured tool calling",
      badge = "DEFAULT"
    ),
    NvidiaModelEntry(
      id = GLM_5_3_FLASH,
      name = "GLM 5.3 Flash",
      category = "Agent & Code",
      description = "Fast, low-latency agentic model for high-throughput workflows",
      badge = "FAST"
    ),
    NvidiaModelEntry(
      id = CODESTRAL_22B,
      name = "Codestral 22B",
      category = "Agent & Code",
      description = "Mistral's state-of-the-art coding and script synthesis specialist",
      badge = "CODE"
    ),
    NvidiaModelEntry(
      id = CODELLAMA_70B,
      name = "CodeLlama 70B",
      category = "Agent & Code",
      description = "Meta's flagship 70B coding model for architecture and scripts",
      badge = "CODE"
    ),
    NvidiaModelEntry(
      id = GRANITE_34B_CODE,
      name = "Granite 34B Code",
      category = "Agent & Code",
      description = "IBM enterprise code generation and command synthesis model",
      badge = "ENTERPRISE"
    ),
    NvidiaModelEntry(
      id = NEMOTRON_70B,
      name = "Llama Nemotron 70B",
      category = "Llama & Nemotron",
      description = "NVIDIA's customized Llama model optimized for AI agents & alignment",
      badge = "NVIDIA"
    ),
    NvidiaModelEntry(
      id = NEMOTRON_51B,
      name = "Nemotron 51B",
      category = "Llama & Nemotron",
      description = "NVIDIA mid-sized instruct model balanced for speed and reasoning",
      badge = "BALANCED"
    ),
    NvidiaModelEntry(
      id = NEMOTRON_4_340B,
      name = "Nemotron 4 340B",
      category = "Llama & Nemotron",
      description = "NVIDIA's massive 340B instruction model with deep world knowledge",
      badge = "340B"
    ),
    NvidiaModelEntry(
      id = MISTRAL_LARGE_2,
      name = "Mistral Large 2",
      category = "Mistral & MoE",
      description = "128k context multilingual flagship with precise tool invocation",
      badge = "128K"
    ),
    NvidiaModelEntry(
      id = MIXTRAL_8X22B,
      name = "Mixtral 8x22B",
      category = "Mistral & MoE",
      description = "High-throughput sparse Mixture-of-Experts architecture",
      badge = "MoE"
    ),
    NvidiaModelEntry(
      id = MISTRAL_NEMO_12B,
      name = "Mistral NeMo 12B",
      category = "Mistral & MoE",
      description = "Collaborative NVIDIA & Mistral compact 12B instruction model",
      badge = "12B"
    ),
    NvidiaModelEntry(
      id = MISTRAL_7B,
      name = "Mistral 7B v0.3",
      category = "Mistral & MoE",
      description = "Lightweight, responsive model with native function calling",
      badge = "LIGHT"
    ),
    NvidiaModelEntry(
      id = KIMI_K3,
      name = "Kimi K3",
      category = "Reasoning & Long-Context",
      description = "Moonshot AI agentic model with extended context comprehension",
      badge = "AGENT"
    ),
    NvidiaModelEntry(
      id = KIMI_K2_6,
      name = "Kimi K2.6",
      category = "Reasoning & Long-Context",
      description = "Fast Moonshot model tuned for conversational agent tasks",
      badge = "FAST"
    ),
    NvidiaModelEntry(
      id = GPT_OSS_20B,
      name = "GPT-OSS 20B",
      category = "Reasoning & Long-Context",
      description = "OpenAI open weights model hosted on NVIDIA infrastructure",
      badge = "OPENAI"
    ),
    NvidiaModelEntry(
      id = JAMBA_1_5,
      name = "Jamba 1.5 Large",
      category = "Reasoning & Long-Context",
      description = "AI21 Labs hybrid SSM-Transformer architecture for long sequences",
      badge = "HYBRID"
    ),
    NvidiaModelEntry(
      id = GEMMA_3_12B,
      name = "Gemma 3 12B",
      category = "Google & Other",
      description = "Google's lightweight instruction-tuned open model",
      badge = "GOOGLE"
    ),
    NvidiaModelEntry(
      id = CODEGEMMA_7B,
      name = "CodeGemma 7B",
      category = "Google & Other",
      description = "Google specialized coding and code completion model",
      badge = "GOOGLE"
    ),
    NvidiaModelEntry(
      id = YI_LARGE,
      name = "Yi Large",
      category = "Google & Other",
      description = "01.AI frontier multilingual foundation model",
      badge = "MULTILINGUAL"
    ),
    NvidiaModelEntry(
      id = GRANITE_8B,
      name = "Granite 3.0 8B",
      category = "Google & Other",
      description = "IBM compact enterprise instruction tuned model",
      badge = "LIGHT"
    )
  )

  val PRESETS: List<String> = CATALOG.map { it.id }

  val CATEGORIES: List<String> = listOf("All") + CATALOG.map { it.category }.distinct()
}

/**
 * Production-quality NVIDIA NIM LLM Provider.
 * Connects AgentEngine to NVIDIA's hosted OpenAI-compatible chat completions API
 * using native structured tool calling.
 *
 * Responsibilities:
 * - Authentication via Bearer token
 * - Request serialization (system context, user intent, conversation history, tool results)
 * - Native tool schemas from canonical ToolRegistry
 * - Native tool-call response parsing (callId, toolName, arguments)
 * - Strict error normalization (HTTP 400, 401, 403, 404, 429, 500+, timeouts, malformed JSON)
 * - Context discipline and credential security
 */
class NvidiaNimProvider(
  initialConfig: NvidiaNimConfig = NvidiaNimConfig()
) : LLMProvider {

  @Volatile
  private var currentConfig: NvidiaNimConfig = initialConfig

  val config: NvidiaNimConfig get() = currentConfig

  override val providerName: String
    get() = "NVIDIA NIM (${currentConfig.model})"

  fun updateConfig(block: (NvidiaNimConfig) -> NvidiaNimConfig) {
    currentConfig = block(currentConfig)
  }

  fun setApiKey(newKey: String) {
    currentConfig = currentConfig.copy(apiKey = newKey.trim())
  }

  fun setModel(newModel: String) {
    currentConfig = currentConfig.copy(model = newModel.trim())
  }

  fun setBaseUrl(newBaseUrl: String) {
    currentConfig = currentConfig.copy(baseUrl = newBaseUrl.trim())
  }

  fun hasApiKey(): Boolean = currentConfig.apiKey.isNotBlank()

  override suspend fun decideNextAction(
    systemPrompt: String,
    taskIntent: String,
    messages: List<AgentMessage>,
    tools: List<Tool>
  ): LLMDecision = withContext(Dispatchers.IO) {
    val activeConfig = currentConfig

    if (activeConfig.apiKey.isBlank()) {
      return@withContext LLMDecision.ProviderError(
        "NVIDIA API key is not configured. Please supply your NVIDIA API key in Settings or configure via .env / BuildConfig.NVIDIA_API_KEY."
      )
    }

    try {
      val baseUrlClean = activeConfig.baseUrl.trimEnd('/')
      val endpoint = "$baseUrlClean/chat/completions"

      val requestJson = JSONObject()
      requestJson.put("model", activeConfig.model)

      // 1. Construct messages conforming to OpenAI / NVIDIA tool calling specifications
      val messagesArray = buildMessagesJson(systemPrompt, messages)
      requestJson.put("messages", messagesArray)

      // 2. Expose canonical registered tools as native OpenAI functions
      val toolsArray = buildToolsJson(tools)
      if (toolsArray.length() > 0) {
        requestJson.put("tools", toolsArray)
        requestJson.put("tool_choice", "auto")
      }

      // 3. Optional generation parameters
      activeConfig.temperature?.let { requestJson.put("temperature", it) }
      activeConfig.maxTokens?.let { requestJson.put("max_tokens", it) }
      activeConfig.topP?.let { requestJson.put("top_p", it) }

      // 4. Build HTTP POST Request
      val mediaType = "application/json; charset=utf-8".toMediaType()
      val requestBody = requestJson.toString().toRequestBody(mediaType)

      val request = Request.Builder()
        .url(endpoint)
        .addHeader("Authorization", "Bearer ${activeConfig.apiKey.trim()}")
        .addHeader("Content-Type", "application/json")
        .addHeader("Accept", "application/json")
        .post(requestBody)
        .build()

      val httpClient = OkHttpClient.Builder()
        .connectTimeout(activeConfig.timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(activeConfig.timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(activeConfig.timeoutSeconds, TimeUnit.SECONDS)
        .build()

      val response = httpClient.newCall(request).execute()
      val responseCode = response.code
      val responseBodyString = response.body?.string() ?: ""

      // 5. HTTP Error Handling and Normalization
      if (!response.isSuccessful) {
        val errorDetail = extractErrorDetail(responseBodyString, responseCode, activeConfig.apiKey)
        val normalizedMessage = when (responseCode) {
          400 -> "NVIDIA NIM bad request (HTTP 400): $errorDetail"
          401 -> "NVIDIA NIM authentication failed (HTTP 401). Please check your NVIDIA API key."
          403 -> "NVIDIA NIM access forbidden (HTTP 403). Your key may lack permissions for model '${activeConfig.model}'."
          404 -> "NVIDIA NIM model or endpoint not found (HTTP 404): '${activeConfig.model}'. Verify base URL and model ID."
          429 -> "NVIDIA NIM rate limit or quota exceeded (HTTP 429): $errorDetail"
          in 500..599 -> "NVIDIA NIM upstream server error (HTTP $responseCode): $errorDetail"
          else -> "NVIDIA NIM API error (HTTP $responseCode): $errorDetail"
        }
        return@withContext LLMDecision.ProviderError(normalizedMessage)
      }

      if (responseBodyString.isBlank()) {
        return@withContext LLMDecision.ProviderError("NVIDIA NIM returned an empty response body.")
      }

      // 6. JSON Response Parsing & Validation
      val rootJson = try {
        JSONObject(responseBodyString)
      } catch (e: Exception) {
        return@withContext LLMDecision.ProviderError("NVIDIA NIM returned malformed JSON: ${e.message}")
      }

      val choices = rootJson.optJSONArray("choices")
      if (choices == null || choices.length() == 0) {
        return@withContext LLMDecision.ProviderError("NVIDIA NIM response choices array is empty.")
      }

      val firstChoice = choices.optJSONObject(0)
        ?: return@withContext LLMDecision.ProviderError("NVIDIA NIM response choice[0] is not a valid JSON object.")

      val messageObj = firstChoice.optJSONObject("message")
        ?: return@withContext LLMDecision.ProviderError("NVIDIA NIM response choice missing required 'message' object.")

      val contentText = messageObj.optString("content", "")
      val reasoningText = messageObj.optString("reasoning_content", "")
        .ifBlank { messageObj.optString("reasoning", "") }

      val combinedThought = when {
        reasoningText.isNotBlank() && contentText.isNotBlank() -> "[Reasoning] $reasoningText\n$contentText"
        reasoningText.isNotBlank() -> reasoningText
        else -> contentText
      }

      // 7. Extract Native Tool Calls
      val toolCallsArray = messageObj.optJSONArray("tool_calls")
      val parsedToolCalls = mutableListOf<ToolCall>()

      if (toolCallsArray != null && toolCallsArray.length() > 0) {
        for (i in 0 until toolCallsArray.length()) {
          val callItem = toolCallsArray.optJSONObject(i)
            ?: return@withContext LLMDecision.ProviderError("NVIDIA NIM tool_calls item at index $i is malformed.")

          val callId = callItem.optString("id").trim()
          if (callId.isBlank()) {
            return@withContext LLMDecision.ProviderError("NVIDIA NIM tool call at index $i is missing required 'id'.")
          }

          val funcObj = callItem.optJSONObject("function")
            ?: return@withContext LLMDecision.ProviderError("NVIDIA NIM tool call '$callId' missing 'function' object.")

          val funcName = funcObj.optString("name").trim()
          if (funcName.isBlank()) {
            return@withContext LLMDecision.ProviderError("NVIDIA NIM tool call '$callId' missing required function 'name'.")
          }

          val argsRaw = funcObj.opt("arguments")
          val argsMap = when (argsRaw) {
            is JSONObject -> jsonObjectToMap(argsRaw)
            is String -> {
              val trimmed = argsRaw.trim()
              if (trimmed.isNotBlank() && trimmed != "{}") {
                try {
                  val parsedJson = JSONObject(trimmed)
                  jsonObjectToMap(parsedJson)
                } catch (e: Exception) {
                  return@withContext LLMDecision.ProviderError(
                    "NVIDIA NIM tool call '$funcName' ($callId) returned malformed arguments JSON: ${e.message}"
                  )
                }
              } else {
                emptyMap()
              }
            }
            null, JSONObject.NULL -> emptyMap()
            else -> {
              return@withContext LLMDecision.ProviderError(
                "NVIDIA NIM tool call '$funcName' ($callId) returned unexpected arguments type: ${argsRaw.javaClass.simpleName}"
              )
            }
          }

          parsedToolCalls.add(
            ToolCall(
              callId = callId,
              toolName = funcName,
              arguments = argsMap
            )
          )
        }
      }

      // 8. Normalize decision to LLMDecision
      if (parsedToolCalls.isNotEmpty()) {
        LLMDecision.ExecuteTool(
          toolCalls = parsedToolCalls,
          thought = combinedThought.ifBlank { "Executing ${parsedToolCalls.size} tool call(s)." }
        )
      } else {
        val finalConclusion = contentText.ifBlank { combinedThought }
        if (finalConclusion.isBlank()) {
          LLMDecision.ProviderError("NVIDIA NIM returned neither tool calls nor message content.")
        } else {
          LLMDecision.Complete(
            conclusion = finalConclusion,
            thought = if (reasoningText.isNotBlank()) reasoningText else "Agent concluded task."
          )
        }
      }
    } catch (e: SocketTimeoutException) {
      LLMDecision.ProviderError("NVIDIA NIM request timed out after ${activeConfig.timeoutSeconds}s: ${e.message}")
    } catch (e: UnknownHostException) {
      LLMDecision.ProviderError("NVIDIA NIM network connection failure: Unable to resolve host '${activeConfig.baseUrl}'.")
    } catch (e: IOException) {
      LLMDecision.ProviderError("NVIDIA NIM connection failure: ${e.message}")
    } catch (ce: CancellationException) {
      throw ce
    } catch (e: Exception) {
      LLMDecision.ProviderError("NVIDIA NIM unexpected error: ${e.message}")
    }
  }

  /**
   * Generates native OpenAI-compatible tool definitions directly from canonical ToolRegistry tools.
   */
  private fun buildToolsJson(tools: List<Tool>): JSONArray {
    val toolsArray = JSONArray()
    for (tool in tools) {
      val toolObj = JSONObject()
      toolObj.put("type", "function")

      val funcObj = JSONObject()
      funcObj.put("name", tool.name)
      funcObj.put("description", tool.description)

      val parametersObj = JSONObject()
      parametersObj.put("type", "object")
      val propertiesObj = JSONObject()
      val requiredList = JSONArray()

      for (param in tool.schema.parameters) {
        val pObj = JSONObject()
        pObj.put("type", param.type.lowercase())
        pObj.put("description", param.description)
        if (!param.enumValues.isNullOrEmpty()) {
          val enumArr = JSONArray()
          param.enumValues.forEach { enumArr.put(it) }
          pObj.put("enum", enumArr)
        }
        propertiesObj.put(param.name, pObj)
        if (param.required) {
          requiredList.put(param.name)
        }
      }

      parametersObj.put("properties", propertiesObj)
      parametersObj.put("required", requiredList)
      funcObj.put("parameters", parametersObj)

      toolObj.put("function", funcObj)
      toolsArray.put(toolObj)
    }
    return toolsArray
  }

  /**
   * Constructs the message history enforcing the OpenAI/NVIDIA assistant-tool association protocol.
   * Applies context window discipline while preserving the initial user goal and latest tool calls/results.
   */
  private fun buildMessagesJson(
    systemPrompt: String,
    messages: List<AgentMessage>
  ): JSONArray {
    val messagesArray = JSONArray()

    // 1. System instruction containing prioritized context
    if (systemPrompt.isNotBlank()) {
      val sysObj = JSONObject()
      sysObj.put("role", "system")
      sysObj.put("content", systemPrompt)
      messagesArray.put(sysObj)
    }

    // 2. Context window discipline: keep initial goal + most recent 14 messages
    val boundedMessages = if (messages.size > 16) {
      val initialUserMsg = messages.firstOrNull { it.role == MessageRole.USER }
      val recentTail = messages.takeLast(14)
      if (initialUserMsg != null && !recentTail.contains(initialUserMsg)) {
        listOf(initialUserMsg) + recentTail
      } else {
        recentTail
      }
    } else {
      messages
    }

    // 3. Serialize messages maintaining exact tool_call_id linkage
    for (msg in boundedMessages) {
      when (msg.role) {
        MessageRole.USER -> {
          val userObj = JSONObject()
          userObj.put("role", "user")
          userObj.put("content", msg.content)
          messagesArray.put(userObj)
        }

        MessageRole.SYSTEM -> {
          val sysObj = JSONObject()
          sysObj.put("role", "system")
          sysObj.put("content", msg.content)
          messagesArray.put(sysObj)
        }

        MessageRole.ASSISTANT -> {
          val assistantObj = JSONObject()
          assistantObj.put("role", "assistant")

          if (msg.toolCallId != null && msg.toolName != null) {
            // Assistant requested a native tool call
            if (msg.content.isNotBlank() && !msg.content.startsWith("Dispatched tool")) {
              assistantObj.put("content", msg.content)
            } else {
              assistantObj.put("content", JSONObject.NULL)
            }

            val toolCallsArr = JSONArray()
            val callObj = JSONObject()
            callObj.put("id", msg.toolCallId)
            callObj.put("type", "function")

            val funcObj = JSONObject()
            funcObj.put("name", msg.toolName)
            val argsJson = mapToJsonObject(msg.toolArgs ?: emptyMap())
            funcObj.put("arguments", argsJson.toString())
            callObj.put("function", funcObj)

            toolCallsArr.put(callObj)
            assistantObj.put("tool_calls", toolCallsArr)
          } else {
            assistantObj.put("content", msg.content)
          }
          messagesArray.put(assistantObj)
        }

        MessageRole.TOOL -> {
          // Native tool response linked to exact tool_call_id
          val toolObj = JSONObject()
          toolObj.put("role", "tool")
          toolObj.put("tool_call_id", msg.toolCallId ?: "")
          if (!msg.toolName.isNullOrBlank()) {
            toolObj.put("name", msg.toolName)
          }

          // Bound observation payload length to preserve token budget
          val boundedContent = if (msg.content.length > 8000) {
            msg.content.take(8000) + "\n...[truncated remainder of large observation]..."
          } else {
            msg.content
          }
          toolObj.put("content", boundedContent)
          messagesArray.put(toolObj)
        }
      }
    }

    return messagesArray
  }

  private fun extractErrorDetail(body: String, code: Int, apiKey: String): String {
    val raw = try {
      val errJson = JSONObject(body)
      when {
        errJson.has("error") -> {
          val errVal = errJson.get("error")
          if (errVal is JSONObject) {
            errVal.optString("message", body)
          } else {
            errVal.toString()
          }
        }
        errJson.has("detail") -> errJson.getString("detail")
        errJson.has("message") -> errJson.getString("message")
        else -> body.take(300)
      }
    } catch (_: Exception) {
      body.take(300).ifBlank { "HTTP $code" }
    }
    return if (apiKey.isNotBlank()) raw.replace(apiKey, "[REDACTED]") else raw
  }

  private fun jsonObjectToMap(jsonObj: JSONObject): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    val keys = jsonObj.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      map[key] = jsonValueToKotlin(jsonObj.get(key))
    }
    return map
  }

  private fun jsonValueToKotlin(value: Any?): Any? {
    return when (value) {
      null, JSONObject.NULL -> null
      is JSONObject -> jsonObjectToMap(value)
      is JSONArray -> {
        val list = mutableListOf<Any?>()
        for (i in 0 until value.length()) {
          list.add(jsonValueToKotlin(value.get(i)))
        }
        list
      }
      else -> value
    }
  }

  private fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
    val jsonObj = JSONObject()
    for ((key, value) in map) {
      jsonObj.put(key, kotlinToJsonValue(value))
    }
    return jsonObj
  }

  private fun kotlinToJsonValue(value: Any?): Any {
    return when (value) {
      null -> JSONObject.NULL
      is Map<*, *> -> {
        val obj = JSONObject()
        for ((k, v) in value) {
          if (k != null) {
            obj.put(k.toString(), kotlinToJsonValue(v))
          }
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
}
