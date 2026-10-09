package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.core.ExecutionStep
import com.example.agent.core.StepType
import com.example.agent.core.ToolStatus
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGrey
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledStatusError
import com.example.ui.theme.AmoledStatusSuccess
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledSurfaceVariant
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay

@Composable
fun ExecutionFeedItem(
  step: ExecutionStep,
  isLast: Boolean,
  modifier: Modifier = Modifier
) {
  var isExpanded by remember {
    mutableStateOf(
      step.type == StepType.TOOL_EXECUTION ||
        step.type == StepType.ERROR ||
        step.type == StepType.CONCLUSION
    )
  }

  // Active step breathing pulse for tool running or in-progress reasoning
  val isActive = !step.isCompleted || step.toolStatus == ToolStatus.RUNNING.name
  val infiniteTransition = rememberInfiniteTransition(label = "step_active_pulse")
  val pulseAlpha by infiniteTransition.animateFloat(
    initialValue = 0.25f,
    targetValue = 0.85f,
    animationSpec = infiniteRepeatable(
      animation = tween(800, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "pulse_alpha"
  )

  // Aggregate step text for easy copying
  val stepFullText = buildString {
    append(step.title)
    if (step.content.isNotBlank()) {
      append("\n\n").append(step.content)
    }
    if (!step.stdout.isNullOrBlank()) {
      append("\n\n[STDOUT]\n").append(step.stdout)
    }
    if (!step.stderr.isNullOrBlank()) {
      append("\n\n[STDERR]\n").append(step.stderr)
    }
  }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .testTag("step_item_${step.stepNumber}")
      .padding(horizontal = 16.dp, vertical = 5.dp)
  ) {
    // Left timeline column with squircle badge and connecting line
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.width(30.dp)
    ) {
      StepIconBadge(type = step.type, toolStatus = step.toolStatus)
      if (!isLast) {
        Box(
          modifier = Modifier
            .width(1.dp)
            .height(38.dp)
            .background(if (isActive) Color.White.copy(alpha = pulseAlpha * 0.5f) else AmoledBorderSubtle)
        )
      }
    }

    Spacer(modifier = Modifier.width(10.dp))

    // Right card content: Flat modern luxury AMOLED card with active breathing border
    val cardBorderColor = when {
      isActive -> Color.White.copy(alpha = pulseAlpha)
      step.type == StepType.ERROR || step.toolStatus == ToolStatus.FAILED.name -> AmoledStatusError.copy(alpha = 0.6f)
      step.type == StepType.CONCLUSION -> AmoledBorder
      else -> AmoledBorderSubtle
    }

    Surface(
      modifier = Modifier
        .weight(1f)
        .clip(RoundedCornerShape(12.dp))
        .border(
          width = 1.dp,
          color = cardBorderColor,
          shape = RoundedCornerShape(12.dp)
        )
        .animateContentSize(animationSpec = tween(220, easing = FastOutSlowInEasing)),
      color = when (step.type) {
        StepType.TOOL_EXECUTION -> AmoledSurfaceElevated
        StepType.CONCLUSION -> AmoledSurfaceVariant
        StepType.ERROR -> Color(0xFF160A0A)
        else -> Color(0xFF0F0F0F)
      }
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(12.dp)
      ) {
        // Top header row: Category pill, Title, Meta metrics, Copy button, Expand/collapse
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .weight(1f)
              .clickable { isExpanded = !isExpanded }
          ) {
            // Category tag pill
            val categoryLabel = when (step.type) {
              StepType.TASK_INTENT -> "OBJECTIVE"
              StepType.THINKING, StepType.PLAN -> "REASONING"
              StepType.TOOL_CALL -> "DISPATCH"
              StepType.TOOL_EXECUTION -> "TOOL CALL"
              StepType.OBSERVATION -> "OBSERVATION"
              StepType.VERIFICATION -> "VERIFY"
              StepType.CONCLUSION -> "AGENT"
              StepType.ERROR -> "ERROR"
            }

            Surface(
              shape = RoundedCornerShape(6.dp),
              color = Color(0xFF1E1E1E),
              border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
              modifier = Modifier.padding(end = 8.dp)
            ) {
              Text(
                text = categoryLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 9.sp,
                color = when (step.type) {
                  StepType.ERROR -> AmoledStatusError
                  StepType.CONCLUSION -> AmoledTextPrimary
                  else -> AmoledIconGreyLight
                },
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }

            Text(
              text = step.title,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Medium,
              color = when (step.type) {
                StepType.ERROR -> AmoledStatusError
                else -> AmoledTextPrimary
              }
            )

            // Duration badge for tools
            if (step.durationMs != null) {
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "${(step.durationMs / 1000.0).let { String.format("%.2fs", it) }}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                color = AmoledTextMuted,
                modifier = Modifier
                  .background(Color(0xFF161616), RoundedCornerShape(4.dp))
                  .padding(horizontal = 5.dp, vertical = 1.dp)
              )
            }

            // Exit code badge
            if (step.exitCode != null) {
              Spacer(modifier = Modifier.width(5.dp))
              Text(
                text = "exit ${step.exitCode}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.sp,
                color = if (step.exitCode == 0) AmoledStatusSuccess else AmoledStatusError,
                modifier = Modifier
                  .background(
                    if (step.exitCode == 0) AmoledStatusSuccess.copy(alpha = 0.12f) else AmoledStatusError.copy(alpha = 0.12f),
                    RoundedCornerShape(4.dp)
                  )
                  .padding(horizontal = 5.dp, vertical = 1.dp)
              )
            }
          }

          Row(verticalAlignment = Alignment.CenterVertically) {
            LittleCopyButton(
              textToCopy = stepFullText,
              testTag = "copy_step_${step.stepNumber}",
              buttonSize = 26.dp,
              iconSize = 13.dp
            )

            Spacer(modifier = Modifier.width(2.dp))

            Surface(
              modifier = Modifier.clickable { isExpanded = !isExpanded },
              color = Color.Transparent
            ) {
              Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = AmoledIconGrey,
                modifier = Modifier.size(18.dp)
              )
            }
          }
        }

        // Summary content / streaming presentation
        if (step.content.isNotBlank()) {
          Spacer(modifier = Modifier.height(6.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
          ) {
            // If it's a conclusion or reasoning, render with ChatGPT-style streaming presentation
            if (step.type == StepType.CONCLUSION || step.type == StepType.THINKING) {
              StreamingTextContent(
                fullText = step.content,
                isCompleted = step.isCompleted,
                modifier = Modifier.weight(1f)
              )
            } else {
              Text(
                text = step.content,
                style = MaterialTheme.typography.bodySmall,
                color = when (step.type) {
                  StepType.ERROR -> AmoledStatusError
                  else -> AmoledTextSecondary
                },
                modifier = Modifier.weight(1f)
              )
            }

            if (step.type == StepType.CONCLUSION || step.type == StepType.ERROR) {
              Spacer(modifier = Modifier.width(4.dp))
              LittleCopyButton(
                textToCopy = step.content,
                label = if (step.type == StepType.CONCLUSION) "Copy" else "Copy Error",
                testTag = "copy_content_${step.stepNumber}"
              )
            }
          }
        }

        // Expanded metadata (Tool name and Call ID)
        AnimatedVisibility(visible = isExpanded && step.toolCallId != null) {
          Column(modifier = Modifier.padding(top = 6.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "ID: ${step.toolCallId}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                color = AmoledTextMuted,
                fontSize = 10.sp
              )
              LittleCopyButton(
                textToCopy = step.toolCallId ?: "",
                label = "Copy ID",
                buttonSize = 20.dp,
                iconSize = 11.dp,
                testTag = "copy_call_id_${step.stepNumber}"
              )
            }
          }
        }

        // Terminal / Raw output block
        AnimatedVisibility(
          visible = isExpanded && (step.type == StepType.TOOL_EXECUTION || !step.stdout.isNullOrBlank() || !step.stderr.isNullOrBlank())
        ) {
          Column(modifier = Modifier.padding(top = 8.dp)) {
            val stdoutDisplay = when {
              !step.stdout.isNullOrBlank() -> step.stdout
              step.type == StepType.TOOL_EXECUTION && step.isCompleted -> "STDOUT: (completed 0 bytes)"
              else -> null
            }
            if (stdoutDisplay != null) {
              TerminalOutputBox(
                label = "OUTPUT",
                text = stdoutDisplay,
                isError = false
              )
            }
            if (!step.stderr.isNullOrBlank()) {
              Spacer(modifier = Modifier.height(6.dp))
              TerminalOutputBox(
                label = "STDERR",
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

/**
 * ChatGPT-style smooth streaming typewriter text component with an animated pulsing cursor.
 */
@Composable
fun StreamingTextContent(
  fullText: String,
  isCompleted: Boolean,
  modifier: Modifier = Modifier
) {
  var displayedCharCount by remember(fullText, isCompleted) {
    mutableIntStateOf(if (isCompleted || fullText.length < 30) fullText.length else (fullText.length / 4).coerceAtLeast(8))
  }

  // Typewriter progressive reveal
  LaunchedEffect(fullText, isCompleted) {
    if (isCompleted) {
      displayedCharCount = fullText.length
    } else if (displayedCharCount < fullText.length) {
      val step = ((fullText.length - displayedCharCount) / 8).coerceAtLeast(2)
      while (displayedCharCount < fullText.length) {
        delay(16)
        displayedCharCount = (displayedCharCount + step).coerceAtMost(fullText.length)
      }
    }
  }

  // Cursor blink transition
  val infiniteTransition = rememberInfiniteTransition(label = "cursor_pulse")
  val cursorAlpha by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 0.15f,
    animationSpec = infiniteRepeatable(
      animation = tween(400, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "cursor_blink"
  )

  val visibleText = fullText.take(displayedCharCount)
  val isStreaming = !isCompleted && displayedCharCount < fullText.length

  Text(
    text = buildString {
      append(visibleText)
      if (isStreaming) {
        append(" █")
      }
    },
    style = MaterialTheme.typography.bodySmall,
    color = AmoledTextPrimary,
    lineHeight = 19.sp,
    modifier = modifier
  )
}

/**
 * Modern Flutter squircle icon badge using neutral grey icons and AMOLED background.
 */
@Composable
fun StepIconBadge(type: StepType, toolStatus: String?) {
  val icon: ImageVector = when (type) {
    StepType.TASK_INTENT -> Icons.Default.PlayArrow
    StepType.PLAN, StepType.THINKING -> Icons.Default.Lightbulb
    StepType.TOOL_CALL -> Icons.Default.Code
    StepType.TOOL_EXECUTION -> {
      if (toolStatus == ToolStatus.RUNNING.name) {
        // Return custom running spinner inside squircle
        Box(
          modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF141414))
            .border(1.dp, AmoledBorder, RoundedCornerShape(7.dp)),
          contentAlignment = Alignment.Center
        ) {
          CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            strokeWidth = 1.5.dp,
            color = AmoledIconGreyLight
          )
        }
        return
      } else if (toolStatus == ToolStatus.FAILED.name) {
        Icons.Default.Close
      } else {
        Icons.Default.Check
      }
    }
    StepType.OBSERVATION -> Icons.Default.Info
    StepType.VERIFICATION -> Icons.Default.Search
    StepType.CONCLUSION -> Icons.Default.Done
    StepType.ERROR -> Icons.Default.Close
  }

  Box(
    modifier = Modifier
      .size(24.dp)
      .clip(RoundedCornerShape(7.dp))
      .background(Color(0xFF141414))
      .border(
        width = 1.dp,
        color = if (type == StepType.ERROR || toolStatus == ToolStatus.FAILED.name) AmoledStatusError.copy(alpha = 0.5f) else AmoledBorderSubtle,
        shape = RoundedCornerShape(7.dp)
      ),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      imageVector = icon,
      contentDescription = type.name,
      tint = when {
        type == StepType.ERROR || toolStatus == ToolStatus.FAILED.name -> AmoledStatusError
        type == StepType.CONCLUSION -> AmoledTextPrimary
        else -> AmoledIconGrey
      },
      modifier = Modifier.size(13.dp)
    )
  }
}

/**
 * Professional AMOLED dark terminal block with JetBrains Mono monospace font.
 */
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
      .background(Color(0xFF080808))
      .border(
        1.dp,
        if (isError) AmoledStatusError.copy(alpha = 0.4f) else AmoledBorderSubtle,
        RoundedCornerShape(8.dp)
      )
      .padding(9.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFontFamily,
        color = if (isError) AmoledStatusError else AmoledTextMuted,
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.sp
      )

      LittleCopyButton(
        textToCopy = text,
        label = "Copy",
        tint = AmoledIconGrey,
        buttonSize = 20.dp,
        iconSize = 11.dp,
        testTag = "copy_${label.lowercase().replace('/', '_').replace(' ', '_')}"
      )
    }

    Spacer(modifier = Modifier.height(3.dp))
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(scrollState)
    ) {
      Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 11.sp,
          lineHeight = 15.sp
        ),
        color = if (isError) AmoledStatusError else AmoledIconGreyLight
      )
    }
  }
}
