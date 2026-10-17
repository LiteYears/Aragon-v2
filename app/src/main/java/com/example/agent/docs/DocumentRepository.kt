package com.example.agent.docs

class DocumentRepository(private val dao: DocumentDao) {

  suspend fun allDocuments(): List<DocumentEntity> = dao.allDocuments()

  suspend fun documentById(id: String): DocumentEntity? = dao.documentById(id)

  suspend fun findBySha(sha: String): DocumentEntity? = dao.documentBySha(sha)

  suspend fun upsert(entity: DocumentEntity) = dao.upsertDocument(entity)

  suspend fun delete(id: String) {
    val ids = dao.chunkIdsForDoc(id)
    if (ids.isNotEmpty()) dao.deleteFtsRows(ids)
    dao.deleteChunksForDoc(id)
    dao.deleteDocument(id)
  }

  suspend fun clearChunks(docId: String) {
    val ids = dao.chunkIdsForDoc(docId)
    if (ids.isNotEmpty()) dao.deleteFtsRows(ids)
    dao.deleteChunksForDoc(docId)
  }

  suspend fun chunkCount(docId: String): Int = dao.chunkCount(docId)

  suspend fun chunksForDoc(docId: String): List<ChunkEntity> = dao.chunksForDoc(docId)

  suspend fun documentsByIds(ids: Collection<String>): Map<String, DocumentEntity> {
    val result = mutableMapOf<String, DocumentEntity>()
    for (id in ids) {
      dao.documentById(id)?.let { result[id] = it }
    }
    return result
  }

  suspend fun insertChunks(drafts: List<ChunkDraft>, embeddings: Map<Int, FloatArray>?): Int {
    if (drafts.isEmpty()) return 0
    val entities = drafts.map { draft ->
      val vector = embeddings?.get(draft.ordinal)
      ChunkEntity(
        docId = draft.docId,
        ordinal = draft.ordinal,
        text = draft.text,
        searchText = draft.searchText,
        page = draft.page,
        sheetName = draft.sheetName,
        rowStart = draft.rowStart,
        rowEnd = draft.rowEnd,
        sectionTitle = draft.sectionTitle,
        direction = draft.direction.name,
        ocrConfidence = draft.ocrConfidence,
        embedding = vector?.let { EmbeddingCodec.toBytes(it) },
        embeddingDims = vector?.size ?: 0
      )
    }
    val rowIds = dao.insertChunks(entities)
    val ftsRows = mutableListOf<ChunkFtsEntity>()
    for (i in rowIds.indices) {
      ftsRows.add(ChunkFtsEntity(rowIds[i], drafts[i].searchText))
    }
    if (ftsRows.isNotEmpty()) dao.insertFts(ftsRows)
    return rowIds.size
  }

  suspend fun searchFtsIds(matchQuery: String, limit: Int): List<Long> =
    dao.searchFtsIds(matchQuery, limit)

  suspend fun chunksByIds(ids: List<Long>): List<ChunkEntity> =
    if (ids.isEmpty()) emptyList() else dao.chunksByIds(ids)

  suspend fun chunksWithEmbeddings(): List<ChunkEntity> = dao.chunksWithEmbeddings()
}
