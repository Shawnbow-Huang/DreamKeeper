package com.dreamkeeper.export

/**
 * txt 渲染（§0.2，D341）：UTF-8 无 BOM、统一 `\n`；头部件 = 标题/日期/标签（空则省略）/空行；
 * 正文后追加 `\n共 <N> 字`，N 只算正文内容字符（[ExportDocument.charCount]）。
 */
object TxtExporter {

    fun render(document: ExportDocument): String {
        val body = ExportText.normalizeLf(document.body)
        return buildString {
            append("# ").append(document.title).append('\n')
            append(document.dateLine).append('\n')
            if (document.tags.isNotEmpty()) {
                append("标签：").append(document.tags.joinToString("、")).append('\n')
            }
            append('\n')
            append(body)
            append("\n共 ").append(document.charCount).append(" 字")
        }
    }

    fun bytes(document: ExportDocument): ByteArray = render(document).toByteArray(Charsets.UTF_8)
}
