package com.eenglish.listening

import android.widget.TextView
import android.content.ClipboardManager
import android.content.Context
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.annotation.*
import com.eenglish.listening.domain.layout.V4AnnotationSources
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.components.LocalAnswerSession
import com.eenglish.listening.ui.components.LocalAnnotations
import com.eenglish.listening.ui.components.AnnotationContext
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

    private fun show(transcript: Boolean = false, submitted: Boolean = false,
                     book: Int = 5, test: Int = 1, section: Int = 1, active: Int = 7,
                     highlightBlock: String? = null, highlightWord: String = "") {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = File.createTempFile("numbering-", ".eelpack", context.cacheDir)
        val layout: JsonObject
        try {
            val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cat /data/local/tmp/eenglish-review/cambridge-$book-v4-test.eelpack")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).use { input -> staged.outputStream().use { input.copyTo(it) } }
            ZipFile(staged).use { zip ->
                val base = "listening/cambridge-$book-test-$test-part-$section/"
                part = PartCodec.decode(zip.getInputStream(zip.getEntry(base + "part.json")).reader().readText())
                layout = Json.parseToJsonElement(zip.getInputStream(zip.getEntry(base + "layout.json")).reader().readText()).jsonObject
            }
        } finally { staged.delete() }
        if (submitted) answers[part.questions.single { it.number == active }.id] = "47.50"
        fun walk(element: JsonElement): List<JsonObject> = when (element) {
            is JsonObject -> listOf(element) + element.values.flatMap(::walk)
            is JsonArray -> element.flatMap(::walk)
            else -> emptyList()
        }
        val highlights = if (highlightBlock == null) emptyList() else {
            val group = layout["groups"]!!.jsonArray.first { g ->
                walk(g.jsonObject["blocks"]!!).any { it["id"]?.jsonPrimitive?.content == highlightBlock }
            }.jsonObject
            val block = walk(group["blocks"]!!).single { it["id"]?.jsonPrimitive?.content == highlightBlock }
            val source = V4AnnotationSources.block(group["groupId"]!!.jsonPrimitive.content, block, part.questions.associateBy { it.id })
            val start = source.text.indexOf(highlightWord)
            source.selection(part.id, start, start + highlightWord.length, true)
                .map { Highlight(it.key, HighlightRange(it.start, it.end, 1L)) }
        }
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
            CompositionLocalProvider(LocalAnswerSession provides attempt.session.id,
                LocalAnnotations provides AnnotationContext(part.id, highlights, { _, _ -> })) {
                ListeningTheme {
                    if (transcript) TranscriptScreen(shown.value, AudioState(), {}, {}, TranscriptMode.ENGLISH, {}, {},
                        onSelect = ::edit, onInlineEdit = ::edit, pageState = rememberTranscriptPageState(active))
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
        compose.onNodeWithTag("fill-review-result-sheet").assertIsDisplayed()
        compose.onNodeWithTag("review-user-answer").performScrollTo().assertTextEquals("47.50")
    }

    private fun assertBeforeEditor(block: String, expected: String) {
        textAt("v4-inline-text-$block").check { view, error ->
            if (error != null) throw error
            val text = (view as TextView).text.toString().substringBefore('\uFFFC')
                .replace(Regex("[\\s\u00a0]+"), " ").trim()
            assertEquals(expected, text)
        }
    }

    @Test fun libraryNotesHideSuffixNumbersRetainCurrencyAndSaveOnlyTypedAnswer() {
        show(test = 2, active = 1)
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("v4-input-1-1"))
        assertBeforeEditor("b-registration-q1-prefix", "· two")
        compose.onNodeWithTag("v4-input-number-1-1", useUnmergedTree = true).assertTextEquals("1")
        compose.onNodeWithTag("v4-input-1-1").performTextReplacement("photos")
        compose.runOnIdle { assertEquals("photos", answers[part.questions.single { it.number == 1 }.id]) }
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("v4-input-3-1"))
        assertBeforeEditor("b-annual-cost-prefix", "Cost to join per year (without current student card): £")
        compose.onNodeWithTag("v4-input-number-3-1", useUnmergedTree = true).assertTextEquals("3")
        capture("library-fixed")
    }

    @Test fun transcriptLibraryCutsKeepExistingHighlightsAndNativeCopyAfterRemovedNumber() {
        show(transcript = true, test = 2, active = 5, highlightBlock = "b-fines-prefix", highlightWord = "£")
        val tag = "v4-inline-text-b-fines-prefix"
        compose.onNodeWithTag("v4-input-5-1").performScrollTo()
        assertBeforeEditor("b-fines-prefix", "Fines start at £")
        var currency = 0
        compose.runOnIdle {
            val text = nativeText(compose.activity, tag).text as Spanned
            currency = text.toString().indexOf('£')
            assertTrue(currency >= 0)
            assertTrue(text.getSpans(currency, currency + 1, BackgroundColorSpan::class.java).isNotEmpty())
        }
        // Copy a different word, so an existing highlight does not intercept the touch.
        textAt(tag).perform(pressWord(2), selectionAction(0, 5))
        selectionMenu("复制")
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("Fines", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag("v4-input-5-1").performTextReplacement("50")
        compose.runOnIdle { assertEquals("50", answers[part.questions.single { it.number == 5 }.id]) }
    }

    @Test fun submittedLibraryQuestionTabShowsCleanTextAndPermanentAnswerNumber() {
        show(transcript = true, submitted = true, test = 2, active = 3)
        assertBeforeEditor("b-annual-cost-prefix", "Cost to join per year (without current student card): £")
        compose.onNodeWithTag("review-answer-3").assertIsDisplayed()
        compose.onNodeWithTag("v4-input-3-1").assertDoesNotExist()
        compose.onNodeWithTag("review-answer-3").performClick()
        compose.onNodeWithTag("fill-review-result-sheet").assertIsDisplayed()
        compose.onNodeWithTag("review-user-answer").performScrollTo().assertTextEquals("47.50")
    }

    @Test fun fullWidthPoundStaysBesideItsAnswerWithoutPrintedNumber() {
        show(book = 14, test = 1, active = 4)
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("v4-input-4-1"))
        assertBeforeEditor("b-g1-wallet-text-0", "a wallet containing approximately ￡")
        compose.onNodeWithTag("v4-input-number-4-1", useUnmergedTree = true).assertTextEquals("4")
    }

    @Test fun quotedBlankKeepsOpeningQuoteWithoutPrintedNumber() {
        show(book = 17, test = 3, section = 4, active = 39)
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("v4-input-39-1"))
        assertBeforeEditor("b-t3p4-birds-text-0", "Ringing depended on what is called the '")
        compose.onNodeWithTag("v4-input-number-39-1", useUnmergedTree = true).assertTextEquals("39")
    }

    @Test fun multipleQuestionsInSameRowLoseOnlyTheirOwnPrintedNumbers() {
        show(book = 19, test = 2, active = 4)
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("v4-input-4-1"))
        textAt("v4-inline-text-b-t2p1g1-place-text-0").check { view, error ->
            if (error != null) throw error
            val text = (view as TextView).text.toString()
            assertFalse(text, Regex("(?<!\\d)[34](?!\\d)").containsMatchIn(text))
            assertEquals(2, text.count { it == '\uFFFC' })
        }
        compose.onNodeWithTag("v4-input-number-3-1", useUnmergedTree = true).assertTextEquals("3")
        compose.onNodeWithTag("v4-input-number-4-1", useUnmergedTree = true).assertTextEquals("4")
    }
}
