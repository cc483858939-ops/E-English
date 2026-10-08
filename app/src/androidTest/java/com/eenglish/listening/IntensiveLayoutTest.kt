package com.eenglish.listening

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.screens.TranscriptScreen
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.PracticeUiState
import com.eenglish.listening.viewmodel.TranscriptMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IntensiveLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longQuestionAndOptionsScrollWithoutMovingTranscriptOrHidingPlayer() {
        // Synthetic layout stress fixture only; never imported into the application's question bank.
        val question = Question("layout-question", 21, QuestionType.SINGLE_CHOICE,
            "Long prompt layout fixture. ".repeat(100),
            listOf(Option("A", "Long option layout fixture. ".repeat(100)),
                Option("B", "Other option layout fixture. ".repeat(100))), "A")
        val reading = "Reading layout fixture. ".repeat(200)
        val part = ListeningPart(1, "layout-fixture", "TEST", 0, 0, 0, "Layout fixture", "Layout fixture",
            "", "", Transcript(reading, "翻译布局测试", listOf(TranscriptSegment(reading, "翻译布局测试"))), listOf(question))
        compose.setContent {
            ListeningTheme {
                TranscriptScreen(PracticeUiState(loading = false, part = part), AudioState(ready = true, durationMs = 100000),
                    onToggle = {}, onSeek = {}, mode = TranscriptMode.ENGLISH, onModeChange = {}, onBack = {}, onSelect = { _, _ -> })
            }
        }
        val readingNode = compose.onNodeWithTag("transcript-body").fetchSemanticsNode()
        val readingOffset = readingNode.config[SemanticsProperties.VerticalScrollAxisRange].value()
        val playerBounds = compose.onNodeWithTag("transcript-player").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot
        val available = compose.onNodeWithTag("intensive-content").fetchSemanticsNode().boundsInRoot
        assertTrue(card.height <= available.height * 0.51f)
        assertTrue(readingNode.boundsInRoot.height >= available.height * 0.49f)
        compose.onNodeWithText(question.prompt).assertExists()
        compose.onNodeWithTag("option-21-B").performScrollTo().assertIsDisplayed()
        val questionAxis = compose.onNodeWithTag("transcript-question-body").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(questionAxis.value() > 0)
        assertEquals(readingOffset, compose.onNodeWithTag("transcript-body").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value(), 0f)
        assertEquals(playerBounds, compose.onNodeWithTag("transcript-player").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("audio-toggle").assertIsDisplayed()
        compose.onNodeWithTag("audio-seek").assertIsDisplayed()
        compose.onNodeWithTag("transcript-card-toggle").assertIsDisplayed()
        compose.onNodeWithText("标准答案", substring = true).assertDoesNotExist()
        val offset = questionAxis.value()
        compose.onNodeWithTag("transcript-body").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val anchor = compose.onNodeWithTag("transcript-body").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(anchor > 0)
        repeat(3) {
            compose.onNodeWithTag("transcript-drag-handle").performTouchInput {
                swipe(center, center + Offset(0f, -available.height * 0.18f), 400)
            }
            assertEquals(offset, compose.onNodeWithTag("transcript-question-body").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value(), 2f)
            compose.onNodeWithTag("transcript-drag-handle").performTouchInput {
                swipe(center, center + Offset(0f, available.height * 0.18f), 400)
            }
            assertEquals(offset, compose.onNodeWithTag("transcript-question-body").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value(), 2f)
            assertEquals(anchor, compose.onNodeWithTag("transcript-body").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value(), 2f)
        }
        compose.onNodeWithTag("transcript-card-toggle").performClick()
        compose.onNodeWithTag("transcript-card-toggle").performClick()
        assertEquals(offset, compose.onNodeWithTag("transcript-question-body").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value(), 2f)
    }
}
