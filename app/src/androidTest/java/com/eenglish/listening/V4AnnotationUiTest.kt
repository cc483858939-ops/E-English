package com.eenglish.listening

import android.content.ClipboardManager
import android.content.Context
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.action.ViewActions.click
import com.eenglish.listening.domain.model.*
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

/** Real native selection/menu gestures, isolated Room highlights, and unchanged Compose answer controls. */
class V4AnnotationUiTest {
    @get:Rule val compose = createAndroidComposeRule<AnnotationTestActivity>()
    private val app get() = compose.activity.application as TestListeningApplication
    private val partId = "v4-annotation-fixture"
    private val answers = mutableStateOf<Map<String, String>>(emptyMap())
    private val visible = mutableStateOf(true)
    private val container = mutableStateOf("paragraph")
    private val optionKind = mutableStateOf("singleChoice")
    private val imageCard = mutableStateOf(false)
    private var selections = 0

    @Before fun isolate() {
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
    }

    private fun text(id: String = "lead", value: String = "Alpha repeat before the answer", offset: Int = 20) = buildJsonObject {
        put("id", id); put("type", "text"); put("text", value)
        put("source", buildJsonObject {
            put("kind", "resourceRange"); put("resource", "content.html"); put("normalization", "htmlTextV1")
            put("sha256", "a".repeat(64)); put("startUtf16", offset); put("endUtf16", offset + value.length)
        })
    }
    private fun slot(id: String) = buildJsonObject { put("id", id); put("type", "slot"); put("slotId", id) }
    private fun question(type: QuestionType = QuestionType.TEXT_INPUT, number: Int = 1) = Question(
        "q$number", number, type, "Alpha repeat before the answer", if (type == QuestionType.TEXT_INPUT) emptyList()
        else listOf(Option("A", "Alpha first choice"), Option("B", "Beta second choice"), Option("C", "Gamma third choice")),
        if (type == QuestionType.TEXT_INPUT) "value" else "A", instructions = "Write an answer",
        wordLimit = if (type == QuestionType.TEXT_INPUT) WordLimit(1) else null,
        groupId = if (type == QuestionType.MULTIPLE_CHOICE) "multi" else null,
        groupNumbers = if (type == QuestionType.MULTIPLE_CHOICE) listOf(1, 2) else emptyList())

    private fun fillGroup(): JsonObject = buildJsonObject {
        put("groupId", "fill"); put("questionIds", buildJsonArray { add("q1") })
        put("instructions", buildJsonArray { add(text("instructions", "Select words to copy and mark", 100)) })
        put("optionPools", buildJsonArray { })
        put("slots", buildJsonArray { add(buildJsonObject {
            put("slotId", "s1"); put("questionId", "q1"); put("slotIndex", 0); put("kind", "fill")
        }) })
        put("responses", buildJsonArray { add(buildJsonObject {
            put("kind", "fill"); put("questionIds", buildJsonArray { add("q1") }); put("slotIds", buildJsonArray { add("s1") })
        }) })
        val row = buildJsonObject {
            put("id", "row"); put("type", if (container.value == "formRow") "formRow" else "paragraph")
            put("children", buildJsonArray { add(text()); add(slot("s1")); add(text("tail", "afterward", 80)) })
        }
        put("blocks", buildJsonArray {
            when (container.value) {
                "flow" -> add(buildJsonObject {
                    put("id", "flow"); put("type", "flow"); put("edges", buildJsonArray { })
                    put("nodes", buildJsonArray { add(buildJsonObject { put("id", "node"); put("children", buildJsonArray { add(row) }) }) })
                })
                "table" -> add(buildJsonObject {
                    put("id", "table"); put("type", "table"); put("rows", buildJsonArray {
                        add(buildJsonObject { put("cells", buildJsonArray {
                            add(buildJsonObject { put("colSpan", 1); put("children", buildJsonArray { add(row) }) })
                        }) })
                    })
                })
                else -> add(row)
            }
        })
    }

    private fun choiceGroup(kind: String): JsonObject = buildJsonObject {
        val ids = if (kind == "multipleChoice") listOf("q1", "q2") else listOf("q1")
        put("groupId", "choice"); put("questionIds", JsonArray(ids.map(::JsonPrimitive)))
        put("instructions", buildJsonArray { add(text("instructions", "Select words to copy and mark", 100)) })
        put("slots", buildJsonArray { ids.forEach { id -> add(buildJsonObject {
            put("slotId", "s-$id"); put("questionId", id); put("slotIndex", 0); put("kind", kind)
        }) } })
        put("responses", buildJsonArray { add(buildJsonObject {
            put("kind", kind); put("questionIds", JsonArray(ids.map(::JsonPrimitive)))
            put("slotIds", JsonArray(ids.map { JsonPrimitive("s-$it") })); put("optionPoolId", "pool"); put("selectCount", ids.size)
        }) })
        put("optionPools", buildJsonArray { add(buildJsonObject {
            put("poolId", "pool"); put("optionIds", buildJsonArray { add("A"); add("B"); add("C") })
        }) })
        put("blocks", buildJsonArray { add(buildJsonObject {
            put("id", "choices"); put("type", "choiceList"); put("optionPoolId", "pool")
            put("slotIds", JsonArray(ids.map { JsonPrimitive("s-$it") }))
        }) })
    }

