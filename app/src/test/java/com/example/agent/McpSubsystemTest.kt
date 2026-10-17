package com.example.agent

import com.example.agent.core.ToolRegistry
import com.example.agent.mcp.InternalMcpTransport
import com.example.agent.mcp.McpBridgedTool
import com.example.agent.mcp.McpClient
import com.example.agent.mcp.McpServerConfig
import com.example.agent.mcp.McpToolDefinition
import com.example.agent.mcp.McpTransportType
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpSubsystemTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun testInternalMcpMemoryService() = runBlocking {
    val workspace = tempFolder.newFolder("ws_memory")
    val config = McpServerConfig(
      id = "memory",
      name = "Knowledge Graph Memory",
      transport = McpTransportType.INTERNAL,
      url = "internal://memory"
    )

    val client = McpClient(config = config, workspaceDir = workspace)
    val connectRes = client.connect()
    assertTrue("Client should connect successfully", connectRes.isSuccess)

    // Test tool discovery
    val tools = client.tools.value
    assertTrue("Should discover memory tools", tools.any { it.name == "memory_store" })
    assertTrue("Should discover memory_retrieve", tools.any { it.name == "memory_retrieve" })

    // Test memory_store
    val storeRes = client.callTool(
      "memory_store",
      mapOf(
        "key" to "target_quarter",
        "content" to "Q4 2026 sales projections",
        "category" to "context"
      )
    )
    assertTrue("memory_store should succeed", storeRes.isSuccess)
    val storeOut = storeRes.getOrThrow()
    assertTrue(storeOut.rawText.contains("STORED"))

    // Test memory_retrieve
    val retrieveRes = client.callTool(
      "memory_retrieve",
      mapOf("query" to "target_quarter")
    )
    assertTrue("memory_retrieve should succeed", retrieveRes.isSuccess)
    val retrieveOut = retrieveRes.getOrThrow()
    assertTrue(retrieveOut.rawText.contains("Q4 2026 sales projections"))

    // Test ping
    val pingRes = client.ping()
    assertTrue("ping should succeed", pingRes.isSuccess)
    assertTrue("latency should be >= 1ms", pingRes.getOrThrow() >= 1L)

    client.disconnect()
  }

  @Test
  fun testInternalMcpPromptsDiscoveryAndGet() = runBlocking {
    val workspace = tempFolder.newFolder("ws_prompts")
    val config = McpServerConfig(
      id = "sqlite",
      name = "SQLite MCP",
      transport = McpTransportType.INTERNAL,
      url = "internal://sqlite"
    )

    val client = McpClient(config = config, workspaceDir = workspace)
    client.connect()

    val prompts = client.prompts.value
    assertTrue("Should discover prompt templates", prompts.isNotEmpty())
    val codeReviewPrompt = prompts.find { it.name == "deep_code_review" }
    assertNotNull("Should discover deep_code_review prompt", codeReviewPrompt)

    val rendered = client.getPrompt(
      "deep_code_review",
      mapOf("language" to "Kotlin", "code" to "fun hello() = println(\"world\")")
    )
    assertTrue("Prompt retrieval should succeed", rendered.isSuccess)
    val msgs = rendered.getOrThrow()
    assertTrue(msgs.first().content.contains("fun hello()"))

    client.disconnect()
  }

  @Test
  fun testMcpBridgedToolTypeCoercion() {
    val schemaJson = JSONObject("""
      {
        "type": "object",
        "properties": {
          "title": { "type": "string" },
          "count": { "type": "integer" },
          "flag": { "type": "boolean" }
        },
        "required": ["title", "count"]
      }
    """.trimIndent())

    val toolSchema = McpBridgedTool.parseJsonSchema(schemaJson)
    assertEquals(3, toolSchema.parameters.size)

    val sanitized = McpBridgedTool.sanitizeToolName("my-server/tool_name.v1")
    assertTrue(sanitized.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")))
  }
}
