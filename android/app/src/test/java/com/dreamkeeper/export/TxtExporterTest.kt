package com.dreamkeeper.export

import com.dreamkeeper.llm.gates.FaithfulGates
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 10 §0.2：txt 三个可证伪点——头部四行、正文后的 `共 N 字`、无 BOM 且只有 LF。
 * 纯 JVM，不依赖 Android（同 [VersionStripTest] 的做法）。
 */
class TxtExporterTest {

    private fun document(
        title: String? = "电梯",
        date: LocalDate = LocalDate.of(2026, 10, 22),
        tags: List<String> = listOf("下坠的预感", "电梯"),
        body: String = "我梦见电梯一直往下。\n门开了。",
        version: Int = 2,
        raw: Boolean = false,
    ): ExportDocument = ExportDocument(
        title = ExportNaming.headerTitle(title),
        dateLine = "2026-10-22 08:00",
        tags = tags,
        body = body,
        date = date,
        version = version,
        raw = raw,
    )

    @Test
    fun `完整导出串是头部四行加正文加共N字`() {
        val body = "我梦见电梯一直往下。\n门开了。"
        val expected = buildString {
            append("# 电梯\n")
            append("2026-10-22 08:00\n")
            append("标签：下坠的预感、电梯\n")
            append("\n")
            append(body)
            append("\n共 ${FaithfulGates.contentCharCount(body)} 字")
        }
        assertEquals(expected, TxtExporter.render(document(body = body)))
    }

    @Test
    fun `标签为空时整行省略`() {
        val expected = "# 电梯\n2026-10-22 08:00\n\n我梦见电梯。\n共 ${FaithfulGates.contentCharCount("我梦见电梯。")} 字"
        assertEquals(expected, TxtExporter.render(document(tags = emptyList(), body = "我梦见电梯。")))
    }

    @Test
    fun `输出无 BOM 且正文的 CR 被归一为 LF`() {
        val bytes = TxtExporter.bytes(document(body = "第一行\r\n第二行\r第三行"))
        assertEquals('#'.code.toByte(), bytes[0])
        assertFalse("不能有 BOM", bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte())
        val text = String(bytes, Charsets.UTF_8)
        assertFalse(text.contains('\r'))
        assertTrue(text.contains("第一行\n第二行\n第三行"))
    }

    @Test
    fun `多行正文的空行原样保留且共N字只算正文内容字符`() {
        val body = "第一段\n\n第二段"
        val rendered = TxtExporter.render(document(title = "一个很长很长很长很长很长的标题", body = body))
        assertTrue(rendered.contains("第一段\n\n第二段"))
        val footer = rendered.substringAfterLast('\n')
        assertEquals("共 ${FaithfulGates.contentCharCount(body)} 字", footer)
    }
}
