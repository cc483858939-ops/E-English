package com.eenglish.listening

import com.eenglish.listening.domain.gapfill.QuestionDisplayItem
import com.eenglish.listening.domain.gapfill.QuestionLayoutDisplayParser
import com.eenglish.listening.domain.gapfill.MatchingInlineFallbackReason
import com.eenglish.listening.domain.gapfill.SharedMatchingGroupParser
import com.eenglish.listening.domain.gapfill.SharedMultipleChoiceGroupParser
import com.eenglish.listening.domain.gapfill.SharedTextFallbackGroupParser
import com.eenglish.listening.domain.model.Option
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType
import com.eenglish.listening.domain.model.WordLimit
import org.junit.Assert.*
import org.junit.Test

class SharedQuestionGroupParserTest {
    private val matchingOptions = listOf(Option("A", "At present"), Option("B", "In the near future"))
    private val matchingPrompt = """
        Questions 26-27
        Which time period matches each item?
        Write the correct letter, A or B, next to questions 26-27.
        A At present
        B In the near future
        26 understanding of tides
        _____
        27 forecasting storms
        _____
    """.trimIndent()

    private fun matching(
        prompt: String = matchingPrompt,
        instructions: String = "Questions 26-27 Write the correct letter, A or B.",
        options: List<Option> = matchingOptions,
        type: QuestionType = QuestionType.MATCHING,
        image: List<String> = emptyList(),
        ids: List<String> = listOf("matching-q26", "matching-q27"),
        numbers: List<Int> = listOf(26, 27),
    ) = numbers.mapIndexed { index, number ->
        Question(ids[index], number, type, prompt, options, if (index == 0) "A" else "B",
            instructions = instructions, images = image)
    }

    private val multiOptions = listOf(Option("A", "a case study"), Option("B", "an essay"), Option("C", "a survey"))
    private val multiPrompt = """
        Questions 24 and 25
        Choose TWO letters A-C.
        What TWO types of coursework are required?
    """.trimIndent()

    private fun multipleChoice(
        ids: List<String> = listOf("mc-q24", "mc-q25"),
        numbers: List<Int> = listOf(24, 25),
        groupId: String = "mc-group-24",
        groupNumbers: List<Int> = listOf(24, 25),
        options: List<Option> = multiOptions,
        prompt: String = multiPrompt,
        answers: List<String> = listOf("A", "B"),
    ) = numbers.mapIndexed { index, number ->
        Question(ids[index], number, QuestionType.MULTIPLE_CHOICE, prompt, options,
            answers[index], instructions = "Questions 24 and 25 Choose TWO letters A-C.",
            groupId = groupId, groupNumbers = groupNumbers)
    }

    @Test fun matchingGroupsExactRangeAndKeepsEachQuestionIndependent() {
        val source = matching()
        val group = requireNotNull(SharedMatchingGroupParser.parseAtStart(source))

        assertEquals(listOf("matching-q26", "matching-q27"), group.questions.map { it.id })
        assertEquals((26..27).toList(), group.questions.map { it.number })
        assertEquals(mapOf("q26" to "understanding of tides", "q27" to "forecasting storms"), group.questionTexts)
        assertTrue(group.optionsInPrompt)
        assertEquals(matchingOptions, group.options)

        val items = QuestionLayoutDisplayParser.parse(source, emptyList())
        assertEquals(1, items.size)
        assertTrue(items.single() is QuestionDisplayItem.SharedMatching)
        assertEquals(source.map { it.id }, items.flatMap { item ->
            (item as QuestionDisplayItem.SharedMatching).group.questions.map { it.id }
        })
        // The display wrapper keeps per-question IDs, so both may independently store A.
        assertNotEquals(group.questions[0].id, group.questions[1].id)
    }

    @Test fun matchingInlineSlotsUseOriginalUtf16OffsetsForSameLineAndFollowingLineBlanks() {
        val prompts = listOf(
            """Questions 26-27
                Which time period matches each item?
                26 understanding of tides _____
                27 forecasting storms _____""".trimIndent(),
            """Questions 26-27
                Which time period matches each item?
                26 understanding of tides
                _____
                27 forecasting storms
                ______""".trimIndent(),
        )
        prompts.forEachIndexed { index, prompt ->
            val group = requireNotNull(SharedMatchingGroupParser.parseAtStart(matching(prompt = prompt)))
            val slots = requireNotNull(group.inlineSlots)
            assertEquals(listOf("matching-q26", "matching-q27"), slots.map { it.questionId })
            assertEquals(listOf(26, 27), slots.map { it.number })
            assertEquals(
                if (index == 0) listOf("_____", "_____") else listOf("_____", "______"),
                slots.map { prompt.substring(it.start, it.end) },
            )
            assertTrue(slots.all { slot -> prompt.substring(slot.start, slot.end).none { it == '\r' || it == '\n' } })
            assertTrue(slots.zipWithNext().all { (left, right) -> left.end <= right.start })
        }
    }

