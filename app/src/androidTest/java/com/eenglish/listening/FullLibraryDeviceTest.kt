package com.eenglish.listening

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.audio.ListeningAudioController
import com.eenglish.listening.data.repository.PartRepository
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.QuestionType
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** PRIVATE packs are pushed separately; APK/test APK do not contain the full library. */
class FullLibraryDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun allBooksValidateAndAllLocalAudioFilesPlayBeforeOptionalInstall() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val install = arguments.getString("listening.installFullLibrary") == "true"
        val packs = File(context.filesDir, "full-library-packs")
        val names = packs.listFiles().orEmpty().filter { it.name.endsWith(".eelpack.b64") }
        if (install || arguments.containsKey("listening.expectedParts")) {
            assertTrue("Explicit full-library run requires readable private packs", names.isNotEmpty())
        } else assumeTrue("Full private book packs must be pushed separately", names.isNotEmpty())
        assertEquals((5..21).map { "cambridge-$it.eelpack.b64" }.toSet(), names.map { it.name }.toSet())
        assertEquals(17, names.size)
        val expectedParts = requireNotNull(arguments.getString("listening.expectedParts")).toInt()
        require(expectedParts in 254..272)
        val databaseHashes = primaryDatabaseHashes(context.getDatabasePath("practice.db").parentFile!!)
        val root = File(context.filesDir, "full-library-check-${UUID.randomUUID()}")
        val repository = PartRepository(context.assets, root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var audio: ListeningAudioController
        instrumentation.runOnMainSync { audio = ListeningAudioController(context, scope) }
        try {
            names.forEach { pack -> android.util.Base64InputStream(pack.inputStream(), android.util.Base64.DEFAULT)
                .use { repository.importPack(it) } }
            val summaries = repository.loadParts()
            assertEquals((5..21).toSet(), summaries.map { it.book }.toSet())
            assertEquals(68, summaries.map { it.book to it.test }.toSet().size)
            assertEquals(expectedParts, summaries.size)
            var questions = 0
            summaries.forEach { summary ->
                val part = repository.loadPart(summary.id)
                questions += part.questions.size
                val selected = part.questions.associate { q -> q.id to if (q.type == QuestionType.MULTIPLE_CHOICE)
                    part.questions.filter { it.groupId == q.groupId }.map { it.correctAnswer }.sorted().joinToString(",") else q.correctAnswer }
                assertEquals("Canonical grading failed for ${part.id}", 10, Grader.grade(part.questions, selected).correctCount)
                part.questions.flatMap { it.images }.distinct().forEach { assertNotNull(repository.readImage(part.id, it)) }
                instrumentation.runOnMainSync { audio.pause(); audio.load(repository.audioPath(part)) }
                withTimeout(15000) { while (!audio.state.value.ready) { check(audio.state.value.error == null); delay(50) } }
                assertTrue("Duration missing for ${part.id}", audio.state.value.durationMs > 1000)
                instrumentation.runOnMainSync { audio.toggle() }
                try {
                    withTimeout(10000) { while (!audio.state.value.isPlaying || audio.state.value.positionMs < 300) delay(50) }
                } catch (error: TimeoutCancellationException) {
                    throw AssertionError("Playback failed for ${part.id}", error)
                }
                instrumentation.runOnMainSync { audio.pause(); audio.seekTo(1000) }
                withTimeout(5000) { while (audio.state.value.positionMs !in 900..1100) delay(50) }
                assertFalse(audio.state.value.isPlaying)
                // Only metadata is logged; exam text and answers remain private.
                android.util.Log.i("FullLibraryCheck", "Verified ${part.id}")
            }
            assertEquals(expectedParts * 10, questions)
            assertEquals(summaries, PartRepository(context.assets, root).loadParts())
            if (install) {
                // Explicit developer-tool argument only. Never touch practice.db or annotations.
                val installed = PartRepository(context.assets, File(context.filesDir, "library"))
                names.forEach { pack -> android.util.Base64InputStream(pack.inputStream(), android.util.Base64.DEFAULT)
                    .use { installed.importPack(it) } }
                assertEquals(summaries, installed.loadParts())
                assertEquals(summaries, PartRepository(context.assets, File(context.filesDir, "library")).loadParts())
            }
            assertEquals(databaseHashes, primaryDatabaseHashes(context.getDatabasePath("practice.db").parentFile!!))
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("stream", "FULL_LIBRARY_VERIFIED $expectedParts Parts\n")
            })
        } finally {
            instrumentation.runOnMainSync { audio.release() }
            scope.cancel()
            // root is a UUID directory created only by this test, inside filesDir.
            if (root.exists()) root.deleteRecursively()
        }
    }

    private fun primaryDatabaseHashes(directory: File): Map<String, String> = directory.listFiles().orEmpty()
        .filter { it.name == "practice.db" || it.name.startsWith("practice.db-") }
        .associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) } }
}
