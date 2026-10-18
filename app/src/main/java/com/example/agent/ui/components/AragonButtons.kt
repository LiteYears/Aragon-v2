package com.example.agent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.core.StepType
import com.example.agent.core.ToolStatus
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledActionPrimaryOn
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
import com.example.ui.theme.JetBrainsMonoFontFamily
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * Reusable tactile spring micro-interaction modifier.
 * Scales down smoothly on touch press and springs back elastically on release.
 */
@Composable
fun Modifier.tactilePress(
  interactionSource: MutableInteractionSource,
  pressedScale: Float = 0.96f
): Modifier {
  val isPressed by interactionSource.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (isPressed) pressedScale else 1.0f,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioNoBouncy,
      stiffness = Spring.StiffnessHigh
    ),
    label = "tactile_press_scale"
  )
  return this.graphicsLayer {
    scaleX = scale
    scaleY = scale
  }
}


/**
 * Unified button variant system for Aragon.
 * Enforces visual hierarchy, tactile feedback, and strict design token consistency across all screens.
 */
enum class AragonButtonVariant {
  DEFAULT,
  PRIMARY,
  SUCCESS,
  DANGER,
  MUTED,
  GHOST
}

/**
 * Canonical Feed Action Button.
 * Used for all inline step actions in the execution feed (e.g. VIEW / HIDE toggle).
 * Perfectly matches AragonStepBadge and AragonFeedCopyButton in height (22.dp),
 * corner radius (4.dp), border stroke (1.dp), and typography baseline (9.sp).
 */
@Composable
fun AragonFeedButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  variant: AragonButtonVariant = AragonButtonVariant.DEFAULT,
  isActive: Boolean = false,
  enabled: Boolean = true,
  testTag: String = "feed_button"
) {
  val shape = RoundedCornerShape(4.dp)
  val interactionSource = remember { MutableInteractionSource() }

  val targetBg = when {
    !enabled -> Color(0xFF101010)
    isActive -> Color(0xFF221A10)
    variant == AragonButtonVariant.PRIMARY -> Color(0xFF1C1C1C)
    variant == AragonButtonVariant.SUCCESS -> Color(0xFF102014)
    variant == AragonButtonVariant.DANGER -> Color(0xFF221111)
    variant == AragonButtonVariant.MUTED -> Color(0xFF0F0F0F)
    variant == AragonButtonVariant.GHOST -> Color.Transparent
    else -> Color(0xFF141414)
  }

  val targetBorder = when {
    !enabled -> AmoledBorderSubtle
    isActive -> AmoledActionPrimary.copy(alpha = 0.6f)
    variant == AragonButtonVariant.PRIMARY -> AmoledActionPrimary.copy(alpha = 0.5f)
    variant == AragonButtonVariant.SUCCESS -> AmoledStatusSuccess.copy(alpha = 0.5f)
    variant == AragonButtonVariant.DANGER -> AmoledStatusError.copy(alpha = 0.5f)
    variant == AragonButtonVariant.MUTED -> AmoledBorderSubtle
    variant == AragonButtonVariant.GHOST -> Color.Transparent
    else -> AmoledBorderSubtle
  }

  val targetText = when {
    !enabled -> AmoledIconGreyDark
    isActive -> AmoledActionPrimary
    variant == AragonButtonVariant.PRIMARY -> AmoledActionPrimary
    variant == AragonButtonVariant.SUCCESS -> AmoledStatusSuccess
    variant == AragonButtonVariant.DANGER -> AmoledStatusError
    variant == AragonButtonVariant.MUTED -> AmoledIconGrey
    else -> AmoledIconGreyLight
  }

  val bgAnimated by animateColorAsState(targetValue = targetBg, animationSpec = tween(120), label = "feed_btn_bg")
  val borderAnimated by animateColorAsState(targetValue = targetBorder, animationSpec = tween(120), label = "feed_btn_border")
  val textAnimated by animateColorAsState(targetValue = targetText, animationSpec = tween(120), label = "feed_btn_text")

  Box(
    modifier = Modifier.minimumInteractiveComponentSize(),
    contentAlignment = Alignment.Center
  ) {
    Surface(
      shape = shape,
      color = bgAnimated,
      border = BorderStroke(1.dp, borderAnimated),
      modifier = modifier
        .tactilePress(interactionSource)
        .clip(shape)
        .clickable(
          interactionSource = interactionSource,
          indication = ripple(color = AmoledActionPrimary.copy(alpha = 0.25f)),
          enabled = enabled,
          role = Role.Button,
          onClick = onClick
        )
        .testTag(testTag)
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .height(24.dp)
          .padding(horizontal = 7.dp)
      ) {
        Text(
          text = text,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = if (isActive || variant == AragonButtonVariant.PRIMARY) FontWeight.Bold else FontWeight.Medium,
          fontSize = 9.sp,
          letterSpacing = 0.5.sp,
          color = textAnimated,
          maxLines = 1
        )
      }
    }
  }
}

