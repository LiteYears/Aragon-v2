package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.agent.mcp.McpClient
import com.example.agent.mcp.McpLogLevel
import com.example.agent.mcp.McpLogMessage
import com.example.agent.mcp.McpPromptDefinition
import com.example.agent.mcp.McpResourceContent
import com.example.agent.mcp.McpResourceDefinition
import com.example.agent.mcp.McpServerConfig
import com.example.agent.mcp.McpServerStatus
import com.example.agent.mcp.McpToolCallResult
import com.example.agent.mcp.McpToolDefinition
import com.example.agent.mcp.McpTransportType
import com.example.ui.theme.AmoledActionBorder
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
import com.example.ui.theme.AmoledStatusWarning
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledSurfaceVariant
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modern, Non-Overlapping Model Context Protocol (MCP) Management Console.
 *
 * Designed with strict responsiveness:
 * - Scrollable tab bar with non-colliding badges.
 * - 3-tier spacious server cards preventing text and control collisions.
 * - Smooth interactive tool testing playground.
 * - Filterable real-time diagnostic log stream.
 */
@Composable
fun McpServersDialog(
  servers: List<McpServerConfig>,
  discoveredTools: List<McpToolDefinition>,
  discoveredResources: List<McpResourceDefinition>,
  discoveredPrompts: List<McpPromptDefinition> = emptyList(),
  logs: List<McpLogMessage>,
  isRefreshing: Boolean,
  onDismiss: () -> Unit,
  onRefreshAll: () -> Unit,
  onUpsertServer: (McpServerConfig) -> Unit,
  onRemoveServer: (String) -> Unit,
  onToggleServer: (String, Boolean) -> Unit,
  onPingServer: suspend (String) -> Result<Long>,
  onReconnectServer: (String) -> Unit,
  onClearLogs: () -> Unit,
  onRunTool: suspend (serverId: String, toolName: String, args: Map<String, Any?>) -> Result<McpToolCallResult>,
  onReadResource: (suspend (serverId: String, uri: String) -> Result<McpResourceContent>)? = null,
  onUsePrompt: ((String) -> Unit)? = null,
  getClientForServer: (String) -> McpClient?
) {
  var selectedTab by remember { mutableIntStateOf(0) }
  var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }
  var showAddDialog by remember { mutableStateOf(false) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 20.dp)
        .widthIn(max = 520.dp)
        .heightIn(min = 380.dp, max = 620.dp),
      shape = RoundedCornerShape(20.dp),
      color = Color(0xFF0A0A0F),
      shadowElevation = 24.dp,
      border = BorderStroke(1.dp, Color(0xFF222232))
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        val customServers = remember(servers) {
          servers.filter { !it.isNative && it.transport != McpTransportType.INTERNAL && !it.url.startsWith("internal://") }
        }

        // Dialog Header
        Surface(
          modifier = Modifier.fillMaxWidth(),
          color = Color(0xFF12121A),
          border = BorderStroke(1.dp, Color(0xFF1E1E2C))
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              modifier = Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Box(
                modifier = Modifier
                  .size(9.dp)
                  .clip(CircleShape)
                  .background(if (customServers.any { it.enabled }) Color(0xFF10B981) else Color(0xFF94A3B8))
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column {
                Text(
                  text = "MODEL CONTEXT PROTOCOL (MCP)",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  fontFamily = InterFontFamily,
                  fontSize = 12.5.sp,
                  letterSpacing = 0.5.sp,
                  color = Color.White,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = "${customServers.size} custom servers · ${discoveredTools.size} tools · ${discoveredResources.size} resources",
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = InterFontFamily,
                  color = Color(0xFF64748B),
                  fontSize = 10.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
              modifier = Modifier
                .weight(1f, fill = false)
                .horizontalScroll(rememberScrollState()),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              AragonHeaderButton(
                text = if (isRefreshing) "SYNCING..." else "SYNC",
                onClick = onRefreshAll,
                enabled = !isRefreshing,
                variant = AragonButtonVariant.MUTED,
                testTag = "mcp_refresh_all_btn"
              )

              AragonHeaderButton(
                text = "+ NEW",
                onClick = { showAddDialog = true },
                variant = AragonButtonVariant.PRIMARY,
                testTag = "mcp_add_server_header_btn"
              )

              AragonHeaderButton(
                text = "CLOSE",
                onClick = onDismiss,
                variant = AragonButtonVariant.MUTED,
                testTag = "mcp_close_dialog_btn"
              )
            }
          }
        }

        // Tabs: ScrollableTabRow prevents clipping and overlap on mobile screens!
        val tabList = listOf(
          Pair("CUSTOM SERVERS", customServers.size),
          Pair("TOOLS", discoveredTools.size),
          Pair("RESOURCES", discoveredResources.size),
          Pair("PROMPTS", discoveredPrompts.size),
          Pair("LOGS", logs.size)
        )

        ScrollableTabRow(
          selectedTabIndex = selectedTab,
          containerColor = Color(0xFF101014),
          contentColor = AmoledTextPrimary,
          edgePadding = 12.dp,
          divider = {},
          indicator = { tabPositions ->
            if (selectedTab < tabPositions.size) {
              TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                color = AmoledActionPrimary,
                height = 2.dp
              )
            }
          }
        ) {
          tabList.forEachIndexed { index, (label, count) ->
            val isSelected = selectedTab == index
            Tab(
              selected = isSelected,
              onClick = { selectedTab = index },
              modifier = Modifier.testTag("mcp_tab_${label.lowercase()}"),
              text = {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.Center,
                  modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                ) {
                  Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.5.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) AmoledTextPrimary else AmoledTextMuted
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = if (isSelected) AmoledActionPrimary else Color(0xFF1A1A20),
                    modifier = Modifier.height(15.dp)
                  ) {
                    Box(
                      contentAlignment = Alignment.Center,
                      modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                      Text(
                        text = "$count",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) AmoledActionPrimaryOn else AmoledIconGreyLight
                      )
                    }
                  }
                }
              }
            )
          }
        }

        // Tab Content Area
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(12.dp)
        ) {
          when (selectedTab) {
            0 -> ServersTabContent(
              servers = servers,
              onToggleServer = onToggleServer,
              onEditServer = { editingServer = it },
              onRemoveServer = onRemoveServer,
              onPingServer = onPingServer,
              onReconnectServer = onReconnectServer,
              getClientForServer = getClientForServer,
              onAddNew = { showAddDialog = true },
              onSelectTab = { selectedTab = it }
            )
            1 -> ToolsTabContent(
              tools = discoveredTools,
              onRunTool = onRunTool
            )
            2 -> ResourcesTabContent(
              resources = discoveredResources,
              onReadResource = onReadResource
            )
            3 -> PromptsTabContent(
              prompts = discoveredPrompts,
              onUsePrompt = onUsePrompt
            )
            4 -> LogsTabContent(
              logs = logs,
              onClearLogs = onClearLogs
            )
          }
        }
      }
    }
  }

  // Add / Edit Server Modal
  if (showAddDialog || editingServer != null) {
    val serverToEdit = editingServer
    McpServerEditDialog(
      initialConfig = serverToEdit,
      onDismiss = {
        showAddDialog = false
        editingServer = null
      },
      onSave = { savedConfig ->
        onUpsertServer(savedConfig)
        showAddDialog = false
        editingServer = null
      }
    )
  }
}

