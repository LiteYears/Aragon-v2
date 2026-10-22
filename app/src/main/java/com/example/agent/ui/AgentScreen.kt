package com.example.agent.ui

import com.example.agent.core.SandboxMode
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import com.example.agent.ui.components.tactilePress
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.IconButton
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import android.content.Context
import com.example.agent.core.AgentState
import com.example.agent.core.AgentStatus
import com.example.agent.core.NvidiaNimConfig
import com.example.agent.core.NvidiaNimModels
import com.example.agent.core.TaskRecord
import com.example.agent.docs.DocumentStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.agent.ui.components.AboutAragonScreen
import com.example.agent.ui.components.ArtifactItem
import com.example.agent.ui.components.ArtifactPreviewDialog
import com.example.agent.ui.components.DocumentsDialog
import com.example.agent.ui.components.ExecutionFeedItem
import com.example.agent.ui.components.FiveStagesInspector
import com.example.agent.ui.components.LittleCopyButton
import com.example.agent.ui.components.McpServersDialog
import com.example.agent.ui.components.WorkspaceFileExplorer
import com.example.agent.ui.components.AragonActionButton
import com.example.agent.ui.components.AragonButtonVariant
import com.example.agent.ui.components.AragonFeedButton
import com.example.agent.ui.components.AragonFeedCopyButton
import com.example.agent.ui.components.AragonHeaderButton
import com.example.agent.ui.components.AragonHeaderCopyButton
import com.example.agent.ui.components.LiveTelemetryBar
import com.example.agent.ui.components.FeedFilterRow
import com.example.agent.ui.components.CompactTelemetryButton
import com.example.agent.ui.components.FeedFilterDropdownButton
import com.example.agent.ui.components.FeedFilterDialog
import com.example.agent.ui.components.AutonomyPolicyPill
import com.example.agent.ui.components.AutonomyPolicyDialog
import com.example.agent.ui.components.RunTelemetryDialog
import com.example.agent.core.ApprovalPolicy
import com.example.agent.core.StepType
import com.example.agent.ui.FeedFilter
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledActionPrimaryOn
import com.example.ui.theme.AmoledBackground
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGrey
import com.example.ui.theme.AmoledIconGreyDark
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledStatusError
import com.example.agent.core.MessageRole
import com.example.ui.theme.AmoledAccentAmber
import com.example.ui.theme.AmoledStatusSuccess
import com.example.ui.theme.AmoledStatusWarning
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledSurfaceVariant
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily

/**
 * MIME filters for the document picker. Real validation happens by magic-byte
 * sniffing after import; the filter merely narrows the browser view. The
 * octet-stream entry keeps documents from file providers that do not declare
 * a precise MIME type selectable.
 */
private val DOCUMENT_MIME_FILTERS = arrayOf("*/*")

/**
 * Formats the entire execution session into a clean, comprehensive structured Markdown log
 * suitable for the clipboard, export, or audit records.
 */
fun formatExecutionFeedLog(state: AgentState): String {
  return buildString {
    appendLine("# ARAGON AUTONOMOUS AGENT EXECUTION LOG")
    appendLine("Session ID: ${state.sessionId}")
    appendLine("Status: ${state.status.name}")
    state.task?.let { t ->
      appendLine("Prompt: ${t.goal}")
      if (!t.expectedArtifact.isNullOrBlank()) {
        appendLine("Target Deliverable: ${t.expectedArtifact}")
      }
    }
    appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(state.task?.createdAt ?: System.currentTimeMillis()))}")
    appendLine()

    if (state.plan.isNotEmpty()) {
      appendLine("## EXECUTION PLAN")
      state.plan.forEachIndexed { idx, p -> appendLine("${idx + 1}. $p") }
      appendLine()
    }

    appendLine("## EXECUTION FEED (${state.executionFeed.size} steps)")
    state.executionFeed.forEach { step ->
      appendLine("---")
      appendLine("### Step ${step.stepNumber}: [${step.type.name}] ${step.title}")
      if (!step.toolName.isNullOrBlank()) {
        appendLine("Tool: ${step.toolName} | Status: ${step.toolStatus ?: "COMPLETED"}")
      }
      if (step.durationMs != null) {
        appendLine("Duration: ${step.durationMs}ms")
      }
      if (step.exitCode != null) {
        appendLine("Exit Code: ${step.exitCode}")
      }
      if (step.content.isNotBlank()) {
        appendLine("\nContent / Arguments:")
        appendLine(step.content)
      }
      if (!step.stdout.isNullOrBlank()) {
        appendLine("\nSTDOUT:")
        appendLine(step.stdout)
      }
      if (!step.stderr.isNullOrBlank()) {
        appendLine("\nSTDERR:")
        appendLine(step.stderr)
      }
      appendLine()
    }

    if (state.trackedDeliverables.isNotEmpty()) {
      appendLine("## DELIVERABLES & ARTIFACTS (${state.trackedDeliverables.size})")
      state.trackedDeliverables.forEach { d ->
        appendLine("- ${d.name} (${d.description.ifBlank { "Deliverable" }}) [Status: ${d.status.name}] Path: ${d.path}")
      }
      appendLine()
    }

    if (!state.completionSummary.isNullOrBlank()) {
      appendLine("## CONCLUSION & SUMMARY")
      appendLine(state.completionSummary)
    }
  }
}

/**
 * Main Aragon Autonomous Agent Screen.
 * Pure typography design without icons, left-aligned logo header,
 * quick model selection separated from hidden developer configuration,
 * suggestions above prompt input field, and pristine home screen.
 */
