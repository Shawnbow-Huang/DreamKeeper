package com.dreamkeeper.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.dreamkeeper.ui.theme.DreamColors
import com.dreamkeeper.ui.theme.LocalDream

/**
 * 「液态玻璃」的 Compose 实现（不引 RenderEffect 真模糊）。
 *
 * 材质判据（专家结论）：**会留下来的 → 纸；不会留下来的 → 玻璃**。
 * 所以玻璃只给弹层（对话框 / 底部弹层）——它们是唯一「背后确实有内容、且内容是临时」的表面；
 * 底栏背后没有内容流过，不是玻璃；正文永远不上玻璃。
 *
 * 深色皮肤：白色 8% 填充 + 1px 白 18% 折射边 + 接触阴影。
 * 晨光纸：「玻璃」在浅底上物理不成立（白 8% 叠米色底 ΔL* 只有 0.4），改写为**透光纸**：
 *   96% 不透明白纸 + 1px 墨 10% 边 + 软接触阴影，不做高光、不做模糊。
 */
@Composable
fun glassFill(): Color = if (LocalDream.current.isDark) {
    Color.White.copy(alpha = 0.08f)
} else {
    DreamColors.nightRaised.copy(alpha = 0.96f)
}

fun Modifier.glassSurface(
    dark: Boolean,
    shape: Shape = RoundedCornerShape(20.dp),
): Modifier {
    val fill = if (dark) Color.White.copy(alpha = 0.08f) else DreamColors.nightRaised.copy(alpha = 0.96f)
    val edge = if (dark) {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.06f)),
        )
    } else {
        Brush.verticalGradient(
            listOf(DreamColors.paper.copy(alpha = 0.12f), DreamColors.paper.copy(alpha = 0.06f)),
        )
    }
    val shadowColor = if (dark) Color.Black.copy(alpha = 0.55f) else DreamColors.paper.copy(alpha = 0.10f)
    return this
        .shadow(elevation = if (dark) 18.dp else 10.dp, shape = shape, ambientColor = shadowColor, spotColor = shadowColor)
        .clip(shape)
        .background(fill, shape)
        .border(1.dp, edge, shape)
}

/** 对话框默认外形：M3 对话框是 28dp 圆角；这里写常量，避免 `AlertDialogDefaults.shape` 的 @Composable getter。 */
val GlassDialogShape: Shape = RoundedCornerShape(28.dp)

/** 给 AlertDialog 用：玻璃底 + 透明容器色。 */
@Composable
fun glassDialogModifier(): Modifier {
    val dark = LocalDream.current.isDark
    return Modifier.glassSurface(dark, GlassDialogShape)
}

@Composable
fun glassDialogTextColor(): Color = MaterialTheme.colorScheme.onSurface
