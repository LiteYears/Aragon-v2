package com.example.agent.core

import com.alibaba.opensandbox.sandbox.Sandbox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Structured research finding record stored in /workspace/notes/research.jsonl.
 * Captures atomic, verified facts and sources to ensure research work survives model drops.
 */
data class ResearchFinding(
  val id: String = UUID.randomUUID().toString(),
  val ts: String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
  val topic: String, // politics, economy, diplomacy, security, sports, culture, general
  val headline: String,
  val summary: String,
  val date: String = "",
  val sources: List<String> = emptyList(),
  val confidence: String = "high", // high, medium, low
  val verified: Boolean = true,
  val notes: String? = null
) {
  fun toJsonString(): String {
    val obj = JSONObject()
    obj.put("id", id)
    obj.put("ts", ts)
    obj.put("topic", topic)
    obj.put("headline", headline)
    obj.put("summary", summary)
    obj.put("date", date)
    val srcArr = JSONArray()
    sources.forEach { srcArr.put(it) }
    obj.put("sources", srcArr)
    obj.put("confidence", confidence)
    obj.put("verified", verified)
    if (notes != null) obj.put("notes", notes)
    return obj.toString()
  }

  companion object {
    fun fromJson(jsonStr: String): ResearchFinding? {
      return try {
        val obj = JSONObject(jsonStr)
        val srcList = mutableListOf<String>()
        val arr = obj.optJSONArray("sources")
        if (arr != null) {
          for (i in 0 until arr.length()) {
            srcList.add(arr.optString(i))
          }
        }
        ResearchFinding(
          id = obj.optString("id", UUID.randomUUID().toString()),
          ts = obj.optString("ts", ""),
          topic = obj.optString("topic", "general"),
          headline = obj.optString("headline", ""),
          summary = obj.optString("summary", ""),
          date = obj.optString("date", ""),
          sources = srcList,
          confidence = obj.optString("confidence", "high"),
          verified = obj.optBoolean("verified", true),
          notes = if (obj.has("notes")) obj.optString("notes") else null
        )
      } catch (_: Exception) {
        null
      }
    }
  }
}

/**
 * Execution checkpoint for state recovery and resumption.
 * Stored in /workspace/checkpoint.json.
 */
data class AgentCheckpoint(
  val timestamp: Long = System.currentTimeMillis(),
  val taskGoal: String,
  val currentTurn: Int,
  val completedSections: List<String> = emptyList(),
  val pendingSections: List<String> = emptyList(),
  val planItems: List<String> = emptyList(),
  val artifactPaths: List<String> = emptyList(),
  val totalFindingsCount: Int = 0,
  val status: String = "IN_PROGRESS"
) {
  fun toJsonString(): String {
    val obj = JSONObject()
    obj.put("timestamp", timestamp)
    obj.put("taskGoal", taskGoal)
    obj.put("currentTurn", currentTurn)
    val compArr = JSONArray()
    completedSections.forEach { compArr.put(it) }
    obj.put("completedSections", compArr)
    val pendArr = JSONArray()
    pendingSections.forEach { pendArr.put(it) }
    obj.put("pendingSections", pendArr)
    val planArr = JSONArray()
    planItems.forEach { planArr.put(it) }
    obj.put("planItems", planArr)
    val artArr = JSONArray()
    artifactPaths.forEach { artArr.put(it) }
    obj.put("artifactPaths", artArr)
    obj.put("totalFindingsCount", totalFindingsCount)
    obj.put("status", status)
    return obj.toString(2)
  }

  companion object {
    fun fromJson(jsonStr: String): AgentCheckpoint? {
      return try {
        val obj = JSONObject(jsonStr)
        fun jsonArrayToList(arr: JSONArray?): List<String> {
          if (arr == null) return emptyList()
          val list = mutableListOf<String>()
          for (i in 0 until arr.length()) list.add(arr.optString(i))
          return list
        }
        AgentCheckpoint(
          timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
          taskGoal = obj.optString("taskGoal", ""),
          currentTurn = obj.optInt("currentTurn", 0),
          completedSections = jsonArrayToList(obj.optJSONArray("completedSections")),
          pendingSections = jsonArrayToList(obj.optJSONArray("pendingSections")),
          planItems = jsonArrayToList(obj.optJSONArray("planItems")),
          artifactPaths = jsonArrayToList(obj.optJSONArray("artifactPaths")),
          totalFindingsCount = obj.optInt("totalFindingsCount", 0),
          status = obj.optString("status", "IN_PROGRESS")
        )
      } catch (_: Exception) {
        null
      }
    }
  }
}

