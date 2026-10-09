package com.example.agent.core

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure Kotlin/Java OpenXML .docx builder with zero external dependencies.
 * Creates authentic Microsoft Word .docx files (valid ZIP archives) that open
 * natively in Microsoft Word, Google Docs, LibreOffice, and mobile Office apps.
 */
class DocxBuilder {

  data class Paragraph(
    val text: String,
    val headingLevel: Int = 0, // 0 = normal paragraph, 1 = Title/H1, 2 = H2, 3 = H3
    val isBold: Boolean = false,
    val isItalic: Boolean = false
  )

  data class Table(
    val headers: List<String>,
    val rows: List<List<String>>
  )

  private val elements = mutableListOf<Any>() // Paragraph or Table

  fun addHeading(text: String, level: Int = 1): DocxBuilder {
    elements.add(Paragraph(text = text, headingLevel = level.coerceIn(1, 3), isBold = true))
    return this
  }

  fun addParagraph(text: String, isBold: Boolean = false, isItalic: Boolean = false): DocxBuilder {
    elements.add(Paragraph(text = text, headingLevel = 0, isBold = isBold, isItalic = isItalic))
    return this
  }

  fun addTable(headers: List<String>, rows: List<List<String>>): DocxBuilder {
    elements.add(Table(headers = headers, rows = rows))
    return this
  }

  fun save(targetFile: File) {
    targetFile.parentFile?.mkdirs()
    FileOutputStream(targetFile).use { fos ->
      ZipOutputStream(fos).use { zos ->
        // 1. [Content_Types].xml
        putZipEntry(zos, "[Content_Types].xml", buildContentTypesXml())

        // 2. _rels/.rels
        putZipEntry(zos, "_rels/.rels", buildRootRelsXml())

        // 3. word/_rels/document.xml.rels
        putZipEntry(zos, "word/_rels/document.xml.rels", buildWordRelsXml())

        // 4. word/styles.xml
        putZipEntry(zos, "word/styles.xml", buildStylesXml())

        // 5. word/document.xml
        putZipEntry(zos, "word/document.xml", buildDocumentXml())
      }
    }
  }

  private fun putZipEntry(zos: ZipOutputStream, path: String, content: String) {
    val entry = ZipEntry(path)
    zos.putNextEntry(entry)
    zos.write(content.toByteArray(StandardCharsets.UTF_8))
    zos.closeEntry()
  }

  private fun buildContentTypesXml(): String = """
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
      <Default Extension="xml" ContentType="application/xml"/>
      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
      <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
    </Types>
  """.trimIndent().trim()

  private fun buildRootRelsXml(): String = """
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
    </Relationships>
  """.trimIndent().trim()

  private fun buildWordRelsXml(): String = """
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
    </Relationships>
  """.trimIndent().trim()

  private fun buildStylesXml(): String = """
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
      <w:docDefaults>
        <w:rPrDefault>
          <w:rPr>
            <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/>
            <w:sz w:val="22"/>
            <w:color w:val="262626"/>
          </w:rPr>
        </w:rPrDefault>
      </w:docDefaults>
    </w:styles>
  """.trimIndent().trim()

  private fun buildDocumentXml(): String {
    val sb = StringBuilder()
    sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    sb.append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
    sb.append("<w:body>")

    for (elem in elements) {
      when (elem) {
        is Paragraph -> appendParagraphXml(sb, elem)
        is Table -> appendTableXml(sb, elem)
      }
    }

    // Default section properties (US Letter, 1-inch margins)
    sb.append("""
      <w:sectPr>
        <w:pgSz w:w="12240" w:h="15840"/>
        <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/>
      </w:sectPr>
    """.trimIndent())

    sb.append("</w:body>")
    sb.append("</w:document>")
    return sb.toString()
  }

