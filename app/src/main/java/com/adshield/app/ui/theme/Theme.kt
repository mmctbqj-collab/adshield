package com.adshield.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1F6FEB)
private val BlueDark = Color(0xFF8AB4F8)
private val Green = Color(0xFF1E8E3E)
private val Red = Color(0xFFD93025)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = Green,
    error = Red,
    background = Color(0xFFF7FAFC),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF10233F),
    secondary = Color(0xFF81C995),
    error = Color(0xFFF28B82),
    background = Color(0xFF101418),
    surface = Color(0xFF1A1F24),
)

@Composable
fun AdShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}