@Composable
fun AgentScreen(
  viewModel: AgentViewModel,
  modifier: Modifier = Modifier,
  onReplaySplash: () -> Unit = {}
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
  val providerType by viewModel.providerType.collectAsStateWithLifecycle()
  val nvidiaApiKey by viewModel.nvidiaApiKey.collectAsStateWithLifecycle()
  val nvidiaApiKeys by viewModel.nvidiaApiKeys.collectAsStateWithLifecycle()
  val nvidiaModel by viewModel.nvidiaModel.collectAsStateWithLifecycle()
  val nvidiaBaseUrl by viewModel.nvidiaBaseUrl.collectAsStateWithLifecycle()
  val nvidiaTemperature by viewModel.nvidiaTemperature.collectAsStateWithLifecycle()
  val nvidiaMaxTokens by viewModel.nvidiaMaxTokens.collectAsStateWithLifecycle()
  val geminiApiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
  val selectedArtifact by viewModel.selectedArtifactForPreview.collectAsStateWithLifecycle()
  val previewContent by viewModel.previewContent.collectAsStateWithLifecycle()
  val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
  val sandboxMode by viewModel.sandboxMode.collectAsStateWithLifecycle()
  val e2bApiKey by viewModel.e2bApiKey.collectAsStateWithLifecycle()
  val e2bTemplate by viewModel.e2bTemplate.collectAsStateWithLifecycle()
  val e2bApiUrl by viewModel.e2bApiUrl.collectAsStateWithLifecycle()
  val sandboxConnectionStatus by viewModel.sandboxConnectionStatus.collectAsStateWithLifecycle()
  val currentExplorerPath by viewModel.currentExplorerPath.collectAsStateWithLifecycle()
  val explorerSearchQuery by viewModel.explorerSearchQuery.collectAsStateWithLifecycle()
  val explorerItems by viewModel.explorerItems.collectAsStateWithLifecycle()
  val approvalPolicy by viewModel.approvalPolicy.collectAsStateWithLifecycle()
  val context = LocalContext.current

  var inputText by rememberSaveable { mutableStateOf("") }
  var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
  var showModelSelectDialog by rememberSaveable { mutableStateOf(false) }
  var showAutonomyDialog by rememberSaveable { mutableStateOf(false) }
  var showTelemetryDialog by rememberSaveable { mutableStateOf(false) }
  var showResetConfirm by remember { mutableStateOf(false) }
  var showDocumentsDialog by rememberSaveable { mutableStateOf(false) }
  var showMcpDialog by rememberSaveable { mutableStateOf(false) }
  var showNavMenu by rememberSaveable { mutableStateOf(false) }
  var showFilterDialog by rememberSaveable { mutableStateOf(false) }
  var showStagesDialog by rememberSaveable { mutableStateOf(false) }
  var showFilesDialog by rememberSaveable { mutableStateOf(false) }
  var showHistoryDialog by rememberSaveable { mutableStateOf(false) }
  var activeFeedFilter by rememberSaveable { mutableStateOf(FeedFilter.ALL) }

  val mcpServers by viewModel.mcpServers.collectAsStateWithLifecycle()
  val mcpDiscoveredTools by viewModel.mcpDiscoveredTools.collectAsStateWithLifecycle()
  val mcpDiscoveredResources by viewModel.mcpDiscoveredResources.collectAsStateWithLifecycle()
  val mcpDiscoveredPrompts by viewModel.mcpDiscoveredPrompts.collectAsStateWithLifecycle()
  val mcpLogs by viewModel.mcpLogs.collectAsStateWithLifecycle()
  val isRefreshingMcp by viewModel.isRefreshingMcp.collectAsStateWithLifecycle()
  val deprecatedModelAlert by viewModel.deprecatedModelAlert.collectAsStateWithLifecycle()

  val documentPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenMultipleDocuments()
  ) { uris ->
    if (uris.isNotEmpty()) {
      viewModel.onDocumentsPicked(uris)
    }
  }
  val getContentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetMultipleContents()
  ) { uris ->
    if (uris.isNotEmpty()) {
      viewModel.onDocumentsPicked(uris)
    }
  }
  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia()
  ) { uris ->
    if (uris.isNotEmpty()) {
      viewModel.onDocumentsPicked(uris)
    }
  }
  val folderPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree()
  ) { uri ->
    if (uri != null) {
      viewModel.onFolderPicked(uri)
    }
  }

  fun launchDocumentPicker() {
    try {
      documentPickerLauncher.launch(DOCUMENT_MIME_FILTERS)
    } catch (_: Throwable) {
      try {
        getContentLauncher.launch("*/*")
      } catch (e: Throwable) {
        android.widget.Toast.makeText(context, "Could not open file picker: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
      }
    }
  }

  fun launchPhotoPicker() {
    try {
      photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    } catch (_: Throwable) {
      try {
        getContentLauncher.launch("image/*")
      } catch (e: Throwable) {
        android.widget.Toast.makeText(context, "Could not open gallery: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
      }
    }
  }

  fun launchFolderPicker() {
    try {
      folderPickerLauncher.launch(null)
    } catch (e: Throwable) {
      android.widget.Toast.makeText(context, "Folder picker unavailable: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
  }

  var showAttachmentMenu by rememberSaveable { mutableStateOf(false) }
  val documents by viewModel.documents.collectAsStateWithLifecycle()
  val documentScope by viewModel.documentScope.collectAsStateWithLifecycle()
  val documentMessages by viewModel.documentMessages.collectAsStateWithLifecycle()
  val autoCloudOcr by viewModel.autoCloudOcr.collectAsStateWithLifecycle()
  val ocrEnabled by viewModel.ocrEnabled.collectAsStateWithLifecycle()

  val listState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val inputFocusRequester = remember { FocusRequester() }
  var isInputFocused by remember { mutableStateOf(false) }

  // Only follow the live tail when the newest step is already visible, so incoming
  // events never yank the user away while they are reading earlier output.
  val isNearBottom by remember {
    derivedStateOf {
      val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
      lastVisible == null || lastVisible.index >= listState.layoutInfo.totalItemsCount - 2
    }
  }

  // Intercept back navigation when viewing an artifact preview, modal dialogs, subfolder, or secondary tabs
  BackHandler(enabled = selectedArtifact != null || showFilesDialog || showStagesDialog || showHistoryDialog || activeTab != UiTab.EXECUTION_FEED || currentExplorerPath.isNotBlank()) {
    if (selectedArtifact != null) {
      viewModel.closeArtifactPreview()
    } else if (showFilesDialog) {
      if (currentExplorerPath.isNotBlank()) {
        viewModel.navigateUpDirectory()
      } else {
        showFilesDialog = false
      }
    } else if (showStagesDialog) {
      showStagesDialog = false
    } else if (showHistoryDialog) {
      showHistoryDialog = false
    } else if (activeTab == UiTab.ARTIFACTS && currentExplorerPath.isNotBlank()) {
      viewModel.navigateUpDirectory()
    } else {
      viewModel.selectTab(UiTab.EXECUTION_FEED)
    }
  }

  val isUserDragging by remember {
    derivedStateOf { listState.isScrollInProgress }
  }

  val feedStepCount = state.executionFeed.size
  val lastStepType = state.executionFeed.lastOrNull()?.type
  val lastStepExitCode = state.executionFeed.lastOrNull()?.exitCode
  val lastStepHasOutput = !state.executionFeed.lastOrNull()?.stdout.isNullOrBlank() || !state.executionFeed.lastOrNull()?.stderr.isNullOrBlank()

  val filterCounts = remember(feedStepCount, lastStepType, lastStepExitCode, lastStepHasOutput) {
    var tools = 0
    var reasoning = 0
    var outputs = 0
    var errors = 0
    val visibleFeed = state.executionFeed.filter { it.type != StepType.OBSERVATION }
    for (step in visibleFeed) {
      if (step.type == StepType.TOOL_CALL || step.type == StepType.TOOL_EXECUTION) tools++
      if (step.type == StepType.THINKING || step.type == StepType.PLAN || step.type == StepType.CONCLUSION) reasoning++
      if (!step.stdout.isNullOrBlank() || !step.stderr.isNullOrBlank()) outputs++
      if (step.type == StepType.ERROR || (step.exitCode != null && step.exitCode != 0)) errors++
    }
    mapOf(
      FeedFilter.ALL to visibleFeed.size,
      FeedFilter.TOOLS to tools,
      FeedFilter.REASONING to reasoning,
      FeedFilter.OUTPUTS to outputs,
      FeedFilter.ERRORS to errors
    )
  }

  val filteredFeed = remember(state.executionFeed, activeFeedFilter) {
    val visibleFeed = state.executionFeed.filter { it.type != StepType.OBSERVATION }
    when (activeFeedFilter) {
      FeedFilter.ALL -> visibleFeed
      FeedFilter.TOOLS -> visibleFeed.filter { it.type == StepType.TOOL_CALL || it.type == StepType.TOOL_EXECUTION }
      FeedFilter.REASONING -> visibleFeed.filter { it.type == StepType.THINKING || it.type == StepType.PLAN || it.type == StepType.CONCLUSION }
      FeedFilter.OUTPUTS -> visibleFeed.filter { !it.stdout.isNullOrBlank() || !it.stderr.isNullOrBlank() }
      FeedFilter.ERRORS -> visibleFeed.filter { it.type == StepType.ERROR || (it.exitCode != null && it.exitCode != 0) }
    }
  }

  // Smooth elastic auto-scroll adhering to best practices for high-speed streaming agent feeds:
  // 1. Never interrupt physics or animation if user is manually scrolling or has scrolled up (!isNearBottom).
  // 2. Animate smoothly to new items or completion state.
  // 3. For live streaming token additions, use instant smooth pin to bottom without restarting animations.
  val currentFeedSize = filteredFeed.size
  val currentStatus = state.status

  LaunchedEffect(currentFeedSize, currentStatus) {
    if (filteredFeed.isNotEmpty() && isNearBottom && !isUserDragging) {
      val targetIndex = (filteredFeed.size - 1).coerceAtLeast(0)
      try {
        if (currentStatus == AgentStatus.COMPLETED) {
          listState.animateScrollToItem(
            index = targetIndex,
            scrollOffset = 220
          )
        } else {
          listState.animateScrollToItem(targetIndex)
        }
      } catch (_: Exception) {}
    }
  }

  val lastStepContentLength = state.executionFeed.lastOrNull()?.content?.length ?: 0
  LaunchedEffect(lastStepContentLength) {
    if (filteredFeed.isNotEmpty() && isNearBottom && !isUserDragging && lastStepContentLength > 0) {
      val targetIndex = (filteredFeed.size - 1).coerceAtLeast(0)
      try {
        listState.scrollToItem(targetIndex, 10000)
      } catch (_: Exception) {}
    }
  }

  val runTelemetry = remember(feedStepCount, state.status, state.trackedDeliverables.size) {
    viewModel.computeRunTelemetry()
  }

  Scaffold(
    modifier = modifier
      .fillMaxSize()
      .background(AmoledBackground),
    containerColor = AmoledBackground,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      val isHomeScreen = activeTab == UiTab.EXECUTION_FEED && state.executionFeed.isEmpty()
      val showFeedButtons = activeTab == UiTab.EXECUTION_FEED && state.executionFeed.isNotEmpty()
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(AmoledBackground)
      ) {
        AgentTopHeader(
          status = state.status,
          currentAction = state.currentAction,
          canCancel = state.canCancel,
          isHomeScreen = isHomeScreen,
          telemetry = if (showFeedButtons) runTelemetry else null,
          activeFeedFilter = if (showFeedButtons) activeFeedFilter else null,
          filterCount = if (showFeedButtons) filteredFeed.size else 0,
          mcpCount = mcpServers.count { it.enabled },
          docsCount = documents.size,
          artifactsCount = state.trackedDeliverables.size,
          historyCount = state.taskHistory.size,
          onOpenFiles = { showFilesDialog = true },
          onOpenStages = { showStagesDialog = true },
          onOpenHistory = { showHistoryDialog = true },
          onOpenTelemetry = { showTelemetryDialog = true },
          onOpenFilterDialog = { showFilterDialog = true },
          onStop = { viewModel.stopExecution() },
          onGoHome = { viewModel.selectTab(UiTab.EXECUTION_FEED) },
          onOpenMcp = { showMcpDialog = true },
          onOpenDocs = { showDocumentsDialog = true },
          onOpenMenu = { showNavMenu = true },
          onCopyAll = if (state.executionFeed.isNotEmpty()) {
            {
              val log = formatExecutionFeedLog(state)
              val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
              if (clipboard != null) {
                val clip = android.content.ClipData.newPlainText("Aragon Execution Feed", log.take(250_000))
                clipboard.setPrimaryClip(clip)
                android.widget.Toast.makeText(context, "Full execution feed & tool calls copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
              }
            }
          } else null
        )
      }
    },
    bottomBar = {
      val isInputActive = isInputFocused || inputText.isNotBlank()
      val containerBorderColor by animateColorAsState(
        targetValue = if (isInputActive) Color(0xFF38BDF8).copy(alpha = 0.85f) else Color(0xFF191C26),
        animationSpec = tween(180),
        label = "prompt_border"
      )
      val activeModelShort = remember(providerType, nvidiaModel) {
        when (providerType) {
          ProviderType.NVIDIA_NIM -> nvidiaModel.substringAfterLast('/')
          ProviderType.SANDBOX_ENGINE -> "Autonomous"
          ProviderType.GEMINI_LIVE_API -> "Gemini 2.5"
        }
      }

      Box(
        modifier = Modifier
          .fillMaxWidth()
          .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
          .background(AmoledBackground),
        contentAlignment = Alignment.Center
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 28.dp)
        ) {
          // 1. Contextual Suggestion Strip above the prompt dock
          val suggestedPresets = if (state.status == AgentStatus.COMPLETED) {
            viewModel.followUpPresets
          } else {
            emptyList()
          }
          if (suggestedPresets.isNotEmpty()) {
            val chipScrollState = rememberScrollState()
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(chipScrollState)
                .padding(bottom = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF10B981).copy(alpha = 0.14f),
                border = BorderStroke(0.8.dp, Color(0xFF10B981).copy(alpha = 0.45f))
              ) {
                Text(
                  text = "NEXT ACTIONS",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 8.sp,
                  fontWeight = FontWeight.ExtraBold,
                  color = Color(0xFF10B981),
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
              }
              suggestedPresets.forEach { preset ->
                AragonActionButton(
                  text = preset.title,
                  onClick = {
                    inputText = ""
                    viewModel.submitTask(preset.prompt, preset.expectedArtifact)
                  },
                  variant = AragonButtonVariant.SUCCESS,
                  height = 28.dp,
                  testTag = "preset_chip_${preset.expectedArtifact ?: preset.title.replace(" ", "_")}"
                )
              }
            }
          } else if (state.executionFeed.isEmpty() && !state.canCancel) {
            // Quick-launch tactical starter pills right above the input dock when idle
            val quickStarters = remember {
              listOf(
                Triple("🌐 Deep Web Brief", "Research the latest breakthroughs in autonomous AI agents and compile a cited Markdown report saved to `ai_agents_brief.md`.", Color(0xFF38BDF8)),
                Triple("🐍 Python + Chart", "Write and run a Python script in the sandbox to simulate 12-month revenue metrics, save a high-res plot to `forecast.png`, and summarize in `summary.md`.", Color(0xFF10B981)),
                Triple("📄 Executive PDF", "Generate a formatted technical architecture whitepaper saved as `architecture_brief.pdf` and `architecture_brief.docx`.", Color(0xFFA855F7)),
                Triple("📊 Excel Model", "Create a multi-sheet financial projection spreadsheet in `financial_model.xlsx` using Python openpyxl.", Color(0xFFF59E0B)),
                Triple("🗂 Audit Workspace", "Inspect all files and documents in the workspace and synthesize a structured `workspace_audit.md`.", Color(0xFFEC4899))
              )
            }
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              quickStarters.forEachIndexed { idx, (label, promptText, accent) ->
                Surface(
                  shape = RoundedCornerShape(10.dp),
                  color = Color(0xFF07070B),
                  border = BorderStroke(1.dp, Color(0xFF191C26)),
                  modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                      inputText = promptText
                    }
                    .testTag("starter_suggestion_pill_$idx")
                ) {
                  Row(
                    modifier = Modifier
                      .background(
                        Brush.horizontalGradient(
                          colors = listOf(accent.copy(alpha = 0.12f), Color(0xFF07070B))
                        )
                      )
                      .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                  ) {
                    Box(
                      modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(accent)
                    )
                    Text(
                      text = label,
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 9.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFFE2E8F0)
                    )
                  }
                }
              }
            }
          }

          // 2. Luxury Pure AMOLED True Black User Prompt Command Dock
          Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF050508),
            border = BorderStroke(if (isInputActive) 1.2.dp else 1.dp, containerBorderColor),
            shadowElevation = if (isInputActive) 16.dp else 6.dp,
            modifier = Modifier
              .fillMaxWidth()
              .animateContentSize(
                animationSpec = spring(
                  dampingRatio = 0.85f,
                  stiffness = 380f
                )
              )
              .testTag("prompt_input_container")
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  Brush.radialGradient(
                    colors = listOf(
                      if (isInputActive) Color(0xFF0F2442).copy(alpha = 0.42f) else Color(0xFF0A1424).copy(alpha = 0.22f),
                      Color(0xFF050508)
                    ),
                    center = Offset(260f, 20f),
                    radius = 560f
                  )
                )
                .padding(vertical = 8.dp, horizontal = 8.dp)
            ) {
              // Attached Documents Strip (if any)
              val attachedDocuments = remember(documents, documentScope) {
                documents.filter { it.id in documentScope }
              }
              if (documentMessages.isNotEmpty() || attachedDocuments.isNotEmpty()) {
                val docChipScrollState = rememberScrollState()
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .horizontalScroll(docChipScrollState),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  documentMessages.forEach { message ->
                    Surface(
                      shape = RoundedCornerShape(7.dp),
                      color = Color(0xFF07070B),
                      border = BorderStroke(1.dp, Color(0xFF191C26)),
                      modifier = Modifier.clickable { viewModel.clearDocumentMessages() }
                    ) {
                      Text(
                        text = message,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 8.5.sp,
                        color = Color(0xFFCBD5E1),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                      )
                    }
                  }
                  attachedDocuments.forEach { doc ->
                    Surface(
                      shape = RoundedCornerShape(7.dp),
                      color = Color(0xFF10B981).copy(alpha = 0.12f),
                      border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.45f)),
                      modifier = Modifier.testTag("doc_chip_${doc.id}")
                    ) {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp, end = 5.dp, top = 3.dp, bottom = 3.dp)
                      ) {
                        Text(
                          text = "📎 ${doc.displayName.take(18)}",
                          fontFamily = JetBrainsMonoFontFamily,
                          fontSize = 8.5.sp,
                          fontWeight = FontWeight.Bold,
                          color = Color(0xFF34D399),
                          maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                          modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF07070B))
                            .clickable { viewModel.setDocumentInScope(doc.id, false) },
                          contentAlignment = Alignment.Center
                        ) {
                          Text(text = "×", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFF34D399))
                        }
                      }
                    }
                  }
                }
              }

              // Quick Action & Syntax Strip (Shows when focused or when text is present)
              AnimatedVisibility(
                visible = isInputActive,
                enter = fadeIn(tween(140)) + expandVertically(tween(140)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(100))
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(
                    modifier = Modifier
                      .weight(1f)
                      .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                  ) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = Color(0xFF07070B),
                      border = BorderStroke(1.dp, Color(0xFF191C26)),
                      modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                          val clip = clipboard?.primaryClip
                          val clipText = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.text?.toString() else null
                          if (!clipText.isNullOrBlank()) {
                            inputText = if (inputText.isBlank()) clipText else "$inputText $clipText"
                          }
                        }
                    ) {
                      Text(
                        text = "📋 PASTE",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFCBD5E1),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                      )
                    }

                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = Color(0xFF07070B),
                      border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f)),
                      modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                          inputText = if (inputText.isBlank()) "Write and run a Python script in the sandbox to:\n```python\n\n```" else "$inputText\n```python\n\n```"
                        }
                    ) {
                      Text(
                        text = "🐍 PYTHON",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                      )
                    }

                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = Color(0xFF07070B),
                      border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.35f)),
                      modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                          val suffix = "Save the final deliverable as a formatted Markdown and PDF report (`report.md`, `report.pdf`)."
                          inputText = if (inputText.isBlank()) suffix else "$inputText\n\n$suffix"
                        }
                    ) {
                      Text(
                        text = "📄 DELIVERABLE",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFA855F7),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                      )
                    }

                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = Color(0xFF07070B),
                      border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f)),
                      modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                          inputText = if (inputText.isBlank()) "$ E = mc^2 $" else "$inputText $ formula $"
                        }
                    ) {
                      Text(
                        text = "∑ MATH",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF10B981),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                      )
                    }
                  }

                  Spacer(modifier = Modifier.width(6.dp))

                  val tokenCount = (inputText.length / 4).coerceAtLeast(if (inputText.isEmpty()) 0 else 1)
                  Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xFF07070B),
                    border = BorderStroke(0.8.dp, Color(0xFF191C26))
                  ) {
                    Text(
                      text = "${inputText.length}c · ~${tokenCount}t",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 8.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color(0xFF38BDF8),
                      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                  }
                }
              }

              // Text Input Area with crisp technical typography
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 42.dp)
                  .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.CenterStart
              ) {
                if (inputText.isEmpty()) {
                  Text(
                    text = if (state.status == AgentStatus.COMPLETED) {
                      "Enter follow-up directive or refine deliverable..."
                    } else {
                      "Describe an autonomous mission, code pipeline, or deliverable..."
                    },
                    style = TextStyle(
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 11.5.sp,
                      color = Color(0xFF64748B),
                      lineHeight = 17.sp,
                      letterSpacing = 0.3.sp,
                      fontWeight = FontWeight.Medium,
                      textAlign = TextAlign.Start
                    ),
                    modifier = Modifier.fillMaxWidth()
                  )
                }

                BasicTextField(
                  value = inputText,
                  onValueChange = { inputText = it },
                  textStyle = TextStyle(
                    fontFamily = InterFontFamily,
                    fontSize = 13.5.sp,
                    color = Color(0xFFF8FAFC),
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Normal
                  ),
                  cursorBrush = SolidColor(Color(0xFF38BDF8)),
                  maxLines = 7,
                  keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                  keyboardActions = KeyboardActions(onSend = {
                    if (inputText.isNotBlank() && !state.canCancel) {
                      val taskText = inputText
                      inputText = ""
                      viewModel.submitTask(taskText)
                    }
                  }),
                  modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inputFocusRequester)
                    .onFocusChanged { isInputFocused = it.isFocused }
                    .testTag("agent_input_field")
                )
              }

              // Bottom Control Bar inside the Command Dock
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                // Left action buttons: Attachment (+), Model Pill, Tools Pill
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  // Attachment button with options menu and rotation animation when opened
                  Box {
                    val attachInteraction = remember { MutableInteractionSource() }
                    val attachRotation by animateFloatAsState(
                      targetValue = if (showAttachmentMenu) 45f else 0f,
                      animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                      label = "attach_rotation"
                    )
                    Box(
                      modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF07070B))
                        .border(
                          BorderStroke(
                            1.dp,
                            if (showAttachmentMenu || attachedDocuments.isNotEmpty()) Color(0xFF38BDF8).copy(alpha = 0.7f) else Color(0xFF191C26)
                          ),
                          RoundedCornerShape(10.dp)
                        )
                        .tactilePress(attachInteraction, pressedScale = 0.85f)
                        .clickable(
                          interactionSource = attachInteraction,
                          indication = ripple(color = Color.White.copy(alpha = 0.2f)),
                          role = Role.Button,
                          onClickLabel = "Attach document, photo, or folder"
                        ) {
                          showAttachmentMenu = true
                        }
                        .testTag("add_attachment_btn"),
                      contentAlignment = Alignment.Center
                    ) {
                      Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Attach files",
                        tint = if (showAttachmentMenu || attachedDocuments.isNotEmpty()) Color(0xFF38BDF8) else Color(0xFFE2E8F0),
                        modifier = Modifier
                          .size(16.dp)
                          .graphicsLayer { rotationZ = attachRotation }
                      )
                    }

                    DropdownMenu(
                      expanded = showAttachmentMenu,
                      onDismissRequest = { showAttachmentMenu = false },
                      modifier = Modifier
                        .background(Color(0xFF07070B))
                        .border(BorderStroke(1.dp, Color(0xFF191C26)), RoundedCornerShape(16.dp))
                    ) {
                      DropdownMenuItem(
                        text = {
                          Column {
                            Text("Upload Documents & Files", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFF8FAFC))
                            Text("PDF, DOCX, XLSX, CSV, Code, Text", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = Color(0xFF94A3B8))
                          }
                        },
                        leadingIcon = {
                          Box(
                            modifier = Modifier
                              .size(28.dp)
                              .clip(RoundedCornerShape(8.dp))
                              .background(Color(0xFF38BDF8).copy(alpha = 0.14f))
                              .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                          ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(15.dp))
                          }
                        },
                        onClick = {
                          showAttachmentMenu = false
                          launchDocumentPicker()
                        },
                        modifier = Modifier.testTag("upload_documents_option")
                      )
                      DropdownMenuItem(
                        text = {
                          Column {
                            Text("Upload Photos & Gallery", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFF8FAFC))
                            Text("Images, diagrams, screenshots", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = Color(0xFF94A3B8))
                          }
                        },
                        leadingIcon = {
                          Box(
                            modifier = Modifier
                              .size(28.dp)
                              .clip(RoundedCornerShape(8.dp))
                              .background(Color(0xFF10B981).copy(alpha = 0.14f))
                              .border(1.dp, Color(0xFF10B981).copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                          ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(15.dp))
                          }
                        },
                        onClick = {
                          showAttachmentMenu = false
                          launchPhotoPicker()
                        },
                        modifier = Modifier.testTag("upload_photos_option")
                      )
                      DropdownMenuItem(
                        text = {
                          Column {
                            Text("Upload Folder", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFF8FAFC))
                            Text("Directory tree or project codebase", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = Color(0xFF94A3B8))
                          }
                        },
                        leadingIcon = {
                          Box(
                            modifier = Modifier
                              .size(28.dp)
                              .clip(RoundedCornerShape(8.dp))
                              .background(Color(0xFFF59E0B).copy(alpha = 0.14f))
                              .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                          ) {
                            Icon(Icons.Default.Inventory2, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(15.dp))
                          }
                        },
                        onClick = {
                          showAttachmentMenu = false
                          launchFolderPicker()
                        },
                        modifier = Modifier.testTag("upload_folder_option")
                      )
                      DropdownMenuItem(
                        text = {
                          Column {
                            Text("Manage Documents (${documents.size})", fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFF8FAFC))
                            Text("OCR, search indexing, and scope", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, color = Color(0xFF94A3B8))
                          }
                        },
                        leadingIcon = {
                          Box(
                            modifier = Modifier
                              .size(28.dp)
                              .clip(RoundedCornerShape(8.dp))
                              .background(Color(0xFFA855F7).copy(alpha = 0.14f))
                              .border(1.dp, Color(0xFFA855F7).copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                          ) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(15.dp))
                          }
                        },
                        onClick = {
                          showAttachmentMenu = false
                          showDocumentsDialog = true
                        },
                        modifier = Modifier.testTag("manage_documents_option")
                      )
                    }
                  }

                  // Quick Tools / MCP status pill
                  Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = Color(0xFF07070B),
                    border = BorderStroke(1.dp, Color(0xFF191C26)),
                    modifier = Modifier
                      .clip(RoundedCornerShape(9.dp))
                      .clickable { showMcpDialog = true }
                  ) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                      Box(
                        modifier = Modifier
                          .size(5.dp)
                          .clip(CircleShape)
                          .background(Color(0xFF10B981))
                      )
                      Spacer(modifier = Modifier.width(5.dp))
                      Text(
                        text = "TOOLS",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFE2E8F0)
                      )
                    }
                  }

                  // Active Model selector badge in dock
                  Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = Color(0xFF07070B),
                    border = BorderStroke(1.dp, Color(0xFF191C26)),
                    modifier = Modifier
                      .clip(RoundedCornerShape(9.dp))
                      .clickable { showModelSelectDialog = true }
                  ) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                      Box(
                        modifier = Modifier
                          .size(5.dp)
                          .clip(CircleShape)
                          .background(Color(0xFF38BDF8))
                      )
                      Spacer(modifier = Modifier.width(5.dp))
                      Text(
                        text = activeModelShort.take(14).uppercase(),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8),
                        maxLines = 1
                      )
                    }
                  }
                }

                // Right action buttons: Clear, Mic, and Cybernetic Send/Stop Button
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  if (inputText.isNotBlank()) {
                    val clearInteraction = remember { MutableInteractionSource() }
                    Box(
                      modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF07070B))
                        .border(BorderStroke(1.dp, Color(0xFF191C26)), RoundedCornerShape(10.dp))
                        .tactilePress(clearInteraction, pressedScale = 0.85f)
                        .clickable(
                          interactionSource = clearInteraction,
                          indication = ripple(color = Color(0xFFEF4444).copy(alpha = 0.3f)),
                          role = Role.Button,
                          onClickLabel = "Clear text"
                        ) {
                          inputText = ""
                        }
                        .testTag("clear_input_btn"),
                      contentAlignment = Alignment.Center
                    ) {
                      Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(15.dp)
                      )
                    }
                  }

                  // Microphone button
                  val micInteraction = remember { MutableInteractionSource() }
                  Box(
                    modifier = Modifier
                      .size(32.dp)
                      .clip(RoundedCornerShape(10.dp))
                      .background(Color(0xFF07070B))
                      .border(BorderStroke(1.dp, Color(0xFF191C26)), RoundedCornerShape(10.dp))
                      .tactilePress(micInteraction, pressedScale = 0.85f)
                      .clickable(
                        interactionSource = micInteraction,
                        indication = ripple(color = Color(0xFF38BDF8).copy(alpha = 0.25f)),
                        role = Role.Button,
                        onClickLabel = "Microphone input"
                      ) {
                        android.widget.Toast.makeText(context, "Voice input ready. Speak your instruction...", android.widget.Toast.LENGTH_SHORT).show()
                      }
                      .testTag("mic_input_btn"),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(
                      imageVector = Icons.Default.Mic,
                      contentDescription = "Voice input",
                      tint = Color(0xFFE2E8F0),
                      modifier = Modifier.size(15.dp)
                    )
                  }

                  // Cybernetic Send / Stop button
                  val canSend = inputText.isNotBlank()
                  val isRunning = state.canCancel

                  val runningInfiniteTransition = rememberInfiniteTransition(label = "send_pulse_trans")
                  val runningPulseScale by runningInfiniteTransition.animateFloat(
                    initialValue = 0.94f,
                    targetValue = 1.06f,
                    animationSpec = infiniteRepeatable(
                      animation = tween(650, easing = FastOutSlowInEasing),
                      repeatMode = RepeatMode.Reverse
                    ),
                    label = "running_pulse_scale"
                  )

                  val targetSendScale = when {
                    isRunning -> runningPulseScale
                    canSend -> 1.06f
                    else -> 0.96f
                  }

                  val sendScale by animateFloatAsState(
                    targetValue = targetSendScale,
                    animationSpec = spring(
                      dampingRatio = Spring.DampingRatioMediumBouncy,
                      stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "send_btn_scale"
                  )

                  val sendBgColor by animateColorAsState(
                    targetValue = when {
                      isRunning -> Color(0xFFDC2626)
                      canSend -> Color(0xFF0284C7)
                      else -> Color(0xFF07070B)
                    },
                    animationSpec = tween(200),
                    label = "send_btn_bg"
                  )

                  val sendBorderColor by animateColorAsState(
                    targetValue = when {
                      isRunning -> Color(0xFFF87171)
                      canSend -> Color(0xFF38BDF8)
                      else -> Color(0xFF191C26)
                    },
                    animationSpec = tween(200),
                    label = "send_btn_border"
                  )

                  val sendIconRotation by animateFloatAsState(
                    targetValue = if (canSend) 0f else -25f,
                    animationSpec = spring(dampingRatio = 0.60f, stiffness = 420f),
                    label = "send_arrow_rotation"
                  )

                  val sendIconOffsetY by animateFloatAsState(
                    targetValue = if (canSend) -1.0f else 1.0f,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = 400f),
                    label = "send_arrow_offset_y"
                  )

                  val sendInteractionSource = remember { MutableInteractionSource() }

                  Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = sendBgColor,
                    shadowElevation = if (canSend || isRunning) 8.dp else 0.dp,
                    border = BorderStroke(1.dp, sendBorderColor),
                    modifier = Modifier
                      .size(34.dp)
                      .graphicsLayer {
                        scaleX = sendScale
                        scaleY = sendScale
                      }
                      .tactilePress(sendInteractionSource, pressedScale = 0.82f)
                      .clickable(
                        interactionSource = sendInteractionSource,
                        indication = ripple(color = Color.White.copy(alpha = 0.35f)),
                        enabled = canSend || isRunning,
                        role = Role.Button,
                        onClickLabel = if (isRunning) "Stop execution" else "Send prompt"
                      ) {
                        if (isRunning) {
                          viewModel.stopExecution()
                        } else if (canSend) {
                          val t = inputText
                          inputText = ""
                          viewModel.submitTask(t)
                        }
                      }
                      .testTag("run_task_button")
                  ) {
                    Box(
                      contentAlignment = Alignment.Center,
                      modifier = Modifier.fillMaxSize()
                    ) {
                      if (isRunning) {
                        Text(
                          text = "◼",
                          color = Color.White,
                          fontSize = 11.sp,
                          fontWeight = FontWeight.Bold
                        )
                      } else {
                        Icon(
                          imageVector = Icons.Default.ArrowUpward,
                          contentDescription = "Send",
                          tint = if (canSend) Color.White else Color(0xFF64748B),
                          modifier = Modifier
                            .size(17.dp)
                            .graphicsLayer {
                              rotationZ = sendIconRotation
                              translationY = sendIconOffsetY
                            }
                        )
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  ) { paddingValues ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(AmoledBackground)
        .padding(paddingValues)
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .background(AmoledBackground)
      ) {
      // Discrete Error Banner (Pure text)
      AnimatedVisibility(visible = state.error != null) {
        state.error?.let { errText ->
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF140A0A),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusError.copy(alpha = 0.5f)),
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 6.dp)
              .testTag("error_banner")
          ) {
            Column(modifier = Modifier.padding(10.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = "EXECUTION ISSUE",
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = FontWeight.Bold,
                  fontSize = 10.sp,
                  color = AmoledStatusError
                )

                LittleCopyButton(
                  textToCopy = errText,
                  label = "COPY",
                  tint = AmoledStatusError,
                  testTag = "copy_error_banner_btn"
                )
              }

              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = errText,
                style = MaterialTheme.typography.bodySmall,
                color = AmoledIconGreyLight
              )

              if (providerType == ProviderType.NVIDIA_NIM && nvidiaModel != NvidiaNimConfig.DEFAULT_MODEL) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                  AragonActionButton(
                    text = "Retry with Flagship GLM 5.3",
                    onClick = { viewModel.retryWithDefaultModel() },
                    height = 26.dp,
                    testTag = "retry_default_model_btn"
                  )
                }
              }
            }
          }
        }
      }

      // Deprecated Model Alert Banner
      AnimatedVisibility(visible = deprecatedModelAlert != null) {
        deprecatedModelAlert?.let { alertText ->
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF1C1608),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusWarning.copy(alpha = 0.5f)),
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 4.dp)
          ) {
            Row(
              modifier = Modifier.padding(10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "NVIDIA CATALOG UPDATE",
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = FontWeight.Bold,
                  fontSize = 9.sp,
                  color = AmoledStatusWarning
                )
                Text(
                  text = alertText,
                  style = MaterialTheme.typography.bodySmall,
                  color = AmoledTextPrimary,
                  fontSize = 11.sp
                )
              }
              AragonActionButton(
                text = "DISMISS",
                onClick = { viewModel.dismissDeprecatedModelAlert() },
                variant = AragonButtonVariant.PRIMARY,
                height = 24.dp,
                testTag = "dismiss_deprecated_alert_btn"
              )
            }
          }
        }
      }



      // Tab Content Views with smooth, directional spring transitions
      AnimatedContent(
        targetState = activeTab,
        transitionSpec = {
          val direction = targetState.ordinal.compareTo(initialState.ordinal)
          val slideDistance = 40
          (slideInHorizontally(
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 450f),
            initialOffsetX = { if (direction >= 0) slideDistance else -slideDistance }
          ) + fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing))).togetherWith(
            slideOutHorizontally(
              animationSpec = spring(dampingRatio = 0.82f, stiffness = 450f),
              targetOffsetX = { if (direction >= 0) -slideDistance else slideDistance }
            ) + fadeOut(animationSpec = tween(120, easing = FastOutSlowInEasing))
          )
        },
        label = "tab_content_view_transition",
        modifier = Modifier.fillMaxSize()
      ) { currentTab ->
        when (currentTab) {
          UiTab.EXECUTION_FEED -> {
            if (state.executionFeed.isEmpty()) {
              if (state.status == AgentStatus.THINKING || state.status == AgentStatus.PLANNING || state.status == AgentStatus.EXECUTING_TOOL) {
                Box(
                  modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                  contentAlignment = Alignment.TopCenter
                ) {
                  val taskStartPulse by rememberInfiniteTransition(label = "task_start_pulse").animateFloat(
                    initialValue = 0.55f,
                    targetValue = 0.95f,
                    animationSpec = infiniteRepeatable(
                      animation = tween(1100, easing = FastOutSlowInEasing),
                      repeatMode = RepeatMode.Reverse
                    ),
                    label = "start_pulse_border"
                  )

                  Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF100E0A),
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, AmoledActionPrimary.copy(alpha = taskStartPulse)),
                    modifier = Modifier
                      .fillMaxWidth()
                      .widthIn(max = 680.dp)
                      .testTag("initial_reasoning_card")
                  ) {
                    Column(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                    ) {
                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                          Box(
                            modifier = Modifier
                              .size(7.dp)
                              .clip(CircleShape)
                              .background(AmoledActionPrimary)
                          )
                          Spacer(modifier = Modifier.width(7.dp))
                          Text(
                            text = if (state.status == AgentStatus.PLANNING) "PREPARING EXECUTION PLAN" else "AUTONOMOUS ENGINE REASONING",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            color = AmoledActionPrimary
                          )
                        }
                        com.example.agent.ui.components.CreativeAmoledLoadingBadge(
                          label = if (state.status == AgentStatus.PLANNING) "PLAN" else "REASON",
                          height = 24.dp,
                          testTag = "initial_reasoning_badge"
                        )
                      }

                      state.task?.let { t ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                          text = t.goal,
                          style = MaterialTheme.typography.bodyMedium,
                          fontFamily = InterFontFamily,
                          color = AmoledTextPrimary,
                          fontSize = 13.sp
                        )
                      }

                      Spacer(modifier = Modifier.height(10.dp))
                      Text(
                        text = state.currentAction ?: "Initializing autonomous kernel, provisioning sandboxed tools, and synthesizing reasoning trajectory...",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = AmoledTextSecondary,
                        fontSize = 10.sp
                      )
                    }
                  }
                }
              } else {
                val currentModelName = when (providerType) {
                  ProviderType.NVIDIA_NIM -> nvidiaModel.substringAfterLast('/')
                  ProviderType.SANDBOX_ENGINE -> "Autonomous"
                  ProviderType.GEMINI_LIVE_API -> "Gemini Live"
                }
                EmptyExecutionState(
                  currentModelName = currentModelName,
                  onOpenModelSelect = { showModelSelectDialog = true },
                  onSelectSuggestionPrompt = { prompt ->
                    inputText = prompt
                  },
                  onRunSuggestionPrompt = { prompt ->
                    inputText = ""
                    viewModel.submitTask(prompt)
                  }
                )
              }
            } else {
              Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
              ) {
                Column(
                  modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp)
                ) {
                  LazyColumn(
                    state = listState,
                    flingBehavior = ScrollableDefaults.flingBehavior(),
                    modifier = Modifier
                      .fillMaxSize()
                      .testTag("execution_feed_list"),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)
                  ) {
                    itemsIndexed(
                      items = filteredFeed,
                      key = { _, step -> step.id },
                      contentType = { _, step -> step.type }
                    ) { index, step ->
                      ExecutionFeedItem(
                        step = step,
                        isLast = index == filteredFeed.size - 1 && state.status != AgentStatus.COMPLETED
                      )
                    }

                  if (state.status == AgentStatus.COMPLETED) {
                    item(key = "continuation_prompt_banner") {
                      var taskRating by rememberSaveable(state.task?.id) { mutableIntStateOf(0) }
                      val checkScale by animateFloatAsState(
                        targetValue = 1f,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = 380f),
                        label = "completion_check_scale"
                      )

                      Column(
                        modifier = Modifier
                          .fillMaxWidth()
                          .padding(horizontal = 14.dp, vertical = 6.dp)
                      ) {
                        // Animated "✔ Task completed" with interactive Rating Stars at the right
                        Row(
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.SpaceBetween,
                          modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .testTag("task_completed_badge_row")
                        ) {
                          Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                          ) {
                            Icon(
                              imageVector = Icons.Default.Check,
                              contentDescription = "Task completed",
                              tint = Color(0xFF22C55E),
                              modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer {
                                  scaleX = checkScale
                                  scaleY = checkScale
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                              text = "Task completed",
                              style = MaterialTheme.typography.titleMedium,
                              fontFamily = InterFontFamily,
                              fontWeight = FontWeight.Bold,
                              fontSize = 17.sp,
                              color = Color(0xFF22C55E)
                            )
                          }

                          // 5 Rating Stars at right
                          Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                            modifier = Modifier.testTag("task_rating_stars_row")
                          ) {
                            (1..5).forEach { starIndex ->
                              val isFilled = starIndex <= taskRating
                              val starScale by animateFloatAsState(
                                targetValue = if (isFilled) 1.15f else 1f,
                                animationSpec = spring(dampingRatio = 0.6f, stiffness = 450f),
                                label = "star_scale_$starIndex"
                              )
                              IconButton(
                                onClick = {
                                  taskRating = if (taskRating == starIndex) 0 else starIndex
                                  if (taskRating > 0) {
                                    android.widget.Toast.makeText(context, "Rated $taskRating star${if (taskRating > 1) "s" else ""}! Thank you.", android.widget.Toast.LENGTH_SHORT).show()
                                  }
                                },
                                modifier = Modifier
                                  .size(28.dp)
                                  .graphicsLayer {
                                    scaleX = starScale
                                    scaleY = starScale
                                  }
                                  .testTag("rate_star_$starIndex")
                              ) {
                                Icon(
                                  imageVector = if (isFilled) Icons.Default.Star else Icons.Default.StarBorder,
                                  contentDescription = "Rate $starIndex star",
                                  tint = if (isFilled) Color(0xFFFBBF24) else Color(0xFF52525B),
                                  modifier = Modifier.size(19.dp)
                                )
                              }
                            }
                          }
                        }

                        // Reduced, sleek follow-up status strip (no duplicate markdown wall)
                        Surface(
                          shape = RoundedCornerShape(10.dp),
                          color = Color(0xFF0C140D),
                          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.25f)),
                          modifier = Modifier
                            .fillMaxWidth()
                            .testTag("conversation_active_banner")
                        ) {
                          Row(
                            modifier = Modifier
                              .fillMaxWidth()
                              .padding(horizontal = 12.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                          ) {
                            Row(
                              verticalAlignment = Alignment.CenterVertically,
                              modifier = Modifier.weight(1f, fill = false)
                            ) {
                              Box(
                                modifier = Modifier
                                  .size(6.dp)
                                  .clip(CircleShape)
                                  .background(Color(0xFF22C55E))
                              )
                              Spacer(modifier = Modifier.width(8.dp))
                              Text(
                                text = "Conversation active • Ready for follow-up",
                                fontFamily = InterFontFamily,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF86EFAC),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                              )
                            }
                            Text(
                              text = "READY",
                              fontFamily = JetBrainsMonoFontFamily,
                              fontSize = 9.sp,
                              fontWeight = FontWeight.Bold,
                              color = Color(0xFF22C55E).copy(alpha = 0.8f),
                              modifier = Modifier.padding(start = 6.dp)
                            )
                          }
                        }

                        // Deliverable discovery banner if files were synthesized
                        if (state.trackedDeliverables.isNotEmpty()) {
                          val delivInteraction = remember { MutableInteractionSource() }
                          Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0F141A),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f)),
                            modifier = Modifier
                              .fillMaxWidth()
                              .padding(top = 6.dp)
                              .tactilePress(delivInteraction)
                              .clickable(
                                interactionSource = delivInteraction,
                                indication = ripple(color = Color(0xFF38BDF8).copy(alpha = 0.3f)),
                                role = Role.Button,
                                onClickLabel = "View generated deliverables"
                              ) {
                                showFilesDialog = true
                              }
                              .testTag("completion_deliverables_banner")
                          ) {
                            Row(
                              modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                              verticalAlignment = Alignment.CenterVertically,
                              horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                              Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f, fill = false)
                              ) {
                                Surface(
                                  shape = RoundedCornerShape(4.dp),
                                  color = Color(0xFF162534),
                                  modifier = Modifier.size(24.dp)
                                ) {
                                  Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                      imageVector = Icons.Default.Inventory2,
                                      contentDescription = null,
                                      tint = Color(0xFF38BDF8),
                                      modifier = Modifier.size(14.dp)
                                    )
                                  }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                  Text(
                                    text = "${state.trackedDeliverables.size} workspace deliverable${if (state.trackedDeliverables.size > 1) "s" else ""} ready",
                                    fontFamily = InterFontFamily,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE0F2FE)
                                  )
                                  Text(
                                    text = "Tap to preview, inspect, or export in files explorer",
                                    fontFamily = InterFontFamily,
                                    fontSize = 10.sp,
                                    color = Color(0xFF7DD3FC)
                                  )
                                }
                              }
                              Text(
                                text = "EXPLORE →",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.padding(start = 6.dp)
                              )
                            }
                          }
                        }

                        // Quick Follow-up continuation chips for seamless chat
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                          modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                          horizontalArrangement = Arrangement.spacedBy(6.dp),
                          verticalAlignment = Alignment.CenterVertically
                        ) {
                          listOf(
                            "Summarize deliverables",
                            "Convert to Python script",
                            "Verify calculations",
                            "Refine styling & formatting",
                            "Next steps"
                          ).forEach { suggestion ->
                            Surface(
                              shape = RoundedCornerShape(12.dp),
                              color = Color(0xFF111116),
                              border = androidx.compose.foundation.BorderStroke(0.75.dp, Color(0xFF262634)),
                              modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                  inputText = suggestion
                                }
                            ) {
                              Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                              ) {
                                Text(
                                  text = "↳ $suggestion",
                                  fontFamily = InterFontFamily,
                                  fontSize = 11.5.sp,
                                  fontWeight = FontWeight.Medium,
                                  color = Color(0xFFD4D4D8)
                                )
                              }
                            }
                          }
                        }
                      }
                    }
                  } else if (
                    state.task != null &&
                    (state.status == AgentStatus.CANCELLED ||
                      state.status == AgentStatus.FAILED ||
                      state.status == AgentStatus.PARTIAL_COMPLETION)
                  ) {
                    item(key = "resume_task_banner") {
                      val isPartial = state.status == AgentStatus.PARTIAL_COMPLETION
                      val bannerAccent = if (isPartial) AmoledActionPrimary else AmoledStatusError

                      val haltPulse by rememberInfiniteTransition(label = "halt_pulse").animateFloat(
                        initialValue = 0.4f,
                        targetValue = 0.85f,
                        animationSpec = infiniteRepeatable(
                          animation = tween(1200, easing = FastOutSlowInEasing),
                          repeatMode = RepeatMode.Reverse
                        ),
                        label = "halt_pulse_border"
                      )

                      Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isPartial) Color(0xFF141208) else Color(0xFF1A0F0F),
                        border = androidx.compose.foundation.BorderStroke(1.dp, bannerAccent.copy(alpha = haltPulse)),
                        modifier = Modifier
                          .fillMaxWidth()
                          .padding(horizontal = 14.dp, vertical = 10.dp)
                          .testTag("resume_task_banner")
                      ) {
                        Column(
                          modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                        ) {
                          Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                          ) {
                            Text(
                              text = when (state.status) {
                                AgentStatus.CANCELLED -> "TASK CANCELLED • WORK PRESERVED"
                                AgentStatus.FAILED -> "TASK HALTED • WORK PRESERVED"
                                else -> "PARTIALLY COMPLETE • WORK PRESERVED"
                              },
                              style = MaterialTheme.typography.labelSmall,
                              fontFamily = JetBrainsMonoFontFamily,
                              fontWeight = FontWeight.Bold,
                              color = bannerAccent,
                              fontSize = 10.sp
                            )
                            Text(
                              text = "RESUMABLE",
                              style = MaterialTheme.typography.labelSmall,
                              fontFamily = JetBrainsMonoFontFamily,
                              color = AmoledIconGreyLight,
                              fontSize = 9.sp
                            )
                          }
                          Spacer(modifier = Modifier.height(4.dp))
                          Text(
                            text = state.completionSummary
                              ?: "Plan, conversation context, and workspace files are preserved. Resume to continue from the last checkpoint.",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = InterFontFamily,
                            color = AmoledTextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                          )
                          Spacer(modifier = Modifier.height(8.dp))
                          Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                          ) {
                            AragonFeedButton(
                              text = "RESUME TASK",
                              onClick = { viewModel.resumeTask() },
                              variant = AragonButtonVariant.PRIMARY,
                              testTag = "resume_task_button"
                            )
                            AragonFeedButton(
                              text = "NEW MISSION",
                              onClick = {
                                viewModel.startNewTask()
                                inputFocusRequester.requestFocus()
                              },
                              variant = AragonButtonVariant.MUTED,
                              testTag = "new_mission_after_halt_btn"
                            )
                          }
                        }
                      }
                    }
                  }
                }
              }

              // Floating 'Jump to Latest' pill when user scrolls away from bottom
              androidx.compose.animation.AnimatedVisibility(
                  visible = !isNearBottom && filteredFeed.isNotEmpty(),
                  enter = fadeIn(tween(140)) + slideInVertically { it / 2 },
                  exit = fadeOut(tween(120)) + slideOutVertically { it / 2 },
                  modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                ) {
                  val pillInteraction = remember { MutableInteractionSource() }
                  Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF141418),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledActionPrimary.copy(alpha = 0.7f)),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                      .tactilePress(pillInteraction)
                      .clickable(
                        interactionSource = pillInteraction,
                        indication = ripple(color = AmoledActionPrimary.copy(alpha = 0.3f)),
                        role = Role.Button,
                        onClickLabel = "Scroll to latest step"
                      ) {
                        coroutineScope.launch {
                          try {
                            listState.animateScrollToItem(filteredFeed.size - 1)
                          } catch (_: Exception) {}
                        }
                      }
                      .testTag("scroll_to_latest_btn")
                  ) {
                    Row(
                      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll down",
                        tint = AmoledActionPrimary,
                        modifier = Modifier.size(15.dp)
                      )
                      Spacer(modifier = Modifier.width(5.dp))
                      Text(
                        text = "LATEST STEP",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = AmoledActionPrimary
                      )
                      if (state.status == AgentStatus.RUNNING || state.status == AgentStatus.EXECUTING_TOOL || state.status == AgentStatus.THINKING) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                          modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(AmoledActionPrimary)
                        )
                      }
                    }
                  }
                }
              }
          }
        }

        UiTab.ARTIFACTS -> {
            WorkspaceFileExplorer(
              currentPath = currentExplorerPath,
              searchQuery = explorerSearchQuery,
              items = explorerItems,
              deliverables = state.trackedDeliverables,
              onNavigateToFolder = { viewModel.navigateToFolder(it) },
              onNavigateUp = { viewModel.navigateUpDirectory() },
              onBreadcrumbClick = { viewModel.navigateToBreadcrumb(it) },
              onSearchQueryChange = { viewModel.setExplorerSearch(it) },
              onCreateFile = { name, content -> viewModel.createNewFile(name, content) },
              onCreateFolder = { name -> viewModel.createNewFolder(name) },
              onDeleteItem = { viewModel.deleteExplorerItem(it) },
              onPreviewItem = { viewModel.previewExplorerItem(it) },
              onSaveItem = { viewModel.saveExplorerItem(context, it) },
              onShareItem = { viewModel.shareExplorerItem(context, it) },
              onRefresh = { viewModel.refreshExplorer() },
              onRenameItem = { item, newName -> viewModel.renameExplorerItem(item, newName) },
              onDuplicateItem = { item -> viewModel.duplicateExplorerItem(item) }
            )
          }

          UiTab.TASK_HISTORY -> {
            TaskHistoryView(
              taskHistory = state.taskHistory,
              onSelectTask = { goal ->
                inputText = goal
                viewModel.selectTab(UiTab.EXECUTION_FEED)
                viewModel.submitTask(goal)
              },
              onRestoreTask = { record ->
                viewModel.restoreTaskFromHistory(record)
              },
              onClearHistory = { viewModel.clearTaskHistory() }
            )
          }

          UiTab.FIVE_STAGES -> {
            FiveStagesInspector(record = state.fiveStageRecord)
          }

          UiTab.ABOUT_ARAGON -> {
            val sbxDesc = "E2B Cloud Firecracker MicroVM (${e2bTemplate.ifBlank { "base" }})"
            AboutAragonScreen(
              currentProviderName = when (providerType) {
                ProviderType.NVIDIA_NIM -> "NVIDIA NIM (${nvidiaModel.substringAfterLast('/')})"
                ProviderType.SANDBOX_ENGINE -> "Autonomous Sandbox Engine"
                ProviderType.GEMINI_LIVE_API -> "Google Gemini Live"
              },
              workspacePath = viewModel.workspaceDir.absolutePath,
              sandboxBoundary = sbxDesc,
              onOpenSettings = { showSettingsDialog = true },
              onReplaySplash = onReplaySplash
            )
          }
        }
      }
    }

    // Clean Nice Green Modern Toast Notification (replaces the old inline execution feed box)
    val sandboxToast by viewModel.sandboxUploadToast.collectAsStateWithLifecycle()
    LaunchedEffect(sandboxToast) {
      if (sandboxToast != null) {
        kotlinx.coroutines.delay(3500)
        viewModel.dismissSandboxToast()
      }
    }

    AnimatedVisibility(
      visible = sandboxToast != null,
      enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
      exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
      modifier = Modifier
        .align(Alignment.TopCenter)
        .padding(top = 10.dp, start = 14.dp, end = 14.dp)
    ) {
      Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF06150B),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.85f)),
        shadowElevation = 8.dp,
        modifier = Modifier.testTag("sandbox_upload_toast")
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            modifier = Modifier
              .size(18.dp)
              .clip(CircleShape)
              .background(Color(0xFF10B981).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = null,
              tint = Color(0xFF10B981),
              modifier = Modifier.size(13.dp)
            )
          }
          Spacer(modifier = Modifier.width(9.dp))
          Text(
            text = sandboxToast ?: "",
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFE6FBF0)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Box(
            modifier = Modifier
              .size(18.dp)
              .clip(CircleShape)
              .clickable { viewModel.dismissSandboxToast() },
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Dismiss",
              tint = Color(0xFF6EE7B7),
              modifier = Modifier.size(12.dp)
            )
          }
        }
      }
    }
  }

  // Artifact Preview Dialog
  if (selectedArtifact != null) {
    ArtifactPreviewDialog(
      artifact = selectedArtifact!!,
      content = previewContent,
      onDismiss = { viewModel.closeArtifactPreview() }
    )
  }

  // Documents Manager Dialog
  if (showDocumentsDialog) {
    DocumentsDialog(
      documents = documents,
      scope = documentScope,
      ocrEnabled = ocrEnabled,
      autoCloudOcr = autoCloudOcr,
      cloudOcrAvailable = viewModel.isCloudOcrAvailable(),
      embeddingLabel = viewModel.embeddingAvailabilityLabel(),
      onDismiss = { showDocumentsDialog = false },
      onAttach = {
        showDocumentsDialog = false
        launchDocumentPicker()
      },
      onAttachPhotos = {
        showDocumentsDialog = false
        launchPhotoPicker()
      },
      onAttachFolder = {
        showDocumentsDialog = false
        launchFolderPicker()
      },
      onToggleScope = { docId, inScope -> viewModel.setDocumentInScope(docId, inScope) },
      onCancelJob = { docId -> viewModel.cancelDocumentJob(docId) },
      onDelete = { docId -> viewModel.deleteDocument(docId) },
      onReindex = { docId -> viewModel.reindexDocument(docId) },
      onToggleOcr = { enabled -> viewModel.setOcrEnabled(enabled) },
      onToggleAutoCloudOcr = { enabled -> viewModel.setAutoCloudOcr(enabled) }
    )
  }

  // Model Context Protocol (MCP) Management Dialog
  if (showMcpDialog) {
    McpServersDialog(
      servers = mcpServers,
      discoveredTools = mcpDiscoveredTools,
      discoveredResources = mcpDiscoveredResources,
      discoveredPrompts = mcpDiscoveredPrompts,
      logs = mcpLogs,
      isRefreshing = isRefreshingMcp,
      onDismiss = { showMcpDialog = false },
      onRefreshAll = { viewModel.refreshMcpServers() },
      onUpsertServer = { cfg -> viewModel.upsertMcpServer(cfg) },
      onRemoveServer = { id -> viewModel.removeMcpServer(id) },
      onToggleServer = { id, enabled -> viewModel.toggleMcpServer(id, enabled) },
      onPingServer = { id -> viewModel.pingMcpServer(id) },
      onReconnectServer = { id -> viewModel.reconnectMcpServer(id) },
      onClearLogs = { viewModel.clearMcpLogs() },
      onRunTool = { serverId, toolName, args -> viewModel.callMcpTool(serverId, toolName, args) },
      onUsePrompt = { promptText ->
        inputText = promptText
        showMcpDialog = false
      },
      getClientForServer = { id -> viewModel.getMcpClientForServer(id) }
    )
  }

  // Model Selection Dialog: Accessible normally from prompt bar
  if (showModelSelectDialog) {
    ModelSelectorDialog(
      currentProvider = providerType,
      currentNvidiaModel = nvidiaModel,
      onSelectSandbox = {
        viewModel.setProviderType(ProviderType.SANDBOX_ENGINE)
        showModelSelectDialog = false
      },
      onSelectNvidiaModel = { modelId ->
        viewModel.setProviderType(ProviderType.NVIDIA_NIM)
        viewModel.updateNvidiaConfig(model = modelId)
        showModelSelectDialog = false
      },
      onSelectGemini = {
        viewModel.setProviderType(ProviderType.GEMINI_LIVE_API)
        showModelSelectDialog = false
      },
      onOpenDeveloperSettings = {
        showModelSelectDialog = false
        showSettingsDialog = true
      },
      onDismiss = { showModelSelectDialog = false }
    )
  }

  // Hidden Developer Settings Dialog: ONLY accessible by 10-tap unlock on build number
  if (showSettingsDialog) {
    SettingsProviderDialog(
      currentProvider = providerType,
      initialNvidiaApiKey = nvidiaApiKey,
      initialNvidiaApiKeys = nvidiaApiKeys,
      initialNvidiaModel = nvidiaModel,
      initialNvidiaBaseUrl = nvidiaBaseUrl,
      initialNvidiaTemperature = nvidiaTemperature,
      initialNvidiaMaxTokens = nvidiaMaxTokens,
      geminiKey = geminiApiKey,
      connectionStatus = connectionStatus,
      keyPoolSummary = viewModel.nvidiaKeyPoolSummary(),
      initialE2bApiKey = e2bApiKey,
      initialE2bTemplate = e2bTemplate,
      initialE2bApiUrl = e2bApiUrl,
      sandboxConnectionStatus = sandboxConnectionStatus,
      onTestSandboxConnection = { e2bKey, template, url ->
        viewModel.testSandboxConnection(e2bKey, template, url)
      },
      onResetSandboxConnectionStatus = { viewModel.resetSandboxConnectionStatus() },
      onSaveSandboxConfig = { e2bKey, template, url ->
        viewModel.updateSandboxConfig(SandboxMode.E2B_CLOUD, e2bKey, template, url)
      },
      onTestConnection = { viewModel.testNvidiaConnection() },
      onResetConnectionStatus = { viewModel.resetConnectionStatus() },
      onProviderChange = { viewModel.setProviderType(it) },
      onSaveNvidiaConfig = { keys, model, baseUrl, temp, maxTokens ->
        viewModel.updateNvidiaConfig(
          apiKeys = keys,
          model = model,
          baseUrl = baseUrl,
          temperature = temp,
          maxTokens = maxTokens
        )
      },
      onSaveGeminiKey = { viewModel.updateGeminiApiKey(it) },
      onDismiss = { showSettingsDialog = false }
    )
  }

  // Destructive workspace reset requires explicit confirmation
  if (showResetConfirm) {
    AlertDialog(
      onDismissRequest = { showResetConfirm = false },
      containerColor = Color(0xFF07070B),
      titleContentColor = Color(0xFFF8FAFC),
      textContentColor = Color(0xFF94A3B8),
      shape = RoundedCornerShape(18.dp),
      title = {
        Text(
          text = "RESET WORKSPACE?",
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.ExtraBold,
          fontSize = 13.sp,
          letterSpacing = 0.8.sp,
          color = Color(0xFFF8FAFC)
        )
      },
      text = {
        Text(
          text = "This stops any running task and permanently deletes every file in the workspace. This cannot be undone.",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 11.5.sp,
          color = Color(0xFF94A3B8)
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            showResetConfirm = false
            inputText = ""
            viewModel.resetWorkspace()
          },
          modifier = Modifier.testTag("confirm_reset_btn")
        ) {
          Text("RESET", color = AmoledStatusError, fontWeight = FontWeight.ExtraBold, fontFamily = JetBrainsMonoFontFamily)
        }
      },
      dismissButton = {
        TextButton(
          onClick = { showResetConfirm = false },
          modifier = Modifier.testTag("cancel_reset_btn")
        ) {
          Text("CANCEL", color = Color(0xFF94A3B8), fontFamily = JetBrainsMonoFontFamily)
        }
      }
    )
  }

  // Autonomy & Safety Policy Dialog
  if (showAutonomyDialog) {
    AutonomyPolicyDialog(
      currentPolicy = approvalPolicy,
      onSelectPolicy = { viewModel.setApprovalPolicy(it) },
      onDismiss = { showAutonomyDialog = false }
    )
  }

  // Live Run Telemetry & Execution Analytics Dialog
  if (showTelemetryDialog) {
    RunTelemetryDialog(
      telemetry = viewModel.computeRunTelemetry(),
      currentPolicy = approvalPolicy,
      currentProviderName = when (providerType) {
        ProviderType.NVIDIA_NIM -> "NVIDIA NIM (${nvidiaModel.substringAfterLast('/')})"
        ProviderType.SANDBOX_ENGINE -> "Autonomous Sandbox Engine"
        ProviderType.GEMINI_LIVE_API -> "Google Gemini Live"
      },
      activeFilter = activeFeedFilter,
      onSelectFilter = { activeFeedFilter = it },
      filterCounts = filterCounts,
      onCopyTranscript = {},
      onShareTranscript = {
        viewModel.shareExecutionTranscript(context)
      },
      onSaveTranscriptToWorkspace = {
        viewModel.saveTranscriptToWorkspace()
        showTelemetryDialog = false
      },
      onDismiss = { showTelemetryDialog = false }
    )
  }

  // Aragon Workspace & Navigation Menu Dialog
  if (showNavMenu) {
    val modelDisplayName = when (providerType) {
      ProviderType.NVIDIA_NIM -> nvidiaModel.substringAfterLast('/')
      ProviderType.SANDBOX_ENGINE -> "Autonomous"
      ProviderType.GEMINI_LIVE_API -> "Gemini Live"
    }
    com.example.agent.ui.components.AragonNavigationMenu(
      currentTab = activeTab,
      agentStatus = state.status,
      currentModelName = modelDisplayName,
      approvalPolicy = approvalPolicy,
      feedCount = state.executionFeed.size,
      historyCount = state.taskHistory.size,
      artifactCount = state.trackedDeliverables.size,
      mcpServerCount = mcpServers.count { it.enabled },
      documentCount = documents.size,
      onSelectTab = { tab ->
        when (tab) {
          UiTab.ARTIFACTS -> showFilesDialog = true
          UiTab.FIVE_STAGES -> showStagesDialog = true
          UiTab.TASK_HISTORY -> showHistoryDialog = true
          else -> viewModel.selectTab(tab)
        }
      },
      onOpenModelSelect = { showModelSelectDialog = true },
      onOpenAutonomy = { showAutonomyDialog = true },
      onOpenMcp = { showMcpDialog = true },
      onOpenDocuments = { showDocumentsDialog = true },
      onNewTask = {
        inputText = ""
        viewModel.startNewTask()
        inputFocusRequester.requestFocus()
      },
      onResetWorkspace = { showResetConfirm = true },
      onReplaySplash = onReplaySplash,
      onDismiss = { showNavMenu = false }
    )
  }

  // Cognitive Execution Stages Dialog
  if (showStagesDialog) {
    Dialog(
      onDismissRequest = { showStagesDialog = false },
      properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
      Surface(
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFF050508)),
        color = Color(0xFF050508)
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
        ) {
          Surface(
            color = Color(0xFF07070B),
            border = BorderStroke(1.dp, Color(0xFF191C26)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  Brush.radialGradient(
                    colors = listOf(Color(0xFF0F203C).copy(alpha = 0.45f), Color(0xFF07070B)),
                    center = Offset(200f, 40f),
                    radius = 520f
                  )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column {
                Text(
                  text = "COGNITIVE PIPELINE",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = FontWeight.Bold,
                  fontSize = 8.5.sp,
                  color = Color(0xFF38BDF8),
                  letterSpacing = 1.2.sp
                )
                Text(
                  text = "EXECUTION STAGES",
                  style = MaterialTheme.typography.titleMedium,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = FontWeight.ExtraBold,
                  color = Color(0xFFF8FAFC)
                )
              }
              AragonActionButton(
                text = "CLOSE",
                onClick = { showStagesDialog = false },
                variant = AragonButtonVariant.MUTED,
                height = 28.dp,
                testTag = "close_stages_dialog_btn"
              )
            }
          }
          FiveStagesInspector(record = state.fiveStageRecord)
        }
      }
    }
  }

  // Workspace Files Dialog
  if (showFilesDialog) {
    Dialog(
      onDismissRequest = { showFilesDialog = false },
      properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
      Surface(
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFF050508)),
        color = Color(0xFF050508)
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
        ) {
          Surface(
            color = Color(0xFF07070B),
            border = BorderStroke(1.dp, Color(0xFF191C26)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  Brush.radialGradient(
                    colors = listOf(Color(0xFF0F203C).copy(alpha = 0.45f), Color(0xFF07070B)),
                    center = Offset(200f, 40f),
                    radius = 520f
                  )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column {
                Text(
                  text = "SANDBOX & LOCAL STORAGE",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = FontWeight.Bold,
                  fontSize = 8.5.sp,
                  color = Color(0xFF10B981),
                  letterSpacing = 1.2.sp
                )
                Text(
                  text = "WORKSPACE FILES",
                  style = MaterialTheme.typography.titleMedium,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = FontWeight.ExtraBold,
                  color = Color(0xFFF8FAFC)
                )
              }
              AragonActionButton(
                text = "CLOSE",
                onClick = { showFilesDialog = false },
                variant = AragonButtonVariant.MUTED,
                height = 28.dp,
                testTag = "close_files_dialog_btn"
              )
            }
          }
          WorkspaceFileExplorer(
            currentPath = currentExplorerPath,
            searchQuery = explorerSearchQuery,
            items = explorerItems,
            deliverables = state.trackedDeliverables,
            onNavigateToFolder = { viewModel.navigateToFolder(it) },
            onNavigateUp = { viewModel.navigateUpDirectory() },
            onBreadcrumbClick = { viewModel.navigateToBreadcrumb(it) },
            onSearchQueryChange = { viewModel.setExplorerSearch(it) },
            onCreateFile = { name, content -> viewModel.createNewFile(name, content) },
            onCreateFolder = { name -> viewModel.createNewFolder(name) },
            onDeleteItem = { viewModel.deleteExplorerItem(it) },
            onPreviewItem = { viewModel.previewExplorerItem(it) },
            onSaveItem = { viewModel.saveExplorerItem(context, it) },
            onShareItem = { viewModel.shareExplorerItem(context, it) },
            onRefresh = { viewModel.refreshExplorer() },
            onRenameItem = { item, newName -> viewModel.renameExplorerItem(item, newName) },
            onDuplicateItem = { item -> viewModel.duplicateExplorerItem(item) }
          )
        }
      }
    }
  }

  // Task Logs & History Dialog
  if (showHistoryDialog) {
    Dialog(
      onDismissRequest = { showHistoryDialog = false },
      properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
      Surface(
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFF050508)),
        color = Color(0xFF050508)
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
        ) {
          Surface(
            color = Color(0xFF07070B),
            border = BorderStroke(1.dp, Color(0xFF191C26)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  Brush.radialGradient(
                    colors = listOf(Color(0xFF0F203C).copy(alpha = 0.45f), Color(0xFF07070B)),
                    center = Offset(200f, 40f),
                    radius = 520f
                  )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column {
                Text(
                  text = "MISSION ARCHIVE",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = FontWeight.Bold,
                  fontSize = 8.5.sp,
                  color = Color(0xFFA855F7),
                  letterSpacing = 1.2.sp
                )
                Text(
                  text = "TASK LOGS & HISTORY",
                  style = MaterialTheme.typography.titleMedium,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = FontWeight.ExtraBold,
                  color = Color(0xFFF8FAFC)
                )
              }
              AragonActionButton(
                text = "CLOSE",
                onClick = { showHistoryDialog = false },
                variant = AragonButtonVariant.MUTED,
                height = 28.dp,
                testTag = "close_history_dialog_btn"
              )
            }
          }
          TaskHistoryView(
            taskHistory = state.taskHistory,
            onSelectTask = { goal ->
              inputText = goal
              showHistoryDialog = false
              viewModel.submitTask(goal)
            },
            onRestoreTask = { record ->
              showHistoryDialog = false
              viewModel.restoreTaskFromHistory(record)
            },
            onClearHistory = { viewModel.clearTaskHistory() }
          )
        }
      }
    }
  }
}
}

