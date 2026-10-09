package com.example.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.core.AgentStatus
import com.example.agent.ui.components.ArtifactItem
import com.example.agent.ui.components.ArtifactPreviewDialog
import com.example.agent.ui.components.ExecutionFeedItem
import com.example.agent.ui.components.FiveStagesInspector

@Composable
fun AgentScreen(
  viewModel: AgentViewModel,
  modifier: Modifier = Modifier
) {
  val state by viewModel.state.collectAsState()
  val activeTab by viewModel.activeTab.collectAsState()
  val providerType by viewModel.providerType.collectAsState()
  val geminiApiKey by viewModel.geminiApiKey.collectAsState()
  val selectedArtifact by viewModel.selectedArtifactForPreview.collectAsState()
  val previewContent by viewModel.previewContent.collectAsState()

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
    modifier = modifier.fillMaxSize().imePadding(),
    topBar = {
      AgentTopHeader(
        status = state.status,
        currentAction = state.currentAction,
        providerType = providerType,
        canCancel = state.canCancel,
        onStop = { viewModel.stopExecution() },
        onOpenSettings = { showSettingsDialog = true },
        onResetWorkspace = { viewModel.resetWorkspace() }
      )
    },
    bottomBar = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surface)
          .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        // Preset prompt suggestion chips
        val chipScrollState = rememberScrollState()
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(chipScrollState)
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
                  style = MaterialTheme.typography.labelSmall
                )
              },
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.testTag("preset_chip_${preset.expectedArtifact ?: "custom"}")
            )
          }
        }

        // Input row
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 2.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            placeholder = {
              Text("Assign an objective (e.g., analyze data.csv → report.md)...", style = MaterialTheme.typography.bodySmall)
            },
            modifier = Modifier
              .weight(1f)
              .testTag("agent_input_field"),
            shape = RoundedCornerShape(8.dp),
            singleLine = false,
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodySmall
          )

          Spacer(modifier = Modifier.width(8.dp))

          if (state.canCancel) {
            Button(
              onClick = { viewModel.stopExecution() },
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.testTag("stop_execution_button")
            ) {
              Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(18.dp))
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
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.testTag("run_task_button")
            ) {
              Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run", modifier = Modifier.size(18.dp))
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
      // Tab navigation bar
      TabRow(
        selectedTabIndex = activeTab.ordinal,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary
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
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tab.label, fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Normal)
                if (badgeCount != null && badgeCount > 0) {
                  Spacer(modifier = Modifier.width(6.dp))
                  Surface(
                    shape = CircleShape,
                    color = if (activeTab == tab) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(18.dp)
                  ) {
                    Box(contentAlignment = Alignment.Center) {
                      Text(
                        text = "$badgeCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp
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
              contentPadding = PaddingValues(vertical = 12.dp)
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
              Text(
                text = "No artifacts generated yet.\nRun a task that produces files in the workspace.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
              )
            }
          } else {
            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp)
                .testTag("artifacts_list")
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
      geminiKey = geminiApiKey,
      onProviderChange = { viewModel.setProviderType(it) },
      onKeyChange = { viewModel.updateGeminiApiKey(it) },
      onDismiss = { showSettingsDialog = false }
    )
  }
}

@Composable
fun AgentTopHeader(
  status: AgentStatus,
  currentAction: String?,
  providerType: ProviderType,
  canCancel: Boolean,
  onStop: () -> Unit,
  onOpenSettings: () -> Unit,
  onResetWorkspace: () -> Unit
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
              )
            }
          }

          Spacer(modifier = Modifier.width(8.dp))

          Column {
            Text(
              text = "Agent Kernel",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = if (providerType == ProviderType.SANDBOX_ENGINE) "Autonomous Sandbox Engine" else "Gemini Live API",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary
            )
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          // Status pill
          StatusPill(status = status)

          Spacer(modifier = Modifier.width(4.dp))

          if (canCancel) {
            IconButton(onClick = onStop) {
              Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
            }
          }

          IconButton(onClick = onResetWorkspace) {
            Icon(Icons.Default.Refresh, contentDescription = "Reset Workspace", tint = MaterialTheme.colorScheme.onSurfaceVariant)
          }

          IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }

      // Live "WHAT IS THE AGENT DOING RIGHT NOW?" ticker
      if (!currentAction.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
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
              maxLines = 1
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
    modifier = Modifier.padding(horizontal = 4.dp)
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = textColor,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
  }
}

@Composable
fun EmptyExecutionState(onQuickRun: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.primaryContainer,
      modifier = Modifier.size(64.dp)
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Default.AutoAwesome,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(36.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = "Autonomous Agent Kernel",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = "GOAL → PLAN → THINK → TOOL CALL → EXECUTE → OBSERVE → THINK AGAIN → VERIFY → COMPLETE",
      style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.SemiBold
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = "The agent dispatches real tools inside the sandboxed workspace. It never pretends an action occurred until authoritative evidence confirms it.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(
      onClick = onQuickRun,
      shape = RoundedCornerShape(8.dp),
      modifier = Modifier.testTag("quick_start_button")
    ) {
      Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text("Run Sample: data.csv → report.md")
    }
  }
}

@Composable
fun SettingsProviderDialog(
  currentProvider: ProviderType,
  geminiKey: String,
  onProviderChange: (ProviderType) -> Unit,
  onKeyChange: (String) -> Unit,
  onDismiss: () -> Unit
) {
  var keyInput by remember { mutableStateOf(geminiKey) }
  var selectedType by remember { mutableStateOf(currentProvider) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text("Agent Engine & Provider Settings", style = MaterialTheme.typography.titleMedium)
    },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        Text("Select Execution Mode:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        // Option 1: Sandbox Engine
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth()
        ) {
          RadioButton(
            selected = selectedType == ProviderType.SANDBOX_ENGINE,
            onClick = { selectedType = ProviderType.SANDBOX_ENGINE }
          )
          Column(modifier = Modifier.padding(start = 4.dp)) {
            Text("Autonomous Sandbox Engine", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("Offline deterministic engine with full tool dispatch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Option 2: Gemini Live API
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth()
        ) {
          RadioButton(
            selected = selectedType == ProviderType.GEMINI_LIVE_API,
            onClick = { selectedType = ProviderType.GEMINI_LIVE_API }
          )
          Column(modifier = Modifier.padding(start = 4.dp)) {
            Text("Google Gemini Live API", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("gemini-2.5-flash with function calling", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }

        AnimatedVisibility(visible = selectedType == ProviderType.GEMINI_LIVE_API) {
          Column(modifier = Modifier.padding(top = 12.dp)) {
            OutlinedTextField(
              value = keyInput,
              onValueChange = { keyInput = it },
              label = { Text("Gemini API Key") },
              placeholder = { Text("AIza...") },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true
            )
            Text(
              text = "Keys can also be configured via .env / BuildConfig.GEMINI_API_KEY",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.outline,
              modifier = Modifier.padding(top = 4.dp)
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onProviderChange(selectedType)
          onKeyChange(keyInput)
          onDismiss()
        }
      ) {
        Text("Apply")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}
