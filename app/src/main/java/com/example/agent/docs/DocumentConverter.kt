package com.example.agent.docs

import com.example.agent.core.DocxBuilder
import com.example.agent.core.XlsxBuilder
import java.io.File

class DocumentConverter(private val workspaceDir: File) {

  fun toDocx(structure: DocumentStructure, targetDir: File, baseName: String): ConversionReport {
    val warnings = mutableListOf<String>()
    val builder = DocxBuilder()
    builder.setCoreProperties(title = baseName, creator = "Aragon Agent")
    val samples = mutableListOf<String>()
    var tablesWritten = 0
    var expectRtl = structure.direction == TextDirection.RTL
    val maxRowsPerTable = 2000

    fun addTableBlock(header: List<String>, rows: List<List<String>>, isRtl: Boolean) {
      var body = rows
      if (body.size > maxRowsPerTable) {
        body = body.take(maxRowsPerTable)
        warnings.add("A table was truncated to $maxRowsPerTable rows in the DOCX output")
      }
      builder.addTable(header, body, isRtl = isRtl || header.any { DocxBuilder.containsRtl(it) })
      tablesWritten++
      if (header.isNotEmpty() && samples.size < 5) samples.add(header.first())
      if (body.isNotEmpty() && samples.size < 5 && body.first().isNotEmpty()) samples.add(body.first().first())
      if (isRtl) expectRtl = true
    }

    if (structure.type.isSpreadsheet && structure.sheets.isNotEmpty()) {
      for ((index, sheet) in structure.sheets.withIndex()) {
        if (index > 0) builder.addPageBreak()
        builder.addHeading(sheet.name, 1, isRtl = sheet.rtl)
        val rows = sheet.rows.map { row -> row.map { cellText(it) } }
        if (rows.isEmpty()) continue
        val header = rows.first()
        val body = rows.drop(1)
        addTableBlock(header, body, sheet.rtl)
      }
    } else {
      var index = 0
      val blocks = structure.blocks
      while (index < blocks.size) {
        val block = blocks[index]
        when (block.kind) {
          BlockKind.HEADING -> {
            builder.addHeading(block.text, block.headingLevel.coerceIn(1, 3))
            if (samples.size < 5) samples.add(block.text)
            if (block.direction == TextDirection.RTL) expectRtl = true
            index++
          }
          BlockKind.PARAGRAPH -> {
            builder.addParagraph(block.text)
            if (samples.size < 5) samples.add(block.text)
            if (block.direction == TextDirection.RTL) expectRtl = true
            index++
          }
          BlockKind.LIST_ITEM -> {
            val items = mutableListOf<String>()
            var ordered = block.orderedHint
            var j = index
            while (j < blocks.size && blocks[j].kind == BlockKind.LIST_ITEM) {
              items.add(blocks[j].text)
              ordered = ordered || blocks[j].orderedHint
              j++
            }
            builder.addList(items, ordered = ordered, isRtl = block.direction == TextDirection.RTL)
            if (samples.size < 5) samples.add(items.firstOrNull() ?: "")
            index = j
          }
          BlockKind.TABLE -> {
            val table = block.table
            if (table == null) {
              index++
              continue
            }
            val rows = table.rows.map { row -> row.map { it } }
            addTableBlock(table.header, rows, table.direction == TextDirection.RTL)
            if (table.uncertain) {
              warnings.add("Table layout '${table.sourceLocation ?: "unknown"}' was low-confidence and may be approximate")
            }
            index++
          }
          BlockKind.SHEET -> {
            index++
          }
        }
      }
    }

    val output = DocumentText.uniqueFile(targetDir, baseName, "docx")
    builder.save(output)

    val verification = DocumentsVerifier.verifyDocx(
      output,
      DocxExpectation(
        minParagraphs = 1,
        minTables = tablesWritten,
        expectRtl = expectRtl,
        sampleTexts = samples.filter { it.isNotBlank() }
      )
    )
    warnings.addAll(verification.issues)

    return ConversionReport(
      outputFile = output,
      format = "docx",
      warnings = warnings,
      tablesWritten = tablesWritten,
      rtlApplied = expectRtl,
      verification = verification.details,
      verified = verification.ok
    )
  }

