package com.example.agent.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig
import com.alibaba.opensandbox.sandbox.config.SandboxBackendType
import com.example.agent.core.AgentEngine
import com.example.agent.core.AgentState
import com.example.agent.core.AgentStatus
import com.example.agent.core.Artifact
import com.example.agent.core.ArtifactDownloader
import com.example.agent.core.AutonomousSandboxProvider
import com.example.agent.core.E2BSandboxProvider
import com.example.agent.core.GeminiLLMProvider
import com.example.agent.core.NvidiaModelEntry
import com.example.agent.core.NvidiaModelRegistry
import com.example.agent.core.NvidiaNimConfig
import com.example.agent.core.NvidiaNimModels
import com.example.agent.core.NvidiaNimProvider
import com.example.agent.core.FileExplorerItem
import com.example.agent.core.PathSecurity
import com.example.agent.core.SandboxMode
import com.example.agent.core.SecureCredentials
import com.example.agent.core.StepType
import com.example.agent.core.TaskRecord
import com.example.agent.docs.DocumentIntelligenceService
import com.example.agent.docs.DocumentUiItem
import com.example.agent.docs.EmbeddingProvider
import com.example.agent.docs.EmbeddingProviders
import com.example.agent.service.AgentExecutionService
import com.example.agent.service.AgentServiceBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import com.example.agent.core.ApprovalPolicy
import com.example.agent.mcp.McpClient
import com.example.agent.mcp.McpLogMessage
import com.example.agent.mcp.McpManager
import com.example.agent.mcp.McpPromptDefinition
import com.example.agent.mcp.McpResourceDefinition
import com.example.agent.mcp.McpServerConfig
import com.example.agent.mcp.McpToolDefinition
import java.io.File
import java.util.Locale

enum class ProviderType {
  NVIDIA_NIM,
  SANDBOX_ENGINE,
  GEMINI_LIVE_API
}

enum class UiTab(val label: String) {
  EXECUTION_FEED("FEED"),
  ARTIFACTS("FILES"),
  TASK_HISTORY("LOGS"),
  FIVE_STAGES("STAGES"),
  ABOUT_ARAGON("ABOUT")
}

enum class FeedFilter(val label: String) {
  ALL("ALL"),
  TOOLS("TOOLS"),
  REASONING("THINKING"),
  OUTPUTS("OUTPUTS"),
  ERRORS("ERRORS")
}

