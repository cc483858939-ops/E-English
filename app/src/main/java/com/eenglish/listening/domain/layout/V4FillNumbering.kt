package com.eenglish.listening.domain.layout

import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.annotation.AnnotationProjection
import com.eenglish.listening.domain.annotation.contentHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class FillNumberCutCatalog(val schemaVersion: Int, val entries: List<FillNumberCut>) {
    private val byGroup by lazy { entries.groupBy { it.groupId } }
    fun forGroup(id: String): List<FillNumberCut> = if (schemaVersion == 1) byGroup[id].orEmpty() else emptyList()
}

@Serializable
data class FillNumberCut(
    val groupId: String, val blockId: String, val slotId: String,
    val questionId: String, val questionNumber: Int, val textSha256: String,
    val startUtf16: Int, val endUtf16: Int,
)

/** Display-only cuts: original text, hashes and UTF-16 annotation coordinates stay intact. */
object V4FillNumbering {
    fun hiddenRanges(group: JsonObject, questions: Map<String, Question>,
                     catalog: FillNumberCutCatalog): Map<String, List<IntRange>> {
        val result = hiddenPrefixes(group, questions).mapValues { mutableListOf(0 until it.value) }.toMutableMap()
        val slots = (group["slots"] as? JsonArray).orEmpty().map { it.jsonObject }
            .associateBy { it["slotId"]?.jsonPrimitive?.contentOrNull }
        val entries = catalog.forGroup(group["groupId"]?.jsonPrimitive?.contentOrNull.orEmpty())
        fun walk(value: JsonElement) {
            when (value) {
                is JsonObject -> {
                    val children = (value["children"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    children.forEachIndexed { index, control ->
                        if (control["type"]?.jsonPrimitive?.contentOrNull != "slot") return@forEachIndexed
                        val slotId = control["slotId"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                        val slot = slots[slotId] ?: return@forEachIndexed
                        if (slot["kind"]?.jsonPrimitive?.contentOrNull != "fill") return@forEachIndexed
                        val questionId = slot["questionId"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                        val question = questions[questionId] ?: return@forEachIndexed
                        val preceding = children.take(index).asReversed()
                            .takeWhile { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                        entries.filter { it.slotId == slotId && it.questionId == questionId && it.questionNumber == question.number }
                            .forEach { cut ->
                                val block = preceding.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == cut.blockId }
                                    ?: return@forEach
                                val text = block["text"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                                if (contentHash(text) != cut.textSha256 || cut.startUtf16 < 0 ||
                                    cut.endUtf16 <= cut.startUtf16 || cut.endUtf16 > text.length ||
                                    text.substring(cut.startUtf16, cut.endUtf16) != question.number.toString()) return@forEach
                                // Remove the following redundant spaces, but retain surrounding words and symbols.
                                var end = cut.endUtf16
                                if (cut.startUtf16 == 0 || text[cut.startUtf16 - 1].isWhitespace()) {
                                    while (end < text.length && text[end].isWhitespace()) end++
                                }
                                result.getOrPut(cut.blockId) { mutableListOf() }.add(cut.startUtf16 until end)
                            }
                    }
                    value.values.forEach(::walk)
                }
                is JsonArray -> value.forEach(::walk)
                else -> Unit
            }
        }
        group["blocks"]?.let(::walk)
        return result
    }

    fun project(original: AnnotationProjection, hidden: List<IntRange>): AnnotationProjection {
        if (hidden.isEmpty() || hidden.any { it.isEmpty() || it.first < 0 || it.last >= original.text.length }) return original
        val visible = mutableListOf<AnnotationProjection>()
        var cursor = 0
        hidden.sortedBy { it.first }.forEach { range ->
            if (range.first > cursor) visible += original.slice(cursor, range.first)
            cursor = maxOf(cursor, range.last + 1)
        }
        if (cursor < original.text.length) visible += original.slice(cursor, original.text.length)
        return AnnotationProjection.join(visible, "")
    }

    fun hiddenPrefixes(group: JsonObject, questions: Map<String, Question>): Map<String, Int> {
        val slots = (group["slots"] as? JsonArray).orEmpty().map { it.jsonObject }
            .associateBy { it["slotId"]?.jsonPrimitive?.content }
        val cuts = mutableMapOf<String, Int>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    val children = (element["children"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    if (element["type"]?.jsonPrimitive?.content in setOf("paragraph", "formRow", "listItem") &&
                        children.all { it["type"]?.jsonPrimitive?.content in setOf("text", "slot") }) {
                        val fields = children.filter { it["type"]?.jsonPrimitive?.content == "slot" }
                            .mapNotNull { slots[it["slotId"]?.jsonPrimitive?.content] }
                        val ids = fields.mapNotNull { it["questionId"]?.jsonPrimitive?.content }.distinct()
                        val first = children.firstOrNull { it["text"]?.jsonPrimitive?.content?.isNotBlank() == true }
                        if (fields.isNotEmpty() && fields.all { it["kind"]?.jsonPrimitive?.content == "fill" } &&
                            ids.size == 1 && first != null && children.indexOf(first) < children.indexOfFirst {
                                it["type"]?.jsonPrimitive?.content == "slot"
                            }) {
                            val question = questions[ids.single()]
                            val id = first["id"]?.jsonPrimitive?.content
                            if (question != null && id != null) {
                                val cut = prefixLength(first["text"]!!.jsonPrimitive.content, question.number)
                                if (cut > 0) cuts[id] = cut
                            }
                        }
                    }
                    element.values.forEach(::walk)
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        group["blocks"]?.let(::walk)
        return cuts
    }

    internal fun prefixLength(text: String, number: Int): Int {
        val match = Regex("^\\s*(?:\\($number\\)|（$number）|Q\\.?\\s*$number[.)]?|$number[.)]?)(?:\\s+|$)")
            .find(text) ?: return 0
        val rest = text.substring(match.value.length)
        val explicit = match.value.trimStart().startsWith("Q") || match.value.trimStart().startsWith("(") ||
            match.value.trimStart().startsWith("（")
        if (rest.isNotEmpty() && !explicit) {
            // Ambiguous quantities, dates, decimals and lower-case continuations stay visible.
            if (!rest.first().isUpperCase() && rest.first() !in "\"'‘“(") return 0
            val word = rest.takeWhile { it.isLetter() }.lowercase()
            if (word in setOf("january", "february", "march", "april", "may", "june", "july", "august",
                    "september", "october", "november", "december", "days", "hours", "weeks", "months",
                    "years", "minutes", "seconds", "percent", "km", "am", "pm", "gbp", "usd")) return 0
        }
        return match.value.length
    }
}

fun fillNumberLabel(number: Int, slotIndex: Int, slotCount: Int): String =
    if (slotCount > 1) "$number.${slotIndex + 1}" else number.toString()
