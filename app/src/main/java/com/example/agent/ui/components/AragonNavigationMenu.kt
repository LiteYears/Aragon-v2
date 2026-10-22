package com.example.agent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.agent.core.AgentStatus
import com.example.agent.core.ApprovalPolicy
import com.example.agent.ui.UiTab
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily

private val MenuObsidianBg = Color(0xFF050508)
private val MenuCardSurface = Color(0xFF07070B)
private val MenuCardElevated = Color(0xFF0C0E14)
private val MenuCardBorder = Color(0xFF191C26)
private val MenuCyan = Color(0xFF38BDF8)
private val MenuEmerald = Color(0xFF10B981)
private val MenuAmber = Color(0xFFF59E0B)
private val MenuPurple = Color(0xFFA855F7)
private val MenuIndigo = Color(0xFF6366F1)
private val MenuDanger = Color(0xFFEF4444)

/**
 * Pure AMOLED True Black Command Drawer matching the About Aragon luxury technical aesthetic.
 */
@Composable
fun AragonNavigationMenu(
  currentTab: UiTab,
  agentStatus: AgentStatus,
  currentModelName: String,
  approvalPolicy: ApprovalPolicy,
  feedCount: Int,
  historyCount: Int,
  artifactCount: Int,
  mcpServerCount: Int,
  documentCount: Int,
  onSelectTab: (UiTab) -> Unit,
  onOpenModelSelect: () -> Unit,
  onOpenAutonomy: () -> Unit,
  onOpenMcp: () -> Unit,
  onOpenDocuments: () -> Unit,
  onNewTask: () -> Unit,
  onResetWorkspace: () -> Unit,
  onReplaySplash: (() -> Unit)? = null,
  onDismiss: () -> Unit
) {
  val isRunning = agentStatus in listOf(
    AgentStatus.THINKING,
    AgentStatus.PLANNING,
    AgentStatus.REPLANNING,
    AgentStatus.EXECUTING_TOOL,
    AgentStatus.OBSERVING,
    AgentStatus.VERIFYING
  )

  val autonomyShortLabel = when (approvalPolicy) {
    ApprovalPolicy.AUTO_APPROVE -> "FULL AUTO"
    ApprovalPolicy.REQUIRE_APPROVAL -> "SAFE HITL"
    ApprovalPolicy.STRICT_SANDBOX -> "STRICT"
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.86f))
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onDismiss
        )
        .testTag("navigation_menu_scrim"),
      contentAlignment = Alignment.CenterEnd
    ) {
      val sysBarsPadding = WindowInsets.systemBars.asPaddingValues()

      Surface(
        modifier = Modifier
          .fillMaxHeight()
          .widthIn(min = 290.dp, max = 340.dp)
          .fillMaxWidth(0.86f)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {}
          )
          .border(
            width = 1.dp,
            color = MenuCardBorder,
            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
          )
          .testTag("aragon_navigation_drawer"),
        color = MenuObsidianBg,
        shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
        shadowElevation = 24.dp
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(
              top = sysBarsPadding.calculateTopPadding(),
              bottom = sysBarsPadding.calculateBottomPadding()
            )
        ) {
          // --- HERO HEADER (Matching AboutAragonScreen aesthetic) ---
          Surface(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 10.dp),
            shape = RoundedCornerShape(16.dp),
            color = MenuCardSurface,
            border = BorderStroke(1.dp, MenuCardBorder)
          ) {
            Box(modifier = Modifier.fillMaxWidth()) {
              Canvas(modifier = Modifier.matchParentSize()) {
                drawCircle(
                  brush = Brush.radialGradient(
                    colors = listOf(
                      MenuCyan.copy(alpha = 0.12f),
                      MenuIndigo.copy(alpha = 0.04f),
                      Color.Transparent
                    ),
                    center = Offset(size.width * 0.2f, size.height * 0.3f),
                    radius = size.maxDimension * 0.7f
                  )
                )
              }

              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 14.dp, vertical = 12.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  // Status Pill
                  Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (isRunning) MenuCyan.copy(alpha = 0.12f) else MenuEmerald.copy(alpha = 0.12f),
                    border = BorderStroke(
                      1.dp,
                      if (isRunning) MenuCyan.copy(alpha = 0.35f) else MenuEmerald.copy(alpha = 0.35f)
                    )
                  ) {
                    Row(
                      modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.5.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                      Box(
                        modifier = Modifier
                          .size(6.dp)
                          .clip(CircleShape)
                          .background(if (isRunning) MenuCyan else MenuEmerald)
                      )
                      Text(
                        text = if (isRunning) "KERNEL ${agentStatus.name}" else "KERNEL ONLINE",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = if (isRunning) MenuCyan else MenuEmerald
                      )
                    }
                  }

                  val closeInteraction = remember { MutableInteractionSource() }
                  Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MenuCardElevated,
                    border = BorderStroke(1.dp, MenuCardBorder),
                    modifier = Modifier
                      .tactilePress(closeInteraction)
                      .clickable(
                        interactionSource = closeInteraction,
                        indication = ripple(color = Color.White),
                        role = Role.Button,
                        onClick = onDismiss
                      )
                      .testTag("close_navigation_menu_btn")
                  ) {
                    Text(
                      text = "ESC ✕",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontWeight = FontWeight.Bold,
                      fontSize = 9.sp,
                      letterSpacing = 0.6.sp,
                      color = Color(0xFFE2E8F0),
                      modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                  }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                  Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0B0E17),
                    border = BorderStroke(1.dp, MenuCyan.copy(alpha = 0.35f)),
                    modifier = Modifier.size(36.dp)
                  ) {
                    Box(contentAlignment = Alignment.Center) {
                      AragonLogoEmblem(
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                      )
                    }
                  }

                  Spacer(modifier = Modifier.width(10.dp))

                  Column {
                    Text(
                      text = "ARAGON COMMAND",
                      fontFamily = InterFontFamily,
                      fontWeight = FontWeight.ExtraBold,
                      fontSize = 14.sp,
                      letterSpacing = 2.sp,
                      color = Color.White
                    )
                    Text(
                      text = "AUTONOMOUS SYSTEM NAVIGATION",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontWeight = FontWeight.SemiBold,
                      fontSize = 8.5.sp,
                      letterSpacing = 0.8.sp,
                      color = MenuCyan
                    )
                  }
                }
              }
            }
          }

          // --- SCROLLABLE BODY ---
          Column(
            modifier = Modifier
              .weight(1f)
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
          ) {
            // Primary Action: New Mission + Reset Workspace Row
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              val newMissionInteraction = remember { MutableInteractionSource() }
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0A1322),
                border = BorderStroke(1.dp, MenuCyan.copy(alpha = 0.45f)),
                shadowElevation = 0.dp,
                modifier = Modifier
                  .weight(1f)
                  .tactilePress(newMissionInteraction)
                  .clickable(
                    interactionSource = newMissionInteraction,
                    indication = ripple(color = MenuCyan.copy(alpha = 0.2f)),
                    role = Role.Button,
                    onClick = {
                      onDismiss()
                      onNewTask()
                    }
                  )
                  .testTag("nav_new_mission_btn")
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    Surface(
                      shape = RoundedCornerShape(5.dp),
                      color = MenuCyan.copy(alpha = 0.16f),
                      border = BorderStroke(1.dp, MenuCyan.copy(alpha = 0.4f))
                    ) {
                      Text(
                        text = "+",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = MenuCyan,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                      )
                    }
                    Text(
                      text = "NEW MISSION",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontWeight = FontWeight.Bold,
                      fontSize = 10.sp,
                      letterSpacing = 0.6.sp,
                      color = Color.White
                    )
                  }
                }
              }

              val resetInteraction = remember { MutableInteractionSource() }
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF150A0D),
                border = BorderStroke(1.dp, MenuDanger.copy(alpha = 0.4f)),
                shadowElevation = 0.dp,
                modifier = Modifier
                  .tactilePress(resetInteraction)
                  .clickable(
                    interactionSource = resetInteraction,
                    indication = ripple(color = MenuDanger.copy(alpha = 0.2f)),
                    role = Role.Button,
                    onClick = {
                      onDismiss()
                      onResetWorkspace()
                    }
                  )
                  .testTag("nav_reset_workspace_btn")
              ) {
                Box(
                  contentAlignment = Alignment.Center,
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                  Text(
                    text = "WIPE",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    color = MenuDanger
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Section 1: Workspace Views
            DrawerSectionLabel(title = "01 // WORKSPACE VIEWS", accent = MenuCyan)

            TypographyNavCard(
              index = "01",
              title = "EXECUTION FEED",
              subtitle = "Live reasoning stream & tool telemetry",
              badge = if (feedCount > 0) "$feedCount STEPS" else null,
              accentColor = MenuCyan,
              isSelected = currentTab == UiTab.EXECUTION_FEED,
              onClick = {
                onSelectTab(UiTab.EXECUTION_FEED)
                onDismiss()
              },
              testTag = "nav_item_execution_feed"
            )

            TypographyNavCard(
              index = "02",
              title = "5-STAGES INSPECTOR",
              subtitle = "Intent, Plan, Exec, Obsv, Termination",
              badge = "PIPELINE",
              accentColor = MenuIndigo,
              isSelected = currentTab == UiTab.FIVE_STAGES,
              onClick = {
                onSelectTab(UiTab.FIVE_STAGES)
                onDismiss()
              },
              testTag = "nav_item_five_stages"
            )

            TypographyNavCard(
              index = "03",
              title = "WORKSPACE EXPLORER",
              subtitle = "Deliverables, code artifacts & reports",
              badge = if (artifactCount > 0) "$artifactCount ITEMS" else "FILES",
              accentColor = MenuEmerald,
              isSelected = currentTab == UiTab.ARTIFACTS,
              onClick = {
                onSelectTab(UiTab.ARTIFACTS)
                onDismiss()
              },
              testTag = "nav_item_files"
            )

            TypographyNavCard(
              index = "04",
              title = "MISSION ARCHIVES",
              subtitle = "Full conversation & execution transcripts",
              badge = if (historyCount > 0) "$historyCount RUNS" else null,
              accentColor = MenuAmber,
              isSelected = currentTab == UiTab.TASK_HISTORY,
              onClick = {
                onSelectTab(UiTab.TASK_HISTORY)
                onDismiss()
              },
              testTag = "nav_item_history"
            )

            TypographyNavCard(
              index = "05",
              title = "ABOUT ARAGON",
              subtitle = "Architecture specs & system manifest",
              badge = "v2.4",
              accentColor = MenuPurple,
              isSelected = currentTab == UiTab.ABOUT_ARAGON,
              onClick = {
                onSelectTab(UiTab.ABOUT_ARAGON)
                onDismiss()
              },
              testTag = "nav_item_about"
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Section 2: Intelligence & Engine Controls
            DrawerSectionLabel(title = "02 // INTELLIGENCE & ENGINE", accent = MenuEmerald)

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              TypographyToolCard(
                modifier = Modifier.weight(1f),
                label = "DOC VAULT",
                value = if (documentCount > 0) "$documentCount Indexed" else "Attach Files",
                tag = "RAG/OCR",
                accentColor = MenuCyan,
                onClick = {
                  onDismiss()
                  onOpenDocuments()
                },
                testTag = "nav_tool_documents"
              )

              TypographyToolCard(
                modifier = Modifier.weight(1f),
                label = "MCP HUB",
                value = "$mcpServerCount Active",
                tag = "TOOLS",
                accentColor = MenuEmerald,
                onClick = {
                  onDismiss()
                  onOpenMcp()
                },
                testTag = "nav_tool_mcp"
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              TypographyToolCard(
                modifier = Modifier.weight(1f),
                label = "AI ENGINE",
                value = currentModelName,
                tag = "MODEL",
                accentColor = MenuPurple,
                onClick = {
                  onDismiss()
                  onOpenModelSelect()
                },
                testTag = "nav_tool_model"
              )

              TypographyToolCard(
                modifier = Modifier.weight(1f),
                label = "AUTONOMY",
                value = autonomyShortLabel,
                tag = "POLICY",
                accentColor = MenuAmber,
                onClick = {
                  onDismiss()
                  onOpenAutonomy()
                },
                testTag = "nav_tool_autonomy"
              )
            }

            if (onReplaySplash != null) {
              TypographyToolCard(
                modifier = Modifier.fillMaxWidth(),
                label = "MONOCHROME BOOT SEQUENCE",
                value = "Replay Aragon Cybernetic Boot Animation",
                tag = "REPLAY",
                accentColor = MenuCyan,
                onClick = {
                  onDismiss()
                  onReplaySplash()
                },
                testTag = "nav_tool_replay_splash"
              )
            }
          }

          // --- FOOTER ---
          HorizontalDivider(color = MenuCardBorder)
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(MenuCardSurface)
              .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(6.dp)
                  .clip(CircleShape)
                  .background(MenuEmerald)
              )
              Text(
                text = "PURE AMOLED // ZERO-TELEMETRY",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = Color(0xFF94A3B8)
              )
            }
            Text(
              text = "v2.4.0",
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 8.5.sp,
              color = MenuCyan
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DrawerSectionLabel(
  title: String,
  accent: Color
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 4.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    Box(
      modifier = Modifier
        .size(width = 3.dp, height = 11.dp)
        .clip(RoundedCornerShape(2.dp))
        .background(accent)
    )
    Text(
      text = title,
      fontFamily = JetBrainsMonoFontFamily,
      fontWeight = FontWeight.Bold,
      fontSize = 9.sp,
      letterSpacing = 1.1.sp,
      color = Color(0xFF94A3B8)
    )
  }
}

@Composable
private fun TypographyNavCard(
  index: String,
  title: String,
  subtitle: String,
  badge: String?,
  accentColor: Color,
  isSelected: Boolean,
  onClick: () -> Unit,
  testTag: String
) {
  val interactionSource = remember { MutableInteractionSource() }
  val targetBg by animateColorAsState(
    targetValue = if (isSelected) Color(0xFF0D1320) else MenuCardSurface,
    animationSpec = tween(120),
    label = "nav_card_bg"
  )
  val targetBorder by animateColorAsState(
    targetValue = if (isSelected) accentColor.copy(alpha = 0.65f) else MenuCardBorder,
    animationSpec = tween(120),
    label = "nav_card_border"
  )

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = targetBg,
    border = BorderStroke(1.dp, targetBorder),
    shadowElevation = 0.dp,
    modifier = Modifier
      .fillMaxWidth()
      .tactilePress(interactionSource)
      .clickable(
        interactionSource = interactionSource,
        indication = ripple(color = accentColor.copy(alpha = 0.2f)),
        role = Role.Button,
        onClick = onClick
      )
      .testTag(testTag)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Surface(
          shape = RoundedCornerShape(7.dp),
          color = accentColor.copy(alpha = if (isSelected) 0.2f else 0.1f),
          border = BorderStroke(1.dp, accentColor.copy(alpha = if (isSelected) 0.5f else 0.25f)),
          modifier = Modifier.padding(end = 10.dp)
        ) {
          Text(
            text = index,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = accentColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
          )
        }

        Column(modifier = Modifier.weight(1f, fill = false)) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp,
            letterSpacing = 0.3.sp,
            color = if (isSelected) Color.White else Color(0xFFF1F5F9),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = InterFontFamily,
            fontSize = 9.5.sp,
            color = if (isSelected) Color(0xFFCBD5E1) else Color(0xFF64748B),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      if (badge != null) {
        Spacer(modifier = Modifier.width(6.dp))
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = accentColor.copy(alpha = 0.14f),
          border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
        ) {
          Text(
            text = badge,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            color = accentColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
          )
        }
      }
    }
  }
}

@Composable
private fun TypographyToolCard(
  modifier: Modifier = Modifier,
  label: String,
  value: String,
  tag: String,
  accentColor: Color,
  onClick: () -> Unit,
  testTag: String
) {
  val interactionSource = remember { MutableInteractionSource() }

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MenuCardSurface,
    border = BorderStroke(1.dp, MenuCardBorder),
    shadowElevation = 0.dp,
    modifier = modifier
      .tactilePress(interactionSource)
      .clickable(
        interactionSource = interactionSource,
        indication = ripple(color = accentColor.copy(alpha = 0.2f)),
        role = Role.Button,
        onClick = onClick
      )
      .testTag(testTag)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = label,
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 8.5.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 0.6.sp,
          color = Color(0xFF94A3B8),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Surface(
          shape = RoundedCornerShape(4.dp),
          color = accentColor.copy(alpha = 0.14f),
          border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
        ) {
          Text(
            text = tag,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            color = accentColor,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(4.dp))

      Text(
        text = value,
        fontFamily = InterFontFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}
