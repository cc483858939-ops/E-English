package com.eenglish.listening

import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.data.repository.PracticeRepository
import com.eenglish.listening.domain.gapfill.SharedMatchingGroupParser
import com.eenglish.listening.domain.gapfill.SharedMatchingQuestionGroup
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.components.SharedMatchingGroupView
import com.eenglish.listening.ui.theme.ListeningTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/** Real View/Compose interaction coverage for the Matching answer projection. */
class MatchingInlineUiTest {
    @get:Rule val compose = createAndroidComposeRule<AnnotationTestActivity>()

    private val app get() = compose.activity.application as TestListeningApplication
    private val repository: PracticeRepository get() = app.practices
    private lateinit var questions: List<Question>
    private lateinit var testPart: ListeningPart
    private lateinit var group: SharedMatchingQuestionGroup
    private lateinit var groupState: MutableState<SharedMatchingQuestionGroup>
    private lateinit var sessionId: String
    private lateinit var answerState: MutableState<Map<String, String>>
    private lateinit var editableState: MutableState<Boolean>
    private lateinit var submittedState: MutableState<Boolean>
    private val pendingSelections = ConcurrentHashMap<String, String>()

    private enum class LayoutMode { INLINE, QUESTION_ROWS, SHARED_PROMPT_FALLBACK }

    @Before fun cleanIsolatedFixture() {
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        beginFixture(LayoutMode.INLINE)
        render()
    }

    @Test fun inlineSelectionIsIsolatedFullTextEditablePersistentAndRestorable() {
        assertNotNull(group.inlineSlots)
        assertEquals(4, group.inlineSlots!!.size)
        onView(withTagValue(equalTo(promptTag()))).check { view, error ->
            if (error != null) throw error
            assertTrue("the shared source prompt is a single visible native view", view.isShown)
        }
        inspectInlineAnchors()
        // Tapping ordinary text is a normal text tap and must not open the picker.
        textAt(promptTag()).perform(tapCharacter(group.prompt.indexOf("archive")))
        compose.onNodeWithTag("matching-option-sheet-37").assertDoesNotExist()
        assertTrue(answerState.value.isEmpty())

        // Each native ReplacementSpan opens the sheet for its own question number.
        tapSlot(38)
        compose.onNodeWithTag("matching-option-sheet-title-38").assertExists()
        pressBack()
        compose.onNodeWithTag("matching-option-sheet-38").assertDoesNotExist()

        tapSlot(37)
        compose.onNodeWithTag("matching-option-sheet-title-37").assertExists()
        compose.onNodeWithTag("matching-option-37-A")
            .assertContentDescriptionEquals("A — Synthetic north wing option")
        compose.onNodeWithTag("matching-option-37-B")
            .assertContentDescriptionEquals("B — Synthetic quiet room option")
        compose.onNodeWithTag("matching-option-37-C")
            .assertContentDescriptionEquals("C — Synthetic garden entrance option")
        compose.onNodeWithTag("matching-option-37-H").performScrollTo().assertIsDisplayed()
        selectOption(37, "A")
        assertEquals(mapOf(qid(37) to "A"), answerState.value)
        assertEquals(answerState.value, savedAnswers())

        // Repeated choices are allowed: another question can keep A independently.
        tapSlot(38)
        selectOption(38, "A")
        tapSlot(39)
        selectOption(39, "C")
        assertEquals(mapOf(qid(37) to "A", qid(38) to "A", qid(39) to "C"), answerState.value)
        assertEquals(answerState.value, savedAnswers())

        // Reopen Q37 and change it; Q38/Q39 stay independent and Q40 remains unanswered.
        tapSlot(37)
        compose.onNodeWithTag("matching-option-sheet-title-37").assertExists()
        selectOption(37, "B")
        assertEquals(mapOf(qid(37) to "B", qid(38) to "A", qid(39) to "C"), answerState.value)
        assertEquals(answerState.value, savedAnswers())

        val submitted = runBlocking(Dispatchers.IO) { repository.submit(sessionId) }
        assertEquals(2, submitted.correctCount)
        compose.runOnIdle {
            submittedState.value = true
            editableState.value = false
        }
        compose.onNodeWithText("Q37 你的答案：B · 标准答案：A").assertExists()
        tapSlot(40)
        compose.onNodeWithTag("matching-option-sheet-40").assertDoesNotExist()

        // Simulate re-entering the submitted attempt from its persisted answers.
        val restored = runBlocking(Dispatchers.IO) {
            repository.all().single { it.session.id == sessionId }.selectedAnswers
        }
        compose.runOnIdle { answerState.value = restored }
        assertEquals(mapOf(qid(37) to "B", qid(38) to "A", qid(39) to "C"), answerState.value)
        assertEquals(answerState.value, savedAnswers())

        val nextSession = runBlocking(Dispatchers.IO) { repository.startNew(testPart) }
        assertNotEquals(sessionId, nextSession)
        assertTrue(runBlocking(Dispatchers.IO) {
            repository.all().single { it.session.id == nextSession }.selectedAnswers.isEmpty()
        })
    }

