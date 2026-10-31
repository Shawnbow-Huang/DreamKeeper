package com.dreamkeeper.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.dreamkeeper.R

/**
 * 插画按皮肤取图。
 *
 * 暗皮肤用「烘焙了夜色的深底插画」，晨光纸用「米白纸上的墨线稿」——
 * 同一张深底图放在纸色上会变成一块黑砖（专家实测 ≈13:1 的硬边），
 * 所以浅色必须换一套，靠代码按 [com.dreamkeeper.ui.theme.DreamColors.isDark] 映射。
 */
@Composable
fun libraryEmptyPainter(): Painter =
    painterResource(if (com.dreamkeeper.ui.theme.SkinRuntime.current.isDark) R.drawable.illus_library_empty else R.drawable.illus_library_empty_light)

@Composable
fun dayEmptyPainter(): Painter =
    painterResource(if (com.dreamkeeper.ui.theme.SkinRuntime.current.isDark) R.drawable.illus_day_empty else R.drawable.illus_day_empty_light)

@Composable
fun novelEmptyPainter(): Painter =
    painterResource(if (com.dreamkeeper.ui.theme.SkinRuntime.current.isDark) R.drawable.illus_novel_empty else R.drawable.illus_novel_empty_light)

@Composable
fun mediaHintPainter(): Painter =
    painterResource(if (com.dreamkeeper.ui.theme.SkinRuntime.current.isDark) R.drawable.illus_media else R.drawable.illus_media_light)
