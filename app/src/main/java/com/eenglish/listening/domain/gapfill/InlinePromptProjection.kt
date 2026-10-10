package com.eenglish.listening.domain.gapfill

import com.eenglish.listening.domain.annotation.HighlightRange

/**
 * Ephemeral display projection. Stored Question.prompt and its SHA-256 remain untouched.
 * Every number + underscore run (including embedded CR/LF) becomes exactly ONE U+FFFC.
 * A ReplacementSpan may only cover that single character, never a hard line break.
 */
data class InlinePromptProjection private constructor(
    val source: String, val text: String, val slots: List<Slot>
) {
    data class Slot(val gap: NumberedGap, val offset: Int)

    fun sourceOffset(displayOffset: Int): Int {
        require(displayOffset in 0..text.length)
        var removed = 0
        slots.forEach { slot ->
            if (displayOffset <= slot.offset) return displayOffset + removed
            removed += slot.gap.end - slot.gap.start - 1
        }
        return displayOffset + removed
    }

    /** Highlight endpoints inside a collapsed answer position are clipped to its visible chip. */
    fun displayOffset(sourceOffset: Int, end: Boolean): Int {
        require(sourceOffset in 0..source.length)
        var removed = 0
        slots.forEach { slot ->
            if (sourceOffset < slot.gap.start) return sourceOffset - removed
            if (sourceOffset < slot.gap.end)
                return slot.offset + if (end) 1 else 0
            removed += slot.gap.end - slot.gap.start - 1
        }
        return sourceOffset - removed
    }

    fun visibleHighlights(sourceRanges: List<HighlightRange>) = sourceRanges.mapNotNull { range ->
        val start = displayOffset(range.start, end = false)
        val end = displayOffset(range.end, end = true)
        if (start < end) range.copy(start = start, end = end) else null
    }

    companion object {
        fun of(group: GapFillGroup): InlinePromptProjection {
            val source = group.prompt
            val text = StringBuilder()
            val slots = mutableListOf<Slot>()
            var cursor = 0
            group.gaps.forEach { gap ->
                require(gap.start >= cursor && gap.end <= source.length)
                text.append(source, cursor, gap.start)
                slots += Slot(gap, text.length)
                text.append('\uFFFC')
                cursor = gap.end
            }
            text.append(source, cursor, source.length)
            val result = InlinePromptProjection(source, text.toString(), slots)
            require(result.slots.size == group.questions.size)
            require(result.slots.all { it.offset >= 0 && result.text[it.offset] == '\uFFFC' })
            return result
        }
    }
}
