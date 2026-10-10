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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

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
  val timeoutSeconds: Long = 40,
  val enableReasoning: Boolean = true
) {
  companion object {
    const val DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
    const val DEFAULT_MODEL = "z-ai/glm-5.3"
  }
}

/**
 * Comprehensive catalog of free hosted models on build.nvidia.com.
 */
data class NvidiaModelEntry(
  val id: String,
  val name: String,
  val category: String,
  val description: String,
  val badge: String = "",
  val isVerified: Boolean = true
)

object NvidiaNimModels {
  // Verified active models on free build key
  const val GLM_5_3 = "z-ai/glm-5.3"
  const val LLAMA_3_2_11B = "meta/llama-3.2-11b-vision-instruct"
  const val LLAMA_3_2_90B = "meta/llama-3.2-90b-vision-instruct"
  const val MUSE_GLIMMER_30B = "meta/muse-glimmer-30b"
  const val NEMOTRON_3_5_LIGHTNING = "nvidia/nemotron-3.5-lightning-30b-a3b"
  const val NEMOTRON_3_SUPER = "nvidia/nemotron-3-super-120b-a12b"
  const val NEMOTRON_3_ULTRA = "nvidia/nemotron-3-ultra-550b-a55b"
  const val DIFFUSION_GEMMA_26B = "google/diffusiongemma-26b-a4b-it"
  const val GPT_OSS_20B = "openai/gpt-oss-20b"
  const val RIVA_TRANSLATE_4B = "nvidia/riva-translate-4b-instruct-v2"
  const val NEMOTRON_PARSE_2_0 = "nvidia/nemotron-parse-2.0"
  const val ISING_CALIBRATION_31B = "nvidia/ising-calibration-1.5-31b"

  // Additional popular models from build.nvidia.com
  const val GLM_5_3_FLASH = "z-ai/glm-5.3-flash"
  const val NEMOTRON_70B = "nvidia/llama-3.1-nemotron-70b-instruct"
  const val MISTRAL_LARGE_2 = "mistralai/mistral-large-2-instruct"
  const val CODESTRAL_22B = "mistralai/codestral-22b-instruct-v0.1"
  const val KIMI_K3 = "moonshotai/kimi-k3"

