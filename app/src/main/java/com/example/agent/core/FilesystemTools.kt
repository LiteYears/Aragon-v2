package com.example.agent.core

import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Path safety helper enforcing strict boundary confinement within the sandbox workspace.
 * Prevents directory traversal attacks via '..' or symlinks.
 */
object PathSecurity {
  fun resolveSafe(workspaceDir: File?, path: String): File {
    if (workspaceDir == null || workspaceDir.path.isBlank()) {
      throw IllegalArgumentException("Missing workspace configuration: workspace directory is not configured.")
    }
    var clean = path.trim().replace('\\', '/')
    if (clean == "/home/user" || clean == "/workspace") {
      clean = "."
    } else if (clean.startsWith("/home/user/")) {
      clean = clean.removePrefix("/home/user/")
    } else if (clean.startsWith("/workspace/")) {
      clean = clean.removePrefix("/workspace/")
    } else if (clean == "/tmp") {
      clean = "tmp"
    } else if (clean.startsWith("/tmp/")) {
      clean = "tmp/" + clean.removePrefix("/tmp/")
    }

    val wsCanonical = try {
      workspaceDir.canonicalFile
    } catch (e: Exception) {
      throw IllegalArgumentException("Missing workspace configuration: invalid workspace directory '${workspaceDir.path}'", e)
    }
    val wsPrefix = if (wsCanonical.path.endsWith(File.separator)) wsCanonical.path else wsCanonical.path + File.separator

    val file = if (clean.startsWith("/")) {
      val candidate = File(clean).canonicalFile
      if (candidate == wsCanonical || candidate.path.startsWith(wsPrefix)) {
        candidate
      } else {
        // Check if path is absolute but refers to another alias/symlink of workspace (e.g. /data/data vs /data/user/0)
        val candidateRel = if (candidate.path.contains("agent_workspace/")) {
          candidate.path.substringAfter("agent_workspace/").trimStart('/')
        } else if (candidate.path.contains("agent_workspace")) {
          candidate.path.substringAfter("agent_workspace").trimStart('/')
        } else null

        if (candidateRel != null) {
          File(workspaceDir, candidateRel).canonicalFile
        } else {
          candidate
        }
      }
    } else {
      File(workspaceDir, clean).canonicalFile
    }

    if (file != wsCanonical && !file.path.startsWith(wsPrefix)) {
      throw SecurityException("Path traversal denied: '$path' attempts to escape the sandbox workspace root.")
    }
    return file
  }

  fun getRelativePath(workspaceDir: File, file: File): String {
    val base = workspaceDir.canonicalFile
    val target = file.canonicalFile
    return if (target == base) "." else target.path.removePrefix(base.path).removePrefix(File.separator)
  }
}

/**
 * Lists entries in a workspace directory.
 */
class ListDirectoryTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager? = null,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" },
  var sandboxProviderSupplier: (() -> SandboxProvider?)? = null
) : Tool {
  override val name: String = "list_directory"
  override val description: String =
    "List files and directories inside the E2B Cloud MicroVM (/home/user). Supports optional path, recursive flag and maxEntries."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Directory path inside the E2B microVM, relative to /home/user (default: '.').",
        required = false,
        defaultValue = "."
      ),
      ToolParameter(
        name = "recursive",
        type = "boolean",
        description = "Whether to recursively list subdirectories (default: false).",
        required = false,
        defaultValue = false
      ),
      ToolParameter(
        name = "maxEntries",
        type = "number",
        description = "Maximum number of entries to return (default: 100).",
        required = false,
        defaultValue = 100
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.READ_ONLY)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val pathStr = arguments["path"]?.toString()?.ifBlank { "." } ?: "."
      val recursive = arguments["recursive"] as? Boolean ?: false
      val maxEntries = ((arguments["maxEntries"] as? Number)?.toInt() ?: 100).coerceIn(1, 500)

      val provider = sandboxProviderSupplier?.invoke() ?: sandboxProvider
      if (provider == null || !provider.isRemote) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "E2B Cloud Sandbox is not configured or offline. Configure your E2B API Key in Settings.",
          errorType = ToolErrorCode.SANDBOX_INFRA_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
      val sessionId = sessionIdProvider()
      val cmd = "python3 -c \"import os, json; target = '$pathStr'; (print(json.dumps({'error': 'Directory not found', 'exists': False})) if not os.path.exists(target) else print(json.dumps({'exists': True, 'path': target, 'entries': [{'name': f, 'isDirectory': os.path.isdir(os.path.join(target, f)), 'sizeBytes': os.path.getsize(os.path.join(target, f)) if os.path.isfile(os.path.join(target, f)) else 0} for f in os.listdir(target)]})))\""
      val req = RunCommandRequest.builder().command(cmd).timeout(java.time.Duration.ofSeconds(15)).build()
      val exec = provider.executeCommand(sessionId, req)
      val duration = System.currentTimeMillis() - startTime
      val stdout = exec.stdoutText().trim()
      if (exec.exitCode != 0 || stdout.isEmpty()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = exec.stderrText().ifBlank { "Failed to list directory in the E2B microVM" },
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = duration,
          exitCode = exec.exitCode ?: 1
        )
      }
      if (stdout.contains("\"exists\": false")) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Directory '$pathStr' does not exist in the E2B microVM (/home/user).",
          errorType = ToolErrorCode.FILE_NOT_FOUND,
          duration = duration,
          exitCode = 1
        )
      }
      val tagged = "[env=e2b_cloud]\n$stdout"
      return@withContext ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = tagged,
        error = null,
        duration = duration,
        stdout = tagged,
        exitCode = 0,
        artifacts = artifactManager?.getArtifacts() ?: emptyList()
      )
    }
}

/**
 * Convenience alias tool for listing files/directories.
 */
class ListFilesTool(
  val delegate: ListDirectoryTool
) : Tool {
  override val name: String = "list_files"
  override val description: String = delegate.description
  override val schema: ToolSchema = delegate.schema
  override val category: ToolCategory = delegate.category
  override val target: ToolTarget = delegate.target
  override val permissions: Set<ToolPermission> = delegate.permissions
  override val owningBackend: ExecutionEnvironmentType = delegate.owningBackend
  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    delegate.execute(callId, arguments)
}

/**
 * Reads text content from a workspace file.
 */
class ReadFileTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager? = null,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" },
  var sandboxProviderSupplier: (() -> SandboxProvider?)? = null
) : Tool {
  override val name: String = "read_file"
  override val description: String =
    "Read the text content of a file inside the E2B Cloud MicroVM (/home/user). Supports optional line offset and limit."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path of the file inside the E2B microVM workspace (relative to /home/user).",
        required = true
      ),
      ToolParameter(
        name = "offset",
        type = "number",
        description = "Optional starting line number (1-based).",
        required = false
      ),
      ToolParameter(
        name = "limit",
        type = "number",
        description = "Optional maximum number of lines to read.",
        required = false
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.READ_ONLY)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val pathStr = arguments["path"]?.toString()?.trim() ?: ""
      val offset = (arguments["offset"] as? Number)?.toInt() ?: 1
      val limit = (arguments["limit"] as? Number)?.toInt()

      if (pathStr.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'path' cannot be blank.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      val provider = sandboxProviderSupplier?.invoke() ?: sandboxProvider
      if (provider == null || !provider.isRemote) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "E2B Cloud Sandbox is not configured or offline. Configure your E2B API Key in Settings.",
          errorType = ToolErrorCode.SANDBOX_INFRA_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
      val sessionId = sessionIdProvider()

      try {
        // Authoritative read from the E2B microVM (/home/user)
        val cleanPath = pathStr.removePrefix("./").removePrefix("/")
        val cmd = "python3 -c \"import os, sys; p = '$cleanPath'; sys.exit(2) if not os.path.exists(p) else None; lines = open(p, 'r', errors='replace').readlines(); start = max(0, $offset - 1); [sys.stdout.write(f'{i}: {line}') for i, line in enumerate(lines[start:start+${limit ?: 500}], start=start+1)]\""
        val req = RunCommandRequest.builder().command(cmd).timeout(java.time.Duration.ofSeconds(15)).build()
        val exec = provider.executeCommand(sessionId, req)
        val duration = System.currentTimeMillis() - startTime
        val exitCode = exec.exitCode ?: 0
        if (exitCode == 0 && exec.error == null) {
          val content = exec.stdoutText().ifBlank { "(empty file)" }
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.SUCCEEDED,
            arguments = arguments,
            output = content,
            error = null,
            duration = duration,
            stdout = content,
            exitCode = 0,
            artifacts = artifactManager?.getArtifacts() ?: emptyList()
          )
        }

        // Remote read failed: fall back to the device-side artifact mirror if present.
        val file = PathSecurity.resolveSafe(workspaceDir, pathStr)
        if (file.exists() && file.isFile) {
          val allLines = file.readLines(StandardCharsets.UTF_8)
          val startIdx = (offset - 1).coerceAtLeast(0)
          val selectedLines = if (startIdx < allLines.size) {
            val remaining = allLines.drop(startIdx)
            if (limit != null && limit > 0) remaining.take(limit) else remaining
          } else {
            emptyList()
          }

          val content = selectedLines.joinToString("\n")
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.SUCCEEDED,
            arguments = arguments,
            output = content,
            error = null,
            duration = System.currentTimeMillis() - startTime,
            stdout = content,
            exitCode = 0,
            artifacts = artifactManager?.getArtifacts() ?: emptyList()
          )
        }

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "File '$pathStr' does not exist inside the E2B microVM (/home/user).",
          errorType = ToolErrorCode.FILE_NOT_FOUND,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      } catch (se: SecurityException) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = se.message,
          errorType = ToolErrorCode.PATH_NOT_ALLOWED,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to read file '$pathStr': ${e.message}",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }
}

/**
 * Creates or overwrites a file in the workspace with authoritative disk verification.
 */
class WriteFileTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager? = null,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" },
  var sandboxProviderSupplier: (() -> SandboxProvider?)? = null
) : Tool {
  override val name: String = "write_file"
  override val description: String =
    "Create, overwrite, or append to a file inside the E2B Cloud MicroVM workspace (/home/user). When target path ends with .docx or .doc, natively compiles formatted Markdown into an authentic Microsoft Word document. When target path ends with .xlsx or .xls, natively compiles CSV/tables into an authentic Microsoft Excel spreadsheet."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path where the file should be saved relative to workspace (e.g. 'report.md', 'output.json', 'document.docx', 'metrics.xlsx'). Note: .docx natively compiles Word documents and .xlsx natively compiles Excel spreadsheets.",
        required = true
      ),
      ToolParameter(
        name = "content",
        type = "string",
        description = "Text content to write into the file.",
        required = true
      ),
      ToolParameter(
        name = "overwrite",
        type = "boolean",
        description = "Whether to overwrite if file already exists and append is false (default: true).",
        required = false,
        defaultValue = true
      ),
      ToolParameter(
        name = "append",
        type = "boolean",
        description = "Whether to append content to the end of the file if it already exists (default: false).",
        required = false,
        defaultValue = false
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.WORKSPACE_WRITE)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val pathStr = arguments["path"]?.toString()?.trim() ?: ""
      val content = arguments["content"]?.toString() ?: ""
      val overwrite = arguments["overwrite"] as? Boolean ?: true
      val append = arguments["append"] as? Boolean ?: false

      if (pathStr.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'path' cannot be blank.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      try {
        val provider = sandboxProviderSupplier?.invoke() ?: sandboxProvider
        if (provider == null || !provider.isRemote) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "E2B Cloud Sandbox is not configured or offline. Configure your E2B API Key in Settings.",
            errorType = ToolErrorCode.SANDBOX_INFRA_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        val file = PathSecurity.resolveSafe(workspaceDir, pathStr)
        if (file.exists() && !overwrite && !append) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "File '$pathStr' already exists, overwrite is false, and append is false.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        // Validate content structure if applicable (unless appending)
        if (!append) {
          val validation = ArtifactValidatorRegistry.validateContent(pathStr, content)
          if (!validation.isValid) {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Content validation failed: ${validation.errorMessage}",
              errorType = ToolErrorCode.INVALID_ARGUMENTS,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }
        }

        file.parentFile?.mkdirs()
        if (pathStr.endsWith(".docx", ignoreCase = true) || pathStr.endsWith(".doc", ignoreCase = true)) {
          compileMarkdownToDocx(content, file)
        } else if (pathStr.endsWith(".xlsx", ignoreCase = true) || pathStr.endsWith(".xls", ignoreCase = true)) {
          compileContentToXlsx(content, file)
        } else {
          if (append && file.exists()) {
            file.appendText(content, StandardCharsets.UTF_8)
          } else {
            file.writeText(content, StandardCharsets.UTF_8)
          }
        }

        // Verify written file exists on disk
        if (!file.exists() || !file.isFile) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Verification failed: file '$pathStr' was not found on disk after write operation.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        val registeredArtifact = artifactManager?.registerArtifact(pathStr, createdByCallId = callId)

        // Push file into the E2B Cloud MicroVM (the only execution environment) and verify receipt
        val relPath = PathSecurity.getRelativePath(workspaceDir, file)
        val pushed = try {
          provider.pushFileToRemote(sessionIdProvider(), relPath, file)
        } catch (_: Exception) {
          false
        }
        if (!pushed) {
          artifactManager?.unregisterArtifact(pathStr)
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "File '$pathStr' was written to the device-side mirror but failed to sync into the E2B microVM. Verify the E2B API Key and session state, then retry.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        val summary = if (append) {
          "Successfully appended ${content.length} character(s) to '$pathStr' (total size: ${file.length()} bytes) in the E2B microVM (mirrored to the device-side artifact workspace)."
        } else {
          "Successfully saved '$pathStr' (${file.length()} bytes) in the E2B microVM (mirrored to the device-side artifact workspace)."
        }

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = summary,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = summary,
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
          error = se.message,
          errorType = ToolErrorCode.PATH_NOT_ALLOWED,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to write file '$pathStr': ${e.message}",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }

  internal fun compileMarkdownToDocx(markdown: String, outputFile: File) {
    val builder = DocxBuilder()
    val lines = markdown.lines()
    var i = 0

    while (i < lines.size) {
      val line = lines[i]
      val trimmed = line.trim()

      if (trimmed.isEmpty()) {
        i++
        continue
      }

      // Check if line is part of a markdown table (e.g. "| Col 1 | Col 2 |")
      if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.count { it == '|' } >= 2) {
        val tableLines = mutableListOf<String>()
        while (i < lines.size) {
          val currentTrimmed = lines[i].trim()
          if (currentTrimmed.startsWith("|") && currentTrimmed.endsWith("|") && currentTrimmed.count { it == '|' } >= 2) {
            tableLines.add(currentTrimmed)
            i++
          } else {
            break
          }
        }

        if (tableLines.isNotEmpty()) {
          addParsedTableToDocx(builder, tableLines)
        }
        continue
      }

      if (trimmed.startsWith("# ")) {
        builder.addHeading(trimmed.removePrefix("# ").trim(), level = 1)
      } else if (trimmed.startsWith("## ")) {
        builder.addHeading(trimmed.removePrefix("## ").trim(), level = 2)
      } else if (trimmed.startsWith("### ")) {
        builder.addHeading(trimmed.removePrefix("### ").trim(), level = 3)
      } else {
        builder.addParagraph(trimmed)
      }
      i++
    }

    builder.save(outputFile)
  }

  private fun addParsedTableToDocx(builder: DocxBuilder, tableLines: List<String>) {
    fun parseCells(line: String): List<String> {
      val stripped = line.trim().removePrefix("|").removeSuffix("|")
      return stripped.split("|").map { it.trim() }
    }

    fun isDivider(cells: List<String>): Boolean {
      return cells.isNotEmpty() && cells.all { cell ->
        val clean = cell.replace(":", "").replace("-", "").trim()
        clean.isEmpty()
      }
    }

    if (tableLines.isEmpty()) return

    val firstRowCells = parseCells(tableLines[0])
    val hasDivider = tableLines.size >= 2 && isDivider(parseCells(tableLines[1]))

    val headers: List<String>
    val dataRowLines: List<String>

    if (hasDivider) {
      headers = firstRowCells
      dataRowLines = tableLines.drop(2).filter { !isDivider(parseCells(it)) }
    } else {
      headers = firstRowCells
      dataRowLines = tableLines.drop(1).filter { !isDivider(parseCells(it)) }
    }

    val parsedRows = dataRowLines.map { parseCells(it) }
    val maxCols = maxOf(headers.size, parsedRows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)

    val paddedHeaders = if (headers.size < maxCols) {
      headers + List(maxCols - headers.size) { "" }
    } else {
      headers.take(maxCols)
    }

    val paddedRows = parsedRows.map { row ->
      if (row.size < maxCols) {
        row + List(maxCols - row.size) { "" }
      } else {
        row.take(maxCols)
      }
    }

    builder.addTable(paddedHeaders, paddedRows)
  }

  internal fun compileContentToXlsx(content: String, outputFile: File) {
    val builder = XlsxBuilder()
    val lines = content.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) {
      builder.save(outputFile)
      return
    }

    val isMarkdownTable = lines.any { it.trim().startsWith("|") && it.trim().endsWith("|") }
    if (isMarkdownTable) {
      val tableLines = lines.filter { it.trim().startsWith("|") && it.trim().endsWith("|") }
      fun parseCells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
      fun isDivider(cells: List<String>): Boolean =
        cells.isNotEmpty() && cells.all { cell ->
          val clean = cell.replace(":", "").replace("-", "").trim()
          clean.isEmpty()
        }

      if (tableLines.isNotEmpty()) {
        val firstRow = parseCells(tableLines[0])
        val hasDivider = tableLines.size >= 2 && isDivider(parseCells(tableLines[1]))
        val headers = firstRow
        val dataLines = if (hasDivider) tableLines.drop(2).filter { !isDivider(parseCells(it)) } else tableLines.drop(1)
        builder.addHeader(headers)
        dataLines.forEach { builder.addRow(parseCells(it)) }
      }
    } else {
      // CSV or TSV format
      val delimiter = if (lines[0].contains("\t")) "\t" else ","
      val rows = lines.map { line ->
        line.split(delimiter).map { it.trim().removeSurrounding("\"") }
      }
      if (rows.isNotEmpty()) {
        builder.addHeader(rows[0])
        rows.drop(1).forEach { builder.addRow(it) }
      }
    }

    builder.save(outputFile)
  }
}