/**
 * Canonical Copy Button for Feed & Detail Rows.
 * Matches AragonFeedButton and AragonStepBadge line-for-line.
 * Transitions smoothly to "COPIED" with emerald green status feedback.
 * Includes transaction-safe clipping to prevent Android IPC binder crashes.
 */
@Composable
fun AragonFeedCopyButton(
  textToCopy: String = "",
  textProvider: (() -> String)? = null,
  modifier: Modifier = Modifier,
  label: String? = null,
  tint: Color? = null,
  testTag: String = "feed_copy_button"
) {
  val clipboardManager = LocalClipboardManager.current
  var hasCopied by remember { mutableStateOf(false) }

  LaunchedEffect(hasCopied) {
    if (hasCopied) {
      delay(1500)
      hasCopied = false
    }
  }

  val displayLabel = if (hasCopied) "COPIED" else (label ?: "COPY")
  val shape = RoundedCornerShape(4.dp)
  val interactionSource = remember { MutableInteractionSource() }

  val targetBg = if (hasCopied) Color(0xFF102416) else Color(0xFF141414)
  val targetBorder = if (hasCopied) AmoledStatusSuccess.copy(alpha = 0.6f) else AmoledBorderSubtle
  val targetColor = if (hasCopied) AmoledStatusSuccess else (tint ?: AmoledIconGreyLight)

  val bgAnimated by animateColorAsState(targetValue = targetBg, animationSpec = tween(140), label = "copy_bg")
  val borderAnimated by animateColorAsState(targetValue = targetBorder, animationSpec = tween(140), label = "copy_border")
  val colorAnimated by animateColorAsState(targetValue = targetColor, animationSpec = tween(140), label = "copy_color")

  Box(
    modifier = Modifier.minimumInteractiveComponentSize(),
    contentAlignment = Alignment.Center
  ) {
    Surface(
      shape = shape,
      color = bgAnimated,
      border = BorderStroke(1.dp, borderAnimated),
      modifier = modifier
        .tactilePress(interactionSource)
        .clip(shape)
        .clickable(
          interactionSource = interactionSource,
          indication = ripple(color = AmoledStatusSuccess.copy(alpha = 0.3f)),
          role = Role.Button,
          onClickLabel = "Copy text"
        ) {
          try {
            val stringToCopy = textProvider?.invoke() ?: textToCopy
            if (stringToCopy.isNotBlank()) {
              val safeContent = if (stringToCopy.length > 250_000) {
                stringToCopy.take(250_000) + "\n\n... [TRUNCATED FOR CLIPBOARD SIZE LIMIT] ..."
              } else {
                stringToCopy
              }
              clipboardManager.setText(AnnotatedString(safeContent))
              hasCopied = true
            }
          } catch (e: Throwable) {
            android.util.Log.e("AragonCopyButton", "Failed to copy text", e)
          }
        }
        .testTag(testTag)
        .semantics {
          contentDescription = if (hasCopied) "Copied to clipboard" else "Copy $displayLabel"
        }
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .height(24.dp)
          .padding(horizontal = 7.dp)
      ) {
        Text(
          text = displayLabel,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = if (hasCopied) FontWeight.Bold else FontWeight.SemiBold,
          fontSize = 9.sp,
          letterSpacing = 0.5.sp,
          color = colorAnimated,
          maxLines = 1
        )
      }
    }
  }
}

/**
 * Unified Step Indicator Badge positioned on the right of feed items.
 * Guaranteed 22.dp height and 4.dp corners to visually align with AragonFeedButton and AragonFeedCopyButton.
 */
