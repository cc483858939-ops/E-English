package com.eenglish.listening

import com.eenglish.listening.domain.gapfill.*
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.domain.grading.Grader
import org.junit.Assert.*
import org.junit.Test

class GapFillGroupParserTest {
    private val summary = """
Questions 26–30
Complete the summary below.
Write NO MORE THAN THREE WORDS AND/OR A NUMBER for each answer.

Modular Courses
Students study 26 _____ during each module.
A module takes 27 ___ and the work is very 28 ______.
To get a Diploma each student has to study 29 _____
and then work on 30 ______ in depth.
    """.trimIndent()

    private fun questions(prompt: String = summary) = (26..30).map {
        Question("synthetic-q$it", it, QuestionType.TEXT_INPUT, prompt, emptyList(), "fixture",
            instructions = "NO MORE THAN THREE WORDS", wordLimit = WordLimit(3, 1))
    }

    @Test fun matchesAllFiveNumberedGapsWithoutTouchingHeading() {
        val original = questions()
        val items = GapFillGroupParser.parse(original)
        assertEquals(1, items.size)
        val group = (items.single() as QuestionDisplayItem.InlineGroup).group
        assertEquals((26..30).toList(), group.gaps.map { it.number })
        assertEquals(original.map { it.id }, group.gaps.map { it.questionId })
        group.gaps.forEach { gap ->
            assertTrue(summary.substring(gap.start, gap.end).matches(Regex("""\d+\s+_{2,}""")))
        }
        assertTrue(group.gaps.first().start > summary.indexOf("Modular Courses"))
        assertEquals(summary, group.prompt)
        assertEquals(5, Grader.grade(original, emptyMap()).totalCount)
    }

    @Test fun oldCardsRemainForAmbiguityAndUnsupportedTypes() {
        val tests = listOf(
            summary.replace("27 ___", "26 ___"),
            summary.replace("29 _____", "unlabelled _____"),
            summary.replace("30 ______", "30 ______ and 30 ______"),
            summary.replace("Questions 26–30", "Questions 26–29"),
            summary.replace("Complete the summary", "Complete the notes"),
            summary.replace("26 _____", "26 text"),
        )
        tests.forEach { text ->
            assertTrue(GapFillGroupParser.parse(questions(text)).all { it is QuestionDisplayItem.Single })
        }
        val paired = questions().toMutableList()
        paired[1] = paired[1].copy(answerSeparator = "and")
        assertTrue(GapFillGroupParser.parse(paired).all { it is QuestionDisplayItem.Single })
        assertTrue(GapFillGroupParser.parse(questions().map { it.copy(type = QuestionType.TABLE_COMPLETION) })
            .all { it is QuestionDisplayItem.Single })
    }

    @Test fun noCrossPartOrNonConsecutiveOrDuplicateGroup() {
        val unrelated = questions().map { it.copy(prompt = summary.replace("Modular Courses", "Different")) }
        assertEquals(5, GapFillGroupParser.parse(unrelated).size)
        assertEquals(5, GapFillGroupParser.parse(questions().toMutableList().apply {
            this[2] = this[2].copy(number = 35)
        }).size)
        assertEquals(5, GapFillGroupParser.parse(questions().toMutableList().apply {
            this[2] = this[2].copy(number = 27)
        }).size)
    }

    @Test fun handlesWhitespaceAndNewlineBeforeGap() {
        val multiline = summary.replace("27 ___", "27 \n \t_____")
        val group = (GapFillGroupParser.parse(questions(multiline)).single() as QuestionDisplayItem.InlineGroup).group
        assertEquals((26..30).toList(), group.gaps.map { it.number })
    }
}
