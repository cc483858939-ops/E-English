package com.eenglish.listening

import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import com.eenglish.listening.domain.annotation.contentHash
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.components.NativeInlineGapFillView
import com.eenglish.listening.ui.components.StructuredLayoutGroup
import com.eenglish.listening.ui.theme.ListeningTheme

/** Exercises structured sidecar input through the same native EditText renderer used by both pages. */
class StructuredLayoutUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun twoSlotsTypeDirectlyAndPersistAsOneReadOnlyGradedAnswer() {
        val question = Question("ui-paired-q26", 26, QuestionType.TEXT_INPUT,
            "😀 café A\u030A 26 _____ and _____ tail", emptyList(), "north and west",
            acceptedAnswers = listOf("north and west"), wordLimit = WordLimit(2, 0), answerSeparator = "and")
        val prefix = "😀 café A\u030A "
        val suffixStart = question.prompt.indexOf(" tail")
        val htmlHash = contentHash("fixture html")
        val source = LayoutSourceTrace("content.html", htmlHash, "wrapper:0", "ui-fixture")
        val group = QuestionLayoutGroup("cambridge-5-test-1-part-3-layout-notes-q26-q26", "notesCompletion",
            listOf(question.id), mapOf(question.id to contentHash(question.prompt)), source, listOf(
                LayoutBlock("paragraph", children = listOf(
                    LayoutBlock("text", text = prefix, source = LayoutBlockSource("prompt", question.id, 0, prefix.length)),
                    LayoutBlock("blank", questionId = question.id, questionNumber = question.number, slotIndex = 0,
                        source = LayoutBlockSource("html-marker", resource = "content.html", locator = "slot:0")),
                    LayoutBlock("fixedText", text = " and ", source = LayoutBlockSource("html", resource = "content.html", locator = "connector")),
                    LayoutBlock("blank", questionId = question.id, questionNumber = question.number, slotIndex = 1,
                        source = LayoutBlockSource("html-marker", resource = "content.html", locator = "slot:1")),
                    LayoutBlock("text", text = " tail", source = LayoutBlockSource("prompt", question.id,
                        suffixStart, question.prompt.length)),
                )),
            ))
        val answers = mutableStateOf(emptyMap<String, String>())
        val submitted = mutableStateOf(false)
        compose.setContent {
            ListeningTheme {
                StructuredLayoutGroup(group, listOf(question), answers.value, editable = !submitted.value,
                    submitted = submitted.value, saving = false, dirty = false,
                    onSelect = { id, value -> answers.value = answers.value + (id to value) },
                    onEdit = { id, value -> answers.value = answers.value + (id to value) })
            }
        }

        onView(withTagValue(equalTo("inline-input-26"))).check(matches(isDisplayed()))
        onView(withTagValue(equalTo("inline-input-26-2"))).check(matches(isDisplayed()))
        onView(isAssignableFrom(NativeInlineGapFillView::class.java)).check { view, error ->
            if (error != null) throw error
            val display = (view as NativeInlineGapFillView).getChildAt(0).let { it as TextView }.text as Spanned
            assertEquals(2, display.getSpans(0, display.length, ReplacementSpan::class.java).size)
            assertEquals(2, display.count { it == '\uFFFC' })
        }
        onView(withTagValue(equalTo("inline-input-26"))).perform(click(), typeText("north"))
        onView(withTagValue(equalTo("inline-input-26-2"))).perform(click(), typeText("west"))
        compose.runOnIdle {
            assertEquals("north and west", answers.value[question.id])
            assertEquals(1, Grader.grade(listOf(question), answers.value).correctCount)
        }
        compose.runOnIdle { submitted.value = true }
        onView(withTagValue(equalTo("inline-input-26"))).check(matches(isNotEnabled()))
        onView(withTagValue(equalTo("inline-input-26-2"))).check(matches(isNotEnabled()))
        compose.onNodeWithTag("result-26").assertExists()
    }
}
