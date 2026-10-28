package com.eenglish.listening

import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.layout.*
import com.eenglish.listening.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class ChoiceReviewMaterialTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private fun load(book: Int, id: String, number: Int): Pair<ListeningPart, JsonObject> {
        ZipFile(File(root, "private-data/layout-v4-android-test-packs/cambridge-$book-v4-test.eelpack")).use { zip ->
            val base = "listening/$id/"
            val part = PartCodec.decode(zip.getInputStream(zip.getEntry(base + "part.json")).reader().readText())
            val layout = Json.parseToJsonElement(zip.getInputStream(zip.getEntry(base + "layout.json")).reader().readText()).jsonObject
            val question = part.questions.single { it.number == number }
            return part to layout.getValue("groups").jsonArray.map { it.jsonObject }
                .single { g -> g.getValue("questionIds").jsonArray.any { it.jsonPrimitive.content == question.id } }
        }
    }
    @Test fun actualDanQuestionExcludesOtherStemsAndKeepsResourceAnnotationOffsets() {
        val (part, group) = load(5, "cambridge-5-test-2-part-2", 12)
        val question = part.questions.single { it.number == 12 }
        val shown = choiceReviewMaterial(group, part.questions, setOf(question.id))
        val text = shown.paragraphs.joinToString("\n") { it.text }
        assertTrue(text.contains(question.prompt))
        assertFalse(text.contains(part.questions.single { it.number == 11 }.prompt))
        assertFalse(text.contains(part.questions.single { it.number == 13 }.prompt))
        val projection = shown.paragraphs.single { it.text.contains(question.prompt) }
        val offset = projection.text.indexOf("neighbour")
        val change = projection.selection(part.id, offset, offset + 9, true).single()
        assertTrue(change.key.textId.startsWith("v4/resource/"))
        assertTrue(change.start > offset)
        assertEquals(9, change.end - change.start)
    }
    @Test fun sharedMatchingKeepsOnlyCurrentRowAndReferenceImage() {
        val (part, group) = load(12, "cambridge-12-test-4-part-2", 15)
        val current = part.questions.single { it.number == 15 }
        val shown = choiceReviewMaterial(group, part.questions, setOf(current.id))
        assertTrue(shown.images.isNotEmpty())
        val allText = shown.paragraphs.joinToString("\n") { it.text }
        assertTrue(allText.contains("The Reynolds House"))
        assertFalse(allText.contains("The Thumb"))
        assertFalse(allText.contains("The Warner Gallery"))
        val others = (group.getValue("slots").jsonArray.map { it.jsonObject })
            .filter { it.getValue("questionId").jsonPrimitive.content != current.id }
        assertTrue(others.isNotEmpty())
    }
    @Test fun multiChoiceKeepsOneWholeResponseAndFlowChoiceKeepsItsReferenceImage() {
        val (part, group) = load(6, "cambridge-6-test-2-part-2", 18)
        val ids = part.questions.filter { it.groupId == part.questions.single { it.number == 18 }.groupId &&
            it.type == QuestionType.MULTIPLE_CHOICE }.map { it.id }.toSet()
        assertEquals(3, ids.size)
        val shown = choiceReviewMaterial(group, part.questions, ids)
        assertTrue(shown.paragraphs.any { it.text.isNotBlank() })
        val (flowPart, flowGroup) = load(12, "cambridge-12-test-2-part-3", 26)
        val flow = choiceReviewMaterial(flowGroup, flowPart.questions, setOf(flowPart.questions.single { it.number == 26 }.id))
        assertTrue(flow.images.isNotEmpty())
    }
}
