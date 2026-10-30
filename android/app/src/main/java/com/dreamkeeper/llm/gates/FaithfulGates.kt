package com.dreamkeeper.llm.gates

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Plan 3 Task 7：Python 五闸门 G1–G5 的纯 Kotlin 移植。
 *
 * 语义真源是 `scripts/check-faithful.py`，逐字照抄；冻结向量是
 * `docs/evidence/plan3/faithful-gate-vectors.json`。
 * 不含任何 android.* / org.json / kotlinx.serialization 依赖，可在 plain JUnit4 下运行。
 */
object FaithfulGates {

    /** 与 Python 的 PUNCT 逐字一致（34 个字符）。 */
    const val PUNCT: String = "，。！？；：、,.!?;:…—　 \n\r\t“”\"'（）()《》【】[]"

    private val FILLERS = listOf(
        "呃", "啊", "嗯", "唉", "那个", "就是说", "然后然后", "就是就是",
        "这个这个", "对对对", "反正", "其实吧", "怎么说呢",
    )

    private val UNCERTAIN = listOf(
        "好像", "记不清", "记不得", "大概", "似乎", "可能", "约莫", "说不定",
        "忘了", "不知道", "也可能", "应该是", "记混", "模糊",
    )

    private val CAUSAL = listOf("于是", "因此", "所以", "正因为", "导致", "结果", "从而", "故而")

    private val SCENE_MARKERS = listOf(
        "后来切换到了", "画面一转", "画面又一转", "画面再一转", "然后我就",
        "紧接着", "再后来", "这时候", "此时", "过了一会儿", "突然",
    )

    // 与 Python 的 DIRECTION_WORDS 逐项一致（左边/右边各出现两次，原样保留）。
    private val DIRECTION_WORDS = listOf(
        "上面", "下面", "里面", "外面", "前面", "后面", "左边", "右边",
        "旁边", "中间", "头顶", "脚下", "楼上", "楼下", "左边", "右边",
        "东边", "西边", "南边", "北边", "往上", "往下", "往左", "往右",
        "往上走", "往下掉",
    )

    private const val CN_NUM = "一二三四五六七八九十百千万两半几"
    private const val MEASURE = "个次条根层楼岁点分米人只块栋间道扇截群遍下天年号步声口"
    private const val SPEECH_VERBS = "说喊道问答叫嚷"
    private const val NEG_PREFIX = "不没未别莫"

    private val COLOR_RE = Regex("[红橙黄绿青蓝紫灰粉棕褐黑白银金彩]色")
    private val HAN_RE = Regex("[\\u4e00-\\u9fff]")
    private val ARABIC_NUM_RE = Regex("\\p{Nd}+(?:\\.\\p{Nd}+)?")
    private val CN_NUM_RE = Regex("[$CN_NUM]+[$MEASURE]")
    private val CN_NUM_MULTI_RE = Regex("[$CN_NUM]{2,}")
    private val QUOTED_RE = Regex("[“\"「『]([^”\"」』]{2,8})[”\"」』]")
    private val SPEECH_NAME_RE = Regex("([\\u4e00-\\u9fff]{2,4})[$SPEECH_VERBS]")

    // ---------------------------------------------------------------- 公共 API

    fun contentChars(s: String): String {
        val cps = contentCodePoints(s)
        return textOf(cps, 0, cps.size)
    }

    fun contentCharCount(s: String): Int = contentCodePoints(s).size

    fun isConservativeOk(input: String, output: String): Boolean =
        contentChars(input) == contentChars(output)

    fun check(
        input: String,
        output: String,
        mode: GateMode = GateMode.FAITHFUL,
    ): GateReport {
        // 五个闸门全部算完、不短路；failed 顺序即 G1→G5。
        val results = listOf(
            gateG1(input, output),
            gateG2(input, output, mode),
            gateG3(input, output),
            gateG4(input, output),
            gateG5(input, output),
        )
        return GateReport(mode, results)
    }

    // ---------------------------------------------------------------- G1

    private fun gateG1(input: String, output: String): GateResult {
        val problems = ArrayList<String>()
        addNewTokens(problems, "数量", extractNumbers(input), extractNumbers(output))
        addNewTokens(problems, "颜色", extractColors(input), extractColors(output))
        addNewTokens(problems, "方位", extractDirections(input), extractDirections(output))
        for (name in newNameLike(input, output)) {
            problems.add("疑似专名「$name」")
        }
        return GateResult(
            gate = Gate.G1,
            passed = problems.isEmpty(),
            value = problems.size.toDouble(),
            detail = problems.take(10),
            advisory = advisoryNewFragments(input, output),
        )
    }

    private fun addNewTokens(
        target: MutableList<String>,
        label: String,
        inputTokens: List<String>,
        outputTokens: List<String>,
    ) {
        val newTokens = (outputTokens.toSet() - inputTokens.toSet()).sorted()
        for (tok in newTokens) target.add("$label「$tok」")
    }

