package com.eenglish.listening.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.domain.gapfill.SharedMatchingQuestionGroup
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.Option

/** Displays the shared, untouched prompt once and keeps each matching answer independently keyed. */
@Composable
fun SharedMatchingGroupView(
    group: SharedMatchingQuestionGroup,
    answers: Map<String, String>,
    editable: Boolean,
    submitted: Boolean,
    activeQuestionNumber: Int? = null,
    onActivate: (Int) -> Unit = {},
    onSelect: (String, String) -> Unit,
) {
    val first = group.questions.first()
    val promptDocument = remember(group.questions, group.prompt) {
        AnnotationDocument.single(group.prompt, "question", "${first.id}/prompt", "und")
    }
    val otherPromptDocuments = remember(group.questions, group.prompt) {
        group.questions.drop(1).map { question ->
            AnnotationDocument.single(question.prompt, "question", "${question.id}/prompt", "und")
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("shared-matching-${first.number}")) {
        AnnotatableText(
            promptDocument,
            otherDocuments = otherPromptDocuments,
            activeDocument = promptDocument,
            viewTag = "shared-matching-prompt-${first.number}",
        )
        if (isAdditionalSharedText(group.instructions, group.prompt, group.context)) {
            Text(group.instructions, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (isAdditionalSharedText(group.context, group.prompt, group.instructions)) {
            Text(group.context, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (!group.optionsInPrompt) {
            if (group.options.all { it.text.trim() == it.id }) {
                Text("可选字母：${group.options.joinToString("、") { it.id }}",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            } else {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    group.options.forEach { option ->
                        val text = "${option.id}. ${option.text}"
                        val firstDocument = remember(group.questions, option, text) {
                            AnnotationDocument.single(text, "question",
                                "${first.id}/option:${option.id}", "und")
                        }
                        val otherDocuments = remember(group.questions, option, text) {
                            group.questions.drop(1).map { question ->
                                AnnotationDocument.single(text, "question",
                                    "${question.id}/option:${option.id}", "und")
                            }
                        }
                        AnnotatableText(firstDocument, otherDocuments = otherDocuments,
                            activeDocument = firstDocument, viewTag = "shared-matching-option-${option.id}")
                    }
                }
            }
        }
        group.images.forEach { path ->
            Box(Modifier.fillMaxWidth().padding(top = 12.dp)) { QuestionImage(path) }
        }
        Column(Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            group.questions.forEach { question ->
                val selected = answers[question.id].orEmpty()
                val active = activeQuestionNumber == question.number
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { onActivate(question.number) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Q.${question.number}", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.width(64.dp).testTag("matching-question-${question.number}"))
                    MatchingAnswerMenu(
                        questionNumber = question.number,
                        options = group.options,
                        selected = selected,
                        enabled = editable,
                        onOpened = { onActivate(question.number) },
                        onSelect = { optionId -> onSelect(question.id, optionId) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (submitted) {
                    val isCorrect = selected.isNotBlank() && question.acceptableAnswers.any {
                        Grader.normalize(it) == Grader.normalize(selected)
                    }
                    Text(
                        "你的答案：${selected.ifBlank { "未作答" }} · 标准答案：${question.acceptableAnswers.joinToString(" / ")}",
                        color = if (isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 4.dp),
                    )
                }
            }
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

@Composable
private fun MatchingAnswerMenu(
    questionNumber: Int,
    options: List<Option>,
    selected: String,
    enabled: Boolean,
    onOpened: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(questionNumber) { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { onOpened(); expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("matching-answer-$questionNumber"),
        ) {
            Text(selected.takeIf(String::isNotBlank)?.let { "答案 $it" } ?: "选择答案")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.id) },
                    onClick = { expanded = false; onSelect(option.id) },
                    modifier = Modifier.testTag("matching-option-$questionNumber-${option.id}"),
                )
            }
        }
    }
}
