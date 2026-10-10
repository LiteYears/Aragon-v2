package com.example.agent.core

import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.RunCodeRequest
import com.alibaba.opensandbox.sandbox.Sandbox
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Executes a shell command inside the OpenSandbox runtime.
 */
class TerminalTool(private val sandbox: Sandbox) : Tool {
  override val name: String = "terminal"
  override val description: String =
    "Execute a shell command within the OpenSandbox workspace. Returns stdout, stderr, and process exit code."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "command",
        type = "string",
        description = "The shell command to run (e.g., 'ls -la', 'python3 script.py', 'head -n 20 data.csv', 'cat report.md').",
        required = true
      ),
      ToolParameter(
        name = "timeoutSeconds",
        type = "number",
        description = "Maximum execution time in seconds (default: 30).",
        required = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val command = arguments["command"]?.toString() ?: ""
      val timeoutSeconds = (arguments["timeoutSeconds"] as? Number)?.toLong() ?: 30L

      if (command.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Command cannot be blank",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Command cannot be blank",
          exitCode = 1
        )
      }

      val execReq = RunCommandRequest.builder()
        .command(command)
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .build()

      val exec = sandbox.commands().run(execReq)
      val duration = System.currentTimeMillis() - startTime
      val exitCode = exec.exitCode ?: 0
      val stdout = exec.stdoutText().trim()
      val stderr = exec.stderrText().trim()

      val status = if (exitCode == 0) ToolStatus.SUCCEEDED else ToolStatus.FAILED
      val errorText = if (exitCode != 0) {
        val baseErr = if (stderr.isNotBlank()) stderr else exec.error?.value ?: "Command exited with non-zero exit code: $exitCode"
        val cmdTrimmed = command.trim()
        if (cmdTrimmed.startsWith("which apt") || cmdTrimmed.startsWith("which pkg") || cmdTrimmed.startsWith("apt") || cmdTrimmed.startsWith("pkg")) {
          "$baseErr\n[OPENSANDBOX NOTE: Desktop package managers ('apt', 'pkg') do not exist on Android. Use 'install_package(name=\"...\")' or download binaries to bin/.]"
        } else if (exitCode == 127 || baseErr.contains("not found") || baseErr.contains("inaccessible") || (cmdTrimmed.startsWith("which ") && stdout.isBlank())) {
          "$baseErr\n[OPENSANDBOX NOTE: The requested binary or interpreter is not in the sandbox PATH. Call 'install_package(name=\"<tool>\")' to install it directly into workspace/bin/ and add to PATH. For Word documents or data processing, use native tools: 'create_docx', 'json_processor', 'csv_processor', 'write_file'.]"
        } else {
          baseErr
        }
      } else {
        if (stderr.isNotBlank()) stderr else null
      }

      val artifacts = sandbox.listArtifacts()

      ToolResult(
        callId = callId,
        toolName = name,
        status = status,
        arguments = arguments,
        output = stdout.ifEmpty { null },
        error = errorText,
        artifacts = artifacts,
        duration = duration,
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode
      )
    }
}

/**
 * Reads the text content of a file in OpenSandbox.
 */
class ReadFileTool(private val sandbox: Sandbox) : Tool {
  override val name: String = "read_file"
  override val description: String =
    "Read the text content of a file in the OpenSandbox workspace. Specify relative path."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Relative path of the file to read (e.g., 'data.csv', 'src/main.py').",
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

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val path = arguments["path"]?.toString() ?: ""
      val offset = (arguments["offset"] as? Number)?.toInt()
      val limit = (arguments["limit"] as? Number)?.toInt()

