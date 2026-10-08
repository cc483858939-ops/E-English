package com.eenglish.listening

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.data.local.PracticeDatabase
import com.eenglish.listening.data.repository.PracticeRepository
import com.eenglish.listening.domain.model.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PracticeRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "repository-test.db"
    private lateinit var db: PracticeDatabase
    private lateinit var repository: PracticeRepository
    private val part = ListeningPart(1, "test-part", "IELTS", 9, 1, 3, "Test fixture", "Choose one",
        "listening/test-part/audio.mp3", "0".repeat(64),
        Transcript("Fixture", "测试", listOf(TranscriptSegment("Fixture", "测试"))),
        (21..30).map { Question("q$it", it, QuestionType.SINGLE_CHOICE, "Synthetic repository fixture",
            listOf(Option("A", "First"), Option("B", "Second")), "A") })

    private fun open() {
        db = Room.databaseBuilder(context, PracticeDatabase::class.java, name).build()
        repository = PracticeRepository(db)
    }
    @Before fun setUp() { context.deleteDatabase(name); open() }
    @After fun tearDown() { db.close(); context.deleteDatabase(name) }

    @Test fun committedAnswersSurviveClosingAndReopeningDatabase() = runBlocking {
        val id = repository.resumeOrCreate(part)
        repository.saveAnswer(id, "q21", "A")
        repository.saveAnswer(id, "q21", "B")
        db.close(); open()
        assertEquals(id, repository.resumeOrCreate(part))
        val restored = repository.all().single()
        assertEquals(mapOf("q21" to "B"), restored.selectedAnswers)
        assertEquals(1, restored.answers.size)
    }
    @Test fun concurrentSubmissionIsIdempotentAndImmutable() = runBlocking {
        val id = repository.resumeOrCreate(part)
        part.questions.forEach { repository.saveAnswer(id, it.id, "A") }
        val submissions = coroutineScope { (1..8).map { async(Dispatchers.IO) { repository.submit(id) } }.awaitAll() }
        assertEquals(1, submissions.toSet().size)
        assertEquals(10, submissions.first().correctCount)
        assertEquals(1, repository.all().size)
        try { repository.saveAnswer(id, "q21", "B"); fail("Submitted answers must be immutable") }
        catch (_: IllegalStateException) { }
        assertEquals(10, repository.submit(id).correctCount)
    }
    @Test fun newPracticePreservesPreviousScoreAndQuestionSnapshot() = runBlocking {
        val first = repository.resumeOrCreate(part)
        part.questions.take(8).forEach { repository.saveAnswer(first, it.id, "A") }
        assertEquals(8, repository.submit(first).correctCount)
        val changed = part.copy(questions = part.questions.map { it.copy(correctAnswer = "B") })
        val next = repository.startNew(changed)
        assertNotEquals(first, next)
        assertEquals(next, repository.startNew(changed))
        val attempts = repository.all()
        assertEquals(2, attempts.size)
        assertTrue(attempts.first().answers.isEmpty())
        assertEquals(8, attempts.last().session.correctCount)
        assertTrue(attempts.last().questions.all { it.correctAnswer == "A" })
        assertTrue(attempts.first().questions.all { it.correctAnswer == "B" })
    }
    @Test fun invalidSelectionsAreRejectedAndMissingAnswersAreWrong() = runBlocking {
        val id = repository.resumeOrCreate(part)
        for ((question, option) in listOf("q21" to "Z", "unknown" to "A")) {
            try { repository.saveAnswer(id, question, option); fail("Invalid answer accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, repository.submit(id).correctCount)
    }
    @Test fun concurrentResumeCreatesOnlyOneDraft() = runBlocking {
        val ids = coroutineScope { (1..8).map { async(Dispatchers.IO) { repository.resumeOrCreate(part) } }.awaitAll() }
        assertEquals(1, ids.toSet().size)
        assertEquals(1, repository.all().size)
    }
}