/**
 * Compact, High-Density Server Cards with filter chips and streamlined action buttons.
 */
@Composable
private fun ServersTabContent(
  servers: List<McpServerConfig>,
  onToggleServer: (String, Boolean) -> Unit,
  onEditServer: (McpServerConfig) -> Unit,
  onRemoveServer: (String) -> Unit,
  onPingServer: suspend (String) -> Result<Long>,
  onReconnectServer: (String) -> Unit,
  getClientForServer: (String) -> McpClient?,
  onAddNew: () -> Unit,
  onSelectTab: (Int) -> Unit = {}
) {
  val scope = rememberCoroutineScope()
  val pingResults = remember { mutableStateMapOf<String, String>() }
  val pingingMap = remember { mutableStateMapOf<String, Boolean>() }
  var filterMode by remember { mutableIntStateOf(0) } // 0: ALL CUSTOM, 1: ACTIVE

  val customServers = remember(servers) {
    servers.filter { !it.isNative && it.transport != McpTransportType.INTERNAL && !it.url.startsWith("internal://") }
  }

  val filteredServers = remember(customServers, filterMode) {
    when (filterMode) {
      1 -> customServers.filter { it.enabled }
      else -> customServers
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    // Native Background Engine Status Banner (All native internal MCPs activated)
    Surface(
      shape = RoundedCornerShape(10.dp),
      color = Color(0xFF0F172A),
      border = BorderStroke(1.dp, Color(0xFF1E293B)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 10.dp)
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(Color(0xFF10B981))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "12 NATIVE ENGINE MCP TOOLS ACTIVE",
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF38BDF8)
          )
          Text(
            text = "Playwright Browser, Wikipedia, ArXiv, Math, SQLite, Workspace Filesystem, Office & System Tools run natively.",
            fontFamily = InterFontFamily,
            fontSize = 8.5.sp,
            color = Color(0xFF94A3B8)
          )
        }
      }
    }

    if (customServers.isEmpty()) {
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth(),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.padding(20.dp)
        ) {
          Text(
            text = "No Custom MCP Servers Added",
            style = MaterialTheme.typography.titleSmall,
            fontFamily = InterFontFamily,
            color = AmoledTextPrimary,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "All native internal tools run automatically in the background. Connect your custom MCP endpoints (SSE, HTTP, or GitHub) below.",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = InterFontFamily,
            fontSize = 10.5.sp,
            color = AmoledTextMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
          Spacer(modifier = Modifier.height(16.dp))
          Button(
            onClick = onAddNew,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = AmoledActionPrimary,
              contentColor = AmoledActionPrimaryOn
            ),
            modifier = Modifier.testTag("add_custom_mcp_server_btn")
          ) {
            Text(
              text = "+ Add Custom MCP Server",
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 11.sp
            )
          }
        }
      }
      return
    }

    // Quick Filter Chips Row for Custom Servers
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      val filterOptions = listOf(
        "ALL CUSTOM (${customServers.size})",
        "ACTIVE (${customServers.count { it.enabled }})"
      )
      filterOptions.forEachIndexed { idx, label ->
        val isSelected = filterMode == idx
        Surface(
          shape = RoundedCornerShape(4.dp),
          color = if (isSelected) AmoledActionPrimary else Color(0xFF121216),
          border = BorderStroke(1.dp, if (isSelected) AmoledActionPrimary else AmoledBorderSubtle),
          modifier = Modifier
            .clickable { filterMode = idx }
            .testTag("mcp_filter_chip_$idx")
        ) {
          Text(
            text = label,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 8.5.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) AmoledActionPrimaryOn else AmoledTextSecondary,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
          )
        }
      }
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      itemsIndexed(filteredServers, key = { index, config -> "${config.id}_$index" }) { index, config ->
        val client = getClientForServer(config.id)
        val clientStatus = client?.status?.value ?: (if (config.enabled) McpServerStatus.DISCONNECTED else McpServerStatus.DISABLED)
        val toolCount = client?.tools?.value?.size ?: 0
        val resourceCount = client?.resources?.value?.size ?: 0
        val lastErr = client?.lastError?.value
        val pingText = pingResults[config.id]
        val isPinging = pingingMap[config.id] == true

        Surface(
          shape = RoundedCornerShape(8.dp),
          color = Color(0xFF0C0C10),
          border = BorderStroke(1.dp, AmoledBorderSubtle),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 10.dp, vertical = 7.dp)
          ) {
            // --- TIER 1: Status Dot + Name + Transport + Tools Badge + Toggle Switch ---
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
              ) {
                val statusColor = when (clientStatus) {
                  McpServerStatus.CONNECTED -> AmoledStatusSuccess
                  McpServerStatus.CONNECTING, McpServerStatus.INITIALIZING -> AmoledStatusWarning
                  McpServerStatus.ERROR -> AmoledStatusError
                  McpServerStatus.DISABLED -> AmoledIconGreyDark
                  McpServerStatus.DISCONNECTED -> AmoledTextMuted
                }

                Box(
                  modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                  text = config.name,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  fontFamily = InterFontFamily,
                  color = AmoledTextPrimary,
                  fontSize = 12.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.width(5.dp))

                // Transport Badge
                Surface(
                  shape = RoundedCornerShape(3.dp),
                  color = Color(0xFF14141A),
                  border = BorderStroke(1.dp, AmoledBorderSubtle)
                ) {
                  Text(
                    text = config.transport.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AmoledActionPrimary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                  )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Tool count chip
                Surface(
                  shape = RoundedCornerShape(3.dp),
                  color = Color(0xFF141419),
                  border = BorderStroke(1.dp, AmoledBorderSubtle),
                  modifier = Modifier
                    .clickable { onSelectTab(1) }
                    .testTag("mcp_card_tools_${config.id}")
                ) {
                  Text(
                    text = "$toolCount tools",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (toolCount > 0) AmoledActionPrimary else AmoledTextMuted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                  )
                }
              }

              // Right: Latency indicator (if any) + Compact Switch
              Row(verticalAlignment = Alignment.CenterVertically) {
                if (!pingText.isNullOrBlank()) {
                  val isPingFail = pingText == "FAIL"
                  Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = if (isPingFail) Color(0xFF200F0F) else Color(0xFF0F1E14),
                    border = BorderStroke(1.dp, if (isPingFail) AmoledStatusError.copy(alpha = 0.5f) else AmoledStatusSuccess.copy(alpha = 0.5f)),
                    modifier = Modifier.padding(end = 6.dp)
                  ) {
                    Text(
                      text = pingText,
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 7.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = if (isPingFail) AmoledStatusError else AmoledStatusSuccess,
                      modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                  }
                }

                Switch(
                  checked = config.enabled,
                  onCheckedChange = { onToggleServer(config.id, it) },
                  colors = SwitchDefaults.colors(
                    checkedThumbColor = AmoledActionPrimary,
                    checkedTrackColor = AmoledSurfaceElevated,
                    uncheckedThumbColor = AmoledIconGreyDark,
                    uncheckedTrackColor = Color(0xFF18181E)
                  ),
                  modifier = Modifier
                    .height(24.dp)
                    .testTag("mcp_toggle_switch_${config.id}")
                )
              }
            }

            Spacer(modifier = Modifier.height(3.dp))

            // --- TIER 2: Monospace URI (Left) + Compact Micro Action Buttons (Right) ---
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = if (config.transport == McpTransportType.STDIO) {
                  "cmd: ${config.command} ${config.args.joinToString(" ")}"
                } else {
                  config.url.ifBlank { "internal://${config.id}" }
                },
                fontFamily = JetBrainsMonoFontFamily,
                color = AmoledTextMuted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                  .weight(1f)
                  .padding(end = 6.dp)
              )

              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
              ) {
                if (config.enabled) {
                  // Ping button
                  OutlinedButton(
                    onClick = {
                      scope.launch {
                        pingingMap[config.id] = true
                        val res = onPingServer(config.id)
                        pingingMap[config.id] = false
                        pingResults[config.id] = if (res.isSuccess) "${res.getOrThrow()}ms" else "FAIL"
                      }
                    },
                    enabled = !isPinging,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, AmoledBorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledTextPrimary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                    modifier = Modifier
                      .height(26.dp)
                      .testTag("mcp_ping_btn_${config.id}")
                  ) {
                    if (isPinging) {
                      CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.2.dp, color = AmoledActionPrimary)
                    } else {
                      Text("PING", fontFamily = JetBrainsMonoFontFamily, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                  }

                  // Sync / Reconnect button
                  OutlinedButton(
                    onClick = { onReconnectServer(config.id) },
                    shape = RoundedCornerShape(5.dp),
                    border = BorderStroke(1.dp, AmoledBorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledTextSecondary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                    modifier = Modifier
                      .height(26.dp)
                      .testTag("mcp_reconnect_btn_${config.id}")
                  ) {
                    Text("SYNC", fontFamily = JetBrainsMonoFontFamily, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                  }
                }

                // Edit button
                OutlinedButton(
                  onClick = { onEditServer(config) },
                  shape = RoundedCornerShape(5.dp),
                  border = BorderStroke(1.dp, AmoledBorderSubtle),
                  colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledTextPrimary),
                  contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                  modifier = Modifier
                    .height(26.dp)
                    .testTag("mcp_edit_btn_${config.id}")
                ) {
                  Text("EDIT", fontFamily = JetBrainsMonoFontFamily, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }

                // Delete button
                OutlinedButton(
                  onClick = { onRemoveServer(config.id) },
                  shape = RoundedCornerShape(5.dp),
                  border = BorderStroke(1.dp, AmoledStatusError.copy(alpha = 0.4f)),
                  colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledStatusError),
                  contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                  modifier = Modifier
                    .height(26.dp)
                    .testTag("mcp_delete_btn_${config.id}")
                ) {
                  Text("DEL", fontFamily = JetBrainsMonoFontFamily, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
              }
            }

            if (!lastErr.isNullOrBlank() && clientStatus == McpServerStatus.ERROR) {
              Spacer(modifier = Modifier.height(3.dp))
              Text(
                text = "ERR: $lastErr",
                fontFamily = JetBrainsMonoFontFamily,
                color = AmoledStatusError,
                fontSize = 8.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Tools Tab with clean search filter and expandable playground.
 */
@Composable
private fun ToolsTabContent(
  tools: List<McpToolDefinition>,
  onRunTool: suspend (serverId: String, toolName: String, args: Map<String, Any?>) -> Result<McpToolCallResult>
) {
  val scope = rememberCoroutineScope()
  var searchQuery by remember { mutableStateOf("") }
  val clipboard = LocalClipboardManager.current

  val filteredTools = remember(tools, searchQuery) {
    if (searchQuery.isBlank()) tools else tools.filter {
      it.namespacedName.contains(searchQuery, ignoreCase = true) ||
        it.description?.contains(searchQuery, ignoreCase = true) == true ||
        it.serverId.contains(searchQuery, ignoreCase = true)
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    // Search Box
    OutlinedTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      placeholder = { Text("Filter tools by name, description, server...", fontSize = 11.sp, color = AmoledTextMuted, fontFamily = InterFontFamily) },
      trailingIcon = {
        if (searchQuery.isNotEmpty()) {
          Text(
            text = "CLEAR",
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = AmoledActionPrimary,
            modifier = Modifier
              .clickable { searchQuery = "" }
              .padding(horizontal = 10.dp, vertical = 6.dp)
          )
        }
      },
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 8.dp),
      singleLine = true,
      shape = RoundedCornerShape(10.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = AmoledTextPrimary,
        unfocusedTextColor = AmoledTextPrimary,
        focusedBorderColor = AmoledActionPrimary,
        unfocusedBorderColor = AmoledBorderSubtle
      )
    )

    if (filteredTools.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          text = if (tools.isEmpty()) {
            "No MCP tools discovered yet.\nEnable and connect an MCP server to discover tools."
          } else {
            "No tools match '$searchQuery'"
          },
          style = MaterialTheme.typography.bodyMedium,
          fontFamily = InterFontFamily,
          color = AmoledTextMuted,
          textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
      }
      return
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      itemsIndexed(filteredTools, key = { index, tool -> "${tool.serverId}_${tool.namespacedName}_$index" }) { _, tool ->
        var expanded by remember { mutableStateOf(false) }
        var showPlayground by remember { mutableStateOf(false) }
        var inputArgsJson by remember(tool.namespacedName) {
          val sampleObj = JSONObject()
          val props = tool.inputSchema.optJSONObject("properties")
          if (props != null) {
            val keys = props.keys()
            while (keys.hasNext()) {
              val k = keys.next()
              val pObj = props.optJSONObject(k)
              val pType = pObj?.optString("type", "string") ?: "string"
              when (pType) {
                "integer", "number" -> sampleObj.put(k, 1)
                "boolean" -> sampleObj.put(k, true)
                "array" -> sampleObj.put(k, JSONArray())
                else -> sampleObj.put(k, "")
              }
            }
          }
          mutableStateOf(if (sampleObj.length() > 0) sampleObj.toString(2) else "{}")
        }
        var toolRunResult by remember { mutableStateOf<String?>(null) }
        var isToolRunning by remember { mutableStateOf(false) }

        Surface(
          shape = RoundedCornerShape(10.dp),
          color = Color(0xFF0F0F13),
          border = BorderStroke(1.dp, AmoledBorderSubtle),
          modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = tool.namespacedName,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 12.sp,
                  color = AmoledTextPrimary
                )
                Text(
                  text = tool.description ?: "No description provided",
                  style = MaterialTheme.typography.bodySmall,
                  fontFamily = InterFontFamily,
                  color = AmoledTextMuted,
                  fontSize = 10.5.sp,
                  maxLines = if (expanded) 10 else 2,
                  overflow = TextOverflow.Ellipsis
                )
              }
              Spacer(modifier = Modifier.width(6.dp))
              Surface(
                shape = RoundedCornerShape(4.dp),
                color = AmoledSurfaceElevated,
                border = BorderStroke(1.dp, AmoledBorderSubtle)
              ) {
                Text(
                  text = tool.serverId,
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 8.sp,
                  color = AmoledActionPrimary,
                  modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
              }
            }

            AnimatedVisibility(visible = expanded) {
              Column(modifier = Modifier.padding(top = 10.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = "INPUT SCHEMA (JSON):",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 9.sp,
                    color = AmoledTextSecondary
                  )

                  OutlinedButton(
                    onClick = { showPlayground = !showPlayground },
                    shape = RoundedCornerShape(5.dp),
                    border = BorderStroke(1.dp, AmoledActionBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                      containerColor = if (showPlayground) AmoledActionPrimary else AmoledSurfaceElevated,
                      contentColor = if (showPlayground) AmoledActionPrimaryOn else AmoledActionPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp)
                  ) {
                    Text(
                      text = if (showPlayground) "HIDE PLAYGROUND" else "TEST TOOL ▶",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 8.sp,
                      fontWeight = FontWeight.Bold
                    )
                  }
                }

                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = Color(0xFF08080A),
                  border = BorderStroke(1.dp, AmoledBorderSubtle),
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                ) {
                  Text(
                    text = tool.inputSchema.toString(2),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 9.sp,
                    color = AmoledIconGreyLight,
                    modifier = Modifier.padding(8.dp)
                  )
                }

                // Interactive Playground
                AnimatedVisibility(visible = showPlayground) {
                  Column(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(top = 8.dp)
                      .background(Color(0xFF0C0C10), RoundedCornerShape(8.dp))
                      .border(1.dp, AmoledBorderSubtle, RoundedCornerShape(8.dp))
                      .padding(10.dp)
                  ) {
                    Text(
                      text = "INTERACTIVE TOOL PLAYGROUND",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 9.sp,
                      fontWeight = FontWeight.Bold,
                      color = AmoledActionPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                      value = inputArgsJson,
                      onValueChange = { inputArgsJson = it },
                      label = { Text("Arguments (JSON)", fontSize = 10.sp) },
                      modifier = Modifier.fillMaxWidth(),
                      minLines = 3,
                      textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp),
                      colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AmoledTextPrimary,
                        unfocusedTextColor = AmoledTextPrimary,
                        focusedBorderColor = AmoledActionPrimary,
                        unfocusedBorderColor = AmoledBorderSubtle
                      )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.End
                    ) {
                      Button(
                        onClick = {
                          scope.launch {
                            isToolRunning = true
                            toolRunResult = null
                            val argsMap = mutableMapOf<String, Any?>()
                            try {
                              val json = JSONObject(inputArgsJson.trim())
                              val keys = json.keys()
                              while (keys.hasNext()) {
                                val k = keys.next()
                                argsMap[k] = json.get(k)
                              }
                            } catch (_: Exception) {}

                            val res = onRunTool(tool.serverId, tool.name, argsMap)
                            isToolRunning = false
                            toolRunResult = if (res.isSuccess) {
                              val r = res.getOrThrow()
                              if (r.isError) "ERROR:\n${r.rawText}" else r.rawText
                            } else {
                              "FAILED: ${res.exceptionOrNull()?.message}"
                            }
                          }
                        },
                        enabled = !isToolRunning,
                        colors = ButtonDefaults.buttonColors(
                          containerColor = AmoledActionPrimary,
                          contentColor = AmoledActionPrimaryOn
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp)
                      ) {
                        if (isToolRunning) {
                          CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = AmoledActionPrimaryOn)
                          Spacer(modifier = Modifier.width(6.dp))
                          Text("RUNNING...", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp)
                        } else {
                          Text("RUN TOOL ⏎", fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                      }
                    }

                    if (!toolRunResult.isNullOrBlank()) {
                      Spacer(modifier = Modifier.height(8.dp))
                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Text(
                          text = "OUTPUT PAYLOAD:",
                          fontFamily = JetBrainsMonoFontFamily,
                          fontSize = 8.sp,
                          color = AmoledTextMuted
                        )
                        OutlinedButton(
                          onClick = { clipboard.setText(AnnotatedString(toolRunResult!!)) },
                          shape = RoundedCornerShape(4.dp),
                          border = BorderStroke(1.dp, AmoledBorderSubtle),
                          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
                          modifier = Modifier.height(22.dp)
                        ) {
                          Text("COPY", fontSize = 8.sp, fontFamily = JetBrainsMonoFontFamily, color = AmoledTextSecondary)
                        }
                      }
                      Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF08080B),
                        border = BorderStroke(1.dp, AmoledBorderSubtle),
                        modifier = Modifier
                          .fillMaxWidth()
                          .padding(top = 3.dp)
                      ) {
                        Text(
                          text = toolRunResult!!,
                          fontFamily = JetBrainsMonoFontFamily,
                          fontSize = 9.sp,
                          color = if (toolRunResult!!.startsWith("ERROR") || toolRunResult!!.startsWith("FAILED")) AmoledStatusError else AmoledStatusSuccess,
                          modifier = Modifier.padding(8.dp)
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
  }
}

/**
 * Resources Tab with neat cards.
 */
@Composable
private fun ResourcesTabContent(
  resources: List<McpResourceDefinition>,
  onReadResource: (suspend (serverId: String, uri: String) -> Result<McpResourceContent>)? = null
) {
  if (resources.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        text = "No MCP resources discovered.\nServers exposing resources will list them here.",
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = InterFontFamily,
        color = AmoledTextMuted,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
      )
    }
    return
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    itemsIndexed(resources, key = { index, res -> "${res.serverId}_${res.uri}_$index" }) { _, res ->
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F0F13),
        border = BorderStroke(1.dp, AmoledBorderSubtle),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(
              text = res.name,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              fontFamily = InterFontFamily,
              color = AmoledTextPrimary
            )
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = AmoledSurfaceElevated,
              border = BorderStroke(1.dp, AmoledBorderSubtle)
            ) {
              Text(
                text = res.serverId,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 8.sp,
                color = AmoledActionPrimary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
              )
            }
          }

          Text(
            text = res.uri,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = AmoledIconGreyLight,
            fontSize = 9.5.sp
          )

          if (!res.description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = res.description,
              style = MaterialTheme.typography.bodySmall,
              fontFamily = InterFontFamily,
              color = AmoledTextMuted,
              fontSize = 11.sp
            )
          }

          if (!res.mimeType.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "MIME: ${res.mimeType}",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 8.sp,
              color = AmoledTextSecondary
            )
          }
        }
      }
    }
  }
}

