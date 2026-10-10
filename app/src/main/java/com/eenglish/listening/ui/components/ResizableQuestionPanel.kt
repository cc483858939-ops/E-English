package com.eenglish.listening.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.gapfill.GapFillGroup
import kotlin.math.roundToInt

@Composable
fun ResizableQuestionPanel(question: Question, selected: String?, expanded: Boolean, panelHeight: Dp,
    minimumPanelHeight: Dp, headerHeight: Dp, fixedNavigation: Boolean, questionScroll: ScrollState,
    heightRatio: Float, ratioRange: ClosedFloatingPointRange<Float>,
    editable: Boolean, submitted: Boolean, hasPrevious: Boolean, hasNext: Boolean,
    onExpand: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onJump: () -> Unit,
    onSelect: (String) -> Unit, onHeaderSize: (Int) -> Unit,
    onResizeStarted: () -> Unit, onResizeDelta: (Float) -> Unit, onResizeStopped: () -> Unit,
    onSetRatio: (Float) -> Unit, inlineGroup: GapFillGroup? = null,
    answers: Map<String, String> = emptyMap(), saving: Boolean = false,
    onGroupSelect: (String, String) -> Unit = { _, _ -> }, onActivate: (Int) -> Unit = {}) {
    val navigation: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onPrevious, enabled = hasPrevious,
                modifier = Modifier.testTag("transcript-previous")) { Text("上一题") }
            TextButton(onClick = onJump, modifier = Modifier.testTag("transcript-jump")) { Text("跳转题号") }
            TextButton(onClick = onNext, enabled = hasNext,
                modifier = Modifier.testTag("transcript-next")) { Text("下一题") }
        }
    }
    val chromeHeight = headerHeight + 1.dp + if (fixedNavigation) 48.dp else 0.dp
    val questionHeight = (panelHeight - chromeHeight).coerceAtLeast(1.dp)
    // This viewport grows with the panel, so its tail must grow by the same amount.
    val questionEndSpace = (panelHeight - minimumPanelHeight).coerceAtLeast(0.dp)
    Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().height(panelHeight).testTag("transcript-question-panel")) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).onSizeChanged { onHeaderSize(it.height) },
                verticalAlignment = Alignment.CenterVertically) {
                Text("Q.${question.number} · ${selected?.let { "已选 $it" } ?: "未作答"}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("transcript-question-summary"))
                if (expanded) {
                    Box(Modifier.width(64.dp).height(48.dp).testTag("transcript-drag-handle")
                        .semantics {
                            contentDescription = "上下拖动调整答题卡高度"
                            stateDescription = "${(heightRatio * 100).roundToInt()}%"
                            progressBarRangeInfo = ProgressBarRangeInfo(heightRatio, ratioRange)
                            setProgress { onSetRatio(it); true }
                        }
                        .draggable(state = rememberDraggableState(onResizeDelta), orientation = Orientation.Vertical,
                            onDragStarted = { onResizeStarted() }, onDragStopped = { onResizeStopped() }),
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.width(32.dp).height(4.dp).background(Color.Gray, RoundedCornerShape(2.dp)))
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = onExpand, modifier = Modifier.testTag("transcript-card-toggle")) {
                        Text(if (expanded) "收起" else "展开")
                    }
                }
            }
            if (expanded) {
                if (fixedNavigation) navigation()
                HorizontalDivider()
                Column(Modifier.height(questionHeight).fillMaxWidth().testTag("transcript-question-body")
                    .verticalScroll(questionScroll).padding(start = 4.dp, end = 4.dp, bottom = questionEndSpace)) {
                    if (!fixedNavigation) navigation()
                    if (submitted) Text("已提交 · 只读", style = MaterialTheme.typography.labelMedium)
                    if (inlineGroup != null) {
                        InlineGapFillGroup(inlineGroup, answers, editable && !saving, submitted, saving,
                            activeQuestionNumber = question.number, onActivate = onActivate, onSelect = onGroupSelect)
                    } else {
                        QuestionCard(question, selected, editable, showResult = submitted, onSelect = onSelect)
                    }
                }
            }
        }
    }
}
