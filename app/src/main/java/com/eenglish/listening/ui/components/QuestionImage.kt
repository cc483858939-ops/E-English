package com.eenglish.listening.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

val LocalQuestionImages = staticCompositionLocalOf<suspend (String) -> Bitmap?> { { null } }

@Composable
fun QuestionImage(path: String) {
    val loader = LocalQuestionImages.current
    var loaded by remember(path, loader) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, path, loader) { value = loader(path); loaded = true }
    var zoom by remember(path) { mutableStateOf(false) }
    bitmap?.let { image ->
        Image(image.asImageBitmap(), "题目图片，点击放大", Modifier.fillMaxWidth()
            .aspectRatio(image.width.toFloat() / image.height).clickable { zoom = true })
        if (zoom) Dialog(onDismissRequest = { zoom = false }) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            Surface {
                Column {
                    TextButton(onClick = { zoom = false }) { Text("关闭 · 双指缩放图片") }
                    Box(Modifier.fillMaxWidth().heightIn(max = 500.dp).weight(1f, fill = false).clipToBounds()) {
                        Image(image.asImageBitmap(), "题目图片", Modifier.fillMaxWidth()
                            .aspectRatio(image.width.toFloat() / image.height)
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                            .transformable(rememberTransformableState { change, pan, _ ->
                                scale = (scale * change).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }))
                    }
                }
            }
        }
    } ?: if (loaded) Text("题目图片加载失败，请返回题库重试", color = MaterialTheme.colorScheme.error) else CircularProgressIndicator()
}
