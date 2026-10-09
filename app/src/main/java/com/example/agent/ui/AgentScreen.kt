package com.example.agent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.example.agent.core.AgentStatus
import com.example.agent.core.NvidiaNimConfig
import com.example.agent.core.NvidiaNimModels
import com.example.agent.ui.components.AboutAragonScreen
import com.example.agent.ui.components.ArtifactItem
import com.example.agent.ui.components.ArtifactPreviewDialog
import com.example.agent.ui.components.ExecutionFeedItem
import com.example.agent.ui.components.FiveStagesInspector
import com.example.agent.ui.components.LittleCopyButton
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledActionPrimaryOn
import com.example.ui.theme.AmoledBackground
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGrey
import com.example.ui.theme.AmoledIconGreyDark
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledStatusError
import com.example.ui.theme.AmoledStatusSuccess
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledSurfaceVariant
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily

/**
 * Main Aragon Autonomous Agent Screen.
 * Pure typography design without icons, left-aligned logo header,
 * quick model selection separated from hidden developer configuration,
 * suggestions above prompt input field, and pristine home screen.
 */
@Composable
fun AgentScreen(
  viewModel: AgentViewModel,
  modifier: Modifier = Modifier
) {
  val state by viewModel.state.collectAsState()
  val activeTab by viewModel.activeTab.collectAsState()
  val providerType by viewModel.providerType.collectAsState()
  val nvidiaApiKey by viewModel.nvidiaApiKey.collectAsState()
  val nvidiaModel by viewModel.nvidiaModel.collectAsState()
  val nvidiaBaseUrl by viewModel.nvidiaBaseUrl.collectAsState()
  val nvidiaTemperature by viewModel.nvidiaTemperature.collectAsState()
  val nvidiaMaxTokens by viewModel.nvidiaMaxTokens.collectAsState()
  val geminiApiKey by viewModel.geminiApiKey.collectAsState()
  val selectedArtifact by viewModel.selectedArtifactForPreview.collectAsState()
  val previewContent by viewModel.previewContent.collectAsState()
  val connectionStatus by viewModel.connectionStatus.collectAsState()

  var inputText by remember { mutableStateOf("") }
  var showSettingsDialog by remember { mutableStateOf(false) }
  var showModelSelectDialog by remember { mutableStateOf(false) }

  val listState = rememberLazyListState()

  // Smooth scroll to newly added steps without blocking the thread
  LaunchedEffect(state.executionFeed.size) {
    if (state.executionFeed.isNotEmpty()) {
      try {
        listState.animateScrollToItem(state.executionFeed.size - 1)
      } catch (_: Exception) {}
    }
  }

  Scaffold(
    modifier = modifier
      .fillMaxSize()
      .background(AmoledBackground),
    containerColor = AmoledBackground,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      AgentTopHeader(
        status = state.status,
        currentAction = state.currentAction,
        canCancel = state.canCancel,
        onStop = { viewModel.stopExecution() },
        onOpenAbout = { viewModel.selectTab(UiTab.ABOUT_ARAGON) },
        onResetWorkspace = { viewModel.resetWorkspace() },
        onGetTranscript = { viewModel.getExecutionTranscript() }
      )
    },
    bottomBar = {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .imePadding(),
        color = AmoledBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 6.dp)
        ) {
          // Preset suggestions kept above the prompt input field (Pure text)
          val chipScrollState = rememberScrollState()
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(chipScrollState)
              .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            viewModel.presets.forEach { preset ->
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF131313),
                border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
                modifier = Modifier
                  .clickable {
                    inputText = preset.prompt
                    viewModel.submitTask(preset.prompt, preset.expectedArtifact)
                  }
                  .testTag("preset_chip_${preset.expectedArtifact ?: "custom"}")
              ) {
                Text(
                  text = preset.title,
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = JetBrainsMonoFontFamily,
                  color = AmoledIconGreyLight,
                  fontSize = 11.sp,
                  modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                )
              }
            }
          }

          // Modern Interactive Floating User Prompt Field
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF101010),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
              // Top meta bar inside prompt field: Separated Model button & Clear button
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                // Interactive model switcher pill: opens list of models directly
                Surface(
                  shape = RoundedCornerShape(4.dp),
                  color = Color(0xFF181818),
                  border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
                  modifier = Modifier
                    .clickable { showModelSelectDialog = true }
                    .testTag("model_switcher_button")
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                  ) {
                    Box(
                      modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (state.canCancel) AmoledActionPrimary else AmoledStatusSuccess)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = when (providerType) {
                        ProviderType.NVIDIA_NIM -> "MODEL: ${nvidiaModel.substringAfterLast('/')}"
                        ProviderType.SANDBOX_ENGINE -> "ENGINE: Autonomous Sandbox"
                        ProviderType.GEMINI_LIVE_API -> "MODEL: Gemini Live"
                      },
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 10.sp,
                      color = AmoledIconGreyLight
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                      text = "[CHANGE]",
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 9.sp,
                      color = AmoledActionPrimary
                    )
                  }
                }

                if (inputText.isNotBlank()) {
                  Text(
                    text = "[CLEAR]",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    color = AmoledIconGrey,
                    modifier = Modifier
                      .clickable { inputText = "" }
                      .padding(horizontal = 4.dp, vertical = 2.dp)
                  )
                }
              }

              Spacer(modifier = Modifier.height(4.dp))

              // Multiline input area with execution task button
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
              ) {
                Box(
                  modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
                ) {
                  if (inputText.isEmpty()) {
                    Text(
                      text = "Assign task (e.g., analyze data.csv → report.md)...",
                      style = MaterialTheme.typography.bodyMedium.copy(
                        color = AmoledTextMuted,
                        fontSize = 13.sp
                      )
                    )
                  }

                  BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(
                      fontFamily = InterFontFamily,
                      fontSize = 13.sp,
                      color = AmoledTextPrimary,
                      lineHeight = 18.sp
                    ),
                    cursorBrush = SolidColor(AmoledActionPrimary),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                      if (inputText.isNotBlank()) {
                        val taskText = inputText
                        viewModel.submitTask(taskText)
                      }
                    }),
                    modifier = Modifier
                      .fillMaxWidth()
                      .testTag("agent_input_field")
                  )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Modern execution task button: Pure text (No icons)
                if (state.canCancel) {
                  Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF241010),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusError.copy(alpha = 0.6f)),
                    modifier = Modifier
                      .height(34.dp)
                      .clickable { viewModel.stopExecution() }
                      .testTag("stop_execution_button")
                  ) {
                    Box(
                      contentAlignment = Alignment.Center,
                      modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                      Text(
                        text = "STOP",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = AmoledStatusError
                      )
                    }
                  }
                } else {
                  val isReady = inputText.isNotBlank()
                  val buttonColor by animateColorAsState(
                    targetValue = if (isReady) AmoledActionPrimary else Color(0xFF1C1C1C),
                    animationSpec = tween(180),
                    label = "btn_color"
                  )
                  val textColor by animateColorAsState(
                    targetValue = if (isReady) AmoledActionPrimaryOn else AmoledIconGreyDark,
                    animationSpec = tween(180),
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
                      .height(34.dp)
                      .clickable(enabled = isReady) {
                        if (isReady) {
                          val taskText = inputText
                          viewModel.submitTask(taskText)
                        }
                      }
                      .testTag("run_task_button")
                  ) {
                    Box(
                      contentAlignment = Alignment.Center,
                      modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                      Text(
                        text = "EXEC",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
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

      // Premium Segmented Navigation Bar: Pure text tabs
      PremiumTabBar(
        selectedTab = activeTab,
        feedCount = state.executionFeed.size,
        artifactCount = state.artifacts.size,
        onSelectTab = { viewModel.selectTab(it) }
      )

      // Tab Content Views with lightweight, jank-free transitions
      AnimatedContent(
        targetState = activeTab,
        transitionSpec = {
          fadeIn(animationSpec = tween(90, easing = FastOutSlowInEasing)) togetherWith
            fadeOut(animationSpec = tween(60, easing = FastOutSlowInEasing))
        },
        label = "tab_content_view_transition",
        modifier = Modifier.fillMaxSize()
      ) { currentTab ->
        when (currentTab) {
          UiTab.EXECUTION_FEED -> {
            if (state.executionFeed.isEmpty()) {
              EmptyExecutionState(
                onQuickRun = {
                  inputText = viewModel.presets[0].prompt
                  viewModel.submitTask(viewModel.presets[0].prompt, viewModel.presets[0].expectedArtifact)
                }
              )
            } else {
              LazyColumn(
                state = listState,
                modifier = Modifier
                  .fillMaxSize()
                  .testTag("execution_feed_list"),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp)
              ) {
                itemsIndexed(
                  items = state.executionFeed,
                  key = { _, step -> step.id }
                ) { index, step ->
                  ExecutionFeedItem(
                    step = step,
                    isLast = index == state.executionFeed.size - 1
                  )
                }
              }
            }
          }

          UiTab.ARTIFACTS -> {
            if (state.artifacts.isEmpty()) {
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(32.dp),
                contentAlignment = Alignment.Center
              ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  Text(
                    text = "[WORKSPACE EMPTY]",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = AmoledIconGreyLight,
                    fontSize = 11.sp
                  )
                  Spacer(modifier = Modifier.height(8.dp))
                  Text(
                    text = "Deliverables Repository",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AmoledTextPrimary
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = "When Aragon generates reports, documents, datasets, or code files, they will appear here ready to inspect and share.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AmoledTextMuted,
                    textAlign = TextAlign.Center
                  )
                }
              }
            } else {
              LazyColumn(
                modifier = Modifier
                  .fillMaxSize()
                  .testTag("artifacts_list"),
                contentPadding = PaddingValues(vertical = 8.dp)
              ) {
                itemsIndexed(
                  items = state.artifacts,
                  key = { _, artifact -> artifact.id }
                ) { _, artifact ->
                  ArtifactItem(
                    artifact = artifact,
                    onOpenPreview = { viewModel.openArtifactPreview(it) }
                  )
                }
              }
            }
          }

          UiTab.FIVE_STAGES -> {
            FiveStagesInspector(record = state.fiveStageRecord)
          }

          UiTab.ABOUT_ARAGON -> {
            AboutAragonScreen(
              currentProviderName = when (providerType) {
                ProviderType.NVIDIA_NIM -> "NVIDIA NIM (${nvidiaModel.substringAfterLast('/')})"
                ProviderType.SANDBOX_ENGINE -> "Autonomous Sandbox Engine"
                ProviderType.GEMINI_LIVE_API -> "Google Gemini Live"
              },
              workspacePath = viewModel.workspaceDir.absolutePath,
              onOpenSettings = { showSettingsDialog = true }
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
      onDismiss = { showModelSelectDialog = false }
    )
  }

  // Hidden Developer Settings Dialog: ONLY accessible by 10-tap unlock on build number
  if (showSettingsDialog) {
    SettingsProviderDialog(
      currentProvider = providerType,
      initialNvidiaApiKey = nvidiaApiKey,
      initialNvidiaModel = nvidiaModel,
      initialNvidiaBaseUrl = nvidiaBaseUrl,
      initialNvidiaTemperature = nvidiaTemperature,
      initialNvidiaMaxTokens = nvidiaMaxTokens,
      geminiKey = geminiApiKey,
      connectionStatus = connectionStatus,
      onTestConnection = { viewModel.testNvidiaConnection() },
      onResetConnectionStatus = { viewModel.resetConnectionStatus() },
      onProviderChange = { viewModel.setProviderType(it) },
      onSaveNvidiaConfig = { key, model, baseUrl, temp, maxTokens ->
        viewModel.updateNvidiaConfig(
          apiKey = key,
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
}

/**
 * Pure Typography Tab Bar (No icons).
 */
@Composable
fun PremiumTabBar(
  selectedTab: UiTab,
  feedCount: Int,
  artifactCount: Int,
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
        .padding(horizontal = 8.dp, vertical = 5.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      UiTab.values().forEach { tab ->
        val isSelected = selectedTab == tab
        val label = when (tab) {
          UiTab.EXECUTION_FEED -> "FEED"
          UiTab.ARTIFACTS -> "FILES"
          UiTab.FIVE_STAGES -> "COGNITION"
          UiTab.ABOUT_ARAGON -> "SYSTEM"
        }
        val badge = when (tab) {
          UiTab.EXECUTION_FEED -> if (feedCount > 0) "$feedCount" else null
          UiTab.ARTIFACTS -> if (artifactCount > 0) "$artifactCount" else null
          else -> null
        }

        val bgColor by animateColorAsState(
          targetValue = if (isSelected) Color(0xFF222222) else Color.Transparent,
          animationSpec = tween(120),
          label = "tab_bg"
        )
        val contentColor by animateColorAsState(
          targetValue = if (isSelected) Color.White else AmoledIconGrey,
          animationSpec = tween(120),
          label = "tab_content_color"
        )

        Surface(
          shape = RoundedCornerShape(6.dp),
          color = bgColor,
          border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF383838)) else null,
          modifier = Modifier
            .weight(1f)
            .clickable { onSelectTab(tab) }
            .testTag("tab_${tab.name.lowercase()}")
        ) {
          Row(
            modifier = Modifier.padding(vertical = 7.dp, horizontal = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = label,
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              color = contentColor,
              fontSize = 11.sp,
              maxLines = 1
            )
            if (badge != null) {
              Spacer(modifier = Modifier.width(4.dp))
              Surface(
                shape = RoundedCornerShape(3.dp),
                color = if (isSelected) AmoledActionPrimary else Color(0xFF1E1E1E),
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
                    fontSize = 8.sp,
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
  onStop: () -> Unit,
  onOpenAbout: () -> Unit,
  onResetWorkspace: () -> Unit,
  onGetTranscript: () -> String
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
        .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        // LEFT-ALIGNED LOGO + ARAGON + STATUS PILL
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f, fill = false)
        ) {
          Icon(
            painter = painterResource(id = R.drawable.ic_aragon_logo),
            contentDescription = "Aragon Logo",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "ARAGON",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.5.sp,
            color = Color.White
          )
          Spacer(modifier = Modifier.width(10.dp))
          StatusPill(status = status)
        }

        // RIGHT-ALIGNED PURE TEXT ACTIONS (No icons)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          LittleCopyButton(
            textToCopy = onGetTranscript(),
            label = "LOG",
            testTag = "copy_transcript_header"
          )

          if (canCancel) {
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = Color(0xFF221111),
              border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusError.copy(alpha = 0.5f)),
              modifier = Modifier
                .clickable { onStop() }
                .padding(horizontal = 1.dp)
            ) {
              Text(
                text = "STOP",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                color = AmoledStatusError,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
              )
            }
          }

          Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color(0xFF141414),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
            modifier = Modifier.clickable { onResetWorkspace() }
          ) {
            Text(
              text = "RESET",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Medium,
              fontSize = 9.sp,
              color = AmoledIconGreyLight,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
            )
          }

          Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color(0xFF141414),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
            modifier = Modifier.clickable { onOpenAbout() }
          ) {
            Text(
              text = "SYSTEM",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Medium,
              fontSize = 9.sp,
              color = AmoledIconGreyLight,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
            )
          }
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
    AgentStatus.THINKING -> Triple(Color(0xFF181818), AmoledIconGreyLight, "THINKING")
    AgentStatus.PLANNING -> Triple(Color(0xFF181818), AmoledIconGreyLight, "PLANNING")
    AgentStatus.EXECUTING_TOOL -> Triple(Color(0xFF1A1A1A), AmoledTextPrimary, "TOOL CALL")
    AgentStatus.OBSERVING -> Triple(Color(0xFF161616), AmoledIconGreyLight, "OBSERVING")
    AgentStatus.VERIFYING -> Triple(Color(0xFF181818), AmoledIconGreyLight, "VERIFYING")
    AgentStatus.COMPLETED -> Triple(Color(0xFF102015), AmoledStatusSuccess, "COMPLETED")
    AgentStatus.FAILED -> Triple(Color(0xFF221111), AmoledStatusError, "FAILED")
    AgentStatus.CANCELLED -> Triple(Color(0xFF141414), AmoledTextMuted, "STOPPED")
  }

  Surface(
    shape = RoundedCornerShape(4.dp),
    color = bgColor,
    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
    modifier = Modifier.height(22.dp)
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.padding(horizontal = 6.dp)
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        color = textColor,
        fontSize = 9.sp
      )
    }
  }
}

