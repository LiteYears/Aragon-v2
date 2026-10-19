package com.example.agent.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest
import com.example.agent.core.E2BSandboxProvider
import com.example.agent.core.SandboxProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrLine(
  val text: String,
  val confidence: Float?,
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int
)

data class OcrTableInference(
  val header: List<String>,
  val rows: List<List<String>>,
  val confidence: String,
  val uncertain: Boolean,
  val bbox: List<Int>? = null
)

data class OcrResult(
  val fullText: String,
  val lines: List<OcrLine>,
  val tables: List<OcrTableInference>,
  val engine: String,
  val averageConfidence: Float?,
  val warnings: List<String> = emptyList(),
  val usedCloud: Boolean = false
)

interface OcrEngine {
  val label: String
  suspend fun recognize(imageFile: File, hintArabic: Boolean): OcrResult
}

/**
 * Configuration of the active model to check for vision capability.
 */
data class ActiveVisionModelConfig(
  val hasVision: Boolean,
  val providerType: String, // "gemini", "nvidia_nim", "sandbox"
  val modelName: String,
  val apiKey: String,
  val apiKeys: List<String> = emptyList(),
  val baseUrl: String = "https://integrate.api.nvidia.com/v1"
)

/**
 * On-device OCR engine placeholder.
 * On-device ML Kit OCR has been replaced by native multimodal vision (Gemini / NVIDIA NIM)
 * and E2B sandbox Python document processing.
 */
class MlKitOcrEngine(private val context: Context) : OcrEngine {

  override val label: String = "On-device OCR (Multimodal Routed)"

  override suspend fun recognize(imageFile: File, hintArabic: Boolean): OcrResult {
    throw DocumentException("On-device OCR disabled in favor of native multimodal vision & E2B document processing")
  }
}

/**
 * Direct vision OCR using NVIDIA NIM OpenAI-compatible multimodal endpoint.
 * Dispatches directly to active vision models such as Llama 3.2 11B/90B Vision.
 */
