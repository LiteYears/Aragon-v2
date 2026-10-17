package com.example.agent.core

import java.util.UUID

/**
 * High-level lifecycle status of the Agent execution.
 */
enum class AgentStatus {
  IDLE,
  THINKING,
  PLANNING,
  EXECUTING_TOOL,
  OBSERVING,
  REPLANNING,
  SANDBOX_RESET,
  DELIVERABLE_PENDING,
  VERIFYING,
  COMPLETED,
  PARTIAL_COMPLETION,
  FAILED,
  CANCELLED
}

/**
 * Role of a message in the conversation history sent to the LLM.
 */
enum class MessageRole {
  SYSTEM,
  USER,
  ASSISTANT,
  TOOL
}

/**
 * Direct file attachment for multimodal models (images, PDFs, text files).
 * Allows files to be uploaded directly to the model without requiring OCR.
 */
data class FileAttachment(
  val name: String,
  val mimeType: String,
  val file: java.io.File? = null,
  val base64Data: String? = null
) {
  fun getBase64(): String? {
    if (!base64Data.isNullOrBlank()) return base64Data
    val f = file ?: return null
    if (!f.exists() || !f.isFile) return null
    return try {
      val bytes = f.readBytes()
      android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    } catch (_: Exception) {
      null
    }
  }
}

/**
 * Structured conversation message.
 */
data class AgentMessage(
  val id: String = UUID.randomUUID().toString(),
  val role: MessageRole,
  val content: String,
  val toolCallId: String? = null,
  val toolName: String? = null,
  val toolArgs: Map<String, Any?>? = null,
  val attachments: List<FileAttachment> = emptyList(),
  val timestamp: Long = System.currentTimeMillis()
)

/**
 * User task representation containing the goal.
 */
data class Task(
  val id: String = UUID.randomUUID().toString(),
  val sessionId: String = UUID.randomUUID().toString(),
  val goal: String,
  val status: AgentStatus = AgentStatus.IDLE,
  val createdAt: Long = System.currentTimeMillis(),
  val expectedArtifact: String? = null
) {
  // Compatibility alias for intent
  val intent: String get() = goal
}

/**
 * Persistent record of an executed agent task for the task history.
 */
