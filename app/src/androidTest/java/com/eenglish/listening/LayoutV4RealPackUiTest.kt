package com.eenglish.listening

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
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
import com.eenglish.listening.ui.components.usesImageFillAnswerList
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
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = File(targetContext.cacheDir, "v4-real-test-packs-${UUID.randomUUID()}")
        val importRoot = File(targetContext.cacheDir, "layout-v4-real-${UUID.randomUUID()}")
        val books = listOf(5, 6, 9, 10, 11, 12, 16)
        val privateDevicePacks = "/data/local/tmp/eenglish-v4-real-test-packs"
        check(staged.mkdirs() || staged.isDirectory)
        books.forEach { book ->
            val name = "cambridge-$book-v4-test.eelpack"
            val destination = File(staged, name)
            val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cat $privateDevicePacks/$name")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            assertTrue("Missing required staged Cambridge $book real test pack at $privateDevicePacks/$name",
                destination.isFile && destination.length() > 0)
        }
        check(importRoot.mkdir())
        try {
            val importer = OfflinePackImporter(importRoot)
            books.forEach { book ->
                val index = importer.install(File(staged, "cambridge-$book-v4-test.eelpack").inputStream())
                assertEquals(2, index.schemaVersion)
                assertTrue(index.parts.all { it.layoutSha256 != null })
            }
            val repository = PartRepository(targetContext.assets, importRoot)
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

            fun collectBlocks(element: JsonElement, type: String, result: MutableList<JsonObject>) {
                when (element) {
                    is JsonObject -> {
                        if (element["type"]?.jsonPrimitive?.contentOrNull == type) result += element
                        element.values.forEach { collectBlocks(it, type, result) }
                    }
                    is JsonArray -> element.forEach { collectBlocks(it, type, result) }
                    else -> Unit
                }
            }

            fun imageBlock(group: JsonObject): JsonObject {
                val images = mutableListOf<JsonObject>()
                collectBlocks(group["instructions"] ?: JsonArray(emptyList()), "image", images)
                collectBlocks(group["blocks"] ?: JsonArray(emptyList()), "image", images)
                return images.single()
            }

            fun assertImageFillRows(part: ListeningPart, group: JsonObject) {
                assertTrue("Expected data-driven image-fill mode for ${group.getValue("groupId")}",
                    usesImageFillAnswerList(group))
                val questionIds = group.getValue("questionIds").jsonArray.map { it.jsonPrimitive.content }
                questionIds.forEach { questionId ->
                    val q = part.questions.single { it.id == questionId }
                    compose.onAllNodesWithTag("v4-image-list-row-${q.number}").assertCountEquals(1)
                    val slots = group.getValue("slots").jsonArray.map { it.jsonObject }
                        .filter { it.getValue("questionId").jsonPrimitive.content == questionId }
                        .sortedBy { it.getValue("slotIndex").jsonPrimitive.int }
                    assertTrue("No fill slot for Q${q.number}", slots.isNotEmpty())
                    slots.forEach { slot ->
                        val slotIndex = slot.getValue("slotIndex").jsonPrimitive.int
                        val inputTag = if (slots.size == 1) "v4-image-list-input-${q.number}"
                            else "v4-image-list-input-${q.number}-slot${slotIndex + 1}"
                        compose.onAllNodesWithTag(inputTag).assertCountEquals(1)
                        compose.onNodeWithTag("v4-input-${q.number}-${slotIndex + 1}").assertDoesNotExist()
                    }
                }
                val image = imageBlock(group)
                val imageId = image.getValue("id").jsonPrimitive.content
                compose.onAllNodesWithTag("v4-image-canvas-$imageId").assertCountEquals(1)
                compose.onAllNodesWithContentDescription("题目图片").assertCountEquals(1)
                image.getValue("overlays").jsonArray.forEach { overlayElement ->
                    val overlay = overlayElement.jsonObject
                    val slot = group.getValue("slots").jsonArray.map { it.jsonObject }.single {
                        it.getValue("slotId").jsonPrimitive.content == overlay.getValue("slotId").jsonPrimitive.content
                    }
                    val q = part.questions.single { it.id == slot.getValue("questionId").jsonPrimitive.content }
                    val slotNumber = slot.getValue("slotIndex").jsonPrimitive.int + 1
                    compose.onNodeWithTag("v4-hotspot-target-${q.number}-$slotNumber").assertDoesNotExist()
                    compose.onNodeWithTag("v4-hotspot-target-zoom-${q.number}-$slotNumber").assertDoesNotExist()
                    compose.onNodeWithTag("v4-hotspot-input-${q.number}-$slotNumber").assertDoesNotExist()
                }
            }

            fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it) }

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

            fun showPracticePage(part: ListeningPart, layout: JsonObject,
                retainedState: PracticeUiState? = null): androidx.compose.runtime.MutableState<PracticeUiState> {
                val attempt = retainedState?.attempt ?: PracticeAttempt(
                    PracticeSession("real-v4-${UUID.randomUUID()}", part.id, SessionStatus.IN_PROGRESS,
                        1L, null, null, part.questions.size), part.questions, emptyList())
                val state = mutableStateOf(retainedState ?: PracticeUiState(loading = false, part = part,
                    questionLayoutV4 = layout, attempt = attempt))
                val imageLoader: suspend (String) -> Bitmap? = { path -> repository.readImage(part.id, path) }
                fun commitDraft(questionId: String) {
                    val draft = state.value.inlineDrafts[questionId]
                    if (draft != null) state.value = state.value.copy(answers = state.value.answers + (questionId to draft),
                        inlineDrafts = state.value.inlineDrafts - questionId)
                }
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
                                    onInlineCommit = ::commitDraft,
                                    onFlushInline = { state.value.inlineDrafts.keys.toList().forEach(::commitDraft) })
                            }
                        }
                    }
                }
                compose.waitForIdle()
                return state
            }

            fun scrollPracticeToGroup(group: JsonObject) {
                val tag = "layout-v4-group-${group.getValue("groupId").jsonPrimitive.content}"
                compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertExists()
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

            fun physicallyTapImageHotspotAndVerifyPlainZoom(part: ListeningPart, group: JsonObject,
                questionNumber: Int, transform: Boolean = false) {
                val q = question(part, questionNumber)
                val image = imageBlock(group)
                val slot = group.getValue("slots").jsonArray.map { it.jsonObject }.single {
                    it.getValue("questionId").jsonPrimitive.content == q.id &&
                        it.getValue("slotIndex").jsonPrimitive.int == 0
                }
                val overlay = image.getValue("overlays").jsonArray.map { it.jsonObject }.single {
                    it.getValue("slotId").jsonPrimitive.content == slot.getValue("slotId").jsonPrimitive.content
                }
                val rect = overlay.getValue("rectPx").jsonObject
                val imageId = image.getValue("id").jsonPrimitive.content
                val canvasTag = "v4-image-canvas-$imageId"
                val bounds = screenBounds(canvasTag)
                val sourceX = rect.getValue("x").jsonPrimitive.int + rect.getValue("width").jsonPrimitive.int / 2f
                val sourceY = rect.getValue("y").jsonPrimitive.int + rect.getValue("height").jsonPrimitive.int / 2f
                val x = (bounds.left + bounds.width * sourceX / image.getValue("widthPx").jsonPrimitive.int).toInt()
                val y = (bounds.top + bounds.height * sourceY / image.getValue("heightPx").jsonPrimitive.int).toInt()
                physicalTapAt(x, y, "${group.getValue("groupId").jsonPrimitive.content} Q$questionNumber image-only hotspot tap")
                val zoomTag = "v4-image-canvas-zoom-$imageId"
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag(zoomTag).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("v4-hotspot-input-$questionNumber-1").assertDoesNotExist()
                compose.onNodeWithTag("v4-hotspot-target-zoom-$questionNumber-1").assertDoesNotExist()
                if (transform) {
                    compose.onNodeWithTag(zoomTag).performTouchInput {
                        val c = center
                        pinch(start0 = Offset(c.x - 28f, c.y), end0 = Offset(c.x - 90f, c.y),
                            start1 = Offset(c.x + 28f, c.y), end1 = Offset(c.x + 90f, c.y))
                        swipe(start = Offset(c.x - 100f, c.y), end = Offset(c.x + 100f, c.y), durationMillis = 450)
                    }
                    compose.waitForIdle()
                }
                compose.onNodeWithText("关闭 · 双指缩放与平移").performClick()
                compose.onAllNodesWithTag(zoomTag).assertCountEquals(0)
            }

            fun saveDeviceScreenshot(name: String) {
                val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                val output = File(requireNotNull(targetContext.getExternalFilesDir(null)), "layout-v4-pilot/$name.png")
                check(output.parentFile?.mkdirs() == true || output.parentFile?.isDirectory == true)
                output.outputStream().use { stream ->
                    assertTrue("Failed to encode screenshot $output", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
                bitmap.recycle()
                Log.i("LayoutV4PilotScreenshot", output.absolutePath)
                println("PILOT_SCREENSHOT ${output.absolutePath}")
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

            // C5 T1 P2: retain its printed form rows while moving all inputs below the clean image.
            run {
                val (part, layout, group) = load("cambridge-5-test-1-part-2", 11)
                val image = imageBlock(group)
                assertEquals(9, image.getValue("overlays").jsonArray.size)
                val state = showPracticePage(part, layout)
                val groupId = group.getValue("groupId").jsonPrimitive.content
                val groupTag = "layout-v4-group-$groupId"
                compose.onNodeWithTag("practice-header").assertExists()
                compose.onNodeWithTag("practice-list").assertExists()
                compose.onNodeWithTag("practice-submit").assertExists()
                compose.onNodeWithTag(groupTag).assertExists()
                compose.onNodeWithTag(groupTag).performScrollTo()
                assertImageFillRows(part, group)
                compose.onNodeWithText("点击图片放大").assertExists()
                val formRows = group.getValue("blocks").jsonArray.map { it.jsonObject }
                    .filter { it["type"]?.jsonPrimitive?.content == "formRow" }
                assertEquals(9, formRows.size)
                formRows.forEach { row ->
                    compose.onNodeWithTag("v4-form-row-${row.getValue("id").jsonPrimitive.content}").assertExists()
                    row.getValue("children").jsonArray.map { it.jsonObject }.forEach { child ->
                        if (child["type"]?.jsonPrimitive?.content == "text") {
                            compose.onNodeWithTag("v4-text-${child.getValue("id").jsonPrimitive.content}").assertExists()
                        }
                    }
                }
                physicallyTapImageHotspotAndVerifyPlainZoom(part, group, questionNumber = 11, transform = true)

                val q11 = question(part, 11)
                compose.onNodeWithTag("v4-image-list-input-11").performScrollTo().performClick()
                    .performTextInput("form-row-answer")
                compose.onNodeWithTag("v4-image-list-input-11").performImeAction()
                compose.runOnIdle { assertEquals("form-row-answer", state.value.answers[q11.id]) }

                // The transcript route's complete reading/player/panel page uses the same real Part and V4 group.
                compose.activity.runOnUiThread {
                    compose.activity.setContent {
                        CompositionLocalProvider(LocalQuestionImages provides { path -> repository.readImage(part.id, path) }) {
                            ListeningTheme {
                                TranscriptScreen(state.value.copy(inlineDrafts = emptyMap()), AudioState(ready = true),
                                    onToggle = {}, onSeek = {}, mode = TranscriptMode.ENGLISH, onModeChange = {},
                                    onBack = {}, onSelect = { id, value -> state.value = state.value.copy(
                                        answers = state.value.answers + (id to value)) },
                                    onInlineEdit = { id, value -> state.value = state.value.copy(
                                        inlineDrafts = state.value.inlineDrafts + (id to value)) },
                                    onInlineCommit = { id ->
                                        val draft = state.value.inlineDrafts[id]
                                        if (draft != null) state.value = state.value.copy(
                                            answers = state.value.answers + (id to draft), inlineDrafts = state.value.inlineDrafts - id)
                                    })
                            }
                        }
                    }
                }
                compose.waitForIdle()
                compose.onNodeWithTag("transcript-header").assertExists()
                compose.onNodeWithTag("transcript-player").assertExists()
                compose.onNodeWithTag(groupTag).assertExists()
                assertImageFillRows(part, group)
                compose.onNodeWithTag("v4-image-list-input-11").assertTextEquals("form-row-answer")
            }

            // C6 T1 P1: ordinary and paired fills share one question-keyed answer state.
            run {
                val (part, layout, group) = load("cambridge-6-test-1-part-1", 5)
                val state = showPracticePage(part, layout)
                scrollPracticeToGroup(group)
                assertImageFillRows(part, group)

                val q5 = question(part, 5)
                val q5Tag = "v4-image-list-input-5"
                compose.onNodeWithTag(q5Tag).performScrollTo().performClick().performTextInput("ordinary-answer")
                compose.onNodeWithTag(q5Tag).performImeAction()
                compose.runOnIdle { assertEquals("ordinary-answer", state.value.answers[q5.id]) }

                val q7 = question(part, 7)
                val groupQuestions = group.getValue("questionIds").jsonArray.map { id ->
                    part.questions.single { it.id == id.jsonPrimitive.content }
                }
                val firstTag = "v4-image-list-input-7-slot1"
                val secondTag = "v4-image-list-input-7-slot2"
                val response = group.getValue("responses").jsonArray.map { it.jsonObject }.single {
                    it.getValue("questionIds").jsonArray.any { id -> id.jsonPrimitive.content == q7.id }
                }
                val joiner = q7.answerSeparator ?: response.getValue("joiner").jsonPrimitive.content
                compose.onNodeWithTag(firstTag).performScrollTo().performClick().performTextInput("left-answer")
                compose.onNodeWithTag(firstTag).performImeAction()
                compose.onNodeWithTag(secondTag).assertIsFocused()
                compose.runOnIdle {
                    assertEquals(3, LayoutV4AnswerStatus.missingCount(groupQuestions, layout, state.value.answers))
                    assertEquals(4, Grader.grade(groupQuestions, state.value.answers).totalCount)
                }
                compose.onNodeWithTag(secondTag).performTextInput("right-answer")
                compose.onNodeWithTag(secondTag).performImeAction()
                compose.runOnIdle {
                    assertEquals("left-answer $joiner right-answer", state.value.answers[q7.id])
                    assertEquals(2, LayoutV4AnswerStatus.missingCount(groupQuestions, layout, state.value.answers))
                    assertEquals(4, Grader.grade(groupQuestions, state.value.answers).totalCount)
                }
                compose.onNodeWithTag(secondTag).performScrollTo().performTextClearance()
                compose.onNodeWithTag(secondTag).performImeAction()
                compose.runOnIdle {
                    assertEquals(3, LayoutV4AnswerStatus.missingCount(groupQuestions, layout, state.value.answers))
                    assertEquals(1, Grader.grade(listOf(q7), state.value.answers.filterKeys { it == q7.id }).totalCount)
                }
            }

            // C16 T2 P3: the flow remains intact above the question-numbered fill list.
            run {
                val (part, layout, group) = load("cambridge-16-test-2-part-3", 25)
                val state = showPracticePage(part, layout)
                scrollPracticeToGroup(group)
                assertImageFillRows(part, group)
                val flows = mutableListOf<JsonObject>()
                collectBlocks(group, "flow", flows)
                assertEquals(1, flows.size)
                val flowId = flows.single().getValue("id").jsonPrimitive.content
                compose.onNodeWithTag("v4-flow-$flowId").assertExists()
                val flowText = mutableListOf<JsonObject>()
                collectBlocks(flows.single(), "text", flowText)
                flowText.forEach { text ->
                    compose.onNodeWithTag("v4-text-${text.getValue("id").jsonPrimitive.content}").assertExists()
                }

                physicallyTapImageHotspotAndVerifyPlainZoom(part, group, questionNumber = 25)
                val q25 = question(part, 25)
                val q25Tag = "v4-image-list-input-25"
                compose.onNodeWithTag(q25Tag).performScrollTo().performClick().performTextInput("flow-answer")
                compose.onNodeWithTag(q25Tag).performImeAction()
                compose.runOnIdle { assertEquals("flow-answer", state.value.answers[q25.id]) }
            }

            // C11 T3 P3 Q21-Q26: the original pilot continues through the generic data route.
            run {
                val (part, layout, group) = load("cambridge-11-test-3-part-3", 21)
                val groupId = group.getValue("groupId").jsonPrimitive.content
                assertEquals("cambridge-11-test-3-part-3-story-drawing-changes-q21-q26", groupId)
                assertEquals((21..26).map { question(part, it).id }, group.getValue("questionIds").jsonArray.map {
                    it.jsonPrimitive.content
                })
                val image = group.getValue("blocks").jsonArray.map { it.jsonObject }
                    .single { it["type"]?.jsonPrimitive?.content == "image" }
                assertEquals("question-image-01.jpg", image.getValue("asset").jsonPrimitive.content)
                assertEquals(1356, image.getValue("widthPx").jsonPrimitive.int)
                assertEquals(482, image.getValue("heightPx").jsonPrimitive.int)
                assertEquals(6, image.getValue("overlays").jsonArray.size)
                assertEquals(setOf("4d8b7c3868d36607f6fb74eab75c791f7c2f6d6c7635987bfbf2e1ef59f9ca47"),
                    image.getValue("overlays").jsonArray.map { it.jsonObject.getValue("evidence").jsonObject
                        .getValue("imageSha256").jsonPrimitive.content }.toSet())
                val packFolder = importer.installed().getValue(11)
                val rawImage = File(importRoot, "$packFolder/listening/${part.id}/${image.getValue("asset").jsonPrimitive.content}")
                    .readBytes()
                assertEquals("The installed pack image must match the reviewed V4 source hash",
                    "4d8b7c3868d36607f6fb74eab75c791f7c2f6d6c7635987bfbf2e1ef59f9ca47", sha256(rawImage))

                val groupTag = "layout-v4-group-$groupId"
                var state = showPracticePage(part, layout)
                compose.onNodeWithTag(groupTag).performScrollTo().assertExists()
                assertImageFillRows(part, group)
                compose.onNodeWithText("点击图片放大").assertExists()
                (21..26).forEach { number ->
                    compose.onAllNodesWithTag("v4-image-list-row-$number").assertCountEquals(1)
                    compose.onAllNodesWithTag("v4-image-list-input-$number").assertCountEquals(1)
                    compose.onNodeWithTag("v4-hotspot-target-$number-1").assertDoesNotExist()
                }
                compose.onNodeWithTag("v4-hotspot-input-21-1").assertDoesNotExist()
                val canvasTag = "v4-image-canvas-${image.getValue("id").jsonPrimitive.content}"
                compose.onAllNodesWithTag(canvasTag).assertCountEquals(1)
                compose.onAllNodesWithContentDescription("题目图片").assertCountEquals(1)
                compose.onNodeWithTag("v4-image-list-input-21").assertIsDisplayed()
                saveDeviceScreenshot("c11-t3-p3-practice-idle")

                // Tap the actual Q21 rectangle in screen coordinates. It must open the plain
                // image viewer, not route to the legacy hotspot answer dialog.
                val q21 = question(part, 21)
                val q21Overlay = image.getValue("overlays").jsonArray.map { it.jsonObject }.single { overlay ->
                    overlay.getValue("slotId").jsonPrimitive.content == "slot-q21-0"
                }
                val rect = q21Overlay.getValue("rectPx").jsonObject
                val canvas = screenBounds(canvasTag)
                val srcX = rect.getValue("x").jsonPrimitive.int + rect.getValue("width").jsonPrimitive.int / 2f
                val srcY = rect.getValue("y").jsonPrimitive.int + rect.getValue("height").jsonPrimitive.int / 2f
                val tapX = (canvas.left + canvas.width * srcX / image.getValue("widthPx").jsonPrimitive.int).toInt()
                val tapY = (canvas.top + canvas.height * srcY / image.getValue("heightPx").jsonPrimitive.int).toInt()
                physicalTapAt(tapX, tapY, "C11 Q21 source hotspot rectangle $rect canvas=$canvas")
                val zoomCanvasTag = "v4-image-canvas-zoom-${image.getValue("id").jsonPrimitive.content}"
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag(zoomCanvasTag).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("v4-hotspot-input-21-1").assertDoesNotExist()
                (21..26).forEach { number -> compose.onNodeWithTag("v4-hotspot-target-zoom-$number-1").assertDoesNotExist() }
                compose.onAllNodesWithTag(canvasTag).assertCountEquals(1)
                compose.onAllNodesWithTag(zoomCanvasTag).assertCountEquals(1)
                compose.onNodeWithTag(zoomCanvasTag).performTouchInput {
                    val c = center
                    pinch(start0 = Offset(c.x - 28f, c.y), end0 = Offset(c.x - 90f, c.y),
                        start1 = Offset(c.x + 28f, c.y), end1 = Offset(c.x + 90f, c.y))
                    swipe(start = Offset(c.x - 100f, c.y), end = Offset(c.x + 100f, c.y), durationMillis = 450)
                }
                compose.waitForIdle()
                compose.onNodeWithTag(zoomCanvasTag).assertExists()
                compose.onNodeWithText("关闭 · 双指缩放与平移").performClick()
                compose.onAllNodesWithTag(zoomCanvasTag).assertCountEquals(0)
                compose.onAllNodesWithTag(canvasTag).assertCountEquals(1)

                val q21Tag = "v4-image-list-input-21"
                val q22Tag = "v4-image-list-input-22"
                compose.onNodeWithTag(q21Tag).performScrollTo().performClick().performTextInput("pilot21")
                compose.onNodeWithTag(q21Tag).performImeAction()
                compose.onNodeWithTag(q22Tag).assertIsFocused().assertIsDisplayed()
                compose.onNodeWithTag(q22Tag).performTextInput("pilot22")
                compose.waitForIdle()
                compose.onNodeWithTag(q22Tag).assertTextEquals("pilot22")
                saveDeviceScreenshot("c11-t3-p3-practice-typing-q22")
                // The existing page action flushes inline drafts before navigation.
                compose.onNodeWithText("查看原文").performClick()
                compose.runOnIdle {
                    assertEquals("pilot21", state.value.answers[q21.id])
                    assertEquals("pilot22", state.value.answers[question(part, 22).id])
                    assertTrue(state.value.inlineDrafts.isEmpty())
                }

                compose.activity.runOnUiThread { compose.activity.setContent { Text("pilot-route-away") } }
                compose.waitForIdle()
                state = showPracticePage(part, layout, state.value)
                compose.onNodeWithTag(q21Tag).performScrollTo().assertTextEquals("pilot21")
                compose.onNodeWithTag(q22Tag).performScrollTo().assertTextEquals("pilot22")

                compose.onNodeWithTag(q21Tag).performClick().performTextClearance()
                compose.onNodeWithTag(q21Tag).performImeAction()
                compose.runOnIdle {
                    assertEquals("", state.value.answers[q21.id])
                    assertEquals(1, LayoutV4AnswerStatus.missingCount(listOf(q21), layout, state.value.answers))
                }

                // The transcript panel uses the same pilot renderer and remains editable.
                compose.activity.runOnUiThread {
                    compose.activity.setContent {
                        CompositionLocalProvider(LocalQuestionImages provides { path -> repository.readImage(part.id, path) }) {
                            ListeningTheme {
                                TranscriptScreen(state.value, AudioState(ready = true, durationMs = 10_000),
                                    onToggle = {}, onSeek = {}, mode = TranscriptMode.ENGLISH, onModeChange = {},
                                    onBack = {}, onSelect = { id, value -> state.value = state.value.copy(
                                        answers = state.value.answers + (id to value)) },
                                    onInlineEdit = { id, value -> state.value = state.value.copy(
                                        inlineDrafts = state.value.inlineDrafts + (id to value)) },
                                    onInlineCommit = { id ->
                                        val draft = state.value.inlineDrafts[id]
                                        if (draft != null) state.value = state.value.copy(
                                            answers = state.value.answers + (id to draft), inlineDrafts = state.value.inlineDrafts - id)
                                    })
                            }
                        }
                    }
                }
                compose.waitForIdle()
                compose.onNodeWithTag("transcript-header").assertExists()
                compose.onNodeWithTag(groupTag).assertExists()
                compose.onNodeWithTag(q22Tag).performScrollTo().assertTextEquals("pilot22")
                compose.onNodeWithTag("v4-image-list-input-23").performScrollTo().performClick()
                    .performTextInput("transcript-edit")
                compose.runOnIdle { assertEquals("transcript-edit", state.value.inlineDrafts[question(part, 23).id]) }

                val retainedAnswers = state.value.answers + state.value.inlineDrafts
                val grade = Grader.grade(part.questions, retainedAnswers)
                val currentAttempt = requireNotNull(state.value.attempt)
                val submittedState = state.value.copy(answers = retainedAnswers, inlineDrafts = emptyMap(),
                    attempt = currentAttempt.copy(session = currentAttempt.session.copy(
                        status = SessionStatus.SUBMITTED, submittedAt = 2L, correctCount = grade.correctCount)))
                compose.activity.runOnUiThread {
                    compose.activity.setContent {
                        CompositionLocalProvider(LocalQuestionImages provides { path -> repository.readImage(part.id, path) }) {
                            ListeningTheme {
                                PracticeScreen(submittedState, AudioState(ready = true), {}, {}, { _, _ -> }, {}, {},
                                    onSubmit = {}, onConfirm = {}, onDismiss = {}, onNew = {}, onHistory = {})
                            }
                        }
                    }
                }
                compose.waitForIdle()
                compose.onNodeWithTag("grade-summary").assertTextContains(
                    "正确 ${grade.correctCount} / ${grade.totalCount}", substring = true)
                compose.onNodeWithTag(q22Tag).performScrollTo().assertIsNotEnabled().assertTextEquals("pilot22")
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
                assertImageFillRows(part, group)
                val firstTag = "v4-image-list-input-12-slot1"
                val secondTag = "v4-image-list-input-12-slot2"
                compose.onNodeWithTag(firstTag).performClick().performTextInput(values[0].trim())
                compose.onNodeWithTag(firstTag).performImeAction()
                compose.onNodeWithTag(secondTag).assertIsFocused()
                compose.runOnIdle {
                    assertEquals(1, LayoutV4AnswerStatus.missingCount(listOf(q), layout, answers.value))
                }
                compose.onNodeWithTag(secondTag).performTextInput(values[1].trim())
                compose.onNodeWithTag(secondTag).performImeAction()
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
