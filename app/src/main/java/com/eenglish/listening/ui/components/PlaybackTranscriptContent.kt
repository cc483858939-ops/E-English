package com.eenglish.listening.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.domain.model.Transcript
import com.eenglish.listening.viewmodel.TranscriptMode
import kotlin.math.roundToInt

data class TranscriptPlaybackBounds(val index: Int, val top: Int, val bottom: Int)

/** Keep native full-document selection in single-language modes and existing paragraph keys in all modes. */
@Composable
fun PlaybackTranscriptContent(transcript: Transcript, mode: TranscriptMode, activeIndex: Int?, anchorIndex: Int?,
    scroll: ScrollState, viewportTop: Float, onBounds: (TranscriptPlaybackBounds?) -> Unit,
    onUserInteraction: () -> Unit) {
    if (mode != TranscriptMode.BILINGUAL) {
        val language = if (mode == TranscriptMode.ENGLISH) "en" else "zh"
        val document = remember(transcript, language) { AnnotationDocument.transcript(transcript, language) }
        val active = document.blocks.firstOrNull { it.textId == "segment:$activeIndex" }
        val anchor = document.blocks.firstOrNull { it.textId == "segment:$anchorIndex" }
        var top by remember(mode) { mutableStateOf<Float?>(null) }
        var relative by remember(mode, anchorIndex) { mutableStateOf<Pair<Int, Int>?>(null) }
        LaunchedEffect(top, relative, anchorIndex) {
            val y = top; val bounds = relative; val index = anchorIndex
            onBounds(if (y != null && bounds != null && index != null)
                TranscriptPlaybackBounds(index, y.roundToInt() + bounds.first, y.roundToInt() + bounds.second) else null)
        }
        AnnotatableText(document, modifier = Modifier.onGloballyPositioned {
            top = it.positionInRoot().y - viewportTop + scroll.value
        }.semantics { stateDescription = activeIndex?.let { "正在播放第${it + 1}段" } ?: "当前无原文播放段落" },
            viewTag = "transcript-$language", playbackRange = active?.let { it.start until it.end },
            playbackAnchor = anchor?.let { it.start until it.end },
            onPlaybackBounds = { relative = it }, onUserInteraction = onUserInteraction)
    } else {
        val positions = remember(transcript, mode) { mutableStateMapOf<Int, Pair<Int, Int>>() }
        val currentBounds = anchorIndex?.let { positions[it] }
        LaunchedEffect(anchorIndex, currentBounds) {
            val index = anchorIndex
            onBounds(if (index != null && currentBounds != null)
                TranscriptPlaybackBounds(index, currentBounds.first, currentBounds.second) else null)
        }
        Text("双语模式按语言段选择；跨段选择请切换英文或中文。", style = MaterialTheme.typography.labelSmall)
        transcript.segments.forEachIndexed { index, segment ->
            val isActive = activeIndex == index
            Column(Modifier.fillMaxWidth().testTag("transcript-segment-$index")
                .semantics { stateDescription = if (isActive) "当前播放段落" else "非当前播放段落" }
                .onGloballyPositioned { coordinates ->
                    val top = (coordinates.positionInRoot().y - viewportTop + scroll.value).roundToInt()
                    positions[index] = top to top + coordinates.size.height
                }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((language, value) in listOf("en" to segment.english.trim(), "zh" to segment.chinese.trim())) {
                    if (value.isNotEmpty()) AnnotatableText(remember(value, index, language) {
                        AnnotationDocument.single(value, "transcript", "segment:$index", language)
                    }, viewTag = "transcript-$language-$index",
                        color = if (language == "zh") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        playbackRange = if (isActive) value.indices else null, onUserInteraction = onUserInteraction)
                }
            }
        }
    }
}
