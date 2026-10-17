package com.example.agent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Validation error details when checking a tool invocation against its schema.
 */
data class ToolValidationError(
  val toolName: String,
  val message: String,
  val missingParam: String? = null,
  val invalidParam: String? = null
)

/**
 * The single, canonical ToolRegistry.
 * Every tool is explicitly registered with its name, schema, category, target,
 * permissions, timeout, and cancellation support.
 *
 * Exposes ONLY tools that actually exist and are currently executable.
 * Never aliases shell binaries or registers hypothetical/fake tools.
 */
class ToolRegistry {
  private val tools = LinkedHashMap<String, Tool>()

  /**
   * Registers a canonical tool. Overwrites if same tool name is registered.
   */
  fun register(tool: Tool) {
    require(tool.name.isNotBlank()) { "Tool name cannot be blank" }
    tools[tool.name] = tool
  }

  /**
   * Unregisters a tool by name.
   */
  fun unregister(name: String): Tool? {
    return tools.remove(name)
  }

  companion object {
    private val TOOL_ALIASES = mapOf(
      "bash" to "terminal",
      "sh" to "terminal",
      "shell" to "terminal",
      "exec" to "terminal",
      "execute_command" to "terminal",
      "run_command" to "terminal",
      "command" to "terminal",
      "cmd" to "terminal",
      "read" to "read_file",
      "view_file" to "read_file",
      "cat" to "read_file",
      "write" to "write_file",
      "create_file" to "write_file",
      "save_file" to "write_file",
      "edit" to "edit_file",
      "patch_file" to "apply_patch",
      "apply_patch" to "apply_patch",
      "patch" to "apply_patch",
      "apply_diff" to "apply_patch",
      "diff_patch" to "apply_patch",
      "diff" to "diff_files",
      "diff_files" to "diff_files",
      "compare_files" to "diff_files",
      "update_file" to "edit_file",
      "ls" to "list_directory",
      "dir" to "list_directory",
      "list_dir" to "list_directory",
      "rm" to "delete_file",
      "delete" to "delete_file",
      "remove_file" to "delete_file",
      "mkdir" to "create_directory",
      "create_dir" to "create_directory",
      "rmdir" to "delete_directory",
      "delete_dir" to "delete_directory",
      "mv" to "move_file",
      "rename" to "move_file",
      "cp" to "copy_file",
      "exists" to "file_exists",
      "stat" to "file_metadata",
      "search" to "web_search",
      "google_search" to "web_search",
      "browse" to "web_open",
      "open_url" to "web_open",
      "extract" to "web_extract",
      "crawl" to "web_crawl",
      "fetch" to "web_fetch",
      "curl" to "web_fetch",
      "fetch_url" to "web_fetch",
      "install" to "install_package",
      "pip_install" to "install_package",
      "pkg_install" to "install_package",
      "run_code" to "code_interpreter",
      "python" to "code_interpreter",
      "execute_code" to "code_interpreter",
      "eval" to "code_interpreter",
      "diagnostics" to "sandbox_diagnostics",
      "system_info" to "sandbox_diagnostics",
      "sandbox_info" to "sandbox_diagnostics",
      "manage_capabilities" to "environment_capability",
      "capability_manager" to "environment_capability",
      "capabilities" to "environment_capability",
      "capability" to "environment_capability",
      "check_capability" to "environment_capability",
      "provision_capability" to "environment_capability",
      "browser" to "browser_action",
      "web_browser" to "browser_action",
      "deep_search" to "deep_research",
      "web_research" to "deep_research",
      "transfer_artifact" to "transfer_artifact_to_sandbox",
      "push_artifact" to "transfer_artifact_to_sandbox",
      "transfer_to_sandbox" to "transfer_artifact_to_sandbox",
      "download_artifact" to "download_artifact_from_sandbox",
      "pull_artifact" to "download_artifact_from_sandbox",
      "download_from_sandbox" to "download_artifact_from_sandbox",
      "list_files" to "list_directory",
      "environment_topology" to "get_environment_topology",
      "get_topology" to "get_environment_topology",
      "topology" to "get_environment_topology",
      "ocr" to "read_image",
      "ocr_image" to "read_image",
      "transcribe_image" to "read_image",
      "read_image" to "read_image",
      "image_to_text" to "read_image",
      "python_execute" to "code_interpreter",
      "python_interpreter" to "code_interpreter",
      "search_web" to "web_search",
      "search_files" to "list_directory",
      "find_files" to "list_directory",
      "view_file" to "read_file",
      "e2b_write" to "write_file",
      "e2b_create_file" to "write_file",
      "e2b_write_file" to "write_file",
      "e2b_read" to "read_file",
      "e2b_read_file" to "read_file",
      "e2b_ls" to "list_directory",
      "e2b_list_dir" to "list_directory",
      "e2b_list_directory" to "list_directory",
      "replace_file_content" to "edit_file",
      "write_to_file" to "write_file",
      "read_text" to "read_file",
      "inspect_file" to "file_metadata",
      "get_file_info" to "file_metadata",
      "check_tool" to "check_tools",
      "probe_tools" to "check_tools",
      "verify_tools" to "check_tools",
      "terminal_command" to "terminal",
      "run_terminal" to "terminal",
      "run_bash" to "terminal",
      "bash_command" to "terminal",
      "replicate_document" to "recreate_document",
      "clone_document" to "recreate_document",
      "recreate_file" to "recreate_document",
      "recreate_word" to "recreate_document",
      "recreate_excel" to "recreate_document",
      "recreate_pdf" to "recreate_document",
      "build_pdf" to "build_pdf_document",
      "create_pdf" to "build_pdf_document",
      "generate_pdf" to "build_pdf_document",
      "pdf_builder" to "build_pdf_document"
    )

    fun resolveCanonicalName(name: String): String {
      val clean = name.trim().lowercase()
      return TOOL_ALIASES[clean] ?: clean
    }
  }

