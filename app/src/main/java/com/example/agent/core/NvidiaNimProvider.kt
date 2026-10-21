package com.example.agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Supports a pool of up to 15 API keys for round-robin rotation and automatic failover.
 */
data class NvidiaNimConfig(
  val baseUrl: String = DEFAULT_BASE_URL,
  val model: String = DEFAULT_MODEL,
  val apiKey: String = "",
  val apiKeys: List<String> = if (apiKey.isNotBlank()) listOf(apiKey) else emptyList(),
  val temperature: Double? = 0.2,
  val maxTokens: Int? = 4096,
  val topP: Double? = null,
  val timeoutSeconds: Long = 45,
  val enableReasoning: Boolean = true
) {
  companion object {
    const val DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
    const val DEFAULT_MODEL = "z-ai/glm-5.3"
    const val MAX_API_KEYS = 15
  }
}

object NvidiaNimModels {
  // Verified active models on free build key
  const val GLM_5_3 = "z-ai/glm-5.3"
  const val LLAMA_3_2_11B = "meta/llama-3.2-11b-vision-instruct"
  const val LLAMA_3_2_90B = "meta/llama-3.2-90b-vision-instruct"
  const val MUSE_GLIMMER_30B = "meta/muse-glimmer-30b"
  const val NEMOTRON_3_5_LIGHTNING = "nvidia/nemotron-3.5-lightning-30b-a3b"
  const val NEMOTRON_3_5_SUPER = "nvidia/nemotron-3.5-super-vl-120b-a12b"
  const val NEMOTRON_3_SUPER = "nvidia/nemotron-3-super-120b-a12b"
  const val NEMOTRON_3_ULTRA = "nvidia/nemotron-3-ultra-550b-a55b"
  const val NEMOTRON_3_NANO_REASONING = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning"
  const val STEP_5_PREVIEW = "stepfun-ai/step-5-preview"
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
  const val KIMI_K3 = "moonshotai/kimi-k3" // Retained for backward reference; marked deprecated
  const val KIMI_K2_6 = "moonshotai/kimi-k2.6"
  const val DEEPSEEK_V4_1_FLASH = "deepseek-ai/deepseek-v4.1-flash"
  const val DEEPSEEK_R1 = "deepseek-ai/deepseek-r1"
  const val LLAMA_3_3_70B = "meta/llama-3.3-70b-instruct"
  const val NEMOTRON_SUPER_49B = "nvidia/llama-3.3-nemotron-super-49b-v1"
  const val QWEN_2_5_CODER_32B = "qwen/qwen2.5-coder-32b-instruct"

  /**
   * Evidence-based tool-calling confidence. Used for ordering fallback candidates.
   */
  enum class ToolCallingConfidence { VERIFIED, LIKELY, UNKNOWN }

  fun toolCallingConfidence(modelId: String): ToolCallingConfidence = when (modelId) {
    GLM_5_3, NEMOTRON_3_5_LIGHTNING, NEMOTRON_3_5_SUPER, STEP_5_PREVIEW, GPT_OSS_20B,
    NEMOTRON_3_NANO_REASONING, LLAMA_3_3_70B, NEMOTRON_SUPER_49B, QWEN_2_5_CODER_32B -> ToolCallingConfidence.VERIFIED
    GLM_5_3_FLASH, DEEPSEEK_V4_1_FLASH, DEEPSEEK_R1, MISTRAL_LARGE_2, KIMI_K2_6 -> ToolCallingConfidence.LIKELY
    else -> ToolCallingConfidence.UNKNOWN
  }

  /**
   * Live synchronized catalog of active models. Deprecated models are strictly excluded.
   */
  val CATALOG: List<NvidiaModelEntry>
    get() = NvidiaModelRegistry.getActiveModels()

  val PRESETS: List<String>
    get() = CATALOG.map { it.id }

  val CATEGORIES: List<String>
    get() = listOf("All") + CATALOG.map { it.category }.distinct()
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

  @Volatile
  private var keyPool: List<String> = if (initialConfig.apiKeys.isNotEmpty()) {
    initialConfig.apiKeys.map { it.trim() }.filter { it.isNotBlank() }.take(15)
  } else if (initialConfig.apiKey.isNotBlank()) {
    listOf(initialConfig.apiKey.trim())
  } else emptyList()

  private val keyCounter = java.util.concurrent.atomic.AtomicInteger(0)

  /** Per-credential health: cooldowns, quarantine and backoff. */
  private val keyHealth = NimKeyHealthTracker()

  /** Caches model IDs that rejected native OpenAI tool definitions to avoid a failing 400 round-trip on every turn. */
  private val modelsRejectingNativeTools = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  val config: NvidiaNimConfig get() = currentConfig

  override val providerName: String
    get() = "NVIDIA NIM (${currentConfig.model})"

  fun updateConfig(block: (NvidiaNimConfig) -> NvidiaNimConfig) {
    currentConfig = block(currentConfig)
  }

  fun setApiKeys(keys: List<String>) {
    val cleaned = keys.map { it.trim() }.filter { it.isNotBlank() }.take(15)
    val changed = cleaned != keyPool
    keyPool = cleaned
    currentConfig = currentConfig.copy(
      apiKeys = cleaned,
      apiKey = cleaned.firstOrNull() ?: ""
    )
    // Fresh credentials start healthy; keep existing health when the set is unchanged
    // (config updates like model or temperature must not erase cooldowns).
    if (changed) keyHealth.reset()
  }

  fun setApiKey(newKey: String) {
    val clean = newKey.trim()
    if (clean.isBlank()) {
      setApiKeys(emptyList())
    } else {
      setApiKeys(listOf(clean))
    }
  }

  fun setModel(newModel: String) {
    val clean = newModel.trim()
    if (clean.isNotBlank()) {
      currentConfig = currentConfig.copy(model = clean)
    }
  }

  fun setBaseUrl(newBaseUrl: String) {
    val clean = newBaseUrl.trim()
    if (clean.isNotBlank()) {
      currentConfig = currentConfig.copy(baseUrl = clean)
    }
  }

  fun getApiKeys(): List<String> = keyPool

