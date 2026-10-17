package com.example.agent.docs

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "documents")
data class DocumentEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "display_name") val displayName: String,
  @ColumnInfo(name = "type") val type: String,
  @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
  @ColumnInfo(name = "sha256") val sha256: String,
  @ColumnInfo(name = "status") val status: String,
  @ColumnInfo(name = "error") val error: String?,
  @ColumnInfo(name = "page_count") val pageCount: Int,
  @ColumnInfo(name = "sheet_count") val sheetCount: Int,
  @ColumnInfo(name = "engine_label") val engineLabel: String?,
  @ColumnInfo(name = "warnings_json") val warningsJson: String?,
  @ColumnInfo(name = "in_scope") val inScope: Boolean,
  @ColumnInfo(name = "has_embeddings") val hasEmbeddings: Boolean,
  @ColumnInfo(name = "embedding_provider") val embeddingProvider: String?,
  @ColumnInfo(name = "embedding_model") val embeddingModel: String?,
  @ColumnInfo(name = "embedding_dims") val embeddingDims: Int,
  @ColumnInfo(name = "original_path") val originalPath: String?,
  @ColumnInfo(name = "structure_path") val structurePath: String?,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(tableName = "chunks", indices = [Index("doc_id")])
data class ChunkEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  @ColumnInfo(name = "doc_id") val docId: String,
  @ColumnInfo(name = "ordinal") val ordinal: Int,
  @ColumnInfo(name = "text") val text: String,
  @ColumnInfo(name = "search_text") val searchText: String,
  @ColumnInfo(name = "page") val page: Int?,
  @ColumnInfo(name = "sheet_name") val sheetName: String?,
  @ColumnInfo(name = "row_start") val rowStart: Int?,
  @ColumnInfo(name = "row_end") val rowEnd: Int?,
  @ColumnInfo(name = "section_title") val sectionTitle: String?,
  @ColumnInfo(name = "direction") val direction: String,
  @ColumnInfo(name = "ocr_confidence") val ocrConfidence: Float?,
  @ColumnInfo(name = "embedding") val embedding: ByteArray?,
  @ColumnInfo(name = "embedding_dims") val embeddingDims: Int
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "chunk_fts")
data class ChunkFtsEntity(
  @PrimaryKey
  @ColumnInfo(name = "rowid") val rowid: Long,
  @ColumnInfo(name = "search_text") val searchText: String
)

@Dao
interface DocumentDao {

  @Query("SELECT * FROM documents ORDER BY created_at DESC")
  suspend fun allDocuments(): List<DocumentEntity>

  @Query("SELECT * FROM documents WHERE id = :id")
  suspend fun documentById(id: String): DocumentEntity?

  @Query("SELECT * FROM documents WHERE sha256 = :sha AND status NOT IN ('FAILED','CANCELLED') LIMIT 1")
  suspend fun documentBySha(sha: String): DocumentEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertDocument(entity: DocumentEntity)

  @Query("DELETE FROM documents WHERE id = :id")
  suspend fun deleteDocument(id: String)

  @Query("SELECT id FROM chunks WHERE doc_id = :docId")
  suspend fun chunkIdsForDoc(docId: String): List<Long>

  @Query("SELECT * FROM chunks WHERE doc_id = :docId ORDER BY ordinal")
  suspend fun chunksForDoc(docId: String): List<ChunkEntity>

  @Query("SELECT COUNT(*) FROM chunks WHERE doc_id = :docId")
  suspend fun chunkCount(docId: String): Int

  @Query("DELETE FROM chunks WHERE doc_id = :docId")
  suspend fun deleteChunksForDoc(docId: String)

  @Query("DELETE FROM chunk_fts WHERE rowid IN (:rowids)")
  suspend fun deleteFtsRows(rowids: List<Long>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertChunks(chunks: List<ChunkEntity>): List<Long>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertFts(rows: List<ChunkFtsEntity>)

  @Query("SELECT rowid FROM chunk_fts WHERE chunk_fts MATCH :matchQuery LIMIT :limit")
  suspend fun searchFtsIds(matchQuery: String, limit: Int): List<Long>

  @Query("SELECT * FROM chunks WHERE id IN (:ids)")
  suspend fun chunksByIds(ids: List<Long>): List<ChunkEntity>

  @Query("SELECT * FROM chunks WHERE embedding IS NOT NULL")
  suspend fun chunksWithEmbeddings(): List<ChunkEntity>
}

@Database(
  entities = [DocumentEntity::class, ChunkEntity::class, ChunkFtsEntity::class],
  version = 1,
  exportSchema = false
)
abstract class DocumentDatabase : RoomDatabase() {
  abstract fun documentDao(): DocumentDao

  companion object {
    fun build(context: Context): DocumentDatabase =
      Room.databaseBuilder(context.applicationContext, DocumentDatabase::class.java, "aragon_documents.db")
        .fallbackToDestructiveMigration(true)
        .build()
  }
}
