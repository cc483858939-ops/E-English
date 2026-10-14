package com.eenglish.listening.domain.layout

import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.Question
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Completeness rules that belong to the display layout while preserving the stored legacy answer. */
object LayoutV4AnswerStatus {
    fun missingCount(questions: List<Question>, layout: JsonObject?, answers: Map<String, String>): Int =
        Grader.missingCount(questions, answers) + incompletePairedFillCount(questions, layout, answers)

    fun incompletePairedFillCount(
        questions: List<Question>,
        layout: JsonObject?,
        answers: Map<String, String>,
    ): Int {
        if (layout == null) return 0
        val questionIds = linkedSetOf<String>()
        val groups = layout["groups"] as? JsonArray ?: return 0
        groups.forEach { groupElement ->
            val responses = groupElement.jsonObject["responses"] as? JsonArray ?: return@forEach
            responses.forEach { responseElement ->
                val response = responseElement.jsonObject
                if (response["kind"]?.jsonPrimitive?.content != "fill") return@forEach
                val slots = response["slotIds"] as? JsonArray ?: return@forEach
                val ids = response["questionIds"] as? JsonArray ?: return@forEach
                if (slots.size != 2 || ids.size != 1) return@forEach
                ids.singleOrNull()?.jsonPrimitive?.content?.let(questionIds::add)
            }
        }
        val questionById = questions.associateBy { it.id }
        return questionIds.count { id ->
            val question = questionById[id] ?: return@count false
            // Grader.missingCount already checks the two sides for legacy answers that
            // declare answerSeparator in part.json. Only reviewed V4 joiners need this
            // additional completeness check.
            if (question.answerSeparator != null) return@count false
            val stored = answers[id].orEmpty().trim()
            if (stored.isBlank()) return@count false // Grader.missingCount already accounts for this.
            val response = groups.asSequence().mapNotNull { it as? JsonObject }
                .flatMap { (it["responses"] as? JsonArray).orEmpty().asSequence().mapNotNull { r -> r as? JsonObject } }
                .firstOrNull { r ->
                    r["kind"]?.jsonPrimitive?.content == "fill" &&
                        (r["questionIds"] as? JsonArray)?.singleOrNull()?.jsonPrimitive?.content == id &&
                        (r["slotIds"] as? JsonArray)?.size == 2
                } ?: return@count false
            val joiner = response["joiner"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
                ?: return@count true
            val values = stored.split(Regex("(?:^|\\s+)${Regex.escape(joiner)}(?:\\s+|$)", RegexOption.IGNORE_CASE), limit = 2)
            values.size != 2 || values.any { it.isBlank() }
        }
    }
}
