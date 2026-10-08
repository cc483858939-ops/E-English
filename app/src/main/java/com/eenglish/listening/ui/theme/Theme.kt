package com.eenglish.listening.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = lightColorScheme(
    primary = Color(0xFF505FD3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9EBFC),
    onPrimaryContainer = Color(0xFF3847BA),
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFF4F5F7),
    onSurface = Color(0xFF282A30),
    onSurfaceVariant = Color(0xFF70747E),
)

@Composable
fun ListeningTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