  /**
   * Resolves a tool name to its canonical form, preferring an exact registered
   * tool name over any alias so aliases can never shadow a real tool
   * (e.g. 'e2b_write_file' resolves to E2BWriteFileTool, not 'write_file').
   */
  fun resolveToolName(name: String): String {
    val clean = name.trim()
    if (tools.containsKey(clean)) return clean
    val canonical = resolveCanonicalName(clean)
    if (tools.containsKey(canonical)) return canonical

    // Smart MCP tool resolution:
    // Handle dot notation (e.g. "sqlite.query" -> "sqlite__query") or hyphen notation
    val doubleUnderscore = clean.replace(".", "__").replace("-", "_").replace(":", "__")
    if (tools.containsKey(doubleUnderscore)) return doubleUnderscore

    // Handle un-namespaced tool calls (e.g. model calls "query" or "describe_table")
    val mcpSuffixMatches = tools.keys.filter { it.endsWith("__$clean") }
    if (mcpSuffixMatches.size == 1) return mcpSuffixMatches.first()

    return canonical
  }

  /**
   * Retrieves a tool by its exact canonical name or common alias.
   */
  fun getTool(name: String): Tool? {
    val resolved = resolveToolName(name)
    val exact = tools[resolved]
    if (exact != null) return exact
    return tools.values.firstOrNull { it.name.equals(resolved, ignoreCase = true) }
  }

