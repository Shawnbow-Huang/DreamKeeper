package com.dreamkeeper.llm

/**
 * 思考档位（Plan 5 Task 2，设计 §9 的唯一音源）。
 *
 * 设计 §9 写死了四档 `OFF / LOW / HIGH / MAX`，没有 medium——服务端只有这三个取值，
 * 多摆一档「中」会让用户以为存在第五种行为，实际只是偷偷映射到 low 或 high。
 *
 * [effort] 为 null 表示关闭思考；非 null 时 [thinking] 必然是 `ENABLED`，两者永远同进同退。
 */
enum class ThinkingLevel(val label: String, val effort: ReasoningEffort?) {
    OFF("关", null),
    LOW("低", ReasoningEffort.LOW),
    HIGH("高", ReasoningEffort.HIGH),
    MAX("最高", ReasoningEffort.MAX),
    ;

    /** 请求体 `thinking.type` 的值。 */
    val thinking: Thinking
        get() = if (effort == null) Thinking.DISABLED else Thinking.ENABLED
}

/**
 * 生成链的可变配置（模型 + 小说化思考档位）。
 *
 * 单例而不是散落参数：设置页写、编排器读，中间不经过 ViewModel——用户改完档位
 * 不必退回详情页重进才能生效。
 *
 * 忠实整理（faithful）有独立档位（v3 起，默认「低」）：它现在允许换词，
 * 需要一点判断但不需要长篇推理；开思考时与小说化一样不发 temperature。
 */
data class GenerationOptions(
    /** Plan 6：当前供应商；生成链按它选 baseUrl / 请求体风格。 */
    val provider: ProviderId = ProviderId.DEEPSEEK,
    val model: String = DEFAULT_MODEL,
    /**
     * 忠实整理用的模型（Plan 6 追加）：默认跟随小说化模型，用户可以单独选一个更便宜的。
     *
     * 忠实链的思考档位仍固定关闭（设计 §9）：整理要做的是「逐字保守」，
     * 开思考只会更慢更贵，闸门也不接受重排。
     */
    val faithfulModel: String = model,
    /** 小说化默认「高」；改稿固定「低」（见 ReviseOrchestrator 的 REVISE_LEVEL）。 */
    val novelThinking: ThinkingLevel = ThinkingLevel.HIGH,
    /** 原样整理默认「低」：换句不换事，不需要长推理。 */
    val faithfulThinking: ThinkingLevel = ThinkingLevel.LOW,
) {
    companion object {
        const val FLASH = "deepseek-flash"
        const val PRO = "deepseek-v4-pro"

        /** DeepSeek 的模型（旧常量保留：Plan 6 之前只有这一家）。 */
        val MODELS = listOf(FLASH, PRO)

        const val DEFAULT_MODEL = FLASH

        val DEFAULT = GenerationOptions()

        fun modelLabel(model: String): String = when (model) {
            FLASH -> "DeepSeek Flash"
            PRO -> "DeepSeek V4 Pro"
            else -> model
        }
    }
}
