package com.example.agent.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmoledIconGreyLight

/**
 * Minimalist typography-only copy action button.
 * Delegates to canonical AragonFeedCopyButton for universal styling and alignment across feed and UI.
 */
@Composable
fun LittleCopyButton(
  textToCopy: String = "",
  textProvider: (() -> String)? = null,
  modifier: Modifier = Modifier,
  label: String? = null,
  tint: Color = AmoledIconGreyLight,
  iconSize: Dp = 12.dp,
  buttonSize: Dp = 26.dp,
  compact: Boolean = false,
  testTag: String = "copy_button"
) {
  AragonFeedCopyButton(
    textToCopy = textToCopy,
    textProvider = textProvider,
    modifier = modifier,
    label = label,
    tint = tint,
    testTag = testTag
  )
}
