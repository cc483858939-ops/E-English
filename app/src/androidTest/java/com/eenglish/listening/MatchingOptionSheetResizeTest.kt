package com.eenglish.listening

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.model.Option
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType
import com.eenglish.listening.ui.components.*
import com.eenglish.listening.ui.theme.ListeningTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class MatchingOptionSheetResizeTest {
    @get:Rule val compose = createAndroidComposeRule<AnnotationTestActivity>()
    private val app get() = compose.activity.application as TestListeningApplication
    private val open = mutableStateOf(false)
    private val answers = mutableStateOf<Map<String, String>>(emptyMap())
    private val session = mutableStateOf("first-attempt")
    private var selections = 0

    @Before fun isolate() {
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
    }

    private fun height() = compose.onNodeWithTag("matching-option-sheet-11").fetchSemanticsNode().boundsInRoot.height
    private fun ratio() = compose.onNodeWithTag("matching-option-sheet-handle").fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current
    private fun scroll() = compose.onNodeWithTag("matching-option-sheet-options").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun setRatio(value: Float) {
        compose.onNodeWithTag("matching-option-sheet-handle").performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        compose.waitForIdle()
    }
    private fun drag(delta: Float) {
        compose.onNodeWithTag("matching-option-sheet-handle").performTouchInput {
            swipe(center, center + Offset(0f, delta), 400)
        }
        compose.waitForIdle()
    }
    private fun waitClosed(number: Int = 11) {
        compose.waitUntil(10000) { compose.onAllNodesWithTag("matching-option-sheet-$number").fetchSemanticsNodes().isEmpty() }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureMatchingResize") != "true") return
        compose.waitForIdle()
        // UiAutomation captures the compositor, which can trail Compose's idle frame.
        android.os.SystemClock.sleep(200)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "eenglish-matching-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/eenglish-matching-resize")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun showSynthetic(fontScale: Float = 1f) {
        val options = ('A'..'J').map { Option(it.toString(), "Synthetic option $it with a longer description that wraps on a phone") }
        val question = Question("q11", 11, QuestionType.MATCHING, "Synthetic matching prompt", options, "A")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAnswerSession provides session.value,
                LocalDensity provides Density(density.density, fontScale)) {
                val height = rememberMatchingSheetHeight("same-group")
                ListeningTheme {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Button(onClick = { open.value = true }) { Text("Open options") }
                    }
                    if (open.value) MatchingOptionSheet(question, options, answers.value[question.id].orEmpty(), true,
                        onSelect = { selections++; answers.value += question.id to it }, onDismiss = { open.value = false }, heightState = height)
                }
            }
        }
        compose.onNodeWithText("Open options").performClick()
        compose.waitForIdle()
    }

    @Test fun handleResizesWhileOptionsOnlyScrollEvenAtListBoundaries() {
        showSynthetic()
        val initial = height()
        drag(-180f)
        assertTrue("Dragging the handle upward must grow the sheet", height() > initial + 30f)
        drag(230f)
        assertTrue("Dragging the handle downward must shrink the sheet", height() < initial - 15f)
        val fixedHeight = height()
        compose.onNodeWithTag("matching-option-sheet-options").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("Option text must have independently scrolled", scroll() > 0f)
        assertEquals(fixedHeight, height(), 1f)
        compose.onNodeWithTag("matching-option-sheet-options").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
        compose.onNodeWithTag("matching-option-sheet-options").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(fixedHeight, height(), 1f)
        compose.onNodeWithTag("matching-option-sheet-options").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100000f) }
        compose.onNodeWithTag("matching-option-sheet-options").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(fixedHeight, height(), 1f)
        assertEquals(0, selections)
        setRatio(0.30f)
        val minimum = height()
        drag(300f)
        assertEquals(minimum, height(), 1f)
        compose.onNodeWithTag("matching-option-sheet-11").assertIsDisplayed()
        setRatio(0.85f)
        val maximum = height()
        drag(-300f)
        assertEquals(maximum, height(), 1f)
    }

    @Test fun largeTextLastOptionRemainsSelectableAndOutsideBackDoNotSave() {
        showSynthetic(fontScale = 1.6f)
        setRatio(0.32f)
        compose.onNodeWithTag("matching-option-11-J").performScrollTo().assertIsDisplayed().performClick()
        waitClosed()
        assertEquals(mapOf("q11" to "J"), answers.value)
        assertEquals(1, selections)
        compose.onNodeWithText("Open options").performClick()
        compose.onNodeWithTag("matching-option-sheet-scrim").performTouchInput { click(Offset(centerX, height * 0.05f)) }
        waitClosed()
        compose.onNodeWithText("Open options").performClick()
        pressBack()
        waitClosed()
        assertEquals(1, selections)
    }

    @Test fun realC19EightOptionsPersistHeightAcrossQuestionsAndSaveThroughRepository() {
        val repository = app.practices
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = File.createTempFile("matching-c19-", ".eelpack", context.cacheDir)
        try {
            val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cat /data/local/tmp/eenglish-matching-resize/cambridge-19-v4-test.eelpack")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).use { input ->
                staged.outputStream().use { input.copyTo(it) }
            }
            assertTrue("Required real C19 pack must be staged on the disposable emulator", staged.length() > 0)
            val (part, group) = ZipFile(staged).use { zip ->
                val base = "listening/cambridge-19-test-3-part-2/"
                val part = PartCodec.decode(zip.getInputStream(zip.getEntry(base + "part.json")).reader().readText())
                val layout = Json.parseToJsonElement(zip.getInputStream(zip.getEntry(base + "layout.json")).reader().readText()).jsonObject
                val q11 = part.questions.single { it.number == 11 }
                part to layout.getValue("groups").jsonArray.map { it.jsonObject }.single {
                    it.getValue("questionIds").jsonArray.any { id -> id.jsonPrimitive.content == q11.id }
                }
            }
            assertTrue(usesSharedTextMatchingLayout(group))
            val questions = group.getValue("questionIds").jsonArray.map { id -> part.questions.single { it.id == id.jsonPrimitive.content } }
            assertEquals(8, questions.first().options.size)
            session.value = runBlocking(Dispatchers.IO) { repository.startNew(part) }
            compose.setContent {
                val scope = rememberCoroutineScope()
                ListeningTheme {
                    CompositionLocalProvider(LocalAnswerSession provides session.value) {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("matching-background")
                            .verticalScroll(rememberScrollState())) {
                            LayoutV4GroupView(group, questions, answers.value, true, false, false,
                                onSelect = { id, value -> scope.launch {
                                    repository.saveAnswer(session.value, id, value)
                                    answers.value += id to value
                                } }, onEdit = { _, _ -> }, onCommit = {})
                        }
                    }
                }
            }
            compose.onNodeWithTag("v4-letter-match-picker-11").performScrollTo().performClick()
            setRatio(0.70f)
            val expanded = ratio()
            if (InstrumentationRegistry.getArguments().getString("captureMatchingResize") == "true") {
                setRatio(0.85f)
                capture("c19-expanded")
                setRatio(expanded)
            }
            compose.onNodeWithTag("matching-option-11-H").performScrollTo().performClick()
            waitClosed()
            val q11 = questions.first { it.number == 11 }
            compose.waitUntil(10000) { answers.value[q11.id] == "H" }
            assertEquals("H", runBlocking(Dispatchers.IO) { repository.all().single { it.session.id == session.value }.selectedAnswers[q11.id] })
            compose.onNodeWithTag("v4-letter-match-picker-12").performScrollTo()
            val backgroundOffset = compose.onNodeWithTag("matching-background").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.onNodeWithTag("v4-letter-match-picker-12").performClick()
            assertEquals(expanded, ratio(), 0.01f)
            if (InstrumentationRegistry.getArguments().getString("captureMatchingResize") == "true") {
                setRatio(0.35f)
                capture("c19-compact")
            }
            pressBack()
            waitClosed(12)
            assertEquals(backgroundOffset, compose.onNodeWithTag("matching-background").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
            compose.runOnIdle { session.value = "another-attempt" }
            compose.onNodeWithTag("v4-letter-match-picker-12").performClick()
            assertEquals(0.55f, ratio(), 0.01f)
            pressBack()
            waitClosed(12)
        } finally {
            staged.delete()
        }
    }
}