      try {
        val content = sandbox.filesystem().readFile(
          path = path,
          encoding = "UTF-8",
          range = null,
          offset = offset,
          limit = limit
        )

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = content,
          error = null,
          artifacts = sandbox.listArtifacts(),
          duration = System.currentTimeMillis() - startTime,
          stdout = content,
          stderr = null,
          exitCode = 0
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to read file '$path': ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Failed to read file '$path': ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Writes text content to a file in OpenSandbox.
 */
class WriteFileTool(private val sandbox: Sandbox) : Tool {
  override val name: String = "write_file"
  override val description: String =
    "Create or overwrite a file in the OpenSandbox workspace with the provided content. Performs byte-level validation."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path where the file should be saved (e.g., 'summary.md', 'output.json', 'script.py').",
        required = true
      ),
      ToolParameter(
        name = "content",
        type = "string",
        description = "The text content to write into the file.",
        required = true
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val path = arguments["path"]?.toString() ?: ""
      val content = arguments["content"]?.toString() ?: ""

      if (path.isBlank()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Path cannot be blank",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Path cannot be blank",
          exitCode = 1
        )
      }

      val validation = ArtifactValidatorRegistry.validateContent(path, content)
      if (!validation.isValid) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Content validation failed: ${validation.errorMessage}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Content validation failed: ${validation.errorMessage}",
          exitCode = 1
        )
      }

      try {
        val isDocx = path.endsWith(".docx", ignoreCase = true) || path.endsWith(".doc", ignoreCase = true)
        if (isDocx) {
          val docxBytes = DocxBuilder.fromMarkdownOrText(content).buildByteArray()
          val file = sandbox.resolveSafe(path)
          file.parentFile?.mkdirs()
          file.writeBytes(docxBytes)
        } else {
          sandbox.filesystem().writeFile(path, content)
        }
        val artifacts = sandbox.listArtifacts()
        val writtenArtifact = artifacts.find { it.path == path }

        val summary = "Successfully saved '$path' (${validation.byteCount} bytes, format: ${validation.detectedFormat ?: "text"}) in OpenSandbox."

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = summary,
          error = null,
          artifacts = artifacts,
          duration = System.currentTimeMillis() - startTime,
          stdout = summary,
          stderr = null,
          exitCode = 0
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to write file '$path': ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Failed to write file '$path': ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Lists entries in an OpenSandbox directory.
 */
class ListFilesTool(private val sandbox: Sandbox) : Tool {
  override val name: String = "list_files"
  override val description: String =
    "List files and directories in the OpenSandbox workspace. Defaults to root directory."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Directory path to list (default: root '.').",
        required = false
      ),
      ToolParameter(
        name = "depth",
        type = "number",
        description = "Max traversal depth (default: 1).",
        required = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val path = arguments["path"]?.toString()?.ifBlank { "." } ?: "."
      val depth = (arguments["depth"] as? Number)?.toInt() ?: 1

      try {
        val entries = sandbox.filesystem().listDirectory(path, depth)
        val sb = StringBuilder()
        sb.appendLine("OpenSandbox Directory Listing for '$path':")
        if (entries.isEmpty()) {
          sb.appendLine("  (directory is empty)")
        } else {
          entries.forEach { entry ->
            val typeIndicator = if (entry.isDirectory) "[DIR]" else "[FILE]"
            sb.appendLine("  $typeIndicator ${entry.path} (${entry.size} bytes, mode: ${Integer.toOctalString(entry.mode)})")
          }
        }

        val out = sb.toString().trim()
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = out,
          error = null,
          artifacts = sandbox.listArtifacts(),
          duration = System.currentTimeMillis() - startTime,
          stdout = out,
          stderr = null,
          exitCode = 0
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to list directory '$path': ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Failed to list directory '$path': ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Deletes a file or directory from OpenSandbox.
 */
class DeleteFileTool(private val sandbox: Sandbox) : Tool {
  override val name: String = "delete_file"
  override val description: String =
    "Delete a file or directory from the OpenSandbox workspace."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path to the file or directory to delete.",
        required = true
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val path = arguments["path"]?.toString() ?: ""

      val target = sandbox.resolveSafe(path)
      if (!target.exists()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "File or directory '$path' does not exist.",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "File or directory '$path' does not exist.",
          exitCode = 1
        )
      }

      try {
        sandbox.filesystem().deleteFiles(listOf(path))
        sandbox.filesystem().deleteDirectories(listOf(path))

        val summary = "Successfully deleted '$path' from OpenSandbox."
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = summary,
          error = null,
          artifacts = sandbox.listArtifacts(),
          duration = System.currentTimeMillis() - startTime,
          stdout = summary,
          stderr = null,
          exitCode = 0
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to delete '$path': ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Failed to delete '$path': ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Creates a directory in OpenSandbox.
 */
class CreateDirectoryTool(private val sandbox: Sandbox) : Tool {
  override val name: String = "create_directory"
  override val description: String =
    "Create a new directory in the OpenSandbox workspace."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path of the directory to create (e.g. 'output', 'src/components').",
        required = true
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val path = arguments["path"]?.toString() ?: ""

      try {
        sandbox.filesystem().createDirectories(listOf(WriteEntry.builder().path(path).build()))
        val summary = "Successfully created directory '$path' in OpenSandbox."
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = summary,
          error = null,
          artifacts = sandbox.listArtifacts(),
          duration = System.currentTimeMillis() - startTime,
          stdout = summary,
          stderr = null,
          exitCode = 0
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to create directory '$path': ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Failed to create directory '$path': ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Inspects authoritative filesystem metadata and preview of an artifact.
 */
class InspectArtifactTool(private val sandbox: Sandbox) : Tool {
  override val name: String = "inspect_artifact"
  override val description: String =
    "Inspect authoritative filesystem metadata (size, lines, preview) of a workspace deliverable in OpenSandbox."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path to the file to inspect.",
        required = true
      ),
      ToolParameter(
        name = "previewLines",
        type = "number",
        description = "Number of preview lines to display (default: 15).",
        required = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val path = arguments["path"]?.toString() ?: ""
      val previewCount = (arguments["previewLines"] as? Number)?.toInt() ?: 15

      try {
        val infoMap = sandbox.filesystem().readFileInfo(listOf(path))
        val entry = infoMap[path]
        if (entry == null) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "File '$path' does not exist in OpenSandbox.",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "File '$path' does not exist in OpenSandbox.",
            exitCode = 1
          )
        }

        val preview = try {
          sandbox.filesystem().readFile(path, offset = 1, limit = previewCount)
        } catch (_: Exception) {
          "(binary or non-text content)"
        }

        val summary = """
          OpenSandbox Artifact Metadata:
          - Path: ${entry.path}
          - Mode: ${Integer.toOctalString(entry.mode)}
          - Size: ${entry.size} bytes
          - Modified: ${entry.modifiedAt}
          - Content Preview (first $previewCount lines):
          $preview
        """.trimIndent()

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = summary,
          error = null,
          artifacts = sandbox.listArtifacts(),
          duration = System.currentTimeMillis() - startTime,
          stdout = summary,
          stderr = null,
          exitCode = 0
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Error inspecting artifact '$path': ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Error inspecting artifact: ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Executes Python code or scripts using the OpenSandbox Code Interpreter.
 */
class PythonTool(private val sandbox: Sandbox) : Tool {
  override val name: String = "python3"
  override val description: String =
    "Execute Python 3 scripts or inline code using the OpenSandbox Code Interpreter. Supports standard libraries, data processing, and document generation."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "code",
        type = "string",
        description = "Inline Python 3 code to execute.",
        required = false
      ),
      ToolParameter(
        name = "script",
        type = "string",
        description = "Path to an existing .py script in the workspace to execute.",
        required = false
      ),
      ToolParameter(
        name = "command",
        type = "string",
        description = "Full python3 command line (e.g. 'python3 script.py arg1').",
        required = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val code = arguments["code"]?.toString()
      val script = arguments["script"]?.toString()
      val command = arguments["command"]?.toString()

      val execution = when {
        !code.isNullOrBlank() -> {
          sandbox.codeInterpreter().codes().run(
            RunCodeRequest.builder().code(code).build()
          )
        }
        !script.isNullOrBlank() -> {
          sandbox.commands().run("python3 $script")
        }
        !command.isNullOrBlank() -> {
          sandbox.commands().run(command)
        }
        else -> {
          sandbox.commands().run("python3 --version")
        }
      }

      val duration = System.currentTimeMillis() - startTime
      val exitCode = execution.exitCode ?: 0
      val stdout = execution.stdoutText().trim()
      val stderr = execution.stderrText().trim()
      val status = if (exitCode == 0 && execution.error == null) ToolStatus.SUCCEEDED else ToolStatus.FAILED

      ToolResult(
        callId = callId,
        toolName = name,
        status = status,
        arguments = arguments,
        output = stdout.ifEmpty { null },
        error = if (status == ToolStatus.FAILED) stderr.ifBlank { execution.error?.value ?: "Python execution failed" } else null,
        artifacts = sandbox.listArtifacts(),
        duration = duration,
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode
      )
    }
}

/**
 * Pip package manager tool in OpenSandbox.
 */
class PipTool(private val sandbox: Sandbox) : Tool {
  override val name: String = "pip"
  override val description: String =
    "Install, list, or inspect Python packages in the OpenSandbox environment (e.g., 'pip install requests', 'pip list')."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "command",
        type = "string",
        description = "The pip command to execute (e.g., 'install pandas', 'list').",
        required = true
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val cmd = arguments["command"]?.toString() ?: "list"
      val fullCmd = if (cmd.startsWith("pip")) cmd else "pip $cmd"

      val exec = sandbox.commands().run(fullCmd)
      val duration = System.currentTimeMillis() - startTime
      val exitCode = exec.exitCode ?: 0
      val stdout = exec.stdoutText().trim()
      val stderr = exec.stderrText().trim()
      val status = if (exitCode == 0 && exec.error == null) ToolStatus.SUCCEEDED else ToolStatus.FAILED

      ToolResult(
        callId = callId,
        toolName = name,
        status = status,
        arguments = arguments,
        output = stdout.ifEmpty { null },
        error = if (status == ToolStatus.FAILED) stderr.ifBlank { exec.error?.value ?: "Pip execution failed" } else null,
        artifacts = sandbox.listArtifacts(),
        duration = duration,
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode
      )
    }
}
