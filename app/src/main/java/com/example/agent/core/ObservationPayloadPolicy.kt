package com.example.agent.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Authoritative output payload management, truncation, targeted extraction, and outcome policy.
 *
 * Guarantees:
 * 1. Model context is protected from massive payloads (default 16KB cap).
 * 2. Complete raw payloads are safely preserved on disk in $WORKSPACE/.observations/.
 * 3. Structured TruncationDetails are provided for fine-grained feedback.
 * 4. Targeted inspection tools (JSON path extraction, keyword search, line pagination) are supported.
 * 5. Distinct classification of tool execution outcomes (failed, empty, results, condition not found, truncated, unverified).
 */
object ObservationPayloadPolicy {
  const val DEFAULT_MAX_PAYLOAD_BYTES = 16 * 1024 // 16 KB default context cap
  const val MAX_PAYLOAD_BYTES = 32 * 1024         // 32 KB upper ceiling

  data class FormattedObservation(
    val contextPayload: String,
    val isTruncated: Boolean,
    val totalBytes: Int,
    val capturedBytes: Int,
    val totalLines: Int,
    val capturedLines: Int,
    val spilloverPath: String?,
    val truncationDetails: TruncationDetails
  )

  /**
   * Processes raw stdout and stderr according to the payload policy.
   * If combined size exceeds [customMaxBytes] (or [DEFAULT_MAX_PAYLOAD_BYTES]),
   * full output is saved to disk and a concise excerpt with targeted inspection hints is returned.
   */
  fun processOutput(
    workspaceDir: File,
    stepId: String,
    rawStdout: String,
    rawStderr: String,
    customMaxBytes: Int? = null,
    isSuppressedStderr: Boolean = false,
    fileExtension: String = "txt"
  ): FormattedObservation {
    val effectiveCap = (customMaxBytes ?: DEFAULT_MAX_PAYLOAD_BYTES).coerceAtMost(MAX_PAYLOAD_BYTES)
    val stderrTag = if (isSuppressedStderr) "[SUPPRESSED_STDERR]\n" else ""
    val combinedRaw = when {
      rawStderr.isBlank() -> rawStdout
      rawStdout.isBlank() -> "$stderrTag$rawStderr"
      else -> "$rawStdout\n${stderrTag}[STDERR]\n$rawStderr"
    }
    val bytes = combinedRaw.toByteArray(StandardCharsets.UTF_8)
    val lines = combinedRaw.lines()
    val totalLines = lines.size
    val totalBytes = bytes.size

    if (totalBytes <= effectiveCap) {
      val details = TruncationDetails(
        isTruncated = false,
        originalBytes = totalBytes,
        capturedBytes = totalBytes,
        originalLines = totalLines,
        capturedLines = totalLines,
        omittedLines = 0,
        omittedBytes = 0,
        spilloverPath = null,
        inspectionHint = null
      )
      return FormattedObservation(
        contextPayload = combinedRaw,
        isTruncated = false,
        totalBytes = totalBytes,
        capturedBytes = totalBytes,
        totalLines = totalLines,
        capturedLines = totalLines,
        spilloverPath = null,
        truncationDetails = details
      )
    }

    // Output exceeds cap: spill full output to disk in $WORKSPACE/.observations
    val obsDir = File(workspaceDir, ".observations").apply { mkdirs() }
    val safeStepId = stepId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    val ext = fileExtension.removePrefix(".").ifBlank { "txt" }
    val spilloverFile = File(obsDir, "${safeStepId}.${ext}")
    try {
      spilloverFile.writeText(combinedRaw, StandardCharsets.UTF_8)
    } catch (_: Exception) {}

    val headLines = lines.take(45).joinToString("\n")
    val tailLines = lines.takeLast(15).joinToString("\n")
    val omittedCount = (lines.size - 60).coerceAtLeast(0)
    val omittedBytesEst = (totalBytes - (headLines.length + tailLines.length)).coerceAtLeast(0)

    val relativeSpillPath = ".observations/${spilloverFile.name}"
    val inspectionHint = "To search: terminal 'grep -in \"pattern\" $relativeSpillPath' | To page: read_file path='$relativeSpillPath' offset=46 limit=50"

    val excerpt = buildString {
      appendLine("⚠️ [OBSERVATION TRUNCATED: Output size ${totalBytes / 1024} KB ($totalLines lines) exceeds context cap of ${effectiveCap / 1024} KB]")
      appendLine("Full output preserved at: \$WORKSPACE/$relativeSpillPath")
      appendLine("--- FIRST 45 LINES ---")
      appendLine(headLines)
      if (omittedCount > 0) {
        appendLine("--- ... [$omittedCount lines omitted ($omittedBytesEst bytes); full output preserved at \$WORKSPACE/$relativeSpillPath] ... ---")
      }
      appendLine("--- LAST 15 LINES ---")
      appendLine(tailLines)
      appendLine()
      appendLine("--- TARGETED INSPECTION HINTS ---")
      appendLine("• Search full output:  terminal: grep -in \"pattern\" $relativeSpillPath")
      appendLine("• Page through lines:  read_file: path=\"$relativeSpillPath\" offset=46 limit=50")
      if (ext == "json" || combinedRaw.trim().startsWith("{") || combinedRaw.trim().startsWith("[")) {
        appendLine("• Extract JSON field:  terminal: python3 -c \"import json; d=json.load(open('$relativeSpillPath')); print(d['key'])\"")
      }
    }

    val capturedBytes = excerpt.toByteArray(StandardCharsets.UTF_8).size
    val details = TruncationDetails(
      isTruncated = true,
      originalBytes = totalBytes,
      capturedBytes = capturedBytes,
      originalLines = totalLines,
      capturedLines = 60.coerceAtMost(totalLines),
      omittedLines = omittedCount,
      omittedBytes = omittedBytesEst,
      spilloverPath = spilloverFile.absolutePath,
      inspectionHint = inspectionHint
    )

    return FormattedObservation(
      contextPayload = excerpt,
      isTruncated = true,
      totalBytes = totalBytes,
      capturedBytes = capturedBytes,
      totalLines = totalLines,
      capturedLines = 60.coerceAtMost(totalLines),
      spilloverPath = spilloverFile.absolutePath,
      truncationDetails = details
    )
  }

