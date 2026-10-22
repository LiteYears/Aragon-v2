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
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.ui.theme.InterFontFamily
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
  val alpha by animateFloatAsState(
    targetValue = if (isPressed) 0.88f else 1.0f,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioNoBouncy,
      stiffness = Spring.StiffnessHigh
    ),
    label = "tactile_press_alpha"
  )
  return this.graphicsLayer {
    scaleX = scale
    scaleY = scale
    this.alpha = alpha
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
 * Transformed to unified clean White Pill style, floating above the AMOLED feed.
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
  val shape = RoundedCornerShape(12.dp)
  val interactionSource = remember { MutableInteractionSource() }

  val targetBg = when {
    !enabled -> Color(0xFFF1F5F9)
    isActive -> Color(0xFF0F172A)
    variant == AragonButtonVariant.DANGER -> Color(0xFFFEE2E2)
    variant == AragonButtonVariant.SUCCESS -> Color(0xFFDCFCE7)
    else -> Color.White
  }

  val targetBorder = when {
    !enabled -> Color(0xFFE2E8F0)
    isActive -> Color(0xFF0F172A)
    variant == AragonButtonVariant.DANGER -> Color(0xFFFCA5A5)
    variant == AragonButtonVariant.SUCCESS -> Color(0xFF86EFAC)
    else -> Color(0xFFE2E8F0)
  }

  val targetText = when {
    !enabled -> Color(0xFF94A3B8)
    isActive -> Color.White
    variant == AragonButtonVariant.DANGER -> Color(0xFFDC2626)
    variant == AragonButtonVariant.SUCCESS -> Color(0xFF16A34A)
    else -> Color(0xFF0F172A)
  }

  val bgAnimated by animateColorAsState(targetValue = targetBg, animationSpec = tween(120), label = "feed_btn_bg")
  val borderAnimated by animateColorAsState(targetValue = targetBorder, animationSpec = tween(120), label = "feed_btn_border")
  val textAnimated by animateColorAsState(targetValue = targetText, animationSpec = tween(120), label = "feed_btn_text")

  Surface(
    shape = shape,
    color = bgAnimated,
    border = BorderStroke(1.dp, borderAnimated),
    shadowElevation = if (enabled) 1.5.dp else 0.dp,
    modifier = modifier
      .tactilePress(interactionSource)
      .clickable(
        interactionSource = interactionSource,
        indication = ripple(color = Color(0xFF0F172A).copy(alpha = 0.2f)),
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
        .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
      Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 9.5.sp,
        letterSpacing = 0.5.sp,
        color = textAnimated,
        maxLines = 1
      )
    }
  }
}

