package com.dreamkeeper.export

import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Task 10 §0.2 / D374：docx 必须是可解析的最小 OOXML——三件套、首段加粗、段映射、
 * 转义与非法控制字符剔除。用 DOM 解析而不是字符串包含，防止「看起来像 XML」。
 */
class DocxExporterTest {

    private fun document(
        title: String? = "电梯",
        tags: List<String> = listOf("下坠的预感", "电梯"),
        body: String = "我梦见电梯一直往下。\n\n门开了。",
    ): ExportDocument = ExportDocument(
        title = ExportNaming.headerTitle(title),
        dateLine = "2026-10-22 08:00",
        tags = tags,
        body = body,
        date = LocalDate.of(2026, 10, 22),
        version = 2,
        raw = false,
    )

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes()
            }
        }
        return out
    }

    private fun documentXml(bytes: ByteArray): String =
        String(entries(bytes).getValue("word/document.xml"), Charsets.UTF_8)

    private fun paragraphs(xml: String): List<Element> {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        return doc.getElementsByTagNameNS(W_NS, "p").let { list ->
            (0 until list.length).map { list.item(it) as Element }
        }
    }

    private fun textOf(paragraph: Element): String =
        paragraph.getElementsByTagNameNS(W_NS, "t").let { list ->
            (0 until list.length).joinToString("") { list.item(it).textContent }
        }

    @Test
    fun `压缩包只含三件套且 document xml 可解析`() {
        val bytes = DocxExporter.render(document())
        assertTrue("docx 不能为空", bytes.isNotEmpty())
        assertEquals(
            setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"),
            entries(bytes).keys,
        )
        assertTrue(paragraphs(documentXml(bytes)).isNotEmpty())
    }

    @Test
    fun `首段是加粗的标题段`() {
        val xml = documentXml(DocxExporter.render(document(title = "电梯")))
        assertTrue(
            "首段标题 run 必须带 <w:rPr><w:b/>",
            xml.contains("<w:rPr><w:b/></w:rPr>"),
        )
        val first = paragraphs(xml).first()
        assertEquals("# 电梯", textOf(first))
        assertTrue(first.getElementsByTagNameNS(W_NS, "b").length == 1)
    }

    @Test
    fun `段数等于头部段数加正文行数且空行生成空段`() {
        val body = "我梦见电梯一直往下。\n\n门开了。"
        val paragraphs = paragraphs(documentXml(DocxExporter.render(document(body = body))))
        // 头部三行（标题/日期/标签；头部第 4 行空行不单独成段，D374）+ 正文三行（中间是空行）
        assertEquals(6, paragraphs.size)
        assertEquals("我梦见电梯一直往下。", textOf(paragraphs[3]))
        assertEquals("", textOf(paragraphs[4]))
        assertEquals("门开了。", textOf(paragraphs[5]))
    }

    @Test
    fun `XML 转义且剔除非法控制字符与孤立代理项`() {
        val bytes = DocxExporter.render(
            document(
                title = "A&B<C>\"D'E",
                body = "x\u0001y\u0000z\uD800",
            ),
        )
        val xml = documentXml(bytes)
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&lt;"))
        assertTrue(xml.contains("&gt;"))
        assertTrue(xml.contains("&quot;"))
        assertTrue(xml.contains("&apos;"))
        assertFalse(xml.contains("\u0001"))
        assertFalse(xml.contains("\u0000"))
        assertFalse(xml.contains("\uD800"))
        // 转义后仍是合法 XML：能重新解析
        assertTrue(paragraphs(xml).isNotEmpty())
    }

    private companion object {
        const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    }
}
