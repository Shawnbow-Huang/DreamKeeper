package com.dreamkeeper.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 皮肤 = 一套「灯下纸」的色板。
 *
 * 为什么不是 Material 的 dark/light 二选一：这个 App 的视觉概念是「一张纸放在桌上」，
 * 亮度不是唯一变量——纸可以是暖白（白天写）、也可以是暖暗（夜里写），桌可以是暖木、
 * 也可以是冷石。所以这里按「场景」定义三套，而不是按 isDark 自动推导。
 */
enum class Skin(val id: String, val label: String, val blurb: String) {
    LAMP("lamp", "灯下纸", "暖暗 · 夜里写"),
    MORNING("morning", "晨光纸", "明亮 · 白天写"),
    INDIGO("indigo", "冷夜", "冷暗 · 屏幕感"),
    SYSTEM("system", "跟随系统", "白天浅色 · 夜里深色"),
    ;

    companion object {
        fun of(id: String?): Skin = entries.firstOrNull { it.id == id } ?: LAMP
    }
}

/**
 * 一套皮肤的完整取值。
 *
 * 命名沿用 Plan 5 的语义 token（night/paper/ink/ember…），而不是 `surfaceContainerLow`：
 * 界面上真正被复用的是「纸、墨、灯」，Material 槽位只是外层的映射。
 */
data class DreamPalette(
    val skin: Skin,
    val isDark: Boolean,
    val night: Color,
    val nightRaised: Color,
    val paper: Color,
    val inkDim: Color,
    val inkFaint: Color,
    val line: Color,
    val ember: Color,
    val emberDeep: Color,
    val onLamp: Color,
    val paperSurface: Color,
    val paperLit: Color,
    val paperRule: Color,
    val ash: Color,
    val ashDim: Color,
    val ashFaint: Color,
    val errorText: Color,
    val errorContainer: Color,
) {
    /**
     * 映射到 Material 3 的槽位。
     *
     * 关键两处：
     * - `surfaceTint = Transparent`：否则带 elevation 的表面会被 primary 染色（深色下偏橙、浅色下偏紫）。
     * - 浅色皮肤里 `onPrimary` 必须是纸色，否则琥珀底上的深墨会变成低对比。
     */
    fun colorScheme(): ColorScheme = if (isDark) {
        darkColorScheme(
            primary = ember,
            onPrimary = onLamp,
            primaryContainer = emberDeep,
            onPrimaryContainer = paper,
            secondary = ash,
            onSecondary = night,
            background = night,
            onBackground = paper,
            surface = nightRaised,
            onSurface = paper,
            surfaceVariant = paperSurface,
            onSurfaceVariant = inkDim,
            outline = line,
            outlineVariant = paperRule,
            error = errorText,
            onError = night,
            errorContainer = errorContainer,
            onErrorContainer = paper,
            surfaceTint = Color.Transparent,
        )
    } else {
        lightColorScheme(
            primary = ember,
            onPrimary = onLamp,
            primaryContainer = emberDeep,
            onPrimaryContainer = paper,
            secondary = ashDim,
            onSecondary = paper,
            background = night,
            onBackground = paper,
            surface = nightRaised,
            onSurface = paper,
            surfaceVariant = paperSurface,
            onSurfaceVariant = inkDim,
            outline = line,
            outlineVariant = paperRule,
            error = errorText,
            onError = paper,
            errorContainer = errorContainer,
            onErrorContainer = emberDeep,
            surfaceTint = Color.Transparent,
        )
    }
}

/** 灯下纸：暖暗 · 夜里写（Plan 6 的默认，也是 0.10.0 的观感）。 */
val LampPalette = DreamPalette(
    skin = Skin.LAMP,
    isDark = true,
    night = Color(0xFF0A0B0E),
    nightRaised = Color(0xFF131519),
    paper = Color(0xFFE7DCC4),
    inkDim = Color(0xFFB6A98F),
    inkFaint = Color(0xFF9C8D72),
    line = Color(0xFF272A30),
    ember = Color(0xFFE2A24E),
    emberDeep = Color(0xFF7A4E22),
    onLamp = Color(0xFF1A1108),
    paperSurface = Color(0xFF231C15),
    paperLit = Color(0xFF2D2418),
    paperRule = Color(0xFF3A2F21),
    ash = Color(0xFFB7BAC0),
    ashDim = Color(0xFF7C8088),
    ashFaint = Color(0xFF6E727A),
    errorText = Color(0xFFE08578),
    errorContainer = Color(0xFF4A2620),
)

/**
 * 晨光纸：明亮 · 白天写。
 *
 * 不是把暗色反相：纸是暖白（避免纯白刺眼），墨是暖黑，强调色压深到 L*≈47，
 * 这样在浅底上仍然满足正文/按钮的对比要求，而不是把 0.10.0 的亮琥珀直接搬过来。
 */
