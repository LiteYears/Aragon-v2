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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import com.example.agent.ui.components.tactilePress
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
private val DOCUMENT_MIME_FILTERS = arrayOf(
  "application/pdf",
  "image/*",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "text/csv",
  "text/comma-separated-values",
  "text/plain",
  "application/octet-stream"
)

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
  val documents by viewModel.documents.collectAsStateWithLifecycle()
  val documentScope by viewModel.documentScope.collectAsStateWithLifecycle()
  val documentMessages by viewModel.documentMessages.collectAsStateWithLifecycle()
  val autoCloudOcr by viewModel.autoCloudOcr.collectAsStateWithLifecycle()
  val ocrEnabled by viewModel.ocrEnabled.collectAsStateWithLifecycle()

  val listState = rememberLazyListState()
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

  // Intercept back navigation when viewing an artifact preview, subfolder, or secondary tabs
  BackHandler(enabled = selectedArtifact != null || activeTab != UiTab.EXECUTION_FEED || currentExplorerPath.isNotBlank()) {
    if (selectedArtifact != null) {
      viewModel.closeArtifactPreview()
    } else if (activeTab == UiTab.ARTIFACTS && currentExplorerPath.isNotBlank()) {
      viewModel.navigateUpDirectory()
    } else {
      viewModel.selectTab(UiTab.EXECUTION_FEED)
    }
  }

  // Follow newly added steps only when the user is at the live tail
  LaunchedEffect(state.executionFeed.size) {
    if (state.executionFeed.isNotEmpty() && isNearBottom) {
      try {
        listState.animateScrollToItem(state.executionFeed.size - 1)
      } catch (_: Exception) {}
    }
  }

  val filterCounts = remember(state.executionFeed) {
    mapOf(
      FeedFilter.ALL to state.executionFeed.size,
      FeedFilter.TOOLS to state.executionFeed.count { it.type == StepType.TOOL_CALL || it.type == StepType.TOOL_EXECUTION },
      FeedFilter.REASONING to state.executionFeed.count { it.type == StepType.THINKING || it.type == StepType.PLAN || it.type == StepType.CONCLUSION },
      FeedFilter.OUTPUTS to state.executionFeed.count { !it.stdout.isNullOrBlank() || !it.stderr.isNullOrBlank() || it.type == StepType.OBSERVATION },
      FeedFilter.ERRORS to state.executionFeed.count { it.type == StepType.ERROR || (it.exitCode != null && it.exitCode != 0) }
    )
  }

  val filteredFeed = remember(state.executionFeed, activeFeedFilter) {
    when (activeFeedFilter) {
      FeedFilter.ALL -> state.executionFeed
      FeedFilter.TOOLS -> state.executionFeed.filter { it.type == StepType.TOOL_CALL || it.type == StepType.TOOL_EXECUTION }
      FeedFilter.REASONING -> state.executionFeed.filter { it.type == StepType.THINKING || it.type == StepType.PLAN || it.type == StepType.CONCLUSION }
      FeedFilter.OUTPUTS -> state.executionFeed.filter { !it.stdout.isNullOrBlank() || !it.stderr.isNullOrBlank() || it.type == StepType.OBSERVATION }
      FeedFilter.ERRORS -> state.executionFeed.filter { it.type == StepType.ERROR || (it.exitCode != null && it.exitCode != 0) }
    }
  }

  val runTelemetry = remember(state.executionFeed, state.status) {
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
          .background(AmoledSurface)
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

        PremiumTabBar(
          selectedTab = activeTab,
          feedCount = state.executionFeed.size,
          artifactsCount = state.trackedDeliverables.size,
          historyCount = state.taskHistory.size,
          onSelectTab = { viewModel.selectTab(it) }
        )
      }
    },
    bottomBar = {
      val isInputActive = isInputFocused || inputText.isNotBlank()
      val containerBorderColor by animateColorAsState(
        targetValue = if (isInputActive) AmoledActionPrimary.copy(alpha = 0.85f) else AmoledBorder,
        animationSpec = tween(180),
        label = "prompt_border"
      )

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
              .widthIn(max = 720.dp)
              .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)
          ) {
            // Quick action buttons above prompt input: Displayed ONLY when suggested (contextual follow-ups upon completion)
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
                  .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
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
            }

            // Modern Interactive Floating User Prompt Field
            Surface(
              shape = RoundedCornerShape(18.dp),
              color = Color(0xFF0C0C0E),
              border = androidx.compose.foundation.BorderStroke(1.2.dp, containerBorderColor),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 10.dp)
              ) {
                // Attached Documents & System Messages Strip (if any)
                if (documentMessages.isNotEmpty() || documents.isNotEmpty()) {
                  val docChipScrollState = rememberScrollState()
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 14.dp, vertical = 4.dp)
                      .horizontalScroll(docChipScrollState),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    documentMessages.forEach { message ->
                      Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF141418),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
                        modifier = Modifier
                          .clickable(role = Role.Button, onClickLabel = "Dismiss message") {
                            viewModel.clearDocumentMessages()
                          }
                          .testTag("document_message_chip")
                      ) {
                        Text(
                          text = message,
                          fontFamily = JetBrainsMonoFontFamily,
                          fontSize = 9.sp,
                          color = AmoledIconGreyLight,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                      }
                    }
                    documents.forEachIndexed { index, doc ->
                      val chipColor = when (doc.status) {
                        DocumentStatus.READY, DocumentStatus.READY_LEXICAL -> AmoledStatusSuccess
                        DocumentStatus.PROCESSING, DocumentStatus.IMPORTING -> AmoledStatusWarning
                        DocumentStatus.FAILED -> AmoledStatusError
                        DocumentStatus.CANCELLED -> AmoledIconGrey
                      }
                      Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF101014),
                        border = androidx.compose.foundation.BorderStroke(1.dp, chipColor.copy(alpha = 0.5f)),
                        modifier = Modifier
                          .clickable(role = Role.Button, onClickLabel = "Open documents manager") {
                            showDocumentsDialog = true
                          }
                          .testTag("doc_chip_${doc.id}")
                      ) {
                        Text(
                          text = "D${index + 1} ${doc.displayName.take(16)} · ${doc.statusLabel}",
                          fontFamily = JetBrainsMonoFontFamily,
                          fontSize = 9.sp,
                          color = chipColor,
                          maxLines = 1,
                          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                      }
                    }
                    if (documents.isNotEmpty()) {
                      AragonFeedButton(
                        text = "MANAGE",
                        onClick = { showDocumentsDialog = true },
                        variant = AragonButtonVariant.MUTED,
                        testTag = "manage_documents_btn"
                      )
                    }
                  }
                }

                // Spacious Full-Width Multiline Editor (enlarged for comfort, not too small not too big)
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                  if (inputText.isEmpty()) {
                    Text(
                      text = if (state.status == AgentStatus.COMPLETED) {
                        "Continue conversation / modify task (e.g. 'Add unit tests')..."
                      } else {
                        "Assign mission (e.g. analyze data.csv → report.docx)..."
                      },
                      style = TextStyle(
                        fontFamily = InterFontFamily,
                        fontSize = 15.sp,
                        color = AmoledTextMuted,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Normal
                      )
                    )
                  }

                  BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(
                      fontFamily = InterFontFamily,
                      fontSize = 15.sp,
                      color = AmoledTextPrimary,
                      lineHeight = 22.sp,
                      fontWeight = FontWeight.Normal
                    ),
                    cursorBrush = SolidColor(AmoledActionPrimary),
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                      if (inputText.isNotBlank()) {
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

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom Controls Shelf: Organized Left Tools + Right Action
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  // Left Tools Cluster: + DOC, Model Selector, Autonomy Pill
                  val toolsScrollState = rememberScrollState()
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                      .weight(1f, fill = false)
                      .horizontalScroll(toolsScrollState)
                  ) {
                    // Document picker capsule button
                    AragonFeedButton(
                      text = "+ DOC",
                      onClick = { documentPickerLauncher.launch(DOCUMENT_MIME_FILTERS) },
                      variant = AragonButtonVariant.MUTED,
                      testTag = "attach_documents_button"
                    )

                    // MCP ecosystem capsule button
                    val activeMcpCount = mcpServers.count { it.enabled }
                    AragonFeedButton(
                      text = "MCP",
                      onClick = { showMcpDialog = true },
                      variant = if (activeMcpCount > 0) AragonButtonVariant.PRIMARY else AragonButtonVariant.MUTED,
                      testTag = "mcp_servers_button"
                    )

                    // Model switcher pill
                    val modelName = when (providerType) {
                      ProviderType.NVIDIA_NIM -> nvidiaModel.substringAfterLast('/')
                      ProviderType.SANDBOX_ENGINE -> "Autonomous"
                      ProviderType.GEMINI_LIVE_API -> "Gemini Live"
                    }
                    val modelInteractionSource = remember { MutableInteractionSource() }
                    Surface(
                      shape = RoundedCornerShape(5.dp),
                      color = Color(0xFF161619),
                      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
                      modifier = Modifier
                        .tactilePress(modelInteractionSource)
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(
                          interactionSource = modelInteractionSource,
                          indication = ripple(color = AmoledActionPrimary.copy(alpha = 0.2f)),
                          role = Role.Button,
                          onClickLabel = "Select AI Model"
                        ) { showModelSelectDialog = true }
                        .testTag("model_switcher_button")
                    ) {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                      ) {
                        Box(
                          modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (state.canCancel) AmoledActionPrimary else AmoledIconGreyDark)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                          text = modelName,
                          style = MaterialTheme.typography.labelSmall,
                          fontFamily = JetBrainsMonoFontFamily,
                          fontSize = 9.sp,
                          color = AmoledIconGreyLight,
                          maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                          text = "▾",
                          fontSize = 8.sp,
                          color = AmoledIconGreyDark
                        )
                      }
                    }

                    // Autonomy Policy Pill
                    AutonomyPolicyPill(
                      currentPolicy = approvalPolicy,
                      onClick = { showAutonomyDialog = true }
                    )
                  }

                  Spacer(modifier = Modifier.width(6.dp))

                  // Right Action Cluster: Character & Token count, CLEAR, and EXEC / STOP Button
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    if (inputText.isNotEmpty()) {
                      Text(
                        text = "${inputText.length}c · ~${(inputText.length / 4).coerceAtLeast(1)}t",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 9.sp,
                        color = AmoledTextMuted
                      )

                      AragonFeedButton(
                        text = "CLEAR",
                        onClick = { inputText = "" },
                        variant = AragonButtonVariant.MUTED,
                        testTag = "clear_prompt_input_btn"
                      )
                    }

                    if (state.canCancel) {
                      val stopInteraction = remember { MutableInteractionSource() }
                      Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF261010),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusError.copy(alpha = 0.7f)),
                        modifier = Modifier
                          .tactilePress(stopInteraction)
                          .clip(RoundedCornerShape(8.dp))
                          .clickable(
                            interactionSource = stopInteraction,
                            indication = ripple(color = AmoledStatusError.copy(alpha = 0.3f)),
                            role = Role.Button,
                            onClickLabel = "Stop execution"
                          ) { viewModel.stopExecution() }
                          .testTag("stop_execution_button")
                      ) {
                        Box(
                          contentAlignment = Alignment.Center,
                          modifier = Modifier
                            .height(30.dp)
                            .padding(horizontal = 13.dp)
                        ) {
                          Text(
                            text = "STOP ◼",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp,
                            color = AmoledStatusError
                          )
                        }
                      }
                    } else {
                      val isReady = inputText.isNotBlank()
                      val execInteraction = remember { MutableInteractionSource() }
                      val buttonColor by animateColorAsState(
                        targetValue = if (isReady) AmoledActionPrimary else Color(0xFF18181A),
                        animationSpec = tween(150),
                        label = "btn_color"
                      )
                      val textColor by animateColorAsState(
                        targetValue = if (isReady) AmoledActionPrimaryOn else AmoledIconGreyDark,
                        animationSpec = tween(150),
                        label = "text_color"
                      )

                      Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = buttonColor,
                        border = androidx.compose.foundation.BorderStroke(
                          1.dp,
                          if (isReady) AmoledActionPrimary else AmoledBorderSubtle
                        ),
                        modifier = Modifier
                          .tactilePress(execInteraction)
                          .clip(RoundedCornerShape(8.dp))
                          .clickable(
                            interactionSource = execInteraction,
                            indication = ripple(color = AmoledActionPrimary.copy(alpha = 0.25f)),
                            enabled = isReady,
                            role = Role.Button,
                            onClickLabel = if (state.status == AgentStatus.COMPLETED) "Send follow-up" else "Execute task"
                          ) {
                            if (isReady) {
                              val taskText = inputText
                              inputText = ""
                              viewModel.submitTask(taskText)
                            }
                          }
                          .testTag("run_task_button")
                      ) {
                        Box(
                          contentAlignment = Alignment.Center,
                          modifier = Modifier
                            .height(30.dp)
                            .padding(horizontal = 13.dp)
                        ) {
                          Text(
                            text = if (state.status == AgentStatus.COMPLETED) "SEND ⏎" else "EXEC ⏎",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp,
                            color = textColor
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
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(AmoledBackground)
        .padding(paddingValues)
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
                  OutlinedButton(
                    onClick = { viewModel.retryWithDefaultModel() },
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledTextPrimary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                  ) {
                    Text("Retry with Flagship GLM 5.3", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                  }
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
              OutlinedButton(
                onClick = { viewModel.dismissDeprecatedModelAlert() },
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusWarning.copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledStatusWarning),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(24.dp)
              ) {
                Text("DISMISS", fontSize = 8.sp, fontFamily = JetBrainsMonoFontFamily)
              }
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
                  Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF100E0A),
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, AmoledActionPrimary.copy(alpha = 0.7f)),
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
                        Text(
                          text = if (state.status == AgentStatus.PLANNING) "PREPARING EXECUTION PLAN" else "AUTONOMOUS ENGINE REASONING",
                          style = MaterialTheme.typography.labelSmall,
                          fontFamily = JetBrainsMonoFontFamily,
                          fontWeight = FontWeight.Bold,
                          fontSize = 11.sp,
                          letterSpacing = 1.sp,
                          color = AmoledActionPrimary
                        )
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
                        text = state.currentAction ?: "Evaluating instructions, provisioning tools, and synthesizing reasoning trajectory...",
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
                  onOpenModelSelect = { showModelSelectDialog = true }
                )
              }
            } else {
              val telemetry = remember(state.executionFeed, state.status) {
                viewModel.computeRunTelemetry()
              }

              val filterCounts = remember(state.executionFeed) {
                mapOf(
                  FeedFilter.ALL to state.executionFeed.size,
                  FeedFilter.TOOLS to state.executionFeed.count { it.type == StepType.TOOL_CALL || it.type == StepType.TOOL_EXECUTION },
                  FeedFilter.REASONING to state.executionFeed.count { it.type == StepType.THINKING || it.type == StepType.PLAN || it.type == StepType.CONCLUSION },
                  FeedFilter.OUTPUTS to state.executionFeed.count { !it.stdout.isNullOrBlank() || !it.stderr.isNullOrBlank() || it.type == StepType.OBSERVATION },
                  FeedFilter.ERRORS to state.executionFeed.count { it.type == StepType.ERROR || (it.exitCode != null && it.exitCode != 0) }
                )
              }

              val filteredFeed = remember(state.executionFeed, activeFeedFilter) {
                when (activeFeedFilter) {
                  FeedFilter.ALL -> state.executionFeed
                  FeedFilter.TOOLS -> state.executionFeed.filter { it.type == StepType.TOOL_CALL || it.type == StepType.TOOL_EXECUTION }
                  FeedFilter.REASONING -> state.executionFeed.filter { it.type == StepType.THINKING || it.type == StepType.PLAN || it.type == StepType.CONCLUSION }
                  FeedFilter.OUTPUTS -> state.executionFeed.filter { !it.stdout.isNullOrBlank() || !it.stderr.isNullOrBlank() || it.type == StepType.OBSERVATION }
                  FeedFilter.ERRORS -> state.executionFeed.filter { it.type == StepType.ERROR || (it.exitCode != null && it.exitCode != 0) }
                }
              }

              Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
              ) {
                Column(
                  modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp)
                ) {
                  // Streamlined feed control bar: Filter indicator on Left + COPY ALL on Right
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF101012),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 14.dp, vertical = 4.dp)
                  ) {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                      ) {
                        Text(
                          text = if (activeFeedFilter != FeedFilter.ALL) "FILTER: ${activeFeedFilter.label} (${filteredFeed.size})" else "FEED: ${filteredFeed.size} steps",
                          fontFamily = JetBrainsMonoFontFamily,
                          fontSize = 9.sp,
                          fontWeight = FontWeight.Bold,
                          color = if (activeFeedFilter != FeedFilter.ALL) AmoledActionPrimary else AmoledIconGreyLight
                        )
                        if (activeFeedFilter != FeedFilter.ALL) {
                          Text(
                            text = "[RESET]",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AmoledActionPrimary,
                            modifier = Modifier
                              .clip(RoundedCornerShape(4.dp))
                              .clickable { activeFeedFilter = FeedFilter.ALL }
                              .padding(2.dp)
                          )
                        }
                      }

                      AragonFeedCopyButton(
                        textToCopy = "",
                        textProvider = { formatExecutionFeedLog(state) },
                        label = "COPY ALL",
                        testTag = "feed_header_copy_all_btn"
                      )
                    }
                  }

                  LazyColumn(
                    state = listState,
                    modifier = Modifier
                      .fillMaxSize()
                      .testTag("execution_feed_list"),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 0.dp)
                  ) {
                    itemsIndexed(
                      items = filteredFeed,
                      key = { _, step -> step.id }
                    ) { index, step ->
                      ExecutionFeedItem(
                        step = step,
                        isLast = index == filteredFeed.size - 1 && state.status != AgentStatus.COMPLETED
                      )
                    }

                    // Active Model Reasoning Indicator Card (Displayed when model is actively reasoning/thinking)
                    if (state.status == AgentStatus.THINKING || state.status == AgentStatus.PLANNING || (state.status == AgentStatus.EXECUTING_TOOL && filteredFeed.lastOrNull()?.isCompleted == true)) {
                      item(key = "active_model_reasoning_indicator") {
                        Surface(
                          shape = RoundedCornerShape(10.dp),
                          color = Color(0xFF14120E),
                          border = androidx.compose.foundation.BorderStroke(1.dp, AmoledActionPrimary.copy(alpha = 0.6f)),
                          modifier = Modifier
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
                                  text = if (state.status == AgentStatus.PLANNING) "PREPARING EXECUTION PLAN" else "REASONING & SYNTHESIZING",
                                  style = MaterialTheme.typography.labelSmall,
                                  fontFamily = JetBrainsMonoFontFamily,
                                  fontWeight = FontWeight.Bold,
                                  fontSize = 10.5.sp,
                                  color = AmoledActionPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                  text = state.currentAction ?: "Evaluating context and executing next action...",
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
                              label = if (state.status == AgentStatus.PLANNING) "PLAN" else "REASON",
                              height = 22.dp,
                              testTag = "waiting_reasoning_loading_badge"
                            )
                          }
                        }
                      }
                    }

                  if (state.status == AgentStatus.COMPLETED) {
                    item(key = "continuation_prompt_banner") {
                      Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0F1810),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusSuccess.copy(alpha = 0.4f)),
                        modifier = Modifier
                          .fillMaxWidth()
                          .padding(horizontal = 14.dp, vertical = 10.dp)
                          .testTag("conversation_active_banner")
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
                              text = "TASK COMPLETED • CONVERSATION ACTIVE",
                              style = MaterialTheme.typography.labelSmall,
                              fontFamily = JetBrainsMonoFontFamily,
                              fontWeight = FontWeight.Bold,
                              color = AmoledStatusSuccess,
                              fontSize = 10.sp
                            )
                            Text(
                              text = "READY FOR FOLLOW-UP",
                              style = MaterialTheme.typography.labelSmall,
                              fontFamily = JetBrainsMonoFontFamily,
                              color = AmoledIconGreyLight,
                              fontSize = 9.sp
                            )
                          }
                          Spacer(modifier = Modifier.height(4.dp))
                          Text(
                            text = "Session context and deliverables remain loaded. Enter follow-up instructions, request code modifications, tests, or optimizations below.",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = InterFontFamily,
                            color = AmoledTextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                          )
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
                      Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isPartial) Color(0xFF141208) else Color(0xFF1A0F0F),
                        border = androidx.compose.foundation.BorderStroke(1.dp, bannerAccent.copy(alpha = 0.4f)),
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
                          AragonFeedButton(
                            text = "RESUME TASK",
                            onClick = { viewModel.resumeTask() },
                            variant = AragonButtonVariant.PRIMARY,
                            testTag = "resume_task_button"
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
        documentPickerLauncher.launch(DOCUMENT_MIME_FILTERS)
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
      containerColor = AmoledSurfaceElevated,
      titleContentColor = AmoledTextPrimary,
      textContentColor = AmoledTextSecondary,
      title = {
        Text(
          text = "Reset Workspace?",
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp
        )
      },
      text = {
        Text(
          text = "This stops any running task and permanently deletes every file in the workspace. This cannot be undone.",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 12.sp,
          color = AmoledTextSecondary
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
          Text("RESET", color = AmoledStatusError, fontWeight = FontWeight.Bold, fontFamily = JetBrainsMonoFontFamily)
        }
      },
      dismissButton = {
        TextButton(
          onClick = { showResetConfirm = false },
          modifier = Modifier.testTag("cancel_reset_btn")
        ) {
          Text("CANCEL", color = AmoledTextMuted, fontFamily = JetBrainsMonoFontFamily)
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
      onSelectTab = { tab -> viewModel.selectTab(tab) },
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
            .clip(RoundedCornerShape(6.dp))
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
            modifier = Modifier.minimumInteractiveComponentSize(),
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
                maxLines = 1
              )
              if (badge != null) {
                Spacer(modifier = Modifier.width(3.dp))
                Surface(
                  shape = RoundedCornerShape(3.dp),
                  color = if (isSelected) AmoledActionPrimary else Color(0xFF1E1E22),
                  modifier = Modifier.height(14.dp)
                ) {
                  Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 3.dp)
                  ) {
                    Text(
                      text = badge,
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 7.5.sp,
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
    color = AmoledSurface,
    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(horizontal = 12.dp, vertical = 7.dp)
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
          Spacer(modifier = Modifier.width(6.dp))
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

        // RIGHT-ALIGNED ACTIONS: MCP, DOCS, STATS, COPY ALL, STOP, MENU
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
          // Direct 1-tap MCP button with no badge
          AragonHeaderButton(
            text = "MCP",
            badge = null,
            onClick = onOpenMcp,
            variant = if (mcpCount > 0) AragonButtonVariant.PRIMARY else AragonButtonVariant.MUTED,
            testTag = "header_mcp_btn"
          )

          if (docsCount > 0) {
            AragonHeaderButton(
              text = "DOCS",
              badge = "$docsCount",
              onClick = onOpenDocs,
              variant = AragonButtonVariant.MUTED,
              testTag = "header_docs_btn"
            )
          }

          if (telemetry != null) {
            val filterBadge = if (activeFeedFilter != null && activeFeedFilter != FeedFilter.ALL) {
              activeFeedFilter.label
            } else if (telemetry.errorCount > 0) {
              "!ERR"
            } else null

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
              text = "COPY ALL",
              onClick = onCopyAll,
              variant = AragonButtonVariant.MUTED,
              testTag = "header_copy_all_btn"
            )
          }

          if (canCancel) {
            AragonHeaderButton(
              text = "STOP",
              onClick = onStop,
              variant = AragonButtonVariant.DANGER,
              testTag = "header_stop_btn"
            )
          }

          // Modern Navigation Menu Trigger (Pure typography)
          AragonHeaderButton(
            text = "MENU",
            onClick = onOpenMenu,
            variant = AragonButtonVariant.PRIMARY,
            testTag = "header_nav_menu_btn"
          )
        }
      }

      // Active status bar (when executing)
      if (!currentAction.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(6.dp))
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(6.dp),
          color = Color(0xFF101010),
          border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle)
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(AmoledActionPrimary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = currentAction,
              style = MaterialTheme.typography.bodySmall,
              fontFamily = JetBrainsMonoFontFamily,
              color = AmoledIconGreyLight,
              fontSize = 10.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f)
            )
          }
        }
      }
    }
  }
}