/**
 * Prompts Tab with formatted copy and use actions.
 */
@Composable
private fun PromptsTabContent(
  prompts: List<McpPromptDefinition>,
  onUsePrompt: ((String) -> Unit)?
) {
  if (prompts.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        text = "No MCP prompt templates discovered.\nServers declaring prompts/list will appear here.",
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = InterFontFamily,
        color = AmoledTextMuted,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
      )
    }
    return
  }

  val clipboard = LocalClipboardManager.current

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    itemsIndexed(prompts, key = { index, p -> "${p.serverId}__${p.name}_$index" }) { _, p ->
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F0F13),
        border = BorderStroke(1.dp, AmoledBorderSubtle),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = p.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFontFamily,
                color = AmoledTextPrimary
              )
              if (!p.description.isNullOrBlank()) {
                Text(
                  text = p.description,
                  style = MaterialTheme.typography.bodySmall,
                  fontFamily = InterFontFamily,
                  color = AmoledTextMuted,
                  fontSize = 11.sp
                )
              }
            }

            Surface(
              shape = RoundedCornerShape(4.dp),
              color = AmoledSurfaceElevated,
              border = BorderStroke(1.dp, AmoledBorderSubtle)
            ) {
              Text(
                text = p.serverId,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 8.sp,
                color = AmoledActionPrimary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
              )
            }
          }

          if (p.arguments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "ARGUMENTS:",
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 8.sp,
              color = AmoledTextSecondary
            )
            p.arguments.forEach { arg ->
              Text(
                text = "• ${arg.name}${if (arg.required) " (required)" else ""}: ${arg.description ?: "no description"}",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 9.sp,
                color = AmoledIconGreyLight
              )
            }
          }

          Spacer(modifier = Modifier.height(8.dp))
          Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
          ) {
            OutlinedButton(
              onClick = {
                val promptText = "Execute prompt '${p.name}' from MCP server '${p.serverId}'."
                clipboard.setText(AnnotatedString(promptText))
              },
              shape = RoundedCornerShape(5.dp),
              border = BorderStroke(1.dp, AmoledBorderSubtle),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledTextSecondary),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
              modifier = Modifier.height(26.dp)
            ) {
              Text("COPY PROMPT", fontFamily = JetBrainsMonoFontFamily, fontSize = 8.sp)
            }

            if (onUsePrompt != null) {
              Spacer(modifier = Modifier.width(6.dp))
              Button(
                onClick = {
                  val promptText = "Execute prompt '${p.name}' from MCP server '${p.serverId}'."
                  onUsePrompt(promptText)
                },
                shape = RoundedCornerShape(5.dp),
                colors = ButtonDefaults.buttonColors(
                  containerColor = AmoledActionPrimary,
                  contentColor = AmoledActionPrimaryOn
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(26.dp)
              ) {
                Text("USE IN PROMPT", fontFamily = JetBrainsMonoFontFamily, fontSize = 8.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Filterable Logs Tab.
 */
@Composable
private fun LogsTabContent(
  logs: List<McpLogMessage>,
  onClearLogs: () -> Unit
) {
  var selectedLevel by remember { mutableStateOf<McpLogLevel?>(null) }
  val clipboard = LocalClipboardManager.current

  val filteredLogs = remember(logs, selectedLevel) {
    if (selectedLevel == null) logs else logs.filter { it.level == selectedLevel }
  }

  val timeFormatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

  Column(modifier = Modifier.fillMaxSize()) {
    // Filter & Action Shelf
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      val filterScrollState = rememberScrollState()
      Row(
        modifier = Modifier
          .weight(1f, fill = false)
          .horizontalScroll(filterScrollState),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        val levels = listOf(null to "ALL") + McpLogLevel.entries.map { it to it.name }
        levels.forEach { (lvl, label) ->
          val isSelected = selectedLevel == lvl
          OutlinedButton(
            onClick = { selectedLevel = lvl },
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, if (isSelected) AmoledActionPrimary else AmoledBorderSubtle),
            colors = ButtonDefaults.outlinedButtonColors(
              containerColor = if (isSelected) AmoledActionPrimary.copy(alpha = 0.15f) else Color.Transparent,
              contentColor = if (isSelected) AmoledActionPrimary else AmoledTextMuted
            ),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
            modifier = Modifier.height(22.dp)
          ) {
            Text(label, fontFamily = JetBrainsMonoFontFamily, fontSize = 8.sp, fontWeight = FontWeight.Bold)
          }
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
          onClick = {
            val text = filteredLogs.joinToString("\n") {
              "${timeFormatter.format(Date(it.timestamp))} [${it.serverId}] [${it.level.name}] ${it.data}"
            }
            clipboard.setText(AnnotatedString(text))
          },
          shape = RoundedCornerShape(4.dp),
          border = BorderStroke(1.dp, AmoledBorderSubtle),
          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
          modifier = Modifier.height(22.dp)
        ) {
          Text("COPY", fontSize = 8.sp, fontFamily = JetBrainsMonoFontFamily, color = AmoledTextSecondary)
        }

        OutlinedButton(
          onClick = onClearLogs,
          shape = RoundedCornerShape(4.dp),
          border = BorderStroke(1.dp, AmoledStatusError.copy(alpha = 0.35f)),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledStatusError),
          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
          modifier = Modifier.height(22.dp)
        ) {
          Text("CLEAR", fontSize = 8.sp, fontFamily = JetBrainsMonoFontFamily)
        }
      }
    }

    if (filteredLogs.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No log messages recorded", fontFamily = JetBrainsMonoFontFamily, color = AmoledTextMuted, fontSize = 11.sp)
      }
      return
    }

    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFF08080B), RoundedCornerShape(8.dp))
        .border(1.dp, AmoledBorderSubtle, RoundedCornerShape(8.dp))
        .padding(6.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      items(filteredLogs) { log ->
        val levelColor = when (log.level) {
          McpLogLevel.DEBUG -> AmoledIconGrey
          McpLogLevel.INFO -> AmoledActionPrimary
          McpLogLevel.NOTICE -> AmoledTextPrimary
          McpLogLevel.WARNING -> AmoledStatusWarning
          McpLogLevel.ERROR, McpLogLevel.CRITICAL, McpLogLevel.ALERT, McpLogLevel.EMERGENCY -> AmoledStatusError
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.Top
        ) {
          Text(
            text = timeFormatter.format(Date(log.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 8.5.sp,
            color = AmoledTextMuted
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "[${log.serverId}]",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            color = AmoledActionPrimary
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "[${log.level.name}]",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            color = levelColor
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = log.data,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledTextPrimary,
            modifier = Modifier.weight(1f)
          )
        }
      }
    }
  }
}

