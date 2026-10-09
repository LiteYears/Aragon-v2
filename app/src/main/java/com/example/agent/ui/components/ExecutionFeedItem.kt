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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.core.ExecutionStep
import com.example.agent.core.StepType
import com.example.agent.core.ToolStatus
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGrey
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledStatusError
import com.example.ui.theme.AmoledStatusSuccess
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay

/**
 * Execution Feed Item:
 * - Model responses (Reasoning, Streaming, Thoughts, Conclusion) stream normally without heavy boxes.
 * - Tool calls reside in an animated, premium Tool Call Box with distinct states:
 *   WAITING, ACTIVE/RUNNING, FINISHED/SUCCESS, FAILED.
 * - Pure typography without vector icons.
 * - Butter-smooth performance: memoized animations only when active.
 */
@Composable
fun ExecutionFeedItem(
  step: ExecutionStep,
  isLast: Boolean,
  modifier: Modifier = Modifier
) {
  val isModelResponse = step.type == StepType.THINKING ||
    step.type == StepType.PLAN ||
    step.type == StepType.CONCLUSION

  val isToolCall = step.type == StepType.TOOL_EXECUTION ||
    step.type == StepType.TOOL_CALL

  val isObjective = step.type == StepType.TASK_INTENT

  val isActive = !step.isCompleted || step.toolStatus == ToolStatus.RUNNING.name

  Row(
    modifier = modifier
      .fillMaxWidth()
      .testTag("step_item_${step.stepNumber}")
      .padding(horizontal = 16.dp, vertical = if (isModelResponse) 4.dp else 6.dp)
  ) {
    // Left timeline column: Pure text step indicator badge + connecting line
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.width(32.dp)
    ) {
      StepTextBadge(
        stepNumber = step.stepNumber,
        type = step.type,
        toolStatus = step.toolStatus,
        isActive = isActive
      )
      if (!isLast) {
        Box(
          modifier = Modifier
            .width(1.dp)
            .height(if (isModelResponse) 32.dp else 42.dp)
            .background(if (isActive) AmoledActionPrimary.copy(alpha = 0.6f) else AmoledBorderSubtle)
        )
      }
    }

    Spacer(modifier = Modifier.width(10.dp))

    // Content area
    Box(modifier = Modifier.weight(1f)) {
      when {
        isModelResponse -> {
          // Model response: Stream naturally without outer boxes or cards
          ModelResponseView(
            step = step,
            isActive = isActive
          )
        }
        isToolCall -> {
          // Tool calls: Premium styled Tool Call Box with animated states
          ToolCallBoxView(
            step = step,
            isActive = isActive
          )
        }
        isObjective -> {
          // Task objective view
          TaskObjectiveView(step = step)
        }
        else -> {
          // Observation, verification, or generic steps
          ObservationStepView(step = step)
        }
      }
    }
  }
}

/**
 * Clean text badge for step indicator: Pure typography (no icons).
 */
@Composable
fun StepTextBadge(
  stepNumber: Int,
  type: StepType,
  toolStatus: String?,
  isActive: Boolean
) {
  val label = when {
    isActive -> "RUN"
    toolStatus == ToolStatus.FAILED.name || type == StepType.ERROR -> "ERR"
    toolStatus == ToolStatus.SUCCEEDED.name -> "OK"
    type == StepType.TASK_INTENT -> "TASK"
    type == StepType.THINKING || type == StepType.PLAN -> "AI"
    type == StepType.CONCLUSION -> "END"
    type == StepType.VERIFICATION -> "VRF"
    type == StepType.OBSERVATION -> "OBS"
    else -> String.format("%02d", stepNumber)
  }

  val textColor = when {
    isActive -> AmoledActionPrimary
    toolStatus == ToolStatus.FAILED.name || type == StepType.ERROR -> AmoledStatusError
    toolStatus == ToolStatus.SUCCEEDED.name || type == StepType.CONCLUSION -> AmoledStatusSuccess
    else -> AmoledIconGreyLight
  }

  val bgColor = when {
    isActive -> Color(0xFF1F1A12)
    toolStatus == ToolStatus.FAILED.name || type == StepType.ERROR -> Color(0xFF221111)
    toolStatus == ToolStatus.SUCCEEDED.name || type == StepType.CONCLUSION -> Color(0xFF101C12)
    else -> Color(0xFF121212)
  }

  Surface(
    shape = RoundedCornerShape(5.dp),
    color = bgColor,
    border = androidx.compose.foundation.BorderStroke(
      1.dp,
      if (isActive) AmoledActionPrimary.copy(alpha = 0.5f) else AmoledBorderSubtle
    ),
    modifier = Modifier.size(24.dp)
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 8.sp,
        color = textColor
      )
    }
  }
}

