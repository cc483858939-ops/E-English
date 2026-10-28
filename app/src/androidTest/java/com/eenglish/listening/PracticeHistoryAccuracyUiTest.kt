package com.eenglish.listening

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.screens.PracticeHistoryScreen
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.PracticeUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PracticeHistoryAccuracyUiTest {
    @get:Rule val compose = createComposeRule()
    private val part = PartSummary("cambridge-5-test-1-part-1", 5, 1, 1, "fixture", 10, 1, 10)
    private val now = System.currentTimeMillis()
    private fun attempt(id: String, correct: Int?, total: Int = 10, submitted: Boolean = true, order: Int = 0) =
        PracticeAttempt(PracticeSession(id, part.id, if (submitted) SessionStatus.SUBMITTED else SessionStatus.IN_PROGRESS,
            now - order * 60000L, if (submitted) now else null, correct, total), emptyList(), emptyList())

    @Test fun savedScoresShowZeroPartialFullAndRoundedAccuracyButDraftsHaveNoRing() {
        val selected = mutableListOf<String>()
        val records = listOf(attempt("draft", null, submitted = false), attempt("zero", 0, order = 1),
            attempt("some", 6, order = 2), attempt("full", 10, order = 3), attempt("rounded", 1, 15, order = 4),
            attempt("unknown", null, 0, order = 5))
        compose.setContent { ListeningTheme {
            PracticeHistoryScreen(PracticeUiState(loading = false, parts = listOf(part), history = records), {}, { selected += it })
        } }
        compose.onNodeWithTag("record-accuracy-draft", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("record-status-draft", useUnmergedTree = true).assertTextEquals("进行中")
        for ((id, percentage, fraction) in listOf(Triple("zero", 0, 0f), Triple("some", 60, 0.6f),
                Triple("full", 100, 1f), Triple("rounded", 7, 1f / 15))) {
            compose.onNodeWithTag("records-list").performScrollToNode(hasTestTag("record-$id"))
            val ring = compose.onNodeWithTag("record-accuracy-$id", useUnmergedTree = true)
                .assertIsDisplayed().assertContentDescriptionEquals("正确率$percentage%")
            val progress = ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(fraction, progress.current, 0.0001f)
            assertEquals(0f..1f, progress.range)
            ring.performTouchInput { click() } // The right-hand graphic still opens the whole record.
            compose.runOnIdle { assertEquals(id, selected.last()) }
        }
        compose.onNodeWithTag("records-list").performScrollToNode(hasTestTag("record-unknown"))
        compose.onNodeWithTag("record-accuracy-unknown", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("record-status-unknown", useUnmergedTree = true).assertTextEquals("已完成")
    }

    @Test fun narrowScreenWithLargeTextKeepsScoreAndRingInsideTheirCard() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                ListeningTheme { Box(Modifier.width(320.dp)) {
                    PracticeHistoryScreen(PracticeUiState(loading = false, parts = listOf(part),
                        history = listOf(attempt("large", 10))), {}, {})
                } }
            }
        }
        val card = compose.onNodeWithTag("record-large").fetchSemanticsNode().boundsInRoot
        val info = compose.onNodeWithTag("record-info-large", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val ring = compose.onNodeWithTag("record-accuracy-large", useUnmergedTree = true)
            .assertIsDisplayed().assertContentDescriptionEquals("正确率100%").fetchSemanticsNode().boundsInRoot
        assertTrue(info.right <= ring.left)
        assertTrue(ring.left >= card.left && ring.right <= card.right)
        assertTrue(ring.top >= card.top && ring.bottom <= card.bottom)
        assertEquals(ring.width, ring.height, 1f)
        compose.onNodeWithTag("record-status-large", useUnmergedTree = true).assertTextEquals("答对 10/10 题")
        capture()
    }

    private fun capture() {
        if (InstrumentationRegistry.getArguments().getString("captureAccuracy") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "eenglish-history-accuracy-large.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/eenglish-history")
            }))
        requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