data class TaskRecord(
  val id: String = UUID.randomUUID().toString(),
  val sessionId: String,
  val goal: String,
  val status: AgentStatus = AgentStatus.IDLE,
  val startedAt: Long = System.currentTimeMillis(),
  val completedAt: Long? = null,
  val expectedArtifact: String? = null,
  val artifactsProduced: List<String> = emptyList(),
  val totalTurns: Int = 0,
  val summary: String? = null,
  val error: String? = null,
  val conclusion: String? = null,
  val deliverables: List<String> = emptyList(),
  val timestamp: Long = startedAt,
  val conversation: List<AgentMessage> = emptyList(),
  val planSteps: List<String> = emptyList(),
  val executionFeed: List<ExecutionStep> = emptyList(),
  val fiveStageRecord: FiveStageRecord? = null
) {
  fun toJsonString(): String {
    val obj = org.json.JSONObject()
    obj.put("id", id)
    obj.put("sessionId", sessionId)
    obj.put("goal", goal)
    obj.put("status", status.name)
    obj.put("startedAt", startedAt)
    if (completedAt != null) obj.put("completedAt", completedAt)
    if (expectedArtifact != null) obj.put("expectedArtifact", expectedArtifact)
    val effectiveDeliverables = if (deliverables.isNotEmpty()) deliverables else artifactsProduced
    val artsArr = org.json.JSONArray()
    effectiveDeliverables.forEach { artsArr.put(it) }
    obj.put("artifactsProduced", artsArr)
    val delivArr = org.json.JSONArray()
    effectiveDeliverables.forEach { delivArr.put(it) }
    obj.put("deliverables", delivArr)
    obj.put("totalTurns", totalTurns)
    if (summary != null) obj.put("summary", summary)
    if (conclusion != null) obj.put("conclusion", conclusion)
    if (error != null) obj.put("error", error)
    obj.put("timestamp", timestamp)

    // Persist conversation
    if (conversation.isNotEmpty()) {
      val convArr = org.json.JSONArray()
      for (msg in conversation) {
        val msgObj = org.json.JSONObject()
        msgObj.put("id", msg.id)
        msgObj.put("role", msg.role.name)
        msgObj.put("content", msg.content)
        if (msg.toolCallId != null) msgObj.put("toolCallId", msg.toolCallId)
        if (msg.toolName != null) msgObj.put("toolName", msg.toolName)
        if (msg.toolArgs != null && msg.toolArgs.isNotEmpty()) {
          val argsObj = org.json.JSONObject()
          msg.toolArgs.forEach { (k, v) -> argsObj.put(k, v) }
          msgObj.put("toolArgs", argsObj)
        }
        if (msg.attachments.isNotEmpty()) {
          val attArr = org.json.JSONArray()
          for (att in msg.attachments) {
            val aObj = org.json.JSONObject()
            aObj.put("name", att.name)
            aObj.put("mimeType", att.mimeType)
            if (att.file != null) aObj.put("path", att.file.absolutePath)
            if (att.base64Data != null) aObj.put("base64Data", att.base64Data)
            attArr.put(aObj)
          }
          msgObj.put("attachments", attArr)
        }
        msgObj.put("timestamp", msg.timestamp)
        convArr.put(msgObj)
      }
      obj.put("conversation", convArr)
    }

    // Persist plan steps
    if (planSteps.isNotEmpty()) {
      val planArr = org.json.JSONArray()
      planSteps.forEach { planArr.put(it) }
      obj.put("planSteps", planArr)
    }

    // Persist execution feed
    if (executionFeed.isNotEmpty()) {
      val feedArr = org.json.JSONArray()
      for (step in executionFeed) {
        val sObj = org.json.JSONObject()
        sObj.put("id", step.id)
        sObj.put("stepNumber", step.stepNumber)
        sObj.put("type", step.type.name)
        sObj.put("title", step.title)
        sObj.put("content", step.content)
        if (step.toolName != null) sObj.put("toolName", step.toolName)
        if (step.toolCallId != null) sObj.put("toolCallId", step.toolCallId)
        if (step.toolStatus != null) sObj.put("toolStatus", step.toolStatus)
        if (step.durationMs != null) sObj.put("durationMs", step.durationMs)
        if (step.exitCode != null) sObj.put("exitCode", step.exitCode)
        if (step.stdout != null) sObj.put("stdout", step.stdout)
        if (step.stderr != null) sObj.put("stderr", step.stderr)
        sObj.put("timestamp", step.timestamp)
        sObj.put("isCompleted", step.isCompleted)
        feedArr.put(sObj)
      }
      obj.put("executionFeed", feedArr)
    }

    // Persist FiveStageRecord
    fiveStageRecord?.let { fsr ->
      val fsrObj = org.json.JSONObject()
      fsrObj.put("intent", fsr.intent)
      val planArr = org.json.JSONArray()
      fsr.currentPlan.forEach { planArr.put(it) }
      fsrObj.put("currentPlan", planArr)

      val actArr = org.json.JSONArray()
      fsr.actions.forEach { actArr.put(it) }
      fsrObj.put("actions", actArr)

      val obsArr = org.json.JSONArray()
      fsr.observations.forEach { obsArr.put(it) }
      fsrObj.put("observations", obsArr)

      if (fsr.conclusion != null) fsrObj.put("conclusion", fsr.conclusion)
      obj.put("fiveStageRecord", fsrObj)
    }

    return obj.toString()
  }

  companion object {
    fun fromJson(jsonStr: String): TaskRecord? {
      return try {
        val obj = org.json.JSONObject(jsonStr)
        val artsList = mutableListOf<String>()
        val arr = obj.optJSONArray("deliverables") ?: obj.optJSONArray("artifactsProduced")
        if (arr != null) {
          for (i in 0 until arr.length()) {
            artsList.add(arr.optString(i))
          }
        }
        val statusStr = obj.optString("status", AgentStatus.IDLE.name)
        val status = try { AgentStatus.valueOf(statusStr) } catch (_: Exception) { AgentStatus.IDLE }
        val ts = if (obj.has("timestamp")) obj.optLong("timestamp") else obj.optLong("startedAt", System.currentTimeMillis())
        val conc = if (obj.has("conclusion")) obj.optString("conclusion") else if (obj.has("summary")) obj.optString("summary") else null

        // Parse conversation
        val conversationList = mutableListOf<AgentMessage>()
        obj.optJSONArray("conversation")?.let { convArr ->
          for (i in 0 until convArr.length()) {
            val mObj = convArr.getJSONObject(i)
            val roleStr = mObj.optString("role", MessageRole.USER.name)
            val role = try { MessageRole.valueOf(roleStr) } catch (_: Exception) { MessageRole.USER }
            val parsedArgs = if (mObj.has("toolArgs")) {
              val tObj = mObj.optJSONObject("toolArgs")
              if (tObj != null) {
                val m = mutableMapOf<String, Any?>()
                val keys = tObj.keys()
                while (keys.hasNext()) {
                  val k = keys.next()
                  m[k] = tObj.get(k)
                }
                m
              } else null
            } else null

            val parsedAttachments = mutableListOf<FileAttachment>()
            mObj.optJSONArray("attachments")?.let { aArr ->
              for (j in 0 until aArr.length()) {
                val aObj = aArr.getJSONObject(j)
                val aName = aObj.optString("name", "attachment")
                val aMime = aObj.optString("mimeType", "application/octet-stream")
                val aPath = if (aObj.has("path")) aObj.optString("path") else null
                val aBase64 = if (aObj.has("base64Data")) aObj.optString("base64Data") else null
                parsedAttachments.add(
                  FileAttachment(
                    name = aName,
                    mimeType = aMime,
                    file = aPath?.let { java.io.File(it) },
                    base64Data = aBase64
                  )
                )
              }
            }

            conversationList.add(
              AgentMessage(
                id = mObj.optString("id", UUID.randomUUID().toString()),
                role = role,
                content = mObj.optString("content", ""),
                toolCallId = if (mObj.has("toolCallId")) mObj.optString("toolCallId") else null,
                toolName = if (mObj.has("toolName")) mObj.optString("toolName") else null,
                toolArgs = parsedArgs,
                attachments = parsedAttachments,
                timestamp = mObj.optLong("timestamp", System.currentTimeMillis())
              )
            )
          }
        }

        // Parse planSteps
        val planList = mutableListOf<String>()
        obj.optJSONArray("planSteps")?.let { pArr ->
          for (i in 0 until pArr.length()) {
            planList.add(pArr.optString(i))
          }
        }

        // Parse executionFeed
        val feedList = mutableListOf<ExecutionStep>()
        obj.optJSONArray("executionFeed")?.let { fArr ->
          for (i in 0 until fArr.length()) {
            val sObj = fArr.getJSONObject(i)
            val typeStr = sObj.optString("type", StepType.THINKING.name)
            val stepType = try { StepType.valueOf(typeStr) } catch (_: Exception) { StepType.THINKING }
            feedList.add(
              ExecutionStep(
                id = sObj.optString("id", UUID.randomUUID().toString()),
                stepNumber = sObj.optInt("stepNumber", i + 1),
                type = stepType,
                title = sObj.optString("title", ""),
                content = sObj.optString("content", ""),
                toolName = if (sObj.has("toolName")) sObj.optString("toolName") else null,
                toolCallId = if (sObj.has("toolCallId")) sObj.optString("toolCallId") else null,
                toolStatus = if (sObj.has("toolStatus")) sObj.optString("toolStatus") else null,
                durationMs = if (sObj.has("durationMs")) sObj.optLong("durationMs") else null,
                exitCode = if (sObj.has("exitCode")) sObj.optInt("exitCode") else null,
                stdout = if (sObj.has("stdout")) sObj.optString("stdout") else null,
                stderr = if (sObj.has("stderr")) sObj.optString("stderr") else null,
                timestamp = sObj.optLong("timestamp", System.currentTimeMillis()),
                isCompleted = sObj.optBoolean("isCompleted", true)
              )
            )
          }
        }

        // Parse FiveStageRecord
        val fiveStages = obj.optJSONObject("fiveStageRecord")?.let { fsrObj ->
          val curPlan = mutableListOf<String>()
          fsrObj.optJSONArray("currentPlan")?.let { cpArr ->
            for (i in 0 until cpArr.length()) curPlan.add(cpArr.optString(i))
          }
          val actions = mutableListOf<String>()
          fsrObj.optJSONArray("actions")?.let { aArr ->
            for (i in 0 until aArr.length()) actions.add(aArr.optString(i))
          }
          val obs = mutableListOf<String>()
          fsrObj.optJSONArray("observations")?.let { oArr ->
            for (i in 0 until oArr.length()) obs.add(oArr.optString(i))
          }
          FiveStageRecord(
            intent = fsrObj.optString("intent", ""),
            currentPlan = curPlan,
            actions = actions,
            observations = obs,
            conclusion = if (fsrObj.has("conclusion")) fsrObj.optString("conclusion") else null
          )
        }

        TaskRecord(
          id = obj.optString("id", UUID.randomUUID().toString()),
          sessionId = obj.optString("sessionId", ""),
          goal = obj.optString("goal", ""),
          status = status,
          startedAt = obj.optLong("startedAt", ts),
          completedAt = if (obj.has("completedAt")) obj.optLong("completedAt") else null,
          expectedArtifact = if (obj.has("expectedArtifact")) obj.optString("expectedArtifact") else null,
          artifactsProduced = artsList,
          totalTurns = obj.optInt("totalTurns", 0),
          summary = if (obj.has("summary")) obj.optString("summary") else conc,
          error = if (obj.has("error")) obj.optString("error") else null,
          conclusion = conc,
          deliverables = artsList,
          timestamp = ts,
          conversation = conversationList,
          planSteps = planList,
          executionFeed = feedList,
          fiveStageRecord = fiveStages
        )
      } catch (_: Exception) {
        null
      }
    }
  }
}

