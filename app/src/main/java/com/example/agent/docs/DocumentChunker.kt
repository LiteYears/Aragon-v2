package com.example.agent.docs

data class ChunkingResult(
  val chunks: List<ChunkDraft>,
  val truncated: Boolean
)

object DocumentChunker {

  fun chunk(structure: DocumentStructure): ChunkingResult {
    val chunks = mutableListOf<ChunkDraft>()
    var ordinal = 0
    var truncated = false

    fun emit(chunk: ChunkDraft): Boolean {
      if (chunks.size >= DocumentLimits.MAX_CHUNKS_PER_DOC) {
        truncated = true
        return false
      }
      chunks.add(chunk.copy(ordinal = ordinal))
      ordinal++
      return true
    }

    var sectionTitle: String? = null
    val pendingText = StringBuilder()
    val pendingPages = mutableListOf<Int>()
    var pendingConfidences = mutableListOf<Float>()

    fun flushPending() {
      val text = pendingText.toString().trim()
      if (text.isEmpty()) {
        pendingText.setLength(0)
        pendingPages.clear()
        pendingConfidences.clear()
        return
      }
      val page = pendingPages.minOrNull()
      val avgConf = if (pendingConfidences.isEmpty()) null else pendingConfidences.average().toFloat()
      emit(
        ChunkDraft(
          docId = structure.docId,
          ordinal = 0,
          text = text,
          searchText = DocumentText.normalizeForSearch(text),
          page = page,
          sectionTitle = sectionTitle,
          direction = DocumentText.detectDirection(text),
          ocrConfidence = avgConf
        )
      )
      pendingText.setLength(0)
      pendingPages.clear()
      pendingConfidences.clear()
    }

    fun appendWithOverlap(text: String) {
      if (pendingText.isEmpty()) {
        pendingText.append(text)
        return
      }
      val projected = pendingText.length + text.length + 1
      if (projected > DocumentLimits.CHUNK_MAX_CHARS) {
        val flushText = pendingText.toString().trim()
        flushPending()
        val tail = overlapTail(flushText)
        if (tail.isNotEmpty()) {
          pendingText.append(tail).append('\n')
        }
        if (text.length > DocumentLimits.CHUNK_MAX_CHARS) {
          var remaining = text
          while (remaining.isNotEmpty() && !truncated) {
            val piece = DocumentText.truncateAtBoundary(remaining, DocumentLimits.CHUNK_MAX_CHARS)
            if (piece.isEmpty()) break
            appendWithOverlap(piece)
            remaining = remaining.substring(piece.length).trimStart()
          }
          return
        }
        pendingText.append(text)
      } else {
        pendingText.append('\n').append(text)
      }
    }

    for (block in structure.blocks) {
      if (truncated) break
      when (block.kind) {
        BlockKind.HEADING -> {
          flushPending()
          sectionTitle = block.text
          pendingText.append(block.text)
          block.page?.let { pendingPages.add(it) }
          block.confidence?.let { pendingConfidences.add(it) }
        }
        BlockKind.PARAGRAPH, BlockKind.LIST_ITEM -> {
          val text = block.text
          appendWithOverlap(text)
          block.page?.let { pendingPages.add(it) }
          block.confidence?.let { pendingConfidences.add(it) }
        }
        BlockKind.TABLE -> {
          flushPending()
          val table = block.table ?: continue
          emitTableChunks(structure.docId, block, table, sectionTitle, ::emit)
        }
        BlockKind.SHEET -> {
          flushPending()
          sectionTitle = null
        }
      }
    }
    flushPending()

    for (sheet in structure.sheets) {
      if (truncated) break
      emitSheetChunks(structure.docId, sheet, ::emit)
    }

    return ChunkingResult(chunks, truncated)
  }