/**
 * Home Screen (Empty State):
 * Keeps ONLY the Logo, Slogan, and "Start a New Task" button.
 * All suggestion cards are removed from the home screen as requested.
 */
@Composable
fun EmptyExecutionState(
  onQuickRun: () -> Unit
) {
  val scrollState = rememberScrollState()
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(scrollState)
      .padding(horizontal = 24.dp, vertical = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    // Aragon Emblem
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(56.dp)
    ) {
      Icon(
        painter = painterResource(id = R.drawable.ic_aragon_logo),
        contentDescription = "Aragon Logo",
        tint = Color.White,
        modifier = Modifier.size(48.dp)
      )
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text(
      text = "ARAGON",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
      letterSpacing = 3.5.sp,
      color = Color.White
    )

    Spacer(modifier = Modifier.height(14.dp))

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

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = "An autonomous execution engine that investigates, writes code, runs Python, and crafts verified deliverables in an isolated environment.",
      style = MaterialTheme.typography.bodySmall,
      fontFamily = InterFontFamily,
      color = AmoledTextMuted,
      lineHeight = 19.sp,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 8.dp)
    )

    Spacer(modifier = Modifier.height(28.dp))

    // "Start a New Task" Button: Pure text (No icon)
    Button(
      onClick = onQuickRun,
      shape = RoundedCornerShape(10.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = AmoledActionPrimary,
        contentColor = AmoledActionPrimaryOn
      ),
      modifier = Modifier
        .fillMaxWidth(0.72f)
        .height(46.dp)
        .testTag("quick_start_button")
    ) {
      Text(
        text = "Start a New Task",
        style = MaterialTheme.typography.labelLarge,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp
      )
    }
  }
}

