package com.eenglish.listening

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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

        compose.setContent {
            ListeningTheme {
                PracticeScreen(state, AudioState(), {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
            }
        }

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

    private fun fieldIsFullyVisible(): Boolean {
        val edit = compose.activity.window.decorView.findViewWithTag<android.widget.EditText>("inline-input-31")
            ?: return false
        val rect = android.graphics.Rect()
        return edit.getGlobalVisibleRect(rect) && rect.height() == edit.height
    }
}
