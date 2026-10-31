package com.dreamkeeper.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 视觉方向「灯下纸」（2026-11-01 改版）：
 * 冷桌（Night/Void）承载结构与工具，暖纸（PaperSurface/PaperLit）承载文字，
 * 琥珀（Ember/Lamp）**只作光**——用于当前时刻、录音钮、播放头，一屏最多一处。
 *
 * 旧版问题（可量化）：Ember #C8873F 与 InkDim #8B95A7 的 L* 只差 0.1，
 * 强调色与二级文字一样亮；且 6 个 token 挤在 hue 218–222，整屏只有明度没有温度。
 */

/** 桌面/空间：冷黑，比旧版更沉（#0B0E13 → #0A0B0E） */
val Night = Color(0xFF0A0B0E)
/** 工具面：卡片、弹窗、输入框（冷） */
val NightRaised = Color(0xFF131519)
/** 冷底细线：只做装饰性分隔 */
val Line = Color(0xFF272A30)

/** 暖纸面：正文区域、列表卡片、空态纸面 */
val PaperSurface = Color(0xFF231C15)
/** 抬起的纸：小说稿、选中的版本 */
val PaperLit = Color(0xFF2D2418)
/** 纸上细线 */
val PaperRule = Color(0xFF3A2F21)

/** 纸面正文：暖象牙，不再偏蓝（旧 Paper #E6EAF2 r−b = −12，是屏幕不是纸） */
val Paper = Color(0xFFE7DCC4)
/** 纸面/冷面次级文字 */
val InkDim = Color(0xFFB6A98F)
/** 注脚（仍需 ≥4.5:1） */
val InkFaint = Color(0xFF9C8D72)

/** 冷面正文（工具区用） */
val Ash = Color(0xFFB7BAC0)
/** 冷面次级 */
val AshDim = Color(0xFF7C8088)
/** 冷面注脚 */
val AshFaint = Color(0xFF6E727A)

/** 唯一的光：琥珀。L* 71.3，比任何文字都亮，才配当强调 */
val Ember = Color(0xFFE2A24E)
/** 光的沉淀：只做 ≤2dp 描边或 ≤8% 晕，永远不做大面积填充 */
val EmberDeep = Color(0xFF7A4E22)
/** 说梦钮上的字：光底上的深墨 */
val OnLamp = Color(0xFF1A1108)

/** 错误文字：保留唯一语义色，不与品牌光抢 */
val ErrorText = Color(0xFFE08578)
/** 错误容器：中性暗红，不再复用 EmberDeep（旧版把品牌砖红注册成 errorContainer） */
val ErrorContainer = Color(0xFF4A2620)
