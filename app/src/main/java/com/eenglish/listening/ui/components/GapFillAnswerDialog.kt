package com.eenglish.listening.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.Question

/**
 * Editing a local draft is side-effect free. Save and clear are the only persistence requests.
 * The caller displays state.answers, never this draft, while the Room command is pending.
 */
@Composable
fun GapFillAnswerDialog(question: Question, savedAnswer: String?, editable: Boolean, saving: Boolean,
    onSave: (String) -> Unit, onDismiss: () -> Unit, submitted: Boolean = false) {
    var draft by remember(question.id) { mutableStateOf(savedAnswer.orEmpty()) }
    val changed = draft.trim() != savedAnswer.orEmpty().trim()
    val valid = draft.isNotBlank() && Grader.withinLimit(question, draft)
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.testTag("gap-dialog-${question.number}"),
        title = { Text("Q${question.number} · ${if (editable) "填写答案" else "查看答案"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(question.instructions.ifBlank {
                    question.wordLimit?.let { "最多 ${it.maxWords} 个单词，数字限制按题目要求" }
                        ?: "按题目要求填写"
                }, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = draft, onValueChange = { if (it.length <= 512) draft = it },
                    enabled = editable && !saving, label = { Text("Q${question.number} 答案") },
                    modifier = Modifier.fillMaxWidth().testTag("gap-input-${question.number}"),
                    minLines = 1, maxLines = 3,
                    isError = editable && draft.isNotBlank() && !valid,
                )
                if (editable && draft.isNotBlank() && !valid)
                    Text("答案不符合当前字数／数字限制", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall)
                if (submitted) {
                    Text("此尝试已提交，不能修改答案。", style = MaterialTheme.typography.labelSmall)
                    Text("标准答案：${question.acceptableAnswers.joinToString(" / ")}",
                        style = MaterialTheme.typography.bodyMedium)
                } else if (!editable) {
                    Text("当前不可修改答案。", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            if (editable) TextButton(onClick = {
                onSave(draft.trim())
                onDismiss()
            }, enabled = !saving && changed && valid,
                modifier = Modifier.testTag("gap-save-${question.number}")) { Text("保存") }
            else TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            Row {
                if (editable && !savedAnswer.isNullOrBlank()) {
                    TextButton(onClick = { onSave(""); onDismiss() }, enabled = !saving,
                        modifier = Modifier.testTag("gap-clear-${question.number}")) {
                        Text("清空已存答案")
                    }
                }
                if (editable) TextButton(onClick = onDismiss, enabled = !saving,
                    modifier = Modifier.testTag("gap-cancel-${question.number}")) { Text("取消") }
            }
        },
    )
}
