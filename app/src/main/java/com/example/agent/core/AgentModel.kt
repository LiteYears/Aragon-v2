package com.example.agent.core

import java.util.UUID

/**
 * High-level lifecycle status of the Agent execution.
 */
enum class AgentStatus {
  IDLE,
  RUNNING,
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
 * High-level phase of multi-step task execution.
 */
enum class TaskPhase {
  DISCOVERY,    // Initial orientation, inspecting inputs, environment, and capabilities
  PLANNING,     // Structuring plan, defining actionable steps & dependencies
  EXECUTION,    // Actively dispatching and executing tool actions
  RECOVERY,     // Handling transient errors, parameter adjustments, or tool switches
  REPLANNING,   // Dynamically revising the plan when observations invalidate prior assumptions
  VERIFICATION, // Authoritative verification of deliverables and postconditions against disk
  COMPLETED,    // Task successfully satisfied and authoritatively verified
  FAILED,       // Terminated with unrecoverable failure
  CANCELLED     // Terminated by user or system cancellation
}

/**
 * Execution status for an individual planned action or step.
 * Distinguishes planned actions from dispatched, actively executing, completed, or failed ones.
 */
enum class ActionExecutionStatus {
  PLANNED,      // Step is in the plan, awaiting prerequisite dependencies or dispatch
  DISPATCHED,   // Sent to executor/dispatcher
  EXECUTING,    // Actively running in sandbox or host
  COMPLETED,    // Successfully finished and verified
  FAILED,       // Execution failed
  SKIPPED,      // Skipped because prerequisite failed or no longer needed
  SUPERSEDED    // Invalidated or replaced during dynamic plan revision
}

/**
 * Verifiable evidence item establishing task or step completion against reality.
 */
data class CompletionEvidenceItem(
  val id: String = "ev_" + UUID.randomUUID().toString().take(8),
  val stepId: String? = null,
  val type: String, // "FILE_EXISTS", "CONTENT_VERIFIED", "EXIT_CODE_ZERO", "TOOL_OUTPUT", "ASSERTION"
  val description: String,
  val target: String? = null,
  val targetPath: String? = target,
  val details: String? = null,
  val verified: Boolean = false,
  val timestamp: Long = System.currentTimeMillis()
) {
  fun toJsonObject(): org.json.JSONObject {
    val obj = org.json.JSONObject()
    obj.put("id", id)
    if (stepId != null) obj.put("stepId", stepId)
    obj.put("type", type)
    obj.put("description", description)
    val effTarget = target ?: targetPath
    if (effTarget != null) {
      obj.put("target", effTarget)
      obj.put("targetPath", effTarget)
    }
    if (details != null) obj.put("details", details)
    obj.put("verified", verified)
    obj.put("timestamp", timestamp)
    return obj
  }

  companion object {
    fun fromJsonObject(obj: org.json.JSONObject): CompletionEvidenceItem {
      val t = if (obj.has("target")) obj.optString("target") else if (obj.has("targetPath")) obj.optString("targetPath") else null
      val d = if (obj.has("details")) obj.optString("details") else null
      return CompletionEvidenceItem(
        id = obj.optString("id", "ev_" + UUID.randomUUID().toString().take(8)),
        stepId = if (obj.has("stepId")) obj.optString("stepId") else null,
        type = obj.optString("type", "ASSERTION"),
        description = obj.optString("description", ""),
        target = t,
        targetPath = t,
        details = d,
        verified = obj.optBoolean("verified", false),
        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
      )
    }
  }
}

/**
 * First-class actionable step in an agent execution plan.
 * Tracks explicit lifecycle transitions, dependencies, assigned tool, and generated artifacts.
 */
data class ActionableStep(
  val id: String = "step_" + UUID.randomUUID().toString().take(8),
  val stepNumber: Int,
  val description: String,
  val phase: TaskPhase = TaskPhase.EXECUTION,
  val status: ActionExecutionStatus = ActionExecutionStatus.PLANNED,
  val dependencies: List<String> = emptyList(), // IDs of prerequisite steps that must be COMPLETED
  val assignedTool: String? = null,
  val toolCallId: String? = null,
  val attempts: Int = 0,
  val observations: List<String> = emptyList(),
  val artifacts: List<String> = emptyList(),
  val completionEvidence: List<String> = emptyList(),
  val lastError: String? = null,
  val isConcurrent: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
  val completedAt: Long? = null
) {
  val isReady: Boolean get() = status == ActionExecutionStatus.PLANNED
  val isTerminal: Boolean get() = status in setOf(
    ActionExecutionStatus.COMPLETED,
    ActionExecutionStatus.FAILED,
    ActionExecutionStatus.SKIPPED,
    ActionExecutionStatus.SUPERSEDED
  )

  fun toJsonObject(): org.json.JSONObject {
    val obj = org.json.JSONObject()
    obj.put("id", id)
    obj.put("stepNumber", stepNumber)
    obj.put("description", description)
    obj.put("phase", phase.name)
    obj.put("status", status.name)
    val depArr = org.json.JSONArray()
    dependencies.forEach { depArr.put(it) }
    obj.put("dependencies", depArr)
    if (assignedTool != null) obj.put("assignedTool", assignedTool)
    if (toolCallId != null) obj.put("toolCallId", toolCallId)
    obj.put("attempts", attempts)
    val obsArr = org.json.JSONArray()
    observations.forEach { obsArr.put(it) }
    obj.put("observations", obsArr)
    val artArr = org.json.JSONArray()
    artifacts.forEach { artArr.put(it) }
    obj.put("artifacts", artArr)
    val evArr = org.json.JSONArray()
    completionEvidence.forEach { evArr.put(it) }
    obj.put("completionEvidence", evArr)
    if (lastError != null) obj.put("lastError", lastError)
    obj.put("isConcurrent", isConcurrent)
    obj.put("createdAt", createdAt)
    if (completedAt != null) obj.put("completedAt", completedAt)
    return obj
  }

  companion object {
    fun fromJsonObject(obj: org.json.JSONObject): ActionableStep {
      fun parseStringList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) list.add(arr.optString(i))
        return list
      }
      val phaseStr = obj.optString("phase", TaskPhase.EXECUTION.name)
      val phase = try { TaskPhase.valueOf(phaseStr) } catch (_: Exception) { TaskPhase.EXECUTION }
      val statusStr = obj.optString("status", ActionExecutionStatus.PLANNED.name)
      val status = try { ActionExecutionStatus.valueOf(statusStr) } catch (_: Exception) { ActionExecutionStatus.PLANNED }

      return ActionableStep(
        id = obj.optString("id", "step_" + UUID.randomUUID().toString().take(8)),
        stepNumber = obj.optInt("stepNumber", 1),
        description = obj.optString("description", ""),
        phase = phase,
        status = status,
        dependencies = parseStringList(obj.optJSONArray("dependencies")),
        assignedTool = if (obj.has("assignedTool")) obj.optString("assignedTool") else null,
        toolCallId = if (obj.has("toolCallId")) obj.optString("toolCallId") else null,
        attempts = obj.optInt("attempts", 0),
        observations = parseStringList(obj.optJSONArray("observations")),
        artifacts = parseStringList(obj.optJSONArray("artifacts")),
        completionEvidence = parseStringList(obj.optJSONArray("completionEvidence")),
        lastError = if (obj.has("lastError")) obj.optString("lastError") else null,
        isConcurrent = obj.optBoolean("isConcurrent", false),
        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
        completedAt = if (obj.has("completedAt")) obj.optLong("completedAt") else null
      )
    }
  }
}

