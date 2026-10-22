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

  @Test
  fun testInternalMcpPlaywrightServerTools() = runBlocking {
    val workspace = tempFolder.newFolder("ws_playwright")
    val config = McpServerConfig(
      id = "playwright",
      name = "Playwright Browser MCP",
      transport = McpTransportType.INTERNAL,
      url = "internal://playwright"
    )

    val client = McpClient(config = config, workspaceDir = workspace)
    val connectRes = client.connect()
    assertTrue("Playwright client should connect", connectRes.isSuccess)

    val tools = client.tools.value
    assertTrue("Should discover browser_navigate", tools.any { it.name == "browser_navigate" })
    assertTrue("Should discover browser_extract_content", tools.any { it.name == "browser_extract_content" })
    assertTrue("Should discover browser_evaluate", tools.any { it.name == "browser_evaluate" })
    assertTrue("Should discover browser_run_javascript", tools.any { it.name == "browser_run_javascript" })

    // Test PlaywrightJsEngine directly
    val sampleHtml = """
      <!DOCTYPE html>
      <html>
        <head><title>Test Page</title></head>
        <body>
          <h1 id="main-title">Playwright Automation</h1>
          <div class="content">
            <a href="https://example.com/one">Link One</a>
            <a href="/two">Link Two</a>
          </div>
        </body>
      </html>
    """.trimIndent()

    val jsEngine = com.example.agent.mcp.PlaywrightJsEngine(url = "https://example.com", html = sampleHtml)
    
    // Evaluate document.title
    val titleRes = jsEngine.evaluate("document.title")
    assertEquals("Test Page", titleRes.result)

    // Evaluate querySelector textContent
    val h1Res = jsEngine.evaluate("document.querySelector('h1').textContent")
    assertEquals("Playwright Automation", h1Res.result)

    // Evaluate querySelectorAll links map
    val linksRes = jsEngine.evaluate("document.querySelectorAll('a').map(a => a.href)")
    assertTrue(linksRes.result is List<*>)
    val linksList = linksRes.result as List<*>
    assertEquals(2, linksList.size)
    assertTrue(linksList[0].toString().contains("https://example.com/one"))

    // Evaluate Math arithmetic
    val mathRes = jsEngine.evaluate("Math.round(15.7) + 5")
    assertEquals(21.0, mathRes.result as Double, 0.001)

    client.disconnect()
  }

  @Test
  fun testInternalMcpMathAndTimeServer() = runBlocking {
    val workspace = tempFolder.newFolder("ws_math")
    val mathConfig = McpServerConfig(
      id = "math",
      name = "Math & Calculator MCP",
      transport = McpTransportType.INTERNAL,
      url = "internal://math"
    )

    val client = McpClient(config = mathConfig, workspaceDir = workspace)
    client.connect()

    val evalRes = client.callTool("calculate_expression", mapOf("expression" to "2 * 3 + 10"))
    assertTrue("Math evaluation should succeed", evalRes.isSuccess)
    val evalOut = evalRes.getOrThrow()
    assertTrue(evalOut.rawText.contains("16"))

    client.disconnect()
  }

  @Test
  fun testInternalMcpFilesystemPathSecurityAndProtectedFileGuard() = runBlocking {
    val workspace = tempFolder.newFolder("ws_fs_sec")
    val fsConfig = McpServerConfig(
      id = "filesystem",
      name = "Filesystem MCP",
      transport = McpTransportType.INTERNAL,
      url = "internal://filesystem"
    )

    val client = McpClient(config = fsConfig, workspaceDir = workspace)
    client.connect()

    // 1. Attempt path traversal outside workspace
    val traversalRes = client.callTool("write_file", mapOf("path" to "../escape.txt", "content" to "pwned"))
    assertTrue("Traversal call returns MCP tool error result", traversalRes.isSuccess)
    val traversalOut = traversalRes.getOrThrow()
    assertTrue("Path traversal must be rejected with isError=true", traversalOut.isError)
    assertTrue("Error message must mention security/traversal", traversalOut.rawText.contains("escapes workspace", ignoreCase = true) || traversalOut.rawText.contains("Security", ignoreCase = true))

    // 2. Attempt overwriting protected database file data.db
    val dbOverwriteRes = client.callTool("write_file", mapOf("path" to "data.db", "content" to "corrupt"))
    assertTrue(dbOverwriteRes.isSuccess)
    val dbOverwriteOut = dbOverwriteRes.getOrThrow()
    assertTrue("Protected system file write must be rejected with isError=true", dbOverwriteOut.isError)

    client.disconnect()
  }
}
