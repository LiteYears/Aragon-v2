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
  val timeoutSeconds: Long = 40,
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
  const val KIMI_K3 = "moonshotai/kimi-k3" // Retained for backward reference; marked deprecated
  const val KIMI_K2_6 = "moonshotai/kimi-k2.6"
  const val DEEPSEEK_V4_1_FLASH = "deepseek-ai/deepseek-v4.1-flash"

  /**
   * Evidence-based tool-calling confidence. Used for ordering fallback candidates.
   */
  enum class ToolCallingConfidence { VERIFIED, LIKELY, UNKNOWN }

  fun toolCallingConfidence(modelId: String): ToolCallingConfidence = when (modelId) {
    GLM_5_3, GLM_5_3_FLASH -> ToolCallingConfidence.VERIFIED
    NEMOTRON_3_5_LIGHTNING -> ToolCallingConfidence.LIKELY
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
    tools: List<Tool>
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

      val effectiveSystemPrompt = if (isFallbackModel) {
        """
        $systemPrompt

        [RECOVERY ROUTING CONTEXT]:
        Operating via $keySlotLabel with fallback model '$model'.
        Verify deliverables on disk before concluding.
        """.trimIndent()
      } else systemPrompt

      val result = executeChatCompletion(
        apiKeyToUse = candidateKey,
        keyLabel = keySlotLabel,
        modelToUse = model,
        systemPrompt = effectiveSystemPrompt,
        taskIntent = taskIntent,
        messages = messages,
        tools = tools,
        useNativeTools = true
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
      NvidiaNimConfig.DEFAULT_MODEL,
      NvidiaNimModels.NEMOTRON_3_5_LIGHTNING,
      NvidiaNimModels.GLM_5_3_FLASH,
      NvidiaNimModels.CODESTRAL_22B,
      NvidiaNimModels.MISTRAL_LARGE_2,
      NvidiaNimModels.DEEPSEEK_V4_1_FLASH,
      NvidiaNimModels.KIMI_K2_6
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

      // 4. Build HTTP POST Request with candidate key from pool
      val mediaType = "application/json; charset=utf-8".toMediaType()
      val requestBody = requestJson.toString().toRequestBody(mediaType)

      val request = Request.Builder()
        .url(endpoint)
        .addHeader("Authorization", "Bearer ${apiKeyToUse.trim()}")
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

      var retryAfterMs: Long? = null
      val (responseCode, isSuccessful, responseBodyString) = httpClient.newCall(request).execute().use { response ->
        retryAfterMs = parseRetryAfterHeader(response.header("Retry-After"))
        Triple(response.code, response.isSuccessful, response.body?.string() ?: "")
      }

      // 5. HTTP Error Handling and Retry
      if (!isSuccessful) {
        val errorDetail = extractErrorDetail(responseBodyString, responseCode, apiKeyToUse)

        // If tools are rejected by this model (HTTP 400 with 'tools' or 'extra input'), retry without native tools
        if (useNativeTools && responseCode == 400 && (errorDetail.contains("tool") || errorDetail.contains("extra input") || errorDetail.contains("parameters"))) {
          return executeChatCompletion(
            apiKeyToUse = apiKeyToUse,
            keyLabel = keyLabel,
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
        return LLMDecision.ProviderError(
          normalizedMessage,
          kind = classifyHttpFailure(responseCode, errorDetail),
          retryAfterMs = retryAfterMs
        )
      }

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

      val contentText = messageObj.optString("content", "")
      val reasoningText = messageObj.optString("reasoning_content", "")
        .ifBlank { messageObj.optString("reasoning", "") }

      val combinedThought = when {
        reasoningText.isNotBlank() && contentText.isNotBlank() -> {
          val rClean = reasoningText.trim()
          val cClean = contentText.trim()
          if (rClean.equals(cClean, ignoreCase = true)) {
            rClean
          } else if (rClean.contains(cClean, ignoreCase = true)) {
            rClean
          } else if (cClean.contains(rClean, ignoreCase = true)) {
            cClean
          } else {
            "[Reasoning] $rClean\n$cClean"
          }
        }
        reasoningText.isNotBlank() -> reasoningText.trim()
        else -> contentText.trim()
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
    val boundedMessages = MessageHistoryBounder.bound(messages)

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
          if (msg.attachments.isEmpty()) {
            userObj.put("content", msg.content)
          } else {
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
          }
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
            val isFillerContent = msg.content.isBlank() ||
              msg.content.startsWith("Dispatched tool") ||
              (msg.content.startsWith("Executing ") && msg.content.contains("requested tool call"))
            if (!isFillerContent) {
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
    val keyToUse = keyPool.firstOrNull() ?: activeConfig.apiKey
    val syncRes = NvidiaModelRegistry.sync(activeConfig.baseUrl, keyToUse, null)
    if (syncRes.isSuccess) {
      Result.success(NvidiaModelRegistry.getActiveModels().map { it.id })
    } else {
      Result.failure(syncRes.exceptionOrNull() ?: IOException("Failed to synchronize models from NVIDIA NIM"))
    }
  }
}
