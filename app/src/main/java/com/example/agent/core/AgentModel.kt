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
  VERIFYING,
  COMPLETED,
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
 * Structured conversation message.
 */
data class AgentMessage(
  val id: String = UUID.randomUUID().toString(),
  val role: MessageRole,
  val content: String,
  val toolCallId: String? = null,
  val toolName: String? = null,
  val toolArgs: Map<String, Any?>? = null,
  val timestamp: Long = System.currentTimeMillis()
)

/**
 * User task representation containing the goal.
 */
data class Task(
  val id: String = UUID.randomUUID().toString(),
  val goal: String,
  val status: AgentStatus = AgentStatus.IDLE,
  val createdAt: Long = System.currentTimeMillis(),
  val expectedArtifact: String? = null
) {
  // Compatibility alias for intent
  val intent: String get() = goal
}

/**
 * Produced or inspected file artifact in the workspace.
 */
data class Artifact(
  val id: String = UUID.randomUUID().toString(),
  val path: String,
  val name: String,
  val type: String,
  val size: Long,
  val createdByCallId: String? = null,
  val exists: Boolean = true,
  val lastModified: Long = System.currentTimeMillis()
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
  val verifiedArtifacts: List<String> = emptyList()
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
  val canCancel: Boolean = false
)
