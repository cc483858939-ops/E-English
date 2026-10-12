package com.eenglish.listening

import com.eenglish.listening.domain.model.Option
import com.eenglish.listening.ui.components.InlineAnswerGap
import com.eenglish.listening.ui.components.matchingOptionLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingInlinePresentationTest {
    @Test fun defaultInlineTextGapStillUsesTheExistingFillPrompt() {
        val gap = InlineAnswerGap(number = 31, start = 5, end = 10, answer = "")
        assertEquals("31 · 填写", gap.displayLabel())
        assertEquals("填写 Q31", gap.accessibilityLabel)
        assertEquals(0, gap.minimumTouchHeightDp)
    }

    @Test fun matchingGapShowsSelectionLabelLetterArrowAndAccessibleTouchHeight() {
        val empty = InlineAnswerGap(
            number = 37,
            start = 10,
            end = 15,
            answer = "",
            emptyLabel = "选择",
            accessibilityLabel = "选择 Q37",
            trailingIndicator = "▾",
            minimumTouchHeightDp = 48,
        )
        val selected = empty.copy(answer = "B")
        assertEquals("37 · 选择 ▾", empty.displayLabel())
        assertEquals("37 · B ▾", selected.displayLabel())
        assertEquals("选择 Q37", empty.accessibilityLabel)
        assertEquals(48, empty.minimumTouchHeightDp)
    }

    @Test fun optionSheetShowsFullOptionTextWithoutInventingLabelOnlyDescriptions() {
        assertEquals("B — library staff", matchingOptionLabel(Option("B", "library staff")))
        assertEquals("A", matchingOptionLabel(Option("A", "A")))
        assertTrue(matchingOptionLabel(Option("C", " ")).isNotBlank())
    }
}
