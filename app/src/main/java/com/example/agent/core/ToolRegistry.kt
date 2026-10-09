package com.example.agent.core

/**
 * Registry of canonical tools available to the Agent.
 * Exposes clean, unambiguous definitions to the LLM.
 */
class ToolRegistry {
  private val tools = LinkedHashMap<String, Tool>()
  private val aliases = HashMap<String, String>()

  fun register(tool: Tool, vararg aliasNames: String) {
    tools[tool.name] = tool
    for (alias in aliasNames) {
      aliases[alias] = tool.name
    }
  }

  fun getTool(name: String): Tool? {
    val canonicalName = aliases[name] ?: name
    return tools[canonicalName]
  }

  fun getAllTools(): List<Tool> = tools.values.toList()

  /**
   * Validates tool name and required schema arguments.
   * Returns null if valid, or a descriptive validation error message if invalid.
   */
  fun validate(toolCall: ToolCall): String? {
    val tool = getTool(toolCall.toolName)
      ?: return "Unknown tool '${toolCall.toolName}'. Available tools: ${tools.keys.joinToString(", ")}"

    for (param in tool.schema.parameters) {
      if (param.required) {
        val value = toolCall.arguments[param.name]
        if (value == null || (value is String && value.isBlank())) {
          return "Missing required parameter '${param.name}' for tool '${tool.name}'"
        }
      }
    }
    return null
  }

  /**
   * Generates a concise system description of all tools formatted for LLM consumption.
   */
  fun formatToolsPrompt(): String {
    val sb = StringBuilder()
    sb.append("AVAILABLE TOOLS:\n")
    for (tool in tools.values) {
      sb.append("- Tool: ").append(tool.name).append("\n")
      sb.append("  Description: ").append(tool.description).append("\n")
      sb.append("  Parameters:\n")
      for (p in tool.schema.parameters) {
        val req = if (p.required) "required" else "optional"
        val enums = if (p.enumValues != null) " [values: ${p.enumValues.joinToString(", ")}]" else ""
        sb.append("    * ${p.name} (${p.type}, $req): ${p.description}$enums\n")
      }
      sb.append("\n")
    }
    return sb.toString()
  }
}