/**
 * Consolidated, persistent task representation containing requested outcome,
 * current phase, actionable steps, dependencies, execution status, observations,
 * generated artifacts, and completion evidence.
 */
data class PersistentTask(
  val id: String = UUID.randomUUID().toString(),
  val sessionId: String = UUID.randomUUID().toString(),
  val requestedOutcome: String,
  val currentPhase: TaskPhase = TaskPhase.DISCOVERY,
  val actionableSteps: List<ActionableStep> = emptyList(),
  val executionStatus: AgentStatus = AgentStatus.IDLE,
  val observations: List<String> = emptyList(),
  val generatedArtifacts: List<String> = emptyList(),
  val completionEvidence: List<CompletionEvidenceItem> = emptyList(),
  val revision: Int = 0,
  val currentTurn: Int = 0,
  val maxTurns: Int = 50,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
  val completedAt: Long? = null,
  val expectedArtifact: String? = null,
  val error: String? = null,
  val conclusion: String? = null,
  val artifacts: List<Artifact> = emptyList()
) {
  fun toJsonObject(): org.json.JSONObject {
    val obj = org.json.JSONObject()
    obj.put("id", id)
    obj.put("sessionId", sessionId)
    obj.put("requestedOutcome", requestedOutcome)
    obj.put("currentPhase", currentPhase.name)
    obj.put("executionStatus", executionStatus.name)
    obj.put("revision", revision)
    obj.put("currentTurn", currentTurn)
    obj.put("maxTurns", maxTurns)
    obj.put("createdAt", createdAt)
    obj.put("updatedAt", updatedAt)
    if (completedAt != null) obj.put("completedAt", completedAt)
    if (expectedArtifact != null) obj.put("expectedArtifact", expectedArtifact)
    if (error != null) obj.put("error", error)
    if (conclusion != null) obj.put("conclusion", conclusion)

    val stepsArr = org.json.JSONArray()
    actionableSteps.forEach { stepsArr.put(it.toJsonObject()) }
    obj.put("actionableSteps", stepsArr)

    val obsArr = org.json.JSONArray()
    observations.forEach { obsArr.put(it) }
    obj.put("observations", obsArr)

    val artArr = org.json.JSONArray()
    generatedArtifacts.forEach { artArr.put(it) }
    obj.put("generatedArtifacts", artArr)

    val evArr = org.json.JSONArray()
    completionEvidence.forEach { evArr.put(it.toJsonObject()) }
    obj.put("completionEvidence", evArr)

    return obj
  }

  fun toJsonString(indent: Int = 2): String = toJsonObject().toString(indent)

  fun toTask(): Task = Task(
    id = id,
    sessionId = sessionId,
    goal = requestedOutcome,
    status = executionStatus,
    createdAt = createdAt,
    expectedArtifact = expectedArtifact,
    currentPhase = currentPhase,
    actionableSteps = actionableSteps,
    observations = observations,
    generatedArtifacts = generatedArtifacts,
    completionEvidence = completionEvidence,
    revision = revision,
    updatedAt = updatedAt,
    completedAt = completedAt
  )

  companion object {
    fun fromJsonObject(obj: org.json.JSONObject): PersistentTask {
      val phaseStr = obj.optString("currentPhase", TaskPhase.DISCOVERY.name)
      val phase = try { TaskPhase.valueOf(phaseStr) } catch (_: Exception) { TaskPhase.DISCOVERY }
      val statusStr = obj.optString("executionStatus", AgentStatus.IDLE.name)
      val status = try { AgentStatus.valueOf(statusStr) } catch (_: Exception) { AgentStatus.IDLE }

      val stepsList = mutableListOf<ActionableStep>()
      obj.optJSONArray("actionableSteps")?.let { arr ->
        for (i in 0 until arr.length()) {
          arr.optJSONObject(i)?.let { stepsList.add(ActionableStep.fromJsonObject(it)) }
        }
      }

      val obsList = mutableListOf<String>()
      obj.optJSONArray("observations")?.let { arr ->
        for (i in 0 until arr.length()) obsList.add(arr.optString(i))
      }

      val artList = mutableListOf<String>()
      obj.optJSONArray("generatedArtifacts")?.let { arr ->
        for (i in 0 until arr.length()) artList.add(arr.optString(i))
      }

      val evList = mutableListOf<CompletionEvidenceItem>()
      obj.optJSONArray("completionEvidence")?.let { arr ->
        for (i in 0 until arr.length()) {
          arr.optJSONObject(i)?.let { evList.add(CompletionEvidenceItem.fromJsonObject(it)) }
        }
      }

      return PersistentTask(
        id = obj.optString("id", UUID.randomUUID().toString()),
        sessionId = obj.optString("sessionId", UUID.randomUUID().toString()),
        requestedOutcome = obj.optString("requestedOutcome", ""),
        currentPhase = phase,
        actionableSteps = stepsList,
        executionStatus = status,
        observations = obsList,
        generatedArtifacts = artList,
        completionEvidence = evList,
        revision = obj.optInt("revision", 0),
        currentTurn = obj.optInt("currentTurn", 0),
        maxTurns = obj.optInt("maxTurns", 50),
        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
        completedAt = if (obj.has("completedAt")) obj.optLong("completedAt") else null,
        expectedArtifact = if (obj.has("expectedArtifact")) obj.optString("expectedArtifact") else null,
        error = if (obj.has("error")) obj.optString("error") else null,
        conclusion = if (obj.has("conclusion")) obj.optString("conclusion") else null
      )
    }

    fun fromJson(jsonStr: String): PersistentTask? {
      return try {
        fromJsonObject(org.json.JSONObject(jsonStr))
      } catch (_: Exception) {
        null
      }
    }
  }
}

