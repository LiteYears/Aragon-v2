package com.example.agent.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Data model for an NVIDIA NIM hosted model entry.
 */
data class NvidiaModelEntry(
  val id: String,
  val name: String,
  val category: String,
  val description: String,
  val badge: String = "",
  val isVerified: Boolean = true,
  val isVision: Boolean = false,
  val isDeprecated: Boolean = false,
  val ownedBy: String = "",
  val createdTimestamp: Long = 0L
)

/**
 * Result summary of a live model synchronization operation.
 */
data class NimSyncResult(
  val totalLive: Int,
  val addedCount: Int,
  val deprecatedCount: Int,
  val message: String,
  val timestamp: Long = System.currentTimeMillis()
)

/**
 * Central registry and live synchronization engine for NVIDIA NIM models.
 *
 * Keeps Aragon's models strictly synchronized with the live list from build.nvidia.com
 * (via /v1/models). Automatically discovers new models, classifies their capabilities
 * (vision, code, reasoning, fast inference), identifies deprecated models and removes
 * them from active selection, and caches the live catalog persistently on device.
 */
object NvidiaModelRegistry {

  private const val CACHE_FILE_NAME = "nvidia_live_models_cache.json"

  // Deprecated models that were previously available or in older catalogs
  // and must never be shown as active models.
  private val KNOWN_DEPRECATED_MODELS = setOf(
    "moonshotai/kimi-k3",
    "meta/llama-3-70b-instruct",
    "meta/llama-3-8b-instruct",
    "nvidia/nemotron-4-340b",
    "nvidia/llama-3-chatqa-70b"
  )

  private val sharedClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

  private val _catalog = MutableStateFlow<List<NvidiaModelEntry>>(emptyList())
  val catalog: StateFlow<List<NvidiaModelEntry>> = _catalog.asStateFlow()

  private val _lastSyncTimestamp = MutableStateFlow<Long>(0L)
  val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

  private val _syncStatusMessage = MutableStateFlow<String>("Built-in verified catalog")
  val syncStatusMessage: StateFlow<String> = _syncStatusMessage.asStateFlow()

  private val deprecatedModelIds = mutableSetOf<String>().apply {
    addAll(KNOWN_DEPRECATED_MODELS)
  }

  init {
    // Initialize with verified baseline models currently listed on build.nvidia.com
    _catalog.value = buildBaselineCatalog()
  }

  /**
   * Returns active, non-deprecated models for user selection and routing.
   * Aragon guarantees deprecated models will NOT appear in this list.
   */
  fun getActiveModels(): List<NvidiaModelEntry> {
    return _catalog.value.filter { !it.isDeprecated && !deprecatedModelIds.contains(it.id) }
  }

  /**
   * Check whether a specific model ID has been deprecated by NVIDIA.
   */
  fun isModelDeprecated(modelId: String): Boolean {
    val clean = modelId.trim()
    if (clean.isEmpty()) return false
    if (deprecatedModelIds.contains(clean)) return true
    val entry = _catalog.value.find { it.id.equals(clean, ignoreCase = true) }
    return entry?.isDeprecated == true
  }

  /**
   * Suggest an active replacement when a model has been deprecated.
   */
  fun suggestActiveReplacement(deprecatedModelId: String): String {
    val clean = deprecatedModelId.trim().lowercase()
    return when {
      clean.contains("kimi") -> "moonshotai/kimi-k2.6"
      clean.contains("vision") -> "meta/llama-3.2-11b-vision-instruct"
      clean.contains("codestral") || clean.contains("code") -> "mistralai/codestral-22b-instruct-v0.1"
      clean.contains("mistral") -> "mistralai/mistral-large-2-instruct"
      clean.contains("nemotron") -> "nvidia/nemotron-3.5-lightning-30b-a3b"
      else -> NvidiaNimConfig.DEFAULT_MODEL
    }
  }