/**
 * Pure Typography Tab Bar (No icons).
 */
@Composable
fun PremiumTabBar(
  selectedTab: UiTab,
  feedCount: Int,
  artifactsCount: Int = 0,
  historyCount: Int = 0,
  onSelectTab: (UiTab) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    color = AmoledSurface,
    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 6.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      UiTab.entries.forEach { tab ->
        val isSelected = selectedTab == tab
        val label = tab.label
        val badge = when (tab) {
          UiTab.EXECUTION_FEED -> if (feedCount > 0) "$feedCount" else null
          UiTab.ARTIFACTS -> if (artifactsCount > 0) "$artifactsCount" else null
          UiTab.TASK_HISTORY -> if (historyCount > 0) "$historyCount" else null
          else -> null
        }

        val bgColor by animateColorAsState(
          targetValue = if (isSelected) Color(0xFF202026) else Color.Transparent,
          animationSpec = tween(120),
          label = "tab_bg"
        )
        val contentColor by animateColorAsState(
          targetValue = if (isSelected) Color.White else AmoledIconGrey,
          animationSpec = tween(120),
          label = "tab_content_color"
        )

        val interactionSource = remember { MutableInteractionSource() }
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = bgColor,
          border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF484852)) else null,
          modifier = Modifier
            .weight(1f)
            .tactilePress(interactionSource)
            .clickable(
              interactionSource = interactionSource,
              indication = ripple(color = AmoledActionPrimary.copy(alpha = 0.2f)),
              role = Role.Tab,
              onClickLabel = "Switch to $label tab"
            ) { onSelectTab(tab) }
            .semantics {
              selected = isSelected
            }
            .testTag("tab_${tab.name.lowercase()}")
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 34.dp),
            contentAlignment = Alignment.Center
          ) {
            Row(
              modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              if (badge != null) {
                Spacer(modifier = Modifier.width(2.5.dp))
                Surface(
                  shape = RoundedCornerShape(2.dp),
                  color = if (isSelected) AmoledActionPrimary else Color(0xFF1E1E22),
                  modifier = Modifier.height(11.dp)
                ) {
                  Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 2.dp)
                  ) {
                    Text(
                      text = badge,
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 6.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = if (isSelected) AmoledActionPrimaryOn else AmoledIconGreyLight
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Top Header:
 * - Logo and ARAGON title aligned to the LEFT (not centered).
 * - Followed by StatusPill.
 * - Right actions are pure text (No icons).
 */
@Composable
fun AgentTopHeader(
  status: AgentStatus,
  currentAction: String?,
  canCancel: Boolean,
  isHomeScreen: Boolean = true,
  telemetry: RunTelemetry? = null,
  activeFeedFilter: FeedFilter? = null,
  filterCount: Int = 0,
  mcpCount: Int = 0,
  docsCount: Int = 0,
  artifactsCount: Int = 0,
  historyCount: Int = 0,
  onOpenFiles: () -> Unit = {},
  onOpenStages: () -> Unit = {},
  onOpenHistory: () -> Unit = {},
  onOpenTelemetry: () -> Unit = {},
  onOpenFilterDialog: () -> Unit = {},
  onStop: () -> Unit,
  onGoHome: () -> Unit = {},
  onOpenMcp: () -> Unit = {},
  onOpenDocs: () -> Unit = {},
  onOpenMenu: () -> Unit,
  onCopyAll: (() -> Unit)? = null
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = AmoledBackground,
    border = null
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .displayCutoutPadding()
        .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        // LEFT-ALIGNED LOGO + ARAGON + STATUS PILL (Full guaranteed visibility, never squished)
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          com.example.agent.ui.components.AragonLogoEmblem(
            tint = Color.White,
            modifier = Modifier.size(20.dp)
          )
          Spacer(modifier = Modifier.width(7.dp))
          Text(
            text = "ARAGON",
            style = MaterialTheme.typography.titleSmall,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            color = Color.White
          )
          Spacer(modifier = Modifier.width(8.dp))
          StatusPill(status = status)
        }

        Spacer(modifier = Modifier.width(8.dp))

        // RIGHT-ALIGNED ACTIONS: Clean, streamlined action row with modern flat design
        Row(
          modifier = Modifier.weight(1f, fill = false),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          if (canCancel) {
            AragonHeaderButton(
              text = "STOP",
              onClick = onStop,
              variant = AragonButtonVariant.DANGER,
              testTag = "header_stop_btn"
            )
          }

          // STATS (Telemetry & analytics)
          val filterBadge = if (activeFeedFilter != null && activeFeedFilter != FeedFilter.ALL) {
            activeFeedFilter.label
          } else if (telemetry != null && telemetry.errorCount > 0) {
            "!ERR"
          } else null

          if (filterBadge != null || (telemetry != null && telemetry.totalSteps > 0)) {
            AragonHeaderButton(
              text = "STATS",
              onClick = onOpenTelemetry,
              variant = if (activeFeedFilter != null && activeFeedFilter != FeedFilter.ALL) AragonButtonVariant.PRIMARY else AragonButtonVariant.MUTED,
              badge = filterBadge,
              testTag = "header_telemetry_filters_btn"
            )
          }

          if (onCopyAll != null) {
            AragonHeaderButton(
              text = "COPY",
              onClick = onCopyAll,
              variant = AragonButtonVariant.MUTED,
              testTag = "header_copy_all_btn"
            )
          }

          // Modern Navigation Menu Trigger (Unified access to Files, Stages, Logs, MCP, Docs, and Models)
          AragonHeaderButton(
            text = "MENU",
            badge = if (artifactsCount > 0) "$artifactsCount" else null,
            onClick = onOpenMenu,
            variant = AragonButtonVariant.PRIMARY,
            testTag = "header_nav_menu_btn"
          )
        }
      }
    }
  }
}

