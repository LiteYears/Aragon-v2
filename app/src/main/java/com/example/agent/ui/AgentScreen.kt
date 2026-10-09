package com.example.agent.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
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

  // Auto-scroll feed on new items
  LaunchedEffect(state.executionFeed.size) {
    if (state.executionFeed.isNotEmpty()) {
      listState.animateScrollToItem(state.executionFeed.size - 1)
    }
  }

  Scaffold(
    modifier = modifier
      .fillMaxSize()
      .imePadding(),
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
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 6.dp)
        ) {
          // Preset prompt suggestion chips with smooth padding
          val chipScrollState = rememberScrollState()
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(chipScrollState)
              .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            viewModel.presets.forEach { preset ->
              FilterChip(
                selected = false,
                onClick = {
                  inputText = preset.prompt
                  viewModel.submitTask(preset.prompt, preset.expectedArtifact)
                },
                label = {
                  Text(
                    text = preset.title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                  )
                },
                shape = RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                  containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.testTag("preset_chip_${preset.expectedArtifact ?: "custom"}")
              )
            }
          }

          // Input row with generous touch targets and padding
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            OutlinedTextField(
              value = inputText,
              onValueChange = { inputText = it },
              placeholder = {
                Text(
                  "Assign an objective (e.g., analyze data.csv → report.md)...",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
              },
              trailingIcon = {
                if (inputText.isNotBlank()) {
                  IconButton(
                    onClick = { inputText = "" },
                    modifier = Modifier.size(24.dp)
                  ) {
                    Icon(
                      imageVector = Icons.Default.Clear,
                      contentDescription = "Clear input",
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                }
              },
              modifier = Modifier
                .weight(1f)
                .testTag("agent_input_field"),
              shape = RoundedCornerShape(14.dp),
              singleLine = false,
              maxLines = 3,
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
              keyboardActions = KeyboardActions(onSend = {
                if (inputText.isNotBlank()) {
                  val taskText = inputText
                  viewModel.submitTask(taskText)
                }
              }),
              textStyle = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.width(10.dp))

            if (state.canCancel) {
              Button(
                onClick = { viewModel.stopExecution() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                  .size(48.dp)
                  .testTag("stop_execution_button"),
                contentPadding = PaddingValues(0.dp)
              ) {
                Icon(Icons.Default.Stop, contentDescription = "Stop execution", modifier = Modifier.size(22.dp))
              }
            } else {
              Button(
                onClick = {
                  if (inputText.isNotBlank()) {
                    val taskText = inputText
                    viewModel.submitTask(taskText)
                  }
                },
                enabled = inputText.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primary,
                  disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                  .size(48.dp)
                  .testTag("run_task_button"),
                contentPadding = PaddingValues(0.dp)
              ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run task", modifier = Modifier.size(20.dp))
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
        .padding(paddingValues)
    ) {
      // Prominent Error Banner with 1-tap Copy Error Button
      AnimatedVisibility(visible = state.error != null) {
        state.error?.let { errText ->
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
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
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = "EXECUTION ISSUE DETECTED",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                  )
                }

                LittleCopyButton(
                  textToCopy = errText,
                  label = "Copy Error",
                  tint = MaterialTheme.colorScheme.error,
                  testTag = "copy_error_banner_btn"
                )
              }

              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = errText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
              )

              if (providerType == ProviderType.NVIDIA_NIM && nvidiaModel != NvidiaNimConfig.DEFAULT_MODEL) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                  OutlinedButton(
                    onClick = { viewModel.retryWithDefaultModel() },
                    shape = RoundedCornerShape(8.dp)
                  ) {
                    Text("Retry with Flagship GLM 5.3", style = MaterialTheme.typography.labelSmall)
                  }
                }
              }
            }
          }
        }
      }

      // Tab navigation bar
      TabRow(
        selectedTabIndex = activeTab.ordinal,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
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
                modifier = Modifier.padding(vertical = 12.dp)
              ) {
                Text(
                  text = tab.label,
                  fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Medium,
                  style = MaterialTheme.typography.bodyMedium
                )
                if (badgeCount != null && badgeCount > 0) {
                  Spacer(modifier = Modifier.width(6.dp))
                  Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (activeTab == tab) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.height(20.dp)
                  ) {
                    Box(
                      contentAlignment = Alignment.Center,
                      modifier = Modifier.padding(horizontal = 6.dp)
                    ) {
                      Text(
                        text = "$badgeCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = if (activeTab == tab) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
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

      // Tab Content View
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
                .testTag("execution_feed_list"),
              contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp)
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
                  shape = RoundedCornerShape(16.dp),
                  color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                  modifier = Modifier.size(56.dp)
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector = Icons.Default.Terminal,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.outline,
                      modifier = Modifier.size(28.dp)
                    )
                  }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                  text = "No artifacts generated yet",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "Run a task that produces files in the workspace (e.g. data.csv → report.md).",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  lineHeight = 20.sp
                )
              }
            }
          } else {
            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .testTag("artifacts_list"),
              contentPadding = PaddingValues(vertical = 14.dp)
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

  // Provider & Settings Dialog
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
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp)
              )
            }
          }

          Spacer(modifier = Modifier.width(10.dp))

          Column {
            Text(
              text = "Agent Kernel",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            val modelNameShort = nvidiaModel.substringAfterLast('/')
            val providerSubtitle = when (providerType) {
              ProviderType.NVIDIA_NIM -> "NVIDIA NIM ($modelNameShort)"
              ProviderType.SANDBOX_ENGINE -> "Autonomous Sandbox Engine"
              ProviderType.GEMINI_LIVE_API -> "Google Gemini Live API"
            }
            Text(
              text = providerSubtitle,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.SemiBold
            )
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          StatusPill(status = status)

          Spacer(modifier = Modifier.width(4.dp))

          // Copy Full Transcript button
          LittleCopyButton(
            textToCopy = onGetTranscript(),
            label = "Log",
            buttonSize = 34.dp,
            iconSize = 16.dp,
            testTag = "copy_transcript_header"
          )

          if (canCancel) {
            IconButton(
              onClick = onStop,
              modifier = Modifier.size(36.dp)
            ) {
              Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
            }
          }

          IconButton(
            onClick = onResetWorkspace,
            modifier = Modifier.size(36.dp)
          ) {
            Icon(Icons.Default.Refresh, contentDescription = "Reset Workspace", tint = MaterialTheme.colorScheme.onSurfaceVariant)
          }

          IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(36.dp)
          ) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }

      if (!currentAction.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "ACTIVE:",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = currentAction,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface,
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
    AgentStatus.IDLE -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "IDLE")
    AgentStatus.THINKING -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, "THINKING")
    AgentStatus.PLANNING -> Triple(Color(0xFFE0E7FF), Color(0xFF3730A3), "PLANNING")
    AgentStatus.EXECUTING_TOOL -> Triple(Color(0xFFFEF3C7), Color(0xFF92400E), "EXECUTING")
    AgentStatus.OBSERVING -> Triple(Color(0xFFD1FAE5), Color(0xFF065F46), "OBSERVING")
    AgentStatus.VERIFYING -> Triple(Color(0xFFEDE9FE), Color(0xFF5B21B6), "VERIFYING")
    AgentStatus.COMPLETED -> Triple(Color(0xFFDCFCE7), Color(0xFF166534), "COMPLETED")
    AgentStatus.FAILED -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "FAILED")
    AgentStatus.CANCELLED -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.outline, "STOPPED")
  }

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = bgColor,
    modifier = Modifier.height(26.dp)
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.padding(horizontal = 9.dp)
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = textColor,
        fontSize = 11.sp
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
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
      modifier = Modifier.size(68.dp)
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Default.AutoAwesome,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(34.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = "Autonomous AI Agent Kernel",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(modifier = Modifier.height(6.dp))

    Surface(
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
      modifier = Modifier.padding(horizontal = 8.dp)
    ) {
      Text(
        text = "GOAL → PLAN → THINK → TOOL CALL → EXECUTE → OBSERVE → VERIFY",
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
      )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = "The agent executes sandboxed tools with verification. Powered by NVIDIA NIM models with native structured tool calling and automatic fallback recovery.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      lineHeight = 22.sp
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(
      onClick = onQuickRun,
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier
        .height(48.dp)
        .testTag("quick_start_button")
    ) {
      Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Text("Run Sample: data.csv → report.md", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
  }
}

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
    title = {
      Text(
        text = "Provider & Model Configuration",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      val scrollState = rememberScrollState()
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(scrollState)
      ) {
        Text("Select Execution Mode:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        // Option 1: NVIDIA NIM (Primary)
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = if (selectedType == ProviderType.NVIDIA_NIM) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface,
          border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selectedType == ProviderType.NVIDIA_NIM) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
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
              onClick = { selectedType = ProviderType.NVIDIA_NIM }
            )
            Column(modifier = Modifier.padding(start = 6.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NVIDIA NIM (Hosted)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                  shape = RoundedCornerShape(4.dp),
                  color = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                  Text(
                    text = "FREE MODELS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                  )
                }
              }
              Text(
                "OpenAI-compatible native structured tool calling API with seamless fallback",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }

        // Expanded NVIDIA NIM settings
        AnimatedVisibility(visible = selectedType == ProviderType.NVIDIA_NIM) {
          Column(modifier = Modifier.padding(top = 12.dp)) {
            // Live Connection Test Button and Status
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
              border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
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
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                  )
                  Button(
                    onClick = onTestConnection,
                    enabled = connectionStatus !is ConnectionStatus.Testing,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                  ) {
                    if (connectionStatus is ConnectionStatus.Testing) {
                      CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                      )
                      Spacer(modifier = Modifier.width(6.dp))
                      Text("Testing...", style = MaterialTheme.typography.labelSmall)
                    } else {
                      Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                      Spacer(modifier = Modifier.width(4.dp))
                      Text("Test Endpoint", style = MaterialTheme.typography.labelSmall)
                    }
                  }
                }

                when (val status = connectionStatus) {
                  is ConnectionStatus.Success -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = Color(0xFF10B981).copy(alpha = 0.15f),
                      modifier = Modifier.fillMaxWidth()
                    ) {
                      Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                          text = "Live: ${status.modelCount} models verified on build.nvidia.com",
                          style = MaterialTheme.typography.labelSmall,
                          color = Color(0xFF047857),
                          fontWeight = FontWeight.SemiBold
                        )
                      }
                    }
                  }
                  is ConnectionStatus.Error -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                      modifier = Modifier.fillMaxWidth()
                    ) {
                      Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                          text = status.message,
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.error
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
              label = { Text("Search Models") },
              placeholder = { Text("Filter models (e.g. glm, llama, nemotron)...") },
              leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
              trailingIcon = {
                if (modelSearchQuery.isNotBlank()) {
                  IconButton(onClick = { modelSearchQuery = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                  }
                }
              },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              textStyle = MaterialTheme.typography.bodySmall,
              shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Category filter chips
            val catScrollState = rememberScrollState()
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(catScrollState),
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              NvidiaNimModels.CATEGORIES.forEach { category ->
                FilterChip(
                  selected = selectedCategory == category,
                  onClick = { selectedCategory = category },
                  label = { Text(category, style = MaterialTheme.typography.labelSmall) },
                  shape = RoundedCornerShape(8.dp),
                  colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                  )
                )
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
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
            ) {
              filteredModels.forEach { modelEntry ->
                val isSelected = nvidiaModelInput == modelEntry.id && !isCustomModelMode
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                  border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
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
                          fontWeight = FontWeight.Bold,
                          color = MaterialTheme.colorScheme.onSurface
                        )
                        if (modelEntry.badge.isNotBlank()) {
                          Spacer(modifier = Modifier.width(6.dp))
                          Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (modelEntry.isVerified) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant
                          ) {
                            Text(
                              text = modelEntry.badge,
                              style = MaterialTheme.typography.labelSmall,
                              fontSize = 9.sp,
                              color = if (modelEntry.isVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                              fontWeight = FontWeight.Bold,
                              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                          }
                        }
                      }
                      Text(
                        text = modelEntry.id,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline
                      )
                      Text(
                        text = modelEntry.description,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                      LittleCopyButton(
                        textToCopy = modelEntry.id,
                        buttonSize = 24.dp,
                        iconSize = 13.dp,
                        testTag = "copy_model_id_${modelEntry.id.substringAfterLast('/')}"
                      )

                      if (isSelected) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                          imageVector = Icons.Default.Check,
                          contentDescription = "Selected",
                          tint = MaterialTheme.colorScheme.primary,
                          modifier = Modifier.size(18.dp)
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
                LittleCopyButton(textToCopy = nvidiaModelInput, buttonSize = 28.dp, iconSize = 14.dp)
              },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
              shape = RoundedCornerShape(10.dp)
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
                  LittleCopyButton(textToCopy = nvidiaKeyInput, buttonSize = 28.dp, iconSize = 14.dp)
                  IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                    Icon(
                      imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                      contentDescription = if (isApiKeyVisible) "Hide key" else "Show key",
                      modifier = Modifier.size(18.dp)
                    )
                  }
                }
              },
              textStyle = MaterialTheme.typography.bodySmall,
              shape = RoundedCornerShape(10.dp)
            )
            Text(
              text = "Configurable via .env / BuildConfig.NVIDIA_API_KEY",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.outline,
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
                  LittleCopyButton(textToCopy = nvidiaBaseUrlInput, buttonSize = 28.dp, iconSize = 14.dp)
                  if (nvidiaBaseUrlInput != NvidiaNimConfig.DEFAULT_BASE_URL) {
                    IconButton(onClick = { nvidiaBaseUrlInput = NvidiaNimConfig.DEFAULT_BASE_URL }) {
                      Icon(Icons.Default.Refresh, contentDescription = "Reset URL", modifier = Modifier.size(16.dp))
                    }
                  }
                }
              },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              textStyle = MaterialTheme.typography.bodySmall,
              shape = RoundedCornerShape(10.dp)
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
                shape = RoundedCornerShape(10.dp)
              )
              OutlinedTextField(
                value = nvidiaMaxTokensInput,
                onValueChange = { nvidiaMaxTokensInput = it },
                label = { Text("Max Tokens") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(10.dp)
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Option 2: Sandbox Engine
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = if (selectedType == ProviderType.SANDBOX_ENGINE) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface,
          border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selectedType == ProviderType.SANDBOX_ENGINE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
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
              onClick = { selectedType = ProviderType.SANDBOX_ENGINE }
            )
            Column(modifier = Modifier.padding(start = 6.dp)) {
              Text("Autonomous Sandbox Engine", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
              Text("Offline deterministic engine with full tool dispatch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Option 3: Gemini Live API
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = if (selectedType == ProviderType.GEMINI_LIVE_API) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface,
          border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selectedType == ProviderType.GEMINI_LIVE_API) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
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
              onClick = { selectedType = ProviderType.GEMINI_LIVE_API }
            )
            Column(modifier = Modifier.padding(start = 6.dp)) {
              Text("Google Gemini Live API", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
              Text("gemini-2.5-flash with function calling", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
              shape = RoundedCornerShape(10.dp)
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
        }
      ) {
        Text("Apply Changes")
      }
    },
    dismissButton = {
      TextButton(
        onClick = {
          onResetConnectionStatus()
          onDismiss()
        }
      ) {
        Text("Cancel")
      }
    }
  )
}
