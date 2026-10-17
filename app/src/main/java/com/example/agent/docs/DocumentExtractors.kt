package com.example.agent.docs

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.time.LocalDate
import java.util.zip.ZipFile

internal fun XmlPullParser.localName(): String {
  val raw = this.name ?: return ""
  return raw.substringAfterLast(':')
}

internal fun XmlPullParser.newInstance(): XmlPullParser {
  val factory = XmlPullParserFactory.newInstance()
  factory.isNamespaceAware = false
  return factory.newPullParser()
}

internal fun XmlPullParser.attr(name: String): String? = this.getAttributeValue(null, name)

internal fun XmlPullParser.findIdAttr(): String? {
  for (i in 0 until this.attributeCount) {
    val attrName = this.getAttributeName(i) ?: continue
    if (attrName == name || attrName.endsWith(":id")) {
      return this.getAttributeValue(i)
    }
  }
  return null
}

internal fun ZipFile.readTextEntry(path: String, maxBytes: Long = 40L * 1024 * 1024): String? {
  val entry = this.getEntry(path) ?: return null
  if (entry.size > maxBytes) {
    throw DocumentException("Archive entry '$path' exceeds the size limit")
  }
  return this.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
}

internal fun splitTableIfLarge(table: ExtractedTable): List<ExtractedTable> {
  if (table.rows.size <= DocumentLimits.MAX_TABLE_ROWS_PER_BLOCK) return listOf(table)
  val parts = mutableListOf<ExtractedTable>()
  var index = 0
  var chunkIndex = 1
  while (index < table.rows.size) {
    val end = minOf(index + DocumentLimits.MAX_TABLE_ROWS_PER_BLOCK, table.rows.size)
    val slice = table.rows.subList(index, end).toList()
    val location = table.sourceLocation?.let { "$it (rows ${index + 1}-$end)" } ?: "rows ${index + 1}-$end"
    parts.add(table.copy(rows = slice, sourceLocation = location))
    index = end
    chunkIndex++
  }
  return parts
}

object DocxTextExtractor {

  private data class NumberingInfo(val ordered: Boolean)

