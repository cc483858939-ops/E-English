package com.eenglish.listening.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.model.ListeningPart
import com.eenglish.listening.ui.components.PageHeader
import com.eenglish.listening.viewmodel.PracticeUiState

@Composable
fun PartListScreen(state: PracticeUiState, onOpen: (ListeningPart) -> Unit) {
    Scaffold { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { PageHeader("试题列表") }
            item { Text("剑桥雅思 · 离线听力练习", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.loading) item { CircularProgressIndicator() }
            if (state.error != null) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
            if (!state.loading && state.parts.isEmpty()) item {
                Text("暂无已导入试题", style = MaterialTheme.typography.titleLarge)
                Text("请先使用题库转换工具导入本地样本。")
            }
            items(state.parts, key = { it.id }) { part ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Cambridge IELTS ${part.book}", style = MaterialTheme.typography.titleLarge)
                        Text("Test ${part.test} · Part ${part.part}")
                        Text("${part.questions.size} Questions · ${part.questions.first().number}–${part.questions.last().number}")
                        Text(if (state.answers.isEmpty()) "未开始" else "进行中", color = MaterialTheme.colorScheme.primary)
                        Button(onClick = { onOpen(part) }) { Text("开始练习") }
                    }
                }
            }
        }
    }
}
