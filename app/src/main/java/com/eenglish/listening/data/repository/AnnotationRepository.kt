package com.eenglish.listening.data.repository

import androidx.room.withTransaction
import com.eenglish.listening.data.local.*
import com.eenglish.listening.domain.annotation.*
import kotlinx.coroutines.flow.map

class AnnotationRepository(private val database: PracticeDatabase) {
    private val dao = database.highlightDao()
    fun observeAll() = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    suspend fun all() = dao.all().map { it.toDomain() }

    /** Cross-paragraph changes commit atomically; concurrent and repeated operations are safe. */
    suspend fun change(changes: List<AnnotationChange>, add: Boolean) = database.withTransaction {
        val time = System.currentTimeMillis()
        changes.forEach { change ->
            val key = change.key
            require(key.partId.isNotBlank() && key.scope in setOf("transcript", "question") && key.textId.isNotBlank())
            require(key.language in setOf("en", "zh", "und") && key.contentHash.matches(Regex("[a-f0-9]{64}")))
            require(change.start >= 0 && change.end > change.start && change.end <= change.textLength)
            val old = dao.forText(key.partId, key.scope, key.textId, key.language, key.contentHash).map { it.toDomain().range }
            val next = if (add) HighlightRanges.add(old, change.start, change.end, time)
                else HighlightRanges.remove(old, change.start, change.end)
            if (old != next) {
                dao.deleteText(key.partId, key.scope, key.textId, key.language, key.contentHash)
                dao.insert(next.map { HighlightEntity(key.partId, key.scope, key.textId, key.language, key.contentHash, it.start, it.end, it.createdAt) })
            }
        }
    }
    private fun HighlightEntity.toDomain() = Highlight(HighlightKey(partId, scope, textId, language, contentHash),
        HighlightRange(startOffset, endOffset, createdAt))
}
