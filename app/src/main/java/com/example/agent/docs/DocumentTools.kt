package com.example.agent.docs

import com.example.agent.core.ArtifactManager
import com.example.agent.core.PathSecurity
import com.example.agent.core.Tool
import kotlinx.coroutines.CancellationException
import com.example.agent.core.ToolCategory
import com.example.agent.core.ToolErrorCode
import com.example.agent.core.ToolParameter
import com.example.agent.core.ToolResult
import com.example.agent.core.ToolSchema
import com.example.agent.core.ToolStatus
import com.example.agent.core.ToolExecutionOutcome
import com.example.agent.core.ExecutionEnvironmentType
import com.example.agent.core.SandboxProvider
import java.io.File

class DocumentListTool(
  private val service: DocumentIntelligenceService
) : Tool {
  override val name: String = "document_list"
  override val description: String =
    "List every imported document with its exact processing status, type, chunk count, OCR/extraction engine, embedding availability, scope state, and warnings. Use this before making any claim about an uploaded document or when a document reference needs to be resolved."

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.ANDROID_NATIVE

  override val schema: ToolSchema = ToolSchema(emptyList())

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
    val startTime = System.currentTimeMillis()
    return try {
      val listing = service.listForTool()
      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = listing,
        error = null,
        duration = System.currentTimeMillis() - startTime,
        stdout = listing,
        exitCode = 0
      )
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      failure(callId, arguments, startTime, "Failed to list documents: ${e.message ?: "unknown error"}")
    }
  }

  private fun failure(callId: String, arguments: Map<String, Any?>, startTime: Long, message: String): ToolResult =
    ToolResult(
      callId = callId,
      toolName = name,
      status = ToolStatus.FAILED,
      arguments = arguments,
      output = null,
      error = message,
      errorType = ToolErrorCode.SANDBOX_ERROR,
      duration = System.currentTimeMillis() - startTime,
      exitCode = 1
    )
}

