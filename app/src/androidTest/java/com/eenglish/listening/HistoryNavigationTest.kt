package com.eenglish.listening

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.viewmodel.PracticeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class HistoryNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TestListeningApplication
    private val vm get() = ViewModelProvider(compose.activity)[PracticeViewModel::class.java]
    private val fillId = "cambridge-5-test-1-part-1"
    private val otherId = "cambridge-9-test-1-part-3"

    @Before fun prepareIsolatedRecordsAndLibrary() {
        compose.waitUntil(15000) { !vm.uiState.value.loading }
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        compose.waitUntil(10000) { vm.uiState.value.history.isEmpty() }
        if (vm.uiState.value.parts.none { it.id == fillId }) {
            val staged = File.createTempFile("history-c5-", ".eelpack", app.cacheDir)
            try {
                val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "cat /data/local/tmp/eenglish-review/cambridge-5-v4-test.eelpack")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { input -> staged.outputStream().use { input.copyTo(it) } }
                assertTrue("Stage the C5 test pack in /data/local/tmp/eenglish-review before running this test", staged.length() > 0)
                compose.runOnIdle { vm.importPack(Uri.fromFile(staged)) }
                compose.waitUntil(20000) { vm.uiState.value.batchImport?.isRunning == false && !vm.uiState.value.saving }
                compose.runOnIdle { vm.dismissBatchImport() }
            } finally { staged.delete() }
        }
        assertTrue(vm.uiState.value.parts.any { it.id == fillId })
    }

    private fun records() {
        compose.onNodeWithTag("tab-me").performClick()
        capture("me")
        compose.onNodeWithTag("me-history").performClick()
        compose.onNodeWithTag("records-list").assertIsDisplayed()
        compose.onNodeWithTag("main-tabs").assertDoesNotExist()
    }

    @Test fun tabsPreserveLibraryLevelAndEmptyRecordsReturnThroughMe() {
        compose.onNodeWithTag("book-9").performScrollTo().performClick()
        compose.onNodeWithTag("test-9-1").performScrollTo().performClick()
        records()
        compose.onNodeWithTag("records-empty").assertIsDisplayed()
        compose.onNodeWithTag("records-back").performClick()
        compose.onNodeWithTag("tab-me").assertIsSelected()
        compose.onNodeWithTag("tab-practice").performClick()
        compose.onNodeWithTag("library-level").assertTextEquals("Cambridge IELTS 9 · Test 1")
        compose.onNodeWithTag("open-$otherId").assertIsDisplayed()
        compose.onNodeWithTag("tab-me").performClick()
        pressBack()
        compose.onNodeWithTag("tab-practice").assertIsSelected()
        compose.onNodeWithTag("library-level").assertTextEquals("Cambridge IELTS 9 · Test 1")
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun olderSubmittedRecordLoadsItsExactSnapshotAcrossPartsWithoutCreatingAttempt() {
        lateinit var old: PracticeAttempt
        lateinit var newer: String
        lateinit var latest: String
        runBlocking(Dispatchers.IO) {
            val part = app.parts.loadPart(fillId)
            // A historical prompt is intentionally different from the currently installed asset.
            val frozen = part.copy(questions = part.questions.mapIndexed { index, q ->
                if (index == 0) q.copy(prompt = "Historical question snapshot") else q
            })
            val id = app.practices.startNew(frozen)
            app.practices.saveAnswer(id, frozen.questions.first().id, "historical answer")
            app.practices.submit(id)
            old = app.practices.all().single { it.session.id == id }
            newer = app.practices.startNew(part)
            app.practices.submit(newer)
            latest = app.practices.startNew(app.parts.loadPart(otherId))
        }
        compose.waitUntil(10000) { vm.uiState.value.history.size == 3 }
        compose.runOnIdle { vm.openPart(otherId) }
        compose.waitUntil(15000) { !vm.uiState.value.loading && !vm.uiState.value.saving && vm.uiState.value.part?.id == otherId }
        val before = runBlocking(Dispatchers.IO) { app.practices.all() }
        records()
        val top = compose.onNodeWithTag("record-$latest").fetchSemanticsNode().boundsInRoot.top
        val middle = compose.onNodeWithTag("record-$newer").fetchSemanticsNode().boundsInRoot.top
        val bottom = compose.onNodeWithTag("record-${old.session.id}").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Newest records must appear first", top < middle && middle < bottom)
        capture("records")
        compose.onNodeWithTag("record-${old.session.id}").performClick()
        compose.waitUntil(15000) { !vm.uiState.value.saving && vm.uiState.value.attempt?.session?.id == old.session.id }
        compose.onNodeWithTag("grade-summary").assertIsDisplayed()
        compose.onNodeWithTag("main-tabs").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(fillId, vm.uiState.value.part?.id)
            assertEquals(old.questions, vm.uiState.value.questions)
            assertEquals(old.selectedAnswers, vm.uiState.value.answers)
            assertFalse(vm.audio.state.value.isPlaying)
        }
        assertEquals(before, runBlocking(Dispatchers.IO) { app.practices.all() })
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.waitUntil(15000) { !vm.uiState.value.loading }
        assertEquals(old.session.id, vm.uiState.value.attempt?.session?.id)
        compose.onNodeWithTag("grade-summary").assertIsDisplayed()
        pressBack()
        // The existing review card consumes the first Back to collapse itself.
        compose.onNodeWithTag("review-scroll").assertDoesNotExist()
        pressBack()
        compose.waitForIdle()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("records-list").fetchSemanticsNodes()
            .any { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 } }
        capture("restored-back")
        compose.onNodeWithTag("records-list").assertIsDisplayed()
        compose.onNodeWithTag("record-status-${old.session.id}", useUnmergedTree = true)
            .assertTextEquals("答对 ${old.session.correctCount}/${old.session.totalCount} 题")
    }

    @Test fun draftRecordResumesAndFastBackFlushesAnswerBeforeReturningToRecords() {
        val id = runBlocking(Dispatchers.IO) {
            val part = app.parts.loadPart(fillId)
            app.practices.startNew(part).also { app.practices.saveAnswer(it, part.questions.single { q -> q.number == 7 }.id, "draft") }
        }
        compose.waitUntil(10000) { vm.uiState.value.history.any { it.session.id == id } }
        compose.runOnIdle { vm.openPart(otherId) }
        compose.waitUntil(15000) { !vm.uiState.value.loading && !vm.uiState.value.saving && vm.uiState.value.part?.id == otherId }
        val count = vm.uiState.value.history.size
        records()
        compose.onNodeWithTag("record-$id").performScrollTo().performClick()
        compose.waitUntil(15000) { !vm.uiState.value.saving && vm.uiState.value.attempt?.session?.id == id }
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("v4-input-7-1"))
        compose.onNodeWithTag("v4-input-7-1").assertTextContains("draft")
        val question = vm.uiState.value.questions.single { it.number == 7 }
        compose.runOnIdle { vm.editInlineAnswer(question.id, "saved on back") }
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("records-list").assertIsDisplayed()
        compose.waitUntil(10000) { !vm.uiState.value.saving && vm.uiState.value.history.single { it.session.id == id }
            .selectedAnswers[question.id] == "saved on back" }
        assertEquals(count, vm.uiState.value.history.size)
        compose.onNodeWithTag("record-$id").performClick()
        compose.waitUntil(15000) { !vm.uiState.value.saving && vm.uiState.value.attempt?.session?.id == id }
        assertEquals("saved on back", vm.uiState.value.answers[question.id])
        assertEquals(SessionStatus.IN_PROGRESS, vm.uiState.value.attempt?.session?.status)
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("history-$id"))
        compose.onNodeWithTag("history-$id").assertIsDisplayed() // Original per-Part shortcut remains.
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("records-list").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("me-history").assertIsDisplayed()
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureHistory") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = app.contentResolver
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "eenglish-history-$name.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/eenglish-history")
            }))
        requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
