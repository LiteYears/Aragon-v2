package com.example.agent.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * First-class agent tool to build professional, verified Microsoft Excel (.xlsx) spreadsheets.
 * Employs XlsxBuilder with pure Kotlin/OpenXML (zero external dependencies).
 * Supports full Microsoft Excel feature parity: multiple sheets, formulas, currency/percentage
 * formatting, total/summary rows with double bottom borders, interactive auto-filters,
 * freeze panes, zebra striping, auto-fitting column widths, and direct CSV/Markdown conversion.
 */
class BuildExcelSpreadsheetTool(
  private val workspaceDir: File,
  private val artifactManager: ArtifactManager
) : Tool {

  override val name: String = "build_excel_spreadsheet"
  override val description: String =
    "Creates a verified, native Microsoft Excel (.xlsx) workbook with full Microsoft Excel feature parity. Supports multiple sheets, styled headers, auto-typed numerical/currency/percentage cells, formula cells (=SUM, =AVERAGE, etc.), total rows with double bottom borders, interactive auto-filters, freeze panes, zebra striping, and direct Markdown table or CSV conversion."

  override val category: ToolCategory = ToolCategory.FILESYSTEM

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Workspace-relative output file path ending in .xlsx (e.g., 'financials/budget_2025.xlsx').",
        required = true
      ),
      ToolParameter(
        name = "sheet_name",
        type = "string",
        description = "Name for the primary sheet (default: 'Sheet1').",
        required = false,
        defaultValue = "Sheet1"
      ),
      ToolParameter(
        name = "headers",
        type = "string",
        description = "JSON array of column header titles, e.g. [\"Item\", \"Category\", \"Cost\", \"Date\"].",
        required = false
      ),
      ToolParameter(
        name = "rows",
        type = "string",
        description = "JSON array of row arrays containing cell values, e.g. [[\"Widget\", \"Supplies\", 14.50, \"2025-01-15\"], [\"Server\", \"Cloud\", 250.0, \"2025-01-16\"]].",
        required = false
      ),
      ToolParameter(
        name = "total_row",
        type = "string",
        description = "Optional array of summary/total values or formulas (e.g. [\"Total\", \"\", \"=SUM(C2:C10)\"]). Formats with bold text and double bottom border.",
        required = false
      ),
      ToolParameter(
        name = "sheets_json",
        type = "string",
        description = "Optional JSON array for multi-sheet workbooks. Each object has 'name', optional 'headers', 'rows', 'total_row', 'theme', 'freeze_header', and 'rtl'.",
        required = false
      ),
      ToolParameter(
        name = "csv_data",
        type = "string",
        description = "Optional raw CSV or TSV string to compile directly into an Excel workbook.",
        required = false
      ),
      ToolParameter(
        name = "markdown_table",
        type = "string",
        description = "Optional Markdown table string to compile directly into an Excel workbook.",
        required = false
      ),
      ToolParameter(
        name = "theme",
        type = "string",
        description = "Visual theme palette for headers: 'navy' (default), 'emerald', 'slate', or 'modern'.",
        required = false,
        defaultValue = "navy"
      ),
      ToolParameter(
        name = "auto_filter",
        type = "boolean",
        description = "Whether to add interactive column filter drop-downs on header row (default: true).",
        required = false,
        defaultValue = true
      ),
      ToolParameter(
        name = "zebra_striping",
        type = "boolean",
        description = "Whether to apply alternating light gray row shading for high readability (default: true).",
        required = false,
        defaultValue = true
      ),
      ToolParameter(
        name = "auto_totals",
        type = "boolean",
        description = "Whether to automatically compute and append a summary/total row with =SUM() formulas for all numeric columns (default: false).",
        required = false,
        defaultValue = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
    val startTime = System.currentTimeMillis()
    val pathArg = (arguments["path"] as? String)?.trim().orEmpty()
    if (pathArg.isEmpty()) {
      return invalidArg(callId, arguments, startTime, "Parameter 'path' is required.")
    }
    val cleanPath = if (pathArg.endsWith(".xlsx", ignoreCase = true)) pathArg else "$pathArg.xlsx"
    val targetFile = PathSecurity.resolveSafe(workspaceDir, cleanPath)

    val primarySheetName = (arguments["sheet_name"] as? String)?.trim().takeUnless { it.isNullOrBlank() } ?: "Sheet1"
    val sheetsJsonArg = arguments["sheets_json"]
    val csvArg = (arguments["csv_data"] as? String)?.trim()
    val mdTableArg = (arguments["markdown_table"] as? String)?.trim()
    val themeStr = (arguments["theme"] as? String)?.trim()?.lowercase() ?: "navy"
    val theme = when (themeStr) {
      "emerald", "green" -> XlsxBuilder.Theme.EMERALD
      "slate", "gray" -> XlsxBuilder.Theme.SLATE
      "modern", "blue" -> XlsxBuilder.Theme.MODERN
      "crimson", "red" -> XlsxBuilder.Theme.CRIMSON
      "indigo", "purple" -> XlsxBuilder.Theme.INDIGO
      "gold", "yellow", "amber" -> XlsxBuilder.Theme.GOLD
      "charcoal", "dark", "black" -> XlsxBuilder.Theme.CHARCOAL
      else -> XlsxBuilder.Theme.NAVY
    }
    val autoFilterArg = (arguments["auto_filter"] as? Boolean) ?: true
    val zebraArg = (arguments["zebra_striping"] as? Boolean) ?: true
    val autoTotalsArg = (arguments["auto_totals"] as? Boolean) ?: false

    try {
      val builder: XlsxBuilder
      var totalRows = 0
      var sheetsCount = 0

      // Priority 1: Multi-sheet JSON configuration
      if (sheetsJsonArg != null && sheetsJsonArg.toString().isNotBlank()) {
        val sheetsArr = when (sheetsJsonArg) {
          is String -> JSONArray(sheetsJsonArg.trim())
          is List<*> -> JSONArray(sheetsJsonArg)
          else -> return invalidArg(callId, arguments, startTime, "Parameter 'sheets_json' must be a valid JSON array.")
        }
        val firstSheetObj = sheetsArr.optJSONObject(0)
        val firstSheetName = firstSheetObj?.optString("name", "Sheet1") ?: "Sheet1"
        builder = XlsxBuilder(firstSheetName)
        builder.current.theme = theme
        builder.current.autoFilter = autoFilterArg
        builder.current.zebraStriping = zebraArg
        sheetsCount++

        for (i in 0 until sheetsArr.length()) {
          val sheetObj = sheetsArr.optJSONObject(i) ?: continue
          val sName = sheetObj.optString("name", "Sheet${i + 1}")
          if (i > 0) {
            builder.addSheet(sName, theme = theme)
            sheetsCount++
          }
          if (sheetObj.optBoolean("rtl", false)) {
            builder.setCurrentSheetRtl(true)
          }
          if (sheetObj.optBoolean("freeze_header", true)) {
            builder.freezeTopRow()
          }
          if (sheetObj.has("auto_filter")) {
            builder.setAutoFilter(sheetObj.optBoolean("auto_filter", true))
          }
          if (sheetObj.has("tab_color")) {
            builder.setTabColor(sheetObj.optString("tab_color"))
          }

          // Headers
          sheetObj.optJSONArray("headers")?.let { hArr ->
            val headers = (0 until hArr.length()).map { hArr.optString(it) }
            if (headers.isNotEmpty()) {
              builder.addHeader(headers)
              totalRows++
            }
          }

          // Rows
          sheetObj.optJSONArray("rows")?.let { rArr ->
            for (r in 0 until rArr.length()) {
              val rowArr = rArr.optJSONArray(r)
              if (rowArr != null) {
                val cells = (0 until rowArr.length()).map { rowArr.optString(it) }
                builder.addRow(cells)
                totalRows++
              }
            }
          }

          // Total row
          sheetObj.optJSONArray("total_row")?.let { totArr ->
            val totCells = (0 until totArr.length()).map { totArr.optString(it) }
            if (totCells.isNotEmpty()) {
              builder.addTotalRow(totCells)
              totalRows++
            }
          }
        }
      } else {
        builder = XlsxBuilder(primarySheetName)
        builder.current.theme = theme
        builder.current.autoFilter = autoFilterArg
        builder.current.zebraStriping = zebraArg
        builder.freezeTopRow()
        sheetsCount = 1

        if (!mdTableArg.isNullOrBlank()) {
          builder.addMarkdownTable(mdTableArg)
          totalRows += mdTableArg.lines().size
        } else if (!csvArg.isNullOrBlank()) {
          val delimiter = if (csvArg.contains("\t") && !csvArg.contains(",")) '\t' else ','
          builder.addCsv(csvArg, delimiter)
          totalRows += csvArg.lines().size
        } else {
          val headersArg = arguments["headers"]
          if (headersArg != null) {
            val headersList = parseStringList(headersArg)
            if (headersList.isNotEmpty()) {
              builder.addHeader(headersList)
              totalRows++
            }
          }

          val rowsArg = arguments["rows"]
          if (rowsArg != null) {
            val rowsList = parseListOfLists(rowsArg)
            for (row in rowsList) {
              builder.addRow(row)
              totalRows++
            }
          }

          val totalRowArg = arguments["total_row"]
          if (totalRowArg != null) {
            val totalList = parseStringList(totalRowArg)
            if (totalList.isNotEmpty()) {
              builder.addTotalRow(totalList)
              totalRows++
            }
          } else if (autoTotalsArg) {
            builder.addAutoTotalRow()
            totalRows++
          }
        }
      }

      builder.autoFitColumnWidths()
      builder.save(targetFile)
      val relPath = PathSecurity.getRelativePath(workspaceDir, targetFile)
      artifactManager.registerArtifact(relPath, createdByCallId = callId)

      val sizeBytes = targetFile.length()
      val msg = "Successfully created Microsoft Excel spreadsheet '$relPath' ($sizeBytes bytes, $sheetsCount sheets, $totalRows rows written, theme: ${theme.name.lowercase()})."
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
        error = "Failed to build Excel spreadsheet: ${e.message ?: "unknown error"}",
        errorType = ToolErrorCode.UNKNOWN_ERROR,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1
      )
    }
  }

  private fun parseStringList(raw: Any): List<String> {
    return when (raw) {
      is List<*> -> raw.mapNotNull { it?.toString() }
      is String -> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
          val arr = JSONArray(trimmed)
          (0 until arr.length()).map { arr.optString(it) }
        } else {
          trimmed.split(",").map { it.trim() }
        }
      }
      else -> emptyList()
    }
  }

  private fun parseListOfLists(raw: Any): List<List<String>> {
    return when (raw) {
      is List<*> -> raw.mapNotNull { row ->
        when (row) {
          is List<*> -> row.mapNotNull { it?.toString() }
          else -> null
        }
      }
      is String -> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
          val arr = JSONArray(trimmed)
          val result = mutableListOf<List<String>>()
          for (i in 0 until arr.length()) {
            val rowArr = arr.optJSONArray(i)
            if (rowArr != null) {
              result.add((0 until rowArr.length()).map { rowArr.optString(it) })
            }
          }
          result
        } else {
          emptyList()
        }
      }
      else -> emptyList()
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