  /**
   * Evaluates the execution results to determine the distinct [ToolExecutionOutcome].
   * Unambiguously differentiates empty searches, command failures, truncated results, and unverified outcomes.
   */
  fun classifyOutcome(
    toolName: String,
    status: ToolStatus,
    exitCode: Int?,
    stdout: String?,
    stderr: String?,
    command: String? = null,
    isTruncated: Boolean = false,
    explicitOutcome: ToolExecutionOutcome? = null
  ): ToolExecutionOutcome {
    if (explicitOutcome != null) return explicitOutcome
    if (status == ToolStatus.FAILED || status == ToolStatus.CANCELLED) {
      return ToolExecutionOutcome.EXECUTION_FAILED
    }

    val cleanCmd = (command ?: "").trim()
    val rawOut = stdout.orEmpty()
    val rawErr = stderr.orEmpty()

    // 1. Process / Shell Command Pipeline Analysis
    if (toolName == "terminal" || cleanCmd.isNotBlank()) {
      val isSearchCommand = isSearchOrValidationCommand(cleanCmd)
      val isConditionCheck = isValidationCheckCommand(cleanCmd)

      if (exitCode != null && exitCode != 0) {
        if (isSearchCommand && exitCode == 1 && rawErr.isBlank()) {
          // In POSIX grep/rg, exit code 1 means 0 lines matched, NOT an execution failure.
          return ToolExecutionOutcome.CONDITION_NOT_FOUND
        }
        if (isConditionCheck && exitCode == 1) {
          // Validation condition evaluated to false (e.g. test -f file, [ -d dir ])
          return ToolExecutionOutcome.CONDITION_NOT_FOUND
        }
        if (cleanCmd.startsWith("diff ") && exitCode == 1) {
          // diff exits 1 when inputs differ (successful diff)
          return if (isTruncated) ToolExecutionOutcome.TRUNCATED_OR_PARTIAL else ToolExecutionOutcome.SUCCESS_WITH_RESULTS
        }
        return ToolExecutionOutcome.EXECUTION_FAILED
      }

      // Exit code is 0 (or null)
      if (rawOut.isBlank()) {
        if (isSearchCommand) {
          // Search/filter succeeded but produced 0 results
          return ToolExecutionOutcome.CONDITION_NOT_FOUND
        }
        return ToolExecutionOutcome.SUCCESS_EMPTY
      }

      if (isTruncated) return ToolExecutionOutcome.TRUNCATED_OR_PARTIAL
      if (isSearchCommand && (rawOut.trim() == "0" || rawOut.contains("0 results") || rawOut.contains("0 matches"))) {
        return ToolExecutionOutcome.CONDITION_NOT_FOUND
      }
      return ToolExecutionOutcome.SUCCESS_WITH_RESULTS
    }

    // 2. Web Search
    if (toolName == "web_search") {
      if (rawOut.contains("0 direct results") || rawOut.contains("0 results") || rawOut.isBlank()) {
        return ToolExecutionOutcome.CONDITION_NOT_FOUND
      }
      return if (isTruncated) ToolExecutionOutcome.TRUNCATED_OR_PARTIAL else ToolExecutionOutcome.SUCCESS_WITH_RESULTS
    }

    // 3. Document Search
    if (toolName == "document_search") {
      if (rawOut.contains("RESULTS: 0") || rawOut.contains("No matching content was found")) {
        return ToolExecutionOutcome.CONDITION_NOT_FOUND
      }
      return if (isTruncated) ToolExecutionOutcome.TRUNCATED_OR_PARTIAL else ToolExecutionOutcome.SUCCESS_WITH_RESULTS
    }

    // 4. File Exists
    if (toolName == "file_exists") {
      if (rawOut.contains("does not exist", ignoreCase = true)) {
        return ToolExecutionOutcome.CONDITION_NOT_FOUND
      }
      return ToolExecutionOutcome.SUCCESS_WITH_RESULTS
    }

    // 5. General Succeeded Outcomes
    if (isTruncated) return ToolExecutionOutcome.TRUNCATED_OR_PARTIAL
    if (rawOut.isBlank() && rawErr.isBlank()) return ToolExecutionOutcome.SUCCESS_EMPTY
    return ToolExecutionOutcome.SUCCESS_WITH_RESULTS
  }