@Composable
fun StatusPill(status: AgentStatus) {
  val (bgColor, textColor, label) = when (status) {
    AgentStatus.IDLE -> Triple(Color(0xFF18181E), Color(0xFF94A3B8), "IDLE")
    AgentStatus.RUNNING -> Triple(Color(0xFF1E1E28), Color.White, "RUNNING")
    AgentStatus.THINKING -> Triple(Color(0xFF1E1E28), Color.White, "THINKING")
    AgentStatus.PLANNING -> Triple(Color(0xFF1E1E28), Color.White, "PLANNING")
    AgentStatus.REPLANNING -> Triple(Color(0xFF1E1E28), Color.White, "REPLAN")
    AgentStatus.EXECUTING_TOOL -> Triple(Color(0xFF1E1E28), Color.White, "TOOL")
    AgentStatus.OBSERVING -> Triple(Color(0xFF16161C), AmoledIconGreyLight, "OBSERVING")
    AgentStatus.SANDBOX_RESET -> Triple(Color(0xFF251510), AmoledStatusWarning, "RESETTING")
    AgentStatus.DELIVERABLE_PENDING -> Triple(Color(0xFF251F10), AmoledStatusWarning, "PENDING")
    AgentStatus.VERIFYING -> Triple(Color(0xFF16161C), AmoledIconGreyLight, "VERIFYING")
    AgentStatus.COMPLETED -> Triple(Color(0xFF102816), Color(0xFF34D399), "COMPLETED")
    AgentStatus.PARTIAL_COMPLETION -> Triple(Color(0xFF251F10), AmoledStatusWarning, "PARTIAL")
    AgentStatus.FAILED -> Triple(Color(0xFF2E1212), Color(0xFFF87171), "FAILED")
    AgentStatus.CANCELLED -> Triple(Color(0xFF18181E), AmoledTextMuted, "STOPPED")
  }

  Surface(
    shape = RoundedCornerShape(10.dp),
    color = bgColor,
    border = null,
    modifier = Modifier.height(20.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
      modifier = Modifier.padding(horizontal = 7.dp)
    ) {
      Box(
        modifier = Modifier
          .size(5.dp)
          .clip(CircleShape)
          .background(textColor)
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.SemiBold,
        color = textColor,
        fontSize = 9.sp
      )
    }
  }
}