  fun toXlsx(structure: DocumentStructure, targetDir: File, baseName: String): ConversionReport {
    val warnings = mutableListOf<String>()
    var formulasRecovered = 0
    var formulasDropped = 0
    val samples = mutableListOf<String>()
    var expectRtl = false

    val builder: XlsxBuilder
    if (structure.sheets.isNotEmpty()) {
      val first = structure.sheets.first()
      builder = XlsxBuilder(first.name)
      for (index in structure.sheets.indices) {
        val sheet = structure.sheets[index]
        if (index > 0) builder.addSheet(sheet.name)
        if (sheet.rtl) {
          builder.setCurrentSheetRtl(true)
          expectRtl = true
        }
        if (sheet.rows.size > 2) builder.freezeTopRow()
        if (sheet.columnWidths.isNotEmpty()) builder.setColumnWidths(sheet.columnWidths)
        for (range in sheet.mergedRanges.take(500)) builder.mergeCells(range)
        var rowCount = 0
        for (row in sheet.rows) {
          if (rowCount >= 20000) {
            warnings.add("Sheet '${sheet.name}' was truncated to 20000 rows")
            break
          }
          val specs = row.map { cell -> cellSpecFor(cell, warnings) { dropped -> if (dropped) formulasDropped++ else formulasRecovered++ } }
          builder.addTypedRow(specs)
          rowCount++
          if (samples.size < 6) {
            row.firstOrNull { it.text.isNotBlank() }?.let { samples.add(it.text) }
          }
        }
      }
    } else {
      builder = XlsxBuilder("Document")
      var tablesOnSheet = 0
      for (block in structure.blocks) {
        when (block.kind) {
          BlockKind.TABLE -> {
            val table = block.table ?: continue
            if (tablesOnSheet > 0) {
              builder.addTypedRow(listOf(XlsxBuilder.CellSpec("", XlsxBuilder.CellKind.BLANK)))
            }
            if (table.sourceLocation != null) {
              builder.addTypedRow(listOf(XlsxBuilder.CellSpec(table.sourceLocation, XlsxBuilder.CellKind.TEXT)))
            }
            if (table.header.isNotEmpty()) builder.addHeader(table.header)
            for (row in table.rows.take(20000)) builder.addRow(row)
            tablesOnSheet++
            if (table.direction == TextDirection.RTL) {
              builder.setCurrentSheetRtl(true)
              expectRtl = true
            }
            if (table.uncertain) warnings.add("An OCR table was low-confidence and may be approximate")
          }
          BlockKind.HEADING, BlockKind.PARAGRAPH -> {
            if (block.text.isNotBlank()) {
              builder.addTypedRow(listOf(XlsxBuilder.CellSpec(block.text, XlsxBuilder.CellKind.TEXT)))
            }
          }
          else -> {
          }
        }
      }
      if (samples.isEmpty()) {
        structure.blocks.firstOrNull { it.text.isNotBlank() }?.let { samples.add(it.text) }
      }
    }

    val output = DocumentText.uniqueFile(targetDir, baseName, "xlsx")
    builder.save(output)

    val verification = DocumentsVerifier.verifyXlsx(
      output,
      XlsxExpectation(
        minSheets = 1,
        minDataRows = 1,
        expectAnyRtlSheet = expectRtl,
        sampleCells = samples.filter { it.isNotBlank() }
      )
    )
    warnings.addAll(verification.issues)

    return ConversionReport(
      outputFile = output,
      format = "xlsx",
      warnings = warnings,
      sheetsWritten = builder.sheetCount(),
      formulasRecovered = formulasRecovered,
      formulasDropped = formulasDropped,
      rtlApplied = expectRtl,
      verification = verification.details,
      verified = verification.ok
    )
  }

  fun toMarkdown(structure: DocumentStructure, targetDir: File, baseName: String): ConversionReport {
    val warnings = mutableListOf<String>()
    val sb = StringBuilder()
    if (structure.sheets.isNotEmpty()) {
      for (sheet in structure.sheets) {
        sb.append("## ").append(sheet.name).append('\n').append('\n')
        val rows = sheet.rows.map { row -> row.map { cellText(it) } }
        if (rows.isNotEmpty()) {
          sb.append("| ").append(rows.first().joinToString(" | ")).append(" |").append('\n')
          sb.append("|").append(rows.first().joinToString("|") { " --- " }).append("|").append('\n')
          for (row in rows.drop(1)) {
            sb.append("| ").append(row.joinToString(" | ")).append(" |").append('\n')
          }
          sb.append('\n')
        }
      }
    } else {
      for (block in structure.blocks) {
        when (block.kind) {
          BlockKind.HEADING -> sb.append("#".repeat(block.headingLevel.coerceIn(1, 6))).append(' ').append(block.text).append('\n').append('\n')
          BlockKind.PARAGRAPH -> sb.append(block.text).append('\n').append('\n')
          BlockKind.LIST_ITEM -> sb.append("- ").append(block.text).append('\n')
          BlockKind.TABLE -> block.table?.let { table ->
            if (table.header.isNotEmpty()) {
              sb.append("| ").append(table.header.joinToString(" | ")).append(" |").append('\n')
              sb.append("|").append(table.header.joinToString("|") { " --- " }).append("|").append('\n')
            }
            for (row in table.rows) {
              sb.append("| ").append(row.joinToString(" | ")).append(" |").append('\n')
            }
            sb.append('\n')
            if (table.uncertain) warnings.add("An OCR table was low-confidence and may be approximate")
          }
          BlockKind.SHEET -> {
          }
        }
      }
    }
    val output = DocumentText.uniqueFile(targetDir, baseName, "md")
    output.writeText(sb.toString(), Charsets.UTF_8)
    val ok = output.exists() && output.length() > 0
    if (!ok) warnings.add("Markdown output could not be verified on disk")
    return ConversionReport(
      outputFile = output,
      format = "md",
      warnings = warnings,
      verification = "Markdown written: ${output.length()} bytes",
      verified = ok
    )
  }

