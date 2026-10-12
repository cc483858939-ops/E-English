package com.eenglish.listening

import com.eenglish.listening.ui.components.structuredLayoutGapDp
import org.junit.Assert.assertEquals
import org.junit.Test

class StructuredLayoutSpacingTest {
    @Test fun structuralGapWinsWithoutAddingLineBreakSpacing() {
        assertEquals(6f, structuredLayoutGapDp("paragraph", "paragraph", 4).value, 0f)
        assertEquals(6f, structuredLayoutGapDp("paragraph", "listItem", 2).value, 0f)
        assertEquals(4f, structuredLayoutGapDp("listItem", "formRow", 3).value, 0f)
    }

    @Test fun ordinaryTextBreaksStayNaturalAndRepeatedBreaksAreCappedAtTenDp() {
        assertEquals(0f, structuredLayoutGapDp("text", "text", 1).value, 0f)
        assertEquals(10f, structuredLayoutGapDp("text", "text", 5).value, 0f)
        assertEquals(10f, structuredLayoutGapDp(null, "paragraph", 1).value, 0f)
    }

    @Test fun trailingBreakDoesNotCreateWhitespaceAfterAnyBlock() {
        assertEquals(0f, structuredLayoutGapDp("paragraph", null, 1).value, 0f)
        assertEquals(0f, structuredLayoutGapDp("text", null, 3).value, 0f)
    }
}
