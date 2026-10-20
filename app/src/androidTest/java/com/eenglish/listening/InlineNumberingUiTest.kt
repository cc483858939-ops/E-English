package com.eenglish.listening

import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.components.LocalAnswerSession
import com.eenglish.listening.ui.screens.*
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.*
import kotlinx.serialization.json.*
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class InlineNumberingUiTest {
    @get:Rule val compose = createAndroidComposeRule<AnnotationTestActivity>()
    private lateinit var part: ListeningPart
    private val answers = mutableMapOf<String, String>()

    private fun show(transcript: Boolean = false, submitted: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = File.createTempFile("numbering-", ".eelpack", context.cacheDir)
        val layout: JsonObject
        try {
            val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cat /data/local/tmp/eenglish-review/cambridge-5-v4-test.eelpack")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).use { input -> staged.outputStream().use { input.copyTo(it) } }
            ZipFile(staged).use { zip ->
                val base = "listening/cambridge-5-test-1-part-1/"
                part = PartCodec.decode(zip.getInputStream(zip.getEntry(base + "part.json")).reader().readText())
                layout = Json.parseToJsonElement(zip.getInputStream(zip.getEntry(base + "layout.json")).reader().readText()).jsonObject
            }
        } finally { staged.delete() }
        if (submitted) answers[part.questions.single { it.number == 7 }.id] = "47.50"
        val attempt = PracticeAttempt(PracticeSession("numbering-attempt", part.id,
            if (submitted) SessionStatus.SUBMITTED else SessionStatus.IN_PROGRESS, 1L,
            if (submitted) 2L else null, if (submitted) Grader.grade(part.questions, answers).correctCount else null, part.questions.size),
            part.questions, emptyList())
        val shown = mutableStateOf(PracticeUiState(loading = false, part = part, questionLayoutV4 = layout,
            attempt = attempt, answers = answers.toMap()))
        fun edit(id: String, value: String) {
            check(!submitted) { "Review must not save" }
            answers[id] = value
            shown.value = shown.value.copy(answers = answers.toMap())
        }
        compose.setContent {
            CompositionLocalProvider(LocalAnswerSession provides attempt.session.id) {
                ListeningTheme {
                    if (transcript) TranscriptScreen(shown.value, AudioState(), {}, {}, TranscriptMode.ENGLISH, {}, {},
                        onSelect = ::edit, onInlineEdit = ::edit, pageState = rememberTranscriptPageState(7))
                    else PracticeScreen(shown.value, AudioState(), {}, {}, ::edit, {}, {}, {}, {}, {}, {}, {}, onInlineEdit = ::edit)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertPrintedPrefixHidden() {
        onView(withTagValue(equalTo("v4-inline-text-b-g3-q7-prefix"))).check { view, error ->
            if (error != null) throw error
            val displayed = (view as TextView).text.toString()
            assertTrue(displayed, displayed.startsWith("The tour costs $"))
            assertFalse(displayed, displayed.startsWith("7 "))
        }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureNumbering") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "eenglish-numbering-$name.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/eenglish-numbering")
            }))
        requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun realPracticeKeepsNumberAfterTypingAndSavesOnlyTheAnswer() {
        show()
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("v4-input-7-1"))
        assertPrintedPrefixHidden()
        compose.onNodeWithTag("v4-input-number-7-1", useUnmergedTree = true).assertTextEquals("7")
        compose.onNodeWithTag("v4-input-7-1").performTextReplacement("47.50")
        compose.onNodeWithTag("v4-input-number-7-1", useUnmergedTree = true).assertTextEquals("7")
        compose.runOnIdle { assertEquals("47.50", answers[part.questions.single { it.number == 7 }.id]) }
        capture("filled")
        compose.onNodeWithTag("v4-input-7-1").performTextClearance()
        compose.onNodeWithTag("v4-input-number-7-1", useUnmergedTree = true).assertTextEquals("7")
        capture("empty")
    }

    @Test fun realTranscriptUsesTheSamePermanentNumberAndDisplayCut() {
        show(transcript = true)
        compose.onNodeWithTag("v4-input-7-1").performScrollTo().performTextReplacement("47.50")
        compose.onNodeWithTag("v4-input-number-7-1", useUnmergedTree = true).assertTextEquals("7")
        assertPrintedPrefixHidden()
        compose.runOnIdle { assertEquals("47.50", answers[part.questions.single { it.number == 7 }.id]) }
    }

    @Test fun submittedTextRetainsNumberAndFullAnswerWithoutAnEditor() {
        show(transcript = true, submitted = true)
        assertPrintedPrefixHidden()
        compose.onNodeWithTag("review-answer-7").assertIsDisplayed()
        compose.onNodeWithText("7", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("v4-input-7-1").assertDoesNotExist()
        compose.onNodeWithTag("review-answer-7").performClick()
        compose.onNodeWithTag("transcript-review-answer-tab").performClick()
        compose.onNodeWithTag("review-user-answer").performScrollTo().assertTextEquals("47.50")
    }
}