class NvidiaNimVisionOcrEngine(
  private val configSupplier: () -> ActiveVisionModelConfig?
) : OcrEngine {

  override val label: String
    get() = "NVIDIA NIM Vision (${configSupplier()?.modelName ?: "vision"})"

  fun isAvailable(): Boolean {
    val cfg = configSupplier() ?: return false
    val key = cfg.apiKey.trim().ifBlank { cfg.apiKeys.firstOrNull()?.trim() ?: "" }
    return cfg.hasVision && key.isNotBlank() && key != "MY_NVIDIA_API_KEY"
  }

  override suspend fun recognize(imageFile: File, hintArabic: Boolean): OcrResult = withContext(Dispatchers.IO) {
    val cfg = configSupplier() ?: throw DocumentException("NVIDIA NIM Vision configuration is not available")
    val key = cfg.apiKey.trim().ifBlank { cfg.apiKeys.firstOrNull()?.trim() ?: "" }
    if (key.isBlank() || key == "MY_NVIDIA_API_KEY") {
      throw DocumentException("NVIDIA NIM Vision API key is not configured")
    }

    val bitmap = UniversalImageHelper.decodeNormalizedBitmap(imageFile, UniversalImageHelper.MAX_OCR_DIMENSION)
      ?: throw DocumentException("Could not decode image for vision model: ${imageFile.name}")
    val base64 = UniversalImageHelper.toBase64Jpeg(bitmap)
    bitmap.recycle()

    val prompt = "You are a high-precision OCR and document analysis engine. " +
      "Extract ALL text, handwriting, numbers, symbols, and tables from this image verbatim. " +
      "Preserve exact wording, language, line breaks, and table structure. Do NOT summarize or omit anything. " +
      "Output valid JSON in this exact format:\n" +
      "{\n" +
      "  \"full_text\": \"full extracted text with newlines\",\n" +
      "  \"lines\": [{\"text\": \"line 1\"}, {\"text\": \"line 2\"}],\n" +
      "  \"tables\": [{\"header\": [\"Col 1\", \"Col 2\"], \"rows\": [[\"val 1\", \"val 2\"]], \"confidence\": \"HIGH\"}]\n" +
      "}"

    val reqJson = JSONObject().apply {
      put("model", cfg.modelName)
      val msgs = JSONArray()
      val userMsg = JSONObject()
      userMsg.put("role", "user")
      val parts = JSONArray()
      parts.put(JSONObject().apply {
        put("type", "text")
        put("text", prompt)
      })
      parts.put(JSONObject().apply {
        put("type", "image_url")
        put("image_url", JSONObject().apply {
          put("url", "data:image/jpeg;base64,$base64")
        })
      })
      userMsg.put("content", parts)
      msgs.put(userMsg)
      put("messages", msgs)
      put("temperature", 0.0)
      put("max_tokens", 4096)
    }

    val endpoint = "${cfg.baseUrl.trimEnd('/')}/chat/completions"
    val url = java.net.URL(endpoint)
    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 35000
      readTimeout = 65000
      doOutput = true
      setRequestProperty("Content-Type", "application/json; charset=UTF-8")
      setRequestProperty("Authorization", "Bearer $key")
    }
    conn.outputStream.use { it.write(reqJson.toString().toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    if (code in 200..299) {
      val resText = conn.inputStream.bufferedReader().use { it.readText() }
      return@withContext parseModelContent(resText, "nvidia-nim-vision (${cfg.modelName})")
    } else {
      val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
      throw DocumentException("NVIDIA NIM Vision HTTP $code (${cfg.modelName}): ${err.take(200)}")
    }
  }
}

/**
 * Multimodal Cloud Vision OCR powered by Google Gemini.
 * Transcribes complex multilingual text, Arabic script, cursive, and structured tables.
 */
class GeminiVisionOcrEngine(
  private val modelName: String = "gemini-2.5-flash",
  private val apiKeySupplier: () -> String?
) : OcrEngine {

  override val label: String = "Cloud Vision OCR (Google Gemini: $modelName)"

  fun isAvailable(): Boolean {
    val key = apiKeySupplier()?.trim().orEmpty()
    return key.isNotBlank() && key != "MY_GEMINI_API_KEY"
  }

  override suspend fun recognize(imageFile: File, hintArabic: Boolean): OcrResult = withContext(Dispatchers.IO) {
    val key = apiKeySupplier()?.trim().orEmpty()
    if (key.isBlank() || key == "MY_GEMINI_API_KEY") {
      throw DocumentException("Gemini Vision OCR is not available: API key not configured")
    }

    val bitmap = UniversalImageHelper.decodeNormalizedBitmap(imageFile, UniversalImageHelper.MAX_OCR_DIMENSION)
      ?: throw DocumentException("Could not decode image for Gemini Vision: ${imageFile.name}")
    val base64 = UniversalImageHelper.toBase64Jpeg(bitmap)
    bitmap.recycle()

    val prompt = "You are a high-precision OCR and document analysis engine. " +
      "Extract ALL text and tables verbatim from this image. " +
      "CRITICAL: If the image contains Arabic text (printed or handwritten), transcribe all Arabic words accurately, preserving their original letters, sequence, and structure. " +
      "Do NOT omit headers, fine print, numbers, or tables. " +
      "Output valid JSON in this exact format:\n" +
      "{\n" +
      "  \"full_text\": \"full extracted text with newlines\",\n" +
      "  \"lines\": [{\"text\": \"line 1\"}, {\"text\": \"line 2\"}],\n" +
      "  \"tables\": [{\"header\": [\"Col 1\", \"Col 2\"], \"rows\": [[\"val 1\", \"val 2\"]], \"confidence\": \"HIGH\"}]\n" +
      "}"

    val reqJson = JSONObject().apply {
      val contents = JSONArray()
      val contentObj = JSONObject()
      val parts = JSONArray()
      parts.put(JSONObject().put("text", prompt))
      parts.put(JSONObject().put("inlineData", JSONObject().apply {
        put("mimeType", "image/jpeg")
        put("data", base64)
      }))
      contentObj.put("parts", parts)
      contents.put(contentObj)
      put("contents", contents)
      put("generationConfig", JSONObject().apply {
        put("responseMimeType", "application/json")
        put("temperature", 0.0)
      })
    }

    val models = listOf(modelName, "gemini-2.5-flash", "gemini-1.5-flash", "gemini-2.0-flash").distinct()
    var lastError: Exception? = null

    for (model in models) {
      val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
      try {
        val url = java.net.URL(endpoint)
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
          requestMethod = "POST"
          connectTimeout = 30000
          readTimeout = 60000
          doOutput = true
          setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        conn.outputStream.use { it.write(reqJson.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code in 200..299) {
          val resText = conn.inputStream.bufferedReader().use { it.readText() }
          return@withContext parseGeminiResponse(resText, model)
        } else {
          val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
          lastError = DocumentException("Gemini Vision HTTP $code ($model): ${err.take(200)}")
          if (code == 404) continue
          break
        }
      } catch (e: Exception) {
        lastError = e
      }
    }
    throw lastError ?: DocumentException("Gemini Vision OCR failed")
  }

  private fun parseGeminiResponse(jsonStr: String, model: String): OcrResult {
    val root = JSONObject(jsonStr)
    val candidates = root.optJSONArray("candidates")
    if (candidates == null || candidates.length() == 0) {
      throw DocumentException("Gemini Vision returned empty candidates list")
    }
    val candidate = candidates.getJSONObject(0)
    val content = candidate.optJSONObject("content")
    val parts = content?.optJSONArray("parts")
    if (parts == null || parts.length() == 0) {
      throw DocumentException("Gemini Vision returned no parts")
    }
    val rawText = parts.getJSONObject(0).optString("text").trim()
    return parseModelContent(rawText, "gemini-vision ($model)")
  }
}

/**
 * Universal active vision OCR dispatcher:
 * Automatically uses the active model directly on the image if it supports vision.
 */
class ActiveVisionOcrEngine(
  private val configSupplier: () -> ActiveVisionModelConfig?
) : OcrEngine {

  override val label: String
    get() = "Active Vision Model (${configSupplier()?.modelName ?: "none"})"

  fun isAvailable(): Boolean {
    val cfg = configSupplier() ?: return false
    if (!cfg.hasVision) return false
    val key = cfg.apiKey.trim().ifBlank { cfg.apiKeys.firstOrNull()?.trim() ?: "" }
    return key.isNotBlank() && !key.startsWith("MY_")
  }

  override suspend fun recognize(imageFile: File, hintArabic: Boolean): OcrResult = withContext(Dispatchers.IO) {
    val cfg = configSupplier() ?: throw DocumentException("Active vision model is not configured")
    when (cfg.providerType.lowercase()) {
      "gemini" -> {
        val geminiEngine = GeminiVisionOcrEngine(modelName = cfg.modelName) { cfg.apiKey }
        geminiEngine.recognize(imageFile, hintArabic)
      }
      "nvidia_nim" -> {
        val nimEngine = NvidiaNimVisionOcrEngine { cfg }
        nimEngine.recognize(imageFile, hintArabic)
      }
      else -> throw DocumentException("Active model does not support vision direct execution")
    }
  }
}

class E2BTesseractOcrEngine(
  private val workspaceDir: File,
  private val sandboxProviderSupplier: () -> SandboxProvider?,
  private val sessionIdSupplier: () -> String
) : OcrEngine {

  override val label: String = "Cloud OCR (E2B Tesseract ara+eng)"

  private val provisioned = ConcurrentHashMap<String, Boolean>()

  fun isAvailable(): Boolean {
    val provider = sandboxProviderSupplier() as? E2BSandboxProvider ?: return false
    val key = provider.apiKey.trim()
    return key.isNotBlank() && key != "MY_E2B_API_KEY"
  }

  override suspend fun recognize(imageFile: File, hintArabic: Boolean): OcrResult = withContext(Dispatchers.IO) {
    if (!isAvailable()) {
      throw DocumentException("Cloud OCR is not available: E2B sandbox is not configured")
    }
    val provider = sandboxProviderSupplier() as? E2BSandboxProvider
      ?: throw DocumentException("Cloud OCR is not available: E2B sandbox is not configured")
    val sessionId = sessionIdSupplier().ifBlank { "document-ocr" }
    val jobId = UUID.randomUUID().toString().replace("-", "").take(10)
    val remoteDir = ".aragon_ocr"
    val scriptRemote = "$remoteDir/aragon_ocr_pipeline.py"
    val inputRemote = "$remoteDir/${jobId}_input.jpg"
    val outputRemote = "$remoteDir/${jobId}_out.json"

    provisionPackages(provider, sessionId)

    val scriptFile = File(workspaceDir, ".documents_tools/aragon_ocr_pipeline.py")
    scriptFile.parentFile?.mkdirs()
    scriptFile.writeText(OCR_PIPELINE_SCRIPT)

    val uploadBitmap = UniversalImageHelper.decodeNormalizedBitmap(imageFile, 2560)
    val uploadFile = File(workspaceDir, ".documents_tools/${jobId}_upload.jpg")
    uploadFile.parentFile?.mkdirs()
    if (uploadBitmap != null) {
      UniversalImageHelper.saveToFile(uploadBitmap, uploadFile)
      uploadBitmap.recycle()
    } else {
      imageFile.copyTo(uploadFile, overwrite = true)
    }

    try {
      if (!provider.pushFileToRemote(sessionId, scriptRemote, scriptFile)) {
        throw DocumentException("Failed to upload OCR pipeline into the sandbox")
      }
      if (!provider.pushFileToRemote(sessionId, inputRemote, uploadFile)) {
        throw DocumentException("Failed to upload the image for cloud OCR")
      }
      val lang = if (hintArabic) "ara+eng" else "eng"
      val command = "bash -lc 'cd /home/user && python3 $scriptRemote $inputRemote $lang $outputRemote'"
      val request = RunCommandRequest.builder()
        .command(command)
        .timeout(Duration.ofSeconds(300))
        .build()
      val execution = provider.executeCommand(sessionId, request)
      if ((execution.exitCode ?: -1) != 0) {
        val stderr = execution.stderrText().trim().takeLast(400)
        throw DocumentException("Cloud OCR failed (exit ${execution.exitCode ?: -1}): $stderr")
      }
      val localOut = File(workspaceDir, ".documents_tools/ocr_$jobId.json")
      localOut.parentFile?.mkdirs()
      if (!provider.pullFileFromRemote(sessionId, outputRemote, localOut)) {
        throw DocumentException("Cloud OCR produced no output file")
      }
      try {
        return@withContext parseCloudResult(localOut)
      } finally {
        localOut.delete()
      }
    } finally {
      try {
        val cleanup = RunCommandRequest.builder()
          .command("bash -lc 'rm -f /home/user/$inputRemote /home/user/$outputRemote 2>/dev/null; true'")
          .timeout(Duration.ofSeconds(20))
          .build()
        provider.executeCommand(sessionId, cleanup)
      } catch (_: Exception) {
      }
      uploadFile.delete()
    }
  }

  private suspend fun provisionPackages(provider: E2BSandboxProvider, sessionId: String) {
    if (provisioned[sessionId] == true) return
    val checkCommand = "bash -lc 'command -v tesseract >/dev/null 2>&1 && tesseract --list-langs 2>/dev/null | grep -qx ara && python3 -c \"import PIL\" 2>/dev/null && echo ARAGON_OCR_READY || echo ARAGON_OCR_MISSING'"
    val check = provider.executeCommand(
      sessionId,
      RunCommandRequest.builder().command(checkCommand).timeout(Duration.ofSeconds(60)).build()
    )
    if (check.stdoutText().contains("ARAGON_OCR_READY")) {
      provisioned[sessionId] = true
      return
    }
    val installCommand = "bash -lc 'sudo apt-get update -qq >/dev/null 2>&1; sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq tesseract-ocr tesseract-ocr-ara python3-pil >/dev/null 2>&1 || (DEBIAN_FRONTEND=noninteractive apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq tesseract-ocr tesseract-ocr-ara python3-pil) >/dev/null 2>&1 && echo ARAGON_OCR_INSTALLED || echo ARAGON_OCR_INSTALL_FAILED'"
    val install = provider.executeCommand(
      sessionId,
      RunCommandRequest.builder().command(installCommand).timeout(Duration.ofSeconds(420)).build()
    )
    val ok = install.stdoutText().contains("ARAGON_OCR_INSTALLED")
    provisioned[sessionId] = ok
    if (!ok) {
      throw DocumentException("Could not provision cloud OCR packages (tesseract-ocr, tesseract-ocr-ara, python3-pil) in the E2B sandbox")
    }
  }

  private fun parseCloudResult(file: File): OcrResult {
    val root = JSONObject(file.readText())
    val fullText = root.optString("full_text")
    val lines = mutableListOf<OcrLine>()
    root.optJSONArray("lines")?.let { arr ->
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val bbox = obj.optJSONArray("bbox")
        lines.add(
          OcrLine(
            text = obj.optString("text"),
            confidence = if (obj.isNull("conf")) null else obj.optDouble("conf").toFloat(),
            left = bbox?.optInt(0) ?: 0,
            top = bbox?.optInt(1) ?: 0,
            right = bbox?.optInt(2) ?: 0,
            bottom = bbox?.optInt(3) ?: 0
          )
        )
      }
    }
    val tables = mutableListOf<OcrTableInference>()
    root.optJSONArray("tables")?.let { arr ->
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val header = mutableListOf<String>()
        obj.optJSONArray("header")?.let { h -> for (j in 0 until h.length()) header.add(h.optString(j)) }
        val rows = mutableListOf<List<String>>()
        obj.optJSONArray("rows")?.let { rs ->
          for (r in 0 until rs.length()) {
            val rowArr = rs.optJSONArray(r) ?: JSONArray()
            val row = mutableListOf<String>()
            for (c in 0 until rowArr.length()) row.add(rowArr.optString(c))
            rows.add(row)
          }
        }
        val confidence = obj.optString("confidence", "LOW")
        val bboxList = obj.optJSONArray("bbox")?.let { b -> (0 until minOf(b.length(), 4)).map { b.optInt(it) } }
        tables.add(OcrTableInference(header, rows, confidence, confidence == "LOW", bboxList))
      }
    }
    val avg = if (root.isNull("avg_conf")) null else root.optDouble("avg_conf").toFloat()
    val warnings = mutableListOf<String>()
    if (fullText.isBlank()) warnings.add("Cloud OCR detected no text")
    for (table in tables) {
      if (table.uncertain) warnings.add("A low-confidence table layout was detected; treat its structure as approximate")
    }
    return OcrResult(
      fullText = fullText,
      lines = lines.filter { it.text.isNotBlank() },
      tables = tables,
      engine = "e2b-tesseract",
      averageConfidence = avg,
      warnings = warnings,
      usedCloud = true
    )
  }
}

