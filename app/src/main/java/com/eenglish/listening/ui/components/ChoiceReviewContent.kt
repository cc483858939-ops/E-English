package com.eenglish.listening.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.domain.annotation.AnnotationProjection
import com.eenglish.listening.domain.annotation.contentHash
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.grading.QuestionReview
import com.eenglish.listening.domain.layout.V4AnnotationSources
import com.eenglish.listening.domain.layout.choiceReviewMaterial
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/** A read-only review, separate from answering controls and their save callbacks. */
@Composable
internal fun ChoiceReviewContent(item: QuestionReview, answers: Map<String, String>,
    group: JsonObject?, groupQuestions: List<Question>) {
    val question = item.questions.first()
    val material = remember(group, groupQuestions, item.questions) {
        // Historical attempts keep their frozen prompts if an imported layout has since changed.
        group?.takeIf { candidate ->
            val hashes = candidate["promptHashes"] as? JsonObject
            item.questions.all { hashes?.get(it.id)?.jsonPrimitive?.contentOrNull == contentHash(it.prompt) }
        }?.let { choiceReviewMaterial(it, groupQuestions, item.questionIds.toSet()) }
    }
    @Composable fun ProjectedText(part: AnnotationProjection, tag: String, small: Boolean = false) {
        AnnotatableText(remember(part.text) { AnnotationDocument.single(part.text, "question", "${question.id}/prompt", "und") },
            projection = part, viewTag = tag, modifier = Modifier.testTag(tag),
            style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("choice-review-content-${question.number}"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (material != null) {
            material.instructions.forEachIndexed { index, text -> ProjectedText(text, "choice-review-instruction-$index", true) }
            material.images.forEach { path -> QuestionImage(path) }
            material.paragraphs.forEachIndexed { index, text -> ProjectedText(text, "choice-review-prompt-$index") }
            if (material.paragraphs.isEmpty() && material.images.isEmpty()) {
                AnnotatableText(remember(question) { AnnotationDocument.single(question.prompt, "question", "${question.id}/prompt", "und") },
                    viewTag = "choice-review-prompt")
            }
        } else {
            if (question.instructions.isNotBlank()) AnnotatableText(remember(question) {
                AnnotationDocument.single(question.instructions, "question", "${question.id}/instructions", "und") },
                style = MaterialTheme.typography.bodySmall)
            AnnotatableText(remember(question) { AnnotationDocument.single(question.prompt, "question", "${question.id}/prompt", "und") },
                viewTag = "choice-review-prompt")
            question.images.forEach { QuestionImage(it) }
        }
        val multiple = question.type == QuestionType.MULTIPLE_CHOICE
        if (multiple) Text("选择 ${item.questions.size} 项，顺序不限", style = MaterialTheme.typography.labelLarge)
        val chosen = (if (multiple) Grader.selection(answers[question.id]) else setOf(answers[question.id].orEmpty()))
            .map { it.uppercase(Locale.ROOT) }.toSet()
        val correct = item.questions.flatMap { it.acceptableAnswers }.map { it.uppercase(Locale.ROOT) }.toSet()
        question.options.forEach { option ->
            val id = option.id.uppercase(Locale.ROOT)
            val isCorrect = id in correct
            val isChosen = id in chosen
            val ink = when { isCorrect -> Color(0xFF287847); isChosen -> Color(0xFFB3261E); else -> MaterialTheme.colorScheme.onSurfaceVariant }
            Row(Modifier.fillMaxWidth().testTag("choice-review-option-${question.number}-${option.id}").semantics(mergeDescendants = true) {
                contentDescription = "${option.id}，${if (isCorrect) "正确选项" else if (isChosen) "你的错误选项" else "未选择"}"
                selected = isChosen
                stateDescription = if (isCorrect) "正确选项" else if (isChosen) "你的错误选项" else "未选择"
            }, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = if (isCorrect || isChosen) ink else Color.Transparent,
                    contentColor = if (isCorrect || isChosen) Color.White else ink, border = BorderStroke(1.dp, ink),
                    modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(option.id, style = MaterialTheme.typography.titleMedium) }
                }
                val projection = remember(option, groupQuestions, item.id) {
                    V4AnnotationSources.option(option, groupQuestions.ifEmpty { item.questions }, option.text)
                }
                AnnotatableText(remember(option) { AnnotationDocument.single(option.text, "question", "${question.id}/option:${option.id}", "und") },
                    projection = projection, color = ink, viewTag = "choice-review-option-text-${question.number}-${option.id}",
                    modifier = Modifier.weight(1f).padding(top = 6.dp))
            }
        }
        HorizontalDivider()
        ReviewResultContent(item, showUserAnswer = true)
    }
}
