package com.eenglish.listening

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.geometry.Offset
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.eenglish.listening.viewmodel.PracticeViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import androidx.test.espresso.action.ViewActions.click

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

    @Test fun fixedPlayerAndSubmitRemainUsableAtQuestionThirty() {
        openPractice()
        select(21, "A")
        compose.waitUntil(15000) { vm.audio.state.value.ready }
        val controller = vm.audio
        val playerBounds = compose.onNodeWithTag("practice-player").fetchSemanticsNode().boundsInRoot
        for (number in 21..30) {
            compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("question-$number"))
            compose.onNodeWithTag("question-$number").assertIsDisplayed()
            compose.onNodeWithTag("audio-toggle").assertIsDisplayed()
            compose.onNodeWithTag("audio-time").assertIsDisplayed()
            compose.onNodeWithTag("audio-seek").assertIsDisplayed()
            compose.onNodeWithText("查看原文").assertIsDisplayed()
            compose.onNodeWithTag("practice-submit").assertIsDisplayed()
            assertEquals(playerBounds, compose.onNodeWithTag("practice-player").fetchSemanticsNode().boundsInRoot)
        }
        select(30, "B")
        assertFixedRegionsDoNotOverlap()
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.waitUntil(10000) { controller.state.value.isPlaying && controller.state.value.positionMs > 500 }
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.waitUntil(5000) { !controller.state.value.isPlaying }
        // Exercise the touch drag, in addition to the existing accessibility seek test.
        compose.onNodeWithTag("audio-seek").performTouchInput {
            swipe(Offset(width * 0.1f, centerY), Offset(width * 0.6f, centerY), 600)
        }
        compose.waitUntil(5000) { controller.state.value.positionMs > 100000 }
        val position = controller.state.value.positionMs
        val answers = vm.uiState.value.answers.toMap()
        val sessionId = vm.uiState.value.attempt!!.session.id
        openTranscript()
        pressBack()
        assertSame(controller, vm.audio)
        assertEquals(answers, vm.uiState.value.answers)
        assertEquals(sessionId, vm.uiState.value.attempt!!.session.id)
        assertTrue(controller.state.value.positionMs in (position - 500)..(position + 500))
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("option-30-B"))
        compose.onNodeWithTag("option-30-B").assertIsDisplayed().assertIsSelected()
        assertFixedRegionsDoNotOverlap()
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.waitUntil(10000) { controller.state.value.isPlaying && controller.state.value.positionMs > position + 500 }
        compose.onNodeWithTag("audio-toggle").performClick()
    }

    private fun assertFixedRegionsDoNotOverlap() {
        val header = compose.onNodeWithTag("practice-header").fetchSemanticsNode().boundsInRoot
        val player = compose.onNodeWithTag("practice-player").fetchSemanticsNode().boundsInRoot
        val questions = compose.onNodeWithTag("practice-list").fetchSemanticsNode().boundsInRoot
        val submit = compose.onNodeWithTag("practice-submit").fetchSemanticsNode().boundsInRoot
        assertTrue(header.bottom <= player.top)
        assertTrue(player.bottom <= questions.top)
        assertTrue(questions.bottom <= submit.top)
        assertTrue(questions.height > 0)
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val bars = requireNotNull(ViewCompat.getRootWindowInsets(decor))
                .getInsets(WindowInsetsCompat.Type.systemBars())
            assertTrue(header.top >= bars.top)
            assertTrue(submit.bottom <= decor.height - bars.bottom)
        }
        val toggle = compose.onNodeWithTag("audio-toggle").fetchSemanticsNode().boundsInRoot
        val time = compose.onNodeWithTag("audio-time").fetchSemanticsNode().boundsInRoot
        val seek = compose.onNodeWithTag("audio-seek").fetchSemanticsNode().boundsInRoot
        assertTrue("Play button overlaps time: $toggle / $time", toggle.right <= time.left)
        assertTrue("Time overlaps seek touch area: $time / $seek", time.right <= seek.left)
        assertTrue("Seek touch area too narrow: $seek", seek.width >= 48 * compose.density.density)
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

    @Test fun nativeOptionTextTapSavesAnswerAndLongPressCopyDoesNotSelectOption() {
        openPractice()
        select(21, "A")
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("option-21-B"))
        textAt("option-text-21-B").perform(click())
        compose.waitUntil(10000) { !vm.uiState.value.saving && vm.uiState.value.answers[vm.uiState.value.questions.first().id] == "B" }
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("option-21-A"))
        textAt("option-text-21-A").perform(pressWord(5))
        val before = vm.uiState.value.answers.toMap()
        selectionMenu("复制")
        assertEquals(before, vm.uiState.value.answers)
        val app = compose.activity.application as TestListeningApplication
        assertEquals(before, runBlocking(Dispatchers.IO) { app.practices.all().single().selectedAnswers })
        compose.onNodeWithTag("option-21-B").assertIsSelected()
    }

    @Test fun intensiveCardSwitchesAllQuestionsAndSharesRoomAnswersInBothDirections() {
        openPractice()
        select(21, "A")
        val sessionId = vm.uiState.value.attempt!!.session.id
        openTranscript()
        compose.onNodeWithTag("transcript-question-summary").assertTextEquals("Q.21 · 已选 A")
        compose.onNodeWithTag("transcript-previous").assertIsNotEnabled()
        for (number in 22..30) {
            compose.onNodeWithTag("transcript-next").performClick()
            compose.onNodeWithTag("transcript-question-summary").assertTextEquals("Q.$number · 未作答")
            compose.onNodeWithTag("question-$number").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("transcript-next").assertIsNotEnabled()
        val last = vm.uiState.value.questions.last()
        compose.onNodeWithText(last.prompt).performScrollTo().assertIsDisplayed()
        last.options.forEach { compose.onNodeWithTag("option-30-${it.id}").performScrollTo().assertIsDisplayed() }
        selectInTranscript(30, "B")
        compose.onNodeWithTag("transcript-previous").performClick()
        compose.onNodeWithTag("transcript-question-summary").assertTextEquals("Q.29 · 未作答")
        compose.onNodeWithTag("transcript-next").performClick()
        compose.onNodeWithTag("transcript-question-summary").assertTextEquals("Q.30 · 已选 B")
        jumpInTranscript(24)
        selectInTranscript(24, "A")
        selectInTranscript(24, "B")
        compose.onNodeWithTag("option-24-A").assertIsNotSelected()
        compose.onNodeWithTag("option-24-B").assertIsSelected()
        compose.onNodeWithTag("transcript-question-summary").assertTextEquals("Q.24 · 已选 B")
        compose.onNodeWithText("标准答案", substring = true).assertDoesNotExist()
        val committed = vm.uiState.value.answers.toMap()
        val app = compose.activity.application as TestListeningApplication
        assertEquals(committed, runBlocking(Dispatchers.IO) {
            app.practices.all().single { it.session.id == sessionId }.selectedAnswers
        })
        pressBack()
        for ((number, option) in listOf(21 to "A", 24 to "B", 30 to "B")) {
            compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("option-$number-$option"))
            compose.onNodeWithTag("option-$number-$option").assertIsSelected()
        }
        select(24, "C")
        openTranscript()
        jumpInTranscript(24)
        compose.onNodeWithTag("transcript-question-summary").assertTextEquals("Q.24 · 已选 C")
        compose.onNodeWithTag("option-24-C").performScrollTo().assertIsSelected()
        assertEquals(sessionId, vm.uiState.value.attempt!!.session.id)
        assertEquals(1, vm.uiState.value.history.size)
    }

    @Test fun intensiveCardTogglesKeepReadingOffsetPlayerAndSavedPresentation() {
        openPractice()
        select(21, "B")
        openTranscript()
        compose.waitUntil(15000) { vm.audio.state.value.ready }
        val controller = vm.audio
        val playerBounds = compose.onNodeWithTag("transcript-player").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("audio-seek").performTouchInput {
            swipe(Offset(width * 0.1f, centerY), Offset(width * 0.4f, centerY), 600)
        }
        compose.waitUntil(5000) { controller.state.value.positionMs in 90000..300000 }
        compose.onNodeWithTag("audio-toggle").performClick()
        val firstPosition = controller.state.value.positionMs
        compose.waitUntil(10000) { controller.state.value.isPlaying && controller.state.value.positionMs > firstPosition + 500 }
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.waitUntil(5000) { !controller.state.value.isPlaying }
        val pausedPosition = controller.state.value.positionMs
        compose.onNodeWithText("英中双语").performClick()
        compose.onNodeWithText(vm.uiState.value.part!!.transcript.segments.last().english).performScrollTo()
        val readingOffset = transcriptOffset()
        assertTrue(readingOffset > 0)
        val answers = vm.uiState.value.answers.toMap()
        repeat(3) {
            assertIntensiveRegionsDoNotOverlap(expanded = true)
            compose.onNodeWithTag("transcript-card-toggle").performClick()
            compose.onNodeWithTag("transcript-question-body").assertDoesNotExist()
            compose.onNodeWithTag("transcript-question-summary").assertTextEquals("Q.21 · 已选 B")
            assertEquals(readingOffset, transcriptOffset(), 2f)
            assertIntensiveRegionsDoNotOverlap(expanded = false)
            compose.onNodeWithTag("transcript-card-toggle").performClick()
            compose.onNodeWithTag("transcript-question-body").assertIsDisplayed()
            assertEquals(readingOffset, transcriptOffset(), 2f)
            assertEquals(playerBounds, compose.onNodeWithTag("transcript-player").fetchSemanticsNode().boundsInRoot)
            assertSame(controller, vm.audio)
            assertTrue(controller.state.value.positionMs in (pausedPosition - 500)..(pausedPosition + 500))
            assertEquals(answers, vm.uiState.value.answers)
        }
        jumpInTranscript(24)
        selectInTranscript(24, "A")
        compose.onNodeWithTag("transcript-card-toggle").performClick()
        val restoredOffset = transcriptOffset()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("transcript-question-summary").assertTextEquals("Q.24 · 已选 A")
        compose.onNodeWithTag("transcript-question-body").assertDoesNotExist()
        compose.onNodeWithText("英中双语").assertIsSelected()
        assertEquals(restoredOffset, transcriptOffset(), 2f)
        compose.onNodeWithTag("transcript-card-toggle").performClick()
        compose.onNodeWithTag("option-24-A").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.waitUntil(10000) { controller.state.value.isPlaying }
        val playingPosition = controller.state.value.positionMs
        repeat(2) { compose.onNodeWithTag("transcript-card-toggle").performClick() }
        assertSame(controller, vm.audio)
        assertTrue(controller.state.value.isPlaying)
        assertTrue(controller.state.value.positionMs >= playingPosition)
        compose.onNodeWithTag("audio-toggle").performClick()
    }

    @Test fun intensiveSubmittedAndHistoricalCardsAreReadOnly() {
        openPractice()
        val question = vm.uiState.value.questions.first()
        val wrong = question.options.first { it.id != question.correctAnswer }
        select(question.number, wrong.id)
        compose.onNodeWithText("提交答案").performClick()
        compose.onNodeWithText("确认提交").performClick()
        compose.waitUntil(10000) { vm.uiState.value.submitted && !vm.uiState.value.saving }
        val completed = vm.uiState.value.attempt!!
        openTranscript()
        assertSubmittedTranscriptQuestion(question.number, wrong.id)
        val chosen = vm.uiState.value.answers.toMap()
        compose.onNodeWithTag("option-${question.number}-${question.correctAnswer}").performScrollTo()
            .performTouchInput { click() }
        compose.runOnIdle { vm.selectAnswer(question.id, question.correctAnswer) }
        assertEquals(chosen, vm.uiState.value.answers)
        pressBack()
        compose.onNodeWithText("重新练习").performClick()
        compose.waitUntil(10000) { !vm.uiState.value.saving && !vm.uiState.value.submitted }
        select(question.number, question.correctAnswer)
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("history-${completed.session.id}"))
        compose.onNodeWithTag("history-${completed.session.id}").performClick()
        openTranscript()
        assertSubmittedTranscriptQuestion(question.number, wrong.id)
        val app = compose.activity.application as TestListeningApplication
        assertEquals(completed, runBlocking(Dispatchers.IO) {
            app.practices.all().single { it.session.id == completed.session.id }
        })
        assertEquals(2, vm.uiState.value.history.size)
    }

    private fun assertSubmittedTranscriptQuestion(number: Int, selected: String) {
        val question = vm.uiState.value.questions.single { it.number == number }
        val correct = question.options.single { it.id == question.correctAnswer }
        val userOption = question.options.single { it.id == selected }
        compose.onNodeWithTag("transcript-question-summary").assertTextEquals("Q.$number · 已选 $selected")
        compose.onNodeWithText("已提交 · 只读").assertExists()
        question.options.forEach { compose.onNodeWithTag("option-$number-${it.id}").assertIsNotEnabled() }
        compose.onNodeWithText("你的答案：${userOption.id}. ${userOption.text}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("result-$number").performScrollTo().assertIsDisplayed()
            .assertTextEquals("标准答案：${correct.id}. ${correct.text}")
    }

    @Test fun handleContinuouslyResizesWithoutSnappingAndRespectsBounds() {
        openPractice()
        openTranscript()
        val initial = panelHeight()
        val handle = compose.onNodeWithTag("transcript-drag-handle")
        handle.performTouchInput { down(center); moveBy(Offset(0f, -70f)) }
        val first = panelHeight()
        assertTrue("Height must change before finger release", first > initial + 20f)
        handle.performTouchInput { moveBy(Offset(0f, -31f)) }
        assertTrue("Height must continuously follow the finger", panelHeight() > first + 20f)
        handle.performTouchInput { up() }
        dragPanelTo(0.613f)
        val released = panelHeight()
        assertEquals(0.613f, panelRatio(), 0.015f)
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals("No settling animation or snapping", released, panelHeight(), 1f)
        dragPanelTo(0.40f)
        assertTrue(panelHeight() < released)
        val range = handle.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range
        dragPanelTo(0.88f)
        assertEquals(range.endInclusive, panelRatio(), 0.015f)
        assertResizableRegions()
        dragPanelTo(0.10f)
        assertEquals(range.start, panelRatio(), 0.015f)
        assertResizableRegions()
    }

    @Test fun collapseRestoresLastDraggedHeightAndRecreationRestoresPresentation() {
        openPractice()
        openTranscript()
        dragPanelTo(0.65f)
        val ratio = panelRatio()
        assertEquals(0.65f, ratio, 0.015f)
        compose.onNodeWithTag("transcript-card-toggle").performClick()
        compose.onNodeWithTag("transcript-question-body").assertDoesNotExist()
        assertTrue(panelRatio() < 0.25f)
        compose.onNodeWithTag("transcript-card-toggle").performClick()
        assertEquals(ratio, panelRatio(), 0.005f)
        jumpInTranscript(27)
        selectInTranscript(27, "B")
        compose.onNodeWithText("中文翻译").performClick()
        compose.onNodeWithTag("transcript-card-toggle").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("transcript-question-summary").assertTextEquals("Q.27 · 已选 B")
        compose.onNodeWithTag("transcript-question-body").assertDoesNotExist()
        compose.onNodeWithText("中文翻译").assertIsSelected()
        compose.onNodeWithTag("transcript-card-toggle").performClick()
        assertEquals(ratio, panelRatio(), 0.005f)
        compose.onNodeWithTag("option-27-B").performScrollTo().assertIsSelected()
        assertResizableRegions()
    }

    @Test fun resizingAtMiddleAndEndPreservesReadingAndQuestionOffsets() {
        openPractice()
        openTranscript()
        compose.onNodeWithText("英中双语").performClick()
        compose.onNodeWithTag("transcript-body").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val middle = transcriptOffset()
        assertTrue(middle > 0)
        repeat(2) {
            dragPanelTo(0.66f)
            assertEquals(middle, transcriptOffset(), 2f)
            dragPanelTo(0.37f)
            assertEquals(middle, transcriptOffset(), 2f)
        }
        compose.onNodeWithText(vm.uiState.value.part!!.transcript.segments.last().english).performScrollTo()
        val end = transcriptOffset()
        assertTrue(end > middle)
        repeat(3) {
            dragPanelTo(0.70f)
            assertEquals(end, transcriptOffset(), 2f)
            compose.onNodeWithTag("transcript-card-toggle").performClick()
            assertEquals(end, transcriptOffset(), 2f)
            compose.onNodeWithTag("transcript-card-toggle").performClick()
            assertEquals(end, transcriptOffset(), 2f)
            dragPanelTo(0.38f)
            assertEquals(end, transcriptOffset(), 2f)
        }
        compose.onNodeWithTag("option-21-C").performScrollTo()
        val questionOffset = questionOffset()
        assertTrue(questionOffset > 0)
        dragPanelTo(0.70f)
        assertEquals(questionOffset, questionOffset(), 2f)
        dragPanelTo(0.38f)
        assertEquals(questionOffset, questionOffset(), 2f)
        assertEquals(end, transcriptOffset(), 2f)
        jumpInTranscript(24)
        assertEquals(end, transcriptOffset(), 2f)
        // Ordinary scrolling in either content region must not resize the panel.
        val height = panelHeight()
        compose.onNodeWithTag("transcript-question-body").performTouchInput { swipeUp() }
        compose.onNodeWithTag("transcript-body").performTouchInput { swipeDown() }
        assertEquals(height, panelHeight(), 1f)
    }

    @Test fun tenDragGesturesKeepPlayerAndRoomAnswersShared() {
        openPractice()
        select(21, "A")
        val sessionId = vm.uiState.value.attempt!!.session.id
        openTranscript()
        dragPanelTo(0.60f)
        selectInTranscript(21, "B")
        pressBack()
        compose.onNodeWithTag("practice-list").performScrollToNode(hasTestTag("option-21-B"))
        compose.onNodeWithTag("option-21-B").assertIsSelected()
        openTranscript()
        compose.waitUntil(15000) { vm.audio.state.value.ready }
        val controller = vm.audio
        compose.onNodeWithTag("audio-seek").performTouchInput {
            swipe(Offset(width * 0.1f, centerY), Offset(width * 0.4f, centerY), 400)
        }
        compose.waitUntil(5000) { controller.state.value.positionMs > 90000 }
        compose.onNodeWithTag("audio-toggle").performClick()
        compose.waitUntil(10000) { controller.state.value.isPlaying }
        var position = controller.state.value.positionMs
        val player = compose.onNodeWithTag("transcript-player").fetchSemanticsNode().boundsInRoot
        repeat(10) {
            dragPanelTo(if (it % 2 == 0) 0.62f else 0.52f)
            assertSame(controller, vm.audio)
            assertTrue(controller.state.value.isPlaying)
            assertTrue(controller.state.value.positionMs >= position)
            position = controller.state.value.positionMs
            assertEquals(player, compose.onNodeWithTag("transcript-player").fetchSemanticsNode().boundsInRoot)
            assertEquals(sessionId, vm.uiState.value.attempt!!.session.id)
        }
        compose.onNodeWithTag("audio-toggle").performClick()
        val app = compose.activity.application as TestListeningApplication
        assertEquals(vm.uiState.value.answers, runBlocking(Dispatchers.IO) {
            app.practices.all().single { it.session.id == sessionId }.selectedAnswers
        })
        assertEquals(1, vm.uiState.value.history.size)
        compose.onNodeWithText("标准答案", substring = true).assertDoesNotExist()
        assertResizableRegions()
    }

    private fun panelHeight() = compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot.height
    private fun availableHeight() = compose.onNodeWithTag("intensive-content").fetchSemanticsNode().boundsInRoot.height
    private fun panelRatio() = panelHeight() / availableHeight()
    private fun questionOffset() = compose.onNodeWithTag("transcript-question-body").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun dragPanelTo(ratio: Float) {
        val delta = (ratio - panelRatio()) * availableHeight()
        if (kotlin.math.abs(delta) < 1f) return
        val slop = android.view.ViewConfiguration.get(compose.activity).scaledTouchSlop
        val movement = delta + kotlin.math.sign(delta) * slop
        compose.onNodeWithTag("transcript-drag-handle").performTouchInput {
            swipe(center, center + Offset(0f, -movement), 400)
        }
        compose.waitForIdle()
    }

    private fun assertResizableRegions() {
        val player = compose.onNodeWithTag("transcript-player").fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("transcript-language-tabs").fetchSemanticsNode().boundsInRoot
        val reading = compose.onNodeWithTag("transcript-body").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("transcript-question-body").fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("transcript-drag-handle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(player.bottom <= tabs.top)
        assertTrue(tabs.bottom <= reading.top)
        assertTrue(reading.bottom <= panel.top)
        assertTrue(body.top >= handle.bottom)
        assertTrue(body.bottom <= panel.bottom)
        assertTrue(reading.height >= 48f * compose.density.density)
        assertTrue(body.height >= 48f * compose.density.density - 1f)
        assertTrue(handle.height >= 48f * compose.density.density)
        val toggle = compose.onNodeWithTag("transcript-card-toggle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(handle.right <= toggle.left)
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val bars = requireNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(WindowInsetsCompat.Type.systemBars())
            assertTrue(panel.bottom <= decor.height - bars.bottom)
        }
    }

    private fun selectInTranscript(number: Int, option: String) {
        compose.onNodeWithTag("option-$number-$option").performScrollTo().performClick()
        compose.waitUntil(10000) {
            !vm.uiState.value.saving && vm.uiState.value.answers[vm.uiState.value.questions.single { it.number == number }.id] == option
        }
    }

    private fun jumpInTranscript(number: Int) {
        compose.onNodeWithTag("transcript-jump").performClick()
        for (item in 21..30) compose.onNodeWithTag("jump-$item").assertIsDisplayed()
        compose.onNodeWithTag("jump-$number").performClick()
    }

    private fun transcriptOffset(): Float = compose.onNodeWithTag("transcript-body")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun assertIntensiveRegionsDoNotOverlap(expanded: Boolean) {
        val player = compose.onNodeWithTag("transcript-player").fetchSemanticsNode().boundsInRoot
        val reading = compose.onNodeWithTag("transcript-body").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("transcript-question-panel").fetchSemanticsNode().boundsInRoot
        val available = compose.onNodeWithTag("intensive-content").fetchSemanticsNode().boundsInRoot
        assertTrue(player.bottom <= reading.top)
        assertTrue(reading.bottom <= panel.top)
        assertTrue("Transcript must keep at least half the reading area", reading.height >= available.height * 0.49f)
        if (expanded) {
            compose.onNodeWithTag("transcript-previous").assertIsDisplayed()
            compose.onNodeWithTag("transcript-next").assertIsDisplayed()
            compose.onNodeWithTag("transcript-jump").assertIsDisplayed()
            assertTrue(compose.onNodeWithTag("transcript-question-body").fetchSemanticsNode().boundsInRoot.height > 0)
        }
        compose.onNodeWithTag("audio-toggle").assertIsDisplayed()
        compose.onNodeWithTag("audio-seek").assertIsDisplayed()
        compose.onNodeWithTag("audio-time").assertIsDisplayed()
        val header = compose.onNodeWithTag("transcript-header").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val bars = requireNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(WindowInsetsCompat.Type.systemBars())
            assertTrue(header.top >= bars.top)
            assertTrue(panel.bottom <= decor.height - bars.bottom)
        }
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
        compose.onNodeWithText("查看原文").assertIsDisplayed().performClick()
    }
}
