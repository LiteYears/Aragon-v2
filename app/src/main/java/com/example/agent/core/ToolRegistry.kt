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

  /**
   * Replaces an existing tool or registers if not present.
   * Returns the previously registered tool under this name, if any.
   */
  fun replace(tool: Tool): Tool? {
    require(tool.name.isNotBlank()) { "Tool name cannot be blank" }
    val old = tools.remove(tool.name)
    tools[tool.name] = tool
    return old
  }

  /**
   * Inspects the full typed metadata of a tool by name.
   */
  fun getToolMetadata(name: String): ToolMetadata? {
    return getTool(name)?.getMetadata()
  }

  /**
   * Lists the full typed metadata of all currently available tools.
   */
  fun listToolMetadata(): List<ToolMetadata> {
    return getAvailableTools().map { it.getMetadata() }
  }

  /**
   * Retrieves registered tools belonging to a specific category.
   */
  fun getToolsByCategory(category: ToolCategory): List<Tool> {
    return getAvailableTools().filter { it.category == category }
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

    // 1. Terminal / Shell Command Execution
    if (canonicalName == "terminal") {
      val cmdAlias = normArgs["command"] ?: normArgs["cmd"] ?: normArgs["c"]
      if (cmdAlias != null) normArgs["command"] = cmdAlias
      val timeoutAlias = normArgs["timeoutSeconds"] ?: normArgs["timeout"] ?: normArgs["timeout_seconds"]
      if (timeoutAlias != null) normArgs["timeoutSeconds"] = timeoutAlias
      val cwdAlias = normArgs["cwd"] ?: normArgs["workingDirectory"] ?: normArgs["working_dir"] ?: normArgs["dir"]
      if (cwdAlias != null) normArgs["cwd"] = cwdAlias
      val maxOutputAlias = normArgs["max_output"] ?: normArgs["maxOutput"]
      if (maxOutputAlias != null) normArgs["max_output"] = maxOutputAlias
      val bestEffortAlias = normArgs["best_effort"] ?: normArgs["bestEffort"]
      if (bestEffortAlias != null) normArgs["best_effort"] = bestEffortAlias
      val allowNonzeroAlias = normArgs["allow_nonzero"] ?: normArgs["allowNonzero"] ?: normArgs["allowNonZero"]
      if (allowNonzeroAlias != null) normArgs["allow_nonzero"] = allowNonzeroAlias
      val resetCwdAlias = normArgs["reset_cwd"] ?: normArgs["resetWorkingDirectory"] ?: normArgs["reset_working_directory"]
      if (resetCwdAlias != null) normArgs["reset_cwd"] = resetCwdAlias
    }

    // 2. Code Interpreter
    if (canonicalName == "code_interpreter") {
      val codeAlias = normArgs["code"] ?: normArgs["script"] ?: normArgs["source"] ?: normArgs["snippet"]
      if (codeAlias != null) normArgs["code"] = codeAlias
      val langAlias = normArgs["language"] ?: normArgs["lang"]
      if (langAlias != null) normArgs["language"] = langAlias
      val timeoutAlias = normArgs["timeoutSeconds"] ?: normArgs["timeout"] ?: normArgs["timeout_seconds"]
      if (timeoutAlias != null) normArgs["timeoutSeconds"] = timeoutAlias
    }

    // 3. Web & Research Tools
    if (canonicalName == "deep_research") {
      val queryAlias = normArgs["query"] ?: normArgs["topic"] ?: normArgs["prompt"] ?: normArgs["search"] ?: normArgs["q"]
      if (queryAlias != null) normArgs["query"] = queryAlias
    }
    if (canonicalName == "web_search") {
      val queryAlias = normArgs["query"] ?: normArgs["q"] ?: normArgs["search"] ?: normArgs["term"] ?: normArgs["prompt"]
      if (queryAlias != null) normArgs["query"] = queryAlias
      val maxResultsAlias = normArgs["maxResults"] ?: normArgs["max_results"] ?: normArgs["limit"]
      if (maxResultsAlias != null) normArgs["maxResults"] = maxResultsAlias
    }
    if (canonicalName in listOf("web_open", "web_extract", "web_fetch")) {
      val urlAlias = normArgs["url"] ?: normArgs["uri"] ?: normArgs["link"] ?: normArgs["target_url"] ?: normArgs["address"]
      if (urlAlias != null) normArgs["url"] = urlAlias
    }
    if (canonicalName == "web_open") {
      val maxCharsAlias = normArgs["maxChars"] ?: normArgs["max_chars"] ?: normArgs["limit"]
      if (maxCharsAlias != null) normArgs["maxChars"] = maxCharsAlias
    }
    if (canonicalName == "web_extract") {
      val targetAlias = normArgs["target"] ?: normArgs["extract"] ?: normArgs["mode"]
      if (targetAlias != null) normArgs["target"] = targetAlias
    }
    if (canonicalName == "web_crawl") {
      val startUrlAlias = normArgs["startUrl"] ?: normArgs["start_url"] ?: normArgs["url"]
      if (startUrlAlias != null) normArgs["startUrl"] = startUrlAlias
      val maxPagesAlias = normArgs["maxPages"] ?: normArgs["max_pages"]
      if (maxPagesAlias != null) normArgs["maxPages"] = maxPagesAlias
    }
    if (canonicalName == "web_fetch") {
      val maxBytesAlias = normArgs["max_bytes"] ?: normArgs["maxBytes"]
      if (maxBytesAlias != null) normArgs["max_bytes"] = maxBytesAlias
      val jsonPathAlias = normArgs["json_path"] ?: normArgs["jsonPath"]
      if (jsonPathAlias != null) normArgs["json_path"] = jsonPathAlias
      val searchQueryAlias = normArgs["search_query"] ?: normArgs["searchQuery"] ?: normArgs["query"] ?: normArgs["filter"]
      if (searchQueryAlias != null) normArgs["search_query"] = searchQueryAlias
    }

    // 4. Utility & Probing Tools
    if (canonicalName == "check_tools") {
      val toolsAlias = normArgs["tools"] ?: normArgs["binaries"] ?: normArgs["commands"] ?: normArgs["tool_names"]
      if (toolsAlias != null) normArgs["tools"] = toolsAlias
    }
    if (canonicalName == "install_package") {
      val nameAlias = normArgs["name"] ?: normArgs["package"] ?: normArgs["pkg"] ?: normArgs["module"] ?: normArgs["package_name"]
      if (nameAlias != null) normArgs["name"] = nameAlias
      val srcAlias = normArgs["sourceUrl"] ?: normArgs["source_url"] ?: normArgs["url"]
      if (srcAlias != null) normArgs["sourceUrl"] = srcAlias
    }
    if (canonicalName == "environment_capability") {
      val actAlias = normArgs["action"] ?: normArgs["act"] ?: normArgs["operation"] ?: normArgs["op"]
      if (actAlias != null) normArgs["action"] = actAlias
      val capAlias = normArgs["capability"] ?: normArgs["cap"] ?: normArgs["id"] ?: normArgs["capId"] ?: normArgs["capability_id"]
      if (capAlias != null) normArgs["capability"] = capAlias
      val catAlias = normArgs["category"] ?: normArgs["filter"]
      if (catAlias != null) normArgs["category"] = catAlias
    }
    if (canonicalName == "browser_action") {
      val actAlias = normArgs["action"] ?: normArgs["act"] ?: normArgs["op"]
      if (actAlias != null) normArgs["action"] = actAlias
      val urlAlias = normArgs["url"] ?: normArgs["target_url"] ?: normArgs["link"] ?: normArgs["address"]
      if (urlAlias != null) normArgs["url"] = urlAlias
      val selAlias = normArgs["selector"] ?: normArgs["element"] ?: normArgs["target"] ?: normArgs["css"]
      if (selAlias != null) normArgs["selector"] = selAlias
      val textAlias = normArgs["text"] ?: normArgs["value"] ?: normArgs["input"]
      if (textAlias != null) normArgs["text"] = textAlias
      val scrollAlias = normArgs["scrollDirection"] ?: normArgs["scroll_direction"] ?: normArgs["direction"]
      if (scrollAlias != null) normArgs["scrollDirection"] = scrollAlias
    }

    // 5. Filesystem Tools (E2B Cloud MicroVM)
    if (canonicalName in listOf("read_file", "write_file", "edit_file", "delete_file", "file_exists", "file_metadata", "apply_patch", "list_directory")) {
      val pathAlias = normArgs["path"] ?: normArgs["file"] ?: normArgs["filename"] ?: normArgs["file_path"] ?: normArgs["filePath"] ?: normArgs["target"] ?: normArgs["dir"] ?: normArgs["directory"] ?: normArgs["folder"]
      if (pathAlias != null) normArgs["path"] = pathAlias
    }
    if (canonicalName == "write_file") {
      val contentAlias = normArgs["content"] ?: normArgs["text"] ?: normArgs["data"] ?: normArgs["body"]
      if (contentAlias != null) normArgs["content"] = contentAlias
    }
    if (canonicalName == "edit_file") {
      val targetAlias = normArgs["targetContent"] ?: normArgs["target_content"] ?: normArgs["old_str"] ?: normArgs["old_string"] ?: normArgs["old_content"] ?: normArgs["search"]
      if (targetAlias != null) normArgs["targetContent"] = targetAlias
      val replAlias = normArgs["replacementContent"] ?: normArgs["replacement_content"] ?: normArgs["new_str"] ?: normArgs["new_string"] ?: normArgs["new_content"] ?: normArgs["replace"]
      if (replAlias != null) normArgs["replacementContent"] = replAlias
    }
    if (canonicalName == "apply_patch") {
      val patchAlias = normArgs["patch"] ?: normArgs["diff"] ?: normArgs["content"] ?: normArgs["patch_content"] ?: normArgs["hunk"]
      if (patchAlias != null) normArgs["patch"] = patchAlias
    }
    if (canonicalName == "diff_files") {
      val origAlias = normArgs["originalPath"] ?: normArgs["original_path"] ?: normArgs["file1"] ?: normArgs["original"] ?: normArgs["orig"] ?: normArgs["source"]
      if (origAlias != null) normArgs["originalPath"] = origAlias
      val modAlias = normArgs["modifiedPath"] ?: normArgs["modified_path"] ?: normArgs["file2"] ?: normArgs["modified"] ?: normArgs["dest"] ?: normArgs["target"]
      if (modAlias != null) normArgs["modifiedPath"] = modAlias
    }
    if (canonicalName == "move_file" || canonicalName == "copy_file") {
      val srcAlias = normArgs["sourcePath"] ?: normArgs["source_path"] ?: normArgs["source"] ?: normArgs["src"] ?: normArgs["file"]
      if (srcAlias != null) normArgs["sourcePath"] = srcAlias
      val destAlias = normArgs["destinationPath"] ?: normArgs["destination_path"] ?: normArgs["destination"] ?: normArgs["dest"] ?: normArgs["target"]
      if (destAlias != null) normArgs["destinationPath"] = destAlias
    }
    if (canonicalName == "create_directory" || canonicalName == "delete_directory") {
      val dirAlias = normArgs["path"] ?: normArgs["dir"] ?: normArgs["directory"] ?: normArgs["folder"]
      if (dirAlias != null) normArgs["path"] = dirAlias
    }
    if (canonicalName == "transfer_artifact_to_sandbox" || canonicalName == "download_artifact_from_sandbox") {
      val localAlias = normArgs["localPath"] ?: normArgs["local_path"] ?: normArgs["source"] ?: normArgs["file"] ?: normArgs["local"]
      if (localAlias != null) normArgs["localPath"] = localAlias
      val remoteAlias = normArgs["remotePath"] ?: normArgs["remote_path"] ?: normArgs["destination"] ?: normArgs["dest"] ?: normArgs["remote"]
      if (remoteAlias != null) normArgs["remotePath"] = remoteAlias
    }

    // 6. Document Generation & Replication Tools
    if (canonicalName in listOf("build_word_document", "build_excel_spreadsheet", "build_pdf_document")) {
      val pathAlias = normArgs["path"] ?: normArgs["file"] ?: normArgs["filePath"] ?: normArgs["file_path"] ?: normArgs["target"]
      if (pathAlias != null) normArgs["path"] = pathAlias
      val contentAlias = normArgs["content"] ?: normArgs["text"] ?: normArgs["markdown"] ?: normArgs["body"]
      if (contentAlias != null) normArgs["content"] = contentAlias
    }
    if (canonicalName == "build_excel_spreadsheet") {
      val csvAlias = normArgs["csvData"] ?: normArgs["csv_data"] ?: normArgs["data"] ?: normArgs["csv"] ?: normArgs["content"]
      if (csvAlias != null) normArgs["csvData"] = csvAlias
    }
    if (canonicalName == "recreate_document") {
      val srcAlias = normArgs["sourcePath"] ?: normArgs["source_path"] ?: normArgs["source"] ?: normArgs["file"] ?: normArgs["path"]
      if (srcAlias != null) normArgs["sourcePath"] = srcAlias
      val fmtAlias = normArgs["targetFormat"] ?: normArgs["target_format"] ?: normArgs["format"] ?: normArgs["output_format"]
      if (fmtAlias != null) normArgs["targetFormat"] = fmtAlias
    }
    if (canonicalName == "document_process") {
      val pathAlias = normArgs["path"] ?: normArgs["file"] ?: normArgs["filePath"] ?: normArgs["file_path"] ?: normArgs["target"]
      if (pathAlias != null) normArgs["path"] = pathAlias
    }
    if (canonicalName == "read_image") {
      val imgAlias = normArgs["imagePath"] ?: normArgs["image_path"] ?: normArgs["path"] ?: normArgs["file"] ?: normArgs["image"]
      if (imgAlias != null) normArgs["imagePath"] = imgAlias
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
   * Performs structured schema and availability validation for a tool call.
   */
  fun validateStructured(toolCall: ToolCall): SchemaValidationResult {
    val normalized = normalizeToolCall(toolCall)
    val tool = getTool(normalized.toolName)
    if (tool == null) {
      val availableNames = getAvailableTools().joinToString(", ") { it.name }
      return SchemaValidationResult.failure(
        ParameterValidationError(
          parameterName = "toolName",
          expectedType = "registered_tool",
          actualValue = toolCall.toolName,
          message = "Unknown tool '${toolCall.toolName}'. Available tools: $availableNames"
        )
      )
    }

    if (!tool.isAvailable()) {
      return SchemaValidationResult.failure(
        ParameterValidationError(
          parameterName = "toolName",
          expectedType = "available_tool",
          actualValue = tool.name,
          message = "Tool '${tool.name}' is registered but currently unavailable in this runtime environment."
        )
      )
    }

    return tool.schema.validateArguments(normalized.arguments)
  }

  /**
   * Validates tool existence, availability, and required schema arguments.
   * Returns null if valid, or a descriptive validation error string if invalid.
   */
  fun validate(toolCall: ToolCall): String? {
    val res = validateStructured(toolCall)
    return res.errorMessage
  }

  /**
   * Generates a concise, authoritative description of all available tools formatted for LLM consumption.
   */
  fun formatToolsPrompt(): String {
    val available = getAvailableTools()
    val sb = StringBuilder()
    sb.appendLine("AVAILABLE CANONICAL TOOLS (${available.size}):")
    for (tool in available) {
      val meta = tool.getMetadata()
      val mutStr = if (meta.mutatesWorkspace) "mutating" else "read-only"
      val cancelStr = if (meta.supportsCancellation) "cancellable" else "non-cancellable"
      sb.appendLine("- Tool: ${meta.name}")
      sb.appendLine("  Environment: ${meta.owningBackend.name} | WorkingDir: ${meta.workingDirectoryRequirement} | Category: ${meta.category.name} | Mode: $mutStr, $cancelStr | Timeout: ${meta.defaultTimeoutMs}ms")
      sb.appendLine("  Description: ${meta.description}")
      sb.appendLine("  Parameters:")
      if (meta.schema.parameters.isEmpty()) {
        sb.appendLine("    (none)")
      } else {
        for (p in meta.schema.parameters) {
          val req = if (p.required) "required" else "optional"
          val enums = if (p.enumValues != null) " [allowed: ${p.enumValues.joinToString(", ")}]" else ""
          val def = if (p.defaultValue != null) " [default: ${p.defaultValue}]" else ""
          val aliases = if (p.aliases.isNotEmpty()) " [aliases: ${p.aliases.joinToString(", ")}]" else ""
          sb.appendLine("    * ${p.name} (${p.type}, $req): ${p.description}$enums$def$aliases")
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
      val meta = tool.getMetadata()
      val toolObj = JSONObject()
      toolObj.put("type", "function")

      val funcObj = JSONObject()
      funcObj.put("name", meta.name)
      val mode = if (meta.mutatesWorkspace) "mutating" else "read-only"
      funcObj.put("description", "[${meta.owningBackend.name} | $mode] ${meta.description}")
      funcObj.put("parameters", meta.schema.toJsonObject())

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
      val meta = tool.getMetadata()
      val funcObj = JSONObject()
      funcObj.put("name", meta.name)
      val mode = if (meta.mutatesWorkspace) "mutating" else "read-only"
      funcObj.put("description", "[${meta.owningBackend.name} | $mode] ${meta.description}")
      funcObj.put("parameters", meta.schema.toJsonObject())
      arr.put(funcObj)
    }
    return arr
  }
}
