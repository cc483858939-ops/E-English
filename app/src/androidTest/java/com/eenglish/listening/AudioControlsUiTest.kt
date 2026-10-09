package com.eenglish.listening

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.audio.playbackSpeeds
import com.eenglish.listening.ui.components.AudioControls
import com.eenglish.listening.ui.components.formatSpeed
import com.eenglish.listening.ui.theme.ListeningTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AudioControlsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun controlsDisableSeekUntilReadyAndAfterError() {
        val state = mutableStateOf(AudioState())
        compose.setContent { ListeningTheme { AudioControls(state.value, {}, {}) } }
        listOf("audio-toggle", "audio-back-5", "audio-forward-5", "audio-seek").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
        compose.runOnIdle { state.value = AudioState(ready = true, durationMs = 90000) }
        compose.onNodeWithTag("audio-back-5").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(error = "Synthetic error") }
        listOf("audio-toggle", "audio-back-5", "audio-forward-5", "audio-seek").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
    }

    @Test fun allSpeedsAndTwoRowsFitCurrentFontAndScreen() {
        val state = mutableStateOf(AudioState(ready = true, durationMs = 45296000, positionMs = 3600000))
        val jumps = mutableListOf<Long>()
        compose.setContent { ListeningTheme { AudioControls(state.value, {}, {}, compact = true,
            onSeekBy = { jumps += it }, onSpeed = { state.value = state.value.copy(speed = it) }) } }
        playbackSpeeds.forEach { speed ->
            compose.onNodeWithTag("audio-speed").performClick()
            compose.onNodeWithTag("audio-speed-${formatSpeed(speed)}").performClick()
            compose.onNodeWithContentDescription("播放速度${formatSpeed(speed)}倍").assertIsDisplayed()
            compose.onNodeWithTag("audio-time").assertTextEquals("1:00:00")
            assertLayout()
        }
        compose.onNodeWithTag("audio-back-5").performClick()
        compose.onNodeWithTag("audio-forward-5").performClick()
        assertEquals(listOf(-5000L, 5000L), jumps)
    }

    @Test fun draggingPreviewsPositionIgnoresPollingAndSeeksOnceOnRelease() {
        val state = mutableStateOf(AudioState(ready = true, durationMs = 100000, positionMs = 1000))
        val seeks = mutableListOf<Long>()
        compose.setContent { ListeningTheme { AudioControls(state.value, {}, { seeks += it }) } }
        compose.onNodeWithTag("audio-seek").performTouchInput {
            down(Offset(width * .15f, centerY)); moveTo(Offset(width * .72f, centerY))
        }
        val preview = compose.onNodeWithTag("audio-seek").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue(preview in 60000f..85000f)
        assertTrue(seeks.isEmpty())
        compose.runOnIdle { state.value = state.value.copy(positionMs = 3000) }
        assertEquals(preview, compose.onNodeWithTag("audio-seek").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current, 1f)
        compose.onNodeWithTag("audio-seek").performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(preview.toLong()), seeks) }
    }

    private fun assertLayout() {
        val bounds = compose.onNodeWithTag("audio-controls").fetchSemanticsNode().boundsInRoot
        val buttons = listOf("audio-back-5", "audio-toggle", "audio-forward-5", "audio-speed").map {
            compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        buttons.zipWithNext().forEach { (a, b) -> assertTrue("Control buttons overlap", a.right <= b.left) }
        buttons.forEach {
            assertTrue(it.left >= bounds.left && it.right <= bounds.right)
            assertTrue(it.width >= 48 * compose.density.density && it.height >= 48 * compose.density.density)
        }
        val time = compose.onNodeWithTag("audio-time").fetchSemanticsNode().boundsInRoot
        val seek = compose.onNodeWithTag("audio-seek").fetchSemanticsNode().boundsInRoot
        val duration = compose.onNodeWithTag("audio-duration").fetchSemanticsNode().boundsInRoot
        assertTrue(buttons.maxOf { it.bottom } <= seek.top)
        assertTrue("Time/seek/duration hit regions overlap: $time / $seek / $duration",
            time.right <= seek.left && seek.right <= duration.left)
        assertTrue(seek.width >= 48 * compose.density.density)
        assertTrue(duration.right <= bounds.right && seek.bottom <= bounds.bottom)
        val thumb = compose.onNodeWithTag("audio-thumb", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(thumb.width <= 18 * compose.density.density)
    }
}