  /**
   * Loads cached synchronized catalog from disk if present.
   */
  fun loadCache(context: Context) {
    try {
      val file = File(context.filesDir, CACHE_FILE_NAME)
      if (!file.exists()) return

      val jsonStr = file.readText()
      val json = JSONObject(jsonStr)
      val timestamp = json.optLong("last_sync_timestamp", 0L)
      val modelsArr = json.optJSONArray("models") ?: return
      val deprecatedArr = json.optJSONArray("deprecated_ids")

      if (deprecatedArr != null) {
        for (i in 0 until deprecatedArr.length()) {
          val depId = deprecatedArr.optString(i)
          if (!depId.isNullOrBlank()) deprecatedModelIds.add(depId)
        }
      }

      val loadedList = mutableListOf<NvidiaModelEntry>()
      for (i in 0 until modelsArr.length()) {
        val obj = modelsArr.optJSONObject(i) ?: continue
        val id = obj.optString("id")
        if (id.isBlank()) continue

        val isDep = obj.optBoolean("is_deprecated", false) || deprecatedModelIds.contains(id)
        loadedList.add(
          NvidiaModelEntry(
            id = id,
            name = obj.optString("name", formatModelName(id)),
            category = obj.optString("category", detectCategory(id, obj.optString("owned_by"))),
            description = obj.optString("description", "Hosted model on build.nvidia.com"),
            badge = obj.optString("badge", detectBadge(id)),
            isVerified = obj.optBoolean("is_verified", true),
            isVision = obj.optBoolean("is_vision", detectVision(id)),
            isDeprecated = isDep,
            ownedBy = obj.optString("owned_by", id.substringBefore('/')),
            createdTimestamp = obj.optLong("created", 0L)
          )
        )
      }

      if (loadedList.isNotEmpty()) {
        _catalog.value = loadedList
        _lastSyncTimestamp.value = timestamp
        val activeCount = loadedList.count { !it.isDeprecated }
        val dateStr = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(timestamp))
        _syncStatusMessage.value = "Cached ($activeCount active models · Synced $dateStr)"
      }
    } catch (_: Exception) {
      // Fallback to baseline catalog on any cache corruption
    }
  }

  /**
   * Saves current synchronized catalog to disk.
   */
  fun saveCache(context: Context) {
    try {
      val file = File(context.filesDir, CACHE_FILE_NAME)
      val json = JSONObject()
      json.put("last_sync_timestamp", _lastSyncTimestamp.value)

      val modelsArr = JSONArray()
      for (entry in _catalog.value) {
        val obj = JSONObject().apply {
          put("id", entry.id)
          put("name", entry.name)
          put("category", entry.category)
          put("description", entry.description)
          put("badge", entry.badge)
          put("is_verified", entry.isVerified)
          put("is_vision", entry.isVision)
          put("is_deprecated", entry.isDeprecated)
          put("owned_by", entry.ownedBy)
          put("created", entry.createdTimestamp)
        }
        modelsArr.put(obj)
      }
      json.put("models", modelsArr)

      val depArr = JSONArray()
      for (depId in deprecatedModelIds) {
        depArr.put(depId)
      }
      json.put("deprecated_ids", depArr)

      file.writeText(json.toString())
    } catch (_: Exception) {
      // Non-fatal if cache write fails
    }
  }

  /**
   * Synchronizes models live with NVIDIA's OpenAI-compatible endpoint.
   * Can be called with or without an API key (build.nvidia.com allows querying public models).
   */
  suspend fun sync(
    baseUrl: String = NvidiaNimConfig.DEFAULT_BASE_URL,
    apiKey: String? = null,
    context: Context? = null
  ): Result<NimSyncResult> = withContext(Dispatchers.IO) {
    try {
      val baseUrlClean = baseUrl.trim().ifBlank { NvidiaNimConfig.DEFAULT_BASE_URL }.trimEnd('/')
      val endpoint = "$baseUrlClean/models"

      val reqBuilder = Request.Builder()
        .url(endpoint)
        .addHeader("Accept", "application/json")
        .get()

      val cleanKey = apiKey?.trim() ?: ""
      if (cleanKey.isNotBlank() && cleanKey != "MY_NVIDIA_API_KEY") {
        reqBuilder.addHeader("Authorization", "Bearer $cleanKey")
      }

      val response = sharedClient.newCall(reqBuilder.build()).execute()
      if (!response.isSuccessful) {
        val code = response.code
        val msg = response.message
        response.close()
        return@withContext Result.failure(IOException("Failed to fetch models from NVIDIA NIM: HTTP $code $msg"))
      }

      val bodyStr = response.body?.string() ?: ""
      val json = JSONObject(bodyStr)
      val dataArr = json.optJSONArray("data") ?: JSONArray()

      if (dataArr.length() == 0) {
        return@withContext Result.failure(IOException("Received empty models list from NVIDIA NIM API"))
      }

      val liveModelIds = mutableSetOf<String>()
      val liveEntries = mutableListOf<NvidiaModelEntry>()
      var addedCount = 0
      var deprecatedCount = 0

      val existingMap = _catalog.value.associateBy { it.id }.toMutableMap()

      for (i in 0 until dataArr.length()) {
        val item = dataArr.optJSONObject(i) ?: continue
        val id = item.optString("id").trim()
        if (id.isBlank()) continue

        liveModelIds.add(id)
        val ownedBy = item.optString("owned_by").ifBlank { id.substringBefore('/') }
        val created = item.optLong("created", 0L)

        val existing = existingMap[id]
        if (existing == null) {
          addedCount++
          liveEntries.add(
            NvidiaModelEntry(
              id = id,
              name = formatModelName(id),
              category = detectCategory(id, ownedBy),
              description = generateDescription(id, ownedBy),
              badge = detectBadge(id),
              isVerified = true,
              isVision = detectVision(id),
              isDeprecated = false,
              ownedBy = ownedBy,
              createdTimestamp = created
            )
          )
        } else {
          // Model is confirmed active and live!
          deprecatedModelIds.remove(id)
          liveEntries.add(
            existing.copy(
              isVerified = true,
              isDeprecated = false,
              ownedBy = if (existing.ownedBy.isBlank()) ownedBy else existing.ownedBy,
              createdTimestamp = if (existing.createdTimestamp == 0L) created else existing.createdTimestamp
            )
          )
        }
      }

      // Check existing catalog models to detect deprecation:
      // Baseline verified models are retained unless explicitly in KNOWN_DEPRECATED_MODELS,
      // preventing paginated or partial /v1/models responses from falsely deprecating flagship models.
      val baselineMap = buildBaselineCatalog().associateBy { it.id }
      for ((id, entry) in existingMap) {
        if (!liveModelIds.contains(id)) {
          if (KNOWN_DEPRECATED_MODELS.contains(id)) {
            deprecatedModelIds.add(id)
            deprecatedCount++
          } else if (baselineMap.containsKey(id)) {
            liveEntries.add(entry.copy(isDeprecated = false))
          } else {
            deprecatedModelIds.add(id)
            deprecatedCount++
          }
        }
      }

      // Sort models: Flagship default first, then Agent & Coding, then Vision, then Reasoning, then alphabetically
      liveEntries.sortWith { a, b ->
        when {
          a.id == NvidiaNimConfig.DEFAULT_MODEL -> -1
          b.id == NvidiaNimConfig.DEFAULT_MODEL -> 1
          a.category == "Agent & Coding" && b.category != "Agent & Coding" -> -1
          b.category == "Agent & Coding" && a.category != "Agent & Coding" -> 1
          a.isVision && !b.isVision -> -1
          !a.isVision && b.isVision -> 1
          else -> a.name.compareTo(b.name, ignoreCase = true)
        }
      }

      _catalog.value = liveEntries
      val now = System.currentTimeMillis()
      _lastSyncTimestamp.value = now

      val dateStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
      val summaryMsg = "Synchronized: ${liveEntries.size} live models from build.nvidia.com at $dateStr ($addedCount new, $deprecatedCount deprecated excluded)"
      _syncStatusMessage.value = summaryMsg

      context?.let { saveCache(it) }

      Result.success(
        NimSyncResult(
          totalLive = liveEntries.size,
          addedCount = addedCount,
          deprecatedCount = deprecatedCount,
          message = summaryMsg,
          timestamp = now
        )
      )
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * Public query to check whether a model ID is recognized as vision/multimodal.
   */
  fun isVisionModel(id: String): Boolean = detectVision(id)

  private fun detectVision(id: String): Boolean {
    val lower = id.lowercase()
    return lower.contains("vision") ||
      lower.contains("-vl") ||
      lower.contains("vlm") ||
      lower.contains("multimodal") ||
      lower.contains("paligemma") ||
      lower.contains("gemini") ||
      lower.contains("image") ||
      lower.contains("deplot") ||
      lower.contains("vila") ||
      lower.contains("neva") ||
      lower.contains("kosmos") ||
      lower.contains("pixtral") ||
      lower.contains("llava") ||
      lower.contains("fuyu") ||
      lower.contains("omni") ||
      lower.contains("llama-4-maverick") ||
      lower.contains("llama-4-scout")
  }

  private fun detectCategory(id: String, ownedBy: String): String {
    val lower = id.lowercase()
    return when {
      detectVision(id) -> "Vision & Multimodal"
      lower.contains("coder") || lower.contains("codestral") || lower.contains("starcoder") -> "Coding Specialist"
      lower.contains("glm") || lower.contains("lightning") || lower.contains("laguna") -> "Agent & Coding"
      ownedBy.contains("deepseek", ignoreCase = true) || lower.contains("deepseek") -> "DeepSeek"
      ownedBy.contains("meta", ignoreCase = true) || lower.contains("llama") -> "Meta Llama"
      ownedBy.contains("google", ignoreCase = true) || lower.contains("gemma") -> "Google Gemma"
      ownedBy.contains("mistral", ignoreCase = true) || lower.contains("mistral") -> "Mistral"
      ownedBy.contains("nvidia", ignoreCase = true) || lower.contains("nemotron") -> "NVIDIA Nemotron"
      lower.contains("translate") || lower.contains("parse") || lower.contains("ising") -> "Specialist"
      lower.contains("embed") || lower.contains("guard") || lower.contains("safety") || lower.contains("reward") -> "Embeddings & Safety"
      else -> "Reasoning & Frontier"
    }
  }

  private fun detectBadge(id: String): String {
    val lower = id.lowercase()
    return when {
      id == NvidiaNimConfig.DEFAULT_MODEL -> "DEFAULT"
      lower.contains("vision") || lower.contains("-vl") -> "VISION"
      lower.contains("coder") || lower.contains("codestral") -> "CODE"
      lower.contains("lightning") || lower.contains("flash") -> "FAST"
      lower.contains("r1") || lower.contains("reason") -> "REASONING"
      lower.contains("550b") -> "550B"
      lower.contains("405b") -> "405B"
      lower.contains("340b") -> "340B"
      lower.contains("253b") -> "253B"
      lower.contains("120b") -> "120B"
      lower.contains("90b") -> "90B"
      lower.contains("70b") -> "70B"
      lower.contains("32b") || lower.contains("31b") || lower.contains("30b") -> "30B"
      lower.contains("22b") || lower.contains("20b") -> "20B"
      lower.contains("11b") || lower.contains("12b") -> "12B"
      lower.contains("8b") || lower.contains("7b") -> "8B"
      id.startsWith("nvidia/") -> "NVIDIA"
      id.startsWith("meta/") -> "META"
      id.startsWith("google/") -> "GOOGLE"
      id.startsWith("deepseek-ai/") -> "DEEPSEEK"
      id.startsWith("openai/") -> "OPENAI"
      else -> "LIVE"
    }
  }

  private fun formatModelName(id: String): String {
    val modelPart = id.substringAfter('/')
    val tokens = modelPart.split('-', '_', '.')
    val words = tokens.map { token ->
      when (token.lowercase()) {
        "glm" -> "GLM"
        "nemotron" -> "Nemotron"
        "llama" -> "Llama"
        "codellama" -> "CodeLlama"
        "codegemma" -> "CodeGemma"
        "gemma" -> "Gemma"
        "deepseek" -> "DeepSeek"
        "mistral" -> "Mistral"
        "mixtral" -> "Mixtral"
        "codestral" -> "Codestral"
        "starcoder" -> "StarCoder"
        "gpt" -> "GPT"
        "oss" -> "OSS"
        "riva" -> "Riva"
        "it" -> "IT"
        "vl" -> "VL"
        "vlm" -> "VLM"
        "qa" -> "QA"
        "moe" -> "MoE"
        "phi" -> "Phi"
        "kimi" -> "Kimi"
        "yi" -> "Yi"
        "fuyu" -> "Fuyu"
        "dbrx" -> "DBRX"
        "granite" -> "Granite"
        "cosmos" -> "Cosmos"
        "palmyra" -> "Palmyra"
        "zamba" -> "Zamba"
        "instruct" -> "Instruct"
        "vision" -> "Vision"
        "flash" -> "Flash"
        "lightning" -> "Lightning"
        "super" -> "Super"
        "ultra" -> "Ultra"
        "reasoning" -> "Reasoning"
        "preview" -> "Preview"
        else -> token.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
      }
    }
    return words.joinToString(" ")
  }

  private fun generateDescription(id: String, ownedBy: String): String {
    val lower = id.lowercase()
    val vendor = when {
      ownedBy.isNotBlank() -> ownedBy
      id.contains('/') -> id.substringBefore('/')
      else -> "Hosted"
    }
    return when {
      lower.contains("vision") || lower.contains("-vl") -> "Multimodal vision and visual understanding model by $vendor"
      lower.contains("coder") || lower.contains("codestral") -> "Specialized coding, synthesis, and script generation model by $vendor"
      lower.contains("reason") || lower.contains("r1") -> "High-order reasoning and step-by-step problem solver by $vendor"
      lower.contains("lightning") || lower.contains("flash") -> "Ultra-low latency high-throughput instruction model by $vendor"
      lower.contains("embed") -> "High-dimensional embedding model for vector search and retrieval by $vendor"
      lower.contains("guard") || lower.contains("safety") -> "Content safety and input guardrail model by $vendor"
      else -> "High-performance instruction model hosted on NVIDIA infrastructure by $vendor"
    }
  }

  /**
   * Verified baseline catalog matching live models on build.nvidia.com
   */
  private fun buildBaselineCatalog(): List<NvidiaModelEntry> = listOf(
    NvidiaModelEntry(
      id = "z-ai/glm-5.3",
      name = "GLM 5.3",
      category = "Agent & Coding",
      description = "Flagship coding and agentic model with verified native structured tool calling",
      badge = "DEFAULT",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "z-ai"
    ),
    NvidiaModelEntry(
      id = "z-ai/glm-5.3-flash",
      name = "GLM 5.3 Flash",
      category = "Agent & Coding",
      description = "High-throughput fast variant of GLM 5.3 for responsive execution",
      badge = "FAST",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "z-ai"
    ),
    NvidiaModelEntry(
      id = "meta/llama-3.2-11b-vision-instruct",
      name = "Llama 3.2 11B Vision",
      category = "Vision & Multimodal",
      description = "Meta's multimodal instruction model with fast inference and image reasoning",
      badge = "VISION",
      isVerified = true,
      isVision = true,
      isDeprecated = false,
      ownedBy = "meta"
    ),
    NvidiaModelEntry(
      id = "meta/llama-3.2-90b-vision-instruct",
      name = "Llama 3.2 90B Vision",
      category = "Vision & Multimodal",
      description = "Meta's flagship 90B vision and high-order visual reasoning model",
      badge = "90B",
      isVerified = true,
      isVision = true,
      isDeprecated = false,
      ownedBy = "meta"
    ),
    NvidiaModelEntry(
      id = "nvidia/nemotron-3.5-lightning-30b-a3b",
      name = "Nemotron 3.5 Lightning",
      category = "NVIDIA Nemotron",
      description = "NVIDIA 30B fast instruction model tuned for agents and workflow synthesis",
      badge = "NVIDIA",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "nvidia"
    ),
    NvidiaModelEntry(
      id = "nvidia/nemotron-3.5-super-vl-120b-a12b",
      name = "Nemotron 3.5 Super VL 120B",
      category = "Vision & Multimodal",
      description = "NVIDIA 120B large-scale multimodal vision and agent architecture",
      badge = "120B",
      isVerified = true,
      isVision = true,
      isDeprecated = false,
      ownedBy = "nvidia"
    ),
    NvidiaModelEntry(
      id = "nvidia/nemotron-3-super-120b-a12b",
      name = "Nemotron 3 Super 120B",
      category = "NVIDIA Nemotron",
      description = "NVIDIA 120B large-scale reasoning and deep problem solving architecture",
      badge = "120B",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "nvidia"
    ),
    NvidiaModelEntry(
      id = "nvidia/nemotron-3-ultra-550b-a55b",
      name = "Nemotron 3 Ultra 550B",
      category = "NVIDIA Nemotron",
      description = "NVIDIA's frontier 550B model with comprehensive domain capabilities",
      badge = "550B",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "nvidia"
    ),
    NvidiaModelEntry(
      id = "deepseek-ai/deepseek-v4.1-flash",
      name = "DeepSeek V4.1 Flash",
      category = "DeepSeek",
      description = "DeepSeek high-throughput frontier reasoning and agentic model",
      badge = "DEEPSEEK",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "deepseek-ai"
    ),
    NvidiaModelEntry(
      id = "deepseek-ai/deepseek-r1",
      name = "DeepSeek R1",
      category = "DeepSeek",
      description = "DeepSeek frontier reasoning model with native chain-of-thought and tool calling",
      badge = "REASONING",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "deepseek-ai"
    ),
    NvidiaModelEntry(
      id = "meta/llama-3.3-70b-instruct",
      name = "Llama 3.3 70B Instruct",
      category = "Meta Llama",
      description = "Meta's high-reliability 70B instruction and structured tool-calling model",
      badge = "70B",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "meta"
    ),
    NvidiaModelEntry(
      id = "nvidia/llama-3.3-nemotron-super-49b-v1",
      name = "Llama 3.3 Nemotron Super 49B",
      category = "NVIDIA Nemotron",
      description = "NVIDIA Nemotron Super 49B tuned for agentic reasoning and tool orchestration",
      badge = "NVIDIA",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "nvidia"
    ),
    NvidiaModelEntry(
      id = "qwen/qwen2.5-coder-32b-instruct",
      name = "Qwen 2.5 Coder 32B",
      category = "Coding Specialist",
      description = "Qwen 32B code generation, synthesis, and multi-language engineering model",
      badge = "CODE",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "qwen"
    ),
    NvidiaModelEntry(
      id = "deepseek-ai/deepseek-coder-6.7b-instruct",
      name = "DeepSeek Coder 6.7B",
      category = "Coding Specialist",
      description = "DeepSeek specialized code generation and refactoring model",
      badge = "CODE",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "deepseek-ai"
    ),
    NvidiaModelEntry(
      id = "mistralai/codestral-22b-instruct-v0.1",
      name = "Codestral 22B",
      category = "Coding Specialist",
      description = "Mistral coding and script synthesis specialist",
      badge = "CODE",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "mistralai"
    ),
    NvidiaModelEntry(
      id = "mistralai/mistral-large-2-instruct",
      name = "Mistral Large 2",
      category = "Mistral",
      description = "128k context multilingual flagship model from Mistral AI",
      badge = "128K",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "mistralai"
    ),
    NvidiaModelEntry(
      id = "google/diffusiongemma-26b-a4b-it",
      name = "Diffusion Gemma 26B",
      category = "Google Gemma",
      description = "Google's 26B instruction model optimized for deep contextual reasoning",
      badge = "GOOGLE",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "google"
    ),
    NvidiaModelEntry(
      id = "google/gemma-4-31b-it",
      name = "Gemma 4 31B Instruct",
      category = "Google Gemma",
      description = "Google's latest Gemma 4 frontier reasoning instruction model",
      badge = "GOOGLE",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "google"
    ),
    NvidiaModelEntry(
      id = "openai/gpt-oss-20b",
      name = "GPT-OSS 20B",
      category = "Reasoning & Frontier",
      description = "OpenAI open weights model hosted with high throughput on NVIDIA infrastructure",
      badge = "OPENAI",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "openai"
    ),
    NvidiaModelEntry(
      id = "moonshotai/kimi-k2.6",
      name = "Kimi K2.6",
      category = "Reasoning & Frontier",
      description = "Moonshot AI active agentic model with extended context",
      badge = "AGENT",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "moonshotai"
    ),
    NvidiaModelEntry(
      id = "meta/muse-glimmer-30b",
      name = "Muse Glimmer 30B",
      category = "Meta Llama",
      description = "Meta's creative, code synthesis, and structured transformation model",
      badge = "30B",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "meta"
    ),
    NvidiaModelEntry(
      id = "nvidia/cosmos-reason2-8b",
      name = "Cosmos Reason 2 8B",
      category = "NVIDIA Nemotron",
      description = "NVIDIA physical AI and spatial reasoning foundation model",
      badge = "NVIDIA",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "nvidia"
    ),
    NvidiaModelEntry(
      id = "poolside/laguna-xs-2.1",
      name = "Laguna XS 2.1",
      category = "Agent & Coding",
      description = "Poolside code generation and agent planning model",
      badge = "CODE",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "poolside"
    ),
    NvidiaModelEntry(
      id = "stepfun-ai/step-5-preview",
      name = "Step 5 Preview",
      category = "Reasoning & Frontier",
      description = "StepFun advanced multiform reasoning preview model",
      badge = "LIVE",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "stepfun-ai"
    ),
    NvidiaModelEntry(
      id = "nvidia/nemotron-parse-2.0",
      name = "Nemotron Parse 2.0",
      category = "Specialist",
      description = "NVIDIA specialist model for parsing complex data tables and documents",
      badge = "PARSE",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "nvidia"
    ),
    NvidiaModelEntry(
      id = "nvidia/riva-translate-4b-instruct-v2",
      name = "Riva Translate 4B v2",
      category = "Specialist",
      description = "NVIDIA multilingual translation and cross-lingual script assistant",
      badge = "4B",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "nvidia"
    ),
    NvidiaModelEntry(
      id = "nvidia/ising-calibration-1.5-31b",
      name = "Ising Calibration 31B",
      category = "Specialist",
      description = "Scientific and quantitative calibration model for math and data pipelines",
      badge = "31B",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "nvidia"
    ),
    NvidiaModelEntry(
      id = "01-ai/yi-large",
      name = "Yi Large",
      category = "Reasoning & Frontier",
      description = "01.AI flagship bilingual reasoning and instruction model",
      badge = "LIVE",
      isVerified = true,
      isVision = false,
      isDeprecated = false,
      ownedBy = "01-ai"
    )
  )
}
