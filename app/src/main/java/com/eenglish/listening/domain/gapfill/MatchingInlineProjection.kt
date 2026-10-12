package com.eenglish.listening.domain.gapfill

import com.eenglish.listening.domain.annotation.HighlightRange

/**
 * A display slice that keeps every rendered UTF-16 character tied to its exact source offset.
 * Matching row mode uses this when it separates a shared prompt from numbered rows.
 */
class MatchingInlineProjection private constructor(
    val source: String,
    val text: String,
    val segments: List<Segment>,
) {
    data class Segment(val displayStart: Int, val sourceStart: Int, val length: Int) {
        val displayEnd: Int get() = displayStart + length
        val sourceEnd: Int get() = sourceStart + length
    }

    data class OffsetRange(val start: Int, val end: Int)

    init {
        require(segments.isNotEmpty())
        require(segments.all { segment ->
            segment.length > 0 && segment.displayStart >= 0 && segment.displayEnd <= text.length &&
                segment.sourceStart >= 0 && segment.sourceEnd <= source.length &&
                text.substring(segment.displayStart, segment.displayEnd) == source.substring(segment.sourceStart, segment.sourceEnd)
        })
        require(segments.zipWithNext().all { (left, right) ->
            left.displayEnd <= right.displayStart && left.sourceEnd <= right.sourceStart
        })
    }

    /** Translate a displayed selection to the one or more contiguous original source slices. */
    fun sourceRanges(displayStart: Int, displayEnd: Int): List<OffsetRange> {
        require(displayStart >= 0 && displayEnd > displayStart && displayEnd <= text.length)
        return segments.mapNotNull { segment ->
            val from = maxOf(displayStart, segment.displayStart)
            val to = minOf(displayEnd, segment.displayEnd)
            if (from >= to) null else OffsetRange(
                segment.sourceStart + from - segment.displayStart,
                segment.sourceStart + to - segment.displayStart,
            )
        }
    }

    /** Translate original annotation ranges into the displayed projection. */
    fun displayRanges(sourceRanges: List<HighlightRange>): List<HighlightRange> =
        sourceRanges.flatMap { range -> segments.mapNotNull { segment ->
            val from = maxOf(range.start, segment.sourceStart)
            val to = minOf(range.end, segment.sourceEnd)
            if (from >= to) null else range.copy(
                start = segment.displayStart + from - segment.sourceStart,
                end = segment.displayStart + to - segment.sourceStart,
            )
        } }

    companion object {
        /** The projection is an exact UTF-16 substring; no text search or normalization is used. */
        fun sourceRange(source: String, start: Int, end: Int): MatchingInlineProjection {
            require(start >= 0 && end > start && end <= source.length)
            return MatchingInlineProjection(
                source = source,
                text = source.substring(start, end),
                segments = listOf(Segment(displayStart = 0, sourceStart = start, length = end - start)),
            )
        }
    }
}