/**
 * Produced or inspected file artifact in the workspace.
 * Preserves provenance, MIME type, size, checksum, and originating environment.
 */
data class Artifact(
  val id: String = UUID.randomUUID().toString(),
  val path: String,
  val name: String,
  val type: String,
  val size: Long,
  val createdByCallId: String? = null,
  val taskId: String? = null,
  val exists: Boolean = true,
  val isReadable: Boolean = true,
  val lastModified: Long = System.currentTimeMillis(),
  val absolutePath: String = "",
  val originatingEnvironment: String = "e2b_cloud",
  val mimeType: String = "",
  val checksum: String? = null
) {
  // Compatibility alias
  val createdByToolCall: String? get() = createdByCallId
}

/**
 * Result of objective verification by the engine.
 */
data class VerificationResult(
  val isSatisfied: Boolean,
  val details: String,
  val verifiedArtifacts: List<String> = emptyList(),
  val missingRequirements: List<String> = emptyList(),
  val isPartial: Boolean = false
)

/**
 * Step types displayed in the chronological execution feed.
 */
enum class StepType {
  TASK_INTENT,
  PLAN,
  THINKING,
  TOOL_CALL,
  TOOL_EXECUTION,
  OBSERVATION,
  REPLANNING,
  SANDBOX_RESET,
  VERIFICATION,
  CONCLUSION,
  ERROR
}

