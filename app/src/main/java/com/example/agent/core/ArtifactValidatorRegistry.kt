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
  val byteCount: Long = 0L,
  val sha256: String? = null,
  val isCorrupted: Boolean = false
)

/**
 * Registry of authoritative validators per file extension.
 * Prevents corrupted, placeholder, or pseudo-format files from being written or accepted.
 */
object ArtifactValidatorRegistry {

  fun computeSha256(file: File): String? {
    if (!file.exists() || !file.isFile || !file.canRead()) return null
    return try {
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(16384)
        var bytesRead = input.read(buffer)
        while (bytesRead != -1) {
          digest.update(buffer, 0, bytesRead)
          bytesRead = input.read(buffer)
        }
      }
      digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
      null
    }
  }

  fun validateContent(path: String, content: String): ArtifactValidationResult {
    val cleanPath = path.trim().lowercase()
    val ext = cleanPath.substringAfterLast('.', "")

    return when (ext) {
      "docx", "doc" -> validateDocxContent(cleanPath, content)
      "xlsx", "xls" -> validateXlsxContent(cleanPath, content)
      "json", "jsonl" -> validateJsonContent(cleanPath, content)
      "csv" -> validateCsvContent(cleanPath, content)
      "md", "markdown", "txt" -> validateTextContent(cleanPath, content)
      "py" -> validatePythonContent(cleanPath, content)
      else -> ArtifactValidationResult(isValid = true, byteCount = content.toByteArray(StandardCharsets.UTF_8).size.toLong())
    }
  }

  fun validateExistingFile(file: File): ArtifactValidationResult {
    if (!file.exists()) {
      return ArtifactValidationResult(isValid = false, errorMessage = "File does not exist: ${file.name}", isCorrupted = false)
    }
    if (file.isDirectory) {
      return ArtifactValidationResult(isValid = false, errorMessage = "Path '${file.name}' is a directory, not a deliverable file.", isCorrupted = false)
    }
    if (!file.canRead()) {
      return ArtifactValidationResult(isValid = false, errorMessage = "File '${file.name}' cannot be read (permission denied).", isCorrupted = false)
    }
    val length = file.length()
    if (length == 0L) {
      return ArtifactValidationResult(
        isValid = false,
        errorMessage = "File '${file.name}' is empty (0 bytes). Deliverables must contain non-empty synthesized content.",
        byteCount = 0L,
        isCorrupted = true
      )
    }

    val ext = file.extension.lowercase()
    val sha = computeSha256(file)

    return when (ext) {
      "docx", "doc" -> {
        if (length < 500L) {
          ArtifactValidationResult(
            isValid = false,
            errorMessage = "DOCX file is too small ($length bytes). A valid Word archive is at least 1-2 KB.",
            byteCount = length,
            sha256 = sha,
            isCorrupted = true
          )
        } else {
          val zipValid = try {
            java.util.zip.ZipFile(file).use { zip ->
              val hasTypes = zip.getEntry("[Content_Types].xml") != null
              val hasDoc = zip.getEntry("word/document.xml") != null
              hasTypes && hasDoc
            }
          } catch (_: Exception) {
            false
          }
          if (!zipValid) {
            ArtifactValidationResult(
              isValid = false,
              errorMessage = "DOCX file is corrupt: missing PK\\x03\\x04 zip archive header or required OpenXML structure ([Content_Types].xml, word/document.xml).",
              byteCount = length,
              sha256 = sha,
              isCorrupted = true
            )
          } else {
            ArtifactValidationResult(isValid = true, detectedFormat = "Microsoft Word (OpenXML)", byteCount = length, sha256 = sha)
          }
        }
      }
      "xlsx", "xls" -> {
        if (length < 300L) {
          ArtifactValidationResult(
            isValid = false,
            errorMessage = "XLSX file is too small ($length bytes). A valid Excel archive is at least 1-2 KB.",
            byteCount = length,
            sha256 = sha,
            isCorrupted = true
          )
        } else {
          val zipValid = try {
            java.util.zip.ZipFile(file).use { zip ->
              val hasTypes = zip.getEntry("[Content_Types].xml") != null
              val hasSheet = zip.getEntry("xl/workbook.xml") != null || zip.getEntry("xl/worksheets/sheet1.xml") != null
              hasTypes && hasSheet
            }
          } catch (_: Exception) {
            false
          }
          if (!zipValid) {
            ArtifactValidationResult(
              isValid = false,
              errorMessage = "XLSX file is corrupt: missing PK\\x03\\x04 zip archive header or workbook structure ([Content_Types].xml, xl/workbook.xml).",
              byteCount = length,
              sha256 = sha,
              isCorrupted = true
            )
          } else {
            ArtifactValidationResult(isValid = true, detectedFormat = "Microsoft Excel (OpenXML)", byteCount = length, sha256 = sha)
          }
        }
      }
      "json" -> {
        try {
          val text = file.readText(StandardCharsets.UTF_8).trim()
          if (text.isBlank()) {
            ArtifactValidationResult(isValid = false, errorMessage = "JSON file contains only whitespace.", byteCount = length, sha256 = sha, isCorrupted = true)
          } else {
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
            ArtifactValidationResult(isValid = true, detectedFormat = "JSON Document", byteCount = length, sha256 = sha)
          }
        } catch (e: Exception) {
          ArtifactValidationResult(isValid = false, errorMessage = "Corrupted JSON syntax: ${e.message}", byteCount = length, sha256 = sha, isCorrupted = true)
        }
      }
      "jsonl" -> {
        try {
          val lines = file.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
          if (lines.isEmpty()) {
            ArtifactValidationResult(isValid = false, errorMessage = "JSONL file is empty or contains only whitespace.", byteCount = length, sha256 = sha, isCorrupted = true)
          } else {
            var badCount = 0
            for (line in lines) {
              try { JSONObject(line.trim()) } catch (_: Exception) { badCount++ }
            }
            if (badCount > 0) {
              ArtifactValidationResult(isValid = false, errorMessage = "Corrupted JSONL syntax: $badCount invalid JSON line(s).", byteCount = length, sha256 = sha, isCorrupted = true)
            } else {
              ArtifactValidationResult(isValid = true, detectedFormat = "JSON Lines (${lines.size} records)", byteCount = length, sha256 = sha)
            }
          }
        } catch (e: Exception) {
          ArtifactValidationResult(isValid = false, errorMessage = "Error reading JSONL: ${e.message}", byteCount = length, sha256 = sha, isCorrupted = true)
        }
      }
      "pdf" -> {
        if (length < 100L) {
          ArtifactValidationResult(isValid = false, errorMessage = "PDF file too small ($length bytes).", byteCount = length, sha256 = sha, isCorrupted = true)
        } else {
          val isPdf = try {
            file.inputStream().use {
              val buf = ByteArray(4)
              it.read(buf) == 4 && buf[0] == '%'.code.toByte() && buf[1] == 'P'.code.toByte() && buf[2] == 'D'.code.toByte() && buf[3] == 'F'.code.toByte()
            }
          } catch (_: Exception) { false }

          val hasEof = try {
            val seekPos = (file.length() - 1024L).coerceAtLeast(0L)
            java.io.RandomAccessFile(file, "r").use { raf ->
              raf.seek(seekPos)
              val tailBytes = ByteArray((file.length() - seekPos).toInt())
              raf.readFully(tailBytes)
              val tailStr = String(tailBytes, StandardCharsets.ISO_8859_1)
              tailStr.contains("%%EOF")
            }
          } catch (_: Exception) { false }

          if (!isPdf) {
            ArtifactValidationResult(isValid = false, errorMessage = "Missing %PDF magic bytes signature.", byteCount = length, sha256 = sha, isCorrupted = true)
          } else if (!hasEof) {
            ArtifactValidationResult(isValid = false, errorMessage = "Truncated or partial PDF deliverable: missing %%EOF trailer signature.", byteCount = length, sha256 = sha, isCorrupted = true)
          } else {
            ArtifactValidationResult(isValid = true, detectedFormat = "PDF Document", byteCount = length, sha256 = sha)
          }
        }
      }
      "csv" -> {
        try {
          val lines = file.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
          if (lines.size < 2) {
            ArtifactValidationResult(isValid = false, errorMessage = "CSV file requires at least a header row and one data row.", byteCount = length, sha256 = sha, isCorrupted = true)
          } else {
            ArtifactValidationResult(isValid = true, detectedFormat = "CSV Table (${lines.size} rows)", byteCount = length, sha256 = sha)
          }
        } catch (e: Exception) {
          ArtifactValidationResult(isValid = false, errorMessage = "CSV reading error: ${e.message}", byteCount = length, sha256 = sha, isCorrupted = true)
        }
      }
      "md", "markdown", "txt" -> {
        try {
          val text = file.readText(StandardCharsets.UTF_8).trim()
          if (text.isBlank()) {
            ArtifactValidationResult(isValid = false, errorMessage = "Text/Markdown document contains only whitespace.", byteCount = length, sha256 = sha, isCorrupted = true)
          } else {
            ArtifactValidationResult(isValid = true, detectedFormat = "Markdown/Text Document (${text.length} chars)", byteCount = length, sha256 = sha)
          }
        } catch (e: Exception) {
          ArtifactValidationResult(isValid = false, errorMessage = "Encoding/read error: ${e.message}", byteCount = length, sha256 = sha, isCorrupted = true)
        }
      }
      else -> ArtifactValidationResult(isValid = true, detectedFormat = "Deliverable Binary (${ext.uppercase()})", byteCount = length, sha256 = sha)
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
    val hasTable = lines.any { it.contains("|") }
    if (lines.size <= 6 && avgLineLen < 35.0 && !hasTable) {
      return ArtifactValidationResult(
        isValid = false,
        errorMessage = "DOCX validation rejected: Detected placeholder section headers without synthesized body paragraphs. Synthesize actual research findings into the document."
      )
    }
    return ArtifactValidationResult(isValid = true, detectedFormat = "DOCX OpenXML Markdown/Text Source")
  }

  private fun validateXlsxContent(path: String, content: String): ArtifactValidationResult {
    val trimmed = content.trim()
    if (trimmed.length < 15) {
      return ArtifactValidationResult(
        isValid = false,
        errorMessage = "XLSX content validation failed: text length (${trimmed.length} chars) is too short to be a valid spreadsheet deliverable."
      )
    }
    return ArtifactValidationResult(isValid = true, detectedFormat = "XLSX OpenXML Spreadsheet Source")
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
