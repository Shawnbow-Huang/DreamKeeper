package com.dreamkeeper.llm

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/**
 * 「测试连接」的一次结果。
 *
 * 带 [providerId] 与 [nonce]：[nonce] 由 UI 每次点击生成，回来时用它与当前选中家的 nonce 对比，
 * 防止 A 家的慢结果覆盖到 B 家的卡片上（切 provider / 重复点击都会产生新 nonce）。
 */
sealed interface ConnectionOutcome {
    val providerId: ProviderId
    val nonce: Long

    data class Ok(
        override val providerId: ProviderId,
        override val nonce: Long,
        val latencyMs: Long,
        val httpStatus: Int,
        val model: String,
    ) : ConnectionOutcome

    data class Failed(
        override val providerId: ProviderId,
        override val nonce: Long,
        val reason: Reason,
    ) : ConnectionOutcome

    /**
     * 失败分类。刻意与生成链的 `UserFacingReason` 保持同一套语义，
     * 但这里不依赖 ui 包——由 UI 负责翻成人话（T4）。
     */
    enum class Reason {
        NO_KEY,
        AUTH,
        RATE_LIMITED,
        TIMEOUT,
        NETWORK,
        BAD_REQUEST,
        SERVER,
        BAD_CONTENT,
        UNKNOWN,
    }
}

/**
 * 「测试连接」的唯一入口（Plan 6 §0.4 / T3）。
 *
 * 三条硬约束：
 * 1. **只测已保存的 Key**（调用方从 `ApiKeyStore.load(provider)` 取，空则直接 [ConnectionOutcome.Failed]，0 HTTP）；
 * 2. **单次请求**：`maxAttempts=1`，不做指数退避——「连接中」挂几十秒比失败还糟；
 * 3. **不写库、不进生成作用域**：本类不碰 `llm_calls`，也不持有任何生成状态。
 *
 * 单飞：同一个 provider 连续点两次，只发一次请求；第二个调用方拿到同一个结果（同一个 nonce 那次）。
 * 实现用 [AtomicBoolean] 而不是 Mutex：测试里可以断言「第二次 0 HTTP」，不需要挂起等待。
 */
class ConnectionTester(
    private val clientFor: (ProviderId) -> LlmClient,
    private val keyOf: (ProviderId) -> String?,
    private val modelOf: (ProviderId) -> String = { ProviderRegistry.of(it).defaultModel().id },
) {

    private val inFlight = AtomicBoolean(false)
    private val nonceSeq = AtomicLong(0L)

    fun nextNonce(): Long = nonceSeq.incrementAndGet()

    suspend fun test(provider: ProviderId, nonce: Long = nextNonce()): ConnectionOutcome {
        if (!inFlight.compareAndSet(false, true)) {
            Log.d(TAG, "test provider=${provider.wire}: 已有请求在飞，忽略重复点击")
            return ConnectionOutcome.Failed(provider, nonce, ConnectionOutcome.Reason.UNKNOWN)
        }
        return try {
            val key = keyOf(provider).orEmpty().trim()
            if (key.isEmpty()) {
                Log.d(TAG, "test provider=${provider.wire}: 未配置 Key，0 HTTP")
                ConnectionOutcome.Failed(provider, nonce, ConnectionOutcome.Reason.NO_KEY)
            } else {
                runOnce(provider, key, nonce)
            }
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun runOnce(
        provider: ProviderId,
        key: String,
        nonce: Long,
    ): ConnectionOutcome {
        val model = modelOf(provider)
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage("user", "只回复 OK")),
            thinking = Thinking.DISABLED,
            temperature = null,
            jsonMode = false,
            apiKey = key,
            kind = "TEST",
            provider = provider,
            maxTokens = 1,
        )
        return try {
            val response = clientFor(provider).chat(request)
            Log.d(TAG, "test provider=${provider.wire}: ok ${response.latencyMs}ms HTTP ${response.httpStatus}")
            ConnectionOutcome.Ok(
                providerId = provider,
                nonce = nonce,
                latencyMs = response.latencyMs,
                httpStatus = response.httpStatus,
                model = model,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: LlmException) {
            val reason = when (e.kind) {
                LlmException.Kind.AUTH -> ConnectionOutcome.Reason.AUTH
                LlmException.Kind.RATE_LIMIT -> ConnectionOutcome.Reason.RATE_LIMITED
                LlmException.Kind.TIMEOUT -> ConnectionOutcome.Reason.TIMEOUT
                LlmException.Kind.NETWORK -> ConnectionOutcome.Reason.NETWORK
                LlmException.Kind.SERVER -> ConnectionOutcome.Reason.SERVER
                LlmException.Kind.BAD_REQUEST -> ConnectionOutcome.Reason.BAD_REQUEST
                LlmException.Kind.EMPTY_CONTENT,
                LlmException.Kind.INVALID_JSON,
                -> ConnectionOutcome.Reason.BAD_CONTENT
            }
            Log.d(TAG, "test provider=${provider.wire}: failed ${reason.name} attempts=${e.attempts.size}")
            ConnectionOutcome.Failed(provider, nonce, reason)
        } catch (e: Exception) {
            Log.d(TAG, "test provider=${provider.wire}: failed UNKNOWN ${e.javaClass.simpleName}")
            ConnectionOutcome.Failed(provider, nonce, ConnectionOutcome.Reason.UNKNOWN)
        }
    }

    private companion object {
        const val TAG = "DKConn"
    }
}
