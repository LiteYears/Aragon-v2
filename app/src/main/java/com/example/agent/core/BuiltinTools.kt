package com.example.agent.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Executes a shell command inside the sandboxed workspace.
 */
class TerminalTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "terminal"
  override val description: String =
    "Execute a shell command within the workspace directory. Returns stdout, stderr, and the process exit code."

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
        description = "Maximum execution time in seconds (default: 15).",
        required = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val command = arguments["command"]?.toString() ?: ""
      val timeoutSeconds = (arguments["timeoutSeconds"] as? Number)?.toLong() ?: 15L

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

      // Seamless execution of Python/Pip commands via native PythonRuntime on Android sandbox
      if (PythonRuntime.matches(command)) {
        val pyResult = PythonRuntime.execute(command, workspace.baseDir)
        val currentArtifacts = workspace.listAllArtifacts()
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = if (pyResult.exitCode == 0) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
          arguments = arguments,
          output = pyResult.stdout.ifEmpty { null },
          error = if (pyResult.exitCode != 0) pyResult.stderr.ifBlank { "Python execution failed" } else null,
          artifacts = currentArtifacts,
          duration = System.currentTimeMillis() - startTime,
          stdout = pyResult.stdout,
          stderr = pyResult.stderr,
          exitCode = pyResult.exitCode
        )
      }

      var process: Process? = null
      try {
        workspace.baseDir.mkdirs()
        val processBuilder = ProcessBuilder("sh", "-c", command)
        processBuilder.directory(workspace.baseDir)
        val env = processBuilder.environment()
        env["PWD"] = workspace.baseDir.absolutePath
        env["WORKSPACE"] = workspace.baseDir.absolutePath

        val p = processBuilder.start()
        process = p

        val stdoutSb = StringBuilder()
        val stderrSb = StringBuilder()

        val stdoutThread = Thread {
          try {
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
              var line: String?
              while (reader.readLine().also { line = it } != null) {
                if (stdoutSb.length < 32000) {
                  stdoutSb.appendLine(line)
                }
              }
            }
          } catch (_: Exception) {}
        }

        val stderrThread = Thread {
          try {
            BufferedReader(InputStreamReader(p.errorStream)).use { reader ->
              var line: String?
              while (reader.readLine().also { line = it } != null) {
                if (stderrSb.length < 32000) {
                  stderrSb.appendLine(line)
                }
              }
            }
          } catch (_: Exception) {}
        }

        stdoutThread.start()
        stderrThread.start()

        val completed = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        stdoutThread.join(500)
        stderrThread.join(500)

        val duration = System.currentTimeMillis() - startTime

        if (!completed) {
          p.destroyForcibly()
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = stdoutSb.toString().trim(),
            error = "Execution timed out after ${timeoutSeconds}s",
            duration = duration,
            stdout = stdoutSb.toString().trim(),
            stderr = "Execution timed out after ${timeoutSeconds}s",
            exitCode = -1
          )
        }

        val exitCode = p.exitValue()
        val stdout = stdoutSb.toString().trim()
        val stderr = stderrSb.toString().trim()

        // If system shell cannot find python/pip (common in Android sandboxes), seamlessly execute via PythonRuntime
        if ((exitCode != 0 || stderr.contains("not found") || stderr.contains("inaccessible")) && PythonRuntime.matches(command)) {
          val pyResult = PythonRuntime.execute(command, workspace.baseDir)
          val currentArtifacts = workspace.listAllArtifacts()
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = if (pyResult.exitCode == 0) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
            arguments = arguments,
            output = pyResult.stdout.ifEmpty { null },
            error = if (pyResult.exitCode != 0) pyResult.stderr.ifBlank { "Python execution failed" } else null,
            artifacts = currentArtifacts,
            duration = System.currentTimeMillis() - startTime,
            stdout = pyResult.stdout,
            stderr = pyResult.stderr,
            exitCode = pyResult.exitCode
          )
        }

        val status = if (exitCode == 0) ToolStatus.SUCCEEDED else ToolStatus.FAILED
        val errorText = if (exitCode != 0) {
          if (stderr.isNotBlank()) stderr else "Command exited with non-zero exit code: $exitCode"
        } else {
          if (stderr.isNotBlank()) stderr else null
        }

        // Detect any newly created or modified artifacts in workspace
        val currentArtifacts = workspace.listAllArtifacts()

        ToolResult(
          callId = callId,
          toolName = name,
          status = status,
          arguments = arguments,
          output = stdout.ifEmpty { null },
          error = errorText,
          artifacts = currentArtifacts,
          duration = duration,
          stdout = stdout,
          stderr = stderr,
          exitCode = exitCode
        )
      } catch (ce: kotlinx.coroutines.CancellationException) {
        process?.destroyForcibly()
        throw ce
      } catch (e: Exception) {
        if (PythonRuntime.matches(command)) {
          val pyResult = PythonRuntime.execute(command, workspace.baseDir)
          val currentArtifacts = workspace.listAllArtifacts()
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = if (pyResult.exitCode == 0) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
            arguments = arguments,
            output = pyResult.stdout.ifEmpty { null },
            error = if (pyResult.exitCode != 0) pyResult.stderr.ifBlank { "Python execution failed" } else null,
            artifacts = currentArtifacts,
            duration = System.currentTimeMillis() - startTime,
            stdout = pyResult.stdout,
            stderr = pyResult.stderr,
            exitCode = pyResult.exitCode
          )
        }
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Failed to spawn shell process: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Failed to spawn shell process: ${e.message}",
          exitCode = 127
        )
      } finally {
        if (process != null && process.isAlive) {
          process.destroyForcibly()
        }
      }
    }
}

