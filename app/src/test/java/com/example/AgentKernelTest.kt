package com.example

import com.example.agent.core.AgentEngine
import com.example.agent.core.AgentStatus
import com.example.agent.core.AutonomousSandboxProvider
import com.example.agent.core.DeleteFileTool
import com.example.agent.core.InspectArtifactTool
import com.example.agent.core.ListFilesTool
import com.example.agent.core.ReadFileTool
import com.example.agent.core.TerminalTool
import com.example.agent.core.ToolCall
import com.example.agent.core.ToolExecutor
import com.example.agent.core.ToolRegistry
import com.example.agent.core.ToolStatus
import com.example.agent.core.WorkspaceManager
import com.example.agent.core.WriteFileTool
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
import java.io.File

class AgentKernelTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var workspace: WorkspaceManager
  private lateinit var registry: ToolRegistry
  private lateinit var executor: ToolExecutor

  @Before
  fun setUp() {
    workspace = WorkspaceManager(tempFolder.newFolder("test_workspace"))
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
    while (engine.state.value.status != AgentStatus.COMPLETED && attempts < 50) {
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
}