  fun extract(file: File, docId: String, displayName: String): DocumentStructure {
    val blocks = mutableListOf<DocumentBlock>()
    val warnings = mutableListOf<String>()
    val engines = mutableListOf("ooxml-parser")
    var mergedCellCount = 0

    ZipFile(file).use { archive ->
      val budget = ZipSafetyBudget()
      for (entry in archive.entries()) {
        budget.recordEntry(entry.isDirectory, entry.size)
        budget.recordCompressedSize(entry.compressedSize)
      }
      val numbering = parseNumbering(archive)
      val xml = archive.readTextEntry("word/document.xml")
        ?: throw DocumentException("DOCX is missing word/document.xml")

      val parser = XmlPullParserFactory.newInstance().newPullParser()
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
      parser.setInput(StringReader(xml))

      var inTable = false
      var tableDepth = 0
      val tableRows = mutableListOf<List<String>>()
      var currentRow: MutableList<String>? = null
      var cellBuffer: StringBuilder? = null
      val paragraphBuffer = StringBuilder()
      var insideText = false
      var insideSkippedField = 0
      var currentStyle: String? = null
      var currentNumId: Int? = null

      fun emitParagraph() {
        val text = DocumentText.collapseWhitespace(paragraphBuffer.toString())
        paragraphBuffer.setLength(0)
        if (text.isBlank()) {
          currentStyle = null
          currentNumId = null
          return
        }
        if (inTable) {
          cellBuffer?.append(text)?.append(' ')
        } else {
          val style = currentStyle?.lowercase() ?: ""
          val headingLevel = when {
            style.contains("title") -> 1
            style.contains("heading") -> {
              val digit = style.filter { it.isDigit() }.firstOrNull()?.digitToInt() ?: 1
              digit.coerceIn(1, 6)
            }
            else -> 0
          }
          val kind = when {
            headingLevel > 0 -> BlockKind.HEADING
            currentNumId != null -> BlockKind.LIST_ITEM
            else -> BlockKind.PARAGRAPH
          }
          val ordered = currentNumId?.let { numbering[it]?.ordered } ?: false
          blocks.add(
            DocumentBlock(
              kind = kind,
              text = text,
              headingLevel = headingLevel,
              orderedHint = ordered,
              direction = DocumentText.detectDirection(text),
              language = DocumentText.detectLanguageHint(text),
              sourceEngine = "docx"
            )
          )
        }
        currentStyle = null
        currentNumId = null
      }

      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
          XmlPullParser.START_TAG -> {
            val name = parser.localName()
            when (name) {
              "tbl" -> {
                if (tableDepth == 0) {
                  inTable = true
                  tableRows.clear()
                }
                tableDepth++
              }
              "tr" -> if (inTable) currentRow = mutableListOf()
              "tc" -> if (inTable) cellBuffer = StringBuilder()
              "p" -> {
                paragraphBuffer.setLength(0)
                currentStyle = null
                currentNumId = null
              }
              "pStyle" -> if (!inTable) currentStyle = parser.attr("val")
              "numId" -> if (!inTable) currentNumId = parser.attr("val")?.toIntOrNull()
              "t" -> {
                if (insideSkippedField == 0) insideText = true
              }
              "instrText", "delText" -> insideSkippedField++
              "br", "cr" -> paragraphBuffer.append('\n')
              "tab" -> paragraphBuffer.append(' ')
              "gridSpan", "vMerge" -> mergedCellCount++
            }
          }
          XmlPullParser.TEXT -> {
            if (insideText && insideSkippedField == 0) {
              paragraphBuffer.append(parser.text ?: "")
            }
          }
          XmlPullParser.END_TAG -> {
            val name = parser.localName()
            when (name) {
              "t" -> insideText = false
              "instrText", "delText" -> if (insideSkippedField > 0) insideSkippedField--
              "p" -> emitParagraph()
              "tc" -> {
                if (inTable) {
                  currentRow?.add(DocumentText.collapseWhitespace(cellBuffer?.toString() ?: ""))
                  cellBuffer = null
                }
              }
              "tr" -> {
                if (inTable) {
                  currentRow?.let { if (it.isNotEmpty()) tableRows.add(it.toList()) }
                  currentRow = null
                }
              }
              "tbl" -> {
                if (tableDepth > 0) tableDepth--
                if (tableDepth == 0 && inTable) {
                  inTable = false
                  if (tableRows.isNotEmpty()) {
                    val header = tableRows.first().map(::normalizeCell)
                    val body = tableRows.drop(1).map { row -> row.map(::normalizeCell) }
                    val joined = (header + body.flatten()).joinToString(" ")
                    val table = ExtractedTable(
                      header = header,
                      rows = body,
                      direction = DocumentText.detectDirection(joined),
                      confidence = "HIGH",
                      sourceLocation = "document table"
                    )
                    splitTableIfLarge(table).forEach { part ->
                      blocks.add(
                        DocumentBlock(
                          kind = BlockKind.TABLE,
                          text = tableRowSummary(part),
                          table = part,
                          direction = part.direction,
                          sourceEngine = "docx"
                        )
                      )
                    }
                  }
                  tableRows.clear()
                }
              }
            }
          }
        }
        event = parser.next()
      }
    }

    if (mergedCellCount > 0) {
      warnings.add("Document contains $mergedCellCount merged cell marker(s); flattened during extraction")
    }
    val joined = blocks.joinToString("\n") { it.text }
    return DocumentStructure(
      docId = docId,
      displayName = displayName,
      type = DocumentType.DOCX,
      blocks = blocks,
      pageCount = 0,
      sheetCount = 0,
      primaryLanguage = DocumentText.detectLanguageHint(joined),
      direction = DocumentText.detectDirection(joined),
      engines = engines,
      warnings = warnings,
      textLength = blocks.sumOf { it.text.length }
    )
  }

  private fun normalizeCell(raw: String): String = DocumentText.collapseWhitespace(raw)

  private fun parseNumbering(archive: ZipFile): Map<Int, NumberingInfo> {
    val xml = try {
      archive.readTextEntry("word/numbering.xml", maxBytes = 4L * 1024 * 1024)
    } catch (_: Exception) {
      null
    } ?: return emptyMap()

    val abstractFormats = mutableMapOf<Int, Boolean>()
    val numToAbstract = mutableMapOf<Int, Int>()
    try {
      val parser = XmlPullParserFactory.newInstance().newPullParser()
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
      parser.setInput(StringReader(xml))
      var currentAbstract: Int? = null
      var currentNum: Int? = null
      var levelZeroFormat: String? = null
      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
          XmlPullParser.START_TAG -> when (parser.localName()) {
            "abstractNum" -> {
              currentAbstract = parser.attr("abstractNumId")?.toIntOrNull()
              levelZeroFormat = null
            }
            "num" -> currentNum = parser.attr("numId")?.toIntOrNull()
            "abstractNumId" -> {
              val ref = parser.attr("val")?.toIntOrNull()
              if (currentNum != null && ref != null) numToAbstract[currentNum!!] = ref
            }
            "lvl" -> {
              val ilvl = parser.attr("ilvl")?.toIntOrNull() ?: 0
              if (ilvl != 0) levelZeroFormat = null
            }
            "numFmt" -> {
              if (levelZeroFormat == null) {
                levelZeroFormat = parser.attr("val")
                val id = currentAbstract
                if (id != null) {
                  val ordered = levelZeroFormat != "bullet" && levelZeroFormat != "none"
                  abstractFormats[id] = ordered
                }
              }
            }
          }
          XmlPullParser.END_TAG -> when (parser.localName()) {
            "abstractNum" -> currentAbstract = null
            "num" -> currentNum = null
            "lvl" -> levelZeroFormat = null
          }
        }
        event = parser.next()
      }
    } catch (_: Exception) {
      return emptyMap()
    }

    val result = mutableMapOf<Int, NumberingInfo>()
    for ((numId, abstractId) in numToAbstract) {
      val ordered = abstractFormats[abstractId] ?: false
      result[numId] = NumberingInfo(ordered)
    }
    return result
  }
}

