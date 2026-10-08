package com.eenglish.listening

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.data.local.PracticeDatabase
import com.eenglish.listening.data.repository.*
import com.eenglish.listening.domain.annotation.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*

class AnnotationRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "annotation-repository-test.db"
    private lateinit var db: PracticeDatabase
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        PracticeDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private val document = AnnotationDocument.transcript(annotationFixture().transcript, "en")
    @Before fun open() { context.deleteDatabase(name); db = Room.databaseBuilder(context, PracticeDatabase::class.java, name).build() }
    @After fun close() { db.close(); context.deleteDatabase(name); context.deleteDatabase("annotation-migration-test.db") }

    @Test fun rangesPersistAndPartialCancellationSplitsWithoutDuplicateRecords() = runBlocking {
        var repository = AnnotationRepository(db)
        val changes = document.selection("annotation-fixture", 4, 21)
        coroutineScope { (1..8).map { async(Dispatchers.IO) { repository.change(changes, true) } }.awaitAll() }
        assertEquals(1, repository.all().size)
        repository.change(document.selection("annotation-fixture", 10, 15), false)
        assertEquals(listOf(4 to 10, 15 to 21), repository.all().map { it.range.start to it.range.end })
        val saved = repository.all()
        db.close()
        db = Room.databaseBuilder(context, PracticeDatabase::class.java, name).build()
        repository = AnnotationRepository(db)
        assertEquals(saved, repository.all())
    }
    @Test fun crossParagraphChangesAreAtomicAndInvalidRangesRollback() = runBlocking {
        val repository = AnnotationRepository(db)
        val selections = document.selection("annotation-fixture", 15, document.text.length - 1)
        assertEquals(2, selections.size)
        repository.change(selections, true)
        assertEquals(2, repository.all().size)
        val before = repository.all()
        try {
            repository.change(listOf(selections.first().copy(start = 0, end = 3), selections.last().copy(end = Int.MAX_VALUE)), true)
            fail("Invalid ranges must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, repository.all())
    }
    @Test fun annotationsSurviveNewPracticeAndHistoryAndRemainPartScoped() = runBlocking {
        val practices = PracticeRepository(db)
        val part = annotationFixture()
        val id = practices.resumeOrCreate(part)
        practices.saveAnswer(id, part.questions.first().id, "B")
        val submitted = practices.submit(id)
        val repository = AnnotationRepository(db)
        repository.change(document.selection(part.id, 15, 21), true)
        val before = repository.all()
        val next = practices.startNew(part)
        assertNotEquals(id, next)
        assertEquals(before, repository.all())
        assertEquals(submitted, practices.all().last().session)
        assertEquals(mapOf(part.questions.first().id to "B"), practices.all().last().selectedAnswers)
        assertTrue(document.visibleRanges("different-part", before).isEmpty())
    }
    @Test fun migrationPreservesCompletedAndDraftSessionsAnswersAndFrozenQuestions() = runBlocking {
        val file = "annotation-migration-test.db"
        val frozen = Json.encodeToString(annotationFixture().questions)
        migration.createDatabase(file, 1).apply {
            for ((id, status) in listOf("completed" to "SUBMITTED", "draft" to "IN_PROGRESS")) {
                execSQL("INSERT INTO sessions(id,partId,status,startedAt,submittedAt,correctCount,totalCount,questionsJson) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(id, "annotation-fixture", status, if (id == "draft") 200L else 100L,
                        if (id == "completed") 150L else null, if (id == "completed") 1 else null, 10, frozen))
                execSQL("INSERT INTO answers(sessionId,questionId,selectedAnswer) VALUES(?,?,?)", arrayOf(id, "annotation-q21", "A"))
            }
            close()
        }
        migration.runMigrationsAndValidate(file, 2, true, PracticeDatabase.MIGRATION_1_2).close()
        val migrated = Room.databaseBuilder(context, PracticeDatabase::class.java, file)
            .addMigrations(PracticeDatabase.MIGRATION_1_2).build()
        try {
            val attempts = PracticeRepository(migrated).all()
            assertEquals(listOf("draft", "completed"), attempts.map { it.session.id })
            assertEquals(1, attempts.last().session.correctCount)
            assertEquals(150L, attempts.last().session.submittedAt)
            assertNull(attempts.first().session.submittedAt)
            attempts.forEach { assertEquals(mapOf("annotation-q21" to "A"), it.selectedAnswers); assertEquals(annotationFixture().questions, it.questions) }
            val annotations = AnnotationRepository(migrated)
            assertTrue(annotations.all().isEmpty())
            annotations.change(document.selection("annotation-fixture", 4, 10), true)
            assertEquals(1, annotations.all().size)
        } finally { migrated.close() }
    }
}