/**
 * A single item in the chronological execution feed.
 */
data class ExecutionStep(
  val id: String = UUID.randomUUID().toString(),
  val stepNumber: Int,
  val type: StepType,
  val title: String,
  val content: String,
  val toolName: String? = null,
  val toolCallId: String? = null,
  val toolStatus: String? = null,
  val durationMs: Long? = null,
  val exitCode: Int? = null,
  val stdout: String? = null,
  val stderr: String? = null,
  val timestamp: Long = System.currentTimeMillis(),
  val isCompleted: Boolean = true
)

/**
 * Explicit separation of the 5 core agentic concepts:
 * 1. INTENT (What user wants)
 * 2. PLAN (What agent intends to do)
 * 3. ACTION (What agent actually executed)
 * 4. OBSERVATION (What environment actually returned)
 * 5. CONCLUSION (What agent believes based on the evidence)
 */
data class FiveStageRecord(
  val intent: String = "",
  val currentPlan: List<String> = emptyList(),
  val actions: List<String> = emptyList(),
  val observations: List<String> = emptyList(),
  val conclusion: String? = null
)

/**
 * The single authoritative live state of the Agent Engine.
 */
data class AgentState(
  val sessionId: String = UUID.randomUUID().toString(),
  val task: Task? = null,
  val status: AgentStatus = AgentStatus.IDLE,
  val currentAction: String? = null,
  val plan: List<String> = emptyList(),
  val messages: List<AgentMessage> = emptyList(),
  val toolExecutions: List<ToolResult> = emptyList(),
  val observations: List<Observation> = emptyList(),
  val artifacts: List<Artifact> = emptyList(),
  val verification: VerificationResult? = null,
  val error: String? = null,
  val currentStep: Int = 0,
  val executionFeed: List<ExecutionStep> = emptyList(),
  val fiveStageRecord: FiveStageRecord = FiveStageRecord(),
  val completionSummary: String? = null,
  val canCancel: Boolean = false,
  val taskHistory: List<TaskRecord> = emptyList(),
  val trackedDeliverables: List<TrackedDeliverable> = emptyList(),
  val deliverableReport: DeliverableReport? = null,
  val isMultitasking: Boolean = false,
  val activeConcurrentTasks: Int = 0
)

/**
 * Entry item in the Workspace File Explorer (supports files and directories).
 */
data class FileExplorerItem(
  val name: String,
  val relativePath: String,
  val isDirectory: Boolean,
  val size: Long = 0L,
  val lastModified: Long = 0L,
  val childCount: Int = 0,
  val extension: String = "",
  val isDeliverable: Boolean = false,
  val deliverableStatus: DeliverableStatus? = null,
  val absolutePath: String = "",
  val lineCount: Int = 0,
  val qualityScore: Int = 0,
  val sha256: String? = null
)


