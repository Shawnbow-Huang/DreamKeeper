package com.dreamkeeper.llm

/**
 * 支持的模型供应商。
 *
 * [wire] 是稳定标识：既用于持久化键后缀（`api_key_packed.<wire>`），也用于卡片与证据。
 * 它**不随界面文案变化**——改显示名不影响已保存的 Key。
 *
 * 顺序即设置页 chips 的顺序：默认的 DeepSeek 排第一。
 */
enum class ProviderId(val wire: String, val displayName: String) {
    DEEPSEEK("deepseek", "DeepSeek"),
    MIMO("mimo", "小米 MiMo"),
    MINIMAX("minimax", "MiniMax"),
    QWEN("qwen", "通义千问"),
    GLM("glm", "智谱 GLM"),
    ;

    companion object {
        /** 未知 wire 返回 null（旧版本/手改 pref 都按未知处理，不回退到别家）。 */
        fun fromWire(wire: String?): ProviderId? = entries.firstOrNull { it.wire == wire }
    }
}
