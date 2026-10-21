package com.example

import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityLaunchTest {

  @Test
  fun testViewModelInstantiationDoesNotCrash() {
    val app = org.robolectric.RuntimeEnvironment.getApplication()
    val vm = com.example.agent.ui.AgentViewModel(app)
    assert(vm.nvidiaModels.value.isNotEmpty())
    assert(vm.activeTab.value == com.example.agent.ui.UiTab.EXECUTION_FEED)
  }

  @Test
  fun testToolSizeAndPrompt() = kotlinx.coroutines.runBlocking {
    val app = org.robolectric.RuntimeEnvironment.getApplication()
    val vm = com.example.agent.ui.AgentViewModel(app)
    val engine = com.example.agent.service.AgentServiceBridge.engine!!
    kotlinx.coroutines.delay(1000L)
    val tools = engine.registry.getAvailableTools()
    println("AVAILABLE TOOLS COUNT = ${tools.size}")
    val manifest = com.example.agent.core.CapabilityManifest.build(engine.registry, engine.sandboxProvider)
    println("MANIFEST JSON LENGTH = ${manifest.toJsonString().length}")
    val openAiTools = engine.registry.toOpenAiToolsJson()
    println("OPENAI TOOLS JSON LENGTH = ${openAiTools.length()}")
    val geminiTools = engine.registry.toGeminiFunctionDeclarations()
    println("GEMINI TOOLS JSON LENGTH = ${geminiTools.length()}")
    val formattedPrompt = engine.registry.formatToolsPrompt()
    println("FORMATTED TOOLS PROMPT LENGTH = ${formattedPrompt.length}")
  }

  @Test
  fun testReproduceExecutionException() = kotlinx.coroutines.runBlocking {
    val app = org.robolectric.RuntimeEnvironment.getApplication()
    val vm = com.example.agent.ui.AgentViewModel(app)
    val engine = com.example.agent.service.AgentServiceBridge.engine!!
    kotlinx.coroutines.delay(1000L)
    vm.submitTask("replicate this please into a word file")
    var tries = 0
    while (tries < 100 && engine.state.value.status != com.example.agent.core.AgentStatus.FAILED && engine.state.value.status != com.example.agent.core.AgentStatus.COMPLETED) {
      kotlinx.coroutines.delay(100L)
      tries++
    }
    println("REPRODUCE ENGINE STATUS: ${engine.state.value.status}")
    println("REPRODUCE ENGINE ERROR: ${engine.state.value.error}")
    engine.state.value.executionFeed.forEach {
      println("STEP ${it.stepNumber} [${it.type}]: ${it.title} -> ${it.content}")
    }
  }
}