/**
 * Parses raw model output (JSON or markdown) into structured OcrResult.
 */
internal fun parseModelContent(rawText: String, engineLabel: String): OcrResult {
  val cleanJson = if (rawText.startsWith("```json")) {
    rawText.removePrefix("```json").substringBeforeLast("```").trim()
  } else if (rawText.startsWith("```")) {
    rawText.removePrefix("```").substringBeforeLast("```").trim()
  } else {
    rawText.trim()
  }

  val parsedJson = try {
    JSONObject(cleanJson)
  } catch (_: Exception) {
    null
  }

  val fullText = parsedJson?.optString("full_text")?.ifBlank { null } ?: rawText.trim()
  val lines = mutableListOf<OcrLine>()
  parsedJson?.optJSONArray("lines")?.let { arr ->
    for (i in 0 until arr.length()) {
      val item = arr.opt(i)
      val lineText = when (item) {
        is JSONObject -> item.optString("text")
        is String -> item
        else -> item.toString()
      }.trim()
      if (lineText.isNotBlank()) {
        lines.add(OcrLine(text = lineText, confidence = 0.98f, left = 0, top = 0, right = 0, bottom = 0))
      }
    }
  }
  if (lines.isEmpty()) {
    fullText.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach {
      lines.add(OcrLine(text = it, confidence = 0.98f, left = 0, top = 0, right = 0, bottom = 0))
    }
  }

  val tables = mutableListOf<OcrTableInference>()
  parsedJson?.optJSONArray("tables")?.let { tArr ->
    for (i in 0 until tArr.length()) {
      val tObj = tArr.optJSONObject(i) ?: continue
      val header = mutableListOf<String>()
      tObj.optJSONArray("header")?.let { hArr ->
        for (j in 0 until hArr.length()) header.add(hArr.optString(j))
      }
      val rows = mutableListOf<List<String>>()
      tObj.optJSONArray("rows")?.let { rArr ->
        for (r in 0 until rArr.length()) {
          val rowA = rArr.optJSONArray(r) ?: continue
          val row = (0 until rowA.length()).map { rowA.optString(it) }
          rows.add(row)
        }
      }
      tables.add(OcrTableInference(header, rows, "HIGH", false))
    }
  }

  return OcrResult(
    fullText = fullText,
    lines = lines,
    tables = tables,
    engine = engineLabel,
    averageConfidence = 0.98f,
    warnings = emptyList(),
    usedCloud = true
  )
}