/**
 * Home Screen (Empty State):
 * Dynamic Cybernetic Aragon Emblem, Slogan, and interactive Middle Aragon Model capsule.
 * Removed redundant new task button and noisy example lists to keep the home screen focused.
 */
@Composable
fun EmptyExecutionState(
  currentModelName: String,
  onOpenModelSelect: () -> Unit,
  onSelectSuggestionPrompt: (String) -> Unit = {},
  onRunSuggestionPrompt: ((String) -> Unit)? = null
) {
  val scrollState = rememberScrollState()
  var showSuggestions by rememberSaveable { mutableStateOf(true) }

  // Animated cybernetic orbital ring behind logo
  val infiniteTransition = rememberInfiniteTransition(label = "empty_state_orbital")
  val orbitAngle by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(16000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "orbit_angle"
  )
  val breatheScale by infiniteTransition.animateFloat(
    initialValue = 0.94f,
    targetValue = 1.06f,
    animationSpec = infiniteRepeatable(
      animation = tween(3200, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "breathe_scale"
  )

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(scrollState)
      .padding(horizontal = 18.dp, vertical = 20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    // Dynamic Cybernetic Emblem
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(84.dp)
    ) {
      Canvas(modifier = Modifier.size(84.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.44f * breatheScale

        rotate(orbitAngle, center) {
          drawCircle(
            color = Color(0xFF191C26),
            radius = radius,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f)
          )

          val tickCount = 8
          for (i in 0 until tickCount) {
            val angle = (i * (2 * Math.PI / tickCount)).toFloat()
            val rOuter = radius
            val rInner = radius - 5f
            drawLine(
              color = Color(0xFF38BDF8).copy(alpha = 0.65f),
              start = Offset(center.x + kotlin.math.cos(angle) * rInner, center.y + kotlin.math.sin(angle) * rInner),
              end = Offset(center.x + kotlin.math.cos(angle) * rOuter, center.y + kotlin.math.sin(angle) * rOuter),
              strokeWidth = 1.4f
            )
          }
        }
      }

      com.example.agent.ui.components.AragonLogoEmblem(
        tint = Color.White,
        modifier = Modifier.size(46.dp)
      )
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text(
      text = "ARAGON",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.ExtraBold,
      letterSpacing = 3.5.sp,
      color = Color.White
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Slogan with Typewriter Effect
    AmoledTypewriterQuote(
      quote = "From ambiguous intent to deterministic reality."
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Middle Aragon Model: Interactive Model Selector Capsule in AboutAragonScreen theme
    val modelInteraction = remember { MutableInteractionSource() }
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = Color(0xFF07070B),
      shadowElevation = 4.dp,
      border = BorderStroke(1.dp, Color(0xFF191C26)),
      modifier = Modifier
        .tactilePress(modelInteraction)
        .clip(RoundedCornerShape(14.dp))
        .clickable(
          interactionSource = modelInteraction,
          indication = ripple(color = Color.White.copy(alpha = 0.15f)),
          role = Role.Button,
          onClick = onOpenModelSelect
        )
        .testTag("home_middle_model_selector")
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
          .background(
            Brush.horizontalGradient(
              colors = listOf(Color(0xFF38BDF8).copy(alpha = 0.10f), Color(0xFF07070B))
            )
          )
          .padding(horizontal = 14.dp, vertical = 7.dp)
      ) {
        Box(
          modifier = Modifier
            .size(6.5.dp)
            .clip(CircleShape)
            .background(Color(0xFF10B981))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "ENGINE",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 8.5.sp,
          letterSpacing = 0.8.sp,
          color = Color(0xFF94A3B8)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = currentModelName,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.ExtraBold,
          fontSize = 11.sp,
          color = Color(0xFFF8FAFC)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = Color(0xFF38BDF8).copy(alpha = 0.14f),
          border = BorderStroke(0.7.dp, Color(0xFF38BDF8).copy(alpha = 0.45f))
        ) {
          Text(
            text = "SWITCH ▾",
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 8.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.6.sp,
            color = Color(0xFF38BDF8),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "Autonomous Intelligence Engine · E2B Sandbox MicroVM",
      fontFamily = JetBrainsMonoFontFamily,
      fontSize = 9.sp,
      letterSpacing = 0.4.sp,
      color = Color(0xFF64748B),
      textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(18.dp))

    // Overhauled Autonomous Workflow Blueprints Panel
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 600.dp),
      contentAlignment = Alignment.Center
    ) {
      if (showSuggestions) {
        com.example.agent.ui.components.AmoledWorkflowSuggestions(
          onSelectPrompt = onSelectSuggestionPrompt,
          onRunPromptImmediately = onRunSuggestionPrompt,
          onDismiss = { showSuggestions = false }
        )
      } else {
        com.example.agent.ui.components.AmoledWorkflowReopenTrigger(
          onReopen = { showSuggestions = true }
        )
      }
    }
  }
}

/**
 * AMOLED Typewriter Quote:
 * Animates inspiring text character-by-character with a sleek pulsing cursor,
 * unified JetBrains Mono typography, centered justification, and pure AMOLED styling.
 */
@Composable
fun AmoledTypewriterQuote(
  quote: String,
  modifier: Modifier = Modifier
) {
  var displayedLength by remember(quote) { mutableIntStateOf(0) }

  LaunchedEffect(quote) {
    displayedLength = 0
    for (i in 1..quote.length) {
      displayedLength = i
      kotlinx.coroutines.delay(32L)
    }
  }

  val infiniteTransition = rememberInfiniteTransition(label = "quote_cursor_trans")
  val cursorAlpha by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 0f,
    animationSpec = infiniteRepeatable(
      animation = tween(450, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "quote_cursor_alpha"
  )

  val displayText = remember(quote, displayedLength) {
    quote.take(displayedLength)
  }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = displayText,
      fontFamily = JetBrainsMonoFontFamily,
      fontSize = 11.5.sp,
      fontWeight = FontWeight.Medium,
      letterSpacing = 0.6.sp,
      color = Color(0xFFCBD5E1),
      textAlign = TextAlign.Center
    )
    Text(
      text = "▌",
      fontFamily = JetBrainsMonoFontFamily,
      fontSize = 11.5.sp,
      color = Color(0xFF38BDF8).copy(alpha = cursorAlpha),
      modifier = Modifier.padding(start = 2.dp)
    )
  }
}

/**
 * Normal Model Selector Dialog:
 * Shows list of available models cleanly in unified AMOLED style.
 */
@Composable
fun ModelSelectorDialog(
  currentProvider: ProviderType,
  currentNvidiaModel: String,
  onSelectSandbox: () -> Unit,
  onSelectNvidiaModel: (String) -> Unit,
  onSelectGemini: () -> Unit,
  onOpenDeveloperSettings: () -> Unit,
  onDismiss: () -> Unit
) {
  val vScroll = rememberScrollState()
  var searchQuery by remember { mutableStateOf("") }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = Color(0xFF050508),
      shadowElevation = 24.dp,
      border = BorderStroke(1.dp, Color(0xFF191C26)),
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 560.dp)
        .heightIn(max = 600.dp)
        .testTag("model_selector_dialog")
    ) {
      Column(
        modifier = Modifier
          .background(
            Brush.radialGradient(
              colors = listOf(Color(0xFF0F203C).copy(alpha = 0.45f), Color(0xFF050508)),
              center = Offset(220f, 50f),
              radius = 560f
            )
          )
          .padding(16.dp)
      ) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text(
              text = "INFERENCE TOPOLOGY",
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 8.5.sp,
              color = Color(0xFF38BDF8),
              letterSpacing = 1.2.sp
            )
            Text(
              text = "SELECT AI MODEL",
              style = MaterialTheme.typography.titleSmall,
              fontFamily = InterFontFamily,
              fontWeight = FontWeight.ExtraBold,
              color = Color(0xFFF8FAFC),
              letterSpacing = 0.8.sp
            )
          }

          AragonHeaderButton(
            text = "CLOSE",
            onClick = onDismiss,
            variant = AragonButtonVariant.MUTED,
            testTag = "close_model_selector_btn"
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search Bar for Models (AboutAragonScreen aesthetic)
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = Color(0xFF07070B),
          border = BorderStroke(1.dp, Color(0xFF191C26)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
          ) {
            Text(
              text = "FILTER",
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 8.5.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF38BDF8),
              modifier = Modifier.padding(end = 6.dp)
            )
            BasicTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
                .testTag("model_search_input"),
              singleLine = true,
              textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                color = Color.White
              ),
              cursorBrush = SolidColor(Color(0xFF38BDF8)),
              decorationBox = { innerTextField ->
                if (searchQuery.isEmpty()) {
                  Text(
                    text = "Search by model name or keyword...",
                    fontFamily = InterFontFamily,
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                  )
                }
                innerTextField()
              }
            )
            if (searchQuery.isNotBlank()) {
              Text(
                text = "✕",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                color = Color(0xFF94A3B8),
                modifier = Modifier
                  .clip(RoundedCornerShape(3.dp))
                  .clickable { searchQuery = "" }
                  .padding(4.dp)
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(vScroll),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          val query = searchQuery.trim().lowercase()

          // Section 1: Built-in engine (no external model API)
          if (query.isEmpty() || "autonomous".contains(query) || "sandbox".contains(query) || "engine".contains(query)) {
            Text(
              text = "BUILT-IN ENGINE (NO KEY REQUIRED)",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              color = Color(0xFF64748B),
              fontSize = 9.sp
            )

            val isSandboxActive = currentProvider == ProviderType.SANDBOX_ENGINE
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = if (isSandboxActive) Color(0xFF161624) else Color(0xFF0F0F14),
              border = BorderStroke(1.dp, if (isSandboxActive) Color(0xFF38BDF8) else Color(0xFF1E1E28)),
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectSandbox() }
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "Autonomous Sandbox Engine",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSandboxActive) Color.White else Color(0xFFF1F5F9)
                  )
                  Text(
                    text = "Built-in rule-based agent loop driving the same sandbox tools — no model API key needed",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = InterFontFamily,
                    color = if (isSandboxActive) Color(0xFF94A3B8) else Color(0xFF64748B),
                    fontSize = 10.sp
                  )
                }
                if (isSandboxActive) {
                  Text(
                    text = "[ACTIVE]",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = Color(0xFF10B981)
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(6.dp))
          }

          // Section 2: NVIDIA NIM Cloud Models
          val filteredNvidiaModels = NvidiaNimModels.CATALOG.filter { entry ->
            query.isEmpty() ||
              entry.name.lowercase().contains(query) ||
              entry.id.lowercase().contains(query) ||
              entry.badge.lowercase().contains(query) ||
              entry.description.lowercase().contains(query)
          }

          if (filteredNvidiaModels.isNotEmpty()) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = "NVIDIA NIM CLOUD MODELS (${filteredNvidiaModels.size})",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = Color(0xFF64748B),
                fontSize = 9.sp
              )
              Text(
                text = "[LIVE SYNCED]",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF10B981)
              )
            }

            filteredNvidiaModels.forEach { modelEntry ->
              val isModelActive = currentProvider == ProviderType.NVIDIA_NIM && currentNvidiaModel == modelEntry.id
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isModelActive) Color(0xFF161624) else Color(0xFF0F0F14),
                border = BorderStroke(1.dp, if (isModelActive) Color(0xFF38BDF8) else Color(0xFF1E1E28)),
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { onSelectNvidiaModel(modelEntry.id) }
              ) {
                Row(
                  modifier = Modifier.padding(10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Text(
                        text = modelEntry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isModelActive) Color.White else Color(0xFFF1F5F9)
                      )
                      if (modelEntry.badge.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                          text = "[${modelEntry.badge}]",
                          style = MaterialTheme.typography.labelSmall,
                          fontFamily = JetBrainsMonoFontFamily,
                          fontSize = 8.sp,
                          color = Color(0xFF38BDF8)
                        )
                      }
                    }
                    Text(
                      text = modelEntry.description,
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = InterFontFamily,
                      color = if (isModelActive) Color(0xFF94A3B8) else Color(0xFF64748B),
                      fontSize = 10.sp,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                  }

                  if (isModelActive) {
                    Text(
                      text = "[ACTIVE]",
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = JetBrainsMonoFontFamily,
                      fontWeight = FontWeight.Bold,
                      fontSize = 9.sp,
                      color = Color(0xFF10B981)
                    )
                  }
                }
              }
            }

            Spacer(modifier = Modifier.height(6.dp))
          }

          // Section 3: Google Gemini
          if (query.isEmpty() || "google".contains(query) || "gemini".contains(query)) {
            Text(
              text = "GOOGLE CLOUD",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              color = Color(0xFF64748B),
              fontSize = 9.sp
            )

            val isGeminiActive = currentProvider == ProviderType.GEMINI_LIVE_API
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = if (isGeminiActive) Color(0xFF161624) else Color(0xFF0F0F14),
              border = BorderStroke(1.dp, if (isGeminiActive) Color(0xFF38BDF8) else Color(0xFF1E1E28)),
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectGemini() }
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "Google Gemini Live",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isGeminiActive) Color.White else Color(0xFFF1F5F9)
                  )
                  Text(
                    text = "gemini-2.5-flash with function calling",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = InterFontFamily,
                    color = if (isGeminiActive) Color(0xFF94A3B8) else Color(0xFF64748B),
                    fontSize = 10.sp
                  )
                }
                if (isGeminiActive) {
                  Text(
                    text = "[ACTIVE]",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = Color(0xFF10B981)
                  )
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Direct Access to Developer Configuration (Keys & Sandbox)
        AragonActionButton(
          text = "CONFIGURE KEYS & SANDBOX (SETTINGS)",
          onClick = onOpenDeveloperSettings,
          modifier = Modifier.fillMaxWidth(),
          variant = AragonButtonVariant.PRIMARY,
          height = 36.dp,
          testTag = "model_dialog_dev_settings_btn"
        )
      }
    }
  }
}

