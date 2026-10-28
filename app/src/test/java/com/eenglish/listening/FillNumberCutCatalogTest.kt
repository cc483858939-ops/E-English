package com.eenglish.listening

import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.annotation.*
import com.eenglish.listening.domain.layout.*
import com.eenglish.listening.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class FillNumberCutCatalogTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private fun catalog() = Json.decodeFromString<FillNumberCutCatalog>(
        File(root, "app/src/main/assets/fill-number-cuts.json").readText())
    private fun walk(element: JsonElement): List<JsonObject> = when (element) {
        is JsonObject -> listOf(element) + element.values.flatMap(::walk)
        is JsonArray -> element.flatMap(::walk)
        else -> emptyList()
    }

    @Test fun all507ReviewedCutsMatchCurrentPacksAndPreserveEverythingExceptNumbersAndSpaces() {
        val catalog = catalog()
        assertEquals(507, catalog.entries.size)
        assertEquals(74, catalog.entries.map { it.groupId }.distinct().size)
        var checked = 0
        var oldCount = 0
        var rangeCount = 0
        val affectedParts = mutableSetOf<String>()
        val affectedBooks = mutableSetOf<Int>()
        val normalize: (String) -> String = { it.replace(Regex("\\s+"), " ").trim() }
        File(root, "private-data/layout-v4-android-test-packs").listFiles()!!
            .filter { it.extension == "eelpack" }.forEach { pack ->
                ZipFile(pack).use { zip ->
                    zip.entries().asSequence().filter { it.name.endsWith("/layout.json") }.forEach { entry ->
                        val layout = Json.parseToJsonElement(zip.getInputStream(entry).reader().readText()).jsonObject
                        val part = PartCodec.decode(zip.getInputStream(zip.getEntry(entry.name.replace("layout.json", "part.json"))).reader().readText())
                        val questions = part.questions.associateBy { it.id }
                        layout["groups"]!!.jsonArray.forEach { groupElement ->
                            val group = groupElement.jsonObject
                            val cuts = V4FillNumbering.hiddenRanges(group, questions, catalog)
                            oldCount += V4FillNumbering.hiddenPrefixes(group, questions).size
                            rangeCount += cuts.values.sumOf { it.size }
                            val blocks = walk(group["blocks"]!!).associateBy { it["id"]?.jsonPrimitive?.content }
                            catalog.forGroup(group["groupId"]!!.jsonPrimitive.content).forEach { cut ->
                                val block = blocks[cut.blockId]!!
                                val text = block["text"]!!.jsonPrimitive.content
                                assertEquals(contentHash(text), cut.textSha256)
                                assertEquals(cut.questionNumber.toString(), text.substring(cut.startUtf16, cut.endUtf16))
                                assertTrue(cuts[cut.blockId]!!.any { cut.startUtf16 in it && cut.endUtf16 - 1 in it })
                                val original = V4AnnotationSources.block(cut.groupId, block, questions)
                                val projected = V4FillNumbering.project(original, cuts[cut.blockId]!!)
                                assertEquals(normalize(text.removeRange(cut.startUtf16, cut.endUtf16)), normalize(projected.text))
                                assertEquals(text, original.text)
                                // Every retained character continues to address its original source position.
                                projected.spans.forEach { span -> span.sources.forEach { source ->
                                    val oldSource = original.spans.single().sources.single()
                                    val start = source.offset - oldSource.offset
                                    assertEquals(text.substring(start, start + span.end - span.start),
                                        projected.text.substring(span.start, span.end))
                                    assertEquals(oldSource.key(part.id), source.key(part.id))
                                } }
                                checked++
                                affectedParts += part.id
                                affectedBooks += part.book
                            }
                        }
                    }
                }
            }
        assertEquals(507, checked)
        assertEquals(73, affectedParts.size)
        assertEquals(12, affectedBooks.size)
        assertEquals(103, oldCount)
        assertEquals(610, rangeCount)
    }

    private val q = Question("q7", 7, QuestionType.TEXT_INPUT, "fixture", emptyList(), "cost")
    private val text = "💷 Cost 7 £ "
    private fun fixture(textValue: String = text, questionId: String = q.id, kind: String = "fill") = buildJsonObject {
        put("groupId", "g")
        put("slots", buildJsonArray { add(buildJsonObject {
            put("slotId", "s"); put("questionId", questionId); put("kind", kind)
        }) })
        put("blocks", buildJsonArray { add(buildJsonObject {
            put("type", "formRow"); put("children", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("id", "b"); put("text", textValue) })
                add(buildJsonObject { put("type", "slot"); put("slotId", "s") })
            })
        }) })
    }
    private fun cut() = FillNumberCut("g", "b", "s", q.id, q.number, contentHash(text), text.indexOf('7'), text.indexOf('7') + 1)

    @Test fun staleTextQuestionSlotsAndInvalidRangesNeverApplyReviewedCuts() {
        val cut = cut()
        fun ranges(c: FillNumberCut = cut, group: JsonObject = fixture(), version: Int = 1) =
            V4FillNumbering.hiddenRanges(group, mapOf(q.id to q), FillNumberCutCatalog(version, listOf(c)))
        assertTrue(ranges().isNotEmpty())
        assertTrue(ranges(group = fixture(text + "changed")).isEmpty())
        assertTrue(ranges(group = fixture(questionId = "other")).isEmpty())
        assertTrue(ranges(group = fixture(kind = "matching")).isEmpty())
        assertTrue(ranges(cut.copy(blockId = "other")).isEmpty())
        assertTrue(ranges(cut.copy(slotId = "other")).isEmpty())
        assertTrue(ranges(cut.copy(questionNumber = 8)).isEmpty())
        assertTrue(ranges(cut.copy(startUtf16 = -1)).isEmpty())
        assertTrue(ranges(cut.copy(endUtf16 = text.length + 1)).isEmpty())
        assertTrue(ranges(cut.copy(startUtf16 = 0, endUtf16 = 1)).isEmpty())
        assertTrue(ranges(version = 2).isEmpty())
        assertTrue(V4FillNumbering.hiddenRanges(fixture(), mapOf(q.id to q), FillNumberCutCatalog(1, emptyList())).isEmpty())
    }

    @Test fun middleCutsKeepOldHighlightsAndCopyCoordinatesOnBothSidesIncludingSurrogatePairs() {
        val source = AnnotationProjection.Source("fixture", "a".repeat(64), 100, 100 + text.length)
        val original = AnnotationProjection(text, listOf(AnnotationProjection.Span(0, text.length, listOf(source))))
        val cuts = V4FillNumbering.hiddenRanges(fixture(), mapOf(q.id to q), FillNumberCutCatalog(1, listOf(cut())))
        val visible = V4FillNumbering.project(original, cuts["b"]!!)
        assertEquals("💷 Cost £ ", visible.text)
        for (word in listOf("💷", "Cost", "£")) {
            val before = original.selection("part", text.indexOf(word), text.indexOf(word) + word.length, true)
            val start = visible.text.indexOf(word)
            assertEquals(before, visible.selection("part", start, start + word.length, true))
            val old = before.map { Highlight(it.key, HighlightRange(it.start, it.end, 1)) }
            assertEquals(listOf(HighlightRange(start, start + word.length, 1)), visible.visibleRanges("part", old))
            assertEquals(before, visible.selection("part", start, start + word.length, false))
        }
        val wholeOldHighlight = Highlight(source.key("part"), HighlightRange(100, 100 + text.length, 1))
        assertEquals(listOf(HighlightRange(0, visible.text.length, 1)), visible.visibleRanges("part", listOf(wholeOldHighlight)))
        val copiedRanges = visible.selection("part", 0, visible.text.length, true)
        assertEquals(2, copiedRanges.size)
        assertTrue(copiedRanges.all { it.end <= 100 + cut().startUtf16 || it.start >= 100 + cut().endUtf16 })
        assertEquals(original, V4FillNumbering.project(original, listOf(-1..1)))
        assertEquals(original, V4FillNumbering.project(original, listOf(0..text.length)))
        assertEquals("", V4FillNumbering.project(original, listOf(text.indices)).text)
    }
}
