package com.dreamkeeper.stt

/**
 * 把长录音切给「离线整段识别器」的纯算法。
 *
 * 为什么必须切：SenseVoice 是整段模型，一次喂 10 分钟既慢又容易在中途丢上下文；
 * 而本项目允许单条录音 4 小时（RecordingLimits），全读进内存会直接把手机打爆。
 *
 * 为什么留重叠：按 25s 硬切，接缝处几乎必然切在字中间——前一段丢半个字、
 * 后一段多半个字。留 0.8s 重叠 + 文本去重（[mergeSegments]）是成本最低的修法：
 * 只是多识别 3% 的音频，换来接缝处不吞字。
 */
internal object SegmentPlan {

    /** 单段时长上限（秒）。SenseVoice 训练以 ≤30s 为主，25s 留一点余量。 */
    const val SEGMENT_SECONDS = 25

    /** 相邻两段的重叠（秒）。0.8s 足够覆盖一个短句的尾巴。 */
    const val OVERLAP_SECONDS = 0.8

    /** 去重时最多比对的字符数：0.8s 大约 2–5 个汉字，8 已经足够宽。 */
    const val MAX_MERGE_CHARS = 8

    /**
     * 按采样点切段。返回的是**闭区间**，段与段之间重叠 [overlapSamples]。
     *
     * 最后一段不足 [chunkSamples] 也照常返回；重叠不会让区间倒退。
     */
    fun plan(totalSamples: Int, chunkSamples: Int, overlapSamples: Int): List<IntRange> {
        if (totalSamples <= 0 || chunkSamples <= 0) return emptyList()
        val overlap = overlapSamples.coerceIn(0, chunkSamples - 1)
        val step = chunkSamples - overlap
        val ranges = mutableListOf<IntRange>()
        var start = 0
        while (start < totalSamples) {
            val end = minOf(start + chunkSamples, totalSamples) - 1
            ranges += start..end
            if (end == totalSamples - 1) break
            start += step
        }
        return ranges
    }

    /** 常见录音的切段计划：16kHz、25s 段、0.8s 重叠。 */
    fun planForRecording(totalSamples: Int, sampleRate: Int = 16_000): List<IntRange> =
        plan(
            totalSamples = totalSamples,
            chunkSamples = SEGMENT_SECONDS * sampleRate,
            overlapSamples = (OVERLAP_SECONDS * sampleRate).toInt(),
        )

    /**
     * 拼接各段文本：若后一段的开头与已拼好的结尾有公共子串（重叠段被识别了两遍），
     * 就去掉重复部分再拼。
     *
     * 只做「最长公共前后缀」这一件事：不猜同音字、不重排标点。宁可多留一个字，
     * 也不要把用户真说过的话当重复删掉——所以最短命中长度是 2 个字符，
     * 且只删完全相同的字符。
     */
    fun mergeSegments(segments: List<String>, maxChars: Int = MAX_MERGE_CHARS): String {
        val cleaned = segments.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return ""
        val builder = StringBuilder(cleaned.first())
        for (next in cleaned.drop(1)) {
            // 用「正文用字」比对，而不是原始字符串：25s 硬切处模型往往会给上一段补个句号、
            // 给下一段开头补个逗号（真机 2026-11-01 的「去澳。，去澳门」就是这种），
            // 带标点比对永远撞不上，接缝处就会把同一个词读两遍。
            val prevContent = contentChars(builder.toString())
            val nextContent = contentChars(next)
            val limit = minOf(maxChars, prevContent.length, nextContent.length)
            var overlap = 0
            for (k in limit downTo 2) {
                if (prevContent.substring(prevContent.length - k) == nextContent.substring(0, k)) {
                    overlap = k
                    break
                }
            }
            if (overlap >= 2) {
                val tail = next.substring(indexAfterContentChars(next, overlap))
                // 切段处模型补的句号/逗号：后面紧跟着被重复的正文，说明这个标点是切的痕迹，不是话的结束。
                while (builder.isNotEmpty() && builder.last() in SEAM_PUNCTUATION) {
                    builder.deleteCharAt(builder.length - 1)
                }
                builder.append(tail)
            } else {
                appendSegment(builder, next)
            }
        }
        return builder.toString()
    }

    /** 去掉标点与空白后的正文用字；与 faithfully 的「正文长度」口径一致。 */
    private fun contentChars(text: String): String =
        text.filterNot { it.isWhitespace() || it in PUNCTUATION }

    /** 第 [count] 个正文用字之后的下标（用于从下一段里切掉被重复的前缀）。 */
    private fun indexAfterContentChars(text: String, count: Int): Int {
        var seen = 0
        for ((index, ch) in text.withIndex()) {
            if (ch.isWhitespace() || ch in PUNCTUATION) continue
            seen++
            if (seen == count) return index + 1
        }
        return text.length
    }

    private const val PUNCTUATION = "，。！？；：、,.!?;:…—“”\"'（）()《》【】[]"

    /** 接缝处只清理句末标点：逗号/顿号留在原位也没意义，一并清掉。 */
    private val SEAM_PUNCTUATION = setOf('。', '，', '、', '；', '：', '！', '？', ',', '.', ';', ':', '!', '?')

    /**
     * 没有重叠可去时的接缝处理：
     * · 原文自带空格或标点 → 原样贴；
     * · 两边都是 ASCII 字母数字（英文单词）→ 补一个空格；
     * · 其余（典型是两个汉字段）→ 补一个逗号，避免「…说完这句下一句」粘连。
     */
    private fun appendSegment(builder: StringBuilder, tail: String) {
        if (tail.isEmpty()) return
        val last = builder.lastOrNull() ?: return
        val first = tail.first()
        if (last.isWhitespace() || first.isWhitespace() ||
            last in SEGMENT_PUNCTUATION || first in SEGMENT_PUNCTUATION
        ) {
            builder.append(tail)
            return
        }
        if (last.isAsciiWordChar() && first.isAsciiWordChar()) {
            builder.append(' ')
        } else {
            builder.append('，')
        }
        builder.append(tail)
    }

    private fun Char.isAsciiWordChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** 已经自带的段末标点：再补一个逗号会变成「，。」这种双标点。 */
    private val SEGMENT_PUNCTUATION = setOf('，', '。', '、', '！', '？', ',', '.', '!', '?', '；', ';', '：', ':')
}
