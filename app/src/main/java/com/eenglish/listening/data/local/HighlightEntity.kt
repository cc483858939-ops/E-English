package com.eenglish.listening.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "highlights", primaryKeys = ["partId", "scope", "textId", "language", "contentHash", "startOffset", "endOffset"])
data class HighlightEntity(val partId: String, val scope: String, val textId: String, val language: String,
    val contentHash: String, val startOffset: Int, val endOffset: Int, val createdAt: Long)

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights ORDER BY partId, scope, textId, language, startOffset")
    fun observeAll(): Flow<List<HighlightEntity>>
    @Query("SELECT * FROM highlights ORDER BY partId, scope, textId, language, startOffset")
    suspend fun all(): List<HighlightEntity>
    @Query("SELECT * FROM highlights WHERE partId = :partId AND scope = :scope AND textId = :textId AND language = :language AND contentHash = :hash ORDER BY startOffset")
    suspend fun forText(partId: String, scope: String, textId: String, language: String, hash: String): List<HighlightEntity>
    @Query("DELETE FROM highlights WHERE partId = :partId AND scope = :scope AND textId = :textId AND language = :language AND contentHash = :hash")
    suspend fun deleteText(partId: String, scope: String, textId: String, language: String, hash: String)
    @Insert suspend fun insert(ranges: List<HighlightEntity>)
}
