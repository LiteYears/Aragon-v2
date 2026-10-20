package com.example.agent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledActionPrimaryOn
import com.example.ui.theme.AmoledBackground
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pure Typography Navigation Hub for Aragon.
 *
 * Strict AMOLED design without decorative icons or emojis:
 * - High-contrast monochromatic palette with selective action borders.
 * - Monospace indexes and clean badges.
 * - Organized into Destinations, Engine Controls, and System Actions.
 * - Smooth spring entry and graceful exit animations.
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
  onReplaySplash: () -> Unit,
  onDismiss: () -> Unit
) {
  val scrollState = rememberScrollState()
  var isVisible by remember { mutableStateOf(false) }
  val coroutineScope = rememberCoroutineScope()

  LaunchedEffect(Unit) {
    isVisible = true
  }

  fun dismissWithAnimation(onComplete: (() -> Unit)? = null) {
    coroutineScope.launch {
      isVisible = false
      delay(150)
      onComplete?.invoke()
      onDismiss()
    }
  }

  Dialog(
    onDismissRequest = { dismissWithAnimation() },
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    BackHandler {
      dismissWithAnimation()
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null
        ) { dismissWithAnimation() },
      contentAlignment = Alignment.Center
    ) {
      AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
          scaleIn(
            initialScale = 0.93f,
            animationSpec = spring(
              dampingRatio = Spring.DampingRatioLowBouncy,
              stiffness = Spring.StiffnessMediumLow
            )
          ) +
          slideInVertically(
            initialOffsetY = { 40 },
            animationSpec = spring(
              dampingRatio = Spring.DampingRatioNoBouncy,
              stiffness = Spring.StiffnessMediumLow
            )
          ),
        exit = fadeOut(animationSpec = tween(140, easing = FastOutSlowInEasing)) +
          scaleOut(
            targetScale = 0.95f,
            animationSpec = tween(140)
          ) +
          slideOutVertically(
            targetOffsetY = { 30 },
            animationSpec = tween(140)
          )
      ) {
        Surface(
          modifier = Modifier
            .fillMaxWidth(0.94f)
            .fillMaxHeight(0.88f)
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null
            ) { /* Consume taps inside menu surface so dialog background doesn't dismiss */ },
          shape = RoundedCornerShape(24.dp),
          color = Color.White,
          shadowElevation = 16.dp,
          border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(horizontal = 18.dp, vertical = 16.dp)
          ) {
            // Top Header Row (Pure Typography - Zero Icons)
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Box(
                    modifier = Modifier
                      .size(6.dp)
                      .clip(CircleShape)
                      .background(Color(0xFF0F172A))
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = "ARAGON WORKSPACE",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    letterSpacing = 1.6.sp,
                    color = Color(0xFF0F172A)
                  )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = "AUTONOMOUS INTELLIGENCE ENGINE",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = FontWeight.Medium,
                  fontSize = 8.5.sp,
                  letterSpacing = 0.8.sp,
                  color = Color(0xFF64748B)
                )
              }

              // Pure text CLOSE button (no icon)
              AragonHeaderButton(
                text = "CLOSE",
                onClick = { dismissWithAnimation() },
                variant = AragonButtonVariant.MUTED,
                testTag = "close_navigation_menu_btn"
              )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Main Navigation & Workspaces Content
            Column(
              modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
              verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
              // SECTION 1: WORKSPACES & DESTINATIONS
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                  text = "WORKSPACES & DESTINATIONS",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 9.5.sp,
                  fontWeight = FontWeight.Bold,
                  letterSpacing = 1.2.sp,
                  color = AmoledTextMuted
                )

                // 01. Execution Feed
                TypographyNavigationCard(
                  index = "01",
                  title = "EXECUTION FEED",
                  subtitle = if (feedCount > 0) "$feedCount live steps executed · Real-time tail" else "Autonomous live steps, reasoning & output",
                  badge = if (feedCount > 0) "$feedCount STEPS" else null,
                  isSelected = currentTab == UiTab.EXECUTION_FEED,
                  onClick = {
                    dismissWithAnimation { onSelectTab(UiTab.EXECUTION_FEED) }
                  },
                  testTag = "nav_feed_item"
                )

                // 02. Five Stages Pipeline
                TypographyNavigationCard(
                  index = "02",
                  title = "FIVE-STAGE PIPELINE",
                  subtitle = "Ingest → Plan → Sandbox → Verify → Deliver cognitive states",
                  badge = "PIPELINE",
                  isSelected = currentTab == UiTab.FIVE_STAGES,
                  onClick = {
                    dismissWithAnimation { onSelectTab(UiTab.FIVE_STAGES) }
                  },
                  testTag = "nav_stages_item"
                )

                // 03. Workspace Files & Deliverables
                TypographyNavigationCard(
                  index = "03",
                  title = "WORKSPACE FILES",
                  subtitle = if (artifactCount > 0) "$artifactCount deliverables generated (.docx, .xlsx, .pdf, code)" else "Workspace file manager & synthesized deliverables",
                  badge = if (artifactCount > 0) "$artifactCount FILES" else null,
                  isSelected = currentTab == UiTab.ARTIFACTS,
                  onClick = {
                    dismissWithAnimation { onSelectTab(UiTab.ARTIFACTS) }
                  },
                  testTag = "nav_artifacts_item"
                )

                // 04. Mission History
                TypographyNavigationCard(
                  index = "04",
                  title = "MISSION HISTORY",
                  subtitle = if (historyCount > 0) "$historyCount missions recorded · Transcripts & restore" else "Completed runs, transcripts & state restoration",
                  badge = if (historyCount > 0) "$historyCount RUNS" else null,
                  isSelected = currentTab == UiTab.TASK_HISTORY,
                  onClick = {
                    dismissWithAnimation { onSelectTab(UiTab.TASK_HISTORY) }
                  },
                  testTag = "nav_history_item"
                )

                // 05. System Specs & Runtime
                TypographyNavigationCard(
                  index = "05",
                  title = "SYSTEM & ENGINE SPECS",
                  subtitle = "Architecture, E2B Cloud Firecracker microVM & runtime settings",
                  badge = null,
                  isSelected = currentTab == UiTab.ABOUT_ARAGON,
                  onClick = {
                    dismissWithAnimation { onSelectTab(UiTab.ABOUT_ARAGON) }
                  },
                  testTag = "nav_about_item"
                )
              }

              // SECTION 2: INTELLIGENCE ENGINE CONTROLS
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                  text = "INTELLIGENCE ENGINE CONTROLS",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 9.5.sp,
                  fontWeight = FontWeight.Bold,
                  letterSpacing = 1.2.sp,
                  color = AmoledTextMuted
                )

                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  TypographyToolCard(
                    modifier = Modifier.weight(1f),
                    label = "AI MODEL",
                    value = currentModelName,
                    tag = "CONFIG",
                    onClick = {
                      dismissWithAnimation { onOpenModelSelect() }
                    },
                    testTag = "nav_open_model_btn"
                  )

                  TypographyToolCard(
                    modifier = Modifier.weight(1f),
                    label = "MCP SERVERS",
                    value = if (mcpServerCount > 0) "$mcpServerCount Active" else "Configure",
                    tag = "TOOLS",
                    onClick = {
                      dismissWithAnimation { onOpenMcp() }
                    },
                    testTag = "nav_open_mcp_btn"
                  )
                }

                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  TypographyToolCard(
                    modifier = Modifier.weight(1f),
                    label = "AUTONOMY POLICY",
                    value = approvalPolicy.name,
                    tag = "SAFETY",
                    onClick = {
                      dismissWithAnimation { onOpenAutonomy() }
                    },
                    testTag = "nav_open_autonomy_btn"
                  )

                  TypographyToolCard(
                    modifier = Modifier.weight(1f),
                    label = "DOCUMENTS",
                    value = if (documentCount > 0) "$documentCount Loaded" else "Attach",
                    tag = "INPUT",
                    onClick = {
                      dismissWithAnimation { onOpenDocuments() }
                    },
                    testTag = "nav_open_docs_btn"
                  )
                }
              }

              // SECTION 3: WORKSPACE ACTIONS
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                  text = "WORKSPACE ACTIONS",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 9.5.sp,
                  fontWeight = FontWeight.Bold,
                  letterSpacing = 1.2.sp,
                  color = AmoledTextMuted
                )

                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  AragonActionButton(
                    text = "REPLAY INTRO",
                    onClick = {
                      dismissWithAnimation { onReplaySplash() }
                    },
                    variant = AragonButtonVariant.MUTED,
                    height = 36.dp,
                    modifier = Modifier.weight(1f),
                    testTag = "nav_replay_intro_btn"
                  )

                  AragonActionButton(
                    text = "RESET WORKSPACE",
                    onClick = {
                      dismissWithAnimation { onResetWorkspace() }
                    },
                    variant = AragonButtonVariant.DANGER,
                    height = 36.dp,
                    modifier = Modifier.weight(1f),
                    testTag = "nav_reset_workspace_btn"
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
 * Pure-typography navigation row card.
 * Zero icons or emojis, using monospace index markers and high-contrast typography.
 */
@Composable
private fun TypographyNavigationCard(
  index: String,
  title: String,
  subtitle: String,
  badge: String?,
  isSelected: Boolean,
  onClick: () -> Unit,
  testTag: String
) {
  val interactionSource = remember { MutableInteractionSource() }
  val targetBg by animateColorAsState(
    targetValue = if (isSelected) Color(0xFF0F172A) else Color(0xFFF8FAFC),
    animationSpec = tween(120),
    label = "nav_card_bg"
  )
  val targetBorder by animateColorAsState(
    targetValue = if (isSelected) Color(0xFF0F172A) else Color(0xFFE2E8F0),
    animationSpec = tween(120),
    label = "nav_card_border"
  )

  Surface(
    shape = RoundedCornerShape(14.dp),
    color = targetBg,
    border = BorderStroke(1.dp, targetBorder),
    shadowElevation = if (isSelected) 3.dp else 1.dp,
    modifier = Modifier
      .fillMaxWidth()
      .tactilePress(interactionSource)
      .clickable(
        interactionSource = interactionSource,
        indication = ripple(color = Color(0xFF0F172A).copy(alpha = 0.2f)),
        role = Role.Button,
        onClick = onClick
      )
      .testTag(testTag)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Monospace Index Tag: [01]
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (isSelected) Color(0xFF1E293B) else Color(0xFFE2E8F0),
          border = null,
          modifier = Modifier.padding(end = 12.dp)
        ) {
          Text(
            text = index,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) Color.White else Color(0xFF334155),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
          )
        }

        Column(modifier = Modifier.weight(1f, fill = false)) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = InterFontFamily,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 0.3.sp,
            color = if (isSelected) Color.White else Color(0xFF0F172A),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = InterFontFamily,
            fontSize = 10.sp,
            color = if (isSelected) Color(0xFF94A3B8) else Color(0xFF64748B),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      if (badge != null) {
        Spacer(modifier = Modifier.width(6.dp))
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (isSelected) Color.White else Color(0xFF0F172A),
          border = null
        ) {
          Text(
            text = badge,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) Color(0xFF0F172A) else Color.White,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
          )
        }
      }
    }
  }
}

/**
 * Pure-typography quick tool card in matching white theme.
 */
@Composable
private fun TypographyToolCard(
  modifier: Modifier = Modifier,
  label: String,
  value: String,
  tag: String,
  onClick: () -> Unit,
  testTag: String
) {
  val interactionSource = remember { MutableInteractionSource() }

  Surface(
    shape = RoundedCornerShape(14.dp),
    color = Color(0xFFF8FAFC),
    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
    shadowElevation = 1.dp,
    modifier = modifier
      .tactilePress(interactionSource)
      .clickable(
        interactionSource = interactionSource,
        indication = ripple(color = Color(0xFF0F172A).copy(alpha = 0.15f)),
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
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 0.8.sp,
          color = Color(0xFF64748B)
        )
        Text(
          text = tag,
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 7.5.sp,
          fontWeight = FontWeight.Bold,
          color = Color(0xFF0284C7)
        )
      }

      Spacer(modifier = Modifier.height(4.dp))

      Text(
        text = value,
        fontFamily = InterFontFamily,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF0F172A),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}
