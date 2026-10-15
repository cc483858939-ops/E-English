package com.eenglish.listening

import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.annotation.*
import com.eenglish.listening.domain.layout.V4AnnotationSources
import com.eenglish.listening.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class V4AnnotationProjectionTest {
    private fun resource(text: String, start: Int, id: String = "text") = buildJsonObject {
        put("id", id); put("type", "text"); put("text", text)
        put("source", buildJsonObject {
            put("kind", "resourceRange"); put("resource", "content.html"); put("normalization", "htmlTextV1")
            put("sha256", "a".repeat(64)); put("startUtf16", start); put("endUtf16", start + text.length)
        })
    }

    @Test fun repeatedWordsAndUtf16UseExactSourceOffsetsAcrossDifferentBlocksAndPages() {
        val first = V4AnnotationSources.block("group", resource("repeat 😀", 30), emptyMap())
        val same = V4AnnotationSources.block("different-layout", resource("repeat 😀", 30, "other"), emptyMap())
        val later = V4AnnotationSources.block("group", resource("repeat 😀", 80), emptyMap())
        val change = first.selection("part", 0, first.text.length, true).single()
        assertEquals(30, change.start)
        assertEquals(39, change.end)
        val saved = listOf(Highlight(change.key, HighlightRange(change.start, change.end, 1)))
        assertEquals(listOf(HighlightRange(0, 9, 1)), same.visibleRanges("part", saved))
        assertTrue(later.visibleRanges("part", saved).isEmpty())
        assertEquals(change, same.selection("part", 0, same.text.length, false).single())
    }

    @Test fun inlineEditorsAndInsertedSeparatorsNeverBecomePartOfSavedHighlights() {
        val left = V4AnnotationSources.block("g", resource("alpha", 10), emptyMap())
        val right = V4AnnotationSources.block("g", resource("beta", 50), emptyMap())
        val run = AnnotationProjection.join(listOf(left, AnnotationProjection("\uFFFC", emptyList()), right))
        val changes = run.selection("part", 0, run.text.length, true)
        assertEquals(listOf(10 to 15, 50 to 54), changes.map { it.start to it.end })
        val saved = changes.map { Highlight(it.key, HighlightRange(it.start, it.end, 1)) }
        assertEquals(listOf(0 to 5, 8 to 12), run.visibleRanges("part", saved).map { it.start to it.end })
        assertTrue(run.selection("part", 6, 7, true).isEmpty())
        assertEquals(12, run.slice(2, 4).selection("part", 0, 2, true).single().start)
    }

    @Test fun optionAliasesPreserveLegacyHighlightsAndCancelAllSharedQuestionKeys() {
        val option = Option("A", "Alpha label")
        val questions = (1..2).map { Question("q$it", it, QuestionType.MATCHING, "Prompt", listOf(option), "A") }
        val projection = V4AnnotationSources.option(option, questions, option.text)
        val legacy = AnnotationDocument.single("A. Alpha label", "question", "q2/option:A", "und")
            .selection("part", 3, 8).single()
        val saved = listOf(Highlight(legacy.key, HighlightRange(3, 8, 1)))
        assertEquals(listOf(HighlightRange(0, 5, 1)), projection.visibleRanges("part", saved))
        assertEquals(1, projection.selection("part", 0, 5, true).size)
        assertEquals(setOf("q1/option:A", "q2/option:A"),
            projection.selection("part", 0, 5, false).map { it.key.textId }.toSet())
    }

    @Test fun partFieldUsesOriginalWholeFieldHashAndOffsetInsteadOfSliceHash() {
        val q = Question("q1", 1, QuestionType.TEXT_INPUT, "First alpha then beta", emptyList(), "value")
        val block = buildJsonObject {
            put("id", "slice"); put("text", "alpha")
            put("source", buildJsonObject {
                put("kind", "partField"); put("field", "question.prompt"); put("questionId", q.id)
                put("startUtf16", 6); put("endUtf16", 11)
            })
        }
        val change = V4AnnotationSources.block("g", block, mapOf(q.id to q)).selection("part", 0, 5, true).single()
        assertEquals("q1/prompt", change.key.textId)
        assertEquals(contentHash(q.prompt), change.key.contentHash)
        assertEquals(6 to 11, change.start to change.end)
    }

    @Test fun everyCurrentPackTextBlockAndOptionHasRoundTripAnnotationCoverage() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val packs = File(root, "private-data/layout-v4-android-test-packs").listFiles { file -> file.extension == "eelpack" }
        requireNotNull(packs) { "Local reviewed V4 packs required for coverage audit" }
        assertEquals(17, packs.size)
        var parts = 0
        var texts = 0
        fun objects(element: JsonElement): List<JsonObject> = when (element) {
            is JsonObject -> listOf(element) + element.values.flatMap(::objects)
            is JsonArray -> element.flatMap(::objects)
            else -> emptyList()
        }
        packs.forEach { pack -> ZipFile(pack).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith("/layout.json") }.forEach { entry ->
                val layout = Json.parseToJsonElement(zip.getInputStream(entry).reader().readText()).jsonObject
                val partEntry = zip.getEntry(entry.name.substringBeforeLast('/') + "/part.json")
                val part = PartCodec.decode(zip.getInputStream(partEntry).reader().readText())
                val byId = part.questions.associateBy { it.id }
                parts++
                layout.getValue("groups").jsonArray.forEach { item ->
                    val group = item.jsonObject
                    val id = group.getValue("groupId").jsonPrimitive.content
                    listOf("instructions", "blocks").flatMap { objects(group.getValue(it)) }
                        .filter { it["type"]?.jsonPrimitive?.content == "text" }.forEach { block ->
                            val projection = V4AnnotationSources.block(id, block, byId)
                            if (projection.text.isNotEmpty()) {
                                val changes = projection.selection(part.id, 0, projection.text.length, true)
                                assertEquals("Unmapped V4 text in $id", 1, changes.size)
                                val saved = changes.map { Highlight(it.key, HighlightRange(it.start, it.end, 1)) }
                                assertEquals(listOf(HighlightRange(0, projection.text.length, 1)), projection.visibleRanges(part.id, saved))
                            }
                            texts++
                        }
                    group.getValue("questionIds").jsonArray.map { byId.getValue(it.jsonPrimitive.content) }.forEach { q ->
                        q.options.forEach { option ->
                            val projection = V4AnnotationSources.option(option, listOf(q), "${option.id}. ${option.text}")
                            assertEquals(1, projection.selection(part.id, 0, projection.text.length, true).size)
                        }
                    }
                }
            }
        } }
        assertEquals(272, parts)
        assertEquals(5595, texts)
    }
}