    @Test fun outsideTapAndSystemBackDismissPickerWithoutChangingAnswers() {
        tapSlot(37)
        compose.onNodeWithTag("matching-option-sheet-title-37").assertExists()
        tapOutsideSheet()
        waitForPickerDismissed(37)
        compose.onNodeWithTag("matching-option-sheet-37").assertDoesNotExist()
        assertTrue(answerState.value.isEmpty())

        tapSlot(37)
        compose.onNodeWithTag("matching-option-sheet-title-37").assertExists()
        pressBack()
        waitForPickerDismissed(37)
        compose.onNodeWithTag("matching-option-sheet-37").assertDoesNotExist()
        assertTrue(answerState.value.isEmpty())
    }

    @Test fun allThreeLayoutsCanSelectAndSaveAnswers() {
        for (mode in LayoutMode.values()) {
            beginFixture(mode)
            assertEquals(mode == LayoutMode.INLINE, group.inlineSlots != null)
            assertEquals(mode != LayoutMode.SHARED_PROMPT_FALLBACK, group.questionRows != null)
            if (mode == LayoutMode.INLINE) tapSlot(37)
            else compose.onNodeWithTag("matching-answer-37").performClick()
            compose.onNodeWithTag("matching-option-sheet-title-37").assertExists()
            selectOption(37, "D")
            assertEquals(mapOf(qid(37) to "D"), answerState.value)
            assertEquals(answerState.value, savedAnswers())
        }
    }

    private val prompt: String get() = group.prompt

    private fun beginFixture(mode: LayoutMode) {
        val partId = "synthetic-matching-${mode.name.lowercase().replace('_', '-')}"
        val prompt = promptFor(mode)
        questions = (37..40).mapIndexed { index, number ->
            Question(
                id = "$partId-q$number",
                number = number,
                type = QuestionType.MATCHING,
                prompt = prompt,
                options = options(),
                correctAnswer = listOf("A", "A", "C", "D")[index],
            )
        }
        testPart = ListeningPart(
            schemaVersion = 1,
            id = partId,
            examType = "IELTS",
            book = 5,
            test = 1,
            part = 4,
            title = "Synthetic Matching UI fixture",
            instructions = "Choose one synthetic option.",
            audioPath = "listening/$partId/audio.mp3",
            audioSha256 = "0".repeat(64),
            transcript = Transcript("Synthetic transcript", "合成测试文本",
                listOf(TranscriptSegment("Synthetic transcript", "合成测试文本"))),
            questions = questions,
        ).validate()
        group = requireNotNull(SharedMatchingGroupParser.parseAtStart(questions))
        sessionId = runBlocking(Dispatchers.IO) { repository.resumeOrCreate(testPart) }
        if (::groupState.isInitialized) {
            compose.runOnIdle {
                groupState.value = group
                answerState.value = emptyMap()
                editableState.value = true
                submittedState.value = false
            }
            compose.waitForIdle()
        } else {
            groupState = mutableStateOf(group)
            answerState = mutableStateOf(emptyMap())
            editableState = mutableStateOf(true)
            submittedState = mutableStateOf(false)
        }
        pendingSelections.clear()
    }

