package com.example.agent.docs

import java.io.File
import java.util.zip.ZipFile

data class DetectionResult(
  val type: DocumentType,
  val mime: String,
  val detail: String
)

object DocumentDetector {

  private const val MIME_PDF = "application/pdf"
  private const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
  private const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
  private const val MIME_IMAGE = "image/*"

  fun sniff(file: File, mimeHint: String?, nameHint: String?): DetectionResult {
    val header = ByteArray(16)
    val read = file.inputStream().use { it.read(header) }
    if (read < 4) {
      throw DocumentException("File is too small or unreadable")
    }

    if (startsWith(header, "%PDF".toByteArray(Charsets.US_ASCII))) {
      return DetectionResult(DocumentType.PDF, MIME_PDF, "PDF signature")
    }

    if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
      (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte()) &&
      (header[3] == 0x04.toByte() || header[3] == 0x06.toByte() || header[3] == 0x08.toByte())
    ) {
      return sniffOoxml(file)
    }

    if (header[0] == 0xD0.toByte() && header[1] == 0xCF.toByte() &&
      header[2] == 0x11.toByte() && header[3] == 0xE0.toByte()
    ) {
      throw DocumentException("Legacy binary Office format (.doc/.xls) is not supported. Please re-save as .docx/.xlsx.")
    }

    if (isImageHeader(header, read)) {
      return DetectionResult(DocumentType.IMAGE, MIME_IMAGE, "Image signature")
    }

    val name = (nameHint ?: file.name).lowercase()
    val isKnownTextExt = name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".tsv") ||
      name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".json") || name.endsWith(".xml") ||
      name.endsWith(".kt") || name.endsWith(".kts") || name.endsWith(".java") || name.endsWith(".py") ||
      name.endsWith(".js") || name.endsWith(".ts") || name.endsWith(".html") || name.endsWith(".css") ||
      name.endsWith(".sh") || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".sql") ||
      name.endsWith(".env") || name.endsWith(".gradle") || name.endsWith(".properties") || name.endsWith(".c") ||
      name.endsWith(".cpp") || name.endsWith(".h") || name.endsWith(".rs") || name.endsWith(".go") ||
      name.endsWith(".log") || name.endsWith(".ini") || name.endsWith(".toml")

    if (isKnownTextExt || looksLikeText(file)) {
      val isCsv = name.endsWith(".csv") || (mimeHint?.contains("csv", ignoreCase = true) == true)
      if (isCsv) {
        return DetectionResult(DocumentType.CSV, "text/csv", "Delimited text")
      }
      return DetectionResult(DocumentType.TXT, mimeHint ?: "text/plain", "Text document")
    }

    // Accept generic files (archives, binaries, unknown formats) so upload never fails
    return DetectionResult(DocumentType.UNSUPPORTED, mimeHint ?: "application/octet-stream", "Workspace file")
  }

  private fun startsWith(source: ByteArray, prefix: ByteArray): Boolean {
    if (source.size < prefix.size) return false
    for (i in prefix.indices) {
      if (source[i] != prefix[i]) return false
    }
    return true
  }

  private fun isImageHeader(header: ByteArray, read: Int): Boolean {
    if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) return true
    if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) return true
    if (header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() && header[3] == 0x38.toByte()) return true
    if (header[0] == 0x42.toByte() && header[1] == 0x4D.toByte()) return true
    if (read >= 12) {
      val riff = String(header, 0, 4, Charsets.US_ASCII)
      val webp = String(header, 8, 4, Charsets.US_ASCII)
      if (riff == "RIFF" && webp == "WEBP") return true
      val ftyp = String(header, 4, 4, Charsets.US_ASCII)
      if (ftyp == "ftyp") {
        val brand = String(header, 8, 4, Charsets.US_ASCII)
        if (brand.startsWith("heic") || brand.startsWith("heif") || brand.startsWith("mif1") || brand.startsWith("avif")) return true
      }
    }
    return false
  }

  private fun looksLikeText(file: File): Boolean {
    val sample = ByteArray(4096)
    val read = file.inputStream().use { it.read(sample) }
    if (read <= 0) return false
    var nul = 0
    var control = 0
    for (i in 0 until read) {
      val b = sample[i].toInt() and 0xFF
      if (b == 0) nul++
      else if (b < 9 || (b in 14..31)) control++
    }
    return nul == 0 && control < read / 20
  }

  private fun sniffOoxml(file: File): DetectionResult {
    val zip = try {
      ZipFile(file)
    } catch (e: Exception) {
      throw DocumentException("Damaged Office archive: ${e.message ?: "cannot open"}")
    }
    zip.use { archive ->
      val names = archive.entries().asSequence().map { it.name }.toList()
      if (names.size > DocumentLimits.MAX_ZIP_ENTRIES) {
        throw DocumentException("Archive rejected: exceeds ${DocumentLimits.MAX_ZIP_ENTRIES} entries")
      }
      val contentTypes = archive.getEntry("[Content_Types].xml")
        ?: throw DocumentException("Not a valid Office document (missing content types)")
      val typesText = archive.getInputStream(contentTypes).use { it.readBytes().toString(Charsets.UTF_8) }
      if (typesText.contains("macroEnabled", ignoreCase = true)) {
        throw DocumentException("Macro-enabled documents are not supported for security reasons")
      }
      return when {
        names.any { it == "word/document.xml" } ->
          DetectionResult(DocumentType.DOCX, MIME_DOCX, "DOCX package")
        names.any { it == "xl/workbook.xml" } ->
          DetectionResult(DocumentType.XLSX, MIME_XLSX, "XLSX package")
        else -> throw DocumentException("Unrecognized Office package structure")
      }
    }
  }

  fun imageExtension(file: File): String? {
    if (!file.exists() || !file.isFile) return null
    return try {
      val header = ByteArray(16)
      val read = file.inputStream().use { it.read(header) }
      if (read >= 4) {
        if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) "jpg"
        else if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) "png"
        else if (header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() && header[3] == 0x38.toByte()) "gif"
        else if (header[0] == 0x42.toByte() && header[1] == 0x4D.toByte()) "bmp"
        else if (read >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP") "webp"
        else null
      } else null
    } catch (_: Exception) {
      null
    }
  }

  fun imageMimeType(file: File): String {
    val ext = file.extension.lowercase()
    return when (ext) {
      "png" -> "image/png"
      "webp" -> "image/webp"
      "gif" -> "image/gif"
      "bmp" -> "image/bmp"
      "heic", "heif" -> "image/heic"
      else -> {
        val detected = imageExtension(file)
        when (detected) {
          "png" -> "image/png"
          "webp" -> "image/webp"
          "gif" -> "image/gif"
          "bmp" -> "image/bmp"
          else -> "image/jpeg"
        }
      }
    }
  }

  fun extensionFor(type: DocumentType, file: File? = null): String = when (type) {
    DocumentType.PDF -> "pdf"
    DocumentType.DOCX -> "docx"
    DocumentType.XLSX -> "xlsx"
    DocumentType.CSV -> "csv"
    DocumentType.TXT -> "txt"
    DocumentType.IMAGE -> (file?.let { imageExtension(it) } ?: "jpg")
    DocumentType.UNSUPPORTED -> "bin"
  }
}
