package com.dreamkeeper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 应用皮肤入口。
 *
 * [skin] 由 `ThemeController` 提供（SharedPreferences 持久化，设置页可改），默认灯下纸，
 * 保持 0.10.0 的观感。
 *
 * 状态栏/导航栏图标要跟着皮肤的明暗走：深色皮肤用浅色图标，浅色皮肤用深色图标，
 * 否则状态栏的白色时间会看不见（晨光纸实测）。
 *
 * [systemDark] 只在 `Skin.SYSTEM` 时有意义：跟随系统时白天用晨光纸、夜里用灯下纸。
 */
@Composable
fun DreamKeeperTheme(
    skin: Skin = Skin.LAMP,
    systemDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = paletteOf(skin, systemDark)
    // 非 @Composable 位置（Canvas draw、默认参数）也读这个快照。
    SkinRuntime.current = palette
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !palette.isDark
                isAppearanceLightNavigationBars = !palette.isDark
            }
        }
    }
    CompositionLocalProvider(LocalDream provides palette) {
        MaterialTheme(
            colorScheme = palette.colorScheme(),
            typography = DreamTypography,
            content = content,
        )
    }
}
