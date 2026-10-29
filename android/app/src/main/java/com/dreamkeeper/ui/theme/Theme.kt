package com.dreamkeeper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DreamColorScheme = darkColorScheme(
    primary = Ember,
    onPrimary = Night,
    background = Night,
    onBackground = Paper,
    surface = NightRaised,
    onSurface = Paper,
    onSurfaceVariant = InkDim,
    // outline 是"必须看得见的边界"（输入框、可点容器），不能给 Line（1.34:1）；
    // 装饰性分隔线走 outlineVariant —— 这是 Line 在 M3 里的唯一去处
    outline = InkDim,
    outlineVariant = Line,
    error = ErrorText,
    // EmberDeep 只做填充：错误容器的底色，不是错误文字的颜色
    errorContainer = EmberDeep,
    onErrorContainer = Paper,
)

@Composable
fun DreamKeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DreamColorScheme, typography = DreamTypography, content = content)
}
