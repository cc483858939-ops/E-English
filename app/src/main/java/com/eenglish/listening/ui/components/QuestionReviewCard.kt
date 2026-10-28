package com.eenglish.listening.ui.components

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.grading.*
import com.eenglish.listening.domain.model.Question

data class ReviewInteraction(val items: List<QuestionReview>, val activeId: String, val select: (String) -> Unit,
    val locateActive: Boolean = true, val onLocated: () -> Unit = {}) {
    fun forQuestion(id: String) = items.firstOrNull { id in it.questionIds }
}
val LocalQuestionReview = staticCompositionLocalOf<ReviewInteraction?> { null }

internal fun ReviewStatus.color(): Color = when (this) {
    ReviewStatus.CORRECT -> Color(0xFF287847)
    ReviewStatus.INCORRECT -> Color(0xFFB3261E)
    ReviewStatus.UNANSWERED -> Color(0xFF665F53)
}

@Composable
fun ReviewResultContent(item: QuestionReview, showUserAnswer: Boolean = false) {
    var showVariants by remember(item.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("review-result-${item.id}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(item.status.label, color = item.status.color(), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("review-status"))
        if (item.questions.size > 1) Text("本组答对 ${item.correctCount} / ${item.questions.size}", style = MaterialTheme.typography.bodyMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("正确答案", style = MaterialTheme.typography.labelLarge)
            Text(item.standardAnswer, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("review-standard-answer"))
        }
        if (showUserAnswer || item.status != ReviewStatus.CORRECT) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("你的答案", style = MaterialTheme.typography.labelLarge)
                Text(item.userAnswer.ifBlank { "未作答" }, color = item.status.color(),
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("review-user-answer"))
            }
        }
        if (item.status == ReviewStatus.UNANSWERED && item.userAnswer.isNotBlank())
            Text("本题尚未填完整", style = MaterialTheme.typography.bodyMedium)
        if (item.acceptedVariants.isNotEmpty()) {
            TextButton(onClick = { showVariants = !showVariants }, modifier = Modifier.testTag("review-variants-toggle")) {
                Text(if (showVariants) "收起其他可接受答案" else "其他可接受答案（${item.acceptedVariants.size}）")
            }
            if (showVariants) Text(item.acceptedVariants.joinToString(" / "), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("review-accepted-variants"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewQuestionStrip(questions: List<Question>) {
    val review = LocalQuestionReview.current ?: return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        questions.mapNotNull { review.forQuestion(it.id) }.distinctBy { it.id }.forEach { item ->
            OutlinedButton(onClick = { review.select(item.id) }, modifier = Modifier.testTag("review-question-${item.questions.first().number}"),
                border = androidx.compose.foundation.BorderStroke(if (review.activeId == item.id) 2.dp else 1.dp, item.status.color())) {
                Text("${item.title} · ${item.status.label}", color = item.status.color())
            }
        }
    }
}

@Composable
fun ReviewAnswerToken(question: Question, text: String, modifier: Modifier = Modifier,
    numberLabel: String = question.number.toString(), singleLine: Boolean = false) {
    val review = LocalQuestionReview.current ?: return
    val item = review.forQuestion(question.id) ?: return
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(review.activeId) {
        if (review.activeId == item.id && review.locateActive) {
            withFrameNanos { }; bring.bringIntoView(); review.onLocated()
        }
    }
    Box(modifier.bringIntoViewRequester(bring).heightIn(min = 48.dp).widthIn(min = 96.dp)
        .border(if (review.activeId == item.id) 2.dp else 1.dp, item.status.color(), RoundedCornerShape(6.dp))
        .clickable(role = Role.Button) { review.select(item.id) }
        .semantics { contentDescription = "Q${question.number}，${item.status.label}，${text.ifBlank { "未作答" }}" }
        .testTag("review-answer-${question.number}").padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(numberLabel, color = item.status.color(), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(text.ifBlank { "未作答" }, color = item.status.color(), style = MaterialTheme.typography.bodyLarge,
                maxLines = if (singleLine) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionReviewCard(items: List<QuestionReview>, activeId: String, onSelect: (String) -> Unit,
    onResize: (Float) -> Unit, modifier: Modifier = Modifier) {
    val item = items.firstOrNull { it.id == activeId } ?: return
    var expanded by remember { mutableStateOf(true) }
    var onlyWrong by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    BackHandler(enabled = expanded && !showJump) { expanded = false }
    LaunchedEffect(item.id) { if (onlyWrong && item.status == ReviewStatus.CORRECT) onlyWrong = false }
    val visible = if (onlyWrong) items.filter { it.status != ReviewStatus.CORRECT } else items
    val index = visible.indexOfFirst { it.id == item.id }
    val previous = { visible.getOrNull(index - 1)?.let { onSelect(it.id) }; Unit }
    val next = { visible.getOrNull(index + 1)?.let { onSelect(it.id) }; Unit }
    if (showJump) AlertDialog(onDismissRequest = { showJump = false }, title = { Text("选择复盘题目") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            items.forEach { entry -> TextButton(onClick = { onlyWrong = false; onSelect(entry.id); showJump = false }) {
                Text("${entry.title} · ${entry.status.label}", color = entry.status.color())
            } }
        } }, confirmButton = { TextButton(onClick = { showJump = false }) { Text("关闭") } })
    Surface(modifier = if (expanded) modifier.fillMaxWidth().testTag("question-review-card")
        else Modifier.fillMaxWidth().testTag("question-review-card"),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 6.dp) {
        Column {
            Box(Modifier.fillMaxWidth().height(24.dp).testTag("review-drag-handle")
                .draggable(rememberDraggableState(onResize), Orientation.Vertical), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
            }
            Row(Modifier.fillMaxWidth().background(item.status.color().copy(alpha = 0.10f)).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("${items.indexOf(item) + 1}/${items.size}", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { showJump = true }, modifier = Modifier.testTag("review-jump")) { Text("题号") }
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("review-toggle")) { Text(if (expanded) "收起" else "展开") }
            }
            if (expanded) {
                key(item.id) {
                    Column(Modifier.weight(1f).fillMaxWidth()
                        .reviewSwipeNavigation(item.id to visible.map { it.id }, onPrevious = previous, onNext = next)
                        .verticalScroll(rememberScrollState()).padding(20.dp)
                        .testTag("review-scroll")) { ReviewResultContent(item) }
                }
                HorizontalDivider()
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = previous, enabled = index > 0,
                        modifier = Modifier.testTag("review-previous")) { Text("上一题") }
                    TextButton(onClick = {
                        onlyWrong = !onlyWrong
                        if (onlyWrong && item.status == ReviewStatus.CORRECT) items.firstOrNull { it.status != ReviewStatus.CORRECT }?.let { onSelect(it.id) }
                    }, enabled = items.any { it.status != ReviewStatus.CORRECT }, modifier = Modifier.testTag("review-only-wrong")) {
                        Text(if (onlyWrong) "查看全部" else "只看错题")
                    }
                    TextButton(onClick = next, enabled = index >= 0 && index < visible.lastIndex,
                        modifier = Modifier.testTag("review-next")) { Text("下一题") }
                }
            }
        }
    }
}