  fun isSearchOrValidationCommand(cmd: String): Boolean {
    val lower = cmd.lowercase(Locale.US)
    return lower.startsWith("grep ") || lower.contains("| grep ") || lower.contains("|grep ") ||
      lower.startsWith("egrep ") || lower.startsWith("fgrep ") || lower.startsWith("rg ") || lower.contains("| rg ") ||
      lower.startsWith("find ") || lower.startsWith("locate ") || lower.startsWith("which ") ||
      lower.startsWith("test ") || lower.startsWith("[ ") || lower.startsWith("[[ ")
  }

  fun isValidationCheckCommand(cmd: String): Boolean {
    val lower = cmd.lowercase(Locale.US)
    return lower.startsWith("test ") || lower.startsWith("[ ") || lower.startsWith("[[ ") ||
      lower.startsWith("which ") || lower.startsWith("command -v ")
  }

  /**
   * Targeted JSON extraction utility.
   * Traverses dotted paths and array index notations (e.g. "items[0].id" or "data.user.name").
   */
  fun extractJsonPath(rawJson: String, path: String): String? {
    if (rawJson.isBlank() || path.isBlank()) return null
    return try {
      val trimmed = rawJson.trim()
      var current: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
      val segments = path.split('.').filter { it.isNotBlank() }

      for (seg in segments) {
        if (seg.contains('[') && seg.endsWith(']')) {
          val key = seg.substringBefore('[')
          val idxStr = seg.substringAfter('[').removeSuffix("]")
          val idx = idxStr.toIntOrNull() ?: return null
          val nextObj = if (key.isNotEmpty()) {
            if (current is JSONObject) (current as JSONObject).get(key) else return null
          } else current

          current = if (nextObj is JSONArray && idx in 0 until nextObj.length()) {
            nextObj.get(idx)
          } else return null
        } else {
          current = if (current is JSONObject && (current as JSONObject).has(seg)) {
            (current as JSONObject).get(seg)
          } else return null
        }
      }

      when (current) {
        is JSONObject -> (current as JSONObject).toString(2)
        is JSONArray -> (current as JSONArray).toString(2)
        else -> current.toString()
      }
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Targeted line search utility.
   * Extracts lines matching a pattern with line numbers and hit counts.
   */
  fun searchLines(rawText: String, query: String, maxMatches: Int = 30): String {
    if (rawText.isBlank() || query.isBlank()) return ""
    val lines = rawText.lines()
    val matches = mutableListOf<String>()
    val qLower = query.lowercase(Locale.US)
    for ((index, line) in lines.withIndex()) {
      if (line.lowercase(Locale.US).contains(qLower)) {
        matches.add("Line ${index + 1}: ${line.trimEnd()}")
        if (matches.size >= maxMatches) break
      }
    }
    if (matches.isEmpty()) {
      return "No lines matching '$query' found across ${lines.size} lines."
    }
    val sb = StringBuilder()
    sb.appendLine("Found ${matches.size} line match(es) for '$query':")
    matches.forEach { sb.appendLine(it) }
    if (matches.size >= maxMatches) {
      sb.appendLine("... [Results capped at $maxMatches matches] ...")
    }
    return sb.toString().trim()
  }

  /**
   * Line pagination utility.
   * Returns a 1-based sliced page of lines with TruncationDetails.
   */
  fun paginateLines(rawText: String, offset: Int, limit: Int): Pair<String, TruncationDetails> {
    val lines = rawText.lines()
    val totalLines = lines.size
    val startIdx = (offset - 1).coerceIn(0, totalLines)
    val endIdx = (startIdx + limit).coerceIn(startIdx, totalLines)
    val pageLines = if (startIdx < totalLines) lines.subList(startIdx, endIdx) else emptyList()
    val remaining = (totalLines - endIdx).coerceAtLeast(0)
    val isTruncated = startIdx > 0 || endIdx < totalLines

    val sb = StringBuilder()
    sb.appendLine("--- LINES $offset TO $endIdx OF $totalLines ---")
    pageLines.forEachIndexed { i, l -> sb.appendLine("${startIdx + i + 1}: $l") }
    if (remaining > 0) {
      sb.appendLine("--- ... [$remaining lines remaining; use offset=${endIdx + 1} limit=$limit to continue] ... ---")
    }

    val content = sb.toString().trim()
    val bytes = content.toByteArray(StandardCharsets.UTF_8).size
    val totalBytes = rawText.toByteArray(StandardCharsets.UTF_8).size

    val details = TruncationDetails(
      isTruncated = isTruncated,
      originalBytes = totalBytes,
      capturedBytes = bytes,
      originalLines = totalLines,
      capturedLines = pageLines.size,
      omittedLines = totalLines - pageLines.size,
      omittedBytes = (totalBytes - bytes).coerceAtLeast(0),
      inspectionHint = if (remaining > 0) "Use offset=${endIdx + 1} limit=$limit to read next page." else null
    )
    return Pair(content, details)
  }
}
