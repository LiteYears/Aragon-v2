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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.ui.theme.AmoledIconGrey
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledSurfaceVariant
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ArtifactItem(
  artifact: Artifact,
  onOpenPreview: (Artifact) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val icon = getArtifactIcon(artifact.name)
  val formattedSize = formatFileSize(artifact.size)
  val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
  val timeStr = dateFormat.format(Date(artifact.lastModified))

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 4.dp)
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, AmoledBorderSubtle, RoundedCornerShape(12.dp))
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
      // Modern Flutter squircle badge with subtle grey icon
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF161616),
        border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
        modifier = Modifier.size(38.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = icon,
            contentDescription = artifact.type,
            tint = AmoledIconGreyLight,
            modifier = Modifier.size(20.dp)
          )
        }
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = artifact.name,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Medium,
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

      // Action buttons: Download & Inspect
      Row(verticalAlignment = Alignment.CenterVertically) {
        // Direct download button
        IconButton(
          onClick = { ArtifactDownloader.downloadArtifact(context, artifact) },
          modifier = Modifier
            .size(32.dp)
            .testTag("download_artifact_btn_${artifact.name}")
        ) {
          Icon(
            imageVector = Icons.Default.FileDownload,
            contentDescription = "Download artifact",
            tint = AmoledIconGrey,
            modifier = Modifier.size(18.dp)
          )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Inspect button (Flutter-style flat pill)
        OutlinedButton(
          onClick = { onOpenPreview(artifact) },
          shape = RoundedCornerShape(8.dp),
          colors = ButtonDefaults.outlinedButtonColors(
            contentColor = AmoledTextPrimary
          ),
          border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
          modifier = Modifier.testTag("inspect_artifact_btn_${artifact.name}")
        ) {
          Text(
            "View",
            style = MaterialTheme.typography.labelMedium,
            fontSize = 11.sp
          )
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
      shape = RoundedCornerShape(16.dp),
      color = AmoledSurface,
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.85f)
        .testTag("artifact_preview_dialog")
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp)
      ) {
        // Dialog header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = artifact.name,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = AmoledTextPrimary
            )
            Text(
              text = "${artifact.path} • ${formatFileSize(artifact.size)}",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              color = AmoledTextMuted,
              fontSize = 10.sp
            )
          }

          IconButton(onClick = onDismiss) {
            Icon(
              Icons.Default.Close,
              contentDescription = "Close",
              tint = AmoledIconGrey
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Content box with AMOLED terminal styling
        Surface(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp)),
          color = Color(0xFF070707)
        ) {
          Box(
            modifier = Modifier
              .padding(12.dp)
              .verticalScroll(vScroll)
              .horizontalScroll(hScroll)
          ) {
            Text(
              text = content ?: "Loading artifact content...",
              style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                lineHeight = 16.sp
              ),
              color = AmoledIconGreyLight
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Bottom action row: Download, Share, Copy, and Done
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            // Direct download button in preview
            Button(
              onClick = { ArtifactDownloader.downloadArtifact(context, artifact) },
              shape = RoundedCornerShape(8.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = AmoledSurfaceElevated,
                contentColor = AmoledTextPrimary
              ),
              border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
              modifier = Modifier.testTag("download_in_dialog_btn")
            ) {
              Icon(
                imageVector = Icons.Default.FileDownload,
                contentDescription = null,
                tint = AmoledIconGreyLight,
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text("Save File", style = MaterialTheme.typography.labelSmall)
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Share action
            IconButton(
              onClick = { ArtifactDownloader.shareArtifact(context, artifact) },
              modifier = Modifier.size(34.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share",
                tint = AmoledIconGrey,
                modifier = Modifier.size(17.dp)
              )
            }

            if (!content.isNullOrBlank()) {
              Spacer(modifier = Modifier.width(4.dp))
              LittleCopyButton(
                textToCopy = content,
                label = "Copy",
                buttonSize = 34.dp,
                iconSize = 15.dp,
                testTag = "copy_preview_content"
              )
            }
          }

          Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = AmoledActionPrimary,
              contentColor = AmoledActionPrimaryOn
            )
          ) {
            Text("Done", style = MaterialTheme.typography.labelMedium)
          }
        }
      }
    }
  }
}

private fun getArtifactIcon(filename: String): ImageVector {
  val ext = filename.substringAfterLast('.', "").lowercase()
  return when (ext) {
    "py", "sh", "js", "ts", "kt" -> Icons.Default.Terminal
    "csv", "tsv" -> Icons.Default.TableChart
    "md", "txt", "json", "xml" -> Icons.Default.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
  }
}

private fun formatFileSize(bytes: Long): String {
  return when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${String.format("%.1f", bytes / 1024.0)} KB"
    else -> "${String.format("%.1f", bytes / (1024.0 * 1024.0))} MB"
  }
}
