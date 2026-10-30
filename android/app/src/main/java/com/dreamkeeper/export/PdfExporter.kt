package com.dreamkeeper.export

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream

/**
 * pdf 渲染（§0.2，D338/D358）：`android.graphics.pdf.PdfDocument`，A4 595x842pt、边距 48pt、
 * 正文 11sp 自动分页、每页页脚 `第 n / N 页`；不做文本抽取级断言，设备端只验结构。
 *
 * 换行用 `Paint.breakText` 逐字量宽（CJK 无空格可断）；不指定等宽字体、不自带 ttf，
 * 走系统 CJK fallback（§7）。
 */
object PdfExporter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f
    private const val TITLE_SIZE = 16f
    private const val BODY_SIZE = 11f
    private const val FOOTER_SIZE = 9f

    fun render(document: ExportDocument): ByteArray {
        val titlePaint = Paint().apply {
            isAntiAlias = true
            textSize = TITLE_SIZE
            isFakeBoldText = true
        }
        val bodyPaint = Paint().apply {
            isAntiAlias = true
            textSize = BODY_SIZE
        }
        val footerPaint = Paint().apply {
            isAntiAlias = true
            textSize = FOOTER_SIZE
        }
        val maxWidth = PAGE_WIDTH - MARGIN * 2

        data class Line(val text: String, val paint: Paint, val height: Float)

        val lines = ArrayList<Line>()
        lines += Line("# ${document.title}", titlePaint, TITLE_SIZE * 2f)
        lines += Line(document.dateLine, bodyPaint, BODY_SIZE * 1.6f)
        if (document.tags.isNotEmpty()) {
            lines += Line("标签：" + document.tags.joinToString("、"), bodyPaint, BODY_SIZE * 1.6f)
        }
        lines += Line("", bodyPaint, BODY_SIZE * 0.8f)
        for (raw in ExportText.normalizeLf(document.body).split("\n")) {
            for (wrapped in wrap(bodyPaint, raw, maxWidth)) {
                lines += Line(wrapped, bodyPaint, BODY_SIZE * 1.5f)
            }
        }

        // 先分页再渲染，页脚才能写出总页数 N。
        val pages = ArrayList<MutableList<Line>>()
        var current = ArrayList<Line>()
        var y = MARGIN
        val contentBottom = PAGE_HEIGHT - MARGIN - FOOTER_SIZE * 2
        for (line in lines) {
            if (y + line.height > contentBottom) {
                pages += current
                current = ArrayList()
                y = MARGIN
            }
            current += line
            y += line.height
        }
        pages += current

        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { index, pageLines ->
                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                val page = pdf.startPage(info)
                val canvas = page.canvas
                var baseline = MARGIN
                for (line in pageLines) {
                    baseline += line.height * 0.78f
                    canvas.drawText(line.text, MARGIN, baseline, line.paint)
                    baseline += line.height * 0.22f
                }
                val footer = "第 ${index + 1} / ${pages.size} 页"
                val footerX = (PAGE_WIDTH - footerPaint.measureText(footer)) / 2f
                canvas.drawText(footer, footerX, PAGE_HEIGHT - 24f, footerPaint)
                pdf.finishPage(page)
            }
            val out = ByteArrayOutputStream()
            pdf.writeTo(out)
            return out.toByteArray()
        } finally {
            pdf.close()
        }
    }

    private fun wrap(paint: Paint, text: String, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            var count = paint.breakText(rest, true, maxWidth, null)
            if (count <= 0) {
                out += rest
                break
            }
            // breakText 按 UTF-16 计数，别把代理对切成半个 emoji。
            if (count < rest.length &&
                Character.isLowSurrogate(rest[count]) &&
                Character.isHighSurrogate(rest[count - 1])
            ) {
                count -= 1
            }
            if (count <= 0) {
                out += rest
                break
            }
            out += rest.substring(0, count)
            rest = rest.substring(count)
        }
        return out
    }
}
