package com.dreamkeeper.llm

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DeepSeek Chat Completions 客户端（Task 5）。
 *
 * 只做一件事：一次 `chat()` = 带退避重试的一次 Chat Completions 调用，并把每一次 HTTP
 * 交换记进 [ChatResponse.attempts] / [LlmException.attempts]，供 Task 8 落 `llm_calls` 表。
 *
 * 几条刻意的边界（brief §0.4 / §0.7 / D290）：
 * - **取消透传**：`CancellationException` 原样抛出，不包成 [LlmException]；同时通过
 *   `invokeOnCancellation { call.cancel() }` 真的取消飞行中的 OkHttp `Call`，否则 socket
 *   会一直挂到服务端写完，用户离开页面后还在耗流量和 token。
 * - **超时按实例派生**：`callTimeout` / `readTimeout` 用 `client.newBuilder()` 派生，
 *   绝不改注入的共享 client，避免一个调用方的超时影响另一个。
 * - **空内容 / 坏 JSON 不重试**（D290）：它们是模型输出问题，重试只会放大计费。
 * - 日志里的 Key 只打后四位。
 *
 * @param client 共享的 OkHttp 客户端（测试注入带 [okhttp3.EventListener] 的实例来数取消）
 * @param baseUrl 允许带或不带结尾斜杠；路径固定追加 `chat/completions`
 * @param callTimeoutMs 整个调用（含重试）的超时上限，派生到本实例的 OkHttp client 上
 * @param readTimeoutMs 单次读超时
 * @param sleeper 退避等待；测试注入记账实现，生产默认 `delay`
 * @param clock 单调时钟（毫秒）；用于 attempt 起点与 latency 记账
 * @param jitter 退避抖动，返回非负毫秒；默认 0..250，测试注入 0
 * @param retry 重试次数与退避参数
 */
