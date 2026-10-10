package com.eenglish.listening.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.domain.gapfill.GapFillGroup
import com.eenglish.listening.domain.grading.Grader

/** One untouched prompt, with native inline replacement spans and the existing annotation keys. */
@Composable
fun InlineGapFillGroup(group: GapFillGroup, answers: Map<String, String>,
    editable: Boolean, submitted: Boolean, saving: Boolean,
    activeQuestionNumber: Int? = null, onActivate: (Int) -> Unit = {},
    onSelect: (String, String) -> Unit) {
    val anchor = group.questions.first()
    val session = LocalAnswerSession.current
    var editingNumber by rememberSaveable(session, anchor.id) { mutableStateOf<Int?>(null) }
    var annotationNumber by rememberSaveable(session, anchor.id) { mutableIntStateOf(anchor.number) }
    val annotationDocs = remember(group) {
        group.questions.map { AnnotationDocument.single(it.prompt, "question", "${it.id}/prompt", "und") }
    }
    val activeNumber = activeQuestionNumber ?: annotationNumber
    val visuals = group.gaps.map { gap ->
        val question = group.questions.first { it.number == gap.number }
        val answer = answers[question.id].orEmpty()
        InlineAnswerGap(gap.number, gap.start, gap.end, answer, gap.number == activeNumber,
            if (submitted) answer.isNotBlank() && Grader.withinLimit(question, answer) &&
                question.acceptableAnswers.any { Grader.normalize(it) == Grader.normalize(answer) } else null)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)
        .testTag("inline-group-${anchor.number}-${group.questions.last().number}")) {
        AnnotatableText(annotationDocs.first(), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 16.dp)
                .testTag("inline-summary-${anchor.number}"),
            viewTag = "inline-prompt-${anchor.number}", inlineGaps = visuals,
            otherDocuments = annotationDocs.drop(1),
            activeDocument = annotationDocs[group.questions.indexOfFirst { it.number == activeNumber }
                .coerceAtLeast(0)],
            onGapClick = { number ->
                annotationNumber = number
                onActivate(number)
                editingNumber = number
            })
        if (submitted) {
            HorizontalDivider()
            group.questions.forEach { q ->
                val answer = answers[q.id].orEmpty()
                val correct = answer.isNotBlank() && Grader.withinLimit(q, answer) &&
                    q.acceptableAnswers.any { Grader.normalize(it) == Grader.normalize(answer) }
                Text("Q${q.number} · ${if (correct) "正确" else "错误"} · 你的答案：${answer.ifBlank { "未作答" }}",
                    color = if (correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp))
                Text("标准答案：${q.acceptableAnswers.joinToString(" / ")}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("result-${q.number}"))
            }
        }
    }
    val editingQuestion = group.questions.firstOrNull { it.number == editingNumber }
    if (editingQuestion != null) {
        GapFillAnswerDialog(editingQuestion, answers[editingQuestion.id], editable, saving,
            onSave = { onSelect(editingQuestion.id, it) },
            onDismiss = { editingNumber = null }, submitted = submitted)
    }
}
