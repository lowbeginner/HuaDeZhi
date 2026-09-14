package cn.student.expensetracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF22695B), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EFE2), onPrimaryContainer = Color(0xFF123C32),
    secondary = Color(0xFF696041), secondaryContainer = Color(0xFFF0E7CD),
    background = Color(0xFFF7F8F2), surface = Color(0xFFF7F8F2),
    surfaceContainer = Color(0xFFEDF0E9), surfaceContainerLow = Color(0xFFF1F3EC),
    surfaceContainerHigh = Color(0xFFE7EBE3), onSurface = Color(0xFF1F2924),
    onSurfaceVariant = Color(0xFF56615A), outlineVariant = Color(0xFFD5DCD2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD8BC), onPrimary = Color(0xFF0A382B),
    primaryContainer = Color(0xFF24503F), onPrimaryContainer = Color(0xFFD4F1E0),
    secondary = Color(0xFFD8C89D), secondaryContainer = Color(0xFF51472E),
    background = Color(0xFF111914), surface = Color(0xFF111914),
    surfaceContainer = Color(0xFF1C251F), surfaceContainerLow = Color(0xFF172019),
    surfaceContainerHigh = Color(0xFF28312A), onSurface = Color(0xFFE2E9DF),
    onSurfaceVariant = Color(0xFFBAC5BA), outlineVariant = Color(0xFF414C42),
)

@Composable
fun ExpenseTheme(theme: String = "SYSTEM", content: @Composable () -> Unit) {
    val dark = when (theme) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
