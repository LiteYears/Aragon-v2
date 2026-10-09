package com.example.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.core.AgentStatus
import com.example.agent.core.NvidiaNimConfig
import com.example.agent.core.NvidiaNimModels
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

  val listState = rememberLazyListState()

  // Smooth scroll to newly added steps
  LaunchedEffect(state.executionFeed.size) {
    if (state.executionFeed.isNotEmpty()) {
      listState.animateScrollToItem(state.executionFeed.size - 1)
    }
  }

  Scaffold(
    modifier = modifier
      .fillMaxSize()
      .background(AmoledBackground)
      .imePadding(),
    containerColor = AmoledBackground,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      AgentTopHeader(
        status = state.status,
        currentAction = state.currentAction,
        providerType = providerType,
        nvidiaModel = nvidiaModel,
        canCancel = state.canCancel,
        onStop = { viewModel.stopExecution() },
        onOpenSettings = { showSettingsDialog = true },
        onResetWorkspace = { viewModel.resetWorkspace() },
        onGetTranscript = { viewModel.getExecutionTranscript() }
      )
    },
    bottomBar = {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AmoledBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
          // Preset prompt chips in modern Flutter flat pill design
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
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF141414),
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
                  fontFamily = InterFontFamily,
                  color = AmoledIconGreyLight,
                  fontSize = 11.sp,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
              }
            }
          }

          // Modern Interactive Floating User Prompt Field
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF101010),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
              // Top meta bar inside prompt field: Active model pill & Clear button
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                // Interactive model switcher pill
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = Color(0xFF181818),
                  border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
                  modifier = Modifier.clickable { showSettingsDialog = true }
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                  ) {
                    Box(
                      modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(AmoledStatusSuccess)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = nvidiaModel.substringAfterLast('/'),
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 10.sp,
                      color = AmoledIconGreyLight
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                      imageVector = Icons.Default.ArrowDropDown,
                      contentDescription = "Switch model",
                      tint = AmoledIconGrey,
                      modifier = Modifier.size(14.dp)
                    )
                  }
                }

                if (inputText.isNotBlank()) {
                  IconButton(
                    onClick = { inputText = "" },
                    modifier = Modifier.size(24.dp)
                  ) {
                    Icon(
                      imageVector = Icons.Default.Clear,
                      contentDescription = "Clear text",
                      tint = AmoledIconGrey,
                      modifier = Modifier.size(15.dp)
                    )
                  }
                }
              }

              Spacer(modifier = Modifier.height(6.dp))

              // Multiline input area with Flutter style execute button
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

                // Modern Flutter-style flat execution task button
                if (state.canCancel) {
                  Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF221111),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusError.copy(alpha = 0.5f)),
                    modifier = Modifier
                      .size(38.dp)
                      .clickable { viewModel.stopExecution() }
                      .testTag("stop_execution_button")
                  ) {
                    Box(contentAlignment = Alignment.Center) {
                      Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop execution",
                        tint = AmoledStatusError,
                        modifier = Modifier.size(18.dp)
                      )
                    }
                  }
                } else {
                  val isReady = inputText.isNotBlank()
                  val buttonColor by animateColorAsState(
                    targetValue = if (isReady) AmoledActionPrimary else Color(0xFF1C1C1C),
                    animationSpec = tween(200),
                    label = "btn_color"
                  )
                  val iconColor by animateColorAsState(
                    targetValue = if (isReady) AmoledActionPrimaryOn else AmoledIconGreyDark,
                    animationSpec = tween(200),
                    label = "icon_color"
                  )

                  Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = buttonColor,
                    border = androidx.compose.foundation.BorderStroke(
                      1.dp,
                      if (isReady) AmoledActionPrimary else AmoledBorderSubtle
                    ),
                    modifier = Modifier
                      .size(38.dp)
                      .clickable(enabled = isReady) {
                        if (isReady) {
                          val taskText = inputText
                          viewModel.submitTask(taskText)
                        }
                      }
                      .testTag("run_task_button")
                  ) {
                    Box(contentAlignment = Alignment.Center) {
                      Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Execute task",
                        tint = iconColor,
                        modifier = Modifier.size(18.dp)
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
      // Discrete Error Banner
      AnimatedVisibility(visible = state.error != null) {
        state.error?.let { errText ->
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF140A0A),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusError.copy(alpha = 0.5f)),
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 6.dp)
              .testTag("error_banner")
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                  Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Error",
                    tint = AmoledStatusError,
                    modifier = Modifier.size(16.dp)
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = "EXECUTION ISSUE",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = AmoledStatusError
                  )
                }

                LittleCopyButton(
                  textToCopy = errText,
                  label = "Copy",
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
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                  OutlinedButton(
                    onClick = { viewModel.retryWithDefaultModel() },
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledTextPrimary)
                  ) {
                    Text("Retry with Flagship GLM 5.3", style = MaterialTheme.typography.labelSmall)
                  }
                }
              }
            }
          }
        }
      }

      // Modern Flat AMOLED Tab Row
      TabRow(
        selectedTabIndex = activeTab.ordinal,
        containerColor = AmoledBackground,
        contentColor = AmoledTextPrimary,
        divider = {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(1.dp)
              .background(AmoledBorderSubtle)
          )
        },
        indicator = { tabPositions ->
          if (activeTab.ordinal < tabPositions.size) {
            TabRowDefaults.SecondaryIndicator(
              modifier = Modifier.tabIndicatorOffset(tabPositions[activeTab.ordinal]),
              color = AmoledActionPrimary,
              height = 2.dp
            )
          }
        },
        modifier = Modifier.fillMaxWidth()
      ) {
        UiTab.values().forEach { tab ->
          val badgeCount = when (tab) {
            UiTab.EXECUTION_FEED -> state.executionFeed.size
            UiTab.ARTIFACTS -> state.artifacts.size
            UiTab.FIVE_STAGES -> null
          }
          Tab(
            selected = activeTab == tab,
            onClick = { viewModel.selectTab(tab) },
            text = {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 10.dp)
              ) {
                Text(
                  text = tab.label,
                  fontWeight = if (activeTab == tab) FontWeight.SemiBold else FontWeight.Normal,
                  style = MaterialTheme.typography.bodySmall,
                  color = if (activeTab == tab) AmoledTextPrimary else AmoledTextMuted
                )
                if (badgeCount != null && badgeCount > 0) {
                  Spacer(modifier = Modifier.width(6.dp))
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF181818),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
                    modifier = Modifier.height(18.dp)
                  ) {
                    Box(
                      contentAlignment = Alignment.Center,
                      modifier = Modifier.padding(horizontal = 5.dp)
                    ) {
                      Text(
                        text = "$badgeCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 10.sp,
                        color = if (activeTab == tab) AmoledTextPrimary else AmoledTextMuted
                      )
                    }
                  }
                }
              }
            },
            modifier = Modifier.testTag("tab_${tab.name.lowercase()}")
          )
        }
      }

      // Tab Content Views
      when (activeTab) {
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
                .testTag("execution_feed_list")
                .animateContentSize(),
              contentPadding = PaddingValues(vertical = 10.dp, horizontal = 2.dp)
            ) {
              itemsIndexed(state.executionFeed) { index, step ->
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
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = Color(0xFF141414),
                  border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
                  modifier = Modifier.size(52.dp)
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector = Icons.Default.Terminal,
                      contentDescription = null,
                      tint = AmoledIconGrey,
                      modifier = Modifier.size(26.dp)
                    )
                  }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                  text = "No artifacts generated yet",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold,
                  color = AmoledTextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "Run a task to produce files in the workspace (e.g. data.csv → report.md).",
                  style = MaterialTheme.typography.bodySmall,
                  color = AmoledTextMuted
                )
              }
            }
          } else {
            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .testTag("artifacts_list"),
              contentPadding = PaddingValues(vertical = 10.dp)
            ) {
              itemsIndexed(state.artifacts) { _, artifact ->
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

  // Improved AMOLED Settings & Provider Dialog
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

@Composable
fun AgentTopHeader(
  status: AgentStatus,
  currentAction: String?,
  providerType: ProviderType,
  nvidiaModel: String,
  canCancel: Boolean,
  onStop: () -> Unit,
  onOpenSettings: () -> Unit,
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
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          // Modern Flutter squircle logo badge in graphite with grey icon
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF161616),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.size(34.dp)
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = AmoledIconGreyLight,
                modifier = Modifier.size(18.dp)
              )
            }
          }

          Spacer(modifier = Modifier.width(10.dp))

          Column {
            Text(
              text = "Agent Kernel",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              color = AmoledTextPrimary
            )
            val modelNameShort = nvidiaModel.substringAfterLast('/')
            val providerSubtitle = when (providerType) {
              ProviderType.NVIDIA_NIM -> "NVIDIA NIM • $modelNameShort"
              ProviderType.SANDBOX_ENGINE -> "Autonomous Sandbox Engine"
              ProviderType.GEMINI_LIVE_API -> "Gemini Live API"
            }
            Text(
              text = providerSubtitle,
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              color = AmoledIconGrey,
              fontSize = 10.sp
            )
          }
        }

        // Header Actions: Status pill, Log copy, Reset, Settings
        Row(verticalAlignment = Alignment.CenterVertically) {
          StatusPill(status = status)

          Spacer(modifier = Modifier.width(4.dp))

          LittleCopyButton(
            textToCopy = onGetTranscript(),
            label = "Log",
            tint = AmoledIconGrey,
            buttonSize = 32.dp,
            iconSize = 14.dp,
            testTag = "copy_transcript_header"
          )

          if (canCancel) {
            IconButton(
              onClick = onStop,
              modifier = Modifier.size(32.dp)
            ) {
              Icon(
                Icons.Default.Stop,
                contentDescription = "Stop",
                tint = AmoledStatusError,
                modifier = Modifier.size(18.dp)
              )
            }
          }

          IconButton(
            onClick = onResetWorkspace,
            modifier = Modifier.size(32.dp)
          ) {
            Icon(
              Icons.Default.Refresh,
              contentDescription = "Reset Workspace",
              tint = AmoledIconGrey,
              modifier = Modifier.size(18.dp)
            )
          }

          IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(32.dp)
          ) {
            Icon(
              Icons.Default.Settings,
              contentDescription = "Settings",
              tint = AmoledIconGrey,
              modifier = Modifier.size(18.dp)
            )
          }
        }
      }

      // Active status bar
      if (!currentAction.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(6.dp))
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(8.dp),
          color = Color(0xFF101010),
          border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle)
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(AmoledActionPrimary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = currentAction,
              style = MaterialTheme.typography.bodySmall,
              fontFamily = InterFontFamily,
              color = AmoledTextSecondary,
              fontSize = 11.sp,
              maxLines = 1,
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
    shape = RoundedCornerShape(8.dp),
    color = bgColor,
    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
    modifier = Modifier.height(24.dp)
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.padding(horizontal = 8.dp)
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Medium,
        color = textColor,
        fontSize = 10.sp
      )
    }
  }
}