    @Test fun matchingInlineSlotsHandleCrLfTabsMultilineRowsAndSupplementaryUnicode() {
        val prompt = listOf(
            "Questions 26-27",
            "Which period matches each item?",
            "26\tCafé 🌊 and a description that continues",
            "on the next source line __",
            "27\tforecasting storms\t_____",
        ).joinToString("\r\n")
        val group = requireNotNull(SharedMatchingGroupParser.parseAtStart(matching(prompt = prompt)))
        val slots = requireNotNull(group.inlineSlots)
        assertEquals(2, slots.size)
        assertEquals(prompt.indexOf("__"), slots[0].start)
        assertEquals(prompt.indexOf("_____") + 5, slots[1].end)
        assertTrue(slots.all { prompt.substring(it.start, it.end).none { ch -> ch == '\r' || ch == '\n' } })
        assertEquals(listOf("matching-q26", "matching-q27"), group.questionRows?.map { it.questionId })
        val firstRow = requireNotNull(group.questionRows).first()
        assertEquals("26\tCafé 🌊 and a description that continues\r\non the next source line __",
            prompt.substring(firstRow.start, firstRow.end))
    }

    @Test fun matchingKeepsQuestionRowFallbackWhenBlankLocationsAreNotUnique() {
        val noBlanks = """Questions 26-27
            Which time period matches each item?
            26 understanding of tides
            27 forecasting storms""".trimIndent()
        val noBlankGroup = requireNotNull(SharedMatchingGroupParser.parseAtStart(matching(prompt = noBlanks)))
        assertNull(noBlankGroup.inlineSlots)
        assertEquals(MatchingInlineFallbackReason.NO_EXPLICIT_PLACEHOLDER, noBlankGroup.inlineFallbackReason)
        assertEquals(listOf("matching-q26", "matching-q27"), noBlankGroup.questionRows?.map { it.questionId })

        val ambiguous = matchingPrompt.replace("_____\n27 forecasting storms\n_____", "_____ _____\n27 forecasting storms")
        val ambiguousGroup = requireNotNull(SharedMatchingGroupParser.parseAtStart(matching(prompt = ambiguous)))
        assertNull(ambiguousGroup.inlineSlots)
        assertNotNull(ambiguousGroup.inlineFallbackReason)
        assertNotNull(ambiguousGroup.questionRows)

        val missingRow = noBlanks.replace("27 forecasting storms", "forecasting storms")
        val missingRowGroup = requireNotNull(SharedMatchingGroupParser.parseAtStart(matching(prompt = missingRow)))
        assertNull(missingRowGroup.inlineSlots)
        assertNull(missingRowGroup.questionRows)
        assertEquals(MatchingInlineFallbackReason.NUMBERED_ROWS_NOT_EXTRACTABLE,
            missingRowGroup.inlineFallbackReason)
    }

    @Test fun matchingSlotRangesAreBoundToEachOriginalQuestionAcrossMultipleGroups() {
        val firstPrompt = """Questions 26-27
            Shared prompt
            26 first item _____
            27 second item _____""".trimIndent()
        val secondPrompt = """Questions 28-29
            Another shared prompt
            28 third item _____
            29 fourth item _____""".trimIndent()
        val source = matching(prompt = firstPrompt) + matching(
            prompt = secondPrompt,
            ids = listOf("matching-q28", "matching-q29"),
            numbers = listOf(28, 29),
        )
        val items = QuestionLayoutDisplayParser.parse(source, emptyList())
        val groups = items.filterIsInstance<QuestionDisplayItem.SharedMatching>().map { it.group }
        assertEquals(2, groups.size)
        assertEquals(listOf("matching-q26", "matching-q27"), groups[0].inlineSlots?.map { it.questionId })
        assertEquals(listOf("matching-q28", "matching-q29"), groups[1].inlineSlots?.map { it.questionId })
        assertEquals(source.map { it.id }, items.flatMap { item ->
            (item as QuestionDisplayItem.SharedMatching).group.questions.map { it.id }
        })
    }