/**
 * Developer Settings & Full Configuration Dialog:
 * ONLY opened when the secret build number is tapped 10 times in About tab.
 */
@Composable
fun SettingsProviderDialog(
  currentProvider: ProviderType,
  initialNvidiaApiKey: String,
  initialNvidiaApiKeys: List<String> = emptyList(),
  initialNvidiaModel: String,
  initialNvidiaBaseUrl: String,
  initialNvidiaTemperature: Double,
  initialNvidiaMaxTokens: Int,
  geminiKey: String,
  connectionStatus: ConnectionStatus,
  keyPoolSummary: String = "",
  initialE2bApiKey: String = "",
  initialE2bTemplate: String = "base",
  initialE2bApiUrl: String = "https://api.e2b.app",
  sandboxConnectionStatus: ConnectionStatus = ConnectionStatus.Idle,
  onTestSandboxConnection: (e2bKey: String, template: String, url: String) -> Unit = { _, _, _ -> },
  onResetSandboxConnectionStatus: () -> Unit = {},
  onSaveSandboxConfig: (e2bKey: String, template: String, url: String) -> Unit = { _, _, _ -> },
  onTestConnection: () -> Unit,
  onResetConnectionStatus: () -> Unit,
  onProviderChange: (ProviderType) -> Unit,
  onSaveNvidiaConfig: (keys: List<String>, model: String, baseUrl: String, temp: Double, maxTokens: Int) -> Unit,
  onSaveGeminiKey: (String) -> Unit,
  onDismiss: () -> Unit
) {
  var selectedType by remember { mutableStateOf(currentProvider) }

  // Initial key pool setup: populate with up to 15 keys, ensuring at least 1 slot
  val initialPool = remember(initialNvidiaApiKeys, initialNvidiaApiKey) {
    val list = initialNvidiaApiKeys.toMutableList()
    if (list.isEmpty() && initialNvidiaApiKey.isNotBlank()) {
      list.add(initialNvidiaApiKey)
    }
    if (list.isEmpty()) {
      list.add("")
    }
    list
  }
  val keyPoolList = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(initialPool) } }
  var isKeyPoolExpanded by remember { mutableStateOf(false) }
  var isApiKeyVisible by remember { mutableStateOf(false) }

  var nvidiaModelInput by remember { mutableStateOf(initialNvidiaModel) }
  var nvidiaBaseUrlInput by remember { mutableStateOf(initialNvidiaBaseUrl) }
  var nvidiaTempInput by remember { mutableStateOf(initialNvidiaTemperature.toString()) }
  var nvidiaMaxTokensInput by remember { mutableStateOf(initialNvidiaMaxTokens.toString()) }
  var geminiKeyInput by remember { mutableStateOf(geminiKey) }

  var e2bApiKeyInput by remember { mutableStateOf(initialE2bApiKey) }
  var e2bTemplateInput by remember { mutableStateOf(initialE2bTemplate) }
  var e2bApiUrlInput by remember { mutableStateOf(initialE2bApiUrl) }
  var isE2bKeyVisible by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = {
      onResetConnectionStatus()
      onResetSandboxConnectionStatus()
      onDismiss()
    },
    containerColor = Color(0xFF050508),
    shape = RoundedCornerShape(22.dp),
    title = {
      Column {
        Text(
          text = "DEVELOPER CONFIGURATION",
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 8.5.sp,
          color = Color(0xFF38BDF8),
          letterSpacing = 1.2.sp
        )
        Text(
          text = "Settings & Credentials",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.ExtraBold,
          color = Color(0xFFF8FAFC)
        )
        Text(
          text = "AI provider, 15-key rotation pool, and the E2B Firecracker MicroVM sandbox.",
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF94A3B8),
          fontSize = 10.sp,
          modifier = Modifier.padding(top = 2.dp)
        )
      }
    },
    text = {
      val scrollState = rememberScrollState()
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(scrollState)
          .padding(top = 10.dp, bottom = 10.dp)
      ) {
        Text(
          "AI PROVIDER",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          color = AmoledTextMuted,
          fontSize = 9.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Option 1: NVIDIA NIM
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (selectedType == ProviderType.NVIDIA_NIM) Color(0xFF181818) else Color(0xFF0F0F0F),
          border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selectedType == ProviderType.NVIDIA_NIM) AmoledActionPrimary else AmoledBorderSubtle
          ),
          modifier = Modifier
            .fillMaxWidth()
            .clickable { selectedType = ProviderType.NVIDIA_NIM }
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp)
          ) {
            RadioButton(
              selected = selectedType == ProviderType.NVIDIA_NIM,
              onClick = { selectedType = ProviderType.NVIDIA_NIM },
              colors = RadioButtonDefaults.colors(
                selectedColor = AmoledActionPrimary,
                unselectedColor = AmoledIconGrey
              )
            )
            Column(modifier = Modifier.padding(start = 6.dp)) {
              Text(
                "NVIDIA NIM Cloud",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = AmoledTextPrimary
              )
              Text(
                "Tool calling with automatic model fallback recovery",
                style = MaterialTheme.typography.labelSmall,
                color = AmoledTextMuted
              )
            }
          }
        }

        // Expanded NVIDIA NIM settings
        AnimatedVisibility(visible = selectedType == ProviderType.NVIDIA_NIM) {
          Column(modifier = Modifier.padding(top = 10.dp)) {
            // Live Connection Test
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = Color(0xFF121212),
              border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(10.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = "Endpoint Verification",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmoledIconGreyLight,
                    fontWeight = FontWeight.SemiBold
                  )
                  Button(
                    onClick = onTestConnection,
                    enabled = connectionStatus !is ConnectionStatus.Testing,
                    colors = ButtonDefaults.buttonColors(
                      containerColor = Color(0xFF222222),
                      contentColor = AmoledTextPrimary
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                  ) {
                    if (connectionStatus is ConnectionStatus.Testing) {
                      CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color = AmoledTextPrimary
                      )
                      Spacer(modifier = Modifier.width(4.dp))
                      Text("Testing...", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                    } else {
                      Text("TEST NIM", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp)
                    }
                  }
                }

                when (val status = connectionStatus) {
                  is ConnectionStatus.Success -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                      shape = RoundedCornerShape(4.dp),
                      color = Color(0xFF102015),
                      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusSuccess.copy(alpha = 0.3f)),
                      modifier = Modifier.fillMaxWidth()
                    ) {
                      Text(
                        text = "Verified: ${status.modelCount} models online on build.nvidia.com",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = AmoledStatusSuccess,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(6.dp)
                      )
                    }
                  }
                  is ConnectionStatus.Error -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                      shape = RoundedCornerShape(4.dp),
                      color = Color(0xFF221111),
                      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusError.copy(alpha = 0.3f)),
                      modifier = Modifier.fillMaxWidth()
                    ) {
                      Text(
                        text = status.message,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = AmoledStatusError,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(6.dp)
                      )
                    }
                  }
                  else -> {}
                }

                if (keyPoolSummary.isNotBlank()) {
                  Spacer(modifier = Modifier.height(6.dp))
                  Text(
                    text = "Key pool health: $keyPoolSummary",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = AmoledTextMuted,
                    fontSize = 9.sp
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Model ID
            OutlinedTextField(
              value = nvidiaModelInput,
              onValueChange = { nvidiaModelInput = it },
              label = { Text("Model ID") },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
              shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // API Key Pool (Up to 15 Keys with automatic round-robin rotation & fallback)
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = Color(0xFF141414),
              border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(10.dp)) {
                // Header: Clickable to Expand / Collapse
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isKeyPoolExpanded = !isKeyPoolExpanded },
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                      imageVector = Icons.Default.Key,
                      contentDescription = null,
                      tint = AmoledActionPrimary,
                      modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                      Text(
                        text = "API Key Pool (Up to 15 Keys)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = AmoledTextPrimary
                      )
                      val activeCount = keyPoolList.count { it.isNotBlank() }
                      val poolStatusText = when (activeCount) {
                        0 -> "No keys configured"
                        1 -> "1 key active • Single key mode"
                        else -> "$activeCount keys active • Auto-rotation & fallback"
                      }
                      Text(
                        text = poolStatusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (activeCount > 1) AmoledStatusSuccess else if (activeCount == 1) AmoledTextSecondary else AmoledTextMuted,
                        fontSize = 9.sp
                      )
                    }
                  }

                  // Expand / Collapse Action Button
                  Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF202020),
                    modifier = Modifier.clickable { isKeyPoolExpanded = !isKeyPoolExpanded }
                  ) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                      Text(
                        text = if (isKeyPoolExpanded) "COLLAPSE" else "EXPAND (15 KEYS)",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = AmoledActionPrimary
                      )
                      Spacer(modifier = Modifier.width(2.dp))
                      Icon(
                        imageVector = if (isKeyPoolExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isKeyPoolExpanded) "Collapse" else "Expand",
                        tint = AmoledActionPrimary,
                        modifier = Modifier.size(12.dp)
                      )
                    }
                  }
                }

                // If COLLAPSED: Show compact primary key input or summary
                if (!isKeyPoolExpanded) {
                  Spacer(modifier = Modifier.height(8.dp))
                  val primaryKey = keyPoolList.getOrElse(0) { "" }
                  OutlinedTextField(
                    value = primaryKey,
                    onValueChange = { newVal ->
                      if (keyPoolList.isEmpty()) keyPoolList.add(newVal)
                      else keyPoolList[0] = newVal
                    },
                    label = { Text("Primary Key (Key 1)") },
                    placeholder = { Text("nvapi-...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                      Text(
                        text = if (isApiKeyVisible) "[HIDE]" else "[SHOW]",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 9.sp,
                        color = AmoledIconGrey,
                        modifier = Modifier
                          .clickable { isApiKeyVisible = !isApiKeyVisible }
                          .padding(horizontal = 6.dp, vertical = 4.dp)
                      )
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(8.dp)
                  )
                  val extraCount = keyPoolList.drop(1).count { it.isNotBlank() }
                  if (extraCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                      text = "+ $extraCount additional backup key(s) in pool. Tap EXPAND to view or edit all slots.",
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = JetBrainsMonoFontFamily,
                      color = AmoledStatusSuccess,
                      fontSize = 8.5.sp
                    )
                  }
                } else {
                  // If EXPANDED: Show full 15-key pool management
                  Spacer(modifier = Modifier.height(10.dp))
                  Text(
                    text = "Configure up to 15 API keys. Aragon automatically distributes requests across active keys and fails over instantly on 429 rate limits or quota drops.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmoledTextMuted,
                    fontSize = 9.sp
                  )
                  Spacer(modifier = Modifier.height(8.dp))

                  Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    keyPoolList.forEachIndexed { index, keyVal ->
                      Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF0C0C0C),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                      ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                          Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                          ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                              Text(
                                text = "Slot ${index + 1} ${if (index == 0) "(Primary)" else "(Rotation/Backup)"}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = AmoledTextPrimary,
                                fontSize = 9.sp
                              )
                              Spacer(modifier = Modifier.width(6.dp))
                              Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = if (keyVal.isNotBlank()) Color(0xFF102815) else Color(0xFF1E1E1E)
                              ) {
                                Text(
                                  text = if (keyVal.isNotBlank()) "ACTIVE" else "EMPTY",
                                  style = MaterialTheme.typography.labelSmall,
                                  fontFamily = JetBrainsMonoFontFamily,
                                  color = if (keyVal.isNotBlank()) AmoledStatusSuccess else AmoledTextMuted,
                                  fontSize = 8.sp,
                                  modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                              }
                            }

                            if (keyPoolList.size > 1) {
                              Text(
                                text = "REMOVE",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = AmoledStatusError,
                                fontSize = 8.sp,
                                modifier = Modifier
                                  .clickable { keyPoolList.removeAt(index) }
                                  .padding(2.dp)
                              )
                            }
                          }

                          Spacer(modifier = Modifier.height(4.dp))
                          OutlinedTextField(
                            value = keyVal,
                            onValueChange = { keyPoolList[index] = it },
                            placeholder = { Text("nvapi-...", fontSize = 10.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp),
                            shape = RoundedCornerShape(6.dp)
                          )
                        }
                      }
                    }

                    // Add Slot & Collapse Controls
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      if (keyPoolList.size < 15) {
                        OutlinedButton(
                          onClick = { keyPoolList.add("") },
                          shape = RoundedCornerShape(6.dp),
                          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                          modifier = Modifier.height(28.dp)
                        ) {
                          Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp)
                          )
                          Spacer(modifier = Modifier.width(4.dp))
                          Text("Add Key Slot (${keyPoolList.size}/15)", fontSize = 8.5.sp, fontFamily = JetBrainsMonoFontFamily)
                        }
                      }

                      TextButton(
                        onClick = { isKeyPoolExpanded = false },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                      ) {
                        Text("Done / Collapse", fontSize = 8.5.sp, color = AmoledActionPrimary)
                      }
                    }
                  }
                }
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
              value = nvidiaBaseUrlInput,
              onValueChange = { nvidiaBaseUrlInput = it },
              label = { Text("Base URL") },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
              shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(
                value = nvidiaTempInput,
                onValueChange = { nvidiaTempInput = it },
                label = { Text("Temperature") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(8.dp)
              )
              OutlinedTextField(
                value = nvidiaMaxTokensInput,
                onValueChange = { nvidiaMaxTokensInput = it },
                label = { Text("Max Tokens") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(8.dp)
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Option 2: Sandbox Engine
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (selectedType == ProviderType.SANDBOX_ENGINE) Color(0xFF181818) else Color(0xFF0F0F0F),
          border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selectedType == ProviderType.SANDBOX_ENGINE) AmoledActionPrimary else AmoledBorderSubtle
          ),
          modifier = Modifier
            .fillMaxWidth()
            .clickable { selectedType = ProviderType.SANDBOX_ENGINE }
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp)
          ) {
            RadioButton(
              selected = selectedType == ProviderType.SANDBOX_ENGINE,
              onClick = { selectedType = ProviderType.SANDBOX_ENGINE },
              colors = RadioButtonDefaults.colors(
                selectedColor = AmoledActionPrimary,
                unselectedColor = AmoledIconGrey
              )
            )
            Column(modifier = Modifier.padding(start = 6.dp)) {
              Text("Autonomous Sandbox Engine", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = AmoledTextPrimary)
              Text("Built-in rule-based agent loop — no model API key required", style = MaterialTheme.typography.labelSmall, color = AmoledTextMuted)
            }
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Option 3: Gemini Live API
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (selectedType == ProviderType.GEMINI_LIVE_API) Color(0xFF181818) else Color(0xFF0F0F0F),
          border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selectedType == ProviderType.GEMINI_LIVE_API) AmoledActionPrimary else AmoledBorderSubtle
          ),
          modifier = Modifier
            .fillMaxWidth()
            .clickable { selectedType = ProviderType.GEMINI_LIVE_API }
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp)
          ) {
            RadioButton(
              selected = selectedType == ProviderType.GEMINI_LIVE_API,
              onClick = { selectedType = ProviderType.GEMINI_LIVE_API },
              colors = RadioButtonDefaults.colors(
                selectedColor = AmoledActionPrimary,
                unselectedColor = AmoledIconGrey
              )
            )
            Column(modifier = Modifier.padding(start = 6.dp)) {
              Text("Google Gemini Live API", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = AmoledTextPrimary)
              Text("gemini-2.5-flash with function calling", style = MaterialTheme.typography.labelSmall, color = AmoledTextMuted)
            }
          }
        }

        AnimatedVisibility(visible = selectedType == ProviderType.GEMINI_LIVE_API) {
          Column(modifier = Modifier.padding(top = 10.dp)) {
            OutlinedTextField(
              value = geminiKeyInput,
              onValueChange = { geminiKeyInput = it },
              label = { Text("Gemini API Key") },
              placeholder = { Text("AIzaSy...") },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              textStyle = MaterialTheme.typography.bodySmall,
              shape = RoundedCornerShape(8.dp)
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
          "EXECUTION SANDBOX (E2B)",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          color = AmoledTextMuted,
          fontSize = 9.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Dedicated E2B Cloud Sandbox
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = Color(0xFF181818),
          border = androidx.compose.foundation.BorderStroke(1.dp, AmoledActionPrimary),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp)
          ) {
            Column(modifier = Modifier.padding(start = 6.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  "E2B Cloud Sandboxes",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = AmoledTextPrimary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                  shape = RoundedCornerShape(3.dp),
                  color = Color(0xFF1B2A1E)
                ) {
                  Text(
                    text = "FIRECRACKER",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = AmoledStatusSuccess,
                    fontSize = 8.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                  )
                }
              }
              Text(
                "Hardware-isolated microVMs via https://e2b.dev",
                style = MaterialTheme.typography.labelSmall,
                color = AmoledTextMuted
              )
            }
          }
        }

        Column(modifier = Modifier.padding(top = 10.dp)) {
          // Live E2B Connection Test
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF121212),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(10.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = "E2B Cloud Verification",
                  style = MaterialTheme.typography.labelSmall,
                  color = AmoledIconGreyLight,
                  fontWeight = FontWeight.SemiBold
                )
                Button(
                  onClick = {
                    onTestSandboxConnection(
                      e2bApiKeyInput,
                      e2bTemplateInput,
                      e2bApiUrlInput
                    )
                  },
                  enabled = sandboxConnectionStatus !is ConnectionStatus.Testing,
                  colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF222222),
                    contentColor = AmoledTextPrimary
                  ),
                  shape = RoundedCornerShape(6.dp),
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                  modifier = Modifier.height(28.dp)
                ) {
                  if (sandboxConnectionStatus is ConnectionStatus.Testing) {
                    CircularProgressIndicator(
                      modifier = Modifier.size(10.dp),
                      strokeWidth = 1.5.dp,
                      color = AmoledTextPrimary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Testing...", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                  } else {
                    Text("TEST E2B", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp)
                  }
                }
              }

              when (val status = sandboxConnectionStatus) {
                is ConnectionStatus.Success -> {
                  Spacer(modifier = Modifier.height(6.dp))
                  Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF102015),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusSuccess.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Text(
                      text = status.message,
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = JetBrainsMonoFontFamily,
                      color = AmoledStatusSuccess,
                      fontSize = 9.sp,
                      modifier = Modifier.padding(6.dp)
                    )
                  }
                }
                is ConnectionStatus.Error -> {
                  Spacer(modifier = Modifier.height(6.dp))
                  Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF221111),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusError.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Text(
                      text = status.message,
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = JetBrainsMonoFontFamily,
                      color = AmoledStatusError,
                      fontSize = 9.sp,
                      modifier = Modifier.padding(6.dp)
                    )
                  }
                }
                else -> {}
              }
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          OutlinedTextField(
            value = e2bApiKeyInput,
            onValueChange = { e2bApiKeyInput = it },
            label = { Text("E2B API Key (e2b_...)") },
            placeholder = { Text("e2b_...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (isE2bKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
              Text(
                text = if (isE2bKeyVisible) "HIDE" else "SHOW",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = AmoledActionPrimary,
                fontSize = 9.sp,
                modifier = Modifier
                  .clickable { isE2bKeyVisible = !isE2bKeyVisible }
                  .padding(8.dp)
              )
            },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp),
            shape = RoundedCornerShape(8.dp)
          )

          Spacer(modifier = Modifier.height(8.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedTextField(
              value = e2bTemplateInput,
              onValueChange = { e2bTemplateInput = it },
              label = { Text("Template ID") },
              placeholder = { Text("base") },
              modifier = Modifier.weight(1f),
              singleLine = true,
              textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp),
              shape = RoundedCornerShape(8.dp)
            )
            OutlinedTextField(
              value = e2bApiUrlInput,
              onValueChange = { e2bApiUrlInput = it },
              label = { Text("API Gateway") },
              placeholder = { Text("https://api.e2b.app") },
              modifier = Modifier.weight(1.3f),
              singleLine = true,
              textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp),
              shape = RoundedCornerShape(8.dp)
            )
          }
        }
      }
    },
    confirmButton = {
      AragonActionButton(
        text = "APPLY CHANGES",
        onClick = {
          onProviderChange(selectedType)
          if (selectedType == ProviderType.NVIDIA_NIM) {
            onSaveNvidiaConfig(
              keyPoolList.map { it.trim() }.filter { it.isNotBlank() },
              nvidiaModelInput,
              nvidiaBaseUrlInput,
              nvidiaTempInput.toDoubleOrNull() ?: 0.2,
              nvidiaMaxTokensInput.toIntOrNull() ?: 4096
            )
          } else if (selectedType == ProviderType.GEMINI_LIVE_API) {
            onSaveGeminiKey(geminiKeyInput)
          }
          onSaveSandboxConfig(
            e2bApiKeyInput,
            e2bTemplateInput,
            e2bApiUrlInput
          )
          onResetConnectionStatus()
          onResetSandboxConnectionStatus()
          onDismiss()
        },
        variant = AragonButtonVariant.PRIMARY,
        height = 34.dp,
        testTag = "settings_apply_btn"
      )
    },
    dismissButton = {
      AragonActionButton(
        text = "CANCEL",
        onClick = {
          onResetConnectionStatus()
          onResetSandboxConnectionStatus()
          onDismiss()
        },
        variant = AragonButtonVariant.MUTED,
        height = 34.dp,
        testTag = "settings_cancel_btn"
      )
    }
  )
}

