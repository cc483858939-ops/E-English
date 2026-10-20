package com.eenglish.listening

import com.eenglish.listening.domain.annotation.*
import com.eenglish.listening.domain.layout.*
import com.eenglish.listening.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class V4FillNumberingTest {
    private val q = Question("q7", 7, QuestionType.TEXT_INPUT, "7 The tour costs $ _____", emptyList(), "47.50")
    private fun group(text: String, other: Boolean = false, kind: String = "fill", rowType: String = "paragraph"): JsonObject = buildJsonObject {
        put("slots", buildJsonArray {
            add(buildJsonObject { put("slotId", "s7"); put("questionId", "q7"); put("kind", kind) })
            if (other) add(buildJsonObject { put("slotId", "s8"); put("questionId", "q8"); put("kind", kind) })
        })
        put("blocks", buildJsonArray { add(buildJsonObject {
            put("type", rowType); put("children", buildJsonArray {
                add(buildJsonObject { put("id", "prefix"); put("type", "text"); put("text", text) })
                add(buildJsonObject { put("type", "slot"); put("slotId", "s7") })
                if (other) add(buildJsonObject { put("type", "slot"); put("slotId", "s8") })
            })
        }) })
    }

    @Test fun hidesOnlyMatchingPrintedPrefixInSingleQuestionFillRows() {
        for (type in listOf("paragraph", "formRow", "listItem")) {
            assertEquals(mapOf("prefix" to 2), V4FillNumbering.hiddenPrefixes(group("7 The tour costs $ ", rowType = type), mapOf(q.id to q)))
        }
        assertTrue(V4FillNumbering.hiddenPrefixes(group("8 The tour costs $ "), mapOf(q.id to q)).isEmpty())
        assertTrue(V4FillNumbering.hiddenPrefixes(group("7 The tour costs $ ", other = true), mapOf(q.id to q)).isEmpty())
        assertTrue(V4FillNumbering.hiddenPrefixes(group("7 Choose an option", kind = "matching"), mapOf(q.id to q)).isEmpty())
    }

    @Test fun preservesQuantitiesDatesRangesDecimalsAndNumbersInsideSentences() {
        for (text in listOf("7 days in advance", "7 January", "7.5 dollars", "7% deposit", "7-10 Questions",
                "$7 per person", "The tour costs 7 dollars", "70 The tour costs", "Questions 7-10")) {
            assertEquals(text, 0, V4FillNumbering.prefixLength(text, 7))
        }
        assertEquals(4, V4FillNumbering.prefixLength("(7) The tour costs", 7))
        assertEquals(4, V4FillNumbering.prefixLength("Q7. The tour costs", 7))
    }

    @Test fun croppedTextKeepsOldHighlightsAndExactSelectionCoordinates() {
        val text = "7 The tour costs $ "
        val block = buildJsonObject {
            put("id", "prefix"); put("text", text)
            put("source", buildJsonObject { put("kind", "resourceRange"); put("resource", "content.html")
                put("normalization", "htmlTextV1"); put("sha256", "a".repeat(64))
                put("startUtf16", 724); put("endUtf16", 724 + text.length) })
        }
        val original = V4AnnotationSources.block("g", block, emptyMap())
        val visible = original.slice(V4FillNumbering.prefixLength(text, 7), text.length)
        assertEquals("The tour costs $ ", visible.text)
        val before = original.selection("part", 6, 16, true).single()
        val after = visible.selection("part", 4, 14, true).single()
        assertEquals(before, after)
        val old = Highlight(before.key, HighlightRange(before.start, before.end, 1))
        assertEquals(listOf(HighlightRange(4, 14, 1)), visible.visibleRanges("part", listOf(old)))
        assertEquals(before.copy(), visible.selection("part", 4, 14, false).single())
        assertEquals(text, original.text)
    }

    @Test fun pairedAnswersRetainDistinctPermanentNumbers() {
        assertEquals("7", fillNumberLabel(7, 0, 1))
        assertEquals("7.1", fillNumberLabel(7, 0, 2))
        assertEquals("7.2", fillNumberLabel(7, 1, 2))
    }
}