/**
 * Reads the text content of a file in the workspace.
 */
class ReadFileTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "read_file"
  override val description: String =
    "Read the text content of a file in the workspace. Specify relative path."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Relative path of the file to read (e.g., 'data.csv', 'src/main.py').",
        required = true
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val path = arguments["path"]?.toString() ?: ""

      try {
        val file = workspace.resolveSafe(path)
        if (!file.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "File does not exist: $path",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "File does not exist: $path",
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
            error = "Target '$path' is a directory, not a regular file.",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "Target '$path' is a directory, not a regular file.",
            exitCode = 1
          )
        }

        val content = file.readText()
        val artifact = workspace.createArtifactFromFile(file, callId)

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = content,
          error = null,
          artifacts = listOf(artifact),
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
          error = "Error reading file: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Error reading file: ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Writes text content to a file in the workspace.
 * Verifies that the write actually succeeded on disk.
 */
class WriteFileTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "write_file"
  override val description: String =
    "Write text content to a file in the workspace. Automatically creates parent directories and verifies write."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Relative path of the target file to create or overwrite.",
        required = true
      ),
      ToolParameter(
        name = "content",
        type = "string",
        description = "The exact text content to write to the file.",
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
          error = "Parameter 'path' cannot be blank.",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Path cannot be blank",
          exitCode = 1
        )
      }

      // Layer 2.1: Artifact format validation
      val validation = ArtifactValidatorRegistry.validateContent(path, content)
      if (!validation.isValid) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Deliverable validation failed: ${validation.errorMessage}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = validation.errorMessage,
          exitCode = 1
        )
      }

      try {
        val file = workspace.resolveSafe(path)
        file.parentFile?.mkdirs()

        // Layer 2.2: Idempotency guard for deliverables
        if (file.exists() && file.isFile) {
          val isDocx = path.endsWith(".docx", ignoreCase = true) || path.endsWith(".doc", ignoreCase = true)
          if (!isDocx && file.readText() == content) {
            val note = "Idempotency notice: File '$path' already exists with identical content. Skipping redundant write."
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.SUCCEEDED,
              arguments = arguments,
              output = note,
              error = null,
              artifacts = listOf(workspace.createArtifactFromFile(file, callId)),
              duration = System.currentTimeMillis() - startTime,
              stdout = note,
              stderr = null,
              exitCode = 0
            )
          }
        }

        // Layer 2.3: Atomic write to temporary file before atomic rename/replace
        val tmpFile = File(file.parentFile, "${file.name}.${System.currentTimeMillis()}.tmp")

        val isDocx = path.endsWith(".docx", ignoreCase = true) || path.endsWith(".doc", ignoreCase = true)

        if (isDocx) {
          DocxBuilder.fromMarkdownOrText(content).save(tmpFile)
          val postCheck = ArtifactValidatorRegistry.validateExistingFile(tmpFile)
          if (!postCheck.isValid) {
            tmpFile.delete()
            return@withContext ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Deliverable validation failed on disk: ${postCheck.errorMessage}",
              duration = System.currentTimeMillis() - startTime,
              stdout = null,
              stderr = postCheck.errorMessage,
              exitCode = 1
            )
          }
        } else {
          tmpFile.writeText(content, Charsets.UTF_8)
        }

        // Atomic replace
        if (file.exists()) file.delete()
        val renameSuccess = tmpFile.renameTo(file)
        if (!renameSuccess) {
          tmpFile.copyTo(file, overwrite = true)
          tmpFile.delete()
        }

        // Strict verification on filesystem
        if (!file.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Filesystem verification failed: '$path' does not exist after write operation.",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "File not found after write",
            exitCode = 1
          )
        }

        val actualBytes = file.length()
        val artifact = workspace.createArtifactFromFile(file, callId)
        val successMsg = "Successfully wrote and validated deliverable '$path' ($actualBytes bytes on disk)."

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = successMsg,
          error = null,
          artifacts = listOf(artifact),
          duration = System.currentTimeMillis() - startTime,
          stdout = successMsg,
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
          error = "Error writing file: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Error writing file: ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Lists files and directories in the workspace.
 */
class ListFilesTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "list_files"
  override val description: String =
    "List files and directories in the workspace or a specified subfolder."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Relative path of folder to inspect (use '.' or '' for workspace root).",
        required = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val path = arguments["path"]?.toString()?.ifBlank { "." } ?: "."

      try {
        val target = workspace.resolveSafe(path)
        if (!target.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Directory does not exist: $path",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "Directory does not exist: $path",
            exitCode = 1
          )
        }

        val entries = target.listFiles()?.sortedWith(
          compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()

        val sb = StringBuilder()
        sb.appendLine("Contents of '${workspace.getRelativePath(target)}' (${entries.size} items):")
        for (item in entries) {
          val type = if (item.isDirectory) "[DIR] " else "[FILE]"
          val size = if (item.isFile) "(${item.length()} bytes)" else ""
          sb.appendLine("  $type ${item.name} $size")
        }

        val artifacts = entries.filter { it.isFile }.map { workspace.createArtifactFromFile(it, callId) }
        val outputText = sb.toString().trim()

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = outputText,
          error = null,
          artifacts = artifacts,
          duration = System.currentTimeMillis() - startTime,
          stdout = outputText,
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
          error = "Error listing files: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Error listing files: ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Deletes a file in the workspace.
 */
class DeleteFileTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "delete_file"
  override val description: String =
    "Delete a file or empty directory in the workspace."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Relative path of file to delete.",
        required = true
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val path = arguments["path"]?.toString() ?: ""

      try {
        val file = workspace.resolveSafe(path)
        if (!file.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "File does not exist: $path",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "File does not exist: $path",
            exitCode = 1
          )
        }

        val deleted = file.delete()
        if (deleted) {
          ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.SUCCEEDED,
            arguments = arguments,
            output = "Successfully deleted '$path'",
            error = null,
            duration = System.currentTimeMillis() - startTime,
            stdout = "Successfully deleted '$path'",
            stderr = null,
            exitCode = 0
          )
        } else {
          ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Failed to delete '$path'. If it is a directory, verify it is empty.",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "Failed to delete '$path'",
            exitCode = 1
          )
        }
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Error deleting file: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Error deleting file: ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Creates a directory in the workspace.
 */
class CreateDirectoryTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "create_directory"
  override val description: String =
    "Create a new directory (and any necessary parent directories) in the workspace."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Relative directory path to create (e.g. 'output/charts', 'src/utils').",
        required = true
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val path = arguments["path"]?.toString() ?: ""

      try {
        val dir = workspace.resolveSafe(path)
        if (dir.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.SUCCEEDED,
            arguments = arguments,
            output = "Directory '$path' already exists.",
            error = null,
            duration = System.currentTimeMillis() - startTime,
            stdout = "Directory '$path' already exists.",
            stderr = null,
            exitCode = 0
          )
        }

        val created = dir.mkdirs()
        if (created || dir.exists()) {
          ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.SUCCEEDED,
            arguments = arguments,
            output = "Successfully created directory '$path'",
            error = null,
            duration = System.currentTimeMillis() - startTime,
            stdout = "Successfully created directory '$path'",
            stderr = null,
            exitCode = 0
          )
        } else {
          ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Could not create directory '$path'",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "Could not create directory '$path'",
            exitCode = 1
          )
        }
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Error creating directory: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Error creating directory: ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Authoritatively inspects an artifact/file in the workspace.
 * Returns metadata: exists, size, lines count, type, preview.
 */
class InspectArtifactTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "inspect_artifact"
  override val description: String =
    "Inspect metadata and contents of an artifact file in the workspace to objectively verify existence and properties."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "path",
        type = "string",
        description = "Path of the artifact to inspect (e.g. 'report.md', 'output.json').",
        required = true
      ),
      ToolParameter(
        name = "previewLines",
        type = "number",
        description = "Number of initial lines to preview (default: 20).",
        required = false
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val path = arguments["path"]?.toString() ?: ""
      val previewCount = (arguments["previewLines"] as? Number)?.toInt() ?: 20

      try {
        val file = workspace.resolveSafe(path)
        if (!file.exists()) {
          return@withContext ToolResult(
            callId = callId,
            toolName = name,
            status = ToolStatus.FAILED,
            arguments = arguments,
            output = null,
            error = "Artifact does not exist at path: $path",
            duration = System.currentTimeMillis() - startTime,
            stdout = null,
            stderr = "Artifact does not exist at path: $path",
            exitCode = 1
          )
        }

        val artifact = workspace.createArtifactFromFile(file, callId)
        val lines = if (file.isFile) file.readLines() else emptyList()
        val preview = lines.take(previewCount).joinToString("\n")

        val summary = """
        Artifact Inspection Report:
        - Path: ${artifact.path}
        - Exists: ${artifact.exists}
        - Type: ${artifact.type}
        - Size: ${artifact.size} bytes
        - Total Lines: ${lines.size}
        - Content Preview (first ${minOf(previewCount, lines.size)} lines):
        $preview
        """.trimIndent()

        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.SUCCEEDED,
          arguments = arguments,
          output = summary,
          error = null,
          artifacts = listOf(artifact),
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
          error = "Error inspecting artifact: ${e.message}",
          duration = System.currentTimeMillis() - startTime,
          stdout = null,
          stderr = "Error inspecting artifact: ${e.message}",
          exitCode = 1
        )
      }
    }
}

/**
 * Executes Python 3 code or scripts in the sandboxed workspace.
 */
class PythonTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "python3"
  override val description: String =
    "Execute Python 3 scripts or inline code in the sandboxed workspace. Supports standard libraries, python-docx (.docx Word document creation), pandas, csv, json, os, and pip-installed libraries."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "code",
        type = "string",
        description = "Inline Python 3 code to execute (e.g., 'import docx; doc = docx.Document(); ...'). Optional if script file is specified.",
        required = false
      ),
      ToolParameter(
        name = "script",
        type = "string",
        description = "Path to an existing .py script in the workspace to execute (e.g., 'transform.py').",
        required = false
      ),
      ToolParameter(
        name = "command",
        type = "string",
        description = "Full python3 command line (e.g. 'python3 transform.py').",
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

      val pyResult = when {
        !code.isNullOrBlank() -> PythonRuntime.runPythonCode(code, workspace.baseDir, emptyList())
        !script.isNullOrBlank() -> PythonRuntime.execute("python3 $script", workspace.baseDir)
        !command.isNullOrBlank() -> PythonRuntime.execute(command, workspace.baseDir)
        else -> PythonRuntime.execute("python3 --version", workspace.baseDir)
      }

      val artifacts = workspace.listAllArtifacts()
      val duration = System.currentTimeMillis() - startTime

      ToolResult(
        callId = callId,
        toolName = name,
        status = if (pyResult.exitCode == 0) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
        arguments = arguments,
        output = pyResult.stdout.ifEmpty { null },
        error = if (pyResult.exitCode != 0) pyResult.stderr.ifBlank { "Python execution error" } else null,
        artifacts = artifacts,
        duration = duration,
        stdout = pyResult.stdout,
        stderr = pyResult.stderr,
        exitCode = pyResult.exitCode
      )
    }
}

/**
 * Pip package manager tool for managing Python packages in the workspace.
 */
class PipTool(private val workspace: WorkspaceManager) : Tool {
  override val name: String = "pip"
  override val description: String =
    "Install, list, or inspect Python packages using the native Pip package manager (e.g., 'pip install python-docx', 'pip list')."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "command",
        type = "string",
        description = "The pip command to execute (e.g., 'install python-docx', 'install pandas', 'list').",
        required = true
      )
    )
  )

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val cmd = arguments["command"]?.toString() ?: "list"
      val fullCmd = if (cmd.startsWith("pip")) cmd else "pip $cmd"

      val pipResult = PythonRuntime.execute(fullCmd, workspace.baseDir)
      val artifacts = workspace.listAllArtifacts()
      val duration = System.currentTimeMillis() - startTime

      ToolResult(
        callId = callId,
        toolName = name,
        status = if (pipResult.exitCode == 0) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
        arguments = arguments,
        output = pipResult.stdout.ifEmpty { null },
        error = if (pipResult.exitCode != 0) pipResult.stderr.ifBlank { "Pip error" } else null,
        artifacts = artifacts,
        duration = duration,
        stdout = pipResult.stdout,
        stderr = pipResult.stderr,
        exitCode = pipResult.exitCode
      )
    }
}
