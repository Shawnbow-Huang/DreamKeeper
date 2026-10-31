package com.dreamkeeper.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan 6 T2：错误翻译。MiniMax 的 HTTP 200 假成功是这里最容易被漏掉的一类。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderErrorsTest {

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
    }

    private fun serve(status: Int, body: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange: HttpExchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun discoverClient(provider: ProviderId, baseUrl: String): ProviderChatClient =
        ProviderChatClient(
            spec = ProviderRegistry.of(provider),
            baseUrlOverride = baseUrl,
            retry = RetryPolicy(maxAttempts = 4, baseDelayMs = 1, factor = 1, maxDelayMs = 1),
            sleeper = {},
            jitter = { 0L },
        )

    private fun request() = ChatRequest(
        model = "MiniMax-M3",
        messages = listOf(ChatMessage("user", "hi")),
        apiKey = "sk-test-0000",
        jsonMode = true,
    )

    @Test
    fun `minimax http 200 with base_resp error is bad request and not retried`() = runBlocking {
        val base = serve(
            200,
            """{"base_resp":{"status_code":1004,"status_msg":"invalid api key"},
                "choices":[{"message":{"content":"{}"}}]}""",
        )
        val e = runCatching { discoverClient(ProviderId.MINIMAX, base).chat(request()) }.exceptionOrNull()
        assertNotNull(e)
        val llm = e as LlmException
        assertEquals(LlmException.Kind.BAD_REQUEST, llm.kind)
        assertEquals("假成功不能被重试", 1, llm.attempts.size)
    }

    @Test
    fun `401 is auth and is sent exactly once`() = runBlocking {
        val base = serve(401, """{"error":{"message":"invalid key"}}""")
        val e = runCatching { discoverClient(ProviderId.GLM, base).chat(request()) }.exceptionOrNull()
        val llm = e as LlmException
        assertEquals(LlmException.Kind.AUTH, llm.kind)
        assertEquals(1, llm.attempts.size)
    }

    @Test
    fun `429 is rate limited and retried to the budget`() = runBlocking {
        val base = serve(429, """{"error":{"message":"slow down"}}""")
        val e = runCatching { discoverClient(ProviderId.QWEN, base).chat(request()) }.exceptionOrNull()
        val llm = e as LlmException
        assertEquals(LlmException.Kind.RATE_LIMIT, llm.kind)
        assertEquals(4, llm.attempts.size)
    }

    @Test
    fun `status to kind mapping covers the families`() {
        val c = ProviderChatClient(ProviderRegistry.MIMO)
        assertEquals(LlmException.Kind.AUTH, c.errorKind(403, ""))
        assertEquals(LlmException.Kind.RATE_LIMIT, c.errorKind(429, ""))
        assertEquals(LlmException.Kind.SERVER, c.errorKind(503, ""))
        assertEquals(LlmException.Kind.BAD_REQUEST, c.errorKind(422, ""))
        assertEquals(null, c.errorKind(200, """{"ok":true}"""))
        assertTrue(LlmException.Kind.AUTH.isRetryable.not())
    }
    @Test
    fun `empty content is empty-content kind and not retried`() = runBlocking {
        val base = serve(200, """{"choices":[{"message":{"content":""}}]}""")
        val e = runCatching { discoverClient(ProviderId.MIMO, base).chat(request().copy(jsonMode = false)) }
            .exceptionOrNull()
        val llm = e as LlmException
        assertEquals(LlmException.Kind.EMPTY_CONTENT, llm.kind)
        assertEquals(1, llm.attempts.size)
    }

    @Test
    fun `json mode with prose content is invalid-json and not retried`() = runBlocking {
        val base = serve(200, """{"choices":[{"message":{"content":"好的，我来改。"}}]}""")
        val e = runCatching { discoverClient(ProviderId.GLM, base).chat(request()) }.exceptionOrNull()
        val llm = e as LlmException
        assertEquals(LlmException.Kind.INVALID_JSON, llm.kind)
        assertEquals(1, llm.attempts.size)
    }
}