/**
 * Model Response View:
 * Shows the Model response streaming normally without any boxes or cards.
 */
@Composable
fun ModelResponseView(
  step: ExecutionStep,
  isActive: Boolean
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
  ) {
    // Discreet header row
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = if (step.type == StepType.CONCLUSION) "ARAGON • CONCLUSION" else "MODEL RESPONSE",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 10.sp,
          letterSpacing = 1.sp,
          color = if (step.type == StepType.CONCLUSION) AmoledStatusSuccess else AmoledActionPrimary
        )

        if (isActive) {
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "streaming...",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledTextMuted
          )
        }
      }

      LittleCopyButton(
        textToCopy = step.content,
        label = "COPY",
        buttonSize = 22.dp,
        testTag = "copy_model_response_${step.stepNumber}"
      )
    }

    Spacer(modifier = Modifier.height(4.dp))

    // Streamed text rendered cleanly without boxes
    StreamingTextContent(
      fullText = step.content,
      isCompleted = step.isCompleted
    )
  }
}

/**
 * Premium Tool Call Box:
 * Beautifully animated when active, finished, failed, or waiting.
 */
@Composable
fun ToolCallBoxView(
  step: ExecutionStep,
  isActive: Boolean
) {
  var isExpanded by remember { mutableStateOf(true) }

  val toolState = when {
    isActive -> "ACTIVE"
    step.toolStatus == ToolStatus.FAILED.name -> "FAILED"
    step.toolStatus == ToolStatus.SUCCEEDED.name -> "FINISHED"
    step.toolStatus == "WAITING" || step.toolStatus == "QUEUED" -> "WAITING"
    else -> if (step.isCompleted) "FINISHED" else "WAITING"
  }

  // Animation only allocated if active to preserve fluid 60fps performance
  val pulseAlpha = if (isActive || toolState == "WAITING") {
    val infiniteTransition = rememberInfiniteTransition(label = "tool_active_pulse")
    val alpha by infiniteTransition.animateFloat(
      initialValue = 0.35f,
      targetValue = 0.95f,
      animationSpec = infiniteRepeatable(
        animation = tween(700, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse
      ),
      label = "pulse_alpha"
    )
    alpha
  } else 1f

  val borderColor = when (toolState) {
    "ACTIVE" -> AmoledActionPrimary.copy(alpha = pulseAlpha)
    "WAITING" -> Color(0xFF886622).copy(alpha = pulseAlpha)
    "FAILED" -> AmoledStatusError.copy(alpha = 0.7f)
    "FINISHED" -> AmoledStatusSuccess.copy(alpha = 0.5f)
    else -> AmoledBorderSubtle
  }

  val boxBgColor = when (toolState) {
    "ACTIVE" -> Color(0xFF14120E)
    "WAITING" -> Color(0xFF141208)
    "FAILED" -> Color(0xFF180A0A)
    "FINISHED" -> Color(0xFF0E1410)
    else -> Color(0xFF111111)
  }

  Surface(
    shape = RoundedCornerShape(10.dp),
    color = boxBgColor,
    border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    modifier = Modifier
      .fillMaxWidth()
      .animateContentSize(animationSpec = tween(180, easing = FastOutSlowInEasing))
      .testTag("tool_call_box_${step.stepNumber}")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(10.dp)
    ) {
      // Header row
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
          // Status pill (Pure text)
          val statusBadgeText = when (toolState) {
            "ACTIVE" -> "RUNNING"
            "WAITING" -> "WAITING"
            "FAILED" -> "FAILED"
            "FINISHED" -> "SUCCESS"
            else -> "TOOL"
          }
          val statusBadgeColor = when (toolState) {
            "ACTIVE" -> AmoledActionPrimary
            "WAITING" -> Color(0xFFCCA033)
            "FAILED" -> AmoledStatusError
            "FINISHED" -> AmoledStatusSuccess
            else -> AmoledIconGreyLight
          }

          Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color(0xFF181818),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle)
          ) {
            Text(
              text = statusBadgeText,
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 9.sp,
              color = statusBadgeColor,
              modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
          }

          Spacer(modifier = Modifier.width(8.dp))

          // Tool name
          Text(
            text = step.toolName ?: step.title,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = AmoledTextPrimary
          )

          // Execution metrics
          if (step.durationMs != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "${(step.durationMs / 1000.0).let { String.format("%.2fs", it) }}",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 9.sp,
              color = AmoledTextMuted,
              modifier = Modifier
                .background(Color(0xFF161616), RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
            )
          }

          if (step.exitCode != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "exit ${step.exitCode}",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 9.sp,
              color = if (step.exitCode == 0) AmoledStatusSuccess else AmoledStatusError,
              modifier = Modifier
                .background(
                  if (step.exitCode == 0) AmoledStatusSuccess.copy(alpha = 0.12f) else AmoledStatusError.copy(alpha = 0.12f),
                  RoundedCornerShape(3.dp)
                )
                .padding(horizontal = 4.dp, vertical = 1.dp)
            )
          }
        }

        // Actions: Copy & Expand toggle (Pure text)
        Row(verticalAlignment = Alignment.CenterVertically) {
          LittleCopyButton(
            textToCopy = buildString {
              append(step.toolName ?: step.title)
              if (step.content.isNotBlank()) append("\n").append(step.content)
              if (!step.stdout.isNullOrBlank()) append("\nSTDOUT:\n").append(step.stdout)
              if (!step.stderr.isNullOrBlank()) append("\nSTDERR:\n").append(step.stderr)
            },
            label = "COPY",
            buttonSize = 22.dp,
            testTag = "copy_tool_${step.stepNumber}"
          )

          Spacer(modifier = Modifier.width(6.dp))

          Text(
            text = if (isExpanded) "[HIDE]" else "[VIEW]",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 9.sp,
            color = AmoledIconGrey,
            modifier = Modifier
              .clickable { isExpanded = !isExpanded }
              .padding(horizontal = 3.dp, vertical = 2.dp)
          )
        }
      }

      // Arguments preview / description
      if (step.content.isNotBlank()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = step.content,
          style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 11.sp,
            lineHeight = 15.sp
          ),
          color = AmoledTextSecondary
        )
      }

      // Collapsible output details
      AnimatedVisibility(visible = isExpanded) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
          if (step.toolCallId != null) {
            Text(
              text = "ID: ${step.toolCallId}",
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
              color = AmoledTextMuted,
              fontSize = 9.sp,
              modifier = Modifier.padding(bottom = 4.dp)
            )
          }

          val stdoutDisplay = when {
            !step.stdout.isNullOrBlank() -> step.stdout
            step.isCompleted && step.exitCode == 0 -> "STDOUT: (completed 0 bytes)"
            else -> null
          }

          if (stdoutDisplay != null) {
            TerminalOutputBox(
              label = "STDOUT",
              text = stdoutDisplay,
              isError = false
            )
          }

          if (!step.stderr.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
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

/**
 * Task Objective View:
 * Renders the user-assigned task goal.
 */
@Composable
fun TaskObjectiveView(step: ExecutionStep) {
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = Color(0xFF141414),
    border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "USER OBJECTIVE",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 9.sp,
          letterSpacing = 1.sp,
          color = AmoledActionPrimary
        )

        LittleCopyButton(
          textToCopy = step.content,
          label = "COPY",
          testTag = "copy_task_objective"
        )
      }

      Spacer(modifier = Modifier.height(4.dp))

      Text(
        text = step.content,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = InterFontFamily,
        color = AmoledTextPrimary,
        lineHeight = 18.sp
      )
    }
  }
}

