package com.example.agent.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool exposing the authoritative EnvironmentCapabilityManager to the agent.
 * Enables inspection, structured querying, on-demand provisioning, and full
 * environment diagnostics within the active E2B Cloud MicroVM.
 */
class EnvironmentCapabilityTool(
  val capabilityManager: EnvironmentCapabilityManager,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" }
) : Tool {

  override val name: String = "environment_capability"
  override val description: String =
    "Inspect, verify, or provision environment capabilities in the E2B Cloud MicroVM. Supports actions: 'check' (verify capability status), 'provision' (install & verify dependencies), 'list' (list capabilities), 'diagnose' (full audit of RAM, disk, OS, and capabilities), or 'smoke_test' (run all 12 capability verification scenarios)."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "action",
        type = "string",
        description = "Capability action to perform: 'check' (default), 'provision', 'list', 'diagnose', or 'smoke_test'.",
        required = false,
        enumValues = listOf("check", "provision", "list", "diagnose", "smoke_test", "verify"),
        defaultValue = "check"
      ),
      ToolParameter(
        name = "capability",
        type = "string",
        description = "Capability identifier (e.g. 'docs.docx', 'data.pandas', 'web.beautifulsoup4', 'ocr.tesseract', 'data.numpy'). Required for 'check' and 'provision'.",
        required = false
      ),
      ToolParameter(
        name = "category",
        type = "string",
        description = "Optional category filter for 'list': 'CORE_EXECUTION', 'WEB_RESEARCH', 'DOCUMENTS', 'OCR_IMAGE', 'DATA_ANALYSIS', 'WEB_APPS', 'SYSTEM_UTILITIES'.",
        required = false
      )
    )
  )

  override val category: ToolCategory = ToolCategory.UTILITY
  override val target: ToolTarget = ToolTarget.SANDBOX_PROCESS
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.NONE)
  override val defaultTimeoutMs: Long = 180_000L
  override val supportsCancellation: Boolean = true

  override fun isAvailable(): Boolean = true

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult =
    withContext(Dispatchers.IO) {
      val startTime = System.currentTimeMillis()
      val action = arguments["action"]?.toString()?.trim()?.lowercase() ?: "check"
      val capId = arguments["capability"]?.toString()?.trim()
      val categoryFilter = arguments["category"]?.toString()?.trim()
      val sessionId = sessionIdProvider()

      try {
        when (action) {
          "list" -> {
            val defs = capabilityManager.definitions.values
              .filter { def ->
                if (categoryFilter.isNullOrBlank()) true
                else def.category.name.equals(categoryFilter, ignoreCase = true)
              }
              .map { def ->
                JSONObject().apply {
                  put("id", def.id)
                  put("name", def.name)
                  put("category", def.category.name)
                  put("description", def.description)
                  put("strategy", def.strategy.name)
                  put("minMemoryMb", def.minMemoryMb)
                  put("minDiskMb", def.minDiskMb)
                }
              }

            val json = JSONObject().apply {
              put("total", defs.size)
              put("capabilities", JSONArray(defs))
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
              exitCode = 0
            )
          }

          "check" -> {
            if (capId.isNullOrBlank()) {
              return@withContext ToolResult(
                callId = callId,
                toolName = name,
                status = ToolStatus.FAILED,
                arguments = arguments,
                output = null,
                error = "Parameter 'capability' is required for action='check'.",
                errorType = ToolErrorCode.INVALID_ARGUMENTS,
                duration = System.currentTimeMillis() - startTime,
                exitCode = 1
              )
            }

            val status = capabilityManager.checkCapability(sessionId, capId)
            val json = status.toJsonObject().toString(2)
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
              metadata = mapOf("state" to status.state.name, "level" to status.verificationLevel.name)
            )
          }

          "provision" -> {
            if (capId.isNullOrBlank()) {
              return@withContext ToolResult(
                callId = callId,
                toolName = name,
                status = ToolStatus.FAILED,
                arguments = arguments,
                output = null,
                error = "Parameter 'capability' is required for action='provision'.",
                errorType = ToolErrorCode.INVALID_ARGUMENTS,
                duration = System.currentTimeMillis() - startTime,
                exitCode = 1
              )
            }

            val result = capabilityManager.provisionCapability(sessionId, capId)
            val json = result.toJsonObject().toString(2)
            ToolResult(
              callId = callId,
              toolName = name,
              status = if (result.success) ToolStatus.SUCCEEDED else ToolStatus.FAILED,
              arguments = arguments,
              output = if (result.success) json else null,
              error = if (result.success) null else result.message,
              errorType = if (result.success) null else ToolErrorCode.SANDBOX_ERROR,
              duration = System.currentTimeMillis() - startTime,
              stdout = json,
              stderr = result.stderr,
              exitCode = if (result.success) 0 else 1
            )
          }

          "diagnose" -> {
            val report = capabilityManager.diagnoseEnvironment(sessionId)
            val summary = capabilityManager.formatDiagnosticSummary(report)
            ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.SUCCEEDED,
              arguments = arguments,
              output = summary,
              error = null,
              duration = System.currentTimeMillis() - startTime,
              stdout = summary,
              exitCode = 0
            )
          }

          "smoke_test", "verify" -> {
            val suite = E2BCapabilitySmokeSuite(
              sandboxProvider = sandboxProvider,
              capabilityManager = capabilityManager
            )
            val report = suite.runAllScenarios(sessionId)
            val jsonReport = report.toJson().toString(2)
            val mdSummary = report.toMarkdown()
            val hasFailures = report.failed > 0

            ToolResult(
              callId = callId,
              toolName = name,
              status = if (hasFailures) ToolStatus.FAILED else ToolStatus.SUCCEEDED,
              arguments = arguments,
              output = mdSummary,
              error = if (hasFailures) "${report.failed} verification scenario(s) failed" else null,
              errorType = if (hasFailures) ToolErrorCode.SANDBOX_ERROR else null,
              duration = System.currentTimeMillis() - startTime,
              stdout = jsonReport,
              exitCode = if (hasFailures) 1 else 0
            )
          }

          else -> {
            ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Unsupported action '$action'. Use 'check', 'provision', 'list', 'diagnose', or 'smoke_test'.",
              errorType = ToolErrorCode.INVALID_ARGUMENTS,
              duration = System.currentTimeMillis() - startTime,
              exitCode = 1
            )
          }
        }
      } catch (e: Exception) {
        ToolResult(
          callId = callId,
          toolName = name,
          status = ToolStatus.FAILED,
          arguments = arguments,
          output = null,
          error = "Capability operation failed: ${e.message}",
          errorType = ToolErrorCode.SANDBOX_ERROR,
          duration = System.currentTimeMillis() - startTime,
          exitCode = 1
        )
      }
    }
}