    @Test fun matchingWhitespaceMayVaryButTextOptionAndInstructionChangesReject() {
        val whitespaceVariantPrompt = matchingPrompt
            .replace("Which time period matches each item?", "Which   time period matches each item?")
            .replace("\n", "\r\n")
        val whitespaceVariant = matching().toMutableList().apply {
            this[1] = this[1].copy(
                prompt = whitespaceVariantPrompt,
                instructions = "Questions 26-27\r\nWrite the correct letter, A or B.",
            )
        }
        assertNotNull(SharedMatchingGroupParser.parseAtStart(whitespaceVariant))

        val promptConflict = matching().toMutableList().apply {
            this[1] = this[1].copy(prompt = matchingPrompt.replace("tides", "currents"))
        }
        val optionConflict = matching().toMutableList().apply {
            this[1] = this[1].copy(options = listOf(Option("A", "Now"), matchingOptions[1]))
        }
        val instructionConflict = matching().toMutableList().apply {
            this[1] = this[1].copy(instructions = "Questions 26-27 Choose a number.")
        }
        listOf(promptConflict, optionConflict, instructionConflict).forEach {
            assertNull(SharedMatchingGroupParser.parseAtStart(it))
            assertTrue(QuestionLayoutDisplayParser.parse(it, emptyList()).all { item -> item is QuestionDisplayItem.Single })
        }
    }

    @Test fun matchingFallsBackToOneWholePromptWhenNumberedRowsCannotBeExtracted() {
        val source = matching(prompt = """
            Questions 26-27
            Which time period matches each item?
            Write the correct letter, A or B, next to questions 26-27.
            A At present
            B In the near future
            tides
            storms
        """.trimIndent())

        val group = SharedMatchingGroupParser.parseAtStart(source)
        assertNotNull(group)
        assertNull(group?.questionTexts)
        assertEquals(source.first().prompt, group?.prompt)
    }

    @Test fun matchingRequiresCompleteContiguousRangeAndUniqueIds() {
        val missing = matching().take(1)
        val skipped = matching().toMutableList().apply { this[1] = this[1].copy(number = 28) }
        val duplicateId = matching().toMutableList().apply { this[1] = this[1].copy(id = this[0].id) }
        listOf(missing, skipped, duplicateId).forEach {
            assertNull(SharedMatchingGroupParser.parseAtStart(it))
            assertEquals(it.map { question -> question.id },
                QuestionLayoutDisplayParser.parse(it, emptyList()).flatMap { item ->
                    when (item) {
                        is QuestionDisplayItem.Single -> listOf(item.question.id)
                        is QuestionDisplayItem.SharedMatching -> item.group.questions.map { question -> question.id }
                        else -> emptyList()
                    }
                })
        }
    }

    @Test fun imageMatchingNeedsExplicitMapLabelingAndDeduplicatesSharedImage() {
        val options = ('A'..'C').map { Option(it.toString(), it.toString()) }
        val source = matching(
            prompt = """
                Questions 14-16
                Label the map below.
                Write the correct letter, A-C, next to questions 14-16.
                14 entrance
                _____
                15 bridge
                _____
                16 café
                _____
            """.trimIndent(),
            instructions = "Questions 14-16 Label the map below.",
            options = options,
            type = QuestionType.IMAGE_BASED,
            image = listOf("map.png"),
            ids = listOf("map-q14", "map-q15", "map-q16"),
            numbers = listOf(14, 15, 16),
        )
        val group = requireNotNull(SharedMatchingGroupParser.parseAtStart(source))
        assertEquals(listOf("map.png"), group.images)
        assertEquals((14..16).toList(), group.questions.map { it.number })

        val firstImageOnly = source.mapIndexed { index, question ->
            question.copy(images = if (index == 0) listOf("map.png") else emptyList())
        }
        assertEquals(listOf("map.png"), SharedMatchingGroupParser.parseAtStart(firstImageOnly)?.images)
        val laterImageOnly = source.mapIndexed { index, question ->
            question.copy(images = if (index == 1) listOf("map.png") else emptyList())
        }
        assertNull(SharedMatchingGroupParser.parseAtStart(laterImageOnly))

        val differentImages = source.mapIndexed { index, question ->
            question.copy(images = listOf(if (index == 1) "other.png" else "map.png"))
        }
        assertNull(SharedMatchingGroupParser.parseAtStart(differentImages))
        assertNull(SharedMatchingGroupParser.parseAtStart(matching(type = QuestionType.SINGLE_CHOICE)))
    }

