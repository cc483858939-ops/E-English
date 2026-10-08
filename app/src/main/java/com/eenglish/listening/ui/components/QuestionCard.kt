package com.eenglish.listening.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.annotation.AnnotationDocument

val LocalAnswerSession = staticCompositionLocalOf<String?> { null }
val LocalAnswerSaving = staticCompositionLocalOf { false }

@Composable
fun QuestionCard(question: Question, selected: String?, editable: Boolean, showResult: Boolean = false, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).selectableGroup()) {
        Text("Q.${question.number}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("question-${question.number}"))
        AnnotatableText(remember(question.id, question.prompt) {
            AnnotationDocument.single(question.prompt, "question", "${question.id}/prompt", "und") },
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 16.dp), viewTag = "prompt-${question.number}")
        question.images.forEach { QuestionImage(it) }
        if (question.type == QuestionType.MULTIPLE_CHOICE) {
            Text("Questions ${question.groupNumbers.joinToString(", ")} · 选择 ${question.groupNumbers.size} 项，顺序不限；每项计 1 分",
                style = MaterialTheme.typography.labelMedium)
        }
        if (question.isTextInput) {
            var draft by rememberSaveable(LocalAnswerSession.current, question.id) { mutableStateOf(selected.orEmpty()) }
            var focused by remember { mutableStateOf(false) }
            LaunchedEffect(selected, editable) { if (!focused || !editable) draft = selected.orEmpty() }
            OutlinedTextField(value = draft, onValueChange = { value ->
                if (value.length <= 512) { draft = value; onSelect(value) }
            }, enabled = editable, label = { Text("Q.${question.number} 答案") },
                modifier = Modifier.fillMaxWidth().testTag("input-${question.number}").onFocusChanged { focused = it.isFocused },
                supportingText = { Text(question.instructions.ifBlank { "按题目规定填写单词或数字" }) })
        }
        val multiple = question.type == QuestionType.MULTIPLE_CHOICE
        val saving = LocalAnswerSaving.current
        var draftChoices by rememberSaveable(LocalAnswerSession.current, question.id) { mutableStateOf(selected.orEmpty()) }
        LaunchedEffect(selected, saving, editable) { if (!saving || !editable) draftChoices = selected.orEmpty() }
        val choices = if (multiple) Grader.selection(draftChoices) else setOfNotNull(selected)
        question.options.forEach { option ->
            val chosen = option.id in choices
            val canSelect = editable && (!multiple || chosen || choices.size < question.groupNumbers.size)
            val choose = {
                val value = if (multiple) (if (chosen) choices - option.id else choices + option.id).sorted().joinToString(",") else option.id
                if (multiple) draftChoices = value
                onSelect(value)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().testTag("option-${question.number}-${option.id}")
                .selectable(chosen, enabled = canSelect, role = if (multiple) Role.Checkbox else Role.RadioButton, onClick = choose)
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (multiple) Checkbox(checked = chosen, onCheckedChange = null, enabled = canSelect)
                else RadioButton(selected = chosen, onClick = null, enabled = editable)
                AnnotatableText(remember(question.id, option) {
                    AnnotationDocument.single("${option.id}. ${option.text}", "question", "${question.id}/option:${option.id}", "und") },
                    modifier = Modifier.weight(1f), viewTag = "option-text-${question.number}-${option.id}",
                    onTap = if (canSelect) choose else null)
            }
        }
        if (showResult) {
            val chosen = question.options.find { it.id == selected }
            val correct = question.options.find { it.id == question.correctAnswer }
            val isCorrect = if (multiple) question.correctAnswer in choices else !selected.isNullOrBlank() &&
                Grader.withinLimit(question, selected) && question.acceptableAnswers.any { Grader.normalize(it) == Grader.normalize(selected) }
            Text("你的答案：${if (multiple) selected?.takeIf { it.isNotBlank() } ?: "未作答" else chosen?.let { "${it.id}. ${it.text}" } ?: selected?.takeIf { it.isNotBlank() } ?: "未作答"}",
                color = if (isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text("标准答案：${correct?.let { "${it.id}. ${it.text}" } ?: question.acceptableAnswers.joinToString(" / ")}", modifier = Modifier.padding(top = 8.dp).testTag("result-${question.number}"))
        }
    }
}