  /**
   * Normalizes tool name and parameter shorthands to canonical definitions.
   */
  fun normalizeToolCall(toolCall: ToolCall): ToolCall {
    val canonicalName = resolveToolName(toolCall.toolName)
    val normArgs = toolCall.arguments.toMutableMap()

    if (canonicalName == "terminal") {
      if (!normArgs.containsKey("command") && normArgs.containsKey("cmd")) {
        normArgs["command"] = normArgs["cmd"]
      }
    }
    if (canonicalName == "code_interpreter") {
      val codeAlias = normArgs["script"] ?: normArgs["source"] ?: normArgs["snippet"]
      if (!normArgs.containsKey("code") && codeAlias != null) {
        normArgs["code"] = codeAlias
      }
    }
    if (canonicalName == "deep_research") {
      val queryAlias = normArgs["topic"] ?: normArgs["prompt"] ?: normArgs["search"]
      if (!normArgs.containsKey("query") && queryAlias != null) {
        normArgs["query"] = queryAlias
      }
    }
    if (canonicalName in listOf("read_file", "write_file", "edit_file", "delete_file", "file_exists", "file_metadata", "apply_patch")) {
      val pathAlias = normArgs["file"] ?: normArgs["filename"] ?: normArgs["file_path"] ?: normArgs["target"]
      if (!normArgs.containsKey("path") && pathAlias != null) {
        normArgs["path"] = pathAlias
      }
    }
    if (canonicalName == "apply_patch") {
      val patchAlias = normArgs["diff"] ?: normArgs["content"] ?: normArgs["patch_content"] ?: normArgs["hunk"]
      if (!normArgs.containsKey("patch") && patchAlias != null) {
        normArgs["patch"] = patchAlias
      }
    }
    if (canonicalName == "diff_files") {
      val origAlias = normArgs["file1"] ?: normArgs["original"] ?: normArgs["orig"] ?: normArgs["source"]
      if (!normArgs.containsKey("originalPath") && origAlias != null) {
        normArgs["originalPath"] = origAlias
      }
      val modAlias = normArgs["file2"] ?: normArgs["modified"] ?: normArgs["dest"] ?: normArgs["target"]
      if (!normArgs.containsKey("modifiedPath") && modAlias != null) {
        normArgs["modifiedPath"] = modAlias
      }
    }
    if (canonicalName == "write_file") {
      val contentAlias = normArgs["text"] ?: normArgs["data"] ?: normArgs["body"]
      if (!normArgs.containsKey("content") && contentAlias != null) {
        normArgs["content"] = contentAlias
      }
    }
    if (canonicalName == "transfer_artifact_to_sandbox") {
      val localAlias = normArgs["local_path"] ?: normArgs["source"] ?: normArgs["file"] ?: normArgs["local"]
      if (!normArgs.containsKey("localPath") && localAlias != null) {
        normArgs["localPath"] = localAlias
      }
      val remoteAlias = normArgs["remote_path"] ?: normArgs["destination"] ?: normArgs["dest"] ?: normArgs["remote"]
      if (!normArgs.containsKey("remotePath") && remoteAlias != null) {
        normArgs["remotePath"] = remoteAlias
      }
    }
    if (canonicalName == "download_artifact_from_sandbox") {
      val remoteAlias = normArgs["remote_path"] ?: normArgs["source"] ?: normArgs["file"] ?: normArgs["remote"]
      if (!normArgs.containsKey("remotePath") && remoteAlias != null) {
        normArgs["remotePath"] = remoteAlias
      }
      val localAlias = normArgs["local_path"] ?: normArgs["destination"] ?: normArgs["dest"] ?: normArgs["local"]
      if (!normArgs.containsKey("localPath") && localAlias != null) {
        normArgs["localPath"] = localAlias
      }
    }
    if (canonicalName == "e2b_write_file") {
      val pathAlias = normArgs["file"] ?: normArgs["filename"] ?: normArgs["file_path"] ?: normArgs["target"]
      if (!normArgs.containsKey("path") && pathAlias != null) {
        normArgs["path"] = pathAlias
      }
      val contentAlias = normArgs["text"] ?: normArgs["data"] ?: normArgs["body"]
      if (!normArgs.containsKey("content") && contentAlias != null) {
        normArgs["content"] = contentAlias
      }
    }
    if (canonicalName == "e2b_read_file") {
      val pathAlias = normArgs["file"] ?: normArgs["filename"] ?: normArgs["file_path"] ?: normArgs["target"]
      if (!normArgs.containsKey("path") && pathAlias != null) {
        normArgs["path"] = pathAlias
      }
    }
    if (canonicalName == "e2b_list_directory") {
      val pathAlias = normArgs["dir"] ?: normArgs["directory"] ?: normArgs["folder"]
      if (!normArgs.containsKey("path") && pathAlias != null) {
        normArgs["path"] = pathAlias
      }
    }

    return toolCall.copy(toolName = canonicalName, arguments = normArgs)
  }

