package com.eenglish.listening.domain.gapfill

import com.eenglish.listening.domain.annotation.AnnotationChange
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.domain.annotation.Highlight
import com.eenglish.listening.domain.annotation.HighlightRange
import com.eenglish.listening.domain.model.LayoutBlock
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionLayoutGroup

data class StructuredGapSlot(val questionId: String, val number: Int, val slotIndex: Int, val offset: Int)
data class StructuredTextSource(val questionId: String, val startUtf16: Int, val endUtf16: Int,
    val displayStart: Int, val displayEnd: Int, val text: String)

/** One paragraph, form row, or list item projected for NativeInlineGapFillView. */
data class StructuredLayoutProjection(
    val text: String,
    val slots: List<StructuredGapSlot>,
    val textSources: List<StructuredTextSource>,
) {
    fun copyVisible(start: Int, end: Int): String {
        require(start in 0..text.length && end in start..text.length)
        return text.substring(start, end).replace("\uFFFC", "_____")
    }

    fun visibleHighlights(partId: String, group: QuestionLayoutGroup, questions: List<Question>,
        highlights: List<Highlight>): List<HighlightRange> {
        val result = mutableListOf<HighlightRange>()
        textSources.forEach { source ->
            val sourceHash = group.promptHashes[source.questionId] ?: return@forEach
            questions.filter { question ->
                group.promptHashes[question.id] == sourceHash && source.endUtf16 <= question.prompt.length &&
                    question.prompt.substring(source.startUtf16, source.endUtf16) == source.text
            }.forEach { question ->
                val document = AnnotationDocument.single(question.prompt, "question", "${question.id}/prompt", "und")
                document.visibleRanges(partId, highlights).forEach { range ->
                    val from = maxOf(range.start, source.startUtf16)
                    val to = minOf(range.end, source.endUtf16)
                    if (from < to) result += range.copy(start = source.displayStart + from - source.startUtf16,
                        end = source.displayStart + to - source.startUtf16)
                }
            }
        }
        return result
    }

    fun selection(partId: String, group: QuestionLayoutGroup, questions: List<Question>,
        activeQuestionId: String?, start: Int, end: Int, add: Boolean): List<AnnotationChange> {
        if (start !in 0 until text.length || end <= start) return emptyList()
        return textSources.flatMap { source ->
            val from = maxOf(start, source.displayStart)
            val to = minOf(end, source.displayEnd)
            if (from >= to) return@flatMap emptyList()
            val sourceHash = group.promptHashes[source.questionId] ?: return@flatMap emptyList()
            val targetQuestionId = if (add) activeQuestionId ?: source.questionId else null
            questions.filter { question ->
                (targetQuestionId == null || question.id == targetQuestionId) && group.promptHashes[question.id] == sourceHash &&
                    source.endUtf16 <= question.prompt.length &&
                    question.prompt.substring(source.startUtf16, source.endUtf16) == source.text
            }.flatMap { question ->
                AnnotationDocument.single(question.prompt, "question", "${question.id}/prompt", "und")
                    .selection(partId, source.startUtf16 + from - source.displayStart,
                        source.startUtf16 + to - source.displayStart)
            }
        }
    }

    companion object {
        fun from(blocks: List<LayoutBlock>): StructuredLayoutProjection {
            val text = StringBuilder()
            val slots = mutableListOf<StructuredGapSlot>()
            val sources = mutableListOf<StructuredTextSource>()
            fun append(block: LayoutBlock) {
                when (block.type) {
                    "text", "fixedText" -> {
                        val value = requireNotNull(block.text)
                        val displayStart = text.length
                        text.append(value)
                        block.source?.takeIf { it.kind == "prompt" }?.let { source ->
                            sources += StructuredTextSource(requireNotNull(source.questionId),
                                requireNotNull(source.startUtf16), requireNotNull(source.endUtf16),
                                displayStart, text.length, value)
                        }
                    }
                    "blank" -> {
                        slots += StructuredGapSlot(requireNotNull(block.questionId),
                            requireNotNull(block.questionNumber), requireNotNull(block.slotIndex), text.length)
                        text.append('\uFFFC')
                    }
                    "lineBreak" -> text.append('\n')
                    "paragraph", "listItem", "formRow" -> block.children.forEach(::append)
                }
            }
            blocks.forEach(::append)
            return StructuredLayoutProjection(text.toString(), slots, sources)
        }
    }
}
