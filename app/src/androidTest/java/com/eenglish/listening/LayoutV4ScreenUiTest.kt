package com.eenglish.listening

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.components.LocalAnswerSession
import com.eenglish.listening.ui.components.LocalQuestionImages
import com.eenglish.listening.ui.screens.PracticeScreen
import com.eenglish.listening.ui.screens.TranscriptScreen
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.PracticeUiState
import com.eenglish.listening.viewmodel.TranscriptMode
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LayoutV4ScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private data class Fixture(val part: ListeningPart, val layout: JsonObject, val attempt: PracticeAttempt)

    private fun fixture(): Fixture {
        val partId = "cambridge-5-test-1-part-2"
        val questions = (11..19).map { number ->
            Question("$partId-q$number", number, QuestionType.TEXT_INPUT,
                "Question $number answer _____", emptyList(), "answer$number", listOf("answer$number"),
                "Write one word", images = if (number == 11) listOf("diagram.png") else emptyList(),
                wordLimit = WordLimit(1, 0))
        }
        val part = ListeningPart(2, partId, "IELTS", 5, 1, 2, "Part 2 fixture", "Complete the form",
            "listening/$partId/audio.mp3", "a".repeat(64),
            Transcript("Synthetic transcript", "合成原文", listOf(TranscriptSegment("Synthetic transcript", "合成原文"))),
            questions).validate()
        val questionIds = questions.map { it.id }
        val slots = questions.map { question -> buildJsonObject {
            put("slotId", "slot-q${question.number}-0"); put("questionId", question.id)
            put("slotIndex", 0); put("kind", "fill")
        } }
        val responses = questions.map { question -> buildJsonObject {
            put("kind", "fill"); put("questionIds", buildJsonArray { add(question.id) })
            put("slotIds", buildJsonArray { add("slot-q${question.number}-0") })
            put("scoring", "legacyPerQuestion")
        } }
        fun text(question: Question) = buildJsonObject {
            put("id", "text-q${question.number}"); put("type", "text"); put("textId", "t-q${question.number}")
            put("text", question.prompt); put("questionId", question.id)
        }
        fun slot(question: Question) = buildJsonObject {
            put("id", "block-slot-q${question.number}"); put("type", "slot")
            put("slotId", "slot-q${question.number}-0")
        }
        val blocks = buildList {
            add(text(questions.first()))
            add(buildJsonObject {
                put("id", "image-diagram"); put("type", "image"); put("asset", "diagram.png")
                put("widthPx", 200); put("heightPx", 80)
                put("overlays", buildJsonArray { add(buildJsonObject {
                    put("slotId", "slot-q11-0")
                    put("rectPx", buildJsonObject { put("x", 40); put("y", 25); put("width", 55); put("height", 25) })
                    put("evidence", buildJsonObject {
                        put("kind", "modelMeasurement"); put("measurementId", "fixture-q11-slot")
                        put("imageSha256", "b".repeat(64))
                    })
                }) })
                put("printedConnectors", buildJsonArray { })
            })
            questions.drop(1).forEach { question -> add(buildJsonObject {
                put("id", "row-q${question.number}"); put("type", "formRow")
                put("children", buildJsonArray { add(text(question)); add(slot(question)) })
            }) }
        }
        val groupId = "$partId-layout-q11-q19"
        val group = buildJsonObject {
            put("groupId", groupId); put("questionIds", JsonArray(questionIds.map(::JsonPrimitive)))
            put("promptHashes", buildJsonObject { questions.forEach { put(it.id, "c".repeat(64)) } })
            put("instructions", buildJsonArray { }); put("optionPools", buildJsonArray { })
            put("slots", JsonArray(slots)); put("responses", JsonArray(responses)); put("blocks", JsonArray(blocks))
        }
        val layout = buildJsonObject {
            put("schemaVersion", 4); put("partId", partId); put("basePartSha256", "d".repeat(64))
            put("groups", buildJsonArray { add(group) })
        }
        val attempt = PracticeAttempt(PracticeSession("v4-ui-attempt", partId, SessionStatus.IN_PROGRESS,
            1L, null, null, questions.size), questions, emptyList())
        return Fixture(part, layout, attempt)
    }

    @Test fun practiceV4ImageFillListEditsSavesAndShowsReadOnlySubmittedResult() {
        val f = fixture()
        val state = mutableStateOf(PracticeUiState(loading = false, part = f.part,
            questionLayoutV4 = f.layout, attempt = f.attempt,
            answers = f.part.questions.drop(1).associate { it.id to "answer${it.number}" }))
        val bitmap = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        val imageLoader: suspend (String) -> Bitmap? = { if (it == "diagram.png") bitmap else null }
        var commits = 0
        compose.activity.setContent {
            CompositionLocalProvider(LocalQuestionImages provides imageLoader,
                LocalAnswerSession provides f.attempt.session.id) {
                ListeningTheme {
                    PracticeScreen(state.value, AudioState(ready = true), {}, {}, { _, _ -> }, {}, {},
                        onSubmit = {
                            val current = state.value
                            state.value = current.copy(attempt = current.attempt!!.copy(session = current.attempt.session.copy(
                                status = SessionStatus.SUBMITTED, submittedAt = 2L, correctCount = f.part.questions.size)))
                        }, onConfirm = {}, onDismiss = {}, onNew = {}, onHistory = {},
                        onInlineEdit = { id, value -> state.value = state.value.copy(inlineDrafts = state.value.inlineDrafts + (id to value)) },
                        onInlineCommit = { id ->
                            commits++
                            val draft = state.value.inlineDrafts[id]
                            if (draft != null) state.value = state.value.copy(answers = state.value.answers + (id to draft),
                                inlineDrafts = state.value.inlineDrafts - id)
                        })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("layout-v4-group-${f.part.id}-layout-q11-q19").assertExists()
        compose.onAllNodesWithTag("v4-image-list-input-11").assertCountEquals(1)
        compose.onNodeWithTag("v4-input-11-1").assertDoesNotExist()
        compose.onNodeWithTag("v4-hotspot-target-11-1").assertDoesNotExist()
        compose.onNodeWithTag("v4-image-list-input-11").performClick().performTextInput("answer11")
        compose.onNodeWithTag("v4-image-list-input-12").performClick()
        compose.runOnIdle {
            assertEquals("answer11", state.value.answers[f.part.questions.first().id])
            assertTrue("Changing question must commit the image-list draft", commits >= 1)
        }
        compose.onNodeWithTag("v4-image-list-input-19").performScrollTo().performClick().performImeAction()
        compose.onNodeWithTag("practice-submit").performClick()
        compose.onNodeWithTag("grade-summary").assertExists()
        val resultText = compose.onNodeWithTag("v4-result-11").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertTrue("Submitted V4 fill result must include its answer and correctness: $resultText",
            resultText.contains("answer11") && resultText.contains("正确"))
        compose.onNodeWithTag("v4-image-list-input-11").assertIsNotEnabled().assertTextEquals("answer11")
    }

    @Test fun transcriptPanelUsesTheV4GroupAndFlushesDraftBeforeQuestionNavigation() {
        val f = fixture()
        val bitmap = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        val imageLoader: suspend (String) -> Bitmap? = { if (it == "diagram.png") bitmap else null }
        var flushes = 0
        compose.activity.setContent {
            CompositionLocalProvider(LocalQuestionImages provides imageLoader,
                LocalAnswerSession provides f.attempt.session.id) {
                ListeningTheme {
                    TranscriptScreen(PracticeUiState(loading = false, part = f.part, questionLayoutV4 = f.layout,
                        attempt = f.attempt), AudioState(ready = true, durationMs = 10_000), {}, {},
                        TranscriptMode.ENGLISH, {}, {}, { _, _ -> }, onInlineCommit = {}, onFlushInline = { flushes++ })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("layout-v4-group-${f.part.id}-layout-q11-q19").assertExists()
        compose.onNodeWithTag("transcript-next").performClick()
        compose.runOnIdle { assertEquals(1, flushes) }
        compose.onNodeWithTag("layout-v4-group-${f.part.id}-layout-q11-q19").assertExists()
    }
}
