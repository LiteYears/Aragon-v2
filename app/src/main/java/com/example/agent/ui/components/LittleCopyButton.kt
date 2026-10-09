package com.example.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.ui.theme.AmoledIconGrey
import com.example.ui.theme.AmoledStatusSuccess
import kotlinx.coroutines.delay

/**
 * Modern tactile Flutter-style flat copy button with sleek grey icons and instantaneous feedback.
 */
@Composable
fun LittleCopyButton(
  textToCopy: String,
  modifier: Modifier = Modifier,
  label: String? = null,
  tint: Color = AmoledIconGrey,
  iconSize: Dp = 14.dp,
  buttonSize: Dp = 28.dp,
  testTag: String = "copy_button"
) {
  val clipboardManager = LocalClipboardManager.current
  var hasCopied by remember { mutableStateOf(false) }

  LaunchedEffect(hasCopied) {
    if (hasCopied) {
      delay(1600)
      hasCopied = false
    }
  }

  Surface(
    shape = RoundedCornerShape(8.dp),
    color = if (hasCopied) AmoledStatusSuccess.copy(alpha = 0.12f) else Color.Transparent,
    modifier = modifier
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 2.dp)
    ) {
      IconButton(
        onClick = {
          if (textToCopy.isNotBlank()) {
            clipboardManager.setText(AnnotatedString(textToCopy))
            hasCopied = true
          }
        },
        modifier = Modifier
          .size(buttonSize)
          .testTag(testTag)
      ) {
        if (hasCopied) {
          Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "Copied",
            tint = AmoledStatusSuccess,
            modifier = Modifier.size(iconSize)
          )
        } else {
          Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = "Copy",
            tint = tint,
            modifier = Modifier.size(iconSize)
          )
        }
      }

      if (label != null) {
        Spacer(modifier = Modifier.width(2.dp))
        Text(
          text = if (hasCopied) "Copied" else label,
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Medium,
          fontSize = 10.sp,
          color = if (hasCopied) AmoledStatusSuccess else tint,
          modifier = Modifier.padding(end = 6.dp)
        )
      }
    }
  }
}