    private fun extractNumbers(text: String): List<String> {
        val out = ArrayList<String>()
        ARABIC_NUM_RE.findAll(text).mapTo(out) { it.value }
        CN_NUM_RE.findAll(text).mapTo(out) { it.value }
        CN_NUM_MULTI_RE.findAll(text).mapTo(out) { it.value }
        return out
    }

    private fun extractColors(text: String): List<String> =
        COLOR_RE.findAll(text).map { it.value }.toList()

    private fun extractDirections(text: String): List<String> =
        DIRECTION_WORDS.filter { it in text }

    private fun extractQuoted(text: String): List<String> =
        QUOTED_RE.findAll(text).map { it.groupValues[1] }.toList()

    private fun newNameLike(input: String, output: String): List<String> {
        val hits = ArrayList<String>()
        for (m in SPEECH_NAME_RE.findAll(output)) {
            val name = m.groupValues[1]
            if (name !in input) hits.add(name)
        }
        for (q in extractQuoted(output)) {
            if (q !in input && !COLOR_RE.containsMatchIn(q)) hits.add("“$q”")
        }
        return hits.distinct()
    }

    private fun advisoryNewFragments(input: String, output: String): List<String> {
        val inputBigrams = bigrams(removeFillers(input)).toSet()
        val outputBigrams = bigrams(removeFillers(output))
        val counts = LinkedHashMap<String, Int>()
        for (bg in outputBigrams) counts[bg] = (counts[bg] ?: 0) + 1
        return counts.entries
            .filter { (bg) ->
                bg !in inputBigrams && HAN_RE.containsMatchIn(bg) && bg !in FILLERS
            }
            .sortedByDescending { it.value }
            .take(12)
            .map { it.key }
    }

    private fun removeFillers(text: String): String {
        var out = text
        for (f in FILLERS) out = out.replace(f, "")
        return out
    }

    // ---------------------------------------------------------------- G2

    private fun gateG2(input: String, output: String, mode: GateMode): GateResult {
        val limit = if (mode == GateMode.REVISE) 1.15 else 1.10
        val a = contentCharCount(input)
        val b = contentCharCount(output)
        if (a == 0) {
            return GateResult(Gate.G2, false, null, listOf("输入为空"))
        }
        val ratio = b.toDouble() / a
        val passed = ratio <= limit
        val detail = if (passed) {
            emptyList()
        } else {
            listOf(
                String.format(
                    Locale.ROOT,
                    "输出/输入 = %.1f%%（上限 %.0f%%）",
                    ratio * 100,
                    limit * 100,
                ),
            )
        }
        return GateResult(Gate.G2, passed, round4(ratio), detail)
    }

    // ---------------------------------------------------------------- G3

    private fun gateG3(input: String, output: String): GateResult {
        val src = bigrams(input)
        if (src.isEmpty()) {
            return GateResult(Gate.G3, true, 1.0, emptyList())
        }
        val out = bigrams(output).toSet()
        val hit = src.count { it in out }
        val cov = hit.toDouble() / src.size
        val passed = cov >= 0.95
        val detail = if (passed) {
            emptyList()
        } else {
            val missing = LinkedHashMap<String, Int>()
            for (bg in src) {
                if (bg !in out) missing[bg] = (missing[bg] ?: 0) + 1
            }
            val worst = missing.entries
                .sortedByDescending { it.value }
                .take(6)
                .joinToString("、") { it.key }
            listOf(String.format(Locale.ROOT, "覆盖率 %.1f%%，掉得最多：%s", cov * 100, worst))
        }
        return GateResult(Gate.G3, passed, round4(cov), detail)
    }

    // ---------------------------------------------------------------- G4

    private fun gateG4(input: String, output: String): GateResult {
        val inCps = codePointsOf(input)
        val outCps = codePointsOf(output)
        val inputHasCausal = CAUSAL.any { containsCp(inCps, codePointsOf(it)) }
        val bad = ArrayList<String>()

        for (start in sceneSplits(outCps)) {
            val window = outCps.copyOfRange(start, minOf(start + 24, outCps.size))
            for (cStr in CAUSAL) {
                val c = codePointsOf(cStr)
                val pos = indexOfCp(window, c, 0)
                if (pos < 0) continue
                val ctxStart = maxOf(0, pos - 6)
                val ctxEnd = minOf(window.size, pos + c.size + 6)
                if (!containsCp(inCps, window.copyOfRange(ctxStart, ctxEnd))) {
                    val ctx = textOf(window, ctxStart, ctxEnd).replace('\n', ' ')
                    bad.add("跨场景新增因果「$cStr」（…$ctx…）")
                }
            }
        }

        if (!inputHasCausal) {
            for (cStr in CAUSAL) {
                val c = codePointsOf(cStr)
                var from = 0
                while (true) {
                    val pos = indexOfCp(outCps, c, from)
                    if (pos < 0) break
                    val ctxStart = maxOf(0, pos - 6)
                    val ctxEnd = minOf(outCps.size, pos + c.size + 6)
                    val marker = "跨场景新增因果「$cStr」"
                    if (!containsCp(inCps, outCps.copyOfRange(ctxStart, ctxEnd)) &&
                        marker !in (bad.joinToString(" ") + " ")
                    ) {
                        val ctx = textOf(outCps, ctxStart, ctxEnd).replace('\n', ' ')
                        bad.add("新增因果「$cStr」（…$ctx…）")
                        break
                    }
                    from = pos + c.size
                }
            }
        }

        return GateResult(Gate.G4, bad.isEmpty(), bad.size.toDouble(), bad.take(6))
    }

