package com.dreamkeeper.llm.gates

import org.json.JSONArray
import org.json.JSONObject

/** 小说化 v3 的单条闸门结果（N1/N2 进 failed；N3 只出 hints）。 */
data class NovelGateResult(val code: String, val passed: Boolean, val detail: String)

data class NovelGateReport(
    val results: List<NovelGateResult>,
    val hints: List<String>,
) {
    val failed: List<String> = results.filter { !it.passed }.map { it.code }

    val passed: Boolean get() = failed.isEmpty()
}

/**
 * 小说化 v3 的 N1/N2/N3。
 *
 * 语义逐字对齐 `scripts/check-novel.py`（唯一真源）；改阈值/清单必须两边同时改。
 * - N1 结构：标题 4–14、正文 800–5000 内容字且 ≥3 段、创作手记 3–6 条（type/detail）、mood 1–6；
 * - N2 文风：A7 禁词、解码句、套话各 0；filter words ≤10/千字、「地」副词 ≤12/千字、说书标签 ≤2、破折号 ≤3；
 * - N3 完整度：只出 hints，不进 failed。
 */
object NovelGates {

    private val A7 = listOf(
        "仿佛", "宛如", "不禁", "不由得", "心中一震", "五味杂陈", "难以言喻",
        "意味深长", "若有所思", "嘴角微微上扬", "眼神深邃",
    )
    private val DECODE = listOf("象征着", "意味着", "其实是在说", "代表着", "暗示着")
    private val CLICHES = listOf(
        "不仅仅", "值得注意", "总而言之", "让我们拭目以待", "奠定基础",
        "充满活力", "令人叹为观止", "专家认为", "在当今",
    )
    private val FILTER = listOf("看见", "听到", "感觉到", "意识到", "发现", "觉得", "注意到")
    private val TELL = listOf("吼道", "冷哼道", "嗤笑道", "怒道", "沉声道", "笑道")
    private val NOTES_TYPES = setOf("借用", "舍弃", "新造", "主题")

    fun contentCharCount(text: String): Int =
        Regex("[\u4e00-\u9fffA-Za-z0-9]").findAll(text).count()

    /** 解析失败直接 N1 红：不能把半个 JSON 当稿子。 */
    fun check(json: String): NovelGateReport {
        val novel = runCatching { JSONObject(stripFence(json)) }.getOrNull()
            ?: return NovelGateReport(
                results = listOf(NovelGateResult("N1", false, "不是合法 JSON")),
                hints = emptyList(),
            )
        val body = novel.optString("body")
        return NovelGateReport(
            results = listOf(n1(novel, body), n2(body)),
            hints = hints(body),
        )
    }

    fun n1(novel: JSONObject, body: String): NovelGateResult {
        val problems = mutableListOf<String>()
        val title = novel.optString("title").trim()
        val notes = novel.optJSONArray("notes") ?: JSONArray()
        val mood = novel.optString("mood").trim()

        if (title.length !in 4..14) problems += "标题 ${title.length} 字，不在 4–14"
        val chars = contentCharCount(body)
        if (chars !in 800..5000) problems += "正文 $chars 内容字，不在 800–5000"
        val paragraphs = body.split(Regex("""\n\s*\n""")).count { it.isNotBlank() }
        if (paragraphs < 3) problems += "正文只有 $paragraphs 段，需 ≥3"
        if (notes.length() !in 3..6) problems += "创作手记 ${notes.length()} 条，需 3–6"
        for (i in 0 until notes.length()) {
            val note = notes.optJSONObject(i)
            if (note == null) {
                problems += "手记 ${i + 1} 不是对象"
                continue
            }
            if (note.optString("type") !in NOTES_TYPES) {
                problems += "手记 ${i + 1} type=${note.optString("type")} 不在 ${NOTES_TYPES.sorted()}"
            }
            val detail = note.optString("detail").trim()
            if (detail.length !in 1..60) problems += "手记 ${i + 1} detail ${detail.length} 字，需 1–60"
        }
        if (mood.length !in 1..6) problems += "mood ${mood.length} 字，需 1–6"
        return NovelGateResult("N1", problems.isEmpty(), problems.joinToString("；"))
    }

    fun n2(body: String): NovelGateResult {
        val chars = contentCharCount(body)
        if (chars == 0) return NovelGateResult("N2", false, "正文为空")
        val perK = { count: Int -> count * 1000.0 / chars }
        val problems = mutableListOf<String>()

        A7.firstOrNull { body.contains(it) }?.let { problems += "A7 禁词：$it" }
        DECODE.firstOrNull { body.contains(it) }?.let { problems += "解码句：$it" }
        CLICHES.firstOrNull { body.contains(it) }?.let { problems += "套话：$it" }

        val filter = FILTER.sumOf { body.windowed(it.length).count { w -> w == it } }
        if (perK(filter) > 10) problems += "filter words ${"%.1f".format(perK(filter))}/千字（上限 10）"
        val de = Regex("[\u4e00-\u9fff]地").findAll(body).count()
        if (perK(de) > 12) problems += "「地」副词 ${"%.1f".format(perK(de))}/千字（上限 12）"
        val tell = TELL.sumOf { body.windowed(it.length).count { w -> w == it } }
        if (tell > 2) problems += "说书标签 $tell 次（上限 2）"
        val dashes = body.count { it == '—' }
        if (dashes > 3) problems += "破折号 $dashes 个（上限 3）"
        return NovelGateResult("N2", problems.isEmpty(), problems.joinToString("；"))
    }

    fun hints(body: String): List<String> {
        val hints = mutableListOf<String>()
        val paragraphs = body.split(Regex("""\n\s*\n""")).count { it.isNotBlank() }
        if (paragraphs < 3) hints += "段落少于 3 段"
        if (!body.contains("「") && !body.contains("“")) hints += "没有对白"
        val sentences = body.split(Regex("[。！？!?]")).filter { it.isNotBlank() }
        if (sentences.size >= 20) {
            val lengths = sentences.map { contentCharCount(it) }
            val mean = lengths.average()
            val sd = kotlin.math.sqrt(lengths.sumOf { (it - mean) * (it - mean) } / lengths.size)
            if (sd < 5) hints += "句长变化不足（标准差 < 5）"
        }
        return hints
    }

    /** 模型偶尔包 ```json 围栏；与 NovelPlan 用同一套剥离规则。 */
    fun stripFence(s: String): String = s.trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()
}
