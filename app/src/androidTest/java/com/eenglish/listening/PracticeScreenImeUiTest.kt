package com.eenglish.listening

import android.widget.EditText
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.domain.annotation.contentHash
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.screens.PracticeScreen
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.PracticeUiState
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PracticeScreenImeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun focusedStructuredFieldScrollsIntoVisibleAreaWhenImeOpens() {
        val question = Question("ime-q31", 31, QuestionType.TEXT_INPUT,
            "According to the speaker, the result is 31 _____.", emptyList(), "confirmed",
            wordLimit = WordLimit(1, 0))
        val group = QuestionLayoutGroup("ime-layout-q31", "sentenceCompletion", listOf(question.id),
            mapOf(question.id to contentHash(question.prompt)),
            LayoutSourceTrace("fixture.html", contentHash("fixture"), "wrapper:0", "ui-fixture"),
            listOf(LayoutBlock("paragraph", children = listOf(
                LayoutBlock("text", "According to the speaker, the result is "),
                LayoutBlock("blank", questionId = question.id, questionNumber = question.number, slotIndex = 0),
                LayoutBlock("text", "."),
            ))))
        val part = ListeningPart(2, "ime-fixture", "IELTS", 5, 1, 4, "Part 4", "Complete the sentence.",
            "listening/ime-fixture/audio.mp3", "0".repeat(64),
            Transcript("fixture transcript", "测试文本", listOf(TranscriptSegment("fixture transcript", "测试文本"))),
            listOf(question))
        val attempt = PracticeAttempt(
            PracticeSession("ime-attempt", part.id, SessionStatus.IN_PROGRESS, 1L, null, null, 1),
            listOf(question), emptyList())
        val state = PracticeUiState(loading = false, part = part,
            questionLayout = QuestionLayoutDocument(1, part.id, "0".repeat(64), listOf(group)), attempt = attempt)

        compose.activity.runOnUiThread {
            compose.activity.setContent {
                ListeningTheme {
                    PracticeScreen(state, AudioState(), {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        compose.waitForIdle()

        val field = onView(withTagValue(equalTo("inline-input-31")))
        field.check(matches(isDisplayed())).perform(click())
        compose.waitUntil(5000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        field.check(matches(isDisplayed()))
        compose.runOnIdle {
            assertTrue("The focused answer field should remain visible above the IME", fieldIsFullyVisible())
        }
        field.perform(closeSoftKeyboard())
    }

    @Test fun lowerFieldsInMultiQuestionGroupStayVisibleWhileFocusMovesAndImeCloses() {
        // Match the production input resize mode while using MainActivity's real edge-to-edge window.
        compose.activity.runOnUiThread {
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val questions = (7..10).map { number ->
            val prompt = "The visitor for question $number must check in at reception: _____ before the session."
            Question("ime-q$number", number, QuestionType.TEXT_INPUT, prompt, emptyList(), "location",
                wordLimit = WordLimit(1, 0))
        }
        val blocks = questions.map { question ->
            LayoutBlock("paragraph", children = listOf(
                LayoutBlock("text", "The visitor for question ${question.number} must check in at reception: "),
                LayoutBlock("blank", questionId = question.id, questionNumber = question.number, slotIndex = 0),
                LayoutBlock("text", " before the session begins."),
            ))
        }
        val part = ListeningPart(2, "ime-multi-fixture", "IELTS", 5, 1, 4, "Part 4", "Complete the sentences.",
            "listening/ime-multi-fixture/audio.mp3", "0".repeat(64),
            Transcript("fixture transcript", "测试文本", listOf(TranscriptSegment("fixture transcript", "测试文本"))),
            questions)
        val group = QuestionLayoutGroup("ime-layout-q7-q10", "sentenceCompletion", questions.map { it.id },
            questions.associate { it.id to contentHash(it.prompt) },
            LayoutSourceTrace("fixture.html", contentHash("fixture"), "wrapper:0", "ui-fixture"), blocks)
        val baseState = PracticeUiState(loading = false, part = part,
            questionLayout = QuestionLayoutDocument(1, part.id, "0".repeat(64), listOf(group)),
            attempt = PracticeAttempt(PracticeSession("ime-multi-attempt", part.id,
                SessionStatus.IN_PROGRESS, 1L, null, null, questions.size), questions, emptyList()))
        val answers = mutableStateOf(emptyMap<String, String>())

        compose.activity.runOnUiThread {
            compose.activity.setContent {
                ListeningTheme {
                    PracticeScreen(
                        state = baseState.copy(answers = answers.value), audio = AudioState(),
                        onToggle = {}, onSeek = {}, onSelect = { id, value -> answers.value = answers.value + (id to value) },
                        onBack = {}, onViewTranscript = {}, onSubmit = {}, onConfirm = {}, onDismiss = {},
                        onNew = {}, onHistory = {},
                        onInlineEdit = { id, value -> answers.value = answers.value + (id to value) },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("practice-submit").assertExists()
        val initialPlayerBounds = compose.onNodeWithTag("practice-player").fetchSemanticsNode().boundsInRoot
        val fields = (7..10).associateWith { number -> onView(withTagValue(equalTo("inline-input-$number"))) }
        fields.getValue(9).check(matches(isDisplayed()))
        assertFieldFullyVisible(9)

        // Reproduce the reported action: Q9 is visible with the keyboard closed,
        // then focusing it opens the IME and must not jump to the group's Q7 header.
        fields.getValue(9).perform(click())
        fields.getValue(9).check { view, error ->
            if (error != null) throw error
            val edit = view as EditText
            val location = IntArray(2)
            edit.getLocationOnScreen(location)
            val otherFocus = (7..10).mapNotNull { number ->
                compose.activity.window.decorView.findViewWithTag<EditText>("inline-input-$number")
                    ?.takeIf { it.hasFocus() }?.let { "Q$number" }
            }.joinToString()
            assertTrue("Q9 EditText should receive focus (enabled=${edit.isEnabled}, focusable=${edit.isFocusable}, " +
                "shown=${edit.isShown}, rect=${location[0]},${location[1]} ${edit.width}x${edit.height}, " +
                "currentlyFocused=$otherFocus)", edit.hasFocus())
        }
        waitForIme(true)
        waitForSubmitVisible(false)
        compose.onNodeWithTag("practice-submit").assertDoesNotExist()
        compose.onNodeWithTag("audio-compact-time").assertExists()
        compose.onNodeWithTag("audio-progress").assertDoesNotExist()
        assertTrue("Typing should release the player's extra row for the question",
            compose.onNodeWithTag("practice-player").fetchSemanticsNode().boundsInRoot.height < initialPlayerBounds.height)
        assertFieldVisibleAboveKeyboardAndBelowPlayer(9)
        fields.getValue(9).perform(replaceText("nine"))

        fields.getValue(7).perform(click())
        assertFieldVisibleAboveKeyboardAndBelowPlayer(7)
        fields.getValue(7).perform(replaceText("seven"))
        fields.getValue(8).perform(click())
        assertFieldVisibleAboveKeyboardAndBelowPlayer(8)
        fields.getValue(8).perform(replaceText("eight"))

        // Expose the last editor before tapping it. Native bounds alone do not include the
        // Compose viewport's clip, so a click on its off-screen center can hit the keyboard.
        val lowerEdit = compose.activity.window.decorView.findViewWithTag<EditText>("inline-input-10")!!
        val lowerLocation = IntArray(2).also { lowerEdit.getLocationOnScreen(it) }
        val windowFrame = android.graphics.Rect().also { compose.activity.window.decorView.getWindowVisibleDisplayFrame(it) }
        val overflow = (lowerLocation[1] + lowerEdit.height - windowFrame.bottom + 24).coerceAtLeast(0)
        if (overflow > 0) compose.onNodeWithTag("practice-list")
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, overflow.toFloat()) }
        compose.waitForIdle()
        fields.getValue(10).perform(click())
        fields.getValue(10).check { view, error ->
            if (error != null) throw error
            assertTrue("Q10 must receive focus before checking keyboard avoidance", view.hasFocus())
        }
        assertFieldVisibleAboveKeyboardAndBelowPlayer(10)
        fields.getValue(10).perform(replaceText("ten"))
        fields.getValue(9).perform(click())
        assertFieldVisibleAboveKeyboardAndBelowPlayer(9)
        fields.getValue(9).check { view, error ->
            if (error != null) throw error
            assertTrue(view is EditText)
            assertTrue((view as EditText).text.toString() == "nine")
        }

        fields.getValue(9).perform(closeSoftKeyboard())
        waitForIme(false)
        waitForSubmitVisible(true)
        compose.onNodeWithTag("practice-submit").assertExists()
        compose.onNodeWithTag("audio-progress").assertExists()
        assertFieldFullyVisible(9)
        val finalPlayerBounds = compose.onNodeWithTag("practice-player").fetchSemanticsNode().boundsInRoot
        assertTrue("The player position must not move as the list exposes focused fields",
            finalPlayerBounds == initialPlayerBounds)
        compose.runOnIdle {
            assertTrue("Q7 answer must remain saved while focus moves", answers.value["ime-q7"] == "seven")
            assertTrue("Q8 answer must remain saved while focus moves", answers.value["ime-q8"] == "eight")
            assertTrue("Q9 answer must remain saved while focus moves", answers.value["ime-q9"] == "nine")
            assertTrue("Q10 answer must remain saved while focus moves", answers.value["ime-q10"] == "ten")
        }
    }

    private fun waitForIme(visible: Boolean) {
        compose.waitUntil(5000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
        }
    }

    private fun waitForSubmitVisible(visible: Boolean) {
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("practice-submit").fetchSemanticsNodes().isNotEmpty() == visible
        }
    }

    private fun assertFieldVisibleAboveKeyboardAndBelowPlayer(number: Int) {
        waitForIme(true)
        val edit = compose.activity.window.decorView.findViewWithTag<EditText>("inline-input-$number")
            ?: throw AssertionError("Q$number EditText was not attached")
        // Focus/IME scrolling is asynchronous; assert the final viewport, not the first click frame.
        compose.waitUntil(5000) {
            val visible = android.graphics.Rect()
            val frame = android.graphics.Rect()
            compose.activity.window.decorView.getWindowVisibleDisplayFrame(frame)
            edit.getGlobalVisibleRect(visible) && visible.height() == edit.height && visible.bottom <= frame.bottom
        }
        val rect = android.graphics.Rect()
        val visibleFrame = android.graphics.Rect()
        val full = edit.getGlobalVisibleRect(rect) && rect.height() == edit.height
        compose.activity.window.decorView.getWindowVisibleDisplayFrame(visibleFrame)
        val composeRoot = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val rootLocation = IntArray(2)
        composeRoot.getLocationOnScreen(rootLocation)
        val playerBottom = rootLocation[1] + compose.onNodeWithTag("practice-player")
            .fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Q$number must remain wholly visible above the keyboard", full && rect.bottom <= visibleFrame.bottom)
        assertTrue("Q$number must remain below the fixed audio player", rect.top >= playerBottom)
    }

    private fun assertFieldFullyVisible(number: Int) {
        val edit = compose.activity.window.decorView.findViewWithTag<EditText>("inline-input-$number")
            ?: throw AssertionError("Q$number EditText was not attached")
        val rect = android.graphics.Rect()
        assertTrue("Q$number must remain visible after the keyboard closes",
            edit.getGlobalVisibleRect(rect) && rect.height() == edit.height)
    }

    private fun fieldIsFullyVisible(): Boolean {
        val edit = compose.activity.window.decorView.findViewWithTag<android.widget.EditText>("inline-input-31")
            ?: return false
        val rect = android.graphics.Rect()
        return edit.getGlobalVisibleRect(rect) && rect.height() == edit.height
    }
}
