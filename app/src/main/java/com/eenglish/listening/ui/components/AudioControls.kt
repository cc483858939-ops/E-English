package com.eenglish.listening.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.audio.playbackSpeeds
import java.util.Locale

fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

fun formatSpeed(speed: Float): String = if (speed % 1f == 0f) "${speed.toInt()}.0" else speed.toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioControls(state: AudioState, onToggle: () -> Unit, onSeek: (Long) -> Unit, compact: Boolean = false,
    onSeekBy: (Long) -> Unit = {}, onSpeed: (Float) -> Unit = {}) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var speedMenu by remember { mutableStateOf(false) }
    val enabled = state.ready && state.error == null
    val canSeek = enabled && state.durationMs > 0
    LaunchedEffect(canSeek) { if (!canSeek) dragging = false }
    val duration = state.durationMs.toFloat().coerceAtLeast(1f)
    val displayed = (if (dragging) dragValue else state.positionMs.toFloat()).coerceIn(0f, duration)
    val colors = MaterialTheme.colorScheme
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().testTag("audio-controls"),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)) {
        Column(Modifier.padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().testTag("audio-buttons"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                IconButton(onClick = { onSeekBy(-5000) }, enabled = canSeek,
                    modifier = Modifier.size(48.dp).testTag("audio-back-5").semantics { contentDescription = "倒退5秒" }) {
                    JumpIcon(forward = false)
                }
                FilledIconButton(onClick = onToggle, enabled = enabled, shape = CircleShape,
                    modifier = Modifier.size(56.dp).testTag("audio-toggle").semantics {
                        contentDescription = if (state.isPlaying) "暂停" else "播放"
                    }) {
                    val color = LocalContentColor.current
                    Canvas(Modifier.size(26.dp)) {
                        if (state.isPlaying) {
                            drawRect(color, Offset(size.width * .22f, size.height * .16f), Size(size.width * .2f, size.height * .68f))
                            drawRect(color, Offset(size.width * .58f, size.height * .16f), Size(size.width * .2f, size.height * .68f))
                        } else drawPath(Path().apply {
                            moveTo(size.width * .25f, size.height * .12f)
                            lineTo(size.width * .85f, size.height * .5f)
                            lineTo(size.width * .25f, size.height * .88f); close()
                        }, color)
                    }
                }
                IconButton(onClick = { onSeekBy(5000) }, enabled = canSeek,
                    modifier = Modifier.size(48.dp).testTag("audio-forward-5").semantics { contentDescription = "快进5秒" }) {
                    JumpIcon(forward = true)
                }
                Box(Modifier.weight(1f, fill = false)) {
                    FilledTonalButton(onClick = { speedMenu = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), shape = CircleShape,
                        modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 48.dp).testTag("audio-speed")
                            .semantics { contentDescription = "播放速度${formatSpeed(state.speed)}倍" }) {
                        Text("${formatSpeed(state.speed)}×", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        playbackSpeeds.forEach { speed ->
                            DropdownMenuItem(text = { Text("${formatSpeed(speed)}×") },
                                onClick = { onSpeed(speed); speedMenu = false },
                                modifier = Modifier.testTag("audio-speed-${formatSpeed(speed)}"))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().testTag("audio-progress"), verticalAlignment = Alignment.CenterVertically,
                // Material Slider extends its accessibility hit area horizontally by 10dp.
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(formatTime(displayed.toLong()), fontSize = 12.sp, maxLines = 1,
                    modifier = Modifier.testTag("audio-time"))
                Slider(value = displayed, valueRange = 0f..duration, enabled = canSeek,
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = { if (dragging && canSeek) onSeek(dragValue.toLong()); dragging = false },
                    thumb = {
                        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(if (dragging) 18.dp else 14.dp)
                                .background(if (canSeek) colors.primary else colors.onSurface.copy(alpha = .38f), CircleShape)
                                .testTag("audio-thumb"))
                        }
                    },
                    track = {
                        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                            drawLine(colors.onSurfaceVariant.copy(alpha = .22f), Offset(0f, center.y), Offset(size.width, center.y), size.height, StrokeCap.Round)
                            drawLine(if (canSeek) colors.primary else colors.onSurface.copy(alpha = .38f),
                                Offset(0f, center.y), Offset(size.width * displayed / duration, center.y), size.height, StrokeCap.Round)
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("audio-seek")
                        .semantics { contentDescription = "播放进度" })
                Text(formatTime(state.durationMs), fontSize = 12.sp, maxLines = 1,
                    modifier = Modifier.testTag("audio-duration"))
            }
            if (state.error != null) Text(state.error, color = colors.error, style = MaterialTheme.typography.labelMedium)
            else if (!state.ready) Text("正在准备音频…", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun JumpIcon(forward: Boolean) {
    val color = LocalContentColor.current
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                scale(if (forward) -1f else 1f, 1f, pivot = Offset(12f, 12f)) {
                    drawArc(color, -90f, 280f, false, Offset(4f, 5f), Size(16f, 16f), style = Stroke(1.8f, cap = StrokeCap.Round))
                    drawLine(color, Offset(12f, 5f), Offset(7f, 5f), 1.8f, StrokeCap.Round)
                    drawLine(color, Offset(7f, 5f), Offset(10f, 2f), 1.8f, StrokeCap.Round)
                    drawLine(color, Offset(7f, 5f), Offset(10f, 8f), 1.8f, StrokeCap.Round)
                }
                // Numeral is part of the icon, so large text settings cannot collide with the arrow.
                drawPath(Path().apply {
                    moveTo(14f, 10f); lineTo(10f, 10f); lineTo(10f, 13f); lineTo(12.5f, 13f)
                    quadraticTo(15f, 13f, 14f, 16f)
                    quadraticTo(12.5f, 18f, 10f, 16.5f)
                }, color, style = Stroke(1.5f, cap = StrokeCap.Round))
            }
        }
    }
}
