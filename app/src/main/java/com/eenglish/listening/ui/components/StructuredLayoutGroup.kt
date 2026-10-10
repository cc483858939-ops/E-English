package com.eenglish.listening.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.domain.gapfill.StructuredLayoutProjection
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.LayoutBlock
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionLayoutGroup

/** Renders schema structure as actual rows/paragraphs and delegates all answer input to the
 * existing native TextView + EditText overlay. */
@Composable
fun StructuredLayoutGroup(
    group: QuestionLayoutGroup,
    questions: List<Question>,
    answers: Map<String, String>,
    editable: Boolean,
    submitted: Boolean,
    saving: Boolean,
    dirty: Boolean,
    activeQuestionNumber: Int? = null,
    onActivate: (Int) -> Unit = {},
    onSelect: (String, String) -> Unit,
    onEdit: (String, String) -> Unit = onSelect,
    onCommit: (String) -> Unit = {},
) {
    val annotations = LocalAnnotations.current
    val incorrect = if (submitted) questions.filter { question ->
        val selected = answers[question.id].orEmpty()
        selected.isBlank() || !Grader.withinLimit(question, selected) ||
            question.acceptableAnswers.none { Grader.normalize(it) == Grader.normalize(selected) }
    }.map { it.number }.toSet() else emptySet()
    val first = questions.first().number
    val last = questions.last().number

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("structured-layout-$first-$last")) {
        key(LocalAnswerSession.current, group.groupId) {
            StructuredLayoutChildren(
                blocks = group.blocks,
                path = group.groupId,
                group = group,
                questions = questions,
                answers = answers,
                editable = editable,
                submitted = submitted,
                incorrect = incorrect,
                activeQuestionNumber = activeQuestionNumber,
                onActivate = onActivate,
                onEdit = onEdit,
                onCommit = onCommit,
            )
        }
        if (dirty && editable) {
            Text(if (saving) "正在保存答案…" else "草稿尚未保存",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (submitted) {
            HorizontalDivider()
            questions.forEach { question ->
                val answer = answers[question.id].orEmpty()
                val correct = question.number !in incorrect
                Text("Q${question.number} · ${if (correct) "正确" else "错误"} · 你的答案：${answer.ifBlank { "未作答" }}",
                    color = if (correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text("标准答案：${question.acceptableAnswers.joinToString(" / ")}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("result-${question.number}"))
            }
        }
    }
}

@Composable
private fun StructuredLayoutChildren(
    blocks: List<LayoutBlock>,
    path: String,
    group: QuestionLayoutGroup,
    questions: List<Question>,
    answers: Map<String, String>,
    editable: Boolean,
    submitted: Boolean,
    incorrect: Set<Int>,
    activeQuestionNumber: Int?,
    onActivate: (Int) -> Unit,
    onEdit: (String, String) -> Unit,
    onCommit: (String) -> Unit,
) {
    var index = 0
    while (index < blocks.size) {
        val block = blocks[index]
        if (block.type in setOf("text", "fixedText", "blank", "lineBreak")) {
            val start = index
            while (index < blocks.size && blocks[index].type in setOf("text", "fixedText", "blank", "lineBreak")) index++
            StructuredLayoutRun(
                blocks = blocks.subList(start, index),
                path = "$path-$start",
                group = group,
                questions = questions,
                answers = answers,
                editable = editable,
                submitted = submitted,
                incorrect = incorrect,
                activeQuestionNumber = activeQuestionNumber,
                onActivate = onActivate,
                onEdit = onEdit,
                onCommit = onCommit,
            )
            continue
        }
        when (block.type) {
            "paragraph" -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                StructuredLayoutChildren(block.children, "$path-$index", group, questions, answers,
                    editable, submitted, incorrect, activeQuestionNumber, onActivate, onEdit, onCommit)
            }
            "listItem" -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top) {
                Text(block.marker ?: "•", modifier = Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    StructuredLayoutChildren(block.children, "$path-$index", group, questions, answers,
                        editable, submitted, incorrect, activeQuestionNumber, onActivate, onEdit, onCommit)
                }
            }
            "formRow" -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                block.label?.takeIf { it.isNotBlank() }?.let {
                    Text(it, modifier = Modifier.widthIn(min = 72.dp, max = 144.dp).padding(end = 8.dp),
                        style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f)) {
                    StructuredLayoutChildren(block.children, "$path-$index", group, questions, answers,
                        editable, submitted, incorrect, activeQuestionNumber, onActivate, onEdit, onCommit)
                }
            }
            else -> Unit // The installer validator rejects unsupported block types.
        }
        index++
    }
}

@Composable
private fun StructuredLayoutRun(
    blocks: List<LayoutBlock>,
    path: String,
    group: QuestionLayoutGroup,
    questions: List<Question>,
    answers: Map<String, String>,
    editable: Boolean,
    submitted: Boolean,
    incorrect: Set<Int>,
    activeQuestionNumber: Int?,
    onActivate: (Int) -> Unit,
    onEdit: (String, String) -> Unit,
    onCommit: (String) -> Unit,
) {
    val annotations = LocalAnnotations.current
    val projection = remember(group.groupId, path, blocks) { StructuredLayoutProjection.from(blocks) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val typography = MaterialTheme.typography.titleMedium
    val fontPx = with(density) { typography.fontSize.toPx() }
    val linePx = with(density) { typography.lineHeight.toPx().toInt() }
    val runQuestions = remember(projection.slots, questions) {
        projection.slots.mapNotNull { slot -> questions.firstOrNull { it.id == slot.questionId } }.distinctBy { it.id }
    }
    if (projection.slots.isEmpty()) {
        // Keep native text selection, copy, and prompt-backed highlights for fixed prose too.
        val documents = remember(runQuestions) {
            runQuestions.map { AnnotationDocument.single(it.prompt, "question", "${it.id}/prompt", "und") }
        }
        val ranges = annotations.partId?.let { id ->
            documents.flatMap { it.visibleRanges(id, annotations.highlights) }
        }.orEmpty()
        val displayProjection = remember(blocks) { StructuredLayoutProjection.from(blocks) }
        AndroidView(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("structured-text-$path"),
            factory = { context -> NativeInlineGapFillView(context) },
            update = { view ->
                view.renderStructured(group, questions, displayProjection, answers, editable = false,
                    activeNumber = activeQuestionNumber, ranges = ranges, annotations = annotations,
                    fontPx = fontPx, linePx = linePx, onActivate = onActivate,
                    onEditSlot = { _, _, _ -> }, onCommit = onCommit,
                    submitted = submitted, incorrect = incorrect)
            }
        )
        return
    }
    val onEditSlot: (String, Int, String) -> Unit = { questionId, slotIndex, value ->
        val question = questions.firstOrNull { it.id == questionId }
        if (question != null) {
            val parts = Grader.answerParts(question, answers[questionId]).toMutableList()
            while (parts.size < (if (question.answerSeparator == null) 1 else 2)) parts += ""
            parts[slotIndex] = value
            val merged = if (question.answerSeparator == null) parts.firstOrNull().orEmpty()
            else if (parts.all(String::isBlank)) ""
            else "${parts[0]} ${question.answerSeparator} ${parts[1]}"
            onEdit(questionId, merged)
        }
    }
    val partId = annotations.partId
    val highlights = partId?.let { projection.visibleHighlights(it, group, questions, annotations.highlights) }.orEmpty()
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("structured-run-$path")) {
        key(LocalAnswerSession.current, "$path-${projection.slots.firstOrNull()?.questionId.orEmpty()}") {
            AndroidView(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                factory = { context -> NativeInlineGapFillView(context) },
                update = { view ->
                    view.renderStructured(group, questions, projection, answers, editable,
                        activeQuestionNumber, highlights, annotations, fontPx, linePx, onActivate,
                        onEditSlot, onCommit, submitted, incorrect)
                }
            )
        }
    }
}
