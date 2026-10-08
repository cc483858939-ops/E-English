package com.eenglish.listening

import com.eenglish.listening.domain.grading.Grader
import org.junit.Assert.*
import org.junit.Test

class GraderTest {
    private val questions = fixturePart().questions
    private val correct = questions.associate { it.id to it.correctAnswer }
    @Test fun allCorrectIsTenOutOfTen() {
        val grade = Grader.grade(questions, correct)
        assertEquals(10, grade.correctCount); assertEquals(0, grade.wrongCount); assertEquals(100, grade.accuracy)
    }
    @Test fun twoWrongIsEightOutOfTen() {
        val answers = correct + questions.take(2).associate { it.id to "B" }
        val grade = Grader.grade(questions, answers)
        assertEquals(8, grade.correctCount); assertEquals(2, grade.wrongCount); assertEquals(80, grade.accuracy)
    }
    @Test fun unansweredIsWrong() {
        assertEquals(9, Grader.grade(questions, correct - questions.first().id).correctCount)
        assertEquals(0, Grader.grade(questions, emptyMap()).correctCount)
    }
    @Test fun orderDoesNotAffectGrade() { assertEquals(10, Grader.grade(questions.reversed(), correct).correctCount) }
    @Test fun foreignQuestionRejected() {
        assertThrows(IllegalArgumentException::class.java) { Grader.grade(questions, correct + ("unknown" to "A")) }
    }
}
