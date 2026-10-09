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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.CheckCircle
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
import com.example.agent.core.FiveStageRecord

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
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Text(
      text = "Authoritative 5-Stage Agentic Architecture",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface
    )
    Text(
      text = "Strict computational separation between model-proposed reasoning (Intent, Plan, Conclusion) and environment-executed truth (Action, Observation).",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // 1. INTENT
    StageCard(
      stageNumber = 1,
      stageName = "INTENT",
      subtitle = "What the user wants (Objective Contract)",
      badgeColor = Color(0xFF3B82F6),
      icon = Icons.AutoMirrored.Filled.Assignment,
      content = listOf(record.intent.ifBlank { "No active task intent." })
    )

    // 2. PLAN
    StageCard(
      stageNumber = 2,
      stageName = "PLAN",
      subtitle = "What the agent intends to do (LLM Decomposition)",
      badgeColor = Color(0xFF8B5CF6),
      icon = Icons.Default.FormatListNumbered,
      content = if (record.currentPlan.isNotEmpty()) record.currentPlan else listOf("Plan is being synthesized...")
    )

    // 3. ACTION
    StageCard(
      stageNumber = 3,
      stageName = "ACTION",
      subtitle = "Authoritative dispatches recorded ONLY by the engine",
      badgeColor = Color(0xFFF59E0B),
      icon = Icons.Default.PlayArrow,
      content = if (record.actions.isNotEmpty()) record.actions else listOf("No actions dispatched yet.")
    )

    // 4. OBSERVATION
    StageCard(
      stageNumber = 4,
      stageName = "OBSERVATION",
      subtitle = "Authoritative outputs returned by the environment",
      badgeColor = Color(0xFF10B981),
      icon = Icons.Default.Visibility,
      content = if (record.observations.isNotEmpty()) record.observations else listOf("No observations recorded yet.")
    )

    // 5. CONCLUSION
    StageCard(
      stageNumber = 5,
      stageName = "CONCLUSION",
      subtitle = "What the agent synthesizes based on verified evidence",
      badgeColor = Color(0xFF06B6D4),
      icon = Icons.Default.CheckCircle,
      content = listOf(record.conclusion ?: "Awaiting verified task completion...")
    )

    Spacer(modifier = Modifier.height(32.dp))
  }
}

@Composable
private fun StageCard(
  stageNumber: Int,
  stageName: String,
  subtitle: String,
  badgeColor: Color,
  icon: ImageVector,
  content: List<String>
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    color = MaterialTheme.colorScheme.surface
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Box(
          modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(badgeColor),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = icon,
            contentDescription = stageName,
            tint = Color.White,
            modifier = Modifier.size(16.dp)
          )
        }

        Column {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = "$stageNumber. $stageName",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = if (stageNumber in listOf(3, 4)) "AUTHORITATIVE TRUTH" else "AGENT SYNTHESIS",
              style = MaterialTheme.typography.labelSmall,
              color = if (stageNumber in listOf(3, 4)) Color(0xFF059669) else Color(0xFF6366F1),
              fontWeight = FontWeight.SemiBold
            )
          }
          Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(6.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
          .padding(10.dp)
      ) {
        for ((idx, item) in content.withIndex()) {
          Row(modifier = Modifier.fillMaxWidth()) {
            if (content.size > 1) {
              Text(
                text = "${idx + 1}. ",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
              )
            }
            Text(
              text = item,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface
            )
          }
        }
      }
    }
  }
}
