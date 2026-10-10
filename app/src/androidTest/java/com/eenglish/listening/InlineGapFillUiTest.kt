package com.eenglish.listening

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eenglish.listening.domain.gapfill.GapFillGroupParser
import com.eenglish.listening.domain.gapfill.QuestionDisplayItem
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.components.InlineGapFillGroup
import com.eenglish.listening.ui.theme.ListeningTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InlineGapFillUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun summaryOnceAndIndependentAnswersWithCancelAndClear() {
        val prompt = """
Questions 26–30
Complete the summary below.
Write NO MORE THAN THREE WORDS.
The warehouse received 26 _____ boxes.
Inspectors checked 27 ______ seals and recorded 28 ______ readings.
The clerk filed 29 ______ notes and printed 30 ______ tags.
        """.trimIndent()
        val questions = (26..30).map { n ->
            Question("ui-q$n", n, QuestionType.TEXT_INPUT, prompt, emptyList(), "fixture",
                wordLimit = WordLimit(3, 0))
        }
        val parsed = GapFillGroupParser.parse(questions)
        assertEquals(1, parsed.size)
        val group = (parsed.single() as QuestionDisplayItem.InlineGroup).group
        val answers = mutableStateMapOf<String, String>()
        compose.setContent {
            ListeningTheme {
                InlineGapFillGroup(group, answers, editable = true, submitted = false, saving = false,
                    onSelect = { id, value -> answers[id] = value })
            }
        }
        compose.onAllNodesWithTag("inline-summary-26").assertCountEquals(1)
        fun open(n: Int) {
            val actions = compose.onNodeWithTag("inline-summary-26").fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
            compose.runOnIdle { assertTrue(actions.single { it.label == "填写 Q$n" }.action()) }
        }
        open(26)
        compose.onNodeWithTag("gap-input-26").performTextInput("three subjects")
        compose.onNodeWithTag("gap-cancel-26").performClick()
        compose.runOnIdle { assertTrue(answers.isEmpty()) }
        open(26)
        compose.onNodeWithTag("gap-input-26").performTextInput("three subjects")
        compose.onNodeWithTag("gap-save-26").performClick()
        compose.runOnIdle {
            assertEquals("three subjects", answers["ui-q26"])
            assertFalse(answers.containsKey("ui-q27"))
        }
        open(26)
        compose.onNodeWithTag("gap-clear-26").performClick()
        compose.runOnIdle { assertEquals("", answers["ui-q26"]) }
        compose.onAllNodesWithTag("inline-summary-26").assertCountEquals(1)
    }
}
