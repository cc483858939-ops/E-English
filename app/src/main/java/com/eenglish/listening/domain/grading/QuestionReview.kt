package com.eenglish.listening.domain.grading

import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType

enum class ReviewStatus(val label: String) { CORRECT("回答正确"), INCORRECT("回答错误"), UNANSWERED("未作答") }

data class QuestionReview(
    val questions: List<Question>, val status: ReviewStatus, val userAnswer: String,
    val standardAnswer: String, val correctCount: Int, val acceptedVariants: List<String> = emptyList(),
) {
    val id get() = questions.first().id
    val title get() = "Q" + questions.joinToString("、") { it.number.toString() }
    val questionIds get() = questions.map { it.id }
}

/** Presentation only: always grade the entire attempt, including atomic shared multi-choice groups. */
fun questionReviews(questions: List<Question>, answers: Map<String, String>): List<QuestionReview> {
    val correct = Grader.correctIds(questions, answers)
    val seenGroups = mutableSetOf<String?>()
    return questions.mapNotNull { question ->
        val multiple = question.type == QuestionType.MULTIPLE_CHOICE
        if (multiple && !seenGroups.add(question.groupId)) return@mapNotNull null
        val group = if (multiple) questions.filter { it.type == QuestionType.MULTIPLE_CHOICE && it.groupId == question.groupId }
            else listOf(question)
        val count = group.count { it.id in correct }
        val raw = answers[question.id].orEmpty()
        val missing = if (multiple) Grader.selection(raw).isEmpty() else raw.isBlank() ||
            question.answerSeparator != null && (Grader.answerParts(question, raw).size != 2 || Grader.answerParts(question, raw).any { it.isBlank() })
        fun optionLabel(id: String): String = question.options.firstOrNull { it.id.equals(id, true) }
            ?.let { if (it.text.isBlank() || it.text.equals(it.id, true)) it.id else "${it.id} — ${it.text}" } ?: id
        val user = if (question.options.isEmpty()) raw else if (multiple)
            Grader.selection(raw).sorted().joinToString("\n", transform = ::optionLabel) else optionLabel(raw)
        fun canonical(q: Question): String = q.acceptableAnswers.firstOrNull {
            Grader.normalize(it) == Grader.normalize(q.correctAnswer)
        } ?: q.acceptableAnswers.first()
        fun display(value: String) = if (question.options.isEmpty()) value else optionLabel(value)
        val standard = group.joinToString("\n") { display(canonical(it)) }
        val alternatives = group.flatMap { q -> q.acceptableAnswers.filter {
            Grader.normalize(it) != Grader.normalize(canonical(q))
        }.map { if (multiple) "Q${q.number}：${display(it)}" else display(it) } }.distinct()
        QuestionReview(group, when {
            count == group.size -> ReviewStatus.CORRECT
            missing -> ReviewStatus.UNANSWERED
            else -> ReviewStatus.INCORRECT
        }, user, standard, count, alternatives)
    }
}
