package com.eenglish.listening.domain.layout

import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType
import kotlinx.serialization.json.JsonObject

data class AnswerSheetEntry(val questions: List<Question>, val answeredCount: Int) {
    val number: Int get() = questions.first().number
    val label: String get() = questions.joinToString("、") { it.number.toString() }
    val complete: Boolean get() = answeredCount == questions.size
}

/** Completion only: never infer correctness or assign a selected option to an individual multi-choice slot. */
fun answerSheetEntries(questions: List<Question>, layout: JsonObject?, answers: Map<String, String>): List<AnswerSheetEntry> {
    val seen = mutableSetOf<String>()
    return questions.sortedBy { it.number }.mapNotNull { question ->
        if (!seen.add(question.id)) return@mapNotNull null
        val group = if (question.type == QuestionType.MULTIPLE_CHOICE) questions.filter {
            it.type == QuestionType.MULTIPLE_CHOICE && it.groupId == question.groupId
        }.sortedBy { it.number } else listOf(question)
        seen.addAll(group.map { it.id })
        val count = if (question.type == QuestionType.MULTIPLE_CHOICE) {
            val selections = group.map { Grader.selection(answers[it.id]) }
            // Stored multi-choice answers are atomic across the group. Inconsistent data is incomplete.
            if (selections.distinct().size == 1) selections.first().size.coerceAtMost(group.size) else 0
        } else if (LayoutV4AnswerStatus.missingCount(group, layout, answers) == 0) 1 else 0
        AnswerSheetEntry(group, count)
    }
}
