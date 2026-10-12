package com.eenglish.listening.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.domain.gapfill.SharedMultipleChoiceQuestionGroup
import com.eenglish.listening.domain.grading.Grader

/** A single shared prompt and one option list backed by the repository's atomic group save. */
@Composable
fun SharedMultipleChoiceGroupView(
    group: SharedMultipleChoiceQuestionGroup,
    answers: Map<String, String>,
    editable: Boolean,
    submitted: Boolean,
    saving: Boolean,
    activeQuestionNumber: Int? = null,
    onSelect: (String, String) -> Unit,
) {
    val selections = group.questions.map { Grader.selection(answers[it.id]) }
    val validSelections = selections.all { selected ->
        selected.size <= group.selectionCount && selected.all { optionId -> group.options.any { it.id == optionId } }
    }
    val consistent = selections.distinct().size == 1 && validSelections
    if (!consistent) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)
            .testTag("shared-multiple-choice-inconsistent-${group.questions.first().number}")) {
            Text(
                "组内历史答案不一致，已按原题分别显示；重新选择后会使用现有整组保存。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            group.questions.forEach { question ->
                QuestionCard(question, answers[question.id], editable, showResult = submitted,
                    onSelect = { value -> onSelect(question.id, value) })
            }
        }
        return
    }

    val first = group.questions.first()
    val stored = selections.firstOrNull().orEmpty().sorted().joinToString(",")
    var draft by rememberSaveable(LocalAnswerSession.current, group.groupId) { mutableStateOf(stored) }
    LaunchedEffect(stored, saving, editable) {
        if (!saving || !editable) draft = stored
    }
    val selected = Grader.selection(draft)
    val promptDocument = remember(group.questions, group.prompt) {
        AnnotationDocument.single(group.prompt, "question", "${first.id}/prompt", "und")
    }
    val otherPromptDocuments = remember(group.questions, group.prompt) {
        group.questions.drop(1).map { question ->
            AnnotationDocument.single(question.prompt, "question", "${question.id}/prompt", "und")
        }
    }
    val optionDocuments = remember(group.questions, group.options) {
        group.options.map { option ->
            val text = if (group.optionsInPrompt) option.id else "${option.id}. ${option.text}"
            val firstDocument = AnnotationDocument.single(text, "question",
                "${first.id}/option:${option.id}", "und")
            val otherDocuments = group.questions.drop(1).map { question ->
                AnnotationDocument.single(text, "question",
                    "${question.id}/option:${option.id}", "und")
            }
            firstDocument to otherDocuments
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)
        .testTag("shared-multiple-choice-${first.number}")) {
        AnnotatableText(promptDocument, otherDocuments = otherPromptDocuments,
            activeDocument = promptDocument, viewTag = "shared-multiple-choice-prompt-${first.number}")
        if (isAdditionalSharedText(group.instructions, group.prompt, group.context)) {
            Text(group.instructions, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (isAdditionalSharedText(group.context, group.prompt, group.instructions)) {
            Text(group.context, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
        }
        group.images.forEach { path ->
            Box(Modifier.fillMaxWidth().padding(top = 12.dp)) { QuestionImage(path) }
        }
        Text("请选择 ${group.selectionCount} 项（当前 ${selected.size}/${group.selectionCount}）",
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            group.options.forEachIndexed { index, option ->
                val checked = option.id in selected
                val canChoose = editable && (checked || selected.size < group.selectionCount)
                val choose = {
                    val next = when {
                        checked -> selected - option.id
                        selected.size < group.selectionCount -> selected + option.id
                        else -> selected
                    }
                    if (next != selected) {
                        val canonical = next.sorted().joinToString(",")
                        draft = canonical
                        // One representative ID lets PracticeRepository atomically update the group.
                        onSelect(first.id, canonical)
                    }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .testTag("multiple-choice-option-${option.id}")
                        .selectable(checked, enabled = canChoose, role = Role.Checkbox, onClick = choose)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = canChoose)
                    val (document, otherDocuments) = optionDocuments[index]
                    AnnotatableText(document, modifier = Modifier.weight(1f),
                        otherDocuments = otherDocuments, activeDocument = document,
                        viewTag = "shared-multiple-choice-option-${option.id}",
                        onTap = if (canChoose) choose else null)
                }
                if (index < group.options.lastIndex) HorizontalDivider()
            }
        }
        if (submitted) {
            Text("你的选择：${selected.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("、") ?: "未作答"}",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            group.questions.forEach { question ->
                val isCorrect = question.acceptableAnswers.any { it.uppercase() in selected }
                Text(
                    "Q.${question.number}：标准答案 ${question.acceptableAnswers.joinToString(" / ")} · ${if (isCorrect) "正确" else "错误"}",
                    color = if (isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else if (activeQuestionNumber != null && group.questions.any { it.number == activeQuestionNumber }) {
            Text("当前题 Q.$activeQuestionNumber · 共用本组选项",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun isAdditionalSharedText(candidate: String, prompt: String, other: String): Boolean {
    val normalized = candidate.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) return false
    val promptText = prompt.replace(Regex("\\s+"), " ").trim()
    val otherText = other.replace(Regex("\\s+"), " ").trim()
    return !promptText.contains(normalized, ignoreCase = true) && !otherText.contains(normalized, ignoreCase = true)
}
