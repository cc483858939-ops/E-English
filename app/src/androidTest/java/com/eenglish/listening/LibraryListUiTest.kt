package com.eenglish.listening

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eenglish.listening.domain.model.PartSummary
import com.eenglish.listening.ui.screens.PartListScreen
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.PracticeUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryListUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun bookTestPartHierarchyAndSearchOpenOnlySelectedSummary() {
        val parts = listOf(14,17).flatMap { book -> (1..4).map { part ->
            PartSummary("cambridge-$book-test-1-part-$part",book,1,part,"Synthetic library index",10,(part-1)*10+1,part*10)
        } }
        var selected: String? = null
        compose.setContent { ListeningTheme { PartListScreen(PracticeUiState(loading=false,parts=parts)) { selected=it } } }
        compose.onNodeWithTag("book-14").performScrollTo().performClick()
        compose.onNodeWithTag("test-14-1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("test-14-1").performClick()
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("open-cambridge-14-test-1-part-3"))
        compose.onNodeWithTag("open-cambridge-14-test-1-part-3").performClick()
        compose.runOnIdle { assertEquals("cambridge-14-test-1-part-3",selected) }
        compose.onNodeWithTag("library-search").assertIsDisplayed()
        compose.onNodeWithTag("library-search").performTextInput("cambridge-17-test-1-part-4")
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("open-cambridge-17-test-1-part-4"))
        compose.onNodeWithTag("open-cambridge-17-test-1-part-4").assertIsDisplayed()
        compose.onNodeWithTag("open-cambridge-14-test-1-part-3").assertDoesNotExist()
    }
}
