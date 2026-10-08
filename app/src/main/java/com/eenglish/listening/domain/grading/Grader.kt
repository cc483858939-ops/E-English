package com.eenglish.listening.domain.grading

import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType
import kotlin.math.roundToInt

data class Grade(val correctCount: Int, val totalCount: Int) {
    val wrongCount: Int get() = totalCount - correctCount
    val accuracy: Int get() = (correctCount * 100.0 / totalCount).roundToInt()
}

object Grader {
    fun grade(questions: List<Question>, selected: Map<String, String>): Grade {
        require(questions.isNotEmpty() && questions.map { it.id }.distinct().size == questions.size)
        require(questions.all { it.type == QuestionType.SINGLE_CHOICE })
        require(selected.keys.all { id -> questions.any { it.id == id } })
        return Grade(questions.count { selected[it.id] == it.correctAnswer }, questions.size)
    }
}