/**
 * Authoritative OCR router with strict precedence:
 * 1. If active model has vision, use model directly on image.
 * 2. If not, use on-device OCR engine (enhanced for all formats, orientations, contrast).
 * 3. Then other fallbacks (Gemini Cloud Vision, E2B Tesseract ara+eng).
 */
class OcrRouter(
  context: Context,
  private val workspaceDir: File,
  private val sandboxProviderSupplier: () -> SandboxProvider?,
  private val sessionIdSupplier: () -> String,
  private val autoCloudSupplier: () -> Boolean,
  private val geminiApiKeySupplier: (() -> String)? = null,
  private val activeVisionSupplier: (() -> ActiveVisionModelConfig?)? = null
) {

  private val activeVision = ActiveVisionOcrEngine { activeVisionSupplier?.invoke() }
  private val onDevice = MlKitOcrEngine(context)
  private val cloud = E2BTesseractOcrEngine(workspaceDir, sandboxProviderSupplier, sessionIdSupplier)
  private val geminiVision = GeminiVisionOcrEngine { geminiApiKeySupplier?.invoke() }

  fun isVisionModelActive(): Boolean = activeVision.isAvailable()

  fun cloudAvailable(): Boolean = activeVision.isAvailable() || geminiVision.isAvailable() || cloud.isAvailable()

  suspend fun recognize(imageFile: File, forceCloud: Boolean = false): OcrResult {
    // 1. IF THE ACTIVE MODEL HAS VISION: USE THE MODEL DIRECTLY ON THE IMAGE INSTEAD OF THE OCR ENGINE
    if (activeVision.isAvailable()) {
      try {
        val modelResult = activeVision.recognize(imageFile, hintArabic = true)
        if (modelResult.fullText.isNotBlank()) {
          return modelResult
        }
      } catch (_: Exception) {
        // Fall back to OCR engine on failure
      }
    }

    // 2. IF NOT, USE THE OCR ENGINE
    val local = try {
      onDevice.recognize(imageFile, hintArabic = false)
    } catch (_: Exception) {
      null
    }

    // 3. THEN OTHER FALLBACKS (Cloud Vision OCR, E2B Tesseract)
    val cloudEnabled = forceCloud || (cloud.isAvailable() && autoCloudSupplier()) || geminiVision.isAvailable()

    if (cloudEnabled) {
      val needsArabic = local == null || local.fullText.isBlank() ||
        DocumentText.arabicRatio(local.fullText) >= 0.15 ||
        (local.averageConfidence ?: 100f) < 55f ||
        wordCount(local.fullText) < 3

      // Fallback A: Gemini Vision Cloud OCR
      if (geminiVision.isAvailable() && (forceCloud || needsArabic)) {
        try {
          val geminiRes = geminiVision.recognize(imageFile, hintArabic = true)
          if (geminiRes.fullText.isNotBlank()) {
            return geminiRes
          }
        } catch (_: Exception) {
        }
      }

      // Fallback B: E2B Cloud Tesseract OCR
      if (cloud.isAvailable() && (forceCloud || needsArabic)) {
        val cloudResult = try {
          cloud.recognize(imageFile, hintArabic = needsArabic || local == null)
        } catch (e: Exception) {
          if (local != null && local.fullText.isNotBlank()) {
            return local.copy(
              warnings = local.warnings + "Cloud OCR unavailable (${e.message ?: "error"}); used on-device OCR"
            )
          }
          null
        }
        if (cloudResult != null) {
          if (local != null && !forceCloud) {
            val localWords = wordCount(local.fullText)
            val cloudWords = wordCount(cloudResult.fullText)
            if (cloudWords < 3 && localWords >= 3 && DocumentText.arabicRatio(local.fullText) < 0.15) {
              return local.copy(warnings = local.warnings + "Cloud OCR returned less text; kept the on-device result")
            }
          }
          return cloudResult
        }
      }
    }

    if (local != null && local.fullText.isNotBlank()) {
      val warnings = local.warnings.toMutableList()
      val arabicRatio = DocumentText.arabicRatio(local.fullText)
      if (arabicRatio >= 0.15) {
        if (!cloud.isAvailable() && !geminiVision.isAvailable() && !activeVision.isAvailable()) {
          warnings.add("Arabic text detected. Cloud OCR is not configured, so on-device Latin OCR was used and may contain errors")
        }
      }
      return local.copy(warnings = warnings)
    }

    // Gracefully handle images without readable text rather than throwing fatal exception
    return OcrResult(
      fullText = "",
      lines = emptyList(),
      tables = emptyList(),
      engine = "none",
      averageConfidence = 0.0f,
      warnings = listOf("No readable text detected by OCR engines; image preserved as visual asset in workspace"),
      usedCloud = false
    )
  }

  private fun wordCount(text: String): Int =
    text.split(Regex("\\s+")).count { it.length >= 2 }
}

