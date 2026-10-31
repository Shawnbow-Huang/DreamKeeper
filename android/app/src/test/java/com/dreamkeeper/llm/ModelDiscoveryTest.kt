package com.dreamkeeper.llm

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「检测可用模型」（GET /models）的契约。
 *
 * 关键行为：只发一次、Bearer 头、四种返回形状都能认、失败一律降级（不当鉴权错误）。
 * 用 JDK 自带 HttpServer（项目零新依赖），记录 method/path/Authorization 供断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDiscoveryTest {

    private lateinit var server: HttpServer
    private var port: Int = 0
    private val hits = AtomicInteger()
    private var lastAuth: String? = null
    private var lastPath: String? = null
    private var body: String = ""
    private var status: Int = 200

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            lastAuth = exchange.requestHeaders.getFirst("Authorization")
            lastPath = exchange.requestURI.path
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        port = server.address.port
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun respond(code: Int, json: String) {
        status = code
        body = json
    }

    @Test
    fun `openai shape returns model ids and sends bearer`() = runBlocking {
        respond(200, """{"data":[{"id":"mimo-v2.6-pro"},{"id":"mimo-v2.6-flash"}]}""")
        val result = ModelDiscovery().list(ProviderId.MIMO, "sk-mimo-1234567890", "http://127.0.0.1:$port/v1")
        assertEquals(listOf("mimo-v2.6-flash", "mimo-v2.6-pro"), result.models)
        assertTrue(result.ok)
        assertEquals("/v1/models", lastPath)
        assertEquals("Bearer sk-mimo-1234567890", lastAuth)
        assertEquals(1, hits.get())
    }

    @Test
    fun `accepts model and name fields plus plain string arrays`() = runBlocking {
        respond(200, """{"data":[{"model":"glm-5.2"},{"name":"glm-5.3"}],"models":["glm-4.6"]}""")
        val result = ModelDiscovery().list(ProviderId.GLM, "sk-glm-1234567890", "http://127.0.0.1:$port/api/paas/v4")
        assertEquals(listOf("glm-4.6", "glm-5.2", "glm-5.3"), result.models)
    }

    @Test
    fun `unsupported endpoint degrades instead of failing auth`() = runBlocking {
        respond(404, """{"error":"not found"}""")
        val result = ModelDiscovery().list(ProviderId.MINIMAX, "sk-minimax-1234567890", "http://127.0.0.1:$port/v1")
        assertFalse(result.ok)
        assertEquals(emptyList<String>(), result.models)
        assertTrue(result.error!!.contains("404"))
    }

    @Test
    fun `blank key never hits the network`() = runBlocking {
        val result = ModelDiscovery().list(ProviderId.DEEPSEEK, "   ", "http://127.0.0.1:$port/")
        assertFalse(result.ok)
        assertEquals(0, hits.get())
    }

    @Test
    fun `garbage body is reported as empty list`() = runBlocking {
        respond(200, "not json")
        val result = ModelDiscovery().list(ProviderId.QWEN, "sk-qwen-1234567890", "http://127.0.0.1:$port/v1")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("认得出"))
    }
}
