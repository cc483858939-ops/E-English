package com.eenglish.listening

import com.eenglish.listening.data.assets.QuestionLayoutCodec
import com.eenglish.listening.data.repository.PackImportException
import com.eenglish.listening.data.repository.validateDisplayOnlyUpdate
import com.eenglish.listening.domain.annotation.contentHash
import com.eenglish.listening.domain.gapfill.QuestionDisplayItem
import com.eenglish.listening.domain.gapfill.QuestionLayoutDisplayParser
import com.eenglish.listening.domain.gapfill.StructuredLayoutProjection
import com.eenglish.listening.domain.layout.QuestionLayoutValidator
import com.eenglish.listening.domain.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class QuestionLayoutTest {
    private fun part(prompt: String = "😀 café A\u030A 26 _____ tail", paired: Boolean = false): ListeningPart {
        val questions = (26..35).map { number ->
            val isPaired = paired && number == 26
            Question("cambridge-5-test-1-part-3-q$number", number, QuestionType.TEXT_INPUT,
                if (number == 26) prompt else "Prompt for question $number", emptyList(),
                if (isPaired) "left and right" else "fixture",
                acceptedAnswers = listOf(if (isPaired) "left and right" else "fixture"),
                instructions = "Write no more than two words", wordLimit = WordLimit(2, 0),
                answerSeparator = if (isPaired) "and" else null)
        }
        return ListeningPart(2, "cambridge-5-test-1-part-3", "IELTS", 5, 1, 3,
            "Synthetic fixture", "Write answers", "listening/cambridge-5-test-1-part-3/audio.mp3",
            "a".repeat(64), Transcript("English fixture", "中文测试",
                listOf(TranscriptSegment("English fixture", "中文测试"))), questions).validate()
    }

    private fun layout(part: ListeningPart, paired: Boolean = false): Pair<QuestionLayoutDocument, ByteArray> {
        val question = part.questions.first()
        val prompt = question.prompt
        val partBytes = Json.encodeToString(part).toByteArray(Charsets.UTF_8)
        val html = "<html>synthetic layout source</html>".toByteArray()
        val blocks = if (paired) listOf(
            LayoutBlock("paragraph", children = listOf(
                LayoutBlock("text", text = "😀 café A\u030A ", source = LayoutBlockSource("prompt",
                    question.id, 0, "😀 café A\u030A ".length)),
                LayoutBlock("blank", questionId = question.id, questionNumber = question.number,
                    slotIndex = 0, source = LayoutBlockSource("html-marker", resource = "content.html", locator = "slot:0")),
                LayoutBlock("fixedText", text = " and ", source = LayoutBlockSource("html", resource = "content.html", locator = "connector")),
                LayoutBlock("blank", questionId = question.id, questionNumber = question.number,
                    slotIndex = 1, source = LayoutBlockSource("html-marker", resource = "content.html", locator = "slot:1")),
                LayoutBlock("text", text = " tail", source = LayoutBlockSource("prompt", question.id,
                    prompt.indexOf(" tail"), prompt.length)),
            )),
        ) else {
            val prefix = "😀 café A\u030A "
            val anchorStart = prompt.indexOf("26 _____")
            val anchorEnd = anchorStart + "26 _____".length
            listOf(LayoutBlock("paragraph", children = listOf(
                LayoutBlock("text", text = prefix,
                    source = LayoutBlockSource("prompt", question.id, 0, prefix.length)),
                LayoutBlock("blank", questionId = question.id, questionNumber = question.number,
                    slotIndex = 0, source = LayoutBlockSource("prompt-anchor", question.id, anchorStart, anchorEnd)),
                LayoutBlock("text", text = " tail", source = LayoutBlockSource("prompt", question.id,
                    prompt.indexOf(" tail"), prompt.length)),
            )))
        }
        val group = QuestionLayoutGroup(
            "${part.id}-layout-summary-q26-q26", "summaryCompletion", listOf(question.id),
            mapOf(question.id to contentHash(question.prompt)),
            LayoutSourceTrace("content.html", contentHash(html.toString(Charsets.UTF_8)), "wrapper:0", "fixture"), blocks)
        return QuestionLayoutDocument(1, part.id,
            java.security.MessageDigest.getInstance("SHA-256").digest(partBytes).joinToString("") { "%02x".format(it) },
            listOf(group)) to html
    }

    @Test fun promptSourcesUseExactUtf16RangesAcrossEmojiAndCombiningMarks() {
        val part = part()
        val (layout, html) = layout(part)
        val bytes = Json.encodeToString(part).toByteArray(Charsets.UTF_8)
        assertSame(layout, QuestionLayoutValidator.validate(layout, part, bytes,
            java.security.MessageDigest.getInstance("SHA-256").digest(html).joinToString("") { "%02x".format(it) }))
        val sourceText = layout.groups.single().blocks.single().children.first()
        assertEquals("😀 café A\u030A ".length, sourceText.source?.endUtf16)
        assertEquals("😀 café A\u030A ", part.questions.first().prompt.substring(0, sourceText.source!!.endUtf16!!))
    }

    @Test fun doubleBlankStaysOneQuestionWithOrderedSlotsAndFixedConnector() {
        val part = part("😀 café A\u030A 26 _____ and _____ tail", paired = true)
        val (layout, _) = layout(part, paired = true)
        val bytes = Json.encodeToString(part).toByteArray(Charsets.UTF_8)
        QuestionLayoutValidator.validate(layout, part, bytes)
        val parsed = QuestionLayoutDisplayParser.parse(part.questions, layout.groups)
        val item = parsed.first() as QuestionDisplayItem.StructuredLayout
        assertEquals(listOf(part.questions.first().id), item.group.questionIds)
        val projection = StructuredLayoutProjection.from(item.group.blocks)
        assertEquals(listOf(0, 1), projection.slots.map { it.slotIndex })
        assertEquals(listOf(part.questions.first().id, part.questions.first().id), projection.slots.map { it.questionId })
        assertEquals(2, projection.text.count { it == '\uFFFC' })
        assertTrue(projection.copyVisible(0, projection.text.length).contains("_____ and _____"))
    }

    @Test fun layoutForAnotherAttemptSnapshotFallsBackWhenItsSlotShapeDiffers() {
        val part = part()
        val (layout, _) = layout(part)
        val staleSnapshot = part.questions.map { question ->
            if (question.id == part.questions.first().id) question.copy(answerSeparator = "and") else question
        }
        val items = QuestionLayoutDisplayParser.parse(staleSnapshot, layout.groups)
        assertEquals(staleSnapshot.size, items.size)
        assertTrue(items.all { it is QuestionDisplayItem.Single })
    }

    @Test fun validatorRejectsBadPromptOffsetsWrongQuestionNumberAndMissingBlank() {
        val part = part()
        val (layout, _) = layout(part)
        val bytes = Json.encodeToString(part).toByteArray(Charsets.UTF_8)
        val group = layout.groups.single()
        val children = group.blocks.single().children

        val badOffset = layout.copy(groups = listOf(group.copy(blocks = listOf(
            LayoutBlock("paragraph", children = listOf(children.first().copy(source =
                children.first().source!!.copy(startUtf16 = 1)), *children.drop(1).toTypedArray()))))))
        assertThrows(IllegalArgumentException::class.java) { QuestionLayoutValidator.validate(badOffset, part, bytes) }

        val wrongNumber = layout.copy(groups = listOf(group.copy(blocks = listOf(
            LayoutBlock("paragraph", children = listOf(children[0], children[1].copy(questionNumber = 27), children[2]))))))
        assertThrows(IllegalArgumentException::class.java) { QuestionLayoutValidator.validate(wrongNumber, part, bytes) }

        val missing = layout.copy(groups = listOf(group.copy(blocks = listOf(
            LayoutBlock("paragraph", children = listOf(children.first(), children.last()))))))
        assertThrows(IllegalArgumentException::class.java) { QuestionLayoutValidator.validate(missing, part, bytes) }
    }

    @Test fun layoutCodecIsStrictAndIndexV1V2RemainReadable() {
        val part = part()
        val (layout, _) = layout(part)
        val layoutBytes = Json.encodeToString(layout).toByteArray(Charsets.UTF_8)
        assertEquals(layout, QuestionLayoutCodec.decode(layoutBytes))
        assertThrows(IllegalArgumentException::class.java) {
            QuestionLayoutCodec.decode("{\"schemaVersion\":1,\"extra\":true}".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuestionLayoutCodec.decode(("[".repeat(40) + "0" + "]".repeat(40)).toByteArray())
        }

        val oldJson = """{"schemaVersion":1,"book":5,"parts":[{"id":"cambridge-5-test-1-part-1","book":5,"test":1,"part":1,"title":"Synthetic","questionCount":10,"firstNumber":1,"lastNumber":10,"audioBytes":1,"partSha256":"${"a".repeat(64)}","audioSha256":"${"b".repeat(64)}","imageHashes":{}}]}"""
        val old = Json.decodeFromString<BookIndex>(oldJson).validate()
        assertNull(old.parts.single().layoutSha256)
        val replacement = old.copy(schemaVersion = 2, parts = old.parts.map { it.copy(layoutSha256 = "c".repeat(64)) })
        assertEquals(replacement, Json.decodeFromString<BookIndex>(Json.encodeToString(replacement)).validate())
    }

    @Test fun onlyLayoutDigestMayChangeForExistingPart() {
        val originalPart = PartSummary("cambridge-5-test-1-part-1", 5, 1, 1, "Synthetic", 10, 1, 10,
            1, "a".repeat(64), "b".repeat(64))
        val old = BookIndex(1, 5, listOf(originalPart))
        val layoutOnly = BookIndex(2, 5, listOf(originalPart.copy(layoutSha256 = "c".repeat(64))))
        validateDisplayOnlyUpdate(old, layoutOnly)
        assertThrows(PackImportException::class.java) {
            validateDisplayOnlyUpdate(old, layoutOnly.copy(parts = listOf(layoutOnly.parts.single().copy(partSha256 = "d".repeat(64)))))
        }
        assertThrows(PackImportException::class.java) {
            validateDisplayOnlyUpdate(old, layoutOnly.copy(parts = listOf(layoutOnly.parts.single().copy(audioSha256 = "d".repeat(64)))))
        }
    }
}
