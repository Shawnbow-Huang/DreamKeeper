package com.dreamkeeper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DreamColorScheme = darkColorScheme(
    primary = Ember,
    onPrimary = OnLamp,
    primaryContainer = EmberDeep,
    onPrimaryContainer = Paper,
    secondary = Ash,
    onSecondary = Night,
    background = Night,
    onBackground = Paper,
    surface = NightRaised,
    onSurface = Paper,
    surfaceVariant = PaperSurface,
    onSurfaceVariant = InkDim,
    // 边界要看得见（输入框、可点容器）；装饰线走 outlineVariant
    outline = InkDim,
    outlineVariant = Line,
    error = ErrorText,
    errorContainer = ErrorContainer,
    onErrorContainer = Paper,
    // 关键：M3 默认 surfaceTint = primary，任何 elevation 都会被琥珀染色；
    // 近黑底上这点染色会变成"脏棕"，必须关掉。
    surfaceTint = Color.Transparent,
)

@Composable
fun DreamKeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DreamColorScheme, typography = DreamTypography, content = content)
}
