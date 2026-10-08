package com.eenglish.listening.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.annotation.AnnotationDocument

@Composable
fun QuestionCard(question: Question, selected: String?, editable: Boolean, showResult: Boolean = false, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).selectableGroup()) {
        Text("Q.${question.number}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("question-${question.number}"))
        AnnotatableText(remember(question.id, question.prompt) {
            AnnotationDocument.single(question.prompt, "question", "${question.id}/prompt", "und") },
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 16.dp), viewTag = "prompt-${question.number}")
        question.options.forEach { option ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().testTag("option-${question.number}-${option.id}")
                .selectable(selected == option.id, enabled = editable, role = Role.RadioButton, onClick = { onSelect(option.id) })
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == option.id, onClick = null, enabled = editable)
                AnnotatableText(remember(question.id, option) {
                    AnnotationDocument.single("${option.id}. ${option.text}", "question", "${question.id}/option:${option.id}", "und") },
                    modifier = Modifier.weight(1f), viewTag = "option-text-${question.number}-${option.id}",
                    onTap = if (editable) ({ onSelect(option.id) }) else null)
            }
        }
        if (showResult) {
            val chosen = question.options.find { it.id == selected }
            val correct = question.options.single { it.id == question.correctAnswer }
            Text("你的答案：${chosen?.let { "${it.id}. ${it.text}" } ?: "未作答"}",
                color = if (selected == correct.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text("标准答案：${correct.id}. ${correct.text}", modifier = Modifier.padding(top = 8.dp).testTag("result-${question.number}"))
        }
    }
}
