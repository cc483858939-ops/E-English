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
import com.eenglish.listening.domain.gapfill.MatchingInlineProjection
import com.eenglish.listening.domain.gapfill.MatchingInlineSlot
import com.eenglish.listening.domain.gapfill.MatchingQuestionRow
import com.eenglish.listening.domain.gapfill.SharedMatchingQuestionGroup
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.Question

/** Shows validated Matching groups in-place where safe and retains a complete shared fallback. */
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
    val matchingSheetHeight = rememberMatchingSheetHeight(group.questions.map { it.id })
    if (submitted) ReviewQuestionStrip(group.questions)
    val first = group.questions.first()
    val promptDocuments = remember(group.questions, group.prompt) {
        group.questions.map { question ->
            question.id to AnnotationDocument.single(group.prompt, "question", "${question.id}/prompt", "und")
        }.toMap()
    }
    val allPromptDocuments = remember(promptDocuments) { promptDocuments.values.toList() }
    val promptDocument = promptDocuments.getValue(first.id)
    var openQuestionId by remember(group.questions.map { it.id }) { mutableStateOf<String?>(null) }
    val openQuestion = group.questions.firstOrNull { it.id == openQuestionId }

    LaunchedEffect(editable, submitted, group.questions.map { it.id }) {
        if (!editable || submitted || openQuestionId !in group.questions.map { it.id }) openQuestionId = null
    }

    val useQuestionRows = group.questionRows != null && (group.inlineSlots == null || group.images.isNotEmpty())
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("shared-matching-${first.number}")) {
        when {
            useQuestionRows -> {
                val rows = requireNotNull(group.questionRows)
                val prefixEnd = trimmedPrefixEnd(group.prompt, rows.first().start)
                if (prefixEnd > 0) {
                    val prefixProjection = remember(group.prompt, prefixEnd) {
                        MatchingInlineProjection.sourceRange(group.prompt, 0, prefixEnd)
                    }
                    val prefixDocument = remember(prefixProjection) {
                        AnnotationDocument.single(prefixProjection.text, "question", "${first.id}/prefix", "und")
                    }
                    AnnotatableText(
                        prefixDocument,
                        otherDocuments = emptyList(),
                        activeDocument = promptDocuments[activeQuestionNumber?.let { number ->
                            group.questions.firstOrNull { it.number == number }?.id
                        }] ?: promptDocument,
                        viewTag = "shared-matching-prefix-${first.number}",
                        matchingProjection = prefixProjection,
                        sourceDocuments = allPromptDocuments,
                    )
                }
                renderAdditionalPromptText(group)
                if (!group.optionsInPrompt) renderSharedOptions(group)
                renderImages(group)
                rows.forEach { row ->
                    val question = group.questions.first { it.id == row.questionId }
                    val slot = group.inlineSlots?.firstOrNull { it.questionId == question.id }
                    MatchingQuestionRowView(
                        group = group,
                        row = row,
                        question = question,
                        slot = slot,
                        answer = answers[question.id].orEmpty(),
                        editable = editable && !submitted,
                        submitted = submitted,
                        active = activeQuestionNumber == question.number,
                        sourceDocuments = allPromptDocuments,
                        sourceDocument = promptDocuments.getValue(question.id),
                        onActivate = onActivate,
                        onOpen = { openQuestionId = question.id },
                    )
                }
            }

            group.inlineSlots != null -> {
                val activeDocument = group.questions.firstOrNull { it.number == activeQuestionNumber }
                    ?.let { promptDocuments[it.id] } ?: promptDocument
                val inlineGaps = group.inlineSlots.map { slot ->
                    matchingGap(slot, answers[slot.questionId].orEmpty(), activeQuestionNumber == slot.number,
                        submittedAnswerCorrect(group.questions.first { it.id == slot.questionId }, answers[slot.questionId].orEmpty(), submitted))
                }
                AnnotatableText(
                    document = promptDocument,
                    otherDocuments = allPromptDocuments.filterNot { it == promptDocument },
                    activeDocument = activeDocument,
                    inlineGaps = inlineGaps,
                    onGapClick = if (editable && !submitted) { number ->
                            onActivate(number)
                            group.questions.firstOrNull { it.number == number }?.let { openQuestionId = it.id }
                        } else null,
                    viewTag = "shared-matching-prompt-${first.number}",
                )
                renderAdditionalPromptText(group)
                if (!group.optionsInPrompt) renderSharedOptions(group)
                renderImages(group)
                if (submitted) {
                    group.questions.forEach { question ->
                        MatchingAnswerStatus(question, answers[question.id].orEmpty())
                    }
                }
            }

            else -> {
                // Third-layer fallback: preserve the whole original shared prompt and every answer.
                AnnotatableText(
                    document = promptDocument,
                    otherDocuments = allPromptDocuments.filterNot { it == promptDocument },
                    activeDocument = promptDocuments[activeQuestionNumber?.let { number ->
                        group.questions.firstOrNull { it.number == number }?.id
                    }] ?: promptDocument,
                    viewTag = "shared-matching-prompt-${first.number}",
                )
                renderAdditionalPromptText(group)
                if (!group.optionsInPrompt) renderSharedOptions(group)
                renderImages(group)
                Column(Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    group.questions.forEach { question ->
                        val selected = answers[question.id].orEmpty()
                        Row(
                            Modifier.fillMaxWidth()
                                .background(
                                    if (activeQuestionNumber == question.number) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    else MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { onActivate(question.number) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Q.${question.number}", style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.width(64.dp).testTag("matching-question-${question.number}"))
                            MatchingAnswerButton(
                                questionNumber = question.number,
                                selected = selected,
                                enabled = editable && !submitted,
                                onOpen = { onActivate(question.number); openQuestionId = question.id },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (submitted) MatchingAnswerStatus(question, selected)
                    }
                }
            }
        }
    }

    openQuestion?.let { question ->
        if (editable && !submitted) {
            MatchingOptionSheet(
                question = question,
                options = group.options,
                selectedAnswer = answers[question.id].orEmpty(),
                editable = editable && !submitted,
                onSelect = { optionId -> onSelect(question.id, optionId) },
                onDismiss = { openQuestionId = null },
                heightState = matchingSheetHeight,
            )
        }
    }
}

@Composable
private fun MatchingQuestionRowView(
    group: SharedMatchingQuestionGroup,
    row: MatchingQuestionRow,
    question: Question,
    slot: MatchingInlineSlot?,
    answer: String,
    editable: Boolean,
    submitted: Boolean,
    active: Boolean,
    sourceDocuments: List<AnnotationDocument>,
    sourceDocument: AnnotationDocument,
    onActivate: (Int) -> Unit,
    onOpen: () -> Unit,
) {
    val projection = remember(group.prompt, row.start, row.end) {
        MatchingInlineProjection.sourceRange(group.prompt, row.start, row.end)
    }
    val displayDocument = remember(projection) {
        AnnotationDocument.single(projection.text, "question", "matching-row:${question.id}", "und")
    }
    val localGap = slot?.let {
        matchingGap(it.copy(start = it.start - row.start, end = it.end - row.start), answer, active,
            submittedAnswerCorrect(question, answer, submitted))
    }
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnnotatableText(
            document = displayDocument,
            modifier = Modifier.weight(1f),
            inlineGaps = listOfNotNull(localGap),
            onGapClick = if (slot != null && editable) { _ -> onActivate(question.number); onOpen() } else null,
            activeDocument = sourceDocument,
            matchingProjection = projection,
            sourceDocuments = sourceDocuments,
            onTap = { onActivate(question.number) },
            viewTag = "shared-matching-row-${question.number}",
        )
        if (slot == null) {
            Spacer(Modifier.width(8.dp))
            MatchingAnswerButton(
                questionNumber = question.number,
                selected = answer,
                enabled = editable,
                onOpen = { onActivate(question.number); onOpen() },
                modifier = Modifier.widthIn(min = 92.dp, max = 136.dp),
            )
        }
    }
    if (submitted) MatchingAnswerStatus(question, answer)
}

private fun matchingGap(
    slot: MatchingInlineSlot,
    answer: String,
    active: Boolean,
    correct: Boolean?,
) = InlineAnswerGap(
    number = slot.number,
    start = slot.start,
    end = slot.end,
    answer = answer,
    active = active,
    correct = correct,
    emptyLabel = "选择",
    accessibilityLabel = "选择 Q${slot.number}",
    trailingIndicator = "▾",
    minimumTouchHeightDp = 48,
)

private fun submittedAnswerCorrect(question: Question, answer: String, submitted: Boolean): Boolean? =
    if (!submitted) null else answer.isNotBlank() && question.acceptableAnswers.any {
        Grader.normalize(it) == Grader.normalize(answer)
    }

@Composable
private fun MatchingAnswerButton(
    questionNumber: Int,
    selected: String,
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayed = selected.takeIf(String::isNotBlank)?.let { "$it ▾" } ?: "选择 ▾"
    OutlinedButton(
        onClick = onOpen,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).testTag("matching-answer-$questionNumber"),
    ) {
        Text(displayed, maxLines = 1)
    }
}

@Composable
private fun MatchingAnswerStatus(question: Question, answer: String) {
    if (LocalQuestionReview.current != null) return
    val correct = submittedAnswerCorrect(question, answer, submitted = true) == true
    Text(
        "Q${question.number} 你的答案：${answer.ifBlank { "未作答" }} · 标准答案：${question.acceptableAnswers.joinToString(" / ")}",
        color = if (correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 4.dp),
    )
}

@Composable
private fun renderAdditionalPromptText(group: SharedMatchingQuestionGroup) {
    if (isAdditionalSharedText(group.instructions, group.prompt, group.context)) {
        Text(group.instructions, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp))
    }
    if (isAdditionalSharedText(group.context, group.prompt, group.instructions)) {
        Text(group.context, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun renderSharedOptions(group: SharedMatchingQuestionGroup) {
    if (group.options.all { it.text.trim() == it.id }) {
        Text("可选字母：${group.options.joinToString("、") { it.id }}",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    } else {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            group.options.forEach { option ->
                val text = "${option.id}. ${option.text}"
                val first = group.questions.first()
                val document = remember(group.questions, option, text) {
                    AnnotationDocument.single(text, "question", "${first.id}/option:${option.id}", "und")
                }
                val others = remember(group.questions, option, text) {
                    group.questions.drop(1).map { question ->
                        AnnotationDocument.single(text, "question", "${question.id}/option:${option.id}", "und")
                    }
                }
                AnnotatableText(
                    document,
                    otherDocuments = others,
                    activeDocument = document,
                    viewTag = "shared-matching-option-${option.id}",
                )
            }
        }
    }
}

@Composable
private fun renderImages(group: SharedMatchingQuestionGroup) {
    group.images.forEach { path ->
        Box(Modifier.fillMaxWidth().padding(top = 12.dp)) { QuestionImage(path) }
    }
}

private fun trimmedPrefixEnd(prompt: String, firstRowStart: Int): Int {
    var end = firstRowStart
    while (end > 0 && prompt[end - 1].isWhitespace()) end--
    return end
}

private fun isAdditionalSharedText(candidate: String, prompt: String, other: String): Boolean {
    val normalized = candidate.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) return false
    val promptText = prompt.replace(Regex("\\s+"), " ").trim()
    val otherText = other.replace(Regex("\\s+"), " ").trim()
    return !promptText.contains(normalized, ignoreCase = true) && !otherText.contains(normalized, ignoreCase = true)
}
