package com.eenglish.listening

import android.content.ClipboardManager
import android.content.Context
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.espresso.action.ViewActions.click
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.domain.annotation.*
import com.eenglish.listening.ui.components.*
import com.eenglish.listening.ui.screens.TranscriptScreen
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class AnnotationUiTest {
    @get:Rule val compose = createAndroidComposeRule<AnnotationTestActivity>()
    private val app get() = compose.activity.application as TestListeningApplication
    private val part = annotationFixture()
    private val practiceVM get() = ViewModelProvider(compose.activity)[PracticeViewModel::class.java]
    private var selections = 0
    private val darkMode = mutableStateOf(false)

    @Before fun fixture() {
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        // Resolve ActivityScenario-backed owners on the test thread, never during composition.
        lateinit var annotationsModel: AnnotationViewModel
        lateinit var practiceModel: PracticeViewModel
        compose.activityRule.scenario.onActivity {
            annotationsModel = ViewModelProvider(it)[AnnotationViewModel::class.java]
            practiceModel = ViewModelProvider(it)[PracticeViewModel::class.java]
        }
        compose.setContent {
            ListeningTheme {
              MaterialTheme(colorScheme = if (darkMode.value) darkColorScheme() else MaterialTheme.colorScheme) {
                val highlights by annotationsModel.highlights.collectAsStateWithLifecycle()
                val audio by practiceModel.audio.state.collectAsStateWithLifecycle()
                var mode by rememberSaveable { mutableStateOf(TranscriptMode.ENGLISH) }
                var visible by rememberSaveable { mutableStateOf(true) }
                var answers by remember { mutableStateOf(emptyMap<String, String>()) }
                CompositionLocalProvider(LocalAnnotations provides AnnotationContext(part.id, highlights, annotationsModel::change)) {
                    if (visible) TranscriptScreen(PracticeUiState(loading = false, part = part, answers = answers,
                        attempt = PracticeAttempt(PracticeSession("fixture-attempt", part.id, SessionStatus.IN_PROGRESS, 1, null, null, 10), part.questions, emptyList())),
                        audio, practiceModel.audio::toggle, practiceModel.audio::seekTo, mode, { mode = it }, { visible = false },
                        onSelect = { id, option -> selections++; answers = answers + (id to option) })
                    else Button(onClick = { visible = true }) { Text("重新进入原文") }
                }
              }
            }
        }
    }
    private fun ranges(tag: String): List<Pair<Int, Int>> {
        var result = emptyList<Pair<Int, Int>>()
        compose.runOnIdle {
            val text = nativeText(compose.activity, tag).text as Spanned
            result = text.getSpans(0, text.length, BackgroundColorSpan::class.java).map { text.getSpanStart(it) to text.getSpanEnd(it) }.sortedBy { it.first }
        }
        return result
    }
    private fun waitRanges(tag: String, expected: List<Pair<Int, Int>>) {
        compose.waitUntil(10000) {
            val rows = runBlocking(Dispatchers.IO) { app.annotations.all() }
            val document = if (tag == "transcript-en") AnnotationDocument.transcript(part.transcript, "en")
                else if (tag == "transcript-zh") AnnotationDocument.transcript(part.transcript, "zh")
                else AnnotationDocument.single(part.transcript.segments[0].let { if (tag.endsWith("zh-0")) it.chinese else it.english }, "transcript", "segment:0", if (tag.endsWith("zh-0")) "zh" else "en")
            document.visibleRanges(part.id, rows).map { it.start to it.end } == expected
        }
        compose.waitForIdle()
        assertEquals(expected, ranges(tag))
    }
    private fun menu(label: String) { selectionMenu(label); compose.waitForIdle() }
    private fun mark(tag: String, start: Int, end: Int, add: Boolean = true) {
        textAt(tag).perform(pressWord(start + 1), selectionAction(start, end))
        menu(if (add) "高亮" else "取消高亮")
    }
    @Test fun longPressCopiesExactWordAndMultilineSelectionAndHighlightPersistsAfterNavigation() {
        textAt("transcript-en").perform(pressWord(6))
        compose.runOnIdle {
            val view = nativeText(compose.activity, "transcript-en")
            assertEquals(4, view.selectionStart)
            assertEquals(10, view.selectionEnd)
        }
        menu("复制")
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("repeat", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        textAt("transcript-en").perform(pressWord(17), selectionAction(15, 40))
        menu("复制")
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(part.transcript.english.substring(15, 40), clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        mark("transcript-en", 15, 21)
        waitRanges("transcript-en", listOf(15 to 21))
        compose.onNodeWithText("返回答题页").performClick()
        compose.onNodeWithText("重新进入原文").performClick()
        waitRanges("transcript-en", listOf(15 to 21))
        compose.runOnIdle { assertEquals(part.transcript.english, nativeText(compose.activity, "transcript-en").text.toString()) }
    }
    @Test fun overlappingHighlightsAndPartialCancellationKeepExactCharactersAndDarkContrast() {
        mark("transcript-en", 4, 21)
        waitRanges("transcript-en", listOf(4 to 21))
        mark("transcript-en", 4, 21)
        mark("transcript-en", 15, 26)
        waitRanges("transcript-en", listOf(4 to 26))
        mark("transcript-en", 10, 15, add = false)
        waitRanges("transcript-en", listOf(4 to 10, 15 to 26))
        compose.runOnIdle { darkMode.value = true }
        compose.runOnIdle {
            val text = nativeText(compose.activity, "transcript-en").text as Spanned
            text.getSpans(0, text.length, BackgroundColorSpan::class.java).forEach { assertEquals(0xFFFFE29A.toInt(), it.backgroundColor) }
            text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach { assertEquals(0xFF29230E.toInt(), it.foregroundColor) }
        }
    }
    @Test fun nativeSelectionHandleActuallyDragsAcrossLinesWithoutResizingPanel() {
        val height = compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height
        textAt("transcript-en").perform(pressWord(6))
        dragEndHandle(nativeText(compose.activity, "transcript-en"), 40)
        var selected = ""
        compose.runOnIdle {
            val view = nativeText(compose.activity, "transcript-en")
            assertEquals(4, view.selectionStart)
            assertTrue("End handle must select across lines: ${view.selectionStart}..${view.selectionEnd}", view.selectionEnd >= 28)
            selected = view.text.substring(view.selectionStart, view.selectionEnd)
            assertTrue(selected.contains('\n'))
        }
        menu("复制")
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(selected, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        assertEquals(height, compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height, 1f)
    }
    @Test fun englishChineseAndBilingualUseSameParagraphAnchorsWithoutMixingOffsets() {
        mark("transcript-en", 15, 21)
        waitRanges("transcript-en", listOf(15 to 21))
        compose.onNodeWithText("中文翻译").performClick()
        assertTrue(ranges("transcript-zh").isEmpty())
        mark("transcript-zh", 2, 7)
        waitRanges("transcript-zh", listOf(2 to 7))
        compose.onNodeWithText("英中双语").performClick()
        waitRanges("transcript-en-0", listOf(15 to 21))
        compose.onNodeWithText(part.transcript.segments.first().chinese).performScrollTo()
        waitRanges("transcript-zh-0", listOf(2 to 7))
        compose.onNodeWithText("英文原文").performClick()
        waitRanges("transcript-en", listOf(15 to 21))
    }
    @Test fun selectingAndMarkingKeepReadingPlayerAndDragPanelAndOptionTapWorks() {
        compose.waitUntil(15000) { practiceVM.audio.state.value.ready }
        val controller = practiceVM.audio
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.waitUntil(10000) { controller.state.value.isPlaying && controller.state.value.positionMs > 500 }
        val position = controller.state.value.positionMs
        val panel = compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot
        textAt("transcript-en").perform(pressWord(17))
        val offset = compose.onNodeWithTag("transcript-body").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        menu("高亮")
        waitRanges("transcript-en", listOf(15 to 21))
        assertEquals(offset, compose.onNodeWithTag("transcript-body").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 2f)
        assertEquals(panel, compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("transcript-drag-handle").performTouchInput { swipe(center, center + Offset(0f, -100f), 400) }
        assertTrue(compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height > panel.height)
        waitRanges("transcript-en", listOf(15 to 21))
        assertSame(controller, practiceVM.audio)
        assertTrue(controller.state.value.isPlaying)
        assertTrue(controller.state.value.positionMs >= position)
        compose.onNodeWithTag("option-21-B").performScrollTo()
        textAt("option-text-21-B").perform(click())
        compose.onNodeWithTag("option-21-B").assertIsSelected()
        val before = selections
        compose.onNodeWithTag("option-21-A").performScrollTo()
        textAt("option-text-21-A").perform(pressWord(5))
        menu("复制")
        assertEquals(before, selections)
        compose.onNodeWithTag("option-21-B").assertIsSelected()
        compose.onNodeWithTag("audio-toggle").performClick()
    }
    @Test fun promptsAndOptionsCopyAndPersistPartialMarksWithoutAnswering() {
        // Give the two-line prompt a full viewport before touching its first line.
        // performScrollTo aligns the bottom of text taller than the small default body.
        compose.onNodeWithTag("transcript-drag-handle").performTouchInput {
            swipe(center, center + Offset(0f, -160f), 400)
        }
        compose.onNodeWithText(part.questions.first().prompt).performScrollTo()
        textAt("prompt-21").perform(pressWord(9))
        menu("复制")
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("part", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        mark("prompt-21", 7, 11)
        compose.waitUntil(10000) { runBlocking(Dispatchers.IO) { app.annotations.all().size == 1 } }
        compose.waitForIdle()
        assertEquals(listOf(7 to 11), ranges("prompt-21"))
        compose.onNodeWithTag("option-21-B").performScrollTo()
        textAt("option-text-21-B").perform(pressWord(5))
        menu("复制")
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("Second", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        mark("option-text-21-B", 3, 9)
        compose.waitUntil(10000) { runBlocking(Dispatchers.IO) { app.annotations.all().size == 2 } }
        compose.waitForIdle()
        assertEquals(listOf(3 to 9), ranges("option-text-21-B"))
        assertEquals(0, selections)
        compose.onNodeWithTag("option-21-B").assertIsNotSelected()
    }
}
