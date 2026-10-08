package com.eenglish.listening.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.model.SessionStatus
import com.eenglish.listening.ui.components.PageHeader
import com.eenglish.listening.viewmodel.PracticeUiState

@Composable
fun PartListScreen(state: PracticeUiState, onImport: () -> Unit = {}, onOpen: (String) -> Unit) {
    var book by rememberSaveable { mutableStateOf<Int?>(null) }
    var test by rememberSaveable { mutableStateOf<Int?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    val books = state.parts.map { it.book }.distinct()
    val currentBook = book ?: books.singleOrNull()
    val tests = state.parts.filter { it.book == currentBook }.map { it.test }.distinct()
    val currentTest = test ?: tests.singleOrNull()
    val parts = if (search.isBlank()) state.parts.filter { it.book == currentBook && it.test == currentTest }
        else state.parts.filter { part -> search.trim().lowercase().split(Regex("\\s+")).all {
            it in "${part.id} ${part.title} ${part.book} ${part.test} ${part.part}".lowercase()
        } }
    Scaffold(topBar = {
        Surface {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                PageHeader("试题列表")
            }
        }
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("library-list"), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("剑桥雅思 · 离线听力练习", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                OutlinedTextField(search, { search = it }, label = { Text("查找册数 / Test / Part") },
                    modifier = Modifier.fillMaxWidth().testTag("library-search"))
                TextButton(onClick = onImport, enabled = !state.saving) { Text("导入本地资料包") }
                state.importMessage?.let { Text(it) }
            }
            if (state.loading) item { CircularProgressIndicator() }
            if (state.error != null) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
            if (!state.loading && state.parts.isEmpty()) item {
                Text("暂无已导入试题", style = MaterialTheme.typography.titleLarge)
                Text("请选择本地 .eelpack 或 ZIP 资料包。")
            }
            if (search.isBlank()) {
                if (currentBook == null) {
                    items(books, key = { "book-$it" }) { number ->
                        OutlinedButton(onClick = { book = number; test = null }, modifier = Modifier.fillMaxWidth().testTag("book-$number")) {
                            Text("Cambridge IELTS $number · ${state.parts.count { it.book == number }} / 16 Parts")
                        }
                    }
                } else {
                    item {
                        Text("Cambridge IELTS $currentBook", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { book = null; test = null }) { Text("返回册数") }
                        if (currentTest != null) TextButton(onClick = { test = null }, modifier = Modifier.testTag("test-$currentBook-$currentTest")) {
                            Text("Test $currentTest · 返回 Test 列表")
                        }
                    }
                    if (currentTest == null) {
                        items(tests, key = { "test-$currentBook-$it" }) { numberTest ->
                            OutlinedButton(onClick = { test = numberTest }, modifier = Modifier.fillMaxWidth().testTag("test-$currentBook-$numberTest")) {
                                Text("Test $numberTest · ${state.parts.count { it.book == currentBook && it.test == numberTest }} Parts")
                            }
                        }
                    }
                }
            }
            items(parts, key = { it.id }) { part ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Cambridge IELTS ${part.book}", style = MaterialTheme.typography.titleLarge)
                        Text("Test ${part.test} · Part ${part.part}")
                        Text("${part.questionCount} Questions · ${part.firstNumber}–${part.lastNumber}")
                        val latest = state.history.firstOrNull { it.session.partId == part.id }
                        Text(when (latest?.session?.status) {
                            SessionStatus.IN_PROGRESS -> "进行中"
                            SessionStatus.SUBMITTED -> "已完成 · ${latest.session.correctCount}/${latest.session.totalCount}"
                            null -> "未开始"
                        }, color = MaterialTheme.colorScheme.primary)
                        Button(onClick = { onOpen(part.id) }, enabled = !state.saving, modifier = Modifier.testTag("open-${part.id}")) { Text("开始练习") }
                    }
                }
            }
        }
    }
}