    @Test fun multipleChoiceUsesValidatedMetadataAndSelectionCount() {
        val source = multipleChoice()
        val group = requireNotNull(SharedMultipleChoiceGroupParser.parseAtStart(source))
        assertEquals("mc-group-24", group.groupId)
        assertEquals(listOf("mc-q24", "mc-q25"), group.questions.map { it.id })
        assertEquals(2, group.selectionCount)
        assertEquals(multiOptions, group.options)

        val items = QuestionLayoutDisplayParser.parse(source, emptyList())
        assertEquals(1, items.size)
        assertTrue(items.single() is QuestionDisplayItem.SharedMultipleChoice)
        assertEquals(source.map { it.id }, items.flatMap { item ->
            (item as QuestionDisplayItem.SharedMultipleChoice).group.questions.map { it.id }
        })

        val promptWithOptions = """$multiPrompt
            A. a case study
            B. an essay
            C. a survey""".trimIndent()
        val optionsInPrompt = requireNotNull(SharedMultipleChoiceGroupParser.parseAtStart(
            multipleChoice(prompt = promptWithOptions)))
        assertTrue(optionsInPrompt.optionsInPrompt)
        assertNull(SharedMultipleChoiceGroupParser.parseAtStart(
            multipleChoice(prompt = promptWithOptions.replace("C. a survey", ""))))
    }

    @Test fun multipleChoiceRejectsWrongGroupNumbersOptionsPromptOrSelectionCount() {
        val wrongNumbers = multipleChoice().map { it.copy(groupNumbers = listOf(24, 26)) }
        val wrongOptions = multipleChoice().mapIndexed { index, q ->
            if (index == 1) q.copy(options = listOf(Option("A", "different"), multiOptions[1], multiOptions[2])) else q
        }
        val wrongGroupId = multipleChoice().mapIndexed { index, q ->
            if (index == 1) q.copy(groupId = "other-group") else q
        }
        val wrongCount = multipleChoice(prompt = multiPrompt.replace("Choose TWO", "Choose THREE"))
        val changedPrompt = multipleChoice().mapIndexed { index, q ->
            if (index == 1) q.copy(prompt = multiPrompt.replace("coursework", "assignments")) else q
        }
        listOf(wrongNumbers, wrongOptions, wrongGroupId, wrongCount, changedPrompt).forEach {
            assertNull(SharedMultipleChoiceGroupParser.parseAtStart(it))
        }
        assertTrue(QuestionLayoutDisplayParser.parse(multipleChoice(type = QuestionType.SINGLE_CHOICE), emptyList())
            .all { it is QuestionDisplayItem.Single })
    }

    @Test fun textFallbackRequiresOneExplicitNumberedBlankPerQuestion() {
        val prompt = """
            Questions 31-32
            Complete the sentences below.
            Write ONE WORD ONLY.
            31 One useful example is
            _____
            32 Another useful example is
            _____
        """.trimIndent()
        val source = (31..32).map { number ->
            Question("text-q$number", number, QuestionType.NOTE_COMPLETION, prompt, emptyList(), "answer",
                instructions = "Questions 31-32 Complete the sentences below.", wordLimit = WordLimit(1, 0))
        }
        val group = requireNotNull(SharedTextFallbackGroupParser.parseAtStart(source))
        assertEquals(source.map { it.id }, group.questions.map { it.id })
        val display = QuestionLayoutDisplayParser.parse(source, emptyList())
        assertEquals(1, display.size)
        assertTrue(display.single() is QuestionDisplayItem.SharedTextFallback)

        val missingBlank = source.mapIndexed { index, question ->
            if (index == 1) question.copy(prompt = prompt.replaceFirst("_____", "")) else question
        }
        val duplicateBlank = source.mapIndexed { index, question ->
            if (index == 1) question.copy(answerSeparator = "and") else question
        }
        assertNull(SharedTextFallbackGroupParser.parseAtStart(missingBlank))
        assertNull(SharedTextFallbackGroupParser.parseAtStart(duplicateBlank))
    }

    private fun multipleChoice(type: QuestionType): List<Question> =
        multipleChoice().map { it.copy(type = type, groupId = null, groupNumbers = emptyList()) }
}
