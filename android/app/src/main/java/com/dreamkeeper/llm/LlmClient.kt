package com.dreamkeeper.llm

/** Chat Completions 客户端的最小接口；按接口注入，Task 8 与单测各自替换实现。 */
interface LlmClient {
    suspend fun chat(req: ChatRequest): ChatResponse
}

/**
 * 重试策略。
 *
 * [maxAttempts] 是**总请求次数**（含首次），默认 4 = 首次 + 3 次重试。
 * [baseDelayMs] 从 1s 起、按 [factor] 翻倍，单次退避不超过 [maxDelayMs]；
 * 服务端给了 `Retry-After` 时优先用它，但同样夹到 [retryAfterMaxMs] 以内，
 * 防止一个坏掉的 header 把调用挂住几分钟。
 *
 * 退避默认带 0..250ms 随机抖动（由 [DeepSeekClient] 的 `jitter` 注入），
 * 单测注入常量 0，断言精确的 1000/2000/4000。
 */
data class RetryPolicy(
    val maxAttempts: Int = 4,
    val baseDelayMs: Long = 1_000L,
    val factor: Int = 2,
    val maxDelayMs: Long = 30_000L,
    val retryAfterMaxMs: Long = 60_000L,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts 至少为 1，实际 $maxAttempts" }
        require(baseDelayMs >= 0) { "baseDelayMs 不能为负，实际 $baseDelayMs" }
        require(factor >= 1) { "factor 至少为 1，实际 $factor" }
        require(maxDelayMs >= 0) { "maxDelayMs 不能为负，实际 $maxDelayMs" }
        require(retryAfterMaxMs >= 0) { "retryAfterMaxMs 不能为负，实际 $retryAfterMaxMs" }
    }
}
