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
import kotlinx.coroutines.delay

/**
 * Compact, tactile copy button with immediate visual feedback and clipboard integration.
 * Enables 1-tap copying for assistant responses, errors, terminal outputs, and artifacts.
 */
@Composable
fun LittleCopyButton(
  textToCopy: String,
  modifier: Modifier = Modifier,
  label: String? = null,
  tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  iconSize: Dp = 15.dp,
  buttonSize: Dp = 28.dp,
  testTag: String = "copy_button"
) {
  val clipboardManager = LocalClipboardManager.current
  var hasCopied by remember { mutableStateOf(false) }

  LaunchedEffect(hasCopied) {
    if (hasCopied) {
      delay(1800)
      hasCopied = false
    }
  }

  Surface(
    shape = RoundedCornerShape(6.dp),
    color = if (hasCopied) Color(0xFF10B981).copy(alpha = 0.15f) else Color.Transparent,
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
            contentDescription = "Copied to clipboard",
            tint = Color(0xFF10B981),
            modifier = Modifier.size(iconSize)
          )
        } else {
          Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = "Copy to clipboard",
            tint = tint,
            modifier = Modifier.size(iconSize)
          )
        }
      }

      if (label != null || hasCopied) {
        AnimatedVisibility(
          visible = hasCopied,
          enter = fadeIn(),
          exit = fadeOut()
        ) {
          Text(
            text = "Copied!",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF10B981),
            fontSize = 10.sp,
            modifier = Modifier.padding(end = 4.dp)
          )
        }

        if (!hasCopied && label != null) {
          Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontSize = 10.sp,
            modifier = Modifier.padding(end = 4.dp)
          )
        }
      }
    }
  }
}