@Composable
fun AragonStepBadge(
  stepNumber: Int,
  type: StepType,
  toolStatus: String?,
  isActive: Boolean,
  outcome: String? = null,
  modifier: Modifier = Modifier
) {
  val isMiss = outcome == "CONDITION_NOT_FOUND"
  val isTrunc = outcome == "TRUNCATED_OR_PARTIAL"
  val isEmpty = outcome == "SUCCESS_EMPTY"
  val isUnverified = outcome == "UNVERIFIED"

  val label = when {
    isActive -> "RUN"
    toolStatus == ToolStatus.FAILED.name || type == StepType.ERROR -> "ERR"
    isMiss -> "MISS"
    isTrunc -> "TRUNC"
    isEmpty -> "EMPTY"
    isUnverified -> "UNVRF"
    toolStatus == ToolStatus.SUCCEEDED.name -> "OK"
    type == StepType.TASK_INTENT -> "TASK"
    type == StepType.THINKING || type == StepType.PLAN -> "AI"
    type == StepType.CONCLUSION -> "END"
    type == StepType.VERIFICATION -> "VRF"
    type == StepType.EVALUATION -> "EVAL"
    type == StepType.RECOVERY -> "RCVR"
    type == StepType.REPLANNING -> "PLAN"
    type == StepType.OBSERVATION -> "OBS"
    else -> "OK"
  }

  val textColor = when {
    isActive -> AmoledActionPrimary
    toolStatus == ToolStatus.FAILED.name || type == StepType.ERROR -> AmoledStatusError
    isMiss -> Color(0xFFE5A93C)
    isTrunc -> Color(0xFFD4A373)
    isEmpty -> AmoledIconGreyLight
    isUnverified -> Color(0xFFE57373)
    type == StepType.EVALUATION -> Color(0xFF64B5F6)
    type == StepType.RECOVERY -> Color(0xFFFFB74D)
    type == StepType.REPLANNING -> Color(0xFFCE93D8)
    toolStatus == ToolStatus.SUCCEEDED.name || type == StepType.CONCLUSION -> AmoledStatusSuccess
    type == StepType.TASK_INTENT -> AmoledActionPrimary
    else -> AmoledIconGreyLight
  }

  val bgColor = when {
    isActive -> Color(0xFF221A10)
    toolStatus == ToolStatus.FAILED.name || type == StepType.ERROR -> Color(0xFF221111)
    isMiss -> Color(0xFF221A0C)
    isTrunc -> Color(0xFF1E1A14)
    isEmpty -> Color(0xFF141414)
    isUnverified -> Color(0xFF241414)
    type == StepType.EVALUATION -> Color(0xFF101C28)
    type == StepType.RECOVERY -> Color(0xFF24180C)
    type == StepType.REPLANNING -> Color(0xFF201224)
    toolStatus == ToolStatus.SUCCEEDED.name || type == StepType.CONCLUSION -> Color(0xFF102014)
    type == StepType.TASK_INTENT -> Color(0xFF1A1712)
    else -> Color(0xFF141414)
  }

  val borderColor = when {
    isActive -> AmoledActionPrimary.copy(alpha = 0.6f)
    toolStatus == ToolStatus.FAILED.name || type == StepType.ERROR -> AmoledStatusError.copy(alpha = 0.45f)
    isMiss -> Color(0xFFE5A93C).copy(alpha = 0.45f)
    isTrunc -> Color(0xFFD4A373).copy(alpha = 0.45f)
    isEmpty -> AmoledBorderSubtle
    isUnverified -> Color(0xFFE57373).copy(alpha = 0.45f)
    type == StepType.EVALUATION -> Color(0xFF64B5F6).copy(alpha = 0.45f)
    type == StepType.RECOVERY -> Color(0xFFFFB74D).copy(alpha = 0.45f)
    type == StepType.REPLANNING -> Color(0xFFCE93D8).copy(alpha = 0.45f)
    toolStatus == ToolStatus.SUCCEEDED.name || type == StepType.CONCLUSION -> AmoledStatusSuccess.copy(alpha = 0.45f)
    type == StepType.TASK_INTENT -> AmoledActionPrimary.copy(alpha = 0.45f)
    else -> AmoledBorderSubtle
  }

  val shape = RoundedCornerShape(4.dp)

  Surface(
    shape = shape,
    color = bgColor,
    border = BorderStroke(1.dp, borderColor),
    modifier = modifier
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .height(22.dp)
        .padding(horizontal = 6.dp)
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        letterSpacing = 0.5.sp,
        color = textColor,
        maxLines = 1
      )
    }
  }
}