  private fun emitTableChunks(
    docId: String,
    block: DocumentBlock,
    table: ExtractedTable,
    sectionTitle: String?,
    emit: (ChunkDraft) -> Boolean
  ) {
    val headerLine = table.header.joinToString(" | ")
    var index = 0
    var part = 1
    while (index < table.rows.size || (part == 1 && table.rows.isEmpty())) {
      val builder = StringBuilder()
      val location = table.sourceLocation ?: block.page?.let { "page $it" }
      if (location != null) builder.append(location).append(": ")
      if (sectionTitle != null) builder.append(sectionTitle).append(" — ")
      builder.append('\n')
      if (headerLine.isNotBlank()) builder.append(headerLine).append('\n')
      val start = index
      while (index < table.rows.size) {
        val rowLine = table.rows[index].joinToString(" | ")
        if (builder.length + rowLine.length > DocumentLimits.CHUNK_MAX_CHARS && index > start) break
        builder.append(rowLine).append('\n')
        index++
        if (index - start >= 40) break
      }
      val text = builder.toString().trim()
      if (text.isNotBlank()) {
        emit(
          ChunkDraft(
            docId = docId,
            ordinal = 0,
            text = text,
            searchText = DocumentText.normalizeForSearch(text),
            page = block.page,
            rowStart = start + 1,
            rowEnd = index,
            sectionTitle = sectionTitle,
            direction = table.direction,
            ocrConfidence = block.confidence
          )
        )
      }
      part++
      if (table.rows.isEmpty()) break
      if (index >= table.rows.size) break
    }
  }

  private fun emitSheetChunks(
    docId: String,
    sheet: ExtractedSheet,
    emit: (ChunkDraft) -> Boolean
  ) {
    if (sheet.rows.isEmpty()) return

    // If sheet has only 1 row, emit it as a chunk directly
    if (sheet.rows.size == 1) {
      val singleRowText = sheet.rows.first().joinToString(" | ") { it.text }
      val fullChunkText = "Sheet '${sheet.name}'\n$singleRowText"
      emit(
        ChunkDraft(
          docId = docId,
          ordinal = 0,
          text = fullChunkText,
          searchText = DocumentText.normalizeForSearch(fullChunkText),
          sheetName = sheet.name,
          rowStart = 1,
          rowEnd = 1,
          direction = DocumentText.detectDirection(singleRowText),
          ocrConfidence = null
        )
      )
      return
    }

    val headerRow = sheet.rows.first().joinToString(" | ") { it.text }
    var index = 1
    while (index < sheet.rows.size) {
      val builder = StringBuilder()
      builder.append("Sheet '").append(sheet.name).append("'")
      val start = index
      builder.append('\n')
      if (headerRow.isNotBlank()) builder.append(headerRow).append('\n')
      while (index < sheet.rows.size) {
        val row = sheet.rows[index]
        val rowLine = row.joinToString(" | ") { it.text }
        if (builder.length + rowLine.length > DocumentLimits.CHUNK_MAX_CHARS && index > start) break
        builder.append("$index: ").append(rowLine).append('\n')
        index++
        if (index - start >= 40) break
      }
      val text = builder.toString().trim()
      if (text.isNotBlank()) {
        val joined = headerRow + " " + sheet.rows.subList(start, index).joinToString(" ") { r -> r.joinToString(" ") { it.text } }
        emit(
          ChunkDraft(
            docId = docId,
            ordinal = 0,
            text = text,
            searchText = DocumentText.normalizeForSearch(text),
            sheetName = sheet.name,
            rowStart = start + 1,
            rowEnd = index,
            direction = DocumentText.detectDirection(joined),
            ocrConfidence = null
          )
        )
      }
    }
  }

  private fun overlapTail(text: String): String {
    if (text.length <= DocumentLimits.CHUNK_OVERLAP_CHARS) return ""
    val start = text.length - DocumentLimits.CHUNK_OVERLAP_CHARS
    val spaceIndex = text.indexOf(' ', start)
    val begin = if (spaceIndex in 0 until text.length - 20) spaceIndex + 1 else start
    return text.substring(begin).trim()
  }
}
