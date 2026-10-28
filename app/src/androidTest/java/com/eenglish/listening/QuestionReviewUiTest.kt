package com.eenglish.listening

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.grading.*
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.components.*
import com.eenglish.listening.ui.screens.*
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class QuestionReviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<AnnotationTestActivity>()
    private val app get() = compose.activity.application as TestListeningApplication
    private lateinit var part: ListeningPart
    private lateinit var state: PracticeUiState
    private lateinit var reviews: List<QuestionReview>
    private lateinit var transcriptPage: TranscriptPageState
    private val reviewHighlights = mutableStateOf<List<com.eenglish.listening.domain.annotation.Highlight>>(emptyList())

    private fun show(book: Int, partId: String, answers: (ListeningPart) -> Map<String, String>, transcript: Boolean = false, fontScale: Float = 1f, frozenQuestion: Int? = null) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = File.createTempFile("review-pack-", ".eelpack", context.cacheDir)
        val images = mutableMapOf<String, Bitmap>()
        val layout: JsonObject
        try {
            val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cat /data/local/tmp/eenglish-review/cambridge-$book-v4-test.eelpack")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).use { input -> staged.outputStream().use { input.copyTo(it) } }
            assertTrue("Stage the local Cambridge $book test pack", staged.length() > 0)
            ZipFile(staged).use { zip ->
                val base = "listening/$partId/"
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
        val attempt = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val snapshot = if (frozenQuestion == null) part else part.copy(questions = part.questions.map {
                if (it.number == frozenQuestion) it.copy(prompt = "Historical saved question $frozenQuestion") else it
            })
            val id = app.practices.startNew(snapshot)
            answers(part).forEach { (question, value) -> app.practices.saveAnswer(id, question, value) }
            app.practices.submit(id)
            app.practices.all().single { it.session.id == id }
        }
        state = PracticeUiState(loading = false, part = part, questionLayoutV4 = layout, attempt = attempt, answers = attempt.selectedAnswers)
        reviews = questionReviews(attempt.questions, state.answers)
        assertEquals(attempt.session.correctCount, reviews.sumOf { it.correctCount })
        val loader: suspend (String) -> Bitmap? = { images[it] }
        val shownState = mutableStateOf(state)
        val showingTranscript = mutableStateOf(transcript)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalQuestionImages provides loader, LocalAnswerSession provides attempt.session.id,
                LocalAnnotations provides AnnotationContext(part.id, reviewHighlights.value),
                LocalDensity provides Density(density.density, fontScale)) {
                ListeningTheme {
                    transcriptPage = rememberTranscriptPageState(if (transcript) 7 else part.questions.first().number)
                    if (showingTranscript.value) TranscriptScreen(state, AudioState(ready = true), {}, {}, TranscriptMode.ENGLISH, {},
                        { showingTranscript.value = false },
                        onSelect = { _, _ -> error("Review must not save") },
                        onInlineEdit = { _, _ -> error("Review must not edit") },
                        onInlineCommit = { error("Review must not commit") },
                        pageState = transcriptPage)
                    else PracticeScreen(shownState.value, AudioState(ready = true), {}, {}, { _, _ -> error("Review must not save") },
                        {}, { showingTranscript.value = true }, {}, {}, {}, {
                            shownState.value = state.copy(answers = emptyMap(), attempt = state.attempt!!.copy(
                                session = state.attempt!!.session.copy(id = "fresh-review-attempt", status = SessionStatus.IN_PROGRESS,
                                    submittedAt = null, correctCount = null), answers = emptyList()))
                        }, {}, onInlineEdit = { _, _ -> error("Review must not edit") },
                        onInlineCommit = { error("Review must not commit") },
                        onReviewTranscript = { transcriptPage.questionNumber.intValue = it; transcriptPage.reviewAnswers.value = false })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun select(number: Int) {
        val item = reviews.single { entry -> entry.questions.any { it.number == number } }
        compose.onNodeWithTag("review-jump").performClick()
        compose.onNode(hasText("${item.title} · ${item.status.label}") and hasAnyAncestor(isDialog()))
            .performScrollTo().performClick()
        compose.onNodeWithTag("review-status").assertTextEquals(item.status.label)
    }
    private fun openFillResult(number: Int) {
        compose.onAllNodesWithTag("review-answer-$number").onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("fill-review-result-sheet").assertIsDisplayed()
        compose.onNodeWithTag("fill-review-result-title").assertTextEquals("Q$number")
    }
    private fun closeFillResult() {
        compose.onNodeWithTag("fill-review-result-close").performClick()
        compose.onNodeWithTag("fill-review-result-sheet").assertDoesNotExist()
    }
    private fun assertImmutable() {
        val stored = runBlocking(Dispatchers.IO) { app.practices.all().single { it.session.id == state.attempt!!.session.id } }
        assertEquals(state.answers, stored.selectedAnswers)
        assertEquals(SessionStatus.SUBMITTED, stored.session.status)
        assertFalse(ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureReview") != "true") return
        compose.waitForIdle()
        android.os.SystemClock.sleep(200)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val resolver = instrumentation.targetContext.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "eenglish-review-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/eenglish-review")
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun realImageResultsShowCorrectWrongAndUnansweredAndNavigateWithoutSaving() {
        show(5, "cambridge-5-test-1-part-2", { p -> mapOf(
            p.questions.single { it.number == 18 }.id to "wheel",
            p.questions.single { it.number == 19 }.id to p.questions.single { it.number == 19 }.correctAnswer) })
        compose.onNodeWithTag("question-review-card").assertIsDisplayed()
        compose.onNodeWithTag("review-status").assertTextEquals("未作答")
        compose.onNodeWithTag("review-standard-answer").assertTextEquals(part.questions.first().correctAnswer)
        compose.onNodeWithTag("v4-result-11").assertDoesNotExist()
        compose.onNodeWithTag("v4-image-card-input-11").assertDoesNotExist()
        capture("unanswered")
        select(18)
        compose.onNodeWithTag("review-user-answer").assertIsDisplayed().assertTextEquals("wheel")
        capture("wrong")
        compose.onNodeWithTag("review-scroll").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("review-status").assertTextEquals("回答正确")
        compose.onNodeWithTag("review-user-answer").assertDoesNotExist()
        // The last question stops; swiping back restores the preceding result.
        select(reviews.last().questions.last().number)
        compose.onNodeWithTag("review-scroll").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("review-result-${reviews.last().id}").assertExists()
        select(19)
        compose.onNodeWithTag("review-scroll").performTouchInput { swipeRight() }
        compose.onNodeWithTag("review-user-answer").assertTextEquals("wheel")
        compose.onNodeWithTag("review-next").performClick()
        compose.onNodeWithTag("review-variants-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("review-accepted-variants").assertTextEquals(reviews.single { it.questions.any { q -> q.number == 19 } }.acceptedVariants.joinToString(" / "))
        capture("correct")
        compose.onNodeWithTag("review-only-wrong").performClick()
        compose.onNodeWithTag("review-status").assertTextEquals("未作答")
        val before = compose.onNodeWithTag("question-review-card").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("review-drag-handle").performTouchInput { swipe(center, center.copy(y = center.y - 140f), 300) }
        val grown = compose.onNodeWithTag("question-review-card").fetchSemanticsNode().boundsInRoot.height
        assertTrue(grown > before)
        compose.onNodeWithTag("review-scroll").performTouchInput { swipeUp() }
        compose.onNodeWithTag("review-standard-answer").assertTextEquals(part.questions.first().correctAnswer)
        assertEquals(grown, compose.onNodeWithTag("question-review-card").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithTag("review-toggle").performClick()
        compose.onNodeWithTag("review-status").assertDoesNotExist()
        compose.onNodeWithTag("review-toggle").performClick()
        assertImmutable()
        compose.onNodeWithTag("practice-submit").performClick()
        compose.onNodeWithTag("question-review-card").assertDoesNotExist()
        compose.onNodeWithTag("v4-image-answer-card").assertDoesNotExist()
        compose.onNodeWithText("提交答案").assertIsDisplayed()
    }

    @Test fun swipesRespectWrongFilterShortDragsAndBothBoundaries() {
        show(6, "cambridge-6-test-1-part-1", { p -> mapOf(p.questions[1].id to p.questions[1].correctAnswer) })
        fun assertQuestion(number: Int) {
            val id = reviews.single { r -> r.questions.any { it.number == number } }.id
            compose.onNodeWithTag("review-result-$id").assertExists()
        }
        assertQuestion(1)
        compose.onNodeWithTag("review-scroll").performTouchInput { swipeRight() }
        assertQuestion(1)
        compose.onNodeWithTag("review-scroll").performTouchInput {
            swipe(center, center.copy(x = center.x - 24f), 300)
        }
        assertQuestion(1)
        compose.onNodeWithTag("review-only-wrong").performClick()
        compose.onNodeWithTag("review-scroll").performTouchInput { swipeLeft() }
        assertQuestion(3)
        compose.onNodeWithTag("review-scroll").performTouchInput { swipeRight() }
        assertQuestion(1)
        compose.onNodeWithTag("review-only-wrong").performClick()
        compose.onNodeWithTag("review-scroll").performTouchInput { swipeLeft() }
        assertQuestion(2)
        assertImmutable()
    }

    @Test fun realSharedMultiChoiceShowsOneGroupAndMatchesPersistedPartialCredit() {
        show(6, "cambridge-6-test-2-part-2", { p -> mapOf(p.questions.single { it.number == 18 }.id to "C") })
        select(18)
        val item = reviews.single { it.questions.any { q -> q.number == 18 } }
        assertEquals(3, item.questions.size)
        assertEquals(1, item.correctCount)
        compose.onNodeWithText("本组答对 1 / 3").assertIsDisplayed()
        compose.onNodeWithTag("review-standard-answer").assertTextEquals(item.standardAnswer)
        compose.onNodeWithTag("review-user-answer").assertTextEquals(item.userAnswer)
        capture("multiple-choice")
        compose.onNodeWithTag("review-scroll").performTouchInput { swipeLeft() }
        compose.onNodeWithText("本组答对 1 / 3").assertIsDisplayed()
        compose.onNodeWithTag("review-scroll").performTouchInput { swipeRight() }
        compose.onNodeWithTag("review-result-${reviews[reviews.indexOf(item) - 1].id}").assertExists()
        compose.onNodeWithTag("review-scroll").performTouchInput { swipeLeft() }
        compose.onNodeWithText("本组答对 1 / 3").assertIsDisplayed()
        assertImmutable()
    }

    @Test fun realInlineAnswerTapSelectsItsResultWithoutOpeningAnEditor() {
        show(6, "cambridge-6-test-1-part-1", { p -> mapOf(p.questions.first().id to p.questions.first().correctAnswer) })
        compose.onNodeWithTag("review-answer-4").performScrollTo().performClick()
        compose.onNodeWithTag("review-status").assertTextEquals("未作答")
        compose.onNodeWithTag("review-standard-answer").assertTextEquals(part.questions.single { it.number == 4 }.correctAnswer)
        compose.onNodeWithTag("v4-input-4-1").assertDoesNotExist()
        assertImmutable()
    }

    @Test fun realPairedFillIsReadOnlyAndShowsBothCorrectPartsInTranscriptWithLargeText() {
        show(6, "cambridge-6-test-1-part-1", { p -> mapOf(p.questions.single { it.number == 7 }.id to "morning and ") },
            transcript = true, fontScale = 1.5f)
        compose.onNodeWithTag("transcript-review-question-tab").assertDoesNotExist()
        compose.onAllNodesWithTag("review-answer-7").onFirst().assertExists()
        compose.onNodeWithText("7.1", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("7.2", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("review-status").assertDoesNotExist()
        openFillResult(7)
        compose.onNodeWithTag("review-status").performScrollTo().assertTextEquals("未作答")
        compose.onNodeWithTag("review-standard-answer").performScrollTo()
            .assertTextEquals(part.questions.single { it.number == 7 }.correctAnswer)
        compose.onNodeWithTag("review-user-answer").performScrollTo().assertTextEquals("morning and ")
        capture("paired-transcript")
        compose.onNodeWithTag("v4-image-list-input-7-slot1").assertDoesNotExist()
        closeFillResult()
        compose.onNodeWithTag("transcript-next").performClick()
        openFillResult(8)
        compose.onNodeWithTag("review-status").performScrollTo().assertTextEquals("未作答")
        assertImmutable()
    }

    @Test fun transcriptShowsQ35FullGroupAndPreservesIndependentScrollPositions() {
        show(5, "cambridge-5-test-1-part-4", { emptyMap() })
        select(35)
        compose.onNodeWithText("查看原文").performClick()
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q35", substring = true)
        compose.onNodeWithTag("transcript-review-question-tab").assertDoesNotExist()
        compose.onNodeWithTag("review-answer-31").assertExists()
        compose.onNodeWithTag("review-answer-35").assertIsDisplayed()
        compose.onNodeWithTag("v4-result-35").assertDoesNotExist()
        compose.onNodeWithTag("transcript-body").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val reading = transcriptPage.transcriptScroll.value
        val questionOffset = transcriptPage.questionScroll.value
        assertTrue(questionOffset > 0)
        openFillResult(35)
        compose.onNodeWithTag("review-standard-answer").performScrollTo().assertTextEquals("nursing care")
        closeFillResult()
        compose.runOnIdle {
            assertEquals(reading, transcriptPage.transcriptScroll.value)
            assertEquals(questionOffset, transcriptPage.questionScroll.value)
        }
        capture("transcript-q35-question")
        openFillResult(34)
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q34", substring = true)
        compose.onNodeWithTag("review-standard-answer").performScrollTo()
            .assertTextEquals(part.questions.single { it.number == 34 }.correctAnswer)
        assertImmutable()
    }

    @Test fun transcriptImageReviewKeepsImageAndRestoresQuestionTabOnReentry() {
        show(5, "cambridge-5-test-1-part-2", { emptyMap() })
        select(18)
        compose.onNodeWithText("查看原文").performClick()
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q18", substring = true)
        compose.onNodeWithTag("transcript-review-question-tab").assertDoesNotExist()
        compose.onNodeWithTag("review-answer-18").assertExists()
        val image = compose.onAllNodes(SemanticsMatcher("question image") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("v4-image-canvas-") == true
        }).onFirst()
        image.assertIsDisplayed().performScrollTo().performTouchInput { click(topLeft + androidx.compose.ui.geometry.Offset(8f, 8f)) }
        compose.onNodeWithText("关闭 · 双指缩放与平移").assertIsDisplayed().performClick()
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q19", substring = true)
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeRight() }
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q18", substring = true)
        compose.onNodeWithTag("v4-image-list-input-18").assertDoesNotExist()
        openFillResult(18)
        compose.onNodeWithTag("review-standard-answer").performScrollTo()
            .assertTextEquals(part.questions.single { it.number == 18 }.correctAnswer)
        closeFillResult()
        compose.onNodeWithText("返回答题页").performClick()
        select(19)
        compose.onNodeWithText("查看原文").performClick()
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q19", substring = true)
        compose.onNodeWithTag("transcript-review-question-tab").assertDoesNotExist()
        assertImmutable()
    }

    @Test fun transcriptSharedMultipleChoiceKeepsWholeGroupAndSkipsDuplicateResults() {
        show(6, "cambridge-6-test-2-part-2", { p -> mapOf(p.questions.single { it.number == 18 }.id to "C") })
        select(18)
        compose.onNodeWithText("查看原文").performClick()
        compose.onNodeWithTag("transcript-review-question-tab").assertDoesNotExist()
        compose.onNodeWithTag("choice-review-content-18").assertExists()
        compose.onNodeWithTag("transcript-review-progress").assertTextEquals("8–10/10")
        compose.onNodeWithTag("transcript-next").assertIsNotEnabled()
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q18、19、20", substring = true)
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeRight() }
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q17", substring = true)
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q18、19、20", substring = true)
        compose.onNodeWithText("本组答对 1 / 3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeRight() }
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q17", substring = true)
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q18、19、20", substring = true)
        assertImmutable()
    }

    @Test fun transcriptSwipesKeepReadingPositionVerticalScrollAndResizeIndependent() {
        show(5, "cambridge-5-test-1-part-4", { emptyMap() })
        select(31)
        compose.onNodeWithText("查看原文").performClick()
        compose.onNodeWithTag("transcript-body").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 200f) }
        compose.waitForIdle()
        val reading = transcriptPage.transcriptScroll.value
        val height = compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeRight() }
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q31", substring = true)
        compose.onNodeWithTag("transcript-question-body").performTouchInput {
            swipe(center, center.copy(x = center.x - 24f), 300)
        }
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q31", substring = true)
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q32", substring = true)
        compose.onNodeWithTag("transcript-review-question-tab").assertDoesNotExist()
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeUp() }
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q32", substring = true)
        assertEquals(height, compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithTag("transcript-drag-handle").performTouchInput { swipe(center, center.copy(y = center.y - 100f), 300) }
        assertTrue(compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height > height)
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q32", substring = true)
        openFillResult(32)
        closeFillResult()
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q33", substring = true)
        compose.onNodeWithTag("fill-review-result-sheet").assertDoesNotExist()
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeRight() }
        compose.onNodeWithTag("transcript-question-summary").assertTextContains("Q32", substring = true)
        compose.runOnIdle { assertEquals(reading, transcriptPage.transcriptScroll.value) }
        assertImmutable()
    }

    @Test fun currentSingleChoiceShowsOptionsAndResultTogetherAndKeepsAnnotationsReadOnly() {
        show(5, "cambridge-5-test-2-part-2", { p -> mapOf(
            p.questions.single { it.number == 12 }.id to "A",
            p.questions.single { it.number == 13 }.id to p.questions.single { it.number == 13 }.correctAnswer) })
        select(12)
        compose.onNodeWithText("查看原文").performClick()
        compose.onNodeWithTag("transcript-review-question-tab").assertDoesNotExist()
        compose.onNodeWithTag("transcript-review-answer-tab").assertDoesNotExist()
        compose.onNodeWithTag("choice-review-content-12").assertExists()
        compose.onNodeWithTag("transcript-review-progress").assertTextEquals("2/10")
        compose.onNodeWithTag("choice-review-content-11").assertDoesNotExist()
        compose.onNodeWithTag("choice-review-option-12-A").performScrollTo().assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "你的错误选项"))
        compose.onNodeWithTag("choice-review-option-12-C").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "正确选项"))
        val group = state.questionLayoutV4!!.getValue("groups").jsonArray.map { it.jsonObject }.single {
            it.getValue("questionIds").jsonArray.any { id -> id.jsonPrimitive.content == part.questions.single { q -> q.number == 12 }.id }
        }
        val material = com.eenglish.listening.domain.layout.choiceReviewMaterial(group, part.questions,
            setOf(part.questions.single { it.number == 12 }.id))
        val promptIndex = material.paragraphs.indexOfFirst { it.text.contains("neighbour") }
        val projection = material.paragraphs[promptIndex]
        val start = projection.text.indexOf("neighbour")
        val change = projection.selection(part.id, start, start + 9, true).single()
        compose.runOnIdle { reviewHighlights.value = listOf(com.eenglish.listening.domain.annotation.Highlight(change.key,
            com.eenglish.listening.domain.annotation.HighlightRange(change.start, change.end, 1))) }
        compose.onNodeWithTag("choice-review-prompt-$promptIndex").performScrollTo()
        textAt("choice-review-prompt-$promptIndex").check { view, error ->
            if (error != null) throw error
            val spans = (view as android.widget.TextView).text as android.text.Spanned
            assertEquals(1, spans.getSpans(0, spans.length, android.text.style.BackgroundColorSpan::class.java).size)
        }.perform(pressWord(start + 2), selectionAction(start, start + 9))
        selectionMenu("复制")
        compose.onNodeWithTag("review-status").performScrollTo().assertTextEquals("回答错误")
        compose.onNodeWithTag("review-user-answer").performScrollTo().assertTextEquals(reviews.single { it.questions.first().number == 12 }.userAnswer)
        capture("choice-wrong-inline")
        compose.onNodeWithTag("transcript-next").performClick()
        compose.onNodeWithTag("choice-review-content-13").assertExists()
        compose.onNodeWithTag("choice-review-content-12").assertDoesNotExist()
        compose.onNodeWithTag("review-status").performScrollTo().assertTextEquals("回答正确")
        compose.onNodeWithTag("review-user-answer").performScrollTo().assertExists()
        assertImmutable()
    }

    @Test fun choiceHandleGrowsIntoLargeReviewWhileScrollingAndSwitchingKeepHeight() {
        show(5, "cambridge-5-test-2-part-2", { emptyMap() })
        select(12)
        compose.onNodeWithText("查看原文").performClick()
        val originalHeight = compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height
        val movement = compose.onNodeWithTag("intensive-content").fetchSemanticsNode().boundsInRoot.height * 0.5f
        compose.onNodeWithTag("transcript-drag-handle").performTouchInput {
            swipe(center, center.copy(y = center.y - movement), 600)
        }
        capture("choice-after-drag")
        assertTrue("Choice ratio after physical drag: ${transcriptPage.panelRatio.floatValue}",
            transcriptPage.panelRatio.floatValue >= 0.8f)
        compose.onNodeWithTag("audio-compact-time").assertIsDisplayed()
        compose.onNodeWithTag("audio-progress").assertDoesNotExist()
        compose.onNodeWithTag("transcript-language-tabs").assertDoesNotExist()
        val tallHeight = compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height
        assertTrue(tallHeight > originalHeight)
        val available = compose.onNodeWithTag("intensive-content").fetchSemanticsNode().boundsInRoot.height
        assertTrue(tallHeight / available > 0.85f)
        capture("choice-large-top")
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeUp() }
        assertEquals(tallHeight, compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height, 2f)
        compose.onNodeWithTag("review-user-answer").assertIsDisplayed()
        capture("choice-large")
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("choice-review-content-13").assertExists()
        assertEquals(tallHeight, compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height, 2f)
        compose.onNodeWithTag("transcript-card-toggle").performClick()
        compose.onNodeWithTag("audio-progress").assertExists()
        compose.onNodeWithTag("transcript-card-toggle").performClick()
        compose.onNodeWithTag("audio-compact-time").assertIsDisplayed()
        compose.onNodeWithTag("transcript-drag-handle").performSemanticsAction(SemanticsActions.SetProgress) { it(0.55f) }
        compose.onNodeWithTag("transcript-language-tabs").assertExists()
        compose.onNodeWithTag("audio-progress").assertExists()
        assertImmutable()
    }

    @Test fun imageChoiceKeepsReferenceImageAndOptionsTogetherWithoutSaving() {
        show(12, "cambridge-12-test-4-part-2", { p -> mapOf(p.questions.single { it.number == 15 }.id to "A") })
        select(15)
        compose.onNodeWithText("查看原文").performClick()
        compose.onNodeWithTag("choice-review-content-15").assertExists()
        compose.onNodeWithContentDescription("题目图片，点击放大").performScrollTo().performClick()
        compose.onNodeWithText("关闭 · 双指缩放图片").performClick()
        compose.onNodeWithTag("choice-review-option-15-A").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("transcript-next").performClick()
        compose.onNodeWithTag("choice-review-content-16").assertExists()
        assertImmutable()
    }

    @Test fun choiceReviewUsesFrozenHistoricalPromptWhenTheImportedLayoutHasChanged() {
        show(5, "cambridge-5-test-2-part-2", { emptyMap() }, frozenQuestion = 12)
        select(12)
        compose.onNodeWithText("查看原文").performClick()
        compose.onNodeWithText("Historical saved question 12").assertIsDisplayed()
        compose.onNodeWithTag("choice-review-content-12").assertExists()
        compose.onNodeWithTag("choice-review-option-12-A").performScrollTo().assertExists()
        assertImmutable()
    }

    @Test fun fillReviewUsesTheCommonLargeCardAndOpensResultsFromItsBlanks() {
        show(6, "cambridge-6-test-1-part-1", { p -> mapOf(p.questions.single { it.number == 7 }.id to "morning and ") },
            transcript = true)
        compose.onNodeWithTag("transcript-body").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 150f) }
        compose.waitForIdle()
        val reading = transcriptPage.transcriptScroll.value
        val movement = compose.onNodeWithTag("intensive-content").fetchSemanticsNode().boundsInRoot.height * 0.55f
        compose.onNodeWithTag("transcript-drag-handle").performTouchInput {
            swipe(center, center.copy(y = center.y - movement), 600)
        }
        compose.onNodeWithTag("audio-compact-time").assertIsDisplayed()
        compose.onNodeWithTag("transcript-language-tabs").assertDoesNotExist()
        compose.onNodeWithTag("transcript-review-question-tab").assertDoesNotExist()
        compose.onNodeWithText("7.1", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("7.2", useUnmergedTree = true).assertExists()
        assertTrue(transcriptPage.panelRatio.floatValue > 0.8f)
        capture("fill-material-large")
        openFillResult(7)
        compose.onNodeWithTag("review-user-answer").performScrollTo().assertTextEquals("morning and ")
        compose.onNodeWithTag("review-status").performScrollTo().assertTextEquals("未作答")
        capture("fill-common-large")
        closeFillResult()
        compose.onNodeWithTag("transcript-drag-handle").performSemanticsAction(SemanticsActions.SetProgress) { it(0.60f) }
        compose.onNodeWithTag("audio-progress").assertExists()
        compose.onNodeWithTag("transcript-language-tabs").assertExists()
        compose.runOnIdle { assertEquals(reading, transcriptPage.transcriptScroll.value) }
        assertImmutable()
    }

    @Test fun commonHeightIsSharedWhenSwitchingBetweenChoiceAndFill() {
        show(5, "cambridge-5-test-1-part-3", { emptyMap() })
        select(21)
        compose.onNodeWithText("查看原文").performClick()
        compose.onNodeWithTag("transcript-drag-handle").performSemanticsAction(SemanticsActions.SetProgress) { it(0.90f) }
        val height = compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("answer-sheet-open").performClick()
        compose.onNodeWithTag("jump-26").performClick()
        compose.onNodeWithTag("transcript-review-question-tab").assertDoesNotExist()
        compose.onNodeWithTag("review-answer-26").assertExists()
        compose.onNodeWithTag("audio-compact-time").assertIsDisplayed()
        assertEquals(height, compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height, 2f)
        compose.onNodeWithTag("answer-sheet-open").performClick()
        compose.onNodeWithTag("jump-21").performClick()
        compose.onNodeWithTag("choice-review-content-21").assertExists()
        assertEquals(height, compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height, 2f)
        assertImmutable()
    }
    @Test fun fillMaterialShowsSavedAnswersAndSingleResultForCorrectWrongAndEmptyBlanks() {
        show(6, "cambridge-6-test-1-part-1", { p -> mapOf(
            p.questions.single { it.number == 1 }.id to p.questions.single { it.number == 1 }.correctAnswer,
            p.questions.single { it.number == 2 }.id to "a deliberately incorrect answer") })
        select(1)
        compose.onNodeWithText("查看原文").performClick()
        compose.onNodeWithTag("transcript-drag-handle").performSemanticsAction(SemanticsActions.SetProgress) { it(0.90f) }
        compose.onNodeWithTag("review-answer-1").assertTextContains(part.questions.first().correctAnswer)
        compose.onNodeWithTag("review-answer-2").assertTextContains("a deliberately incorrect answer")
        capture("fill-text-material")
        for (number in 1..3) {
            compose.onNodeWithTag("review-answer-$number").performScrollTo()
            compose.waitForIdle()
            val materialOffset = transcriptPage.questionScroll.value
            val readingOffset = transcriptPage.transcriptScroll.value
            val ratio = transcriptPage.panelRatio.floatValue
            openFillResult(number)
            val expected = reviews.single { it.questions.first().number == number }
            compose.onNodeWithTag("review-status").assertTextEquals(expected.status.label)
            compose.onNodeWithTag("review-standard-answer").performScrollTo().assertTextEquals(expected.standardAnswer)
            compose.onNodeWithTag("review-user-answer").performScrollTo().assertTextEquals(expected.userAnswer.ifBlank { "未作答" })
            assertImmutable()
            if (number == 2) capture("fill-text-result")
            if (number == 3) {
                androidx.test.espresso.Espresso.pressBack()
                compose.onNodeWithTag("fill-review-result-sheet").assertDoesNotExist()
            } else closeFillResult()
            compose.runOnIdle {
                assertEquals(materialOffset, transcriptPage.questionScroll.value)
                assertEquals(readingOffset, transcriptPage.transcriptScroll.value)
                assertEquals(ratio, transcriptPage.panelRatio.floatValue, 0.001f)
            }
        }
        assertImmutable()
    }

}