  fun hasApiKey(): Boolean = keyPool.isNotEmpty()

  /**
   * Secret-free per-key status for diagnostics and the Settings UI.
   * Keys are identified by short non-reversible fingerprints only.
   */
  fun keyPoolStatus(nowMs: Long = System.currentTimeMillis()): List<NimKeyHealthTracker.KeyStatus> =
    keyPool.map { keyHealth.statusOf(it, nowMs) }

  /** Compact human-readable pool health summary, e.g. "3 keys · 1 cooling (28s) · 1 invalid". */
  fun keyPoolSummary(nowMs: Long = System.currentTimeMillis()): String {
    if (keyPool.isEmpty()) return "No NVIDIA keys configured"
    val statuses = keyPoolStatus(nowMs)
    val parts = mutableListOf("${keyPool.size} key${if (keyPool.size == 1) "" else "s"}")
    val quarantined = statuses.count { it.quarantined }
    val cooling = statuses.count { it.coolingDown }
    if (quarantined > 0) parts += "$quarantined invalid"
    if (cooling > 0) {
      val soonest = statuses.filter { it.coolingDown }.minOf { it.cooldownRemainingMs }
      parts += "$cooling cooling (${(soonest / 1000L).coerceAtLeast(1L)}s)"
    }
    return parts.joinToString(" · ")
  }

  override suspend fun decideNextAction(
    systemPrompt: String,
    taskIntent: String,
    messages: List<AgentMessage>,
    tools: List<Tool>,
    streamListener: LLMStreamListener?
  ): LLMDecision = withContext(Dispatchers.IO) {
    val activeConfig = currentConfig
    val pool = keyPool

    if (pool.isEmpty()) {
      return@withContext LLMDecision.ProviderError(
        "NVIDIA API key is not configured. Please supply your NVIDIA API key in Settings (supports up to 15 keys with auto-rotation & fallback)."
      )
    }

    val now = System.currentTimeMillis()
    val poolSize = pool.size
    val startIndex = if (poolSize == 1) 0 else Math.floorMod(keyCounter.getAndIncrement(), poolSize)

    // Health-aware key preference: rotating start offset, skipping cooling-down and
    // quarantined credentials. A key is never penalized for model or payload errors.
    val eligibleKeyIndices = (0 until poolSize)
      .map { (startIndex + it) % poolSize }
      .filter { keyHealth.isEligible(pool[it], now) }

    val quarantinedCount = pool.count { keyHealth.stateOf(it).quarantined }
    val coolingCount = (poolSize - eligibleKeyIndices.size - quarantinedCount).coerceAtLeast(0)

    if (eligibleKeyIndices.isEmpty()) {
      val earliestCooldown = pool.minOf { keyHealth.stateOf(it).cooldownUntilMs }
      val retryAfterMs = if (earliestCooldown == Long.MAX_VALUE) null
        else (earliestCooldown - now).coerceAtLeast(0L)
      val reason = if (quarantinedCount == poolSize) {
        "All $poolSize configured NVIDIA API keys were rejected with authentication errors and are quarantined. Update the keys in Settings to continue."
      } else {
        val waitSeconds = ((retryAfterMs ?: 0L) / 1000L).coerceAtLeast(1L)
        "All $poolSize configured NVIDIA API keys are cooling down after rate limits or quota errors. Earliest retry in ${waitSeconds}s."
      }
      return@withContext LLMDecision.ProviderError(
        message = reason,
        kind = if (quarantinedCount == poolSize) ProviderFailureKind.AUTH else ProviderFailureKind.RATE_LIMITED,
        retryAfterMs = retryAfterMs
      )
    }

    // Capability-aware model cascade: configured model first, then models with
    // documented tool-calling evidence, then remaining verified reasoning fallbacks.
    val candidateModels = buildCandidateModels(activeConfig.model)

    // Bounded routing plan. The primary model is attempted across up to
    // MAX_PRIMARY_KEY_ATTEMPTS healthy keys (credential failover never sacrifices
    // the configured model). Fallback models are then attempted with a bounded
    // number of keys. Total HTTP attempts per decision stay within
    // MAX_ROUTING_ATTEMPTS no matter how many keys are configured.
    val primaryModel = candidateModels.first()
    val plan = mutableListOf<Pair<Int, String>>()
    for (k in 0 until eligibleKeyIndices.size.coerceAtMost(MAX_PRIMARY_KEY_ATTEMPTS)) {
      plan += eligibleKeyIndices[k] to primaryModel
    }
    planLoop@ for (model in candidateModels.drop(1)) {
      for (k in 0 until eligibleKeyIndices.size.coerceAtMost(MAX_FALLBACK_KEY_ATTEMPTS)) {
        if (plan.size >= MAX_ROUTING_ATTEMPTS) break@planLoop
        plan += eligibleKeyIndices[k] to model
      }
    }

    var lastError = "Unknown error"
    var lastKind: ProviderFailureKind = ProviderFailureKind.OTHER
    var lastRetryAfterMs: Long? = null

    val exhaustedKeyIndices = mutableSetOf<Int>()
    val unusableModels = mutableSetOf<String>()
    var transientRetriesLeft = MAX_TRANSIENT_RETRIES
    var planIndex = 0

    while (planIndex < plan.size) {
      val (keyIndex, model) = plan[planIndex]
      if (keyIndex in exhaustedKeyIndices || model in unusableModels) {
        planIndex++
        continue
      }

      val candidateKey = pool[keyIndex]
      if (!keyHealth.isEligible(candidateKey, System.currentTimeMillis())) {
        planIndex++
        continue
      }

      val keySlotLabel = if (poolSize > 1) "Key #${keyIndex + 1}" else "Primary Key"
      val isFallbackModel = model != activeConfig.model

      val useNativeToolsForModel = model !in modelsRejectingNativeTools
      val effectiveSystemPrompt = buildString {
        append(systemPrompt)
        if (isFallbackModel) {
          append("\n\n[RECOVERY ROUTING CONTEXT]:\nOperating via $keySlotLabel with fallback model '$model'.\nVerify deliverables on disk before concluding.")
        }
        if (!useNativeToolsForModel) {
          append("\n\nAvailable tools:\n")
          append(formatToolsTextDescription(tools))
        }
      }

      val result = executeChatCompletion(
        apiKeyToUse = candidateKey,
        keyLabel = keySlotLabel,
        modelToUse = model,
        systemPrompt = effectiveSystemPrompt,
        taskIntent = taskIntent,
        messages = messages,
        tools = tools,
        useNativeTools = useNativeToolsForModel,
        streamListener = streamListener
      )

      when (result) {
        is LLMDecision.ExecuteTool -> {
          keyHealth.recordSuccess(candidateKey)
          val thoughtNotice = if (isFallbackModel) {
            "[Pool: $keySlotLabel / Model: $model] ${result.thought ?: ""}"
          } else result.thought
          return@withContext result.copy(thought = thoughtNotice)
        }
        is LLMDecision.Complete -> {
          keyHealth.recordSuccess(candidateKey)
          val thoughtNotice = if (isFallbackModel) {
            "[Pool: $keySlotLabel / Model: $model] ${result.thought ?: ""}"
          } else result.thought
          return@withContext result.copy(thought = thoughtNotice)
        }
        is LLMDecision.ProviderError -> {
          lastError = result.message
          lastKind = result.kind
          lastRetryAfterMs = result.retryAfterMs
          keyHealth.recordFailure(candidateKey, result.kind, result.retryAfterMs)

          when (result.kind) {
            ProviderFailureKind.AUTH -> {
              // Credential rejected: quarantine and fail over to the next eligible key.
              exhaustedKeyIndices += keyIndex
            }
            ProviderFailureKind.RATE_LIMITED,
            ProviderFailureKind.QUOTA_EXHAUSTED -> {
              // Cool down this key (honoring Retry-After) and fail over.
              exhaustedKeyIndices += keyIndex
            }
            ProviderFailureKind.TRANSIENT,
            ProviderFailureKind.NETWORK -> {
              if (transientRetriesLeft > 0) {
                transientRetriesLeft--
                delay(1_000L)
                continue // retry the same key/model once before moving on
              }
            }
            ProviderFailureKind.MODEL_UNAVAILABLE,
            ProviderFailureKind.PERMISSION,
            ProviderFailureKind.INVALID_REQUEST -> {
              // Model- or payload-specific problem: rotating credentials cannot fix it.
              unusableModels += model
              planIndex++
              continue
            }
            ProviderFailureKind.EMPTY_RESPONSE,
            ProviderFailureKind.OTHER -> Unit
          }
          planIndex++
        }
      }
    }

    val poolNote = buildString {
      if (poolSize > 1) {
        append(" [pool: $poolSize keys")
        if (exhaustedKeyIndices.isNotEmpty()) append(", ${exhaustedKeyIndices.size} exhausted this call")
        if (coolingCount > 0) append(", $coolingCount cooling down")
        if (quarantinedCount > 0) append(", $quarantinedCount quarantined")
        append("]")
      }
    }
    return@withContext LLMDecision.ProviderError(
      message = "NVIDIA NIM request failed: $lastError$poolNote",
      kind = lastKind,
      retryAfterMs = lastRetryAfterMs
    )
  }