/**
 * User task representation containing the goal and synchronized execution state.
 */
data class Task(
  val id: String = UUID.randomUUID().toString(),
  val sessionId: String = UUID.randomUUID().toString(),
  val goal: String,
  val status: AgentStatus = AgentStatus.IDLE,
  val createdAt: Long = System.currentTimeMillis(),
  val expectedArtifact: String? = null,
  val currentPhase: TaskPhase = TaskPhase.DISCOVERY,
  val actionableSteps: List<ActionableStep> = emptyList(),
  val observations: List<String> = emptyList(),
  val generatedArtifacts: List<String> = emptyList(),
  val completionEvidence: List<CompletionEvidenceItem> = emptyList(),
  val revision: Int = 0,
  val updatedAt: Long = createdAt,
  val completedAt: Long? = null
) {
  // Compatibility alias for intent
  val intent: String get() = goal

  fun toPersistentTask(turn: Int = 0, maxTurns: Int = 50, error: String? = null, conclusion: String? = null): PersistentTask {
    return PersistentTask(
      id = id,
      sessionId = sessionId,
      requestedOutcome = goal,
      currentPhase = currentPhase,
      actionableSteps = actionableSteps,
      executionStatus = status,
      observations = observations,
      generatedArtifacts = generatedArtifacts,
      completionEvidence = completionEvidence,
      revision = revision,
      currentTurn = turn,
      maxTurns = maxTurns,
      createdAt = createdAt,
      updatedAt = updatedAt,
      completedAt = completedAt,
      expectedArtifact = expectedArtifact,
      error = error,
      conclusion = conclusion
    )
  }
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
  val fiveStageRecord: FiveStageRecord? = null,
  val currentPhase: TaskPhase = TaskPhase.COMPLETED,
  val actionableSteps: List<ActionableStep> = emptyList(),
  val completionEvidence: List<CompletionEvidenceItem> = emptyList(),
  val revision: Int = 0,
  val persistentTask: PersistentTask? = null,
  val artifacts: List<Artifact> = emptyList()
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
    val richArtsArr = org.json.JSONArray()
    artifacts.forEach { richArtsArr.put(it.toJsonObject()) }
    obj.put("artifacts", richArtsArr)
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

    // Persist rich task state
    obj.put("currentPhase", currentPhase.name)
    obj.put("revision", revision)
    if (actionableSteps.isNotEmpty()) {
      val stepsArr = org.json.JSONArray()
      actionableSteps.forEach { stepsArr.put(it.toJsonObject()) }
      obj.put("actionableSteps", stepsArr)
    }
    if (completionEvidence.isNotEmpty()) {
      val evArr = org.json.JSONArray()
      completionEvidence.forEach { evArr.put(it.toJsonObject()) }
      obj.put("completionEvidence", evArr)
    }
    persistentTask?.let {
      obj.put("persistentTask", it.toJsonObject())
    }

    return obj.toString()
  }

  fun toPersistentTask(): PersistentTask {
    if (persistentTask != null) return persistentTask
    return PersistentTask(
      id = id,
      sessionId = sessionId,
      requestedOutcome = goal,
      currentPhase = currentPhase,
      actionableSteps = actionableSteps,
      executionStatus = status,
      observations = emptyList(),
      generatedArtifacts = deliverables.ifEmpty { artifactsProduced },
      completionEvidence = completionEvidence,
      revision = revision,
      currentTurn = totalTurns,
      maxTurns = 50,
      createdAt = startedAt,
      updatedAt = completedAt ?: startedAt,
      completedAt = completedAt,
      expectedArtifact = expectedArtifact,
      error = error,
      conclusion = conclusion ?: summary
    )
  }

  companion object {
    fun fromTask(task: Task, artifacts: List<String> = emptyList(), totalTurns: Int = 0): TaskRecord {
      val pTask = task.toPersistentTask(turn = totalTurns)
      return fromPersistentTask(pTask, artifacts)
    }

    fun fromPersistentTask(pTask: PersistentTask, artifacts: List<String> = emptyList()): TaskRecord {
      return TaskRecord(
        id = pTask.id,
        sessionId = pTask.sessionId,
        goal = pTask.requestedOutcome,
        status = pTask.executionStatus,
        startedAt = pTask.createdAt,
        completedAt = pTask.completedAt,
        expectedArtifact = pTask.expectedArtifact,
        artifactsProduced = artifacts.ifEmpty { pTask.generatedArtifacts },
        totalTurns = pTask.currentTurn,
        summary = pTask.conclusion,
        error = pTask.error,
        conclusion = pTask.conclusion,
        deliverables = artifacts.ifEmpty { pTask.generatedArtifacts },
        timestamp = pTask.updatedAt,
        conversation = emptyList(),
        planSteps = pTask.actionableSteps.map { it.description },
        executionFeed = emptyList(),
        fiveStageRecord = null,
        currentPhase = pTask.currentPhase,
        actionableSteps = pTask.actionableSteps,
        completionEvidence = pTask.completionEvidence,
        revision = pTask.revision,
        persistentTask = pTask
      )
    }

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

        val phaseStr = obj.optString("currentPhase", TaskPhase.COMPLETED.name)
        val phase = try { TaskPhase.valueOf(phaseStr) } catch (_: Exception) { TaskPhase.COMPLETED }
        val revision = obj.optInt("revision", 0)

        val parsedActionableSteps = mutableListOf<ActionableStep>()
        obj.optJSONArray("actionableSteps")?.let { arr ->
          for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { parsedActionableSteps.add(ActionableStep.fromJsonObject(it)) }
          }
        }

        val parsedEvidence = mutableListOf<CompletionEvidenceItem>()
        obj.optJSONArray("completionEvidence")?.let { arr ->
          for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { parsedEvidence.add(CompletionEvidenceItem.fromJsonObject(it)) }
          }
        }

        val richArtsList = mutableListOf<Artifact>()
        obj.optJSONArray("artifacts")?.let { arr ->
          for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { richArtsList.add(Artifact.fromJson(it)) }
          }
        }

        val pTask = obj.optJSONObject("persistentTask")?.let {
          try { PersistentTask.fromJsonObject(it) } catch (_: Exception) { null }
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
          fiveStageRecord = fiveStages,
          currentPhase = phase,
          actionableSteps = parsedActionableSteps,
          completionEvidence = parsedEvidence,
          revision = revision,
          persistentTask = pTask,
          artifacts = richArtsList
        )
      } catch (_: Exception) {
        null
      }
    }
  }
}

