package com.dreamkeeper.ui.theme

import androidx.compose.ui.graphics.Color

/** 夜色背景：冷灰（偏蓝调的墨黑），不是暖黑。这个值不动——整屏观感由它定调，改了所有对比度都得重算 */
val Night = Color(0xFF0B0E13)
/** 抬升层（卡片、弹窗） */
val NightRaised = Color(0xFF12161F)
/** 正文：冷灰的浅色（偏蓝调），不用纯白 */
val Paper = Color(0xFFE6EAF2)
/** 次要文字 */
val InkDim = Color(0xFF8B95A7)
/** 更弱的辅助信息。12sp 也属于正文，必须 ≥ 4.5:1 —— 旧值 #5A6474 只有 3.23:1 */
val InkFaint = Color(0xFF7C8698)
/** 分隔线：**只做装饰**。旧值拿它画"今天"的描边（1.34:1）等于看不见，今天改用 Ember */
val Line = Color(0xFF232A36)
/** 唯一强调色：琥珀，夜间不刺眼，也不与"红钮"抢注意力（Night 上 6.4:1，够做 1.5dp 描边） */
val Ember = Color(0xFFC8873F)
/** 录音状态专用**填充色**：暗红。不做文字色（Night 上只有 2.57:1），只用于说梦钮与错误容器的底色 */
val EmberDeep = Color(0xFF8C3B34)
/** 错误文字：EmberDeep 当 `error` 用时对比度不达标，M3 的 `error` 必须用这个（Night 上 7.18:1） */
val ErrorText = Color(0xFFE08578)
