package com.eenglish.listening

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    }

    @Test
    fun threePagesNavigateAndReturnWithModePreserved() {
        compose.onNodeWithText("预览答题页").performClick()
        compose.onNodeWithText("听力答题").assertIsDisplayed()
        compose.onNodeWithText("提交答案").assertIsNotEnabled()
        compose.onNodeWithText("查看原文").performClick()
        compose.onNodeWithText("听力原文").assertIsDisplayed()
        compose.onNodeWithText("英中双语").performClick()
        compose.onNodeWithText("英中双语").assertIsSelected()
        compose.onNodeWithText("返回答题页").performClick()
        compose.onNodeWithText("听力答题").assertIsDisplayed()
        compose.onNodeWithText("查看原文").performClick()
        compose.onNodeWithText("英中双语").assertIsSelected()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("试题列表").assertIsDisplayed()
    }
}
