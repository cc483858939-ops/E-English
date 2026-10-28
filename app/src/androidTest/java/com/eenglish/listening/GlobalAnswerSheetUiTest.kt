package com.eenglish.listening

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.components.*
import com.eenglish.listening.ui.screens.*
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class GlobalAnswerSheetUiTest {
    @get:Rule val compose = createAndroidComposeRule<AnnotationTestActivity>()
    private lateinit var state: MutableState<PracticeUiState>
    private lateinit var transcriptPage: TranscriptPageState
    private var flushed = 0
    private var submitted = 0

    private fun load(transcript: Boolean = false, review: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = File.createTempFile("answer-sheet-", ".eelpack", context.cacheDir)
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cat /data/local/tmp/eenglish-image-ime/cambridge-6-v4-test.eelpack")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).use { input -> staged.outputStream().use { input.copyTo(it) } }
        assertTrue("Stage the C6 test pack", staged.length() > 0)
        val part: ListeningPart
        val layout: JsonObject
        val images = mutableMapOf<String, Bitmap>()
        try {
            ZipFile(staged).use { zip ->
                val base = "listening/cambridge-6-test-1-part-1/"
                part = PartCodec.decode(zip.getInputStream(zip.getEntry(base + "part.json")).reader().readText())
                layout = Json.parseToJsonElement(zip.getInputStream(zip.getEntry(base + "layout.json")).reader().readText()).jsonObject
                fun readImages(element: JsonElement) {
                    when (element) {
                        is JsonObject -> {
                            if (element["type"]?.jsonPrimitive?.contentOrNull == "image") {
                                val asset = element.getValue("asset").jsonPrimitive.content
                                images[asset] = zip.getInputStream(requireNotNull(zip.getEntry(base + asset) ?: zip.getEntry(asset)))
                                    .use { requireNotNull(BitmapFactory.decodeStream(it)) }
                            }
                            element.values.forEach(::readImages)
                        }
                        is JsonArray -> element.forEach(::readImages)
                        else -> Unit
                    }
                }
                readImages(layout)
            }
        } finally { staged.delete() }
        val session = PracticeSession("answer-sheet", part.id, if (review) SessionStatus.SUBMITTED else SessionStatus.IN_PROGRESS,
            1L, if (review) 2L else null, if (review) 0 else null, part.questions.size)
        val first = part.questions.first().id
        state = mutableStateOf(PracticeUiState(loading = false, part = part, questionLayoutV4 = layout,
            attempt = PracticeAttempt(session, part.questions, emptyList()),
            inlineDrafts = if (review) emptyMap() else mapOf(first to "draft answer")))
        val loader: suspend (String) -> Bitmap? = { images[it] }
        val flush = {
            flushed++
            state.value = state.value.copy(answers = state.value.answers + state.value.inlineDrafts, inlineDrafts = emptyMap())
        }
        compose.setContent { ListeningTheme {
            CompositionLocalProvider(LocalQuestionImages provides loader, LocalAnswerSession provides session.id) {
                transcriptPage = rememberTranscriptPageState(1)
                if (transcript) TranscriptScreen(state.value, AudioState(ready = true), {}, {}, TranscriptMode.ENGLISH, {}, {},
                    onSelect = { _, _ -> error("Navigation must not change answers") }, onFlushInline = flush,
                    onSubmit = { submitted++ }, pageState = transcriptPage)
                else PracticeScreen(state.value, AudioState(ready = true), {}, {}, { _, _ -> error("Navigation must not change answers") },
                    {}, {}, { submitted++ }, {}, {}, {}, {}, onFlushInline = flush)
            }
        } }
        compose.waitForIdle()
    }

    private fun open() = compose.onNodeWithTag("answer-sheet-open").performClick()
    private fun jump(number: Int) {
        open()
        compose.onNodeWithTag("answer-sheet-grid").performScrollToNode(hasTestTag("jump-$number"))
        compose.onNodeWithTag("jump-$number").performClick()
        compose.onNodeWithTag("global-answer-sheet").assertDoesNotExist()
    }

    @Test fun practiceCardIncludesDraftAndJumpsToImageThenTextWithoutKeyboard() {
        load()
        open()
        compose.onNodeWithTag("jump-1").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已答"))
        compose.onNodeWithTag("jump-2").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "未答"))
        if (InstrumentationRegistry.getArguments().getString("captureAnswerSheet") == "true") {
            android.os.SystemClock.sleep(500) // Wait for the platform Dialog window animation before visual QA.
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val resolver = instrumentation.targetContext.contentResolver
            val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "eenglish-global-answer-sheet.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/eenglish-answer-sheet")
                }))
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithTag("answer-sheet-close").performClick()
        jump(8)
        compose.onNodeWithTag("v4-image-card-question").assertTextEquals("Q8")
        jump(2)
        compose.onNodeWithTag("v4-image-card-question").assertDoesNotExist()
        compose.onNodeWithTag("v4-input-2-1").assertIsDisplayed()
        assertFalse(ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
        compose.runOnIdle { assertEquals("draft answer", state.value.answers[state.value.questions.first().id]); assertTrue(flushed > 0) }
        open()
        compose.onNodeWithTag("answer-sheet-submit").performClick()
        compose.runOnIdle { assertEquals(1, submitted) }
    }

    @Test fun transcriptUsesTheSameCardAndExpandsTheSelectedQuestion() {
        load(transcript = true)
        compose.runOnIdle { transcriptPage.expanded.value = false }
        jump(8)
        compose.runOnIdle { assertEquals(8, transcriptPage.questionNumber.intValue); assertTrue(transcriptPage.expanded.value) }
        compose.onNodeWithTag("v4-image-list-input-8").assertIsDisplayed()
        open()
        compose.onNodeWithTag("jump-8").assertIsSelected()
        compose.onNodeWithTag("answer-sheet-submit").performClick()
        compose.runOnIdle { assertEquals(1, submitted) }
    }

    @Test fun submittedCardJumpsToReviewAndCannotSubmitAgain() {
        load(review = true)
        jump(9)
        compose.onNode(hasText("Q9") and hasAnyAncestor(hasTestTag("question-review-card"))).assertIsDisplayed()
        open()
        compose.onNodeWithTag("answer-sheet-submit").assertDoesNotExist()
        compose.onNodeWithTag("answer-sheet-close").performClick()
        compose.runOnIdle { assertEquals(SessionStatus.SUBMITTED, state.value.attempt!!.session.status); assertEquals(0, submitted) }
    }

    @Test fun largeTextShowsPartialMultiChoiceAndScrollsToLastQuestion() {
        val questions = (21..40).map { number -> Question("q$number", number,
            if (number <= 22) QuestionType.MULTIPLE_CHOICE else QuestionType.TEXT_INPUT,
            "prompt", emptyList(), "answer", groupId = if (number <= 22) "multi" else null) }
        var picked = 0
        var open by mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) { ListeningTheme {
                if (open) GlobalAnswerSheet(questions, null, mapOf("q21" to "A", "q22" to "A", "q23" to "answer"),
                    23, submitted = false, busy = true, onDismiss = { open = false },
                    onQuestion = { picked = it }, onSubmit = { error("Busy card cannot submit") })
            } }
        }
        compose.onNodeWithTag("jump-21").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已选 1/2"))
        compose.onNodeWithTag("jump-22").assertDoesNotExist()
        compose.onNodeWithTag("jump-23").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已答"))
        compose.onNodeWithTag("answer-sheet-submit").assertIsNotEnabled()
        compose.onNodeWithTag("answer-sheet-grid").performScrollToNode(hasTestTag("jump-40"))
        compose.onNodeWithTag("jump-40").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(40, picked) }
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("global-answer-sheet").assertDoesNotExist()
    }

    @Test fun legacyLongInlineSummaryLocatesTheNativeEditorWithoutFocus() {
        val prompt = "Questions 26–30\nComplete the summary below.\nWrite NO MORE THAN THREE WORDS.\n" +
            "The warehouse received 26 _____ boxes.\n" +
            "Inspectors checked 27 ______ seals and recorded 28 ______ readings.\n" +
            "The clerk filed 29 ______ notes.\n" + "Additional reference information.\n".repeat(25) +
            "The clerk printed 30 ______ tags."
        val questions = (26..30).map { Question("q$it", it, QuestionType.TEXT_INPUT, prompt, emptyList(), "fixture", wordLimit = WordLimit(3)) }
        val part = ListeningPart(1, "legacy-fixture", "IELTS", 9, 1, 3, "Fixture", "Complete the summary",
            "listening/legacy-fixture/audio.mp3", "0".repeat(64), Transcript("fixture", "测试", emptyList()), questions)
        val attempt = PracticeAttempt(PracticeSession("legacy", part.id, SessionStatus.IN_PROGRESS, 1L, null, null, 5), questions, emptyList())
        compose.setContent { ListeningTheme {
            PracticeScreen(PracticeUiState(loading = false, part = part, attempt = attempt), AudioState(), {}, {}, { _, _ -> },
                {}, {}, {}, {}, {}, {}, {})
        } }
        jump(30)
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(
            org.hamcrest.CoreMatchers.equalTo("inline-input-30"))).check { view, error ->
            if (error != null) throw error
            val rect = android.graphics.Rect()
            assertTrue("Target editor must be visible", view.getGlobalVisibleRect(rect))
            assertEquals("Entire input must be visible", view.height, rect.height())
            assertFalse("Jumping must not focus an editor", view.hasFocus())
        }
    }
}
