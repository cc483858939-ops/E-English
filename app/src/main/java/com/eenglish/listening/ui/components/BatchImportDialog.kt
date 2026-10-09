package com.eenglish.listening.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.eenglish.listening.data.repository.BatchImportUiState
import com.eenglish.listening.data.repository.ImportOutcome

@Composable
fun BatchImportDialog(state: BatchImportUiState, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!state.isRunning) onDone() },
        properties = DialogProperties(dismissOnBackPress = !state.isRunning, dismissOnClickOutside = !state.isRunning),
        title = { Text(if (state.isRunning) "正在导入" else "导入完成") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                .testTag("batch-import"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择：${state.total} 个资料包 · 已处理 ${state.processed} / ${state.total}")
                LinearProgressIndicator(progress = { if (state.total == 0) 0f else state.processed.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth().testTag("batch-progress"))
                if (state.isRunning) {
                    state.currentFile?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(listOfNotNull(state.currentBook?.let { "Cambridge $it" }, state.stage.label).joinToString(" · "))
                    }
                }
                Text("成功：${state.succeeded} · 已存在：${state.alreadyInstalled} · 失败：${state.failed}")
                Text("新增 Part：${state.addedParts} · 已存在 Part：${state.existingParts}")
                state.results.forEach { result ->
                    HorizontalDivider()
                    Text(listOfNotNull(result.book?.let { "Cambridge $it" }, result.fileName).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(when (result.outcome) {
                        ImportOutcome.INSTALLED -> "已导入 · 新增 ${result.addedParts} 个 Part，已存在 ${result.existingParts} 个 Part"
                        ImportOutcome.ALREADY_INSTALLED -> "已存在 · ${result.existingParts} 个 Part"
                        ImportOutcome.FAILED -> result.failure?.label ?: "导入失败"
                    }, color = if (result.outcome == ImportOutcome.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.failed > 0) Text("失败文件未替换现有资料；其他成功导入的资料已保留。")
            }
        },
        confirmButton = {
            if (!state.isRunning) TextButton(onClick = onDone, modifier = Modifier.testTag("batch-done")) { Text("完成") }
        },
    )
}
