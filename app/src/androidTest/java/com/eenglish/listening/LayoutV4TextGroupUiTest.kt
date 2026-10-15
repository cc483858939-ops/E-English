package com.eenglish.listening

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.data.repository.OfflinePackImporter
import com.eenglish.listening.data.repository.PartRepository
import com.eenglish.listening.domain.model.ListeningPart
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.ui.components.LayoutV4GroupView
import com.eenglish.listening.ui.components.usesSharedTextMatchingLayout
import com.eenglish.listening.ui.theme.ListeningTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Focused phone-width checks against one real V4 test pack. */
class LayoutV4TextGroupUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realTextMatchingAndFillGroupsStayReadableAndSaveAnswers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = "/data/local/tmp/eenglish-v4-real-test-packs/cambridge-10-v4-test.eelpack"
        val pack = File(context.cacheDir, "c10-v4-text-${UUID.randomUUID()}.eelpack")
        val root = File(context.cacheDir, "c10-v4-text-${UUID.randomUUID()}")
        try {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $source")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                pack.outputStream().use { output -> input.copyTo(output) }
            }
            assertTrue("Stage the Cambridge 10 V4 test pack at $source", pack.length() > 0)
            assertTrue(root.mkdir())
            OfflinePackImporter(root).install(pack.inputStream())
            val repository = PartRepository(context.assets, root)
            repository.loadParts()

            suspend fun load(partId: String, number: Int): Pair<ListeningPart, JsonObject> {
                val part = repository.loadPart(partId)
                val layout = requireNotNull(repository.loadLayoutV4(part))
                val question = part.questions.single { it.number == number }
                val group = layout.getValue("groups").jsonArray.map { it.jsonObject }.single {
                    it.getValue("questionIds").jsonArray.any { id -> id.jsonPrimitive.content == question.id }
                }
                return part to group
            }

            fun render(part: ListeningPart, group: JsonObject): androidx.compose.runtime.MutableState<Map<String, String>> {
                val answers = mutableStateOf(emptyMap<String, String>())
                val questions = group.getValue("questionIds").jsonArray.map { id ->
                    part.questions.single { it.id == id.jsonPrimitive.content }
                }
                compose.activity.runOnUiThread {
                    compose.activity.setContent {
                        ListeningTheme {
                            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                                .verticalScroll(rememberScrollState())) {
                                LayoutV4GroupView(group, questions, answers.value,
                                    editable = true, submitted = false, saving = false,
                                    onSelect = { id, value -> answers.value = answers.value + (id to value) },
                                    onEdit = { id, value -> answers.value = answers.value + (id to value) },
                                    onCommit = {})
                            }
                        }
                    }
                }
                compose.waitForIdle()
                return answers
            }

            fun capture(name: String) {
                if (InstrumentationRegistry.getArguments().getString("captureTextLayout") != "true") return
                val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "eenglish-v4-$name.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/eenglish-v4-text-check")
                }
                val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                bitmap.recycle()
            }

            val (matchingPart, matchingGroup) = load("cambridge-10-test-2-part-2", 15)
            assertTrue(usesSharedTextMatchingLayout(matchingGroup))
            val answers = render(matchingPart, matchingGroup)
            val groupId = matchingGroup.getValue("groupId").jsonPrimitive.content
            compose.onAllNodesWithTag("v4-letter-match-option-$groupId-A").assertCountEquals(1)
            val optionWidth = compose.onNodeWithTag("v4-letter-match-option-text-$groupId-A")
                .fetchSemanticsNode().boundsInRoot.width
            val screenWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
            assertTrue("Shared option text must use the phone width", optionWidth > screenWidth * 0.5f)
            compose.onNodeWithTag("v4-letter-match-picker-15").performScrollTo().performClick()
            compose.onNodeWithTag("matching-option-15-A").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("v4-letter-match-picker-16").performScrollTo().performClick()
            compose.onNodeWithTag("matching-option-16-A").performClick()
            compose.runOnIdle {
                assertEquals("A", answers.value[matchingPart.questions.single { it.number == 15 }.id])
                assertEquals("A", answers.value[matchingPart.questions.single { it.number == 16 }.id])
            }

            val (fillPart, fillGroup) = load("cambridge-10-test-1-part-1", 1)
            val fillAnswers = render(fillPart, fillGroup)
            val input = compose.onNodeWithTag("v4-input-1-1").performScrollTo().assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
            assertTrue("Inline fill input must remain on screen", input.left >= 0f && input.right <= width)
            assertTrue("Inline fill input should leave room for surrounding words", input.width < width * 0.34f)
            capture("compact-text-fill")
            fun objects(element: JsonElement): List<JsonObject> = when (element) {
                is JsonObject -> listOf(element) + element.values.flatMap(::objects)
                is JsonArray -> element.flatMap(::objects)
                else -> emptyList()
            }
            for (number in 3..5) {
                val slotId = "slot-q$number-0"
                compose.onNodeWithTag("v4-input-$number-1").performScrollTo()
                val blank = compose.onNodeWithTag("v4-input-$number-1")
                    .fetchSemanticsNode().boundsInRoot
                val run = objects(fillGroup).first { block ->
                    (block["children"] as? JsonArray)?.any { it.jsonObject["slotId"]?.jsonPrimitive?.content == slotId } == true
                }
                val firstId = run.getValue("children").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
                compose.runOnIdle {
                    val text = nativeText(compose.activity, "v4-inline-text-$firstId")
                    val anchor = text.text.indexOf('\uFFFC')
                    val prefix = text.text.substring(0, anchor).trimEnd()
                    val word = prefix.indexOfLast { it.isWhitespace() } + 1
                    val line = text.layout.getLineForOffset(anchor)
                    assertEquals("Q$number must keep the preceding word beside its blank",
                        text.layout.getLineForOffset(word), line)
                    val location = IntArray(2).also(text::getLocationInWindow)
                    val wordRight = location[0] + text.totalPaddingLeft + text.layout.getPrimaryHorizontal(prefix.length)
                    val centerY = location[1] + text.totalPaddingTop + text.layout.getLineBaseline(line) +
                        (text.paint.fontMetrics.ascent + text.paint.fontMetrics.descent) / 2f
                    assertTrue("Q$number editor must remain aligned with its native text: " +
                        "wordRight=$wordRight editorLeft=${blank.left} textCenter=$centerY editorCenter=${blank.center.y}",
                        wordRight <= blank.left + 2f && abs(centerY - blank.center.y) <= 3f * compose.density.density)
                    if (number == 4) assertTrue("Q4 should keep surrounding words before the blank",
                        location[0] + text.totalPaddingLeft + text.layout.getPrimaryHorizontal(word) > 24f)
                }
            }
            capture("compact-text-fill-joined")
            compose.onNodeWithTag("v4-input-3-1").performScrollTo().performTextInput("trial")
            compose.runOnIdle {
                assertEquals("trial", fillAnswers.value[fillPart.questions.single { it.number == 3 }.id])
            }
            compose.onNodeWithTag("v4-input-1-1").performTextInput("test")
            compose.runOnIdle {
                assertEquals("test", fillAnswers.value[fillPart.questions.single { it.number == 1 }.id])
            }

            val (singlePart, singleGroup) = load("cambridge-10-test-1-part-3", 21)
            val singleAnswers = render(singlePart, singleGroup)
            val singleGroupId = singleGroup.getValue("groupId").jsonPrimitive.content
            compose.onAllNodesWithText("Q21 · 选择一项").assertCountEquals(0)
            compose.onNodeWithTag("v4-matching-choice-$singleGroupId-21-A").assertIsDisplayed()
            capture("single-choice-no-duplicate-heading")
            compose.onNodeWithTag("v4-matching-choice-$singleGroupId-21-B").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals("B", singleAnswers.value[singlePart.questions.single { it.number == 21 }.id])
            }

            val (multiPart, multiGroup) = load("cambridge-10-test-1-part-2", 11)
            val multiAnswers = render(multiPart, multiGroup)
            val multiGroupId = multiGroup.getValue("groupId").jsonPrimitive.content
            compose.onNodeWithTag("v4-multiple-choice-count-$multiGroupId")
                .assertTextEquals("已选 0/2 项")
            compose.onNodeWithTag("v4-choice-$multiGroupId-A").performScrollTo().performClick()
            compose.onNodeWithTag("v4-choice-$multiGroupId-B").performScrollTo().performClick()
            compose.onNodeWithTag("v4-multiple-choice-count-$multiGroupId")
                .assertTextEquals("已选 2/2 项")
            compose.runOnIdle {
                assertEquals(setOf("A", "B"), Grader.selection(
                    multiAnswers.value[multiPart.questions.single { it.number == 11 }.id].orEmpty()))
            }
        } finally {
            root.deleteRecursively()
            pack.delete()
        }
    }
}
