package com.dreamkeeper.llm

import android.util.Log
import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
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
import org.json.JSONObject

/**
 * OpenAI 兼容方言的通用客户端（Plan 6 T2）。
 *
 * 只服务非 DeepSeek 的四家：DeepSeek 继续走 [DeepSeekClient]，用 `p6-contract-01` 的
 * 4 组请求体基线钉住「不回归」。两家代码刻意不合并——DeepSeek 的请求体是历史契约，
 * 合并重构的回归风险高于这点重复。
 *
 * 差异全部收敛在三个纯函数里：[buildBody]（能力位决定字段）、[parseUsage]（三种形状）、
 * [errorKind]（含 MiniMax 的 HTTP 200 + `base_resp.status_code != 0` 假成功）。
 */
class ProviderChatClient(
    private val spec: ProviderSpec,
    private val client: OkHttpClient = OkHttpClient(),
    callTimeoutMs: Long = 300_000,
    readTimeoutMs: Long = 180_000,
    connectTimeoutMs: Long = 10_000,
    private val baseUrlOverride: String? = null,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Long = { ThreadLocalRandom.current().nextLong(0L, JITTER_BOUND_MS) },
    private val retry: RetryPolicy = RetryPolicy(),
) : LlmClient {

    private val http: OkHttpClient = client.newBuilder()
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val endpoint: String =
        (baseUrlOverride ?: spec.baseUrl).trimEnd('/') + spec.chatPath

    override suspend fun chat(req: ChatRequest): ChatResponse {
        val attempts = ArrayList<Attempt>(retry.maxAttempts)
        var firstStartedAtMs = 0L

        while (true) {
            val attemptNo = attempts.size + 1
            val startedAtMs = clock()
            if (attemptNo == 1) firstStartedAtMs = startedAtMs
            Log.d(
                TAG,
                "chat provider=${spec.provider.wire} attempt=$attemptNo model=${req.model} " +
                    "thinking=${req.thinking.wire} jsonMode=${req.jsonMode} key=${maskKey(req.apiKey)}",
            )

            val raw = try {
                execute(buildRequest(req))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (io: IOException) {
                currentCoroutineContext().ensureActive()
                val kind = transportKind(io)
                attempts += Attempt(attemptNo, null, elapsedMs(startedAtMs), kind, null, startedAtMs)
                logAttempt(req, attemptNo, ok = false, httpStatus = null)
                if (!kind.isRetryable || attempts.size >= retry.maxAttempts) {
                    throw LlmException(kind, null, null, attempts.toList(), io)
                }
                sleeper(nextDelayMs(attempts.size - 1, retryAfterMs = null))
                continue
            }

            val latencyMs = elapsedMs(startedAtMs)
            val statusKind = errorKind(raw.status, raw.body)
            attempts += Attempt(attemptNo, raw.status, latencyMs, statusKind, raw.requestId, startedAtMs)

            if (raw.status in 200..299) {
                val response = try {
                    parseSuccess(req, raw, attempts.toList(), elapsedMs(firstStartedAtMs))
                } catch (e: LlmException) {
                    logAttempt(req, attemptNo, ok = false, httpStatus = raw.status)
                    throw e
                }
                logAttempt(req, attemptNo, ok = true, httpStatus = raw.status)
                return response
            }

            logAttempt(req, attemptNo, ok = false, httpStatus = raw.status)
            val kind = statusKind ?: LlmException.Kind.SERVER
            if (!kind.isRetryable || attempts.size >= retry.maxAttempts) {
                throw LlmException(kind, raw.status, raw.requestId, attempts.toList())
            }
            sleeper(nextDelayMs(attempts.size - 1, raw.retryAfterMs))
        }
    }

    /** 能力位 → 请求体。字段集合由 [ProviderSpec] 与 [ModelSpec] 决定，调用方只给意图。 */
    internal fun buildBody(req: ChatRequest): JSONObject {
        val model = spec.model(req.model) ?: spec.defaultModel()
        val body = JSONObject()
        body.put("model", model.id)
        body.put("messages", JSONArray().apply {
            req.messages.forEach { m -> put(JSONObject().put("role", m.role).put("content", m.content)) }
        })
        body.put("stream", false)

        when (spec.thinkingStyle) {
            ThinkingStyle.THINKING_OBJECT -> body.put(
                "thinking",
                JSONObject().put("type", if (req.thinking == Thinking.ENABLED) "enabled" else "disabled"),
            )
            ThinkingStyle.MINIMAX_THINKING -> body.put(
                "thinking",
                JSONObject().put("type", if (req.thinking == Thinking.ENABLED) "adaptive" else "disabled"),
            )
            ThinkingStyle.QWEN_ENABLE -> body.put("enable_thinking", req.thinking == Thinking.ENABLED)
        }

        if (req.thinking == Thinking.ENABLED && req.effort != null && model.supportsEffort) {
            body.put("reasoning_effort", spec.effortWire[req.effort] ?: req.effort.wire)
        }
        if (req.temperature != null) body.put("temperature", req.temperature)
        if (req.jsonMode && spec.supportsResponseFormat && model.supportsJsonMode) {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }
        if (req.maxTokens != null) body.put("max_tokens", req.maxTokens)
        return body
    }

    private fun buildRequest(req: ChatRequest): Request = Request.Builder()
        .url(endpoint)
        .header("Authorization", "Bearer ${req.apiKey}")
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .post(buildBody(req).toString().toRequestBody(JSON))
        .build()

    private suspend fun execute(request: Request): Raw = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val text = r.body?.string().orEmpty()
                    if (cont.isActive) {
                        cont.resumeWith(
                            Result.success(
                                Raw(
                                    status = r.code,
                                    body = text,
                                    requestId = r.header("x-request-id") ?: r.header("request-id"),
                                    retryAfterMs = parseRetryAfterMs(r.header("Retry-After")),
                                ),
                            ),
                        )
                    }
                }
            }
        })
    }

    private fun parseSuccess(
        req: ChatRequest,
        raw: Raw,
        attempts: List<Attempt>,
        latencyMs: Long,
    ): ChatResponse {
        val root = try {
            JSONObject(raw.body)
        } catch (_: Exception) {
            throw LlmException(
                LlmException.Kind.INVALID_JSON, raw.status, raw.requestId,
                attempts.markLastErrorKind(LlmException.Kind.INVALID_JSON),
            )
        }
        if (spec.provider == ProviderId.MINIMAX) {
            val base = root.optJSONObject("base_resp")
            val code = base?.optInt("status_code", 0) ?: 0
            if (code != 0) {
                Log.w(TAG, "minimax base_resp status_code=$code")
                throw LlmException(
                    LlmException.Kind.BAD_REQUEST, raw.status, raw.requestId,
                    attempts.markLastErrorKind(LlmException.Kind.BAD_REQUEST),
                )
            }
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val content = message?.optString("content").orEmpty()
        if (content.isBlank()) {
            throw LlmException(
                LlmException.Kind.EMPTY_CONTENT, raw.status, raw.requestId,
                attempts.markLastErrorKind(LlmException.Kind.EMPTY_CONTENT),
            )
        }
        if (req.jsonMode && !looksLikeJsonObject(content)) {
            throw LlmException(
                LlmException.Kind.INVALID_JSON, raw.status, raw.requestId,
                attempts.markLastErrorKind(LlmException.Kind.INVALID_JSON),
            )
        }
        return ChatResponse(
            content = content,
            reasoning = message?.optString("reasoning_content")?.takeIf { it.isNotBlank() }
                ?: message?.optString("reasoning")?.takeIf { it.isNotBlank() },
            usage = parseUsage(root.optJSONObject("usage")),
            requestId = raw.requestId,
            httpStatus = raw.status,
            latencyMs = latencyMs,
            finishReason = choice?.optString("finish_reason")?.takeIf { it.isNotBlank() },
            attempts = attempts,
        )
    }

    /** 三种 shapes：DeepSeek cache_hit/miss、OpenAI `prompt_tokens_details.cached_tokens`、其余缺字段。 */
    internal fun parseUsage(usage: JSONObject?): Usage? {
        if (usage == null) return null
        val prompt = usage.optLong("prompt_tokens", 0L)
        val completion = usage.optLong("completion_tokens", 0L)
        val total = usage.optLong("total_tokens", 0L)
        val hit = usage.optLong("prompt_cache_hit_tokens", 0L).takeIf { it > 0 }
            ?: usage.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens", 0L)?.takeIf { it > 0 }
            ?: 0L
        val miss = usage.optLong("prompt_cache_miss_tokens", 0L).takeIf { it > 0 }
            ?: (prompt - hit).coerceAtLeast(0L)
        val reasoning = usage.optJSONObject("completion_tokens_details")
            ?.optLong("reasoning_tokens", 0L)?.takeIf { it > 0 }
            ?: usage.optLong("reasoning_tokens", 0L)
        return Usage(
            promptCacheHitTokens = hit,
            promptCacheMissTokens = miss,
            completionTokens = completion,
            reasoningTokens = reasoning,
            totalTokens = if (total > 0) total else prompt + completion,
        )
    }

    /** MiniMax 的 HTTP 200 假成功必须在状态判定阶段就变成错误，不能等解析。 */
    internal fun errorKind(status: Int, body: String): LlmException.Kind? = when {
        status in 200..299 ->
            if (spec.provider == ProviderId.MINIMAX && hasMiniMaxError(body)) {
                LlmException.Kind.BAD_REQUEST
            } else {
                null
            }
        status == 401 || status == 403 -> LlmException.Kind.AUTH
        status == 429 -> LlmException.Kind.RATE_LIMIT
        status in 500..599 -> LlmException.Kind.SERVER
        status in 400..499 -> LlmException.Kind.BAD_REQUEST
        else -> LlmException.Kind.SERVER
    }

    private fun hasMiniMaxError(body: String): Boolean = try {
        val base = JSONObject(body).optJSONObject("base_resp")
        base != null && base.optInt("status_code", 0) != 0
    } catch (_: Exception) {
        false
    }

    private fun logAttempt(req: ChatRequest, attemptNo: Int, ok: Boolean, httpStatus: Int?) {
        Log.d(
            TAG,
            "DKLLM attempt n=$attemptNo kind=${req.kind} " +
                "status=${if (ok) "ok" else "failed"} http=${httpStatus ?: "none"} " +
                "provider=${spec.provider.wire}",
        )
    }

    private fun nextDelayMs(retryIndex: Int, retryAfterMs: Long?): Long {
        val base = (retry.baseDelayMs * Math.pow(retry.factor.toDouble(), retryIndex.toDouble())).toLong()
            .coerceAtMost(retry.maxDelayMs)
        val cap = retryAfterMs?.coerceAtMost(retry.retryAfterMaxMs)
        val delayMs = maxOf(base, cap ?: 0L)
        return (delayMs + jitter().coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    private fun parseRetryAfterMs(header: String?): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { return (it * 1000L).coerceAtLeast(0L) }
        return runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                .minus(clock()).coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun transportKind(io: IOException): LlmException.Kind = when (io) {
        is InterruptedIOException -> LlmException.Kind.TIMEOUT
        else -> LlmException.Kind.NETWORK
    }

    private fun elapsedMs(startedAtMs: Long): Long = (clock() - startedAtMs).coerceAtLeast(0L)

    private fun maskKey(key: String): String =
        if (key.length <= KEY_SUFFIX) "…" else "…${key.takeLast(KEY_SUFFIX)}"

    private fun looksLikeJsonObject(text: String): Boolean = try {
        JSONObject(text)
        true
    } catch (_: Exception) {
        false
    }

    private fun List<Attempt>.markLastErrorKind(kind: LlmException.Kind): List<Attempt> =
        if (isEmpty()) this else dropLast(1) + last().copy(errorKind = kind)

    private data class Raw(
        val status: Int,
        val body: String,
        val requestId: String?,
        val retryAfterMs: Long?,
    )

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val TAG = "DKLLM"
        const val JITTER_BOUND_MS = 250L
        const val KEY_SUFFIX = 4
    }
}
