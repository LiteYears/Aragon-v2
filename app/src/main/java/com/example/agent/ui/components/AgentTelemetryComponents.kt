package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.agent.core.ApprovalPolicy
import com.example.agent.ui.FeedFilter
import com.example.agent.ui.RunTelemetry
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledActionPrimaryOn
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
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.util.Locale

/**
 * Compact Live Telemetry & Metrics bar displayed above the Execution Feed.
 * Shows run duration, step count, tool count, and estimated tokens with a 1-tap inspector.
 */
@Composable
fun LiveTelemetryBar(
  telemetry: RunTelemetry,
  onOpenTelemetryDialog: () -> Unit,
  modifier: Modifier = Modifier
) {
  val formattedDuration = if (telemetry.totalDurationMs > 0) {
    String.format(Locale.US, "%.1fs", telemetry.totalDurationMs / 1000.0)
  } else {
    "<0.1s"
  }

  val formattedTokens = if (telemetry.estimatedTotalTokens >= 1000) {
    String.format(Locale.US, "%.1fk", telemetry.estimatedTotalTokens / 1000.0)
  } else {
    "${telemetry.estimatedTotalTokens}"
  }

  Surface(
    shape = RoundedCornerShape(8.dp),
    color = Color(0xFF101010),
    border = BorderStroke(1.dp, AmoledBorderSubtle),
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 4.dp)
      .clip(RoundedCornerShape(8.dp))
      .clickable(
        role = Role.Button,
        onClickLabel = "Open Run Telemetry Inspector"
      ) { onOpenTelemetryDialog() }
      .testTag("live_telemetry_bar")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Box(
          modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(if (telemetry.errorCount > 0) AmoledStatusError else AmoledStatusSuccess)
        )

        Text(
          text = "TELEMETRY",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 9.sp,
          color = AmoledIconGreyLight,
          letterSpacing = 0.5.sp
        )

        Text(
          text = "•",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 9.sp,
          color = AmoledTextMuted
        )

        Text(
          text = "$formattedDuration",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 9.sp,
          color = AmoledTextPrimary,
          fontWeight = FontWeight.SemiBold
        )

        Text(
          text = "•",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 9.sp,
          color = AmoledTextMuted
        )

        Text(
          text = "${telemetry.totalSteps} steps",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 9.sp,
          color = AmoledTextSecondary
        )

        if (telemetry.toolCallsCount > 0) {
          Text(
            text = "•",
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledTextMuted
          )
          Text(
            text = "${telemetry.toolCallsCount} tools",
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledActionPrimary
          )
        }

        Text(
          text = "•",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 9.sp,
          color = AmoledTextMuted
        )

        Text(
          text = "~$formattedTokens tok",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 9.sp,
          color = AmoledIconGreyLight
        )
      }

      Text(
        text = "[METRICS]",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = AmoledActionPrimary
      )
    }
  }
}

/**
 * Filter Pill Row for Execution Feed steps:
 * Allows user to filter by ALL, TOOLS, THINKING, OUTPUTS, ERRORS.
 */
@Composable
fun FeedFilterRow(
  activeFilter: FeedFilter,
  onSelectFilter: (FeedFilter) -> Unit,
  counts: Map<FeedFilter, Int>,
  modifier: Modifier = Modifier
) {
  val scrollState = rememberScrollState()
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 2.dp)
      .horizontalScroll(scrollState),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    FeedFilter.values().forEach { filter ->
      val isSelected = activeFilter == filter
      val count = counts[filter] ?: 0

      val bgAnimated by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF222222) else Color(0xFF101010),
        animationSpec = tween(120),
        label = "filter_bg"
      )
      val borderAnimated by animateColorAsState(
        targetValue = if (isSelected) AmoledActionPrimary else AmoledBorderSubtle,
        animationSpec = tween(120),
        label = "filter_border"
      )
      val textAnimated by animateColorAsState(
        targetValue = if (isSelected) Color.White else AmoledIconGrey,
        animationSpec = tween(120),
        label = "filter_text"
      )

      Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgAnimated,
        border = BorderStroke(1.dp, borderAnimated),
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .clickable(
            role = Role.Tab,
            onClickLabel = "Filter by ${filter.label}"
          ) { onSelectFilter(filter) }
          .testTag("filter_chip_${filter.name.lowercase()}")
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Text(
            text = filter.label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 9.sp,
            color = textAnimated
          )

          if (count > 0) {
            Text(
              text = "($count)",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 8.sp,
              color = if (isSelected) AmoledActionPrimary else AmoledIconGreyDark
            )
          }
        }
      }
    }
  }
}

