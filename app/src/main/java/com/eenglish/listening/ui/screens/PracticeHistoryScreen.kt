package com.eenglish.listening.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.grading.Grade
import com.eenglish.listening.domain.model.PracticeAttempt
import com.eenglish.listening.domain.model.SessionStatus
import com.eenglish.listening.viewmodel.PracticeUiState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen(onHistory: () -> Unit, bottomBar: @Composable () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("我") }) }, bottomBar = bottomBar) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).padding(16.dp)) {
            Card(onClick = onHistory, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().testTag("me-history")) {
                ListItem(headlineContent = { Text("练习记录") },
                    supportingContent = { Text("继续练习或查看已完成的成绩") },
                    trailingContent = { Text("›") }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeHistoryScreen(state: PracticeUiState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val records = remember(state.history) {
        state.history.sortedWith(compareByDescending<PracticeAttempt> { it.session.startedAt }.thenByDescending { it.session.id })
    }
    val parts = remember(state.parts) { state.parts.associateBy { it.id } }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("练习记录") }, navigationIcon = {
            TextButton(onClick = onBack, modifier = Modifier.testTag("records-back")) { Text("返回") }
        })
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).testTag("records-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.loading) item { CircularProgressIndicator(Modifier.testTag("records-loading")) }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (!state.loading && records.isEmpty()) item {
                Text("暂无练习记录", modifier = Modifier.testTag("records-empty"))
                Text("开始练习后，记录会保存在这里。", style = MaterialTheme.typography.bodyMedium)
            }
            items(records, key = { it.session.id }) { attempt ->
                val session = attempt.session
                val part = parts[session.partId]
                val grade = session.correctCount?.takeIf {
                    session.status == SessionStatus.SUBMITTED && session.totalCount > 0 && it in 0..session.totalCount
                }?.let { Grade(it, session.totalCount) }
                Card(onClick = { onOpen(session.id) }, enabled = part != null && !state.loading && !state.saving,
                    modifier = Modifier.fillMaxWidth().testTag("record-${session.id}"), shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f).testTag("record-info-${session.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(part?.let { "Cambridge IELTS ${it.book} · Test ${it.test} · Part ${it.part}" } ?: session.partId,
                                style = MaterialTheme.typography.titleSmall)
                            Text(dateFormat.format(Date(session.startedAt)), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (grade != null) "答对 ${grade.correctCount}/${grade.totalCount} 题"
                                else if (session.status == SessionStatus.SUBMITTED) "已完成" else "进行中",
                                modifier = Modifier.testTag("record-status-${session.id}"))
                            Text(if (part == null) "请先导入对应资料包" else if (session.status == SessionStatus.SUBMITTED) "查看复盘 ›" else "继续练习 ›",
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        if (grade != null) RecordAccuracy(grade, session.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordAccuracy(grade: Grade, sessionId: String) {
    val fraction = grade.correctCount.toFloat() / grade.totalCount
    val diameter = 96.dp * maxOf(1f, LocalDensity.current.fontScale / 1.5f)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val ringColor = Color(0xFF68B79A)
    val textColor = Color(0xFF287847)
    Box(Modifier.size(diameter).testTag("record-accuracy-$sessionId").clearAndSetSemantics {
        contentDescription = "正确率${grade.accuracy}%"
        stateDescription = "${grade.accuracy}%"
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().padding(4.dp)) {
            val stroke = 4.dp.toPx()
            val radius = (size.minDimension - stroke) / 2
            drawCircle(trackColor, radius, style = Stroke(stroke))
            if (fraction >= 1f) drawCircle(ringColor, radius, style = Stroke(stroke))
            else if (fraction > 0f) drawArc(ringColor, -90f, 360f * fraction, false,
                Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("正确率", color = textColor, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text("${grade.accuracy}%", color = textColor, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
