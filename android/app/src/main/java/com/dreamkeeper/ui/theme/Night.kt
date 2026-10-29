package com.dreamkeeper.ui.theme

import androidx.compose.ui.graphics.Color

/** 录音中 / 追问全屏页的底：比 Night 更深一档，让全屏页与首页有层级差 */
val NightVoid = Color(0xFF05070A)
/** 全屏页之上盖内容时的遮罩（80% 不透明的 NightVoid） */
val NightScrim = Color(0xCC05070A)

/** 动画时长口径：夜间不做长动画，超过 200ms 的过渡一律砍掉 */
object DreamMotion {
    const val Quick = 120      // 月份切换、tab 内容替换
    const val Standard = 200   // 上下限
}