/**
 * Autonomy Policy Selector Pill & Dialog:
 * Switch between FULL AUTO, SAFE HITL, and STRICT SANDBOX.
 */
@Composable
fun AutonomyPolicyPill(
  currentPolicy: ApprovalPolicy,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val (pillBg, pillBorder, pillColor, label) = when (currentPolicy) {
    ApprovalPolicy.AUTO_APPROVE -> Quadruple(Color(0xFF102014), AmoledStatusSuccess.copy(alpha = 0.5f), AmoledStatusSuccess, "FULL AUTO")
    ApprovalPolicy.REQUIRE_APPROVAL -> Quadruple(Color(0xFF221A0E), AmoledStatusWarning.copy(alpha = 0.5f), AmoledStatusWarning, "SAFE HITL")
    ApprovalPolicy.STRICT_SANDBOX -> Quadruple(Color(0xFF141822), Color(0xFF64B5F6).copy(alpha = 0.5f), Color(0xFF90CAF9), "STRICT")
  }

  Surface(
    shape = RoundedCornerShape(6.dp),
    color = pillBg,
    border = BorderStroke(1.dp, pillBorder),
    modifier = modifier
      .clip(RoundedCornerShape(6.dp))
      .clickable(
        role = Role.Button,
        onClickLabel = "Change Agent Autonomy Policy: Current is $label"
      ) { onClick() }
      .testTag("autonomy_policy_pill")
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
      Box(
        modifier = Modifier
          .size(5.dp)
          .clip(CircleShape)
          .background(pillColor)
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        color = pillColor
      )
    }
  }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/**
 * Dialog to configure Agent Autonomy & Execution Policy.
 */
@Composable
fun AutonomyPolicyDialog(
  currentPolicy: ApprovalPolicy,
  onSelectPolicy: (ApprovalPolicy) -> Unit,
  onDismiss: () -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = Color(0xFF050508),
      shadowElevation = 24.dp,
      border = BorderStroke(1.dp, Color(0xFF191C26)),
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 520.dp)
        .testTag("autonomy_policy_dialog")
    ) {
      Column(
        modifier = Modifier
          .background(
            androidx.compose.ui.graphics.Brush.radialGradient(
              colors = listOf(Color(0xFF0F203C).copy(alpha = 0.45f), Color(0xFF050508)),
              center = androidx.compose.ui.geometry.Offset(200f, 50f),
              radius = 520f
            )
          )
          .padding(18.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text(
              text = "GOVERNANCE & SAFETY",
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 9.sp,
              color = Color(0xFF38BDF8),
              letterSpacing = 1.2.sp
            )
            Text(
              text = "AGENT AUTONOMY POLICY",
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
            testTag = "close_autonomy_dialog_btn"
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "Controls how tools, terminal commands, and workspace changes are approved during agent task runs.",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF94A3B8),
          fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          // Option 1: FULL AUTO
          PolicyCard(
            title = "FULL AUTONOMOUS (RECOMMENDED)",
            badge = "AUTO",
            badgeColor = Color(0xFF10B981),
            description = "Agent executes tools, bash scripts, and file modifications automatically without pausing. Optimal for high-speed end-to-end task solving.",
            isSelected = currentPolicy == ApprovalPolicy.AUTO_APPROVE,
            onClick = {
              onSelectPolicy(ApprovalPolicy.AUTO_APPROVE)
              onDismiss()
            }
          )

          // Option 2: SAFE HITL (Human-in-the-Loop)
          PolicyCard(
            title = "SAFE HUMAN-IN-THE-LOOP",
            badge = "HITL",
            badgeColor = Color(0xFFF59E0B),
            description = "Intercepts potentially high-risk shell commands (e.g. rm -rf, system resets, network writes) and prompts for approval before execution.",
            isSelected = currentPolicy == ApprovalPolicy.REQUIRE_APPROVAL,
            onClick = {
              onSelectPolicy(ApprovalPolicy.REQUIRE_APPROVAL)
              onDismiss()
            }
          )

          // Option 3: STRICT SANDBOX
          PolicyCard(
            title = "STRICT CONFINED SANDBOX",
            badge = "STRICT",
            badgeColor = Color(0xFF38BDF8),
            description = "Disallows raw process execution, package installation, and network crawling. Restricts the agent to read-only inspections and confined file edits.",
            isSelected = currentPolicy == ApprovalPolicy.STRICT_SANDBOX,
            onClick = {
              onSelectPolicy(ApprovalPolicy.STRICT_SANDBOX)
              onDismiss()
            }
          )
        }
      }
    }
  }
}