@Composable
fun EmptyExecutionState(
  onQuickRun: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 24.dp, vertical = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = Color(0xFF141414),
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
      modifier = Modifier.size(60.dp)
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Default.Terminal,
          contentDescription = null,
          tint = AmoledIconGreyLight,
          modifier = Modifier.size(28.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = "Autonomous AI Agent Kernel",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      color = AmoledTextPrimary
    )

    Spacer(modifier = Modifier.height(6.dp))

    Surface(
      shape = RoundedCornerShape(8.dp),
      color = Color(0xFF111111),
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
      modifier = Modifier.padding(horizontal = 8.dp)
    ) {
      Text(
        text = "GOAL → PLAN → TOOL CALL → EXECUTE → VERIFY",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFontFamily,
        color = AmoledIconGrey,
        fontSize = 10.sp,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
      )
    }

    Spacer(modifier = Modifier.height(14.dp))

    Text(
      text = "Execute sandboxed tasks with multi-turn tool calling, verifiable filesystem artifacts, and fallback recovery.",
      style = MaterialTheme.typography.bodySmall,
      color = AmoledTextMuted,
      lineHeight = 18.sp,
      modifier = Modifier.padding(horizontal = 16.dp)
    )

    Spacer(modifier = Modifier.height(20.dp))

    Button(
      onClick = onQuickRun,
      shape = RoundedCornerShape(10.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = AmoledActionPrimary,
        contentColor = AmoledActionPrimaryOn
      ),
      modifier = Modifier
        .height(42.dp)
        .testTag("quick_start_button")
    ) {
      Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text("Sample: data.csv → report.md", style = MaterialTheme.typography.labelMedium)
    }
  }
}