private const val OCR_PIPELINE_SCRIPT = """import json, os, subprocess, sys, tempfile

def fallback_dirs():
    for p in ("/usr/lib/python3/dist-packages", "/usr/local/lib/python3.11/dist-packages", "/usr/local/lib/python3.10/dist-packages"):
        if os.path.isdir(p) and p not in sys.path:
            sys.path.insert(0, p)

fallback_dirs()
os.environ.setdefault("LD_LIBRARY_PATH", "/usr/lib/x86_64-linux-gnu")

def load_image(path):
    try:
        from PIL import Image, ImageOps
    except Exception:
        return None
    img = Image.open(path)
    img = img.convert("L")
    w, h = img.size
    if w < 1400 and w > 0:
        scale = 1400.0 / float(w)
        img = img.resize((int(w * scale), int(h * scale)), Image.LANCZOS)
    if max(img.size) > 4000:
        s = 4000.0 / float(max(img.size))
        img = img.resize((int(img.size[0] * s), int(img.size[1] * s)), Image.LANCZOS)
    try:
        img = ImageOps.autocontrast(img, cutoff=1)
    except Exception:
        pass
    out = os.path.join(tempfile.gettempdir(), "aragon_ocr_input.png")
    img.save(out, "PNG")
    return out

def run_tesseract(image_path, lang, base):
    cmd = ["tesseract", image_path, base, "-l", lang, "--psm", "3", "tsv"]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=240)
    if proc.returncode != 0:
        raise RuntimeError("tesseract failed: " + proc.stderr.decode("utf-8", "ignore")[-500:])

def parse_tsv(path):
    words = []
    with open(path, "r", encoding="utf-8", errors="ignore") as fh:
        fh.readline()
        for raw in fh:
            parts = raw.rstrip("\n").split("\t")
            if len(parts) < 12:
                continue
            if parts[0] != "5":
                continue
            text = parts[11].strip()
            if not text:
                continue
            try:
                conf = float(parts[10])
            except Exception:
                conf = 0.0
            if conf < 0:
                conf = 0.0
            key = (int(parts[1]), int(parts[2]), int(parts[3]), int(parts[4]))
            left = int(parts[6]); top = int(parts[7]); w = int(parts[8]); h = int(parts[9])
            words.append({"text": text, "conf": conf, "left": left, "top": top, "right": left + w, "bottom": top + h, "key": key})
    return words

def group_lines(words):
    lines = {}
    order = []
    for w in words:
        key = w["key"]
        if key not in lines:
            lines[key] = []
            order.append(key)
        lines[key].append(w)
    result = []
    for key in order:
        ws = lines[key]
        text = " ".join([w["text"] for w in ws])
        conf = sum([w["conf"] for w in ws]) / max(1, len(ws))
        left = min([w["left"] for w in ws]); top = min([w["top"] for w in ws])
        right = max([w["right"] for w in ws]); bottom = max([w["bottom"] for w in ws])
        result.append({"text": text, "conf": conf, "bbox": [left, top, right, bottom], "words": ws})
    return result

def arabic_ratio(text):
    ar = 0; tot = 0
    for ch in text:
        o = ord(ch)
        if 0x600 <= o <= 0x6FF or 0x750 <= o <= 0x77F or 0xFB50 <= o <= 0xFDFF or 0xFE70 <= o <= 0xFEFF:
            ar += 1; tot += 1
        elif ch.isalpha():
            tot += 1
    if tot == 0:
        return 0.0
    return float(ar) / float(tot)

def build_rows(candidates, page_width):
    centers = []
    for line in candidates:
        for w in line["words"]:
            centers.append((w["left"] + w["right"]) / 2.0)
    centers.sort()
    tol = max(18.0, page_width * 0.03)
    clusters = []
    for c in centers:
        if clusters and abs(c - clusters[-1][-1]) <= tol:
            clusters[-1].append(c)
        else:
            clusters.append([c])
    col_centers = [sum(cl) / float(len(cl)) for cl in clusters]
    if len(col_centers) < 2 or len(col_centers) > 10:
        return None
    per_line_counts = [len(l["words"]) for l in candidates]
    avg_words = float(sum(per_line_counts)) / float(len(per_line_counts))
    if len(col_centers) == 2 and (avg_words > 4.0 or len(candidates) < 4):
        return None
    if avg_words > len(col_centers) * 1.6:
        return None
    rtl = arabic_ratio(" ".join([l["text"] for l in candidates])) > 0.4
    rows = []
    filled = 0
    for line in candidates:
        cells = [""] * len(col_centers)
        counted = set()
        for w in line["words"]:
            c = (w["left"] + w["right"]) / 2.0
            best = None; bestd = None
            for idx, cc in enumerate(col_centers):
                d = abs(c - cc)
                if bestd is None or d < bestd:
                    bestd = d; best = idx
            if best is not None and bestd is not None and bestd <= tol * 1.8:
                if cells[best]:
                    cells[best] = cells[best] + " " + w["text"]
                else:
                    cells[best] = w["text"]
                counted.add(best)
        if len(counted) >= 2:
            filled += 1
        rows.append(cells)
    ratio = float(filled) / float(len(candidates))
    min_ratio = 0.8 if len(col_centers) == 2 else 0.6
    if ratio < min_ratio:
        return None
    if rtl:
        rows = [list(reversed(r)) for r in rows]
    header = rows[0]
    body = rows[1:]
    if ratio >= 0.95 and len(col_centers) >= 3 and len(row_cells_consistent(candidates, len(col_centers))):
        conf = "HIGH"
    elif ratio >= 0.75:
        conf = "MEDIUM"
    else:
        conf = "LOW"
    bbox = [min([l["bbox"][0] for l in candidates]), min([l["bbox"][1] for l in candidates]), max([l["bbox"][2] for l in candidates]), max([l["bbox"][3] for l in candidates])]
    return {"header": header, "rows": body, "confidence": conf, "bbox": bbox}

def row_cells_consistent(candidates, ncols):
    counts = [len(l["words"]) for l in candidates]
    if not counts:
        return False
    spread = max(counts) - min(counts)
    return spread <= 2

def infer_tables(lines, page_width):
    tables = []
    i = 0
    n = len(lines)
    while i < n:
        candidates = []
        j = i
        while j < n:
            line = lines[j]
            if len(line["words"]) < 2:
                break
            candidates.append(line)
            j += 1
            if len(candidates) >= 40:
                break
        if len(candidates) >= 3:
            table = build_rows(candidates, page_width)
            if table is not None:
                tables.append(table)
                i = j
                continue
        i += 1
    return tables

def main():
    image_in = sys.argv[1]
    lang = sys.argv[2]
    out_path = sys.argv[3]
    prepared = load_image(image_in)
    work_image = prepared if prepared else image_in
    base = os.path.join(tempfile.gettempdir(), "aragon_ocr_out")
    run_tesseract(work_image, lang, base)
    words = parse_tsv(base + ".tsv")
    lines = group_lines(words)
    page_width = 1
    try:
        from PIL import Image
        with Image.open(work_image) as im:
            page_width = im.size[0]
    except Exception:
        for l in lines:
            page_width = max(page_width, l["bbox"][2])
    tables = infer_tables(lines, page_width)
    full_text = "\n".join([l["text"] for l in lines])
    confs = [l["conf"] for l in lines if l["conf"] > 0]
    avg = (sum(confs) / float(len(confs))) if confs else None
    payload = {
        "full_text": full_text,
        "lines": [{"text": l["text"], "conf": l["conf"], "bbox": l["bbox"]} for l in lines],
        "tables": tables,
        "avg_conf": avg,
        "engine": "e2b-tesseract"
    }
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False)

if __name__ == "__main__":
    main()
"""
