package com.eenglish.listening.ui.components

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Bridge exact native editor bounds into the surrounding Compose scroll viewport. */
@Composable
internal fun NativeInlineAnswerView(modifier: Modifier, update: (NativeInlineGapFillView) -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<Job>(1) }
    AndroidView(modifier = modifier.bringIntoViewRequester(requester),
        factory = { NativeInlineGapFillView(it) },
        update = { view ->
            view.onRequestEditorVisibility = { rect ->
                pending[0]?.cancel()
                pending[0] = scope.launch {
                    requester.bringIntoView(Rect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()))
                }
            }
            update(view)
        })
}
