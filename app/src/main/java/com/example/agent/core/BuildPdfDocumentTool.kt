package com.example.agent.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * First-class agent tool to build professional, verified native PDF (.pdf) documents.
 * Employs pure Kotlin Android PdfBuilder with zero external PDF services.
 * Supports headings H1-H3, rich paragraphs, callout boxes, tables with zebra striping,
 * bullet/numbered lists, code blocks, running headers/footers, dynamic page numbers,
 * and direct Markdown-to-PDF compilation.
 */
class BuildPdfDocumentTool(
  private val workspaceDir: File,
  private val artifactManager: ArtifactManager
) : Tool {

  override val name: String = "build_pdf_document"
  override val description: String =
    "Creates a verified, native PDF (.pdf) document. Accepts either a Markdown document string ('markdown') or structured sections ('sections'). Supports headings H1-H3, styled tables with zebra striping, callout boxes, bullet/numbered lists, running headers and footers with page numbers, and custom accent colors."

  override val category: ToolCategory = ToolCategory.FILESYSTEM

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Workspace-relative output file path ending in .pdf (e.g., 'reports/summary.pdf').",
        required = true
      ),
      ToolParameter(
        name = "title",
        type = "string",
        description = "Title of the document (appears on the cover/header).",
        required = false
      ),
      ToolParameter(
        name = "markdown",
        type = "string",
        description = "Optional Markdown report content to compile directly into a styled PDF.",
        required = false
      ),
      ToolParameter(
        name = "author",
        type = "string",
        description = "Author name for document metadata and header (default: 'Aragon Agent').",
        required = false
      ),
      ToolParameter(
        name = "date",
        type = "string",
        description = "Date string (e.g., 'October 16, 2026' or 'today' for the current date).",
        required = false
      ),
      ToolParameter(
        name = "theme_color",
        type = "string",
        description = "Hex color for headings and table headers (e.g., '1F497D' for Navy, '0F766E' for Emerald).",
        required = false
      ),
      ToolParameter(
        name = "page_size",
        type = "string",
        description = "Page size: 'a4' (default) or 'letter'.",
        required = false,
        defaultValue = "a4"
      ),
      ToolParameter(
        name = "orientation",
        type = "string",
        description = "Page orientation: 'portrait' (default) or 'landscape'.",
        required = false,
        defaultValue = "portrait"
      ),
      ToolParameter(
        name = "sections",
        type = "string",
        description = "Optional JSON array of document sections with 'type' ('title', 'heading', 'paragraph', 'table', 'callout', 'list', 'code', 'page_break').",
        required = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
    val startTime = System.currentTimeMillis()
    val pathArg = (arguments["path"] as? String)?.trim().orEmpty()
    if (pathArg.isEmpty()) {
      return invalidArg(callId, arguments, startTime, "Parameter 'path' is required.")
    }
    val cleanPath = if (pathArg.endsWith(".pdf", ignoreCase = true)) pathArg else "$pathArg.pdf"
    val targetFile = PathSecurity.resolveSafe(workspaceDir, cleanPath)

    val titleArg = (arguments["title"] as? String)?.trim() ?: "Document"
    val markdownArg = (arguments["markdown"] as? String)?.trim()
    val authorArg = (arguments["author"] as? String)?.trim() ?: "Aragon Agent"
    val rawDate = (arguments["date"] as? String)?.trim()
    val resolvedDate = when {
      rawDate == null || rawDate.equals("today", true) || rawDate.equals("current", true) || rawDate.equals("now", true) ->
        LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
      else -> rawDate
    }
    val themeColor = (arguments["theme_color"] as? String)?.trim() ?: "1F497D"
    val pageSizeStr = (arguments["page_size"] as? String)?.trim()?.lowercase() ?: "a4"
    val orientationStr = (arguments["orientation"] as? String)?.trim()?.lowercase() ?: "portrait"
    val sectionsArg = arguments["sections"]

    try {
      val builder = PdfBuilder(
        title = titleArg,
        author = authorArg,
        date = resolvedDate,
        brandColorHex = themeColor
      )

      builder.pageSize = if (pageSizeStr == "letter") PdfBuilder.PageSize.LETTER else PdfBuilder.PageSize.A4
      builder.orientation = if (orientationStr == "landscape") PdfBuilder.PageOrientation.LANDSCAPE else PdfBuilder.PageOrientation.PORTRAIT

      var itemsAdded = 0

      if (titleArg.isNotBlank() && titleArg != "Document") {
        builder.addTitle(titleArg, author = authorArg, date = resolvedDate)
        itemsAdded++
      }

      if (!markdownArg.isNullOrBlank()) {
        builder.addMarkdown(markdownArg)
        itemsAdded += markdownArg.lines().size
      }

      if (sectionsArg != null) {
        val sectionsJson = when (sectionsArg) {
          is String -> if (sectionsArg.trim().startsWith("[")) JSONArray(sectionsArg.trim()) else null
          is List<*> -> JSONArray(sectionsArg)
          else -> null
        }
        if (sectionsJson != null) {
          for (i in 0 until sectionsJson.length()) {
            val item = sectionsJson.optJSONObject(i) ?: continue
            val type = item.optString("type", "paragraph").lowercase()
            when (type) {
              "title" -> {
                val t = item.optString("text", titleArg)
                val sub = item.optString("subtitle", "").takeIf { it.isNotBlank() }
                builder.addTitle(t, subtitle = sub, author = authorArg, date = resolvedDate)
                itemsAdded++
              }
              "heading", "header" -> {
                val t = item.optString("text", "")
                val lvl = item.optInt("level", 1)
                if (t.isNotBlank()) {
                  builder.addHeading(t, lvl)
                  itemsAdded++
                }
              }
              "paragraph", "p", "text" -> {
                val t = item.optString("text", "")
                val bold = item.optBoolean("bold", false)
                val italic = item.optBoolean("italic", false)
                val color = item.optString("color", "").takeIf { it.isNotBlank() }
                if (t.isNotBlank()) {
                  builder.addParagraph(t, isBold = bold, isItalic = italic, colorHex = color)
                  itemsAdded++
                }
              }
              "callout", "note", "alert", "warning" -> {
                val t = item.optString("text", "")
                val cTitle = item.optString("title", "").takeIf { it.isNotBlank() }
                val cType = when (item.optString("callout_type", type).uppercase()) {
                  "TIP" -> PdfBuilder.CalloutType.TIP
                  "WARNING" -> PdfBuilder.CalloutType.WARNING
                  "DANGER", "IMPORTANT" -> PdfBuilder.CalloutType.DANGER
                  "SUCCESS" -> PdfBuilder.CalloutType.SUCCESS
                  else -> PdfBuilder.CalloutType.NOTE
                }
                if (t.isNotBlank()) {
                  builder.addCallout(t, title = cTitle, type = cType)
                  itemsAdded++
                }
              }
              "table" -> {
                val headersArr = item.optJSONArray("headers")
                val rowsArr = item.optJSONArray("rows")
                val headers = mutableListOf<String>()
                if (headersArr != null) {
                  for (j in 0 until headersArr.length()) headers.add(headersArr.optString(j))
                }
                val rows = mutableListOf<List<String>>()
                if (rowsArr != null) {
                  for (j in 0 until rowsArr.length()) {
                    val rowArr = rowsArr.optJSONArray(j)
                    if (rowArr != null) {
                      rows.add((0 until rowArr.length()).map { rowArr.optString(it) })
                    }
                  }
                }
                if (headers.isNotEmpty() || rows.isNotEmpty()) {
                  builder.addTable(headers, rows)
                  itemsAdded++
                }
              }
              "list" -> {
                val itemsArr = item.optJSONArray("items")
                val ordered = item.optBoolean("ordered", false)
                val list = mutableListOf<String>()
                if (itemsArr != null) {
                  for (j in 0 until itemsArr.length()) list.add(itemsArr.optString(j))
                }
                if (list.isNotEmpty()) {
                  builder.addList(list, ordered)
                  itemsAdded += list.size
                }
              }
              "code" -> {
                val c = item.optString("code", item.optString("text", ""))
                if (c.isNotBlank()) {
                  builder.addCodeBlock(c)
                  itemsAdded++
                }
              }
              "page_break" -> {
                builder.addPageBreak()
                itemsAdded++
              }
            }
          }
        }
      }

      builder.save(targetFile)
      val relPath = PathSecurity.getRelativePath(workspaceDir, targetFile)
      artifactManager.registerArtifact(relPath, createdByCallId = callId)

      val sizeBytes = targetFile.length()
      val msg = "Successfully created native PDF document '$relPath' ($sizeBytes bytes, $itemsAdded elements, date: $resolvedDate, orientation: ${builder.orientation.name.lowercase()})."
      return ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = msg,
        error = null,
        duration = System.currentTimeMillis() - startTime,
        stdout = msg,
        exitCode = 0
      )
    } catch (e: Exception) {
      return ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.FAILED,
        arguments = arguments,
        output = null,
        error = "Failed to build PDF document: ${e.message ?: "unknown error"}",
        errorType = ToolErrorCode.UNKNOWN_ERROR,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1
      )
    }
  }

  private fun invalidArg(callId: String, arguments: Map<String, Any?>, startTime: Long, msg: String): ToolResult =
    ToolResult(
      callId = callId,
      toolName = name,
      status = ToolStatus.FAILED,
      arguments = arguments,
      output = null,
      error = msg,
      errorType = ToolErrorCode.INVALID_ARGUMENTS,
      duration = System.currentTimeMillis() - startTime,
      exitCode = 1
    )
}
