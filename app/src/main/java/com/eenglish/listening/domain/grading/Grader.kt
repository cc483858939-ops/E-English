package com.eenglish.listening.domain.grading

import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType
import kotlin.math.roundToInt
import java.text.Normalizer

data class Grade(val correctCount: Int, val totalCount: Int) {
    val wrongCount: Int get() = totalCount - correctCount
    val accuracy: Int get() = (correctCount * 100.0 / totalCount).roundToInt()
}

object Grader {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .trim().replace(Regex("[\\s\u00a0\u202f]+"), " ").lowercase(java.util.Locale.ROOT)

    fun selection(value: String?): Set<String> = value.orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    fun answerParts(question: Question, value: String?): List<String> {
        val separator = question.answerSeparator ?: return listOf(value.orEmpty())
        return value.orEmpty().split(Regex("(?:^|\\s+)${Regex.escape(separator)}(?:\\s+|$)", RegexOption.IGNORE_CASE))
    }

    fun withinLimit(question: Question, value: String): Boolean {
        val limit = question.wordLimit ?: return true
        val parts = answerParts(question, normalize(value))
        if (question.answerSeparator != null && (parts.size != 2 || parts.any { it.isBlank() })) return false
        val counted = parts.joinToString(" ").replace(
            Regex("(?<!\\S)(\\d{1,2}(?:[.:]\\d{2})?)\\s+([ap]\\.?m\\.?)(?!\\S)"), "$1$2")
        val tokens = counted.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val numbers = tokens.count { it.matches(Regex("(?:[£$€+-]?\\d+(?:[.,:/-]\\d+)*(?:st|nd|rd|th)?%?|\\d{1,2}(?:[.:]\\d{2})?[ap]\\.?m\\.?)")) }
        val words = tokens.size - numbers
        return words <= limit.maxWords && (limit.maxNumbers == null || numbers <= limit.maxNumbers)
            && (!limit.numberOnly || words == 0) && (!limit.wordsOrNumber || words == 0 || numbers == 0)
    }

    fun correctIds(questions: List<Question>, selected: Map<String, String>): Set<String> {
        val correct = mutableSetOf<String>()
        questions.filter { it.type != QuestionType.MULTIPLE_CHOICE }.forEach { question ->
            val value = selected[question.id]
            if (!value.isNullOrBlank() && withinLimit(question, value) &&
                question.acceptableAnswers.any { normalize(value) == normalize(it) }) correct += question.id
        }
        questions.filter { it.type == QuestionType.MULTIPLE_CHOICE }.groupBy { it.groupId }.values.forEach { group ->
            val sets = group.map { selection(selected[it.id]) }
            // Group choices are persisted atomically on every slot. No duplicate credit.
            if (sets.distinct().size == 1 && sets.first().size <= group.size) {
                group.forEach { q -> if (q.acceptableAnswers.any { it.uppercase() in sets.first() }) correct += q.id }
            }
        }
        return correct
    }

    fun missingCount(questions: List<Question>, selected: Map<String, String>): Int =
        questions.filter { it.type != QuestionType.MULTIPLE_CHOICE }.count {
            selected[it.id].isNullOrBlank() || it.answerSeparator != null &&
                (answerParts(it, selected[it.id]).size != 2 || answerParts(it, selected[it.id]).any(String::isBlank))
        } +
        questions.filter { it.type == QuestionType.MULTIPLE_CHOICE }.groupBy { it.groupId }.values.sumOf { group ->
            (group.size - selection(selected[group.first().id]).size).coerceAtLeast(0)
        }

    fun grade(questions: List<Question>, selected: Map<String, String>): Grade {
        require(questions.isNotEmpty() && questions.map { it.id }.distinct().size == questions.size)
        require(selected.keys.all { id -> questions.any { it.id == id } })
        return Grade(correctIds(questions, selected).size, questions.size)
    }
}
