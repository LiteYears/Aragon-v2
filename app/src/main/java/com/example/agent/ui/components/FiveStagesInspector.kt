package com.example.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.core.FiveStageRecord
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGrey
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledSurfaceVariant
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun FiveStagesInspector(
  record: FiveStageRecord,
  modifier: Modifier = Modifier
) {
  val scrollState = rememberScrollState()

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(scrollState)
      .padding(16.dp)
      .testTag("five_stages_inspector"),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Reasoning & Execution Pipeline",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = AmoledTextPrimary
        )
        Text(
          text = "Live breakdown of model cognition, tool actions, and verified deliverables",
          style = MaterialTheme.typography.bodySmall,
          color = AmoledTextMuted
        )
      }

      val fullReport = buildString {
        appendLine("=== COGNITIVE & EXECUTION RECORD ===")
        appendLine("1. GOAL: ${record.intent}")
        appendLine("2. STRATEGY:\n${record.currentPlan.joinToString("\n") { "  - $it" }}")
        appendLine("3. ACTIONS:\n${record.actions.joinToString("\n") { "  - $it" }}")
        appendLine("4. OBSERVATIONS:\n${record.observations.joinToString("\n") { "  - $it" }}")
        appendLine("5. RESULT:\n${record.conclusion}")
      }
      LittleCopyButton(
        textToCopy = fullReport,
        label = "Export",
        buttonSize = 32.dp,
        iconSize = 14.dp,
        testTag = "copy_full_5stages_report"
      )
    }

    // 1. GOAL
    StageCard(
      stageNumber = 1,
      stageName = "GOAL",
      subtitle = "What you asked Aragon to accomplish",
      icon = Icons.AutoMirrored.Filled.Assignment,
      content = listOf(record.intent.ifBlank { "Ready for your instructions." })
    )

    // 2. STRATEGY
    StageCard(
      stageNumber = 2,
      stageName = "STRATEGY",
      subtitle = "Decomposed execution plan",
      icon = Icons.Default.FormatListNumbered,
      content = if (record.currentPlan.isNotEmpty()) record.currentPlan else listOf("Strategy generates as soon as a task begins...")
    )

    // 3. ACTION
    StageCard(
      stageNumber = 3,
      stageName = "ACTIONS",
      subtitle = "Dispatched tools and terminal commands",
      icon = Icons.Default.PlayArrow,
      content = if (record.actions.isNotEmpty()) record.actions else listOf("No actions dispatched yet.")
    )

    // 4. OBSERVATION
    StageCard(
      stageNumber = 4,
      stageName = "OBSERVATIONS",
      subtitle = "Live environment feedback and outputs",
      icon = Icons.Default.Visibility,
      content = if (record.observations.isNotEmpty()) record.observations else listOf("Outputs will appear during execution.")
    )

    // 5. CONCLUSION
    StageCard(
      stageNumber = 5,
      stageName = "DELIVERABLE",
      subtitle = "Final synthesized outcome and created files",
      icon = Icons.Default.Check,
      content = listOf(record.conclusion ?: "Awaiting verified task completion...")
    )

    Spacer(modifier = Modifier.height(28.dp))
  }
}

@Composable
private fun StageCard(
  stageNumber: Int,
  stageName: String,
  subtitle: String,
  icon: ImageVector,
  content: List<String>
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, AmoledBorderSubtle, RoundedCornerShape(12.dp)),
    color = AmoledSurfaceElevated
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.weight(1f)
        ) {
          // Flutter squircle badge in monochrome/grey
          Box(
            modifier = Modifier
              .size(28.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(Color(0xFF1C1C1C))
              .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = icon,
              contentDescription = stageName,
              tint = AmoledIconGreyLight,
              modifier = Modifier.size(15.dp)
            )
          }

          Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "$stageNumber. $stageName",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AmoledTextPrimary
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = if (stageNumber in listOf(3, 4)) "AUTHORITATIVE" else "SYNTHESIS",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = AmoledTextMuted,
                fontSize = 9.sp
              )
            }
            Text(
              text = subtitle,
              style = MaterialTheme.typography.labelSmall,
              color = AmoledTextMuted
            )
          }
        }

        LittleCopyButton(
          textToCopy = content.joinToString("\n"),
          testTag = "copy_stage_${stageNumber}",
          buttonSize = 28.dp,
          iconSize = 13.dp
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .background(Color(0xFF0C0C0C))
          .border(1.dp, AmoledBorderSubtle, RoundedCornerShape(8.dp))
          .padding(10.dp)
      ) {
        for ((idx, item) in content.withIndex()) {
          Row(modifier = Modifier.fillMaxWidth()) {
            if (content.size > 1) {
              Text(
                text = "${idx + 1}. ",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = AmoledTextMuted
              )
            }
            Text(
              text = item,
              style = MaterialTheme.typography.bodySmall,
              color = AmoledTextSecondary
            )
          }
        }
      }
    }
  }
}
