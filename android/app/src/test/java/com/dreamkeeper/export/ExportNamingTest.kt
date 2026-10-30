package com.dreamkeeper.export

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task 10 §0.2 / §0.5：文件名规则必须与 `scripts/check-export.py` 逐字一致，否则
 * 「实际文件名 == 推导名」的断言会把实现口径的偏差算到导出器头上。
 */
class ExportNamingTest {

    @Test
    fun `清洗非法字符并把连续空白压成一个空格`() {
        assertEquals("abcdefghij", ExportNaming.cleanTitle(" a/b\\c:d*e?f\"g<h>i|j "))
        assertEquals("电梯 下坠的预感", ExportNaming.cleanTitle("电梯 \t  下坠的预感"))
        assertEquals("电梯 下坠", ExportNaming.cleanTitle("电梯\u3000\u3000下坠"))
    }

    @Test
    fun `标题按码点截前20且空标题用dream`() {
        val long = "一二三四五六七八九十一二三四五六七八九十一二三"
        assertEquals("一二三四五六七八九十一二三四五六七八九十", ExportNaming.cleanTitle(long))
        // 代理对按 code point 截断，不切半个 emoji
        assertEquals("😀😀", ExportNaming.takeCodePoints("😀😀😀", 2))
        val emoji20 = ExportNaming.cleanTitle("😀".repeat(30))
        assertEquals(20, emoji20.codePointCount(0, emoji20.length))
        assertEquals("dream", ExportNaming.cleanTitle(null))
        assertEquals("dream", ExportNaming.cleanTitle("   "))
    }

    @Test
    fun `有稿带版本号无稿带raw`() {
        val date = LocalDate.of(2026, 10, 22)
        assertEquals("20261022-电梯-v2.txt", ExportNaming.fileName(date, "电梯", 2, false, ExportFormat.TXT))
        assertEquals("20261022-电梯-v2.docx", ExportNaming.fileName(date, "电梯", 2, false, ExportFormat.DOCX))
        assertEquals("20261022-电梯-v2.pdf", ExportNaming.fileName(date, "电梯", 2, false, ExportFormat.PDF))
        assertEquals("20261022-电梯-raw.txt", ExportNaming.fileName(date, "电梯", 0, true, ExportFormat.TXT))
    }

    @Test
    fun `头部日期时分取createdAt的上海时区且历史行写0000`() {
        val date = LocalDate.of(2026, 10, 22)
        assertEquals("2026-10-22 00:00", ExportDates.dateLine(date, 0L))
        val createdAt = Instant.parse("2026-10-21T17:30:00Z").toEpochMilli() // 上海 10-22 01:30
        assertEquals("2026-10-22 01:30", ExportDates.dateLine(date, createdAt))
    }
}
