package com.eenglish.listening.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.eenglish.listening.domain.model.Option
import com.eenglish.listening.domain.model.Question
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Owned by the question group, not the conditional popup, so reopening keeps its height. */
@Composable
internal fun rememberMatchingSheetHeight(groupKey: Any?): MutableFloatState =
    rememberSaveable(LocalAnswerSession.current, groupKey) { mutableFloatStateOf(0.55f) }

/** Only the handle resizes this modal. Its option list has no draggable/nested-scroll sheet parent. */
@Composable
fun MatchingOptionSheet(
    question: Question,
    options: List<Option>,
    selectedAnswer: String,
    editable: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    heightState: MutableFloatState = rememberMatchingSheetHeight(question.id),
) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var selecting by remember(question.id) { mutableStateOf(false) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(180)) }
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                progress.animateTo(0f, tween(150))
                latestDismiss()
            }
        }
    }
    Dialog(onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        // The content owns entry/exit animation; avoid stacking a platform transition on top.
        SideEffect { window?.setWindowAnimations(0) }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().testTag("matching-option-sheet-scrim")
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = dismiss))
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val minimum = (maxHeight * 0.30f).coerceAtLeast(minOf(maxHeight, 180.dp))
                val maximum = (maxHeight * 0.85f).coerceAtLeast(minimum)
                val panelHeight = (maxHeight * heightState.floatValue).coerceIn(minimum, maximum)
                val availablePx = with(density) { maxHeight.toPx() }
                val panelPx = with(density) { panelHeight.toPx() }
                val minimumRatio = if (maxHeight.value > 0) minimum / maxHeight else 1f
                val maximumRatio = if (maxHeight.value > 0) maximum / maxHeight else 1f
                val currentRatio = if (maxHeight.value > 0) panelHeight / maxHeight else 1f
                Surface(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth().height(panelHeight)
                    .graphicsLayer { translationY = (1f - progress.value) * panelPx }
                    .testTag("matching-option-sheet-${question.number}")
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 1.dp) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(48.dp)
                            .testTag("matching-option-sheet-handle")
                            .semantics {
                                contentDescription = "上下拖动调整选项卡高度"
                                stateDescription = "${(currentRatio * 100).roundToInt()}%"
                                progressBarRangeInfo = ProgressBarRangeInfo(currentRatio, minimumRatio..maximumRatio)
                                setProgress { value ->
                                    if (!closing) heightState.floatValue = value.coerceIn(minimumRatio, maximumRatio)
                                    true
                                }
                            }
                            .draggable(rememberDraggableState { delta ->
                                if (availablePx > 0f) heightState.floatValue =
                                    (heightState.floatValue.coerceIn(minimumRatio, maximumRatio) - delta / availablePx)
                                        .coerceIn(minimumRatio, maximumRatio)
                            }, Orientation.Vertical, enabled = !closing), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(36.dp, 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant,
                                RoundedCornerShape(2.dp)))
                        }
                        Text("Q${question.number} · 选择答案", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, bottom = 12.dp)
                                .testTag("matching-option-sheet-title-${question.number}"))
                        key(question.id) {
                            Column(Modifier.weight(1f).fillMaxWidth().testTag("matching-option-sheet-options")
                                .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                options.forEach { option ->
                                    val label = matchingOptionLabel(option)
                                    val chosen = selectedAnswer.equals(option.id, ignoreCase = true)
                                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                        .selectable(selected = chosen, enabled = editable && !selecting && !closing,
                                            role = Role.RadioButton, onClick = {
                                                if (!editable || selecting || closing) return@selectable
                                                selecting = true
                                                onSelect(option.id)
                                                dismiss()
                                            })
                                        .semantics { contentDescription = label }
                                        .testTag("matching-option-${question.number}-${option.id}")
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = chosen, onClick = null, enabled = editable && !selecting && !closing)
                                        Spacer(Modifier.width(12.dp))
                                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun matchingOptionLabel(option: Option): String {
    val text = option.text.trim()
    return if (text.isBlank() || text.equals(option.id, ignoreCase = true)) option.id else "${option.id} — $text"
}
