package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.core.ExecutionStep
import com.example.agent.core.StepType
import com.example.agent.core.ToolStatus
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGrey
import com.example.ui.theme.AmoledIconGreyDark
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledStatusError
import com.example.ui.theme.AmoledStatusSuccess
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Execution Feed Item:
 * - Badges ([OK], [OBS], [ERR], [RUN], etc.) are positioned on the RIGHT side.
 * - Tool calls & search results boxes are HIDDEN by default, expanding only when [VIEW] is clicked.
 * - Model responses stream smoothly without heavy outer card wraps.
 * - Pure typography without generic icons.
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
      .padding(horizontal = 14.dp, vertical = if (isModelResponse) 4.dp else 5.dp)
  ) {
    // Left timeline column: Minimal step index number + vertical connecting line
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .width(22.dp)
        .padding(top = 3.dp)
    ) {
      Text(
        text = String.format(Locale.US, "%02d", step.stepNumber),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        color = if (isActive) AmoledActionPrimary else AmoledIconGreyDark
      )
      if (!isLast) {
        Spacer(modifier = Modifier.height(3.dp))
        Box(
          modifier = Modifier
            .width(1.dp)
            .height(if (isModelResponse) 30.dp else 40.dp)
            .background(if (isActive) AmoledActionPrimary.copy(alpha = 0.5f) else AmoledBorderSubtle)
        )
      }
    }

    Spacer(modifier = Modifier.width(10.dp))

    // Content area
    Box(modifier = Modifier.weight(1f)) {
      when {
        isModelResponse -> {
          ModelResponseView(
            step = step,
            isActive = isActive
          )
        }
        isToolCall -> {
          ToolCallBoxView(
            step = step,
            isActive = isActive
          )
        }
        isObjective -> {
          TaskObjectiveView(step = step)
        }
        else -> {
          ObservationStepView(step = step)
        }
      }
    }
  }
}

/**
 * Clean text badge for step indicator on the RIGHT: Pure typography (no icons).
 * Displays [OK], [OBS], [RUN], [ERR], [AI], [END], [VRF], [TASK].
 */
@Composable
fun StepTextBadge(
  stepNumber: Int,
  type: StepType,
  toolStatus: String?,
  isActive: Boolean,
  outcome: String? = null
) {
  AragonStepBadge(
    stepNumber = stepNumber,
    type = type,
    toolStatus = toolStatus,
    isActive = isActive,
    outcome = outcome
  )
}

/**
 * Model Response View:
 * Streamed thoughts or conclusions with status badge and copy button on the RIGHT.
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
    // Header row
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = when (step.type) {
            StepType.THINKING -> "THINKING & REASONING"
            StepType.PLAN -> "EXECUTION PLAN"
            StepType.CONCLUSION -> "ARAGON • CONCLUSION"
            else -> "MODEL RESPONSE"
          },
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 10.sp,
          letterSpacing = 1.sp,
          color = when (step.type) {
            StepType.CONCLUSION -> AmoledStatusSuccess
            StepType.THINKING -> AmoledIconGreyLight
            else -> AmoledActionPrimary
          }
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

      // BADGE AND ACTIONS ALIGNED ON THE RIGHT - Consistent 22dp height and styling
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        if (isActive) {
          CreativeAmoledLoadingBadge(
            label = "THINKING",
            height = 22.dp,
            testTag = "model_thinking_badge_${step.stepNumber}"
          )
        } else {
          AragonStepBadge(
            stepNumber = step.stepNumber,
            type = step.type,
            toolStatus = step.toolStatus,
            isActive = false
          )
        }

        AragonFeedCopyButton(
          textToCopy = step.content,
          label = "COPY",
          testTag = "copy_model_response_${step.stepNumber}"
        )
      }
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
 * - Details & search results HIDDEN by default.
 * - Only viewable when user clicks [VIEW].
 * - Status badge [OK], [RUN], etc. on the RIGHT.
 */