/**
 * Canonical Header Action Button.
 * Used for top navigation actions (+ NEW, LOG, RESET, STOP, SYSTEM).
 * Guarantees uniform 28.dp height, 6.dp corner radius, clear tactile borders, and ripple feedback.
 */
@Composable
fun AragonHeaderButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  variant: AragonButtonVariant = AragonButtonVariant.DEFAULT,
  badge: String? = null,
  enabled: Boolean = true,
  testTag: String = "header_button"
) {
  val shape = RoundedCornerShape(6.dp)
  val interactionSource = remember { MutableInteractionSource() }

  val bg = when (variant) {
    AragonButtonVariant.PRIMARY -> Color(0xFF1E1810)
    AragonButtonVariant.DANGER -> Color(0xFF261212)
    AragonButtonVariant.SUCCESS -> Color(0xFF102416)
    AragonButtonVariant.MUTED -> Color(0xFF101010)
    else -> Color(0xFF141414)
  }

  val border = when (variant) {
    AragonButtonVariant.PRIMARY -> AmoledActionPrimary.copy(alpha = 0.6f)
    AragonButtonVariant.DANGER -> AmoledStatusError.copy(alpha = 0.6f)
    AragonButtonVariant.SUCCESS -> AmoledStatusSuccess.copy(alpha = 0.6f)
    AragonButtonVariant.MUTED -> AmoledBorderSubtle
    else -> AmoledBorderSubtle
  }

  val textColor = when (variant) {
    AragonButtonVariant.PRIMARY -> AmoledActionPrimary
    AragonButtonVariant.DANGER -> AmoledStatusError
    AragonButtonVariant.SUCCESS -> AmoledStatusSuccess
    AragonButtonVariant.MUTED -> AmoledIconGreyLight
    else -> AmoledIconGreyLight
  }

  Box(
    modifier = Modifier.minimumInteractiveComponentSize(),
    contentAlignment = Alignment.Center
  ) {
    Surface(
      shape = shape,
      color = bg,
      border = BorderStroke(1.dp, border),
      modifier = modifier
        .tactilePress(interactionSource)
        .clip(shape)
        .clickable(
          interactionSource = interactionSource,
          indication = ripple(color = textColor.copy(alpha = 0.25f)),
          enabled = enabled,
          role = Role.Button,
          onClick = onClick
        )
        .testTag(testTag)
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .height(28.dp)
          .padding(horizontal = 9.dp)
      ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
      ) {
        Text(
          text = text,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = if (variant == AragonButtonVariant.PRIMARY || variant == AragonButtonVariant.DANGER) FontWeight.Bold else FontWeight.SemiBold,
          fontSize = 10.sp,
          color = textColor,
          maxLines = 1
        )
        if (badge != null) {
          Spacer(modifier = Modifier.width(4.dp))
          Surface(
            shape = RoundedCornerShape(3.dp),
            color = AmoledActionPrimary,
            modifier = Modifier.padding(vertical = 1.dp)
          ) {
            Text(
              text = badge,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 8.sp,
              color = AmoledActionPrimaryOn,
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
          }
        }
      }
    }
  }
}
}

/**
 * Top Header Copy Action Button (e.g. LOG export).
 * Matches AragonHeaderButton at 28.dp height with 6.dp rounded corners.
 */
