package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.core.ExecutionStep
import com.example.agent.core.StepType
import com.example.agent.core.ToolStatus

@Composable
fun ExecutionFeedItem(
  step: ExecutionStep,
  isLast: Boolean,
  modifier: Modifier = Modifier
) {
  var isExpanded by remember { mutableStateOf(step.type == StepType.TOOL_EXECUTION || step.type == StepType.ERROR) }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .testTag("step_item_${step.stepNumber}")
      .padding(horizontal = 14.dp, vertical = 5.dp)
  ) {
    // Left timeline column with node icon and connecting line
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.width(32.dp)
    ) {
      StepIconBadge(type = step.type, toolStatus = step.toolStatus)
      if (!isLast) {
        Box(
          modifier = Modifier
            .width(2.dp)
            .height(42.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        )
      }
    }

    Spacer(modifier = Modifier.width(10.dp))

    // Right card content
    Surface(
      modifier = Modifier
        .weight(1f)
        .clip(RoundedCornerShape(10.dp))
        .border(
          width = 1.dp,
          color = when (step.type) {
            StepType.TOOL_EXECUTION -> if (step.toolStatus == ToolStatus.FAILED.name) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
            StepType.VERIFICATION -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            StepType.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
          },
          shape = RoundedCornerShape(10.dp)
        ),
      color = when (step.type) {
        StepType.TOOL_EXECUTION -> MaterialTheme.colorScheme.surface
        StepType.OBSERVATION -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        StepType.VERIFICATION -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        StepType.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surface
      }
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(12.dp)
      ) {
        // Header row
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
          ) {
            Text(
              text = step.title,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              color = when (step.type) {
                StepType.ERROR -> MaterialTheme.colorScheme.error
                StepType.VERIFICATION -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
              }
            )

            // Duration badge for tools
            if (step.durationMs != null) {
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "${(step.durationMs / 1000.0).let { String.format("%.2fs", it) }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                  .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }

            // Exit code badge
            if (step.exitCode != null) {
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "exit ${step.exitCode}",
                style = MaterialTheme.typography.labelSmall,
                color = if (step.exitCode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier
                  .background(
                    if (step.exitCode == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    RoundedCornerShape(4.dp)
                  )
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }

          Icon(
            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
          )
        }

        // Summary content
        if (step.content.isNotBlank()) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = step.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        // Expanded metadata (Tool name and Call ID)
        AnimatedVisibility(visible = isExpanded && step.toolCallId != null) {
          Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
              text = "Call ID: ${step.toolCallId}",
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
              color = MaterialTheme.colorScheme.outline,
              fontSize = 10.sp
            )
          }
        }

        // Terminal / Raw output block
        AnimatedVisibility(visible = isExpanded && (step.type == StepType.TOOL_EXECUTION || !step.stdout.isNullOrBlank() || !step.stderr.isNullOrBlank())) {
          Column(modifier = Modifier.padding(top = 8.dp)) {
            val stdoutDisplay = when {
              !step.stdout.isNullOrBlank() -> step.stdout
              step.type == StepType.TOOL_EXECUTION && step.isCompleted -> "STDOUT: empty (0 bytes)"
              else -> null
            }
            if (stdoutDisplay != null) {
              TerminalOutputBox(
                label = "STDOUT / OUTPUT",
                text = stdoutDisplay,
                isError = false
              )
            }
            if (!step.stderr.isNullOrBlank()) {
              Spacer(modifier = Modifier.height(6.dp))
              TerminalOutputBox(
                label = "STDERR / ERROR",
                text = step.stderr,
                isError = true
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun StepIconBadge(type: StepType, toolStatus: String?) {
  val icon: ImageVector
  val bgColor: Color
  val tintColor: Color

  when (type) {
    StepType.TASK_INTENT -> {
      icon = Icons.Default.PlayArrow
      bgColor = MaterialTheme.colorScheme.primaryContainer
      tintColor = MaterialTheme.colorScheme.onPrimaryContainer
    }
    StepType.PLAN, StepType.THINKING -> {
      icon = Icons.Default.Lightbulb
      bgColor = MaterialTheme.colorScheme.secondaryContainer
      tintColor = MaterialTheme.colorScheme.onSecondaryContainer
    }
    StepType.TOOL_CALL -> {
      icon = Icons.Default.Code
      bgColor = MaterialTheme.colorScheme.tertiaryContainer
      tintColor = MaterialTheme.colorScheme.onTertiaryContainer
    }
    StepType.TOOL_EXECUTION -> {
      when (toolStatus) {
        ToolStatus.RUNNING.name -> {
          Box(
            modifier = Modifier
              .size(24.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.primary
            )
          }
          return
        }
        ToolStatus.FAILED.name -> {
          icon = Icons.Default.Close
          bgColor = MaterialTheme.colorScheme.errorContainer
          tintColor = MaterialTheme.colorScheme.onErrorContainer
        }
        else -> {
          icon = Icons.Default.Check
          bgColor = MaterialTheme.colorScheme.primaryContainer
          tintColor = MaterialTheme.colorScheme.onPrimaryContainer
        }
      }
    }
    StepType.OBSERVATION -> {
      icon = Icons.Default.Info
      bgColor = MaterialTheme.colorScheme.surfaceVariant
      tintColor = MaterialTheme.colorScheme.onSurfaceVariant
    }
    StepType.VERIFICATION -> {
      icon = Icons.Default.Search
      bgColor = MaterialTheme.colorScheme.primaryContainer
      tintColor = MaterialTheme.colorScheme.primary
    }
    StepType.CONCLUSION -> {
      icon = Icons.Default.Verified
      bgColor = MaterialTheme.colorScheme.primary
      tintColor = MaterialTheme.colorScheme.onPrimary
    }
    StepType.ERROR -> {
      icon = Icons.Default.Close
      bgColor = MaterialTheme.colorScheme.errorContainer
      tintColor = MaterialTheme.colorScheme.error
    }
  }

  Box(
    modifier = Modifier
      .size(24.dp)
      .clip(CircleShape)
      .background(bgColor),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      imageVector = icon,
      contentDescription = type.name,
      tint = tintColor,
      modifier = Modifier.size(14.dp)
    )
  }
}

@Composable
fun TerminalOutputBox(
  label: String,
  text: String,
  isError: Boolean
) {
  val scrollState = rememberScrollState()

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(Color(0xFF0F172A)) // Sleek dark slate terminal
      .border(1.dp, if (isError) Color(0xFF7F1D1D) else Color(0xFF334155), RoundedCornerShape(8.dp))
      .padding(10.dp)
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = if (isError) Color(0xFFF87171) else Color(0xFF94A3B8),
      fontWeight = FontWeight.Bold,
      fontSize = 10.sp
    )
    Spacer(modifier = Modifier.height(4.dp))
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(scrollState)
    ) {
      Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          lineHeight = 15.sp
        ),
        color = if (isError) Color(0xFFFCA5A5) else Color(0xFFE2E8F0)
      )
    }
  }
}