/**
 * Canonical Copy Button for Feed & Detail Rows.
 * Transformed to unified clean White Pill style, floating above the AMOLED feed.
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
  val shape = RoundedCornerShape(12.dp)
  val interactionSource = remember { MutableInteractionSource() }

  val targetBg = if (hasCopied) Color(0xFFDCFCE7) else Color.White
  val targetBorder = if (hasCopied) Color(0xFF86EFAC) else Color(0xFFE2E8F0)
  val targetColor = if (hasCopied) Color(0xFF16A34A) else (tint ?: Color(0xFF0F172A))

  val bgAnimated by animateColorAsState(targetValue = targetBg, animationSpec = tween(140), label = "copy_bg")
  val borderAnimated by animateColorAsState(targetValue = targetBorder, animationSpec = tween(140), label = "copy_border")
  val colorAnimated by animateColorAsState(targetValue = targetColor, animationSpec = tween(140), label = "copy_color")

  Surface(
    shape = shape,
    color = bgAnimated,
    border = BorderStroke(1.dp, borderAnimated),
    shadowElevation = 1.5.dp,
    modifier = modifier
      .tactilePress(interactionSource)
      .clickable(
        interactionSource = interactionSource,
        indication = ripple(color = Color(0xFF0F172A).copy(alpha = 0.2f)),
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
        .padding(horizontal = 9.dp)
    ) {
      Text(
        text = displayLabel,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 9.5.sp,
        letterSpacing = 0.5.sp,
        color = colorAnimated,
        maxLines = 1
      )
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
  val shape = RoundedCornerShape(16.dp)
  val interactionSource = remember { MutableInteractionSource() }

  val bg = when (variant) {
    AragonButtonVariant.DANGER -> Color(0xFFEF4444)
    else -> Color.White
  }

  val textColor = when (variant) {
    AragonButtonVariant.DANGER -> Color.White
    else -> Color(0xFF0F172A)
  }

  val border = when (variant) {
    AragonButtonVariant.DANGER -> BorderStroke(1.dp, Color(0xFFFCA5A5))
    else -> BorderStroke(1.dp, Color(0xFFE2E8F0))
  }

  Surface(
    shape = shape,
    color = bg,
    border = border,
    shadowElevation = 2.dp,
    modifier = modifier
      .tactilePress(interactionSource)
      .clickable(
        interactionSource = interactionSource,
        indication = ripple(color = textColor.copy(alpha = 0.2f)),
        enabled = enabled,
        role = Role.Button,
        onClick = onClick
      )
      .testTag(testTag)
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .height(32.dp)
        .padding(horizontal = 14.dp, vertical = 2.dp)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
      ) {
        Text(
          text = text,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = InterFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 11.sp,
          color = textColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        if (badge != null) {
          Spacer(modifier = Modifier.width(5.dp))
          Surface(
            shape = CircleShape,
            color = if (variant == AragonButtonVariant.DANGER) Color.White else Color(0xFF0F172A),
            border = null,
            modifier = Modifier.padding(start = 2.dp)
          ) {
            Text(
              text = badge,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 9.sp,
              color = if (variant == AragonButtonVariant.DANGER) Color(0xFFEF4444) else Color.White,
              modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
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
  val shape = RoundedCornerShape(16.dp)
  val interactionSource = remember { MutableInteractionSource() }

  Surface(
    shape = shape,
    color = Color.White,
    border = null,
    shadowElevation = 3.dp,
    modifier = modifier
      .tactilePress(interactionSource)
      .clickable(
        interactionSource = interactionSource,
        indication = ripple(color = Color(0xFF0F172A).copy(alpha = 0.2f)),
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
        .height(30.dp)
        .padding(horizontal = 12.dp)
    ) {
      Text(
        text = displayLabel,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        color = if (hasCopied) Color(0xFF10B981) else Color(0xFF0F172A),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/**
 * Standard Action Button for toolbars, dialogs, presets, and workspaces.
 * Transformed to modern White Pill Button theme across the entire app.
 */
@Composable
fun AragonActionButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  variant: AragonButtonVariant = AragonButtonVariant.DEFAULT,
  enabled: Boolean = true,
  height: Dp = 32.dp,
  testTag: String = "action_button"
) {
  val shape = RoundedCornerShape(16.dp)
  val interactionSource = remember { MutableInteractionSource() }

  val bg = when {
    !enabled -> Color(0xFFF1F5F9)
    variant == AragonButtonVariant.DANGER -> Color(0xFFFEE2E2)
    variant == AragonButtonVariant.SUCCESS -> Color(0xFFDCFCE7)
    else -> Color.White
  }

  val border = when {
    !enabled -> Color(0xFFE2E8F0)
    variant == AragonButtonVariant.DANGER -> Color(0xFFFCA5A5)
    variant == AragonButtonVariant.SUCCESS -> Color(0xFF86EFAC)
    else -> Color(0xFFE2E8F0)
  }

  val textColor = when {
    !enabled -> Color(0xFF94A3B8)
    variant == AragonButtonVariant.DANGER -> Color(0xFFDC2626)
    variant == AragonButtonVariant.SUCCESS -> Color(0xFF16A34A)
    variant == AragonButtonVariant.MUTED -> Color(0xFF475569)
    else -> Color(0xFF0F172A)
  }

  Surface(
    shape = shape,
    color = bg,
    border = BorderStroke(1.dp, border),
    shadowElevation = if (enabled) 2.5.dp else 0.dp,
    modifier = modifier
      .tactilePress(interactionSource)
      .clickable(
        interactionSource = interactionSource,
        indication = ripple(color = Color(0xFF0F172A).copy(alpha = 0.2f)),
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
        .padding(horizontal = 14.dp, vertical = 2.dp)
    ) {
      Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp,
        letterSpacing = 0.3.sp,
        color = textColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}
