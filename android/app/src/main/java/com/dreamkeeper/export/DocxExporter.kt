package com.dreamkeeper.export

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * docx 渲染（§0.2，D340/D374）：手写最小 OOXML 三件套，只用 `java.util.zip`，零新依赖。
 *
 * 段落映射（D374，check-export.py 会逐段核对）：
 *  - 头部三行各一段：`# <标题>`（加粗 run）/ 日期时间 / 标签（空则省略）；
 *  - 头部第 4 行空行**不单独成段**（它不是内容）；
 *  - 正文按 `\n` 每行一段，空行生成空段；
 *  - 不引 styles.xml、不写 `Heading1`，首段的加粗只在 run 内用 `<w:rPr><w:b/></w:rPr>`。
 *
 * 时间戳写死，保证同一份出参字节稳定（§7 / D373）。
 */
object DocxExporter {

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val OFFICE_DOC_REL =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    private const val DOCX_MAIN =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"

    private val FIXED_TIME: LocalDateTime = LocalDateTime.of(2020, 1, 1, 0, 0, 0)

    fun render(document: ExportDocument): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            put(zip, "[Content_Types].xml", contentTypes())
            put(zip, "_rels/.rels", packageRels())
            put(zip, "word/document.xml", documentXml(document))
        }
        return out.toByteArray()
    }

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        val text = content.toByteArray(Charsets.UTF_8)
        val entry = ZipEntry(name).apply {
            setTimeLocal(FIXED_TIME)
            size = text.size.toLong()
        }
        zip.putNextEntry(entry)
        zip.write(text)
        zip.closeEntry()
    }

    private fun contentTypes(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<Types xmlns=\"$CT_NS\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/word/document.xml\" ContentType=\"$DOCX_MAIN\"/>")
        append("</Types>")
    }

    private fun packageRels(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<Relationships xmlns=\"$PKG_REL_NS\">")
        append("<Relationship Id=\"rId1\" Type=\"$OFFICE_DOC_REL\" Target=\"word/document.xml\"/>")
        append("</Relationships>")
    }

    private fun documentXml(document: ExportDocument): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<w:document xmlns:w=\"$W_NS\"><w:body>")
        append(paragraph("# ${document.title}", bold = true))
        append(paragraph(document.dateLine))
        if (document.tags.isNotEmpty()) append(paragraph("标签：" + document.tags.joinToString("、")))
        for (line in ExportText.normalizeLf(document.body).split("\n")) {
            append(paragraph(line))
        }
        append("</w:body></w:document>")
    }

    private fun paragraph(text: String, bold: Boolean = false): String {
        val runProps = if (bold) "<w:rPr><w:b/></w:rPr>" else ""
        return "<w:p><w:r>$runProps<w:t xml:space=\"preserve\">${escape(sanitize(text))}</w:t></w:r></w:p>"
    }

    /** 剔除 OOXML 非法控制字符（0x00–0x08/0x0B/0x0C/0x0E–0x1F）与孤立代理项。 */
    private fun sanitize(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            if (cp in 0xD800..0xDFFF) { i += width; continue }
            if (cp in 0x00..0x08 || cp == 0x0B || cp == 0x0C || cp in 0x0E..0x1F) { i += width; continue }
            sb.appendCodePoint(cp)
            i += width
        }
        return sb.toString()
    }

    private fun escape(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}