/**
 * Normal Model Selector Dialog:
 * Shows list of available models cleanly without exposing secret settings.
 */
@Composable
fun ModelSelectorDialog(
  currentProvider: ProviderType,
  currentNvidiaModel: String,
  onSelectSandbox: () -> Unit,
  onSelectNvidiaModel: (String) -> Unit,
  onSelectGemini: () -> Unit,
  onDismiss: () -> Unit
) {
  val vScroll = rememberScrollState()

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = Color(0xFF101010),
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 520.dp)
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
            text = "SELECT MODEL",
            style = MaterialTheme.typography.titleSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = AmoledTextPrimary,
            letterSpacing = 1.sp
          )

          Text(
            text = "[CLOSE]",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 10.sp,
            color = AmoledIconGrey,
            modifier = Modifier
              .clickable { onDismiss() }
              .padding(4.dp)
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(vScroll),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          // Section 1: Local Sandbox Engine
          Text(
            text = "LOCAL EXECUTION",
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
                  text = "Deterministic offline engine with verified tool execution",
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

          // Section 2: NVIDIA NIM Cloud Models
          Text(
            text = "NVIDIA NIM CLOUD MODELS",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = AmoledTextMuted,
            fontSize = 9.sp
          )

          NvidiaNimModels.CATALOG.forEach { modelEntry ->
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

          // Section 3: Google Gemini
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
  initialNvidiaModel: String,
  initialNvidiaBaseUrl: String,
  initialNvidiaTemperature: Double,
  initialNvidiaMaxTokens: Int,
  geminiKey: String,
  connectionStatus: ConnectionStatus,
  onTestConnection: () -> Unit,
  onResetConnectionStatus: () -> Unit,
  onProviderChange: (ProviderType) -> Unit,
  onSaveNvidiaConfig: (key: String, model: String, baseUrl: String, temp: Double, maxTokens: Int) -> Unit,
  onSaveGeminiKey: (String) -> Unit,
  onDismiss: () -> Unit
) {
  var selectedType by remember { mutableStateOf(currentProvider) }
  var nvidiaKeyInput by remember { mutableStateOf(initialNvidiaApiKey) }
  var isApiKeyVisible by remember { mutableStateOf(false) }
  var nvidiaModelInput by remember { mutableStateOf(initialNvidiaModel) }
  var nvidiaBaseUrlInput by remember { mutableStateOf(initialNvidiaBaseUrl) }
  var nvidiaTempInput by remember { mutableStateOf(initialNvidiaTemperature.toString()) }
  var nvidiaMaxTokensInput by remember { mutableStateOf(initialNvidiaMaxTokens.toString()) }
  var geminiKeyInput by remember { mutableStateOf(geminiKey) }

  AlertDialog(
    onDismissRequest = {
      onResetConnectionStatus()
      onDismiss()
    },
    containerColor = AmoledSurface,
    title = {
      Text(
        text = "Developer Configuration & API Settings",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = AmoledTextPrimary
      )
    },
    text = {
      val scrollState = rememberScrollState()
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(scrollState)
      ) {
        Text(
          "Active Engine:",
          style = MaterialTheme.typography.labelMedium,
          color = AmoledIconGreyLight,
          fontWeight = FontWeight.SemiBold
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

            // API Key field
            OutlinedTextField(
              value = nvidiaKeyInput,
              onValueChange = { nvidiaKeyInput = it },
              label = { Text("NVIDIA API Key") },
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
              Text("Offline deterministic engine with full tool dispatch", style = MaterialTheme.typography.labelSmall, color = AmoledTextMuted)
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
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onProviderChange(selectedType)
          if (selectedType == ProviderType.NVIDIA_NIM) {
            onSaveNvidiaConfig(
              nvidiaKeyInput,
              nvidiaModelInput,
              nvidiaBaseUrlInput,
              nvidiaTempInput.toDoubleOrNull() ?: 0.2,
              nvidiaMaxTokensInput.toIntOrNull() ?: 4096
            )
          } else if (selectedType == ProviderType.GEMINI_LIVE_API) {
            onSaveGeminiKey(geminiKeyInput)
          }
          onResetConnectionStatus()
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
          onDismiss()
        }
      ) {
        Text("Cancel", color = AmoledIconGrey)
      }
    }
  )
}