@Composable
fun ToolCallBoxView(
  step: ExecutionStep,
  isActive: Boolean
) {
  // HIDDEN BY DEFAULT! ONLY VIEW WHEN USER CLICKS [VIEW]
  var isExpanded by remember { mutableStateOf(false) }

  val toolState = when {
    isActive -> "ACTIVE"
    step.toolStatus == ToolStatus.FAILED.name -> "FAILED"
    step.toolStatus == ToolStatus.SUCCEEDED.name -> "FINISHED"
    step.toolStatus == "WAITING" || step.toolStatus == "QUEUED" -> "WAITING"
    else -> if (step.isCompleted) "FINISHED" else "WAITING"
  }

  // Static state borders (kept calm; no perpetual pulsing animations)
  val borderColor = when (toolState) {
    "ACTIVE" -> AmoledActionPrimary.copy(alpha = 0.85f)
    "WAITING" -> Color(0xFF886622).copy(alpha = 0.7f)
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
      // Header row: Tool name & metrics on LEFT, Status Badge & [VIEW] on RIGHT
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        // LEFT: Tool name, telemetry loading glyph, and execution metrics
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .weight(1f)
            .clickable(
              role = androidx.compose.ui.semantics.Role.Button,
              onClickLabel = if (isExpanded) "Collapse tool details" else "Expand tool details"
            ) { isExpanded = !isExpanded }
        ) {
          // Non-standard creative telemetry indicator for every tool call card
          Box(modifier = Modifier.padding(end = 8.dp)) {
            if (isActive || step.toolStatus == "RUNNING" || step.toolStatus == "ACTIVE" || !step.isCompleted) {
              CreativeAmoledLoadingBadge(
                label = "",
                showLabel = false,
                height = 20.dp,
                accentColor = AmoledActionPrimary,
                testTag = "tool_glyph_loading_${step.stepNumber}"
              )
            } else {
              Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (step.exitCode == 0 || step.toolStatus == ToolStatus.SUCCEEDED.name) Color(0xFF102014) else Color(0xFF161618),
                border = BorderStroke(1.dp, if (step.exitCode == 0 || step.toolStatus == ToolStatus.SUCCEEDED.name) AmoledStatusSuccess.copy(alpha = 0.5f) else AmoledBorderSubtle),
                modifier = Modifier.height(20.dp)
              ) {
                Box(
                  modifier = Modifier.padding(horizontal = 6.dp),
                  contentAlignment = Alignment.Center
                ) {
                  Text(
                    text = if (step.exitCode == 0 || step.toolStatus == ToolStatus.SUCCEEDED.name) "⚡ OK" else "⚡ CALL",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (step.exitCode == 0 || step.toolStatus == ToolStatus.SUCCEEDED.name) AmoledStatusSuccess else AmoledIconGreyLight
                  )
                }
              }
            }
          }

          Column(modifier = Modifier.padding(end = 4.dp)) {
            Text(
              text = formatToolDisplayName(step.toolName ?: step.title),
              style = MaterialTheme.typography.labelMedium,
              fontFamily = InterFontFamily,
              fontWeight = FontWeight.SemiBold,
              fontSize = 12.sp,
              color = AmoledTextPrimary
            )
            val rawName = step.toolName
            if (!rawName.isNullOrBlank() && formatToolDisplayName(rawName) != rawName) {
              Text(
                text = rawName,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 9.sp,
                color = AmoledIconGreyDark
              )
            }
          }

          // Execution metrics
          if (step.durationMs != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "${(step.durationMs / 1000.0).let { String.format(Locale.US, "%.2fs", it) }}",
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

        // RIGHT: Status badge ("OK", "RUN", "ERR", etc.), [VIEW] toggle, and [COPY] button
        // RIGHT: Status badge ("OK", "RUN", "ERR", etc.), [VIEW]/[HIDE] toggle, and [COPY] button
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          // Status badge on the RIGHT with non-standard creative loading badge when active or running
          if (isActive || step.toolStatus == "RUNNING" || step.toolStatus == "ACTIVE" || step.toolStatus == "WAITING" || !step.isCompleted) {
            CreativeAmoledLoadingBadge(
              label = if (step.toolStatus == "WAITING") "WAIT" else "RUN",
              height = 22.dp,
              testTag = "tool_running_badge_${step.stepNumber}"
            )
          } else {
            AragonStepBadge(
              stepNumber = step.stepNumber,
              type = step.type,
              toolStatus = step.toolStatus,
              isActive = false,
              outcome = step.outcome
            )
          }

          // VIEW / HIDE toggle button with consistent 22dp height and tactile feedback
          AragonFeedButton(
            text = if (isExpanded) "HIDE" else "VIEW",
            isActive = isExpanded,
            onClick = { isExpanded = !isExpanded },
            testTag = "toggle_tool_box_${step.stepNumber}"
          )

          AragonFeedCopyButton(
            textToCopy = buildString {
              append(step.toolName ?: step.title)
              if (step.content.isNotBlank()) append("\n").append(step.content)
              if (!step.stdout.isNullOrBlank()) append("\nSTDOUT:\n").append(step.stdout)
              if (!step.stderr.isNullOrBlank()) append("\nSTDERR:\n").append(step.stderr)
            },
            label = "COPY",
            testTag = "copy_tool_${step.stepNumber}"
          )
        }
      }

      // HIDDEN BY DEFAULT: Arguments and search results box only viewable when user clicks [VIEW]
      AnimatedVisibility(visible = isExpanded) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
          // Arguments preview / description
          if (step.content.isNotBlank()) {
            Text(
              text = step.content,
              style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                lineHeight = 15.sp
              ),
              color = AmoledTextSecondary,
              modifier = Modifier.padding(bottom = 6.dp)
            )
          }

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
            val isSearchTool = step.toolName in listOf("web_search", "web_browse", "deep_research", "search")
            TerminalOutputBox(
              label = if (isSearchTool) "SEARCH RESULTS" else "STDOUT",
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
 * Renders the user-assigned task goal with [TASK] badge and [COPY] on the RIGHT.
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

        // BADGE AND ACTIONS ON THE RIGHT
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          AragonStepBadge(
            stepNumber = step.stepNumber,
            type = step.type,
            toolStatus = step.toolStatus,
            isActive = false
          )

          AragonFeedCopyButton(
            textToCopy = step.content,
            label = "COPY",
            testTag = "copy_task_objective"
          )
        }
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
 * Clean presentation with [OBS] / [VRF] / [ERR] badge on the RIGHT,
 * and collapsible detail view for lengthy sensory data.
 */
