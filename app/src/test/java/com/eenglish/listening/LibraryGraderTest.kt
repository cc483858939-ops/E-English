package com.eenglish.listening

import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class LibraryGraderTest {
    private fun input(answer: String = "alpha", accepted: List<String> = listOf(answer), limit: WordLimit = WordLimit(1)) =
        Question("text", 1, QuestionType.TEXT_INPUT, "Synthetic fill", emptyList(), answer, acceptedAnswers = accepted, wordLimit = limit)
    private fun group() = listOf("A", "C").mapIndexed { i, answer ->
        Question("multi-$i", i + 1, QuestionType.MULTIPLE_CHOICE, "Synthetic choose two",
            listOf(Option("A", "First"), Option("B", "Second"), Option("C", "Third")), answer,
            groupId = "group", groupNumbers = listOf(1, 2))
    }
    @Test fun caseSpacesAndExplicitAlternatives() {
        val q = input(accepted = listOf("alpha", "beta"))
        assertEquals(1, Grader.grade(listOf(q), mapOf(q.id to "  BeTa  ")).correctCount)
        assertEquals(0, Grader.grade(listOf(q), mapOf(q.id to "alphas")).correctCount)
        assertEquals("two words", Grader.normalize("Two\u00a0\u202fWords"))
    }
    @Test fun numericLimitsAndOverlongAcceptedVariants() {
        val q = input("20 percent", listOf("20 percent", "twenty percent"), WordLimit(1, 1))
        assertEquals(1, Grader.grade(listOf(q), mapOf(q.id to "20 percent")).correctCount)
        assertEquals(0, Grader.grade(listOf(q), mapOf(q.id to "twenty percent")).correctCount)
        assertFalse(Grader.withinLimit(q, "20 30 percent"))
        assertTrue(Grader.withinLimit(input(limit = WordLimit(1, 2)), "2004 to 2005"))
        assertTrue(Grader.withinLimit(input(limit = WordLimit(1, 1)), "April 24th"))
        assertFalse(Grader.withinLimit(input(limit = WordLimit(1, 1, wordsOrNumber = true)), "20 percent"))
    }
    @Test fun unorderedMultiGetsOnePointPerOriginalNumber() {
        val q = group()
        val selected = q.associate { it.id to "C,A" }
        assertEquals(2, Grader.grade(q, selected).correctCount)
        assertEquals(0, Grader.missingCount(q, selected))
        assertEquals(1, Grader.grade(q, q.associate { it.id to "A,B" }).correctCount)
        assertEquals(1, Grader.grade(q, q.associate { it.id to "A" }).correctCount)
        assertEquals(1, Grader.missingCount(q, q.associate { it.id to "A" }))
        assertEquals(1, Grader.grade(q, q.associate { it.id to "A,A" }).correctCount)
        assertEquals(0, Grader.grade(q, q.associate { it.id to "A,B,C" }).correctCount)
    }
    @Test fun missingBlankAndInconsistentGroupAreNotScored() {
        val q = group()
        assertEquals(0, Grader.grade(q, mapOf(q[0].id to "A,C")).correctCount)
        assertEquals(0, Grader.grade(listOf(input()), mapOf("text" to "   ")).correctCount)
        assertEquals(1, Grader.missingCount(listOf(input()), mapOf("text" to "   ")))
    }
    @Test fun versionOneSnapshotsStillDecodeWithNewDefaults() {
        val old = fixturePart()
        assertEquals(emptyList<String>(), old.questions.first().acceptedAnswers)
        assertEquals(10, Grader.grade(old.questions, old.questions.associate { it.id to "A" }).correctCount)
        val englishOnly = old.copy(schemaVersion = 2, transcript = Transcript("Fixture", "", listOf(TranscriptSegment("Fixture", ""))))
        englishOnly.validate()
    }
}
