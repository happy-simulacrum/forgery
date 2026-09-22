package com.forgery.app.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Legacy accents from www/style.css: :root orange #ff9800, flux cyan #00e5ff, qwen pink #ff007f
val ForgeryOrange = Color(0xFFFF9800)
val ForgeryFluxCyan = Color(0xFF00E5FF)
val ForgeryQwenPink = Color(0xFFFF007F)

private val DarkScheme = darkColorScheme(
    primary = ForgeryOrange,
    secondary = ForgeryFluxCyan,
    tertiary = ForgeryQwenPink,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFE65100),
    secondary = Color(0xFF0097A7),
    tertiary = Color(0xFFC2185B),
)

@Composable
fun ForgeryTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}

object ForgeryIcons // placeholder: use Material icons-extended in features
