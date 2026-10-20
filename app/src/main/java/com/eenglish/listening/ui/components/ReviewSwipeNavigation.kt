package com.eenglish.listening.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Horizontal slop locks navigation to its axis; vertical scrolling remains independent. */
@Composable
internal fun Modifier.reviewSwipeNavigation(key: Any?, enabled: Boolean = true,
    onPrevious: () -> Unit, onNext: () -> Unit): Modifier {
    if (!enabled) return this
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    return pointerInput(key, threshold) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragCancel = { distance = 0f },
            onDragEnd = {
                if (distance <= -threshold) next()
                else if (distance >= threshold) previous()
                distance = 0f
            },
            onHorizontalDrag = { _, amount -> distance += amount }
        )
    }
}
