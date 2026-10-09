package com.example.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.core.AgentEngine
import com.example.agent.core.AgentState
import com.example.agent.core.AgentStatus
import com.example.agent.core.Artifact
import com.example.agent.core.AutonomousSandboxProvider
import com.example.agent.core.GeminiLLMProvider
import com.example.agent.core.NvidiaNimConfig
import com.example.agent.core.NvidiaNimModels
import com.example.agent.core.NvidiaNimProvider
import com.example.agent.core.StepType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class ProviderType {
  NVIDIA_NIM,
  SANDBOX_ENGINE,
  GEMINI_LIVE_API
}

enum class UiTab(val label: String) {
  EXECUTION_FEED("Execution Feed"),
  ARTIFACTS("Workspace Artifacts"),
  FIVE_STAGES("5 Stages")
}

data class PromptPreset(
  val title: String,
  val prompt: String,
  val expectedArtifact: String? = null
)

sealed class ConnectionStatus {
  object Idle : ConnectionStatus()
  object Testing : ConnectionStatus()
  data class Success(val message: String, val modelCount: Int) : ConnectionStatus()
  data class Error(val message: String) : ConnectionStatus()
}

class AgentViewModel(
  application: Application
) : AndroidViewModel(application) {

  // Explicit no-argument constructor for generic reflection or testing
  constructor() : this(resolveDefaultApplication())

  val workspaceDir: File = resolveWorkspaceDir(application)

  private val sandboxProvider = AutonomousSandboxProvider()
  private val nvidiaProvider = NvidiaNimProvider(
    NvidiaNimConfig(
      apiKey = getInitialNvidiaApiKey(),
      model = NvidiaNimConfig.DEFAULT_MODEL
    )
  )
  private val geminiProvider = GeminiLLMProvider(
    apiKey = getInitialGeminiApiKey()
  )

  private val engine = AgentEngine(
    workspaceDir = workspaceDir,
    initialProvider = nvidiaProvider
  )

  val state: StateFlow<AgentState> = engine.state.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = engine.state.value
  )

  private val _activeTab = MutableStateFlow(UiTab.EXECUTION_FEED)
  val activeTab: StateFlow<UiTab> = _activeTab.asStateFlow()

  private val _providerType = MutableStateFlow(ProviderType.NVIDIA_NIM)
  val providerType: StateFlow<ProviderType> = _providerType.asStateFlow()

  private val _nvidiaApiKey = MutableStateFlow(getInitialNvidiaApiKey())
  val nvidiaApiKey: StateFlow<String> = _nvidiaApiKey.asStateFlow()

  private val _nvidiaModel = MutableStateFlow(NvidiaNimConfig.DEFAULT_MODEL)
  val nvidiaModel: StateFlow<String> = _nvidiaModel.asStateFlow()

  private val _nvidiaBaseUrl = MutableStateFlow(NvidiaNimConfig.DEFAULT_BASE_URL)
  val nvidiaBaseUrl: StateFlow<String> = _nvidiaBaseUrl.asStateFlow()

  private val _nvidiaTemperature = MutableStateFlow(0.2)
  val nvidiaTemperature: StateFlow<Double> = _nvidiaTemperature.asStateFlow()

  private val _nvidiaMaxTokens = MutableStateFlow(4096)
  val nvidiaMaxTokens: StateFlow<Int> = _nvidiaMaxTokens.asStateFlow()

  private val _geminiApiKey = MutableStateFlow(getInitialGeminiApiKey())
  val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

  private val _selectedArtifactForPreview = MutableStateFlow<Artifact?>(null)
  val selectedArtifactForPreview: StateFlow<Artifact?> = _selectedArtifactForPreview.asStateFlow()

  private val _previewContent = MutableStateFlow<String?>(null)
  val previewContent: StateFlow<String?> = _previewContent.asStateFlow()

  private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
  val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

  private fun getInitialNvidiaApiKey(): String {
    return try {
      val field = com.example.BuildConfig::class.java.getField("NVIDIA_API_KEY")
      val rawKey = field.get(null) as? String ?: ""
      if (rawKey.isNotBlank() && rawKey != "MY_NVIDIA_API_KEY" && rawKey != "\"MY_NVIDIA_API_KEY\"") {
        rawKey.trim()
      } else {
        "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"
      }
    } catch (_: Exception) {
      "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"
    }
  }

  private fun getInitialGeminiApiKey(): String {
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
      title = "Financial Report",
      prompt = "Analyze data.csv and generate an executive report.md with product revenues and total metrics.",
      expectedArtifact = "report.md"
    ),
    PromptPreset(
      title = "Python Word Doc",
      prompt = "Transform sys_info.txt into a structured Word document (sys_info.docx) using Python 3 and python-docx.",
      expectedArtifact = "sys_info.docx"
    ),
    PromptPreset(
      title = "Project Summary",
      prompt = "Inspect workspace files and synthesize a structured project_summary.json document.",
      expectedArtifact = "project_summary.json"
    ),
    PromptPreset(
      title = "Shell Diagnostics",
      prompt = "Run environment diagnostics via terminal and record system status in sys_info.txt.",
      expectedArtifact = "sys_info.txt"
    ),
    PromptPreset(
      title = "Data Validator",
      prompt = "Create a validation script check_data.sh to test for missing CSV fields and run it.",
      expectedArtifact = "check_data.sh"
    )
  )

  fun selectTab(tab: UiTab) {
    _activeTab.value = tab
  }

  fun setProviderType(type: ProviderType) {
    _providerType.value = type
    when (type) {
      ProviderType.SANDBOX_ENGINE -> {
        engine.setProvider(sandboxProvider)
      }
      ProviderType.NVIDIA_NIM -> {
        applyNvidiaConfig()
        engine.setProvider(nvidiaProvider)
      }
      ProviderType.GEMINI_LIVE_API -> {
        geminiProvider.setApiKey(_geminiApiKey.value)
        engine.setProvider(geminiProvider)
      }
    }
  }

  fun updateNvidiaConfig(
    apiKey: String? = null,
    model: String? = null,
    baseUrl: String? = null,
    temperature: Double? = null,
    maxTokens: Int? = null
  ) {
    apiKey?.let { _nvidiaApiKey.value = it.trim() }
    model?.let { _nvidiaModel.value = it.trim() }
    baseUrl?.let { _nvidiaBaseUrl.value = it.trim() }
    temperature?.let { _nvidiaTemperature.value = it }
    maxTokens?.let { _nvidiaMaxTokens.value = it }

    applyNvidiaConfig()
    if (_providerType.value == ProviderType.NVIDIA_NIM) {
      engine.setProvider(nvidiaProvider)
    }
  }

  private fun applyNvidiaConfig() {
    nvidiaProvider.setApiKey(_nvidiaApiKey.value)
    nvidiaProvider.setModel(_nvidiaModel.value)
    nvidiaProvider.setBaseUrl(_nvidiaBaseUrl.value)
    nvidiaProvider.updateConfig {
      it.copy(
        temperature = _nvidiaTemperature.value,
        maxTokens = _nvidiaMaxTokens.value
      )
    }
  }

  fun testNvidiaConnection() {
    viewModelScope.launch {
      _connectionStatus.value = ConnectionStatus.Testing
      applyNvidiaConfig()
      val result = nvidiaProvider.fetchLiveModels()
      if (result.isSuccess) {
        val models = result.getOrNull() ?: emptyList()
        _connectionStatus.value = ConnectionStatus.Success(
          message = "Connected to NVIDIA NIM API successfully!",
          modelCount = models.size
        )
      } else {
        val errorMsg = result.exceptionOrNull()?.message ?: "Unknown connection failure."
        _connectionStatus.value = ConnectionStatus.Error(errorMsg)
      }
    }
  }

  fun resetConnectionStatus() {
    _connectionStatus.value = ConnectionStatus.Idle
  }

  fun retryWithDefaultModel() {
    updateNvidiaConfig(model = NvidiaNimConfig.DEFAULT_MODEL)
    val lastIntent = state.value.currentAction ?: presets[0].prompt
    submitTask(lastIntent)
  }

  fun getExecutionTranscript(): String {
    val currentState = state.value
    return buildString {
      appendLine("=== AGENT KERNEL EXECUTION TRANSCRIPT ===")
      appendLine("Status: ${currentState.status}")
      appendLine("Current Provider: ${_providerType.value}")
      if (_providerType.value == ProviderType.NVIDIA_NIM) {
        appendLine("NVIDIA Model: ${_nvidiaModel.value}")
      }
      appendLine("Workspace: ${engine.workspace.baseDir.absolutePath}")
      appendLine()
      appendLine("=== EXECUTION STEPS (${currentState.executionFeed.size}) ===")
      currentState.executionFeed.forEachIndexed { i, step ->
        appendLine("[Step ${step.stepNumber}] ${step.type}: ${step.title}")
        if (step.content.isNotBlank()) {
          appendLine("Content: ${step.content}")
        }
        if (!step.stdout.isNullOrBlank()) {
          appendLine("STDOUT:\n${step.stdout}")
        }
        if (!step.stderr.isNullOrBlank()) {
          appendLine("STDERR:\n${step.stderr}")
        }
        if (step.durationMs != null) {
          appendLine("Duration: ${step.durationMs}ms | Exit: ${step.exitCode ?: 0}")
        }
        appendLine("-".repeat(40))
      }
      if (currentState.error != null) {
        appendLine()
        appendLine("=== ERROR ===")
        appendLine(currentState.error)
      }
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

  companion object {
    private fun resolveDefaultApplication(): Application {
      return try {
        val appProviderClass = Class.forName("androidx.test.core.app.ApplicationProvider")
        val getAppContextMethod = appProviderClass.getMethod("getApplicationContext")
        (getAppContextMethod.invoke(null) as? Application) ?: Application()
      } catch (_: Throwable) {
        try {
          val activityThread = Class.forName("android.app.ActivityThread")
          val getApp = activityThread.getMethod("currentApplication")
          (getApp.invoke(null) as? Application) ?: Application()
        } catch (_: Throwable) {
          Application()
        }
      }
    }

    private fun resolveWorkspaceDir(app: Application?): File {
      return try {
        val filesDir = app?.filesDir
        if (filesDir != null) {
          File(filesDir, "agent_workspace")
        } else {
          File(System.getProperty("java.io.tmpdir") ?: ".", "agent_workspace")
        }
      } catch (_: Throwable) {
        File(System.getProperty("java.io.tmpdir") ?: ".", "agent_workspace")
      }
    }
  }
}
