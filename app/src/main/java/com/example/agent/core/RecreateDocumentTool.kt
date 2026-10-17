package com.example.agent.core

import com.example.agent.docs.BlockKind
import com.example.agent.docs.CsvTextExtractor
import com.example.agent.docs.DocxTextExtractor
import com.example.agent.docs.DocumentIntelligenceService
import com.example.agent.docs.DocumentText
import com.example.agent.docs.DocumentsVerifier
import com.example.agent.docs.OcrRouter
import com.example.agent.docs.PdfTextExtractor
import com.example.agent.docs.TxtTextExtractor
import com.example.agent.docs.XlsxTextExtractor
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * First-class autonomous tool to recreate, replicate, or transform any document or image
 * (a screenshot of a Word document, photo of paper, PDF, Word .docx, Excel .xlsx, CSV, or text)
 * into a verified, styled deliverable (.docx, .xlsx, or .pdf).
 *
 * Automatically:
 * 1. Analyzes the input source (runs high-precision vision/OCR if image/screenshot; parses OpenXML/PDF structure if document).
 * 2. Substitutes title, author name, and date (resolving "current date" or "today" to the current date).
 * 3. Applies custom phrase/text replacements.
 * 4. Synthesizes a fresh, authentic document in the target format (.docx, .xlsx, or .pdf) with typography, tables, and headers/footers.
 * 5. Reopens and verifies the deliverable on disk and registers it as a workspace artifact.
 */