@Composable
fun ObservationStepView(step: ExecutionStep) {
  val isError = step.type == StepType.ERROR
  val isVerify = step.type == StepType.VERIFICATION
  val isRecovery = step.type == StepType.RECOVERY
  val isEvaluation = step.type == StepType.EVALUATION
  var isExpanded by remember { mutableStateOf(false) }
  val isLongContent = step.content.length > 180 || !step.stdout.isNullOrBlank() || !step.stderr.isNullOrBlank()

  val cardBg = when {
    isError -> Color(0xFF160A0A)
    isRecovery -> Color(0xFF18120C)
    isEvaluation -> Color(0xFF0C141C)
    step.type == StepType.REPLANNING -> Color(0xFF160E18)
    else -> Color(0xFF101010)
  }

  val cardBorder = when {
    isError -> AmoledStatusError.copy(alpha = 0.5f)
    isRecovery -> Color(0xFFFFB74D).copy(alpha = 0.45f)
    isEvaluation -> Color(0xFF64B5F6).copy(alpha = 0.45f)
    step.type == StepType.REPLANNING -> Color(0xFFCE93D8).copy(alpha = 0.45f)
    else -> AmoledBorderSubtle
  }

  Surface(
    shape = RoundedCornerShape(8.dp),
    color = cardBg,
    border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
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
            isRecovery -> "RECOVERY INTERVENTION"
            isEvaluation -> "EVALUATION"
            step.type == StepType.REPLANNING -> "REPLANNING"
            else -> "OBSERVATION"
          },
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 9.sp,
          color = when {
            isError -> AmoledStatusError
            isVerify -> AmoledStatusSuccess
            isRecovery -> Color(0xFFFFB74D)
            isEvaluation -> Color(0xFF64B5F6)
            step.type == StepType.REPLANNING -> Color(0xFFCE93D8)
            else -> AmoledIconGreyLight
          }
        )

        // BADGE AND ACTIONS ON THE RIGHT (Identical 22dp compact buttons)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          AragonStepBadge(
            stepNumber = step.stepNumber,
            type = step.type,
            toolStatus = step.toolStatus,
            isActive = false,
            outcome = step.outcome
          )

          if (isLongContent) {
            // Compact VIEW / HIDE toggle button matching ToolCallBoxView
            AragonFeedButton(
              text = if (isExpanded) "HIDE" else "VIEW",
              isActive = isExpanded,
              onClick = { isExpanded = !isExpanded },
              testTag = "toggle_obs_${step.stepNumber}"
            )
          }

          AragonFeedCopyButton(
            textToCopy = step.content,
            label = "COPY",
            testTag = "copy_obs_${step.stepNumber}"
          )
        }
      }

      Spacer(modifier = Modifier.height(4.dp))

      val textToShow = if (isLongContent && !isExpanded) {
        step.content.take(150) + "..."
      } else if (step.content.length > 5000) {
        step.content.take(5000) + "\n\n... [Content truncated for display (${step.content.length} chars). Use COPY button above for full content] ..."
      } else {
        step.content
      }

      Text(
        text = textToShow,
        style = MaterialTheme.typography.bodySmall.copy(
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 11.sp,
          lineHeight = 15.sp
        ),
        color = if (isError) AmoledStatusError else AmoledTextSecondary
      )

      if (isExpanded) {
        if (!step.stdout.isNullOrBlank()) {
          Spacer(modifier = Modifier.height(4.dp))
          TerminalOutputBox(
            label = "STDOUT",
            text = step.stdout,
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

/**
 * Canonical tool name formatter for clean UI display.
 */
fun formatToolDisplayName(toolName: String?): String {
  if (toolName.isNullOrBlank()) return "Action"
  return when (toolName.lowercase().trim()) {
    "terminal", "bash", "sh", "exec", "run_command" -> "Terminal (Shell)"
    "read_file", "cat", "view_file", "read" -> "Read File"
    "write_file", "write", "create_file" -> "Write File"
    "edit_file" -> "Edit File"
    "list_directory", "ls", "dir" -> "List Directory"
    "delete_file", "rm" -> "Delete File"
    "move_file", "mv" -> "Move File"
    "copy_file", "cp" -> "Copy File"
    "create_directory", "mkdir" -> "Create Directory"
    "delete_directory", "rmdir" -> "Delete Directory"
    "file_exists" -> "File Exists Check"
    "file_metadata" -> "File Metadata"
    "install_package", "apt", "install" -> "Install Package (APT)"
    "web_search", "search" -> "Web Search"
    "web_open" -> "Web Page Visit"
    "web_extract" -> "Web Content Extract"
    "web_crawl" -> "Web Crawler"
    "web_fetch" -> "Web HTTP Request"
    else -> toolName.replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
  }
}

sealed class MarkdownBlock {
  data class Heading(val text: String, val level: Int) : MarkdownBlock()
  data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
  data class BulletItem(val text: String) : MarkdownBlock()
  data class NumberedItem(val number: String, val text: String) : MarkdownBlock()
  data class Blockquote(val text: String) : MarkdownBlock()
  object Divider : MarkdownBlock()
  data class Paragraph(val text: String) : MarkdownBlock()
}

fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
  val lines = text.lines()
  val blocks = mutableListOf<MarkdownBlock>()
  var i = 0

  while (i < lines.size) {
    val line = lines[i]
    val trimmed = line.trim()

    // 1. Fenced Code Block
    if (trimmed.startsWith("```")) {
      val lang = trimmed.removePrefix("```").trim().uppercase().ifBlank { "CODE" }
      val codeLines = mutableListOf<String>()
      i++
      while (i < lines.size && !lines[i].trim().startsWith("```")) {
        codeLines.add(lines[i])
        i++
      }
      if (i < lines.size && lines[i].trim().startsWith("```")) {
        i++
      }
      blocks.add(MarkdownBlock.CodeBlock(language = lang, code = codeLines.joinToString("\n")))
      continue
    }

    // 2. Horizontal Dividers
    if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
      blocks.add(MarkdownBlock.Divider)
      i++
      continue
    }

    // 3. Headings
    if (trimmed.startsWith("# ")) {
      blocks.add(MarkdownBlock.Heading(text = trimmed.removePrefix("# ").trim(), level = 1))
      i++
      continue
    } else if (trimmed.startsWith("## ")) {
      blocks.add(MarkdownBlock.Heading(text = trimmed.removePrefix("## ").trim(), level = 2))
      i++
      continue
    } else if (trimmed.startsWith("### ")) {
      blocks.add(MarkdownBlock.Heading(text = trimmed.removePrefix("### ").trim(), level = 3))
      i++
      continue
    } else if (trimmed.startsWith("#### ")) {
      blocks.add(MarkdownBlock.Heading(text = trimmed.removePrefix("#### ").trim(), level = 4))
      i++
      continue
    }

    // 4. Blockquotes
    if (trimmed.startsWith("> ") || trimmed.startsWith(">")) {
      val bqText = trimmed.removePrefix("> ").removePrefix(">").trim()
      blocks.add(MarkdownBlock.Blockquote(text = bqText))
      i++
      continue
    }

    // 5. Bullet list item
    if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
      blocks.add(MarkdownBlock.BulletItem(text = trimmed.substring(2).trim()))
      i++
      continue
    }

    // 6. Numbered list item
    val numMatch = Regex("^(\\d+[.)])\\s+(.*)").find(trimmed)
    if (numMatch != null) {
      val num = numMatch.groupValues[1]
      val content = numMatch.groupValues[2]
      blocks.add(MarkdownBlock.NumberedItem(number = num, text = content))
      i++
      continue
    }

    // 7. Blank line
    if (trimmed.isBlank()) {
      i++
      continue
    }

    // 8. Regular paragraph
    val paraLines = mutableListOf<String>()
    while (i < lines.size) {
      val curLine = lines[i]
      val curTrimmed = curLine.trim()
      if (curTrimmed.isBlank() ||
        curTrimmed.startsWith("```") ||
        curTrimmed == "---" ||
        curTrimmed == "***" ||
        curTrimmed == "___" ||
        curTrimmed.startsWith("# ") ||
        curTrimmed.startsWith("## ") ||
        curTrimmed.startsWith("### ") ||
        curTrimmed.startsWith("#### ") ||
        curTrimmed.startsWith("> ") ||
        curTrimmed.startsWith(">") ||
        curTrimmed.startsWith("- ") ||
        curTrimmed.startsWith("* ") ||
        curTrimmed.startsWith("+ ") ||
        Regex("^(\\d+[.)])\\s+").containsMatchIn(curTrimmed)
      ) {
        break
      }
      paraLines.add(curLine)
      i++
    }
    if (paraLines.isNotEmpty()) {
      blocks.add(MarkdownBlock.Paragraph(paraLines.joinToString(" ")))
    }
  }

  return blocks
}

