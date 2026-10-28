package com.eenglish.listening

import com.eenglish.listening.domain.layout.answerSheetEntries
import com.eenglish.listening.domain.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AnswerSheetTest {
    private fun q(n: Int, type: QuestionType = QuestionType.TEXT_INPUT) =
        Question("q$n", n, type, "prompt", emptyList(), "correct")

    @Test fun preservesActualNumbersAndCountsAnyNonblankAnswerWithoutGrading() {
        val entries = answerSheetEntries(listOf(q(23), q(21), q(22)), null,
            mapOf("q21" to "wrong but answered", "q22" to " \n"))
        assertEquals(listOf(21, 22, 23), entries.map { it.number })
        assertEquals(listOf(true, false, false), entries.map { it.complete })
    }

    @Test fun multiChoiceIsOneEntryAndIsIncompleteUntilAllChoicesAreSelected() {
        val questions = listOf(q(21, QuestionType.MULTIPLE_CHOICE), q(22, QuestionType.MULTIPLE_CHOICE))
            .map { it.copy(groupId = "group", groupNumbers = listOf(21, 22)) } + q(23)
        fun entries(value: String) = answerSheetEntries(questions, null, mapOf("q21" to value, "q22" to value))
        assertEquals(2, entries("").size)
        assertEquals("21、22", entries("A").first().label)
        assertFalse(entries("A").first().complete)
        assertEquals(1, entries("A").first().answeredCount)
        assertTrue(entries("A,C").first().complete)
        assertFalse(answerSheetEntries(questions, null, mapOf("q21" to "A,C", "q22" to "A")).first().complete)
    }

    @Test fun pairedAnswersRequireBothSidesInLegacyAndV4() {
        val legacy = q(1).copy(answerSeparator = "and")
        val v4 = q(2)
        val layout = Json.parseToJsonElement("""{"groups":[{"responses":[
            {"kind":"fill","slotIds":["s1","s2"],"questionIds":["q2"],"joiner":"and"}]}]}""").jsonObject
        val questions = listOf(legacy, v4)
        assertTrue(answerSheetEntries(questions, layout, mapOf("q1" to "one and two", "q2" to "one and two")).all { it.complete })
        assertTrue(answerSheetEntries(questions, layout, mapOf("q1" to "one and ", "q2" to "one and ")).none { it.complete })
    }
}
