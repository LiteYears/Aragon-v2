package com.example.agent.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest
import java.io.File

/**
 * Dedicated structured tool-probing capability.
 * Safely determines command availability using POSIX 'command -v' without
 * error-swallowing shell pipelines or exit-code confusion.
 * Executes exclusively in E2B Cloud Firecracker MicroVMs.
 */
class CheckToolsTool(
  private val workspaceDir: File,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" }
) : Tool {

  override val name: String = "check_tools"
  override val description: String =
    "Check availability of shell commands and binaries in the sandbox. Returns a structured JSON map indicating whether each binary is installed."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "tools",
        type = "array",
        description = "List of command names to check (e.g. ['nmap', 'curl', 'dig', 'python3']).",
        required = true
      )
    )
  )

  override val category: ToolCategory = ToolCategory.UTILITY
  override val target: ToolTarget = ToolTarget.SANDBOX_PROCESS
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.NONE)
  override val defaultTimeoutMs: Long = 15000L
  override val supportsCancellation: Boolean = false

  override fun isAvailable(): Boolean = true

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val toolsArg = arguments["tools"]
      val toolsList = mutableListOf<String>()

      when (toolsArg) {
        is List<*> -> toolsList.addAll(toolsArg.filterIsInstance<String>())
        is JSONArray -> {
          for (i in 0 until toolsArg.length()) {
            toolsList.add(toolsArg.optString(i))
          }
        }
        is String -> {
          toolsList.addAll(toolsArg.split(",", " ").map { it.trim() }.filter { it.isNotBlank() })
        }
      }

      if (toolsList.isEmpty()) {
        return@withContext ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Parameter 'tools' cannot be empty.",
          errorType = ToolErrorCode.INVALID_ARGUMENTS,
          duration = System.currentTimeMillis() - startTime
        )
      }

      val resultMap = mutableMapOf<String, Boolean>()
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
          duration = System.currentTimeMillis() - startTime
        )
      }
      val sessionId = sessionIdProvider()

      for (toolName in toolsList.distinct()) {
        val sanitized = toolName.trim()
        if (sanitized.isBlank() || sanitized.contains(Regex("[^a-zA-Z0-9_.-]"))) {
          resultMap[sanitized] = false
          continue
        }

        try {
          val req = RunCommandRequest.builder()
            .command("command -v $sanitized")
            .timeout(java.time.Duration.ofSeconds(5))
            .build()
          val exec = provider.executeCommand(sessionId, req)
          resultMap[sanitized] = (exec.exitCode == 0 && exec.stdoutText().isNotBlank())
        } catch (_: Exception) {
          resultMap[sanitized] = false
        }
      }

      val json = JSONObject(resultMap as Map<*, *>).toString(2)
      return@withContext ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = json,
        error = null,
        errorType = null,
        duration = System.currentTimeMillis() - startTime,
        stdout = json,
        stderr = "",
        exitCode = 0
      )
    }
}