/**
 * Add / Edit MCP Server Dialog with clean non-cramped transport selection and form inputs.
 */
@Composable
private fun McpServerEditDialog(
  initialConfig: McpServerConfig?,
  onDismiss: () -> Unit,
  onSave: (McpServerConfig) -> Unit
) {
  var id by remember { mutableStateOf(initialConfig?.id ?: "") }
  var name by remember { mutableStateOf(initialConfig?.name ?: "") }
  var description by remember { mutableStateOf(initialConfig?.description ?: "") }
  var transport by remember { mutableStateOf(initialConfig?.transport?.takeIf { it != McpTransportType.INTERNAL } ?: McpTransportType.HTTP) }
  var url by remember { mutableStateOf(initialConfig?.url?.takeIf { !it.startsWith("internal://") } ?: "https://") }
  var command by remember { mutableStateOf(initialConfig?.command ?: "") }
  var args by remember { mutableStateOf(initialConfig?.args?.joinToString(" ") ?: "") }
  var timeoutSeconds by remember { mutableStateOf((initialConfig?.timeoutSeconds ?: 30L).toString()) }
  var autoReconnect by remember { mutableStateOf(initialConfig?.autoReconnect ?: true) }

  var headersJson by remember {
    val h = initialConfig?.headers ?: emptyMap()
    val str = if (h.isEmpty()) "" else JSONObject(h).toString(2)
    mutableStateOf(str)
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = Color.White,
      border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
      shadowElevation = 16.dp,
      modifier = Modifier
        .fillMaxWidth(0.95f)
        .fillMaxHeight(0.86f)
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(16.dp)
      ) {
        Text(
          text = if (initialConfig == null) "ADD MCP SERVER" else "EDIT MCP SERVER",
          style = MaterialTheme.typography.titleMedium,
          fontFamily = InterFontFamily,
          fontWeight = FontWeight.Bold,
          color = Color(0xFF0F172A),
          fontSize = 13.5.sp
        )

        // Template Quick Fill Row (New Server Only)
        if (initialConfig == null) {
          Spacer(modifier = Modifier.height(6.dp))
          val templatesScrollState = rememberScrollState()
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(templatesScrollState),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
          ) {
            val quickTemplates = listOf(
              Triple("github", "GitHub MCP API", "https://api.github.com/mcp"),
              Triple("weather", "Weather Service SSE", "https://weather.mcp.example.com/sse"),
              Triple("postgres", "Remote PostgreSQL MCP", "http://localhost:8080/mcp"),
              Triple("linear", "Linear Project MCP", "https://linear.mcp.example.com/sse"),
              Triple("custom_http", "Custom HTTP Endpoint", "https://api.custom-agent.dev/mcp")
            )
            quickTemplates.forEach { (tId, tName, tUrl) ->
              Surface(
                shape = RoundedCornerShape(4.dp),
                color = AmoledSurfaceElevated,
                border = BorderStroke(1.dp, AmoledActionBorder),
                modifier = Modifier.clickable {
                  id = tId
                  name = tName
                  url = tUrl
                  transport = if (tUrl.contains("/sse")) McpTransportType.SSE else McpTransportType.HTTP
                }
              ) {
                Text(
                  text = "+ $tName",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 8.sp,
                  color = AmoledActionPrimary,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          item {
            OutlinedTextField(
              value = id,
              onValueChange = { id = it },
              label = { Text("Server ID (unique key, e.g. 'sqlite')") },
              enabled = initialConfig == null,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(8.dp),
              colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AmoledTextPrimary,
                unfocusedTextColor = AmoledTextPrimary,
                focusedBorderColor = AmoledActionPrimary,
                unfocusedBorderColor = AmoledBorder
              )
            )
          }

          item {
            OutlinedTextField(
              value = name,
              onValueChange = { name = it },
              label = { Text("Display Name") },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(8.dp),
              colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AmoledTextPrimary,
                unfocusedTextColor = AmoledTextPrimary,
                focusedBorderColor = AmoledActionPrimary,
                unfocusedBorderColor = AmoledBorder
              )
            )
          }

          item {
            Text(
              text = "TRANSPORT TYPE",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              color = AmoledTextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            val transportScrollState = rememberScrollState()
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(transportScrollState),
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              listOf(McpTransportType.HTTP, McpTransportType.SSE, McpTransportType.STDIO).forEach { t ->
                val selected = transport == t
                OutlinedButton(
                  onClick = { transport = t },
                  colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected) AmoledActionPrimary else AmoledSurface,
                    contentColor = if (selected) AmoledActionPrimaryOn else AmoledTextPrimary
                  ),
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                  border = BorderStroke(1.dp, if (selected) AmoledActionPrimary else AmoledBorder),
                  shape = RoundedCornerShape(6.dp),
                  modifier = Modifier.height(30.dp)
                ) {
                  Text(t.name, fontSize = 9.sp, fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Bold)
                }
              }
            }
          }

          if (transport == McpTransportType.HTTP || transport == McpTransportType.SSE) {
            item {
              OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Endpoint URL (${if (transport == McpTransportType.SSE) "SSE stream URL" else "POST JSON-RPC URL"})") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedTextColor = AmoledTextPrimary,
                  unfocusedTextColor = AmoledTextPrimary,
                  focusedBorderColor = AmoledActionPrimary,
                  unfocusedBorderColor = AmoledBorder
                )
              )
            }

            item {
              OutlinedTextField(
                value = headersJson,
                onValueChange = { headersJson = it },
                label = { Text("Custom Headers (JSON, e.g. {\"Authorization\": \"Bearer ...\"})") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedTextColor = AmoledTextPrimary,
                  unfocusedTextColor = AmoledTextPrimary,
                  focusedBorderColor = AmoledActionPrimary,
                  unfocusedBorderColor = AmoledBorder
                )
              )
            }
          } else {
            // STDIO
            item {
              OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Command (e.g. 'npx', 'python3', 'docker')") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedTextColor = AmoledTextPrimary,
                  unfocusedTextColor = AmoledTextPrimary,
                  focusedBorderColor = AmoledActionPrimary,
                  unfocusedBorderColor = AmoledBorder
                )
              )
            }

            item {
              OutlinedTextField(
                value = args,
                onValueChange = { args = it },
                label = { Text("Arguments (space separated)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedTextColor = AmoledTextPrimary,
                  unfocusedTextColor = AmoledTextPrimary,
                  focusedBorderColor = AmoledActionPrimary,
                  unfocusedBorderColor = AmoledBorder
                )
              )
            }
          }

          item {
            OutlinedTextField(
              value = description,
              onValueChange = { description = it },
              label = { Text("Description (optional)") },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(8.dp),
              colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AmoledTextPrimary,
                unfocusedTextColor = AmoledTextPrimary,
                focusedBorderColor = AmoledActionPrimary,
                unfocusedBorderColor = AmoledBorder
              )
            )
          }

          item {
            OutlinedTextField(
              value = timeoutSeconds,
              onValueChange = { timeoutSeconds = it },
              label = { Text("Timeout (seconds)") },
              modifier = Modifier.fillMaxWidth(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              shape = RoundedCornerShape(8.dp),
              colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AmoledTextPrimary,
                unfocusedTextColor = AmoledTextPrimary,
                focusedBorderColor = AmoledActionPrimary,
                unfocusedBorderColor = AmoledBorder
              )
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically
        ) {
          AragonActionButton(
            text = "CANCEL",
            onClick = onDismiss,
            variant = AragonButtonVariant.MUTED
          )

          Spacer(modifier = Modifier.width(8.dp))

          AragonActionButton(
            text = "SAVE SERVER",
            onClick = {
              val parsedHeaders = mutableMapOf<String, String>()
              if (headersJson.isNotBlank()) {
                try {
                  val obj = JSONObject(headersJson.trim())
                  val keys = obj.keys()
                  while (keys.hasNext()) {
                    val k = keys.next()
                    parsedHeaders[k] = obj.optString(k, "")
                  }
                } catch (_: Exception) {}
              }

              val config = McpServerConfig(
                id = id.trim().ifBlank { "server_${System.currentTimeMillis()}" },
                name = name.trim().ifBlank { "MCP Server" },
                description = description.trim(),
                transport = transport,
                url = url.trim(),
                command = command.trim(),
                args = if (args.isBlank()) emptyList() else args.trim().split("\\s+".toRegex()),
                headers = parsedHeaders,
                timeoutSeconds = timeoutSeconds.toLongOrNull() ?: 30L,
                autoReconnect = autoReconnect,
                enabled = true
              )
              onSave(config)
            },
            enabled = name.isNotBlank() && (id.isNotBlank() || initialConfig != null),
            variant = AragonButtonVariant.PRIMARY
          )
        }
      }
    }
  }
}
