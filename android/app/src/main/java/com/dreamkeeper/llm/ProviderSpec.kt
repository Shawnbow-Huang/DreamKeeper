package com.dreamkeeper.llm

/**
 * 思考开关在请求体里的方言。
 *
 * 五家都叫「思考」，但线协议不一样：
 * - DeepSeek / MiMo / GLM：顶层 `thinking: {"type": "enabled"|"disabled"}`
 * - MiniMax：`thinking: {"type": "adaptive"|"disabled"}`（开了只能 adaptive，没有 effort）
 * - Qwen / DashScope：顶层布尔 `enable_thinking: true|false`
 */
enum class ThinkingStyle {
    THINKING_OBJECT,
    MINIMAX_THINKING,
    QWEN_ENABLE,
}

/** 一个可选的模型。能力位决定请求体能出现哪些字段。 */
data class ModelSpec(
    val id: String,
    val label: String,
    /** 是否支持 `reasoning_effort`（DeepSeek / Qwen / GLM / MiMo 部分模型）。 */
    val supportsEffort: Boolean = false,
    /** 是否支持 `response_format={"type":"json_object"}`。MiniMax 不发。 */
    val supportsJsonMode: Boolean = false,
    /** 强制思考：不能关，也就不能做「忠实整理」（必须低温、无思考）。 */
    val forcedThinking: Boolean = false,
)

/** 一家供应商的静态契约。**端点与模型都标「需实测确认」**（D502），v1 只做 OpenAI 兼容方言。 */
data class ProviderSpec(
    val provider: ProviderId,
    val baseUrl: String,
    val models: List<ModelSpec>,
    val thinkingStyle: ThinkingStyle,
    val supportsResponseFormat: Boolean,
    val chatPath: String = "/chat/completions",
    /** effort 的线值映射：不同家对 low/high/max 的命名不同（Qwen 用 xhigh，没有 high）。 */
    val effortWire: Map<ReasoningEffort, String> = mapOf(
        ReasoningEffort.LOW to "low",
        ReasoningEffort.HIGH to "high",
        ReasoningEffort.MAX to "max",
    ),
) {
    fun model(id: String?): ModelSpec? = models.firstOrNull { it.id == id }

    /** 默认模型：列表第一个；调用方传了未知 id 时也回它。 */
    fun defaultModel(): ModelSpec = models.first()
}

/**
 * 五家的 v1 契约（Plan 6 §0.2）。
 *
 * 模型清单刻意收敛：强制思考的模型（MiniMax-M3.1-Flash、GLM-5.3）不进 v1——
 * 它们无法满足忠实整理的「低温 + 关思考」，放进来只会让三级兜底链无从谈起。
 */
object ProviderRegistry {
    val DEEPSEEK = ProviderSpec(
        provider = ProviderId.DEEPSEEK,
        baseUrl = "https://api.deepseek.com",
        thinkingStyle = ThinkingStyle.THINKING_OBJECT,
        supportsResponseFormat = true,
        models = listOf(
            ModelSpec("deepseek-flash", "DeepSeek Flash", supportsEffort = true, supportsJsonMode = true),
            ModelSpec("deepseek-v4-pro", "DeepSeek V4 Pro", supportsEffort = true, supportsJsonMode = true),
        ),
    )

    val MIMO = ProviderSpec(
        provider = ProviderId.MIMO,
        baseUrl = "https://api.xiaomimimo.com/v1",
        thinkingStyle = ThinkingStyle.THINKING_OBJECT,
        supportsResponseFormat = true,
        models = listOf(
            ModelSpec("mimo-v2.6-pro", "MiMo V2.6 Pro", supportsJsonMode = true),
            ModelSpec("mimo-v2.6-flash", "MiMo V2.6 Flash", supportsJsonMode = true),
        ),
    )

    val MINIMAX = ProviderSpec(
        provider = ProviderId.MINIMAX,
        baseUrl = "https://api.minimax.cn/v1",
        thinkingStyle = ThinkingStyle.MINIMAX_THINKING,
        supportsResponseFormat = false,
        models = listOf(
            ModelSpec("MiniMax-M3", "MiniMax M3"),
        ),
    )

    val QWEN = ProviderSpec(
        provider = ProviderId.QWEN,
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        thinkingStyle = ThinkingStyle.QWEN_ENABLE,
        supportsResponseFormat = true,
        models = listOf(
            ModelSpec("qwen3.8-max", "Qwen 3.8 Max", supportsEffort = true, supportsJsonMode = true),
            ModelSpec("qwen-plus", "Qwen Plus", supportsJsonMode = true),
        ),
        effortWire = mapOf(
            ReasoningEffort.LOW to "low",
            ReasoningEffort.HIGH to "xhigh",
            ReasoningEffort.MAX to "xhigh",
        ),
    )

    val GLM = ProviderSpec(
        provider = ProviderId.GLM,
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        thinkingStyle = ThinkingStyle.THINKING_OBJECT,
        supportsResponseFormat = true,
        models = listOf(
            ModelSpec("glm-5.2", "GLM 5.2", supportsEffort = true, supportsJsonMode = true),
        ),
    )

    val all: List<ProviderSpec> = listOf(DEEPSEEK, MIMO, MINIMAX, QWEN, GLM)

    fun of(provider: ProviderId): ProviderSpec = all.first { it.provider == provider }
}
