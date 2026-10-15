package com.eenglish.listening.domain.layout

import com.eenglish.listening.domain.annotation.AnnotationProjection
import com.eenglish.listening.domain.annotation.contentHash
import com.eenglish.listening.domain.model.Option
import com.eenglish.listening.domain.model.Question
import kotlinx.serialization.json.*

/** Resource offsets stay stable when a V4 paragraph is split or used in another page. */
object V4AnnotationSources {
    private fun JsonObject.string(name: String) = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun block(groupId: String, block: JsonObject, questions: Map<String, Question>): AnnotationProjection {
        val text = block.string("text")
        if (text.isEmpty()) return AnnotationProjection(text, emptyList())
        val source = block["source"] as? JsonObject
        val start = source?.get("startUtf16")?.jsonPrimitive?.intOrNull ?: 0
        val end = source?.get("endUtf16")?.jsonPrimitive?.intOrNull ?: text.length
        val question = questions[source?.string("questionId")]
        val field = source?.string("field")
        val original = when (field) {
            "question.prompt" -> question?.prompt
            "question.instructions" -> question?.instructions
            "question.context" -> question?.context
            else -> null
        }
        val mapped = when {
            source?.string("kind") == "partField" && original != null && start >= 0 &&
                end <= original.length && start <= end && original.substring(start, end) == text ->
                AnnotationProjection.Source("${question!!.id}/${field!!.substringAfter('.')}",
                    contentHash(original), start, original.length)
            source?.string("kind") == "resourceRange" && start >= 0 && end - start == text.length &&
                source.string("sha256").matches(Regex("[a-f0-9]{64}")) ->
                AnnotationProjection.Source("v4/resource/${source.string("normalization")}/${source.string("resource")}",
                    source.string("sha256"), start, end)
            else -> AnnotationProjection.Source("v4/block/$groupId/${block.string("id")}",
                contentHash(text), 0, text.length)
        }
        return AnnotationProjection(text, listOf(AnnotationProjection.Span(0, text.length, listOf(mapped))))
    }

    /** The legacy option document includes the letter prefix, even when V4 displays only the label. */
    fun option(option: Option, questions: List<Question>, display: String): AnnotationProjection {
        val original = "${option.id}. ${option.text}"
        val offset = when (display) {
            original -> 0
            option.text -> option.id.length + 2
            option.id -> 0
            else -> return AnnotationProjection(display, listOf(AnnotationProjection.Span(0, display.length,
                listOf(AnnotationProjection.Source("v4/option/${questions.first().id}/${option.id}", contentHash(display), 0, display.length)))))
        }
        val sources = questions.filter { q -> q.options.any { it == option } }.map { q ->
            AnnotationProjection.Source("${q.id}/option:${option.id}", contentHash(original), offset, original.length)
        }
        return AnnotationProjection(display, if (display.isEmpty() || sources.isEmpty()) emptyList()
            else listOf(AnnotationProjection.Span(0, display.length, sources)))
    }
}
