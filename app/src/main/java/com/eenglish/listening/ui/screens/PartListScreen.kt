package com.eenglish.listening.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.eenglish.listening.domain.model.SessionStatus
import com.eenglish.listening.viewmodel.PracticeUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartListScreen(state: PracticeUiState, onImport: () -> Unit = {}, backEnabled: Boolean = true,
    onExit: () -> Unit = {}, onOpen: (String) -> Unit) {
    var book by rememberSaveable { mutableStateOf<Int?>(null) }
    var test by rememberSaveable { mutableStateOf<Int?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    // Keep separate state objects outside the changing list content. Inactive levels
    // must not have their offsets clamped against a different level's shorter list.
    val scrollStates = rememberSaveable(saver = mapSaver(
        save = { states: MutableMap<String, LazyListState> -> states.mapValues { (_, value) ->
            arrayListOf(value.firstVisibleItemIndex, value.firstVisibleItemScrollOffset) } },
        restore = { saved -> saved.mapValues { (_, value) ->
            val position = value as List<*>
            LazyListState(position[0] as Int, position[1] as Int)
        }.toMutableMap() }
    )) { mutableMapOf<String, LazyListState>() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val searching = search.isNotEmpty()
    // Capture the level for this content provider. Reading mutable book/test from
    // a disposed LazyColumn can briefly empty its items and clamp its saved scroll.
    val selectedBook = book
    val selectedTest = test
    val books = state.parts.map { it.book }.distinct()
    val tests = state.parts.filter { it.book == book }.map { it.test }.distinct()
    val parts = if (!searching) state.parts.filter { it.book == book && it.test == test }
        else state.parts.filter { part -> search.trim().lowercase().split(Regex("\\s+")).all {
            it in "${part.id} ${part.title} ${part.book} ${part.test} ${part.part}".lowercase()
        } }
    fun navigateBack() {
        if (state.batchImport != null) return // The dialog owns dismissal; never interrupt a running import.
        focus.clearFocus(); keyboard?.hide()
        when {
            searching -> search = ""
            test != null -> test = null
            book != null -> book = null
            else -> confirmExit = true
        }
    }
    // Only the active list destination handles Back. The system IME gets first refusal.
    BackHandler(enabled = backEnabled) {
        // Read current window insets at dispatch, rather than a captured Compose
        // inset value which can lag behind keyboard dismissal on a rapid second Back.
        if (ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            keyboard?.hide(); focus.clearFocus()
        } else navigateBack()
    }
    LaunchedEffect(state.batchImport != null) { if (state.batchImport != null) confirmExit = false }
    if (confirmExit && state.batchImport == null) AlertDialog(onDismissRequest = { confirmExit = false },
        properties = DialogProperties(dismissOnBackPress = true),
        title = { Text("退出 E-English？") }, text = { Text("已保存的答案、成绩和高亮会保留。") },
        confirmButton = { TextButton(onClick = onExit, modifier = Modifier.testTag("confirm-exit")) { Text("退出") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }, modifier = Modifier.testTag("cancel-exit")) { Text("继续练习") } })
    Scaffold(topBar = {
        TopAppBar(title = { Text("试题列表", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                if (book != null || searching) IconButton(onClick = ::navigateBack,
                    modifier = Modifier.testTag("library-back").semantics { contentDescription = "返回上一级" }) {
                    val color = MaterialTheme.colorScheme.onSurface
                    Canvas(Modifier.size(24.dp)) {
                        drawLine(color, Offset(size.width * .65f, size.height * .2f), Offset(size.width * .35f, size.height * .5f), 2.dp.toPx())
                        drawLine(color, Offset(size.width * .35f, size.height * .5f), Offset(size.width * .65f, size.height * .8f), 2.dp.toPx())
                    }
                }
            })
    }, contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedTextField(search, { search = it }, singleLine = true,
                    placeholder = { Text("查找册数 / Test / Part", fontSize = 14.sp) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().testTag("library-search"))
                TextButton(onClick = onImport, enabled = !state.loading && !state.saving && state.batchImport == null,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("import-packs")) { Text("导入本地资料包", fontSize = 13.sp) }
                state.importMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
                }
                if (book != null || searching) Text(
                    if (searching) "搜索结果 · ${parts.size} 个 Part" else "Cambridge IELTS $book" + (test?.let { " · Test $it" } ?: ""),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp).testTag("library-level"))
            }
            val layer = if (searching) "search" else "book-${book ?: "all"}-test-${test ?: "all"}"
            key(layer) {
                val listState = scrollStates.getOrPut(layer) { LazyListState() }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("library-list"), state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.loading) item { CircularProgressIndicator() }
                    state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                    if (!state.loading && state.parts.isEmpty()) item {
                        Text("暂无已导入试题", style = MaterialTheme.typography.titleMedium)
                        Text("请选择本地 .eelpack 或 ZIP 资料包。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!searching && selectedBook == null) items(books, key = { "book-$it" }) { number ->
                        LibraryRow("Cambridge IELTS $number", "${state.parts.count { it.book == number }} / 16 Parts",
                            action = "查看 Test", tag = "book-$number") { book = number; test = null }
                    }
                    if (!searching && selectedBook != null && selectedTest == null) items(tests, key = { "test-$selectedBook-$it" }) { number ->
                        LibraryRow("Test $number", "${state.parts.count { it.book == selectedBook && it.test == number }} Parts",
                            action = "查看 Part", tag = "test-$selectedBook-$number") { test = number }
                    }
                    items(parts, key = { it.id }) { part ->
                        val latest = state.history.firstOrNull { it.session.partId == part.id }?.session
                        val status = when (latest?.status) {
                            SessionStatus.IN_PROGRESS -> "进行中"
                            SessionStatus.SUBMITTED -> "已完成 · ${latest.correctCount}/${latest.totalCount}"
                            null -> "未开始"
                        }
                        val action = when (latest?.status) {
                            SessionStatus.IN_PROGRESS -> "继续练习"
                            SessionStatus.SUBMITTED -> "查看成绩"
                            null -> "开始练习"
                        }
                        LibraryRow("Cambridge IELTS ${part.book} · Test ${part.test}",
                            "Part ${part.part} · Questions ${part.firstNumber}–${part.lastNumber}", status,
                            action, "open-${part.id}", !state.saving) { onOpen(part.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(title: String, detail: String, status: String? = null, action: String, tag: String,
    enabled: Boolean = true, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("$tag-card"), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("$tag-title"))
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("$tag-detail"))
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.widthIn(max = 128.dp).heightIn(min = 48.dp).testTag(tag)) {
                Text(action, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