@Composable
fun AragonHeaderCopyButton(
  textToCopy: String = "",
  textProvider: (() -> String)? = null,
  modifier: Modifier = Modifier,
  label: String = "LOG",
  testTag: String = "header_copy_button"
) {
  val clipboardManager = LocalClipboardManager.current
  var hasCopied by remember { mutableStateOf(false) }

  LaunchedEffect(hasCopied) {
    if (hasCopied) {
      delay(1500)
      hasCopied = false
    }
  }

  val displayLabel = if (hasCopied) "COPIED" else label
  val shape = RoundedCornerShape(6.dp)
  val interactionSource = remember { MutableInteractionSource() }

  val targetBg = if (hasCopied) Color(0xFF102416) else Color(0xFF141414)
  val targetBorder = if (hasCopied) AmoledStatusSuccess.copy(alpha = 0.6f) else AmoledBorderSubtle
  val targetColor = if (hasCopied) AmoledStatusSuccess else AmoledIconGreyLight

  Box(
    modifier = Modifier.minimumInteractiveComponentSize(),
    contentAlignment = Alignment.Center
  ) {
    Surface(
      shape = shape,
      color = targetBg,
      border = BorderStroke(1.dp, targetBorder),
      modifier = modifier
        .tactilePress(interactionSource)
        .clip(shape)
        .clickable(
          interactionSource = interactionSource,
          indication = ripple(color = AmoledStatusSuccess.copy(alpha = 0.3f)),
          role = Role.Button,
          onClickLabel = "Copy log"
        ) {
          try {
            val stringToCopy = textProvider?.invoke() ?: textToCopy
            if (stringToCopy.isNotBlank()) {
              val safeContent = if (stringToCopy.length > 250_000) {
                stringToCopy.take(250_000) + "\n\n... [TRUNCATED FOR CLIPBOARD SIZE LIMIT] ..."
              } else {
                stringToCopy
              }
              clipboardManager.setText(AnnotatedString(safeContent))
              hasCopied = true
            }
          } catch (e: Throwable) {
            android.util.Log.e("AragonHeaderCopyButton", "Failed to copy transcript", e)
          }
        }
        .testTag(testTag)
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .height(28.dp)
          .padding(horizontal = 9.dp)
      ) {
        Text(
          text = displayLabel,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = if (hasCopied) FontWeight.Bold else FontWeight.SemiBold,
          fontSize = 10.sp,
          color = targetColor,
          maxLines = 1
        )
      }
    }
  }
}

/**
 * Standard Action Button for toolbars, dialogs, and artifact items (SAVE, VIEW, CLOSE, RE-RUN, CLEAR).
 * Replaces mismatched OutlinedButton implementations across the app.
 * Provides unified 28.dp height, 6.dp rounded corners, and accessibility-compliant touch padding.
 */
@Composable
fun AragonActionButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  variant: AragonButtonVariant = AragonButtonVariant.DEFAULT,
  enabled: Boolean = true,
  height: Dp = 28.dp,
  testTag: String = "action_button"
) {
  val shape = RoundedCornerShape(6.dp)
  val interactionSource = remember { MutableInteractionSource() }

  val bg = when (variant) {
    AragonButtonVariant.PRIMARY -> Color(0xFF1E1810)
    AragonButtonVariant.DANGER -> Color(0xFF241010)
    AragonButtonVariant.SUCCESS -> Color(0xFF102416)
    AragonButtonVariant.MUTED -> Color(0xFF0E0E0E)
    else -> Color(0xFF161616)
  }

  val border = when (variant) {
    AragonButtonVariant.PRIMARY -> AmoledActionPrimary.copy(alpha = 0.7f)
    AragonButtonVariant.DANGER -> AmoledStatusError.copy(alpha = 0.6f)
    AragonButtonVariant.SUCCESS -> AmoledStatusSuccess.copy(alpha = 0.6f)
    AragonButtonVariant.MUTED -> AmoledBorderSubtle
    else -> AmoledBorder
  }

  val textColor = when (variant) {
    AragonButtonVariant.PRIMARY -> AmoledActionPrimary
    AragonButtonVariant.DANGER -> AmoledStatusError
    AragonButtonVariant.SUCCESS -> AmoledStatusSuccess
    AragonButtonVariant.MUTED -> AmoledTextMuted
    else -> AmoledTextPrimary
  }

  Box(
    modifier = Modifier.minimumInteractiveComponentSize(),
    contentAlignment = Alignment.Center
  ) {
    Surface(
      shape = shape,
      color = bg,
      border = BorderStroke(1.dp, border),
      modifier = modifier
        .tactilePress(interactionSource)
        .clip(shape)
        .clickable(
          interactionSource = interactionSource,
          indication = ripple(color = textColor.copy(alpha = 0.25f)),
          enabled = enabled,
          role = Role.Button,
          onClick = onClick
        )
        .testTag(testTag)
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .height(height)
          .padding(horizontal = 10.dp)
      ) {
        Text(
          text = text,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.SemiBold,
          fontSize = 9.5.sp,
          color = textColor,
          maxLines = 1
        )
      }
    }
  }
}
