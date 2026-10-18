package com.example.agent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.agent.docs.DocumentStatus
import com.example.agent.docs.DocumentUiItem
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledBorderSubtle
import com.example.ui.theme.AmoledIconGrey
import com.example.ui.theme.AmoledIconGreyLight
import com.example.ui.theme.AmoledStatusError
import com.example.ui.theme.AmoledStatusSuccess
import com.example.ui.theme.AmoledStatusWarning
import com.example.ui.theme.AmoledSurfaceElevated
import com.example.ui.theme.AmoledTextMuted
import com.example.ui.theme.AmoledTextPrimary
import com.example.ui.theme.AmoledTextSecondary
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun DocumentsDialog(
  documents: List<DocumentUiItem>,
  scope: Set<String>,
  ocrEnabled: Boolean,
  autoCloudOcr: Boolean,
  cloudOcrAvailable: Boolean,
  embeddingLabel: String,
  onDismiss: () -> Unit,
  onAttach: () -> Unit,
  onAttachPhotos: (() -> Unit)? = null,
  onAttachFolder: (() -> Unit)? = null,
  onToggleScope: (String, Boolean) -> Unit,
  onCancelJob: (String) -> Unit,
  onDelete: (String) -> Unit,
  onReindex: (String) -> Unit,
  onToggleOcr: (Boolean) -> Unit,
  onToggleAutoCloudOcr: (Boolean) -> Unit
) {
  var pendingDeleteId by remember { mutableStateOf<String?>(null) }
  val scrollState = rememberScrollState()

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = AmoledSurfaceElevated,
      border = BorderStroke(1.dp, AmoledBorder),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .widthIn(max = 640.dp)
        .testTag("documents_dialog")
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(scrollState)
          .padding(16.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "DOCUMENTS (${documents.size})",
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = AmoledTextPrimary
          )
          TextButton(onClick = onDismiss, modifier = Modifier.testTag("close_documents_dialog_btn")) {
            Text("CLOSE", fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp, color = AmoledTextMuted)
          }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Box(modifier = Modifier.weight(1.2f)) {
            AragonFeedButton(
              text = "+ ATTACH FILES",
              onClick = onAttach,
              variant = AragonButtonVariant.PRIMARY,
              testTag = "attach_documents_dialog_btn"
            )
          }
          if (onAttachPhotos != null) {
            Box(modifier = Modifier.weight(1f)) {
              AragonFeedButton(
                text = "PHOTOS",
                onClick = onAttachPhotos,
                variant = AragonButtonVariant.DEFAULT,
                testTag = "attach_photos_dialog_btn"
              )
            }
          }
          if (onAttachFolder != null) {
            Box(modifier = Modifier.weight(1f)) {
              AragonFeedButton(
                text = "FOLDER",
                onClick = onAttachFolder,
                variant = AragonButtonVariant.DEFAULT,
                testTag = "attach_folder_dialog_btn"
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (documents.isEmpty()) {
          Text(
            text = "No documents imported yet. Attach PDF, Word (.docx), Excel (.xlsx), CSV, TXT, or images (photos of documents). Scanned pages and photos are OCR-processed; Arabic OCR uses the E2B cloud engine when configured.",
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            color = AmoledTextSecondary
          )
        } else {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            documents.forEachIndexed { index, doc ->
              DocumentRow(
                index = index + 1,
                doc = doc,
                inScope = doc.id in scope,
                onToggleScope = { onToggleScope(doc.id, !(doc.id in scope)) },
                onCancelJob = { onCancelJob(doc.id) },
                onReindex = { onReindex(doc.id) },
                onRequestDelete = { pendingDeleteId = doc.id }
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Surface(
          shape = RoundedCornerShape(8.dp),
          color = androidx.compose.ui.graphics.Color(0xFF101010),
          border = BorderStroke(1.dp, AmoledBorderSubtle),
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleOcr(!ocrEnabled) }
            .testTag("optional_ocr_toggle")
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "${if (ocrEnabled) "[x]" else "[ ]"} OCR PROCESSING (OPTIONAL)",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = if (ocrEnabled) AmoledStatusSuccess else AmoledIconGreyLight
              )
              Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (!ocrEnabled) Color(0xFF102010) else Color(0xFF181818),
                border = BorderStroke(1.dp, if (!ocrEnabled) AmoledStatusSuccess.copy(alpha = 0.5f) else AmoledBorderSubtle)
              ) {
                Text(
                  text = if (!ocrEnabled) "DIRECT MODEL UPLOAD ACTIVE" else "OCR ENABLED",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 8.sp,
                  color = if (!ocrEnabled) AmoledStatusSuccess else AmoledIconGreyLight,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
              }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = if (!ocrEnabled) {
                "OCR is OFF: Images & documents upload directly to the AI model's multimodal context instantaneously. No waiting, no OCR transcription errors."
              } else {
                "OCR is ON: On-device & cloud OCR extract text into the local searchable index in addition to direct multimodal upload."
              },
              fontFamily = InterFontFamily,
              fontSize = 10.sp,
              color = AmoledTextSecondary
            )
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
          shape = RoundedCornerShape(8.dp),
          color = androidx.compose.ui.graphics.Color(0xFF101010),
          border = BorderStroke(1.dp, AmoledBorderSubtle),
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleAutoCloudOcr(!autoCloudOcr) }
            .testTag("auto_cloud_ocr_toggle")
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Text(
              text = "${if (autoCloudOcr) "[x]" else "[ ]"} AUTO CLOUD OCR (E2B TESSERACT)",
              fontFamily = JetBrainsMonoFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 10.sp,
              color = if (autoCloudOcr) AmoledStatusSuccess else AmoledIconGrey
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = if (cloudOcrAvailable) {
                "Cloud OCR: available. When Arabic (or low-confidence) text is detected, a downscaled image is sent to your own E2B microVM for Tesseract OCR (ara+eng)."
              } else {
                "Cloud OCR: NOT configured (E2B API key missing). Arabic images will fall back to on-device Latin OCR and may contain errors."
              },
              fontFamily = InterFontFamily,
              fontSize = 10.sp,
              color = if (cloudOcrAvailable) AmoledTextSecondary else AmoledStatusWarning
            )
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "Embeddings: $embeddingLabel",
          fontFamily = InterFontFamily,
          fontSize = 10.sp,
          color = AmoledTextSecondary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "Privacy: document text is indexed on-device. Embeddings send chunk text to the configured provider (NVIDIA NIM / Google). Cloud OCR sends only the downscaled image to your E2B sandbox. Keyword search is always available on-device.",
          fontFamily = InterFontFamily,
          fontSize = 9.sp,
          color = AmoledTextMuted
        )
      }
    }
  }

  val deleteTarget = pendingDeleteId
  if (deleteTarget != null) {
    AlertDialog(
      onDismissRequest = { pendingDeleteId = null },
      containerColor = AmoledSurfaceElevated,
      title = {
        Text(
          "Delete document?",
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp,
          color = AmoledTextPrimary
        )
      },
      text = {
        Text(
          "This removes the document, its extracted structure, and its search index from the device.",
          fontFamily = InterFontFamily,
          fontSize = 12.sp,
          color = AmoledTextSecondary
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onDelete(deleteTarget)
            pendingDeleteId = null
          },
          modifier = Modifier.testTag("confirm_delete_document_btn")
        ) {
          Text("DELETE", color = AmoledStatusError, fontWeight = FontWeight.Bold, fontFamily = JetBrainsMonoFontFamily)
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDeleteId = null }) {
          Text("CANCEL", color = AmoledTextMuted, fontFamily = JetBrainsMonoFontFamily)
        }
      }
    )
  }
}