fun parseInlineMarkdown(text: String): AnnotatedString {
  return buildAnnotatedString {
    var i = 0
    while (i < text.length) {
      // Bold + Italic: ***text***
      if (text.startsWith("***", i)) {
        val end = text.indexOf("***", i + 3)
        if (end != -1) {
          withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = AmoledTextPrimary)) {
            append(text.substring(i + 3, end))
          }
          i = end + 3
          continue
        }
      }

      // Strikethrough: ~~text~~
      if (text.startsWith("~~", i)) {
        val end = text.indexOf("~~", i + 2)
        if (end != -1) {
          withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = AmoledIconGrey)) {
            append(text.substring(i + 2, end))
          }
          i = end + 2
          continue
        }
      }

      // Bold: **text** or __text__
      val isDoubleStar = text.startsWith("**", i)
      val isDoubleUnder = text.startsWith("__", i)
      if (isDoubleStar || isDoubleUnder) {
        val delim = if (isDoubleStar) "**" else "__"
        val end = text.indexOf(delim, i + 2)
        if (end != -1) {
          withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = AmoledTextPrimary)) {
            append(text.substring(i + 2, end))
          }
          i = end + 2
          continue
        }
      }

      // Inline code: `code`
      if (text[i] == '`') {
        val end = text.indexOf('`', i + 1)
        if (end != -1) {
          withStyle(
            SpanStyle(
              fontFamily = JetBrainsMonoFontFamily,
              background = Color(0xFF1E1E1E),
              color = AmoledActionPrimary,
              fontSize = 11.sp
            )
          ) {
            append(text.substring(i + 1, end))
          }
          i = end + 1
          continue
        }
      }

      // Italic: *text* or _text_ (word boundary check)
      if ((text[i] == '*' || text[i] == '_') && (i == 0 || text[i - 1].isWhitespace()) && i + 1 < text.length && !text[i + 1].isWhitespace()) {
        val targetChar = text[i]
        val end = text.indexOf(targetChar, i + 1)
        if (end != -1 && end > i + 1) {
          withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = AmoledTextSecondary)) {
            append(text.substring(i + 1, end))
          }
          i = end + 1
          continue
        }
      }

      append(text[i])
      i++
    }
  }
}