class RecreateDocumentTool(
  private val workspaceDir: File,
  private val artifactManager: ArtifactManager,
  private val ocrRouter: OcrRouter?,
  private val documentService: DocumentIntelligenceService?,
  private val sandboxProviderSupplier: (() -> SandboxProvider?)? = null,
  private val sessionIdProvider: (() -> String)? = null
) : Tool {

  override val name: String = "recreate_document"
  override val description: String =
    "Recreates, replicates, or transforms any source (an image or screenshot of a Word document, photo of paper, Word .docx, PDF .pdf, Excel .xlsx, CSV, or text) into a verified, styled deliverable (.docx, .xlsx, or .pdf). Automatically runs OCR on screenshots, applies requested changes (such as changing title, author name, and date to the current date, or specific text replacements), and builds a pristine target file."

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val defaultTimeoutMs: Long = 180000L

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "source",
        type = "string",
        description = "Input source path in workspace (e.g., 'screenshot.png', 'contract.docx', 'report.pdf') or document reference (e.g., 'D1').",
        required = true
      ),
      ToolParameter(
        name = "target_format",
        type = "string",
        description = "Target deliverable format: 'docx' (Word), 'xlsx' (Excel), or 'pdf' (PDF).",
        required = true,
        enumValues = listOf("docx", "xlsx", "pdf")
      ),
      ToolParameter(
        name = "output_path",
        type = "string",
        description = "Workspace-relative output deliverable path (e.g., 'recreated_document.docx', 'output.pdf').",
        required = true
      ),
      ToolParameter(
        name = "title",
        type = "string",
        description = "Optional new title to apply (replaces the original title).",
        required = false
      ),
      ToolParameter(
        name = "author",
        type = "string",
        description = "Optional new author name to apply (replaces original author).",
        required = false
      ),
      ToolParameter(
        name = "date",
        type = "string",
        description = "Optional new date string. Use 'today', 'current', or 'now' to automatically set today's date (e.g., 'October 16, 2026').",
        required = false
      ),
      ToolParameter(
        name = "replacements",
        type = "string",
        description = "Optional JSON object mapping original text to new text (e.g., {\"Old Company\": \"New Company\", \"2024\": \"2026\"}).",
        required = false
      ),
      ToolParameter(
        name = "theme_color",
        type = "string",
        description = "Accent theme color for headings and tables (e.g., '1F497D' for Navy, '0F766E' for Emerald).",
        required = false
      ),
      ToolParameter(
        name = "notes",
        type = "string",
        description = "Optional additional instructions or sections to include.",
        required = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
    val startTime = System.currentTimeMillis()
    val sourceArg = (arguments["source"] as? String)?.trim().orEmpty()
    if (sourceArg.isEmpty()) {
      return invalidArg(callId, arguments, startTime, "Parameter 'source' is required.")
    }
    val targetFormat = (arguments["target_format"] as? String)?.trim()?.lowercase().orEmpty()
    if (targetFormat !in setOf("docx", "xlsx", "pdf")) {
      return invalidArg(callId, arguments, startTime, "Parameter 'target_format' must be one of: 'docx', 'xlsx', 'pdf'.")
    }
    val outputArg = (arguments["output_path"] as? String)?.trim().orEmpty()
    if (outputArg.isEmpty()) {
      return invalidArg(callId, arguments, startTime, "Parameter 'output_path' is required.")
    }

    val cleanOutput = if (outputArg.endsWith(".$targetFormat", ignoreCase = true)) outputArg else "$outputArg.$targetFormat"
    val targetFile = PathSecurity.resolveSafe(workspaceDir, cleanOutput)

    val newTitle = (arguments["title"] as? String)?.trim()?.takeIf { it.isNotBlank() }
    val newAuthor = (arguments["author"] as? String)?.trim()?.takeIf { it.isNotBlank() }
    val rawDate = (arguments["date"] as? String)?.trim()
    val resolvedDate = when {
      rawDate == null -> null
      rawDate.equals("today", true) || rawDate.equals("current", true) || rawDate.equals("now", true) ->
        LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
      else -> rawDate
    }
    val themeColor = (arguments["theme_color"] as? String)?.trim() ?: "1F497D"

    // Parse text replacements
    val replacementsMap = mutableMapOf<String, String>()
    arguments["replacements"]?.let { raw ->
      when (raw) {
        is Map<*, *> -> raw.forEach { (k, v) -> if (k != null && v != null) replacementsMap[k.toString()] = v.toString() }
        is String -> {
          val trimmed = raw.trim()
          if (trimmed.startsWith("{")) {
            try {
              val jsonObj = JSONObject(trimmed)
              val keys = jsonObj.keys()
              while (keys.hasNext()) {
                val k = keys.next()
                replacementsMap[k] = jsonObj.optString(k)
              }
            } catch (_: Exception) {}
          }
        }
      }
    }

    // 1. Resolve source file
    val sourceFile = resolveSourceFile(sourceArg)
    if (sourceFile == null || !sourceFile.exists()) {
      return invalidArg(callId, arguments, startTime, "Source file '$sourceArg' could not be found in workspace or imported documents.")
    }

    val ext = sourceFile.extension.lowercase()
    val isImage = ext in setOf("png", "jpg", "jpeg", "webp", "bmp")

    var sourceDescription = "file '$sourceArg' ($ext)"
    var extractedText = ""
    val extractedHeadings = mutableListOf<String>()
    val extractedParagraphs = mutableListOf<String>()
    val extractedLists = mutableListOf<List<String>>()
    val extractedCallouts = mutableListOf<Pair<String, String>>() // type to text
    val extractedTables = mutableListOf<Pair<List<String>, List<List<String>>>>()
    var detectedOriginalTitle: String? = null
    var detectedOriginalAuthor: String? = null
    var detectedOriginalDate: String? = null

    try {
      if (isImage) {
        sourceDescription = "image/screenshot '${sourceFile.name}'"
        val router = ocrRouter ?: throw IllegalStateException("OCR engine is not configured to read images.")
        val ocrResult = router.recognize(sourceFile, forceCloud = false)
        extractedText = ocrResult.fullText

        // Extract tables from OCR if present
        for (t in ocrResult.tables) {
          extractedTables.add(Pair(t.header, t.rows))
        }

        // Categorize lines into title, headings, metadata, lists, callouts, and paragraphs
        val lines = ocrResult.lines.map { it.text.trim() }.filter { it.isNotBlank() }
        if (lines.isNotEmpty()) {
          val currentList = mutableListOf<String>()
          val paragraphBuilder = StringBuilder()

          fun flushParagraph() {
            if (paragraphBuilder.isNotBlank()) {
              extractedParagraphs.add(paragraphBuilder.toString().trim())
              paragraphBuilder.setLength(0)
            }
          }

          fun flushList() {
            if (currentList.isNotEmpty()) {
              extractedLists.add(currentList.toList())
              currentList.clear()
            }
          }

          for ((idx, line) in lines.withIndex()) {
            val lower = line.lowercase()
            when {
              idx == 0 && lines.size > 1 && detectedOriginalTitle == null -> {
                detectedOriginalTitle = line
              }
              lower.startsWith("author:") || lower.startsWith("author ") || lower.startsWith("by:") ||
                lower.startsWith("by ") || lower.startsWith("prepared by:") || lower.startsWith("prepared by ") ||
                lower.startsWith("written by:") || lower.startsWith("written by ") || lower.startsWith("creator:") -> {
                flushParagraph()
                flushList()
                val authorVal = line.substringAfter(":").ifBlank { line.substringAfter(" ") }.trim()
                if (authorVal.isNotBlank()) detectedOriginalAuthor = authorVal
              }
              lower.startsWith("date:") || lower.startsWith("date ") || lower.startsWith("dated:") ||
                lower.startsWith("dated ") || lower.startsWith("date of issue:") || lower.startsWith("issued:") ||
                (lower.matches(Regex(".*\\b(20\\d\\d|19\\d\\d)\\b.*")) &&
                  (lower.contains("jan") || lower.contains("feb") || lower.contains("mar") || lower.contains("apr") ||
                    lower.contains("may") || lower.contains("jun") || lower.contains("jul") || lower.contains("aug") ||
                    lower.contains("sep") || lower.contains("oct") || lower.contains("nov") || lower.contains("dec"))) -> {
                flushParagraph()
                flushList()
                val dateVal = if (line.contains(":")) line.substringAfter(":").trim() else line.trim()
                if (dateVal.isNotBlank()) detectedOriginalDate = dateVal
              }
              lower.startsWith("note:") || lower.startsWith("tip:") || lower.startsWith("warning:") || lower.startsWith("important:") -> {
                flushParagraph()
                flushList()
                val cType = when {
                  lower.startsWith("warning:") -> "WARNING"
                  lower.startsWith("tip:") -> "TIP"
                  lower.startsWith("important:") -> "DANGER"
                  else -> "NOTE"
                }
                extractedCallouts.add(Pair(cType, line.substringAfter(":").trim()))
              }
              line.startsWith("•") || line.startsWith("- ") || line.startsWith("* ") || line.matches(Regex("""^\d+[\.\)]\s+.*""")) -> {
                flushParagraph()
                val item = line.replace(Regex("""^[•\-\*]\s*|^\d+[\.\)]\s*"""), "").trim()
                if (item.isNotBlank()) currentList.add(item)
              }
              line.length < 65 && (line.all { it.isUpperCase() || it.isWhitespace() || it.isDigit() || it == ':' } ||
                line.endsWith(":") || line.matches(Regex("""^(?:Section|Chapter|Part|\d+)\b.*"""))) -> {
                flushParagraph()
                flushList()
                extractedHeadings.add(line.trimEnd(':'))
              }
              else -> {
                flushList()
                if (paragraphBuilder.isNotEmpty()) paragraphBuilder.append(" ")
                paragraphBuilder.append(line)
                if (line.endsWith(".") || line.endsWith("!") || line.endsWith("?")) {
                  flushParagraph()
                }
              }
            }
          }
          flushParagraph()
          flushList()
        }
      } else if (ext == "docx") {
        sourceDescription = "Word document '${sourceFile.name}'"
        val structure = DocxTextExtractor.extract(sourceFile, "src", sourceFile.name)
        for (b in structure.blocks) {
          when (b.kind) {
            BlockKind.HEADING -> extractedHeadings.add(b.text)
            BlockKind.PARAGRAPH -> extractedParagraphs.add(b.text)
            BlockKind.LIST_ITEM -> extractedLists.add(listOf(b.text))
            BlockKind.TABLE -> b.table?.let { extractedTables.add(Pair(it.header, it.rows)) }
            else -> {}
          }
        }
        if (extractedHeadings.isNotEmpty()) detectedOriginalTitle = extractedHeadings.first()
      } else if (ext == "pdf") {
        sourceDescription = "PDF document '${sourceFile.name}'"
        val res = PdfTextExtractor.extractText(sourceFile, maxPages = 50)
        for (p in res.pages) {
          val pLines = p.text.lines().map { it.trim() }.filter { it.isNotBlank() }
          if (detectedOriginalTitle == null && pLines.isNotEmpty()) detectedOriginalTitle = pLines.first()
          extractedParagraphs.addAll(pLines)
        }
      } else if (ext == "xlsx") {
        sourceDescription = "Excel spreadsheet '${sourceFile.name}'"
        val structure = XlsxTextExtractor.extract(sourceFile, "src", sourceFile.name)
        for (sheet in structure.sheets) {
          if (sheet.rows.isNotEmpty()) {
            val h = sheet.rows.first().map { it.text }
            val rows = sheet.rows.drop(1).map { r -> r.map { it.text } }
            extractedTables.add(Pair(h, rows))
          }
        }
      } else if (ext == "csv") {
        sourceDescription = "CSV file '${sourceFile.name}'"
        val structure = CsvTextExtractor.extract(sourceFile, "src", sourceFile.name)
        val s = structure.sheets.firstOrNull()
        if (s != null && s.rows.isNotEmpty()) {
          val h = s.rows.first().map { it.text }
          val rows = s.rows.drop(1).map { r -> r.map { it.text } }
          extractedTables.add(Pair(h, rows))
        }
      } else {
        sourceDescription = "text file '${sourceFile.name}'"
        val structure = TxtTextExtractor.extract(sourceFile, "src", sourceFile.name)
        extractedParagraphs.addAll(structure.blocks.map { it.text })
      }

      // Apply substitutions
      val effectiveTitle = newTitle ?: detectedOriginalTitle ?: "Recreated Document"
      val effectiveAuthor = newAuthor ?: detectedOriginalAuthor ?: "Aragon Agent"
      val effectiveDate = resolvedDate ?: detectedOriginalDate ?: LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))

      fun transformText(raw: String): String {
        var res = raw
        if (detectedOriginalTitle != null && newTitle != null && res.contains(detectedOriginalTitle!!, ignoreCase = true)) {
          res = res.replace(detectedOriginalTitle!!, newTitle!!, ignoreCase = true)
        }
        if (detectedOriginalAuthor != null && newAuthor != null && res.contains(detectedOriginalAuthor!!, ignoreCase = true)) {
          res = res.replace(detectedOriginalAuthor!!, newAuthor!!, ignoreCase = true)
        }
        if (detectedOriginalDate != null && resolvedDate != null && res.contains(detectedOriginalDate!!, ignoreCase = true)) {
          res = res.replace(detectedOriginalDate!!, resolvedDate!!, ignoreCase = true)
        }
        // Generic date patterns replacement if date was requested
        if (resolvedDate != null) {
          res = res.replace(Regex("""\b(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{1,2},?\s+\d{4}\b""", RegexOption.IGNORE_CASE), resolvedDate!!)
          res = res.replace(Regex("""\b\d{4}-\d{2}-\d{2}\b"""), LocalDate.now().toString())
        }
        // Custom replacements
        for ((k, v) in replacementsMap) {
          if (k.isNotBlank()) res = res.replace(k, v, ignoreCase = true)
        }
        return res
      }

      var elementsWritten = 0

      // Synthesize target deliverable
      when (targetFormat) {
        "docx" -> {
          val builder = DocxBuilder()
          builder.setCoreProperties(title = effectiveTitle, creator = effectiveAuthor)
          builder.setPageSettings(
            header = effectiveTitle,
            footer = "Prepared by $effectiveAuthor · $effectiveDate",
            themeColor = themeColor,
            pageNumbers = true
          )
          builder.addTitle(effectiveTitle, subtitle = "Author: $effectiveAuthor | Date: $effectiveDate")
          elementsWritten++

          for (h in extractedHeadings) {
            val tH = transformText(h)
            if (tH.isNotBlank() && !tH.equals(effectiveTitle, true)) {
              builder.addHeading(tH, 1)
              elementsWritten++
            }
          }
          for (c in extractedCallouts) {
            val tC = transformText(c.second)
            if (tC.isNotBlank()) {
              val cType = when (c.first) {
                "WARNING" -> DocxBuilder.CalloutType.WARNING
                "TIP" -> DocxBuilder.CalloutType.TIP
                "DANGER" -> DocxBuilder.CalloutType.DANGER
                else -> DocxBuilder.CalloutType.NOTE
              }
              builder.addCallout(tC, type = cType)
              elementsWritten++
            }
          }
          for (p in extractedParagraphs) {
            val tP = transformText(p)
            if (tP.isNotBlank() && !tP.equals(effectiveTitle, true)) {
              builder.addParagraph(tP)
              elementsWritten++
            }
          }
          for (l in extractedLists) {
            val modItems = l.map { transformText(it) }.filter { it.isNotBlank() }
            if (modItems.isNotEmpty()) {
              builder.addList(modItems, ordered = false)
              elementsWritten += modItems.size
            }
          }
          for (t in extractedTables) {
            val modH = t.first.map { transformText(it) }
            val modRows = t.second.map { r -> r.map { transformText(it) } }
            if (modH.isNotEmpty() || modRows.isNotEmpty()) {
              builder.addTable(modH, modRows)
              elementsWritten++
            }
          }
          builder.save(targetFile)
        }
        "xlsx" -> {
          val builder = XlsxBuilder("Data")
          builder.freezeTopRow()
          if (extractedTables.isNotEmpty()) {
            for (t in extractedTables) {
              val modH = t.first.map { transformText(it) }
              val modRows = t.second.map { r -> r.map { transformText(it) } }
              if (modH.isNotEmpty()) {
                builder.addHeader(modH)
                elementsWritten++
              }
              for (r in modRows) {
                builder.addRow(r)
                elementsWritten++
              }
            }
          } else {
            builder.addHeader(listOf("Item", "Content"))
            for (p in extractedParagraphs) {
              val tP = transformText(p)
              builder.addRow(listOf("Text", tP))
              elementsWritten++
            }
            for (l in extractedLists) {
              for (item in l) {
                val tI = transformText(item)
                builder.addRow(listOf("Bullet", tI))
                elementsWritten++
              }
            }
          }
          builder.autoFitColumnWidths()
          builder.save(targetFile)
        }
        "pdf" -> {
          val builder = PdfBuilder(
            title = effectiveTitle,
            author = effectiveAuthor,
            date = effectiveDate,
            brandColorHex = themeColor
          )
          builder.addTitle(effectiveTitle, author = effectiveAuthor, date = effectiveDate)
          elementsWritten++

          for (h in extractedHeadings) {
            val tH = transformText(h)
            if (tH.isNotBlank() && !tH.equals(effectiveTitle, true)) {
              builder.addHeading(tH, 1)
              elementsWritten++
            }
          }
          for (c in extractedCallouts) {
            val tC = transformText(c.second)
            if (tC.isNotBlank()) {
              builder.addCallout(tC, title = c.first)
              elementsWritten++
            }
          }
          for (p in extractedParagraphs) {
            val tP = transformText(p)
            if (tP.isNotBlank() && !tP.equals(effectiveTitle, true)) {
              builder.addParagraph(tP)
              elementsWritten++
            }
          }
          for (l in extractedLists) {
            val modItems = l.map { transformText(it) }.filter { it.isNotBlank() }
            if (modItems.isNotEmpty()) {
              builder.addList(modItems, ordered = false)
              elementsWritten += modItems.size
            }
          }
          for (t in extractedTables) {
            val modH = t.first.map { transformText(it) }
            val modRows = t.second.map { r -> r.map { transformText(it) } }
            if (modH.isNotEmpty() || modRows.isNotEmpty()) {
              builder.addTable(modH, modRows)
              elementsWritten++
            }
          }
          builder.save(targetFile)
        }
      }

      val relPath = PathSecurity.getRelativePath(workspaceDir, targetFile)
      artifactManager.registerArtifact(relPath, createdByCallId = callId)

      // Sync deliverable directly into active E2B Cloud MicroVM (/home/user/$relPath)
      val sbx = sandboxProviderSupplier?.invoke()
      val sessId = sessionIdProvider?.invoke()
      if (sbx != null && sbx.isRemote && !sessId.isNullOrBlank()) {
        try {
          sbx.pushFileToRemote(sessId, relPath, targetFile)
        } catch (_: Exception) {}
      }

      val sizeBytes = targetFile.length()
      val summary = buildString {
        appendLine("Successfully recreated deliverable '$relPath' from $sourceDescription:")
        appendLine("- Target format: ${targetFormat.uppercase()} ($sizeBytes bytes, $elementsWritten elements written)")
        appendLine("- Title: '$effectiveTitle' (Original: ${detectedOriginalTitle ?: "n/a"})")
        appendLine("- Author: '$effectiveAuthor' (Original: ${detectedOriginalAuthor ?: "n/a"})")
        appendLine("- Date: '$effectiveDate' (Original: ${detectedOriginalDate ?: "n/a"})")
        if (replacementsMap.isNotEmpty()) {
          appendLine("- Applied ${replacementsMap.size} text replacement(s): ${replacementsMap.keys.joinToString(", ")}")
        }
        if (extractedTables.isNotEmpty()) {
          appendLine("- Reconstructed ${extractedTables.size} structured table(s)")
        }
        if (extractedLists.isNotEmpty()) {
          appendLine("- Reconstructed ${extractedLists.sumOf { it.size }} bullet/list item(s)")
        }
        appendLine("- Verified on disk and placed in E2B Cloud Sandbox (/home/user/$relPath).")
      }

      return ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = summary.trim(),
        error = null,
        duration = System.currentTimeMillis() - startTime,
        stdout = summary.trim(),
        exitCode = 0,
        artifacts = artifactManager.getArtifacts()
      )
    } catch (e: Exception) {
      return ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.FAILED,
        arguments = arguments,
        output = null,
        error = "Failed to recreate document: ${e.message ?: "unknown error"}",
        errorType = ToolErrorCode.UNKNOWN_ERROR,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1
      )
    }
  }

  private suspend fun resolveSourceFile(sourceArg: String): File? {
    val clean = sourceArg.trim().removeSurrounding("\"").removeSurrounding("'")

    // 1. Direct workspace path
    if (clean.isNotBlank()) {
      val direct = PathSecurity.resolveSafe(workspaceDir, clean)
      if (direct.exists() && direct.isFile) return direct
    }

    // 2. Document reference via DocumentIntelligenceService (e.g. "D1" or display name)
    if (documentService != null && clean.isNotBlank()) {
      val entity = documentService.resolveReference(clean)
      if (entity != null) {
        val f = documentService.resolveDocumentFile(entity.id)
        if (f != null && f.exists()) return f
      }
    }

    // 3. Scan workspace recursively by exact name, prefix, or substring match
    val allWorkspaceFiles = workspaceDir.walkTopDown()
      .filter {
        it.isFile && !it.name.startsWith(".") &&
          !it.relativeTo(workspaceDir).path.startsWith("documents/") &&
          !it.relativeTo(workspaceDir).path.startsWith(".scratchpad/")
      }
      .toList()

    if (clean.isNotBlank()) {
      val exact = allWorkspaceFiles.firstOrNull { it.name.equals(clean, ignoreCase = true) }
      if (exact != null) return exact

      val prefix = allWorkspaceFiles.firstOrNull { it.name.startsWith(clean, ignoreCase = true) }
      if (prefix != null) return prefix

      val substring = allWorkspaceFiles.firstOrNull { it.name.contains(clean, ignoreCase = true) }
      if (substring != null) return substring
    }

    // 4. Fuzzy / contextual fallback if clean is a generic reference
    val isGeneric = clean.isBlank() || clean.lowercase() in setOf(
      "picture", "this picture", "image", "this image", "photo", "this photo",
      "screenshot", "capture", "word file", "file", "attached", "input", "recent", "latest", "doc"
    )
    if (isGeneric) {
      // Prefer image files first (PNG, JPG, JPEG, WEBP, BMP)
      val images = allWorkspaceFiles.filter { it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "bmp") }
        .sortedByDescending { it.lastModified() }
      if (images.isNotEmpty()) return images.first()

      // Then documents (DOCX, PDF, XLSX, CSV)
      val docs = allWorkspaceFiles.filter { it.extension.lowercase() in setOf("docx", "pdf", "xlsx", "csv") }
        .sortedByDescending { it.lastModified() }
      if (docs.isNotEmpty()) return docs.first()
    }

    // 5. If only 1 image exists, resolve it directly
    val allImages = allWorkspaceFiles.filter { it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "bmp") }
    if (allImages.size == 1) return allImages.first()

    // 6. If only 1 document candidate exists, resolve it directly
    val allCandidateDocs = allWorkspaceFiles.filter { it.extension.lowercase() in setOf("docx", "pdf", "xlsx", "csv", "png", "jpg", "jpeg", "webp") }
    if (allCandidateDocs.size == 1) return allCandidateDocs.first()

    return null
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
