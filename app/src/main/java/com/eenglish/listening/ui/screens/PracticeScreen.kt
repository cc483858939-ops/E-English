package com.eenglish.listening.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.ui.components.*
import com.eenglish.listening.viewmodel.PracticeUiState
import com.eenglish.listening.domain.grading.Grade
import com.eenglish.listening.domain.model.SessionStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun PracticeScreen(state: PracticeUiState, audio: AudioState, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onSelect: (String, String) -> Unit, onBack: () -> Unit, onViewTranscript: () -> Unit,
    onSubmit: () -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit, onNew: () -> Unit, onHistory: (String) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.attempt?.session?.id, state.submitted) { listState.scrollToItem(0) }
    if (state.confirmMissing != null) AlertDialog(onDismissRequest = onDismiss,
        title = { Text("还有 ${state.confirmMissing} 题未作答") },
        text = { Text("未作答的题目将计为错误，确定提交吗？") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认提交") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("继续答题") } })
    Scaffold(topBar = { PageHeader("听力答题", onBack) }, bottomBar = {
        Button(onClick = if (state.submitted) onNew else onSubmit,
            enabled = state.attempt != null && !state.saving && !state.submitting,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            Text(if (state.submitted) "重新练习" else if (state.saving) "正在保存…" else "提交答案")
        }
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("practice-list"), state = listState, contentPadding = PaddingValues(20.dp)) {
            val part = state.part
            if (part != null) {
                item {
                    Text(part.title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(20.dp))
                    AudioControls(audio, onToggle, onSeek)
                    TextButton(onClick = onViewTranscript) { Text("查看原文") }
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
                    } else Text("已保存 ${state.answers.size} / ${state.questions.size} 题", modifier = Modifier.padding(vertical = 8.dp))
                }
                items(state.questions, key = { it.id }) { question ->
                    QuestionCard(question, state.answers[question.id], !state.submitted && !state.submitting && state.attempt != null,
                        showResult = state.submitted, onSelect = { onSelect(question.id, it) })
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