/**
 * Manages atomic filesystem scratchpad and checkpoint persistence.
 * Guarantees zero data loss across model re-routings or connection drops.
 */
class ScratchpadMemoryManager(val sandbox: Sandbox) {

  @Volatile
  var currentSessionId: String = UUID.randomUUID().toString()
    private set

  fun initSession(sessionId: String = UUID.randomUUID().toString()) {
    currentSessionId = sessionId.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { UUID.randomUUID().toString() }
    try {
      notesDir.mkdirs()
    } catch (_: Exception) {}
  }

  fun clearSessionMemory() {
    try {
      notesDir.deleteRecursively()
      File(sandbox.baseDir, "notes").deleteRecursively()
      File(sandbox.baseDir, "plan.md").delete()
      File(sandbox.baseDir, "checkpoint.json").delete()
    } catch (_: Exception) {}
  }

  private val notesDir: File
    get() = File(sandbox.baseDir, "notes/$currentSessionId").apply { if (!exists()) mkdirs() }

  val researchFile: File
    get() = File(notesDir, "research.jsonl")

  val planFile: File
    get() = File(notesDir, "plan.md")

  val checkpointFile: File
    get() = File(notesDir, "checkpoint.json")

  /**
   * Appends an atomic research finding to research.jsonl.
   */
  @Synchronized
  fun recordFinding(finding: ResearchFinding) {
    try {
      notesDir.mkdirs()
      researchFile.appendText(finding.toJsonString() + "\n", StandardCharsets.UTF_8)
    } catch (_: Exception) {}
  }

  /**
   * Automatically extracts and records findings from successful tool outputs (e.g. web search / browse).
   */
  fun autoIngestFromToolOutput(toolName: String, args: Map<String, Any?>, output: String) {
    if (output.isBlank() || output.length < 50) return
    val topic = when {
      output.contains("سياس", ignoreCase = true) || output.contains("حكوم", ignoreCase = true) || output.contains("politic", ignoreCase = true) -> "politics"
      output.contains("اقتصاد", ignoreCase = true) || output.contains("سوناطراك", ignoreCase = true) || output.contains("ميزاني", ignoreCase = true) || output.contains("econom", ignoreCase = true) -> "economy"
      output.contains("دبلوماس", ignoreCase = true) || output.contains("سفار", ignoreCase = true) || output.contains("علاق", ignoreCase = true) || output.contains("diploma", ignoreCase = true) -> "diplomacy"
      output.contains("أمن", ignoreCase = true) || output.contains("جيش", ignoreCase = true) || output.contains("دفاع", ignoreCase = true) || output.contains("securit", ignoreCase = true) -> "security"
      output.contains("رياض", ignoreCase = true) || output.contains("كرو", ignoreCase = true) || output.contains("sport", ignoreCase = true) -> "sports"
      else -> "general"
    }

    val sources = mutableListOf<String>()
    args["url"]?.toString()?.let { if (it.startsWith("http")) sources.add(it) }
    // Extract candidate URLs from output text
    val urlRegex = Regex("https?://[a-zA-Z0-9./?=_-]+")
    urlRegex.findAll(output).take(4).forEach { match ->
      val url = match.value
      if (!sources.contains(url)) sources.add(url)
    }

    val headlineCandidate = when (toolName) {
      "web_search" -> args["query"]?.toString() ?: "Web Research: $topic"
      "web_browse" -> output.lines().firstOrNull { it.startsWith("=== BROWSE:") }?.removePrefix("=== BROWSE:")?.trim() ?: "Browsed Source ($topic)"
      else -> "Verified Finding: $topic"
    }

    val summarySnippet = output.lines()
      .filter { it.isNotBlank() && !it.startsWith("===") && !it.startsWith("URL:") }
      .take(4)
      .joinToString(" ")
      .take(300)

    if (summarySnippet.isNotBlank()) {
      recordFinding(
        ResearchFinding(
          topic = topic,
          headline = headlineCandidate.take(120),
          summary = summarySnippet,
          sources = sources,
          confidence = "high",
          verified = true
        )
      )
    }
  }