  /**
   * Returns only tools that are currently available and executable in the environment.
   */
  fun getAvailableTools(): List<Tool> {
    return tools.values.filter { it.isAvailable() }
  }

  /**
   * Returns all registered tools.
   */
  fun getAllTools(): List<Tool> = tools.values.toList()

  /**
   * Validates tool existence, availability, and required schema arguments.
   * Returns null if valid, or a descriptive validation error string if invalid.
   */
  fun validate(toolCall: ToolCall): String? {
    val normalized = normalizeToolCall(toolCall)
    val tool = getTool(normalized.toolName)
      ?: return "Unknown tool '${toolCall.toolName}'. Available tools: ${getAvailableTools().joinToString(", ") { it.name }}"

    if (!tool.isAvailable()) {
      return "Tool '${tool.name}' is currently unavailable in this runtime environment."
    }

    for (param in tool.schema.parameters) {
      val value = normalized.arguments[param.name]
      val isNullOrBlank = value == null ||
        value == JSONObject.NULL ||
        (value is String && value.isBlank())

      if (param.required && isNullOrBlank) {
        return "Missing required parameter '${param.name}' for tool '${tool.name}'."
      }

      if (!isNullOrBlank && param.enumValues != null) {
        val strValue = value.toString()
        if (!param.enumValues.contains(strValue)) {
          return "Invalid value '$strValue' for parameter '${param.name}'. Allowed values: ${param.enumValues.joinToString(", ")}"
        }
      }
    }
    return null
  }

  /**
   * Generates a concise, authoritative description of all available tools formatted for LLM consumption.
   */
  fun formatToolsPrompt(): String {
    val available = getAvailableTools()
    val sb = StringBuilder()
    sb.appendLine("AVAILABLE CANONICAL TOOLS (${available.size}):")
    for (tool in available) {
      sb.appendLine("- Tool: ${tool.name}")
      sb.appendLine("  Environment/Backend: ${tool.owningBackend.name} | Category: ${tool.category.name} | Target: ${tool.target.name} | Permissions: ${tool.permissions.joinToString()}")
      sb.appendLine("  Description: ${tool.description}")
      sb.appendLine("  Parameters:")
      if (tool.schema.parameters.isEmpty()) {
        sb.appendLine("    (none)")
      } else {
        for (p in tool.schema.parameters) {
          val req = if (p.required) "required" else "optional"
          val enums = if (p.enumValues != null) " [values: ${p.enumValues.joinToString(", ")}]" else ""
          val def = if (p.defaultValue != null) " [default: ${p.defaultValue}]" else ""
          sb.appendLine("    * ${p.name} (${p.type}, $req): ${p.description}$enums$def")
        }
      }
      sb.appendLine()
    }
    return sb.toString().trim()
  }

  /**
   * Generates OpenAI-compatible function definitions array for all available tools.
   */
  fun toOpenAiToolsJson(): JSONArray {
    val arr = JSONArray()
    for (tool in getAvailableTools()) {
      val toolObj = JSONObject()
      toolObj.put("type", "function")

      val funcObj = JSONObject()
      funcObj.put("name", tool.name)
      funcObj.put("description", "[Environment: ${tool.owningBackend.name}] ${tool.description}")
      funcObj.put("parameters", tool.schema.toJsonObject())

      toolObj.put("function", funcObj)
      arr.put(toolObj)
    }
    return arr
  }

  /**
   * Generates Gemini-compatible function declarations array for all available tools.
   */
  fun toGeminiFunctionDeclarations(): JSONArray {
    val arr = JSONArray()
    for (tool in getAvailableTools()) {
      val funcObj = JSONObject()
      funcObj.put("name", tool.name)
      funcObj.put("description", "[Environment: ${tool.owningBackend.name}] ${tool.description}")
      funcObj.put("parameters", tool.schema.toJsonObject())
      arr.put(funcObj)
    }
    return arr
  }
}