  fun toPdf(structure: DocumentStructure, targetDir: File, baseName: String): ConversionReport {
    val warnings = mutableListOf<String>()
    val builder = com.example.agent.core.PdfBuilder(title = baseName)
    var tablesWritten = 0

    builder.addTitle(baseName)

    if (structure.type.isSpreadsheet && structure.sheets.isNotEmpty()) {
      for (sheet in structure.sheets) {
        builder.addHeading(sheet.name, 1)
        val rows = sheet.rows.map { row -> row.map { cellText(it) } }
        if (rows.isNotEmpty()) {
          builder.addTable(rows.first(), rows.drop(1))
          tablesWritten++
        }
      }
    } else {
      for (b in structure.blocks) {
        when (b.kind) {
          BlockKind.HEADING -> builder.addHeading(b.text, b.headingLevel.coerceIn(1, 3))
          BlockKind.PARAGRAPH -> builder.addParagraph(b.text)
          BlockKind.LIST_ITEM -> builder.addList(listOf(b.text), ordered = b.orderedHint)
          BlockKind.TABLE -> {
            b.table?.let { t ->
              builder.addTable(t.header, t.rows)
              tablesWritten++
            }
          }
          else -> {}
        }
      }
    }

    val output = DocumentText.uniqueFile(targetDir, baseName, "pdf")
    builder.save(output)

    val verification = DocumentsVerifier.verifyPdf(output)
    warnings.addAll(verification.issues)

    return ConversionReport(
      outputFile = output,
      format = "pdf",
      warnings = warnings,
      tablesWritten = tablesWritten,
      verification = verification.details,
      verified = verification.ok
    )
  }

  private fun cellText(cell: CellValue): String = when (cell.kind) {
    CellKind.FORMULA -> cell.text.ifBlank { cell.formula ?: "" }
    CellKind.DATE -> cell.text.ifBlank { cell.dateIso ?: "" }
    else -> cell.text
  }

  private fun cellSpecFor(cell: CellValue, warnings: MutableList<String>, formulaCounter: (Boolean) -> Unit): XlsxBuilder.CellSpec {
    return when (cell.kind) {
      CellKind.BLANK -> XlsxBuilder.CellSpec("", XlsxBuilder.CellKind.BLANK)
      CellKind.TEXT -> XlsxBuilder.CellSpec(cell.text, XlsxBuilder.CellKind.TEXT)
      CellKind.NUMBER -> XlsxBuilder.CellSpec(cell.text, XlsxBuilder.CellKind.NUMBER)
      CellKind.BOOLEAN -> XlsxBuilder.CellSpec(cell.text, XlsxBuilder.CellKind.BOOLEAN)
      CellKind.DATE -> {
        val serial = cell.dateIso?.let { XlsxBuilder.excelSerialFromIso(it) }
        if (serial != null) {
          XlsxBuilder.CellSpec(cell.dateIso ?: cell.text, XlsxBuilder.CellKind.DATE, dateSerial = serial)
        } else {
          XlsxBuilder.CellSpec(cell.text, XlsxBuilder.CellKind.TEXT)
        }
      }
      CellKind.FORMULA -> {
        val formula = cell.formula
        if (formula != null && formula.isNotBlank()) {
          formulaCounter(false)
          XlsxBuilder.CellSpec(cell.text, XlsxBuilder.CellKind.FORMULA, formula = formula)
        } else {
          formulaCounter(true)
          warnings.add("A formula with no readable definition was written as text")
          XlsxBuilder.CellSpec(cell.text, XlsxBuilder.CellKind.TEXT)
        }
      }
    }
  }
}
