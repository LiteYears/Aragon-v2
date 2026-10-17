package com.example.agent.docs

data class RetrievalOutcome(
  val hits: List<SearchHit>,
  val mode: String,
  val notes: List<String> = emptyList()
)

class DocumentRetriever(
  private val repository: DocumentRepository,
  private val embeddingProviderSupplier: () -> EmbeddingProvider?
) {

  suspend fun search(query: String, k: Int, scopeDocIds: Set<String>?): RetrievalOutcome {
    val notes = mutableListOf<String>()
    val cleanQuery = query.trim().take(400)
    if (cleanQuery.isEmpty()) return RetrievalOutcome(emptyList(), "lexical", listOf("Empty query"))

    val scopeDocs: Map<String, DocumentEntity> = run {
      val all = repository.allDocuments()
        .filter { it.status == DocumentStatus.READY.name || it.status == DocumentStatus.READY_LEXICAL.name }
        .filter { scopeDocIds == null || it.id in scopeDocIds }
      all.associateBy { it.id }
    }
    if (scopeDocs.isEmpty()) {
      return RetrievalOutcome(emptyList(), "lexical", listOf("No ready documents are in scope"))
    }

    val legLimit = maxOf(30, k * 4)
    val ftsRanking = mutableListOf<Long>()
    val ftsQueryAnd = DocumentText.buildFtsQuery(cleanQuery)
    if (ftsQueryAnd != null) {
      val ids = try {
        repository.searchFtsIds(ftsQueryAnd, legLimit * 2)
      } catch (_: Exception) {
        emptyList()
      }
      if (ids.isNotEmpty()) {
        val chunks = repository.chunksByIds(ids).filter { it.docId in scopeDocs.keys }
        val byId = chunks.associateBy { it.id }
        for (id in ids) {
          val chunk = byId[id] ?: continue
          if (!ftsRanking.contains(chunk.id)) {
            ftsRanking.add(chunk.id)
            if (ftsRanking.size >= legLimit) break
          }
        }
      }
    }

    // Fallback: If AND returned few or no hits, try OR search for flexible keyword matching
    if (ftsRanking.size < k) {
      val ftsQueryOr = DocumentText.buildFtsQueryOr(cleanQuery)
      if (ftsQueryOr != null && ftsQueryOr != ftsQueryAnd) {
        val orIds = try {
          repository.searchFtsIds(ftsQueryOr, legLimit * 2)
        } catch (_: Exception) {
          emptyList()
        }
        if (orIds.isNotEmpty()) {
          val chunks = repository.chunksByIds(orIds).filter { it.docId in scopeDocs.keys }
          val byId = chunks.associateBy { it.id }
          for (id in orIds) {
            val chunk = byId[id] ?: continue
            if (!ftsRanking.contains(chunk.id)) {
              ftsRanking.add(chunk.id)
              if (ftsRanking.size >= legLimit) break
            }
          }
        }
      }
    }

    var vectorUsed = false
    val vectorRanking = mutableListOf<Long>()
    val provider = embeddingProviderSupplier()
    if (provider != null) {
      val providerProfile = provider.profile
      val compatibleDocs = scopeDocs.values.filter {
        it.hasEmbeddings && it.embeddingProvider == providerProfile.provider && it.embeddingModel == providerProfile.model
      }
      if (compatibleDocs.isEmpty()) {
        notes.add("Semantic retrieval unavailable for the current scope (embeddings were built with a different or no provider); using lexical search")
      } else {
        val queryVector = try {
          provider.embedQuery(cleanQuery)
        } catch (e: Exception) {
          notes.add("Semantic search failed (${e.message ?: "embedding error"}); using lexical search")
          null
        }
        if (queryVector != null) {
          val compatibleIds = compatibleDocs.map { it.id }.toSet()
          val candidates = try {
            repository.chunksWithEmbeddings().filter { it.docId in compatibleIds && it.embedding != null }
          } catch (_: Exception) {
            emptyList()
          }
          val scored = mutableListOf<Pair<Long, Double>>()
          for (chunk in candidates.take(6000)) {
            val bytes = chunk.embedding ?: continue
            if (bytes.size / 4 != queryVector.size) continue
            val vector = EmbeddingCodec.fromBytes(bytes)
            scored.add(chunk.id to EmbeddingCodec.cosine(queryVector, vector))
          }
          if (scored.isNotEmpty()) {
            vectorUsed = true
            scored.sortByDescending { it.second }
            for (entry in scored.take(legLimit)) vectorRanking.add(entry.first)
          }
        }
      }
    } else {
      notes.add("Embeddings are not configured; using lexical (keyword) search only")
    }

    val fusedScores = mutableMapOf<Long, Double>()
    for ((rank, id) in ftsRanking.withIndex()) {
      fusedScores[id] = (fusedScores[id] ?: 0.0) + 1.0 / (60.0 + (rank + 1))
    }
    for ((rank, id) in vectorRanking.withIndex()) {
      fusedScores[id] = (fusedScores[id] ?: 0.0) + 1.0 / (60.0 + (rank + 1))
    }
    if (fusedScores.isEmpty()) {
      return RetrievalOutcome(emptyList(), if (vectorUsed) "semantic+lexical" else "lexical", notes)
    }

    val orderedIds = fusedScores.entries.sortedByDescending { it.value }.map { it.key }
    val chunkMap = repository.chunksByIds(orderedIds).associateBy { it.id }

    val hits = mutableListOf<SearchHit>()
    val perDocCount = mutableMapOf<String, Int>()
    val mode = if (vectorUsed) "semantic+lexical" else "lexical"

    for (id in orderedIds) {
      if (hits.size >= k) break
      val chunk = chunkMap[id] ?: continue
      val timesUsed = perDocCount[chunk.docId] ?: 0
      if (timesUsed >= 3 && hits.size >= k / 2) continue
      val doc = scopeDocs[chunk.docId] ?: continue
      perDocCount[chunk.docId] = timesUsed + 1
      hits.add(
        SearchHit(
          chunkId = chunk.id,
          docId = chunk.docId,
          docName = doc.displayName,
          text = chunk.text,
          page = chunk.page,
          sheetName = chunk.sheetName,
          rowStart = chunk.rowStart,
          rowEnd = chunk.rowEnd,
          score = fusedScores[id] ?: 0.0,
          retrievalMode = mode,
          direction = runCatching { TextDirection.valueOf(chunk.direction) }.getOrDefault(TextDirection.UNKNOWN)
        )
      )
    }

    return RetrievalOutcome(hits, mode, notes)
  }
}
