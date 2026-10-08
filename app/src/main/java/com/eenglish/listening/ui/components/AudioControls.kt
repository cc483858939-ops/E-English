package com.eenglish.listening.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eenglish.listening.audio.AudioState

fun formatTime(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

@Composable
fun AudioControls(state: AudioState, onToggle: () -> Unit, onSeek: (Long) -> Unit, compact: Boolean = false) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = if (compact) 8.dp else 16.dp, vertical = if (compact) 0.dp else 8.dp)) {
            val toggle: @Composable () -> Unit = {
                TextButton(onClick = onToggle, enabled = state.ready && state.error == null,
                    modifier = Modifier.testTag("audio-toggle")) { Text(if (state.isPlaying) "暂停" else "播放") }
            }
            val time: @Composable (Modifier) -> Unit = { modifier ->
                Text("${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}",
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
                    maxLines = if (compact) 1 else Int.MAX_VALUE, modifier = modifier.testTag("audio-time"))
            }
            val seek: @Composable (Modifier) -> Unit = { modifier ->
                Slider(value = if (dragging) dragValue else state.positionMs.toFloat().coerceAtMost(state.durationMs.toFloat()),
                    valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f),
                    enabled = state.ready && state.durationMs > 0 && state.error == null,
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = { onSeek(dragValue.toLong()); dragging = false },
                    modifier = modifier.testTag("audio-seek"))
            }
            if (compact) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    toggle()
                    time(Modifier)
                    seek(Modifier.weight(1f).padding(start = 16.dp))
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    toggle()
                    time(Modifier.padding(top = 14.dp))
                }
                seek(Modifier)
            }
            if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
            else if (!state.ready) Text("正在准备音频…", style = MaterialTheme.typography.labelMedium)
        }
    }
}
