package com.dreamkeeper.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan 6 T2：usage 三种形状。真实响应的字段集合不同，不能按 DeepSeek 一种写死。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderUsageTest {

    private val client = ProviderChatClient(ProviderRegistry.MIMO)

    @Test
    fun `deepseek style cache hit and miss are preserved`() {
        val usage = client.parseUsage(
            JSONObject(
                """{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,
                    "prompt_cache_hit_tokens":70,"prompt_cache_miss_tokens":30,
                    "completion_tokens_details":{"reasoning_tokens":5}}""",
            ),
        )!!
        assertEquals(70, usage.promptCacheHitTokens)
        assertEquals(30, usage.promptCacheMissTokens)
        assertEquals(20, usage.completionTokens)
        assertEquals(5, usage.reasoningTokens)
        assertEquals(120, usage.totalTokens)
    }

    @Test
    fun `openai style cached tokens are mapped to hit and rest to miss`() {
        val usage = client.parseUsage(
            JSONObject(
                """{"prompt_tokens":100,"completion_tokens":20,
                    "prompt_tokens_details":{"cached_tokens":40}}""",
            ),
        )!!
        assertEquals(40, usage.promptCacheHitTokens)
        assertEquals(60, usage.promptCacheMissTokens)
        assertEquals(120, usage.totalTokens)
    }

    @Test
    fun `missing cache fields fall back to zero and null usage stays null`() {
        val usage = client.parseUsage(JSONObject("""{"prompt_tokens":10,"completion_tokens":2}"""))!!
        assertEquals(0, usage.promptCacheHitTokens)
        assertEquals(10, usage.promptCacheMissTokens)
        assertEquals(0, usage.reasoningTokens)
        assertNull(client.parseUsage(null))
    }
}