@Composable
fun StatusPill(status: AgentStatus) {
  val (bgColor, textColor, label) = when (status) {
    AgentStatus.IDLE -> Triple(Color(0xFF141414), AmoledTextMuted, "IDLE")
    AgentStatus.THINKING -> Triple(Color(0xFF1B1710), AmoledActionPrimary, "THINKING")
    AgentStatus.PLANNING -> Triple(Color(0xFF1B1710), AmoledActionPrimary, "PLANNING")
    AgentStatus.REPLANNING -> Triple(Color(0xFF1B1710), AmoledActionPrimary, "REPLAN")
    AgentStatus.EXECUTING_TOOL -> Triple(Color(0xFF1B1710), AmoledActionPrimary, "TOOL")
    AgentStatus.OBSERVING -> Triple(Color(0xFF161616), AmoledIconGreyLight, "OBSERVING")
    AgentStatus.SANDBOX_RESET -> Triple(Color(0xFF251510), AmoledStatusWarning, "RESETTING")
    AgentStatus.DELIVERABLE_PENDING -> Triple(Color(0xFF251F10), AmoledStatusWarning, "PENDING")
    AgentStatus.VERIFYING -> Triple(Color(0xFF161616), AmoledIconGreyLight, "VERIFYING")
    AgentStatus.COMPLETED -> Triple(Color(0xFF102015), AmoledStatusSuccess, "COMPLETED")
    AgentStatus.PARTIAL_COMPLETION -> Triple(Color(0xFF251F10), AmoledStatusWarning, "PARTIAL")
    AgentStatus.FAILED -> Triple(Color(0xFF221111), AmoledStatusError, "FAILED")
    AgentStatus.CANCELLED -> Triple(Color(0xFF141414), AmoledTextMuted, "STOPPED")
  }

  Surface(
    shape = RoundedCornerShape(10.dp),
    color = bgColor,
    border = androidx.compose.foundation.BorderStroke(1.dp, textColor.copy(alpha = 0.35f)),
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
  onOpenModelSelect: () -> Unit
) {
  val scrollState = rememberScrollState()

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
      .padding(horizontal = 24.dp, vertical = 28.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    // Dynamic Cybernetic Emblem
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(92.dp)
    ) {
      Canvas(modifier = Modifier.size(92.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.44f * breatheScale

        rotate(orbitAngle, center) {
          drawCircle(
            color = Color(0xFF26262A),
            radius = radius,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f)
          )

          val tickCount = 8
          for (i in 0 until tickCount) {
            val angle = (i * (2 * Math.PI / tickCount)).toFloat()
            val rOuter = radius
            val rInner = radius - 5f
            drawLine(
              color = Color.White.copy(alpha = 0.5f),
              start = Offset(center.x + kotlin.math.cos(angle) * rInner, center.y + kotlin.math.sin(angle) * rInner),
              end = Offset(center.x + kotlin.math.cos(angle) * rOuter, center.y + kotlin.math.sin(angle) * rOuter),
              strokeWidth = 1.2f
            )
          }
        }
      }

      com.example.agent.ui.components.AragonLogoEmblem(
        tint = Color.White,
        modifier = Modifier.size(50.dp)
      )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = "ARAGON",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
      letterSpacing = 3.5.sp,
      color = Color.White
    )

    Spacer(modifier = Modifier.height(10.dp))

    // Slogan
    Text(
      text = "Turn Ambitious Thought Into Reality.",
      style = MaterialTheme.typography.titleMedium,
      fontFamily = InterFontFamily,
      fontWeight = FontWeight.SemiBold,
      color = AmoledTextPrimary,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 12.dp)
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Middle Aragon Model: Interactive Model Selector Capsule
    val modelInteraction = remember { MutableInteractionSource() }
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = Color(0xFF121217),
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledActionPrimary.copy(alpha = 0.45f)),
      modifier = Modifier
        .tactilePress(modelInteraction)
        .clip(RoundedCornerShape(16.dp))
        .clickable(
          interactionSource = modelInteraction,
          indication = ripple(color = AmoledActionPrimary.copy(alpha = 0.2f)),
          role = Role.Button,
          onClick = onOpenModelSelect
        )
        .testTag("home_middle_model_selector")
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
      ) {
        Box(
          modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(AmoledActionPrimary)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "MODEL: $currentModelName",
          style = MaterialTheme.typography.labelMedium,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 11.5.sp,
          color = AmoledActionPrimary
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = "CHANGE",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          color = AmoledIconGreyLight
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "Autonomous Intelligence Engine · E2B Sandbox MicroVM",
      fontFamily = JetBrainsMonoFontFamily,
      fontSize = 9.5.sp,
      color = AmoledTextMuted,
      textAlign = TextAlign.Center
    )
  }
}

