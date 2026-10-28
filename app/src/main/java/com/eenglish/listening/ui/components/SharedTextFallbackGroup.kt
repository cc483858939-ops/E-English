package com.eenglish.listening.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.domain.gapfill.SharedTextFallbackQuestionGroup

/** Keeps a conservative shared prompt visible once and reuses each original per-question text field. */
@Composable
fun SharedTextFallbackGroupView(
    group: SharedTextFallbackQuestionGroup,
    answers: Map<String, String>,
    editable: Boolean,
    submitted: Boolean,
    activeQuestionNumber: Int? = null,
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
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)
        .testTag("shared-text-fallback-${first.number}")) {
        AnnotatableText(promptDocument, otherDocuments = otherPromptDocuments,
            activeDocument = promptDocument, viewTag = "shared-text-fallback-prompt-${first.number}")
        if (isAdditionalSharedText(group.instructions, group.prompt, group.context)) {
            Text(group.instructions, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (isAdditionalSharedText(group.context, group.prompt, group.instructions)) {
            Text(group.context, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
        }
        group.questions.forEach { question ->
            Column(
                Modifier.fillMaxWidth()
                    .padding(top = 2.dp)
                    .background(
                        if (activeQuestionNumber == question.number)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(10.dp),
                    )
                    .questionJumpTarget(question.number).testTag("shared-text-answer-${question.number}"),
            ) {
                QuestionCard(
                    question = question,
                    selected = answers[question.id],
                    editable = editable,
                    showResult = submitted,
                    showPrompt = false,
                    showInputInstructions = false,
                    onSelect = { value -> onSelect(question.id, value) },
                )
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
