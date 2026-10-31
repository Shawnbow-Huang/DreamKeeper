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
 * 忠实整理（faithful）**不吃这个档位**：设计 §9 要求它恒 `thinking=disabled`、temp 0.1，
 * 开着思考整理一段口述只会更慢更贵，且闸门本来就要逐字保守。
 */
data class GenerationOptions(
    val model: String = DEFAULT_MODEL,
    /** 小说化默认「高」；改稿固定「低」（见 ReviseOrchestrator 的 REVISE_LEVEL）。 */
    val novelThinking: ThinkingLevel = ThinkingLevel.HIGH,
) {
    companion object {
        const val FLASH = "deepseek-flash"
        const val PRO = "deepseek-v4-pro"

        /** 设置页展示顺序：默认档在前。 */
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
