package com.dreamkeeper.ui.library

import com.dreamkeeper.domain.Dream

/**
 * 「全部」页卡片/时间轴的预览正文（D332）。
 *
 * 顺序：**选中版正文**（没选就是最新版，来自 `novel_versions`）→ 整理稿 → 原始转写。
 * 不能反过来：反着写会把「已经挑好的 v4」显示成最新稿，用户改回来就看不出来。
 */
fun previewText(finalNovelText: String?, dream: Dream): String {
    finalNovelText?.takeIf { it.isNotBlank() }?.let { return it }
    dream.faithfulText?.takeIf { it.isNotBlank() }?.let { return it }
    return dream.rawTranscript
}