internal fun tableRowSummary(table: ExtractedTable): String {
  val lines = mutableListOf<String>()
  lines.add(table.header.joinToString(" | "))
  for (row in table.rows.take(60)) {
    lines.add(row.joinToString(" | "))
  }
  return lines.joinToString("\n")
}

object XlsxTextExtractor {

  fun extract(file: File, docId: String, displayName: String): DocumentStructure {
    val warnings = mutableListOf<String>()
    val sheets = mutableListOf<ExtractedSheet>()

    ZipFile(file).use { archive ->
      val budget = ZipSafetyBudget()
      for (entry in archive.entries()) {
        budget.recordEntry(entry.isDirectory, entry.size)
        budget.recordCompressedSize(entry.compressedSize)
      }

      val sharedStrings = parseSharedStrings(archive)
      val styles = parseStyles(archive)
      val rels = parseRels(archive)
      val workbookSheets = parseWorkbook(archive)

      var sheetCount = 0
      for ((name, rid) in workbookSheets) {
        if (sheetCount >= DocumentLimits.MAX_XLSX_SHEETS) {
          warnings.add("Only the first ${DocumentLimits.MAX_XLSX_SHEETS} sheets were processed")
          break
        }
        val target = rels[rid]
        if (target == null) {
          warnings.add("Sheet '$name' is missing its package relationship and was skipped")
          continue
        }
        val path = normalizeSheetPath(target)
        val xml = try {
          archive.readTextEntry(path)
        } catch (_: Exception) {
          null
        }
        if (xml == null) {
          warnings.add("Sheet '$name' could not be read from the package")
          continue
        }
        val sheetWarnings = mutableListOf<String>()
        val parsed = parseSheetXml(xml, sharedStrings, styles, sheetWarnings)
        warnings.addAll(sheetWarnings.map { "$name: $it" })
        sheets.add(
          ExtractedSheet(
            name = name,
            rows = parsed.rows,
            columnWidths = parsed.columnWidths,
            rtl = parsed.rtl,
            mergedRanges = parsed.merges
          )
        )
        sheetCount++
      }
    }

    val blocks = sheets.map { sheet ->
      val dataRows = sheet.rows.size
      val cols = sheet.rows.maxOfOrNull { it.size } ?: 0
      val headerPreview = sheet.rows.firstOrNull()?.joinToString(" | ") { it.text }?.take(300) ?: ""
      DocumentBlock(
        kind = BlockKind.SHEET,
        text = "Sheet '${sheet.name}': $dataRows rows x $cols columns.\n$headerPreview",
        sheetName = sheet.name,
        direction = if (sheet.rtl) TextDirection.RTL else TextDirection.LTR,
        sourceEngine = "xlsx"
      )
    }

    val joined = sheets.joinToString("\n") { s -> s.rows.joinToString("\n") { r -> r.joinToString(" ") { it.text } } }
    return DocumentStructure(
      docId = docId,
      displayName = displayName,
      type = DocumentType.XLSX,
      blocks = blocks,
      sheets = sheets,
      pageCount = 0,
      sheetCount = sheets.size,
      primaryLanguage = DocumentText.detectLanguageHint(joined),
      direction = DocumentText.detectDirection(joined),
      engines = listOf("ooxml-parser"),
      warnings = warnings,
      textLength = joined.length
    )
  }

