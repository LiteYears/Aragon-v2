package com.example.agent.docs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

interface EmbeddingProvider {
  val label: String
  val profile: EmbeddingProfile
  suspend fun embedPassages(texts: List<String>): List<FloatArray>
  suspend fun embedQuery(text: String): FloatArray
}

object EmbeddingCodec {
  fun toBytes(vector: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (value in vector) buffer.putFloat(value)
    return buffer.array()
  }

  fun fromBytes(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val vector = FloatArray(bytes.size / 4)
    for (i in vector.indices) vector[i] = buffer.getFloat()
    return vector
  }

  fun normalize(vector: FloatArray): FloatArray {
    var sum = 0.0
    for (value in vector) sum += value.toDouble() * value.toDouble()
    if (sum <= 0.0) return vector
    val norm = sqrt(sum).toFloat()
    if (norm == 0f) return vector
    val result = FloatArray(vector.size)
    for (i in vector.indices) result[i] = vector[i] / norm
    return result
  }

  fun cosine(a: FloatArray, b: FloatArray): Double {
    val size = minOf(a.size, b.size)
    var dot = 0.0
    for (i in 0 until size) dot += a[i].toDouble() * b[i].toDouble()
    return dot
  }
}

class NimEmbeddingProvider(
  private val apiKeys: List<String>,
  baseUrl: String,
  private val model: String = DEFAULT_MODEL,
  private val client: OkHttpClient = defaultClient
) : EmbeddingProvider {

  private val endpoint: String = baseUrl.trim().trimEnd('/').ifBlank { "https://integrate.api.nvidia.com/v1" } + "/embeddings"
  private var keyCursor = 0

  override val label: String = "NVIDIA NIM ($model)"

  override val profile: EmbeddingProfile
    get() = EmbeddingProfile("nim", model, 0)

  override suspend fun embedPassages(texts: List<String>): List<FloatArray> =
    embed(texts, inputType = "passage")

  override suspend fun embedQuery(text: String): FloatArray =
    embed(listOf(text), inputType = "query").firstOrNull()
      ?: throw DocumentException("Embedding provider returned no vector")

  private suspend fun embed(texts: List<String>, inputType: String?): List<FloatArray> = withContext(Dispatchers.IO) {
    val keys = apiKeys.map { it.trim() }.filter { it.isNotBlank() }
    if (keys.isEmpty()) throw DocumentException("No NVIDIA API key configured for embeddings")
    val results = mutableListOf<FloatArray>()
    var dims = 0
    for (batch in texts.chunked(DocumentLimits.EMBED_BATCH_SIZE)) {
      val vectors = requestWithRotation(keys, batch, inputType)
      for (vector in vectors) {
        if (dims == 0) dims = vector.size
        if (vector.size != dims) throw DocumentException("Embedding dimension mismatch from provider")
        results.add(EmbeddingCodec.normalize(vector))
      }
    }
    results
  }

  private suspend fun requestWithRotation(keys: List<String>, batch: List<String>, inputType: String?): List<FloatArray> {
    var lastError: Exception? = null
    for (attempt in keys.indices) {
      val key = keys[(keyCursor + attempt) % keys.size]
      try {
        return requestWithModelFallback(key, batch, inputType)
      } catch (e: EmbeddingHttpException) {
        lastError = e
        if (e.status !in listOf(401, 403, 429, 500, 502, 503)) throw e
        delay(350L * (attempt + 1))
      }
    }
    keyCursor = (keyCursor + 1) % keys.size
    throw DocumentException("Embedding request failed: ${lastError?.message ?: "unknown error"}")
  }

  private fun requestWithModelFallback(key: String, batch: List<String>, inputType: String?): List<FloatArray> {
    val requiresInputType = model.contains("nv-embed", ignoreCase = true) || model.contains("nemotron", ignoreCase = true)
    return try {
      request(key, batch, if (requiresInputType) inputType else null, model)
    } catch (e: EmbeddingHttpException) {
      if ((e.status == 400 || e.status == 422) && !requiresInputType && inputType != null) {
        request(key, batch, inputType, model)
      } else if (e.status == 404 && model == DEFAULT_MODEL) {
        request(key, batch, inputType ?: "passage", FALLBACK_MODEL)
      } else {
        throw e
      }
    }
  }

  private fun request(key: String, batch: List<String>, inputType: String?, modelToUse: String = model): List<FloatArray> {
    val payload = JSONObject()
    payload.put("input", JSONArray(batch))
    payload.put("model", modelToUse)
    payload.put("encoding_format", "float")
    payload.put("truncate", "END")
    if (inputType != null) payload.put("input_type", inputType)

    val request = Request.Builder()
      .url(endpoint)
      .addHeader("Authorization", "Bearer $key")
      .addHeader("Accept", "application/json")
      .post(payload.toString().toRequestBody(JSON_MEDIA))
      .build()

    client.newCall(request).execute().use { response ->
      val body = response.body?.string() ?: ""
      if (!response.isSuccessful) {
        throw EmbeddingHttpException(response.code, "HTTP ${response.code}: ${body.take(200)}")
      }
      val root = JSONObject(body)
      val data = root.optJSONArray("data") ?: throw DocumentException("Embedding response missing data")
      val ordered = mutableListOf<Pair<Int, FloatArray>>()
      for (i in 0 until data.length()) {
        val item = data.getJSONObject(i)
        val index = item.optInt("index", i)
        val arr = item.optJSONArray("embedding") ?: continue
        val vector = FloatArray(arr.length())
        for (j in 0 until arr.length()) vector[j] = arr.optDouble(j).toFloat()
        ordered.add(index to vector)
      }
      return ordered.sortedBy { it.first }.map { it.second }
    }
  }

  companion object {
    const val DEFAULT_MODEL = "baai/bge-m3"
    const val FALLBACK_MODEL = "nvidia/nv-embedqa-e5-v5"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    val defaultClient: OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(90, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .build()
  }
}

class GeminiEmbeddingProvider(
  private val apiKey: String,
  private val model: String = DEFAULT_MODEL,
  private val client: OkHttpClient = NimEmbeddingProvider.defaultClient
) : EmbeddingProvider {

  override val label: String = "Google Gemini ($model)"

  override val profile: EmbeddingProfile
    get() = EmbeddingProfile("gemini", model, 0)

  override suspend fun embedPassages(texts: List<String>): List<FloatArray> =
    embed(texts, taskType = "RETRIEVAL_DOCUMENT")

  override suspend fun embedQuery(text: String): FloatArray =
    embed(listOf(text), taskType = "RETRIEVAL_QUERY").firstOrNull()
      ?: throw DocumentException("Gemini embedding returned no vector")

  private suspend fun embed(texts: List<String>, taskType: String): List<FloatArray> = withContext(Dispatchers.IO) {
    val key = apiKey.trim()
    if (key.isBlank()) throw DocumentException("No Gemini API key configured for embeddings")
    val results = mutableListOf<FloatArray>()
    for (batch in texts.chunked(DocumentLimits.EMBED_BATCH_SIZE)) {
      val requests = JSONArray()
      for (text in batch) {
        val item = JSONObject()
        item.put("model", "models/$model")
        item.put("taskType", taskType)
        val content = JSONObject()
        val parts = JSONArray()
        val part = JSONObject()
        part.put("text", text)
        parts.put(part)
        content.put("parts", parts)
        item.put("content", content)
        requests.put(item)
      }
      val payload = JSONObject().put("requests", requests)
      val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:batchEmbedContents?key=$key"
      val request = Request.Builder()
        .url(url)
        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
      client.newCall(request).execute().use { response ->
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
          throw DocumentException("Gemini embedding failed (HTTP ${response.code}): ${body.take(200)}")
        }
        val root = JSONObject(body)
        val embeddings = root.optJSONArray("embeddings") ?: throw DocumentException("Gemini embedding response missing vectors")
        for (i in 0 until embeddings.length()) {
          val values = embeddings.getJSONObject(i).optJSONArray("values") ?: continue
          val vector = FloatArray(values.length())
          for (j in 0 until values.length()) vector[j] = values.optDouble(j).toFloat()
          results.add(EmbeddingCodec.normalize(vector))
        }
      }
    }
    results
  }

  companion object {
    const val DEFAULT_MODEL = "gemini-embedding-001"
  }
}

class EmbeddingHttpException(val status: Int, message: String) : Exception(message)

object EmbeddingProviders {

  fun create(
    nimBaseUrl: String,
    nimApiKeys: List<String>,
    geminiApiKey: String
  ): EmbeddingProvider? {
    val keys = nimApiKeys.map { it.trim() }.filter { it.isNotBlank() }
    if (keys.isNotEmpty()) {
      return NimEmbeddingProvider(keys, nimBaseUrl)
    }
    if (geminiApiKey.trim().isNotBlank()) {
      return GeminiEmbeddingProvider(geminiApiKey.trim())
    }
    return null
  }

  fun describeAvailability(nimApiKeys: List<String>, geminiApiKey: String): String {
    val keys = nimApiKeys.map { it.trim() }.filter { it.isNotBlank() }
    return when {
      keys.isNotEmpty() -> "NVIDIA NIM ${NimEmbeddingProvider.DEFAULT_MODEL}"
      geminiApiKey.trim().isNotBlank() -> "Google ${GeminiEmbeddingProvider.DEFAULT_MODEL}"
      else -> "not configured (lexical search only)"
    }
  }
}