  /**
   * Orders model candidates for the fallback cascade using documented
   * tool-calling evidence: the configured model always leads, followed by
   * tool-capable models, with the remaining verified reasoning models last.
   * Deprecated models are strictly filtered out so they are never invoked.
   */
  private fun buildCandidateModels(configuredModel: String): List<String> = buildList {
    val activeConfigured = if (NvidiaModelRegistry.isModelDeprecated(configuredModel)) {
      NvidiaModelRegistry.suggestActiveReplacement(configuredModel)
    } else {
      configuredModel
    }
    add(activeConfigured)
    val orderedFallbacks = listOf(
      NvidiaNimModels.NEMOTRON_3_5_LIGHTNING,
      NvidiaNimModels.LLAMA_3_3_70B,
      NvidiaNimModels.NEMOTRON_SUPER_49B,
      NvidiaNimModels.QWEN_2_5_CODER_32B,
      NvidiaNimModels.NEMOTRON_3_5_SUPER,
      NvidiaNimModels.STEP_5_PREVIEW,
      NvidiaNimModels.GPT_OSS_20B,
      NvidiaNimModels.NEMOTRON_3_NANO_REASONING,
      NvidiaNimConfig.DEFAULT_MODEL
    ).filterNot { it.contains("vision") }
      .filterNot { NvidiaModelRegistry.isModelDeprecated(it) }
      .sortedByDescending { confidenceRank(it) }
    for (fallback in orderedFallbacks) {
      if (!contains(fallback)) add(fallback)
    }
  }

  private fun confidenceRank(modelId: String): Int = when (NvidiaNimModels.toolCallingConfidence(modelId)) {
    NvidiaNimModels.ToolCallingConfidence.VERIFIED -> 3
    NvidiaNimModels.ToolCallingConfidence.LIKELY -> 2
    NvidiaNimModels.ToolCallingConfidence.UNKNOWN -> 1
  }

  companion object {
    /** Primary model is attempted across at most this many healthy keys before model fallback. */
    private const val MAX_PRIMARY_KEY_ATTEMPTS = 3

    /** Each fallback model is attempted across at most this many healthy keys. */
    private const val MAX_FALLBACK_KEY_ATTEMPTS = 2

    /** Hard ceiling on HTTP attempts per decideNextAction call, regardless of pool size. */
    private const val MAX_ROUTING_ATTEMPTS = 6

    /** Same key/model may be retried once per transient (5xx/network) failure, globally bounded. */
    private const val MAX_TRANSIENT_RETRIES = 2
  }

