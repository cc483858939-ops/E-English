package com.eenglish.listening

import com.eenglish.listening.domain.grading.*
import com.eenglish.listening.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class QuestionReviewTest {
    private fun text(id: String, number: Int, correct: String = "resting") = Question(id, number,
        QuestionType.TEXT_INPUT, "prompt", emptyList(), correct, wordLimit = WordLimit(2, 0))

    @Test fun correctWrongAndUnansweredUseTheSameResultsAsTheGrade() {
        val questions = listOf(text("q1", 1), text("q2", 2), text("q3", 3))
        val answers = mapOf("q1" to " RESTING ", "q2" to "rests")
        val results = questionReviews(questions, answers)
        assertEquals(listOf(ReviewStatus.CORRECT, ReviewStatus.INCORRECT, ReviewStatus.UNANSWERED), results.map { it.status })
        assertEquals(Grader.grade(questions, answers).correctCount, results.sumOf { it.correctCount })
        assertEquals("resting", results[1].standardAnswer)
        assertEquals("rests", results[1].userAnswer)
    }

    @Test fun acceptedVariantsAndWordLimitArePreserved() {
        val question = text("q", 1).copy(acceptedAnswers = listOf("relaxing", "resting"), wordLimit = WordLimit(1, 0))
        assertEquals(ReviewStatus.CORRECT, questionReviews(listOf(question), mapOf("q" to "Relaxing")).single().status)
        assertEquals("resting", questionReviews(listOf(question), emptyMap()).single().standardAnswer)
        assertEquals(listOf("relaxing"), questionReviews(listOf(question), emptyMap()).single().acceptedVariants)
        assertEquals(ReviewStatus.INCORRECT, questionReviews(listOf(question), mapOf("q" to "very relaxing")).single().status)
    }

    @Test fun pairedFillIsOneReviewAndPartialAnswerIsUnanswered() {
        val question = text("q", 7, "morning and evening").copy(answerSeparator = "and")
        for (value in listOf("morning", "morning and ")) {
            val result = questionReviews(listOf(question), mapOf("q" to value)).single()
            assertEquals(ReviewStatus.UNANSWERED, result.status)
            assertEquals(0, result.correctCount)
        }
        assertEquals(ReviewStatus.CORRECT, questionReviews(listOf(question), mapOf("q" to "morning and evening")).single().status)
    }

    @Test fun sharedMultipleChoiceIsOneReviewAndPreservesPartialCreditAndInconsistentHistory() {
        val options = listOf(Option("A", "first"), Option("B", "second"), Option("C", "third"))
        val group = listOf("A", "C").mapIndexed { index, correct -> Question("q$index", 11 + index,
            QuestionType.MULTIPLE_CHOICE, "choose two", options, correct, groupId = "g", groupNumbers = listOf(11, 12)) }
        for (answers in listOf(group.associate { it.id to "C,A" }, group.associate { it.id to "A" },
            mapOf("q0" to "A,C", "q1" to "A"), emptyMap())) {
            val result = questionReviews(group, answers).single()
            assertEquals(Grader.grade(group, answers).correctCount, result.correctCount)
            assertEquals(2, result.questionIds.size)
        }
        assertEquals(ReviewStatus.CORRECT, questionReviews(group, group.associate { it.id to "C,A" }).single().status)
        assertEquals(ReviewStatus.INCORRECT, questionReviews(group, group.associate { it.id to "A" }).single().status)
    }
}
