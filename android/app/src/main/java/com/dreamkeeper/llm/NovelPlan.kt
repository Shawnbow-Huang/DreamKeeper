package com.dreamkeeper.llm

import org.json.JSONObject

/** 一个场景的 sequel：反应 → 两难 → 决定。 */
data class NovelSequel(
    val reaction: String,
    val dilemma: String,
    val decision: String,
) {
    val isComplete: Boolean
        get() = reaction.isNotBlank() && dilemma.isNotBlank() && decision.isNotBlank()
}

/** PLAN 阶段的一个场景：目标 / 冲突 / 结果 + sequel。 */
data class NovelScene(
    val goal: String,
    val conflict: String,
    val outcome: String,
    val sequel: NovelSequel,
) {
    val isComplete: Boolean
        get() = goal.isNotBlank() && conflict.isNotBlank() && outcome.isNotBlank() && sequel.isComplete
}

/**
 * PLAN 阶段的结构化产物（spec §4.2.1）。
 *
 * [parse] 是唯一入口：剥代码围栏、逐字段校验、`coreImages` 必须逐字来自原样稿；
 * 任何一条不满足都返回 null，调用方据此重试一次，而不是把半截规划送进 DRAFT。
 */
data class NovelPlan(
    val premise: String,
    val want: String,
    val obstacle: String,
    val cost: String,
    val controllingIdea: String,
    val counterPremise: String,
    val pov: Pov,
    val targetLength: Int,
    val scenes: List<NovelScene>,
    val endingChange: String,
    val coreImages: List<String>,
    val emotionalTruth: String,
) {
    enum class Pov { FIRST, THIRD }

    fun isValid(raw: String): Boolean =
        premise.isNotBlank() &&
            want.isNotBlank() &&
            obstacle.isNotBlank() &&
            cost.isNotBlank() &&
            controllingIdea.isNotBlank() && controllingIdea.contains("因为") &&
            counterPremise.isNotBlank() &&
            targetLength in 800..5000 &&
            scenes.size in 3..5 && scenes.all { it.isComplete } &&
            endingChange.isNotBlank() &&
            coreImages.size in 1..6 && coreImages.all { raw.contains(it) } &&
            emotionalTruth.isNotBlank()

    companion object {
        fun parse(json: String, raw: String): NovelPlan? = runCatching {
            val o = JSONObject(stripFence(json))
            val scenes = o.getJSONArray("scenes").let { arr ->
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    val seq = s.getJSONObject("sequel")
                    NovelScene(
                        goal = s.getString("goal").trim(),
                        conflict = s.getString("conflict").trim(),
                        outcome = s.getString("outcome").trim(),
                        sequel = NovelSequel(
                            reaction = seq.getString("reaction").trim(),
                            dilemma = seq.getString("dilemma").trim(),
                            decision = seq.getString("decision").trim(),
                        ),
                    )
                }
            }
            val images = o.getJSONArray("coreImages").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
            NovelPlan(
                premise = o.getString("premise").trim(),
                want = o.getString("want").trim(),
                obstacle = o.getString("obstacle").trim(),
                cost = o.getString("cost").trim(),
                controllingIdea = o.getString("controllingIdea").trim(),
                counterPremise = o.getString("counterPremise").trim(),
                pov = when (o.getString("pov").trim().uppercase()) {
                    "FIRST" -> Pov.FIRST
                    "THIRD" -> Pov.THIRD
                    else -> error("pov 只能是 FIRST/THIRD")
                },
                targetLength = o.getInt("targetLength"),
                scenes = scenes,
                endingChange = o.getString("endingChange").trim(),
                coreImages = images,
                emotionalTruth = o.getString("emotionalTruth").trim(),
            )
        }.getOrNull()?.takeIf { it.isValid(raw) }

        /** 模型偶尔会包 ```json 围栏；剥掉，避免 JSONObject 直接解析失败。 */
        fun stripFence(s: String): String = s.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
    }
}
