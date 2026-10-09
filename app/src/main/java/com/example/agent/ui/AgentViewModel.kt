package com.example.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.core.AgentEngine
import com.example.agent.core.AgentState
import com.example.agent.core.Artifact
import com.example.agent.core.AutonomousSandboxProvider
import com.example.agent.core.GeminiLLMProvider
import com.example.agent.core.LLMProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class ProviderType {
  SANDBOX_ENGINE,
  GEMINI_LIVE_API
}

enum class UiTab(val label: String) {
  EXECUTION_FEED("Feed"),
  ARTIFACTS("Artifacts"),
  FIVE_STAGES("5-Stage View")
}

data class PromptPreset(
  val title: String,
  val prompt: String,
  val expectedArtifact: String? = null
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {

  private val workspaceDir = File(application.filesDir, "agent_workspace")
  private val sandboxProvider = AutonomousSandboxProvider()
  private val geminiProvider = GeminiLLMProvider(
    apiKey = getInitialApiKey()
  )

  private val engine = AgentEngine(
    workspaceDir = workspaceDir,
    initialProvider = sandboxProvider
  )

  val state: StateFlow<AgentState> = engine.state.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = engine.state.value
  )

  private val _activeTab = MutableStateFlow(UiTab.EXECUTION_FEED)
  val activeTab: StateFlow<UiTab> = _activeTab.asStateFlow()

  private val _providerType = MutableStateFlow(ProviderType.SANDBOX_ENGINE)
  val providerType: StateFlow<ProviderType> = _providerType.asStateFlow()

  private val _geminiApiKey = MutableStateFlow(getInitialApiKey())
  val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

  private val _selectedArtifactForPreview = MutableStateFlow<Artifact?>(null)
  val selectedArtifactForPreview: StateFlow<Artifact?> = _selectedArtifactForPreview.asStateFlow()

  private val _previewContent = MutableStateFlow<String?>(null)
  val previewContent: StateFlow<String?> = _previewContent.asStateFlow()

  private fun getInitialApiKey(): String {
    return try {
      val field = com.example.BuildConfig::class.java.getField("GEMINI_API_KEY")
      val rawKey = field.get(null) as? String ?: ""
      if (rawKey == "MY_GEMINI_API_KEY") "" else rawKey.trim()
    } catch (_: Exception) {
      ""
    }
  }

  val presets = listOf(
    PromptPreset(
      title = "Analyze data.csv → report.md",
      prompt = "Create a Python script that analyzes data.csv and produce report.md.",
      expectedArtifact = "report.md"
    ),
    PromptPreset(
      title = "Inspect & Summary JSON",
      prompt = "Inspect the workspace files and write a structured project_summary.json document.",
      expectedArtifact = "project_summary.json"
    ),
    PromptPreset(
      title = "Shell Diagnostics",
      prompt = "Run shell environment diagnostics via terminal and record the output in sys_info.txt.",
      expectedArtifact = "sys_info.txt"
    ),
    PromptPreset(
      title = "Data Validator Script",
      prompt = "Create a validation script check_data.sh to test for missing values and execute it.",
      expectedArtifact = "check_data.sh"
    )
  )

  fun selectTab(tab: UiTab) {
    _activeTab.value = tab
  }

  fun setProviderType(type: ProviderType) {
    _providerType.value = type
    if (type == ProviderType.SANDBOX_ENGINE) {
      engine.setProvider(sandboxProvider)
    } else {
      geminiProvider.setApiKey(_geminiApiKey.value)
      engine.setProvider(geminiProvider)
    }
  }

  fun updateGeminiApiKey(key: String) {
    _geminiApiKey.value = key
    geminiProvider.setApiKey(key)
    if (_providerType.value == ProviderType.GEMINI_LIVE_API) {
      engine.setProvider(geminiProvider)
    }
  }

  fun submitTask(intent: String, expectedArtifact: String? = null) {
    _activeTab.value = UiTab.EXECUTION_FEED
    engine.submitTask(intent, expectedArtifact)
  }

  fun stopExecution() {
    engine.cancel()
  }

  fun resetWorkspace(cleanFiles: Boolean = true) {
    engine.reset(cleanFiles)
    _selectedArtifactForPreview.value = null
    _previewContent.value = null
  }

  fun openArtifactPreview(artifact: Artifact) {
    _selectedArtifactForPreview.value = artifact
    viewModelScope.launch {
      try {
        val file = engine.workspace.resolveSafe(artifact.path)
        if (file.exists() && file.isFile) {
          _previewContent.value = file.readText()
        } else {
          _previewContent.value = "(File not found or cannot be read)"
        }
      } catch (e: Exception) {
        _previewContent.value = "Error reading artifact: ${e.message}"
      }
    }
  }

  fun closeArtifactPreview() {
    _selectedArtifactForPreview.value = null
    _previewContent.value = null
  }
}
