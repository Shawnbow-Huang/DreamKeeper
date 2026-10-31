package com.dreamkeeper.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 6 T0/T2：DeepSeek 请求体基线。
 *
 * Provider 化之前先把「当前请求体长什么样」冻结成可断言的值；重构后必须仍然绿。
 * 任何字节级变化（新增字段、字段改名、少发字段）都会在这里红。
 *
 * 四个用例覆盖 [ChatRequest] 里会改变请求体的开关组合（thinking 开/关、jsonMode、
 * temperature 有无）。比较用「解析后再规范化」的方式，不依赖 JSON 键顺序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepSeekRequestBaselineTest {

    private val servers = mutableListOf<Pair<HttpServer, java.util.concurrent.ExecutorService>>()

    @After
    fun tearDown() {
        servers.forEach { (server, pool) ->
            server.stop(0)
            pool.shutdownNow()
        }
        servers.clear()
    }

    @Test
    fun faithfulBodyIsFrozen() = capture("faithful", request(thinking = Thinking.DISABLED, temperature = 0.1))

    @Test
    fun novelBodyIsFrozen() = capture("novel", request(thinking = Thinking.ENABLED, temperature = null))

    @Test
    fun jsonModeBodyIsFrozen() = capture("json", request(thinking = Thinking.DISABLED, temperature = 0.1, jsonMode = true))

    @Test
    fun noTemperatureBodyIsFrozen() = capture("no-temp", request(thinking = Thinking.DISABLED, temperature = null))

    private fun capture(name: String, req: ChatRequest) = runBlocking {
        val (server, bodies) = startServer { exchange ->
            respond(exchange, 200, if (req.jsonMode) okJsonBody() else okBody())
        }
        val baseUrl = "http://127.0.0.1:${server.address.port}/"
        newClient(baseUrl).chat(req)

        assertEquals("每个用例必须恰好发一次请求", 1, bodies.size)
        val canonical = canonicalize(JSONObject(bodies.single()))
        println("[BASELINE] $name $canonical")
        assertEquals("请求体基线变化（改名/加字段/少发字段都算破坏契约）", expected(name), canonical)
        bodies.clear()
    }

    private fun expected(name: String): String = when (name) {
        "faithful" -> """{"messages":[{"content":"你是梦境记录助手。","role":"system"},{"content":"记录：屋顶有鲸鱼。","role":"user"}],"model":"deepseek-flash","stream":false,"temperature":0.1,"thinking":{"type":"disabled"}}"""
        "novel" -> """{"messages":[{"content":"你是梦境记录助手。","role":"system"},{"content":"记录：屋顶有鲸鱼。","role":"user"}],"model":"deepseek-flash","reasoning_effort":"high","stream":false,"thinking":{"type":"enabled"}}"""
        "json" -> """{"messages":[{"content":"你是梦境记录助手。","role":"system"},{"content":"记录：屋顶有鲸鱼。","role":"user"}],"model":"deepseek-flash","response_format":{"type":"json_object"},"stream":false,"temperature":0.1,"thinking":{"type":"disabled"}}"""
        "no-temp" -> """{"messages":[{"content":"你是梦境记录助手。","role":"system"},{"content":"记录：屋顶有鲸鱼。","role":"user"}],"model":"deepseek-flash","stream":false,"thinking":{"type":"disabled"}}"""
        else -> error("unknown case $name")
    }

    private fun canonicalize(value: Any?): String = when (value) {
        null -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
            JSONObject.quote(key) + ":" + canonicalize(value.get(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") { index ->
            canonicalize(value.get(index))
        }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    // ---------------------------------------------------------------- 脚手架

    private fun startServer(responder: (HttpExchange) -> Unit): Pair<HttpServer, MutableList<String>> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "baseline-http").apply { isDaemon = true }
        }
        val bodies = mutableListOf<String>()
        server.executor = pool
        server.createContext("/chat/completions") { exchange ->
            bodies += exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            runCatching { responder(exchange) }
        }
        server.start()
        servers += server to pool
        return server to bodies
    }

    private fun newClient(baseUrl: String): DeepSeekClient = DeepSeekClient(
        client = OkHttpClient(),
        baseUrl = baseUrl,
        callTimeoutMs = 10_000L,
        readTimeoutMs = 5_000L,
        sleeper = { },
        jitter = { 0L },
        retry = RetryPolicy(maxAttempts = 1),
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

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun okJsonBody(): String = """
        {"id":"chatcmpl-baseline2","object":"chat.completion","created":1,
         "model":"deepseek-flash",
         "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"{\"title\":\"鲸鱼\",\"body\":\"屋顶有鲸鱼。\"}"}}],
         "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
    """.trimIndent()

    private fun okBody(): String = """
        {"id":"chatcmpl-baseline","object":"chat.completion","created":1,
         "model":"deepseek-flash",
         "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"一只鲸鱼。"}}],
         "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,
                  "prompt_cache_hit_tokens":4,"prompt_cache_miss_tokens":6,
                  "completion_tokens_details":{"reasoning_tokens":2}}}
    """.trimIndent()
}
