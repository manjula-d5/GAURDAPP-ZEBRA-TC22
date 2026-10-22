package com.storelense.gateBt.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ColorScheme = lightColorScheme(
    primary   = Color(0xFF0F766E),
    secondary = Color(0xFF14B8A6),
    background = Color(0xFFF5F7FA),
    surface   = Color.White
)

@Composable
fun GateBtTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, content = content)
}
