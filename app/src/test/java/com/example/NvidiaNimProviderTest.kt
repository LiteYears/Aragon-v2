package com.example

import com.example.agent.core.AgentMessage
import com.example.agent.core.LLMDecision
import com.example.agent.core.MessageRole
import com.example.agent.core.NvidiaNimConfig
import com.example.agent.core.NvidiaNimModels
import com.example.agent.core.NvidiaNimProvider
import com.example.agent.core.Tool
import com.example.agent.core.ToolParameter
import com.example.agent.core.ToolResult
import com.example.agent.core.ToolSchema
import com.example.agent.core.ToolStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NvidiaNimProviderTest {

  // Test tool implementation for schema testing
  private class DummyTool : Tool {
    override val name: String = "write_file"
    override val description: String = "Writes text content to a specified file."
    override val schema: ToolSchema = ToolSchema(
      parameters = listOf(
        ToolParameter(
          name = "path",
          type = "string",
          description = "Relative file path",
          required = true
        ),
        ToolParameter(
          name = "content",
          type = "string",
          description = "Text content to write",
          required = true
        ),
        ToolParameter(
          name = "mode",
          type = "string",
          description = "Write mode",
          required = false,
          enumValues = listOf("overwrite", "append")
        )
      )
    )

    override suspend fun execute(callId: String, arguments: Map<String, Any?>): ToolResult {
      return ToolResult(
        callId = callId,
        toolName = name,
        status = ToolStatus.SUCCEEDED,
        arguments = arguments,
        output = "File written",
        error = null,
        duration = 1L
      )
    }
  }

  @Test
  fun testProviderConfigurationDefaults() {
    val provider = NvidiaNimProvider()
    assertEquals("https://integrate.api.nvidia.com/v1", provider.config.baseUrl)
    assertEquals("z-ai/glm-5.3", provider.config.model)
    assertEquals("NVIDIA NIM (z-ai/glm-5.3)", provider.providerName)
    assertFalse(provider.hasApiKey())

    // Switch model without modifying agent
    provider.setModel("nvidia/llama-3.1-nemotron-70b-instruct")
    assertEquals("nvidia/llama-3.1-nemotron-70b-instruct", provider.config.model)
    assertEquals("NVIDIA NIM (nvidia/llama-3.1-nemotron-70b-instruct)", provider.providerName)

    // Verify presets
    assertTrue(NvidiaNimModels.PRESETS.contains("z-ai/glm-5.3"))
    assertTrue(NvidiaNimModels.PRESETS.contains("z-ai/glm-5.3-flash"))
    assertTrue(NvidiaNimModels.PRESETS.contains("moonshotai/kimi-k3"))
    assertTrue(NvidiaNimModels.PRESETS.contains("nvidia/llama-3.1-nemotron-70b-instruct"))
  }

  @Test
  fun testMissingApiKeyReturnsProviderError() = runBlocking {
    val provider = NvidiaNimProvider(NvidiaNimConfig(apiKey = ""))
    val decision = provider.decideNextAction(
      systemPrompt = "System instructions",
      taskIntent = "Create hello.txt",
      messages = listOf(
        AgentMessage(role = MessageRole.USER, content = "Create hello.txt containing Hello World")
      ),
      tools = listOf(DummyTool())
    )

    assertTrue(decision is LLMDecision.ProviderError)
    val error = decision as LLMDecision.ProviderError
    assertTrue(error.message.contains("API key is not configured"))
  }

  @Test
  fun testNativeToolDefinitionGeneration() {
    val tool = DummyTool()
    val provider = NvidiaNimProvider()

    // Test reflection access to buildToolsJson to verify exact schema structure
    val method = NvidiaNimProvider::class.java.getDeclaredMethod("buildToolsJson", List::class.java)
    method.isAccessible = true
    val toolsJson = method.invoke(provider, listOf(tool)) as JSONArray

    assertEquals(1, toolsJson.length())
    val toolObj = toolsJson.getJSONObject(0)
    assertEquals("function", toolObj.getString("type"))

    val funcObj = toolObj.getJSONObject("function")
    assertEquals("write_file", funcObj.getString("name"))
    assertEquals("Writes text content to a specified file.", funcObj.getString("description"))

    val paramsObj = funcObj.getJSONObject("parameters")
    assertEquals("object", paramsObj.getString("type"))

    val propsObj = paramsObj.getJSONObject("properties")
    assertTrue(propsObj.has("path"))
    assertTrue(propsObj.has("content"))
    assertTrue(propsObj.has("mode"))

    val requiredArr = paramsObj.getJSONArray("required")
    assertEquals(2, requiredArr.length())
    val requiredFields = (0 until requiredArr.length()).map { requiredArr.getString(it) }
    assertTrue(requiredFields.contains("path"))
    assertTrue(requiredFields.contains("content"))
  }

  @Test
  fun testMessageSerializationWithToolResults() {
    val provider = NvidiaNimProvider()

    val messages = listOf(
      AgentMessage(
        role = MessageRole.USER,
        content = "Create hello.txt containing Hello World."
      ),
      AgentMessage(
        role = MessageRole.ASSISTANT,
        content = "Writing hello.txt to the workspace.",
        toolCallId = "call_abc123",
        toolName = "write_file",
        toolArgs = mapOf("path" to "hello.txt", "content" to "Hello World")
      ),
      AgentMessage(
        role = MessageRole.TOOL,
        content = "=== OBSERVATION ===\nTOOL: write_file\nSTATUS: SUCCEEDED",
        toolCallId = "call_abc123",
        toolName = "write_file"
      )
    )

    val method = NvidiaNimProvider::class.java.getDeclaredMethod(
      "buildMessagesJson",
      String::class.java,
      List::class.java
    )
    method.isAccessible = true
    val serialized = method.invoke(provider, "System persona prompt", messages) as JSONArray

    // Index 0: system
    assertEquals("system", serialized.getJSONObject(0).getString("role"))
    assertEquals("System persona prompt", serialized.getJSONObject(0).getString("content"))

    // Index 1: user
    assertEquals("user", serialized.getJSONObject(1).getString("role"))
    assertEquals("Create hello.txt containing Hello World.", serialized.getJSONObject(1).getString("content"))

    // Index 2: assistant with native tool_calls
    val assistantMsg = serialized.getJSONObject(2)
    assertEquals("assistant", assistantMsg.getString("role"))
    val toolCalls = assistantMsg.getJSONArray("tool_calls")
    assertEquals(1, toolCalls.length())
    val toolCall = toolCalls.getJSONObject(0)
    assertEquals("call_abc123", toolCall.getString("id"))
    assertEquals("function", toolCall.getString("type"))
    val fn = toolCall.getJSONObject("function")
    assertEquals("write_file", fn.getString("name"))
    val argsObj = JSONObject(fn.getString("arguments"))
    assertEquals("hello.txt", argsObj.getString("path"))
    assertEquals("Hello World", argsObj.getString("content"))

    // Index 3: tool result associated with call_abc123
    val toolMsg = serialized.getJSONObject(3)
    assertEquals("tool", toolMsg.getString("role"))
    assertEquals("call_abc123", toolMsg.getString("tool_call_id"))
    assertEquals("write_file", toolMsg.getString("name"))
    assertTrue(toolMsg.getString("content").contains("STATUS: SUCCEEDED"))
  }

  @Test
  fun testConsecutiveAssistantToolCallsAreSerializedAsOneBatch() {
    val provider = NvidiaNimProvider()
    val messages = listOf(
      AgentMessage(role = MessageRole.USER, content = "Inspect and summarize the workspace."),
      AgentMessage(
        role = MessageRole.ASSISTANT,
        content = "Inspecting the workspace.",
        toolCallId = "call_one",
        toolName = "list_files",
        toolArgs = mapOf("path" to ".")
      ),
      AgentMessage(
        role = MessageRole.ASSISTANT,
        content = "",
        toolCallId = "call_two",
        toolName = "read_file",
        toolArgs = mapOf("path" to "README.md")
      ),
      AgentMessage(
        role = MessageRole.TOOL,
        content = "first result",
        toolCallId = "call_one",
        toolName = "list_files"
      ),
      AgentMessage(
        role = MessageRole.TOOL,
        content = "second result",
        toolCallId = "call_two",
        toolName = "read_file"
      )
    )

    val method = NvidiaNimProvider::class.java.getDeclaredMethod(
      "buildMessagesJson",
      String::class.java,
      List::class.java
    )
    method.isAccessible = true
    val serialized = method.invoke(provider, "System", messages) as JSONArray

    val assistant = serialized.getJSONObject(2)
    assertEquals("assistant", assistant.getString("role"))
    assertEquals(2, assistant.getJSONArray("tool_calls").length())
    assertEquals("call_one", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    assertEquals("call_two", assistant.getJSONArray("tool_calls").getJSONObject(1).getString("id"))
    assertEquals("tool", serialized.getJSONObject(3).getString("role"))
    assertEquals("tool", serialized.getJSONObject(4).getString("role"))
  }
}