    /** 等价于 Python `_scene_splits`：空行（`\n\s*\n` 非重叠匹配的 end）+ 场景硬切词 start。 */
    private fun sceneSplits(text: IntArray): List<Int> {
        val idx = sortedSetOf(0)

        var i = 0
        while (i < text.size) {
            if (text[i] == '\n'.code) {
                var j = i + 1
                while (j < text.size && isWhitespace(text[j])) j++
                var lastNewline = -1
                var k = j - 1
                while (k > i) {
                    if (text[k] == '\n'.code) {
                        lastNewline = k
                        break
                    }
                    k--
                }
                if (lastNewline >= 0) {
                    idx.add(lastNewline + 1)
                    i = lastNewline + 1
                    continue
                }
            }
            i++
        }

        for (marker in SCENE_MARKERS) {
            val m = codePointsOf(marker)
            var from = 0
            while (true) {
                val at = indexOfCp(text, m, from)
                if (at < 0) break
                idx.add(at)
                from = at + m.size
            }
        }

        return idx.toList()
    }

    // ---------------------------------------------------------------- G5

    private fun gateG5(input: String, output: String): GateResult {
        val inCps = codePointsOf(input)
        val outCps = codePointsOf(output)
        var value = 0
        val bad = ArrayList<String>()
        for (w in UNCERTAIN) {
            val wc = codePointsOf(w)
            val need = countUncertain(inCps, wc)
            val got = countUncertain(outCps, wc)
            value += need
            if (need > 0 && got < need) {
                bad.add("「$w」原文 $need 处，输出只剩 $got 处")
            }
        }
        return GateResult(Gate.G5, bad.isEmpty(), value.toDouble(), bad.take(6))
    }

    /** 等价于 Python `count_uncertain`：非重叠计数，排除否定式前缀（不没未别莫）。 */
    private fun countUncertain(text: IntArray, word: IntArray): Int {
        var n = 0
        var start = 0
        while (true) {
            val i = indexOfCp(text, word, start)
            if (i < 0) return n
            if (i == 0 || !isNegPrefix(text[i - 1])) n++
            start = i + word.size
        }
    }

    // ---------------------------------------------------------------- 码点工具

    private fun codePointsOf(s: String): IntArray {
        val out = IntArray(s.length)
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out[n++] = cp
            i += Character.charCount(cp)
        }
        return out.copyOf(n)
    }

    private fun textOf(cps: IntArray, from: Int, to: Int): String {
        val sb = StringBuilder(to - from)
        var i = from
        while (i < to) {
            sb.appendCodePoint(cps[i])
            i++
        }
        return sb.toString()
    }

    private fun indexOfCp(text: IntArray, needle: IntArray, from: Int): Int {
        if (needle.isEmpty()) return if (from <= text.size) from else -1
        val last = text.size - needle.size
        var i = maxOf(0, from)
        while (i <= last) {
            var k = 0
            while (k < needle.size && text[i + k] == needle[k]) k++
            if (k == needle.size) return i
            i++
        }
        return -1
    }

    private fun containsCp(text: IntArray, needle: IntArray): Boolean =
        indexOfCp(text, needle, 0) >= 0

    private fun isNegPrefix(cp: Int): Boolean =
        cp <= 0xFFFF && NEG_PREFIX.indexOf(cp.toChar()) >= 0

    private fun isWhitespace(cp: Int): Boolean = when (cp) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D,
        0x1C, 0x1D, 0x1E, 0x1F,
        0x20, 0x85, 0xA0, 0x1680,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        in 0x2000..0x200A -> true
        else -> false
    }

    private fun isPunct(cp: Int): Boolean =
        cp <= 0xFFFF && PUNCT.indexOf(cp.toChar()) >= 0

    private fun contentCodePoints(s: String): IntArray {
        val cps = codePointsOf(s)
        val out = IntArray(cps.size)
        var n = 0
        for (cp in cps) {
            if (isWhitespace(cp) || isPunct(cp)) continue
            out[n++] = cp
        }
        return out.copyOf(n)
    }

    private fun bigrams(s: String): List<String> {
        val cps = contentCodePoints(s)
        if (cps.size < 2) return emptyList()
        val out = ArrayList<String>(cps.size - 1)
        for (i in 0 until cps.size - 1) out.add(textOf(cps, i, i + 2))
        return out
    }

    private fun round4(x: Double): Double =
        BigDecimal.valueOf(x).setScale(4, RoundingMode.HALF_EVEN).toDouble()
}
