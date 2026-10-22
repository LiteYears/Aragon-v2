package com.example.agent

import com.example.agent.core.AgentMessage
import com.example.agent.core.AgentStatus
import com.example.agent.core.ExecutionStep
import com.example.agent.core.FiveStageRecord
import com.example.agent.core.MessageRole
import com.example.agent.core.StepType
import com.example.agent.core.TaskRecord
import com.example.agent.docs.DocumentChunker
import com.example.agent.docs.DocumentStructure
import com.example.agent.docs.DocumentText
import com.example.agent.docs.DocumentType
import com.example.agent.docs.CellValue
import com.example.agent.docs.ExtractedSheet
import com.example.agent.core.ArtifactManager
import com.example.agent.core.Tool
import com.example.agent.core.ToolCall
import com.example.agent.core.ToolDispatcher
import com.example.agent.core.ToolErrorCode
import com.example.agent.core.ToolExecutor
import com.example.agent.core.ToolParameter
import com.example.agent.core.ToolRegistry
import com.example.agent.core.ToolResult
import com.example.agent.core.ToolSchema
import com.example.agent.core.ToolStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class AgentTaskAndOfficeTest {

  @Test
  fun testTaskRecordRichSerializationAndDeserialization() {
    val message1 = AgentMessage(
      role = MessageRole.USER,
      content = "Draft an annual sales report in Word and Excel"
    )
    val message2 = AgentMessage(
      role = MessageRole.ASSISTANT,
      content = "I will plan the outline and construct the deliverables."
    )
    val step1 = ExecutionStep(
      stepNumber = 1,
      type = StepType.PLAN,
      title = "Outline Strategy",
      content = "1. Gather data\n2. Build Word document\n3. Build Excel spreadsheet"
    )
    val step2 = ExecutionStep(
      stepNumber = 2,
      type = StepType.TOOL_CALL,
      title = "Invoke build_word_document",
      content = "Constructing reports/annual_sales.docx",
      toolName = "build_word_document",
      durationMs = 120L,
      stdout = "Successfully created Microsoft Word document"
    )
    val fiveStages = FiveStageRecord(
      intent = "Create annual sales deliverables",
      currentPlan = listOf("1. Analyze numbers", "2. Build docx", "3. Build xlsx"),
      actions = listOf("build_word_document called", "build_excel_spreadsheet called"),
      observations = listOf("File reports/annual_sales.docx created", "File financials/sales.xlsx created"),
      conclusion = "All deliverables generated and verified"
    )

    val record = TaskRecord(
      id = "test-task-123",
      sessionId = "session-456",
      goal = "Draft an annual sales report in Word and Excel",
      status = AgentStatus.COMPLETED,
      startedAt = 1000L,
      completedAt = 25000L,
      expectedArtifact = "reports/annual_sales.docx",
      artifactsProduced = listOf("reports/annual_sales.docx", "financials/sales.xlsx"),
      totalTurns = 2,
      summary = "Report successfully drafted and verified",
      deliverables = listOf("reports/annual_sales.docx", "financials/sales.xlsx"),
      conversation = listOf(message1, message2),
      planSteps = listOf("Gather data", "Build Word document", "Build Excel spreadsheet"),
      executionFeed = listOf(step1, step2),
      fiveStageRecord = fiveStages
    )

    val jsonString = record.toJsonString()
    assertTrue(jsonString.contains("test-task-123"))
    assertTrue(jsonString.contains("conversation"))
    assertTrue(jsonString.contains("executionFeed"))
    assertTrue(jsonString.contains("fiveStageRecord"))

    val parsed = TaskRecord.fromJson(jsonString)
    assertNotNull(parsed)
    assertEquals(record.id, parsed!!.id)
    assertEquals(record.goal, parsed.goal)
    assertEquals(record.status, parsed.status)
    assertEquals(2, parsed.conversation.size)
    assertEquals("Draft an annual sales report in Word and Excel", parsed.conversation[0].content)
    assertEquals(3, parsed.planSteps.size)
    assertEquals(2, parsed.executionFeed.size)
    assertEquals("build_word_document", parsed.executionFeed[1].toolName)
    assertNotNull(parsed.fiveStageRecord)
    assertEquals("Create annual sales deliverables", parsed.fiveStageRecord!!.intent)
    assertEquals(2, parsed.fiveStageRecord!!.actions.size)
  }

  @Test
  fun testRtsAndOrSearchQueries() {
    val query = "quarterly revenue 2025"
    val andQuery = DocumentText.buildFtsQuery(query)
    val orQuery = DocumentText.buildFtsQueryOr(query)

    assertNotNull(andQuery)
    assertNotNull(orQuery)
    assertTrue(andQuery!!.contains("AND"))
    assertTrue(orQuery!!.contains("OR"))
  }

  @Test
  fun testSingleRowSheetChunking() {
    val sheet = ExtractedSheet(
      name = "SingleRowSheet",
      rows = listOf(
        listOf(
          CellValue(text = "Department", kind = com.example.agent.docs.CellKind.TEXT),
          CellValue(text = "Operations", kind = com.example.agent.docs.CellKind.TEXT)
        )
      )
    )
    val structure = DocumentStructure(
      docId = "doc-test-1",
      displayName = "TestSpreadsheet.xlsx",
      type = DocumentType.XLSX,
      blocks = emptyList(),
      sheets = listOf(sheet)
    )

    val chunkingResult = DocumentChunker.chunk(structure)
    assertTrue(chunkingResult.chunks.isNotEmpty())
    val firstChunk = chunkingResult.chunks.first()
    assertEquals("SingleRowSheet", firstChunk.sheetName)
    assertTrue(firstChunk.text.contains("Operations"))
  }

  @Test
  fun testVisionModelDetectionLogic() {
    val visionCandidates = listOf(
      "meta/llama-3.2-11b-vision-instruct",
      "meta/llama-3.2-90b-vision-instruct",
      "google/paligemma-3b-pt-448",
      "qwen/qwen-vl-max",
      "gemini-2.5-flash"
    )
    for (m in visionCandidates) {
      val hasVision = com.example.agent.core.NvidiaModelRegistry.isVisionModel(m)
      assertTrue("Expected $m to be detected as vision model", hasVision)
    }

    val nonVision = "z-ai/glm-5.3"
    val isVision = com.example.agent.core.NvidiaModelRegistry.isVisionModel(nonVision)
    assertTrue(!isVision)
  }

  @Test
  fun testToolDispatcherCollisionFreeHashingAndRepetitionGuard() = runBlocking {
    val tempDir = File.createTempFile("test_ws", "").apply {
      delete()
      mkdirs()
    }
    try {
      val registry = ToolRegistry()
      val artifactMgr = ArtifactManager(tempDir)
      val executor = ToolExecutor(registry, artifactMgr)
      val dispatcher = ToolDispatcher(registry, executor)

      var executionCount = 0
      var lastArgReceived = ""

      val mockTool = object : Tool {
        override val name: String = "mock_command"
        override val description: String = "Test mock command tool"
        override val schema: ToolSchema = ToolSchema(
          listOf(
            ToolParameter("command", "string", "Command to run", required = true)
          )
        )
        override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
          executionCount++
          val cmd = arguments["command"] as String
          lastArgReceived = cmd
          return if (cmd.contains("FAIL")) {
            ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.FAILED,
              arguments = arguments,
              output = null,
              error = "Execution failed deterministically",
              errorType = ToolErrorCode.COMMAND_NOT_FOUND,
              duration = 5L,
              exitCode = 127
            )
          } else {
            ToolResult(
              callId = callId,
              toolName = name,
              status = ToolStatus.SUCCEEDED,
              arguments = arguments,
              output = "Output for $cmd [iter=$executionCount]",
              error = null,
              duration = 5L,
              exitCode = 0
            )
          }
        }
      }
      registry.register(mockTool)

      // Command 1 and Command 2 have identical first 65 characters!
      val prefix = "python3 -c \"import os, sys, json; print('Starting long execution: ')"
      val cmd1 = prefix + "; open('file_alpha.txt', 'w').write('A')\" // FAIL"
      val cmd2 = prefix + "; open('file_beta.txt', 'w').write('B')\" // SUCCESS"

      // Dispatch cmd1 -> Fails with COMMAND_NOT_FOUND
      val call1 = ToolCall(callId = "call-1", toolName = "mock_command", arguments = mapOf("command" to cmd1))
      val (res1, _) = dispatcher.dispatch(call1, taskId = "task-1")
      assertEquals(ToolStatus.FAILED, res1.status)
      assertEquals(1, executionCount)

      // Dispatch cmd2 -> Shares 65-char prefix with cmd1, but is a DIFFERENT command!
      // With canonical hashing, cmd2 must NOT be blocked by Deterministic Failure Repeat Guard!
      val call2 = ToolCall(callId = "call-2", toolName = "mock_command", arguments = mapOf("command" to cmd2))
      val (res2, _) = dispatcher.dispatch(call2, taskId = "task-1")
      assertEquals(ToolStatus.SUCCEEDED, res2.status)
      assertEquals(2, executionCount)
      assertEquals(cmd2, lastArgReceived)

      // Test Repetition Guard with varying observations vs identical observations:
      // Dispatching cmd2 multiple times where output changes:
      dispatcher.dispatch(ToolCall("call-3", "mock_command", mapOf("command" to cmd2)), "task-1")
      dispatcher.dispatch(ToolCall("call-4", "mock_command", mapOf("command" to cmd2)), "task-1")
      // Output changes each time because iter increases, so it makes progress and should not be blocked
      val (resProgress, _) = dispatcher.dispatch(ToolCall("call-5", "mock_command", mapOf("command" to cmd2)), "task-1")
      assertEquals(ToolStatus.SUCCEEDED, resProgress.status)
    } finally {
      tempDir.deleteRecursively()
    }
  }
}
