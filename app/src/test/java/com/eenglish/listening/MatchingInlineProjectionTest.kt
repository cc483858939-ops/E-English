package com.eenglish.listening

import com.eenglish.listening.domain.annotation.HighlightRange
import com.eenglish.listening.domain.gapfill.MatchingInlineProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingInlineProjectionTest {
    @Test fun exactSubstringProjectionMapsSelectionsAndHighlightsBidirectionallyWithUtf16Offsets() {
        val source = "Shared 🌐 prompt\r\n37 Café and naïve speakers _____\r\n38 next item _____"
        val start = source.indexOf("37 Café")
        val end = source.indexOf("\r\n38")
        assertEquals(18, start)
        val projection = MatchingInlineProjection.sourceRange(source, start, end)

        assertEquals(source.substring(start, end), projection.text)
        assertTrue(projection.text.startsWith("37 Café"))

        val selectedStart = projection.text.indexOf("Café") + 1
        val selectedEnd = selectedStart + 3
        assertEquals(
            listOf(MatchingInlineProjection.OffsetRange(start + selectedStart, start + selectedEnd)),
            projection.sourceRanges(selectedStart, selectedEnd),
        )

        val highlighted = projection.displayRanges(listOf(HighlightRange(start + 3, start + 9, 7L)))
        assertEquals(listOf(HighlightRange(3, 9, 7L)), highlighted)
        assertEquals(listOf(HighlightRange(0, 6, 7L)), projection.displayRanges(
            listOf(HighlightRange(start - 2, start + 6, 7L))))
    }

    @Test fun displayedTextMustBeAnExactSourceSubstring() {
        val source = "26 item ____\n27 next ____"
        val projection = MatchingInlineProjection.sourceRange(source, 0, source.indexOf('\n'))
        assertEquals(source.substring(0, source.indexOf('\n')), projection.text)
        assertEquals(listOf(MatchingInlineProjection.OffsetRange(3, 7)), projection.sourceRanges(3, 7))
    }
}
