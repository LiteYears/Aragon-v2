package com.example.agent.docs

import java.io.File

enum class DocumentType {
  PDF, DOCX, XLSX, CSV, TXT, IMAGE, UNSUPPORTED;

  val isSpreadsheet: Boolean get() = this == XLSX || this == CSV
  val isTextual: Boolean get() = this == DOCX || this == XLSX || this == CSV || this == TXT
}

enum class DocumentStatus {
  IMPORTING, PROCESSING, READY, READY_LEXICAL, FAILED, CANCELLED
}

enum class TextDirection { LTR, RTL, MIXED, UNKNOWN }

enum class BlockKind { HEADING, PARAGRAPH, LIST_ITEM, TABLE, SHEET }

enum class CellKind { TEXT, NUMBER, BOOLEAN, DATE, FORMULA, BLANK }

data class CellValue(
  val text: String,
  val kind: CellKind,
  val formula: String? = null,
  val dateIso: String? = null
)

data class ExtractedTable(
  val header: List<String>,
  val rows: List<List<String>>,
  val direction: TextDirection = TextDirection.UNKNOWN,
  val confidence: String = "HIGH",
  val sourceLocation: String? = null,
  val mergedRanges: List<String> = emptyList(),
  val formulaCells: Map<String, String> = emptyMap(),
  val uncertain: Boolean = false
)

data class ExtractedSheet(
  val name: String,
  val rows: List<List<CellValue>>,
  val columnWidths: List<Double> = emptyList(),
  val rtl: Boolean = false,
  val mergedRanges: List<String> = emptyList()
)

data class DocumentBlock(
  val kind: BlockKind,
  val text: String,
  val headingLevel: Int = 0,
  val orderedHint: Boolean = false,
  val page: Int? = null,
  val sheetName: String? = null,
  val rowStart: Int? = null,
  val rowEnd: Int? = null,
  val table: ExtractedTable? = null,
  val direction: TextDirection = TextDirection.UNKNOWN,
  val language: String? = null,
  val confidence: Float? = null,
  val uncertain: Boolean = false,
  val sourceEngine: String? = null
)

data class DocumentStructure(
  val docId: String,
  val displayName: String,
  val type: DocumentType,
  val blocks: List<DocumentBlock>,
  val sheets: List<ExtractedSheet> = emptyList(),
  val pageCount: Int = 0,
  val sheetCount: Int = 0,
  val primaryLanguage: String? = null,
  val direction: TextDirection = TextDirection.UNKNOWN,
  val engines: List<String> = emptyList(),
  val warnings: List<String> = emptyList(),
  val textLength: Int = 0
) {
  fun toSummaryText(): String = blocks.joinToString("\n\n") { block ->
    when (block.kind) {
      BlockKind.HEADING -> "#".repeat(block.headingLevel.coerceIn(1, 6)) + " " + block.text
      BlockKind.TABLE -> block.table?.let { table ->
        (listOf(table.header) + table.rows).joinToString("\n") { row -> "| " + row.joinToString(" | ") + " |" }
      } ?: block.text
      BlockKind.SHEET -> block.text
      BlockKind.LIST_ITEM -> "- " + block.text
      else -> block.text
    }
  }
}

data class DocumentUiItem(
  val id: String,
  val displayName: String,
  val type: DocumentType,
  val sizeBytes: Long,
  val status: DocumentStatus,
  val phaseNote: String? = null,
  val chunkCount: Int = 0,
  val pageCount: Int = 0,
  val sheetCount: Int = 0,
  val engineLabel: String? = null,
  val warningCount: Int = 0,
  val error: String? = null,
  val createdAt: Long = 0L,
  val inScope: Boolean = true,
  val hasEmbeddings: Boolean = false,
  val embeddingsLabel: String? = null,
  val originalPath: String? = null,
  val structurePath: String? = null
) {
  val isReady: Boolean get() = status == DocumentStatus.READY || status == DocumentStatus.READY_LEXICAL
  val isBusy: Boolean get() = status == DocumentStatus.IMPORTING || status == DocumentStatus.PROCESSING
  val statusLabel: String
    get() = when (status) {
      DocumentStatus.IMPORTING -> "IMPORTING"
      DocumentStatus.PROCESSING -> phaseNote ?: "PROCESSING"
      DocumentStatus.READY -> if (hasEmbeddings) "READY" else "READY (LEXICAL)"
      DocumentStatus.READY_LEXICAL -> "READY (LEXICAL)"
      DocumentStatus.FAILED -> "FAILED"
      DocumentStatus.CANCELLED -> "CANCELLED"
    }
}

data class ChunkDraft(
  val docId: String,
  val ordinal: Int,
  val text: String,
  val searchText: String,
  val page: Int? = null,
  val sheetName: String? = null,
  val rowStart: Int? = null,
  val rowEnd: Int? = null,
  val sectionTitle: String? = null,
  val direction: TextDirection = TextDirection.UNKNOWN,
  val ocrConfidence: Float? = null
)

data class SearchHit(
  val chunkId: Long,
  val docId: String,
  val docName: String,
  val text: String,
  val page: Int?,
  val sheetName: String?,
  val rowStart: Int?,
  val rowEnd: Int?,
  val score: Double,
  val retrievalMode: String,
  val direction: TextDirection
)

data class EmbeddingProfile(
  val provider: String,
  val model: String,
  val dims: Int
)

data class ImportOutcome(
  val docId: String?,
  val displayName: String,
  val ok: Boolean,
  val message: String
)

data class ConversionReport(
  val outputFile: File,
  val format: String,
  val warnings: List<String> = emptyList(),
  val tablesWritten: Int = 0,
  val sheetsWritten: Int = 0,
  val formulasRecovered: Int = 0,
  val formulasDropped: Int = 0,
  val rtlApplied: Boolean = false,
  val verification: String = "",
  val verified: Boolean = false
)

class DocumentException(message: String) : Exception(message)

object DocumentLimits {
  const val MAX_FILE_BYTES = 50L * 1024 * 1024
  const val MAX_UNCOMPRESSED_BYTES = 250L * 1024 * 1024
  const val MAX_ZIP_ENTRIES = 4000
  const val MAX_COMPRESSION_RATIO = 200
  const val MAX_OCR_PAGES = 30
  const val MAX_OCR_IMAGE_DIM = 2600
  const val MAX_PDF_RENDER_DIM = 2200
  const val CHUNK_MAX_CHARS = 1100
  const val CHUNK_OVERLAP_CHARS = 150
  const val MAX_CHUNKS_PER_DOC = 6000
  const val EMBED_BATCH_SIZE = 16
  const val MAX_XLSX_SHEETS = 30
  const val MAX_XLSX_CELLS_PER_SHEET = 60000
  const val MAX_CSV_CHARS = 24 * 1024 * 1024
  const val MAX_TABLE_ROWS_PER_BLOCK = 400
}
