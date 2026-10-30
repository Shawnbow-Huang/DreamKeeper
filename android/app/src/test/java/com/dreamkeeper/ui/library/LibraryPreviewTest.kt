package com.dreamkeeper.ui.library

import com.dreamkeeper.domain.Dream
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T6a / D332：「全部」页预览优先显示**选中版**小说，其次整理稿，最后原始转写。
 */
class LibraryPreviewTest {

    private fun dream(faithful: String? = null, raw: String = "原始转写") = Dream(
        id = "dream-1",
        date = LocalDate.of(2026, 10, 8),
        title = null,
        rawTranscript = raw,
        faithfulText = faithful,
        novelText = null,
        novelChangeLog = null,
        mood = null,
        isDraft = false,
    )

    @Test
    fun `selected novel text wins over faithful and raw`() {
        assertEquals("选中的小说稿", previewText("选中的小说稿", dream(faithful = "整理稿")))
    }

    @Test
    fun `falls back to faithful then raw when no novel version exists`() {
        assertEquals("整理稿", previewText(null, dream(faithful = "整理稿")))
        assertEquals("原始转写", previewText("   ", dream(faithful = " ")))
    }
}
