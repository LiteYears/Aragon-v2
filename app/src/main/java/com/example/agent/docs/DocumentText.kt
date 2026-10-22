package com.example.agent.docs

import java.io.File
import java.security.MessageDigest

object DocumentText {

  private val ARABIC_RANGES = listOf(
    0x0600 to 0x06FF,
    0x0750 to 0x077F,
    0x08A0 to 0x08FF,
    0xFB50 to 0xFDFF,
    0xFE70 to 0xFEFF
  )

  private val TASHKEEL_RANGES = listOf(
    0x064B to 0x065F,
    0x0670 to 0x0670,
    0x06D6 to 0x06ED
  )

  fun isArabicLetter(c: Char): Boolean {
    val code = c.code
    return ARABIC_RANGES.any { code in it.first..it.second }
  }

  fun isLatinLetter(c: Char): Boolean = (c in 'a'..'z') || (c in 'A'..'Z') || (c.code in 0x00C0..0x024F)

  fun isDigit(c: Char): Boolean =
    c in '0'..'9' || c.code in 0x0660..0x0669 || c.code in 0x06F0..0x06F9

  fun arabicRatio(text: String): Float {
    var arabic = 0
    var letters = 0
    for (c in text) {
      if (isArabicLetter(c)) { arabic++; letters++ }
      else if (isLatinLetter(c)) { letters++ }
    }
    if (letters == 0) return 0f
    return arabic.toFloat() / letters.toFloat()
  }

  fun detectDirection(text: String): TextDirection {
    var arabic = 0
    var latin = 0
    for (c in text) {
      if (isArabicLetter(c)) arabic++
      else if (isLatinLetter(c)) latin++
    }
    val letters = arabic + latin
    if (letters < 2) return TextDirection.UNKNOWN
    val ratio = arabic.toFloat() / letters.toFloat()
    return when {
      ratio >= 0.6f -> TextDirection.RTL
      ratio <= 0.1f -> TextDirection.LTR
      else -> TextDirection.MIXED
    }
  }

  fun detectLanguageHint(text: String): String? {
    var arabic = 0
    var latin = 0
    for (c in text) {
      if (isArabicLetter(c)) arabic++
      else if (isLatinLetter(c)) latin++
    }
    val letters = arabic + latin
    if (letters < 2) return null
    val ratio = arabic.toFloat() / letters.toFloat()
    return when {
      ratio >= 0.6f -> "ar"
      ratio <= 0.1f -> "en"
      else -> "mixed"
    }
  }

  fun stripTashkeelAndTatweel(text: String): String {
    val sb = StringBuilder(text.length)
    for (c in text) {
      val code = c.code
      if (code == 0x0640) continue
      if (TASHKEEL_RANGES.any { code in it.first..it.second }) continue
      sb.append(c)
    }
    return sb.toString()
  }

  private fun foldArabicIndicDigits(c: Char): Char {
    val code = c.code
    return when {
      code in 0x0660..0x0669 -> ('0' + (code - 0x0660))
      code in 0x06F0..0x06F9 -> ('0' + (code - 0x06F0))
      else -> c
    }
  }

  fun collapseWhitespace(text: String): String {
    val sb = StringBuilder(text.length)
    var lastWasSpace = false
    for (c in text) {
      val isSpace = c == ' ' || c == '\t' || c == '\u00A0' || c == '\u200B' || c == '\u200F' || c == '\u200E'
      if (isSpace) {
        if (!lastWasSpace && sb.isNotEmpty()) sb.append(' ')
        lastWasSpace = true
      } else {
        sb.append(c)
        lastWasSpace = false
      }
    }
    return sb.toString().trim()
  }

  fun normalizeForSearch(text: String): String {
    val stripped = stripTashkeelAndTatweel(text)
    val folded = StringBuilder(stripped.length)
    for (c in stripped) folded.append(foldArabicIndicDigits(c))
    return collapseWhitespace(folded.toString()).lowercase()
  }

  fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

  fun truncateAtBoundary(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val slice = text.substring(0, maxChars)
    val boundary = slice.lastIndexOfAny(charArrayOf(' ', '\n', '.', '،', '。'))
    return if (boundary > maxChars / 2) slice.substring(0, boundary).trimEnd() else slice.trimEnd()
  }

  fun sanitizeDisplayName(raw: String): String {
    val cleaned = raw.replace(Regex("[\\p{Cntrl}]"), " ").trim()
    return cleaned.ifBlank { "document" }.take(120)
  }

  fun sanitizeFileName(raw: String, maxLen: Int = 80): String {
    var cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
    cleaned = collapseWhitespace(cleaned).replace(' ', '_')
    if (cleaned.isBlank()) cleaned = "document"
    return if (cleaned.length > maxLen) cleaned.take(maxLen).trimEnd('_') else cleaned
  }

  fun sanitizeSheetName(raw: String): String {
    var cleaned = raw.replace(Regex("[\\\\/:*?\\[\\]]"), "_").trim()
    if (cleaned.isBlank()) cleaned = "Sheet"
    return cleaned.take(31)
  }

  fun uniqueFile(dir: File, baseName: String, extension: String): File {
    dir.mkdirs()
    val clean = sanitizeFileName(baseName)
    var candidate = File(dir, "$clean.$extension")
    var counter = 1
    while (candidate.exists()) {
      candidate = File(dir, "$clean($counter).$extension")
      counter++
    }
    return candidate
  }

  fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  fun buildFtsQuery(raw: String): String? {
    val tokens = raw.split(Regex("[^\\p{L}\\p{N}]+"))
      .map { it.trim() }
      .filter { it.isNotEmpty() && (it.length >= 2 || it[0].isDigit() || it[0].isLetter()) }
      .map { "\"" + it.replace("\"", "") + "\"" }
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" AND ")
  }

  fun buildFtsQueryOr(raw: String): String? {
    val tokens = raw.split(Regex("[^\\p{L}\\p{N}]+"))
      .map { it.trim() }
      .filter { it.isNotEmpty() && (it.length >= 2 || it[0].isDigit() || it[0].isLetter()) }
      .map { "\"" + it.replace("\"", "") + "\"" }
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" OR ")
  }

  fun directionLabel(direction: TextDirection): String = when (direction) {
    TextDirection.RTL -> "RTL"
    TextDirection.LTR -> "LTR"
    TextDirection.MIXED -> "MIXED"
    TextDirection.UNKNOWN -> "UNKNOWN"
  }
}

class ZipSafetyBudget {
  private var entries = 0
  private var totalUncompressed = 0L
  private var totalCompressed = 0L

  fun recordEntry(isDirectory: Boolean, uncompressedSize: Long) {
    if (isDirectory) return
    entries++
    totalUncompressed += uncompressedSize.coerceAtLeast(0)
    if (entries > DocumentLimits.MAX_ZIP_ENTRIES) {
      throw DocumentException("Archive rejected: exceeds ${DocumentLimits.MAX_ZIP_ENTRIES} entries")
    }
    if (totalUncompressed > DocumentLimits.MAX_UNCOMPRESSED_BYTES) {
      throw DocumentException("Archive rejected: uncompressed content exceeds size limit")
    }
  }

  fun recordCompressedSize(compressedSize: Long) {
    totalCompressed += compressedSize.coerceAtLeast(0)
    if (totalCompressed > 1024) {
      val ratio = totalUncompressed.toDouble() / totalCompressed.toDouble()
      if (ratio > DocumentLimits.MAX_COMPRESSION_RATIO && totalUncompressed > 20L * 1024 * 1024) {
        throw DocumentException("Archive rejected: suspicious compression ratio")
      }
    }
  }
}
