package com.eenglish.listening.domain.model

enum class SessionStatus { IN_PROGRESS, SUBMITTED }

data class PracticeSession(
    val id: String,
    val partId: String,
    val status: SessionStatus,
    val startedAt: Long,
    val submittedAt: Long?,
    val correctCount: Int?,
    val totalCount: Int,
)

data class PracticeAnswer(val sessionId: String, val questionId: String, val selectedAnswer: String)

/** Questions are frozen at attempt creation so future asset updates cannot change history. */
data class PracticeAttempt(val session: PracticeSession, val questions: List<Question>, val answers: List<PracticeAnswer>) {
    val selectedAnswers: Map<String, String> get() = answers.associate { it.questionId to it.selectedAnswer }
}
