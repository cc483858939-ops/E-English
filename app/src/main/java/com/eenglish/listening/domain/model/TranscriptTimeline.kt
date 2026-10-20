package com.eenglish.listening.domain.model

import com.eenglish.listening.domain.annotation.contentHash
import kotlinx.serialization.Serializable

@Serializable
data class TranscriptCue(val startMs: Long, val endMs: Long)

@Serializable
data class TranscriptTimeline(val partId: String, val audioSha256: String, val transcriptSha256: String,
    val segments: List<TranscriptCue?>) {
    fun matches(part: ListeningPart): Boolean = partId == part.id && audioSha256 == part.audioSha256 &&
        transcriptSha256 == transcriptTimingHash(part.transcript) && segments.size == part.transcript.segments.size &&
        segments.filterNotNull().let { cues -> cues.isNotEmpty() && cues.all { it.startMs >= 0 && it.endMs > it.startMs } &&
            cues.zipWithNext().all { (a, b) -> a.startMs <= b.startMs && a.endMs <= b.endMs } }

    // Source cues can overlap at sentence boundaries. The later start takes precedence.
    fun activeIndex(positionMs: Long): Int? = segments.indexOfLast {
        it != null && positionMs >= it.startMs && positionMs < it.endMs
    }.takeIf { it >= 0 }

    fun nearestIndex(positionMs: Long): Int? = activeIndex(positionMs) ?: segments.indices.filter { segments[it] != null }
        .minByOrNull { index -> segments[index]!!.let { if (positionMs < it.startMs) it.startMs - positionMs else positionMs - it.endMs } }

    fun fitsDuration(durationMs: Long): Boolean = durationMs > 0 && segments.filterNotNull().all { it.endMs <= durationMs }

    /** Some source tails include <1s of padding beyond the MP3. Clip only the tail, never shift speech starts. */
    fun forDuration(durationMs: Long): TranscriptTimeline? {
        if (durationMs <= 0 || segments.filterNotNull().any { it.startMs >= durationMs || it.endMs - durationMs > 1000 }) return null
        return if (fitsDuration(durationMs)) this else copy(segments = segments.map { cue ->
            cue?.copy(endMs = minOf(cue.endMs, durationMs))
        })
    }
}

@Serializable
data class TranscriptTimelineCatalog(val schemaVersion: Int, val parts: List<TranscriptTimeline>)

fun transcriptTimingHash(transcript: Transcript): String = contentHash(transcript.segments.joinToString("\u0001") {
    it.english + "\u0000" + it.chinese
})
