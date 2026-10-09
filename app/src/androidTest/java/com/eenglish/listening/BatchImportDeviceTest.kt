package com.eenglish.listening

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.eenglish.listening.navigation.OfflinePackDocuments
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.data.local.PracticeDatabase
import com.eenglish.listening.data.repository.*
import com.eenglish.listening.domain.annotation.AnnotationDocument
import java.io.File
import java.io.FilterInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Synthetic documents only. No production library or practice database is modified. */
class BatchImportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val documents = mutableListOf<Uri>()
    private lateinit var root: File
    private lateinit var repository: PartRepository
    private val states = mutableListOf<BatchImportUiState>()
    private val refreshedSnapshots = mutableListOf<List<com.eenglish.listening.domain.model.PartSummary>>()
    private var openStreams = 0
    private var peakStreams = 0

    @Before fun setUp() {
        root = File(context.cacheDir, "batch-test-${UUID.randomUUID()}").apply { mkdirs() }
        repository = PartRepository(context.assets, root)
    }
    @After fun tearDown() {
        documents.forEach { resolver.delete(it, null, null) }
        root.deleteRecursively()
    }
    private fun document(name: String, bytes: ByteArray, mime: String = "application/octet-stream"): Uri {
        check(android.os.Build.VERSION.SDK_INT >= 29) { "Downloads fixture requires API 29+" }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/EEnglishBatchTests-${root.name}")
        }))
        documents += uri
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        return uri
    }
    private suspend fun run(uris: List<Uri>): BatchImportUiState {
        return BatchPackImporter(repository::loadParts, repository::importPack).run(uris.map { uri ->
            PackImportSource({
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use {
                    assertTrue(it.moveToFirst()); it.getString(0)
                }
            }, {
                openStreams++; peakStreams = maxOf(openStreams, peakStreams)
                object : FilterInputStream(requireNotNull(resolver.openInputStream(uri))) {
                    private var closed = false
                    override fun close() { if (!closed) { closed = true; openStreams-- }; super.close() }
                }
            })
        }, states::add, { parts ->
            assertTrue(parts.isNotEmpty())
            refreshedSnapshots += parts
        })
    }

    @Test fun oneAndThreeDocumentsImportSeriallyAndDuplicatesDoNotAddParts() = runBlocking {
        val uris = listOf(14,15,16).map { document("synthetic-$it.eelpack", libraryPack(book=it),
            if (it == 15) "application/zip" else "application/octet-stream") }
        val single = run(uris.take(1))
        assertEquals(1,single.succeeded); assertEquals(1,single.addedParts)
        val three = run(uris)
        assertEquals(2,three.succeeded); assertEquals(1,three.alreadyInstalled)
        assertEquals(2,three.addedParts); assertEquals(1,three.existingParts)
        val before = repository.loadParts()
        assertEquals(before,refreshedSnapshots.last())
        val repeat = run(uris)
        assertEquals(0,repeat.succeeded); assertEquals(3,repeat.alreadyInstalled)
        assertEquals(0,repeat.addedParts); assertEquals(3,repeat.existingParts)
        assertEquals(before,PartRepository(context.assets,root).loadParts())
        assertEquals(1,peakStreams); assertEquals(0,openStreams)
        val providerName = resolver.query(uris[1],arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)!!.use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        assertTrue(states.any { it.processed == 1 && it.currentFile == providerName && it.isRunning })
        assertTrue(states.any { it.stage == ImportStage.VALIDATING && it.currentBook == 16 })
        assertTrue(states.any { it.stage == ImportStage.SAVING })
    }

    @Test fun damagedAndNonPackFilesDoNotStopQueueAndConflictPreservesPublishedBook() = runBlocking {
        val result = run(listOf(
            document("first.eelpack",libraryPack(book=14)),
            document("damaged.eelpack",libraryPack(book=15,corrupt=true)),
            document("ordinary.txt","Synthetic plain text".toByteArray(),"text/plain"),
            document("last.eelpack",libraryPack(book=16)),
        ))
        assertEquals(2,result.succeeded); assertEquals(2,result.failed); assertEquals(2,result.addedParts)
        assertEquals(ImportFailure.DAMAGED,result.results[1].failure)
        assertEquals(ImportFailure.INVALID_PACK,result.results[2].failure)
        val before = repository.loadParts()
        val conflict = run(listOf(document("changed.eelpack",libraryPack(book=14,changed=true))))
        assertEquals(ImportFailure.CONFLICT,conflict.results.single().failure)
        assertEquals(before,PartRepository(context.assets,root).loadParts())
        assertEquals(before.first { it.book == 14 }.title,repository.loadPart("cambridge-14-test-1-part-1").title)
        assertEquals(0,openStreams)
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("import-") })
    }

    @Test fun batchImportPreservesExistingRoomAnswersScoresAndHighlightsAfterReopen() = runBlocking {
        val name = "batch-records-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context,PracticeDatabase::class.java,name).build()
        try {
            val practice = PracticeRepository(db)
            val part = libraryFixture()
            val session = practice.resumeOrCreate(part)
            practice.saveAnswer(session,part.questions[2].id,"alpha")
            practice.submit(session)
            AnnotationRepository(db).change(AnnotationDocument.transcript(part.transcript,"en").selection(part.id,0,9),true)
            val before = practice.all()
            val highlights = AnnotationRepository(db).all()
            val uris = listOf(14,15,16).map { document("records-$it.eelpack",libraryPack(book=it)) }
            assertEquals(3,run(uris).succeeded)
            assertEquals(3,run(uris).alreadyInstalled)
            db.close(); db = Room.databaseBuilder(context,PracticeDatabase::class.java,name).build()
            assertEquals(before,PracticeRepository(db).all())
            assertEquals(highlights,AnnotationRepository(db).all())
        } finally { db.close();context.deleteDatabase(name) }
    }

    @Test fun pickerUsesOpenDocumentAndAllowsMultipleCustomMimeDocuments() {
        val intent = OfflinePackDocuments().createIntent(context,arrayOf("*/*"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action)
        assertEquals("*/*",intent.type)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false))
        assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE))
        assertNotNull(intent.resolveActivity(context.packageManager))
    }
}