/**
 * Authoritative creation and validation status of an artifact.
 */
enum class ArtifactCreationStatus {
  PENDING,      // Declared or expected deliverable, file not yet written or discovered
  IN_PROGRESS,  // File currently being written or exists with 0 bytes
  VERIFIED,     // Exists on disk, non-zero bytes, readable, and passed structural/format validation
  CORRUPTED,    // Exists on disk but failed format/structural validation
  EMPTY,        // Exists on disk but contains 0 bytes
  MISSING,      // Claimed to exist or previously tracked, but not found on disk
  DELETED       // Deliberately deleted or pruned from workspace
}

/**
 * Functional category separating user deliverables from internal logs and temporary files.
 */
enum class ArtifactCategory {
  USER_FACING_DELIVERABLE,  // User-requested deliverables (reports, documents, spreadsheets, data exports)
  INTERNAL_OBSERVATION,     // Spillover logs, observation captures in .observations/
  TEMPORARY_SCRATCH         // Temporary scripts, .tmp_, .part, scratch files
}

/**
 * Detailed cryptographic and structural integrity metadata for an artifact.
 */
data class ArtifactIntegrityInfo(
  val sha256: String? = null,
  val byteCount: Long = 0L,
  val isFormatValid: Boolean = true,
  val formatDescription: String? = null,
  val validationError: String? = null,
  val verifiedAt: Long = System.currentTimeMillis()
) {
  fun toJsonObject(): org.json.JSONObject {
    val obj = org.json.JSONObject()
    if (sha256 != null) obj.put("sha256", sha256)
    obj.put("byteCount", byteCount)
    obj.put("isFormatValid", isFormatValid)
    if (formatDescription != null) obj.put("formatDescription", formatDescription)
    if (validationError != null) obj.put("validationError", validationError)
    obj.put("verifiedAt", verifiedAt)
    return obj
  }

  companion object {
    fun fromJson(obj: org.json.JSONObject?): ArtifactIntegrityInfo {
      if (obj == null) return ArtifactIntegrityInfo()
      return ArtifactIntegrityInfo(
        sha256 = if (obj.has("sha256")) obj.optString("sha256") else null,
        byteCount = obj.optLong("byteCount", 0L),
        isFormatValid = obj.optBoolean("isFormatValid", true),
        formatDescription = if (obj.has("formatDescription")) obj.optString("formatDescription") else null,
        validationError = if (obj.has("validationError")) obj.optString("validationError") else null,
        verifiedAt = obj.optLong("verifiedAt", System.currentTimeMillis())
      )
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
  val originatingEnvironment: String = "E2B_CLOUD",
  val mimeType: String = "",
  val checksum: String? = null,
  val creationStatus: ArtifactCreationStatus = ArtifactCreationStatus.VERIFIED,
  val integrity: ArtifactIntegrityInfo = ArtifactIntegrityInfo(sha256 = checksum, byteCount = size, isFormatValid = true),
  val category: ArtifactCategory = ArtifactCategory.USER_FACING_DELIVERABLE,
  val version: Int = 1
) {
  // Compatibility and semantic aliases
  val createdByToolCall: String? get() = createdByCallId
  val filename: String get() = name
  val canonicalPath: String get() = absolutePath.ifBlank { path }
  val executionEnvironment: String get() = originatingEnvironment
  val sizeBytes: Long get() = size
  val sha256: String? get() = integrity.sha256 ?: checksum
  val isUserFacing: Boolean get() = category == ArtifactCategory.USER_FACING_DELIVERABLE
  val isVerified: Boolean get() = creationStatus == ArtifactCreationStatus.VERIFIED && exists && isReadable && size > 0L
  val e2bPath: String get() = if (path.startsWith("/")) path else "/home/user/$path"
  val validationDetails: String? get() = integrity.validationError ?: integrity.formatDescription

  fun toJsonObject(): org.json.JSONObject {
    val obj = org.json.JSONObject()
    obj.put("id", id)
    obj.put("path", path)
    obj.put("name", name)
    obj.put("type", type)
    obj.put("size", size)
    if (createdByCallId != null) obj.put("createdByCallId", createdByCallId)
    if (taskId != null) obj.put("taskId", taskId)
    obj.put("exists", exists)
    obj.put("isReadable", isReadable)
    obj.put("lastModified", lastModified)
    obj.put("absolutePath", absolutePath)
    obj.put("originatingEnvironment", originatingEnvironment)
    obj.put("mimeType", mimeType)
    if (checksum != null) obj.put("checksum", checksum)
    obj.put("creationStatus", creationStatus.name)
    obj.put("integrity", integrity.toJsonObject())
    obj.put("category", category.name)
    obj.put("version", version)
    return obj
  }

  fun toJsonString(): String = toJsonObject().toString()

  companion object {
    fun fromJson(obj: org.json.JSONObject): Artifact {
      val chk = if (obj.has("checksum")) obj.optString("checksum") else null
      val sz = obj.optLong("size", 0L)
      val integObj = obj.optJSONObject("integrity")
      val integ = if (integObj != null) ArtifactIntegrityInfo.fromJson(integObj) else ArtifactIntegrityInfo(sha256 = chk, byteCount = sz)
      val statusStr = obj.optString("creationStatus", "VERIFIED")
      val status = try { ArtifactCreationStatus.valueOf(statusStr) } catch (_: Exception) { ArtifactCreationStatus.VERIFIED }
      val catStr = obj.optString("category", "USER_FACING_DELIVERABLE")
      val cat = try { ArtifactCategory.valueOf(catStr) } catch (_: Exception) { ArtifactCategory.USER_FACING_DELIVERABLE }

      return Artifact(
        id = obj.optString("id", UUID.randomUUID().toString()),
        path = obj.optString("path", ""),
        name = obj.optString("name", ""),
        type = obj.optString("type", "Deliverable File"),
        size = sz,
        createdByCallId = if (obj.has("createdByCallId")) obj.optString("createdByCallId") else null,
        taskId = if (obj.has("taskId")) obj.optString("taskId") else null,
        exists = obj.optBoolean("exists", true),
        isReadable = obj.optBoolean("isReadable", true),
        lastModified = obj.optLong("lastModified", System.currentTimeMillis()),
        absolutePath = obj.optString("absolutePath", ""),
        originatingEnvironment = obj.optString("originatingEnvironment", "E2B_CLOUD"),
        mimeType = obj.optString("mimeType", ""),
        checksum = chk,
        creationStatus = status,
        integrity = integ,
        category = cat,
        version = obj.optInt("version", 1)
      )
    }

    fun fromJsonString(jsonStr: String): Artifact? {
      return try {
        fromJson(org.json.JSONObject(jsonStr))
      } catch (_: Exception) {
        null
      }
    }
  }
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
  EVALUATION,
  RECOVERY,
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
  val isCompleted: Boolean = true,
  val outcome: String? = null,
  val recoveryStrategy: String? = null,
  val failureCategory: String? = null,
  val actionStatus: ActionExecutionStatus? = null,
  val actionableStepId: String? = null,
  val waveIndex: Int? = null
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
  val activeConcurrentTasks: Int = 0,
  val persistentTask: PersistentTask? = null
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


