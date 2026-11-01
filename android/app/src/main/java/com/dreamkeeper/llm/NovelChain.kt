package com.dreamkeeper.llm

import com.dreamkeeper.llm.gates.NovelGates
import org.json.JSONObject

/** 小说化 v3 的创作手记一条（`type ∈ 借用/舍弃/新造/主题`）。 */
data class NovelNote(val type: String, val detail: String)

data class NovelDraft(
    val title: String,
    val body: String,
    val notes: List<NovelNote>,
    val mood: String,
)

/** 三段式结果：要么全绿，要么带阶段与原因失败（不静默产出半截）。 */
sealed interface NovelChainResult {
    data class Success(
        val draft: NovelDraft,
        /** 依次经过的阶段，供 `generation_job` 记账/测试断言。 */
        val stages: List<GenerationStage>,
        /** POLISH 两次都没过闸门时回退 DRAFT，并追加一条 WARN 手记。 */
        val polishFallback: Boolean,
    ) : NovelChainResult

    data class Failure(
        val stage: GenerationStage,
        val reason: String,
        val stages: List<GenerationStage>,
    ) : NovelChainResult
}

/**
 * 规划 → 写作 → 润色。
 *
 * 依赖全部用函数注入：三段提示词怎么渲染、怎么调模型、模型答什么，测试里换掉即可，不碰 Room/网络。
 * 每段最多 [attemptsPerStage] 次；PLAN/DRAFT 失败即整次失败，POLISH 失败回退 DRAFT 并标 WARN。
 */
class NovelChain(
    private val renderPlan: (raw: String, base: String, idea: String?) -> String,
    private val renderDraft: (planJson: String, base: String, idea: String?) -> String,
    private val renderPolish: (planJson: String, draftJson: String) -> String,
    private val chat: suspend (stage: GenerationStage, prompt: String) -> String,
    private val attemptsPerStage: Int = 2,
) {

    suspend fun run(raw: String, base: String, idea: String?): NovelChainResult {
        val stages = mutableListOf<GenerationStage>()

        // ---- PLAN：只出规划 JSON；两次都不合法就整次失败。
        stages += GenerationStage.PLAN
        val planPrompt = renderPlan(raw, base, idea)
        var planRaw: String? = null
        val plan = attempt(GenerationStage.PLAN, planPrompt) { text ->
            NovelPlan.parse(text, base)?.also { planRaw = text }
        } ?: return NovelChainResult.Failure(GenerationStage.PLAN, "规划 JSON 两次都不合法", stages)
        val planJson = JSONObject(NovelPlan.stripFence(requireNotNull(planRaw))).toString()

        // ---- DRAFT：按规划写正文；N1/N2 都要过。
        stages += GenerationStage.DRAFT
        val draftPrompt = renderDraft(planJson, base, idea)
        val draft = attempt(GenerationStage.DRAFT, draftPrompt) { text -> parseDraft(text) }
            ?: return NovelChainResult.Failure(GenerationStage.DRAFT, "草稿两次都没过 N1/N2", stages)
        val draftJson = draftJsonOf(draft)

        // ---- POLISH：润色失败不拦整次，回退草稿并加一条 WARN 手记。
        stages += GenerationStage.POLISH
        val polishPrompt = renderPolish(planJson, draftJson)
        val polished = attempt(GenerationStage.POLISH, polishPrompt) { text -> parseDraft(text) }
        return if (polished != null) {
            NovelChainResult.Success(polished, stages, polishFallback = false)
        } else {
            val warn = NovelNote("主题", "润色两次未过闸门，保留草稿")
            NovelChainResult.Success(draft.copy(notes = (draft.notes + warn).take(6)), stages, polishFallback = true)
        }
    }

    /** 每段重试 [attemptsPerStage] 次；[parse] 返回 null 视为不合法。 */
    private suspend fun <T : Any> attempt(
        stage: GenerationStage,
        prompt: String,
        parse: (String) -> T?,
    ): T? {
        repeat(attemptsPerStage) {
            val text = runCatching { chat(stage, prompt) }.getOrNull() ?: return@repeat
            parse(text)?.let { return it }
        }
        return null
    }

    private fun parseDraft(text: String): NovelDraft? {
        val report = NovelGates.check(text)
        if (!report.passed) return null
        val o = runCatching { JSONObject(NovelGates.stripFence(text)) }.getOrNull() ?: return null
        val notes = o.getJSONArray("notes").let { arr ->
            (0 until arr.length()).map { i ->
                val n = arr.getJSONObject(i)
                NovelNote(n.getString("type").trim(), n.getString("detail").trim())
            }
        }
        return NovelDraft(
            title = o.getString("title").trim(),
            body = o.getString("body").trim(),
            notes = notes,
            mood = o.getString("mood").trim(),
        )
    }

    private fun draftJsonOf(draft: NovelDraft): String = buildString {
        append("{\"title\":").append(JSONObject.quote(draft.title))
        append(",\"body\":").append(JSONObject.quote(draft.body))
        append(",\"notes\":[")
        append(draft.notes.joinToString(",") { n ->
            "{\"type\":${JSONObject.quote(n.type)},\"detail\":${JSONObject.quote(n.detail)}}"
        })
        append("],\"mood\":").append(JSONObject.quote(draft.mood)).append("}")
    }
}
