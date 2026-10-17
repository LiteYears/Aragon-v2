package com.example.agent

import com.example.agent.core.AgentMessage
import com.example.agent.core.AgentStatus
import com.example.agent.core.DocxBuilder
import com.example.agent.core.ExecutionStep
import com.example.agent.core.FiveStageRecord
import com.example.agent.core.MessageRole
import com.example.agent.core.StepType
import com.example.agent.core.TaskRecord
import com.example.agent.core.XlsxBuilder
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
  fun testDocxBuilderGenerationAndIntegrity() {
    val tempFile = File.createTempFile("test_document", ".docx")
    try {
      val builder = DocxBuilder()
      builder.setCoreProperties("Executive Summary", "Aragon Test")
      builder.addHeading("Market Overview", level = 1)
      builder.addParagraph("This is a verified test paragraph detailing the quarterly surge.")
      builder.addList(listOf("Point A", "Point B", "Point C"), ordered = false)
      builder.addTable(
        headers = listOf("Metric", "2024", "2025"),
        rows = listOf(
          listOf("Revenue", "$1.2M", "$2.5M"),
          listOf("Users", "50,000", "120,000")
        )
      )
      builder.save(tempFile)

      assertTrue(tempFile.exists())
      assertTrue(tempFile.length() > 500)

      // Verify it is a valid Zip archive containing OpenXML parts
      ZipFile(tempFile).use { zip ->
        assertNotNull(zip.getEntry("[Content_Types].xml"))
        assertNotNull(zip.getEntry("_rels/.rels"))
        assertNotNull(zip.getEntry("word/document.xml"))
        assertNotNull(zip.getEntry("word/styles.xml"))
        val docXml = zip.getInputStream(zip.getEntry("word/document.xml")!!).bufferedReader().readText()
        assertTrue(docXml.contains("Market Overview"))
        assertTrue(docXml.contains("Executive Summary") || docXml.contains("quarterly surge"))
        assertTrue(docXml.contains("Revenue"))
      }
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun testXlsxBuilderGenerationAndIntegrity() {
    val tempFile = File.createTempFile("test_spreadsheet", ".xlsx")
    try {
      val builder = XlsxBuilder("Q1 Performance")
      builder.freezeTopRow()
      builder.addHeader("Division", "Budget", "Actual", "Variance")
      builder.addRow("Engineering", "500000", "480000", "-20000")
      builder.addRow("Marketing", "200000", "215000", "15000")
      builder.addSheet("Headcount")
      builder.addHeader("Department", "FTEs")
      builder.addRow("Engineering", "35")
      builder.addRow("Marketing", "12")
      builder.save(tempFile)

      assertTrue(tempFile.exists())
      assertTrue(tempFile.length() > 500)

      ZipFile(tempFile).use { zip ->
        assertNotNull(zip.getEntry("[Content_Types].xml"))
        assertNotNull(zip.getEntry("_rels/.rels"))
        assertNotNull(zip.getEntry("xl/workbook.xml"))
        assertNotNull(zip.getEntry("xl/worksheets/sheet1.xml"))
        assertNotNull(zip.getEntry("xl/worksheets/sheet2.xml"))
        val sheet1Xml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")!!).bufferedReader().readText()
        assertTrue(sheet1Xml.contains("Engineering"))
        assertTrue(sheet1Xml.contains("500000"))
      }
    } finally {
      tempFile.delete()
    }
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
  fun testDocxBuilderAdvancedFeatureParity() {
    val tempFile = File.createTempFile("test_adv_doc", ".docx")
    try {
      val builder = DocxBuilder()
      builder.setCoreProperties("Q3 Strategy", "Aragon Test", "Strategic Operations", "Quarterly Roadmap")
      builder.setPageSettings(
        orientation = DocxBuilder.PageOrientation.PORTRAIT,
        margins = DocxBuilder.MarginPreset.NORMAL,
        header = "Executive Strategy 2026",
        footer = "Confidential",
        pageNumbers = true
      )
      builder.addTitle("Q3 Strategic Operations", subtitle = "Mission Critical Initiatives")
      builder.addHeading("Key Performance Indicators", level = 1)
      builder.addCallout("Maintain 99.99% microVM execution uptime across all clusters.", title = "SYSTEM TARGET", type = DocxBuilder.CalloutType.TIP)
      builder.addBlockquote("Simplicity is prerequisite for reliability.", citation = "Edsger W. Dijkstra")
      builder.addCodeBlock("fun calculateRun(): Long = System.currentTimeMillis()", language = "kotlin")
      builder.addHyperlink("Aragon Documentation", "https://github.com/LiteYears/Aragon-v2")
      builder.addHorizontalRule()
      builder.addPageBreak()

      val markdown = """
        ## Markdown Synthesis
        This section is parsed directly from Markdown.
        - High priority track
        - Deterministic tool calls
        - Comprehensive verification

        | Cluster | Node Count | Status |
        | --- | --- | --- |
        | Primary | 16 | Active |
        | Backup | 4 | Standby |
      """.trimIndent()
      builder.addMarkdown(markdown)
      builder.save(tempFile)

      assertTrue(tempFile.exists())
      assertTrue(tempFile.length() > 1000)

      ZipFile(tempFile).use { zip ->
        assertNotNull(zip.getEntry("word/header1.xml"))
        assertNotNull(zip.getEntry("word/footer1.xml"))
        val docXml = zip.getInputStream(zip.getEntry("word/document.xml")!!).bufferedReader().readText()
        assertTrue(docXml.contains("Executive Strategy 2026") || docXml.contains("Q3 Strategic Operations"))
        assertTrue(docXml.contains("SYSTEM TARGET") || docXml.contains("99.99%"))
        assertTrue(docXml.contains("Dijkstra"))
        assertTrue(docXml.contains("Primary"))
      }
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun testXlsxBuilderAdvancedFeatureParity() {
    val tempFile = File.createTempFile("test_adv_sheet", ".xlsx")
    try {
      val builder = XlsxBuilder("Financial Model")
      builder.current.theme = XlsxBuilder.Theme.EMERALD
      builder.freezeTopRow()
      builder.setAutoFilter(true)
      builder.setZebraStriping(true)
      builder.addHeader("Asset", "Qty", "Unit Price", "Total Value")
      builder.addRow("Compute Clusters", "10", "$5,000.00", "=B2*C2")
      builder.addRow("Storage Buckets", "25", "$200.00", "=B3*C3")
      builder.addRow("Bandwidth Allocation", "50", "$45.00", "=B4*C4")
      builder.addTotalRow("Grand Total", "", "", "=SUM(D2:D4)")

      val mdTable = """
        | Region | Latency | Availability |
        | us-central1 | 12ms | 99.9% |
        | europe-west1 | 18ms | 99.8% |
      """.trimIndent()
      builder.addSheet("Regional Telemetry", theme = XlsxBuilder.Theme.SLATE)
      builder.addMarkdownTable(mdTable)

      builder.save(tempFile)

      assertTrue(tempFile.exists())
      assertTrue(tempFile.length() > 1000)

      ZipFile(tempFile).use { zip ->
        val s1Xml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")!!).bufferedReader().readText()
        assertTrue(s1Xml.contains("Compute Clusters"))
        assertTrue(s1Xml.contains("autoFilter"))
        assertTrue(s1Xml.contains("SUM(D2:D4)"))
        val s2Xml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml")!!).bufferedReader().readText()
        assertTrue(s2Xml.contains("us-central1"))
      }
    } finally {
      tempFile.delete()
    }
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
