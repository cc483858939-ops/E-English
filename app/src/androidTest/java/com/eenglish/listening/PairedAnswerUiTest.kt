package com.eenglish.listening

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.WordLimit
import com.eenglish.listening.ui.components.QuestionCard
import com.eenglish.listening.ui.theme.ListeningTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PairedAnswerUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun twoAnswerBoxesKeepFixedSeparatorAndBecomeReadOnlyAfterSubmission() {
        val q=libraryFixture().questions[2].copy(correctAnswer="alpha and beta",wordLimit=WordLimit(2),answerSeparator="and")
        val value=mutableStateOf(""); val submitted=mutableStateOf(false)
        compose.setContent { ListeningTheme { QuestionCard(q,value.value,!submitted.value,submitted.value) { value.value=it } } }
        compose.onNodeWithTag("input-3-1").performTextInput("alpha")
        compose.runOnIdle { assertEquals(1,Grader.missingCount(listOf(q),mapOf(q.id to value.value))) }
        compose.onNodeWithTag("input-3-2").performTextInput("beta")
        compose.runOnIdle {
            assertEquals("alpha and beta",value.value)
            assertEquals(1,Grader.grade(listOf(q),mapOf(q.id to value.value)).correctCount)
        }
        compose.onNodeWithTag("result-3").assertDoesNotExist()
        compose.runOnIdle { submitted.value=true }
        compose.onNodeWithTag("input-3-1").assertIsNotEnabled()
        compose.onNodeWithTag("input-3-2").assertIsNotEnabled()
        compose.onNodeWithTag("result-3").assertExists()
    }
}
