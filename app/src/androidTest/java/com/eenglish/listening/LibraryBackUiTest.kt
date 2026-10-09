package com.eenglish.listening

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.room.Room
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import com.eenglish.listening.data.local.PracticeDatabase
import com.eenglish.listening.data.repository.*
import com.eenglish.listening.domain.model.PartSummary
import com.eenglish.listening.ui.components.BatchImportDialog
import com.eenglish.listening.ui.screens.PartListScreen
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.PracticeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic text/indexes only; no user library or practice database is modified. */
class LibraryBackUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val parts = (5..21).flatMap { b -> (1..4).flatMap { t -> (1..4).map { p ->
        PartSummary("cambridge-$b-test-$t-part-$p",b,t,p,"Synthetic",10,(p-1)*10+1,p*10)
    } } }
    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(compose.activity.isFinishing)
    }
    private fun systemBack() {
        // Use actual system key injection, including the focused IME/dialog window;
        // Espresso's root matcher can pick the underlying Activity behind a dialog.
        compose.waitForIdle() // Publish the focused IME/dialog and current callbacks before injecting a key.
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
        compose.waitForIdle()
        assertFalse(compose.activity.isFinishing)
    }
    private fun waitForKeyboard(visible: Boolean) {
        compose.waitUntil(10000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
        }
        compose.waitForIdle()
    }
    private fun assertRow(tag: String) {
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val card=compose.onNodeWithTag("$tag-card").fetchSemanticsNode().boundsInRoot
        val button=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val title=compose.onNodeWithTag("$tag-title").fetchSemanticsNode().boundsInRoot
        val detail=compose.onNodeWithTag("$tag-detail").fetchSemanticsNode().boundsInRoot
        assertTrue(title.right <= button.left && detail.right <= button.left)
        assertTrue(title.bottom <= detail.top)
        assertTrue(title.top >= card.top && detail.bottom <= card.bottom)
        assertTrue(button.left >= card.left && button.right <= card.right && button.bottom <= card.bottom)
        assertTrue(card.height > 0 && card.width <= compose.activity.window.decorView.width)
    }
    @Test fun dispatcherToolbarSearchAndEachLayerRestoreScrollAndSavedState() {
        val restore=StateRestorationTester(compose)
        restore.setContent { ListeningTheme { PartListScreen(PracticeUiState(loading=false,parts=parts),onOpen={}) } }
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("book-17"))
        val bookBounds=compose.onNodeWithTag("book-17").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("book-17").performClick()
        compose.onNodeWithTag("test-17-1").performClick()
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("open-cambridge-17-test-1-part-4"))
        val partBounds=compose.onNodeWithTag("open-cambridge-17-test-1-part-4").fetchSemanticsNode().boundsInRoot
        restore.emulateSavedInstanceStateRestore()
        assertEquals(partBounds,compose.onNodeWithTag("open-cambridge-17-test-1-part-4").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("library-search").performTextInput("cambridge-9-test-1-part-3")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("open-cambridge-9-test-1-part-3").assertIsDisplayed()
        back()
        assertEquals(partBounds,compose.onNodeWithTag("open-cambridge-17-test-1-part-4").fetchSemanticsNode().boundsInRoot)
        back()
        compose.onNodeWithTag("test-17-1").assertIsDisplayed()
        compose.onNodeWithTag("library-back").performClick()
        assertEquals(bookBounds,compose.onNodeWithTag("book-17").fetchSemanticsNode().boundsInRoot)
        repeat(2) { back(); compose.onNodeWithTag("cancel-exit").assertIsDisplayed().performClick() }
    }
    @Test fun keyboardBackPrecedesSearchDismissalAndRunningImportCannotExit() {
        val state=mutableStateOf(PracticeUiState(loading=false,parts=parts))
        compose.setContent { ListeningTheme {
            PartListScreen(state.value,onOpen={})
            state.value.batchImport?.let { BatchImportDialog(it) { state.value=state.value.copy(batchImport=null) } }
        } }
        compose.onNodeWithTag("library-search").performTextInput("cambridge-14-test-1-part-1")
        waitForKeyboard(true) // IME visibility/animations are outside Compose's idle tracking.
        systemBack()
        waitForKeyboard(false)
        compose.onNodeWithTag("open-cambridge-14-test-1-part-1").assertExists()
        back(); compose.onNodeWithTag("book-5").assertIsDisplayed()
        compose.runOnIdle { state.value=state.value.copy(batchImport=BatchImportUiState(3)) }
        systemBack(); compose.onNodeWithTag("batch-import").assertIsDisplayed()
        back(); compose.onNodeWithTag("batch-import").assertIsDisplayed()
        compose.onNodeWithTag("confirm-exit").assertDoesNotExist()
        compose.runOnIdle { state.value=state.value.copy(batchImport=state.value.batchImport!!.copy(isRunning=false)) }
        systemBack(); compose.onNodeWithTag("batch-import").assertDoesNotExist()
        back(); compose.onNodeWithTag("cancel-exit").assertIsDisplayed()
    }
    @Test fun compactRowsFitCurrentDeviceAndActionsReflectRoomHistory() = runBlocking(Dispatchers.IO) {
        val db=Room.inMemoryDatabaseBuilder(compose.activity,PracticeDatabase::class.java).build()
        try {
            val repo=PracticeRepository(db); val part=libraryFixture(14)
            val session=repo.resumeOrCreate(part)
            repo.saveAnswer(session,part.questions[2].id,"alpha")
            val state=mutableStateOf(PracticeUiState(loading=false,parts=parts,history=repo.all()))
            var opened: String?=null
            compose.setContent { ListeningTheme { PartListScreen(state.value) { opened=it } } }
            assertRow("book-14"); compose.onNodeWithTag("book-14").performClick()
            assertRow("test-14-1"); compose.onNodeWithTag("test-14-1").performClick()
            val tag="open-${part.id}"
            assertRow(tag); compose.onNodeWithText("继续练习").assertIsDisplayed()
            compose.onNodeWithTag(tag).performClick(); compose.runOnIdle { assertEquals(part.id,opened) }
            assertEquals("alpha",repo.all().single().selectedAnswers[part.questions[2].id])
            repo.submit(session)
            val history=repo.all()
            compose.runOnIdle { state.value=state.value.copy(history=history) }
            assertRow(tag); compose.onNodeWithText("查看成绩").assertIsDisplayed()
            compose.onNodeWithText("已完成 · 1/10").assertIsDisplayed()
            compose.onNodeWithTag(tag).performClick(); assertEquals(session,repo.resumeOrCreate(part))
            assertEquals(history,repo.all())
        } finally { db.close() }
    }
}
