package com.eenglish.listening

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.view.WindowManager
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.model.ListeningPart
import com.eenglish.listening.ui.components.LocalAnswerSession
import com.eenglish.listening.ui.components.LocalQuestionImages
import com.eenglish.listening.ui.screens.PracticeScreen
import com.eenglish.listening.ui.screens.TranscriptScreen
import com.eenglish.listening.ui.screens.TranscriptPageState
import com.eenglish.listening.viewmodel.TranscriptMode
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.PracticeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Real C6 image/text fills in both reading routes, using the platform IME and isolated Room. */
class ImageFillCompactImeTest {
    @get:Rule val compose = createAndroidComposeRule<AnnotationTestActivity>()
    private val app get() = compose.activity.application as TestListeningApplication
    private lateinit var part: ListeningPart
    private lateinit var state: MutableState<PracticeUiState>
    private var listOffset = { 0 to 0 }
    private var toggles = 0
    private var seekDelta = 0L
    private lateinit var transcriptPage: TranscriptPageState

    private fun load(fontScale: Float = 1f, textGroup: Boolean = false, transcript: Boolean = false) {
        compose.activity.runOnUiThread {
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = File.createTempFile("image-ime-c6-", ".eelpack", context.cacheDir)
        val layout: JsonObject
        val images = mutableMapOf<String, Bitmap>()
        try {
            val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cat /data/local/tmp/eenglish-image-ime/cambridge-6-v4-test.eelpack")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).use { input ->
                staged.outputStream().use { input.copyTo(it) }
            }
            assertTrue("Stage the local C6 test pack before running this focused test", staged.length() > 0)
            ZipFile(staged).use { zip ->
                val base = "listening/cambridge-6-test-1-part-1/"
                part = PartCodec.decode(zip.getInputStream(zip.getEntry(base + "part.json")).reader().readText())
                val original = Json.parseToJsonElement(zip.getInputStream(zip.getEntry(base + "layout.json")).reader().readText()).jsonObject
                val q5 = part.questions.single { it.number == if (textGroup) 1 else 5 }
                val group = original.getValue("groups").jsonArray.single {
                    it.jsonObject.getValue("questionIds").jsonArray.any { id -> id.jsonPrimitive.content == q5.id }
                }
                layout = JsonObject(original + ("groups" to buildJsonArray { add(group) }))
                fun readImages(element: JsonElement) {
                    when (element) {
                        is JsonObject -> {
                            if (element["type"]?.jsonPrimitive?.contentOrNull == "image") {
                                val asset = element.getValue("asset").jsonPrimitive.content
                                val entry = zip.getEntry(base + asset) ?: zip.getEntry(asset)
                                images[asset] = zip.getInputStream(requireNotNull(entry)).use { requireNotNull(BitmapFactory.decodeStream(it)) }
                            }
                            element.values.forEach(::readImages)
                        }
                        is JsonArray -> element.forEach(::readImages)
                        else -> Unit
                    }
                }
                readImages(group)
            }
        } finally { staged.delete() }
        val attempt = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val id = app.practices.startNew(part)
            app.practices.all().single { it.session.id == id }
        }
        state = mutableStateOf(PracticeUiState(loading = false, part = part, attempt = attempt, questionLayoutV4 = layout))
        val loader: suspend (String) -> Bitmap? = { images[it] }
        transcriptPage = TranscriptPageState(if (textGroup) 1 else 5)
        fun commit(id: String) {
            val value = state.value.inlineDrafts[id] ?: return
            compose.activity.lifecycleScope.launch { app.practices.saveAnswer(attempt.session.id, id, value) }
            state.value = state.value.copy(answers = state.value.answers + (id to value), inlineDrafts = state.value.inlineDrafts - id)
        }
        compose.setContent {
            val density = LocalDensity.current
            val list = rememberLazyListState()
            listOffset = { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                LocalQuestionImages provides loader, LocalAnswerSession provides attempt.session.id) {
                ListeningTheme {
                    if (transcript) TranscriptScreen(state.value, AudioState(ready = true, durationMs = 365000),
                        onToggle = {}, onSeek = {}, mode = TranscriptMode.ENGLISH, onModeChange = {}, onBack = {},
                        onSelect = { _, _ -> },
                        onInlineEdit = { id, value -> state.value = state.value.copy(inlineDrafts = state.value.inlineDrafts + (id to value)) },
                        onInlineCommit = ::commit, onFlushInline = { state.value.inlineDrafts.keys.toList().forEach(::commit) },
                        pageState = transcriptPage)
                    else PracticeScreen(state.value, AudioState(ready = true, durationMs = 365000),
                        onToggle = { toggles++ }, onSeek = {}, onSelect = { _, _ -> },
                        onBack = {}, onViewTranscript = {}, onSubmit = {}, onConfirm = {}, onDismiss = {}, onNew = {}, onHistory = {},
                        onSeekBy = { seekDelta += it },
                        onInlineEdit = { id, value -> state.value = state.value.copy(inlineDrafts = state.value.inlineDrafts + (id to value)) },
                        onInlineCommit = ::commit, onFlushInline = { state.value.inlineDrafts.keys.toList().forEach(::commit) }, listState = list)
                }
            }
        }
        if (textGroup) {
            compose.onNodeWithTag("v4-input-1-1").assertExists()
            return
        }
        compose.waitUntil(10000) { compose.onAllNodes(hasTestTagPrefix("v4-image-canvas-")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTagPrefix("v4-image-canvas-")).onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("v4-image-card-input-5").assertIsDisplayed()
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag prefix $prefix") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun waitIme(visible: Boolean) {
        compose.waitUntil(10000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.let { it.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                (if (visible) it.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
                    else it.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0) } == true }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("audio-compact-time").fetchSemanticsNodes().isNotEmpty() == visible }
        compose.waitForIdle()
    }
    private fun assertAboveIme(tag: String) {
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val field = bounds(tag)
        val card = bounds("v4-image-answer-card")
        val root = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val location = IntArray(2).also { root.getLocationOnScreen(it) }
        val ime = requireNotNull(ViewCompat.getRootWindowInsets(root)).getInsets(WindowInsetsCompat.Type.ime()).bottom
        assertTrue("$tag must have a full input height: $field", field.height >= 48 * root.resources.displayMetrics.density)
        assertTrue("$tag must fit inside card $card: $field", field.top >= card.top && field.bottom <= card.bottom + 1)
        assertTrue("$tag must remain above actual keyboard", field.bottom + location[1] <= root.height - ime + location[1] + 1)
    }
    private fun saved(number: Int): String? = runBlocking(Dispatchers.IO) {
        app.practices.all().single { it.session.id == state.value.attempt!!.session.id }
            .selectedAnswers[part.questions.single { it.number == number }.id]
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureImageIme") != "true") return
        compose.waitForIdle()
        android.os.SystemClock.sleep(200)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val resolver = instrumentation.targetContext.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "eenglish-image-ime-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/eenglish-image-ime")
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun realSingleAndPairedFillsKeepFocusSaveAndRestoreHeightAfterIme() {
        load()
        compose.onNodeWithTag("v4-image-card-drag-handle").performTouchInput { swipe(center, center.copy(y = center.y - 70f), 300) }
        val originalHeight = bounds("v4-image-answer-card").height
        val originalPlayerHeight = bounds("practice-player").height
        val originalOffset = listOffset()
        compose.onNodeWithTag("v4-image-card-input-5").performClick().performTextInput("ordinary")
        waitIme(true)
        assertAboveIme("v4-image-card-input-5")
        assertTrue(bounds("practice-player").height < originalPlayerHeight)
        compose.onNodeWithTag("practice-submit").assertDoesNotExist()
        compose.onNodeWithTag("v4-image-card-next").performClick()
        waitIme(true)
        compose.onNodeWithTag("v4-image-card-input-6").assertIsFocused().performTextInput("six")
        assertEquals("ordinary", saved(5))
        compose.onNodeWithTag("v4-image-card-next").performClick()
        waitIme(true)
        compose.onNodeWithTag("v4-image-card-input-7-1").assertIsFocused().performTextInput("left")
        assertAboveIme("v4-image-card-input-7-1")
        assertAboveIme("v4-image-card-input-7-2")
        val imageBounds = compose.onAllNodes(hasTestTagPrefix("v4-image-canvas-")).onFirst().fetchSemanticsNode().boundsInRoot
        assertTrue("The actual question image must remain visible above the compact card",
            imageBounds.height > 48f && imageBounds.bottom <= bounds("v4-image-answer-card").top + 1)
        capture("paired")
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.onNodeWithTag("audio-back-5").performClick()
        compose.onNodeWithTag("audio-forward-5").performClick()
        assertEquals(1, toggles)
        assertEquals(0L, seekDelta)
        compose.onNodeWithTag("v4-image-card-input-7-1").performImeAction()
        compose.onNodeWithTag("v4-image-card-input-7-2").assertIsFocused().performTextInput("right")
        compose.onNodeWithTag("v4-image-card-input-7-2").performImeAction()
        waitIme(false)
        compose.onNodeWithTag("practice-submit").assertIsDisplayed()
        assertEquals(originalHeight, bounds("v4-image-answer-card").height, 1f)
        assertEquals(originalPlayerHeight, bounds("practice-player").height, 1f)
        assertEquals(originalOffset, listOffset())
        assertTrue(saved(7).orEmpty().contains("left") && saved(7).orEmpty().contains("right"))
        capture("restored")
    }

    @Test fun largeTextPairedFillAndRequirementDetailsDoNotLoseDraftOrFocus() {
        load(fontScale = 1.6f)
        repeat(2) { compose.onNodeWithTag("v4-image-card-next").performClick() }
        compose.onNodeWithTag("v4-image-card-input-7-2").performScrollTo().performClick().performTextInput("large")
        waitIme(true)
        assertAboveIme("v4-image-card-input-7-2")
        capture("large-text")
        compose.onNodeWithTag("v4-image-card-compact-instructions").performScrollTo().performClick()
        compose.onNodeWithText("Q7 · 作答要求").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("v4-image-card-input-7-2").performScrollTo().performClick().assertTextContains("large")
        waitIme(true)
        compose.onNodeWithTag("v4-image-card-input-7-2").performImeAction()
        waitIme(false)
        assertTrue(saved(7).orEmpty().contains("large"))
        closeSoftKeyboard()
    }

    @Test fun realTextFormInPracticeUsesCompactPlayerWithoutReplacingItsInlineFields() {
        load(textGroup = true)
        val playerHeight = bounds("practice-player").height
        compose.onNodeWithTag("v4-input-4-1").performScrollTo().performClick().performTextInput("four")
        waitIme(true)
        compose.onNodeWithTag("v4-input-4-1").assertIsFocused()
        assertTrue(bounds("practice-player").height < playerHeight)
        assertTextFieldVisible("v4-input-4-1", "practice-list")
        compose.onNodeWithTag("v4-input-4-1").performImeAction()
        compose.onNodeWithTag("v4-input-1-1").performScrollTo().performClick().performTextInput("one")
        assertTextFieldVisible("v4-input-1-1", "practice-list")
        compose.onNodeWithTag("v4-input-1-1").performImeAction()
        closeSoftKeyboard()
        waitIme(false)
        assertEquals(playerHeight, bounds("practice-player").height, 1f)
        compose.waitUntil(5000) { saved(4) == "four" && saved(1) == "one" }
    }

    @Test fun realTextFormInTranscriptCompactsChromeAndRestoresReadingPositionAndHeight() {
        load(textGroup = true, transcript = true)
        compose.onNodeWithTag("transcript-drag-handle").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.60f) }
        compose.onNodeWithTag("transcript-body").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 200f) }
        compose.waitForIdle()
        val readingOffset = transcriptPage.transcriptScroll.value
        val panelHeight = bounds("transcript-question-panel").height
        val playerHeight = bounds("transcript-player").height
        compose.onNodeWithTag("v4-input-4-1").performScrollTo().performClick().performTextInput("transcript-four")
        waitIme(true)
        compose.onNodeWithTag("transcript-language-tabs").assertDoesNotExist()
        compose.onNodeWithTag("transcript-drag-handle").assertDoesNotExist()
        assertTrue(bounds("transcript-player").height < playerHeight)
        assertEquals(bounds("transcript-question-summary").center.y, bounds("transcript-next").center.y, 2f)
        assertTextFieldVisible("v4-input-4-1", "transcript-question-body")
        assertTrue(bounds("transcript-body").height > 48f)
        capture("transcript-text")
        compose.onNodeWithTag("v4-input-4-1").performImeAction()
        closeSoftKeyboard()
        waitIme(false)
        compose.onNodeWithTag("transcript-language-tabs").assertIsDisplayed()
        compose.onNodeWithTag("transcript-drag-handle").assertIsDisplayed()
        assertEquals(panelHeight, bounds("transcript-question-panel").height, 1f)
        assertEquals(playerHeight, bounds("transcript-player").height, 1f)
        assertEquals(readingOffset, transcriptPage.transcriptScroll.value)
        compose.waitUntil(5000) { saved(4) == "transcript-four" }
    }

    private fun assertTextFieldVisible(tag: String, bodyTag: String) {
        val field = bounds(tag)
        val body = bounds(bodyTag)
        val root = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val ime = requireNotNull(ViewCompat.getRootWindowInsets(root)).getInsets(WindowInsetsCompat.Type.ime()).bottom
        assertTrue("$tag must retain its full touch target", field.height >= 48 * root.resources.displayMetrics.density)
        assertTrue("$tag must fit the scrolling question viewport", field.top >= body.top && field.bottom <= body.bottom + 1)
        assertTrue("$tag must remain above the real keyboard", field.bottom <= root.height - ime + 1)
    }
}