/**
 * Precision in-place substring patching of an existing workspace file.
 */
class EditFileTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" }
) : Tool {
  override val name: String = "edit_file"
  override val description: String =
    "Edit an existing file in the sandbox workspace by replacing target text with replacement text. Verifies that the target text exists before modifying."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path of the file relative to the workspace.",
        required = true
      ),
      ToolParameter(
        name = "targetContent",
        type = "string",
        description = "The exact substring in the file to be replaced.",
        required = true
      ),
      ToolParameter(
        name = "replacementContent",
        type = "string",
        description = "The new text that will replace targetContent.",
        required = true
      ),
      ToolParameter(
        name = "replaceAll",
        type = "boolean",
        description = "If true, replaces all occurrences. If false, replaces only the first occurrence.",
        required = false,
        defaultValue = false
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.WORKSPACE_WRITE)

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val pathStr = arguments["path"]?.toString()?.trim() ?: ""
      val targetContent = arguments["targetContent"]?.toString() ?: ""
      val replacementContent = arguments["replacementContent"]?.toString() ?: ""
      val replaceAll = arguments["replaceAll"] as? Boolean ?: false

      if (pathStr.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'path' cannot be blank.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
      if (targetContent.isEmpty()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'targetContent' cannot be empty.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      try {
        val provider = sandboxProvider
        if (provider == null || !provider.isRemote) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "E2B Cloud Sandbox is not configured or offline. Configure your E2B API Key in Settings.",
            errorType = ToolErrorCode.SANDBOX_INFRA_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }
        val file = PathSecurity.resolveSafe(workspaceDir, pathStr)
        if (!file.exists() || !file.isFile) {
          try {
            provider.pullFileFromRemote(sessionIdProvider(), pathStr, file)
          } catch (_: Exception) {}
        }
        if (!file.exists() || !file.isFile) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "File '$pathStr' does not exist in the E2B microVM workspace.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        val originalText = file.readText(StandardCharsets.UTF_8)
        var newText: String? = null

        if (originalText.contains(targetContent)) {
          newText = if (replaceAll) {
            originalText.replace(targetContent, replacementContent)
          } else {
            originalText.replaceFirst(targetContent, replacementContent)
          }
        } else {
          // Normalize line endings and attempt match
          val normOriginal = originalText.replace("\r\n", "\n")
          val normTarget = targetContent.replace("\r\n", "\n")
          val normReplacement = replacementContent.replace("\r\n", "\n")

          if (normOriginal.contains(normTarget)) {
            newText = if (replaceAll) {
              normOriginal.replace(normTarget, normReplacement)
            } else {
              normOriginal.replaceFirst(normTarget, normReplacement)
            }
          } else {
            // Fuzzy line-by-line whitespace-tolerant match
            val fuzzyReplaced = tryFuzzyLineReplace(normOriginal, normTarget, normReplacement, replaceAll)
            if (fuzzyReplaced != null) {
              newText = fuzzyReplaced
            }
          }
        }

        if (newText == null) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Target content not found in '$pathStr'. Please ensure matching context or use 'apply_patch' for diff-based editing.",
            errorType = ToolErrorCode.INVALID_ARGUMENTS,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        file.writeText(newText, StandardCharsets.UTF_8)
        artifactManager.registerArtifact(pathStr, createdByCallId = callId)

        val relPath = PathSecurity.getRelativePath(workspaceDir, file)
        val pushed = try {
          provider.pushFileToRemote(sessionIdProvider(), relPath, file)
        } catch (_: Exception) {
          false
        }
        if (!pushed) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Edit applied to the device-side mirror but failed to sync into the E2B microVM. Verify the E2B API Key and session state, then retry.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        val summary = "Successfully updated '$pathStr' in the E2B microVM workspace."
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = summary,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = summary,
          exitCode = 0,
          artifacts = artifactManager.getArtifacts()
        )
      } catch (se: SecurityException) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = se.message,
          errorType = ToolErrorCode.PATH_NOT_ALLOWED,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to edit file '$pathStr': ${e.message}",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }

  private fun tryFuzzyLineReplace(
    original: String,
    target: String,
    replacement: String,
    replaceAll: Boolean
  ): String? {
    val origLines = original.lines()
    val targetLines = target.lines().filter { it.isNotBlank() }
    if (targetLines.isEmpty() || targetLines.size > origLines.size) return null

    var matchStart = -1
    var matchEnd = -1

    for (i in 0..origLines.size - targetLines.size) {
      var matched = true
      for (j in targetLines.indices) {
        val oLine = origLines[i + j].trim()
        val tLine = targetLines[j].trim()
        if (oLine != tLine) {
          matched = false
          break
        }
      }
      if (matched) {
        matchStart = i
        matchEnd = i + targetLines.size
        break
      }
    }

    if (matchStart >= 0 && matchEnd >= matchStart) {
      val before = origLines.take(matchStart).joinToString("\n")
      val after = origLines.drop(matchEnd).joinToString("\n")
      val res = buildString {
        if (before.isNotEmpty()) {
          append(before)
          append("\n")
        }
        append(replacement)
        if (after.isNotEmpty()) {
          append("\n")
          append(after)
        }
      }
      return res
    }
    return null
  }
}

/**
 * Applies a unified diff or search/replace patch to a workspace file.
 * Enables the agent to quickly modify and fix code with pinpoint accuracy.
 */
class ApplyPatchTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" }
) : Tool {
  override val name: String = "apply_patch"
  override val description: String =
    "Apply a patch or diff to a file in the sandbox workspace to fix or update code quickly. Supports both standard Unified Diff format (--- / +++ / @@ ... @@) and Search/Replace block format (<<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE). Automatically matches context lines, trims whitespace differences, applies changes, and saves the file with authoritative verification."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path of the target file relative to workspace (e.g. 'src/app.py', 'index.js'). Optional if specified in the unified diff header (e.g. '+++ b/path').",
        required = false
      ),
      ToolParameter(
        name = "patch",
        type = "string",
        description = "The diff or patch content to apply. Can be unified diff format or <<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE blocks.",
        required = true
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.WORKSPACE_WRITE)

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      var pathStr = arguments["path"]?.toString()?.trim() ?: ""
      val patchContent = arguments["patch"]?.toString() ?: ""

      if (patchContent.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'patch' cannot be blank.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      // If path was omitted, attempt extraction from patch headers
      if (pathStr.isBlank()) {
        val extracted = extractPathFromPatch(patchContent)
        if (extracted != null) {
          pathStr = extracted
        } else {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Parameter 'path' was not provided and could not be inferred from patch headers.",
            errorType = ToolErrorCode.INVALID_ARGUMENTS,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }
      }

      try {
        val provider = sandboxProvider
        if (provider == null || !provider.isRemote) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "E2B Cloud Sandbox is not configured or offline. Configure your E2B API Key in Settings.",
            errorType = ToolErrorCode.SANDBOX_INFRA_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }
        val file = PathSecurity.resolveSafe(workspaceDir, pathStr)
        if (!file.exists() || !file.isFile) {
          try {
            provider.pullFileFromRemote(sessionIdProvider(), pathStr, file)
          } catch (_: Exception) {}
        }
        if (!file.exists() || !file.isFile) {
          // If file does not exist, check if the patch creates a new file
          if (isNewFilePatch(patchContent)) {
            val newContent = extractNewFileContent(patchContent)
            file.parentFile?.mkdirs()
            file.writeText(newContent, StandardCharsets.UTF_8)
            artifactManager.registerArtifact(pathStr, createdByCallId = callId)

            val relPath = PathSecurity.getRelativePath(workspaceDir, file)
            val pushed = try {
              provider.pushFileToRemote(sessionIdProvider(), relPath, file)
            } catch (_: Exception) {
              false
            }
            if (!pushed) {
              return@withContext ToolResult(
                callId = callId,
                toolName = name,
                status = ToolStatus.FAILED,
                arguments = arguments,
                output = null,
                error = "New file '$pathStr' was written to the device-side mirror but failed to sync into the E2B microVM. Verify the E2B API Key and session state, then retry.",
                errorType = ToolErrorCode.SANDBOX_ERROR,
                duration = System.currentTimeMillis() - startTime,
                exitCode = 1
              )
            }

            val msg = "Successfully created new file '$pathStr' from patch (${newContent.length} bytes) in the E2B microVM workspace."
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.SUCCEEDED,
              arguments = arguments,
              output = msg,
              error = null,
              duration = System.currentTimeMillis() - startTime,
              stdout = msg,
              exitCode = 0,
              artifacts = artifactManager.getArtifacts()
            )
          }

          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Target file '$pathStr' does not exist in the E2B microVM workspace.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        val originalText = file.readText(StandardCharsets.UTF_8).replace("\r\n", "\n")
        val (patchedText, changesSummary) = applyPatchToText(originalText, patchContent)

        if (patchedText == null) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Failed to apply patch to '$pathStr': $changesSummary",
            errorType = ToolErrorCode.INVALID_ARGUMENTS,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        file.writeText(patchedText, StandardCharsets.UTF_8)
        artifactManager.registerArtifact(pathStr, createdByCallId = callId)

        val relPath = PathSecurity.getRelativePath(workspaceDir, file)
        val pushed = try {
          provider.pushFileToRemote(sessionIdProvider(), relPath, file)
        } catch (_: Exception) {
          false
        }
        if (!pushed) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Patch applied to the device-side mirror but failed to sync into the E2B microVM. Verify the E2B API Key and session state, then retry.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        val outMsg = "Successfully applied patch to '$pathStr' in the E2B microVM workspace: $changesSummary"
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = outMsg,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = outMsg,
          exitCode = 0,
          artifacts = artifactManager.getArtifacts()
        )
      } catch (se: SecurityException) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = se.message,
          errorType = ToolErrorCode.PATH_NOT_ALLOWED,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Error applying patch to '$pathStr': ${e.message}",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }

  companion object {
    fun extractPathFromPatch(patch: String): String? {
      for (line in patch.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("+++ b/") || trimmed.startsWith("+++ ")) {
          val p = trimmed.removePrefix("+++ b/").removePrefix("+++ ").substringBefore("\t").trim()
          if (p.isNotBlank() && p != "/dev/null") return p
        }
        if (trimmed.startsWith("--- a/") || trimmed.startsWith("--- ")) {
          val p = trimmed.removePrefix("--- a/").removePrefix("--- ").substringBefore("\t").trim()
          if (p.isNotBlank() && p != "/dev/null") return p
        }
      }
      return null
    }

    fun isNewFilePatch(patch: String): Boolean {
      return patch.contains("--- /dev/null") || patch.contains("new file mode")
    }

    fun extractNewFileContent(patch: String): String {
      val sb = StringBuilder()
      for (line in patch.lines()) {
        if (line.startsWith("+") && !line.startsWith("+++")) {
          sb.appendLine(line.substring(1))
        }
      }
      return sb.toString()
    }

    /**
     * Applies search/replace blocks or unified diff hunks to text.
     */
    fun applyPatchToText(original: String, patch: String): Pair<String?, String> {
      val normPatch = patch.replace("\r\n", "\n")

      // 1. Try SEARCH/REPLACE block format
      if (normPatch.contains("<<<<<<< SEARCH") && normPatch.contains(">>>>>>> REPLACE")) {
        return applySearchReplaceBlocks(original, normPatch)
      }

      // 2. Try Unified Diff format
      if (normPatch.contains("@@")) {
        return applyUnifiedDiffHunks(original, normPatch)
      }

      // 3. Fallback: single search/replace separated by =======
      if (normPatch.contains("=======")) {
        val parts = normPatch.split("=======", limit = 2)
        val search = parts[0].trim()
        val replace = parts[1].trim()
        if (original.contains(search)) {
          return Pair(original.replace(search, replace), "1 replacement block applied.")
        }
      }

      return Pair(null, "Unrecognized patch format. Provide either Unified Diff (@@ ... @@) or SEARCH/REPLACE blocks (<<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE).")
    }

    private fun applySearchReplaceBlocks(original: String, patch: String): Pair<String?, String> {
      var currentText = original
      val pattern = Regex("""<<<<<<< SEARCH\n(.*?)\n=======\n(.*?)\n>>>>>>> REPLACE""", RegexOption.DOT_MATCHES_ALL)
      val matches = pattern.findAll(patch).toList()

      if (matches.isEmpty()) {
        return Pair(null, "No valid <<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE blocks found.")
      }

      var appliedCount = 0
      for (match in matches) {
        val search = match.groupValues[1]
        val replace = match.groupValues[2]

        if (currentText.contains(search)) {
          currentText = currentText.replaceFirst(search, replace)
          appliedCount++
        } else {
          // Whitespace-normalized line matching
          val origLines = currentText.lines()
          val searchLines = search.lines()
          var foundIndex = -1

          for (i in 0..origLines.size - searchLines.size) {
            var allMatch = true
            for (j in searchLines.indices) {
              if (origLines[i + j].trim() != searchLines[j].trim()) {
                allMatch = false
                break
              }
            }
            if (allMatch) {
              foundIndex = i
              break
            }
          }

          if (foundIndex >= 0) {
            val before = origLines.take(foundIndex).joinToString("\n")
            val after = origLines.drop(foundIndex + searchLines.size).joinToString("\n")
            currentText = buildString {
              if (before.isNotEmpty()) {
                append(before)
                append("\n")
              }
              append(replace)
              if (after.isNotEmpty()) {
                append("\n")
                append(after)
              }
            }
            appliedCount++
          } else {
            return Pair(null, "Could not find SEARCH block in file: '${search.take(80)}...'")
          }
        }
      }

      return Pair(currentText, "Applied $appliedCount search/replace block(s).")
    }

    private fun applyUnifiedDiffHunks(original: String, patch: String): Pair<String?, String> {
      val lines = original.lines().toMutableList()
      val patchLines = patch.lines()
      var hunkCount = 0
      var addedLines = 0
      var removedLines = 0

      var i = 0
      while (i < patchLines.size) {
        val line = patchLines[i]
        if (line.startsWith("@@")) {
          // Parse hunk header: @@ -oldStart,oldLen +newStart,newLen @@
          val headerMatch = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""").find(line)
          val expectedStart = headerMatch?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: 0

          val oldHunkLines = mutableListOf<String>()
          val newHunkLines = mutableListOf<String>()

          i++
          while (i < patchLines.size && !patchLines[i].startsWith("@@")) {
            val hLine = patchLines[i]
            when {
              hLine.startsWith("-") -> {
                oldHunkLines.add(hLine.substring(1))
                removedLines++
              }
              hLine.startsWith("+") -> {
                newHunkLines.add(hLine.substring(1))
                addedLines++
              }
              hLine.startsWith(" ") -> {
                oldHunkLines.add(hLine.substring(1))
                newHunkLines.add(hLine.substring(1))
              }
              hLine.isEmpty() -> {
                oldHunkLines.add("")
                newHunkLines.add("")
              }
              hLine.startsWith("\\") -> {
                // Ignore "\ No newline at end of file"
              }
              else -> {
                oldHunkLines.add(hLine)
                newHunkLines.add(hLine)
              }
            }
            i++
          }

          // Locate oldHunkLines in lines
          var matchPos = -1
          // Try near expectedStart first
          val searchStart = (expectedStart - 5).coerceAtLeast(0)
          val searchEnd = (expectedStart + 20).coerceAtMost(lines.size - oldHunkLines.size)

          for (pos in searchStart..searchEnd) {
            if (pos < 0 || pos + oldHunkLines.size > lines.size) continue
            var ok = true
            for (k in oldHunkLines.indices) {
              if (lines[pos + k].trim() != oldHunkLines[k].trim()) {
                ok = false
                break
              }
            }
            if (ok) {
              matchPos = pos
              break
            }
          }

          // Full file search if not found near expected start
          if (matchPos == -1) {
            for (pos in 0..(lines.size - oldHunkLines.size)) {
              var ok = true
              for (k in oldHunkLines.indices) {
                if (lines[pos + k].trim() != oldHunkLines[k].trim()) {
                  ok = false
                  break
                }
              }
              if (ok) {
                matchPos = pos
                break
              }
            }
          }

          if (matchPos == -1) {
            return Pair(null, "Hunk ${hunkCount + 1} failed to match target lines.")
          }

          // Replace old lines with new lines
          for (k in oldHunkLines.indices) {
            lines.removeAt(matchPos)
          }
          lines.addAll(matchPos, newHunkLines)
          hunkCount++
        } else {
          i++
        }
      }

      if (hunkCount == 0) {
        return Pair(null, "No unified diff hunks found in patch.")
      }

      return Pair(lines.joinToString("\n"), "Applied $hunkCount hunk(s) (+$addedLines, -$removedLines lines).")
    }
  }
}

/**
 * Generates unified diff between two files or between a file and modified content.
 */
class DiffFilesTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager? = null
) : Tool {
  override val name: String = "diff_files"
  override val description: String =
    "Generate a unified diff between two files or between a file and modified content. Displays line-by-line differences with standard diff format."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "originalPath",
        type = "string",
        description = "Path of the original file relative to workspace.",
        required = true
      ),
      ToolParameter(
        name = "modifiedPath",
        type = "string",
        description = "Optional path of the modified file relative to workspace.",
        required = false
      ),
      ToolParameter(
        name = "modifiedContent",
        type = "string",
        description = "Optional replacement content string to compare against originalPath.",
        required = false
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.READ_ONLY)

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val origPath = (arguments["originalPath"] ?: arguments["file1"] ?: arguments["path"])?.toString()?.trim() ?: ""
      val modPath = (arguments["modifiedPath"] ?: arguments["file2"])?.toString()?.trim()
      val modContent = (arguments["modifiedContent"] ?: arguments["content2"] ?: arguments["content"])?.toString()

      if (origPath.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'originalPath' cannot be blank.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      try {
        val origFile = PathSecurity.resolveSafe(workspaceDir, origPath)
        if (!origFile.exists() || !origFile.isFile) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Original file '$origPath' does not exist in workspace.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        val origLines = origFile.readLines(StandardCharsets.UTF_8)
        val modLines: List<String> = when {
          !modPath.isNullOrBlank() -> {
            val modFile = PathSecurity.resolveSafe(workspaceDir, modPath)
            if (!modFile.exists() || !modFile.isFile) {
              return@withContext ToolResult(
                callId = callId,
                toolName = name,
                status = ToolStatus.FAILED,
                arguments = arguments,
                output = null,
                error = "Modified file '$modPath' does not exist in workspace.",
                errorType = ToolErrorCode.SANDBOX_ERROR,
                duration = System.currentTimeMillis() - startTime,
                exitCode = 1
              )
            }
            modFile.readLines(StandardCharsets.UTF_8)
          }
          modContent != null -> modContent.lines()
          else -> {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Must provide either 'modifiedPath' or 'modifiedContent'.",
              errorType = ToolErrorCode.INVALID_ARGUMENTS,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }
        }

        val targetLabel = modPath ?: "$origPath (modified)"
        val diffOutput = computeSimpleUnifiedDiff(origLines, modLines, origPath, targetLabel)

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = diffOutput,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = diffOutput,
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
          error = se.message,
          errorType = ToolErrorCode.PATH_NOT_ALLOWED,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to compute diff: ${e.message}",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }

  companion object {
    fun computeSimpleUnifiedDiff(
      a: List<String>,
      b: List<String>,
      fromFile: String,
      toFile: String
    ): String {
      if (a == b) {
        return "Files are identical (0 differences)."
      }

      val sb = StringBuilder()
      sb.appendLine("--- $fromFile")
      sb.appendLine("+++ $toFile")

      // Find first difference and last difference
      var firstDiff = 0
      while (firstDiff < a.size && firstDiff < b.size && a[firstDiff] == b[firstDiff]) {
        firstDiff++
      }

      var aEnd = a.size - 1
      var bEnd = b.size - 1
      while (aEnd > firstDiff && bEnd > firstDiff && a[aEnd] == b[bEnd]) {
        aEnd--
        bEnd--
      }

      val ctxStart = (firstDiff - 3).coerceAtLeast(0)
      val ctxAEnd = (aEnd + 3).coerceAtMost(a.size - 1)
      val ctxBEnd = (bEnd + 3).coerceAtMost(b.size - 1)

      val aLen = if (ctxAEnd >= ctxStart) (ctxAEnd - ctxStart + 1) else 0
      val bLen = if (ctxBEnd >= ctxStart) (ctxBEnd - ctxStart + 1) else 0

      sb.appendLine("@@ -${ctxStart + 1},$aLen +${ctxStart + 1},$bLen @@")

      // Leading context
      for (k in ctxStart until firstDiff) {
        if (k < a.size) sb.appendLine(" ${a[k]}")
      }

      // Deletions
      for (k in firstDiff..aEnd) {
        if (k < a.size) sb.appendLine("-${a[k]}")
      }

      // Additions
      for (k in firstDiff..bEnd) {
        if (k < b.size) sb.appendLine("+${b[k]}")
      }

      // Trailing context
      val trailStart = aEnd + 1
      for (k in trailStart..ctxAEnd) {
        if (k < a.size) sb.appendLine(" ${a[k]}")
      }

      return sb.toString().trimEnd()
    }
  }
}

/**
 * Deletes a file from the workspace.
 */
class DeleteFileTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager
) : Tool {
  override val name: String = "delete_file"
  override val description: String = "Delete a file from the sandbox workspace."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path of the file to delete relative to the workspace.",
        required = true
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.WORKSPACE_WRITE)

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val pathStr = arguments["path"]?.toString()?.trim() ?: ""

      if (pathStr.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'path' cannot be blank.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }

      try {
        val file = PathSecurity.resolveSafe(workspaceDir, pathStr)
        if (!file.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "File '$pathStr' does not exist.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }
        if (file.isDirectory) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "'$pathStr' is a directory. Use 'delete_directory' to delete directories.",
            errorType = ToolErrorCode.INVALID_ARGUMENTS,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        val deleted = file.delete()
        if (deleted) {
          artifactManager.unregisterArtifact(pathStr)
          val summary = "Successfully deleted '$pathStr' from workspace."
          ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.SUCCEEDED,
            arguments = arguments,
            output = summary,
            error = null,
            duration = System.currentTimeMillis() - startTime,
            stdout = summary,
            exitCode = 0,
            artifacts = artifactManager.getArtifacts()
          )
        } else {
          ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Failed to delete file '$pathStr'.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }
      } catch (se: SecurityException) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = se.message,
          errorType = ToolErrorCode.PATH_NOT_ALLOWED,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to delete file: ${e.message}",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }
}

/**
 * Moves or renames a file or directory within the workspace.
 */
class MoveFileTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager
) : Tool {
  override val name: String = "move_file"
  override val description: String = "Move or rename a file or directory within the sandbox workspace."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "sourcePath",
        type = "string",
        description = "Source file or directory path relative to workspace.",
        required = true
      ),
      ToolParameter(
        name = "destinationPath",
        type = "string",
        description = "Destination path relative to workspace.",
        required = true
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.WORKSPACE_WRITE)

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val src = arguments["sourcePath"]?.toString()?.trim() ?: ""
      val dst = arguments["destinationPath"]?.toString()?.trim() ?: ""

      try {
        val srcFile = PathSecurity.resolveSafe(workspaceDir, src)
        val dstFile = PathSecurity.resolveSafe(workspaceDir, dst)

        if (!srcFile.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Source '$src' does not exist.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        dstFile.parentFile?.mkdirs()
        val success = srcFile.renameTo(dstFile)
        if (!success) {
          srcFile.copyRecursively(dstFile, overwrite = true)
          srcFile.deleteRecursively()
        }

        artifactManager.unregisterArtifact(src)
        artifactManager.registerArtifact(dst, createdByCallId = callId)

        val summary = "Successfully moved '$src' to '$dst' in workspace."
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = summary,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = summary,
          exitCode = 0,
          artifacts = artifactManager.getArtifacts()
        )
      } catch (se: SecurityException) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = se.message,
          errorType = ToolErrorCode.PATH_NOT_ALLOWED,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to move file: ${e.message}",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }
}

/**
 * Copies a file or directory within the workspace.
 */
class CopyFileTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager
) : Tool {
  override val name: String = "copy_file"
  override val description: String = "Copy a file or directory within the sandbox workspace."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "sourcePath",
        type = "string",
        description = "Source file or directory path relative to workspace.",
        required = true
      ),
      ToolParameter(
        name = "destinationPath",
        type = "string",
        description = "Destination path relative to workspace.",
        required = true
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.WORKSPACE_WRITE)

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val src = arguments["sourcePath"]?.toString()?.trim() ?: ""
      val dst = arguments["destinationPath"]?.toString()?.trim() ?: ""

      try {
        val srcFile = PathSecurity.resolveSafe(workspaceDir, src)
        val dstFile = PathSecurity.resolveSafe(workspaceDir, dst)

        if (!srcFile.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Source '$src' does not exist.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        dstFile.parentFile?.mkdirs()
        srcFile.copyRecursively(dstFile, overwrite = true)
        artifactManager.registerArtifact(dst, createdByCallId = callId)

        val summary = "Successfully copied '$src' to '$dst' in workspace."
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = summary,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = summary,
          exitCode = 0,
          artifacts = artifactManager.getArtifacts()
        )
      } catch (se: SecurityException) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = se.message,
          errorType = ToolErrorCode.PATH_NOT_ALLOWED,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to copy file: ${e.message}",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }
}

/**
 * Creates a directory within the workspace.
 */
class CreateDirectoryTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager? = null
) : Tool {
  override val name: String = "create_directory"
  override val description: String = "Create a new directory in the sandbox workspace."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path of the directory to create relative to workspace.",
        required = true
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.WORKSPACE_WRITE)

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val pathStr = arguments["path"]?.toString()?.trim() ?: ""

      try {
        val dir = PathSecurity.resolveSafe(workspaceDir, pathStr)
        dir.mkdirs()
        val summary = "Successfully created directory '$pathStr' in workspace."
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = summary,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = summary,
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
          error = se.message,
          errorType = ToolErrorCode.PATH_NOT_ALLOWED,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to create directory: ${e.message}",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }
}

/**
 * Deletes a directory from the workspace.
 */
class DeleteDirectoryTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager
) : Tool {
  override val name: String = "delete_directory"
  override val description: String = "Delete a directory from the sandbox workspace."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path of the directory to delete relative to workspace.",
        required = true
      ),
      ToolParameter(
        name = "recursive",
        type = "boolean",
        description = "Whether to recursively delete contents (default: false).",
        required = false,
        defaultValue = false
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.WORKSPACE_WRITE)

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val pathStr = arguments["path"]?.toString()?.trim() ?: ""
      val recursive = arguments["recursive"] as? Boolean ?: false

      try {
        val dir = PathSecurity.resolveSafe(workspaceDir, pathStr)
        if (!dir.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Directory '$pathStr' does not exist.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        if (recursive) {
          dir.deleteRecursively()
        } else {
          val success = dir.delete()
          if (!success) {
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Directory '$pathStr' is not empty. Set 'recursive=true' to delete recursively.",
              errorType = ToolErrorCode.INVALID_ARGUMENTS,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }
        }

        artifactManager.syncWorkspace()
        val summary = "Successfully deleted directory '$pathStr'."
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = summary,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = summary,
          exitCode = 0,
          artifacts = artifactManager.getArtifacts()
        )
      } catch (se: SecurityException) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = se.message,
          errorType = ToolErrorCode.PATH_NOT_ALLOWED,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to delete directory: ${e.message}",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }
}

/**
 * Checks whether a file or directory exists.
 */
class FileExistsTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager? = null,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" }
) : Tool {
  override val name: String = "file_exists"
  override val description: String = "Check whether a file or directory exists inside the E2B Cloud MicroVM (/home/user)."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path relative to workspace.",
        required = true
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.READ_ONLY)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val pathStr = arguments["path"]?.toString()?.trim() ?: ""

      try {
        val file = PathSecurity.resolveSafe(workspaceDir, pathStr)
        if (!file.exists()) {
          val provider = sandboxProvider
          if (provider != null && provider.isRemote) {
            try {
              provider.pullFileFromRemote(sessionIdProvider(), pathStr, file)
            } catch (_: Exception) {}
          }
        }
        val exists = file.exists()
        val type = if (exists) (if (file.isDirectory) "directory" else "file") else "none"

        val json = JSONObject().apply {
          put("exists", exists)
          put("type", type)
          put("path", pathStr)
          if (exists && file.isFile) {
            put("sizeBytes", file.length())
          }
        }.toString()

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = json,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = json,
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
          error = se.message,
          errorType = ToolErrorCode.PATH_NOT_ALLOWED,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }
}

/**
 * Inspects authoritative filesystem metadata for a file or directory.
 */
class FileMetadataTool(
  val workspaceDir: File,
  private val artifactManager: ArtifactManager? = null,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" }
) : Tool {
  override val name: String = "file_metadata"
  override val description: String = "Inspect authoritative filesystem metadata (size, last modified, permissions) for a file inside the E2B Cloud MicroVM (/home/user)."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path relative to workspace.",
        required = true
      )
    )
  )

  override val category: ToolCategory = ToolCategory.FILESYSTEM
  override val target: ToolTarget = ToolTarget.SANDBOX_FILESYSTEM
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.READ_ONLY)
  override val owningBackend: ExecutionEnvironmentType = ExecutionEnvironmentType.E2B_CLOUD

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val pathStr = arguments["path"]?.toString()?.trim() ?: ""

      try {
        val file = PathSecurity.resolveSafe(workspaceDir, pathStr)
        if (!file.exists()) {
          val provider = sandboxProvider
          if (provider != null && provider.isRemote) {
            try {
              provider.pullFileFromRemote(sessionIdProvider(), pathStr, file)
            } catch (_: Exception) {}
          }
        }
        if (!file.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Path '$pathStr' does not exist in the E2B microVM workspace.",
            errorType = ToolErrorCode.SANDBOX_ERROR,
            duration = System.currentTimeMillis() - startTime,
            exitCode = 1
          )
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val formattedDate = dateFormat.format(Date(file.lastModified()))

        val json = JSONObject().apply {
          put("path", pathStr)
          put("exists", true)
          put("isDirectory", file.isDirectory)
          put("isFile", file.isFile)
          put("sizeBytes", file.length())
          put("readable", file.canRead())
          put("writable", file.canWrite())
          put("executable", file.canExecute())
          put("lastModified", formattedDate)
        }.toString(2)

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = json,
          error = null,
          duration = System.currentTimeMillis() - startTime,
          stdout = json,
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
          error = se.message,
          errorType = ToolErrorCode.PATH_NOT_ALLOWED,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }
}