@Composable
private fun DocumentRow(
  index: Int,
  doc: DocumentUiItem,
  inScope: Boolean,
  onToggleScope: () -> Unit,
  onCancelJob: () -> Unit,
  onReindex: () -> Unit,
  onRequestDelete: () -> Unit
) {
  val statusColor = when (doc.status) {
    DocumentStatus.READY, DocumentStatus.READY_LEXICAL -> AmoledStatusSuccess
    DocumentStatus.PROCESSING, DocumentStatus.IMPORTING -> AmoledStatusWarning
    DocumentStatus.FAILED -> AmoledStatusError
    DocumentStatus.CANCELLED -> AmoledIconGrey
  }

  Surface(
    shape = RoundedCornerShape(8.dp),
    color = androidx.compose.ui.graphics.Color(0xFF101010),
    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("document_row_${doc.id}")
  ) {
    Column(modifier = Modifier.padding(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "D$index  ${doc.displayName}",
          fontFamily = InterFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
          color = AmoledTextPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
        Text(
          text = doc.statusLabel,
          fontFamily = JetBrainsMonoFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 9.sp,
          color = statusColor,
          maxLines = 1
        )
      }

      Spacer(modifier = Modifier.height(4.dp))

      val infoParts = mutableListOf<String>()
      infoParts.add(doc.type.name.lowercase())
      infoParts.add("${doc.sizeBytes / 1024} KB")
      if (doc.pageCount > 0) infoParts.add("${doc.pageCount}p")
      if (doc.sheetCount > 0) infoParts.add("${doc.sheetCount} sheets")
      infoParts.add("${doc.chunkCount} chunks")
      if (doc.engineLabel != null) infoParts.add(doc.engineLabel)
      if (doc.embeddingsLabel != null) infoParts.add(doc.embeddingsLabel)
      if (doc.warningCount > 0) infoParts.add("${doc.warningCount} warning(s)")
      Text(
        text = infoParts.joinToString(" · "),
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 9.sp,
        color = AmoledIconGreyLight
      )

      if (doc.error != null) {
        Spacer(modifier = Modifier.height(3.dp))
        Text(
          text = doc.error,
          fontFamily = InterFontFamily,
          fontSize = 10.sp,
          color = AmoledStatusError,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        AragonFeedButton(
          text = if (inScope) "[IN SCOPE]" else "[OUT OF SCOPE]",
          onClick = onToggleScope,
          variant = if (inScope) AragonButtonVariant.SUCCESS else AragonButtonVariant.MUTED,
          testTag = "doc_scope_toggle_${doc.id}"
        )
        if (doc.isBusy) {
          AragonFeedButton(
            text = "CANCEL",
            onClick = onCancelJob,
            variant = AragonButtonVariant.DANGER,
            testTag = "doc_cancel_${doc.id}"
          )
        }
        if (doc.isReady || doc.status == DocumentStatus.FAILED || doc.status == DocumentStatus.CANCELLED) {
          AragonFeedButton(
            text = "REINDEX",
            onClick = onReindex,
            variant = AragonButtonVariant.MUTED,
            testTag = "doc_reindex_${doc.id}"
          )
        }
        AragonFeedButton(
          text = "DELETE",
          onClick = onRequestDelete,
          variant = AragonButtonVariant.DANGER,
          testTag = "doc_delete_${doc.id}"
        )
      }
    }
  }
}
