package com.example.agent.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * First-class agent tool to build professional, verified Microsoft Word (.docx) documents.
 * Employs DocxBuilder with pure Kotlin/OpenXML (zero external dependencies).
 * Supports full Microsoft Word feature parity: headings, rich in-line styling, callout boxes,
 * blockquotes, code blocks, bullet/numbered lists, multi-column tables, embedded images,
 * page orientations, headers/footers, dynamic page numbers, and direct Markdown conversion.
 */
class BuildWordDocumentTool(
  private val workspaceDir: File,
  private val artifactManager: ArtifactManager
) : Tool {

  override val name: String = "build_word_document"
  override val description: String =
    "Creates a verified, native Microsoft Word (.docx) file with full Microsoft Word feature parity. Accepts either a Markdown document string ('markdown') or a structured JSON array of sections ('sections'). Supports headings H1-H6, rich text styling, callout alert boxes, blockquotes, code blocks, lists, styled tables with zebra striping, embedded images, headers/footers, dynamic page numbers, and RTL Arabic formatting."

  override val category: ToolCategory = ToolCategory.FILESYSTEM

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Workspace-relative output file path ending in .docx (e.g., 'reports/market_analysis.docx').",
        required = true
      ),
      ToolParameter(
        name = "title",
        type = "string",
        description = "Title of the document (used in document properties, running header, and top title).",
        required = false
      ),
      ToolParameter(
        name = "markdown",
        type = "string",
        description = "Optional complete Markdown report content. When provided, automatically compiles Markdown headings, tables, lists, callouts, and text into authentic OpenXML elements.",
        required = false
      ),
      ToolParameter(
        name = "sections",
        type = "string",
        description = "Optional JSON array of document sections. Each item has a 'type' ('heading', 'paragraph', 'callout', 'blockquote', 'code', 'list', 'table', 'image', 'page_break', 'hr') and corresponding properties.",
        required = false
      ),
      ToolParameter(
        name = "orientation",
        type = "string",
        description = "Page orientation: 'portrait' (default) or 'landscape'.",
        required = false,
        defaultValue = "portrait"
      ),
      ToolParameter(
        name = "margins",
        type = "string",
        description = "Page margin preset: 'normal' (1-inch, default), 'narrow' (0.5-inch), or 'wide'.",
        required = false,
        defaultValue = "normal"
      ),
      ToolParameter(
        name = "theme_color",
        type = "string",
        description = "Hex accent color for headings, callouts, and borders (e.g., '1F497D' for Navy, '0D9488' for Teal).",
        required = false
      ),
      ToolParameter(
        name = "author",
        type = "string",
        description = "Author name for document properties (default: 'Aragon Agent').",
        required = false
      ),
      ToolParameter(
        name = "header_text",
        type = "string",
        description = "Running header text displayed at the top of each page.",
        required = false
      ),
      ToolParameter(
        name = "footer_text",
        type = "string",
        description = "Running footer text displayed at the bottom of each page.",
        required = false
      ),
      ToolParameter(
        name = "include_page_numbers",
        type = "boolean",
        description = "Whether to include dynamic 'Page X of Y' numbers in the footer (default: true).",
        required = false,
        defaultValue = true
      ),
      ToolParameter(
        name = "date",
        type = "string",
        description = "Document date. Use 'today' or 'current' for the current date.",
        required = false
      ),
      ToolParameter(
        name = "cover_page",
        type = "boolean",
        description = "Whether to generate a styled executive cover page with title, subtitle, author, and date.",
        required = false,
        defaultValue = false
      ),
      ToolParameter(
        name = "source_docx",
        type = "string",
        description = "Optional path to an existing Word document to replicate/modify (e.g., 'input.docx').",
        required = false
      ),
      ToolParameter(
        name = "replacements",
        type = "string",
        description = "Optional JSON map of text replacements (e.g., {\"Old Title\": \"New Title\"}).",
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
    val cleanPath = if (pathArg.endsWith(".docx", ignoreCase = true)) pathArg else "$pathArg.docx"
    val targetFile = PathSecurity.resolveSafe(workspaceDir, cleanPath)

    val titleArg = (arguments["title"] as? String)?.trim()
    val markdownArg = (arguments["markdown"] as? String)?.trim()
    val sectionsArg = arguments["sections"]
    val orientationArg = (arguments["orientation"] as? String)?.trim()?.lowercase() ?: "portrait"
    val marginsArg = (arguments["margins"] as? String)?.trim()?.lowercase() ?: "normal"
    val themeColorArg = (arguments["theme_color"] as? String)?.trim()
    val authorArg = (arguments["author"] as? String)?.trim() ?: "Aragon Agent"
    val headerArg = (arguments["header_text"] as? String)?.trim()
    val footerArg = (arguments["footer_text"] as? String)?.trim()
    val pageNumArg = (arguments["include_page_numbers"] as? Boolean) ?: true
    val coverPageArg = (arguments["cover_page"] as? Boolean) ?: false
    val rawDate = (arguments["date"] as? String)?.trim()
    val resolvedDate = when {
      rawDate == null -> null
      rawDate.equals("today", true) || rawDate.equals("current", true) || rawDate.equals("now", true) ->
        java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.US))
      else -> rawDate
    }
    val sourceDocxArg = (arguments["source_docx"] as? String)?.trim()

    // Fast replication if source_docx is provided
    if (!sourceDocxArg.isNullOrBlank()) {
      val srcFile = PathSecurity.resolveSafe(workspaceDir, sourceDocxArg)
      if (srcFile.exists()) {
        val repMap = mutableMapOf<String, String>()
        arguments["replacements"]?.let { raw ->
          if (raw is String && raw.trim().startsWith("{")) {
            try {
              val obj = JSONObject(raw.trim())
              obj.keys().forEach { k -> repMap[k] = obj.optString(k) }
            } catch (_: Exception) {}
          }
        }
        val ok = DocxBuilder.modifyDocx(srcFile, targetFile, newTitle = titleArg, newAuthor = authorArg, newDate = resolvedDate, replacements = repMap)
        if (ok) {
          val relPath = PathSecurity.getRelativePath(workspaceDir, targetFile)
          artifactManager.registerArtifact(relPath, createdByCallId = callId)
          val msg = "Successfully replicated and modified Word document '$relPath' from '$sourceDocxArg' with updated metadata (Title: $titleArg, Author: $authorArg, Date: $resolvedDate)."
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
        }
      }
    }

    try {
      val builder = DocxBuilder()
      builder.setCoreProperties(title = titleArg, creator = authorArg)

      val orientation = if (orientationArg == "landscape") DocxBuilder.PageOrientation.LANDSCAPE else DocxBuilder.PageOrientation.PORTRAIT
      val margins = when (marginsArg) {
        "narrow" -> DocxBuilder.MarginPreset.NARROW
        "wide" -> DocxBuilder.MarginPreset.WIDE
        else -> DocxBuilder.MarginPreset.NORMAL
      }

      builder.setPageSettings(
        orientation = orientation,
        margins = margins,
        header = headerArg ?: titleArg,
        footer = footerArg,
        pageNumbers = pageNumArg,
        themeColor = themeColorArg
      )

      var itemsAdded = 0

      if (coverPageArg && !titleArg.isNullOrBlank()) {
        builder.addCoverPage(titleArg, author = authorArg, date = resolvedDate ?: java.time.LocalDate.now().toString())
        itemsAdded++
      } else if (!titleArg.isNullOrBlank()) {
        builder.addTitle(titleArg, subtitle = resolvedDate?.let { "Date: $it" })
        itemsAdded++
      }

      // Priority 1: Compile direct Markdown if supplied
      if (!markdownArg.isNullOrBlank()) {
        builder.addMarkdown(markdownArg)
        itemsAdded += markdownArg.lines().size
      }

      // Priority 2: Parse structured sections JSON
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
                val text = item.optString("text", "")
                val subtitle = item.optString("subtitle", "").takeIf { it.isNotBlank() }
                if (text.isNotBlank()) {
                  builder.addTitle(text, subtitle)
                  itemsAdded++
                }
              }
              "heading", "header" -> {
                val text = item.optString("text", "")
                val level = item.optInt("level", 1)
                if (text.isNotBlank()) {
                  builder.addHeading(text, level = level)
                  itemsAdded++
                }
              }
              "paragraph", "p", "text" -> {
                val text = item.optString("text", "")
                val bold = item.optBoolean("bold", false)
                val italic = item.optBoolean("italic", false)
                val underline = item.optBoolean("underline", false)
                val color = item.optString("color", "").takeIf { it.isNotBlank() }
                val alignStr = item.optString("align", "left").lowercase()
                val align = when (alignStr) {
                  "center" -> DocxBuilder.TextAlignment.CENTER
                  "right" -> DocxBuilder.TextAlignment.RIGHT
                  "justify", "both" -> DocxBuilder.TextAlignment.JUSTIFY
                  else -> DocxBuilder.TextAlignment.LEFT
                }
                if (text.isNotBlank()) {
                  builder.addParagraph(text, isBold = bold, isItalic = italic, isUnderline = underline, alignment = align, colorHex = color)
                  itemsAdded++
                }
              }
              "callout", "note", "alert", "tip", "warning" -> {
                val text = item.optString("text", "")
                val calloutTitle = item.optString("title", "").takeIf { it.isNotBlank() }
                val cTypeStr = item.optString("callout_type", type).uppercase()
                val cType = when (cTypeStr) {
                  "TIP" -> DocxBuilder.CalloutType.TIP
                  "WARNING" -> DocxBuilder.CalloutType.WARNING
                  "DANGER", "IMPORTANT", "CAUTION" -> DocxBuilder.CalloutType.DANGER
                  "SUCCESS" -> DocxBuilder.CalloutType.SUCCESS
                  else -> DocxBuilder.CalloutType.NOTE
                }
                if (text.isNotBlank()) {
                  builder.addCallout(text, title = calloutTitle, type = cType)
                  itemsAdded++
                }
              }
              "blockquote", "quote" -> {
                val text = item.optString("text", "")
                val citation = item.optString("citation", "").takeIf { it.isNotBlank() }
                if (text.isNotBlank()) {
                  builder.addBlockquote(text, citation = citation)
                  itemsAdded++
                }
              }
              "code", "code_block" -> {
                val code = item.optString("code", item.optString("text", ""))
                val lang = item.optString("language", "").takeIf { it.isNotBlank() }
                if (code.isNotBlank()) {
                  builder.addCodeBlock(code, language = lang)
                  itemsAdded++
                }
              }
              "list", "bullet_list", "numbered_list" -> {
                val ordered = item.optBoolean("ordered", type == "numbered_list")
                val level = item.optInt("level", 0)
                val itemsArr = item.optJSONArray("items")
                val listItems = mutableListOf<String>()
                if (itemsArr != null) {
                  for (j in 0 until itemsArr.length()) {
                    val s = itemsArr.optString(j)
                    if (s.isNotBlank()) listItems.add(s)
                  }
                }
                if (listItems.isNotEmpty()) {
                  builder.addList(listItems, ordered = ordered, level = level)
                  itemsAdded += listItems.size
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
                      val row = (0 until rowArr.length()).map { rowArr.optString(it) }
                      rows.add(row)
                    }
                  }
                }
                val zebra = item.optBoolean("zebra_striping", true)
                if (headers.isNotEmpty() || rows.isNotEmpty()) {
                  builder.addTable(headers, rows, zebraStriping = zebra)
                  itemsAdded++
                }
              }
              "image", "picture" -> {
                val imgPath = item.optString("path", item.optString("src", "")).trim()
                val widthInches = item.optDouble("width_inches", 5.5)
                val caption = item.optString("caption", "").takeIf { it.isNotBlank() }
                if (imgPath.isNotBlank()) {
                  val imgFile = PathSecurity.resolveSafe(workspaceDir, imgPath)
                  if (imgFile.exists()) {
                    builder.addImage(imgFile, widthInches = widthInches, caption = caption)
                    itemsAdded++
                  }
                }
              }
              "hr", "divider", "horizontal_rule" -> {
                builder.addHorizontalRule()
                itemsAdded++
              }
              "page_break", "pagebreak" -> {
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
      val msg = "Successfully created Microsoft Word document '$relPath' ($sizeBytes bytes, $itemsAdded elements written, orientation: ${orientation.name.lowercase()})."
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
        error = "Failed to build Word document: ${e.message ?: "unknown error"}",
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