  private data class ParsedSheet(
    val rows: List<List<CellValue>>,
    val columnWidths: List<Double>,
    val rtl: Boolean,
    val merges: List<String>
  )

  private data class StyleInfo(
    val cellXfs: List<Int>,
    val customFormats: Map<Int, String>
  ) {
    fun isDateStyle(index: Int): Boolean {
      val numFmtId = cellXfs.getOrNull(index) ?: 0
      if (numFmtId in 14..22 || numFmtId in 45..47) return true
      val code = customFormats[numFmtId] ?: return false
      val lower = code.lowercase()
      return lower.contains("yy") || lower.contains("dd") || lower.contains("hh") ||
        lower.contains(":mm") || lower.contains("ss")
    }
  }

  private fun parseSharedStrings(archive: ZipFile): List<String> {
    val xml = try {
      archive.readTextEntry("xl/sharedStrings.xml")
    } catch (_: Exception) {
      null
    } ?: return emptyList()
    val strings = mutableListOf<String>()
    try {
      val parser = XmlPullParserFactory.newInstance().newPullParser()
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
      parser.setInput(StringReader(xml))
      var inSi = false
      var inPhonetic = 0
      var insideT = false
      val buffer = StringBuilder()
      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
          XmlPullParser.START_TAG -> when (parser.localName()) {
            "si" -> { inSi = true; buffer.setLength(0) }
            "rPh" -> inPhonetic++
            "t" -> insideT = true
          }
          XmlPullParser.TEXT -> if (inSi && insideT && inPhonetic == 0) buffer.append(parser.text ?: "")
          XmlPullParser.END_TAG -> when (parser.localName()) {
            "si" -> { strings.add(buffer.toString()); inSi = false }
            "rPh" -> if (inPhonetic > 0) inPhonetic--
            "t" -> insideT = false
          }
        }
        event = parser.next()
      }
    } catch (_: Exception) {
      return strings
    }
    return strings
  }

  private fun parseStyles(archive: ZipFile): StyleInfo {
    val xml = try {
      archive.readTextEntry("xl/styles.xml")
    } catch (_: Exception) {
      null
    } ?: return StyleInfo(emptyList(), emptyMap())
    val custom = mutableMapOf<Int, String>()
    val xfs = mutableListOf<Int>()
    try {
      val parser = XmlPullParserFactory.newInstance().newPullParser()
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
      parser.setInput(StringReader(xml))
      var inCellXfs = false
      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
          XmlPullParser.START_TAG -> when (parser.localName()) {
            "numFmt" -> {
              val id = parser.attr("numFmtId")?.toIntOrNull()
              val code = parser.attr("formatCode")
              if (id != null && code != null) custom[id] = code
            }
            "cellXfs" -> inCellXfs = true
            "xf" -> if (inCellXfs) {
              xfs.add(parser.attr("numFmtId")?.toIntOrNull() ?: 0)
            }
          }
          XmlPullParser.END_TAG -> if (parser.localName() == "cellXfs") inCellXfs = false
        }
        event = parser.next()
      }
    } catch (_: Exception) {
    }
    return StyleInfo(xfs, custom)
  }

  private fun parseRels(archive: ZipFile): Map<String, String> {
    val xml = try {
      archive.readTextEntry("xl/_rels/workbook.xml.rels", maxBytes = 4L * 1024 * 1024)
    } catch (_: Exception) {
      null
    } ?: return emptyMap()
    val rels = mutableMapOf<String, String>()
    try {
      val parser = XmlPullParserFactory.newInstance().newPullParser()
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
      parser.setInput(StringReader(xml))
      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.localName() == "Relationship") {
          val id = parser.attr("Id")
          val target = parser.attr("Target")
          val mode = parser.attr("TargetMode")
          if (id != null && target != null && mode != "External") {
            rels[id] = target
          }
        }
        event = parser.next()
      }
    } catch (_: Exception) {
    }
    return rels
  }

  private fun parseWorkbook(archive: ZipFile): List<Pair<String, String>> {
    val xml = archive.readTextEntry("xl/workbook.xml", maxBytes = 8L * 1024 * 1024)
      ?: throw DocumentException("XLSX is missing xl/workbook.xml")
    val sheets = mutableListOf<Pair<String, String>>()
    try {
      val parser = XmlPullParserFactory.newInstance().newPullParser()
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
      parser.setInput(StringReader(xml))
      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.localName() == "sheet") {
          val name = parser.attr("name") ?: continue
          val rid = parser.findIdAttr()
          if (rid != null) sheets.add(name to rid)
        }
        event = parser.next()
      }
    } catch (e: DocumentException) {
      throw e
    } catch (_: Exception) {
    }
    if (sheets.isEmpty()) {
      throw DocumentException("XLSX workbook lists no readable sheets")
    }
    return sheets
  }

  private fun normalizeSheetPath(target: String): String {
    val cleaned = target.removePrefix("/")
    return if (cleaned.startsWith("xl/")) cleaned else "xl/$cleaned"
  }

  private fun parseSheetXml(
    xml: String,
    sharedStrings: List<String>,
    styles: StyleInfo,
    warnings: MutableList<String>
  ): ParsedSheet {
    val rows = mutableListOf<MutableList<CellValue>>()
    val merges = mutableListOf<String>()
    val widths = mutableListOf<Double>()
    var truncatedCells = false
    var compressedGaps = false
    var totalCells = 0
    var arabicChars = 0
    var latinChars = 0

    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(StringReader(xml))

    var currentRow: MutableList<CellValue>? = null
    var currentCellType: String? = null
    var currentStyleIndex = 0
    val cellValue = StringBuilder()
    var cellFormula: String? = null
    var insideValue = false
    var insideFormula = false
    var lastColumnIndex = 0

    fun ensureRow(rowNumber: Int): MutableList<CellValue> {
      while (rows.size < rowNumber) rows.add(mutableListOf())
      return rows[rowNumber - 1]
    }

    fun finalizeCell() {
      val row = currentRow ?: return
      if (lastColumnIndex >= 128) {
        truncatedCells = true
        return
      }
      while (row.size <= lastColumnIndex) row.add(CellValue("", CellKind.BLANK))
      val resolved = resolveCell(cellValue.toString(), currentCellType, currentStyleIndex, styles, cellFormula, sharedStrings)
      for (c in resolved.text) {
        if (DocumentText.isArabicLetter(c)) arabicChars++
        else if (DocumentText.isLatinLetter(c)) latinChars++
      }
      row[lastColumnIndex] = resolved
    }

    var event = parser.eventType
    parseLoop@ while (event != XmlPullParser.END_DOCUMENT) {
      when (event) {
        XmlPullParser.START_TAG -> when (parser.localName()) {
          "row" -> {
            val declared = parser.attr("r")?.toIntOrNull() ?: (rows.size + 1)
            val effective = if (declared > rows.size + 500) {
              compressedGaps = true
              rows.size + 1
            } else declared
            currentRow = ensureRow(effective.coerceAtLeast(1))
          }
          "c" -> {
            currentCellType = parser.attr("t")
            currentStyleIndex = parser.attr("s")?.toIntOrNull() ?: 0
            val ref = parser.attr("r")
            lastColumnIndex = if (ref != null) columnIndexFromRef(ref) else (currentRow?.size ?: 0)
            cellValue.setLength(0)
            cellFormula = null
            totalCells++
            if (totalCells > DocumentLimits.MAX_XLSX_CELLS_PER_SHEET) {
              truncatedCells = true
              break@parseLoop
            }
          }
          "v", "t" -> insideValue = true
          "f" -> insideFormula = true
          "mergeCell" -> parser.attr("ref")?.let { merges.add(it) }
          "col" -> {
            val min = parser.attr("min")?.toIntOrNull() ?: 0
            val max = parser.attr("max")?.toIntOrNull() ?: 0
            val width = parser.attr("width")?.toDoubleOrNull() ?: 0.0
            if (min in 1..512 && max >= min) {
              while (widths.size < max) widths.add(0.0)
              for (i in (min - 1) until minOf(max, 512)) widths[i] = width
            }
          }
        }
        XmlPullParser.TEXT -> {
          val text = parser.text ?: ""
          if (insideValue) cellValue.append(text)
          else if (insideFormula) cellFormula = (cellFormula ?: "") + text
        }
        XmlPullParser.END_TAG -> when (parser.localName()) {
          "v", "t" -> insideValue = false
          "f" -> insideFormula = false
          "c" -> finalizeCell()
          "row" -> {
            val row = currentRow
            if (row != null) {
              while (row.isNotEmpty() && row.last().kind == CellKind.BLANK) row.removeAt(row.size - 1)
            }
            currentRow = null
          }
        }
      }
      event = parser.next()
    }

    while (rows.isNotEmpty() && rows.last().all { it.kind == CellKind.BLANK }) rows.removeAt(rows.size - 1)

    if (truncatedCells) warnings.add("sheet exceeded the cell limit; later cells were skipped")
    if (compressedGaps) warnings.add("sheet had extreme row gaps; empty rows were compressed")

    val rtl = arabicChars > 0 && arabicChars >= latinChars
    return ParsedSheet(rows.map { it.toList() }, widths, rtl, merges)
  }

  private fun resolveCell(
    rawValue: String,
    type: String?,
    styleIndex: Int,
    styles: StyleInfo,
    formula: String?,
    sharedStrings: List<String>
  ): CellValue {
    val formulaText = formula?.trim()?.takeIf { it.isNotEmpty() }
    val value = when (type) {
      "s" -> {
        val idx = rawValue.trim().toIntOrNull()
        if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else rawValue
      }
      "inlineStr", "str" -> rawValue
      "b" -> if (rawValue.trim() == "1") "TRUE" else "FALSE"
      "e" -> rawValue
      else -> rawValue
    }

    if (formulaText != null) {
      return CellValue(text = value, kind = CellKind.FORMULA, formula = "=$formulaText")
    }

    return when (type) {
      "b" -> CellValue(value, CellKind.BOOLEAN)
      "s", "inlineStr", "str", "e" -> CellValue(value, CellKind.TEXT)
      else -> {
        if (value.isBlank()) CellValue("", CellKind.BLANK)
        else if (styles.isDateStyle(styleIndex)) {
          val serial = value.toDoubleOrNull()
          if (serial != null) {
            CellValue(value, CellKind.DATE, dateIso = excelSerialToIso(serial))
          } else {
            CellValue(value, CellKind.TEXT)
          }
        } else if (isPlainNumber(value)) {
          CellValue(value, CellKind.NUMBER)
        } else {
          CellValue(value, CellKind.TEXT)
        }
      }
    }
  }

  fun isPlainNumber(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.length > 1 && trimmed.startsWith("0") && !trimmed.startsWith("0.")) return false
    return trimmed.toDoubleOrNull() != null
  }

  fun excelSerialToIso(serial: Double): String {
    val days = kotlin.math.floor(serial).toLong()
    val fraction = serial - days
    val date: LocalDate = when {
      days == 60L -> LocalDate.of(1900, 2, 28)
      days < 60L -> LocalDate.of(1899, 12, 31).plusDays(days)
      else -> LocalDate.of(1899, 12, 30).plusDays(days)
    }
    if (fraction <= 0.0) return date.toString()
    val totalSeconds = kotlin.math.round(fraction * 86400.0).toLong()
    val hours = (totalSeconds / 3600).toInt()
    val minutes = ((totalSeconds % 3600) / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    return "%sT%02d:%02d:%02d".format(date.toString(), hours, minutes, seconds)
  }

  fun columnIndexFromRef(ref: String): Int {
    var index = 0
    for (c in ref) {
      if (c == '$') continue
      if (c.isLetter()) {
        index = index * 26 + ((c.uppercaseChar() - 'A') + 1)
      } else {
        break
      }
    }
    return (index - 1).coerceAtLeast(0)
  }
}