val MorningPalette = DreamPalette(
    skin = Skin.MORNING,
    isDark = false,
    night = Color(0xFFF4EEE2),      // 桌（浅暖灰）
    nightRaised = Color(0xFFFFFCF4), // 抬起的纸面
    paper = Color(0xFF2C2419),      // 墨（暖黑）
    inkDim = Color(0xFF6B5C49),
    inkFaint = Color(0xFF8A7B66),
    line = Color(0xFFD8CBB4),
    ember = Color(0xFFB06524),      // 压深的琥珀，浅底上才够对比
    emberDeep = Color(0xFF8A4B16),
    onLamp = Color(0xFFFFF8EE),
    paperSurface = Color(0xFFFBF5E7),
    paperLit = Color(0xFFF2E8D4),
    paperRule = Color(0xFFE3D6BF),
    ash = Color(0xFF5F5A52),
    ashDim = Color(0xFF6E6A62),
    ashFaint = Color(0xFF847F76),
    errorText = Color(0xFFA43A32),
    errorContainer = Color(0xFFF3DCD6),
)

/** 冷夜：冷暗 · 屏幕感（保留 Plan 5 之前的钢蓝方向，给喜欢冷色的人）。 */
val IndigoPalette = DreamPalette(
    skin = Skin.INDIGO,
    isDark = true,
    night = Color(0xFF0E1117),
    nightRaised = Color(0xFF161B23),
    paper = Color(0xFFE6EAF2),
    inkDim = Color(0xFFA7B0BF),
    inkFaint = Color(0xFF7C8698),
    line = Color(0xFF262C36),
    ember = Color(0xFFC8873F),
    emberDeep = Color(0xFF8C3B34),
    onLamp = Color(0xFF1A1108),
    paperSurface = Color(0xFF171C24),
    paperLit = Color(0xFF1F2530),
    paperRule = Color(0xFF2C333F),
    ash = Color(0xFFB7BAC0),
    ashDim = Color(0xFF848D9C),
    ashFaint = Color(0xFF6E727A),
    errorText = Color(0xFFE08578),
    errorContainer = Color(0xFF4A2620),
)

fun paletteOf(skin: Skin, systemDark: Boolean = false): DreamPalette = when (skin) {
    Skin.LAMP -> LampPalette
    Skin.MORNING -> MorningPalette
    Skin.INDIGO -> IndigoPalette
    // 跟随系统不做第三套色板：白天用晨光纸、夜里用灯下纸。
    Skin.SYSTEM -> if (systemDark) LampPalette else MorningPalette
}

val LocalDream = staticCompositionLocalOf { LampPalette }

/**
 * 当前皮肤的运行期快照。
 *
 * 为什么不是纯 CompositionLocal：颜色也会在非 @Composable 的位置被读（Canvas 的 draw lambda、
 * 默认参数、辅助函数）。这些位置不能调用 @Composable getter，但它们在整棵树重组后才会重绘，
 * 所以「Theme 写入、其余人读取」这个朴素做法是安全的，也更少样板。
 */
object SkinRuntime {
    @Volatile
    var current: DreamPalette = LampPalette
}

/**
 * 在 @Composable 里读当前皮肤的 token。
 *
 * 用只读 @Composable getter 而不是把 484 处调用点改成 `MaterialTheme.colorScheme.xxx`：
 * 语义 token（纸/墨/灯）比 Material 槽位更贴近这个 App 的语言，换皮肤时也不会漏改。
 */
object DreamColors {
    val night: Color get() = SkinRuntime.current.night
    val nightRaised: Color get() = SkinRuntime.current.nightRaised
    val paper: Color get() = SkinRuntime.current.paper
    val inkDim: Color get() = SkinRuntime.current.inkDim
    val inkFaint: Color get() = SkinRuntime.current.inkFaint
    val line: Color get() = SkinRuntime.current.line
    val ember: Color get() = SkinRuntime.current.ember
    val emberDeep: Color get() = SkinRuntime.current.emberDeep
    val onLamp: Color get() = SkinRuntime.current.onLamp
    val paperSurface: Color get() = SkinRuntime.current.paperSurface
    val paperLit: Color get() = SkinRuntime.current.paperLit
    val paperRule: Color get() = SkinRuntime.current.paperRule
    val ash: Color get() = SkinRuntime.current.ash
    val ashDim: Color get() = SkinRuntime.current.ashDim
    val ashFaint: Color get() = SkinRuntime.current.ashFaint
    val errorText: Color get() = SkinRuntime.current.errorText
    val errorContainer: Color get() = SkinRuntime.current.errorContainer
}