class DocumentSearchTool(
  private val service: DocumentIntelligenceService
) : Tool {
  override val name: String = "document_search"
  override val description: String =
    "Search the user's imported documents (RAG hybrid retrieval: keyword index + semantic embeddings when available) and return the most relevant ground-truth passages with document, page/sheet, and row references. Use this to answer questions about uploaded files, cross-document reasoning, or to locate content before generating a deliverable. Never guess document content without searching first."

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.ANDROID_NATIVE

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "query",
        type = "string",
        description = "Natural-language or keyword query to find relevant content in the indexed documents.",
        required = true,
        aliases = listOf("q", "search", "term")
      ),
      ToolParameter(
        name = "limit",
        type = "number",
        description = "Maximum number of passages to return (default 8, max 20).",
        required = false,
        defaultValue = 8,
        aliases = listOf("max_results", "maxResults")
      ),
      ToolParameter(
        name = "doc_ids",
        type = "string",
        description = "Optional comma-separated document references (D# numbers, exact names, or ids) to restrict the search to specific documents.",
        required = false,
        aliases = listOf("docIds", "documents", "docs")
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
    val startTime = System.currentTimeMillis()
    return try {
      val query = (arguments["query"] as? String)?.trim().orEmpty()
      if (query.isEmpty()) {
        return invalidArguments(callId, arguments, startTime, "Parameter 'query' is required and must not be empty.")
      }
      val limit = parseLimit(arguments["limit"])
      val docFilter = (arguments["doc_ids"] as? String)?.trim().takeUnless { it.isNullOrEmpty() }
      val docIds = if (docFilter == null) {
        null
      } else {
        val resolved = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (part in docFilter.split(',')) {
          val ref = part.trim()
          if (ref.isEmpty()) continue
          val entity = service.resolveReference(ref)
          if (entity == null) failures.add(ref) else resolved.add(entity.id)
        }
        if (failures.isNotEmpty()) {
          return invalidArguments(
            callId, arguments, startTime,
            "Unknown document reference(s): ${failures.joinToString(", ")}. Run document_list to see available documents."
          )
        }
        resolved
      }

      val outcome = service.searchDocuments(query, limit, docIds)
      val sb = StringBuilder()
      sb.appendLine("QUERY: $query")
      sb.appendLine("MODE: ${outcome.mode} (${if (outcome.mode.contains("vector")) "keyword + semantic embeddings" else "keyword index"})")
      sb.appendLine("RESULTS: ${outcome.hits.size}")
      if (outcome.hits.isEmpty()) {
        sb.appendLine("No matching content was found. Try different keywords, check document status with document_list, or verify the documents are in scope.")
      }
      val docs = service.documents.value
      for ((index, hit) in outcome.hits.withIndex()) {
        val docNumber = docs.indexOfFirst { it.id == hit.docId } + 1
        val docTag = if (docNumber > 0) "D$docNumber" else hit.docName
        val location = buildString {
          if (hit.page != null) append("page ${hit.page}")
          if (hit.sheetName != null) {
            if (isNotEmpty()) append(", ")
            append("sheet \"${hit.sheetName}\"")
            if (hit.rowStart != null) append(" rows ${hit.rowStart}${if (hit.rowEnd != null && hit.rowEnd != hit.rowStart) "-${hit.rowEnd}" else ""}")
          }
        }
        val text = DocumentText.collapseWhitespace(hit.text)
        val clipped = if (text.length > 600) text.take(600) + " …" else text
        sb.appendLine("${index + 1}. [$docTag${if (location.isNotBlank()) " · $location" else ""}] score=%.3f".format(hit.score))
        sb.appendLine("   $clipped")
      }
      if (outcome.notes.isNotEmpty()) {
        sb.appendLine()
        for (note in outcome.notes) sb.appendLine("Note: $note")
      }
      sb.appendLine()
      sb.appendLine("Cite these sources in answers using the [D#] references shown above.")
      val output = sb.toString().trim()
      val toolOutcome = if (outcome.hits.isEmpty()) ToolExecutionOutcome.CONDITION_NOT_FOUND else ToolExecutionOutcome.SUCCESS_WITH_RESULTS
      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = output,
        error = null,
        duration = System.currentTimeMillis() - startTime,
        stdout = output,
        exitCode = 0,
        outcome = toolOutcome,
        responseMetadata = mapOf("query" to query, "hitsCount" to outcome.hits.size, "mode" to outcome.mode)
      )
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.FAILED,
        arguments = arguments,
        output = null,
        error = "Document search failed: ${e.message ?: "unknown error"}",
        errorType = ToolErrorCode.SANDBOX_ERROR,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1
      )
    }
  }

  private fun parseLimit(raw: Any?): Int {
    val asInt = when (raw) {
      null -> 8
      is Number -> raw.toInt()
      is String -> raw.trim().toIntOrNull() ?: 8
      else -> 8
    }
    return asInt.coerceIn(1, 20)
  }

  private fun invalidArguments(callId: String, arguments: Map<String, Any?>, startTime: Long, message: String): ToolResult =
    ToolResult(
      callId = callId,
      toolName = name,
      status = ToolStatus.FAILED,
      arguments = arguments,
      output = null,
      error = message,
      errorType = ToolErrorCode.INVALID_ARGUMENTS,
      duration = System.currentTimeMillis() - startTime,
      exitCode = 1
    )
}

