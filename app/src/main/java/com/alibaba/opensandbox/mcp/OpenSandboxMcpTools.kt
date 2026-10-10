/*
 * Copyright 2025 The OpenSandbox Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.opensandbox.mcp

import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.CodeContext
import com.alibaba.opensandbox.codeinterpreter.domain.models.execd.executions.RunCodeRequest
import com.alibaba.opensandbox.sandbox.Sandbox
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.ContentReplaceEntry
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.MoveEntry
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.SearchEntry
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry
import com.example.agent.core.Tool
import com.example.agent.core.ToolParameter
import com.example.agent.core.ToolResult
import com.example.agent.core.ToolSchema
import com.example.agent.core.ToolStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenSandbox MCP Tools.
 *
 * Exposes canonical OpenSandbox operations conforming to the OpenSandbox Model Context Protocol (MCP) server specifications:
 * - sandbox_get_info
 * - sandbox_healthcheck
 * - sandbox_get_metrics
 * - command_run
 * - command_interrupt
 * - file_read
 * - file_write
 * - file_delete
 * - file_search
 * - file_replace_contents
 * - file_move
 * - code_run
 */
class OpenSandboxMcpTool(
  private val sandbox: Sandbox,
  override val name: String,
  override val description: String,
  override val schema: ToolSchema,
  private val handler: suspend (arguments: Map<String, Any?>) -> Pair<String?, String?>
) : Tool {

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val start = System.currentTimeMillis()
      try {
        val (output, error) = handler(arguments)
        val status = if (error == null) ToolStatus.SUCCEEDED else ToolStatus.FAILED
        ToolResult(
          callId = callId,
          toolName = name,
          status = status,
          arguments = arguments,
          output = output,
          error = error,
          artifacts = sandbox.listArtifacts(),
          duration = System.currentTimeMillis() - start,
          stdout = output,
          stderr = error,
          exitCode = if (error == null) 0 else 1
        )
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = e.message ?: "Operation failed",
          artifacts = sandbox.listArtifacts(),
          duration = System.currentTimeMillis() - start,
          stdout = null,
          stderr = e.message,
          exitCode = 1
        )
      }
    }

  companion object {
    fun createAllTools(sandbox: Sandbox): List<Tool> {
      val tools = mutableListOf<Tool>()

      // 1. sandbox_get_info
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "sandbox_get_info",
          description = "Get status, resource metrics, lifecycle, and endpoint metadata of the active OpenSandbox.",
          schema = ToolSchema(emptyList())
        ) {
          val info = sandbox.info
          val diag = sandbox.diagnostics().getDiagnostics()
          val report = """
            OpenSandbox ID: ${info.id}
            Status: ${info.status}
            OS: ${diag.os} (${diag.architecture})
            Kernel: ${diag.kernel}
            CPU Cores: ${diag.cpuCores}
            Memory: ${diag.totalMemoryBytes / (1024 * 1024)} MB Total, ${diag.freeMemoryBytes / (1024 * 1024)} MB Free
            Workspace: ${sandbox.baseDir.absolutePath}
          """.trimIndent()
          Pair(report, null)
        }
      )

      // 2. sandbox_healthcheck
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "sandbox_healthcheck",
          description = "Ping the OpenSandbox runtime and verify execution health.",
          schema = ToolSchema(emptyList())
        ) {
          val healthy = sandbox.health().ping()
          if (healthy) Pair("OpenSandbox daemon is healthy and serving requests.", null)
          else Pair(null, "OpenSandbox health check failed.")
        }
      )

      // 3. sandbox_get_metrics
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "sandbox_get_metrics",
          description = "Query active CPU, memory, and disk resource metrics from OpenSandbox.",
          schema = ToolSchema(emptyList())
        ) {
          val metrics = sandbox.metrics().getMetrics()
          val result = """
            CPU Usage: ${metrics.cpuPercent}%
            Memory Usage: ${metrics.memoryBytesUsed / (1024 * 1024)} MB / ${metrics.memoryBytesTotal / (1024 * 1024)} MB
            Disk Usage: ${metrics.diskBytesUsed / (1024 * 1024)} MB / ${metrics.diskBytesTotal / (1024 * 1024)} MB
          """.trimIndent()
          Pair(result, null)
        }
      )

      // 4. command_run
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "command_run",
          description = "Execute a shell command within OpenSandbox with environment isolation and streaming output.",
          schema = ToolSchema(
            listOf(
              ToolParameter("command", "string", "Shell command string to execute", required = true),
              ToolParameter("working_directory", "string", "Optional working directory inside sandbox", required = false),
              ToolParameter("background", "boolean", "Whether to launch the command in background mode", required = false)
            )
          )
        ) { args ->
          val cmd = args["command"]?.toString().orEmpty()
          val workDir = args["working_directory"]?.toString()
          val bg = args["background"]?.toString()?.toBoolean() ?: false
          val req = RunCommandRequest.builder()
            .command(cmd)
            .workingDirectory(workDir)
            .background(bg)
            .build()
          val exec = sandbox.commands().run(req)
          val out = exec.stdoutText()
          val err = exec.stderrText()
          if (exec.exitCode == 0 || exec.error == null) {
            Pair(out.ifBlank { "Command finished successfully with exit code 0." }, null)
          } else {
            Pair(out.ifBlank { null }, err.ifBlank { exec.error?.value ?: "Command failed with code ${exec.exitCode}" })
          }
        }
      )

      // 5. command_interrupt
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "command_interrupt",
          description = "Interrupt and terminate a running command execution in OpenSandbox.",
          schema = ToolSchema(
            listOf(
              ToolParameter("execution_id", "string", "Execution ID of the running command to terminate", required = true)
            )
          )
        ) { args ->
          val execId = args["execution_id"]?.toString().orEmpty()
          sandbox.commands().interrupt(execId)
          Pair("Interrupted execution $execId in OpenSandbox.", null)
        }
      )

      // 6. file_read
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "file_read",
          description = "Read text content of a file from OpenSandbox filesystem.",
          schema = ToolSchema(
            listOf(
              ToolParameter("path", "string", "Relative or absolute file path to read", required = true),
              ToolParameter("encoding", "string", "File encoding (default UTF-8)", required = false)
            )
          )
        ) { args ->
          val path = args["path"]?.toString().orEmpty()
          val enc = args["encoding"]?.toString() ?: "UTF-8"
          val content = sandbox.filesystem().readFile(path, encoding = enc)
          Pair(content, null)
        }
      )

      // 7. file_write
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "file_write",
          description = "Write content to a file in OpenSandbox filesystem.",
          schema = ToolSchema(
            listOf(
              ToolParameter("path", "string", "Destination file path in sandbox", required = true),
              ToolParameter("content", "string", "Text content to write", required = true)
            )
          )
        ) { args ->
          val path = args["path"]?.toString().orEmpty()
          val content = args["content"]?.toString().orEmpty()
          sandbox.filesystem().writeFile(
            WriteEntry.builder().path(path).data(content).build()
          )
          Pair("Wrote ${content.length} characters to '$path' in OpenSandbox.", null)
        }
      )

      // 8. file_delete
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "file_delete",
          description = "Delete a file from the OpenSandbox filesystem.",
          schema = ToolSchema(
            listOf(
              ToolParameter("path", "string", "File path to delete", required = true)
            )
          )
        ) { args ->
          val path = args["path"]?.toString().orEmpty()
          sandbox.filesystem().deleteFiles(listOf(path))
          Pair("Deleted '$path' from OpenSandbox.", null)
        }
      )

      // 9. file_search
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "file_search",
          description = "Search files matching a regex pattern or string in OpenSandbox filesystem.",
          schema = ToolSchema(
            listOf(
              ToolParameter("path", "string", "Directory path to search in", required = true),
              ToolParameter("pattern", "string", "Regex pattern to match filenames or content", required = true)
            )
          )
        ) { args ->
          val path = args["path"]?.toString().orEmpty().ifBlank { "." }
          val pattern = args["pattern"]?.toString().orEmpty()
          val entries = sandbox.filesystem().search(
            SearchEntry.builder().path(path).pattern(pattern).build()
          )
          val formatted = if (entries.isEmpty()) {
            "No files matched pattern '$pattern' in '$path'."
          } else {
            entries.joinToString("\n") { "${it.path} (${it.size} bytes)" }
          }
          Pair(formatted, null)
        }
      )

      // 10. file_replace_contents
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "file_replace_contents",
          description = "Search and replace text contents in a file within OpenSandbox.",
          schema = ToolSchema(
            listOf(
              ToolParameter("path", "string", "File path to modify", required = true),
              ToolParameter("old_content", "string", "Exact string to replace", required = true),
              ToolParameter("new_content", "string", "Replacement string", required = true)
            )
          )
        ) { args ->
          val path = args["path"]?.toString().orEmpty()
          val oldText = args["old_content"]?.toString().orEmpty()
          val newText = args["new_content"]?.toString().orEmpty()
          val res = sandbox.filesystem().replaceContentsDetailed(
            listOf(
              ContentReplaceEntry.builder()
                .path(path)
                .oldContent(oldText)
                .newContent(newText)
                .build()
            )
          )
          val count = res.firstOrNull()?.replacedCount ?: 0
          Pair("Replaced $count occurrences in '$path'.", null)
        }
      )

      // 11. file_move
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "file_move",
          description = "Move or rename a file or directory within OpenSandbox.",
          schema = ToolSchema(
            listOf(
              ToolParameter("source", "string", "Source path", required = true),
              ToolParameter("destination", "string", "Destination path", required = true)
            )
          )
        ) { args ->
          val src = args["source"]?.toString().orEmpty()
          val dst = args["destination"]?.toString().orEmpty()
          sandbox.filesystem().moveFiles(
            listOf(
              MoveEntry.builder().sourcePath(src).destinationPath(dst).build()
            )
          )
          Pair("Moved '$src' to '$dst' in OpenSandbox.", null)
        }
      )

      // 12. file_create_directories
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "file_create_directories",
          description = "Create directory path hierarchy in OpenSandbox filesystem.",
          schema = ToolSchema(
            listOf(
              ToolParameter("path", "string", "Directory path to create", required = true)
            )
          )
        ) { args ->
          val path = args["path"]?.toString().orEmpty()
          sandbox.filesystem().createDirectories(
            listOf(WriteEntry.builder().path(path).build())
          )
          Pair("Created directory '$path' in OpenSandbox.", null)
        }
      )

      // 13. file_delete_directories
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "file_delete_directories",
          description = "Delete a directory and its contents from OpenSandbox.",
          schema = ToolSchema(
            listOf(
              ToolParameter("path", "string", "Directory path to remove", required = true)
            )
          )
        ) { args ->
          val path = args["path"]?.toString().orEmpty()
          sandbox.filesystem().deleteDirectories(listOf(path))
          Pair("Deleted directory '$path' from OpenSandbox.", null)
        }
      )

      // 14. code_run
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "code_run",
          description = "Execute multi-language code (Python, Bash, JavaScript) with persistent state context in OpenSandbox.",
          schema = ToolSchema(
            listOf(
              ToolParameter("code", "string", "Code to execute in the interpreter", required = true),
              ToolParameter("language", "string", "Runtime language ('python', 'bash', 'javascript')", required = false),
              ToolParameter("context_id", "string", "Optional session context ID for state persistence", required = false)
            )
          )
        ) { args ->
          val code = args["code"]?.toString().orEmpty()
          val lang = args["language"]?.toString() ?: "python"
          val ctxId = args["context_id"]?.toString()
          val ctx = CodeContext.builder().id(ctxId).language(lang).build()
          val exec = sandbox.codeInterpreter().codes().run(
            RunCodeRequest.builder().code(code).context(ctx).build()
          )
          val out = exec.stdoutText()
          val err = exec.stderrText()
          if (exec.exitCode == 0 || exec.error == null) {
            Pair(out.ifBlank { "Code executed successfully." }, null)
          } else {
            Pair(out.ifBlank { null }, err.ifBlank { exec.error?.value ?: "Execution failed with code ${exec.exitCode}" })
          }
        }
      )

      // 15. sandbox_get_endpoint
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "sandbox_get_endpoint",
          description = "Get host and port connection endpoint for a service running inside OpenSandbox.",
          schema = ToolSchema(
            listOf(
              ToolParameter("port", "number", "Port number to resolve", required = true)
            )
          )
        ) { args ->
          val port = ((args["port"] as? Number)?.toInt()) ?: 8080
          val endpoint = sandbox.getEndpoint(port)
          Pair("Endpoint: ${endpoint.host}:${endpoint.port}", null)
        }
      )

      // 16. sandbox_renew
      tools.add(
        OpenSandboxMcpTool(
          sandbox = sandbox,
          name = "sandbox_renew",
          description = "Renew lease and keep-alive heartbeat of the active OpenSandbox.",
          schema = ToolSchema(emptyList())
        ) {
          sandbox.renew()
          Pair("Renewed lease for OpenSandbox ${sandbox.id}.", null)
        }
      )

      return tools
    }
  }
}
