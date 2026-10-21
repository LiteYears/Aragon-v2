package com.example.agent.core

data class CommandLintResult(
  val isAllowed: Boolean,
  val ruleId: String? = null,
  val message: String? = null,
  val rewrittenCommand: String? = null,
  val isSuppressedStderr: Boolean = false,
  val autoFix: Map<String, Any?>? = null
)

/**
 * Static command linter enforcing E2B Cloud MicroVM containment, output visibility,
 * and deterministic execution guardrails. The sandbox is the only execution
 * environment: the workspace root is /home/user and scratch space is /tmp.
 */
class CommandLinter {

  private val errRedirectRegex = Regex("""2\s*>\s*/dev/null""")
  private val pipeTrueRegex = Regex("""\|\s*true\b""")
  private val unboundedCurlRegex = Regex("""curl\s+[^|]+\|\s*(?:head|tail)\b""")
  private val destructiveRmRegex = Regex("""rm\s+(?:-[a-zA-Z]*r[a-zA-Z]*\s+)?(?:/|/\*|/home|/home/user|/root)\b""")
  private val externalWriteRegex = Regex("""(?:>|>>|tee\s+(?:-a\s+)?)\s*(/home(?:/[^\s;&|]+)?|/var/[^\s;&|]+|/etc/[^\s;&|]+|/root/[^\s;&|]+)""")
  private val foreignCdRegex = Regex("""\bcd\s+(/home(?:/[^\s;&|]+)?|/root|/var|/usr|/etc)[^\s;&|]*""")

  private val pipInstallSegmentRegex = Regex("""(?<![\w./-])(?:pip3?|python3?\s+-m\s+pip)\s+install\b([^;&|\n]*)""")
  private val pipInstallTokenRegex = Regex("""(?<![\w./-])(?:pip3?|python3?\s+-m\s+pip)\s+install\b""")
  private val sudoWordRegex = Regex("""(?<![\w-])sudo(?![\w-])""")
  private val aptMutateTokenRegex = Regex("""(?<![\w./-])(?:apt-get|apt)(?=\s+(?:update|install|upgrade|dist-upgrade|full-upgrade|remove|purge|autoremove)\b)""")

  fun lint(
    command: String,
    isBestEffort: Boolean = false,
    timeoutSeconds: Int = 0
  ): CommandLintResult {
    val trimmed = command.trim()

    // 1. Destructive system operations
    if (destructiveRmRegex.containsMatchIn(trimmed)) {
      return CommandLintResult(
        isAllowed = false,
        ruleId = "LINT_CRITICAL_DESTRUCTIVE",
        message = "Destructive file removal targeting system root or user profile is forbidden."
      )
    }

    // 1b. Multi-GB ML stacks pip install (torch/tensorflow/easyocr...) exceed the microVM budget
    val heavyPip = findHeavyPipPackage(trimmed)
    if (heavyPip != null) {
      return CommandLintResult(
        isAllowed = false,
        ruleId = "LINT_HEAVY_PIP",
        message = "Refusing to pip-install '${heavyPip.first}' inside the E2B MicroVM: ${heavyPip.second}"
      )
    }

    // 2. Track stderr suppression (2>/dev/null) without blocking standard shell discovery idioms (find, which, netstat || ss)
    val isSuppressed = errRedirectRegex.containsMatchIn(trimmed) || pipeTrueRegex.containsMatchIn(trimmed)

    // 4. Writing outside the E2B workspace (/home/user) and /tmp scratch
    val writeMatch = externalWriteRegex.find(trimmed)
    if (writeMatch != null) {
      val targetPath = writeMatch.groupValues[1]
      val isE2bHome = targetPath.startsWith("/home/user")
      val isTmp = targetPath.startsWith("/tmp")

      if (!isE2bHome && !isTmp) {
        return CommandLintResult(
          isAllowed = false,
          ruleId = "LINT_004",
          message = "Disallowed write to '$targetPath' outside the workspace. All intermediate and deliverable files must be written inside /home/user (or /tmp for scratch)."
        )
      }
    }

    // 5. Changing directory outside the E2B workspace
    val cdMatch = foreignCdRegex.find(trimmed)
    if (cdMatch != null) {
      val targetDir = cdMatch.groupValues[1]
      val isE2bHome = targetDir.startsWith("/home/user")
      val isTmp = targetDir.startsWith("/tmp")

      if (!isE2bHome && !isTmp) {
        return CommandLintResult(
          isAllowed = false,
          ruleId = "LINT_005",
          message = "Disallowed directory navigation to '$targetDir'. Working directory is confined to the workspace (/home/user) or /tmp. Store and access files within /home/user."
        )
      }
    }

    // 6. Unbounded curl pipe without timeout
    if (unboundedCurlRegex.containsMatchIn(trimmed) && timeoutSeconds <= 0 && !trimmed.contains("--max-time") && !trimmed.contains("-m ")) {
      return CommandLintResult(
        isAllowed = false,
        ruleId = "LINT_003",
        message = "Unbounded curl pipe detected without timeout. Add '--max-time 15' to prevent hung socket connections.",
        autoFix = mapOf("inject_arg" to "--max-time", "value" to "15")
      )
    }

    // 7. Deterministic auto-fixes applied to otherwise allowed commands:
    //    a) pip install -> append --no-cache-dir (prevents pip buffering whole wheels in RAM => MemoryError)
    //    b) apt/apt-get mutations -> prefix sudo (E2B commands run as non-root 'user')
    var rewritten: String? = null
    if (pipInstallTokenRegex.containsMatchIn(trimmed) &&
      !trimmed.contains("--no-cache-dir") &&
      !trimmed.contains("PIP_NO_CACHE_DIR")
    ) {
      rewritten = pipInstallTokenRegex.replace(rewritten ?: trimmed) { "${it.value} --no-cache-dir" }
    }
    if (aptMutateTokenRegex.containsMatchIn(trimmed) && !sudoWordRegex.containsMatchIn(trimmed)) {
      rewritten = aptMutateTokenRegex.replace(rewritten ?: trimmed) { "sudo ${it.value}" }
    }
    if (rewritten != null && rewritten != trimmed) {
      return CommandLintResult(
        isAllowed = true,
        ruleId = "LINT_AUTOFIX",
        rewrittenCommand = rewritten,
        isSuppressedStderr = isSuppressed
      )
    }

    return CommandLintResult(isAllowed = true, isSuppressedStderr = isSuppressed)
  }

  /**
   * Detects pip install arguments matching known multi-GB packages. Returns the normalized
   * package name and its actionable remediation guidance, or null when the command is safe.
   */
  private fun findHeavyPipPackage(command: String): Pair<String, String>? {
    for (match in pipInstallSegmentRegex.findAll(command)) {
      val args = match.groupValues.getOrNull(1).orEmpty()
      for (rawArg in args.split(Regex("""\s+"""))) {
        val arg = rawArg.trim().trim('\'', '"')
        if (arg.isBlank() || arg.startsWith("-")) continue
        val baseName = arg
          .substringBefore("==").substringBefore(">=").substringBefore("<=")
          .substringBefore("~=").substringBefore("!=").substringBefore(">")
          .substringBefore("<").substringBefore("[").substringBefore(";")
          .substringBefore("@").trim().lowercase().replace('_', '-')
        val guidance = SandboxPackagePolicy.HEAVY_PACKAGES[baseName]
        if (guidance != null) return baseName to guidance
      }
    }
    return null
  }
}
