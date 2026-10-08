package com.eenglish.listening.domain.annotation

import com.eenglish.listening.domain.model.Transcript
import java.security.MessageDigest

data class HighlightKey(val partId: String, val scope: String, val textId: String,
    val language: String, val contentHash: String)
data class HighlightRange(val start: Int, val end: Int, val createdAt: Long)
data class Highlight(val key: HighlightKey, val range: HighlightRange)
data class AnnotationChange(val key: HighlightKey, val start: Int, val end: Int, val textLength: Int)

/** All offsets use UTF-16, matching TextView.selectionStart/End. No text search is involved. */
object HighlightRanges {
    fun add(existing: List<HighlightRange>, start: Int, end: Int, time: Long): List<HighlightRange> {
        require(start >= 0 && end > start)
        val result = mutableListOf<HighlightRange>()
        (existing + HighlightRange(start, end, time)).sortedBy { it.start }.forEach { next ->
            val last = result.lastOrNull()
            if (last != null && next.start <= last.end) {
                result[result.lastIndex] = HighlightRange(last.start, maxOf(last.end, next.end), minOf(last.createdAt, next.createdAt))
            } else result += next
        }
        return result
    }
    fun remove(existing: List<HighlightRange>, start: Int, end: Int): List<HighlightRange> {
        require(start >= 0 && end > start)
        return existing.flatMap { range ->
            if (end <= range.start || start >= range.end) listOf(range)
            else buildList {
                if (start > range.start) add(range.copy(end = start))
                if (end < range.end) add(range.copy(start = end))
            }
        }
    }
}

data class TextBlock(val start: Int, val content: String, val scope: String, val textId: String, val language: String) {
    val end get() = start + content.length
    val hash: String = contentHash(content)
    fun key(partId: String) = HighlightKey(partId, scope, textId, language, hash)
}

data class AnnotationDocument(val text: String, val blocks: List<TextBlock>) {
    init {
        require(blocks.all { it.start >= 0 && it.end <= text.length && text.substring(it.start, it.end) == it.content })
        require(blocks.zipWithNext().all { (a, b) -> a.end <= b.start })
    }
    fun selection(partId: String, start: Int, end: Int): List<AnnotationChange> {
        require(start >= 0 && end <= text.length && end > start)
        return blocks.mapNotNull { block ->
            val from = maxOf(start, block.start)
            val to = minOf(end, block.end)
            if (from < to) AnnotationChange(block.key(partId), from - block.start, to - block.start, block.content.length) else null
        }
    }
    fun visibleRanges(partId: String, highlights: List<Highlight>): List<HighlightRange> = blocks.flatMap { block ->
        highlights.filter { it.key == block.key(partId) && it.range.start >= 0 && it.range.end > it.range.start && it.range.end <= block.content.length }
            .map { it.range.copy(start = block.start + it.range.start, end = block.start + it.range.end) }
    }
    companion object {
        fun single(text: String, scope: String, id: String, language: String) =
            AnnotationDocument(text, listOf(TextBlock(0, text, scope, id, language)))

        fun transcript(transcript: Transcript, language: String): AnnotationDocument {
            require(language == "en" || language == "zh")
            val full = if (language == "en") transcript.english else transcript.chinese
            var offset = full.length - full.trimStart().length
            val blocks = transcript.segments.mapIndexedNotNull { index, segment ->
                val value = (if (language == "en") segment.english else segment.chinese).trim()
                if (value.isEmpty()) null else TextBlock(offset, value, "transcript", "segment:$index", language)
                    .also { offset += value.length + 2 }
            }
            // The existing import validator requires exactly this ordered paragraph composition.
            // Fail explicitly rather than relocating repeated words or changed content by guessing.
            require(full.trim() == blocks.joinToString("\n\n") { it.content }) { "Transcript annotation structure mismatch" }
            return AnnotationDocument(full, blocks)
        }
    }
}

fun contentHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
