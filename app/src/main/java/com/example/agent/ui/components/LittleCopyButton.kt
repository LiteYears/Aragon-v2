package com.example.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGrey
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledStatusSuccess
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay

/**
 * Minimalist typography-only copy action button.
 * Eliminates icons and relies strictly on crisp, tactile text states.
 */
@Composable
fun LittleCopyButton(
  textToCopy: String,
  modifier: Modifier = Modifier,
  label: String? = null,
  tint: Color = AmoledIconGreyLight,
  iconSize: Dp = 12.dp,
  buttonSize: Dp = 26.dp,
  testTag: String = "copy_button"
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
  val badgeBg = if (hasCopied) AmoledStatusSuccess.copy(alpha = 0.16f) else Color(0xFF141414)
  val badgeBorder = if (hasCopied) AmoledStatusSuccess.copy(alpha = 0.5f) else AmoledBorderSubtle
  val textColor = if (hasCopied) AmoledStatusSuccess else tint

  Surface(
    shape = RoundedCornerShape(5.dp),
    color = badgeBg,
    border = androidx.compose.foundation.BorderStroke(1.dp, badgeBorder),
    modifier = modifier
      .clip(RoundedCornerShape(5.dp))
      .clickable {
        if (textToCopy.isNotBlank()) {
          clipboardManager.setText(AnnotatedString(textToCopy))
          hasCopied = true
        }
      }
      .testTag(testTag)
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
      Text(
        text = displayLabel,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.sp,
        letterSpacing = 0.5.sp,
        color = textColor
      )
    }
  }
}
