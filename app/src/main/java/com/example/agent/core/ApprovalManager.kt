package com.example.agent.core

/**
 * Safety policy level for tool and command execution approval.
 */
enum class ApprovalPolicy {
  AUTO_APPROVE,       // Non-destructive commands and registered read-only tools auto-approved
  REQUIRE_APPROVAL,   // High-risk commands (e.g. rm -rf, apt-get, network writes, system modifications) require approval
  STRICT_SANDBOX      // Only read-only or strictly confined workspace operations permitted
}

/**
 * Decision returned by the ApprovalManager for an evaluated tool call.
 */
data class ApprovalDecision(
  val isApproved: Boolean,
  val requiresUserPrompt: Boolean = false,
  val reason: String = ""
)

/**
 * Enforces least-privilege security, resource limits, approval policies,
 * and dangerous command interception across all sandbox tool invocations.
 */
class ApprovalManager(
  var policy: ApprovalPolicy = ApprovalPolicy.AUTO_APPROVE
) {

  // Dangerous shell patterns that must be intercepted or require explicit approval
  private val dangerousShellPatterns = listOf(
    Regex("""\brm\s+(-[rfRF]+\s+)?(/|/\*|~|\.\./\.\.)"""),
    Regex("""\b(mkfs|dd\s+if=|fdisk|parted)\b"""),
    Regex("""\b(shutdown|reboot|poweroff|halt)\b"""),
    Regex("""\b(chmod\s+777\s+/|chown\s+root)\b"""),
    Regex("""\b(:\(\)\s*\{\s*:\|:&\s*\};:)\b"""), // fork bomb
    Regex("""\b(cat\s+/dev/urandom\s*>\s*/dev/|cat\s+/dev/zero\s*>\s*/dev/)""")
  )

  /**
   * Evaluates whether a tool call is safe to proceed under the current approval policy.
   */
  fun evaluate(toolCall: ToolCall, tool: Tool?): ApprovalDecision {
    if (tool == null) {
      return ApprovalDecision(
        isApproved = false,
        reason = "Tool '${toolCall.toolName}' is not registered in the canonical ToolRegistry."
      )
    }

    // Read-only tools are intrinsically safe under all policies
    if (tool.permissions.contains(ToolPermission.READ_ONLY) && tool.permissions.size == 1) {
      return ApprovalDecision(isApproved = true, reason = "Read-only inspection tool.")
    }

    // Strict sandbox policy blocks destructive process or network tools
    if (policy == ApprovalPolicy.STRICT_SANDBOX) {
      if (tool.permissions.contains(ToolPermission.PROCESS_EXECUTION) || tool.permissions.contains(ToolPermission.PACKAGE_INSTALL)) {
        return ApprovalDecision(
          isApproved = false,
          reason = "Execution blocked: STRICT_SANDBOX policy disallows raw process execution or package installation."
        )
      }
    }

    // Inspect command arguments for dangerous shell patterns
    if (toolCall.toolName in listOf("terminal", "bash", "sh", "exec")) {
      val cmd = toolCall.arguments["command"]?.toString() ?: ""
      for (pattern in dangerousShellPatterns) {
        if (pattern.containsMatchIn(cmd)) {
          val needsPrompt = policy == ApprovalPolicy.REQUIRE_APPROVAL
          return ApprovalDecision(
            isApproved = false,
            requiresUserPrompt = needsPrompt,
            reason = "Potentially destructive shell command intercepted by sandbox safety guard: '$cmd'"
          )
        }
      }
    }

    // Check path traversal parameters
    for ((key, value) in toolCall.arguments) {
      if ((key in listOf("path", "file", "filename", "sourcePath", "destinationPath", "localPath", "remotePath", "originalPath", "modifiedPath")) && value is String) {
        val clean = value.trim()
        if (clean.contains("../") || clean.startsWith("/etc") || clean.startsWith("/sys") || clean.startsWith("/proc")) {
          return ApprovalDecision(
            isApproved = false,
            reason = "Path traversal violation: argument '$key' ('$clean') attempts to escape the configured sandbox workspace root."
          )
        }
      }
    }

    return ApprovalDecision(
      isApproved = true,
      reason = "Permitted under current policy ($policy)."
    )
  }
}
