package com.dreamkeeper.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * DeepSeekClient 的 JVM 单测（Task 5 验收，严格先红后绿）。
 *
 * 所有用例都指向本机 [HttpServer]，绝不访问 api.deepseek.com；退避由注入的 sleeper 记账，
 * 不发生真实 sleep（唯一的真实等待是「取消必须立刻返回」的 200ms 采样窗口和服务端故意慢响应）。
 *
 * 取消断言依赖注入 [OkHttpClient] 的 [EventListener.canceled]：`OkHttpClient.newCall()` 是
 * internal，测试拿不到客户端内部的 `Call`，因此不能用 `call.isCanceled()`；监听器回调是
 * OkHttp 自己执行 `call.cancel()` 时同步触发的，是「真的取消了那个飞行中的 Call」的直接证据
 * （D291/D287）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepSeekClientTest {

    private val servers = mutableListOf<Pair<HttpServer, ExecutorService>>()
    private val canceledCalls = AtomicInteger()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .eventListener(object : EventListener() {
            override fun canceled(call: Call) {
                canceledCalls.incrementAndGet()
            }
        })
        .build()

    @Before
    fun resetCanceledCounter() {
        canceledCalls.set(0)
    }

    @After
    fun stopServers() {
        servers.forEach { (server, pool) ->
            server.stop(0)
            pool.shutdownNow()
        }
        servers.clear()
    }

    // ---------------------------------------------------------------- 用例 1

    @Test
    fun `200 正常解析 usage requestId attempts 且请求体逐字段匹配`() = runBlocking {
        val server = startFakeServer { index, exchange ->
            if (index == 1) {
                respond(exchange, 200, okBody(), mapOf("x-ds-request-id" to "req-200-abc"))
            } else {
                respond(
                    exchange,
                    200,
                    okBody(content = """{"dream":"鲸鱼在屋顶"}"""),
                    mapOf("x-ds-request-id" to "req-200-json"),
                )
            }
        }
        val client = newClient(server.url)
        val before = System.currentTimeMillis()

        val plain = client.chat(request(thinking = Thinking.DISABLED, temperature = 0.3))
        val after = System.currentTimeMillis()

        assertEquals("一只鲸鱼从屋顶游过。", plain.content)
        assertEquals("先想一个画面。", plain.reasoning)
        assertEquals(Usage(60, 40, 40, 12, 140), plain.usage)
        assertEquals("req-200-abc", plain.requestId)
        assertEquals(200, plain.httpStatus)
        assertEquals("stop", plain.finishReason)
        assertEquals(1, plain.attempts.size)
        assertEquals(1, plain.attempts[0].attemptNo)
        assertEquals(200, plain.attempts[0].httpStatus)
        assertNull(plain.attempts[0].errorKind)
        assertEquals("req-200-abc", plain.attempts[0].requestId)
        assertTrue(
            "startedAtMs 应落在调用窗口内：${plain.attempts[0].startedAtMs} ∉ [$before, $after]",
            plain.attempts[0].startedAtMs in before..after,
        )
        assertTrue("latencyMs 不应为负", plain.latencyMs >= 0)

        // ---- 请求体逐字段：thinking=disabled，temperature 允许发送
        val first = JSONObject(server.recorded[0].body)
        assertEquals("模型", "deepseek-flash", first.getString("model"))
        assertEquals(
            "disabled 请求体的字段集合",
            setOf("model", "messages", "thinking", "stream", "temperature"),
            first.keys().asSequence().toSet(),
        )
        val messages = first.getJSONArray("messages")
        assertEquals("消息条数", 2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("你是梦境记录助手。", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("记录：屋顶有鲸鱼。", messages.getJSONObject(1).getString("content"))
        assertEquals("disabled", first.getJSONObject("thinking").getString("type"))
        assertEquals(0.3, first.getDouble("temperature"), 0.0)
        assertFalse("stream 必须是 false", first.getBoolean("stream"))
        assertFalse("disabled 不该带 reasoning_effort", first.has("reasoning_effort"))
        assertFalse("非 jsonMode 不该带 response_format", first.has("response_format"))
        assertEquals(
            "Authorization 头",
            "Bearer sk-test-deepseek-1234",
            server.recorded[0].authorization,
        )

        // ---- 请求体逐字段：thinking=enabled，必须带 reasoning_effort 且不发 temperature
        val json = client.chat(
            request(thinking = Thinking.ENABLED, temperature = 0.5, jsonMode = true),
        )
        assertEquals("""{"dream":"鲸鱼在屋顶"}""", json.content)
        assertEquals("req-200-json", json.requestId)

        val second = JSONObject(server.recorded[1].body)
        assertEquals(
            "enabled 请求体的字段集合（不得出现 temperature）",
            setOf("model", "messages", "thinking", "reasoning_effort", "response_format", "stream"),
            second.keys().asSequence().toSet(),
        )
        assertEquals("enabled", second.getJSONObject("thinking").getString("type"))
        assertEquals("high", second.getString("reasoning_effort"))
        assertEquals("json_object", second.getJSONObject("response_format").getString("type"))
        assertFalse("enabled 不得带 temperature", second.has("temperature"))
        assertFalse("stream 必须是 false", second.getBoolean("stream"))
        assertEquals("enabled 请求同样带 system+user 两条消息", 2, second.getJSONArray("messages").length())
    }

    // ---------------------------------------------------------------- 用例 2

    @Test
    fun `空 content 判 EMPTY_CONTENT 且不重试`() = runBlocking {
        val server = startFakeServer { _, exchange -> respond(exchange, 200, okBody(content = "   ")) }
        val client = newClient(server.url)

        val failure = try {
            client.chat(request())
            null
        } catch (e: LlmException) {
            e
        }
        val error = failure ?: error("空 content 应当抛 LlmException")

        assertEquals(LlmException.Kind.EMPTY_CONTENT, error.kind)
        assertFalse("EMPTY_CONTENT 不进重试链", error.kind.isRetryable)
        assertEquals(200, error.httpStatus)
        assertEquals("只发一次请求", 1, server.requestCount.get())
        assertEquals(1, error.attempts.size)
        assertEquals(LlmException.Kind.EMPTY_CONTENT, error.attempts.single().errorKind)
    }

    // ---------------------------------------------------------------- 用例 3

    @Test
    fun `jsonMode 下非 JSON 判 INVALID_JSON 且请求带 response_format`() = runBlocking {
        val server = startFakeServer { _, exchange ->
            respond(exchange, 200, okBody(content = "这不是 JSON"))
        }
        val client = newClient(server.url)

        val failure = try {
            client.chat(request(jsonMode = true))
            null
        } catch (e: LlmException) {
            e
        }
        val error = failure ?: error("jsonMode 下非 JSON 应当抛 LlmException")

        assertEquals(LlmException.Kind.INVALID_JSON, error.kind)
        assertFalse("INVALID_JSON 不进重试链", error.kind.isRetryable)
        assertEquals(200, error.httpStatus)
        assertEquals("只发一次请求", 1, server.requestCount.get())
        assertEquals(1, error.attempts.size)
        assertEquals(LlmException.Kind.INVALID_JSON, error.attempts.single().errorKind)

        val body = JSONObject(server.recorded.single().body)
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
    }

    // ---------------------------------------------------------------- 用例 4

    @Test
    fun `429 三次后成功且 sleeper 收到 1000 2000 4000`() = runBlocking {
        val slept = mutableListOf<Long>()
        val server = startFakeServer { index, exchange ->
            if (index <= 3) {
                respond(
                    exchange,
                    429,
                    errorBody("rate limited"),
                    mapOf("x-ds-request-id" to "req-429-$index"),
                )
            } else {
                respond(exchange, 200, okBody(), mapOf("x-ds-request-id" to "req-ok"))
            }
        }
        val client = newClient(server.url, sleeper = { ms -> slept += ms })

        val response = client.chat(request())

        assertEquals("退避序列（jitter=0）", listOf(1_000L, 2_000L, 4_000L), slept)
        assertEquals("服务端共收到 4 次请求", 4, server.requestCount.get())
        assertEquals("req-ok", response.requestId)
        assertEquals(200, response.httpStatus)
        assertEquals(4, response.attempts.size)
        assertEquals(listOf(1, 2, 3, 4), response.attempts.map { it.attemptNo })
        assertEquals(listOf(429, 429, 429, 200), response.attempts.map { it.httpStatus })
        assertEquals(
            listOf(
                LlmException.Kind.RATE_LIMIT,
                LlmException.Kind.RATE_LIMIT,
                LlmException.Kind.RATE_LIMIT,
                null,
            ),
            response.attempts.map { it.errorKind },
        )
        assertEquals(
            listOf("req-429-1", "req-429-2", "req-429-3", "req-ok"),
            response.attempts.map { it.requestId },
        )
        assertTrue("每次交换都要有耗时", response.attempts.all { it.latencyMs >= 0 })
    }

    // ---------------------------------------------------------------- 用例 5

    @Test
    fun `401 只发一次请求即判 AUTH`() = runBlocking {
        val server = startFakeServer { _, exchange ->
            respond(
                exchange,
                401,
                errorBody("invalid api key"),
                mapOf("x-ds-request-id" to "req-auth"),
            )
        }
        val client = newClient(server.url)

        val failure = try {
            client.chat(request())
            null
        } catch (e: LlmException) {
            e
        }
        val error = failure ?: error("401 应当抛 LlmException")

        assertEquals(LlmException.Kind.AUTH, error.kind)
        assertFalse("AUTH 不进重试链", error.kind.isRetryable)
        assertEquals(401, error.httpStatus)
        assertEquals("req-auth", error.requestId)
        assertEquals("服务端只应收到 1 次请求", 1, server.requestCount.get())
        assertEquals(1, error.attempts.size)
        assertEquals(LlmException.Kind.AUTH, error.attempts.single().errorKind)
    }

    // ---------------------------------------------------------------- 用例 6

    @Test
    fun `callTimeout 超时映射 TIMEOUT 且总耗时小于 2 秒`() = runBlocking {
        val server = startFakeServer { _, exchange ->
            Thread.sleep(3_000)
            runCatching { respond(exchange, 200, okBody()) }
        }
        // maxAttempts=1：本用例只验证单次 callTimeout 的分类与耗时；默认策略会重试 TIMEOUT，
        // 4 次 ×1s 会超过验收的 2s 上限，所以显式关掉重试。
        val client = newClient(
            server.url,
            callTimeoutMs = 1_000,
            readTimeoutMs = 5_000,
            retry = RetryPolicy(maxAttempts = 1),
        )

        val startedAt = System.currentTimeMillis()
        val failure = try {
            client.chat(request())
            null
        } catch (e: LlmException) {
            e
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        val error = failure ?: error("超时应当抛 LlmException")

        assertEquals(LlmException.Kind.TIMEOUT, error.kind)
        assertTrue("超时总耗时应 < 2000ms，实际 ${elapsedMs}ms", elapsedMs < 2_000)
        assertNull("传输层超时没有 HTTP 状态码", error.httpStatus)
        assertEquals(1, error.attempts.size)
        assertNull(error.attempts.single().httpStatus)
        assertEquals(LlmException.Kind.TIMEOUT, error.attempts.single().errorKind)
    }

    // ---------------------------------------------------------------- 用例 7

    @Test
    fun `飞行中取消抛出 CancellationException 且 canceled 回调命中`() = runBlocking {
        val server = startFakeServer { _, exchange ->
            Thread.sleep(3_000)
            runCatching { respond(exchange, 200, okBody()) }
        }
        val client = newClient(server.url, callTimeoutMs = 10_000, readTimeoutMs = 10_000)

        var thrown: Throwable? = null
        val job = launch {
            try {
                client.chat(request())
            } catch (t: Throwable) {
                thrown = t
            }
        }
        delay(300)

        val cancelStartedAt = System.nanoTime()
        job.cancel()
        job.join()
        val cancelElapsedMs = (System.nanoTime() - cancelStartedAt) / 1_000_000

        val failure = thrown ?: error("取消后 chat 必须以异常结束")
        assertTrue("取消必须透传 CancellationException，实际 $failure", failure is CancellationException)
        assertFalse("取消不许包成 LlmException", failure is LlmException)
        assertTrue(
            "飞行中的 Call 必须被 cancel（EventListener.canceled 命中），实际 ${canceledCalls.get()}",
            canceledCalls.get() >= 1,
        )
        assertTrue("取消应在一秒内返回，实际 ${cancelElapsedMs}ms", cancelElapsedMs < 1_000)
    }

    // ---------------------------------------------------------------- 用例 8

    @Test
    fun `退避中取消立即返回且 Retry-After 优先`() = runBlocking {
        val server = startFakeServer { index, exchange ->
            if (index == 1) {
                respond(
                    exchange,
                    429,
                    errorBody("slow down"),
                    mapOf("Retry-After" to "2"),
                )
            } else {
                respond(exchange, 200, okBody())
            }
        }
        val sleeperEntered = CompletableDeferred<Long>()
        val client = newClient(
            server.url,
            sleeper = { ms ->
                sleeperEntered.complete(ms)
                awaitCancellation()
            },
        )

        var thrown: Throwable? = null
        val job = launch {
            try {
                client.chat(request())
            } catch (t: Throwable) {
                thrown = t
            }
        }
        val firstDelayMs = withTimeout(2_000) { sleeperEntered.await() }
        assertEquals("Retry-After: 2 应优先于 1000ms 指数退避", 2_000L, firstDelayMs)

        val cancelStartedAt = System.nanoTime()
        job.cancel()
        job.join()
        val cancelElapsedMs = (System.nanoTime() - cancelStartedAt) / 1_000_000

        val failure = thrown ?: error("退避中取消后 chat 必须以异常结束")
        assertTrue("取消必须透传 CancellationException，实际 $failure", failure is CancellationException)
        assertTrue("退避中取消应立即返回，实际 ${cancelElapsedMs}ms", cancelElapsedMs < 200)
    }

    // ---------------------------------------------------------------- 脚手架

    private fun newClient(
        baseUrl: String,
        callTimeoutMs: Long = 10_000L,
        readTimeoutMs: Long = 5_000L,
        sleeper: suspend (Long) -> Unit = { },
        retry: RetryPolicy = RetryPolicy(),
    ): DeepSeekClient = DeepSeekClient(
        client = httpClient,
        baseUrl = baseUrl,
        callTimeoutMs = callTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        sleeper = sleeper,
        jitter = { 0L },
        retry = retry,
    )

    private fun request(
        jsonMode: Boolean = false,
        thinking: Thinking = Thinking.DISABLED,
        temperature: Double? = 0.3,
    ): ChatRequest = ChatRequest(
        model = "deepseek-flash",
        messages = listOf(
            ChatMessage("system", "你是梦境记录助手。"),
            ChatMessage("user", "记录：屋顶有鲸鱼。"),
        ),
        thinking = thinking,
        effort = if (thinking == Thinking.ENABLED) ReasoningEffort.HIGH else null,
        temperature = temperature,
        jsonMode = jsonMode,
        apiKey = "sk-test-deepseek-1234",
    )

    private fun startFakeServer(responder: (Int, HttpExchange) -> Unit): FakeServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "fake-deepseek-http").apply { isDaemon = true }
        }
        val fake = FakeServer("http://127.0.0.1:${server.address.port}/")
        server.executor = pool
        server.createContext("/chat/completions") { exchange ->
            val requestNo = fake.requestCount.incrementAndGet()
            val body = exchange.requestBody.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
            fake.recorded += RecordedRequest(body, exchange.requestHeaders.getFirst("Authorization"))
            // 取消/超时用例里客户端会提前挂断，写响应抛 broken pipe 属预期，别污染测试输出。
            runCatching { responder(requestNo, exchange) }
        }
        server.start()
        servers += server to pool
        return fake
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun okBody(
        content: String = "一只鲸鱼从屋顶游过。",
        reasoning: String = "先想一个画面。",
        finishReason: String = "stop",
    ): String = JSONObject()
        .put("id", "chatcmpl-test")
        .put("object", "chat.completion")
        .put("model", "deepseek-flash")
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put(
                        "message",
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", content)
                            .put("reasoning_content", reasoning),
                    )
                    .put("finish_reason", finishReason),
            ),
        )
        .put(
            "usage",
            JSONObject()
                .put("prompt_tokens", 100)
                .put("completion_tokens", 40)
                .put("total_tokens", 140)
                .put("prompt_cache_hit_tokens", 60)
                .put("prompt_cache_miss_tokens", 40)
                .put("completion_tokens_details", JSONObject().put("reasoning_tokens", 12)),
        )
        .toString()

    private fun errorBody(message: String): String =
        JSONObject().put("error", JSONObject().put("message", message)).toString()
}

/** 服务端收到的请求快照：请求体 + Authorization 头（只用于断言，不落日志）。 */
private data class RecordedRequest(val body: String, val authorization: String?)

/** 每个用例一个独立的本机 HttpServer，计数与请求记录都按实例隔离。 */
private class FakeServer(val url: String) {
    val requestCount = AtomicInteger()
    val recorded = CopyOnWriteArrayList<RecordedRequest>()
}
