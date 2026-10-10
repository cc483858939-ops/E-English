package com.eenglish.listening

import android.widget.EditText
import android.widget.TextView
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.eenglish.listening.ui.components.NativeInlineGapFillView
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.*
import com.eenglish.listening.domain.gapfill.*
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.components.InlineGapFillGroup
import com.eenglish.listening.ui.theme.ListeningTheme
import org.junit.Rule
import org.junit.Test

/** Native EditText instrumentation: actual clicks and text input, not a semantics callback. */
class InlineGapFillUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exactlyFiveInlineEditorsAcceptTypingWithoutDialog() {
        val prompt = """
Questions 26–30
Complete the summary below.
Write NO MORE THAN THREE WORDS.
The warehouse received 26
 _____ boxes.
Inspectors checked 27 ______ seals and recorded 28 ______ readings.
The clerk filed 29 ______ notes and printed 30 ______ tags.
        """.trimIndent()
        val questions = (26..30).map { n ->
            Question("ui-q$n", n, QuestionType.TEXT_INPUT, prompt, emptyList(), "fixture",
                wordLimit = WordLimit(3, 0))
        }
        val group = (GapFillGroupParser.parse(questions).single() as QuestionDisplayItem.InlineGroup).group
        val drafts = mutableStateMapOf<String, String>()
        var submitted by mutableStateOf(false)
        compose.setContent {
            ListeningTheme {
                InlineGapFillGroup(group, drafts, editable = !submitted, submitted = submitted,
                    saving = false, onSelect = { id, value -> drafts[id] = value },
                    onEdit = { id, value -> drafts[id] = value })
            }
        }
        compose.onAllNodesWithTag("inline-summary-26").assertCountEquals(1)
        (26..30).forEach { n ->
            onView(withTagValue(equalTo("inline-input-$n"))).check(matches(isDisplayed()))
        }
        onView(isAssignableFrom(NativeInlineGapFillView::class.java)).check { view, error ->
            if (error != null) throw error
            val native = view as NativeInlineGapFillView
            val text = (native.getChildAt(0) as TextView).text as Spanned
            val anchors = text.getSpans(0, text.length, ReplacementSpan::class.java)
            assertEquals("one native text anchor per question", 5, anchors.size)
            assertTrue("no anchor may cross a newline",
                anchors.all { text.getSpanEnd(it) - text.getSpanStart(it) == 1 })
        }
        onView(withTagValue(equalTo("inline-input-26")))
            .perform(click(), replaceText("two boxes"))
            .check(matches(hasFocus()))
        onView(withTagValue(equalTo("inline-input-26"))).check { view, error ->
            if (error != null) throw error
            val editor = view as EditText
            assertEquals(editor.text.length, editor.selectionStart)
            assertTrue("IME input connection should be active",
                (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager).isActive(editor))
        }
        onView(withTagValue(equalTo("inline-input-27")))
            .perform(click(), replaceText("three"))
            .check(matches(hasFocus()))
        onView(withTagValue(equalTo("inline-input-28")))
            .perform(click(), replaceText("blue"), closeSoftKeyboard())
        compose.runOnIdle {
            assertEquals("two boxes", drafts["ui-q26"])
            assertEquals("three", drafts["ui-q27"])
            assertEquals("blue", drafts["ui-q28"])
            assertFalse(drafts.containsKey("ui-q29"))
        }
        onView(withText("Q26 · 填写答案")).check(doesNotExist())
        compose.runOnIdle { submitted = true }
        onView(withTagValue(equalTo("inline-input-26"))).check(matches(androidx.test.espresso.matcher.ViewMatchers.isNotEnabled()))
        onView(withTagValue(equalTo("inline-input-28"))).check(matches(withText("blue")))
        compose.onAllNodesWithTag("inline-summary-26").assertCountEquals(1)
    }
}
