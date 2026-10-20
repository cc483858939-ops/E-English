package com.eenglish.listening.domain.layout

import com.eenglish.listening.domain.model.Question
import kotlinx.serialization.json.*

/** Display-only cuts: original text, hashes and UTF-16 annotation coordinates stay intact. */
object V4FillNumbering {
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
