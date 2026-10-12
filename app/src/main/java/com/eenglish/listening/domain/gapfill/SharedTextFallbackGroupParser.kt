package com.eenglish.listening.domain.gapfill

import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType

data class SharedTextFallbackQuestionGroup(
    val questions: List<Question>,
    val title: String,
    val prompt: String,
    val instructions: String,
    val context: String,
)

/** Conservative display fallback for explicit, shared numbered text-fill prompts without layouts. */
object SharedTextFallbackGroupParser {
    private const val MAX_GROUP_SIZE = 20
    private val rangeLine = Regex(
        "(?i)^\\s*Questions?\\s+(\\d{1,3})\\s*[-–—]\\s*(\\d{1,3})\\s*$"
    )
    private val placeholder = Regex("(?:_{2,}|[＿﹍]{2,})")
    private val numberedQuestionLabel = Regex("(?m)^[ \\t]*([1-9]\\d{0,2})[.)]?(?:[ \\t]+|\\r?$)")

    fun parseAtStart(questions: List<Question>): SharedTextFallbackQuestionGroup? {
        val first = questions.firstOrNull() ?: return null
        if (!eligible(first)) return null
        val lines = first.prompt.replace("\r\n", "\n").replace('\r', '\n').lines()
        val title = lines.firstOrNull()?.trim().orEmpty()
        val range = rangeLine.matchEntire(title) ?: return null
        val start = range.groupValues[1].toIntOrNull() ?: return null
        val end = range.groupValues[2].toIntOrNull() ?: return null
        if (first.number != start || end < start) return null
        val size = end - start + 1
        if (size !in 2..MAX_GROUP_SIZE || questions.size < size) return null

        val members = questions.take(size)
        if (members.map { it.number } != (start..end).toList() ||
            members.map { it.id }.distinct().size != size) return null
        val promptKey = normalize(first.prompt)
        val instructionsKey = normalize(first.instructions)
        val contextKey = normalize(first.context)
        val wordLimit = first.wordLimit ?: return null
        if (members.any { question ->
                !eligible(question) || question.type != first.type || normalize(question.prompt) != promptKey ||
                    normalize(question.instructions) != instructionsKey || normalize(question.context) != contextKey ||
                    question.wordLimit != wordLimit
            }) return null

        val blanks = placeholder.findAll(first.prompt).toList()
        if (blanks.size != size) return null
        var sourceOffset = 0
        blanks.forEachIndexed { index, blank ->
            val precedingText = first.prompt.substring(sourceOffset, blank.range.first)
            val questionNumbers = numberedQuestionLabel.findAll(precedingText)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in start..end }
                .toList()
            if (questionNumbers != listOf(start + index)) return null
            sourceOffset = blank.range.last + 1
        }

        return SharedTextFallbackQuestionGroup(members, title, first.prompt, first.instructions, first.context)
    }

    private fun eligible(question: Question): Boolean =
        question.isTextInput && question.type in setOf(
            QuestionType.TEXT_INPUT,
            QuestionType.NOTE_COMPLETION,
            QuestionType.TABLE_COMPLETION,
            QuestionType.FLOW_COMPLETION,
            QuestionType.SHORT_ANSWER,
        ) && question.options.isEmpty() && question.wordLimit != null &&
            question.answerSeparator == null && question.groupId == null &&
            question.groupNumbers.isEmpty() && question.images.isEmpty()

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}
