package com.eenglish.listening

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.data.local.PracticeDatabase
import com.eenglish.listening.data.repository.*
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.domain.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*

fun libraryFixture(book: Int = 14): ListeningPart {
    val id = "cambridge-$book-test-1-part-1"
    return ListeningPart(2,id,"IELTS",book,1,1,"Synthetic offline library", "Synthetic instructions",
        "listening/$id/audio.mp3",libraryHash(libraryAudio()),Transcript("Synthetic English", "", listOf(TranscriptSegment("Synthetic English", ""))),
        (1..10).map { n ->
            if (n <= 2) Question("$id-q$n",n,QuestionType.MULTIPLE_CHOICE,"Synthetic group",
                listOf(Option("A","First"),Option("B","Second"),Option("C","Third")), if (n==1) "A" else "C",
                groupId="group-1",groupNumbers=listOf(1,2))
            else Question("$id-q$n",n,QuestionType.TEXT_INPUT,"Synthetic input",emptyList(),"alpha",wordLimit=WordLimit(1))
        })
}
private fun libraryAudio() = "ID3".toByteArray() + ByteArray(300)
private fun libraryHash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

internal fun libraryPack(corrupt: Boolean = false, unsafe: Boolean = false, book: Int = 14, changed: Boolean = false): ByteArray {
    val part = libraryFixture(book).let { if (changed) it.copy(title = "Synthetic changed title") else it }
    val data = Json.encodeToString(part).toByteArray()
    val audio = libraryAudio()
    val index = BookIndex(1,book,listOf(PartSummary(part.id,book,1,1,part.title,10,1,10,audio.size.toLong(),libraryHash(data),libraryHash(audio))))
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        mapOf("index.json" to Json.encodeToString(index).toByteArray(), "listening/${part.id}/part.json" to data,
            "listening/${part.id}/audio.mp3" to if (corrupt) byteArrayOf(1) else audio).forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
        }
        if (unsafe) { zip.putNextEntry(ZipEntry("../escape"));zip.write(1);zip.closeEntry() }
    }
    return output.toByteArray()
}

class OfflineLibraryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var importer: OfflinePackImporter
    @Before fun setUp() {
        directory = File(context.cacheDir,"library-test-${UUID.randomUUID()}").apply { mkdirs() }
        importer = OfflinePackImporter(directory)
    }
    @After fun tearDown() { directory.deleteRecursively();context.deleteDatabase("library-isolated-test.db") }

    @Test fun importIsPersistentAndIdempotentAndCorruptionCannotReplaceBook() = runBlocking {
        val index = importer.install(ByteArrayInputStream(libraryPack()))
        assertEquals(10,index.parts.single().questionCount)
        val before = importer.installed()
        assertEquals(before,OfflinePackImporter(directory).installed())
        importer.install(ByteArrayInputStream(libraryPack()))
        assertEquals(before,importer.installed())
        for (pack in listOf(libraryPack(corrupt=true),libraryPack(unsafe=true))) {
            try { importer.install(ByteArrayInputStream(pack));fail("Invalid pack accepted") }
            catch (_: IllegalArgumentException) { }
            catch (_: java.util.zip.ZipException) { /* API 34+ also rejects ZIP-slip before extraction. */ }
            assertEquals(before,importer.installed())
        }
        assertFalse(File(directory.parentFile,"escape").exists())
    }

    @Test fun textAndMultiAnswersSurviveRestartAndImportPreservesScoresAndHighlights() = runBlocking {
        val name="library-isolated-test.db"
        context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,PracticeDatabase::class.java,name).build()
        try {
            var repository=PracticeRepository(db)
            val part=libraryFixture()
            val id=repository.resumeOrCreate(part)
            repository.saveAnswer(id,part.questions[0].id,"C,A")
            repository.saveAnswer(id,part.questions[2].id," Alpha ")
            val changes=AnnotationDocument.transcript(part.transcript,"en").selection(part.id,0,9)
            AnnotationRepository(db).change(changes,true)
            val highlights=AnnotationRepository(db).all()
            db.close();db=Room.databaseBuilder(context,PracticeDatabase::class.java,name).build();repository=PracticeRepository(db)
            assertEquals("A,C",repository.all().single().selectedAnswers[part.questions[1].id])
            assertEquals(3,repository.submit(id).correctCount)
            val before=repository.all()
            importer.install(ByteArrayInputStream(libraryPack()))
            assertEquals(before,repository.all())
            assertEquals(highlights,AnnotationRepository(db).all())
            val next=repository.startNew(part)
            assertNotEquals(id,next)
            assertEquals(3,repository.all().last().session.correctCount)
            assertEquals(highlights,AnnotationRepository(db).all())
            try { repository.saveAnswer(id,part.questions[2].id,"beta");fail("Submitted attempt changed") }
            catch (_: IllegalStateException) { }
        } finally { db.close() }
    }
}
