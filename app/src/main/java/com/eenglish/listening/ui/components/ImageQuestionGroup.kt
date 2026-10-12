package com.eenglish.listening.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.gapfill.SharedImageQuestionGroup
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.Question

@Composable
fun ImageQuestionGroup(
    group: SharedImageQuestionGroup,
    answers: Map<String, String>,
    editable: Boolean,
    submitted: Boolean,
    saving: Boolean,
    dirty: Boolean,
    activeQuestionNumber: Int? = null,
    onActivate: (Int) -> Unit = {},
    onSelect: (String, String) -> Unit,
) {
    val firstNumber = group.questions.first().number
    val lastNumber = group.questions.last().number
    val shownContext = group.context.trim().takeIf { context ->
        context.isNotEmpty() && !group.title.contains(context) && !group.instructions.contains(context)
    }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp)
            .testTag("image-question-group-$firstNumber-$lastNumber"),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(group.title, style = MaterialTheme.typography.titleLarge)
        Text(group.instructions, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp))
        shownContext?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
        }

        if (group.images.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            group.images.forEachIndexed { index, path ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                QuestionImage(path)
            }
            Spacer(Modifier.height(12.dp))
        }

        AnswerGrid(
            questions = group.questions,
            answers = answers,
            editable = editable,
            submitted = submitted,
            activeQuestionNumber = activeQuestionNumber,
            onActivate = onActivate,
            onSelect = onSelect,
        )

        if (dirty && editable) {
            Text(if (saving) "正在保存答案…" else "草稿尚未保存",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun AnswerGrid(
    questions: List<Question>,
    answers: Map<String, String>,
    editable: Boolean,
    submitted: Boolean,
    activeQuestionNumber: Int?,
    onActivate: (Int) -> Unit,
    onSelect: (String, String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 136.dp * 2 + 8.dp) 2 else 1
        val rows = if (columns == 2) questions.chunked(2) else questions.map { listOf(it) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { rowQuestions ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top) {
                    rowQuestions.forEach { question ->
                        AnswerCell(
                            question = question,
                            selected = answers[question.id].orEmpty(),
                            editable = editable,
                            submitted = submitted,
                            active = activeQuestionNumber == question.number,
                            modifier = Modifier.weight(1f),
                            onActivate = onActivate,
                            onSelect = { value -> onSelect(question.id, value) },
                        )
                    }
                    if (columns == 2 && rowQuestions.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AnswerCell(
    question: Question,
    selected: String,
    editable: Boolean,
    submitted: Boolean,
    active: Boolean,
    modifier: Modifier,
    onActivate: (Int) -> Unit,
    onSelect: (String) -> Unit,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Q${question.number}", style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(min = 34.dp).padding(end = 4.dp)
                    .testTag("image-question-number-${question.number}"))
            ImageAnswerInput(
                selected = selected,
                question = question,
                editable = editable,
                modifier = Modifier.weight(1f),
                onActivate = onActivate,
                onSelect = onSelect,
            )
        }
        if (submitted) {
            val isCorrect = selected.isNotBlank() && Grader.withinLimit(question, selected) &&
                question.acceptableAnswers.any { Grader.normalize(it) == Grader.normalize(selected) }
            Text("你的答案：${selected.ifBlank { "未作答" }}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp))
            Text("标准答案：${question.acceptableAnswers.joinToString(" / ")}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("result-${question.number}"))
        }
    }
}

@Composable
private fun ImageAnswerInput(
    selected: String,
    question: Question,
    editable: Boolean,
    modifier: Modifier,
    onActivate: (Int) -> Unit,
    onSelect: (String) -> Unit,
) {
    var draft by rememberSaveable(LocalAnswerSession.current, question.id) { mutableStateOf(selected) }
    var focused by remember(question.id) { mutableStateOf(false) }
    LaunchedEffect(selected, editable) {
        if (!focused || !editable) draft = selected
    }

    val shape = RoundedCornerShape(6.dp)
    val colors = MaterialTheme.colorScheme
    val outlineColor = when {
        focused && editable -> colors.primary
        editable -> colors.outline
        else -> colors.outlineVariant
    }
    BasicTextField(
        value = draft,
        onValueChange = { value ->
            if (value.length <= 512) {
                draft = value
                onSelect(value)
            }
        },
        enabled = editable,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
            .background(colors.surface)
            .border(1.dp, outlineColor, shape)
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) onActivate(question.number)
            }
            .semantics { contentDescription = "Q${question.number} 答案" }
            .testTag("image-input-${question.number}"),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) {
                    Text("填写答案", style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant)
                }
                innerTextField()
            }
        },
    )

}
