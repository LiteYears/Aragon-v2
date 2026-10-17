package com.example.agent.core

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure Android native PDF builder producing professional, verified, styled PDF documents.
 * Employs android.graphics.pdf.PdfDocument with automatic multi-page flow, running headers/footers,
 * dynamic page numbering ("Page X of Y"), styled tables with zebra striping, headings H1-H3,
 * callout alert boxes, bullet/numbered lists, and Markdown-to-PDF compilation.
 */
class PdfBuilder(
  var title: String = "Document",
  var author: String = "Aragon Agent",
  var date: String = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)),
  var brandColorHex: String = "1F497D"
) {

  enum class PageSize(val widthPt: Int, val heightPt: Int) {
    A4(595, 842),
    LETTER(612, 792)
  }

  enum class PageOrientation { PORTRAIT, LANDSCAPE }

  enum class CalloutType(val title: String, val borderColorHex: String, val bgColorHex: String) {
    NOTE("NOTE", "2563EB", "EFF6FF"),
    TIP("TIP", "0D9488", "F0FDFA"),
    WARNING("WARNING", "D97706", "FFFBEB"),
    DANGER("IMPORTANT", "DC2626", "FEF2F2"),
    SUCCESS("SUCCESS", "16A34A", "F0FDF4")
  }

  sealed class PdfElement {
    data class TitleBlock(val title: String, val subtitle: String? = null, val author: String? = null, val date: String? = null) : PdfElement()
    data class Heading(val text: String, val level: Int) : PdfElement()
    data class Paragraph(val text: String, val isBold: Boolean = false, val isItalic: Boolean = false, val colorHex: String? = null) : PdfElement()
    data class Callout(val text: String, val title: String? = null, val type: CalloutType = CalloutType.NOTE) : PdfElement()
    data class Table(val headers: List<String>, val rows: List<List<String>>, val zebra: Boolean = true, val colWidthPcts: List<Float> = emptyList()) : PdfElement()
    data class ListBlock(val items: List<String>, val ordered: Boolean = false) : PdfElement()
    data class CodeBlock(val code: String, val language: String? = null) : PdfElement()
    object HorizontalRule : PdfElement()
    object PageBreak : PdfElement()
  }

  var pageSize: PageSize = PageSize.A4
  var orientation: PageOrientation = PageOrientation.PORTRAIT
  var headerText: String? = null
  var footerText: String? = null
  var includePageNumbers: Boolean = true

  private val elements = mutableListOf<PdfElement>()

  fun addTitle(title: String, subtitle: String? = null, author: String? = null, date: String? = null): PdfBuilder {
    this.title = title
    if (author != null) this.author = author
    if (date != null) this.date = date
    elements.add(PdfElement.TitleBlock(title, subtitle, author ?: this.author, date ?: this.date))
    return this
  }

  fun addHeading(text: String, level: Int = 1): PdfBuilder {
    elements.add(PdfElement.Heading(text, level.coerceIn(1, 3)))
    return this
  }

  fun addParagraph(text: String, isBold: Boolean = false, isItalic: Boolean = false, colorHex: String? = null): PdfBuilder {
    if (text.isNotBlank()) {
      elements.add(PdfElement.Paragraph(text, isBold, isItalic, colorHex))
    }
    return this
  }

  fun addCallout(text: String, title: String? = null, type: CalloutType = CalloutType.NOTE): PdfBuilder {
    elements.add(PdfElement.Callout(text, title, type))
    return this
  }

  fun addTable(headers: List<String>, rows: List<List<String>>, zebra: Boolean = true, colWidthPcts: List<Float> = emptyList()): PdfBuilder {
    elements.add(PdfElement.Table(headers, rows, zebra, colWidthPcts))
    return this
  }

  fun addList(items: List<String>, ordered: Boolean = false): PdfBuilder {
    if (items.isNotEmpty()) {
      elements.add(PdfElement.ListBlock(items, ordered))
    }
    return this
  }

  fun addCodeBlock(code: String, language: String? = null): PdfBuilder {
    elements.add(PdfElement.CodeBlock(code, language))
    return this
  }

  fun addHorizontalRule(): PdfBuilder {
    elements.add(PdfElement.HorizontalRule)
    return this
  }

  fun addPageBreak(): PdfBuilder {
    elements.add(PdfElement.PageBreak)
    return this
  }

  fun addMarkdown(markdown: String): PdfBuilder {
    val lines = markdown.lines()
    var i = 0
    val n = lines.size
    while (i < n) {
      val raw = lines[i]
      val line = raw.trim()
      when {
        line.startsWith("```") -> {
          val lang = line.removePrefix("```").trim().takeIf { it.isNotBlank() }
          val codeLines = mutableListOf<String>()
          i++
          while (i < n && !lines[i].trim().startsWith("```")) {
            codeLines.add(lines[i])
            i++
          }
          addCodeBlock(codeLines.joinToString("\n"), lang)
        }
        line.startsWith("# ") -> addHeading(line.removePrefix("# ").trim(), 1)
        line.startsWith("## ") -> addHeading(line.removePrefix("## ").trim(), 2)
        line.startsWith("### ") -> addHeading(line.removePrefix("### ").trim(), 3)
        line.startsWith("#### ") || line.startsWith("##### ") -> addHeading(line.replace(Regex("^#+\\s*"), "").trim(), 3)
        line.startsWith("---") || line.startsWith("***") -> addHorizontalRule()
        line.startsWith("> ") || line == ">" -> {
          val quoteLines = mutableListOf<String>()
          var j = i
          while (j < n && (lines[j].trim().startsWith(">") || lines[j].trim().isEmpty())) {
            if (lines[j].trim().startsWith(">")) {
              quoteLines.add(lines[j].trim().removePrefix(">").trim())
            }
            j++
          }
          i = j - 1
          val joined = quoteLines.joinToString(" ")
          if (joined.startsWith("[!NOTE]") || joined.startsWith("[!TIP]") || joined.startsWith("[!WARNING]") || joined.startsWith("[!DANGER]")) {
            val typeStr = joined.substringAfter("[!").substringBefore("]")
            val cType = when (typeStr.uppercase()) {
              "TIP" -> CalloutType.TIP
              "WARNING" -> CalloutType.WARNING
              "DANGER", "CAUTION" -> CalloutType.DANGER
              else -> CalloutType.NOTE
            }
            addCallout(joined.substringAfter("]").trim(), type = cType)
          } else {
            addCallout(joined, title = "QUOTE", type = CalloutType.NOTE)
          }
        }
        line.startsWith("|") && line.contains("|") -> {
          val tableLines = mutableListOf<String>()
          var j = i
          while (j < n && lines[j].trim().startsWith("|") && lines[j].trim().endsWith("|")) {
            tableLines.add(lines[j].trim())
            j++
          }
          if (tableLines.size >= 2) {
            val headers = parseMarkdownRow(tableLines[0])
            val rows = mutableListOf<List<String>>()
            val startIndex = if (tableLines[1].contains("---")) 2 else 1
            for (r in startIndex until tableLines.size) {
              val row = parseMarkdownRow(tableLines[r])
              if (row.isNotEmpty()) rows.add(row)
            }
            addTable(headers, rows)
            i = j - 1
          } else {
            addParagraph(line)
          }
        }
        line.startsWith("- ") || line.startsWith("* ") -> {
          val items = mutableListOf<String>()
          var j = i
          while (j < n && (lines[j].trim().startsWith("- ") || lines[j].trim().startsWith("* "))) {
            items.add(lines[j].trim().substring(2).trim())
            j++
          }
          addList(items, ordered = false)
          i = j - 1
        }
        Regex("""^\d+\.\s+""").containsMatchIn(line) -> {
          val items = mutableListOf<String>()
          var j = i
          while (j < n) {
            val l = lines[j].trim()
            val m = Regex("""^\d+\.\s+(.*)""").find(l)
            if (m != null) {
              items.add(m.groupValues[1].trim())
              j++
            } else break
          }
          addList(items, ordered = true)
          i = j - 1
        }
        line.isNotBlank() -> {
          val clean = line.replace(Regex("""\*\*(.*?)\*\*"""), "$1")
            .replace(Regex("""\*(.*?)\*"""), "$1")
            .replace(Regex("""`(.*?)`"""), "$1")
          addParagraph(clean)
        }
      }
      i++
    }
    return this
  }

  private fun parseMarkdownRow(rowLine: String): List<String> {
    return rowLine.trim().removePrefix("|").removeSuffix("|")
      .split("|")
      .map { it.trim() }
  }

  fun save(targetFile: File) {
    targetFile.parentFile?.mkdirs()
    val doc = PdfDocument()

    val (rawW, rawH) = when (pageSize) {
      PageSize.A4 -> Pair(PageSize.A4.widthPt, PageSize.A4.heightPt)
      PageSize.LETTER -> Pair(PageSize.LETTER.widthPt, PageSize.LETTER.heightPt)
    }
    val pageW = if (orientation == PageOrientation.LANDSCAPE) rawH else rawW
    val pageH = if (orientation == PageOrientation.LANDSCAPE) rawW else rawH

    val marginL = 42f
    val marginR = 42f
    val contentW = pageW - marginL - marginR
    val marginT = 44f
    val marginB = 44f
    val maxY = pageH - marginB

    val brandColor = parseHexColor(brandColorHex, Color.parseColor("#1F497D"))

    // Paints
    val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
      textSize = 10.5f
      color = Color.parseColor("#1F2937")
    }
    val paintBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = 10.5f
      color = Color.parseColor("#111827")
    }
    val paintItalic = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
      textSize = 10.5f
      color = Color.parseColor("#4B5563")
    }
    val paintTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = 22f
      color = brandColor
    }
    val paintSubtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
      textSize = 12f
      color = Color.parseColor("#4B5563")
    }
    val paintH1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = 16f
      color = brandColor
    }
    val paintH2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = 13.5f
      color = Color.parseColor("#1E3A8A")
    }
    val paintH3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = 11.5f
      color = Color.parseColor("#374151")
    }
    val paintCode = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
      textSize = 9f
      color = Color.parseColor("#0F172A")
    }
    val paintHeaderFooter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
      textSize = 8.5f
      color = Color.parseColor("#6B7280")
    }
    val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      strokeWidth = 0.75f
      color = Color.parseColor("#E5E7EB")
    }
    val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
    }

    var pageNum = 1
    var pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
    var currentPage = doc.startPage(pageInfo)
    var canvas: Canvas = currentPage.canvas
    var curY = marginT + 10f

    fun drawHeaderAndFooter(canv: Canvas, pNum: Int) {
      // Header
      val hText = headerText ?: title
      canv.drawText(hText.take(45), marginL, 26f, paintHeaderFooter)
      canv.drawText(date, pageW - marginR - paintHeaderFooter.measureText(date), 26f, paintHeaderFooter)
      canv.drawLine(marginL, 32f, pageW - marginR, 32f, paintLine)

      // Footer
      canv.drawLine(marginL, pageH - 28f, pageW - marginR, pageH - 28f, paintLine)
      val fLeft = footerText ?: "Generated by Aragon"
      canv.drawText(fLeft, marginL, pageH - 16f, paintHeaderFooter)
      if (includePageNumbers) {
        val pStr = "Page $pNum"
        canv.drawText(pStr, pageW - marginR - paintHeaderFooter.measureText(pStr), pageH - 16f, paintHeaderFooter)
      }
    }

    drawHeaderAndFooter(canvas, pageNum)

    fun newPage() {
      doc.finishPage(currentPage)
      pageNum++
      pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
      currentPage = doc.startPage(pageInfo)
      canvas = currentPage.canvas
      drawHeaderAndFooter(canvas, pageNum)
      curY = marginT + 10f
    }

    fun ensureSpace(neededHeight: Float) {
      if (curY + neededHeight > maxY) {
        newPage()
      }
    }

    for (elem in elements) {
      when (elem) {
        is PdfElement.TitleBlock -> {
          ensureSpace(80f)
          canvas.drawText(elem.title, marginL, curY + 20f, paintTitle)
          curY += 28f
          if (!elem.subtitle.isNullOrBlank()) {
            canvas.drawText(elem.subtitle, marginL, curY + 12f, paintSubtitle)
            curY += 18f
          }
          val meta = buildString {
            if (!elem.author.isNullOrBlank()) append("Author: ${elem.author}   ")
            if (!elem.date.isNullOrBlank()) append("Date: ${elem.date}")
          }
          if (meta.isNotBlank()) {
            canvas.drawText(meta, marginL, curY + 10f, paintHeaderFooter)
            curY += 16f
          }
          curY += 8f
          canvas.drawLine(marginL, curY, pageW - marginR, curY, paintLine)
          curY += 14f
        }
        is PdfElement.Heading -> {
          val (hPaint, hHeight, hBefore) = when (elem.level) {
            1 -> Triple(paintH1, 20f, 18f)
            2 -> Triple(paintH2, 17f, 14f)
            else -> Triple(paintH3, 15f, 10f)
          }
          ensureSpace(hHeight + hBefore + 10f)
          curY += hBefore
          if (elem.level == 1) {
            paintFill.color = brandColor
            canvas.drawRect(marginL, curY, marginL + 3.5f, curY + 16f, paintFill)
            canvas.drawText(elem.text, marginL + 8f, curY + 14f, hPaint)
          } else {
            canvas.drawText(elem.text, marginL, curY + 12f, hPaint)
          }
          curY += hHeight + 4f
        }
        is PdfElement.Paragraph -> {
          val pPaint = when {
            elem.isBold -> paintBold
            elem.isItalic -> paintItalic
            elem.colorHex != null -> Paint(paintText).apply { color = parseHexColor(elem.colorHex, Color.BLACK) }
            else -> paintText
          }
          val lines = wrapText(elem.text, contentW, pPaint)
          val lineHeight = 15f
          ensureSpace(lines.size * lineHeight + 8f)
          for (l in lines) {
            if (curY + lineHeight > maxY) newPage()
            canvas.drawText(l, marginL, curY + 11f, pPaint)
            curY += lineHeight
          }
          curY += 6f
        }
        is PdfElement.Callout -> {
          val cBorder = parseHexColor(elem.type.borderColorHex, Color.BLUE)
          val cBg = parseHexColor(elem.type.bgColorHex, Color.parseColor("#EFF6FF"))
          val lines = wrapText(elem.text, contentW - 24f, paintText)
          val boxH = lines.size * 14f + 20f
          ensureSpace(boxH + 10f)
          paintFill.color = cBg
          canvas.drawRoundRect(RectF(marginL, curY, pageW - marginR, curY + boxH), 4f, 4f, paintFill)
          paintFill.color = cBorder
          canvas.drawRect(marginL, curY, marginL + 4f, curY + boxH, paintFill)

          val label = elem.title ?: elem.type.title
          paintBold.color = cBorder
          canvas.drawText("[$label]", marginL + 12f, curY + 13f, paintBold)
          paintBold.color = Color.parseColor("#111827")

          var textY = curY + 13f
          for ((idx, l) in lines.withIndex()) {
            if (idx == 0) {
              val offset = paintBold.measureText("[$label] ") + 4f
              canvas.drawText(l, marginL + 12f + offset, textY, paintText)
            } else {
              textY += 14f
              canvas.drawText(l, marginL + 12f, textY, paintText)
            }
          }
          curY += boxH + 8f
        }
        is PdfElement.Table -> {
          val cols = elem.headers.size.coerceAtLeast(1)
          val colW = contentW / cols.toFloat()
          val rowH = 20f
          val tableH = (elem.rows.size + 1) * rowH + 10f
          ensureSpace(minOf(tableH, 100f))

          // Draw Header
          paintFill.color = brandColor
          canvas.drawRect(marginL, curY, pageW - marginR, curY + rowH, paintFill)
          paintBold.color = Color.WHITE
          for ((cIdx, h) in elem.headers.withIndex()) {
            val cellX = marginL + cIdx * colW + 4f
            canvas.drawText(h.take(25), cellX, curY + 14f, paintBold)
          }
          paintBold.color = Color.parseColor("#111827")
          curY += rowH

          // Draw Rows
          for ((rIdx, r) in elem.rows.withIndex()) {
            if (curY + rowH > maxY) {
              newPage()
              // Redraw header on new page
              paintFill.color = brandColor
              canvas.drawRect(marginL, curY, pageW - marginR, curY + rowH, paintFill)
              paintBold.color = Color.WHITE
              for ((cIdx, h) in elem.headers.withIndex()) {
                val cellX = marginL + cIdx * colW + 4f
                canvas.drawText(h.take(25), cellX, curY + 14f, paintBold)
              }
              paintBold.color = Color.parseColor("#111827")
              curY += rowH
            }
            if (elem.zebra && rIdx % 2 == 1) {
              paintFill.color = Color.parseColor("#F8FAFC")
              canvas.drawRect(marginL, curY, pageW - marginR, curY + rowH, paintFill)
            }
            canvas.drawLine(marginL, curY + rowH, pageW - marginR, curY + rowH, paintLine)
            for (cIdx in 0 until cols) {
              val valText = r.getOrNull(cIdx) ?: ""
              val cellX = marginL + cIdx * colW + 4f
              canvas.drawText(valText.take(28), cellX, curY + 14f, paintText)
            }
            curY += rowH
          }
          curY += 8f
        }
        is PdfElement.ListBlock -> {
          val indent = 16f
          val lineHeight = 15f
          for ((idx, itm) in elem.items.withIndex()) {
            val bullet = if (elem.ordered) "${idx + 1}." else "\u2022"
            val lines = wrapText(itm, contentW - indent, paintText)
            ensureSpace(lines.size * lineHeight + 4f)
            canvas.drawText(bullet, marginL + 2f, curY + 11f, paintBold)
            for ((lIdx, l) in lines.withIndex()) {
              if (curY + lineHeight > maxY) newPage()
              canvas.drawText(l, marginL + indent, curY + 11f, paintText)
              curY += lineHeight
            }
            curY += 3f
          }
          curY += 4f
        }
        is PdfElement.CodeBlock -> {
          val lines = elem.code.lines()
          val lineHeight = 13f
          val boxH = lines.size * lineHeight + 14f
          ensureSpace(minOf(boxH, 80f))
          paintFill.color = Color.parseColor("#F1F5F9")
          canvas.drawRoundRect(RectF(marginL, curY, pageW - marginR, curY + boxH), 3f, 3f, paintFill)
          var codeY = curY + 11f
          for (l in lines) {
            if (codeY + lineHeight > maxY) {
              curY = codeY
              newPage()
              codeY = curY + 11f
            }
            canvas.drawText(l.take(90), marginL + 8f, codeY, paintCode)
            codeY += lineHeight
          }
          curY += boxH + 8f
        }
        PdfElement.HorizontalRule -> {
          ensureSpace(12f)
          curY += 4f
          canvas.drawLine(marginL, curY, pageW - marginR, curY, paintLine)
          curY += 8f
        }
        PdfElement.PageBreak -> {
          newPage()
        }
      }
    }

    doc.finishPage(currentPage)
    FileOutputStream(targetFile).use { fos ->
      doc.writeTo(fos)
    }
    doc.close()
  }

  private fun wrapText(text: String, maxWidth: Float, paint: Paint): List<String> {
    if (text.isBlank()) return listOf("")
    val lines = mutableListOf<String>()
    val paragraphs = text.split("\n")
    for (p in paragraphs) {
      if (p.isBlank()) {
        lines.add("")
        continue
      }
      val words = p.split(Regex("\\s+"))
      var curLine = StringBuilder()
      for (w in words) {
        val test = if (curLine.isEmpty()) w else "${curLine} $w"
        if (paint.measureText(test) <= maxWidth) {
          curLine.append(if (curLine.isEmpty()) w else " $w")
        } else {
          if (curLine.isNotEmpty()) lines.add(curLine.toString())
          curLine = StringBuilder(w)
        }
      }
      if (curLine.isNotEmpty()) lines.add(curLine.toString())
    }
    return lines
  }

  private fun parseHexColor(hex: String, fallback: Int): Int {
    return try {
      val clean = hex.removePrefix("#").trim()
      Color.parseColor("#$clean")
    } catch (_: Exception) {
      fallback
    }
  }
}