/**
 * Normal Model Selector Dialog:
 * Shows list of available models cleanly with instant search and direct access to Developer Settings.
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
      shape = RoundedCornerShape(14.dp),
      color = Color(0xFF101010),
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 560.dp)
        .heightIn(max = 580.dp)
        .testTag("model_selector_dialog")
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "SELECT AI MODEL",
            style = MaterialTheme.typography.titleSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = AmoledTextPrimary,
            letterSpacing = 1.sp
          )

          Surface(
            color = Color.Transparent,
            modifier = Modifier
              .clickable(
                role = Role.Button,
                onClickLabel = "Close model selector dialog"
              ) { onDismiss() }
          ) {
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .padding(4.dp)
            ) {
              Text(
                text = "[CLOSE]",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                color = AmoledIconGrey
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search Bar for Models
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = Color(0xFF161616),
          border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (searchQuery.isNotBlank()) AmoledActionPrimary else AmoledBorderSubtle
          ),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
          ) {
            Text(
              text = "FILTER",
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 9.sp,
              fontWeight = FontWeight.Bold,
              color = AmoledActionPrimary,
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
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                color = AmoledTextPrimary
              ),
              cursorBrush = SolidColor(AmoledActionPrimary),
              decorationBox = { innerTextField ->
                if (searchQuery.isEmpty()) {
                  Text(
                    text = "Search by model name or keyword...",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = AmoledTextMuted
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
                color = AmoledIconGrey,
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
              color = AmoledTextMuted,
              fontSize = 9.sp
            )

            val isSandboxActive = currentProvider == ProviderType.SANDBOX_ENGINE
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = if (isSandboxActive) Color(0xFF1C1C1C) else Color(0xFF141414),
              border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isSandboxActive) AmoledActionPrimary else AmoledBorderSubtle
              ),
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
                    fontWeight = FontWeight.SemiBold,
                    color = AmoledTextPrimary
                  )
                  Text(
                    text = "Built-in rule-based agent loop driving the same sandbox tools — no model API key needed",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmoledTextMuted,
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
                    color = AmoledStatusSuccess
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
                color = AmoledTextMuted,
                fontSize = 9.sp
              )
              Text(
                text = "[LIVE SYNCED]",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = AmoledStatusSuccess
              )
            }

            filteredNvidiaModels.forEach { modelEntry ->
              val isModelActive = currentProvider == ProviderType.NVIDIA_NIM && currentNvidiaModel == modelEntry.id
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isModelActive) Color(0xFF1C1C1C) else Color(0xFF141414),
                border = androidx.compose.foundation.BorderStroke(
                  1.dp,
                  if (isModelActive) AmoledActionPrimary else AmoledBorderSubtle
                ),
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
                        fontWeight = FontWeight.SemiBold,
                        color = AmoledTextPrimary
                      )
                      if (modelEntry.badge.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                          text = "[${modelEntry.badge}]",
                          style = MaterialTheme.typography.labelSmall,
                          fontFamily = JetBrainsMonoFontFamily,
                          fontSize = 8.sp,
                          color = AmoledActionPrimary
                        )
                      }
                    }
                    Text(
                      text = modelEntry.description,
                      style = MaterialTheme.typography.labelSmall,
                      color = AmoledTextMuted,
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
                      color = AmoledStatusSuccess
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
              color = AmoledTextMuted,
              fontSize = 9.sp
            )

            val isGeminiActive = currentProvider == ProviderType.GEMINI_LIVE_API
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = if (isGeminiActive) Color(0xFF1C1C1C) else Color(0xFF141414),
              border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isGeminiActive) AmoledActionPrimary else AmoledBorderSubtle
              ),
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
                    fontWeight = FontWeight.SemiBold,
                    color = AmoledTextPrimary
                  )
                  Text(
                    text = "gemini-2.5-flash with function calling",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmoledTextMuted,
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
                    color = AmoledStatusSuccess
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
          variant = AragonButtonVariant.PRIMARY,
          height = 34.dp,
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
    containerColor = AmoledSurface,
    title = {
      Column {
        Text(
          text = "Settings & Configuration",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = AmoledTextPrimary
        )
        Text(
          text = "AI provider, API keys, and the E2B execution sandbox. Changes apply to new tasks.",
          style = MaterialTheme.typography.labelSmall,
          color = AmoledTextMuted,
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
      Button(
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
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = AmoledActionPrimary,
          contentColor = AmoledActionPrimaryOn
        )
      ) {
        Text("Apply Changes", style = MaterialTheme.typography.labelMedium)
      }
    },
    dismissButton = {
      TextButton(
        onClick = {
          onResetConnectionStatus()
          onResetSandboxConnectionStatus()
          onDismiss()
        }
      ) {
        Text("Cancel", color = AmoledIconGrey)
      }
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
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(AmoledBackground)
  ) {
    // Header Row with count and Clear button
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "TASK ARCHIVE & LOGS",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 10.sp,
          color = AmoledIconGreyLight
        )
        if (taskHistory.isNotEmpty()) {
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "${taskHistory.size}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledActionPrimary,
            modifier = Modifier
              .background(Color(0xFF1E1A12), RoundedCornerShape(3.dp))
              .padding(horizontal = 5.dp, vertical = 1.dp)
          )
        }
      }

      if (taskHistory.isNotEmpty()) {
        AragonActionButton(
          text = "CLEAR ALL",
          onClick = onClearHistory,
          variant = AragonButtonVariant.DANGER,
          height = 24.dp,
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
            fontSize = 11.sp
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "No Previous Task History",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = AmoledTextPrimary
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "When you execute tasks, each objective, deliverable record, full conversation, planning breakdown, and agentic execution steps are completely saved and accessible here.",
            style = MaterialTheme.typography.bodySmall,
            color = AmoledTextMuted,
            textAlign = TextAlign.Center
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        itemsIndexed(
          items = taskHistory,
          key = { _, record -> record.id }
        ) { _, record ->
          TaskHistoryCard(
            record = record,
            onReRun = { onSelectTask(record.goal) },
            onRestore = { onRestoreTask(record) }
          )
        }
      }
    }
  }
}

@Composable
fun TaskHistoryCard(
  record: TaskRecord,
  onReRun: () -> Unit,
  onRestore: () -> Unit
) {
  var isExpanded by remember { mutableStateOf(false) }
  var activeDetailTab by remember { mutableStateOf(0) } // 0: FEED, 1: CONVERSATION, 2: PLAN & STAGES

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
    shape = RoundedCornerShape(10.dp),
    color = Color(0xFF0C0C0C),
    border = androidx.compose.foundation.BorderStroke(1.dp, if (isExpanded) AmoledActionPrimary.copy(alpha = 0.5f) else Color(0xFF1E1E1E)),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
      // Top meta row: Status badge + date + duration, Re-run and Expand buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = statusColor,
            modifier = Modifier
              .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
              .padding(horizontal = 5.dp, vertical = 2.dp)
          )

          Spacer(modifier = Modifier.width(8.dp))

          Text(
            text = formattedDate,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledIconGreyDark
          )

          if (executionDuration != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "• $executionDuration",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 9.sp,
              color = AmoledTextMuted
            )
          }

          if (record.executionFeed.isNotEmpty()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "• ${record.executionFeed.size} steps",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 9.sp,
              color = AmoledActionPrimary.copy(alpha = 0.8f)
            )
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
          AragonActionButton(
            text = if (isExpanded) "COLLAPSE" else "INSPECT",
            onClick = { isExpanded = !isExpanded },
            variant = if (isExpanded) AragonButtonVariant.PRIMARY else AragonButtonVariant.MUTED,
            height = 24.dp,
            testTag = "inspect_task_btn_${record.id}"
          )

          AragonActionButton(
            text = "RE-RUN",
            onClick = onReRun,
            variant = AragonButtonVariant.DEFAULT,
            height = 24.dp,
            testTag = "rerun_task_btn_${record.id}"
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Goal text
      Text(
        text = record.goal,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        color = AmoledTextPrimary,
        maxLines = if (isExpanded) 10 else 2,
        overflow = TextOverflow.Ellipsis
      )

      // Conclusion / Outcome if available
      if (!record.conclusion.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = record.conclusion,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = InterFontFamily,
          fontSize = 11.sp,
          color = AmoledTextSecondary,
          maxLines = if (isExpanded) 10 else 2,
          overflow = TextOverflow.Ellipsis
        )
      }

      // Deliverables pills if any
      if (record.deliverables.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          for (deliv in record.deliverables) {
            Text(
              text = deliv,
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 9.sp,
              color = AmoledStatusSuccess,
              modifier = Modifier
                .background(AmoledStatusSuccess.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                .border(1.dp, AmoledStatusSuccess.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp)
            )
          }
        }
      }

      // Deep inspection section when expanded
      AnimatedVisibility(visible = isExpanded) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(Color(0xFF080808), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF181818), RoundedCornerShape(6.dp))
            .padding(10.dp)
        ) {
          // Sub-tabs: EXECUTION STEPS, CONVERSATION, PLAN & STAGES
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            val tabs = listOf(
              "EXECUTION (${record.executionFeed.size})",
              "CONVERSATION (${record.conversation.size})",
              "PLAN & STAGES (${record.planSteps.size})"
            )
            tabs.forEachIndexed { idx, label ->
              val isSel = activeDetailTab == idx
              Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (isSel) AmoledActionPrimary.copy(alpha = 0.15f) else Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) AmoledActionPrimary else Color(0xFF222222)),
                modifier = Modifier.clickable { activeDetailTab = idx }
              ) {
                Text(
                  text = label,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 9.sp,
                  fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                  color = if (isSel) AmoledActionPrimary else AmoledTextSecondary,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          when (activeDetailTab) {
            0 -> {
              // EXECUTION STEPS
              if (record.executionFeed.isEmpty()) {
                Text(
                  text = "No detailed execution steps were recorded for this task.",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 10.sp,
                  color = AmoledTextMuted
                )
              } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  record.executionFeed.take(30).forEach { step ->
                    Surface(
                      shape = RoundedCornerShape(4.dp),
                      color = Color(0xFF101010),
                      border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E1E1E)),
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
                              text = "#${step.stepNumber}",
                              fontFamily = JetBrainsMonoFontFamily,
                              fontSize = 9.sp,
                              fontWeight = FontWeight.Bold,
                              color = AmoledActionPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                              text = step.type.name,
                              fontFamily = JetBrainsMonoFontFamily,
                              fontSize = 8.sp,
                              fontWeight = FontWeight.Bold,
                              color = if (step.type == StepType.ERROR) AmoledStatusError else AmoledIconGreyLight,
                              modifier = Modifier
                                .background(Color(0xFF1A1A1A), RoundedCornerShape(2.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                            if (!step.toolName.isNullOrBlank()) {
                              Spacer(modifier = Modifier.width(4.dp))
                              Text(
                                text = step.toolName,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AmoledStatusSuccess
                              )
                            }
                          }
                          if (step.durationMs != null) {
                            Text(
                              text = "${step.durationMs}ms",
                              fontFamily = JetBrainsMonoFontFamily,
                              fontSize = 8.sp,
                              color = AmoledTextMuted
                            )
                          }
                        }

                        if (step.content.isNotBlank()) {
                          Spacer(modifier = Modifier.height(4.dp))
                          Text(
                            text = step.content.take(300),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 9.sp,
                            color = AmoledTextSecondary,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                          )
                        }

                        if (!step.stdout.isNullOrBlank()) {
                          Spacer(modifier = Modifier.height(4.dp))
                          Text(
                            text = step.stdout.take(250),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 8.sp,
                            color = Color(0xFF7CB342),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                          )
                        }
                      }
                    }
                  }
                  if (record.executionFeed.size > 30) {
                    Text(
                      text = "... and ${record.executionFeed.size - 30} more steps (view all by restoring to feed).",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 9.sp,
                      color = AmoledTextMuted
                    )
                  }
                }
              }
            }

            1 -> {
              // CONVERSATION
              if (record.conversation.isEmpty()) {
                Text(
                  text = "No saved conversation turns recorded for this task.",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 10.sp,
                  color = AmoledTextMuted
                )
              } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  record.conversation.forEach { msg ->
                    val isUser = msg.role == MessageRole.USER
                    Surface(
                      shape = RoundedCornerShape(4.dp),
                      color = if (isUser) Color(0xFF141414) else Color(0xFF10141A),
                      border = androidx.compose.foundation.BorderStroke(1.dp, if (isUser) Color(0xFF242424) else Color(0xFF1C2430)),
                      modifier = Modifier.fillMaxWidth()
                    ) {
                      Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                          Text(
                            text = if (isUser) "USER" else "AGENT (${msg.role.name})",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isUser) AmoledActionPrimary else Color(0xFF64B5F6)
                          )
                          if (!msg.toolName.isNullOrBlank()) {
                            Text(
                              text = "tool: ${msg.toolName}",
                              fontFamily = JetBrainsMonoFontFamily,
                              fontSize = 8.sp,
                              color = AmoledStatusSuccess
                            )
                          }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                          text = msg.content.take(800),
                          fontFamily = InterFontFamily,
                          fontSize = 10.sp,
                          color = AmoledTextPrimary,
                          maxLines = 12,
                          overflow = TextOverflow.Ellipsis
                        )
                      }
                    }
                  }
                }
              }
            }

            2 -> {
              // PLAN & 5 STAGES
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (record.planSteps.isNotEmpty()) {
                  Text(
                    text = "PLANNED STEPS (${record.planSteps.size}):",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = AmoledActionPrimary
                  )
                  record.planSteps.forEachIndexed { i, step ->
                    Text(
                      text = "${i + 1}. $step",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 10.sp,
                      color = AmoledTextSecondary
                    )
                  }
                }

                record.fiveStageRecord?.let { fsr ->
                  if (fsr.actions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                      text = "ACTIONS TAKEN (${fsr.actions.size}):",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 9.sp,
                      fontWeight = FontWeight.Bold,
                      color = AmoledStatusSuccess
                    )
                    fsr.actions.take(10).forEach { act ->
                      Text(
                        text = "• $act",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 9.sp,
                        color = AmoledTextSecondary
                      )
                    }
                  }

                  if (fsr.observations.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                      text = "KEY OBSERVATIONS (${fsr.observations.size}):",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 9.sp,
                      fontWeight = FontWeight.Bold,
                      color = AmoledAccentAmber
                    )
                    fsr.observations.take(10).forEach { obs ->
                      Text(
                        text = "• $obs",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 9.sp,
                        color = AmoledTextSecondary
                      )
                    }
                  }
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          // Bottom Action: RESTORE TO ACTIVE FEED
          AragonActionButton(
            text = "RESTORE TASK INTO LIVE FEED",
            onClick = onRestore,
            variant = AragonButtonVariant.PRIMARY,
            height = 28.dp,
            modifier = Modifier.fillMaxWidth(),
            testTag = "restore_task_to_feed_btn_${record.id}"
          )
        }
      }
    }
  }
}