data class RunTelemetry(
  val totalDurationMs: Long = 0L,
  val totalSteps: Int = 0,
  val toolCallsCount: Int = 0,
  val toolBreakdown: Map<String, Int> = emptyMap(),
  val estimatedPromptTokens: Int = 0,
  val estimatedCompletionTokens: Int = 0,
  val estimatedTotalTokens: Int = 0,
  val estimatedCostUsd: Double = 0.0,
  val errorCount: Int = 0,
  val deliverableCount: Int = 0
)

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

  private val prefs = application.getSharedPreferences("aragon_agent_prefs", Context.MODE_PRIVATE)

  private val initialNvidiaKeys = getInitialNvidiaApiKeys()
  private val initialNvidiaKey = initialNvidiaKeys.firstOrNull() ?: ""
  private val hasNvidiaKey = initialNvidiaKeys.isNotEmpty()

  private val sandboxProvider = AutonomousSandboxProvider()
  private val nvidiaProvider = NvidiaNimProvider(
    NvidiaNimConfig(
      apiKey = initialNvidiaKey,
      apiKeys = initialNvidiaKeys,
      model = getInitialNvidiaModel(),
      baseUrl = getInitialNvidiaBaseUrl(),
      temperature = getInitialNvidiaTemperature(),
      maxTokens = getInitialNvidiaMaxTokens()
    )
  )
  private val geminiProvider = GeminiLLMProvider(
    apiKey = getInitialGeminiApiKey()
  )

  private val initialE2bApiKey = getInitialE2bApiKey()
  private val initialE2bTemplate = prefs.getString("e2b_template", "base")?.trim() ?: "base"
  private val initialE2bApiUrl = prefs.getString("e2b_api_url", "https://api.e2b.app")?.trim() ?: "https://api.e2b.app"
  private val initialSandboxMode: SandboxMode = SandboxMode.E2B_CLOUD

  private val initialSandboxConfig = ConnectionConfig(
    endpoint = initialE2bApiUrl.ifBlank { "https://api.e2b.app" },
    apiKey = initialE2bApiKey,
    backendType = SandboxBackendType.E2B_CLOUD,
    e2bTemplate = initialE2bTemplate.ifBlank { "base" }
  )

  private val initialProviderType = getInitialProviderType()

  private var engineRef: AgentEngine? = null

  private val documentService = DocumentIntelligenceService(
    appContext = application,
    workspaceDir = workspaceDir,
    sandboxProviderSupplier = { engineRef?.sandboxProvider },
    sessionIdSupplier = { engineRef?.state?.value?.sessionId ?: "" },
    embeddingProviderSupplier = { buildEmbeddingProvider() },
    embeddingAvailabilitySupplier = { describeEmbeddingAvailability() },
    geminiApiKeySupplier = { _geminiApiKey.value.trim() },
    activeVisionSupplier = { getActiveVisionConfig() },
    nvidiaApiKeysSupplier = { _nvidiaApiKeys.value },
    nvidiaBaseUrlSupplier = { _nvidiaBaseUrl.value.trim().ifBlank { NvidiaNimConfig.DEFAULT_BASE_URL } }
  )

  private fun buildEmbeddingProvider(): EmbeddingProvider? = EmbeddingProviders.create(
    nimBaseUrl = _nvidiaBaseUrl.value.trim().ifBlank { NvidiaNimConfig.DEFAULT_BASE_URL },
    nimApiKeys = _nvidiaApiKeys.value,
    geminiApiKey = _geminiApiKey.value
  )

  private fun describeEmbeddingAvailability(): String = EmbeddingProviders.describeAvailability(
    nimApiKeys = _nvidiaApiKeys.value,
    geminiApiKey = _geminiApiKey.value
  )

  private val _sandboxUploadToast = MutableStateFlow<String?>(null)
  val sandboxUploadToast: StateFlow<String?> = _sandboxUploadToast.asStateFlow()

  fun dismissSandboxToast() {
    _sandboxUploadToast.value = null
  }

  private val engine: AgentEngine = AgentEngine(
    workspaceDir = workspaceDir,
    initialProvider = when (initialProviderType) {
      ProviderType.NVIDIA_NIM -> if (hasNvidiaKey) nvidiaProvider else sandboxProvider
      ProviderType.SANDBOX_ENGINE -> sandboxProvider
      ProviderType.GEMINI_LIVE_API -> geminiProvider
    },
    initialSandboxConfig = initialSandboxConfig,
    documentService = documentService
  ).also {
    engineRef = it
    it.onFilesSyncedToSandbox = { count, _ ->
      viewModelScope.launch {
        _sandboxUploadToast.value = "Synced $count workspace file${if (count > 1) "s" else ""} to E2B Cloud Sandbox"
      }
    }
  }

  // First-Class Model Context Protocol (MCP) Subsystem
  val mcpManager = McpManager(
    appContext = application,
    workspaceDir = workspaceDir,
    toolRegistry = engine.registry
  )

  val mcpServers: StateFlow<List<McpServerConfig>> = mcpManager.servers
  val mcpDiscoveredTools: StateFlow<List<McpToolDefinition>> = mcpManager.discoveredTools
  val mcpDiscoveredResources: StateFlow<List<McpResourceDefinition>> = mcpManager.discoveredResources
  val mcpDiscoveredPrompts: StateFlow<List<McpPromptDefinition>> = mcpManager.discoveredPrompts
  val mcpLogs: StateFlow<List<McpLogMessage>> = mcpManager.logs
  val isRefreshingMcp: StateFlow<Boolean> = mcpManager.isRefreshing

  fun refreshMcpServers() {
    viewModelScope.launch {
      mcpManager.refreshAll()
    }
  }

  fun upsertMcpServer(config: McpServerConfig) {
    viewModelScope.launch {
      mcpManager.upsertServer(config)
    }
  }

  fun removeMcpServer(serverId: String) {
    viewModelScope.launch {
      mcpManager.removeServer(serverId)
    }
  }

  fun toggleMcpServer(serverId: String, enabled: Boolean) {
    viewModelScope.launch {
      mcpManager.toggleServer(serverId, enabled)
    }
  }

  fun getMcpClientForServer(serverId: String): McpClient? = mcpManager.getClient(serverId)

  suspend fun pingMcpServer(serverId: String): Result<Long> = mcpManager.pingServer(serverId)

  fun reconnectMcpServer(serverId: String) {
    viewModelScope.launch {
      mcpManager.reconnectServer(serverId)
    }
  }

  fun clearMcpLogs() {
    mcpManager.clearLogs()
  }

  suspend fun callMcpTool(serverId: String, toolName: String, args: Map<String, Any?>): Result<com.example.agent.mcp.McpToolCallResult> {
    val client = mcpManager.getClient(serverId)
      ?: return Result.failure(IllegalStateException("Server '$serverId' not active or connected"))
    return client.callTool(toolName, args)
  }

  suspend fun readMcpResource(serverId: String, uri: String): Result<com.example.agent.mcp.McpResourceContent> {
    val client = mcpManager.getClient(serverId)
      ?: return Result.failure(IllegalStateException("Server '$serverId' not active or connected"))
    return client.readResource(uri)
  }

  // Workspace File Explorer State
  private val _currentExplorerPath = MutableStateFlow("")
  val currentExplorerPath: StateFlow<String> = _currentExplorerPath.asStateFlow()

  private val _explorerSearchQuery = MutableStateFlow("")
  val explorerSearchQuery: StateFlow<String> = _explorerSearchQuery.asStateFlow()

  private val _explorerItems = MutableStateFlow<List<FileExplorerItem>>(emptyList())
  val explorerItems: StateFlow<List<FileExplorerItem>> = _explorerItems.asStateFlow()

  private val _approvalPolicy = MutableStateFlow(
    try {
      ApprovalPolicy.valueOf(prefs.getString("aragon_approval_policy", ApprovalPolicy.AUTO_APPROVE.name) ?: ApprovalPolicy.AUTO_APPROVE.name)
    } catch (_: Exception) {
      ApprovalPolicy.AUTO_APPROVE
    }
  )
  val approvalPolicy: StateFlow<ApprovalPolicy> = _approvalPolicy.asStateFlow()

  fun setApprovalPolicy(policy: ApprovalPolicy) {
    _approvalPolicy.value = policy
    engine.approvalManager.policy = policy
    prefs.edit().putString("aragon_approval_policy", policy.name).apply()
  }

  val state: StateFlow<AgentState> = engine.state

  private val _activeTab = MutableStateFlow(UiTab.EXECUTION_FEED)
  val activeTab: StateFlow<UiTab> = _activeTab.asStateFlow()

  private val _providerType = MutableStateFlow(initialProviderType)
  val providerType: StateFlow<ProviderType> = _providerType.asStateFlow()

  private val _nvidiaApiKeys = MutableStateFlow<List<String>>(initialNvidiaKeys)
  val nvidiaApiKeys: StateFlow<List<String>> = _nvidiaApiKeys.asStateFlow()

  private val _nvidiaApiKey = MutableStateFlow(initialNvidiaKey)
  val nvidiaApiKey: StateFlow<String> = _nvidiaApiKey.asStateFlow()

  private val _nvidiaModel = MutableStateFlow(getInitialNvidiaModel())
  val nvidiaModel: StateFlow<String> = _nvidiaModel.asStateFlow()

  private val _nvidiaModels = MutableStateFlow<List<NvidiaModelEntry>>(NvidiaModelRegistry.getActiveModels())
  val nvidiaModels: StateFlow<List<NvidiaModelEntry>> = _nvidiaModels.asStateFlow()

  private val _isSyncingNvidiaModels = MutableStateFlow(false)
  val isSyncingNvidiaModels: StateFlow<Boolean> = _isSyncingNvidiaModels.asStateFlow()

  val nvidiaModelSyncMessage: StateFlow<String> = NvidiaModelRegistry.syncStatusMessage

  private val _deprecatedModelAlert = MutableStateFlow<String?>(null)
  val deprecatedModelAlert: StateFlow<String?> = _deprecatedModelAlert.asStateFlow()

  fun dismissDeprecatedModelAlert() {
    _deprecatedModelAlert.value = null
  }

  private val _nvidiaBaseUrl = MutableStateFlow(getInitialNvidiaBaseUrl())
  val nvidiaBaseUrl: StateFlow<String> = _nvidiaBaseUrl.asStateFlow()

  private val _nvidiaTemperature = MutableStateFlow(getInitialNvidiaTemperature())
  val nvidiaTemperature: StateFlow<Double> = _nvidiaTemperature.asStateFlow()

  private val _nvidiaMaxTokens = MutableStateFlow(getInitialNvidiaMaxTokens())
  val nvidiaMaxTokens: StateFlow<Int> = _nvidiaMaxTokens.asStateFlow()

  private val _geminiApiKey = MutableStateFlow(getInitialGeminiApiKey())
  val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

  private val _selectedArtifactForPreview = MutableStateFlow<Artifact?>(null)
  val selectedArtifactForPreview: StateFlow<Artifact?> = _selectedArtifactForPreview.asStateFlow()

  private val _previewContent = MutableStateFlow<String?>(null)
  val previewContent: StateFlow<String?> = _previewContent.asStateFlow()

  private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
  val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

  private val _sandboxMode = MutableStateFlow(initialSandboxMode)
  val sandboxMode: StateFlow<SandboxMode> = _sandboxMode.asStateFlow()

  private val _e2bApiKey = MutableStateFlow(initialE2bApiKey)
  val e2bApiKey: StateFlow<String> = _e2bApiKey.asStateFlow()

  private val _e2bTemplate = MutableStateFlow(initialE2bTemplate)
  val e2bTemplate: StateFlow<String> = _e2bTemplate.asStateFlow()

  private val _e2bApiUrl = MutableStateFlow(initialE2bApiUrl)
  val e2bApiUrl: StateFlow<String> = _e2bApiUrl.asStateFlow()

  private val _sandboxConnectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
  val sandboxConnectionStatus: StateFlow<ConnectionStatus> = _sandboxConnectionStatus.asStateFlow()

  // Document Intelligence State
  val documents: StateFlow<List<DocumentUiItem>> = documentService.documents
  val documentScope: StateFlow<Set<String>> = documentService.scope

  private val _ocrEnabled = MutableStateFlow(documentService.ocrEnabled())
  val ocrEnabled: StateFlow<Boolean> = _ocrEnabled.asStateFlow()

  private val _autoCloudOcr = MutableStateFlow(documentService.autoCloudOcrEnabled())
  val autoCloudOcr: StateFlow<Boolean> = _autoCloudOcr.asStateFlow()

  private val _documentMessages = MutableStateFlow<List<String>>(emptyList())
  val documentMessages: StateFlow<List<String>> = _documentMessages.asStateFlow()

  init {
    try {
      ArtifactDownloader.defaultWorkspaceDir = engine.workspaceDir
      engine.mcpManager = mcpManager
      AgentServiceBridge.engine = engine
      engine.approvalManager.policy = _approvalPolicy.value
      loadExplorerDirectory("")
      viewModelScope.launch {
        try {
          engine.state
            .map { Triple(it.status, it.artifacts.size, it.trackedDeliverables.size) }
            .distinctUntilChanged()
            .collect {
              loadExplorerDirectory(_currentExplorerPath.value)
            }
        } catch (_: Throwable) {}
      }
      viewModelScope.launch {
        try {
          documentService.initialize()
        } catch (_: Throwable) {}
      }
      try {
        mcpManager.initialize()
      } catch (_: Throwable) {}
      viewModelScope.launch(Dispatchers.IO) {
        try {
          NvidiaModelRegistry.loadCache(application)
          _nvidiaModels.value = NvidiaModelRegistry.getActiveModels()
          val currentModel = _nvidiaModel.value
          if (NvidiaModelRegistry.isModelDeprecated(currentModel)) {
            val replacement = NvidiaModelRegistry.suggestActiveReplacement(currentModel)
            _nvidiaModel.value = replacement
            prefs.edit().putString("nvidia_model", replacement).apply()
            nvidiaProvider.setModel(replacement)
            _deprecatedModelAlert.value = "Selected model '$currentModel' was deprecated by NVIDIA. Switched to '$replacement'."
          }
          syncNvidiaModels(force = false)
        } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}
  }

  fun isVisionModel(modelId: String): Boolean =
    NvidiaModelRegistry.isVisionModel(modelId)

  fun getActiveVisionConfig(): com.example.agent.docs.ActiveVisionModelConfig {
    return when (_providerType.value) {
      ProviderType.GEMINI_LIVE_API -> {
        val key = _geminiApiKey.value.trim()
        com.example.agent.docs.ActiveVisionModelConfig(
          hasVision = key.isNotBlank() && key != "MY_GEMINI_API_KEY",
          providerType = "gemini",
          modelName = "gemini-2.5-flash",
          apiKey = key
        )
      }
      ProviderType.NVIDIA_NIM -> {
        val model = _nvidiaModel.value.trim()
        val hasVision = isVisionModel(model)
        val key = _nvidiaApiKey.value.trim().ifBlank { _nvidiaApiKeys.value.firstOrNull()?.trim() ?: "" }
        val baseUrl = _nvidiaBaseUrl.value.trim().ifBlank { NvidiaNimConfig.DEFAULT_BASE_URL }
        com.example.agent.docs.ActiveVisionModelConfig(
          hasVision = hasVision && key.isNotBlank() && key != "MY_NVIDIA_API_KEY",
          providerType = "nvidia_nim",
          modelName = model,
          apiKey = key,
          apiKeys = _nvidiaApiKeys.value,
          baseUrl = baseUrl
        )
      }
      ProviderType.SANDBOX_ENGINE -> {
        com.example.agent.docs.ActiveVisionModelConfig(
          hasVision = false,
          providerType = "sandbox",
          modelName = "autonomous",
          apiKey = ""
        )
      }
    }
  }


  fun onDocumentsPicked(uris: List<Uri>) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
      val outcomes = documentService.importUris(uris)
      _documentMessages.value = outcomes.map { outcome ->
        if (outcome.ok) "${outcome.displayName}: ${outcome.message}"
        else "${outcome.displayName}: FAILED - ${outcome.message}"
      }
      refreshExplorer()
    }
  }

  fun onFolderPicked(treeUri: Uri) {
    viewModelScope.launch {
      val outcomes = documentService.importTreeUri(treeUri)
      _documentMessages.value = outcomes.map { outcome ->
        if (outcome.ok) "${outcome.displayName}: ${outcome.message}"
        else "${outcome.displayName}: FAILED - ${outcome.message}"
      }
      refreshExplorer()
    }
  }

  fun clearDocumentMessages() {
    _documentMessages.value = emptyList()
  }

  fun cancelDocumentJob(docId: String) {
    documentService.cancelJob(docId)
  }

  fun deleteDocument(docId: String) {
    viewModelScope.launch {
      val doc = documents.value.firstOrNull { it.id == docId }
      documentService.deleteDocument(docId)
      _documentMessages.value = listOf("Deleted ${doc?.displayName ?: docId}")
    }
  }

  fun reindexDocument(docId: String) {
    documentService.reindexDocument(docId)
  }

  fun setDocumentInScope(docId: String, inScope: Boolean) {
    documentService.setInScope(docId, inScope)
  }

  fun clearDocumentScope() {
    documentService.clearScope()
  }

  fun setOcrEnabled(enabled: Boolean) {
    _ocrEnabled.value = enabled
    documentService.setOcrEnabled(enabled)
  }

  fun setAutoCloudOcr(enabled: Boolean) {
    _autoCloudOcr.value = enabled
    documentService.setAutoCloudOcr(enabled)
  }

  fun isCloudOcrAvailable(): Boolean = documentService.isCloudOcrAvailable()

  fun embeddingAvailabilityLabel(): String = documentService.embeddingAvailabilityLabel()

  private fun getInitialNvidiaApiKeys(): List<String> {
    val loaded = mutableListOf<String>()
    for (i in 0 until 15) {
      val key = SecureCredentials.read(prefs, "nvidia_api_key_$i")
      if (!key.isNullOrBlank()) {
        loaded.add(key)
      }
    }
    if (loaded.isEmpty()) {
      val single = SecureCredentials.read(prefs, "nvidia_api_key")
      if (!single.isNullOrBlank()) {
        loaded.add(single)
      } else {
        try {
          val rawKey = com.example.BuildConfig.NVIDIA_API_KEY
          if (rawKey.isNotBlank() && rawKey != "MY_NVIDIA_API_KEY" && rawKey != "\"MY_NVIDIA_API_KEY\"") {
            loaded.add(rawKey.trim())
          }
        } catch (_: Exception) {}
      }
    }
    return loaded.take(15)
  }

  private fun getInitialNvidiaApiKey(): String {
    return getInitialNvidiaApiKeys().firstOrNull() ?: ""
  }

  private fun getInitialGeminiApiKey(): String {
    val saved = SecureCredentials.read(prefs, "gemini_api_key")
    if (!saved.isNullOrBlank()) return saved
    return try {
      val field = com.example.BuildConfig::class.java.getField("GEMINI_API_KEY")
      val rawKey = field.get(null) as? String ?: ""
      if (rawKey.isNotBlank() && rawKey != "MY_GEMINI_API_KEY" && rawKey != "\"MY_GEMINI_API_KEY\"") {
        rawKey.trim()
      } else {
        ""
      }
    } catch (_: Exception) {
      ""
    }
  }

  private fun getInitialE2bApiKey(): String {
    val saved = SecureCredentials.read(prefs, "e2b_api_key")
    if (!saved.isNullOrBlank()) return saved
    return try {
      val field = com.example.BuildConfig::class.java.getField("E2B_API_KEY")
      val rawKey = field.get(null) as? String ?: ""
      if (rawKey.isNotBlank() && rawKey != "MY_E2B_API_KEY" && rawKey != "\"MY_E2B_API_KEY\"") {
        rawKey.trim()
      } else {
        ""
      }
    } catch (_: Exception) {
      ""
    }
  }


  private fun getInitialProviderType(): ProviderType {
    val saved = prefs.getString("provider_type", null)
    if (!saved.isNullOrBlank()) {
      try {
        return ProviderType.valueOf(saved)
      } catch (_: Exception) {}
    }
    val hasGeminiKey = getInitialGeminiApiKey().isNotBlank()
    return when {
      hasNvidiaKey -> ProviderType.NVIDIA_NIM
      hasGeminiKey -> ProviderType.GEMINI_LIVE_API
      else -> ProviderType.SANDBOX_ENGINE
    }
  }

  private fun getInitialNvidiaModel(): String {
    val saved = prefs.getString("nvidia_model", null)?.trim()?.ifBlank { null }
      ?: NvidiaNimConfig.DEFAULT_MODEL
    return if (NvidiaModelRegistry.isModelDeprecated(saved)) {
      NvidiaModelRegistry.suggestActiveReplacement(saved)
    } else {
      saved
    }
  }

  private fun getInitialNvidiaBaseUrl(): String {
    return prefs.getString("nvidia_base_url", null)?.trim()?.ifBlank { null }
      ?: NvidiaNimConfig.DEFAULT_BASE_URL
  }

  private fun getInitialNvidiaTemperature(): Double {
    val saved = prefs.getFloat("nvidia_temp", -1f)
    return if (saved >= 0f) saved.toDouble() else 0.2
  }

  private fun getInitialNvidiaMaxTokens(): Int {
    val saved = prefs.getInt("nvidia_max_tokens", -1)
    return if (saved > 0) saved else 4096
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

  val followUpPresets = listOf(
    PromptPreset(
      title = "+ Add Unit Tests",
      prompt = "Add comprehensive unit tests and automated assertions to validate the deliverables in workspace.",
      expectedArtifact = null
    ),
    PromptPreset(
      title = "+ Optimize & Refactor",
      prompt = "Refactor the existing deliverables for maximum performance, cleaner structure, and maintainability.",
      expectedArtifact = null
    ),
    PromptPreset(
      title = "+ Documentation & Guide",
      prompt = "Add comprehensive documentation, docstrings, and a guide explaining the newly generated deliverables.",
      expectedArtifact = null
    ),
    PromptPreset(
      title = "+ Benchmark Speed",
      prompt = "Run performance diagnostics and benchmark the newly created deliverables, reporting execution metrics.",
      expectedArtifact = null
    )
  )

  fun selectTab(tab: UiTab) {
    _activeTab.value = tab
    if (tab == UiTab.ARTIFACTS) {
      refreshExplorer()
    }
  }

  fun setProviderType(type: ProviderType) {
    _providerType.value = type
    prefs.edit().putString("provider_type", type.name).apply()
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
    apiKeys: List<String>? = null,
    model: String? = null,
    baseUrl: String? = null,
    temperature: Double? = null,
    maxTokens: Int? = null
  ) {
    val editor = prefs.edit()
    val updatedKeys = if (apiKeys != null) {
      apiKeys.map { it.trim() }.filter { it.isNotBlank() }.take(15)
    } else if (apiKey != null) {
      val clean = apiKey.trim()
      if (clean.isNotBlank()) listOf(clean) else emptyList()
    } else {
      _nvidiaApiKeys.value
    }

    _nvidiaApiKeys.value = updatedKeys
    val primary = updatedKeys.firstOrNull() ?: ""
    _nvidiaApiKey.value = primary
    SecureCredentials.write(editor, "nvidia_api_key", primary)

    for (i in 0 until 15) {
      val k = updatedKeys.getOrNull(i)
      SecureCredentials.write(editor, "nvidia_api_key_$i", k)
    }

    model?.let {
      val clean = it.trim()
      if (NvidiaModelRegistry.isModelDeprecated(clean)) {
        val replacement = NvidiaModelRegistry.suggestActiveReplacement(clean)
        _nvidiaModel.value = replacement
        editor.putString("nvidia_model", replacement)
        _deprecatedModelAlert.value = "Selected model '$clean' is deprecated by NVIDIA. Switched to '$replacement'."
      } else {
        _nvidiaModel.value = clean
        editor.putString("nvidia_model", clean)
      }
    }
    baseUrl?.let {
      val clean = it.trim()
      _nvidiaBaseUrl.value = clean
      editor.putString("nvidia_base_url", clean)
    }
    temperature?.let {
      _nvidiaTemperature.value = it
      editor.putFloat("nvidia_temp", it.toFloat())
    }
    maxTokens?.let {
      _nvidiaMaxTokens.value = it
      editor.putInt("nvidia_max_tokens", it)
    }
    editor.apply()

    applyNvidiaConfig()
    if (_providerType.value == ProviderType.NVIDIA_NIM) {
      engine.setProvider(nvidiaProvider)
    }
  }

  private fun applyNvidiaConfig() {
    nvidiaProvider.setApiKeys(_nvidiaApiKeys.value)
    nvidiaProvider.setModel(_nvidiaModel.value)
    nvidiaProvider.setBaseUrl(_nvidiaBaseUrl.value)
    nvidiaProvider.updateConfig {
      it.copy(
        temperature = _nvidiaTemperature.value,
        maxTokens = _nvidiaMaxTokens.value
      )
    }
  }

  /**
   * Secret-free key pool health summary for the Settings UI
   * (e.g. "3 keys · 1 cooling (28s) · 1 invalid"). Never exposes key material.
   */
  fun nvidiaKeyPoolSummary(): String = nvidiaProvider.keyPoolSummary()

  fun syncNvidiaModels(force: Boolean = true) {
    viewModelScope.launch {
      _isSyncingNvidiaModels.value = true
      withContext(Dispatchers.IO) {
        try {
          val baseUrl = _nvidiaBaseUrl.value.trim().ifBlank { NvidiaNimConfig.DEFAULT_BASE_URL }
          val key = _nvidiaApiKeys.value.firstOrNull() ?: _nvidiaApiKey.value
          val res = NvidiaModelRegistry.sync(baseUrl, key, getApplication<Application>())
          if (res.isSuccess) {
            val liveModels = NvidiaModelRegistry.getActiveModels()
            _nvidiaModels.value = liveModels
            val current = _nvidiaModel.value
            if (NvidiaModelRegistry.isModelDeprecated(current)) {
              val replacement = NvidiaModelRegistry.suggestActiveReplacement(current)
              _nvidiaModel.value = replacement
              prefs.edit().putString("nvidia_model", replacement).apply()
              nvidiaProvider.setModel(replacement)
              _deprecatedModelAlert.value = "Selected model '$current' was deprecated by NVIDIA. Upgraded to '$replacement'."
            }
          }
        } catch (_: Exception) {
        } finally {
          _isSyncingNvidiaModels.value = false
        }
      }
    }
  }

  fun testNvidiaConnection() {
    viewModelScope.launch {
      _connectionStatus.value = ConnectionStatus.Testing
      withContext(Dispatchers.IO) {
        try {
          applyNvidiaConfig()
          val result = nvidiaProvider.fetchLiveModels()
          if (result.isSuccess) {
            val models = result.getOrNull() ?: emptyList()
            _nvidiaModels.value = NvidiaModelRegistry.getActiveModels()
            _connectionStatus.value = ConnectionStatus.Success(
              message = "Connected to NVIDIA NIM API successfully! (${models.size} live models synchronized)",
              modelCount = models.size
            )
          } else {
            val errorMsg = result.exceptionOrNull()?.message ?: "Unknown connection failure."
            _connectionStatus.value = ConnectionStatus.Error(errorMsg)
          }
        } catch (t: Throwable) {
          _connectionStatus.value = ConnectionStatus.Error("Connection error: ${t.message ?: t.javaClass.simpleName}")
        }
      }
    }
  }

  fun resetConnectionStatus() {
    _connectionStatus.value = ConnectionStatus.Idle
  }

  fun updateSandboxConfig(
    mode: SandboxMode,
    e2bApiKey: String? = null,
    e2bTemplate: String = "base",
    e2bApiUrl: String = "https://api.e2b.app"
  ) {
    val cleanE2bKey = e2bApiKey?.trim() ?: ""
    val cleanTemplate = e2bTemplate.trim().ifBlank { "base" }
    val cleanE2bUrl = e2bApiUrl.trim().ifBlank { "https://api.e2b.app" }

    _sandboxMode.value = mode
    _e2bApiKey.value = cleanE2bKey
    _e2bTemplate.value = cleanTemplate
    _e2bApiUrl.value = cleanE2bUrl

    val editor = prefs.edit()
      .putString("sandbox_mode", mode.name)
      .putString("e2b_template", cleanTemplate)
      .putString("e2b_api_url", cleanE2bUrl)
    SecureCredentials.write(editor, "e2b_api_key", cleanE2bKey)
    editor.apply()

    val config = ConnectionConfig(
      endpoint = cleanE2bUrl,
      apiKey = cleanE2bKey,
      backendType = SandboxBackendType.E2B_CLOUD,
      e2bTemplate = cleanTemplate
    )
    engine.setSandboxConfig(config)
  }

  fun testSandboxConnection(
    overrideE2bKey: String? = null,
    overrideTemplate: String? = null,
    overrideUrl: String? = null
  ) {
    viewModelScope.launch {
      _sandboxConnectionStatus.value = ConnectionStatus.Testing
      withContext(Dispatchers.IO) {
        try {
          val key = (overrideE2bKey ?: _e2bApiKey.value).trim()
          val template = (overrideTemplate ?: _e2bTemplate.value).trim().ifBlank { "base" }
          val url = (overrideUrl ?: _e2bApiUrl.value).trim().ifBlank { "https://api.e2b.app" }

          if (key.isBlank() || key == "MY_E2B_API_KEY") {
            _sandboxConnectionStatus.value = ConnectionStatus.Error(
              "E2B API Key is missing. Please enter your API Key starting with 'e2b_'."
            )
            return@withContext
          }

          val provider = E2BSandboxProvider(
            apiKey = key,
            templateId = template,
            apiUrl = url,
            localWorkspaceDir = workspaceDir
          )
          val result = provider.pingDetailed()
          if (result.isSuccess) {
            _sandboxConnectionStatus.value = ConnectionStatus.Success(
              message = result.message,
              modelCount = result.activeSandboxes.size.coerceAtLeast(1)
            )
          } else {
            _sandboxConnectionStatus.value = ConnectionStatus.Error(result.message)
          }
        } catch (t: Throwable) {
          _sandboxConnectionStatus.value = ConnectionStatus.Error("Sandbox test error: ${t.message ?: t.javaClass.simpleName}")
        }
      }
    }
  }

  fun resetSandboxConnectionStatus() {
    _sandboxConnectionStatus.value = ConnectionStatus.Idle
  }

  fun retryWithDefaultModel() {
    updateNvidiaConfig(model = NvidiaNimConfig.DEFAULT_MODEL)
    val lastIntent = state.value.task?.goal ?: presets[0].prompt
    submitTask(lastIntent)
  }

  fun getExecutionTranscript(): String {
    return try {
      val currentState = state.value
      val feedSnapshot = currentState.executionFeed.toList()
      buildString {
        appendLine("=== AGENT KERNEL EXECUTION TRANSCRIPT ===")
        appendLine("Status: ${currentState.status}")
        appendLine("Current Provider: ${_providerType.value}")
        if (_providerType.value == ProviderType.NVIDIA_NIM) {
          appendLine("NVIDIA Model: ${_nvidiaModel.value}")
        }
        appendLine("Workspace: ${engine.workspace.baseDir.absolutePath}")
        appendLine()
        val topology = com.example.agent.core.EnvironmentTopology.createDefault()
        appendLine(topology.toFormattedHeaderBlock())
        appendLine()
        appendLine("=== EXECUTION STEPS (${feedSnapshot.size}) ===")
        feedSnapshot.forEach { step ->
          appendLine("[Step ${step.stepNumber}] ${step.type} [${step.toolStatus ?: "COMPLETED"}]: ${step.title}")
          if (!step.toolName.isNullOrBlank()) {
            appendLine("Tool: ${step.toolName}${if (step.toolCallId != null) " (ID: ${step.toolCallId})" else ""}")
          }
          if (step.content.isNotBlank()) {
            appendLine("Content/Args:\n${step.content}")
          }
          if (!step.stdout.isNullOrBlank()) {
            val out = if (step.stdout.length > 20_000) step.stdout.take(20_000) + "\n...[TRUNCATED]..." else step.stdout
            appendLine("STDOUT:\n$out")
          }
          if (!step.stderr.isNullOrBlank()) {
            val err = if (step.stderr.length > 20_000) step.stderr.take(20_000) + "\n...[TRUNCATED]..." else step.stderr
            appendLine("STDERR:\n$err")
          }
          if (step.durationMs != null || step.exitCode != null) {
            appendLine("Duration: ${step.durationMs ?: 0}ms | Exit: ${step.exitCode ?: 0}")
          }
          appendLine("-".repeat(40))
        }
        if (currentState.error != null) {
          appendLine()
          appendLine("=== ERROR ===")
          appendLine(currentState.error)
        }
      }
    } catch (e: Throwable) {
      "Error generating transcript: ${e.message}"
    }
  }

  fun computeRunTelemetry(): RunTelemetry {
    val feed = state.value.executionFeed
    var dur = 0L
    var toolCount = 0
    val toolsMap = mutableMapOf<String, Int>()
    var promptChars = (state.value.task?.goal?.length ?: 0) * 2
    var completionChars = 0
    var errors = 0

    feed.forEach { step ->
      dur += (step.durationMs ?: 0L)
      if (step.type == StepType.TOOL_CALL || step.type == StepType.TOOL_EXECUTION) {
        toolCount++
        val name = step.toolName ?: "unknown"
        toolsMap[name] = (toolsMap[name] ?: 0) + 1
      }
      if (step.type == StepType.ERROR || (step.exitCode != null && step.exitCode != 0)) {
        errors++
      }
      completionChars += step.content.length + (step.stdout?.length ?: 0) + (step.stderr?.length ?: 0)
    }

    val promptTokens = (promptChars / 4).coerceAtLeast(35)
    val completionTokens = (completionChars / 4)
    val totalTokens = promptTokens + completionTokens
    val cost = (totalTokens / 1000.0) * 0.00015

    return RunTelemetry(
      totalDurationMs = dur,
      totalSteps = feed.size,
      toolCallsCount = toolCount,
      toolBreakdown = toolsMap,
      estimatedPromptTokens = promptTokens,
      estimatedCompletionTokens = completionTokens,
      estimatedTotalTokens = totalTokens,
      estimatedCostUsd = cost,
      errorCount = errors,
      deliverableCount = state.value.artifacts.size
    )
  }

  fun shareExecutionTranscript(context: Context) {
    try {
      val transcript = getExecutionTranscript()
      val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Aragon Execution Log - ${state.value.task?.goal?.take(30) ?: "Task"}")
        putExtra(Intent.EXTRA_TEXT, transcript)
      }
      val chooser = Intent.createChooser(sendIntent, "Export Aragon Run Transcript")
      chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(chooser)
    } catch (e: Throwable) {
      android.util.Log.e("AgentViewModel", "Failed to share transcript", e)
    }
  }

  fun saveTranscriptToWorkspace(): String {
    return try {
      val transcript = getExecutionTranscript()
      val filename = "run_transcript_${System.currentTimeMillis()}.md"
      val file = File(workspaceDir, filename)
      file.writeText(transcript)
      refreshExplorer()
      filename
    } catch (e: Throwable) {
      android.util.Log.e("AgentViewModel", "Failed to save transcript to workspace", e)
      ""
    }
  }

  fun updateGeminiApiKey(key: String) {
    val clean = key.trim()
    _geminiApiKey.value = clean
    val editor = prefs.edit()
    SecureCredentials.write(editor, "gemini_api_key", clean)
    editor.apply()
    geminiProvider.setApiKey(clean)
    if (_providerType.value == ProviderType.GEMINI_LIVE_API) {
      engine.setProvider(geminiProvider)
    }
  }

  @Volatile
  private var lastSubmitTimeMs = 0L
  @Volatile
  private var lastSubmittedIntent = ""

  fun submitTask(intent: String, expectedArtifact: String? = null) {
    val clean = intent.trim()
    if (clean.isBlank()) return

    val now = System.currentTimeMillis()
    if (clean == lastSubmittedIntent && now - lastSubmitTimeMs < 400L) {
      // Rapid duplicate submission ignored to prevent concurrent session race condition
      return
    }
    lastSubmitTimeMs = now
    lastSubmittedIntent = clean

    _activeTab.value = UiTab.EXECUTION_FEED
    _selectedArtifactForPreview.value = null
    _previewContent.value = null
    _documentMessages.value = emptyList()
    AgentExecutionService.start(getApplication(), clean)

    val isContinuation = state.value.status == AgentStatus.COMPLETED && state.value.messages.isNotEmpty()
    val resumeSessionId = if (isContinuation) state.value.sessionId else null
    engine.submitTask(
      intent = clean,
      expectedArtifact = expectedArtifact,
      resumeSessionId = resumeSessionId,
      continueConversation = isContinuation
    )
  }

  fun resumeTask() {
    val currentStatus = state.value.status
    val isResumable = currentStatus == AgentStatus.CANCELLED ||
      currentStatus == AgentStatus.FAILED ||
      currentStatus == AgentStatus.PARTIAL_COMPLETION
    if (!isResumable) return
    val currentGoal = state.value.task?.goal
    val currentSessionId = state.value.sessionId
    if (!currentGoal.isNullOrBlank() && currentSessionId.isNotBlank()) {
      _activeTab.value = UiTab.EXECUTION_FEED
      AgentExecutionService.start(getApplication(), currentGoal)
      engine.submitTask(currentGoal, state.value.task?.expectedArtifact, resumeSessionId = currentSessionId)
    }
  }

  /**
   * Starts a completely empty, fresh task with an isolated new session and removed artifacts.
   */
  fun startNewTask() {
    AgentExecutionService.stop(getApplication())
    engine.cancel()
    engine.startNewSession()
    _selectedArtifactForPreview.value = null
    _previewContent.value = null
    _activeTab.value = UiTab.EXECUTION_FEED
    documentService.clearScope()
  }

  fun stopExecution() {
    AgentExecutionService.stop(getApplication())
    engine.cancel()
  }

  fun pauseExecution() {
    AgentExecutionService.stop(getApplication())
    engine.pauseTask()
  }

  fun resetWorkspace(cleanFiles: Boolean = true) {
    AgentExecutionService.stop(getApplication())
    engine.reset(cleanFiles)
    _selectedArtifactForPreview.value = null
    _previewContent.value = null
  }

  fun openArtifactPreview(artifact: Artifact) {
    _selectedArtifactForPreview.value = artifact
    viewModelScope.launch {
      val content = withContext(Dispatchers.IO) {
        try {
          var file: File? = null
          if (artifact.absolutePath.isNotBlank()) {
            val af = File(artifact.absolutePath)
            if (af.exists() && af.isFile) file = af
          }
          if (file == null || !file.exists()) {
            try {
              file = engine.workspace.resolveSafe(artifact.path)
            } catch (_: Exception) {}
          }
          if (file == null || !file.exists()) {
            file = ArtifactDownloader.resolveArtifactFile(getApplication<Application>(), artifact, engine.workspaceDir)
          }

          if (file != null && file.exists() && file.isFile) {
            val ext = file.extension.lowercase(Locale.ROOT)
            val extractedText: String? = when (ext) {
              "docx" -> {
                try {
                  val struct = com.example.agent.docs.DocxTextExtractor.extract(file, artifact.name, artifact.name)
                  val summary = struct.toSummaryText()
                  if (summary.isNotBlank()) {
                    "# [Microsoft Word Document]\n> ${file.name} • ${com.example.agent.core.DeliverableTracker.formatFileSize(file.length())}\n\n$summary"
                  } else null
                } catch (_: Exception) { null }
              }
              "xlsx", "xls" -> {
                try {
                  val struct = com.example.agent.docs.XlsxTextExtractor.extract(file, artifact.name, artifact.name)
                  val summary = struct.toSummaryText()
                  if (summary.isNotBlank()) {
                    "# [Microsoft Excel Spreadsheet]\n> ${file.name} • ${com.example.agent.core.DeliverableTracker.formatFileSize(file.length())}\n\n$summary"
                  } else null
                } catch (_: Exception) { null }
              }
              "pdf" -> {
                try {
                  val res = com.example.agent.docs.PdfTextExtractor.extractText(file)
                  val combined = res.pages.joinToString("\n\n") { "--- Page ${it.page} ---\n${it.text}" }
                  if (combined.isNotBlank()) {
                    "# [PDF Document Preview]\n> ${file.name} • ${res.pageCount} pages • ${com.example.agent.core.DeliverableTracker.formatFileSize(file.length())}\n\n$combined"
                  } else null
                } catch (_: Exception) { null }
              }
              else -> null
            }

            if (extractedText != null) {
              if (extractedText.length > 64000) {
                extractedText.take(64000) + "\n\n...[Preview truncated at 64,000 characters]..."
              } else {
                extractedText
              }
            } else if (!ArtifactDownloader.isTextFile(file.name)) {
              val length = file.length()
              val formatBadge = file.extension.uppercase(Locale.ROOT)
              """
                [Binary Deliverable File]
                Name: ${file.name}
                Format: $formatBadge (${artifact.type})
                Size: ${com.example.agent.core.DeliverableTracker.formatFileSize(length)} ($length bytes)
                Path: ${file.path}
                
                This is a compiled/binary deliverable file.
                Tap 'SAVE' to export to Downloads or 'SHARE' to open with external applications.
              """.trimIndent()
            } else {
              val raw = file.readText()
              if (raw.length > 64000) {
                raw.take(64000) + "\n\n...[Preview truncated at 64,000 characters to conserve memory]..."
              } else {
                raw
              }
            }
          } else {
            "(File not found or cannot be read)"
          }
        } catch (e: Exception) {
          "Error reading artifact: ${e.message}"
        }
      }
      _previewContent.value = content
    }
  }

  fun closeArtifactPreview() {
    _selectedArtifactForPreview.value = null
    _previewContent.value = null
  }

  fun restoreTaskFromHistory(record: TaskRecord) {
    engine.restoreTaskFromHistory(record)
    _activeTab.value = UiTab.EXECUTION_FEED
  }

  fun clearTaskHistory() {
    engine.clearTaskHistory()
  }

  private var explorerLoadJob: Job? = null

  fun loadExplorerDirectory(relativePath: String = _currentExplorerPath.value) {
    explorerLoadJob?.cancel()
    explorerLoadJob = viewModelScope.launch(Dispatchers.IO) {
      val cleanRel = relativePath.trim().removePrefix("/").removePrefix("./")
      val targetDir = if (cleanRel.isBlank()) {
        workspaceDir
      } else {
        try {
          PathSecurity.resolveSafe(workspaceDir, cleanRel)
        } catch (_: Exception) {
          workspaceDir
        }
      }
      if (!targetDir.exists()) {
        targetDir.mkdirs()
      }
      _currentExplorerPath.value = cleanRel

      val children = targetDir.listFiles() ?: emptyArray()
      val deliverables = engine.deliverableTracker.deliverablesMap.values

      val items = children.filter { file ->
        val rel = PathSecurity.getRelativePath(workspaceDir, file)
        !com.example.agent.core.DeliverableTracker.isInternalWorkspacePath(rel, file.name.lowercase(Locale.ROOT)) &&
        !file.name.startsWith(".tmp_")
      }.map { file ->
        val fileRel = PathSecurity.getRelativePath(workspaceDir, file)
        val isDir = file.isDirectory
        val childCount = if (isDir) (file.listFiles()?.count { !it.name.startsWith(".") } ?: 0) else 0
        val matchedDeliverable = deliverables.firstOrNull {
          it.name.equals(file.name, ignoreCase = true) || it.path.equals(fileRel, ignoreCase = true)
        }
        val metrics = if (!isDir && file.exists() && file.length() > 0) {
          com.example.agent.core.DeliverableTracker.computeDeliverableMetrics(file)
        } else {
          com.example.agent.core.DeliverableFileMetrics()
        }
        FileExplorerItem(
          name = file.name,
          relativePath = fileRel,
          isDirectory = isDir,
          size = if (isDir) 0L else file.length(),
          lastModified = file.lastModified(),
          childCount = childCount,
          extension = if (isDir) "" else file.extension.lowercase(Locale.ROOT),
          isDeliverable = matchedDeliverable != null,
          deliverableStatus = matchedDeliverable?.status,
          absolutePath = file.absolutePath,
          lineCount = metrics.lineCount,
          qualityScore = matchedDeliverable?.qualityScore ?: metrics.qualityScore,
          sha256 = matchedDeliverable?.sha256 ?: metrics.sha256
        )
      }.sortedWith(
        compareBy<FileExplorerItem> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) }
      )

      _explorerItems.value = items
    }
  }

  fun renameExplorerItem(item: FileExplorerItem, newName: String) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val clean = newName.trim()
        if (clean.isBlank() || clean.contains("/") || clean.contains("\\")) return@launch
        val oldFile = File(workspaceDir, item.relativePath)
        if (oldFile.exists()) {
          val newFile = File(oldFile.parentFile, clean)
          if (!newFile.exists()) {
            val success = oldFile.renameTo(newFile)
            if (success) {
              if (!item.isDirectory) {
                engine.artifactManager.unregisterArtifact(item.relativePath)
                val newRel = PathSecurity.getRelativePath(workspaceDir, newFile)
                engine.artifactManager.registerArtifact(newRel)
              } else {
                engine.artifactManager.syncWorkspace()
              }
              engine.deliverableTracker.scanAndVerify(engine.artifactManager)
              loadExplorerDirectory(_currentExplorerPath.value)
            }
          }
        }
      } catch (e: Exception) {
        android.util.Log.e("AgentViewModel", "Failed to rename explorer item: ${item.relativePath} -> $newName", e)
      }
    }
  }

  fun duplicateExplorerItem(item: FileExplorerItem) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val file = File(workspaceDir, item.relativePath)
        if (file.exists() && file.isFile) {
          val baseName = item.name.substringBeforeLast('.')
          val ext = if (item.name.contains('.')) ".${item.name.substringAfterLast('.')}" else ""
          var candidateName = "${baseName}_copy$ext"
          var counter = 1
          while (File(file.parentFile, candidateName).exists()) {
            candidateName = "${baseName}_copy$counter$ext"
            counter++
          }
          val destFile = File(file.parentFile, candidateName)
          file.copyTo(destFile)
          val relPath = PathSecurity.getRelativePath(workspaceDir, destFile)
          engine.artifactManager.registerArtifact(relPath)
          engine.deliverableTracker.scanAndVerify(engine.artifactManager)
          loadExplorerDirectory(_currentExplorerPath.value)
        }
      } catch (e: Exception) {
        android.util.Log.e("AgentViewModel", "Failed to duplicate explorer item: ${item.relativePath}", e)
      }
    }
  }

  fun navigateToFolder(folderName: String) {
    val current = _currentExplorerPath.value.trim().removePrefix("/").removeSuffix("/")
    val next = if (current.isBlank()) folderName else "$current/$folderName"
    loadExplorerDirectory(next)
  }

  fun navigateToBreadcrumb(targetPath: String) {
    loadExplorerDirectory(targetPath)
  }

  fun navigateUpDirectory() {
    val current = _currentExplorerPath.value.trim().removePrefix("/").removeSuffix("/")
    if (current.isBlank()) return
    val parent = if (current.contains("/")) current.substringBeforeLast("/") else ""
    loadExplorerDirectory(parent)
  }

  fun setExplorerSearch(query: String) {
    _explorerSearchQuery.value = query
  }

  fun createNewFile(fileName: String, initialContent: String = "") {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val cleanName = fileName.trim()
        if (cleanName.isBlank()) return@launch
        val current = _currentExplorerPath.value.trim().removePrefix("/").removeSuffix("/")
        val targetDir = if (current.isBlank()) workspaceDir else File(workspaceDir, current)
        targetDir.mkdirs()
        val file = File(targetDir, cleanName)
        file.writeText(initialContent)
        val relPath = PathSecurity.getRelativePath(workspaceDir, file)
        engine.artifactManager.registerArtifact(relPath)
        engine.deliverableTracker.scanAndVerify(engine.artifactManager)
        loadExplorerDirectory(_currentExplorerPath.value)
      } catch (e: Exception) {
        android.util.Log.e("AgentViewModel", "Failed to create new file: $fileName", e)
      }
    }
  }

  fun createNewFolder(folderName: String) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val cleanName = folderName.trim()
        if (cleanName.isBlank()) return@launch
        val current = _currentExplorerPath.value.trim().removePrefix("/").removeSuffix("/")
        val targetDir = if (current.isBlank()) workspaceDir else File(workspaceDir, current)
        val newDir = File(targetDir, cleanName)
        newDir.mkdirs()
        loadExplorerDirectory(_currentExplorerPath.value)
      } catch (e: Exception) {
        android.util.Log.e("AgentViewModel", "Failed to create new folder: $folderName", e)
      }
    }
  }

  fun deleteExplorerItem(item: FileExplorerItem) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val file = File(workspaceDir, item.relativePath)
        if (file.exists()) {
          if (file.isDirectory) {
            file.deleteRecursively()
          } else {
            file.delete()
            engine.artifactManager.unregisterArtifact(item.relativePath)
          }
        }
        engine.deliverableTracker.scanAndVerify(engine.artifactManager)
        loadExplorerDirectory(_currentExplorerPath.value)
      } catch (e: Exception) {
        android.util.Log.e("AgentViewModel", "Failed to delete explorer item: ${item.relativePath}", e)
      }
    }
  }

  fun refreshExplorer() {
    viewModelScope.launch(Dispatchers.IO) {
      engine.artifactManager.syncWorkspace()
      engine.deliverableTracker.scanAndVerify(engine.artifactManager)
      loadExplorerDirectory(_currentExplorerPath.value)
    }
  }

  fun previewExplorerItem(item: FileExplorerItem) {
    if (item.isDirectory) {
      navigateToFolder(item.name)
      return
    }
    val artifact = engine.artifactManager.getArtifact(item.relativePath) ?: Artifact(
      id = engine.artifactManager.generateStableId(null, item.relativePath),
      path = item.relativePath,
      name = item.name,
      type = engine.artifactManager.determineFileType(item.name),
      size = item.size,
      lastModified = item.lastModified,
      absolutePath = item.absolutePath
    )
    openArtifactPreview(artifact)
  }

  fun saveExplorerItem(context: Context, item: FileExplorerItem) {
    val artifact = engine.artifactManager.getArtifact(item.relativePath) ?: Artifact(
      id = engine.artifactManager.generateStableId(null, item.relativePath),
      path = item.relativePath,
      name = item.name,
      type = engine.artifactManager.determineFileType(item.name),
      size = item.size,
      lastModified = item.lastModified,
      absolutePath = item.absolutePath
    )
    ArtifactDownloader.downloadArtifact(context, artifact, engine.workspaceDir)
  }

  fun shareExplorerItem(context: Context, item: FileExplorerItem) {
    val artifact = engine.artifactManager.getArtifact(item.relativePath) ?: Artifact(
      id = engine.artifactManager.generateStableId(null, item.relativePath),
      path = item.relativePath,
      name = item.name,
      type = engine.artifactManager.determineFileType(item.name),
      size = item.size,
      lastModified = item.lastModified,
      absolutePath = item.absolutePath
    )
    ArtifactDownloader.shareArtifact(context, artifact, engine.workspaceDir)
  }

  private fun resolveDeliverableArtifact(deliverable: com.example.agent.core.TrackedDeliverable): Artifact {
    val existing = engine.artifactManager.getArtifact(deliverable.path)
    if (existing != null) return existing
    val directFile = java.io.File(engine.workspaceDir, deliverable.path)
    val matchedFile = if (directFile.exists()) directFile else engine.deliverableTracker.findMatchingFile(deliverable.name) ?: directFile
    val relPath = if (matchedFile.exists()) {
      PathSecurity.toCanonicalRelativePath(
        engine.workspaceDir,
        PathSecurity.getRelativePath(engine.workspaceDir, matchedFile)
      )
    } else deliverable.path
    return Artifact(
      id = engine.artifactManager.generateStableId(null, relPath),
      path = relPath,
      name = matchedFile.name.ifBlank { deliverable.name },
      type = engine.artifactManager.determineFileType(matchedFile.name.ifBlank { deliverable.name }),
      size = if (matchedFile.exists()) matchedFile.length() else deliverable.sizeBytes,
      lastModified = if (matchedFile.exists()) matchedFile.lastModified() else deliverable.lastModified,
      absolutePath = matchedFile.absolutePath
    )
  }

  fun previewDeliverable(deliverable: com.example.agent.core.TrackedDeliverable) {
    openArtifactPreview(resolveDeliverableArtifact(deliverable))
  }

  fun saveDeliverable(context: Context, deliverable: com.example.agent.core.TrackedDeliverable) {
    ArtifactDownloader.downloadArtifact(context, resolveDeliverableArtifact(deliverable), engine.workspaceDir)
  }

  fun shareDeliverable(context: Context, deliverable: com.example.agent.core.TrackedDeliverable) {
    ArtifactDownloader.shareArtifact(context, resolveDeliverableArtifact(deliverable), engine.workspaceDir)
  }

  override fun onCleared() {
    super.onCleared()
    mcpManager.shutdown()
    if (AgentServiceBridge.engine === engine && !engine.state.value.canCancel) {
      AgentServiceBridge.engine = null
    }
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
      val filesDir = app?.filesDir
      val ws = if (filesDir != null) {
        File(filesDir, "agent_workspace")
      } else {
        File(System.getProperty("java.io.tmpdir") ?: ".", "agent_workspace")
      }
      if (!ws.exists()) {
        ws.mkdirs()
      }
      return ws
    }
  }
}
