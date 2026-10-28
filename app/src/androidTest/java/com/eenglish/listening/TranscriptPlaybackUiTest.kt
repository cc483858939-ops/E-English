package com.eenglish.listening

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.domain.annotation.*
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.components.AnnotationContext
import com.eenglish.listening.ui.components.LocalAnnotations
import com.eenglish.listening.ui.screens.*
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.*
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TranscriptPlaybackUiTest {
    @get:Rule val compose = createAndroidComposeRule<AnnotationTestActivity>()
    private val segments = listOf(TranscriptSegment("PART 1", "")) + (1..32).map {
        TranscriptSegment("Segment $it contains useful listening material. Read this sentence carefully and compare it with the recording.",
            "第${it}段包含听力材料。请仔细阅读这句话，并与录音中的内容进行对照。")
    }
    private val transcript = Transcript(segments.joinToString("\n\n") { it.english },
        segments.map { it.chinese }.filter { it.isNotBlank() }.joinToString("\n\n"), segments)
    private val question = Question("sync-q1", 1, QuestionType.TEXT_INPUT, "Complete the answer", emptyList(), "answer", wordLimit = WordLimit(1))
    private val part = ListeningPart(2, "cambridge-5-test-1-part-1", "IELTS", 5, 1, 1, "Playback fixture", "Listen",
        "listening/cambridge-5-test-1-part-1/audio.mp3", "a".repeat(64), transcript, listOf(question))
    private val timeline = TranscriptTimeline(part.id, part.audioSha256, transcriptTimingHash(transcript),
        segments.indices.map { if (it == 0) null else TranscriptCue(it * 1000L, it * 1000L + 900) })
    private val audio = mutableStateOf(AudioState(ready = true, durationMs = 33000L))
    private lateinit var page: TranscriptPageState
    private val mode = mutableStateOf(TranscriptMode.ENGLISH)
    private val seeks = mutableListOf<Long>()
    private val highlights = mutableStateOf<List<Highlight>>(emptyList())
    private val markEvents = mutableListOf<Pair<List<AnnotationChange>, Boolean>>()

    private fun show(valid: Boolean = true) {
        val block = AnnotationDocument.transcript(transcript, "en").blocks.single { it.textId == "segment:15" }
        highlights.value = listOf(Highlight(block.key(part.id), HighlightRange(0, 7, 1)))
        val state = PracticeUiState(loading = false, part = part,
            transcriptTimeline = if (valid) timeline else timeline.copy(audioSha256 = "b".repeat(64)))
        compose.setContent {
            ListeningTheme {
                page = rememberTranscriptPageState(1)
                CompositionLocalProvider(LocalAnnotations provides AnnotationContext(part.id, highlights.value, { changes, add ->
                    markEvents += changes to add
                    changes.forEach { change ->
                        val previous = highlights.value.filter { it.key == change.key }.map { it.range }
                        val updated = if (add) HighlightRanges.add(previous, change.start, change.end, 2)
                            else HighlightRanges.remove(previous, change.start, change.end)
                        highlights.value = highlights.value.filter { it.key != change.key } + updated.map { Highlight(change.key, it) }
                    }
                })) {
                    TranscriptScreen(state, audio.value, { audio.value = audio.value.copy(isPlaying = !audio.value.isPlaying) },
                        { seeks += it; audio.value = audio.value.copy(positionMs = it) }, mode.value, { mode.value = it }, {},
                        onSelect = { _, _ -> error("Playback must not save answers") },
                        onSeekBy = { audio.value = audio.value.copy(positionMs = (audio.value.positionMs + it).coerceIn(0, 33000)) },
                        onSpeed = { audio.value = audio.value.copy(speed = it) }, pageState = page)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun blueRange(tag: String): Pair<Int, Int>? {
        var result: Pair<Int, Int>? = null
        compose.runOnIdle {
            val text = nativeText(compose.activity, tag).text as Spanned
            val spans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
                .filter { it.javaClass.simpleName == "PlaybackForeground" }
            assertTrue("Only one playback span", spans.size <= 1)
            result = spans.singleOrNull()?.let { text.getSpanStart(it) to text.getSpanEnd(it) }
        }
        return result
    }

    private fun assertActive(index: Int, language: String = "en") {
        val block = AnnotationDocument.transcript(transcript, language).blocks.single { it.textId == "segment:$index" }
        assertEquals(block.start to block.end, blueRange("transcript-$language"))
    }

    private fun assertActiveVisible(index: Int, language: String = "en") {
        compose.runOnIdle {
            val view = nativeText(compose.activity, "transcript-$language")
            val block = AnnotationDocument.transcript(transcript, language).blocks.single { it.textId == "segment:$index" }
            val visible = Rect(); assertTrue(view.getGlobalVisibleRect(visible))
            val screen = IntArray(2); view.getLocationOnScreen(screen)
            val y = screen[1] + view.layout.getLineTop(view.layout.getLineForOffset(block.start))
            assertTrue("Current paragraph top must be visible: $y in $visible", y >= visible.top && y < visible.bottom)
        }
    }

    private fun capture(name: String) {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("capturePlayback") != "true") return
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val resolver = instrumentation.targetContext.contentResolver
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "eenglish-playback-$name.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/eenglish-playback")
            }))
        requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun playingAndPausedPositionsMarkTheRightParagraphAndPreserveOldHighlights() {
        show()
        assertNull(blueRange("transcript-en"))
        compose.runOnIdle { audio.value = audio.value.copy(isPlaying = true, positionMs = 15100) }
        compose.waitForIdle()
        assertActive(15); assertActiveVisible(15)
        capture("english")
        val before = highlights.value
        compose.runOnIdle {
            val text = nativeText(compose.activity, "transcript-en").text as Spanned
            assertEquals(1, text.getSpans(0, text.length, BackgroundColorSpan::class.java).size)
            audio.value = audio.value.copy(isPlaying = false, speed = 1.5f)
        }
        assertActive(15)
        assertEquals(before, highlights.value)
    }

    @Test fun pausedSliderJumpManualReadingAndResumeUseCurrentMediaPosition() {
        show()
        compose.onNodeWithTag("audio-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(15100f) }
        compose.waitForIdle()
        assertEquals(listOf(15100L), seeks)
        assertActive(15); assertActiveVisible(15)
        compose.onNodeWithTag("transcript-body").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertFalse(page.followPlayback.value)
        val reading = page.transcriptScroll.value
        compose.runOnIdle { audio.value = audio.value.copy(isPlaying = true, positionMs = 25100) }
        assertActive(25)
        compose.runOnIdle { assertEquals(reading, page.transcriptScroll.value) }
        compose.onNodeWithTag("transcript-follow-resume").performClick()
        compose.waitForIdle()
        assertTrue(page.followPlayback.value)
        assertActiveVisible(25)
    }

    @Test fun languageSwitchesKeepEnglishChineseAndBilingualOnTheSameCue() {
        show()
        compose.runOnIdle { audio.value = audio.value.copy(positionMs = 22100) }
        assertActive(22)
        compose.onNodeWithText("中文翻译").performClick()
        compose.waitForIdle()
        assertActive(22, "zh"); assertActiveVisible(22, "zh")
        compose.onNodeWithText("英中双语").performClick()
        compose.onNodeWithTag("transcript-segment-22").assertIsDisplayed()
        assertEquals(0 to segments[22].english.length, blueRange("transcript-en-22"))
        assertEquals(0 to segments[22].chinese.length, blueRange("transcript-zh-22"))
        compose.runOnIdle { audio.value = audio.value.copy(isPlaying = true, positionMs = 28100) }
        compose.onNodeWithTag("transcript-segment-28").assertIsDisplayed()
        assertEquals(0 to segments[28].english.length, blueRange("transcript-en-28"))
        assertNull(blueRange("transcript-en-22"))
        capture("bilingual")
    }

    @Test fun selectingBlueTextCopiesTheExactWordAndSuspendsFollowing() {
        show()
        compose.runOnIdle { audio.value = audio.value.copy(isPlaying = true, positionMs = 15100) }
        compose.waitForIdle()
        val block = AnnotationDocument.transcript(transcript, "en").blocks.single { it.textId == "segment:15" }
        val start = block.start + block.content.indexOf("contains")
        onView(withTagValue(equalTo("transcript-en"))).perform(pressWord(start + 2), selectionAction(start, start + 8))
        selectionMenu("复制")
        compose.runOnIdle {
            assertFalse(page.followPlayback.value)
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("contains", clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(1, highlights.value.size)
        }
        assertActive(15)
    }

    @Test fun mismatchedAudioFallsBackToNormalReadingAndSeeking() {
        show(valid = false)
        compose.onNodeWithTag("transcript-follow-bar").assertDoesNotExist()
        compose.onNodeWithTag("audio-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(15100f) }
        assertEquals(listOf(15100L), seeks)
        assertNull(blueRange("transcript-en"))
        compose.runOnIdle { assertEquals(0, page.transcriptScroll.value) }
    }

    @Test fun substantiallyDifferentAudioDurationDisablesFollowing() {
        show()
        compose.runOnIdle { audio.value = audio.value.copy(durationMs = 2000, positionMs = 1100) }
        compose.onNodeWithTag("transcript-follow-bar").assertDoesNotExist()
        assertNull(blueRange("transcript-en"))
    }

    @Test fun tapHighlightedEnglishSeeksWhilePausedAndRetainsMarks() {
        show()
        compose.runOnIdle { audio.value = audio.value.copy(positionMs = 15100, speed = 1.5f) }
        compose.waitForIdle()
        val block = AnnotationDocument.transcript(transcript, "en").blocks.single { it.textId == "segment:15" }
        textAt("transcript-en").perform(tapCharacter(block.start + 2))
        compose.runOnIdle {
            assertEquals(listOf(15000L), seeks)
            assertFalse(audio.value.isPlaying)
            assertEquals(1.5f, audio.value.speed)
            assertTrue(page.followPlayback.value)
            assertEquals(1, highlights.value.size)
            assertTrue(markEvents.isEmpty())
        }
        assertActive(15)
        compose.onAllNodesWithText("复制整块高亮").assertCountEquals(0)
    }

    @Test fun tapChineseAndBothBilingualTextsUseTheSameCueAndKeepPlaying() {
        show()
        compose.runOnIdle { audio.value = audio.value.copy(positionMs = 15100, isPlaying = true) }
        compose.onNodeWithText("中文翻译").performClick()
        val block = AnnotationDocument.transcript(transcript, "zh").blocks.single { it.textId == "segment:15" }
        textAt("transcript-zh").perform(tapCharacter(block.start + 2))
        assertActive(15, "zh")
        compose.onNodeWithText("英中双语").performClick()
        compose.onNodeWithTag("transcript-segment-15").assertIsDisplayed()
        for (language in listOf("en", "zh")) {
            textAt("transcript-$language-15").perform(tapCharacter(2))
        }
        compose.runOnIdle {
            assertEquals(listOf(15000L, 15000L, 15000L), seeks)
            assertTrue(audio.value.isPlaying)
            assertTrue(page.followPlayback.value)
        }
    }

    @Test fun longPressStillCopiesMarksAndRemovesWithoutSeeking() {
        show()
        compose.runOnIdle { audio.value = audio.value.copy(positionMs = 15100) }
        compose.waitForIdle()
        val block = AnnotationDocument.transcript(transcript, "en").blocks.single { it.textId == "segment:15" }
        textAt("transcript-en").perform(pressWord(block.start + 2), selectionAction(block.start, block.start + 7))
        selectionMenu("复制")
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("Segment", clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertTrue(seeks.isEmpty())
        }
        val start = block.start + block.content.indexOf("contains")
        textAt("transcript-en").perform(pressWord(start + 2), selectionAction(start, start + 8))
        selectionMenu("高亮")
        compose.runOnIdle { assertTrue(markEvents.last().second); assertTrue(seeks.isEmpty()) }
        textAt("transcript-en").perform(pressWord(start + 2), selectionAction(start, start + 8))
        selectionMenu("取消高亮")
        compose.runOnIdle { assertFalse(markEvents.last().second); assertEquals(1, highlights.value.size); assertTrue(seeks.isEmpty()) }
    }

    @Test fun titleWhitespaceAndDraggingDoNotSeek() {
        show()
        textAt("transcript-en").perform(tapCharacter(2))
        // The blank separator after PART 1 is not a paragraph's spoken text.
        textAt("transcript-en").perform(tapCharacter(segments.first().english.length))
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
        compose.onNodeWithTag("transcript-body").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(seeks.isEmpty()); assertFalse(page.followPlayback.value) }
    }

    @Test fun mismatchedTimelineTapNeverSeeks() {
        show(valid = false)
        val block = AnnotationDocument.transcript(transcript, "en").blocks.single { it.textId == "segment:1" }
        textAt("transcript-en").perform(tapCharacter(block.start + 2))
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
    }

    @Test fun twoQuickTapsBothSeekWithoutOpeningSelection() {
        show()
        compose.runOnIdle { audio.value = audio.value.copy(positionMs = 15100) }
        compose.waitForIdle()
        val block = AnnotationDocument.transcript(transcript, "en").blocks.single { it.textId == "segment:15" }
        textAt("transcript-en").perform(doubleTapCharacter(block.start + 2))
        compose.runOnIdle {
            assertEquals(listOf(15000L, 15000L), seeks)
            val view = nativeText(compose.activity, "transcript-en")
            assertFalse(view.selectionStart >= 0 && view.selectionEnd > view.selectionStart)
        }
    }

    @Test fun tappingWhileSelectingDoesNotSeek() {
        show()
        compose.runOnIdle { audio.value = audio.value.copy(positionMs = 15100) }
        compose.waitForIdle()
        val block = AnnotationDocument.transcript(transcript, "en").blocks.single { it.textId == "segment:15" }
        val start = block.start + block.content.indexOf("contains")
        textAt("transcript-en").perform(pressWord(start + 2), selectionAction(start, start + 8),
            tapCharacter(block.start + block.content.indexOf("useful") + 2))
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
    }
}
