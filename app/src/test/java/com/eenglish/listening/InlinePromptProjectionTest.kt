package com.eenglish.listening

import com.eenglish.listening.domain.annotation.HighlightRange
import com.eenglish.listening.domain.gapfill.*
import com.eenglish.listening.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class InlinePromptProjectionTest {
    private val source = """
Questions 26–30
Complete the summary below.
A 26
 ______ marker, B 27 _____ marker.
C 28 ____ marker, D 29 _____ and E 30 ____ end.
    """.trimIndent()
    private val questions = (26..30).map { number ->
        Question("synthetic-$number", number, QuestionType.TEXT_INPUT, source, emptyList(),
            "synthetic", wordLimit = WordLimit(3, 0))
    }

    @Test fun eachMultilineGapBecomesExactlyOneInlineAnchor() {
        val parsed = GapFillGroupParser.parse(questions)
        val group = (parsed.single() as QuestionDisplayItem.InlineGroup).group
        val projection = InlinePromptProjection.of(group)
        assertEquals(5, projection.slots.size)
        assertEquals(5, projection.text.count { it == '\uFFFC' })
        assertTrue(projection.text.startsWith("Questions 26–30"))
        assertFalse(projection.text.contains("26\n"))
        group.gaps.forEachIndexed { index, gap ->
            val slot = projection.slots[index]
            assertEquals(gap.number, slot.gap.number)
            assertEquals('\uFFFC', projection.text[slot.offset])
            assertEquals(gap.start, projection.sourceOffset(slot.offset))
            assertEquals(gap.end, projection.sourceOffset(slot.offset + 1))
            assertEquals(slot.offset, projection.displayOffset(gap.start, false))
            assertEquals(slot.offset + 1, projection.displayOffset(gap.end, true))
        }
    }

    @Test fun highlightOffsetsBeforeAndAfterNewlineGapsRetainSourcePositions() {
        val group = (GapFillGroupParser.parse(questions).single() as QuestionDisplayItem.InlineGroup).group
        val projection = InlinePromptProjection.of(group)
        val offset = source.indexOf("C 28")
        val actual = projection.visibleHighlights(listOf(HighlightRange(offset, offset + 1, 0L))).single()
        assertEquals(offset, projection.sourceOffset(actual.start))
        assertEquals(offset + 1, projection.sourceOffset(actual.end))
        val before = HighlightRange(0, "Questions 26–30".length, 0L)
        assertEquals(before, projection.visibleHighlights(listOf(before)).single())
    }
}
