package com.eenglish.listening

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eenglish.listening.ui.screens.PartListScreen
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.PracticeUiState
import org.junit.Rule
import org.junit.Test

class EmptyPartScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unimportedPartsCannotBePracticed() {
        compose.setContent { ListeningTheme { PartListScreen(PracticeUiState(loading = false)) { error("No part exists") } } }
        compose.onNodeWithText("暂无已导入试题").assertIsDisplayed()
        compose.onNodeWithText("开始练习").assertDoesNotExist()
        compose.onNodeWithText("Cambridge IELTS 9").assertDoesNotExist()
    }
}