  private suspend fun executeChatCompletion(
    apiKeyToUse: String,
    keyLabel: String,
    modelToUse: String,
    systemPrompt: String,
    taskIntent: String,
    messages: List<AgentMessage>,
    tools: List<Tool>,
    useNativeTools: Boolean,
    streamListener: LLMStreamListener? = null,
    maxTokensOverride: Int? = null
  ): LLMDecision {
    val activeConfig = currentConfig
    streamListener?.onReset()

    try {
      val baseUrlClean = activeConfig.baseUrl.trimEnd('/')
      val endpoint = "$baseUrlClean/chat/completions"

      val requestJson = JSONObject()
      requestJson.put("model", modelToUse)

      // 1. Construct messages conforming to OpenAI / NVIDIA tool calling specifications
      val modelSupportsVision = NvidiaModelRegistry.isVisionModel(modelToUse)
      val messagesArray = buildMessagesJson(systemPrompt, messages, modelSupportsVision)
      requestJson.put("messages", messagesArray)

      // 2. Expose canonical registered tools
      if (useNativeTools) {
        val toolsArray = buildToolsJson(tools)
        if (toolsArray.length() > 0) {
          requestJson.put("tools", toolsArray)
          requestJson.put("tool_choice", "auto")
        }
      }

      // 3. Optional generation parameters & streaming
      if (streamListener != null) {
        requestJson.put("stream", true)
      }
      activeConfig.temperature?.let { requestJson.put("temperature", it) }
      val effectiveMaxTokens = maxTokensOverride ?: activeConfig.maxTokens
      effectiveMaxTokens?.let { requestJson.put("max_tokens", it) }
      activeConfig.topP?.let { requestJson.put("top_p", it) }

      // 4. Build HTTP POST Request with candidate key from pool
      val mediaType = "application/json; charset=utf-8".toMediaType()
      val requestBody = requestJson.toString().toRequestBody(mediaType)

      val request = Request.Builder()
        .url(endpoint)
        .addHeader("Authorization", "Bearer ${apiKeyToUse.trim()}")
        .addHeader("Content-Type", "application/json")
        .addHeader("Accept", if (streamListener != null) "text/event-stream, application/json" else "application/json")
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

      var retryAfterMs: Long? = null
      val response = httpClient.newCall(request).execute()
      retryAfterMs = parseRetryAfterHeader(response.header("Retry-After"))

      // 5. HTTP Error Handling and Retry
      if (!response.isSuccessful) {
        val responseBodyString = response.body?.string() ?: ""
        response.close()
        val errorDetail = extractErrorDetail(responseBodyString, response.code, apiKeyToUse)
        val lowerErr = errorDetail.lowercase()

        // If tools are rejected by this model (HTTP 400/422 with tool/schema/template error), cache and retry without native tools
        if (useNativeTools && (response.code == 400 || response.code == 422) && (
            lowerErr.contains("tool") ||
              lowerErr.contains("function") ||
              lowerErr.contains("extra") ||
              lowerErr.contains("parameters") ||
              lowerErr.contains("schema") ||
              lowerErr.contains("chat_template") ||
              lowerErr.contains("guided")
          )
        ) {
          modelsRejectingNativeTools.add(modelToUse)
          return executeChatCompletion(
            apiKeyToUse = apiKeyToUse,
            keyLabel = keyLabel,
            modelToUse = modelToUse,
            systemPrompt = "$systemPrompt\n\nAvailable tools:\n${formatToolsTextDescription(tools)}",
            taskIntent = taskIntent,
            messages = messages,
            tools = tools,
            useNativeTools = false,
            streamListener = streamListener,
            maxTokensOverride = maxTokensOverride
          )
        }

        // If max_tokens exceeds remaining context window on this model, automatically retry with a bounded 2048 token cap
        if (maxTokensOverride == null && (response.code == 400 || response.code == 422) && (
            lowerErr.contains("max_tokens") ||
              lowerErr.contains("context length") ||
              lowerErr.contains("maximum context")
          )
        ) {
          return executeChatCompletion(
            apiKeyToUse = apiKeyToUse,
            keyLabel = keyLabel,
            modelToUse = modelToUse,
            systemPrompt = systemPrompt,
            taskIntent = taskIntent,
            messages = messages,
            tools = tools,
            useNativeTools = useNativeTools,
            streamListener = streamListener,
            maxTokensOverride = 2048
          )
        }

        val normalizedMessage = when (response.code) {
          400 -> "NVIDIA NIM bad request (HTTP 400): $errorDetail"
          401 -> "NVIDIA NIM authentication failed (HTTP 401). Please check your NVIDIA API key."
          403 -> "NVIDIA NIM access forbidden (HTTP 403). Your key may lack permissions for model '$modelToUse'."
          404 -> "NVIDIA NIM model or endpoint not found (HTTP 404): '$modelToUse'. $errorDetail"
          429 -> "NVIDIA NIM rate limit or quota exceeded (HTTP 429): $errorDetail"
          in 500..599 -> "NVIDIA NIM upstream server error (HTTP ${response.code}): $errorDetail"
          else -> "NVIDIA NIM API error (HTTP ${response.code}): $errorDetail"
        }
        return LLMDecision.ProviderError(
          normalizedMessage,
          kind = classifyHttpFailure(response.code, errorDetail),
          retryAfterMs = retryAfterMs
        )
      }

      val contentType = response.header("Content-Type") ?: ""
      val isEventStream = (contentType.contains("text/event-stream") || streamListener != null) && response.body != null

      if (isEventStream) {
        val accumulatedContent = StringBuilder()
        val accumulatedReasoning = StringBuilder()
        val toolCallBuffers = mutableMapOf<Int, Triple<String, String, StringBuilder>>()

        try {
          response.body?.use { body ->
            val source = body.source()
            while (!source.exhausted()) {
              val rawLine = source.readUtf8Line() ?: break
              val line = rawLine.trim()
              if (line.isEmpty() || line.startsWith(":")) continue
              if (line.startsWith("data:")) {
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                try {
                  val chunkObj = JSONObject(data)
                  val choices = chunkObj.optJSONArray("choices")
                  if (choices != null && choices.length() > 0) {
                    val choice = choices.optJSONObject(0)
                    val delta = choice?.optJSONObject("delta")
                    if (delta != null) {
                      val contentDelta = delta.optCleanString("content")
                      val reasoningDelta = delta.optCleanString("reasoning_content")
                        .ifEmpty { delta.optCleanString("reasoning") }

                      if (reasoningDelta.isNotEmpty()) {
                        accumulatedReasoning.append(reasoningDelta)
                        streamListener?.onToken(reasoningDelta)
                      }
                      if (contentDelta.isNotEmpty()) {
                        accumulatedContent.append(contentDelta)
                        // Only stream contentDelta into live thinking preview if reasoning_content wasn't already streamed,
                        // preventing double-reasoning display during SSE streaming.
                        if (accumulatedReasoning.isEmpty()) {
                          streamListener?.onToken(contentDelta)
                        }
                      }

                      if (delta.has("tool_calls") && !delta.isNull("tool_calls")) {
                        val tcArr = delta.optJSONArray("tool_calls")
                        if (tcArr != null) {
                          for (k in 0 until tcArr.length()) {
                            val tc = tcArr.optJSONObject(k) ?: continue
                            val idx = tc.optInt("index", k)
                            val existing = toolCallBuffers[idx]
                            val idChunk = tc.optCleanString("id")
                            val id = if (idChunk.isNotBlank()) idChunk else existing?.first ?: ""
                            val fn = tc.optJSONObject("function")
                            val nameChunk = fn?.optCleanString("name").orEmpty()
                            val fnName = if (nameChunk.isNotBlank()) nameChunk else existing?.second ?: ""
                            val fnArgs = fn?.optCleanString("arguments").orEmpty()
                            val argsBuilder = existing?.third ?: StringBuilder()
                            argsBuilder.append(fnArgs)
                            toolCallBuffers[idx] = Triple(id, fnName, argsBuilder)
                            // If the model streams tool calls directly without prior reasoning/content text,
                            // notify streamListener as soon as the tool name is known so UI shows immediate responsiveness.
                            if (existing == null && fnName.isNotBlank() && accumulatedReasoning.isEmpty() && accumulatedContent.isEmpty()) {
                              streamListener?.onToken("Preparing tool action: $fnName...\n")
                            }
                          }
                        }
                      }
                    }
                  }
                } catch (_: Exception) {}
              }
            }
          }
        } catch (_: Exception) {}

        val rawContentText = accumulatedContent.toString().trim()
        val thinkRegex = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
        val extractedThinkBlocks = thinkRegex.findAll(rawContentText)
          .map { it.groupValues[1].trim() }
          .filter { it.isNotBlank() }
          .joinToString("\n")
        val contentText = rawContentText
          .replace(thinkRegex, "")
          .replace(Regex("^\\s*\\[Reasoning]\\s*", RegexOption.IGNORE_CASE), "")
          .trim()
        val reasoningText = listOf(accumulatedReasoning.toString().trim(), extractedThinkBlocks)
          .filter { it.isNotBlank() }
          .joinToString("\n")
          .replace(Regex("^\\s*\\[Reasoning]\\s*", RegexOption.IGNORE_CASE), "")
          .trim()

        val cleanedContentForThought = contentText
          .replace(Regex("<tool_call>.*?</tool_call>", RegexOption.DOT_MATCHES_ALL), "")
          .replace(Regex("^\\s*\\[Reasoning]\\s*", RegexOption.IGNORE_CASE), "")
          .trim()

        val parsedToolCalls = if (toolCallBuffers.isNotEmpty()) {
          toolCallBuffers.values.mapNotNull { (callId, fnName, argsBuilder) ->
            if (fnName.isBlank()) return@mapNotNull null
            val rawArgs = argsBuilder.toString()
            val argsMap = parseArguments(rawArgs)
            ToolCall(
              callId = if (callId.isNotBlank()) callId else ToolCall.generateCallId("stream", (System.currentTimeMillis() % 100000).toInt()),
              toolName = fnName,
              arguments = argsMap
            )
          }
        } else {
          extractToolCalls(null, rawContentText, tools)
        }

        val combinedThought = mergeReasoningAndContent(
          reasoningText = reasoningText,
          contentText = cleanedContentForThought,
          hasToolCalls = parsedToolCalls.isNotEmpty()
        )

        if (parsedToolCalls.isNotEmpty()) {
          return LLMDecision.ExecuteTool(
            toolCalls = parsedToolCalls,
            thought = combinedThought.ifBlank { "Executing ${parsedToolCalls.size} requested tool call(s)." }
          )
        } else if (contentText.isNotBlank() || reasoningText.isNotBlank()) {
          val finalConclusion = cleanContentConclusion(contentText).ifBlank { combinedThought }
          return LLMDecision.Complete(
            conclusion = finalConclusion,
            thought = if (reasoningText.isNotBlank()) reasoningText else "Agent concluded task."
          )
        } else if (streamListener != null) {
          // Stream closed without emitting content or tool calls; retry once without SSE streaming
          return executeChatCompletion(
            apiKeyToUse = apiKeyToUse,
            keyLabel = keyLabel,
            modelToUse = modelToUse,
            systemPrompt = systemPrompt,
            taskIntent = taskIntent,
            messages = messages,
            tools = tools,
            useNativeTools = useNativeTools,
            streamListener = null,
            maxTokensOverride = maxTokensOverride
          )
        }
      }

      val responseBodyString = response.body?.string() ?: ""
      response.close()

      if (responseBodyString.isBlank()) {
        return LLMDecision.ProviderError("NVIDIA NIM returned an empty response body.", kind = ProviderFailureKind.EMPTY_RESPONSE)
      }

      // 6. JSON Response Parsing & Validation
      val rootJson = try {
        JSONObject(responseBodyString)
      } catch (e: Exception) {
        return LLMDecision.ProviderError("NVIDIA NIM returned malformed JSON: ${e.message}", kind = ProviderFailureKind.EMPTY_RESPONSE)
      }

      val choices = rootJson.optJSONArray("choices")
      if (choices == null || choices.length() == 0) {
        return LLMDecision.ProviderError("NVIDIA NIM response choices array is empty.", kind = ProviderFailureKind.EMPTY_RESPONSE)
      }

      val firstChoice = choices.optJSONObject(0)
        ?: return LLMDecision.ProviderError("NVIDIA NIM response choice[0] is not a valid JSON object.", kind = ProviderFailureKind.EMPTY_RESPONSE)

      val messageObj = firstChoice.optJSONObject("message")
        ?: return LLMDecision.ProviderError("NVIDIA NIM response choice missing required 'message' object.", kind = ProviderFailureKind.EMPTY_RESPONSE)

      val contentText = messageObj.optCleanString("content")
        .replace(Regex("^\\s*\\[Reasoning]\\s*", RegexOption.IGNORE_CASE), "")
        .trim()
      val reasoningText = messageObj.optCleanString("reasoning_content")
        .ifBlank { messageObj.optCleanString("reasoning") }
        .replace(Regex("^\\s*\\[Reasoning]\\s*", RegexOption.IGNORE_CASE), "")
        .trim()

      // 7. Multi-Format Tool Calls Extraction
      val parsedToolCalls = extractToolCalls(messageObj, contentText, tools)

      val combinedThought = mergeReasoningAndContent(
        reasoningText = reasoningText,
        contentText = contentText,
        hasToolCalls = parsedToolCalls.isNotEmpty()
      )

      // 8. Normalize decision to LLMDecision
      return if (parsedToolCalls.isNotEmpty()) {
        LLMDecision.ExecuteTool(
          toolCalls = parsedToolCalls,
          thought = combinedThought.ifBlank { "Executing ${parsedToolCalls.size} requested tool call(s)." }
        )
      } else {
        val finalConclusion = cleanContentConclusion(contentText).ifBlank { combinedThought }
        if (finalConclusion.isBlank()) {
          LLMDecision.ProviderError("NVIDIA NIM returned neither tool calls nor message content.", kind = ProviderFailureKind.EMPTY_RESPONSE)
        } else {
          LLMDecision.Complete(
            conclusion = finalConclusion,
            thought = if (reasoningText.isNotBlank()) reasoningText else "Agent concluded task."
          )
        }
      }
    } catch (e: SocketTimeoutException) {
      return LLMDecision.ProviderError("NVIDIA NIM request timed out after ${activeConfig.timeoutSeconds}s: ${e.message}", kind = ProviderFailureKind.NETWORK)
    } catch (e: UnknownHostException) {
      return LLMDecision.ProviderError("NVIDIA NIM network connection failure: Unable to resolve host '${activeConfig.baseUrl}'.", kind = ProviderFailureKind.NETWORK)
    } catch (e: IOException) {
      return LLMDecision.ProviderError("NVIDIA NIM connection failure: ${e.message}", kind = ProviderFailureKind.NETWORK)
    } catch (ce: CancellationException) {
      throw ce
    } catch (e: Exception) {
      return LLMDecision.ProviderError("NVIDIA NIM unexpected error: ${e.message}", kind = ProviderFailureKind.OTHER)
    }
  }