class DeepSeekClient(
    client: OkHttpClient = OkHttpClient(),
    baseUrl: String = DEFAULT_BASE_URL,
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Long = { ThreadLocalRandom.current().nextLong(0L, JITTER_BOUND_MS) },
    private val retry: RetryPolicy = RetryPolicy(),
) : LlmClient {

    private val http: OkHttpClient = client.newBuilder()
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val endpoint: String = baseUrl.trimEnd('/') + CHAT_PATH

    override suspend fun chat(req: ChatRequest): ChatResponse {
        val attempts = ArrayList<Attempt>(retry.maxAttempts)
        var firstStartedAtMs = 0L

        while (true) {
            val attemptNo = attempts.size + 1
            val startedAtMs = clock()
            if (attemptNo == 1) firstStartedAtMs = startedAtMs
            Log.d(
                TAG,
                "chat attempt=$attemptNo model=${req.model} thinking=${req.thinking.wire} " +
                    "jsonMode=${req.jsonMode} key=${maskKey(req.apiKey)}",
            )

            val raw = try {
                execute(buildRequest(req))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (io: IOException) {
                // 取消会以 IOException("Canceled") 从 OkHttp 冒出来；协程已被取消时必须让位给
                // CancellationException，否则上层会把「用户离页」记成一次网络失败。
                currentCoroutineContext().ensureActive()
                val kind = transportKind(io)
                attempts += Attempt(attemptNo, null, elapsedMs(startedAtMs), kind, null, startedAtMs)
                if (!kind.isRetryable || attempts.size >= retry.maxAttempts) {
                    throw LlmException(kind, null, null, attempts.toList(), io)
                }
                sleeper(nextDelayMs(attempts.size - 1, retryAfterMs = null))
                continue
            }

            val latencyMs = elapsedMs(startedAtMs)
            val statusKind = kindForStatus(raw.status)
            attempts += Attempt(attemptNo, raw.status, latencyMs, statusKind, raw.requestId, startedAtMs)

            if (raw.status in 200..299) {
                return parseSuccess(req, raw, attempts.toList(), elapsedMs(firstStartedAtMs))
            }

            val kind = statusKind ?: LlmException.Kind.SERVER
            if (!kind.isRetryable || attempts.size >= retry.maxAttempts) {
                throw LlmException(kind, raw.status, raw.requestId, attempts.toList())
            }
            sleeper(nextDelayMs(attempts.size - 1, raw.retryAfterMs))
        }
    }

    // ---------------------------------------------------------------- HTTP

    private suspend fun execute(request: Request): RawResponse =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            // 只取消协程不会关 socket；必须把取消落到 OkHttp 的 Call 上。
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // 已取消时不再 resume：取消语义由 CancellationException 负责。
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val raw = try {
                        response.use { resp ->
                            RawResponse(
                                status = resp.code,
                                body = resp.body?.string().orEmpty(),
                                requestId = resp.header(HEADER_DS_REQUEST_ID)
                                    ?: resp.header(HEADER_REQUEST_ID),
                                retryAfterMs = parseRetryAfterMs(resp.header(HEADER_RETRY_AFTER)),
                            )
                        }
                    } catch (io: IOException) {
                        // 读 body 时被取消/超时；continuation 已取消时不再 resume。
                        if (continuation.isActive) continuation.resumeWithException(io)
                        return
                    }
                    if (continuation.isActive) continuation.resume(raw)
                }
            })
        }

    private fun buildRequest(req: ChatRequest): Request {
        val body = JSONObject()
            .put("model", req.model)
            .put(
                "messages",
                JSONArray().also { array ->
                    req.messages.forEach { message ->
                        array.put(JSONObject().put("role", message.role).put("content", message.content))
                    }
                },
            )
            .put("thinking", JSONObject().put("type", req.thinking.wire))
            .put("stream", false)

        if (req.thinking == Thinking.ENABLED) {
            // 思考模式下 temperature 会被服务端忽略；发出去只会让请求体难以断言。
            req.effort?.let { body.put("reasoning_effort", it.wire) }
        } else {
            req.temperature?.let { body.put("temperature", it) }
        }
        if (req.jsonMode) {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }

        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${req.apiKey}")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    // ---------------------------------------------------------------- 解析

    private fun parseSuccess(
        req: ChatRequest,
        raw: RawResponse,
        attempts: List<Attempt>,
        latencyMs: Long,
    ): ChatResponse {
        val root = try {
            JSONObject(raw.body)
        } catch (e: JSONException) {
            throw LlmException(
                LlmException.Kind.INVALID_JSON,
                raw.status,
                raw.requestId,
                attempts.markLastErrorKind(LlmException.Kind.INVALID_JSON),
                e,
            )
        }

        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val content = message?.nullableString("content").orEmpty()

        if (content.isBlank()) {
            throw LlmException(
                LlmException.Kind.EMPTY_CONTENT,
                raw.status,
                raw.requestId,
                attempts.markLastErrorKind(LlmException.Kind.EMPTY_CONTENT),
            )
        }
        if (req.jsonMode && !looksLikeJsonObject(content)) {
            throw LlmException(
                LlmException.Kind.INVALID_JSON,
                raw.status,
                raw.requestId,
                attempts.markLastErrorKind(LlmException.Kind.INVALID_JSON),
            )
        }

        return ChatResponse(
            content = content,
            reasoning = message?.nullableString("reasoning_content"),
            usage = parseUsage(root.optJSONObject("usage")),
            requestId = raw.requestId,
            httpStatus = raw.status,
            latencyMs = latencyMs,
            finishReason = choice?.nullableString("finish_reason"),
            attempts = attempts,
        )
    }

    private fun parseUsage(usage: JSONObject?): Usage? {
        if (usage == null) return null
        return Usage(
            promptCacheHitTokens = usage.optLong("prompt_cache_hit_tokens", 0L),
            promptCacheMissTokens = usage.optLong("prompt_cache_miss_tokens", 0L),
            completionTokens = usage.optLong("completion_tokens", 0L),
            reasoningTokens = usage.optJSONObject("completion_tokens_details")
                ?.optLong("reasoning_tokens", 0L) ?: 0L,
            totalTokens = usage.optLong("total_tokens", 0L),
        )
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun looksLikeJsonObject(text: String): Boolean = try {
        JSONObject(text)
        true
    } catch (e: JSONException) {
        false
    }

    // ---------------------------------------------------------------- 分类与退避

    private fun kindForStatus(status: Int): LlmException.Kind? = when {
        status in 200..299 -> null
        status == 401 || status == 403 -> LlmException.Kind.AUTH
        status == 429 -> LlmException.Kind.RATE_LIMIT
        status in 500..599 -> LlmException.Kind.SERVER
        status in 400..499 -> LlmException.Kind.BAD_REQUEST
        else -> LlmException.Kind.SERVER
    }

    private fun transportKind(io: IOException): LlmException.Kind =
        if (io is InterruptedIOException) LlmException.Kind.TIMEOUT else LlmException.Kind.NETWORK

    /**
     * 第 [retryIndex] 次重试前要等的毫秒数。
     *
     * `Retry-After` 优先于指数退避，但要夹到 [RetryPolicy.retryAfterMaxMs]：header 来自服务端，
     * 一个坏值不该把调用挂住几分钟。指数退避是 `base * factor^n`，单次不超过
     * [RetryPolicy.maxDelayMs]；抖动只加不减。
     */
    private fun nextDelayMs(retryIndex: Int, retryAfterMs: Long?): Long {
        if (retryAfterMs != null) {
            return retryAfterMs.coerceIn(0L, retry.retryAfterMaxMs)
        }
        var delayMs = retry.baseDelayMs.coerceIn(0L, retry.maxDelayMs)
        repeat(retryIndex) {
            delayMs = if (delayMs > retry.maxDelayMs / retry.factor) {
                retry.maxDelayMs
            } else {
                delayMs * retry.factor
            }
        }
        return (delayMs + jitter().coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    private fun parseRetryAfterMs(header: String?): Long? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { seconds ->
            if (seconds <= 0L) return 0L
            return if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
        }
        return try {
            val instant = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (instant.toEpochMilli() - clock()).coerceAtLeast(0L)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun elapsedMs(startedAtMs: Long): Long = (clock() - startedAtMs).coerceAtLeast(0L)

    /** 日志只留后四位；Key 短到不足以掩码时整段隐藏。 */
    private fun maskKey(key: String): String = if (key.length <= KEY_SUFFIX) "…" else "…${key.takeLast(KEY_SUFFIX)}"

    private fun List<Attempt>.markLastErrorKind(kind: LlmException.Kind): List<Attempt> =
        if (isEmpty()) this else dropLast(1) + last().copy(errorKind = kind)

    /** 一次 HTTP 交换的原始结果；分类与记账都由 [chat] 统一完成。 */
    private data class RawResponse(
        val status: Int,
        val body: String,
        val requestId: String?,
        val retryAfterMs: Long?,
    )

    companion object {
        /** 生产默认 baseUrl；请求路径固定追加 `chat/completions`。 */
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/"

        /** 单次调用（含重试）的总超时上限。 */
        const val DEFAULT_CALL_TIMEOUT_MS = 300_000L

        /** 单次读超时；DeepSeek 长思考可能很慢，但不该慢到 3 分钟。 */
        const val DEFAULT_READ_TIMEOUT_MS = 180_000L

        private const val CHAT_PATH = "/chat/completions"
        private const val HEADER_DS_REQUEST_ID = "x-ds-request-id"
        private const val HEADER_REQUEST_ID = "x-request-id"
        private const val HEADER_RETRY_AFTER = "Retry-After"
        private const val KEY_SUFFIX = 4
        private const val TAG = "DeepSeekClient"
        private const val JITTER_BOUND_MS = 251L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
