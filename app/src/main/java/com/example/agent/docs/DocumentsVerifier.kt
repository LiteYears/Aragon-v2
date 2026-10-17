package com.example.agent.docs

import java.io.File
import java.util.zip.ZipFile

data class VerificationResult(
  val ok: Boolean,
  val details: String,
  val issues: List<String> = emptyList()
)

data class DocxExpectation(
  val minParagraphs: Int = 1,
  val minTables: Int = 0,
  val expectRtl: Boolean = false,
  val sampleTexts: List<String> = emptyList()
)

data class XlsxExpectation(
  val minSheets: Int = 1,
  val minDataRows: Int = 1,
  val expectAnyRtlSheet: Boolean = false,
  val sampleCells: List<String> = emptyList()
)

object DocumentsVerifier {

  fun verifyDocx(file: File, expectation: DocxExpectation): VerificationResult {
    val issues = mutableListOf<String>()
    if (!file.exists() || file.length() == 0L) {
      return VerificationResult(false, "DOCX file missing or empty", listOf("File does not exist"))
    }
    val structure = try {
      DocxTextExtractor.extract(file, "verify", file.name)
    } catch (e: Exception) {
      return VerificationResult(false, "DOCX could not be reopened: ${e.message}", listOf("Reopen failed"))
    }
    val paragraphCount = structure.blocks.size
    val tableCount = structure.blocks.count { it.kind == BlockKind.TABLE }
    if (paragraphCount < expectation.minParagraphs) {
      issues.add("Expected at least ${expectation.minParagraphs} paragraphs, found $paragraphCount")
    }
    if (tableCount < expectation.minTables) {
      issues.add("Expected at least ${expectation.minTables} tables, found $tableCount")
    }

    val rawXml = readZipEntry(file, "word/document.xml") ?: ""
    val bidiMarkers = Regex("<w:(?:bidi|rtl)\\b").findAll(rawXml).count()
    if (expectation.expectRtl && bidiMarkers == 0) {
      issues.add("RTL markers were requested but none were found in the generated document")
    }

    val combined = DocumentText.normalizeForSearch(buildSamplePool(structure))
    for (sample in expectation.sampleTexts.take(5)) {
      val normalized = DocumentText.normalizeForSearch(sample)
      if (normalized.isNotEmpty() && !combined.contains(normalized)) {
        issues.add("Generated document is missing expected text: \"${sample.take(60)}\"")
      }
    }

    val details = "DOCX reopened: $paragraphCount paragraphs, $tableCount tables, $bidiMarkers RTL marker(s)"
    return VerificationResult(issues.isEmpty(), details, issues)
  }

  fun verifyXlsx(file: File, expectation: XlsxExpectation): VerificationResult {
    val issues = mutableListOf<String>()
    if (!file.exists() || file.length() == 0L) {
      return VerificationResult(false, "XLSX file missing or empty", listOf("File does not exist"))
    }
    val structure = try {
      XlsxTextExtractor.extract(file, "verify", file.name)
    } catch (e: Exception) {
      return VerificationResult(false, "XLSX could not be reopened: ${e.message}", listOf("Reopen failed"))
    }
    if (structure.sheets.size < expectation.minSheets) {
      issues.add("Expected at least ${expectation.minSheets} sheets, found ${structure.sheets.size}")
    }
    val dataRows = structure.sheets.sumOf { sheet -> sheet.rows.count { row -> row.any { it.kind != CellKind.BLANK } } }
    if (dataRows < expectation.minDataRows) {
      issues.add("Expected at least ${expectation.minDataRows} data rows, found $dataRows")
    }
    val formulaCount = structure.sheets.sumOf { sheet -> sheet.rows.sumOf { row -> row.count { it.kind == CellKind.FORMULA } } }

    var rtlSheets = 0
    ZipFile(file).use { archive ->
      for (sheet in structure.sheets) {
        val entry = archive.getEntry("xl/worksheets/sheet${structure.sheets.indexOf(sheet) + 1}.xml")
        if (entry != null) {
          val xml = archive.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
          if (xml.contains("rightToLeft=\"1\"")) rtlSheets++
        }
      }
    }
    if (expectation.expectAnyRtlSheet && rtlSheets == 0) {
      issues.add("An RTL sheet was requested but the generated workbook has none")
    }

    val pool = StringBuilder()
    for (sheet in structure.sheets) {
      for (row in sheet.rows) {
        for (cell in row) {
          pool.append(cell.text).append('\n')
          cell.formula?.let { pool.append(it).append('\n') }
        }
      }
    }
    val combined = DocumentText.normalizeForSearch(pool.toString())
    for (sample in expectation.sampleCells.take(6)) {
      val normalized = DocumentText.normalizeForSearch(sample)
      if (normalized.isNotEmpty() && !combined.contains(normalized)) {
        issues.add("Generated workbook is missing expected cell content: \"${sample.take(60)}\"")
      }
    }

    val details = "XLSX reopened: ${structure.sheets.size} sheet(s), $dataRows data row(s), $formulaCount formula(s), $rtlSheets RTL sheet(s)"
    return VerificationResult(issues.isEmpty(), details, issues)
  }

  private fun buildSamplePool(structure: DocumentStructure): String {
    val pool = StringBuilder()
    for (block in structure.blocks) {
      pool.append(block.text).append('\n')
      block.table?.let { table ->
        for (cell in table.header) pool.append(cell).append('\n')
        for (row in table.rows) {
          for (cell in row) pool.append(cell).append('\n')
        }
      }
    }
    for (sheet in structure.sheets) {
      for (row in sheet.rows) {
        for (cell in row) pool.append(cell.text).append('\n')
      }
    }
    return pool.toString()
  }

  private fun readZipEntry(file: File, path: String): String? = try {
    ZipFile(file).use { archive ->
      val entry = archive.getEntry(path)
      if (entry == null) null
      else archive.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    }
  } catch (_: Exception) {
    null
  }

  fun verifyPdf(file: File, minPages: Int = 1): VerificationResult {
    if (!file.exists() || file.length() < 100L) {
      return VerificationResult(false, "PDF file missing or empty", listOf("File does not exist or empty"))
    }
    return try {
      val res = PdfTextExtractor.extractText(file, maxPages = 10)
      if (res.pageCount < minPages) {
        VerificationResult(false, "PDF has ${res.pageCount} pages, expected at least $minPages", listOf("Page count too low"))
      } else {
        VerificationResult(true, "PDF verified successfully (${res.pageCount} pages, ${file.length()} bytes)")
      }
    } catch (e: Exception) {
      VerificationResult(false, "PDF verification failed: ${e.message}", listOf(e.message ?: "PDF error"))
    }
  }
}