  /**
   * Maps HTTP status codes plus response detail to the routing failure taxonomy.
   * Key rule: only failures a different credential could fix may cool down or
   * quarantine a key. Payload and model problems never trigger key rotation.
   */
  private fun classifyHttpFailure(responseCode: Int, errorDetail: String): ProviderFailureKind = when (responseCode) {
    400, 422 -> ProviderFailureKind.INVALID_REQUEST
    401 -> ProviderFailureKind.AUTH
    403 -> ProviderFailureKind.PERMISSION
    404 -> ProviderFailureKind.MODEL_UNAVAILABLE
    408 -> ProviderFailureKind.NETWORK
    429 -> if (errorDetail.contains("quota", ignoreCase = true)) {
      ProviderFailureKind.QUOTA_EXHAUSTED
    } else {
      ProviderFailureKind.RATE_LIMITED
    }
    in 500..599 -> ProviderFailureKind.TRANSIENT
    else -> ProviderFailureKind.OTHER
  }

  /**
   * Parses Retry-After in either delta-seconds or HTTP-date form, clamped to the
   * maximum cooldown so a hostile or mistaken header cannot stall the agent.
   */
  private fun parseRetryAfterHeader(rawHeader: String?): Long? {
    val value = rawHeader?.trim().orEmpty()
    if (value.isBlank()) return null
    value.toLongOrNull()?.let { seconds ->
      if (seconds >= 0) return (seconds * 1000L).coerceAtMost(NimKeyHealthTracker.MAX_COOLDOWN_MS)
    }
    return try {
      val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
      val parsed = fmt.parse(value) ?: return null
      (parsed.time - System.currentTimeMillis()).coerceIn(0L, NimKeyHealthTracker.MAX_COOLDOWN_MS)
    } catch (_: Exception) {
      null
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
    messageObj: JSONObject? = null,
    contentText: String,
    availableTools: List<Tool>
  ): List<ToolCall> {
    val results = mutableListOf<ToolCall>()
    val toolNames = availableTools.map { it.name }.toSet()

    // 1. Native tool_calls
    val toolCallsArray = messageObj?.optJSONArray("tool_calls")
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

    // 2b. <tool_call>...</tool_call> XML format (Qwen / Hermes / GLM / Nemotron)
    if (contentText.contains("<tool_call>")) {
      val tcPattern = Pattern.compile("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", Pattern.DOTALL)
      val matcher = tcPattern.matcher(contentText)
      while (matcher.find()) {
        val jsonStr = matcher.group(1) ?: continue
        parseSingleToolJson(jsonStr, toolNames)?.let { results.add(it) }
      }
      if (results.isNotEmpty()) return results
    }

    // 2c. Llama 3.1/3.3 <function=tool_name>{...}</function> format
    if (contentText.contains("<function=")) {
      val fnPattern = Pattern.compile("<function=([a-zA-Z0-9_.-]+)>\\s*(\\{.*?\\})\\s*</function>", Pattern.DOTALL)
      val matcher = fnPattern.matcher(contentText)
      while (matcher.find()) {
        val fnName = matcher.group(1)?.trim() ?: continue
        val argsStr = matcher.group(2)?.trim() ?: continue
        val canonical = ToolRegistry.resolveCanonicalName(fnName)
        if (toolNames.isEmpty() || toolNames.contains(fnName) || toolNames.contains(canonical)) {
          val argsMap = parseArguments(argsStr)
          results.add(
            ToolCall(
              callId = "call_${UUID.randomUUID()}",
              toolName = fnName,
              arguments = argsMap
            )
          )
        }
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
        val argsMap = parseArguments(actionInput)
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
      val canonical = ToolRegistry.resolveCanonicalName(name)
      val isKnown = knownToolNames.isEmpty() || knownToolNames.contains(name) || knownToolNames.contains(canonical)
      if (name.isNotBlank() && isKnown) {
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
          .removePrefix("```json")
          .removePrefix("```")
          .removeSuffix("```")
          .trim()
        if (trimmed.isNotBlank() && trimmed != "{}") {
          try {
            jsonObjectToMap(JSONObject(trimmed))
          } catch (_: Exception) {
            try {
              // Resilient recovery for trailing commas or unescaped control chars inside JSON strings
              val repaired = sanitizeJsonStringLiterals(trimmed).replace(Regex(",\\s*([}\\]])"), "$1")
              jsonObjectToMap(JSONObject(repaired))
            } catch (_: Exception) {
              emptyMap()
            }
          }
        } else {
          emptyMap()
        }
      }
      null, JSONObject.NULL -> emptyMap()
      else -> emptyMap()
    }
  }

  private fun sanitizeJsonStringLiterals(raw: String): String {
    val sb = StringBuilder(raw.length)
    var inQuotes = false
    var escaped = false
    for (ch in raw) {
      if (escaped) {
        sb.append(ch)
        escaped = false
        continue
      }
      if (ch == '\\') {
        sb.append(ch)
        escaped = true
        continue
      }
      if (ch == '"') {
        inQuotes = !inQuotes
        sb.append(ch)
        continue
      }
      if (inQuotes && (ch == '\n' || ch == '\r' || ch == '\t')) {
        when (ch) {
          '\n' -> sb.append("\\n")
          '\r' -> sb.append("\\r")
          '\t' -> sb.append("\\t")
        }
      } else {
        sb.append(ch)
      }
    }
    return sb.toString()
  }

  private fun JSONObject.optCleanString(key: String): String {
    if (!has(key) || isNull(key)) return ""
    val v = optString(key, "")
    return if (v == "null") "" else v
  }

  private fun mergeReasoningAndContent(
    reasoningText: String,
    contentText: String,
    hasToolCalls: Boolean
  ): String {
    val rClean = reasoningText.replace(Regex("^\\s*\\[Reasoning]\\s*", RegexOption.IGNORE_CASE), "").trim()
    val cClean = contentText.replace(Regex("^\\s*\\[Reasoning]\\s*", RegexOption.IGNORE_CASE), "").trim()
    return when {
      rClean.isNotBlank() && cClean.isNotBlank() -> {
        if (hasToolCalls) {
          // On tool-calling turns, reasoning_content already captures the pre-tool thought;
          // appending contentText duplicates the same reasoning in slightly different words.
          rClean
        } else if (rClean.equals(cClean, ignoreCase = true) || rClean.contains(cClean, ignoreCase = true)) {
          rClean
        } else if (cClean.contains(rClean, ignoreCase = true)) {
          cClean
        } else {
          val rWords = rClean.lowercase().split(Regex("\\s+")).filter { it.length > 3 }.toSet()
          val cWords = cClean.lowercase().split(Regex("\\s+")).filter { it.length > 3 }.toSet()
          val overlap = if (rWords.isEmpty() || cWords.isEmpty()) 0.0 else {
            rWords.intersect(cWords).size.toDouble() / minOf(rWords.size, cWords.size).toDouble()
          }
          if (overlap >= 0.45) {
            if (cClean.length >= rClean.length) cClean else rClean
          } else {
            "$rClean\n\n$cClean"
          }
        }
      }
      rClean.isNotBlank() -> rClean
      else -> cClean
    }
  }

  private fun cleanContentConclusion(raw: String): String {
    return raw
      .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
      .replace(Regex("<tool_call>.*?</tool_call>", RegexOption.DOT_MATCHES_ALL), "")
      .replace("<|python_tag|>", "")
      .replace(Regex("<\\|.*?\\|>"), "")
      .replace(Regex("^\\s*\\[Reasoning]\\s*", RegexOption.IGNORE_CASE), "")
      .trim()
  }

  private fun formatToolsTextDescription(tools: List<Tool>): String {
    return tools.joinToString("\n\n") { tool ->
      val meta = tool.getMetadata()
      val mode = if (meta.mutatesWorkspace) "mutating" else "read-only"
      val perms = if (meta.permissions.isEmpty()) "NONE" else meta.permissions.joinToString(", ") { it.name }
      val params = tool.schema.parameters.joinToString(", ") {
        val req = if (it.required) "required" else "optional"
        val enums = if (it.enumValues != null) " [${it.enumValues.joinToString(",")}]" else ""
        "${it.name}: ${it.type} ($req$enums)"
      }
      "- ${tool.name}($params) [${meta.owningBackend.name} | $mode | perms: $perms]: ${tool.description}"
    }
  }

  /**
   * Generates native OpenAI-compatible tool definitions directly from canonical ToolRegistry tools.
   */
  private fun buildToolsJson(tools: List<Tool>): JSONArray {
    val toolsArray = JSONArray()
    for (tool in tools) {
      val meta = tool.getMetadata()
      val toolObj = JSONObject()
      toolObj.put("type", "function")

      val funcObj = JSONObject()
      funcObj.put("name", tool.name)
      val mode = if (meta.mutatesWorkspace) "mutating" else "read-only"
      val permsStr = if (meta.permissions.isEmpty()) "NONE" else meta.permissions.joinToString(",") { it.name }
      funcObj.put("description", "[${meta.owningBackend.name} | $mode | perms: $permsStr] ${meta.description}")

      val parametersObj = JSONObject()
      parametersObj.put("type", "object")
      val propertiesObj = JSONObject()
      val requiredList = JSONArray()

      for (param in tool.schema.parameters) {
        val pObj = JSONObject()
        pObj.put("type", param.type.lowercase())
        val descWithMeta = buildString {
          append(param.description)
          if (param.defaultValue != null) {
            append(" (default: ${param.defaultValue})")
          }
        }
        pObj.put("description", descWithMeta)
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
    messages: List<AgentMessage>,
    modelSupportsVision: Boolean = false
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
    val boundedMessages = MessageHistoryBounder.bound(messages)

    // 3. Serialize messages maintaining exact tool_call_id linkage.
    // Consecutive assistant tool-call records are folded into one assistant message,
    // because OpenAI-compatible APIs expect the whole requested batch together before
    // any corresponding role=tool observations.
    var activeToolCallIds = mutableSetOf<String>()
    var messageIndex = 0
    while (messageIndex < boundedMessages.size) {
      val msg = boundedMessages[messageIndex]
      when (msg.role) {
        MessageRole.USER -> {
          activeToolCallIds.clear()
          val userObj = JSONObject()
          userObj.put("role", "user")
          if (msg.attachments.isEmpty()) {
            userObj.put("content", msg.content)
          } else if (modelSupportsVision) {
            val contentArr = JSONArray()
            contentArr.put(JSONObject().put("type", "text").put("text", msg.content))
            for (att in msg.attachments) {
              if (att.mimeType.startsWith("image/")) {
                val b64 = att.getBase64()
                if (b64 != null) {
                  val imgObj = JSONObject()
                  imgObj.put("type", "image_url")
                  imgObj.put("image_url", JSONObject().put("url", "data:${att.mimeType};base64,$b64"))
                  contentArr.put(imgObj)
                }
              } else {
                val textContent = try {
                  att.file?.readText() ?: att.base64Data?.let {
                    String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8)
                  }
                } catch (_: Exception) { null }
                if (textContent != null) {
                  contentArr.put(
                    JSONObject().put("type", "text").put("text", "\n\n[Attached File: ${att.name} (${att.mimeType})]\n$textContent")
                  )
                }
              }
            }
            userObj.put("content", contentArr)
          } else {
            // Text-only NIM models reject array content with image_url; serialize attachments as text context
            val textSb = StringBuilder(msg.content)
            for (att in msg.attachments) {
              if (att.mimeType.startsWith("image/")) {
                textSb.append("\n\n[Attached Image: ${att.name} (${att.mimeType}) — placed at /home/user/${att.name} and workspace '${att.name}']")
              } else {
                val textContent = try {
                  att.file?.readText() ?: att.base64Data?.let {
                    String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8)
                  }
                } catch (_: Exception) { null }
                if (textContent != null) {
                  textSb.append("\n\n[Attached File: ${att.name} (${att.mimeType})]\n$textContent")
                }
              }
            }
            userObj.put("content", textSb.toString())
          }
          messagesArray.put(userObj)
        }

        MessageRole.SYSTEM -> {
          activeToolCallIds.clear()
          val sysObj = JSONObject()
          sysObj.put("role", "system")
          sysObj.put("content", msg.content)
          messagesArray.put(sysObj)
        }

        MessageRole.ASSISTANT -> {
          val assistantObj = JSONObject()
          assistantObj.put("role", "assistant")

          if (msg.toolCallId != null && msg.toolName != null) {
            // For native tool calls in conversation history, keep content null so reasoning models
            // (GLM, DeepSeek, Nemotron) do not learn to echo internal reasoning blocks inside content.
            assistantObj.put("content", JSONObject.NULL)
            activeToolCallIds = mutableSetOf()

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
              activeToolCallIds.add(batchMessage.toolCallId)
              batchIndex++
            }
            assistantObj.put("tool_calls", toolCallsArr)
            messageIndex = batchIndex - 1
          } else {
            activeToolCallIds.clear()
            assistantObj.put("content", msg.content)
          }
          messagesArray.put(assistantObj)
        }

        MessageRole.TOOL -> {
          // Bound observation payload length to preserve token budget
          val boundedContent = if (msg.content.length > 8000) {
            msg.content.take(8000) + "\n...[truncated remainder of large observation]..."
          } else {
            msg.content
          }
          val callId = msg.toolCallId ?: ""
          if (callId.isNotBlank() && activeToolCallIds.contains(callId)) {
            val toolObj = JSONObject()
            toolObj.put("role", "tool")
            toolObj.put("tool_call_id", callId)
            if (!msg.toolName.isNullOrBlank()) {
              toolObj.put("name", msg.toolName)
            }
            toolObj.put("content", boundedContent)
            messagesArray.put(toolObj)
          } else {
            // Orphaned tool message without matching preceding assistant tool_calls batch:
            // serialize as user observation so strict vLLM/TRT-LLM templates never reject with HTTP 400
            val fallbackUserObj = JSONObject()
            fallbackUserObj.put("role", "user")
            fallbackUserObj.put("content", "[Tool Observation (${msg.toolName ?: "tool"})]:\n$boundedContent")
            messagesArray.put(fallbackUserObj)
          }
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
    var redacted = raw
    val allKeys = (keyPool + listOf(currentConfig.apiKey)).filter { it.isNotBlank() }
    for (k in allKeys) {
      redacted = redacted.replace(k, "[REDACTED]")
    }
    redacted = redacted.replace(Regex("Bearer\\s+[a-zA-Z0-9_.-]+"), "Bearer [REDACTED]")
    return redacted
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
   * Synchronizes with NvidiaModelRegistry, discovering new models and filtering deprecations.
   */
  suspend fun fetchLiveModels(): Result<List<String>> = withContext(Dispatchers.IO) {
    val activeConfig = currentConfig
    val now = System.currentTimeMillis()
    val candidates = keyPool.filter { keyHealth.isEligible(it, now) }.ifEmpty { keyPool }
    for (candidateKey in candidates) {
      val syncRes = NvidiaModelRegistry.sync(activeConfig.baseUrl, candidateKey, null)
      if (syncRes.isSuccess) {
        keyHealth.recordSuccess(candidateKey)
        return@withContext Result.success(NvidiaModelRegistry.getActiveModels().map { it.id })
      }
    }
    val fallbackSync = NvidiaModelRegistry.sync(activeConfig.baseUrl, activeConfig.apiKey, null)
    if (fallbackSync.isSuccess) {
      Result.success(NvidiaModelRegistry.getActiveModels().map { it.id })
    } else {
      Result.failure(fallbackSync.exceptionOrNull() ?: IOException("Failed to synchronize models from NVIDIA NIM"))
    }
  }
}
