package com.eenglish.listening

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eenglish.listening.ui.components.QuestionCard
import com.eenglish.listening.ui.theme.ListeningTheme
import org.junit.Rule
import org.junit.Test

class LibraryQuestionUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun textAnswerCanBeEditedAndSubmittedQuestionIsReadOnly() {
        val q=libraryFixture().questions[2]
        val value=mutableStateOf("")
        val submitted=mutableStateOf(false)
        compose.setContent { ListeningTheme { QuestionCard(q,value.value,!submitted.value,submitted.value) { value.value=it } } }
        compose.onNodeWithTag("input-3").performTextInput("alpha")
        compose.onNodeWithTag("input-3").assertTextContains("alpha")
        compose.runOnIdle { submitted.value=true }
        compose.onNodeWithTag("input-3").assertIsNotEnabled()
        compose.onNodeWithTag("result-3").assertIsDisplayed()
    }
    @Test fun multipleChoicesAreBoundedAndCanBeChangedWithoutLeakingAnswers() {
        val q=libraryFixture().questions[0]
        val value=mutableStateOf("")
        compose.setContent { ListeningTheme { QuestionCard(q,value.value,true) { value.value=it } } }
        compose.onNodeWithTag("option-1-A").performClick().assertIsSelected()
        compose.onNodeWithTag("option-1-C").performClick().assertIsSelected()
        compose.onNodeWithTag("option-1-B").assertIsNotEnabled()
        compose.onNodeWithTag("option-1-A").performClick().assertIsNotSelected()
        compose.onNodeWithTag("option-1-B").performClick().assertIsSelected()
        compose.onNodeWithText("标准答案",substring=true).assertDoesNotExist()
    }
}
