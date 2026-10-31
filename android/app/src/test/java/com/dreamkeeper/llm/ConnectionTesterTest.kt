package com.dreamkeeper.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan 6 T3：测试连接的单次、单飞、错误分类与「空 Key 0 HTTP」。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionTesterTest {

    private var server: HttpServer? = null
    private val requests = AtomicInteger(0)

    @After
    fun tearDown() {
        server?.stop(0)
    }

    private fun serve(status: Int, body: String, delayMs: Long = 0L): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.executor = java.util.concurrent.Executors.newCachedThreadPool()
        s.createContext("/") { exchange: HttpExchange ->
            requests.incrementAndGet()
            if (delayMs > 0) Thread.sleep(delayMs)
            val bytes = body.toByteArray()
            runCatching {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun tester(
        provider: ProviderId = ProviderId.QWEN,
        key: String? = "sk-test-0000",
        base: String,
        readTimeoutMs: Long = 5_000,
    ): ConnectionTester {
        val client = ProviderChatClient(
            spec = ProviderRegistry.of(provider),
            baseUrlOverride = base,
            callTimeoutMs = 10_000,
            readTimeoutMs = readTimeoutMs,
            connectTimeoutMs = 2_000,
            retry = RetryPolicy(maxAttempts = 1),
            sleeper = {},
            jitter = { 0L },
        )
        return ConnectionTester(
            clientFor = { client },
            keyOf = { key },
            modelOf = { ProviderRegistry.of(provider).defaultModel().id },
        )
    }

    @Test
    fun `success reports latency http status and the default model`() = runBlocking {
        val base = serve(200, """{"choices":[{"message":{"content":"OK"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""")
        val outcome = tester(base = base).test(ProviderId.QWEN, nonce = 42)

        val ok = outcome as ConnectionOutcome.Ok
        assertEquals(42, ok.nonce)
        assertEquals(ProviderId.QWEN, ok.providerId)
        assertEquals(200, ok.httpStatus)
        assertEquals("qwen3.8-max", ok.model)
        assertTrue(ok.latencyMs >= 0)
        assertEquals(1, requests.get())
    }

    @Test
    fun `missing key makes zero http requests`() = runBlocking {
        val base = serve(200, """{"choices":[{"message":{"content":"OK"}}]}""")
        val outcome = tester(key = null, base = base).test(ProviderId.GLM, nonce = 7)

        val failed = outcome as ConnectionOutcome.Failed
        assertEquals(ConnectionOutcome.Reason.NO_KEY, failed.reason)
        assertEquals(7, failed.nonce)
        assertEquals(0, requests.get())
    }

    @Test
    fun `401 is auth and sent once`() = runBlocking {
        val base = serve(401, """{"error":{"message":"bad key"}}""")
        val outcome = tester(base = base).test(ProviderId.MIMO) as ConnectionOutcome.Failed
        assertEquals(ConnectionOutcome.Reason.AUTH, outcome.reason)
        assertEquals(1, requests.get())
    }

    @Test
    fun `429 is rate limited and never retried`() = runBlocking {
        val base = serve(429, """{"error":{"message":"slow down"}}""")
        val outcome = tester(base = base).test(ProviderId.GLM) as ConnectionOutcome.Failed
        assertEquals(ConnectionOutcome.Reason.RATE_LIMITED, outcome.reason)
        assertEquals(1, requests.get())
    }

    @Test
    fun `read timeout is reported as timeout`() = runBlocking {
        val base = serve(200, """{"choices":[{"message":{"content":"OK"}}]}""", delayMs = 1_500)
        val outcome = tester(base = base, readTimeoutMs = 200).test(ProviderId.MINIMAX) as ConnectionOutcome.Failed
        assertEquals(ConnectionOutcome.Reason.TIMEOUT, outcome.reason)
    }

    @Test
    fun `second tap while in flight makes no second request`() = runBlocking {
        val base = serve(200, """{"choices":[{"message":{"content":"OK"}}]}""", delayMs = 600)
        val t = tester(base = base)

        val first = async(Dispatchers.IO) { t.test(ProviderId.QWEN, nonce = 1) }
        delay(150)
        val second = t.test(ProviderId.QWEN, nonce = 2)

        assertEquals(ConnectionOutcome.Reason.UNKNOWN, (second as ConnectionOutcome.Failed).reason)
        assertEquals(2, second.nonce)
        assertTrue(first.await() is ConnectionOutcome.Ok)
        assertEquals("单飞：只发一次", 1, requests.get())
    }
}