/**
 * Improved AMOLED Dark Settings & Model Picker Dialog
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
  var modelSearchQuery by remember { mutableStateOf("") }
  var selectedCategory by remember { mutableStateOf("All") }
  var isCustomModelMode by remember { mutableStateOf(NvidiaNimModels.CATALOG.none { it.id == initialNvidiaModel }) }
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
        text = "Provider & Model Configuration",
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
          "Execution Engine:",
          style = MaterialTheme.typography.labelMedium,
          color = AmoledIconGreyLight,
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Option 1: NVIDIA NIM (Primary)
        Surface(
          shape = RoundedCornerShape(10.dp),
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
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  "NVIDIA NIM Cloud",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = AmoledTextPrimary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                  shape = RoundedCornerShape(4.dp),
                  color = Color(0xFF222222),
                  border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder)
                ) {
                  Text(
                    text = "FREE MODELS",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmoledIconGreyLight,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                  )
                }
              }
              Text(
                "Structured tool calling with automatic model fallback recovery",
                style = MaterialTheme.typography.labelSmall,
                color = AmoledTextMuted
              )
            }
          }
        }

        // Expanded NVIDIA NIM settings
        AnimatedVisibility(visible = selectedType == ProviderType.NVIDIA_NIM) {
          Column(modifier = Modifier.padding(top = 10.dp)) {
            // Live Connection Test Button and Status
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
                    modifier = Modifier.height(30.dp)
                  ) {
                    if (connectionStatus is ConnectionStatus.Testing) {
                      CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = AmoledTextPrimary
                      )
                      Spacer(modifier = Modifier.width(6.dp))
                      Text("Testing...", style = MaterialTheme.typography.labelSmall)
                    } else {
                      Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(13.dp))
                      Spacer(modifier = Modifier.width(4.dp))
                      Text("Test NIM", style = MaterialTheme.typography.labelSmall)
                    }
                  }
                }

                when (val status = connectionStatus) {
                  is ConnectionStatus.Success -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = Color(0xFF102015),
                      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusSuccess.copy(alpha = 0.3f)),
                      modifier = Modifier.fillMaxWidth()
                    ) {
                      Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = AmoledStatusSuccess, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                          text = "Verified: ${status.modelCount} models online on build.nvidia.com",
                          style = MaterialTheme.typography.labelSmall,
                          color = AmoledStatusSuccess
                        )
                      }
                    }
                  }
                  is ConnectionStatus.Error -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = Color(0xFF221111),
                      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledStatusError.copy(alpha = 0.3f)),
                      modifier = Modifier.fillMaxWidth()
                    ) {
                      Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = AmoledStatusError, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                          text = status.message,
                          style = MaterialTheme.typography.labelSmall,
                          color = AmoledStatusError
                        )
                      }
                    }
                  }
                  else -> {}
                }
              }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Model Search & Filter
            OutlinedTextField(
              value = modelSearchQuery,
              onValueChange = { modelSearchQuery = it },
              label = { Text("Filter Models") },
              placeholder = { Text("glm, llama, nemotron...") },
              leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AmoledIconGrey, modifier = Modifier.size(16.dp)) },
              trailingIcon = {
                if (modelSearchQuery.isNotBlank()) {
                  IconButton(onClick = { modelSearchQuery = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = AmoledIconGrey, modifier = Modifier.size(15.dp))
                  }
                }
              },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              textStyle = MaterialTheme.typography.bodySmall,
              shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Category filter chips in Flutter flat pill style
            val catScrollState = rememberScrollState()
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(catScrollState),
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              NvidiaNimModels.CATEGORIES.forEach { category ->
                val isCatSelected = selectedCategory == category
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = if (isCatSelected) AmoledActionPrimary else Color(0xFF161616),
                  border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
                  modifier = Modifier.clickable { selectedCategory = category }
                ) {
                  Text(
                    text = category,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = if (isCatSelected) AmoledActionPrimaryOn else AmoledIconGreyLight,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Filtered Models List Cards
            val filteredModels = NvidiaNimModels.CATALOG.filter { model ->
              val matchesCat = selectedCategory == "All" || model.category == selectedCategory
              val matchesQuery = modelSearchQuery.isBlank() ||
                model.id.contains(modelSearchQuery, ignoreCase = true) ||
                model.name.contains(modelSearchQuery, ignoreCase = true) ||
                model.description.contains(modelSearchQuery, ignoreCase = true)
              matchesCat && matchesQuery
            }

            Column(
              verticalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
            ) {
              filteredModels.forEach { modelEntry ->
                val isSelected = nvidiaModelInput == modelEntry.id && !isCustomModelMode
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = if (isSelected) Color(0xFF1E1E1E) else Color(0xFF101010),
                  border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isSelected) AmoledActionPrimary else AmoledBorderSubtle
                  ),
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                      nvidiaModelInput = modelEntry.id
                      isCustomModelMode = false
                    }
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                          text = modelEntry.name,
                          style = MaterialTheme.typography.bodySmall,
                          fontWeight = FontWeight.SemiBold,
                          color = AmoledTextPrimary
                        )
                        if (modelEntry.badge.isNotBlank()) {
                          Spacer(modifier = Modifier.width(6.dp))
                          Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF222222)
                          ) {
                            Text(
                              text = modelEntry.badge,
                              style = MaterialTheme.typography.labelSmall,
                              fontFamily = JetBrainsMonoFontFamily,
                              fontSize = 9.sp,
                              color = AmoledIconGreyLight,
                              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                          }
                        }
                      }
                      Text(
                        text = modelEntry.id,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        fontSize = 10.sp,
                        color = AmoledTextMuted
                      )
                      Text(
                        text = modelEntry.description,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        color = AmoledTextSecondary
                      )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                      LittleCopyButton(
                        textToCopy = modelEntry.id,
                        buttonSize = 24.dp,
                        iconSize = 12.dp,
                        testTag = "copy_model_id_${modelEntry.id.substringAfterLast('/')}"
                      )

                      if (isSelected) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                          imageVector = Icons.Default.Check,
                          contentDescription = "Selected",
                          tint = AmoledTextPrimary,
                          modifier = Modifier.size(16.dp)
                        )
                      }
                    }
                  }
                }
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Custom Model toggle/field with copy button
            OutlinedTextField(
              value = nvidiaModelInput,
              onValueChange = {
                nvidiaModelInput = it
                isCustomModelMode = true
              },
              label = { Text("Active Model Identifier") },
              placeholder = { Text(NvidiaNimConfig.DEFAULT_MODEL) },
              trailingIcon = {
                LittleCopyButton(textToCopy = nvidiaModelInput, buttonSize = 28.dp, iconSize = 13.dp)
              },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
              shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // API Key field with show/hide toggle
            OutlinedTextField(
              value = nvidiaKeyInput,
              onValueChange = { nvidiaKeyInput = it },
              label = { Text("NVIDIA API Key") },
              placeholder = { Text("nvapi-...") },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
              trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  LittleCopyButton(textToCopy = nvidiaKeyInput, buttonSize = 28.dp, iconSize = 13.dp)
                  IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                    Icon(
                      imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                      contentDescription = if (isApiKeyVisible) "Hide key" else "Show key",
                      tint = AmoledIconGrey,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                }
              },
              textStyle = MaterialTheme.typography.bodySmall,
              shape = RoundedCornerShape(8.dp)
            )
            Text(
              text = "Preconfigured via BuildConfig.NVIDIA_API_KEY",
              style = MaterialTheme.typography.labelSmall,
              color = AmoledTextMuted,
              modifier = Modifier.padding(top = 2.dp, start = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
              value = nvidiaBaseUrlInput,
              onValueChange = { nvidiaBaseUrlInput = it },
              label = { Text("Base URL") },
              placeholder = { Text(NvidiaNimConfig.DEFAULT_BASE_URL) },
              trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  LittleCopyButton(textToCopy = nvidiaBaseUrlInput, buttonSize = 28.dp, iconSize = 13.dp)
                  if (nvidiaBaseUrlInput != NvidiaNimConfig.DEFAULT_BASE_URL) {
                    IconButton(onClick = { nvidiaBaseUrlInput = NvidiaNimConfig.DEFAULT_BASE_URL }) {
                      Icon(Icons.Default.Refresh, contentDescription = "Reset URL", tint = AmoledIconGrey, modifier = Modifier.size(15.dp))
                    }
                  }
                }
              },
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

        Spacer(modifier = Modifier.height(10.dp))

        // Option 2: Sandbox Engine
        Surface(
          shape = RoundedCornerShape(10.dp),
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

        Spacer(modifier = Modifier.height(10.dp))

        // Option 3: Gemini Live API
        Surface(
          shape = RoundedCornerShape(10.dp),
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
