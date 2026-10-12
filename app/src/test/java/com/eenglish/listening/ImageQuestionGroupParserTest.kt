package com.eenglish.listening

import com.eenglish.listening.domain.gapfill.ImageQuestionGroupParser
import com.eenglish.listening.domain.gapfill.QuestionDisplayItem
import com.eenglish.listening.domain.gapfill.QuestionLayoutDisplayParser
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType
import com.eenglish.listening.domain.model.WordLimit
import org.junit.Assert.*
import org.junit.Test

class ImageQuestionGroupParserTest {
    private val prompt = buildString {
        appendLine("Questions 11-19")
        appendLine("Complete the table below.")
        appendLine("Write no more than three words for each answer. 11")
        appendLine("______")
        (12..19).forEach { number ->
            appendLine(number.toString())
            appendLine("______")
        }
    }.trimEnd()
    private val instructions = prompt.lines().take(3).joinToString("\n")

    private fun questions(
        imageFor: (Int) -> List<String> = { listOf("question-image-01.png") },
        promptOverride: (Int) -> String = { prompt },
        instructionsOverride: (Int) -> String = { instructions },
    ) = (11..19).map { number ->
        Question(
            id = "synthetic-part-q$number",
            number = number,
            type = QuestionType.TABLE_COMPLETION,
            prompt = promptOverride(number),
            options = emptyList(),
            correctAnswer = "answer-$number",
            instructions = instructionsOverride(number),
            context = "Shared synthetic context",
            images = imageFor(number),
            wordLimit = WordLimit(3, 1),
        )
    }

    @Test fun groupsNineExplicitQuestionsAndKeepsIdsAndSharedDisplayText() {
        val original = questions()
        val group = requireNotNull(ImageQuestionGroupParser.parseAtStart(original))

        assertEquals((11..19).toList(), group.questions.map { it.number })
        assertEquals(original.map { it.id }, group.questions.map { it.id })
        assertEquals("Questions 11-19", group.title)
        assertEquals("Complete the table below.\nWrite no more than three words for each answer.", group.instructions)
        assertEquals("Shared synthetic context", group.context)
        assertEquals(listOf("question-image-01.png"), group.images)

        val displayed = QuestionLayoutDisplayParser.parse(original, emptyList())
        assertEquals(1, displayed.size)
        assertSame(group.questions.first(), (displayed.single() as QuestionDisplayItem.ImageGroup).group.questions.first())
    }

    @Test fun allowsImageOnlyOnFirstQuestionAndDeduplicatesSharedPaths() {
        val firstOnly = questions(imageFor = { number -> if (number == 11) listOf("table.png") else emptyList() })
        assertEquals(listOf("table.png"), ImageQuestionGroupParser.parseAtStart(firstOnly)?.images)

        val repeated = questions(imageFor = { listOf("table.png", "table.png") })
        assertEquals(listOf("table.png"), ImageQuestionGroupParser.parseAtStart(repeated)?.images)
    }

    @Test fun supportsNumberedPlaceholderRowsWhenNumberAndBlankShareALine() {
        val numberedPrompt = """
            Questions 11-12
            Complete the table below.
            Write no more than three words for each answer.

            11 ______
            12 ______
        """.trimIndent()
        val numberedInstructions = numberedPrompt.lines().take(3).joinToString("\n")
        val pair = questions().take(2).map { question ->
            question.copy(prompt = numberedPrompt, instructions = numberedInstructions)
        }
        assertEquals((11..12).toList(), ImageQuestionGroupParser.parseAtStart(pair)?.questions?.map { it.number })
    }

    @Test fun differingImagesPromptsOrInstructionsFallBackToIndependentCards() {
        val conflicts = listOf(
            questions(imageFor = { number -> if (number == 15) listOf("other.png") else listOf("table.png") }),
            questions(instructionsOverride = { number -> if (number == 16) "$instructions extra" else instructions }),
            questions(promptOverride = { number -> if (number == 17) prompt.replace("table", "form") else prompt }),
        )
        conflicts.forEach { source ->
            assertNull(ImageQuestionGroupParser.parseAtStart(source))
            assertTrue(QuestionLayoutDisplayParser.parse(source, emptyList()).all { it is QuestionDisplayItem.Single })
        }
    }

    @Test fun unclearRangesOrNonconsecutiveQuestionsFallBackWithoutHidingPrompts() {
        val wrongRange = questions(promptOverride = { prompt.replace("Questions 11-19", "Questions 11-18") })
        val skippedNumber = questions().toMutableList().apply {
            this[3] = this[3].copy(number = 15)
        }
        listOf(wrongRange, skippedNumber).forEach { source ->
            assertNull(ImageQuestionGroupParser.parseAtStart(source))
            val display = QuestionLayoutDisplayParser.parse(source, emptyList())
            assertEquals(source.size, display.size)
            assertTrue(display.all { it is QuestionDisplayItem.Single })
        }
    }

    @Test fun ordinaryTextQuestionsAreNotImageGroups() {
        val ordinary = questions(imageFor = { emptyList() })
        assertNull(ImageQuestionGroupParser.parseAtStart(ordinary))
        assertTrue(QuestionLayoutDisplayParser.parse(ordinary, emptyList()).all { it is QuestionDisplayItem.Single })
    }
}
