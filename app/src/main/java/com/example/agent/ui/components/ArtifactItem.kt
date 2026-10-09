package com.example.agent.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.agent.core.Artifact
import com.example.agent.core.ArtifactDownloader
import com.example.ui.theme.AmoledActionPrimary
import com.example.ui.theme.AmoledActionPrimaryOn
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledStatusSuccess
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledSurfaceVariant
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deliverables / Artifact list item.
 * Pure typography-driven design without vector icons.
 */
@Composable
fun ArtifactItem(
  artifact: Artifact,
  onOpenPreview: (Artifact) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val extBadge = getArtifactExtensionBadge(artifact.name)
  val formattedSize = formatFileSize(artifact.size)
  val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
  val timeStr = dateFormat.format(Date(artifact.lastModified))

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 4.dp)
      .clip(RoundedCornerShape(10.dp))
      .border(1.dp, AmoledBorderSubtle, RoundedCornerShape(10.dp))
      .clickable { onOpenPreview(artifact) }
      .testTag("artifact_card_${artifact.name}"),
    color = AmoledSurfaceElevated
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Pure text extension badge (Replaces file icons)
      Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF161616),
        border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
        modifier = Modifier.size(38.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            text = extBadge,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = AmoledActionPrimary
          )
        }
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = artifact.name,
          style = MaterialTheme.typography.titleSmall,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Medium,
          fontSize = 13.sp,
          color = AmoledTextPrimary
        )

        Spacer(modifier = Modifier.height(2.dp))
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = artifact.type.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledIconGreyLight
          )
          Text(text = "•", style = MaterialTheme.typography.labelSmall, color = AmoledTextMuted)
          Text(
            text = formattedSize,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledTextSecondary
          )
          Text(text = "•", style = MaterialTheme.typography.labelSmall, color = AmoledTextMuted)
          Text(
            text = timeStr,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = AmoledTextMuted
          )
        }
      }

      // Pure text action buttons: SAVE & VIEW
      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
          onClick = { ArtifactDownloader.downloadArtifact(context, artifact) },
          shape = RoundedCornerShape(6.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledTextSecondary),
          border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
          modifier = Modifier
            .height(28.dp)
            .testTag("download_artifact_btn_${artifact.name}")
        ) {
          Text("SAVE", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp)
        }

        Spacer(modifier = Modifier.width(6.dp))

        OutlinedButton(
          onClick = { onOpenPreview(artifact) },
          shape = RoundedCornerShape(6.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledTextPrimary),
          border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
          modifier = Modifier
            .height(28.dp)
            .testTag("inspect_artifact_btn_${artifact.name}")
        ) {
          Text("VIEW", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp)
        }
      }
    }
  }
}

@Composable
fun ArtifactPreviewDialog(
  artifact: Artifact,
  content: String?,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  val vScroll = rememberScrollState()
  val hScroll = rememberScrollState()

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = Color(0xFF0F0F0F),
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.85f)
        .testTag("artifact_preview_dialog")
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = artifact.name,
              style = MaterialTheme.typography.titleMedium,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              color = AmoledTextPrimary
            )
            Text(
              text = "${artifact.type} • ${formatFileSize(artifact.size)}",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              color = AmoledTextMuted,
              fontSize = 10.sp
            )
          }

          // Pure text CLOSE button
          OutlinedButton(
            onClick = onDismiss,
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledTextPrimary),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            modifier = Modifier.height(28.dp).testTag("close_preview_btn")
          ) {
            Text("CLOSE", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp)
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Actions toolbar: Pure text COPY, SAVE, SHARE
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          if (!content.isNullOrBlank()) {
            LittleCopyButton(
              textToCopy = content,
              label = "COPY CONTENT",
              testTag = "copy_preview_content"
            )
          }

          OutlinedButton(
            onClick = { ArtifactDownloader.downloadArtifact(context, artifact) },
            shape = RoundedCornerShape(6.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledTextPrimary),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.height(26.dp)
          ) {
            Text("SAVE FILE", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp)
          }

          OutlinedButton(
            onClick = { ArtifactDownloader.shareArtifact(context, artifact) },
            shape = RoundedCornerShape(6.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AmoledTextPrimary),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.height(26.dp)
          ) {
            Text("SHARE", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFontFamily, fontSize = 9.sp)
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Content viewer
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = Color(0xFF060606),
          border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .verticalScroll(vScroll)
              .horizontalScroll(hScroll)
              .padding(12.dp)
          ) {
            Text(
              text = content ?: "Loading deliverable content...",
              style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                lineHeight = 16.sp
              ),
              color = AmoledTextSecondary
            )
          }
        }
      }
    }
  }
}

fun getArtifactExtensionBadge(fileName: String): String {
  val ext = fileName.substringAfterLast('.', "").uppercase()
  return if (ext.isNotBlank()) ext.take(4) else "FILE"
}

fun formatFileSize(bytes: Long): String {
  return when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))} MB"
  }
}
