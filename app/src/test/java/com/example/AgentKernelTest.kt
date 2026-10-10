package com.example

import com.example.agent.core.AgentEngine
import com.example.agent.core.AgentStatus
import com.example.agent.core.AutonomousSandboxProvider
import com.example.agent.core.BrowserAutomationTool
import com.example.agent.core.CopyFileTool
import com.example.agent.core.CsvProcessorTool
import com.example.agent.core.DeepResearchTool
import com.example.agent.core.DeleteFileTool
import com.example.agent.core.EditFileTool
import com.example.agent.core.ExtractWebDataTool
import com.example.agent.core.FileSearchTool
import com.example.agent.core.InspectArtifactTool
import com.example.agent.core.JsonProcessorTool
import com.example.agent.core.ListFilesTool
import com.example.agent.core.MoveFileTool
import com.example.agent.core.ReadFileTool
import com.example.agent.core.TerminalTool
import com.example.agent.core.ToolCall
import com.example.agent.core.ToolExecutor
import com.example.agent.core.ToolRegistry
import com.example.agent.core.ToolStatus
import com.example.agent.core.WebBrowseTool
import com.example.agent.core.WebCrawlerTool
import com.example.agent.core.WebSearchTool
import com.alibaba.opensandbox.sandbox.Sandbox
import com.example.agent.core.WriteFileTool
import com.example.agent.core.Artifact
import com.example.agent.core.ArtifactDownloader
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentKernelTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var workspace: Sandbox
  private lateinit var registry: ToolRegistry
  private lateinit var executor: ToolExecutor

  @Before
  fun setUp() {
    workspace = Sandbox.builder().baseDir(tempFolder.newFolder("test_workspace")).build()
    registry = ToolRegistry()
    registry.register(WriteFileTool(workspace), "save_file")
    registry.register(ReadFileTool(workspace), "cat")
    registry.register(ListFilesTool(workspace), "ls")
    registry.register(InspectArtifactTool(workspace), "stat")
    registry.register(TerminalTool(workspace), "sh")

    executor = ToolExecutor(registry, workspace)
  }

  @Test
  fun testToolRegistryValidation() {
    // Valid tool call
    val validCall = ToolCall(
      toolName = "write_file",
      arguments = mapOf("path" to "test.txt", "content" to "hello")
    )
    assertNull(registry.validate(validCall))

    // Missing required argument
    val invalidCall = ToolCall(
      toolName = "write_file",
      arguments = mapOf("path" to "test.txt")
    )
    val error = registry.validate(invalidCall)
    assertNotNull(error)
    assertTrue(error!!.contains("content"))

    // Null JSON object parameter
    val jsonNullCall = ToolCall(
      toolName = "write_file",
      arguments = mapOf("path" to "test.txt", "content" to org.json.JSONObject.NULL)
    )
    val jsonNullError = registry.validate(jsonNullCall)
    assertNotNull(jsonNullError)
    assertTrue(jsonNullError!!.contains("content"))

    // Unknown tool
    val unknownCall = ToolCall(
      toolName = "magic_tool",
      arguments = emptyMap()
    )
    assertNotNull(registry.validate(unknownCall))
  }

  @Test
  fun testAuthoritativeFileTools() = runBlocking {
    // 1. Write file
    val writeCall = ToolCall(
      toolName = "write_file",
      arguments = mapOf("path" to "notes.txt", "content" to "Autonomous Agent Kernel")
    )
    val writeResult = executor.execute(writeCall)
    assertEquals(ToolStatus.SUCCEEDED, writeResult.status)
    assertEquals(1, writeResult.artifacts.size)
    assertEquals("notes.txt", writeResult.artifacts[0].path)

    // 2. Read file
    val readCall = ToolCall(
      toolName = "read_file",
      arguments = mapOf("path" to "notes.txt")
    )
    val readResult = executor.execute(readCall)
    assertEquals(ToolStatus.SUCCEEDED, readResult.status)
    assertEquals("Autonomous Agent Kernel", readResult.output)

    // 3. Inspect artifact
    val inspectCall = ToolCall(
      toolName = "inspect_artifact",
      arguments = mapOf("path" to "notes.txt")
    )
    val inspectResult = executor.execute(inspectCall)
    assertEquals(ToolStatus.SUCCEEDED, inspectResult.status)
    assertTrue(inspectResult.output!!.contains("Autonomous Agent Kernel"))

    // 4. Create Observation
    val obs = executor.createObservation(inspectResult)
    assertTrue(obs.isSuccess)
    assertTrue(obs.summary.contains("inspect_artifact"))
    assertTrue(obs.rawOutput.contains("STATUS: SUCCEEDED"))
  }

  @Test
  fun testAgentEngineExecutionAndVerification() = runBlocking {
    val workspaceDir = tempFolder.newFolder("engine_workspace")
    val engine = AgentEngine(
      workspaceDir = workspaceDir,
      initialProvider = AutonomousSandboxProvider(),
      maxSteps = 10
    )

    engine.submitTask(
      intent = "Create a Python script that analyzes data.csv and produce report.md",
      expectedArtifact = "report.md"
    )

    // Wait for autonomous completion in coroutine
    var attempts = 0
    while (engine.state.value.status != AgentStatus.COMPLETED && attempts < 80) {
      delay(100)
      attempts++
    }

    val finalState = engine.state.value
    assertEquals(AgentStatus.COMPLETED, finalState.status)
    assertTrue(finalState.toolExecutions.isNotEmpty())
    assertTrue(File(workspaceDir, "report.md").exists())
    assertNotNull(finalState.completionSummary)

    // Verify 5-stage separation
    assertEquals("Create a Python script that analyzes data.csv and produce report.md", finalState.fiveStageRecord.intent)
    assertTrue(finalState.fiveStageRecord.actions.isNotEmpty())
    assertTrue(finalState.fiveStageRecord.observations.isNotEmpty())
    assertNotNull(finalState.fiveStageRecord.conclusion)
  }

  @Test
  fun testHonestFailureWhenDeletingNonExistentFile() = runBlocking {
    val deleteTool = DeleteFileTool(workspace)
    val result = deleteTool.execute("call-1", mapOf("path" to "non_existent_file.txt"))
    assertEquals(ToolStatus.FAILED, result.status)
    assertNotNull(result.error)
    assertTrue(result.error!!.contains("does not exist"))

    val obs = executor.createObservation(result)
    assertFalse(obs.isSuccess)
    assertTrue(obs.rawOutput.contains("STATUS: FAILED"))
  }

  @Test
  fun testObservationExplicitEmptyOutput() = runBlocking {
    val emptyResult = com.example.agent.core.ToolResult(
      callId = "test-call-id",
      toolName = "terminal",
      status = ToolStatus.SUCCEEDED,
      arguments = mapOf("command" to "true"),
      output = null,
      error = null,
      duration = 10L,
      stdout = "",
      stderr = "",
      exitCode = 0
    )
    val obs = executor.createObservation(emptyResult)
    assertTrue(obs.rawOutput.contains("STDOUT: empty (0 bytes)"))
    assertTrue(obs.rawOutput.contains("STDERR: empty (0 bytes)"))
    assertTrue(obs.rawOutput.contains("STATUS: SUCCEEDED"))
  }

  @Test
  fun testPrecisionEditAndFileSearchTools() = runBlocking {
    val writeTool = WriteFileTool(workspace)
    writeTool.execute("c1", mapOf("path" to "code.py", "content" to "def hello():\n    return 'old_value'\n"))

    // Precision edit
    val editTool = EditFileTool(workspace)
    val editResult = editTool.execute("c2", mapOf(
      "path" to "code.py",
      "targetContent" to "old_value",
      "replacementContent" to "new_value"
    ))
    assertEquals(ToolStatus.SUCCEEDED, editResult.status)

    val readTool = ReadFileTool(workspace)
    val readResult = readTool.execute("c3", mapOf("path" to "code.py"))
    assertTrue(readResult.output!!.contains("new_value"))
    assertFalse(readResult.output!!.contains("old_value"))

    // File search / grep
    val searchTool = FileSearchTool(workspace)
    val searchResult = searchTool.execute("c4", mapOf("query" to "new_value"))
    assertEquals(ToolStatus.SUCCEEDED, searchResult.status)
    assertTrue(searchResult.output!!.contains("code.py:2"))
  }

  @Test
  fun testCopyMoveAndCsvProcessorTools() = runBlocking {
    // Write sample CSV
    val writeTool = WriteFileTool(workspace)
    writeTool.execute("c1", mapOf(
      "path" to "products.csv",
      "content" to "id,name,price\n1,Server,500\n2,Database,300\n3,Router,150\n"
    ))

    // Copy file
    val copyTool = CopyFileTool(workspace)
    val copyResult = copyTool.execute("c2", mapOf("source" to "products.csv", "destination" to "backup/products.csv"))
    assertEquals(ToolStatus.SUCCEEDED, copyResult.status)
    assertTrue(workspace.resolveSafe("backup/products.csv").exists())

    // Move file
    val moveTool = MoveFileTool(workspace)
    val moveResult = moveTool.execute("c3", mapOf("source" to "backup/products.csv", "destination" to "backup/products_renamed.csv"))
    assertEquals(ToolStatus.SUCCEEDED, moveResult.status)
    assertFalse(workspace.resolveSafe("backup/products.csv").exists())
    assertTrue(workspace.resolveSafe("backup/products_renamed.csv").exists())

    // CSV Processor
    val csvTool = CsvProcessorTool(workspace)
    val summaryResult = csvTool.execute("c4", mapOf("path" to "products.csv", "action" to "summary"))
    assertEquals(ToolStatus.SUCCEEDED, summaryResult.status)
    assertTrue(summaryResult.output!!.contains("Total Rows: 3"))
    assertTrue(summaryResult.output!!.contains("Columns: id, name, price"))

    val mdResult = csvTool.execute("c5", mapOf("path" to "products.csv", "action" to "markdown"))
    assertTrue(mdResult.output!!.contains("| id | name | price |"))
  }

  @Test
  fun testJsonProcessorTool() = runBlocking {
    val jsonTool = JsonProcessorTool(workspace)
    val sampleJson = """{"user": {"name": "Alice", "role": "admin"}, "tags": ["agent", "sandbox"]}"""

    // Query dot-path
    val queryRes = jsonTool.execute("c1", mapOf("input" to sampleJson, "action" to "query", "query" to "user.name"))
    assertEquals(ToolStatus.SUCCEEDED, queryRes.status)
    assertEquals("Alice", queryRes.output)

    // Format JSON
    val formatRes = jsonTool.execute("c2", mapOf("input" to sampleJson, "action" to "format"))
    assertEquals(ToolStatus.SUCCEEDED, formatRes.status)
    assertTrue(formatRes.output!!.contains("\"name\": \"Alice\""))

    // Validate
    val valRes = jsonTool.execute("c3", mapOf("input" to sampleJson, "action" to "validate"))
    assertEquals(ToolStatus.SUCCEEDED, valRes.status)
    assertTrue(valRes.output!!.contains("Valid JSON syntax"))
  }

  @Test
  fun testWebResearchHtmlConverterAndBrowserTool() = runBlocking {
    // Test HTML to Markdown & link discovery
    val rawHtml = """
      <html>
        <head><title>Aragon Research Hub</title></head>
        <body>
          <header><p>Banner</p></header>
          <h1>Autonomous Systems</h1>
          <p>Autonomous AI agents use <strong>deterministic tools</strong> to accomplish real goals.</p>
          <table>
            <tr><th>Metric</th><th>Score</th></tr>
            <tr><td>Accuracy</td><td>99.4%</td></tr>
          </table>
          <a href="/docs/tools">Tool Documentation</a>
          <a href="https://external.org/benchmarks">Benchmarks</a>
        </body>
      </html>
    """.trimIndent()

    val (markdown, links) = com.example.agent.core.WebContentConverter.cleanHtmlToMarkdown(rawHtml, "https://example.com/hub")
    assertTrue(markdown.contains("# Autonomous Systems"))
    assertTrue(markdown.contains("**deterministic tools**"))
    assertEquals(2, links.size)
    assertEquals("https://example.com/docs/tools", links[0].url)
    assertTrue(links[0].isInternal)

    val tables = com.example.agent.core.WebContentConverter.extractTables(rawHtml)
    assertEquals(1, tables.size)
    assertTrue(tables[0].contains("| Metric | Score |"))
    assertTrue(tables[0].contains("| Accuracy | 99.4% |"))

    // Test Playwright Browser Automation tool
    val browserTool = BrowserAutomationTool(workspace)
    assertNotNull(browserTool.schema)
    assertEquals("browser_tool", browserTool.name)
  }

  @Test
  fun testDeepResearchSynthesisReport() = runBlocking {
    val deepResearch = DeepResearchTool(workspace)
    assertNotNull(deepResearch.schema)
    assertEquals("deep_research", deepResearch.name)

    // Verify workspace report writing & verification
    val reportFile = workspace.writeWorkspaceFile(
      "test_report.md",
      "# Research Dossier: Artificial General Intelligence\n\n- Source: https://example.com\n- Synthesis complete."
    )
    assertTrue(reportFile.exists())
    assertTrue(reportFile.length() > 0)
    val artifact = workspace.createArtifactFromFile(reportFile)
    assertEquals("test_report.md", artifact.path)
    assertTrue(artifact.size > 0)
  }

  @Test
  fun testArtifactCreationAndAbsolutePath() {
    val reportFile = workspace.writeWorkspaceFile(
      "exports/summary.md",
      "# Executive Summary\nAnalysis generated successfully."
    )
    val artifact = workspace.createArtifactFromFile(reportFile)
    assertEquals("exports/summary.md", artifact.path)
    assertEquals("summary.md", artifact.name)
    assertEquals(reportFile.canonicalPath, artifact.absolutePath)
    assertTrue(artifact.exists)
    assertTrue(artifact.size > 0)
  }

  @Test
  fun testArtifactDownloaderFileResolutionAndDownload() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    // Create a file in workspace
    val artifactFile = workspace.writeWorkspaceFile(
      "reports/financial_report.csv",
      "quarter,revenue,net_profit\nQ1,1200000,340000\nQ2,1450000,410000"
    )
    val artifact = workspace.createArtifactFromFile(artifactFile)

    // Test resolving the artifact file
    val resolvedFile = ArtifactDownloader.resolveArtifactFile(context, artifact)
    assertNotNull(resolvedFile)
    assertTrue(resolvedFile!!.exists())
    assertEquals(artifactFile.canonicalPath, resolvedFile.canonicalPath)

    // Also test resolving when absolutePath is empty (simulating legacy/external artifact)
    val legacyArtifact = artifact.copy(absolutePath = "")
    val resolvedLegacy = ArtifactDownloader.resolveArtifactFile(context, legacyArtifact, workspace.baseDir)
    assertNotNull(resolvedLegacy)
    assertTrue(resolvedLegacy!!.exists())

    // Test downloading the artifact
    val downloaded = ArtifactDownloader.downloadArtifact(context, artifact, workspace.baseDir)
    assertTrue(downloaded)
  }

  @Test
  fun testSeparateSessionsAndArtifactCleaning() {
    val engine = AgentEngine(
      workspaceDir = workspace.baseDir,
      initialProvider = AutonomousSandboxProvider()
    )

    // Write a dummy artifact in first session
    workspace.writeWorkspaceFile("task1_result.txt", "Task 1 data")
    assertEquals(1, workspace.listAllArtifacts().filter { it.path == "task1_result.txt" }.size)

    // Start a new session
    val session1 = engine.state.value.sessionId
    val session2 = engine.startNewSession()

    assertFalse("Sessions must have different IDs", session1 == session2)
    // Artifacts must be completely cleaned
    assertEquals(0, engine.state.value.artifacts.size)
    assertFalse(File(workspace.baseDir, "task1_result.txt").exists())
    assertEquals(0, engine.state.value.executionFeed.size)
  }

  @Test
  fun testTemporaryFilesCleaning() {
    val tempFile = workspace.writeWorkspaceFile("test.tmp", "Temporary content")
    assertTrue(tempFile.exists())
    workspace.cleanTemporaryFiles()
    assertFalse("Temporary file should be removed", tempFile.exists())
  }

  @Test
  fun testTransformSysInfoWordDocExecutionAndVerification() = runBlocking {
    val workspaceDir = tempFolder.newFolder("word_workspace")
    val engine = AgentEngine(
      workspaceDir = workspaceDir,
      initialProvider = AutonomousSandboxProvider(),
      maxSteps = 15
    )

    engine.submitTask(
      intent = "Transform sys_info.txt into a structured Word document (sys_info.docx) using Python 3 and python-docx.",
      expectedArtifact = "sys_info.docx"
    )

    var attempts = 0
    while (engine.state.value.status != AgentStatus.COMPLETED && attempts < 80) {
      delay(100)
      attempts++
    }

    val finalState = engine.state.value
    assertEquals(AgentStatus.COMPLETED, finalState.status)
    assertTrue(File(workspaceDir, "sys_info.docx").exists())
    assertTrue(finalState.executionFeed.size < 30) // No brute-force loop explosion
  }

  @Test
  fun testVerificationRejectionCircuitBreakerPreventsInfiniteLoop() = runBlocking {
    val workspaceDir = tempFolder.newFolder("circuit_breaker_workspace")
    // Provider that always proposes complete without executing any tools
    val prematureCompleteProvider = object : com.example.agent.core.LLMProvider {
      override val providerName: String = "StubPrematureComplete"
      override suspend fun decideNextAction(
        systemPrompt: String,
        taskIntent: String,
        messages: List<com.example.agent.core.AgentMessage>,
        tools: List<com.example.agent.core.Tool>
      ): com.example.agent.core.LLMDecision = com.example.agent.core.LLMDecision.Complete("Premature done")
    }

    val engine = AgentEngine(
      workspaceDir = workspaceDir,
      initialProvider = prematureCompleteProvider,
      maxSteps = 20
    )

    engine.submitTask(
      intent = "Create missing_file.docx",
      expectedArtifact = "missing_file.docx"
    )

    var attempts = 0
    while (engine.state.value.status != AgentStatus.FAILED && attempts < 80) {
      delay(100)
      attempts++
    }

    val finalState = engine.state.value
    assertEquals(AgentStatus.FAILED, finalState.status)
    // Verify that it halted without unbounded loops (rejections capped by circuit breaker)
    val rejectionsCount = finalState.executionFeed.count { it.title.contains("Verification Incomplete") }
    assertTrue("Verification rejections must be capped by circuit breaker", rejectionsCount in 1..3)
  }
}