/**
 * Observation and Verification Step View:
 * Clean, lightweight presentation for sensory data.
 */
@Composable
fun ObservationStepView(step: ExecutionStep) {
  val isError = step.type == StepType.ERROR
  val isVerify = step.type == StepType.VERIFICATION

  Surface(
    shape = RoundedCornerShape(8.dp),
    color = if (isError) Color(0xFF160A0A) else Color(0xFF101010),
    border = androidx.compose.foundation.BorderStroke(
      1.dp,
      if (isError) AmoledStatusError.copy(alpha = 0.5f) else AmoledBorderSubtle
    ),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(9.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = when {
            isError -> "SYSTEM ERROR"
            isVerify -> "EMPIRICAL VERIFICATION"
            else -> "OBSERVATION"
          },
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 9.sp,
          color = when {
            isError -> AmoledStatusError
            isVerify -> AmoledStatusSuccess
            else -> AmoledIconGreyLight
          }
        )

        LittleCopyButton(
          textToCopy = step.content,
          label = "COPY",
          testTag = "copy_obs_${step.stepNumber}"
        )
      }

      Spacer(modifier = Modifier.height(4.dp))

      Text(
        text = step.content,
        style = MaterialTheme.typography.bodySmall.copy(
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 11.sp,
          lineHeight = 15.sp
        ),
        color = if (isError) AmoledStatusError else AmoledTextSecondary
      )
    }
  }
}