  val CATALOG: List<NvidiaModelEntry> = listOf(
    NvidiaModelEntry(
      id = GLM_5_3,
      name = "GLM 5.3",
      category = "Agent & Coding",
      description = "Flagship coding and agentic model with verified native structured tool calling",
      badge = "DEFAULT",
      isVerified = true
    ),
    NvidiaModelEntry(
      id = LLAMA_3_2_11B,
      name = "Llama 3.2 11B Vision",
      category = "Agent & Coding",
      description = "Meta's multimodal instruction model with fast inference and script generation",
      badge = "VERIFIED",
      isVerified = true
    ),
    NvidiaModelEntry(
      id = LLAMA_3_2_90B,
      name = "Llama 3.2 90B Vision",
      category = "Agent & Coding",
      description = "Meta's flagship 90B vision and high-order reasoning architecture",
      badge = "90B",
      isVerified = true
    ),
    NvidiaModelEntry(
      id = MUSE_GLIMMER_30B,
      name = "Muse Glimmer 30B",
      category = "Agent & Coding",
      description = "Meta's creative, code synthesis, and structured transformation model",
      badge = "30B",
      isVerified = true
    ),
    NvidiaModelEntry(
      id = NEMOTRON_3_5_LIGHTNING,
      name = "Nemotron 3.5 Lightning",
      category = "NVIDIA Nemotron",
      description = "NVIDIA 30B fast instruction model tuned for agents and workflow synthesis",
      badge = "NVIDIA",
      isVerified = true
    ),
    NvidiaModelEntry(
      id = NEMOTRON_3_SUPER,
      name = "Nemotron 3 Super 120B",
      category = "NVIDIA Nemotron",
      description = "NVIDIA 120B large-scale reasoning and deep problem solving architecture",
      badge = "120B",
      isVerified = true
    ),
    NvidiaModelEntry(
      id = NEMOTRON_3_ULTRA,
      name = "Nemotron 3 Ultra 550B",
      category = "NVIDIA Nemotron",
      description = "NVIDIA's frontier 550B model with comprehensive domain capabilities",
      badge = "550B",
      isVerified = true
    ),
    NvidiaModelEntry(
      id = GPT_OSS_20B,
      name = "GPT-OSS 20B",
      category = "Reasoning & Open",
      description = "OpenAI open weights model hosted with high throughput on NVIDIA infrastructure",
      badge = "OPENAI",
      isVerified = true
    ),
    NvidiaModelEntry(
      id = DIFFUSION_GEMMA_26B,
      name = "Diffusion Gemma 26B",
      category = "Reasoning & Open",
      description = "Google's 26B instruction model optimized for deep contextual reasoning",
      badge = "GOOGLE",
      isVerified = true
    ),
    NvidiaModelEntry(
      id = NEMOTRON_PARSE_2_0,
      name = "Nemotron Parse 2.0",
      category = "Specialist",
      description = "NVIDIA specialist model for parsing complex data tables and documents",
      badge = "PARSE",
      isVerified = true
    ),
    NvidiaModelEntry(
      id = RIVA_TRANSLATE_4B,
      name = "Riva Translate 4B",
      category = "Specialist",
      description = "NVIDIA multilingual translation and cross-lingual script assistant",
      badge = "4B",
      isVerified = true
    ),
    NvidiaModelEntry(
      id = ISING_CALIBRATION_31B,
      name = "Ising Calibration 31B",
      category = "Specialist",
      description = "Scientific and quantitative calibration model for math and data pipelines",
      badge = "31B",
      isVerified = true
    ),
    NvidiaModelEntry(
      id = GLM_5_3_FLASH,
      name = "GLM 5.3 Flash",
      category = "Agent & Coding",
      description = "High-throughput fast variant of GLM 5.3",
      badge = "FAST",
      isVerified = false
    ),
    NvidiaModelEntry(
      id = NEMOTRON_70B,
      name = "Llama Nemotron 70B",
      category = "NVIDIA Nemotron",
      description = "NVIDIA customized Llama 3.1 70B instruct alignment model",
      badge = "70B",
      isVerified = false
    ),
    NvidiaModelEntry(
      id = MISTRAL_LARGE_2,
      name = "Mistral Large 2",
      category = "Reasoning & Open",
      description = "128k context multilingual flagship model",
      badge = "128K",
      isVerified = false
    ),
    NvidiaModelEntry(
      id = CODESTRAL_22B,
      name = "Codestral 22B",
      category = "Agent & Coding",
      description = "Mistral coding and script synthesis specialist",
      badge = "CODE",
      isVerified = false
    ),
    NvidiaModelEntry(
      id = KIMI_K3,
      name = "Kimi K3",
      category = "Reasoning & Open",
      description = "Moonshot AI agentic model with extended context",
      badge = "AGENT",
      isVerified = false
    )
  )

  val PRESETS: List<String> = CATALOG.map { it.id }

  val CATEGORIES: List<String> = listOf("All") + CATALOG.map { it.category }.distinct()
}

/**
 * Production-quality NVIDIA NIM LLM Provider.
 * Connects AgentEngine to NVIDIA's hosted OpenAI-compatible chat completions API
 * with multi-format tool calling (native, embedded python tags, JSON, and ReAct)
 * and seamless fallback recovery.
 */
