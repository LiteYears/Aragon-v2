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
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.agent.core.Artifact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ArtifactItem(
  artifact: Artifact,
  onOpenPreview: (Artifact) -> Unit,
  modifier: Modifier = Modifier
) {
  val icon = getArtifactIcon(artifact.name)
  val formattedSize = formatFileSize(artifact.size)
  val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
  val timeStr = dateFormat.format(Date(artifact.lastModified))

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 4.dp)
      .clip(RoundedCornerShape(8.dp))
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
      .clickable { onOpenPreview(artifact) }
      .testTag("artifact_card_${artifact.name}"),
    color = MaterialTheme.colorScheme.surface
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(40.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = icon,
            contentDescription = artifact.type,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(24.dp)
          )
        }
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = artifact.name,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = artifact.type,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
          )
          Text(
            text = "•",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
          )
          Text(
            text = formattedSize,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            text = "•",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
          )
          Text(
            text = timeStr,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
          )
        }
      }

      OutlinedButton(
        onClick = { onOpenPreview(artifact) },
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.testTag("inspect_artifact_btn_${artifact.name}")
      ) {
        Text("Inspect", style = MaterialTheme.typography.labelMedium)
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
  val vScroll = rememberScrollState()
  val hScroll = rememberScrollState()

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.8f)
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
          Column {
            Text(
              text = artifact.name,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Path: ${artifact.path}  (${formatFileSize(artifact.size)})",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Close")
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Content box
        Surface(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
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
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp
              )
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End
        ) {
          Button(onClick = onDismiss) {
            Text("Done")
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