class DocumentProcessTool(
  private val workspaceDir: File,
  private val service: DocumentIntelligenceService,
  private val artifactManager: ArtifactManager? = null
) : Tool {
  override val name: String = "document_process"
  override val description: String =
    "Generate a real, verified deliverable (DOCX with RTL support, XLSX with typed cells, or Markdown) from an imported document's extracted structure, OCR output, and tables. The output file is natively constructed on-device, reopened for structural verification, and registered as a workspace artifact. Requires a processed document (status READY or READY (LEXICAL)) - use document_list first."

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.ANDROID_NATIVE
  override val defaultTimeoutMs: Long = 120000L

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "document",
        type = "string",
        description = "Document reference: the D# number from document_list, the exact file name, or the document id.",
        required = true,
        aliases = listOf("doc", "source", "file")
      ),
      ToolParameter(
        name = "format",
        type = "string",
        description = "Output format to generate.",
        required = true,
        enumValues = listOf("docx", "xlsx", "markdown", "pdf"),
        aliases = listOf("target_format", "output_format")
      ),
      ToolParameter(
        name = "output_name",
        type = "string",
        description = "Optional base file name for the output (without extension). Defaults to the source document name.",
        required = false,
        aliases = listOf("outputName", "name", "filename")
      ),
      ToolParameter(
        name = "target_dir",
        type = "string",
        description = "Optional workspace-relative directory for the output file (default: workspace root).",
        required = false,
        aliases = listOf("targetDir", "dir", "directory")
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
    val startTime = System.currentTimeMillis()
    return try {
      val reference = (arguments["document"] as? String)?.trim().orEmpty()
      if (reference.isEmpty()) {
        return invalidArguments(callId, arguments, startTime, "Parameter 'document' is required (use a D# reference from document_list).")
      }
      val format = (arguments["format"] as? String)?.trim()?.lowercase().orEmpty()
      if (format !in setOf("docx", "xlsx", "markdown", "md", "pdf")) {
        return invalidArguments(callId, arguments, startTime, "Parameter 'format' must be one of: docx, xlsx, markdown, pdf.")
      }
      val entity = service.resolveReference(reference)
        ?: return invalidArguments(callId, arguments, startTime, "Unknown document reference '$reference'. Run document_list to see available documents and their status.")
      if (entity.status != DocumentStatus.READY.name && entity.status != DocumentStatus.READY_LEXICAL.name) {
        return invalidArguments(callId, arguments, startTime, "Document '${entity.displayName}' is not ready (status: ${entity.status.lowercase()}). Wait for processing to finish or re-index it.")
      }

      val outputName = (arguments["output_name"] as? String)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.replace(Regex("[\\\\/]"), "_")
        ?.take(80)
      val targetDirArg = (arguments["target_dir"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "."
      val targetDir = PathSecurity.resolveSafe(workspaceDir, targetDirArg)
      if (targetDir.exists() && !targetDir.isDirectory) {
        return invalidArguments(callId, arguments, startTime, "Target path '$targetDirArg' exists but is not a directory.")
      }
      targetDir.mkdirs()

      val report = service.convertDocument(entity.id, format, outputName, targetDir)
      val relPath = PathSecurity.getRelativePath(workspaceDir, report.outputFile)
      artifactManager?.registerArtifact(relPath, createdByCallId = callId)

      val sizeKb = (report.outputFile.length() + 1023) / 1024
      val sb = StringBuilder()
      sb.appendLine("Generated $relPath (${report.format}, ${sizeKb} KB).")
      sb.appendLine("Verification: ${report.verification}")
      sb.appendLine(
        "Structure written: tables=${report.tablesWritten}, sheets=${report.sheetsWritten}, " +
          "formulas recovered=${report.formulasRecovered}, formulas downgraded to values=${report.formulasDropped}, RTL applied=${if (report.rtlApplied) "yes" else "no"}."
      )
      if (report.warnings.isNotEmpty()) {
        sb.appendLine("Warnings:")
        for (warning in report.warnings) sb.appendLine("- $warning")
      }
      val output = sb.toString().trim()

      if (!report.verified) {
        return ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = output,
          error = "The generated file exists at '$relPath' but failed reopen verification: ${report.verification}",
          errorType = ToolErrorCode.WRITE_INTEGRITY_MISMATCH,
          duration = System.currentTimeMillis() - startTime,
          stdout = output,
          exitCode = 1,
          artifacts = artifactManager?.getArtifacts() ?: emptyList()
        )
      }

      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = output,
        error = null,
        duration = System.currentTimeMillis() - startTime,
        stdout = output,
        exitCode = 0,
        artifacts = artifactManager?.getArtifacts() ?: emptyList()
      )
    } catch (se: SecurityException) {
      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.FAILED,
        arguments = arguments,
        output = null,
        error = se.message ?: "Path traversal denied.",
        errorType = ToolErrorCode.PATH_NOT_ALLOWED,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1
      )
    } catch (de: DocumentException) {
      invalidArguments(callId, arguments, startTime, de.message ?: "Document processing failed.")
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.FAILED,
        arguments = arguments,
        output = null,
        error = "Document processing failed: ${e.message ?: "unknown error"}",
        errorType = ToolErrorCode.SANDBOX_ERROR,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1
      )
    }
  }

  private fun invalidArguments(callId: String, arguments: Map<String, Any?>, startTime: Long, message: String): ToolResult =
    ToolResult(
      callId = callId,
      toolName = name,
      status = ToolStatus.FAILED,
      arguments = arguments,
      output = null,
      error = message,
      errorType = ToolErrorCode.INVALID_ARGUMENTS,
      duration = System.currentTimeMillis() - startTime,
      exitCode = 1
    )
}

