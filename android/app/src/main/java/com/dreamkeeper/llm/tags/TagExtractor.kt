package com.dreamkeeper.llm.tags

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把模型返回的标签 JSON 解析成可入库的草稿；任何不合格都返回失败原因，不抛异常。
 *
 * 校验口径与 `prompts/tag.v1.md` 一一对应：3–6 个、name 1–8 字、kind 白名单、
 * evidence 必须是原样稿里的逐字片段、同名去重。
 */
object TagExtractor {

    data class Draft(val name: String, val kind: String, val evidence: String)

    sealed interface Result {
        data class Ok(val drafts: List<Draft>) : Result
        data class Bad(val reason: String) : Result
    }

    val KINDS = setOf("PERSON", "PLACE", "OBJECT", "ACTION", "EMOTION", "OTHER")

    private const val MIN_TAGS = 3
    private const val MAX_TAGS = 6
    private const val MAX_NAME = 8

    fun parse(raw: String, source: String): Result {
        val json = stripFence(raw).trim()
        if (!json.startsWith("{")) return Result.Bad("不是 JSON 对象")
        val root = runCatching { JSONObject(json) }.getOrElse { return Result.Bad("JSON 解析失败") }
        val array = root.optJSONArray("tags") ?: return Result.Bad("缺少 tags 数组")
        return validate(array, source)
    }

    private fun validate(array: JSONArray, source: String): Result {
        if (array.length() < MIN_TAGS || array.length() > MAX_TAGS) {
            return Result.Bad("标签数量 ${array.length()}，要求 $MIN_TAGS–$MAX_TAGS")
        }
        val drafts = mutableListOf<Draft>()
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: return Result.Bad("第 ${i + 1} 个不是对象")
            val name = o.optString("name").trim()
            val kind = o.optString("kind").trim().uppercase()
            val evidence = o.optString("evidence").trim()
            if (name.isEmpty() || name.length > MAX_NAME) return Result.Bad("名字长度不合格：$name")
            if (kind !in KINDS) return Result.Bad("未知 kind：$kind")
            if (evidence.length < 2 || evidence.length > 12) return Result.Bad("evidence 长度不合格：$evidence")
            if (!source.contains(evidence)) return Result.Bad("evidence 不在原文里：$evidence")
            val key = name.lowercase().replace(" ", "")
            if (!seen.add(key)) continue
            drafts += Draft(name, kind, evidence)
        }
        if (drafts.size < MIN_TAGS) return Result.Bad("去重后只剩 ${drafts.size} 个")
        return Result.Ok(drafts)
    }

    private fun stripFence(raw: String): String {
        val t = raw.trim()
        if (!t.startsWith("```")) return t
        val body = t.removePrefix("```")
        val firstLineBreak = body.indexOf('\n')
        val withoutLang = if (firstLineBreak >= 0) body.substring(firstLineBreak + 1) else body
        return withoutLang.substringBeforeLast("```").trim()
    }
}