/**
 * Task Execution History View:
 * Displays all past tasks executed by Aragon across sessions with status badges,
 * timestamps, objective goals, deliverables produced, and one-tap re-execution.
 */
/**
 * Task Execution History View:
 * Displays all past tasks executed by Aragon across sessions with status badges,
 * timestamps, objective goals, deliverables produced, one-tap re-execution,
 * and deep inspection of saved conversations, planning stages, and execution feeds.
 */
@Composable
fun TaskHistoryView(
  taskHistory: List<TaskRecord>,
  onSelectTask: (String) -> Unit,
  onRestoreTask: (TaskRecord) -> Unit,
  onClearHistory: () -> Unit,
  modifier: Modifier = Modifier
) {
  var viewingRecord by remember { mutableStateOf<TaskRecord?>(null) }

  // Handle system back gesture when inspecting a full conversation
  BackHandler(enabled = viewingRecord != null) {
    viewingRecord = null
  }

  if (viewingRecord != null) {
    TaskFullConversationHistoryView(
      record = viewingRecord!!,
      onBack = { viewingRecord = null },
      onReRun = {
        val goal = viewingRecord!!.goal
        viewingRecord = null
        onSelectTask(goal)
      },
      onRestore = {
        val record = viewingRecord!!
        viewingRecord = null
        onRestoreTask(record)
      },
      modifier = modifier
    )
  } else {
    Column(
      modifier = modifier
        .fillMaxSize()
        .background(AmoledBackground)
    ) {
      // Header Row with count and Clear button
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "TASK LOGS & ARCHIVE",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = AmoledIconGreyLight
          )
          if (taskHistory.isNotEmpty()) {
            Spacer(modifier = Modifier.width(5.dp))
            Surface(
              shape = RoundedCornerShape(2.dp),
              color = Color(0xFF1E1A12),
              modifier = Modifier.padding(start = 2.dp)
            ) {
              Text(
                text = "${taskHistory.size}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                color = AmoledActionPrimary,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.5.dp)
              )
            }
          }
        }

        if (taskHistory.isNotEmpty()) {
          AragonActionButton(
            text = "CLEAR ALL",
            onClick = onClearHistory,
            variant = AragonButtonVariant.DANGER,
            height = 22.dp,
            testTag = "clear_all_history_btn"
          )
        }
      }

      if (taskHistory.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
          contentAlignment = Alignment.Center
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = "[ARCHIVE EMPTY]",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              color = AmoledIconGreyLight,
              fontSize = 10.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "No Previous Task History",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              color = AmoledTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "Executed missions, conversations, tool outputs, and generated deliverables are preserved here with full transcript inspection.",
              style = MaterialTheme.typography.bodySmall,
              color = AmoledTextMuted,
              textAlign = TextAlign.Center,
              fontSize = 11.5.sp
            )
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          itemsIndexed(
            items = taskHistory,
            key = { _, record -> record.id }
          ) { _, record ->
            CompactTaskHistoryCard(
              record = record,
              onClick = { viewingRecord = record },
              onReRun = { onSelectTask(record.goal) }
            )
          }
        }
      }
    }
  }
}

