package com.eenglish.listening.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eenglish.listening.domain.layout.answerSheetEntries
import com.eenglish.listening.domain.model.Question
import kotlinx.serialization.json.JsonObject

@Composable
fun AnswerSheetButton(enabled: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag("answer-sheet-open")
        .semantics { contentDescription = "答题卡" }) {
        Canvas(Modifier.size(24.dp)) {
            val ink = if (enabled) color else color.copy(alpha = 0.38f)
            drawRect(ink, Offset(size.width * .12f, size.height * .08f),
                androidx.compose.ui.geometry.Size(size.width * .76f, size.height * .84f), style = Stroke(1.5.dp.toPx()))
            listOf(.3f, .5f, .7f).forEach { y ->
                drawCircle(ink, 1.dp.toPx(), Offset(size.width * .3f, size.height * y))
                drawLine(ink, Offset(size.width * .45f, size.height * y),
                    Offset(size.width * .72f, size.height * y), 1.5.dp.toPx())
            }
        }
    }
}

@Composable
fun GlobalAnswerSheet(questions: List<Question>, layout: JsonObject?, answers: Map<String, String>,
    currentNumber: Int?, submitted: Boolean, busy: Boolean, onDismiss: () -> Unit,
    onQuestion: (Int) -> Unit, onSubmit: (() -> Unit)? = null) {
    val entries = remember(questions, layout, answers) { answerSheetEntries(questions, layout, answers) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("global-answer-sheet")) {
            Scaffold(topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("answer-sheet-close")) { Text("关闭") }
                    Text("答题卡", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
            }, bottomBar = {
                if (!submitted && onSubmit != null) Button(onClick = onSubmit, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("answer-sheet-submit")) {
                    Text(if (busy) "正在保存…" else "提交并查看结果")
                }
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                    Text("听力 · 当前 Part", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
                    Text("已答 ${entries.sumOf { it.answeredCount }} / ${questions.size} · 蓝色已答，白色未答",
                        style = MaterialTheme.typography.bodyMedium)
                    LazyVerticalGrid(GridCells.Adaptive(76.dp), modifier = Modifier.weight(1f).padding(top = 20.dp)
                        .testTag("answer-sheet-grid"), verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(entries, key = { it.questions.first().id }) { entry ->
                            val active = entry.questions.any { it.number == currentNumber }
                            val blue = Color(0xFF1976D2)
                            val status = if (entry.complete) "已答" else if (entry.answeredCount > 0)
                                "已选 ${entry.answeredCount}/${entry.questions.size}" else "未答"
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(onClick = { onQuestion(entry.number) }, shape = CircleShape,
                                    color = if (entry.complete) blue else Color.White,
                                    contentColor = if (entry.complete) Color.White else blue,
                                    border = BorderStroke(if (active) 3.dp else 1.dp,
                                        if (active) MaterialTheme.colorScheme.primary else if (entry.complete) blue else Color(0xFFCCCCCC)),
                                    modifier = Modifier.sizeIn(minWidth = 64.dp, minHeight = 64.dp)
                                        .testTag("jump-${entry.number}").semantics(mergeDescendants = true) {
                                            contentDescription = "Q${entry.label}，$status${if (active) "，当前题" else ""}"
                                            stateDescription = status
                                            selected = active
                                        }) {
                                    Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                                        Text(entry.label, style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                                if (entry.questions.size > 1) Text(status, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