class NvidiaNimProvider(
  initialConfig: NvidiaNimConfig = NvidiaNimConfig()
) : LLMProvider {

  private val sharedHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

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

    // Candidate models to try in sequence: requested model first, then capability-verified coding & reasoning models only
    // Exclude multimodal vision-only models (like llama-3.2-11b-vision-instruct) from reasoning fallback
    val candidateModels = buildList {
      add(activeConfig.model)
      val verifiedReasoningFallbacks = listOf(
        NvidiaNimConfig.DEFAULT_MODEL,
        NvidiaNimModels.GLM_5_3_FLASH,
        NvidiaNimModels.NEMOTRON_3_5_LIGHTNING,
        NvidiaNimModels.CODESTRAL_22B,
        NvidiaNimModels.MISTRAL_LARGE_2,
        NvidiaNimModels.KIMI_K3
      )
      for (f in verifiedReasoningFallbacks) {
        if (!contains(f) && !f.contains("vision")) add(f)
      }
    }

    var lastError = "Unknown error"

    for ((modelIndex, modelCandidate) in candidateModels.withIndex()) {
      val isFallbackModel = modelIndex > 0
      val retryAttempts = if (isFallbackModel) 1 else 2

      // Context-injection for fallback models so they don't act blind
      val effectiveSystemPrompt = if (isFallbackModel) {
        """
        $systemPrompt
        
        [RECOVERY ROUTING CONTEXT]:
        You are acting as a verified reasoning & tool-dispatching fallback for task: '$taskIntent'.
        Never output placeholder section headers as content. Read workspace deliverables, notes, or research files before taking action.
        """.trimIndent()
      } else systemPrompt

      for (attempt in 0..retryAttempts) {
        if (attempt > 0) {
          kotlinx.coroutines.delay(1200L * attempt) // Exponential backoff on dropped connection
        }

        val result = executeChatCompletion(
          modelToUse = modelCandidate,
          systemPrompt = effectiveSystemPrompt,
          taskIntent = taskIntent,
          messages = messages,
          tools = tools,
          useNativeTools = true
        )

        when (result) {
          is LLMDecision.ExecuteTool -> {
            val thoughtNotice = if (isFallbackModel) {
              "[Notice] Recovered from endpoint failure on '${activeConfig.model}'. Seamlessly routed to model '$modelCandidate'.\n${result.thought ?: ""}"
            } else result.thought
            return@withContext result.copy(thought = thoughtNotice)
          }
          is LLMDecision.Complete -> {
            val thoughtNotice = if (isFallbackModel) {
              "[Notice] Recovered from endpoint failure on '${activeConfig.model}'. Seamlessly routed to model '$modelCandidate'.\n${result.thought ?: ""}"
            } else result.thought
            return@withContext result.copy(thought = thoughtNotice)
          }
          is LLMDecision.ProviderError -> {
            lastError = result.message
            // If the error is fatal auth (401), don't retry other models with same key
            if (result.message.contains("401") || result.message.contains("authentication failed")) {
              return@withContext result
            }
            // If model does not exist (404), is forbidden (403), or not found, break immediately to fallback model
            if (result.message.contains("404") || result.message.contains("403") || result.message.contains("not found")) {
              break
            }
            // If error is non-recoverable on the current model, break to next model candidate
            if (!isRecoverableModelError(result.message)) {
              break
            }
            // Otherwise transient error: retry same model with exponential backoff
          }
        }
      }
    }

    return@withContext LLMDecision.ProviderError("NVIDIA NIM endpoint dropped or timed out after multiple retries and model fallbacks: $lastError")
  }

  private fun isRecoverableModelError(errorMessage: String): Boolean {
    val lower = errorMessage.lowercase()
    return lower.contains("timeout") ||
      lower.contains("timed out") ||
      lower.contains("connection") ||
      lower.contains("stream") ||
      lower.contains("reset") ||
      lower.contains("429") ||
      lower.contains("500") ||
      lower.contains("502") ||
      lower.contains("503") ||
      lower.contains("504")
  }

  private suspend fun executeChatCompletion(
    modelToUse: String,
    systemPrompt: String,
    taskIntent: String,
    messages: List<AgentMessage>,
    tools: List<Tool>,
    useNativeTools: Boolean
  ): LLMDecision {
    val activeConfig = currentConfig

    try {
      val baseUrlClean = activeConfig.baseUrl.trimEnd('/')
      val endpoint = "$baseUrlClean/chat/completions"

      val requestJson = JSONObject()
      requestJson.put("model", modelToUse)

      // 1. Construct messages conforming to OpenAI / NVIDIA tool calling specifications
      val messagesArray = buildMessagesJson(systemPrompt, messages)
      requestJson.put("messages", messagesArray)

      // 2. Expose canonical registered tools
      if (useNativeTools) {
        val toolsArray = buildToolsJson(tools)
        if (toolsArray.length() > 0) {
          requestJson.put("tools", toolsArray)
          requestJson.put("tool_choice", "auto")
        }
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

      val httpClient = if (activeConfig.timeoutSeconds != 60L) {
        sharedHttpClient.newBuilder()
          .connectTimeout(activeConfig.timeoutSeconds, TimeUnit.SECONDS)
          .readTimeout(activeConfig.timeoutSeconds, TimeUnit.SECONDS)
          .writeTimeout(activeConfig.timeoutSeconds, TimeUnit.SECONDS)
          .build()
      } else {
        sharedHttpClient
      }

      val (responseCode, isSuccessful, responseBodyString) = httpClient.newCall(request).execute().use { response ->
        Triple(response.code, response.isSuccessful, response.body?.string() ?: "")
      }

      // 5. HTTP Error Handling and Retry
      if (!isSuccessful) {
        val errorDetail = extractErrorDetail(responseBodyString, responseCode, activeConfig.apiKey)

        // If tools are rejected by this model (HTTP 400 with 'tools' or 'extra input'), retry without native tools
        if (useNativeTools && responseCode == 400 && (errorDetail.contains("tool") || errorDetail.contains("extra input") || errorDetail.contains("parameters"))) {
          return executeChatCompletion(
            modelToUse = modelToUse,
            systemPrompt = "$systemPrompt\n\nAvailable tools:\n${formatToolsTextDescription(tools)}",
            taskIntent = taskIntent,
            messages = messages,
            tools = tools,
            useNativeTools = false
          )
        }

        val normalizedMessage = when (responseCode) {
          400 -> "NVIDIA NIM bad request (HTTP 400): $errorDetail"
          401 -> "NVIDIA NIM authentication failed (HTTP 401). Please check your NVIDIA API key."
          403 -> "NVIDIA NIM access forbidden (HTTP 403). Your key may lack permissions for model '$modelToUse'."
          404 -> "NVIDIA NIM model or endpoint not found (HTTP 404): '$modelToUse'. $errorDetail"
          429 -> "NVIDIA NIM rate limit or quota exceeded (HTTP 429): $errorDetail"
          in 500..599 -> "NVIDIA NIM upstream server error (HTTP $responseCode): $errorDetail"
          else -> "NVIDIA NIM API error (HTTP $responseCode): $errorDetail"
        }
        return LLMDecision.ProviderError(normalizedMessage)
      }

      if (responseBodyString.isBlank()) {
        return LLMDecision.ProviderError("NVIDIA NIM returned an empty response body.")
      }

      // 6. JSON Response Parsing & Validation
      val rootJson = try {
        JSONObject(responseBodyString)
      } catch (e: Exception) {
        return LLMDecision.ProviderError("NVIDIA NIM returned malformed JSON: ${e.message}")
      }

      val choices = rootJson.optJSONArray("choices")
      if (choices == null || choices.length() == 0) {
        return LLMDecision.ProviderError("NVIDIA NIM response choices array is empty.")
      }

      val firstChoice = choices.optJSONObject(0)
        ?: return LLMDecision.ProviderError("NVIDIA NIM response choice[0] is not a valid JSON object.")

      val messageObj = firstChoice.optJSONObject("message")
        ?: return LLMDecision.ProviderError("NVIDIA NIM response choice missing required 'message' object.")

      val contentText = messageObj.optString("content", "")
      val reasoningText = messageObj.optString("reasoning_content", "")
        .ifBlank { messageObj.optString("reasoning", "") }

      val combinedThought = when {
        reasoningText.isNotBlank() && contentText.isNotBlank() -> "[Reasoning] $reasoningText\n$contentText"
        reasoningText.isNotBlank() -> reasoningText
        else -> contentText
      }

      // 7. Multi-Format Tool Calls Extraction
      val parsedToolCalls = extractToolCalls(messageObj, contentText, tools)

      // 8. Normalize decision to LLMDecision
      return if (parsedToolCalls.isNotEmpty()) {
        LLMDecision.ExecuteTool(
          toolCalls = parsedToolCalls,
          // Native structured tool calls are already authoritative; visible reasoning is
          // optional and many NVIDIA-hosted models legitimately return it as null.
          thought = combinedThought.ifBlank { "Executing ${parsedToolCalls.size} requested tool call(s)." }
        )
      } else {
        val finalConclusion = cleanContentConclusion(contentText).ifBlank { combinedThought }
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
      return LLMDecision.ProviderError("NVIDIA NIM request timed out after ${activeConfig.timeoutSeconds}s: ${e.message}")
    } catch (e: UnknownHostException) {
      return LLMDecision.ProviderError("NVIDIA NIM network connection failure: Unable to resolve host '${activeConfig.baseUrl}'.")
    } catch (e: IOException) {
      return LLMDecision.ProviderError("NVIDIA NIM connection failure: ${e.message}")
    } catch (ce: CancellationException) {
      throw ce
    } catch (e: Exception) {
      return LLMDecision.ProviderError("NVIDIA NIM unexpected error: ${e.message}")
    }
  }

  /**
   * Multi-format tool call extractor.
   * Supports:
   * 1. Native OpenAI message.tool_calls array
   * 2. Meta/Llama <|python_tag|> embedded tool call syntax
   * 3. Markdown JSON blocks: ```json {"name": "...", "parameters": {...}} ```
   * 4. Raw JSON object with tool/name and parameters
   * 5. ReAct format: Action: <tool>\nAction Input: <json>
   */
  private fun extractToolCalls(
    messageObj: JSONObject,
    contentText: String,
    availableTools: List<Tool>
  ): List<ToolCall> {
    val results = mutableListOf<ToolCall>()
    val toolNames = availableTools.map { it.name }.toSet()

    // 1. Native tool_calls
    val toolCallsArray = messageObj.optJSONArray("tool_calls")
    if (toolCallsArray != null && toolCallsArray.length() > 0) {
      for (i in 0 until toolCallsArray.length()) {
        val callItem = toolCallsArray.optJSONObject(i) ?: continue
        val callId = callItem.optString("id").ifBlank { "call_${UUID.randomUUID()}" }
        val funcObj = callItem.optJSONObject("function") ?: continue
        val funcName = funcObj.optString("name").trim()
        if (funcName.isNotBlank()) {
          val argsRaw = funcObj.opt("arguments")
          val argsMap = parseArguments(argsRaw)
          results.add(ToolCall(callId = callId, toolName = funcName, arguments = argsMap))
        }
      }
      if (results.isNotEmpty()) return results
    }

    // 2. Meta/Llama 3 <|python_tag|> format
    if (contentText.contains("<|python_tag|>")) {
      val tagPattern = Pattern.compile("<\\|python_tag\\|>\\s*(\\{.*?\\})", Pattern.DOTALL)
      val matcher = tagPattern.matcher(contentText)
      while (matcher.find()) {
        val jsonStr = matcher.group(1) ?: continue
        parseSingleToolJson(jsonStr, toolNames)?.let { results.add(it) }
      }
      if (results.isNotEmpty()) return results
    }

    // 3. Embedded JSON markdown block ```json ... ```
    if (contentText.contains("```")) {
      val codeBlockPattern = Pattern.compile("```(?:json)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL)
      val matcher = codeBlockPattern.matcher(contentText)
      while (matcher.find()) {
        val jsonStr = matcher.group(1) ?: continue
        parseSingleToolJson(jsonStr, toolNames)?.let { results.add(it) }
      }
      if (results.isNotEmpty()) return results
    }

    // 4. Raw JSON object in text
    val jsonCandidate = contentText.trim()
    if (jsonCandidate.startsWith("{") && jsonCandidate.endsWith("}")) {
      parseSingleToolJson(jsonCandidate, toolNames)?.let {
        results.add(it)
        return results
      }
    }

    // 5. ReAct format: Action: <name>\nAction Input: <json>
    if (contentText.contains("Action:")) {
      val reactPattern = Pattern.compile("Action:\\s*([a-zA-Z0-9_]+)\\s*Action Input:\\s*(\\{.*?\\})", Pattern.DOTALL)
      val matcher = reactPattern.matcher(contentText)
      while (matcher.find()) {
        val actionName = matcher.group(1)?.trim() ?: continue
        val actionInput = matcher.group(2)?.trim() ?: continue
        val argsMap = try {
          jsonObjectToMap(JSONObject(actionInput))
        } catch (_: Exception) {
          emptyMap()
        }
        results.add(
          ToolCall(
            callId = "call_${UUID.randomUUID()}",
            toolName = actionName,
            arguments = argsMap
          )
        )
      }
      if (results.isNotEmpty()) return results
    }

    return results
  }

  private fun parseSingleToolJson(jsonStr: String, knownToolNames: Set<String>): ToolCall? {
    return try {
      val obj = JSONObject(jsonStr)
      val name = obj.optString("name", obj.optString("tool", "")).trim()
      if (name.isNotBlank() && (knownToolNames.isEmpty() || knownToolNames.contains(name))) {
        val argsObj = obj.optJSONObject("parameters")
          ?: obj.optJSONObject("arguments")
          ?: obj.optJSONObject("args")
          ?: obj
        val argsMap = jsonObjectToMap(argsObj).filterKeys { it != "name" && it != "tool" }
        ToolCall(
          callId = "call_${UUID.randomUUID()}",
          toolName = name,
          arguments = argsMap
        )
      } else {
        null
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun parseArguments(argsRaw: Any?): Map<String, Any?> {
    return when (argsRaw) {
      is JSONObject -> jsonObjectToMap(argsRaw)
      is String -> {
        val trimmed = argsRaw.trim()
        if (trimmed.isNotBlank() && trimmed != "{}") {
          try {
            val parsedJson = JSONObject(trimmed)
            jsonObjectToMap(parsedJson)
          } catch (_: Exception) {
            emptyMap()
          }
        } else {
          emptyMap()
        }
      }
      null, JSONObject.NULL -> emptyMap()
      else -> emptyMap()
    }
  }

  private fun cleanContentConclusion(raw: String): String {
    return raw.replace("<|python_tag|>", "")
      .replace(Regex("<\\|.*?\\|>"), "")
      .trim()
  }

  private fun formatToolsTextDescription(tools: List<Tool>): String {
    return tools.joinToString("\n\n") { tool ->
      val params = tool.schema.parameters.joinToString(", ") { "${it.name}: ${it.type}${if (it.required) " (required)" else ""}" }
      "- ${tool.name}($params): ${tool.description}"
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

    // 2. Context window discipline: keep initial goal + most recent messages, ensuring tool calls and responses are never orphaned
    val boundedMessages = if (messages.size > 24) {
      val initialUserMsg = messages.firstOrNull { it.role == MessageRole.USER }
      var tailStartIndex = messages.size - 20
      while (tailStartIndex > 0 && messages[tailStartIndex].role == MessageRole.TOOL) {
        tailStartIndex--
      }
      val recentTail = messages.subList(tailStartIndex, messages.size)
      if (initialUserMsg != null && !recentTail.contains(initialUserMsg)) {
        listOf(initialUserMsg) + recentTail
      } else {
        recentTail
      }
    } else {
      messages
    }

    // 3. Serialize messages maintaining exact tool_call_id linkage.
    // Consecutive assistant tool-call records are folded into one assistant message,
    // because OpenAI-compatible APIs expect the whole requested batch together before
    // any corresponding role=tool observations.
    var messageIndex = 0
    while (messageIndex < boundedMessages.size) {
      val msg = boundedMessages[messageIndex]
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
            // Assistant requested one or more native tool calls.
            if (msg.content.isNotBlank() && !msg.content.startsWith("Dispatched tool")) {
              assistantObj.put("content", msg.content)
            } else {
              assistantObj.put("content", JSONObject.NULL)
            }

            val toolCallsArr = JSONArray()
            var batchIndex = messageIndex
            while (batchIndex < boundedMessages.size) {
              val batchMessage = boundedMessages[batchIndex]
              if (batchMessage.role != MessageRole.ASSISTANT ||
                batchMessage.toolCallId == null || batchMessage.toolName == null
              ) break

              val callObj = JSONObject()
              callObj.put("id", batchMessage.toolCallId)
              callObj.put("type", "function")

              val funcObj = JSONObject()
              funcObj.put("name", batchMessage.toolName)
              val argsJson = mapToJsonObject(batchMessage.toolArgs ?: emptyMap())
              funcObj.put("arguments", argsJson.toString())
              callObj.put("function", funcObj)
              toolCallsArr.put(callObj)
              batchIndex++
            }
            assistantObj.put("tool_calls", toolCallsArr)
            messageIndex = batchIndex - 1
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
      messageIndex++
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

  /**
   * Health check and live model discovery for the configured base URL and API key.
   */
  suspend fun fetchLiveModels(): Result<List<String>> = withContext(Dispatchers.IO) {
    val activeConfig = currentConfig
    if (activeConfig.apiKey.isBlank()) {
      return@withContext Result.failure(IllegalStateException("API key is blank."))
    }
    try {
      val baseUrlClean = activeConfig.baseUrl.trimEnd('/')
      val endpoint = "$baseUrlClean/models"
      val request = Request.Builder()
        .url(endpoint)
        .addHeader("Authorization", "Bearer ${activeConfig.apiKey.trim()}")
        .addHeader("Accept", "application/json")
        .get()
        .build()

      val bodyStr = sharedHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
        }
        response.body?.string() ?: ""
      }
      val json = JSONObject(bodyStr)
      val dataArr = json.optJSONArray("data") ?: JSONArray()
      val list = mutableListOf<String>()
      for (i in 0 until dataArr.length()) {
        val item = dataArr.optJSONObject(i)
        val id = item?.optString("id")
        if (!id.isNullOrBlank()) {
          list.add(id)
        }
      }
      Result.success(list)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}
