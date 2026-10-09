package com.example.agent.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Result of artifact validation before writing or finalizing a deliverable.
 */
data class ArtifactValidationResult(
  val isValid: Boolean,
  val errorMessage: String? = null,
  val detectedFormat: String? = null,
  val byteCount: Long = 0L
)

/**
 * Registry of authoritative validators per file extension.
 * Prevents corrupted, placeholder, or pseudo-format files from being written or accepted.
 */
object ArtifactValidatorRegistry {

  fun validateContent(path: String, content: String): ArtifactValidationResult {
    val cleanPath = path.trim().lowercase()
    val ext = cleanPath.substringAfterLast('.', "")

    return when (ext) {
      "docx", "doc" -> validateDocxContent(cleanPath, content)
      "json", "jsonl" -> validateJsonContent(cleanPath, content)
      "csv" -> validateCsvContent(cleanPath, content)
      "md", "markdown", "txt" -> validateTextContent(cleanPath, content)
      "py" -> validatePythonContent(cleanPath, content)
      else -> ArtifactValidationResult(isValid = true, byteCount = content.toByteArray(StandardCharsets.UTF_8).size.toLong())
    }
  }

  fun validateExistingFile(file: File): ArtifactValidationResult {
    if (!file.exists()) {
      return ArtifactValidationResult(isValid = false, errorMessage = "File does not exist: ${file.name}")
    }
    val ext = file.extension.lowercase()
    val length = file.length()

    return when (ext) {
      "docx", "doc" -> {
        if (length < 500L) {
          ArtifactValidationResult(
            isValid = false,
            errorMessage = "DOCX file is too small ($length bytes). A valid Word archive is at least 1-2 KB."
          )
        } else if (!DocxBuilder.isZipFile(file)) {
          ArtifactValidationResult(
            isValid = false,
            errorMessage = "DOCX file is corrupt: missing PK\\x03\\x04 zip archive header signature."
          )
        } else {
          ArtifactValidationResult(isValid = true, detectedFormat = "Microsoft Word (OpenXML)", byteCount = length)
        }
      }
      "json" -> {
        try {
          val text = file.readText(StandardCharsets.UTF_8).trim()
          if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
          ArtifactValidationResult(isValid = true, detectedFormat = "JSON", byteCount = length)
        } catch (e: Exception) {
          ArtifactValidationResult(isValid = false, errorMessage = "Corrupted JSON syntax: ${e.message}")
        }
      }
      "pdf" -> {
        if (length < 100L) {
          ArtifactValidationResult(isValid = false, errorMessage = "PDF file too small ($length bytes).")
        } else {
          val isPdf = try {
            file.inputStream().use {
              val buf = ByteArray(4)
              it.read(buf) == 4 && buf[0] == '%'.code.toByte() && buf[1] == 'P'.code.toByte() && buf[2] == 'D'.code.toByte() && buf[3] == 'F'.code.toByte()
            }
          } catch (_: Exception) { false }
          if (!isPdf) {
            ArtifactValidationResult(isValid = false, errorMessage = "Missing %PDF magic bytes.")
          } else {
            ArtifactValidationResult(isValid = true, detectedFormat = "PDF", byteCount = length)
          }
        }
      }
      else -> ArtifactValidationResult(isValid = true, byteCount = length)
    }
  }

  private fun validateDocxContent(path: String, content: String): ArtifactValidationResult {
    val trimmed = content.trim()
    if (trimmed.length < 80) {
      return ArtifactValidationResult(
        isValid = false,
        errorMessage = "DOCX content validation failed: text length (${trimmed.length} chars) is too short to be a valid document deliverable. Do not write bare section headers without body."
      )
    }
    // Check if it's purely emojis/headers without content
    val lines = trimmed.lines().filter { it.isNotBlank() }
    val avgLineLen = if (lines.isNotEmpty()) lines.map { it.length }.average() else 0.0
    if (lines.size <= 6 && avgLineLen < 35.0) {
      return ArtifactValidationResult(
        isValid = false,
        errorMessage = "DOCX validation rejected: Detected placeholder section headers without synthesized body paragraphs. Synthesize actual research findings into the document."
      )
    }
    return ArtifactValidationResult(isValid = true, detectedFormat = "DOCX OpenXML Markdown/Text Source")
  }

  private fun validateJsonContent(path: String, content: String): ArtifactValidationResult {
    val trimmed = content.trim()
    if (path.endsWith(".jsonl")) {
      val badLines = trimmed.lines().filter { it.isNotBlank() }.filter { line ->
        try { JSONObject(line.trim()); false } catch (_: Exception) { true }
      }
      if (badLines.isNotEmpty()) {
        return ArtifactValidationResult(
          isValid = false,
          errorMessage = "JSONL validation failed: ${badLines.size} malformed JSON line(s)."
        )
      }
      return ArtifactValidationResult(isValid = true, detectedFormat = "JSON Lines")
    }

    return try {
      if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
      ArtifactValidationResult(isValid = true, detectedFormat = "JSON Document")
    } catch (e: Exception) {
      ArtifactValidationResult(
        isValid = false,
        errorMessage = "JSON syntax validation error: ${e.message}"
      )
    }
  }

  private fun validateCsvContent(path: String, content: String): ArtifactValidationResult {
    val lines = content.trim().lines().filter { it.isNotBlank() }
    if (lines.size < 2) {
      return ArtifactValidationResult(
        isValid = false,
        errorMessage = "CSV validation failed: requires at least header row and one data row."
      )
    }
    return ArtifactValidationResult(isValid = true, detectedFormat = "CSV Table")
  }

  private fun validateTextContent(path: String, content: String): ArtifactValidationResult {
    if (content.isBlank()) {
      return ArtifactValidationResult(
        isValid = false,
        errorMessage = "File content is blank (0 bytes)."
      )
    }
    return ArtifactValidationResult(isValid = true, detectedFormat = "Text/Markdown")
  }

  private fun validatePythonContent(path: String, content: String): ArtifactValidationResult {
    if (content.isBlank()) {
      return ArtifactValidationResult(isValid = false, errorMessage = "Python script is blank.")
    }
    return ArtifactValidationResult(isValid = true, detectedFormat = "Python Script")
  }
}
