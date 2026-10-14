package com.eenglish.listening.ui.screens

import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.ui.components.*
import com.eenglish.listening.viewmodel.PracticeUiState
import com.eenglish.listening.domain.grading.Grade
import com.eenglish.listening.domain.layout.LayoutV4AnswerStatus
import com.eenglish.listening.domain.model.SessionStatus
import com.eenglish.listening.domain.gapfill.QuestionLayoutDisplayParser
import com.eenglish.listening.domain.gapfill.QuestionDisplayItem
import java.text.DateFormat
import java.util.Date
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
fun PracticeScreen(state: PracticeUiState, audio: AudioState, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onSelect: (String, String) -> Unit, onBack: () -> Unit, onViewTranscript: () -> Unit,
    onSubmit: () -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit, onNew: () -> Unit, onHistory: (String) -> Unit,
    onSeekBy: (Long) -> Unit = {}, onSpeed: (Float) -> Unit = {},
    onInlineEdit: (String, String) -> Unit = onSelect,
    onInlineCommit: (String) -> Unit = {}, onFlushInline: () -> Unit = {}) {
    val listState = rememberLazyListState()
    val imeVisible = rememberImeVisible()
    var activeQuestionNumber by remember { mutableStateOf<Int?>(null) }
    val displayItems = remember(state.questions, state.questionLayout) {
        QuestionLayoutDisplayParser.parse(state.questions, state.questionLayout?.groups.orEmpty())
    }
    val v4Groups = remember(state.questionLayoutV4) {
        state.questionLayoutV4?.get("groups")?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
    }
    val resumeDraft = state.submitted && state.history.firstOrNull { it.session.partId == state.part?.id }
        ?.session?.status == SessionStatus.IN_PROGRESS
    LaunchedEffect(state.attempt?.session?.id, state.submitted) { listState.scrollToItem(0) }
    if (state.confirmMissing != null) AlertDialog(onDismissRequest = onDismiss,
        title = { Text("还有 ${state.confirmMissing} 题未作答") },
        text = { Text("未作答的题目将计为错误，确定提交吗？") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认提交") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("继续答题") } })
    Scaffold(topBar = {
        Row(Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .testTag("practice-header"), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onFlushInline(); onBack() }) { Text("返回") }
            Text("听力答题", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onFlushInline(); onViewTranscript() }, enabled = state.part != null) { Text("查看原文") }
        }
    }, bottomBar = {
        if (!imeVisible) {
            Button(onClick = if (state.submitted) onNew else onSubmit,
                enabled = state.attempt != null && !state.saving && !state.submitting,
                modifier = Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp, vertical = 8.dp).testTag("practice-submit")) {
                Text(if (resumeDraft) "继续未完成练习" else if (state.submitted) "重新练习" else if (state.saving) "正在保存…" else "提交答案")
            }
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            if (state.part != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("practice-player")) {
                    AudioControls(audio, onToggle, onSeek, compact = true, onSeekBy = onSeekBy, onSpeed = onSpeed)
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding().testTag("practice-list"), state = listState, contentPadding = PaddingValues(20.dp)) {
                val part = state.part
                if (part != null) {
                    item {
                        Text(part.title, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                        Text(part.instructions, style = MaterialTheme.typography.titleMedium)
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (state.submitted) {
                            val session = requireNotNull(state.attempt).session
                            val grade = Grade(requireNotNull(session.correctCount), session.totalCount)
                            Card(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("练习成绩", style = MaterialTheme.typography.titleLarge)
                                    Text("正确 ${grade.correctCount} / ${grade.totalCount} · 错误 ${grade.wrongCount} · 正确率 ${grade.accuracy}%",
                                        modifier = Modifier.testTag("grade-summary"))
                                    Text("已提交的尝试不可修改", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        } else {
                            val missing = LayoutV4AnswerStatus.missingCount(
                                state.questions, state.questionLayoutV4, state.answers)
                            Text("已保存 ${state.questions.size - missing} / ${state.questions.size} 题", modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                    if (state.questionLayoutV4 != null) {
                        val partQuestions = state.part?.questions.orEmpty().associateBy { it.id }
                        items(v4Groups, key = { group -> group["groupId"]?.jsonPrimitive?.contentOrNull.orEmpty() }) { group ->
                            val groupQuestions = group["questionIds"]?.jsonArray.orEmpty()
                                .mapNotNull { partQuestions[it.jsonPrimitive.content] }
                            if (groupQuestions.isNotEmpty()) LayoutV4GroupView(
                                group = group,
                                questions = groupQuestions,
                                answers = state.answers + state.inlineDrafts,
                                editable = !state.submitted && !state.submitting && state.attempt != null,
                                submitted = state.submitted,
                                saving = state.saving,
                                onSelect = onSelect,
                                onEdit = onInlineEdit,
                                onCommit = onInlineCommit,
                                onActivate = { activeQuestionNumber = it },
                            )
                        }
                    } else itemsIndexed(displayItems, key = { _, entry -> when (entry) {
                        is QuestionDisplayItem.Single -> entry.question.id
                        is QuestionDisplayItem.InlineGroup -> "inline-${entry.group.questions.first().id}"
                        is QuestionDisplayItem.StructuredLayout -> "layout-${entry.group.groupId}"
                        is QuestionDisplayItem.ImageGroup -> "image-${entry.group.questions.first().id}"
                        is QuestionDisplayItem.SharedMatching -> "matching-${entry.group.questions.first().id}"
                        is QuestionDisplayItem.SharedMultipleChoice -> "multiple-choice-${entry.group.groupId}"
                        is QuestionDisplayItem.SharedTextFallback -> "text-fallback-${entry.group.questions.first().id}"
                    } }) { _, entry ->
                        val onActivateInput: (Int) -> Unit = { number ->
                            activeQuestionNumber = number
                        }
                        when (entry) {
                            is QuestionDisplayItem.Single -> {
                                val question = entry.question
                                QuestionCard(question, state.answers[question.id],
                                    !state.submitted && !state.submitting && state.attempt != null,
                                    showResult = state.submitted, onSelect = { onSelect(question.id, it) })
                            }
                            is QuestionDisplayItem.InlineGroup -> InlineGapFillGroup(
                                entry.group, state.answers + state.inlineDrafts,
                                editable = !state.submitted && !state.submitting && state.attempt != null,
                                activeQuestionNumber = activeQuestionNumber,
                                onActivate = onActivateInput,
                                submitted = state.submitted,
                                saving = state.saving, dirty = state.inlineDrafts.isNotEmpty(),
                                onSelect = onSelect, onEdit = onInlineEdit, onCommit = onInlineCommit)
                            is QuestionDisplayItem.StructuredLayout -> StructuredLayoutGroup(
                                entry.group, entry.questions, state.answers + state.inlineDrafts,
                                editable = !state.submitted && !state.submitting && state.attempt != null,
                                activeQuestionNumber = activeQuestionNumber,
                                onActivate = onActivateInput,
                                submitted = state.submitted, saving = state.saving,
                                dirty = state.inlineDrafts.isNotEmpty(), onSelect = onSelect,
                                onEdit = onInlineEdit, onCommit = onInlineCommit)
                            is QuestionDisplayItem.ImageGroup -> ImageQuestionGroup(
                                entry.group, state.answers + state.inlineDrafts,
                                editable = !state.submitted && !state.submitting && state.attempt != null,
                                activeQuestionNumber = activeQuestionNumber,
                                onActivate = onActivateInput,
                                submitted = state.submitted, saving = state.saving,
                                dirty = state.inlineDrafts.isNotEmpty(), onSelect = onSelect)
                            is QuestionDisplayItem.SharedMatching -> SharedMatchingGroupView(
                                entry.group, state.answers, editable = !state.submitted && !state.submitting && state.attempt != null,
                                submitted = state.submitted, onSelect = onSelect)
                            is QuestionDisplayItem.SharedMultipleChoice -> SharedMultipleChoiceGroupView(
                                entry.group, state.answers, editable = !state.submitted && !state.submitting && state.attempt != null,
                                submitted = state.submitted, saving = state.saving, onSelect = onSelect)
                            is QuestionDisplayItem.SharedTextFallback -> SharedTextFallbackGroupView(
                                entry.group, state.answers + state.inlineDrafts,
                                editable = !state.submitted && !state.submitting && state.attempt != null,
                                activeQuestionNumber = activeQuestionNumber,
                                submitted = state.submitted, onSelect = onSelect)
                        }
                    }
                    item {
                        Text("练习记录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
                        state.history.filter { it.session.partId == part.id }.forEach { attempt ->
                            val session = attempt.session
                            TextButton(onClick = { onHistory(session.id) }, enabled = !state.saving,
                                modifier = Modifier.testTag("history-${session.id}")) {
                                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.startedAt)) +
                                    if (session.status == SessionStatus.SUBMITTED) " · ${session.correctCount}/${session.totalCount}" else " · 进行中")
                            }
                        }
                    }
                } else item { Text("暂无已导入试题") }
            }
        }
    }
}

@Composable
private fun rememberImeVisible(): Boolean {
    val view = LocalView.current
    val visible = remember(view) { mutableStateOf(false) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            visible.value = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        val observer = view.viewTreeObserver
        observer.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        }
    }
    return visible.value
}
