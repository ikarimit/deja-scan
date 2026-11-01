package com.dejascan.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Extra colours beyond Material's scheme: signal strength levels and radar grid. */
@Immutable
class DejaColors(
    val strong: Color,
    val mid: Color,
    val weak: Color,
    val grid: Color,
)

private val DarkDeja = DejaColors(
    strong = Color(0xFF2BFF88), mid = Color(0xFFFFC53D), weak = Color(0xFF7C8F84), grid = Color(0xFF1F3A2A),
)
private val LightDeja = DejaColors(
    strong = Color(0xFF00794A), mid = Color(0xFFB8860B), weak = Color(0xFF7C8F84), grid = Color(0xFFBFD6C8),
)

val LocalDejaColors = staticCompositionLocalOf { DarkDeja }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF2BFF88),
    onPrimary = Color(0xFF00210F),
    primaryContainer = Color(0xFF0F3D22),
    onPrimaryContainer = Color(0xFFB6F5CF),
    secondary = Color(0xFF7FD9A8),
    background = Color(0xFF070D09),
    onBackground = Color(0xFFD9F5E3),
    surface = Color(0xFF0E1711),
    onSurface = Color(0xFFD9F5E3),
    surfaceVariant = Color(0xFF16231B),
    onSurfaceVariant = Color(0xFF9DB8A8),
    outline = Color(0xFF2E4A39),
    outlineVariant = Color(0xFF1F3A2A),
    error = Color(0xFFFF5C6C),
    onError = Color(0xFF3B0008),
    errorContainer = Color(0xFF4A1219),
    onErrorContainer = Color(0xFFFFD9DD),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00794A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F2DA),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF3F6B55),
    background = Color(0xFFF3F8F5),
    onBackground = Color(0xFF0E1A13),
    surface = Color.White,
    onSurface = Color(0xFF0E1A13),
    surfaceVariant = Color(0xFFE3EEE7),
    onSurfaceVariant = Color(0xFF45594D),
    outline = Color(0xFF8AA595),
    outlineVariant = Color(0xFFCADBD0),
    error = Color(0xFFBA1A2B),
    onError = Color.White,
    errorContainer = Color(0xFFFFD9DD),
    onErrorContainer = Color(0xFF410008),
)

@Composable
fun DejaTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDejaColors provides if (dark) DarkDeja else LightDeja) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
