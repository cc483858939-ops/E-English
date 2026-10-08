package com.eenglish.listening

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun unimportedPartCannotBePracticed() {
        compose.onNodeWithText("试题列表").assertIsDisplayed()
        compose.onNodeWithText("待导入").assertIsDisplayed()
        compose.onNodeWithText("开始练习").assertIsNotEnabled()
        compose.onAllNodesWithText("Cambridge IELTS 9").assertCountEquals(1)
    }

    @Test
    fun threePagesNavigateAndReturnWithModePreserved() {
        openPractice()
        compose.onNodeWithText("听力答题").assertIsDisplayed()
        compose.onNodeWithText("提交答案").assertIsNotEnabled()
        openTranscript()
        compose.onNodeWithText("听力原文").assertIsDisplayed()
        compose.onNodeWithText("英中双语").performClick()
        compose.onNodeWithText("英中双语").assertIsSelected()
        compose.onNodeWithText("英文原文").assertIsNotSelected()
        compose.onNodeWithText("返回答题页").performClick()
        compose.onNodeWithText("听力答题").assertIsDisplayed()
        openTranscript()
        compose.onNodeWithText("英中双语").assertIsSelected()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("试题列表").assertIsDisplayed()
    }

    @Test
    fun systemBackReturnsThroughTheNavigationStack() {
        openPractice()
        openTranscript()
        pressBack()
        compose.onNodeWithText("听力答题").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("试题列表").assertIsDisplayed()
    }

    @Test
    fun activityRecreationRestoresDestinationAndTranscriptMode() {
        openPractice()
        openTranscript()
        compose.onNodeWithText("中文翻译").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("听力原文").assertIsDisplayed()
        compose.onNodeWithText("中文翻译").assertIsSelected()
        compose.onNodeWithText("英文原文").assertIsNotSelected()
        pressBack()
        compose.onNodeWithText("听力答题").assertIsDisplayed()
        openTranscript()
        compose.onNodeWithText("中文翻译").assertIsSelected()
    }

    @Test
    fun unavailableAudioAndSubmissionStayDisabledOnBothPages() {
        openPractice()
        compose.onNodeWithText("播放").assertIsNotEnabled()
        compose.onNodeWithText("提交答案").assertIsNotEnabled()
        openTranscript()
        compose.onNodeWithText("播放").assertIsNotEnabled()
        compose.onNodeWithText("音频尚未导入").assertIsDisplayed()
    }

    private fun openPractice() {
        compose.onNodeWithText("预览答题页").performScrollTo().performClick()
    }

    private fun openTranscript() {
        compose.onNodeWithText("查看原文").performScrollTo().performClick()
    }
}
