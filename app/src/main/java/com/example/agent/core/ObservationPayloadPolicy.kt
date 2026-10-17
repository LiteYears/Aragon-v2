package com.example.agent.core

import java.io.File
import java.nio.charset.StandardCharsets

object ObservationPayloadPolicy {
  const val MAX_PAYLOAD_BYTES = 32 * 1024 // 32 KB threshold

  data class FormattedObservation(
    val contextPayload: String,
    val isTruncated: Boolean,
    val totalBytes: Int,
    val spilloverPath: String?
  )

  fun processOutput(
    workspaceDir: File,
    stepId: String,
    rawStdout: String,
    rawStderr: String,
    customMaxBytes: Int? = null,
    isSuppressedStderr: Boolean = false
  ): FormattedObservation {
    val effectiveCap = customMaxBytes ?: MAX_PAYLOAD_BYTES
    val stderrTag = if (isSuppressedStderr) "[SUPPRESSED_STDERR]\n" else ""
    val combinedRaw = when {
      rawStderr.isBlank() -> rawStdout
      rawStdout.isBlank() -> "$stderrTag$rawStderr"
      else -> "$rawStdout\n${stderrTag}[STDERR]\n$rawStderr"
    }
    val bytes = combinedRaw.toByteArray(StandardCharsets.UTF_8)

    if (bytes.size <= effectiveCap) {
      return FormattedObservation(
        contextPayload = combinedRaw,
        isTruncated = false,
        totalBytes = bytes.size,
        spilloverPath = null
      )
    }

    // Output exceeds hard cap: spill full output to disk in $WORKSPACE/.observations
    val obsDir = File(workspaceDir, ".observations").apply { mkdirs() }
    val safeStepId = stepId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    val spilloverFile = File(obsDir, "${safeStepId}.txt")
    try {
      spilloverFile.writeText(combinedRaw, StandardCharsets.UTF_8)
    } catch (_: Exception) {}

    val lines = combinedRaw.lines()
    val headLines = lines.take(50).joinToString("\n")
    val tailLines = lines.takeLast(25).joinToString("\n")
    val omittedCount = (lines.size - 75).coerceAtLeast(0)

    val excerpt = buildString {
      appendLine("⚠️ [OBSERVATION TRUNCATED: Output size ${bytes.size / 1024} KB exceeds context cap of ${effectiveCap / 1024} KB]")
      appendLine("Full output saved to: \$WORKSPACE/.observations/${spilloverFile.name}")
      appendLine("--- FIRST 50 LINES ---")
      appendLine(headLines)
      if (omittedCount > 0) {
        appendLine("--- ... [$omittedCount lines omitted; inspect via grep or head/tail on file path above] ... ---")
      }
      appendLine("--- LAST 25 LINES ---")
      appendLine(tailLines)
    }

    return FormattedObservation(
      contextPayload = excerpt,
      isTruncated = true,
      totalBytes = bytes.size,
      spilloverPath = spilloverFile.absolutePath
    )
  }
}