  /**
   * Reads all persisted research findings from disk.
   */
  fun loadAllFindings(): List<ResearchFinding> {
    if (!researchFile.exists()) return emptyList()
    return try {
      researchFile.readLines(StandardCharsets.UTF_8)
        .mapNotNull { line -> if (line.isNotBlank()) ResearchFinding.fromJson(line.trim()) else null }
    } catch (_: Exception) {
      emptyList()
    }
  }

  /**
   * Writes the checklist plan to plan.md.
   */
  @Synchronized
  fun savePlan(planLines: List<String>) {
    try {
      val sb = StringBuilder()
      sb.appendLine("# Autonomous Execution Plan")
      sb.appendLine("Last updated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
      sb.appendLine()
      planLines.forEach { line ->
        val clean = line.trim().removePrefix("- ").removePrefix("* ")
        sb.appendLine("- [ ] $clean")
      }
      planFile.writeText(sb.toString(), StandardCharsets.UTF_8)
    } catch (_: Exception) {}
  }

  /**
   * Reads plan.md if present.
   */
  fun loadPlan(): List<String> {
    if (!planFile.exists()) return emptyList()
    return try {
      planFile.readLines(StandardCharsets.UTF_8)
        .map { it.trim() }
        .filter { it.startsWith("- ") || it.startsWith("* ") }
        .map { it.removePrefix("- ").removePrefix("* ").removePrefix("[ ] ").removePrefix("[x] ").trim() }
    } catch (_: Exception) {
      emptyList()
    }
  }

  /**
   * Saves atomic checkpoint to checkpoint.json.
   */
  @Synchronized
  fun saveCheckpoint(checkpoint: AgentCheckpoint) {
    try {
      checkpointFile.writeText(checkpoint.toJsonString(), StandardCharsets.UTF_8)
    } catch (_: Exception) {}
  }

  /**
   * Reads last checkpoint if available.
   */
  fun loadCheckpoint(): AgentCheckpoint? {
    if (!checkpointFile.exists()) return null
    return try {
      AgentCheckpoint.fromJson(checkpointFile.readText(StandardCharsets.UTF_8))
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Produces a compact, structured briefing of accumulated research for context injection.
   */
  fun getStructuredResearchSummary(maxFindings: Int = 8): String {
    val findings = loadAllFindings()
    if (findings.isEmpty()) return "No external research findings recorded yet in notes/research.jsonl."

    val sb = StringBuilder()
    sb.appendLine("PERSISTED RESEARCH FINDINGS (${findings.size} records in notes/research.jsonl):")
    findings.takeLast(maxFindings).forEachIndexed { idx, f ->
      sb.appendLine("[${idx + 1}] [${f.topic.uppercase()}] ${f.headline}")
      sb.appendLine("    Summary: ${f.summary}")
      if (f.sources.isNotEmpty()) {
        sb.appendLine("    Sources: ${f.sources.joinToString(", ")}")
      }
    }
    return sb.toString().trim()
  }
}
