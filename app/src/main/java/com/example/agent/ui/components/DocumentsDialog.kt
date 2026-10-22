package com.example.agent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.agent.docs.DocumentStatus
import com.example.agent.docs.DocumentUiItem
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily

private val DocObsidianBg = Color(0xFF050508)
private val DocCardSurface = Color(0xFF07070B)
private val DocCardElevated = Color(0xFF0C0E14)
private val DocCardBorder = Color(0xFF191C26)
private val DocCyan = Color(0xFF38BDF8)
private val DocEmerald = Color(0xFF10B981)
private val DocAmber = Color(0xFFF59E0B)
private val DocPurple = Color(0xFFA855F7)
private val DocDanger = Color(0xFFEF4444)

@Composable
fun DocumentsDialog(
  documents: List<DocumentUiItem>,
  scope: Set<String>,
  ocrEnabled: Boolean,
  autoCloudOcr: Boolean = true,
  cloudOcrAvailable: Boolean = false,
  embeddingLabel: String = "Lexical + Vector",
  onDismiss: () -> Unit,
  onAttach: () -> Unit,
  onAttachPhotos: () -> Unit = {},
  onAttachFolder: () -> Unit = {},
  onToggleScope: (String, Boolean) -> Unit,
  onCancelJob: (String) -> Unit,
  onDelete: (String) -> Unit,
  onReindex: (String) -> Unit,
  onToggleOcr: (Boolean) -> Unit,
  onToggleAutoCloudOcr: (Boolean) -> Unit = {}
) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = DocObsidianBg,
      border = BorderStroke(1.dp, DocCardBorder),
      shadowElevation = 24.dp,
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 580.dp)
        .heightIn(max = 650.dp)
        .testTag("documents_dialog")
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        // --- HERO HEADER (Matching AboutAragonScreen aesthetic) ---
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = DocCardSurface,
          border = BorderStroke(1.dp, DocCardBorder),
          modifier = Modifier.fillMaxWidth()
        ) {
          Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(modifier = Modifier.matchParentSize()) {
              drawCircle(
                brush = Brush.radialGradient(
                  colors = listOf(
                    DocCyan.copy(alpha = 0.12f),
                    DocPurple.copy(alpha = 0.04f),
                    Color.Transparent
                  ),
                  center = Offset(size.width * 0.2f, size.height * 0.3f),
                  radius = size.maxDimension * 0.7f
                )
              )
            }

            Column(modifier = Modifier.padding(12.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Surface(
                  shape = RoundedCornerShape(999.dp),
                  color = DocCyan.copy(alpha = 0.12f),
                  border = BorderStroke(1.dp, DocCyan.copy(alpha = 0.35f))
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                  ) {
                    Box(
                      modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(DocCyan)
                    )
                    Text(
                      text = "RAG VAULT • ${documents.size} DOCS • ${scope.size} IN SCOPE",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 8.sp,
                      fontWeight = FontWeight.Bold,
                      color = DocCyan
                    )
                  }
                }

                AragonHeaderButton(
                  text = "CLOSE",
                  onClick = onDismiss,
                  variant = AragonButtonVariant.MUTED,
                  testTag = "close_documents_dialog_btn"
                )
              }

              Spacer(modifier = Modifier.height(8.dp))

              Text(
                text = "DOCUMENT INTELLIGENCE VAULT",
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp,
                letterSpacing = 1.sp,
                color = Color.White
              )
              Text(
                text = "Index Engine: $embeddingLabel • PDF, DOCX, XLSX, CSV, TXT & Image OCR",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 8.5.sp,
                color = Color(0xFF94A3B8)
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Scrollable Body
        val bodyScroll = rememberScrollState()
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .verticalScroll(bodyScroll),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          // Action Bar: Add Files, Photos & Folder
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Button(
              onClick = onAttach,
              colors = ButtonDefaults.buttonColors(
                containerColor = DocCyan,
                contentColor = Color(0xFF04070D)
              ),
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
              modifier = Modifier
                .weight(1f)
                .height(34.dp)
                .testTag("attach_files_btn")
            ) {
              Text(
                text = "+ FILES",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
              )
            }

            OutlinedButton(
              onClick = onAttachPhotos,
              border = BorderStroke(1.dp, DocCyan.copy(alpha = 0.45f)),
              colors = ButtonDefaults.outlinedButtonColors(
                containerColor = DocCardSurface,
                contentColor = DocCyan
              ),
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
              modifier = Modifier
                .weight(1f)
                .height(34.dp)
                .testTag("attach_images_btn")
            ) {
              Text(
                text = "+ PHOTOS (OCR)",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
              )
            }

            OutlinedButton(
              onClick = onAttachFolder,
              border = BorderStroke(1.dp, DocCardBorder),
              colors = ButtonDefaults.outlinedButtonColors(
                containerColor = DocCardSurface,
                contentColor = Color(0xFFE2E8F0)
              ),
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
              modifier = Modifier
                .weight(0.9f)
                .height(34.dp)
                .testTag("attach_folder_btn")
            ) {
              Text(
                text = "+ FOLDER",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
              )
            }
          }

          // OCR Toggle Row
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = DocCardSurface,
            border = BorderStroke(1.dp, DocCardBorder),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    text = "Local ML Kit OCR Engine",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = InterFontFamily,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = DocEmerald.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, DocEmerald.copy(alpha = 0.35f))
                  ) {
                    Text(
                      text = "ON-DEVICE",
                      fontFamily = JetBrainsMonoFontFamily,
                      fontSize = 7.sp,
                      fontWeight = FontWeight.Bold,
                      color = DocEmerald,
                      modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                  }
                }
                Text(
                  text = "Extract text from scanned PDFs and attached images",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFF94A3B8),
                  fontSize = 9.sp
                )
              }
              Switch(
                checked = ocrEnabled,
                onCheckedChange = onToggleOcr,
                colors = SwitchDefaults.colors(
                  checkedThumbColor = DocCyan,
                  checkedTrackColor = DocCyan.copy(alpha = 0.25f),
                  uncheckedThumbColor = Color(0xFF64748B),
                  uncheckedTrackColor = DocCardElevated
                ),
                modifier = Modifier.testTag("ocr_toggle_switch")
              )
            }
          }

          // Cloud Vision AI OCR Toggle Row
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = DocCardSurface,
            border = BorderStroke(1.dp, if (autoCloudOcr && cloudOcrAvailable) DocPurple.copy(alpha = 0.45f) else DocCardBorder),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    text = "Cloud Vision AI OCR",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = InterFontFamily,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (cloudOcrAvailable) DocPurple.copy(alpha = 0.15f) else DocCardElevated,
                    border = BorderStroke(1.dp, if (cloudOcrAvailable) DocPurple.copy(alpha = 0.4f) else DocCardBorder)
                  ) {
                    Text(
                      text = if (cloudOcrAvailable) "VISION ACTIVE" else "LOCAL FALLBACK",
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = JetBrainsMonoFontFamily,
                      color = if (cloudOcrAvailable) DocPurple else Color(0xFF94A3B8),
                      fontSize = 7.5.sp,
                      fontWeight = FontWeight.Bold,
                      modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                  }
                }
                Text(
                  text = "High-precision multimodal layout, table & handwriting extraction",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFF94A3B8),
                  fontSize = 9.sp
                )
              }
              Switch(
                checked = autoCloudOcr,
                onCheckedChange = onToggleAutoCloudOcr,
                colors = SwitchDefaults.colors(
                  checkedThumbColor = DocPurple,
                  checkedTrackColor = DocPurple.copy(alpha = 0.25f),
                  uncheckedThumbColor = Color(0xFF64748B),
                  uncheckedTrackColor = DocCardElevated
                ),
                modifier = Modifier.testTag("cloud_ocr_toggle_switch")
              )
            }
          }

          // Scope Controls Header
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "INDEXED DOCUMENTS (${documents.size})",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              color = DocCyan,
              fontSize = 9.sp
            )

            if (documents.isNotEmpty()) {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                  shape = RoundedCornerShape(5.dp),
                  color = DocCyan.copy(alpha = 0.14f),
                  border = BorderStroke(1.dp, DocCyan.copy(alpha = 0.35f)),
                  modifier = Modifier
                    .clickable {
                      documents.forEach { onToggleScope(it.id, true) }
                    }
                    .testTag("scope_all_btn")
                ) {
                  Text(
                    text = "SELECT ALL",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = DocCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                  )
                }
                Surface(
                  shape = RoundedCornerShape(5.dp),
                  color = DocCardElevated,
                  border = BorderStroke(1.dp, DocCardBorder),
                  modifier = Modifier
                    .clickable {
                      documents.forEach { onToggleScope(it.id, false) }
                    }
                    .testTag("scope_clear_btn")
                ) {
                  Text(
                    text = "CLEAR",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                  )
                }
              }
            }
          }

          // Documents List
          if (documents.isEmpty()) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = DocCardSurface,
              border = BorderStroke(1.dp, DocCardBorder),
              modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
                Text(
                  text = "No documents indexed yet.\nTap '+ FILES' or '+ PHOTOS (OCR)' above to ingest local files.",
                  style = MaterialTheme.typography.bodySmall,
                  fontFamily = InterFontFamily,
                  color = Color(0xFF94A3B8),
                  fontSize = 11.sp
                )
              }
            }
          } else {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .testTag("documents_list"),
              verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              documents.forEach { doc ->
                val inScope = scope.contains(doc.id) || doc.inScope
                DocumentRowCard(
                  doc = doc,
                  inScope = inScope,
                  onToggleScope = { onToggleScope(doc.id, !inScope) },
                  onCancelJob = { onCancelJob(doc.id) },
                  onReindex = { onReindex(doc.id) },
                  onDelete = { onDelete(doc.id) }
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DocumentRowCard(
  doc: DocumentUiItem,
  inScope: Boolean,
  onToggleScope: () -> Unit,
  onCancelJob: () -> Unit,
  onReindex: () -> Unit,
  onDelete: () -> Unit
) {
  val statusColor = when (doc.status) {
    DocumentStatus.READY, DocumentStatus.READY_LEXICAL -> DocEmerald
    DocumentStatus.IMPORTING, DocumentStatus.PROCESSING -> DocCyan
    DocumentStatus.CANCELLED -> DocAmber
    DocumentStatus.FAILED -> DocDanger
  }

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = if (inScope) Color(0xFF0A101D) else DocCardSurface,
    border = BorderStroke(1.dp, if (inScope) DocCyan.copy(alpha = 0.5f) else DocCardBorder),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("doc_item_${doc.id}")
  ) {
    Column(modifier = Modifier.padding(10.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
      ) {
        Checkbox(
          checked = inScope,
          onCheckedChange = { onToggleScope() },
          colors = CheckboxDefaults.colors(
            checkedColor = DocCyan,
            checkmarkColor = Color(0xFF04070D),
            uncheckedColor = Color(0xFF64748B)
          ),
          modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = doc.displayName,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = "${doc.type.name} • ${formatBytes(doc.sizeBytes)} • ${doc.chunkCount} chunks",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = Color(0xFF94A3B8),
            fontSize = 8.5.sp
          )
        }

        Surface(
          shape = RoundedCornerShape(5.dp),
          color = statusColor.copy(alpha = 0.14f),
          border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
        ) {
          Text(
            text = doc.statusLabel,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = statusColor,
            fontSize = 7.5.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
          )
        }
      }

      if (!doc.error.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = doc.error,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          color = DocDanger,
          fontSize = 8.5.sp
        )
      }

      Spacer(modifier = Modifier.height(8.dp))
      HorizontalDivider(color = DocCardBorder)
      Spacer(modifier = Modifier.height(6.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = doc.engineLabel ?: "id:${doc.id.take(8)}",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFontFamily,
          color = Color(0xFF64748B),
          fontSize = 8.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          if (doc.isBusy) {
            ActionChip(text = "CANCEL", color = DocAmber, onClick = onCancelJob, testTag = "doc_cancel_${doc.id}")
          } else {
            ActionChip(text = "REINDEX", color = DocCyan, onClick = onReindex, testTag = "doc_reindex_${doc.id}")
          }
          ActionChip(text = "DELETE", color = DocDanger, onClick = onDelete, testTag = "doc_del_${doc.id}")
        }
      }
    }
  }
}

@Composable
private fun ActionChip(
  text: String,
  color: Color,
  onClick: () -> Unit,
  testTag: String
) {
  Surface(
    shape = RoundedCornerShape(5.dp),
    color = color.copy(alpha = 0.1f),
    border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    modifier = Modifier
      .clickable(onClick = onClick)
      .testTag(testTag)
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      fontFamily = JetBrainsMonoFontFamily,
      fontWeight = FontWeight.Bold,
      color = color,
      fontSize = 7.5.sp,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp)
    )
  }
}

private fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "${bytes}B"
  val kb = bytes / 1024.0
  if (kb < 1024) return String.format(java.util.Locale.US, "%.1fKB", kb)
  val mb = kb / 1024.0
  return String.format(java.util.Locale.US, "%.1fMB", mb)
}