object CsvTextExtractor {

  fun extract(file: File, docId: String, displayName: String): DocumentStructure {
    val raw = file.readBytes()
    if (raw.size > DocumentLimits.MAX_CSV_CHARS) {
      throw DocumentException("CSV file exceeds the 24 MB import limit")
    }
    var text = raw.toString(Charsets.UTF_8)
    if (text.startsWith("\uFEFF")) text = text.substring(1)

    val delimiter = detectDelimiter(text)
    val rows = parseDelimited(text, delimiter)
    val warnings = mutableListOf<String>()
    if (rows.size > 20000) warnings.add("Large CSV: only the first 20000 rows were indexed")

    val limited = rows.take(20000).map { row -> row.take(128) }
    val hasHeader = detectHeader(limited)
    val cells = limited.map { row ->
      row.map { cell ->
        when {
          cell.isBlank() -> CellValue("", CellKind.BLANK)
          cell.equals("true", true) || cell.equals("false", true) -> CellValue(cell.uppercase(), CellKind.BOOLEAN)
          XlsxTextExtractor.isPlainNumber(cell) -> CellValue(cell, CellKind.NUMBER)
          else -> CellValue(cell, CellKind.TEXT)
        }
      }
    }
    val sheetName = DocumentText.sanitizeSheetName(displayName.substringBeforeLast('.'))
    val joined = cells.joinToString("\n") { r -> r.joinToString(" ") { it.text } }
    val sheet = ExtractedSheet(
      name = sheetName,
      rows = cells,
      rtl = DocumentText.detectDirection(joined) == TextDirection.RTL
    )
    val blocks = listOf(
      DocumentBlock(
        kind = BlockKind.SHEET,
        text = "CSV table '$sheetName': ${cells.size} rows x ${cells.maxOfOrNull { it.size } ?: 0} columns. Delimiter: ${delimiterLabel(delimiter)}.${if (hasHeader) " First row treated as header." else ""}",
        sheetName = sheetName,
        direction = if (sheet.rtl) TextDirection.RTL else TextDirection.LTR,
        sourceEngine = "csv"
      )
    )
    return DocumentStructure(
      docId = docId,
      displayName = displayName,
      type = DocumentType.CSV,
      blocks = blocks,
      sheets = listOf(sheet),
      sheetCount = 1,
      primaryLanguage = DocumentText.detectLanguageHint(joined),
      direction = DocumentText.detectDirection(joined),
      engines = listOf("csv-parser"),
      warnings = warnings,
      textLength = joined.length
    )
  }

