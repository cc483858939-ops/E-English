package com.eenglish.listening

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eenglish.listening.viewmodel.PracticeViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[PracticeViewModel::class.java]

    @Test fun realPartHasTenQuestionsAndSingleSelectionsCanChange() {
        openPractice()
        for (number in 21..30) {
            compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("question-$number"))
            compose.onNodeWithTag("question-$number").assertIsDisplayed()
        }
        select(21, "A")
        compose.onNodeWithTag("option-21-A").assertIsSelected()
        select(21, "B")
        compose.onNodeWithTag("option-21-B").assertIsSelected()
        compose.onNodeWithTag("option-21-A").assertIsNotSelected()
        compose.onNodeWithText("标准答案", substring = true).assertDoesNotExist()
    }

    @Test fun navigationAndSystemBackKeepSelectedAnswer() {
        openPractice()
        select(21, "A")
        openTranscript()
        compose.onNodeWithText("听力原文").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("听力答题").assertIsDisplayed()
        compose.onNodeWithTag("option-21-A").assertIsSelected()
        pressBack()
        compose.onNodeWithText("试题列表").assertIsDisplayed()
    }

    @Test fun activityRecreationRestoresDestinationAndTranscriptMode() {
        openPractice()
        openTranscript()
        compose.onNodeWithText("中文翻译").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("听力原文").assertIsDisplayed()
        compose.onNodeWithText("中文翻译").assertIsSelected()
        compose.onNodeWithText("英文原文").assertIsNotSelected()
        pressBack()
        openTranscript()
        compose.onNodeWithText("中文翻译").assertIsSelected()
    }

    @Test fun actualMp3PlaysPausesAndSeeks() {
        openPractice()
        compose.waitUntil(15000) { vm.audio.state.value.ready }
        assertTrue(vm.audio.state.value.durationMs > 420000)
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.waitUntil(10000) { vm.audio.state.value.isPlaying && vm.audio.state.value.positionMs > 500 }
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.waitUntil(5000) { !vm.audio.state.value.isPlaying }
        compose.onNodeWithTag("audio-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(10000f) }
        compose.waitUntil(5000) { vm.audio.state.value.positionMs in 9500..10500 }
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.waitUntil(10000) { vm.audio.state.value.positionMs > 11000 }
        compose.onNodeWithTag("audio-toggle").performClick()
    }

    private fun openPractice() {
        compose.waitUntil(15000) { !vm.uiState.value.loading }
        assumeTrue("Private sample not imported", vm.uiState.value.parts.isNotEmpty())
        compose.onNodeWithText("开始练习").performScrollTo().performClick()
    }
    private fun select(number: Int, option: String) {
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("option-$number-$option"))
        compose.onNodeWithTag("option-$number-$option").performClick()
    }
    private fun openTranscript() {
        compose.onNodeWithTag("practice-list").performScrollToNode(hasText("查看原文"))
        compose.onNodeWithText("查看原文").performClick()
    }
}
