package com.example.agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest
import java.io.File

/**
 * Dedicated structured tool-probing capability.
 * Safely determines command and module availability in batch or bounded parallel execution.
 * Executes exclusively in E2B Cloud Firecracker MicroVMs.
 */
class CheckToolsTool(
  private val workspaceDir: File,
  var sandboxProvider: SandboxProvider? = null,
  var sessionIdProvider: () -> String = { "default" }
) : Tool {

  override val name: String = "check_tools"
  override val description: String =
    "Check availability of shell commands, binaries, and Python modules in the sandbox. Returns a structured JSON map indicating individual status for each requested binary."

  override val schema: ToolSchema = ToolSchema(
    listOf(
      ToolParameter(
        name = "tools",
        type = "array",
        description = "List of command names to check (e.g. ['nmap', 'curl', 'dig', 'python3', 'lsof']).",
        required = true,
        aliases = listOf("binaries", "commands", "tool_names")
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
      val distinctTools = toolsList.map { it.trim() }.filter { it.isNotBlank() }.distinct()
      val detailsMap = mutableMapOf<String, JSONObject>()
      val simpleMap = mutableMapOf<String, Boolean>()

      // Enforce strict 12-second total budget (well within 15-second timeout)
      val batchSuccess = withTimeoutOrNull(12_000L) {
        // Strategy 1: Efficient Single-Batch Remote Execution (1 network round-trip)
        try {
          val jsonToolsArray = JSONArray(distinctTools).toString()
          val batchScript = """
python3 -c "
import os, sys, shutil, importlib.util, json

tools = json.loads('''$jsonToolsArray''')
alias_map = {
    'docx': 'docx',
    'xlsx': 'openpyxl',
    'excel': 'openpyxl',
    'bs4': 'bs4',
    'beautifulsoup': 'bs4',
    'beautifulsoup4': 'bs4',
    'pil': 'PIL',
    'pillow': 'PIL',
    'yaml': 'yaml',
    'pyyaml': 'yaml',
    'pdf': 'pypdf',
    'pypdf': 'pypdf'
}

venv_py = '/home/user/venv/bin/python3'
has_venv = os.path.isfile(venv_py) and os.access(venv_py, os.X_OK)

res = {}
for t in tools:
    name = str(t).strip()
    if not name or any(c in name for c in '\";&|'):
        res[name] = {'available': False, 'error': 'invalid name'}
        continue
    bin_path = shutil.which(name)
    if bin_path:
        res[name] = {'available': True, 'type': 'binary', 'path': bin_path}
        continue
    py_mod = alias_map.get(name.lower(), name)
    mod_found = False
    try:
        if importlib.util.find_spec(py_mod) is not None:
            mod_found = True
    except Exception:
        pass
    if not mod_found and has_venv:
        try:
            import subprocess
            cmd = [venv_py, '-c', f'import importlib.util, sys; sys.exit(0 if importlib.util.find_spec(\"{py_mod}\") is not None else 1)']
            r = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2)
            if r.returncode == 0:
                mod_found = True
        except Exception:
            pass
    if mod_found:
        res[name] = {'available': True, 'type': 'python_module', 'module': py_mod}
    else:
        res[name] = {'available': False, 'type': 'missing'}

print('___CHECK_TOOLS_START___' + json.dumps(res) + '___CHECK_TOOLS_END___')
"
          """.trimIndent()

          val req = RunCommandRequest.builder()
            .command(batchScript)
            .timeout(java.time.Duration.ofSeconds(10))
            .build()

          val exec = provider.executeCommand(sessionId, req)
          val out = exec.stdoutText()
          if (out.contains("___CHECK_TOOLS_START___") && out.contains("___CHECK_TOOLS_END___")) {
            val jsonStr = out.substringAfter("___CHECK_TOOLS_START___").substringBefore("___CHECK_TOOLS_END___")
            val batchObj = JSONObject(jsonStr)
            for (t in distinctTools) {
              val item = batchObj.optJSONObject(t)
              if (item != null) {
                detailsMap[t] = item
                simpleMap[t] = item.optBoolean("available", false)
              } else {
                val missingObj = JSONObject().apply {
                  put("available", false)
                  put("type", "missing")
                }
                detailsMap[t] = missingObj
                simpleMap[t] = false
              }
            }
            return@withTimeoutOrNull true
          }
        } catch (_: Exception) {}

        // Strategy 2: Bounded Parallel Checks Fallback (Semaphore of 4 concurrency)
        val sem = Semaphore(4)
        val deferredList = distinctTools.map { toolName ->
          async {
            sem.withPermit {
              val sanitized = toolName.trim()
              if (sanitized.isBlank() || sanitized.contains(Regex("[^a-zA-Z0-9_.-]"))) {
                return@withPermit toolName to JSONObject().apply {
                  put("available", false)
                  put("error", "invalid_name")
                }
              }
              try {
                val req = RunCommandRequest.builder()
                  .command("command -v $sanitized")
                  .timeout(java.time.Duration.ofSeconds(3))
                  .build()
                val exec = provider.executeCommand(sessionId, req)
                val isBin = exec.exitCode == 0 && exec.stdoutText().isNotBlank()
                if (isBin) {
                  return@withPermit toolName to JSONObject().apply {
                    put("available", true)
                    put("type", "binary")
                    put("path", exec.stdoutText().trim())
                  }
                }

                // Check python module fallback
                val pyPkg = when (sanitized.lowercase()) {
                  "docx" -> "docx"
                  "xlsx", "excel" -> "openpyxl"
                  "bs4", "beautifulsoup", "beautifulsoup4" -> "bs4"
                  "pil", "pillow" -> "PIL"
                  else -> sanitized
                }
                val pyCheck = "([ -x /home/user/venv/bin/python3 ] && /home/user/venv/bin/python3 -c 'import $pyPkg' 2>/dev/null) || python3 -c 'import $pyPkg' 2>/dev/null"
                val pyExec = provider.executeCommand(sessionId, RunCommandRequest.builder().command(pyCheck).timeout(java.time.Duration.ofSeconds(3)).build())
                val isPy = pyExec.exitCode == 0
                return@withPermit toolName to JSONObject().apply {
                  put("available", isPy)
                  put("type", if (isPy) "python_module" else "missing")
                }
              } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@withPermit toolName to JSONObject().apply {
                  put("available", false)
                  put("error", e.message ?: "check_failed")
                }
              }
            }
          }
        }

        val results = deferredList.awaitAll()
        for ((name, obj) in results) {
          detailsMap[name] = obj
          simpleMap[name] = obj.optBoolean("available", false)
        }
        true
      }

      // Ensure every requested tool has an individual result, even upon timeout
      for (t in distinctTools) {
        if (!detailsMap.containsKey(t)) {
          detailsMap[t] = JSONObject().apply {
            put("available", false)
            put("error", "deadline_exceeded")
          }
          simpleMap[t] = false
        }
      }

      val responseJson = JSONObject().apply {
        // Backwards compatibility: flat boolean map
        for ((k, v) in simpleMap) {
          put(k, v)
        }
        val detailsObj = JSONObject()
        for ((k, v) in detailsMap) {
          detailsObj.put(k, v)
        }
        put("_details", detailsObj)
        put("_total_checked", distinctTools.size)
        put("_available_count", simpleMap.values.count { it })
      }.toString(2)

      return@withContext ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = responseJson,
        error = null,
        errorType = null,
        duration = System.currentTimeMillis() - startTime,
        stdout = responseJson,
        stderr = "",
        exitCode = 0
      )
    }
}