  private fun delimiterLabel(delimiter: Char): String = when (delimiter) {
    '\t' -> "tab"
    ';' -> "semicolon"
    else -> "comma"
  }

  fun detectDelimiter(text: String): Char {
    val sample = text.lineSequence().take(10).joinToString("\n")
    val candidates = listOf(',', '\t', ';')
    var best = ','
    var bestCount = -1
    for (candidate in candidates) {
      val count = countOutsideQuotes(sample, candidate)
      if (count > bestCount) {
        bestCount = count
        best = candidate
      }
    }
    return best
  }

  private fun countOutsideQuotes(text: String, delimiter: Char): Int {
    var inQuotes = false
    var count = 0
    for (c in text) {
      when {
        c == '"' -> inQuotes = !inQuotes
        c == delimiter && !inQuotes -> count++
        c == '\n' -> if (!inQuotes) count++
      }
    }
    return count
  }

  fun parseDelimited(text: String, delimiter: Char): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    var pendingQuote = false
    while (i < text.length) {
      val c = text[i]
      if (inQuotes) {
        if (pendingQuote) {
          if (c == '"') {
            field.append('"')
            pendingQuote = false
          } else {
            inQuotes = false
            pendingQuote = false
            continue
          }
        } else if (c == '"') {
          pendingQuote = true
        } else {
          field.append(c)
        }
      } else {
        when (c) {
          '"' -> inQuotes = true
          delimiter -> {
            row.add(field.toString())
            field.setLength(0)
          }
          '\r' -> { }
          '\n' -> {
            row.add(field.toString())
            field.setLength(0)
            rows.add(row)
            row = mutableListOf()
          }
          else -> field.append(c)
        }
      }
      i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
      row.add(field.toString())
      rows.add(row)
    }
    return rows
  }

  private fun detectHeader(rows: List<List<String>>): Boolean {
    val first = rows.firstOrNull() ?: return false
    if (first.isEmpty()) return false
    val nonBlank = first.filter { it.isNotBlank() }
    if (nonBlank.isEmpty()) return false
    val allText = nonBlank.none { XlsxTextExtractor.isPlainNumber(it) }
    val hasLetters = nonBlank.any { cell -> cell.any { it.isLetter() || DocumentText.isArabicLetter(it) } }
    return allText && hasLetters
  }
}

object TxtTextExtractor {

  fun extract(file: File, docId: String, displayName: String): DocumentStructure {
    val text = file.readBytes().toString(Charsets.UTF_8)
    val paragraphs = text.split(Regex("\\n\\s*\\n"))
      .map { DocumentText.collapseWhitespace(it) }
      .filter { it.isNotBlank() }
    val blocks = paragraphs.map { paragraph ->
      DocumentBlock(
        kind = BlockKind.PARAGRAPH,
        text = paragraph,
        direction = DocumentText.detectDirection(paragraph),
        language = DocumentText.detectLanguageHint(paragraph),
        sourceEngine = "txt"
      )
    }
    val joined = blocks.joinToString("\n") { it.text }
    return DocumentStructure(
      docId = docId,
      displayName = displayName,
      type = DocumentType.TXT,
      blocks = blocks,
      primaryLanguage = DocumentText.detectLanguageHint(joined),
      direction = DocumentText.detectDirection(joined),
      engines = listOf("txt"),
      textLength = joined.length
    )
  }
}