  private fun appendParagraphXml(sb: StringBuilder, p: Paragraph) {
    sb.append("<w:p>")
    sb.append("<w:pPr>")

    val (fontSizeHalfPt, colorHex, spacingBefore, spacingAfter) = when (p.headingLevel) {
      1 -> Quad(36, "1F497D", 280, 160) // 18pt bold
      2 -> Quad(28, "2E74B5", 200, 100) // 14pt bold
      3 -> Quad(24, "365F91", 160, 80)  // 12pt bold
      else -> Quad(22, "262626", 0, 100) // 11pt normal
    }

    sb.append("""<w:spacing w:before="$spacingBefore" w:after="$spacingAfter" w:line="276" w:lineRule="auto"/>""")
    sb.append("</w:pPr>")

    // Runs
    val lines = p.text.split("\n")
    for ((idx, line) in lines.withIndex()) {
      if (idx > 0) {
        sb.append("<w:r><w:br/></w:r>")
      }
      sb.append("<w:r>")
      sb.append("<w:rPr>")
      sb.append("""<w:sz w:val="$fontSizeHalfPt"/>""")
      sb.append("""<w:color w:val="$colorHex"/>""")
      if (p.isBold || p.headingLevel > 0) sb.append("<w:b/>")
      if (p.isItalic) sb.append("<w:i/>")
      sb.append("</w:rPr>")
      sb.append("""<w:t xml:space="preserve">${escapeXml(line)}</w:t>""")
      sb.append("</w:r>")
    }

    sb.append("</w:p>")
  }

  private fun appendTableXml(sb: StringBuilder, t: Table) {
    sb.append("<w:tbl>")
    // Table properties: borders & center alignment
    sb.append("""
      <w:tblPr>
        <w:tblW w:w="5000" w:type="pct"/>
        <w:jc w:val="center"/>
        <w:tblBorders>
          <w:top w:val="single" w:sz="6" w:space="0" w:color="D3D3D3"/>
          <w:left w:val="single" w:sz="6" w:space="0" w:color="D3D3D3"/>
          <w:bottom w:val="single" w:sz="6" w:space="0" w:color="D3D3D3"/>
          <w:right w:val="single" w:sz="6" w:space="0" w:color="D3D3D3"/>
          <w:insideH w:val="single" w:sz="4" w:space="0" w:color="E5E5E5"/>
          <w:insideV w:val="single" w:sz="4" w:space="0" w:color="E5E5E5"/>
        </w:tblBorders>
      </w:tblPr>
    """.trimIndent())

    // Headers row
    if (t.headers.isNotEmpty()) {
      sb.append("<w:tr>")
      sb.append("<w:trPr><w:tblHeader/></w:trPr>")
      for (h in t.headers) {
        appendCellXml(sb, h, isHeader = true)
      }
      sb.append("</w:tr>")
    }

    // Data rows
    for (row in t.rows) {
      sb.append("<w:tr>")
      for (cell in row) {
        appendCellXml(sb, cell, isHeader = false)
      }
      sb.append("</w:tr>")
    }

    sb.append("</w:tbl>")
    // Spacer after table
    sb.append("<w:p><w:pPr><w:spacing w:after=\"160\"/></w:pPr></w:p>")
  }

  private fun appendCellXml(sb: StringBuilder, text: String, isHeader: Boolean) {
    sb.append("<w:tc>")
    sb.append("<w:tcPr>")
    val bgColor = if (isHeader) "F2F4F7" else "FFFFFF"
    sb.append("""<w:shd w:val="clear" w:color="auto" w:fill="$bgColor"/>""")
    sb.append("""<w:tcMar><w:top w:w="120" w:type="dxa"/><w:bottom w:w="120" w:type="dxa"/><w:left w:w="160" w:type="dxa"/><w:right w:w="160" w:type="dxa"/></w:tcMar>""")
    sb.append("</w:tcPr>")
    sb.append("<w:p>")
    sb.append("<w:r>")
    sb.append("<w:rPr>")
    sb.append("""<w:sz w:val="20"/>""")
    if (isHeader) {
      sb.append("<w:b/>")
      sb.append("""<w:color w:val="1F2937"/>""")
    } else {
      sb.append("""<w:color w:val="374151"/>""")
    }
    sb.append("</w:rPr>")
    sb.append("""<w:t xml:space="preserve">${escapeXml(text)}</w:t>""")
    sb.append("</w:r>")
    sb.append("</w:p>")
    sb.append("</w:tc>")
  }

  private fun escapeXml(str: String): String =
    str.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")

  private data class Quad(val a: Int, val b: String, val c: Int, val d: Int)
}
