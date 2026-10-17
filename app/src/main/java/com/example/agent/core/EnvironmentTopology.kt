package com.example.agent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Detailed specification of an individual execution environment.
 */
data class EnvironmentSpec(
  val envId: String,
  val name: String,
  val tools: List<String>,
  val interpreterPath: String?,
  val sysPrefix: String?,
  val rootFilesystem: String,
  val persistsAcrossCalls: Boolean,
  val workspacePath: String
) {
  fun toJsonObject(): JSONObject = JSONObject().apply {
    put("env_id", envId)
    put("name", name)
    put("tools", JSONArray(tools))
    if (interpreterPath != null) put("interpreter_path", interpreterPath) else put("interpreter_path", JSONObject.NULL)
    if (sysPrefix != null) put("sys_prefix", sysPrefix) else put("sys_prefix", JSONObject.NULL)
    put("root_filesystem", rootFilesystem)
    put("persists_across_calls", persistsAcrossCalls)
    put("workspace_path", workspacePath)
  }
}

/**
 * Authoritative Environment Topology model declaring all execution environments,
 * their stable IDs, tool bindings, Python runtime paths, and persistence guarantees.
 */
data class EnvironmentTopology(
  val environments: List<EnvironmentSpec>
) {
  fun toJsonString(indent: Int = 2): String {
    val root = JSONObject()
    val envsArray = JSONArray()
    environments.forEach { envsArray.put(it.toJsonObject()) }
    root.put("environments", envsArray)
    return root.toString(indent)
  }

  fun toFormattedHeaderBlock(): String {
    val sb = StringBuilder()
    sb.appendLine("=== ENVIRONMENT TOPOLOGY ===")
    environments.forEach { env ->
      sb.appendLine("Environment: [${env.envId}] (${env.name})")
      sb.appendLine("  Workspace Path: ${env.workspacePath}")
      sb.appendLine("  Root Filesystem: ${env.rootFilesystem}")
      sb.appendLine("  Persistence: ${if (env.persistsAcrossCalls) "State persists across tool calls" else "Ephemeral"}")
      if (env.interpreterPath != null) {
        sb.appendLine("  Python Interpreter: ${env.interpreterPath} (sys.prefix: ${env.sysPrefix ?: "N/A"})")
      }
      sb.appendLine("  Bound Tools: ${env.tools.joinToString(", ")}")
    }
    return sb.toString().trimEnd()
  }

  companion object {
    fun createDefault(): EnvironmentTopology {
      val cloudTools = listOf(
        "terminal", "code_interpreter", "write_file", "read_file", "list_files", "list_directory",
        "edit_file", "apply_patch", "diff_files", "delete_file", "move_file", "copy_file",
        "create_directory", "delete_directory", "file_exists", "file_metadata",
        "install_package", "web_search", "web_open", "web_fetch",
        "e2b_sandbox", "e2b_write_file", "e2b_read_file", "e2b_list_directory",
        "check_tools", "get_environment_topology"
      )

      val cloudEnv = EnvironmentSpec(
        envId = "e2b_cloud",
        name = "E2B Cloud Sandbox (base)",
        tools = cloudTools,
        interpreterPath = "/usr/bin/python3",
        sysPrefix = "/usr",
        rootFilesystem = "/",
        persistsAcrossCalls = true,
        workspacePath = "/home/user"
      )

      return EnvironmentTopology(listOf(cloudEnv))
    }
  }
}

/**
 * Tool for retrieving the authoritative environment topology.
 */
class GetEnvironmentTopologyTool(
  val topologyProvider: () -> EnvironmentTopology
) : Tool {
  override val name: String = "get_environment_topology"
  override val description: String =
    "Retrieve the authoritative environment topology for the E2B Cloud Sandbox (/home/user), listing its stable env_ids, bound tools, interpreter paths, sys.prefix, persistence guarantees, and workspace paths."

  override val schema: ToolSchema = ToolSchema(emptyList())
  override val category: ToolCategory = ToolCategory.UTILITY
  override val target: ToolTarget = ToolTarget.SANDBOX_PROCESS
  override val permissions: Set<ToolPermission> = setOf(ToolPermission.READ_ONLY)

  override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
    val topology = topologyProvider()
    val json = topology.toJsonString()
    return ToolResult(
      callId = callId,
      toolName = name,
      status = ToolStatus.SUCCEEDED,
      arguments = arguments,
      output = json,
      error = null,
      duration = 0L,
      stdout = json,
      exitCode = 0
    )
  }
}

/**
 * Record representing an observed cross-environment event for audit trails.
 */
data class CrossEnvEvent(
  val envSrc: String,
  val envDst: String,
  val resource: String,
  val tool: String,
  val stepId: String,
  val resolution: String, // "failed" | "transferred" | "abandoned"
  val timestamp: Long = System.currentTimeMillis(),
  val details: String? = null
)

/**
 * Forensic audit logger tracking all cross-environment events across tools.
 */
object CrossEnvAuditLogger {
  private val events = mutableListOf<CrossEnvEvent>()

  fun logEvent(event: CrossEnvEvent) {
    synchronized(events) {
      events.add(event)
    }
  }

  fun getEvents(): List<CrossEnvEvent> {
    synchronized(events) {
      return events.toList()
    }
  }

  fun clear() {
    synchronized(events) {
      events.clear()
    }
  }
}

/**
 * Metadata record for a file within a specific environment.
 */
data class EnvFileEntry(
  val path: String,
  val hash: String,
  val size: Long,
  val mtime: Long
)

/**
 * Scoped filesystem and package index partitioned by environment ID.
 */
class EnvironmentStateIndex {
  val files: MutableMap<String, MutableMap<String, EnvFileEntry>> = mutableMapOf(
    "e2b_cloud" to mutableMapOf()
  )

  val packages: MutableMap<String, MutableMap<String, String>> = mutableMapOf(
    "e2b_cloud" to mutableMapOf()
  )

  fun recordFile(envId: String, path: String, hash: String, size: Long, mtime: Long) {
    val map = files.getOrPut(envId) { mutableMapOf() }
    map[path] = EnvFileEntry(path, hash, size, mtime)
  }

  fun recordPackage(envId: String, name: String, version: String = "installed") {
    val map = packages.getOrPut(envId) { mutableMapOf() }
    map[name.trim().lowercase()] = version
  }

  fun getPackage(envId: String, name: String): String? {
    return packages[envId]?.get(name.trim().lowercase())
  }

  fun isPackageInstalled(envId: String, name: String): Boolean {
    return packages[envId]?.containsKey(name.trim().lowercase()) == true
  }

  fun getFile(envId: String, path: String): EnvFileEntry? {
    return files[envId]?.get(path)
  }
}