/**
 * Sleek, compact task history card.
 * Minimalist footprint with immediate visual status, goal preview, deliverables count, and tap-to-enter conversation history.
 */
@Composable
fun CompactTaskHistoryCard(
  record: TaskRecord,
  onClick: () -> Unit,
  onReRun: () -> Unit
) {
  val statusColor = when (record.status) {
    AgentStatus.COMPLETED -> AmoledStatusSuccess
    AgentStatus.PARTIAL_COMPLETION -> AmoledStatusWarning
    AgentStatus.FAILED -> AmoledStatusError
    AgentStatus.CANCELLED -> AmoledTextMuted
    AgentStatus.THINKING, AgentStatus.PLANNING, AgentStatus.REPLANNING, AgentStatus.EXECUTING_TOOL, AgentStatus.OBSERVING, AgentStatus.VERIFYING -> AmoledActionPrimary
    else -> AmoledIconGreyLight
  }

  val statusLabel = when (record.status) {
    AgentStatus.COMPLETED -> "DONE"
    AgentStatus.PARTIAL_COMPLETION -> "PART"
    AgentStatus.FAILED -> "FAIL"
    AgentStatus.CANCELLED -> "STOP"
    AgentStatus.THINKING, AgentStatus.PLANNING, AgentStatus.REPLANNING, AgentStatus.EXECUTING_TOOL, AgentStatus.OBSERVING, AgentStatus.VERIFYING -> "RUN"
    else -> "IDLE"
  }

  val formattedDate = remember(record.timestamp) {
    SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date(record.timestamp))
  }

  val executionDuration = remember(record.startedAt, record.completedAt) {
    if (record.completedAt != null && record.completedAt > record.startedAt) {
      val diffSec = (record.completedAt - record.startedAt) / 1000
      if (diffSec < 60) "${diffSec}s" else "${diffSec / 60}m ${diffSec % 60}s"
    } else null
  }

  Surface(
    shape = RoundedCornerShape(8.dp),
    color = Color(0xFF0C0C0E),
    border = BorderStroke(1.dp, Color(0xFF1B1B1F)),
    modifier = Modifier
      .fillMaxWidth()
      .clickable(
        role = Role.Button,
        onClickLabel = "View full task conversation",
        onClick = onClick
      )
      .testTag("task_history_card_${record.id}")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
      // Top meta row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          modifier = Modifier.weight(1f, fill = false),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Status Badge
          Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            color = statusColor,
            modifier = Modifier
              .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(2.5.dp))
              .border(0.8.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(2.5.dp))
              .padding(horizontal = 4.dp, vertical = 1.dp)
          )

          Spacer(modifier = Modifier.width(6.dp))

          Text(
            text = formattedDate,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 8.5.sp,
            color = AmoledIconGreyDark
          )

          if (executionDuration != null) {
            Spacer(modifier = Modifier.width(5.dp))
            Text(
              text = "• $executionDuration",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 8.5.sp,
              color = AmoledTextMuted
            )
          }

          if (record.executionFeed.isNotEmpty()) {
            Spacer(modifier = Modifier.width(5.dp))
            Text(
              text = "• ${record.executionFeed.size} steps",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 8.5.sp,
              color = AmoledActionPrimary.copy(alpha = 0.7f)
            )
          }
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          AragonActionButton(
            text = "RE-RUN",
            onClick = onReRun,
            variant = AragonButtonVariant.MUTED,
            height = 20.dp,
            testTag = "rerun_task_btn_${record.id}"
          )

          Text(
            text = "VIEW ›",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            color = AmoledActionPrimary,
            modifier = Modifier.padding(start = 2.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(5.dp))

      // Goal text (clean, 2-line max)
      Text(
        text = record.goal,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        color = AmoledTextPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )

      // Deliverables summary chips if any
      if (record.deliverables.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "DELIVERABLES:",
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 7.5.sp,
            color = AmoledIconGreyDark,
            fontWeight = FontWeight.SemiBold
          )
          for (deliv in record.deliverables) {
            Surface(
              shape = RoundedCornerShape(2.dp),
              color = AmoledStatusSuccess.copy(alpha = 0.10f),
              border = BorderStroke(0.7.dp, AmoledStatusSuccess.copy(alpha = 0.35f))
            ) {
              Text(
                text = deliv,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = AmoledStatusSuccess,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Deep inspection screen displaying the FULL conversation and execution transcript of a recorded task.
 * Includes user prompts, assistant thoughts/reasoning, tool calls, stdout, planning breakdown, and deliverables.
 */
@Composable
fun TaskFullConversationHistoryView(
  record: TaskRecord,
  onBack: () -> Unit,
  onReRun: () -> Unit,
  onRestore: () -> Unit,
  modifier: Modifier = Modifier
) {
  val scrollState = rememberScrollState()

  val statusColor = when (record.status) {
    AgentStatus.COMPLETED -> AmoledStatusSuccess
    AgentStatus.PARTIAL_COMPLETION -> AmoledStatusWarning
    AgentStatus.FAILED -> AmoledStatusError
    AgentStatus.CANCELLED -> AmoledTextMuted
    AgentStatus.THINKING, AgentStatus.PLANNING, AgentStatus.REPLANNING, AgentStatus.EXECUTING_TOOL, AgentStatus.OBSERVING, AgentStatus.VERIFYING -> AmoledActionPrimary
    else -> AmoledIconGreyLight
  }

  val formattedDate = remember(record.timestamp) {
    SimpleDateFormat("MMMM dd, yyyy • HH:mm:ss", Locale.US).format(Date(record.timestamp))
  }

  val executionDuration = remember(record.startedAt, record.completedAt) {
    if (record.completedAt != null && record.completedAt > record.startedAt) {
      val diffSec = (record.completedAt - record.startedAt) / 1000
      if (diffSec < 60) "${diffSec}s" else "${diffSec / 60}m ${diffSec % 60}s"
    } else "In progress / interrupted"
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF050508))
  ) {
    // Top Navigation Header in AboutAragonScreen theme
    Surface(
      shape = RoundedCornerShape(0.dp),
      color = Color(0xFF07070B),
      border = BorderStroke(1.dp, Color(0xFF191C26)),
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            Brush.radialGradient(
              colors = listOf(Color(0xFF0F203C).copy(alpha = 0.40f), Color(0xFF07070B)),
              center = Offset(200f, 30f),
              radius = 520f
            )
          )
          .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        AragonHeaderButton(
          text = "← BACK TO LOGS",
          onClick = onBack,
          variant = AragonButtonVariant.MUTED,
          testTag = "task_history_back_btn"
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          AragonHeaderButton(
            text = "RE-RUN",
            onClick = onReRun,
            variant = AragonButtonVariant.DEFAULT,
            testTag = "task_history_rerun_header_btn"
          )

          AragonHeaderButton(
            text = "CONTINUE TASK",
            onClick = onRestore,
            variant = AragonButtonVariant.PRIMARY,
            testTag = "task_history_continue_header_btn"
          )
        }
      }
    }

    // Fast, RAM-light LazyColumn Transcript Body
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      // Mission Overview Card
      item(key = "mission_overview") {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = Color(0xFF07070B),
          border = BorderStroke(1.dp, Color(0xFF191C26)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(14.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "TASK OBJECTIVE & METRICS",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                color = Color(0xFF38BDF8)
              )
              Surface(
                shape = RoundedCornerShape(4.dp),
                color = statusColor.copy(alpha = 0.14f),
                border = BorderStroke(0.8.dp, statusColor.copy(alpha = 0.45f))
              ) {
                Text(
                  text = record.status.name,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = FontWeight.ExtraBold,
                  fontSize = 8.sp,
                  color = statusColor,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
              }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
              text = record.goal,
              style = MaterialTheme.typography.bodyMedium,
              fontFamily = InterFontFamily,
              fontWeight = FontWeight.SemiBold,
              fontSize = 13.sp,
              color = Color(0xFFF8FAFC)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Text(
                text = "Time: $formattedDate",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 9.sp,
                color = Color(0xFF94A3B8)
              )
              Text(
                text = "Duration: $executionDuration",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 9.sp,
                color = Color(0xFF94A3B8)
              )
            }

            if (!record.sessionId.isNullOrBlank()) {
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = "Session ID: ${record.sessionId}",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 8.5.sp,
                color = Color(0xFF64748B)
              )
            }
          }
        }
      }

      // Conclusion / Summary Card
      if (!record.conclusion.isNullOrBlank() || !record.summary.isNullOrBlank()) {
        item(key = "mission_conclusion") {
          val summaryText = record.conclusion ?: record.summary ?: ""
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF07070B),
            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.40f)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Text(
                text = "MISSION SYNTHESIS & OUTCOME",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                color = Color(0xFF10B981)
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = summaryText,
                fontFamily = InterFontFamily,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = Color(0xFFF8FAFC)
              )
            }
          }
        }
      }

      // Generated Deliverables Section
      if (record.deliverables.isNotEmpty()) {
        item(key = "mission_deliverables") {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF07070B),
            border = BorderStroke(1.dp, Color(0xFF191C26)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Text(
                text = "FINAL DELIVERABLES (${record.deliverables.size})",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                color = Color(0xFF38BDF8)
              )
              Spacer(modifier = Modifier.height(6.dp))
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                record.deliverables.forEach { item ->
                  Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF050508),
                    border = BorderStroke(1.dp, Color(0xFF191C26)),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Text(
                        text = item,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                        color = Color(0xFF10B981),
                        fontWeight = FontWeight.SemiBold
                      )
                      Text(
                        text = "SAVED TO WORKSPACE",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 7.5.sp,
                        color = Color(0xFF64748B)
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }

      // Cognitive Plan Steps (if any)
      if (record.planSteps.isNotEmpty()) {
        item(key = "mission_plan") {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF07070B),
            border = BorderStroke(1.dp, Color(0xFF191C26)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Text(
                text = "COGNITIVE EXECUTION PLAN (${record.planSteps.size} STAGES)",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                color = Color(0xFFA855F7)
              )
              Spacer(modifier = Modifier.height(6.dp))
              record.planSteps.forEachIndexed { i, step ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                  verticalAlignment = Alignment.Top
                ) {
                  Text(
                    text = "[✓] ${i + 1}. ",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                  )
                  Text(
                    text = step,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = Color(0xFFCBD5E1),
                    modifier = Modifier.weight(1f)
                  )
                }
              }
            }
          }
        }
      }

      // FULL CONVERSATION & AGENTIC FEED TRANSCRIPT
      item(key = "transcript_header") {
        Text(
          text = "FULL CONVERSATION & REASONING TRANSCRIPT",
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.ExtraBold,
          fontSize = 10.sp,
          letterSpacing = 0.8.sp,
          color = Color(0xFF94A3B8),
          modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
        )
      }

      if (record.conversation.isEmpty() && record.executionFeed.isEmpty()) {
        item(key = "transcript_empty") {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF07070B),
            border = BorderStroke(1.dp, Color(0xFF191C26)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = "No detailed conversation or tool feed was archived for this mission.",
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 10.sp,
              color = Color(0xFF64748B),
              modifier = Modifier.padding(14.dp)
            )
          }
        }
      }

      // 1. Conversation turns rendered lazily
      itemsIndexed(
        items = record.conversation,
        key = { idx, _ -> "conv_turn_$idx" }
      ) { _, msg ->
        val isUser = msg.role == MessageRole.USER
        val isAssistant = msg.role == MessageRole.ASSISTANT
        val isTool = msg.role == MessageRole.TOOL

        Surface(
          shape = RoundedCornerShape(12.dp),
          color = Color(0xFF07070B),
          border = BorderStroke(
            1.dp,
            when {
              isUser -> Color(0xFF38BDF8).copy(alpha = 0.35f)
              isTool -> Color(0xFFA855F7).copy(alpha = 0.35f)
              else -> Color(0xFF191C26)
            }
          ),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(11.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = when {
                  isUser -> "USER PROMPT"
                  isAssistant -> "ARAGON AGENT"
                  isTool -> "TOOL OBSERVATION (${msg.toolName ?: "OUTPUT"})"
                  else -> msg.role.name
                },
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 8.sp,
                color = when {
                  isUser -> Color(0xFF38BDF8)
                  isAssistant -> Color(0xFF10B981)
                  else -> Color(0xFFA855F7)
                }
              )

              if (!msg.toolName.isNullOrBlank()) {
                Text(
                  text = msg.toolName,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 7.5.sp,
                  color = Color(0xFF10B981)
                )
              }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
              text = msg.content,
              fontFamily = if (isUser) InterFontFamily else JetBrainsMonoFontFamily,
              fontSize = 10.5.sp,
              color = Color(0xFFF8FAFC),
              lineHeight = 16.sp
            )
          }
        }
      }

      // 2. Execution Feed Steps (Tool Calls & Outputs) rendered lazily
      if (record.executionFeed.isNotEmpty()) {
        item(key = "tool_chain_header") {
          Text(
            text = "EXECUTION TOOL CHAIN (${record.executionFeed.size} STEPS)",
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
            color = Color(0xFF38BDF8),
            modifier = Modifier.padding(top = 6.dp)
          )
        }

        itemsIndexed(
          items = record.executionFeed,
          key = { idx, step -> "hist_step_${step.id}_$idx" }
        ) { _, step ->
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF07070B),
            border = BorderStroke(1.dp, Color(0xFF191C26)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(10.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    text = "#${step.stepNumber}",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.5.sp,
                    color = Color(0xFF38BDF8)
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = step.type.name,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 7.5.sp,
                    color = if (step.type == StepType.ERROR) AmoledStatusError else Color(0xFFCBD5E1),
                    modifier = Modifier
                      .background(Color(0xFF0D111A), RoundedCornerShape(4.dp))
                      .padding(horizontal = 5.dp, vertical = 1.dp)
                  )
                  if (!step.toolName.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                      text = step.toolName,
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 8.5.sp,
                      fontWeight = FontWeight.SemiBold,
                      color = Color(0xFF10B981)
                    )
                  }
                }

                if (step.durationMs != null) {
                  Text(
                    text = "${step.durationMs}ms",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 7.5.sp,
                    color = Color(0xFF64748B)
                  )
                }
              }

              if (step.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = step.content,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 9.5.sp,
                  color = Color(0xFFCBD5E1)
                )
              }

              if (!step.stdout.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = Color(0xFF050508),
                  border = BorderStroke(0.8.dp, Color(0xFF10B981).copy(alpha = 0.28f)),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Text(
                    text = step.stdout,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 8.sp,
                    color = Color(0xFF6EE7B7),
                    modifier = Modifier.padding(6.dp)
                  )
                }
              }
            }
          }
        }
      }

      // Bottom Action: Restore / Continue
      item(key = "restore_footer") {
        Spacer(modifier = Modifier.height(6.dp))
        AragonActionButton(
          text = "RESTORE MISSION INTO ACTIVE LIVE CHAT",
          onClick = onRestore,
          variant = AragonButtonVariant.PRIMARY,
          height = 36.dp,
          modifier = Modifier.fillMaxWidth(),
          testTag = "restore_mission_bottom_btn"
        )
        Spacer(modifier = Modifier.height(16.dp))
      }
    }
  }
}

@Composable
private fun ActiveReasoningIndicatorCard(
  isPlanning: Boolean,
  currentAction: String?,
  modifier: Modifier = Modifier
) {
  val activePulse by rememberInfiniteTransition(label = "active_reasoning_pulse").animateFloat(
    initialValue = 0.5f,
    targetValue = 0.95f,
    animationSpec = infiniteRepeatable(
      animation = tween(900, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "reasoning_border_pulse"
  )

  Surface(
    shape = RoundedCornerShape(10.dp),
    color = Color(0xFF14120E),
    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledActionPrimary.copy(alpha = activePulse)),
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 6.dp)
      .testTag("active_reasoning_card")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f)
      ) {
        Column {
          Text(
            text = if (isPlanning) "PREPARING EXECUTION PLAN" else "REASONING & SYNTHESIZING",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.5.sp,
            color = AmoledActionPrimary
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = currentAction ?: "Evaluating context and executing next action...",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = InterFontFamily,
            fontSize = 11.sp,
            color = AmoledTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
      com.example.agent.ui.components.CreativeAmoledLoadingBadge(
        label = if (isPlanning) "PLAN" else "REASON",
        height = 22.dp,
        testTag = "waiting_reasoning_loading_badge"
      )
    }
  }
}

