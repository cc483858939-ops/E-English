package com.eenglish.listening.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val structuralTypes = setOf("paragraph", "listItem", "formRow")

/** Computes one gap for neighboring blocks; an explicit line break never adds to block spacing. */
internal fun structuredLayoutGapDp(
    previousType: String?,
    currentType: String?,
    pendingLineBreaks: Int,
): Dp {
    // A trailing break has no following content to separate, so it has no visible spacing.
    if (currentType == null) return 0.dp

    val previousIsStructural = previousType != null && previousType in structuralTypes
    val currentIsStructural = currentType in structuralTypes
    val blockGap = if (previousIsStructural && currentIsStructural) {
        maxOf(blockSpacing(previousType), blockSpacing(currentType))
    } else {
        0.dp
    }
    val lineBreakGap = when {
        pendingLineBreaks <= 0 -> 0.dp
        previousType == null -> 10.dp
        previousIsStructural && currentIsStructural -> 0.dp
        pendingLineBreaks > 1 -> 10.dp
        else -> 0.dp
    }
    return maxOf(blockGap, lineBreakGap)
}

private fun blockSpacing(type: String?): Dp = if (type == "paragraph") 6.dp else 4.dp