@Composable
private fun PolicyCard(
  title: String,
  badge: String,
  badgeColor: Color,
  description: String,
  isSelected: Boolean,
  onClick: () -> Unit
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = if (isSelected) Color(0xFF0A111D) else Color(0xFF07070B),
    border = BorderStroke(
      1.dp,
      if (isSelected) badgeColor.copy(alpha = 0.65f) else Color(0xFF191C26)
    ),
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .clickable { onClick() }
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Surface(
            shape = RoundedCornerShape(5.dp),
            color = badgeColor.copy(alpha = 0.14f),
            border = BorderStroke(0.8.dp, badgeColor.copy(alpha = 0.45f)),
            modifier = Modifier.padding(end = 4.dp)
          ) {
            Text(
              text = badge,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.ExtraBold,
              fontSize = 8.sp,
              color = badgeColor,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
          }

          Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp,
            color = if (isSelected) Color(0xFFF8FAFC) else Color(0xFFE2E8F0)
          )
        }

        if (isSelected) {
          Text(
            text = "[ACTIVE]",
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 9.sp,
            color = Color(0xFF10B981)
          )
        }
      }

      Spacer(modifier = Modifier.height(4.dp))

      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = if (isSelected) Color(0xFFCBD5E1) else Color(0xFF94A3B8),
        fontSize = 10.5.sp,
        lineHeight = 15.sp
      )
    }
  }
}

/**
 * Full Run Telemetry & Execution Analytics Dialog:
 * Styled in Pure AMOLED True Black matching AboutAragonScreen.kt.
 */
