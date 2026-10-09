package com.example

import com.example.agent.core.PlanStepStatus
import com.example.agent.core.TaskPlanner
import com.example.agent.core.ToolResult
import com.example.agent.core.ToolStatus
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskPlannerTest {
  private fun result(status: ToolStatus, toolName: String = "read_file", error: String? = null) =
    ToolResult(
      callId = "test-call",
      toolName = toolName,
      status = status,
      arguments = emptyMap(),
      output = if (status == ToolStatus.SUCCEEDED) "ok" else null,
      error = error,
      duration = 1L,
      exitCode = if (status == ToolStatus.SUCCEEDED) 0 else 1
    )

  @Test
  fun parsesPlansAndTracksCompletedSteps() {
    val planner = TaskPlanner("Create a report")
    planner.acceptModelPlan("1. Inspect input\n2. Generate report\n3. Verify artifact")

    planner.markToolStarted("read_file")
    planner.recordToolResult(result(ToolStatus.SUCCEEDED))

    val context = planner.context()
    assertTrue(context.contains("[COMPLETED] Inspect input"))
    assertTrue(context.contains("[PENDING] Generate report"))
    assertEquals(listOf("Inspect input", "Generate report", "Verify artifact"), planner.planDescriptions())
  }

  @Test
  fun failedToolRequestsReplanAndStopsDependentBatch() {
    val planner = TaskPlanner("Create a report")
    planner.acceptModelPlan("1. Inspect input\n2. Generate report")
    planner.markToolStarted("read_file")
    planner.recordToolResult(result(ToolStatus.FAILED, error = "missing input"))

    assertTrue(planner.hasReplanRequest())
    assertTrue(planner.context().contains("REPLAN REQUIRED"))
    assertTrue(planner.context().contains("missing input"))
  }

  @Test
  fun repeatedFailureBlocksStepAfterThreeAttempts() {
    val planner = TaskPlanner("Finish task")
    planner.acceptModelPlan("1. Run validation")

    repeat(3) {
      planner.markToolStarted("terminal")
      planner.recordToolResult(result(ToolStatus.FAILED, toolName = "terminal", error = "exit 1"))
      if (it < 2) planner.requestReplan("try recovery")
    }

    assertTrue(planner.context().contains("[BLOCKED] Run validation"))
    assertTrue(planner.isBlocked())
  }
}
