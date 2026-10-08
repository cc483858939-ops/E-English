package com.eenglish.listening.data.repository

import androidx.room.withTransaction
import com.eenglish.listening.data.local.*
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.*
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PracticeRepository(private val database: PracticeDatabase) {
    private val dao = database.practiceDao()
    private val json = Json

    fun observeAll() = dao.observeAll().map { attempts -> attempts.map { it.toDomain() } }
    suspend fun all(): List<PracticeAttempt> = dao.all().map { it.toDomain() }

    suspend fun resumeOrCreate(part: ListeningPart): String = database.withTransaction {
        dao.latest(part.id)?.id ?: create(part)
    }
    /** Repeated new-practice requests reuse the newly created draft. */
    suspend fun startNew(part: ListeningPart): String = database.withTransaction {
        val latest = dao.latest(part.id)
        if (latest?.status == SessionStatus.IN_PROGRESS.name) latest.id else create(part)
    }
    private suspend fun create(part: ListeningPart): String {
        part.validate()
        val id = UUID.randomUUID().toString()
        val started = maxOf(System.currentTimeMillis(), (dao.latest(part.id)?.startedAt ?: 0) + 1)
        dao.insert(SessionEntity(id, part.id, SessionStatus.IN_PROGRESS.name, started, null, null,
            part.questions.size, json.encodeToString(part.questions)))
        return id
    }
    suspend fun saveAnswer(sessionId: String, questionId: String, selected: String) = database.withTransaction {
        val session = requireNotNull(dao.session(sessionId)) { "Missing session" }
        check(session.status == SessionStatus.IN_PROGRESS.name) { "Submitted attempt is immutable" }
        val question = questions(session).singleOrNull { it.id == questionId }
        requireNotNull(question) { "Invalid answer question" }
        when {
            question.type == QuestionType.MULTIPLE_CHOICE -> {
                val group = questions(session).filter { it.groupId == question.groupId }
                val choices = Grader.selection(selected)
                require(choices.size <= group.size && choices.all { choice -> question.options.any { it.id == choice } }) { "Invalid group choice" }
                val canonical = choices.sorted().joinToString(",")
                group.forEach { dao.save(AnswerEntity(sessionId, it.id, canonical)) }
            }
            question.isTextInput -> {
                require(selected.length <= 512) { "Answer too long" }
                dao.save(AnswerEntity(sessionId, questionId, selected))
            }
            else -> {
                require(question.options.any { it.id == selected }) { "Invalid answer selection" }
                dao.save(AnswerEntity(sessionId, questionId, selected))
            }
        }
    }
    /** Score and status change atomically. Repeated or concurrent submits return the same record. */
    suspend fun submit(sessionId: String): PracticeSession = database.withTransaction {
        val session = requireNotNull(dao.session(sessionId)) { "Missing session" }
        if (session.status == SessionStatus.IN_PROGRESS.name) {
            val grade = Grader.grade(questions(session), dao.answers(sessionId).associate { it.questionId to it.selectedAnswer })
            check(grade.totalCount == session.totalCount)
            check(dao.submit(sessionId, System.currentTimeMillis(), grade.correctCount) == 1)
        }
        requireNotNull(dao.session(sessionId)).toDomain()
    }
    private fun questions(session: SessionEntity): List<Question> = json.decodeFromString(session.questionsJson)
    private fun SessionEntity.toDomain() = PracticeSession(id, partId, SessionStatus.valueOf(status), startedAt,
        submittedAt, correctCount, totalCount)
    private fun AttemptEntity.toDomain() = PracticeAttempt(session.toDomain(), questions(session),
        answers.map { PracticeAnswer(it.sessionId, it.questionId, it.selectedAnswer) })
}
