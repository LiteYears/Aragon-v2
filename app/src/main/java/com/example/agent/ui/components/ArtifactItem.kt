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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.ui.theme.AmoledAccentAmber
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
  val extColor = getArtifactExtensionColor(artifact.name)
  val formattedSize = formatFileSize(artifact.size)
  val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
  val timeStr = dateFormat.format(Date(artifact.lastModified))

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 4.dp)
      .clip(RoundedCornerShape(10.dp))
      .border(1.dp, AmoledBorderSubtle, RoundedCornerShape(10.dp))
      .clickable(
        role = androidx.compose.ui.semantics.Role.Button,
        onClickLabel = "Inspect ${artifact.name}"
      ) { onOpenPreview(artifact) }
      .testTag("artifact_card_${artifact.name}"),
    color = AmoledSurfaceElevated
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Pure text extension badge with format color accent
      Surface(
        shape = RoundedCornerShape(6.dp),
        color = extColor.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.dp, extColor.copy(alpha = 0.4f)),
        modifier = Modifier.size(38.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            text = extBadge,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = extColor
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

      // Pure text action buttons: SAVE & VIEW with unified 28dp height and styling
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        AragonActionButton(
          text = "SAVE",
          onClick = { ArtifactDownloader.downloadArtifact(context, artifact) },
          variant = AragonButtonVariant.MUTED,
          height = 28.dp,
          testTag = "download_artifact_btn_${artifact.name}"
        )

        AragonActionButton(
          text = "VIEW",
          onClick = { onOpenPreview(artifact) },
          variant = AragonButtonVariant.PRIMARY,
          height = 28.dp,
          testTag = "inspect_artifact_btn_${artifact.name}"
        )
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
  var showLineNumbers by remember { mutableStateOf(true) }

  val lines = remember(content) {
    content?.lines() ?: emptyList()
  }
  val lineCount = lines.size
  val wordCount = remember(content) {
    if (content.isNullOrBlank()) 0 else content.split(Regex("\\s+")).count { it.isNotBlank() }
  }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = Color(0xFF0C0C0C),
      border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorder),
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 720.dp)
        .fillMaxHeight(0.88f)
        .testTag("artifact_preview_dialog")
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
          ) {
            val badge = getArtifactExtensionBadge(artifact.name)
            val badgeColor = getArtifactExtensionColor(artifact.name)
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = badgeColor.copy(alpha = 0.15f),
              border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.5f)),
              modifier = Modifier.size(28.dp)
            ) {
              Box(contentAlignment = Alignment.Center) {
                Text(
                  text = badge,
                  fontFamily = JetBrainsMonoFontFamily,
                  fontWeight = FontWeight.Bold,
                  fontSize = 9.sp,
                  color = badgeColor
                )
              }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column {
              Text(
                text = artifact.name,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = AmoledTextPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
              )
              Text(
                text = "${artifact.type.uppercase()} • ${formatFileSize(artifact.size)} • $lineCount lines • $wordCount words",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = AmoledTextMuted,
                fontSize = 9.sp
              )
            }
          }

          // Pure text CLOSE button
          AragonActionButton(
            text = "CLOSE",
            onClick = onDismiss,
            variant = AragonButtonVariant.DEFAULT,
            height = 26.dp,
            testTag = "close_preview_btn"
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Actions toolbar
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          if (!content.isNullOrBlank()) {
            AragonFeedCopyButton(
              textToCopy = content,
              label = "COPY TEXT",
              testTag = "copy_preview_content"
            )
          }

          LittleCopyButton(
            textToCopy = artifact.path,
            label = "COPY PATH",
            testTag = "copy_preview_path"
          )

          AragonActionButton(
            text = if (showLineNumbers) "NUMS: ON" else "NUMS: OFF",
            onClick = { showLineNumbers = !showLineNumbers },
            variant = if (showLineNumbers) AragonButtonVariant.PRIMARY else AragonButtonVariant.MUTED,
            height = 24.dp,
            testTag = "toggle_line_numbers_btn"
          )

          AragonActionButton(
            text = "SAVE",
            onClick = { ArtifactDownloader.downloadArtifact(context, artifact) },
            variant = AragonButtonVariant.DEFAULT,
            height = 24.dp,
            testTag = "save_file_btn"
          )

          AragonActionButton(
            text = "SHARE",
            onClick = { ArtifactDownloader.shareArtifact(context, artifact) },
            variant = AragonButtonVariant.DEFAULT,
            height = 24.dp,
            testTag = "share_file_btn"
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Content viewer with line numbering
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = Color(0xFF050505),
          border = androidx.compose.foundation.BorderStroke(1.dp, AmoledBorderSubtle),
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
        ) {
          if (content == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text(
                text = "Loading deliverable content...",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                color = AmoledTextMuted
              )
            }
          } else {
            Row(
              modifier = Modifier
                .fillMaxSize()
                .verticalScroll(vScroll)
                .horizontalScroll(hScroll)
                .padding(8.dp)
            ) {
              if (showLineNumbers && lines.isNotEmpty()) {
                Column(
                  modifier = Modifier
                    .padding(end = 10.dp)
                    .border(
                      width = 0.dp,
                      color = Color.Transparent
                    )
                ) {
                  lines.indices.forEach { idx ->
                    Text(
                      text = String.format(Locale.ROOT, "%3d", idx + 1),
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 10.sp,
                      lineHeight = 15.sp,
                      color = AmoledIconGreyLight,
                      textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                  }
                }
              }

              Column {
                lines.forEach { line ->
                  Text(
                    text = if (line.isEmpty()) " " else line,
                    style = MaterialTheme.typography.bodySmall.copy(
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 10.sp,
                      lineHeight = 15.sp
                    ),
                    color = AmoledTextSecondary
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

fun getArtifactExtensionBadge(fileName: String): String {
  val ext = fileName.substringAfterLast('.', "").uppercase()
  return when (ext) {
    "MARKDOWN" -> "MD"
    "PYTHON" -> "PY"
    "JAVASCRIPT" -> "JS"
    "TYPESCRIPT" -> "TS"
    else -> if (ext.isNotBlank()) ext.take(4) else "FILE"
  }
}

fun getArtifactExtensionColor(fileName: String): Color {
  val ext = fileName.substringAfterLast('.', "").lowercase()
  return when (ext) {
    "docx", "doc" -> Color(0xFF4A90E2) // Blue
    "xlsx", "xls" -> Color(0xFF388E3C) // Green
    "json", "jsonl" -> AmoledAccentAmber // Amber
    "csv" -> Color(0xFF10B981) // Emerald
    "py" -> Color(0xFF3B82F6) // Sky Blue
    "sh" -> Color(0xFF9CA3AF) // Slate
    "md", "txt" -> Color(0xFFEAB308) // Warm Gold
    "pdf" -> Color(0xFFEF4444) // Red
    else -> AmoledActionPrimary
  }
}

fun formatFileSize(bytes: Long): String {
  return when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))} MB"
  }
}