@Composable
fun RunTelemetryDialog(
  telemetry: RunTelemetry,
  currentPolicy: ApprovalPolicy,
  currentProviderName: String,
  activeFilter: FeedFilter? = null,
  onSelectFilter: ((FeedFilter) -> Unit)? = null,
  filterCounts: Map<FeedFilter, Int>? = null,
  onCopyTranscript: () -> Unit,
  onShareTranscript: () -> Unit,
  onSaveTranscriptToWorkspace: () -> Unit,
  onDismiss: () -> Unit
) {
  val formattedDuration = if (telemetry.totalDurationMs > 0) {
    String.format(Locale.US, "%.2fs", telemetry.totalDurationMs / 1000.0)
  } else {
    "<0.1s"
  }

  val formattedCost = String.format(Locale.US, "$%.4f", telemetry.estimatedCostUsd)

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = Color(0xFF050508),
      shadowElevation = 24.dp,
      border = BorderStroke(1.dp, Color(0xFF191C26)),
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 580.dp)
        .testTag("run_telemetry_dialog")
    ) {
      Column(
        modifier = Modifier
          .background(
            androidx.compose.ui.graphics.Brush.radialGradient(
              colors = listOf(Color(0xFF0F203C).copy(alpha = 0.45f), Color(0xFF050508)),
              center = androidx.compose.ui.geometry.Offset(220f, 50f),
              radius = 580f
            )
          )
          .padding(18.dp)
          .verticalScroll(rememberScrollState())
      ) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text(
              text = "REAL-TIME DIAGNOSTICS",
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 8.5.sp,
              color = Color(0xFF38BDF8),
              letterSpacing = 1.2.sp
            )
            Text(
              text = "RUN TELEMETRY & METRICS",
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
            testTag = "close_telemetry_dialog_btn"
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (activeFilter != null && onSelectFilter != null && filterCounts != null) {
          Text(
            text = "FEED FILTER",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF94A3B8),
            fontSize = 9.sp,
            letterSpacing = 0.8.sp
          )
          Spacer(modifier = Modifier.height(6.dp))
          FeedFilterRow(
            activeFilter = activeFilter,
            onSelectFilter = onSelectFilter,
            counts = filterCounts,
            modifier = Modifier.padding(horizontal = 0.dp)
          )
          Spacer(modifier = Modifier.height(12.dp))
        }

        // Summary Metric Cards
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          TelemetryMetricCard(
            label = "TOTAL TIME",
            value = formattedDuration,
            accentColor = Color(0xFF38BDF8),
            modifier = Modifier.weight(1f)
          )
          TelemetryMetricCard(
            label = "STEPS",
            value = "${telemetry.totalSteps}",
            accentColor = Color(0xFF10B981),
            modifier = Modifier.weight(1f)
          )
          TelemetryMetricCard(
            label = "TOOLS",
            value = "${telemetry.toolCallsCount}",
            accentColor = Color(0xFFA855F7),
            modifier = Modifier.weight(1f)
          )
          TelemetryMetricCard(
            label = "EST. COST",
            value = formattedCost,
            accentColor = Color(0xFFF59E0B),
            modifier = Modifier.weight(1f)
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Token Usage Breakdown
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = Color(0xFF07070B),
          border = BorderStroke(1.dp, Color(0xFF191C26)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Text(
              text = "TOKEN USAGE ESTIMATE",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.ExtraBold,
              fontSize = 9.sp,
              color = Color(0xFF38BDF8),
              letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("Prompt Tokens (approx):", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFF94A3B8))
              Text("~${telemetry.estimatedPromptTokens}", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFFF8FAFC), fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("Completion Tokens (approx):", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFF94A3B8))
              Text("~${telemetry.estimatedCompletionTokens}", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFFF8FAFC), fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("Total Combined Tokens:", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFF94A3B8))
              Text("~${telemetry.estimatedTotalTokens}", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Tool Invocation Breakdown
        if (telemetry.toolBreakdown.isNotEmpty()) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF07070B),
            border = BorderStroke(1.dp, Color(0xFF191C26)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text(
                text = "TOOL CALLS BREAKDOWN",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 9.sp,
                color = Color(0xFFA855F7),
                letterSpacing = 0.8.sp
              )
              Spacer(modifier = Modifier.height(8.dp))
              telemetry.toolBreakdown.forEach { (toolName, count) ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Text(toolName, fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFFCBD5E1))
                  Text("$count calls", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)
                }
              }
            }
          }
          Spacer(modifier = Modifier.height(10.dp))
        }

        // Runtime Details
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = Color(0xFF07070B),
          border = BorderStroke(1.dp, Color(0xFF191C26)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Text(
              text = "EXECUTION ENVIRONMENT",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.ExtraBold,
              fontSize = 9.sp,
              color = Color(0xFF10B981),
              letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Active Engine:", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFF94A3B8))
              Text(currentProviderName, fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFFF8FAFC), fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Autonomy Policy:", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFF94A3B8))
              Text(currentPolicy.name, fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Deliverables Produced:", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFF94A3B8))
              Text("${telemetry.deliverableCount} files", fontFamily = JetBrainsMonoFontFamily, fontSize = 10.sp, color = Color(0xFFF8FAFC), fontWeight = FontWeight.SemiBold)
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Quick Export Toolbar
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          AragonActionButton(
            text = "SHARE TRANSCRIPT",
            onClick = onShareTranscript,
            variant = AragonButtonVariant.PRIMARY,
            height = 32.dp,
            testTag = "telemetry_share_transcript_btn"
          )

          AragonActionButton(
            text = "SAVE AS .MD FILE",
            onClick = onSaveTranscriptToWorkspace,
            variant = AragonButtonVariant.DEFAULT,
            height = 32.dp,
            testTag = "telemetry_save_file_btn"
          )
        }
      }
    }
  }
}

