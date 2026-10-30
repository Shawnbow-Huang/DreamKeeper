package com.dreamkeeper.llm

/**
 * 思考模式开关，`wire` 直接进请求体 `thinking.type`。
 *
 * 用枚举而不是 `Boolean`：请求体是线协议的一部分，枚举在编译期就挡住拼错
 * （`"enable"` / `"enabled"` 这类错误只有真实请求才会暴露）。
 */
enum class Thinking(val wire: String) {
    ENABLED("enabled"),
    DISABLED("disabled"),
}

/**
 * 思考强度，`wire` 直接进请求体顶层 `reasoning_effort`。
 *
 * 只有 [Thinking.ENABLED] 时才允许发送；具体取值由调用方（Task 8 的模型路由）决定。
 */
enum class ReasoningEffort(val wire: String) {
    LOW("low"),
    HIGH("high"),
    MAX("max"),
}

/** 一条对话消息。`role` 用字符串：DeepSeek 目前只有 system/user/assistant，未来可能扩展。 */
data class ChatMessage(val role: String, val content: String)

/**
 * 一次 Chat Completions 请求。
 *
 * 线上的可选字段由 [thinking] / [effort] / [temperature] / [jsonMode] 共同决定：
 * - [Thinking.ENABLED]：发 `thinking.type=enabled` + 顶层 `reasoning_effort`，**不发** `temperature`
 *   （思考模式下温度会被服务端忽略，发出去只会让请求体难以断言）；
 * - [Thinking.DISABLED]：发 `thinking.type=disabled`；[temperature] 非空时才发。
 * - [jsonMode]：追加 `response_format={"type":"json_object"}`。
 *
 * [apiKey] 只在这里短暂存在，日志只能打后四位；[toString] 已做掩码，避免 data class 自动
 * toString 把完整 Key 带进崩溃日志。
 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val thinking: Thinking = Thinking.DISABLED,
    val effort: ReasoningEffort? = null,
    val temperature: Double? = null,
    val jsonMode: Boolean = false,
    val apiKey: String,
) {
    override fun toString(): String =
        "ChatRequest(model=$model, messages=${messages.size} 条, thinking=$thinking, " +
            "effort=$effort, temperature=$temperature, jsonMode=$jsonMode, " +
            "apiKey=${if (apiKey.length <= 4) "…" else "…${apiKey.takeLast(4)}"})"
}

/**
 * token 用量，字段名与 DeepSeek 的 `usage` 对齐。
 *
 * 全部用 `Long`：`Int` 在超长上下文下会溢出，且 Task 8 的计价入参就是 `Long`。
 * [promptCacheHitTokens] + [promptCacheMissTokens] 不一定等于 [totalTokens]（服务端口径），
 * 所以照原样保留，不做推导。
 */
data class Usage(
    val promptCacheHitTokens: Long,
    val promptCacheMissTokens: Long,
    val completionTokens: Long,
    val reasoningTokens: Long,
    val totalTokens: Long,
)

/**
 * 一次 HTTP 交换的审计记录（成功和失败都要记，Task 8 要落 `llm_calls` 表）。
 *
 * [httpStatus] 为 `null` 表示传输层就失败了（DNS / 连接 / 读超时 / 取消），此时没有 HTTP 状态码；
 * [errorKind] 为 `null` 表示这次交换成功。
 */
data class Attempt(
    val attemptNo: Int,
    val httpStatus: Int?,
    val latencyMs: Long,
    val errorKind: LlmException.Kind?,
    val requestId: String?,
    val startedAtMs: Long,
)

/** 一次成功调用的结果。[latencyMs] 是端到端耗时（含重试退避），不是最后一次交换的耗时。 */
data class ChatResponse(
    val content: String,
    val reasoning: String?,
    val usage: Usage?,
    val requestId: String?,
    val httpStatus: Int,
    val latencyMs: Long,
    val finishReason: String?,
    val attempts: List<Attempt>,
)

/**
 * 客户端统一异常。
 *
 * [kind] 是唯一需要分支的字段；[httpStatus] / [requestId] / [attempts] 用于落库和排障。
 * **取消不是 [LlmException]**：`CancellationException` 原样透传（协作式取消语义），
 * 否则上层分不清「用户离页」和「网络失败」，会把一次主动取消记成一次失败调用。
 */
class LlmException(
    val kind: Kind,
    val httpStatus: Int?,
    val requestId: String?,
    val attempts: List<Attempt>,
    cause: Throwable? = null,
) : Exception(
    buildString {
        append(kind.name)
        if (httpStatus != null) append(" HTTP ").append(httpStatus)
        if (requestId != null) append(" requestId=").append(requestId)
        append(" attempts=").append(attempts.size)
    },
    cause,
) {
    /**
     * 错误分类。[isRetryable] 是重试链的唯二判据之一（另一个是「还有剩余次数」）。
     *
     * 空内容与坏 JSON 不重试：它们是模型输出/协议解析问题，重试大概率得到同样的结果，
     * 只会把一次失败放大成 N 次计费（D290）。
     */
    enum class Kind(val isRetryable: Boolean) {
        NETWORK(isRetryable = true),
        TIMEOUT(isRetryable = true),
        RATE_LIMIT(isRetryable = true),
        SERVER(isRetryable = true),
        AUTH(isRetryable = false),
        BAD_REQUEST(isRetryable = false),
        EMPTY_CONTENT(isRetryable = false),
        INVALID_JSON(isRetryable = false),
    }
}
