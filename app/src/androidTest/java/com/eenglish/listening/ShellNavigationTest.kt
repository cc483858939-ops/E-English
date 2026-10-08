package com.eenglish.listening

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import com.eenglish.listening.viewmodel.PracticeViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class ShellNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[PracticeViewModel::class.java]

    @Before fun resetIsolatedTestRecords() {
        compose.waitUntil(15000) { !vm.uiState.value.loading }
        val app = compose.activity.application as TestListeningApplication
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        compose.waitUntil(10000) { vm.uiState.value.history.isEmpty() }
    }

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
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("option-21-A"))
        compose.onNodeWithTag("option-21-A").assertIsSelected()
        pressBack()
        compose.onNodeWithText("试题列表").assertIsDisplayed()
    }

    @Test fun activityRecreationRestoresDestinationAndTranscriptMode() {
        openPractice()
        select(21, "A")
        openTranscript()
        compose.onNodeWithText("中文翻译").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("听力原文").assertIsDisplayed()
        compose.onNodeWithText("中文翻译").assertIsSelected()
        compose.onNodeWithText("英文原文").assertIsNotSelected()
        pressBack()
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("option-21-A"))
        compose.onNodeWithTag("option-21-A").assertIsSelected()
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

    @Test fun transcriptModesDisplayImportedTextAndKeepAnswerAndPlayer() {
        openPractice()
        select(21, "B")
        compose.waitUntil(15000) { vm.audio.state.value.ready }
        val controller = vm.audio
        compose.runOnIdle { controller.seekTo(60000) }
        openTranscript()
        compose.onNodeWithText("英文原文").performClick()
        val transcript = vm.uiState.value.part!!.transcript
        compose.onNodeWithText(transcript.english).assertExists()
        compose.onNodeWithText(transcript.chinese).assertDoesNotExist()
        compose.onNodeWithText("中文翻译").performClick()
        compose.onNodeWithText(transcript.chinese).assertExists()
        compose.onNodeWithText(transcript.english).assertDoesNotExist()
        compose.onNodeWithText("英中双语").performClick()
        compose.onNodeWithText(transcript.segments.first { it.chinese.isNotBlank() }.chinese).assertExists()
        compose.onNodeWithText(transcript.segments.last().english).performScrollTo().assertIsDisplayed()
        assertSame(controller, vm.audio)
        assertTrue(vm.audio.state.value.positionMs in 59500..60500)
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.waitUntil(10000) { controller.state.value.positionMs > 61000 }
        repeat(5) {
            pressBack()
            openTranscript()
            assertSame(controller, vm.audio)
            assertTrue(controller.state.value.positionMs >= 61000)
        }
        compose.onNodeWithTag("audio-toggle").performClick()
        pressBack()
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("option-21-B"))
        compose.onNodeWithTag("option-21-B").assertIsSelected()
    }

    @Test fun backgroundPausesAndForegroundRequiresManualResume() {
        openPractice()
        compose.waitUntil(15000) { vm.audio.state.value.ready }
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.waitUntil(10000) { vm.audio.state.value.isPlaying }
        val controller = vm.audio
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5000) { !controller.state.value.isPlaying }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertFalse(controller.state.value.isPlaying)
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.waitUntil(10000) { controller.state.value.isPlaying }
        compose.onNodeWithTag("audio-toggle").performClick()
    }

    @Test fun missingAnswerConfirmationAndRepeatedSubmitKeepOneImmutableRecord() {
        openPractice()
        val question = vm.uiState.value.questions.first()
        select(question.number, question.correctAnswer)
        compose.onNodeWithText("提交答案").performClick()
        compose.onNodeWithText("还有 9 题未作答").assertIsDisplayed()
        compose.onNodeWithText("继续答题").performClick()
        assertFalse(vm.uiState.value.submitted)
        compose.onNodeWithText("提交答案").performClick()
        compose.onNodeWithText("确认提交").performClick()
        compose.runOnIdle { vm.confirmSubmit(); vm.requestSubmit() }
        compose.waitUntil(10000) { vm.uiState.value.submitted && !vm.uiState.value.saving }
        compose.onNodeWithTag("grade-summary").assertTextEquals("正确 1 / 10 · 错误 9 · 正确率 10%")
        assertEquals(1, vm.uiState.value.history.size)
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("option-21-A"))
        compose.onNodeWithTag("option-21-A").assertIsNotEnabled()
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("result-21"))
        compose.onNodeWithTag("result-21").assertExists()
    }

    @Test fun tenOutOfTenAndEightOutOfTenAndHistoryRemainAccurate() {
        openPractice()
        val questions = vm.uiState.value.questions
        questions.forEach { select(it.number, it.correctAnswer) }
        compose.onNodeWithText("提交答案").performClick()
        compose.waitUntil(10000) { vm.uiState.value.submitted && !vm.uiState.value.saving }
        compose.onNodeWithTag("grade-summary").assertTextEquals("正确 10 / 10 · 错误 0 · 正确率 100%")
        val firstId = vm.uiState.value.attempt!!.session.id
        compose.onNodeWithText("重新练习").performClick()
        compose.waitUntil(10000) { !vm.uiState.value.saving && vm.uiState.value.attempt?.session?.id != firstId }
        assertTrue(vm.uiState.value.answers.isEmpty())
        questions.forEachIndexed { index, question ->
            select(question.number, if (index < 2) question.options.first { it.id != question.correctAnswer }.id else question.correctAnswer)
        }
        compose.onNodeWithText("提交答案").performClick()
        compose.waitUntil(10000) { vm.uiState.value.submitted && !vm.uiState.value.saving }
        compose.onNodeWithTag("grade-summary").assertTextEquals("正确 8 / 10 · 错误 2 · 正确率 80%")
        assertEquals(2, vm.uiState.value.history.size)
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("history-$firstId"))
        compose.onNodeWithTag("history-$firstId").performClick()
        compose.onNodeWithTag("grade-summary").assertTextEquals("正确 10 / 10 · 错误 0 · 正确率 100%")
        assertEquals(firstId, vm.uiState.value.attempt!!.session.id)
        compose.onNodeWithText("重新练习").performClick()
        compose.waitUntil(10000) { !vm.uiState.value.saving && !vm.uiState.value.submitted }
        val draftId = vm.uiState.value.attempt!!.session.id
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("history-$firstId"))
        compose.onNodeWithTag("history-$firstId").performClick()
        compose.onNodeWithText("继续未完成练习").performClick()
        compose.waitUntil(10000) { !vm.uiState.value.saving && !vm.uiState.value.submitted }
        assertEquals(draftId, vm.uiState.value.attempt!!.session.id)
        assertEquals(3, vm.uiState.value.history.size)
    }

    @Test fun rapidSelectionEventsAreCommittedInOrder() {
        openPractice()
        val id = vm.uiState.value.questions.first().id
        compose.runOnIdle {
            repeat(10) { vm.selectAnswer(id, "A"); vm.selectAnswer(id, "C") }
            vm.selectAnswer(id, "B")
        }
        compose.waitUntil(15000) { !vm.uiState.value.saving }
        assertEquals(mapOf(id to "B"), vm.uiState.value.answers)
    }

    private fun openPractice() {
        compose.waitUntil(15000) { !vm.uiState.value.loading }
        assumeTrue("Private sample not imported", vm.uiState.value.parts.isNotEmpty())
        compose.onNodeWithText("开始练习").performScrollTo().performClick()
        compose.waitUntil(10000) { vm.uiState.value.attempt != null && !vm.uiState.value.saving }
    }
    private fun select(number: Int, option: String) {
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("option-$number-$option"))
        compose.onNodeWithTag("option-$number-$option").performClick()
        compose.waitUntil(10000) {
            !vm.uiState.value.saving && vm.uiState.value.answers[vm.uiState.value.questions.single { it.number == number }.id] == option
        }
    }
    private fun openTranscript() {
        compose.onNodeWithTag("practice-list").performScrollToNode(hasText("查看原文"))
        compose.onNodeWithText("查看原文").performClick()
    }
}