@Composable
private fun TelemetryMetricCard(
  label: String,
  value: String,
  accentColor: Color = Color(0xFF38BDF8),
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = Color(0xFF07070B),
    border = BorderStroke(1.dp, Color(0xFF191C26)),
    modifier = modifier
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = label,
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF94A3B8),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Spacer(modifier = Modifier.height(3.dp))
      Text(
        text = value,
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.ExtraBold,
        color = accentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/**
 * Sleek, unified telemetry button designed for top bar / header integration.
 */
@Composable
fun CompactTelemetryButton(
  telemetry: RunTelemetry,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val formattedDuration = if (telemetry.totalDurationMs > 0) {
    String.format(Locale.US, "%.1fs", telemetry.totalDurationMs / 1000.0)
  } else {
    "<0.1s"
  }

  Surface(
    shape = RoundedCornerShape(14.dp),
    color = Color(0xFF07070B),
    border = BorderStroke(1.dp, Color(0xFF191C26)),
    shadowElevation = 2.dp,
    modifier = modifier
      .clip(RoundedCornerShape(14.dp))
      .clickable(role = Role.Button, onClickLabel = "Open Telemetry") { onClick() }
      .testTag("compact_telemetry_btn")
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Box(
        modifier = Modifier
          .size(5.dp)
          .clip(CircleShape)
          .background(if (telemetry.errorCount > 0) Color(0xFFEF4444) else Color(0xFF10B981))
      )
      Text(
        text = "⚡ $formattedDuration · ${telemetry.totalSteps}s",
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFFE2E8F0)
      )
    }
  }
}

/**
 * Compact filter button that opens an organized filter dialog/popover.
 */
@Composable
fun FeedFilterDropdownButton(
  activeFilter: FeedFilter,
  totalCount: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = Color(0xFF07070B),
    border = BorderStroke(1.dp, Color(0xFF191C26)),
    shadowElevation = 2.dp,
    modifier = modifier
      .clip(RoundedCornerShape(14.dp))
      .clickable(role = Role.Button, onClickLabel = "Change Feed Filter") { onClick() }
      .testTag("feed_filter_dropdown_btn")
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Text(
        text = "${activeFilter.label} ($totalCount) ▾",
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFFF8FAFC)
      )
    }
  }
}

/**
 * Modal dialog for selecting feed filters cleanly in Pure AMOLED style.
 */
@Composable
fun FeedFilterDialog(
  activeFilter: FeedFilter,
  counts: Map<FeedFilter, Int>,
  onSelect: (FeedFilter) -> Unit,
  onDismiss: () -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = Color(0xFF050508),
      shadowElevation = 24.dp,
      border = BorderStroke(1.dp, Color(0xFF191C26)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .widthIn(max = 420.dp)
        .testTag("feed_filter_dialog")
    ) {
      Column(
        modifier = Modifier
          .background(
            androidx.compose.ui.graphics.Brush.radialGradient(
              colors = listOf(Color(0xFF0F203C).copy(alpha = 0.45f), Color(0xFF050508)),
              center = androidx.compose.ui.geometry.Offset(180f, 40f),
              radius = 420f
            )
          )
          .padding(18.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "FILTER EXECUTIONS",
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 12.sp,
            letterSpacing = 0.8.sp,
            color = Color(0xFFF8FAFC)
          )
          AragonHeaderButton(
            text = "DONE",
            onClick = onDismiss,
            variant = AragonButtonVariant.MUTED,
            testTag = "done_filter_dialog_btn"
          )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          FeedFilter.values().forEach { filter ->
            val isSelected = activeFilter == filter
            val count = counts[filter] ?: 0
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = if (isSelected) Color(0xFF0B1322) else Color(0xFF07070B),
              border = BorderStroke(1.dp, if (isSelected) Color(0xFF38BDF8) else Color(0xFF191C26)),
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                  onSelect(filter)
                  onDismiss()
                }
                .testTag("filter_option_${filter.name.lowercase()}")
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(
                    text = if (isSelected) "●" else "○",
                    fontSize = 10.sp,
                    color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF64748B)
                  )
                  Text(
                    text = filter.label,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                    fontSize = 11.sp,
                    color = if (isSelected) Color(0xFFF8FAFC) else Color(0xFFCBD5E1)
                  )
                }
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = if (isSelected) Color(0xFF38BDF8).copy(alpha = 0.16f) else Color(0xFF0D111A),
                  border = BorderStroke(0.8.dp, if (isSelected) Color(0xFF38BDF8).copy(alpha = 0.45f) else Color(0xFF191C26))
                ) {
                  Text(
                    text = "$count",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
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
