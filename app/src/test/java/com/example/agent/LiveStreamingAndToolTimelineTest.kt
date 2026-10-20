package com.example.agent

import com.example.agent.core.AgentMessage
import com.example.agent.core.ExecutionStep
import com.example.agent.core.LLMStreamListener
import com.example.agent.core.MessageRole
import com.example.agent.core.StepType
import com.example.agent.core.ToolCall
import com.example.agent.core.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class LiveStreamingAndToolTimelineTest {

  @Test
  fun testLLMStreamListener_ReceivesIncrementalTokensAndToolCalls() {
    val receivedTokens = mutableListOf<String>()
    val discoveredToolCalls = mutableListOf<ToolCall>()

    val listener = object : LLMStreamListener {
      override fun onToken(token: String) {
        receivedTokens.add(token)
      }

      override fun onToolCallDiscovered(call: ToolCall) {
        discoveredToolCalls.add(call)
      }
    }

    listener.onToken("I ")
    listener.onToken("will ")
    listener.onToken("list ")
    listener.onToken("files.")
    val call = ToolCall(callId = "call_test_1", toolName = "list_directory", arguments = mapOf("path" to "."))
    listener.onToolCallDiscovered(call)

    assertEquals("I will list files.", receivedTokens.joinToString(""))
    assertEquals(1, discoveredToolCalls.size)
    assertEquals("list_directory", discoveredToolCalls[0].toolName)
  }

  @Test
  fun testTimelineStepLifecycle_PendingToRunningToCompleted() {
    val callId = "call_exec_123"
    val stepId = "step_tool_$callId"

    // 1. Pending step (inserted when model emits tool call)
    val pendingStep = ExecutionStep(
      id = stepId,
      stepNumber = 1,
      type = StepType.TOOL_CALL,
      title = "List Directory",
      content = "{\"path\": \".\"}",
      toolName = "list_directory",
      toolCallId = callId,
      toolStatus = "PENDING",
      isCompleted = false
    )

    assertEquals("PENDING", pendingStep.toolStatus)
    assertFalse(pendingStep.isCompleted)

    // 2. Running step (transitioned when tool execution begins)
    val runningStep = pendingStep.copy(
      type = StepType.TOOL_EXECUTION,
      title = "Executing: list_directory",
      toolStatus = ToolStatus.RUNNING.name,
      isCompleted = false
    )

    assertEquals(ToolStatus.RUNNING.name, runningStep.toolStatus)
    assertFalse(runningStep.isCompleted)

    // 3. Completed step (updated with execution result and timing)
    val completedStep = runningStep.copy(
      toolStatus = ToolStatus.SUCCEEDED.name,
      stdout = "README.md\nbuild.gradle.kts\napp",
      exitCode = 0,
      durationMs = 45L,
      isCompleted = true
    )

    assertEquals(ToolStatus.SUCCEEDED.name, completedStep.toolStatus)
    assertTrue(completedStep.isCompleted)
    assertEquals(0, completedStep.exitCode)
    assertEquals("README.md\nbuild.gradle.kts\napp", completedStep.stdout)
  }

  @Test
  fun testTimelineStepLifecycle_FailureState() {
    val callId = "call_err_456"
    val stepId = "step_tool_$callId"

    val failedStep = ExecutionStep(
      id = stepId,
      stepNumber = 2,
      type = StepType.TOOL_EXECUTION,
      title = "Executing: run_command",
      toolName = "run_command",
      toolCallId = callId,
      toolStatus = ToolStatus.FAILED.name,
      stderr = "Command exited with status 127: not found",
      exitCode = 127,
      durationMs = 12L,
      isCompleted = true
    )

    assertEquals(ToolStatus.FAILED.name, failedStep.toolStatus)
    assertTrue(failedStep.isCompleted)
    assertEquals(127, failedStep.exitCode)
  }

  @Test
  fun testIncrementalMessageStreaming_PreservesSingleMessageIdentity() {
    var messages = listOf<AgentMessage>()

    val activeMessageId = "msg_${UUID.randomUUID()}"
    val initialMsg = AgentMessage(
      id = activeMessageId,
      role = MessageRole.ASSISTANT,
      content = "Starting analysis"
    )
    messages = messages + initialMsg

    // Simulate incremental token arrivals
    val streamedChunks = listOf(" of the workspace", " directory...", " Checking configuration.")
    for (chunk in streamedChunks) {
      val existing = messages.firstOrNull { it.id == activeMessageId }
      val updatedContent = (existing?.content ?: "") + chunk
      messages = messages.map {
        if (it.id == activeMessageId) it.copy(content = updatedContent) else it
      }
    }

    // Must still have exactly 1 assistant message, not 4 separate messages
    assertEquals(1, messages.size)
    assertEquals(
      "Starting analysis of the workspace directory... Checking configuration.",
      messages[0].content
    )
    assertEquals(activeMessageId, messages[0].id)
  }
}
