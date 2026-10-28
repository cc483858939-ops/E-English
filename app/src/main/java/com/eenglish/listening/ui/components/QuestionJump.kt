package com.eenglish.listening.ui.components

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

internal data class QuestionJump(val number: Int, val sequence: Int, val onLocated: () -> Unit)
internal val LocalQuestionJump = staticCompositionLocalOf<QuestionJump?> { null }

/** Explicit answer-card navigation never focuses an editor or opens the keyboard. */
internal fun Modifier.questionJumpTarget(number: Int): Modifier = composed {
    val jump = LocalQuestionJump.current
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(jump?.sequence) {
        if (jump?.number == number) {
            withFrameNanos { }
            requester.bringIntoView()
            jump.onLocated()
        }
    }
    bringIntoViewRequester(requester)
}