/**
 * Typewriter smooth streaming text component with rich Markdown rendering.
 * Supports headings, fenced code blocks with language pills & copy buttons,
 * formatted bullet/numbered lists, and inline monospace code.
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
  val blocks = remember(visibleText) { parseMarkdownBlocks(visibleText) }

  if (blocks.isEmpty()) {
    if (isStreaming) {
      Text(
        text = "█",
        style = MaterialTheme.typography.bodyMedium,
        color = AmoledActionPrimary,
        modifier = modifier
      )
    }
    return
  }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    blocks.forEachIndexed { idx, block ->
      val isLastBlock = idx == blocks.size - 1

      when (block) {
        is MarkdownBlock.Heading -> {
          val headingColor = when (block.level) {
            1 -> AmoledActionPrimary
            2 -> AmoledTextPrimary
            else -> AmoledIconGreyLight
          }
          val headingSize = when (block.level) {
            1 -> 15.sp
            2 -> 13.5.sp
            else -> 12.sp
          }
          Column(modifier = Modifier.padding(top = if (idx > 0) 6.dp else 0.dp)) {
            Text(
              text = parseInlineMarkdown(block.text),
              style = MaterialTheme.typography.titleSmall,
              fontFamily = InterFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = headingSize,
              color = headingColor
            )
            if (block.level == 1) {
              Box(
                modifier = Modifier
                  .padding(top = 3.dp)
                  .width(28.dp)
                  .height(1.5.dp)
                  .background(AmoledActionPrimary.copy(alpha = 0.5f))
              )
            }
          }
        }

        is MarkdownBlock.Blockquote -> {
          Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color(0xFF111111),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Box(
                modifier = Modifier
                  .width(2.dp)
                  .height(16.dp)
                  .background(AmoledActionPrimary.copy(alpha = 0.6f))
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = parseInlineMarkdown(block.text),
                style = MaterialTheme.typography.bodySmall.copy(
                  fontStyle = FontStyle.Italic,
                  color = AmoledTextSecondary,
                  lineHeight = 17.sp,
                  fontSize = 11.5.sp
                )
              )
            }
          }
        }

        is MarkdownBlock.Divider -> {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 4.dp)
              .height(1.dp)
              .background(AmoledBorderSubtle)
          )
        }

        is MarkdownBlock.CodeBlock -> {
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF0C0C0C),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
          ) {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = block.language,
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 8.sp,
                  fontWeight = FontWeight.Bold,
                  color = AmoledActionPrimary,
                  modifier = Modifier
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
                )

                AragonFeedCopyButton(
                  textToCopy = block.code,
                  label = "COPY",
                  tint = AmoledIconGrey,
                  testTag = "copy_code_block"
                )
              }

              Spacer(modifier = Modifier.height(4.dp))

              val codeScroll = rememberScrollState()
              Box(modifier = Modifier.fillMaxWidth().horizontalScroll(codeScroll)) {
                Text(
                  text = block.code,
                  style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                  ),
                  color = AmoledTextPrimary
                )
              }
            }
          }
        }

        is MarkdownBlock.BulletItem -> {
          Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            verticalAlignment = Alignment.Top
          ) {
            Text(
              text = "•",
              color = AmoledActionPrimary,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(end = 6.dp)
            )
            Text(
              text = parseInlineMarkdown(block.text),
              style = MaterialTheme.typography.bodyMedium,
              fontFamily = InterFontFamily,
              color = AmoledTextPrimary,
              fontSize = 12.sp,
              lineHeight = 18.sp
            )
          }
        }

        is MarkdownBlock.NumberedItem -> {
          Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            verticalAlignment = Alignment.Top
          ) {
            Text(
              text = block.number,
              color = AmoledActionPrimary,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(end = 6.dp, top = 2.dp)
            )
            Text(
              text = parseInlineMarkdown(block.text),
              style = MaterialTheme.typography.bodyMedium,
              fontFamily = InterFontFamily,
              color = AmoledTextPrimary,
              fontSize = 12.sp,
              lineHeight = 18.sp
            )
          }
        }

        is MarkdownBlock.Paragraph -> {
          Text(
            text = parseInlineMarkdown(block.text + if (isLastBlock && isStreaming) " █" else ""),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = InterFontFamily,
            color = AmoledTextPrimary,
            lineHeight = 20.sp
          )
        }
      }
    }

    if (isStreaming && blocks.lastOrNull() !is MarkdownBlock.Paragraph) {
      Text(
        text = "█",
        style = MaterialTheme.typography.bodyMedium,
        color = AmoledActionPrimary
      )
    }
  }
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

      AragonFeedCopyButton(
        textToCopy = text,
        label = "COPY",
        tint = AmoledIconGrey,
        testTag = "copy_${label.lowercase().replace(' ', '_')}"
      )
    }

    Spacer(modifier = Modifier.height(3.dp))

    val displayText = remember(text) {
      if (text.length > 5000) {
        text.take(5000) + "\n\n... [Remaining text truncated in view (${text.length} total chars). Use COPY button above for full content] ..."
      } else {
        text
      }
    }

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(scrollState)
    ) {
      Text(
        text = displayText,
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
