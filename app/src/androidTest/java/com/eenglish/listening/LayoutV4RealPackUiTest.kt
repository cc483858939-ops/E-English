package com.eenglish.listening

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.data.repository.OfflinePackImporter
import com.eenglish.listening.data.repository.PartRepository
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.layout.LayoutV4AnswerStatus
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.components.LayoutV4GroupView
import com.eenglish.listening.ui.components.LocalQuestionImages
import com.eenglish.listening.ui.screens.PracticeScreen
import com.eenglish.listening.ui.screens.TranscriptScreen
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.PracticeUiState
import com.eenglish.listening.viewmodel.TranscriptMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Imports real generated test packs from app-private cache; no real pack is copied into source/assets. */
class LayoutV4RealPackUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun selectedRealPartGroupsRenderAndUseTheirDeclaredV4Interactions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = File(context.cacheDir, "v4-real-test-packs")
        val importRoot = File(context.cacheDir, "layout-v4-real-${UUID.randomUUID()}")
        val books = listOf(5, 6, 9, 10, 11, 12)
        books.forEach { book ->
            assertTrue("Missing required staged Cambridge $book real test pack at ${File(staged, "cambridge-$book-v4-test.eelpack").absolutePath}",
                File(staged, "cambridge-$book-v4-test.eelpack").isFile)
        }
        check(importRoot.mkdir())
        try {
            val importer = OfflinePackImporter(importRoot)
            books.forEach { book ->
                val index = importer.install(File(staged, "cambridge-$book-v4-test.eelpack").inputStream())
                assertEquals(2, index.schemaVersion)
                assertTrue(index.parts.all { it.layoutSha256 != null })
            }
            val repository = PartRepository(context.assets, importRoot)
            repository.loadParts()

            fun load(partId: String, questionNumber: Int): Triple<ListeningPart, JsonObject, JsonObject> = runBlocking {
                val part = repository.loadPart(partId)
                val layout = repository.loadLayoutV4(part)
                assertNotNull("V4 must load for $partId", layout)
                val question = part.questions.single { it.number == questionNumber }
                val group = layout!!.getValue("groups").jsonArray.map { it.jsonObject }.single { group ->
                    group.getValue("questionIds").jsonArray.any { it.jsonPrimitive.content == question.id }
                }
                Triple(part, layout, group)
            }

            fun question(part: ListeningPart, number: Int) = part.questions.single { it.number == number }

            fun render(part: ListeningPart, group: JsonObject): androidx.compose.runtime.MutableState<Map<String, String>> {
                val answers = mutableStateOf(emptyMap<String, String>())
                val groupQuestions = group.getValue("questionIds").jsonArray.map { id ->
                    part.questions.single { it.id == id.jsonPrimitive.content }
                }
                val imageLoader: suspend (String) -> Bitmap? = { path -> repository.readImage(part.id, path) }
                compose.activity.runOnUiThread {
                    compose.activity.setContent {
                        CompositionLocalProvider(LocalQuestionImages provides imageLoader) {
                            ListeningTheme {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    LayoutV4GroupView(group, groupQuestions, answers.value,
                                        editable = true, submitted = false, saving = false,
                                        onSelect = { id, value -> answers.value = answers.value + (id to value) },
                                        onEdit = { id, value -> answers.value = answers.value + (id to value) },
                                        onCommit = {})
                                }
                            }
                        }
                    }
                }
                compose.waitForIdle()
                return answers
            }

            fun showPracticePage(part: ListeningPart, layout: JsonObject): androidx.compose.runtime.MutableState<PracticeUiState> {
                val attempt = PracticeAttempt(
                    PracticeSession("real-v4-${UUID.randomUUID()}", part.id, SessionStatus.IN_PROGRESS,
                        1L, null, null, part.questions.size), part.questions, emptyList())
                val state = mutableStateOf(PracticeUiState(loading = false, part = part,
                    questionLayoutV4 = layout, attempt = attempt))
                val imageLoader: suspend (String) -> Bitmap? = { path -> repository.readImage(part.id, path) }
                compose.activity.runOnUiThread {
                    compose.activity.setContent {
                        CompositionLocalProvider(LocalQuestionImages provides imageLoader) {
                            ListeningTheme {
                                PracticeScreen(state.value, AudioState(ready = true),
                                    onToggle = {}, onSeek = {},
                                    onSelect = { id, value -> state.value = state.value.copy(
                                        answers = state.value.answers + (id to value)) },
                                    onBack = {}, onViewTranscript = {}, onSubmit = {}, onConfirm = {},
                                    onDismiss = {}, onNew = {}, onHistory = {},
                                    onInlineEdit = { id, value -> state.value = state.value.copy(
                                        inlineDrafts = state.value.inlineDrafts + (id to value)) },
                                    onInlineCommit = { id ->
                                        val draft = state.value.inlineDrafts[id]
                                        if (draft != null) state.value = state.value.copy(
                                            answers = state.value.answers + (id to draft),
                                            inlineDrafts = state.value.inlineDrafts - id)
                                    })
                            }
                        }
                    }
                }
                compose.waitForIdle()
                return state
            }

            fun screenBounds(tag: String, scrollTo: Boolean = true): androidx.compose.ui.geometry.Rect {
                val node = compose.onNodeWithTag(tag)
                if (scrollTo) node.performScrollTo()
                compose.waitForIdle()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                val location = IntArray(2)
                compose.activity.runOnUiThread {
                    val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
                    val composeRoot: View = if (content.childCount > 0) content.getChildAt(0) else content
                    composeRoot.getLocationOnScreen(location)
                }
                return androidx.compose.ui.geometry.Rect(
                    bounds.left + location[0], bounds.top + location[1],
                    bounds.right + location[0], bounds.bottom + location[1])
            }

            fun accessibilityScreenBounds(descriptionFragment: String): androidx.compose.ui.geometry.Rect {
                val root = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow) {
                    "No active accessibility window while locating $descriptionFragment"
                }
                val pending = ArrayDeque<AccessibilityNodeInfo>()
                pending.add(root)
                val matches = mutableListOf<android.graphics.Rect>()
                val descriptions = mutableListOf<String>()
                while (pending.isNotEmpty()) {
                    val node = pending.removeFirst()
                    val description = node.contentDescription?.toString().orEmpty()
                    if (description.isNotBlank()) descriptions += description
                    if (description.contains(descriptionFragment, ignoreCase = false)) {
                        val rect = android.graphics.Rect()
                        node.getBoundsInScreen(rect)
                        matches += rect
                    }
                    for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
                }
                assertEquals("Expected one visible accessibility node for '$descriptionFragment'; found $matches among $descriptions",
                    1, matches.size)
                val rect = matches.single()
                return androidx.compose.ui.geometry.Rect(rect.left.toFloat(), rect.top.toFloat(),
                    rect.right.toFloat(), rect.bottom.toFloat())
            }

            fun injectTouch(action: Int, x: Int, y: Int, downTime: Long, eventTime: Long): Boolean {
                val event = MotionEvent.obtain(downTime, eventTime, action, x.toFloat(), y.toFloat(), 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                return try {
                    InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)
                } finally {
                    event.recycle()
                }
            }

            fun physicalTapAt(x: Int, y: Int, evidence: String) {
                val downTime = SystemClock.uptimeMillis()
                println("PHYSICAL_TAP screenPx=($x,$y) $evidence")
                Log.i("LayoutV4PhysicalUi", "tap screenPx=($x,$y) $evidence")
                assertTrue("Touch down rejected: $evidence", injectTouch(MotionEvent.ACTION_DOWN, x, y, downTime, downTime))
                assertTrue("Touch up rejected: $evidence", injectTouch(MotionEvent.ACTION_UP, x, y, downTime, SystemClock.uptimeMillis()))
            }

            fun physicalTap(tag: String, evidence: String = tag) {
                val bounds = screenBounds(tag)
                val x = bounds.center.x.toInt()
                val y = bounds.center.y.toInt()
                physicalTapAt(x, y, "tag=$tag bounds=$bounds $evidence")
            }

            fun farthestBlankPixel(image: JsonObject): Pair<Int, Int> {
                val width = image.getValue("widthPx").jsonPrimitive.int
                val height = image.getValue("heightPx").jsonPrimitive.int
                val rects = image.getValue("overlays").jsonArray.map { it.jsonObject.getValue("rectPx").jsonObject }
                fun gap(x: Int, y: Int, rect: JsonObject): Float {
                    val left = rect.getValue("x").jsonPrimitive.int
                    val top = rect.getValue("y").jsonPrimitive.int
                    val right = left + rect.getValue("width").jsonPrimitive.int
                    val bottom = top + rect.getValue("height").jsonPrimitive.int
                    val dx = maxOf(left - x, 0, x - right).toFloat()
                    val dy = maxOf(top - y, 0, y - bottom).toFloat()
                    return kotlin.math.hypot(dx, dy)
                }
                val points = (5..95 step 5).flatMap { y -> (5..95 step 5).map { x ->
                    (width * x / 100) to (height * y / 100)
                } }
                val imageId = image.getValue("id").jsonPrimitive.content
                val best = points.maxByOrNull { (x, y) -> rects.minOfOrNull { gap(x, y, it) } ?: Float.MAX_VALUE }
                    ?: error("No candidate blank coordinate for $imageId")
                val clearance = rects.minOfOrNull { gap(best.first, best.second, it) } ?: Float.MAX_VALUE
                assertTrue("No blank image coordinate safely outside the hotspot hit regions: $imageId at $best clearance=$clearance px",
                    clearance >= 110f)
                return best
            }

            fun tapHotspot(number: Int, slotIndex: Int = 1) {
                val tag = "v4-hotspot-target-$number-$slotIndex"
                compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag(tag).performScrollTo().performClick()
            }

            fun selectFromPicker(number: Int, optionId: String) {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("matching-option-sheet-title-$number").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("matching-option-$number-$optionId").performClick()
                compose.waitForIdle()
            }

            // C5 T1 P2: exercise the real Part and layout inside the complete PracticeScreen.
            run {
                val (part, layout, group) = load("cambridge-5-test-1-part-2", 11)
                val image = group.getValue("blocks").jsonArray.map { it.jsonObject }
                    .single { it["type"]?.jsonPrimitive?.content == "image" }
                assertEquals(9, image.getValue("overlays").jsonArray.size)
                val state = showPracticePage(part, layout)
                val groupId = group.getValue("groupId").jsonPrimitive.content
                val groupTag = "layout-v4-group-$groupId"
                compose.onNodeWithTag("practice-header").assertExists()
                compose.onNodeWithTag("practice-list").assertExists()
                compose.onNodeWithTag("practice-submit").assertExists()
                compose.onNodeWithTag(groupTag).assertExists()
                val targetTag = "v4-hotspot-target-11-1"
                physicalTap(targetTag, "C5 T1 P2 Q11 base-image hotspot")
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("v4-hotspot-input-11-1").fetchSemanticsNodes().isNotEmpty()
                }
                val expected = question(part, 11).acceptableAnswers.first()
                compose.onNodeWithTag("v4-hotspot-input-11-1").performTextInput(expected)
                compose.onNodeWithText("完成").performClick()
                compose.runOnIdle { assertEquals(expected, state.value.answers[question(part, 11).id]) }

                // Tap a source-image point well clear of every hotspot to open the enlarged image.
                val imageId = image.getValue("id").jsonPrimitive.content
                val canvasTag = "v4-image-canvas-$imageId"
                val canvas = screenBounds(canvasTag)
                val blank = farthestBlankPixel(image)
                val tapX = (canvas.left + canvas.width * blank.first / image.getValue("widthPx").jsonPrimitive.int).toInt()
                val tapY = (canvas.top + canvas.height * blank.second / image.getValue("heightPx").jsonPrimitive.int).toInt()
                physicalTapAt(tapX, tapY,
                    "blank C5 image sourcePx=$blank canvas=$canvas")
                val zoomCanvasTag = "v4-image-canvas-zoom-$imageId"
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag(zoomCanvasTag).fetchSemanticsNodes().isNotEmpty()
                }

                // Inject an actual two-pointer zoom gesture, then tap the hotspot by its displayed coordinates.
                compose.onNodeWithTag(zoomCanvasTag).performTouchInput {
                    val c = center
                    println("MULTI_TOUCH_PINCH canvas=$zoomCanvasTag center=$c")
                    pinch(
                        start0 = Offset(c.x - 28f, c.y), end0 = Offset(c.x - 100f, c.y),
                        start1 = Offset(c.x + 28f, c.y), end1 = Offset(c.x + 100f, c.y),
                    )
                }
                compose.waitForIdle()
                Thread.sleep(350)
                val q11Description = "Q11 ${question(part, 11).prompt.take(48)}"
                val zoomTarget = accessibilityScreenBounds(q11Description)
                var x = zoomTarget.center.x.toInt()
                var y = zoomTarget.center.y.toInt()
                physicalTapAt(x, y, "C5 Q11 after zoom bounds=$zoomTarget")
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("v4-hotspot-input-11-1").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("关闭").performClick()
                compose.waitForIdle()

                // Pan the enlarged source image, prove the target moved, then tap its new screen position.
                val zoomImageBounds = accessibilityScreenBounds("题目图片")
                val beforePan = accessibilityScreenBounds(q11Description)
                val panDeltaPx = 240
                compose.onNodeWithTag(zoomCanvasTag).performTouchInput {
                    val c = center
                    println("PAN_ZOOMED_CANVAS tag=$zoomCanvasTag imageScreenBounds=$zoomImageBounds")
                    swipe(start = Offset(c.x - panDeltaPx / 2f, c.y),
                        end = Offset(c.x + panDeltaPx / 2f, c.y), durationMillis = 500)
                }
                Thread.sleep(500)
                compose.waitForIdle()
                x = beforePan.center.x.toInt() + panDeltaPx
                y = beforePan.center.y.toInt()
                physicalTapAt(x, y,
                    "C5 Q11 after panning right by ${panDeltaPx}px from pre-pan target=$beforePan zoomCanvas=$zoomCanvasTag")
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("v4-hotspot-input-11-1").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("关闭").performClick()

                // The transcript route's complete reading/player/panel page uses the same real Part and V4 group.
                compose.activity.runOnUiThread {
                    compose.activity.setContent {
                        CompositionLocalProvider(LocalQuestionImages provides { path -> repository.readImage(part.id, path) }) {
                            ListeningTheme {
                                TranscriptScreen(state.value.copy(inlineDrafts = emptyMap()), AudioState(ready = true),
                                    onToggle = {}, onSeek = {}, mode = TranscriptMode.ENGLISH, onModeChange = {},
                                    onBack = {}, onSelect = { id, value -> state.value = state.value.copy(
                                        answers = state.value.answers + (id to value)) })
                            }
                        }
                    }
                }
                compose.waitForIdle()
                compose.onNodeWithTag("transcript-header").assertExists()
                compose.onNodeWithTag("transcript-player").assertExists()
                compose.onNodeWithTag(groupTag).assertExists()
                compose.onNodeWithTag(canvasTag).assertExists()
            }

            // C6 T1 P1 is authoritative real data for list numbering: its first two list items
            // already contain the printed ordinals 1 and 2, while the adjacent item is a bullet.
            run {
                val (part, _, group) = load("cambridge-6-test-1-part-1", 1)
                render(part, group)
                compose.onNodeWithTag("v4-text-b-g1-q1-prefix").assertTextEquals("1")
                compose.onNodeWithTag("v4-text-b-g1-q2-prefix").assertTextEquals("2")
                compose.onNodeWithTag("v4-list-marker-b-g1-q1-row").assertDoesNotExist()
                compose.onNodeWithTag("v4-list-marker-b-g1-q2-row").assertDoesNotExist()
                compose.onNodeWithTag("v4-list-marker-b-g1-q3-row").assertTextEquals("•")
            }

            // C12 T2 P3: image single-choice options come from the Part and obey the declared pool.
            run {
                val (part, _, group) = load("cambridge-12-test-2-part-3", 26)
                val response = group.getValue("responses").jsonArray.map { it.jsonObject }.single {
                    it.getValue("questionIds").jsonArray.any { id -> id.jsonPrimitive.content == question(part, 26).id }
                }
                assertEquals("singleChoice", response.getValue("kind").jsonPrimitive.content)
                val pool = group.getValue("optionPools").jsonArray.map { it.jsonObject }
                    .single { it.getValue("poolId").jsonPrimitive.content == response.getValue("optionPoolId").jsonPrimitive.content }
                val optionId = pool.getValue("optionIds").jsonArray.first().jsonPrimitive.content
                val answers = render(part, group)
                tapHotspot(26)
                selectFromPicker(26, optionId)
                compose.runOnIdle { assertEquals(optionId, answers.value[question(part, 26).id]) }
            }

            // C9 T4 P2: matching remains a shared option pool, with each image slot opening its own picker.
            run {
                val (part, _, group) = load("cambridge-9-test-4-part-2", 11)
                val response = group.getValue("responses").jsonArray.map { it.jsonObject }.single {
                    it.getValue("kind").jsonPrimitive.content == "matching"
                }
                val pool = group.getValue("optionPools").jsonArray.map { it.jsonObject }
                    .single { it.getValue("poolId").jsonPrimitive.content == response.getValue("optionPoolId").jsonPrimitive.content }
                val optionId = pool.getValue("optionIds").jsonArray.first().jsonPrimitive.content
                val answers = render(part, group)
                tapHotspot(11)
                selectFromPicker(11, optionId)
                compose.runOnIdle { assertEquals(optionId, answers.value[question(part, 11).id]) }
                val otherOption = pool.getValue("optionIds").jsonArray[1].jsonPrimitive.content
                val replacementOption = pool.getValue("optionIds").jsonArray[2].jsonPrimitive.content

                // Reusing A from Q11 must be blocked in Q12's hotspot picker, while Q12 can
                // choose another option and Q11 can still change its answer.
                tapHotspot(12)
                compose.onNodeWithTag("matching-option-12-$optionId").assertIsNotEnabled()
                selectFromPicker(12, otherOption)
                tapHotspot(11)
                selectFromPicker(11, replacementOption)
                tapHotspot(12)
                compose.onNodeWithTag("matching-option-12-$optionId").assertIsEnabled()
                selectFromPicker(12, optionId)
                compose.runOnIdle {
                    assertEquals(replacementOption, answers.value[question(part, 11).id])
                    assertEquals(optionId, answers.value[question(part, 12).id])
                }
            }

            // C9 T4 P2 Q14-Q18 uses inline choice lists; it enforces the same forbidden reuse rule.
            run {
                val (part, _, group) = load("cambridge-9-test-4-part-2", 14)
                val groupId = group.getValue("groupId").jsonPrimitive.content
                val response = group.getValue("responses").jsonArray.map { it.jsonObject }.single {
                    it.getValue("kind").jsonPrimitive.content == "matching"
                }
                val pool = group.getValue("optionPools").jsonArray.map { it.jsonObject }
                    .single { it.getValue("poolId").jsonPrimitive.content == response.getValue("optionPoolId").jsonPrimitive.content }
                val optionId = pool.getValue("optionIds").jsonArray[0].jsonPrimitive.content
                val otherOption = pool.getValue("optionIds").jsonArray[1].jsonPrimitive.content
                val replacementOption = pool.getValue("optionIds").jsonArray[2].jsonPrimitive.content
                val answers = render(part, group)
                fun choiceTag(number: Int, id: String) = "v4-matching-choice-$groupId-$number-$id"

                compose.onNodeWithTag(choiceTag(14, optionId)).performScrollTo().performClick()
                compose.runOnIdle { assertEquals(optionId, answers.value[question(part, 14).id]) }
                compose.onNodeWithTag(choiceTag(15, optionId)).performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag(choiceTag(15, otherOption)).performScrollTo().assertIsEnabled().performClick()
                compose.runOnIdle { assertEquals(otherOption, answers.value[question(part, 15).id]) }
                compose.onNodeWithTag(choiceTag(14, replacementOption)).performScrollTo().assertIsEnabled().performClick()
                compose.runOnIdle { assertEquals(replacementOption, answers.value[question(part, 14).id]) }
                compose.onNodeWithTag(choiceTag(15, optionId)).performScrollTo().assertIsEnabled().performClick()
                compose.runOnIdle {
                    assertEquals(replacementOption, answers.value[question(part, 14).id])
                    assertEquals(optionId, answers.value[question(part, 15).id])
                }
            }

            // C11 T1 P2: map remains a side reference and declares no image hotspots.
            run {
                val (part, _, group) = load("cambridge-11-test-1-part-2", 15)
                val image = group.getValue("blocks").jsonArray.map { it.jsonObject }
                    .single { it["type"]?.jsonPrimitive?.content == "image" }
                assertTrue(image.getValue("overlays").jsonArray.isEmpty())
                render(part, group)
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithContentDescription("题目图片").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("题目图片").assertExists()
                compose.onAllNodesWithTag("v4-hotspot-target-15-1").assertCountEquals(0)
            }

            // C9 T2 P2: the reviewed no-separator pair stays one saved answer and is incomplete after one slot.
            run {
                val (part, layout, group) = load("cambridge-9-test-2-part-2", 12)
                val q = question(part, 12)
                val response = group.getValue("responses").jsonArray.map { it.jsonObject }.single {
                    it.getValue("questionIds").jsonArray.any { id -> id.jsonPrimitive.content == q.id }
                }
                val joiner = q.answerSeparator ?: response.getValue("joiner").jsonPrimitive.content
                val accepted = q.acceptableAnswers.first()
                val values = accepted.split(Regex("(?:^|\\s+)${Regex.escape(joiner)}(?:\\s+|$)", RegexOption.IGNORE_CASE), limit = 2)
                assertEquals(2, values.size)
                val answers = render(part, group)
                tapHotspot(12, 1)
                compose.onNodeWithTag("v4-hotspot-input-12-1").performTextInput(values[0].trim())
                compose.onNodeWithText("完成").performClick()
                compose.runOnIdle {
                    assertEquals(1, LayoutV4AnswerStatus.missingCount(listOf(q), layout, answers.value))
                }
                tapHotspot(12, 2)
                compose.onNodeWithTag("v4-hotspot-input-12-2").performTextInput(values[1].trim())
                compose.onNodeWithText("完成").performClick()
                compose.runOnIdle {
                    val saved = answers.value[q.id].orEmpty()
                    assertEquals("${values[0].trim()} $joiner ${values[1].trim()}", saved)
                    assertEquals(0, LayoutV4AnswerStatus.missingCount(listOf(q), layout, answers.value))
                    assertTrue(Grader.withinLimit(q, saved))
                    assertTrue(q.acceptableAnswers.any { Grader.normalize(it) == Grader.normalize(saved) })
                }
            }

            // Ordinary shared multiple-choice selection still uses the canonical sorted group encoding.
            run {
                val (part, _, group) = load("cambridge-10-test-1-part-2", 11)
                val response = group.getValue("responses").jsonArray.map { it.jsonObject }.single {
                    it.getValue("kind").jsonPrimitive.content == "multipleChoice"
                }
                val pool = group.getValue("optionPools").jsonArray.map { it.jsonObject }
                    .single { it.getValue("poolId").jsonPrimitive.content == response.getValue("optionPoolId").jsonPrimitive.content }
                val selected = response.getValue("questionIds").jsonArray.map { id ->
                    part.questions.single { it.id == id.jsonPrimitive.content }.correctAnswer.uppercase()
                }.toSet()
                val answers = render(part, group)
                selected.sorted().forEach { optionId ->
                    assertTrue(pool.getValue("optionIds").jsonArray.any { it.jsonPrimitive.content == optionId })
                    compose.onNodeWithTag("v4-choice-${group.getValue("groupId").jsonPrimitive.content}-$optionId")
                        .performScrollTo().performClick()
                }
                val firstQuestionId = response.getValue("questionIds").jsonArray.first().jsonPrimitive.content
                compose.runOnIdle { assertEquals(selected, Grader.selection(answers.value[firstQuestionId])) }
            }

            // The bundled C9 sample has different raw bytes but must use its package V4 layout when decoded equal.
            val bundledC9 = repository.loadPart("cambridge-9-test-1-part-3")
            assertNotNull(repository.loadLayoutV4(bundledC9))
        } finally {
            importRoot.deleteRecursively()
            staged.deleteRecursively()
        }
    }

}