    private fun render() {
        compose.setContent {
            ListeningTheme {
                SharedMatchingGroupView(
                    group = groupState.value,
                    answers = answerState.value,
                    editable = editableState.value,
                    submitted = submittedState.value,
                    onSelect = { questionId, optionId ->
                        answerState.value = answerState.value + (questionId to optionId)
                        pendingSelections[questionId] = optionId
                    },
                )
            }
        }
    }

    private fun selectOption(number: Int, optionId: String) {
        compose.onNodeWithTag("matching-option-$number-$optionId").performClick()
        waitForPickerDismissed(number)
        val questionId = qid(number)
        val selected = requireNotNull(pendingSelections.remove(questionId)) {
            "Q$number option selection did not reach the UI callback"
        }
        runBlocking(Dispatchers.IO) { repository.saveAnswer(sessionId, questionId, selected) }
    }

    private fun tapOutsideSheet() {
        val metrics = compose.activity.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.15f
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val properties = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coordinates = MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 1,
                arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            try {
                check(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(80)
        send(MotionEvent.ACTION_UP)
        SystemClock.sleep(400)
    }

    private fun waitForPickerDismissed(number: Int) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("matching-option-sheet-$number").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun promptFor(mode: LayoutMode): String = when (mode) {
        LayoutMode.INLINE -> """Questions 37-40
Match each description with one of the synthetic options below.
37. The 🌐 archive is beside the tall windows _____
38. The quiet room is above the main reading area _____
39. The garden entrance is close to the western path _____
40. The study desk faces the central notice board _____"""
        LayoutMode.QUESTION_ROWS -> """Questions 37-40
Match each description with one of the synthetic options below.
37. The 🌐 archive is beside the tall windows
38. The quiet room is above the main reading area
39. The garden entrance is close to the western path
40. The study desk faces the central notice board"""
        LayoutMode.SHARED_PROMPT_FALLBACK -> """Questions 37-40
Match each description with one of the synthetic options below.
The set describes four places inside a fictional learning centre.
Choose an option for each question; an option may be used more than once."""
    }

    private fun options() = listOf(
        Option("A", "Synthetic north wing option"),
        Option("B", "Synthetic quiet room option"),
        Option("C", "Synthetic garden entrance option"),
        Option("D", "Synthetic central study option"),
        Option("E", "Synthetic east corridor option"),
        Option("F", "Synthetic upper gallery option"),
        Option("G", "Synthetic lower reading option"),
        Option("H", "Synthetic west courtyard option"),
    )

    private fun qid(number: Int) = questions.single { it.number == number }.id
    private fun promptTag() = "shared-matching-prompt-37"

    private fun tapSlot(number: Int) {
        onView(withTagValue(equalTo(promptTag()))).perform(object : ViewAction {
            override fun getDescription() = "Tap the actual inline answer span for Q$number"
            override fun getConstraints(): Matcher<View> = isAssignableFrom(TextView::class.java)
            override fun perform(controller: UiController, view: View) {
                val text = view as TextView
                val slot = requireNotNull(group.inlineSlots).single { it.number == number }
                val path = Path()
                text.layout.getSelectionPath(slot.start, slot.end, path)
                val bounds = RectF()
                path.computeBounds(bounds, true)
                assertTrue("Q$number inline span must have a visible hit target", bounds.width() > 1f && bounds.height() > 1f)
                GeneralClickAction(Tap.SINGLE, { floatArrayOf(bounds.centerX(), bounds.centerY()) },
                    Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, 0).perform(controller, view)
            }
        })
    }

    private fun inspectInlineAnchors() {
        onView(withTagValue(equalTo(promptTag()))).check { view, error ->
            if (error != null) throw error
            val text = (view as TextView).text as Spanned
            val spans = text.getSpans(0, text.length, ReplacementSpan::class.java)
            assertEquals("one native inline anchor per question", 4, spans.size)
            val ranges = spans.map { text.getSpanStart(it) to text.getSpanEnd(it) }.sortedBy { it.first }
            assertEquals(group.inlineSlots!!.map { it.start to it.end }.sortedBy { it.first }, ranges)
            assertTrue("prompt remains a single original text document", text.toString() == group.prompt)
        }
    }

    private fun savedAnswers(): Map<String, String> = runBlocking(Dispatchers.IO) {
        repository.all().single { it.session.id == sessionId }.selectedAnswers
    }

}