/**
 * Typewriter smooth streaming text component.
 * Highly optimized to prevent jank, frame drops, or recomposition thrashing.
 */
@Composable
fun StreamingTextContent(
  fullText: String,
  isCompleted: Boolean,
  modifier: Modifier = Modifier
) {
  var displayedCharCount by remember(fullText, isCompleted) {
    mutableIntStateOf(if (isCompleted || fullText.length < 30) fullText.length else (fullText.length / 3).coerceAtLeast(10))
  }

  LaunchedEffect(fullText, isCompleted) {
    if (isCompleted) {
      displayedCharCount = fullText.length
    } else if (displayedCharCount < fullText.length) {
      val step = ((fullText.length - displayedCharCount) / 6).coerceAtLeast(3)
      while (displayedCharCount < fullText.length) {
        delay(20)
        displayedCharCount = (displayedCharCount + step).coerceAtMost(fullText.length)
      }
    }
  }

  val isStreaming = !isCompleted && displayedCharCount < fullText.length
  val visibleText = fullText.take(displayedCharCount)

  Text(
    text = buildString {
      append(visibleText)
      if (isStreaming) {
        append(" █")
      }
    },
    style = MaterialTheme.typography.bodyMedium,
    fontFamily = InterFontFamily,
    color = AmoledTextPrimary,
    lineHeight = 20.sp,
    modifier = modifier
  )
}

/**
 * Terminal Monospace Output Block:
 * Pure typography with text copy.
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
      .clip(RoundedCornerShape(6.dp))
      .background(Color(0xFF080808))
      .border(
        1.dp,
        if (isError) AmoledStatusError.copy(alpha = 0.4f) else AmoledBorderSubtle,
        RoundedCornerShape(6.dp)
      )
      .padding(8.dp)
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
        fontWeight = FontWeight.Bold,
        fontSize = 8.sp
      )

      LittleCopyButton(
        textToCopy = text,
        label = "COPY",
        tint = AmoledIconGrey,
        testTag = "copy_${label.lowercase()}"
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
