package com.example.agent.core

data class ReasoningHistoryItem(
  val cleanedText: String,
  val timestampMs: Long,
  val stepNumber: Int,
  val observationFingerprint: String? = null
)

/**
 * Deduplication and post-processing pipeline for LLM reasoning text.
 * Prevents streaming-vs-buffering duplication, loops, and corrupted null tokens.
 */
class ReasoningDedupPipeline(
  private val similarityThreshold: Double = 0.80,
  private val timeWindowMs: Long = 30_000L
) {

  private val history = mutableListOf<ReasoningHistoryItem>()

  fun process(
    rawThought: String?,
    currentStep: Int,
    nowMs: Long = System.currentTimeMillis(),
    observationFingerprint: String? = null
  ): String {
    if (rawThought.isNullOrBlank()) return ""

    // 1. Strip null/undefined tokens, non-printable null bytes, and redundant [Reasoning] markers
    val sanitized = rawThought
      .replace("\u0000", "")
      .replace(Regex("""\[Reasoning]\s*""", RegexOption.IGNORE_CASE), "")
      .replace(Regex("""^(?:ing]|-docx)\s*""", RegexOption.IGNORE_CASE), "")
      .trim()

    if (sanitized.isBlank()) return ""

    // 2. Intra-thought sentence & paragraph deduplication (preserving markdown line breaks)
    val dedupedWithinBlock = deduplicateAdjacentSentences(sanitized)

    // 3. Sliding time-window similarity check
    history.removeAll { nowMs - it.timestampMs > timeWindowMs }

    for (previous in history) {
      // Intra-turn streaming chunk or duplicate callback deduplication
      if (currentStep == previous.stepNumber) {
        if (dedupedWithinBlock.equals(previous.cleanedText, ignoreCase = true)) {
          return "[REASONING_DEDUP_HIT: Duplicate callback in Turn $currentStep]"
        }
        if (dedupedWithinBlock.startsWith(previous.cleanedText) && dedupedWithinBlock.length > previous.cleanedText.length) {
          // Accumulated streaming chunk; update the recorded item and return the complete text
          val idx = history.indexOf(previous)
          if (idx >= 0) {
            history[idx] = previous.copy(cleanedText = dedupedWithinBlock, timestampMs = nowMs)
          }
          return dedupedWithinBlock
        }
      }

      val similarity = calculateJaccardSimilarity(dedupedWithinBlock, previous.cleanedText)

      // When a genuine new observation occurred between turns, do not suppress reasoning
      // simply because it mentions similar entity or task names. Only collapse if exact text duplicate.
      val hasNewObservation = observationFingerprint != null &&
        previous.observationFingerprint != null &&
        observationFingerprint != previous.observationFingerprint

      val shouldCollapse = if (hasNewObservation) {
        similarity >= 0.95 && dedupedWithinBlock.equals(previous.cleanedText, ignoreCase = true)
      } else {
        similarity >= similarityThreshold
      }

      if (shouldCollapse) {
        val logMsg = "[REASONING_DEDUP_HIT: Collapsed repeated reasoning from Turn ${previous.stepNumber} (No new justification or state change)]"
        return logMsg
      }
    }

    history.add(ReasoningHistoryItem(dedupedWithinBlock, nowMs, currentStep, observationFingerprint))
    return dedupedWithinBlock
  }

  fun clear() {
    history.clear()
  }

  private fun deduplicateAdjacentSentences(text: String): String {
    val lines = text.lines()
    val dedupedLines = mutableListOf<String>()
    for (line in lines) {
      val trimmedLine = line.trim()
      if (trimmedLine.isBlank()) {
        if (dedupedLines.isNotEmpty() && dedupedLines.last().isNotBlank()) {
          dedupedLines.add("")
        }
        continue
      }
      val sentences = trimmedLine.split(SENTENCE_SPLIT_REGEX).filter { it.isNotBlank() }
      val uniqueSentences = mutableListOf<String>()
      for (s in sentences) {
        val cleanS = s.trim()
        val isDupSentence = uniqueSentences.any { existing ->
          existing.equals(cleanS, ignoreCase = true) ||
            (cleanS.length > 24 && existing.length > 24 && calculateJaccardSimilarity(existing, cleanS) >= 0.72)
        }
        if (!isDupSentence) {
          uniqueSentences.add(cleanS)
        }
      }
      val reconstructedLine = uniqueSentences.joinToString(" ")
      val isDupLine = dedupedLines.filter { it.isNotBlank() }.any { existingLine ->
        existingLine.equals(reconstructedLine, ignoreCase = true) ||
          (reconstructedLine.length > 30 && existingLine.length > 30 && calculateJaccardSimilarity(existingLine, reconstructedLine) >= 0.70)
      }
      if (!isDupLine) {
        dedupedLines.add(reconstructedLine)
      }
    }
    return dedupedLines.joinToString("\n").trim()
  }

  fun calculateJaccardSimilarity(s1: String, s2: String): Double {
    val set1 = s1.lowercase().split(WHITESPACE_SPLIT_REGEX).filter { it.length > 2 }.toSet()
    val set2 = s2.lowercase().split(WHITESPACE_SPLIT_REGEX).filter { it.length > 2 }.toSet()
    if (set1.isEmpty() && set2.isEmpty()) return 1.0
    if (set1.isEmpty() || set2.isEmpty()) return 0.0

    var intersection = 0
    val (smaller, larger) = if (set1.size <= set2.size) set1 to set2 else set2 to set1
    for (word in smaller) {
      if (larger.contains(word)) intersection++
    }
    val union = set1.size + set2.size - intersection
    if (union == 0) return 1.0
    return intersection.toDouble() / union.toDouble()
  }

  companion object {
    private val SENTENCE_SPLIT_REGEX = Regex("""(?<=[.!?])\s+""")
    private val WHITESPACE_SPLIT_REGEX = Regex("""\s+""")
  }
}
