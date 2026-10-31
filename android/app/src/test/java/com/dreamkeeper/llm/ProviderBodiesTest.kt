package com.dreamkeeper.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 6 T2：五家请求体 golden。
 *
 * 只测纯函数 [ProviderChatClient.buildBody]（不发网络）：字段集合与线值是契约，
 * 差错只会在真实请求里暴露，所以必须在 JVM 上钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderBodiesTest {

    private fun client(provider: ProviderId) = ProviderChatClient(ProviderRegistry.of(provider))

    private fun req(
        model: String = "",
        thinking: Thinking = Thinking.DISABLED,
        effort: ReasoningEffort? = null,
        temperature: Double? = null,
        jsonMode: Boolean = false,
        maxTokens: Int? = null,
    ) = ChatRequest(
        model = model,
        messages = listOf(ChatMessage("system", "sys"), ChatMessage("user", "把这段理顺：屋顶有鲸鱼。")),
        thinking = thinking,
        effort = effort,
        temperature = temperature,
        jsonMode = jsonMode,
        apiKey = "sk-test-0000",
        kind = "NOVEL",
        maxTokens = maxTokens,
    )

    private fun JSONObject.keysSorted(): List<String> = keys().asSequence().sorted().toList()

    @Test
    fun `mimo sends thinking object and never effort`() {
        val body = client(ProviderId.MIMO).buildBody(
            req(model = "mimo-v2.6-pro", thinking = Thinking.ENABLED, effort = ReasoningEffort.HIGH),
        )

        assertEquals("mimo-v2.6-pro", body.getString("model"))
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertFalse("MiMo 不支持 reasoning_effort", body.has("reasoning_effort"))
        assertFalse("思考开时不许发 temperature", body.has("temperature"))
        assertEquals(
            listOf("messages", "model", "stream", "thinking"),
            body.keysSorted(),
        )
    }

    @Test
    fun `mimo json mode adds response format and keeps temperature when thinking off`() {
        val body = client(ProviderId.MIMO).buildBody(
            req(model = "mimo-v2.6-flash", temperature = 0.2, jsonMode = true),
        )

        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals(0.2, body.getDouble("temperature"), 0.0001)
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
    }

    @Test
    fun `minimax never sends response format and uses disabled only`() {
        val body = client(ProviderId.MINIMAX).buildBody(
            req(model = "MiniMax-M3", jsonMode = true, temperature = 0.1),
        )

        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertFalse("MiniMax 无 response_format", body.has("response_format"))
        assertTrue("关思考时可以有 temperature", body.has("temperature"))
    }

    @Test
    fun `qwen uses enable_thinking boolean and xhigh for high effort`() {
        val body = client(ProviderId.QWEN).buildBody(
            req(model = "qwen3.8-max", thinking = Thinking.ENABLED, effort = ReasoningEffort.HIGH),
        )

        assertTrue(body.getBoolean("enable_thinking"))
        assertFalse("Qwen 不发 thinking 对象", body.has("thinking"))
        assertEquals("xhigh", body.getString("reasoning_effort"))
        assertFalse(body.has("temperature"))
    }

    @Test
    fun `glm maps max effort and sends temperature only when thinking off`() {
        val on = client(ProviderId.GLM).buildBody(
            req(model = "glm-5.2", thinking = Thinking.ENABLED, effort = ReasoningEffort.MAX),
        )
        assertEquals("max", on.getString("reasoning_effort"))
        assertFalse(on.has("temperature"))

        val off = client(ProviderId.GLM).buildBody(
            req(model = "glm-5.2", thinking = Thinking.DISABLED, temperature = 0.9),
        )
        assertEquals(0.9, off.getDouble("temperature"), 0.0001)
        assertFalse("关思考不发 effort", off.has("reasoning_effort"))
    }

    @Test
    fun `max tokens is sent only when set`() {
        val none = client(ProviderId.DEEPSEEK).buildBody(req(model = "deepseek-flash"))
        assertFalse(none.has("max_tokens"))

        val one = client(ProviderId.DEEPSEEK).buildBody(req(model = "deepseek-flash", maxTokens = 1))
        assertEquals(1, one.getInt("max_tokens"))
    }

    @Test
    fun `unknown model falls back to the provider default`() {
        val body = client(ProviderId.MINIMAX).buildBody(req(model = "no-such-model"))
        assertEquals("MiniMax-M3", body.getString("model"))
    }
}
