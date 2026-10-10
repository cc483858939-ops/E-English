package com.eenglish.listening.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.domain.gapfill.GapFillGroup
import com.eenglish.listening.domain.gapfill.InlinePromptProjection
import com.eenglish.listening.domain.grading.Grader

/** Single native TextView layout with five real, focusable EditTexts in its inline glyph slots. */
@Composable
fun InlineGapFillGroup(group: GapFillGroup, answers: Map<String, String>,
    editable: Boolean, submitted: Boolean, saving: Boolean,
    activeQuestionNumber: Int? = null, onActivate: (Int) -> Unit = {},
    onSelect: (String, String) -> Unit,
    onEdit: (String, String) -> Unit = onSelect, onCommit: (String) -> Unit = {},
    dirty: Boolean = false) {
    val annotations = LocalAnnotations.current
    val projection = remember(group) { InlinePromptProjection.of(group) }
    val documents = remember(group) {
        group.questions.map { AnnotationDocument.single(it.prompt, "question", "${it.id}/prompt", "und") }
    }
    val highlights = annotations.partId?.let { id ->
        documents.flatMap { it.visibleRanges(id, annotations.highlights) }
    }.orEmpty()
    val density = LocalDensity.current
    val typography = MaterialTheme.typography.titleMedium
    val fontPx = with(density) { typography.fontSize.toPx() }
    val linePx = with(density) { typography.lineHeight.toPx().toInt() }
    val incorrect = if (submitted) group.questions.filter { q ->
        val selected = answers[q.id].orEmpty()
        selected.isBlank() || !Grader.withinLimit(q, selected) ||
            q.acceptableAnswers.none { Grader.normalize(it) == Grader.normalize(selected) }
    }.map { it.number }.toSet() else emptySet()
    val first = group.questions.first().number
    val last = group.questions.last().number
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)
        .testTag("inline-group-$first-$last")) {
        // A different Room attempt must never inherit a focused editor or stale IME text.
        key(LocalAnswerSession.current, group.questions.first().id) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    .testTag("inline-summary-$first"),
                factory = { context -> NativeInlineGapFillView(context) },
                update = { view ->
                    view.render(group, projection, answers, editable, activeQuestionNumber, highlights,
                        annotations, fontPx, linePx, onActivate, onEdit, onCommit,
                        submitted = submitted, incorrect = incorrect)
                }
            )
        }
        if (dirty && editable) {
            Text(if (saving) "正在保存答案…" else "草稿尚未保存",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (submitted) {
            HorizontalDivider()
            group.questions.forEach { q ->
                val answer = answers[q.id].orEmpty()
                val correct = q.number !in incorrect
                Text("Q${q.number} · ${if (correct) "正确" else "错误"} · 你的答案：${answer.ifBlank { "未作答" }}",
                    color = if (correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text("标准答案：${q.acceptableAnswers.joinToString(" / ")}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("result-${q.number}"))
            }
        }
    }
}