/**
 * First-class agent tool to OCR and transcribe any image in the workspace.
 * Automatically leverages active model vision if capable, or on-device universal OCR and cloud fallbacks.
 */
class ReadImageTool(
  private val workspaceDir: File,
  private val ocrRouter: OcrRouter,
  private val documentServiceSupplier: (() -> DocumentIntelligenceService?)? = null,
  private val sandboxProviderSupplier: (() -> SandboxProvider?)? = null,
  private val sessionIdProvider: () -> String = { "default" }
) : Tool {
  override val name: String = "read_image"
  override val description: String =
    "Transcribes and extracts text, numbers, handwriting, and tables from any image file in the workspace (.png, .jpg, .webp, .bmp, .gif, .heic). If the active model has vision, it processes the image directly. If not, it uses the advanced multi-pass OCR engine with auto-orientation and contrast correction, then cloud fallbacks."

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.ANDROID_NATIVE

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Workspace-relative path to the image file to read/transcribe (e.g. 'receipt.jpg', 'diagram.png', 'scan.webp').",
        required = true,
        aliases = listOf("file", "image", "image_path", "imagePath", "filePath")
      ),
      ToolParameter(
        name = "force_cloud",
        type = "boolean",
        description = "Whether to force cloud/fallback OCR if local OCR produces partial text.",
        required = false,
        defaultValue = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
    val startTime = System.currentTimeMillis()
    val rawPath = (arguments["path"] as? String)?.trim().orEmpty()
    if (rawPath.isEmpty()) {
      return invalidArguments(callId, arguments, startTime, "Parameter 'path' is required.")
    }
    var cleanPath = rawPath.removeSurrounding("\"").removeSurrounding("'").trim()
    while (cleanPath.startsWith("./")) cleanPath = cleanPath.removePrefix("./").trim()
    when {
      cleanPath.startsWith("/home/user/") -> cleanPath = cleanPath.removePrefix("/home/user/")
      cleanPath.startsWith("home/user/") -> cleanPath = cleanPath.removePrefix("home/user/")
      cleanPath.startsWith("/workspace/") -> cleanPath = cleanPath.removePrefix("/workspace/")
      cleanPath.startsWith("workspace/") -> cleanPath = cleanPath.removePrefix("workspace/")
      cleanPath.startsWith("/") -> cleanPath = cleanPath.removePrefix("/")
    }

    var targetFile: File? = null
    // 1. Direct safe resolve
    try {
      val f = PathSecurity.resolveSafe(workspaceDir, cleanPath)
      if (f.exists() && f.isFile) targetFile = f
    } catch (_: Exception) {}

    // 2. Direct workspace file by name or base name
    if (targetFile == null) {
      val f = File(workspaceDir, cleanPath)
      if (f.exists() && f.isFile) targetFile = f
    }
    if (targetFile == null) {
      val f = File(workspaceDir, File(cleanPath).name)
      if (f.exists() && f.isFile) targetFile = f
    }

    // 3. DocumentIntelligenceService resolution
    if (targetFile == null) {
      val docService = documentServiceSupplier?.invoke()
      val docFile = docService?.getOriginalFile(cleanPath)
        ?: docService?.resolveDocumentFile(cleanPath)
        ?: docService?.getOriginalFile(File(cleanPath).name)
        ?: docService?.resolveDocumentFile(File(cleanPath).name)
      if (docFile != null && docFile.exists() && docFile.isFile) {
        val wsTarget = File(workspaceDir, File(cleanPath).name)
        try { docFile.copyTo(wsTarget, overwrite = true) } catch (_: Exception) {}
        targetFile = wsTarget
      }
    }

    // 4. Recursive workspace scan by filename or ending
    if (targetFile == null) {
      val baseName = File(cleanPath).name.lowercase()
      val found = workspaceDir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }
        .firstOrNull { it.name.lowercase() == baseName || it.name.lowercase().contains(baseName) }
      if (found != null) targetFile = found
    }

    // 5. Check documents/ folder directly
    if (targetFile == null) {
      val docsDir = File(workspaceDir, "documents")
      if (docsDir.exists()) {
        val found = docsDir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }
          .firstOrNull {
            it.name.startsWith("original") || it.name.lowercase() == File(cleanPath).name.lowercase()
          }
        if (found != null) {
          val wsTarget = File(workspaceDir, File(cleanPath).name)
          try { found.copyTo(wsTarget, overwrite = true) } catch (_: Exception) {}
          targetFile = wsTarget
        }
      }
    }

    // 6. If only 1 image exists anywhere in workspace
    if (targetFile == null) {
      val imageExts = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
      val allImages = workspaceDir.walkTopDown().filter { it.isFile && it.extension.lowercase() in imageExts }.toList()
      if (allImages.isNotEmpty()) {
        targetFile = allImages.first()
      }
    }

    // 7. Pull from remote E2B microVM if still not found
    if (targetFile == null) {
      val sbx = sandboxProviderSupplier?.invoke()
      if (sbx != null && sbx.isRemote) {
        val dest = File(workspaceDir, File(cleanPath).name)
        if (sbx.pullFileFromRemote(sessionIdProvider(), cleanPath, dest) && dest.exists()) {
          targetFile = dest
        }
      }
    }

    if (targetFile == null || !targetFile.exists() || !targetFile.isFile) {
      return ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.FAILED,
        arguments = arguments,
        output = null,
        error = "File does not exist: $rawPath",
        errorType = ToolErrorCode.FILE_NOT_FOUND,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1
      )
    }

    val forceCloud = (arguments["force_cloud"] as? Boolean) ?: false
    return try {
      val res = ocrRouter.recognize(targetFile, forceCloud)
      val sb = StringBuilder()
      sb.append("=== IMAGE TRANSCRIPTION (${res.engine}) ===\n")
      if (res.fullText.isNotBlank()) {
        sb.append(res.fullText).append("\n\n")
      } else {
        sb.append("[No text detected in image]\nHint: For handwriting, photos, or receipts, inspect with Python (PIL / pytesseract) via code_interpreter or terminal.\n\n")
      }
      if (res.tables.isNotEmpty()) {
        sb.append("--- Extracted Tables (${res.tables.size}) ---\n")
        for ((idx, t) in res.tables.withIndex()) {
          sb.append("Table ${idx + 1}:\n")
          if (t.header.isNotEmpty()) {
            sb.append("| ").append(t.header.joinToString(" | ")).append(" |\n")
            sb.append("| ").append(t.header.map { "---" }.joinToString(" | ")).append(" |\n")
          }
          for (r in t.rows) {
            sb.append("| ").append(r.joinToString(" | ")).append(" |\n")
          }
          sb.append("\n")
        }
      }
      if (res.warnings.isNotEmpty()) {
        sb.append("Warnings: ").append(res.warnings.joinToString("; ")).append("\n")
      }
      val outText = sb.toString().trim()
      val toolOutcome = if (res.fullText.isBlank() && res.tables.isEmpty()) ToolExecutionOutcome.CONDITION_NOT_FOUND else ToolExecutionOutcome.SUCCESS_WITH_RESULTS
      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = outText,
        error = null,
        duration = System.currentTimeMillis() - startTime,
        stdout = outText,
        exitCode = 0,
        outcome = toolOutcome,
        responseMetadata = mapOf("engine" to res.engine, "tablesCount" to res.tables.size)
      )
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.FAILED,
        arguments = arguments,
        output = null,
        error = "Failed to OCR image: ${e.message ?: "unknown error"}",
        errorType = ToolErrorCode.SANDBOX_ERROR,
        duration = System.currentTimeMillis() - startTime,
        exitCode = 1
      )
    }
  }

  private fun invalidArguments(callId: String, arguments: Map<String, Any?>, startTime: Long, message: String): ToolResult =
    ToolResult(
      callId = callId,
      toolName = name,
      status = ToolStatus.FAILED,
      arguments = arguments,
      output = null,
      error = message,
      errorType = ToolErrorCode.INVALID_ARGUMENTS,
      duration = System.currentTimeMillis() - startTime,
      exitCode = 1
    )
}