    private fun show(choices: Boolean = false) {
        val repository = app.annotations
        compose.setContent {
            val highlights by repository.observeAll().collectAsState(initial = emptyList())
            val scope = rememberCoroutineScope()
            ListeningTheme {
                CompositionLocalProvider(LocalAnnotations provides AnnotationContext(partId, highlights, { changes, add ->
                    scope.launch { repository.change(changes, add) }
                })) {
                    if (visible.value) Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                        .verticalScroll(rememberScrollState())) {
                        val kind = optionKind.value
                        val questions = if (!choices) listOf(question()) else if (kind == "multipleChoice")
                            listOf(question(QuestionType.MULTIPLE_CHOICE), question(QuestionType.MULTIPLE_CHOICE, 2))
                            else listOf(question(if (kind == "matching") QuestionType.MATCHING else QuestionType.SINGLE_CHOICE))
                        val group = if (choices) choiceGroup(kind) else fillGroup()
                        val select: (String, String) -> Unit = { id, value ->
                            selections++
                            answers.value = if (kind == "multipleChoice") questions.associate { it.id to value }
                                else answers.value + (id to value)
                        }
                        if (imageCard.value) LayoutV4ImageAnswerCard(group, questions, 0, answers.value,
                            true, false, false, select, { id, value -> answers.value += id to value }, {}, {}, {}, {}, {},
                            Modifier.height(androidx.compose.ui.unit.Dp(420f)))
                        else LayoutV4GroupView(group, questions, answers.value, true, false, false, select,
                            { id, value -> answers.value += id to value }, {})
                    }
                }
            }
        }
    }

    private fun ranges(tag: String): List<Pair<Int, Int>> {
        var result = emptyList<Pair<Int, Int>>()
        compose.runOnIdle {
            val text = nativeText(compose.activity, tag).text as Spanned
            result = text.getSpans(0, text.length, BackgroundColorSpan::class.java)
                .map { text.getSpanStart(it) to text.getSpanEnd(it) }
        }
        return result
    }
    private fun waitRanges(tag: String, expected: List<Pair<Int, Int>>) {
        compose.waitUntil(10000) { ranges(tag) == expected }
    }

    @Test fun nativeMenusCopyMarkPartiallyCancelAndRestoreV4Text() {
        show()
        val tag = "v4-text-instructions"
        textAt(tag).perform(pressWord(8), selectionAction(7, 12))
        selectionMenu("复制")
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("words", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        textAt(tag).perform(pressWord(8), selectionAction(7, 12))
        selectionMenu("高亮")
        waitRanges(tag, listOf(7 to 12))
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        waitRanges(tag, listOf(7 to 12))
        textAt(tag).perform(pressWord(8), selectionAction(8, 10))
        selectionMenu("取消高亮")
        waitRanges(tag, listOf(7 to 8, 10 to 12))
    }

    @Test fun singleMultipleAndMatchingOptionsKeepAnswerTapSeparateFromSelectionAndHighlights() {
        show(choices = true)
        for (kind in listOf("singleChoice", "multipleChoice", "matching")) {
            compose.runOnIdle { optionKind.value = kind; answers.value = emptyMap() }
            val tag = if (kind == "multipleChoice") "v4-choice-text-choice-A" else "v4-choice-text-choice-1-A"
            val before = selections
            textAt(tag).perform(pressWord(5), selectionAction(3, 8))
            selectionMenu("高亮")
            waitRanges(tag, listOf(3 to 8))
            assertEquals(before, selections)
            textAt(tag).perform(tapCharacter(5))
            selectionMenu("复制整块高亮")
            assertEquals(before, selections)
            textAt(tag).perform(tapCharacter(5))
            selectionMenu("取消整块高亮")
            waitRanges(tag, emptyList())
            textAt(tag).perform(tapCharacter(5))
            compose.waitUntil(5000) { selections == before + 1 }
            assertEquals("A", answers.value["q1"])
        }
    }

    @Test fun paragraphFormFlowAndTableKeepNativeSelectionAndRealInlineEditors() {
        show()
        for (type in listOf("paragraph", "formRow", "flow", "table")) {
            compose.runOnIdle { container.value = type }
            val tag = "v4-inline-text-lead"
            textAt(tag).perform(pressWord(7), selectionAction(6, 12))
            selectionMenu("高亮")
            waitRanges(tag, listOf(6 to 12))
            compose.onNodeWithTag("v4-input-1-1").performTextReplacement("value")
            compose.waitUntil(5000) { answers.value["q1"] == "value" }
            textAt(tag).perform(tapCharacter(7))
            selectionMenu("取消整块高亮")
            waitRanges(tag, emptyList())
        }
    }

    @Test fun imageCardUsesSameCaptionHighlightSourceAndOptionsAsNormalGroup() {
        show()
        textAt("v4-inline-text-lead").perform(pressWord(7), selectionAction(6, 12))
        selectionMenu("高亮")
        compose.runOnIdle { imageCard.value = true }
        waitRanges("v4-image-card-caption", listOf(6 to 12))
        textAt("v4-image-card-caption").perform(tapCharacter(7))
        selectionMenu("取消整块高亮")
        compose.runOnIdle { imageCard.value = false }
        waitRanges("v4-inline-text-lead", emptyList())
    }
}
