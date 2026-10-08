package com.eenglish.listening

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.eenglish.listening.audio.ListeningAudioController
import com.eenglish.listening.data.repository.PartRepository
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.QuestionType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule

/** Optional PRIVATE runtime fixtures; never add the packs to Git/CI artifacts. */
class RealLibraryDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun sixRealBooksImportOnDeviceAndAllTwentyFourLocalMp3sPlay() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testAssets = instrumentation.context.assets
        val names = testAssets.list("private-library").orEmpty().filter { it.endsWith(".eelpack") }
        assumeTrue("Private representative packs absent", names.isNotEmpty())
        assertEquals(6, names.size)
        val root = File(context.cacheDir,"real-library-${UUID.randomUUID()}")
        val repository = PartRepository(context.assets,root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var audio: ListeningAudioController
        instrumentation.runOnMainSync { audio = ListeningAudioController(context,scope) }
        try {
            names.forEach { path -> testAssets.open("private-library/$path").use { repository.importPack(it) } }
            val parts = repository.loadParts()
            assertEquals(setOf(5,9,13,17,18,21),parts.map { it.book }.toSet())
            assertEquals(24,parts.size)
            parts.forEach { summary ->
                val part = repository.loadPart(summary.id)
                val selected = part.questions.associate { q -> q.id to if (q.type == QuestionType.MULTIPLE_CHOICE)
                    part.questions.filter { it.groupId == q.groupId }.map { it.correctAnswer }.sorted().joinToString(",") else q.correctAnswer }
                assertEquals("Grading failed for ${part.id}",10,Grader.grade(part.questions,selected).correctCount)
                part.questions.flatMap { it.images }.distinct().forEach { assertNotNull(repository.readImage(part.id,it)) }
                instrumentation.runOnMainSync { audio.load(repository.audioPath(part)) }
                withTimeout(15000) { while (!audio.state.value.ready) { check(audio.state.value.error == null);delay(100) } }
                assertTrue(audio.state.value.durationMs > 1000)
                instrumentation.runOnMainSync { audio.toggle() }
                try {
                    withTimeout(10000) { while (!audio.state.value.isPlaying || audio.state.value.positionMs < 300) delay(100) }
                } catch (error: TimeoutCancellationException) {
                    throw AssertionError("Playback failed for ${part.id}; ${audio.state.value}", error)
                }
                instrumentation.runOnMainSync { audio.pause();audio.seekTo(1000) }
                withTimeout(5000) { while (audio.state.value.positionMs !in 900..1100) delay(100) }
                assertFalse(audio.state.value.isPlaying)
            }
            assertEquals(parts,PartRepository(context.assets,root).loadParts())
        } finally {
            instrumentation.runOnMainSync { audio.release() };scope.cancel();root.deleteRecursively()
        }
    }
}
