package com.dreamkeeper.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelChainTest {

    private val raw = "我梦见去澳门玩，好像要坐宇宙飞船去别的星球一样。"
    private val base = "我梦见去澳门玩，好像要坐宇宙飞船去别的星球。"

    private fun planJson() = """
        {"premise":"去澳门却始终没出发","want":"去成澳门","obstacle":"攻略没做好","cost":"兴奋被消磨",
         "controllingIdea":"安全感败，因为澳门不再禁枪让他更慌","counterPremise":"安全感胜，因为家人一起出发",
         "pov":"FIRST","targetLength":1500,
         "scenes":[
           {"goal":"出发","conflict":"纠结","outcome":"没走","sequel":{"reaction":"愣","dilemma":"走留","decision":"留"}},
           {"goal":"找船","conflict":"找不到","outcome":"但是上了车","sequel":{"reaction":"急","dilemma":"等不等","decision":"等"}},
           {"goal":"进店","conflict":"被拦","outcome":"不进去了","sequel":{"reaction":"恼","dilemma":"争不争","decision":"不争"}}],
         "endingChange":"他从兴奋变成发怵","coreImages":["澳门"],"emotionalTruth":"想出发又怕出发"}
    """.trimIndent()

    private fun body(extra: String = "") = "他推开门。走廊尽头有一扇窗。风从窗缝里进来。" + extra

    private fun draftJson(): String {
        val b = buildString {
            repeat(3) { i ->
                if (i > 0) append("\n\n")
                repeat(16) { append(body()) }
            }
        }
        return """{"title":"下不完的楼梯","body":${org.json.JSONObject.quote(b)},"notes":[
            {"type":"借用","detail":"沿用了楼梯"},
            {"type":"舍弃","detail":"舍掉吃饭"},
            {"type":"主题","detail":"落在回不去的家"}],"mood":"发怵"}"""
    }

    private fun chain(chat: suspend (String) -> String, stages: MutableList<String> = mutableListOf()) = NovelChain(
        renderPlan = { _, _, _ -> stages += "plan"; "PLAN_PROMPT" },
        renderDraft = { plan, _, _ -> stages += "draft:" + plan.contains("去澳门"); "DRAFT_PROMPT" },
        renderPolish = { p, d -> stages += "polish"; "POLISH_PROMPT" },
        chat = { _, prompt -> chat(prompt) },
    )

    @Test
    fun `debug 草稿本身过闸门`() {
        val report = com.dreamkeeper.llm.gates.NovelGates.check(draftJson())
        assertTrue(report.results.joinToString(" | ") { it.code + "=" + it.passed + ":" + it.detail }, report.passed)
    }

    @Test
    fun `三段按顺序各发一次并把规划带进 DRAFT`() = runTest {
        val sent = mutableListOf<String>()
        val order = mutableListOf<String>()
        val result = chain({ prompt ->
            sent += prompt
            when (prompt) {
                "PLAN_PROMPT" -> planJson()
                "DRAFT_PROMPT" -> draftJson()
                else -> draftJson()
            }
        }, order).run(raw, base, null)

        assertTrue((result as? NovelChainResult.Failure)?.reason ?: "", result is NovelChainResult.Success)
        result as NovelChainResult.Success
        assertEquals(listOf(GenerationStage.PLAN, GenerationStage.DRAFT, GenerationStage.POLISH), result.stages)
        assertEquals(listOf("plan", "draft:true", "polish"), order)
        assertEquals("下不完的楼梯", result.draft.title)
        assertEquals(3, sent.size)
        assertEquals(false, result.polishFallback)
    }

    @Test
    fun `PLAN 两次都坏 JSON 就整次失败且不进 DRAFT`() = runTest {
        val order = mutableListOf<String>()
        val result = chain({ "不是 JSON" }, order).run(raw, base, null)

        assertTrue(result is NovelChainResult.Failure)
        result as NovelChainResult.Failure
        assertEquals(GenerationStage.PLAN, result.stage)
        assertEquals(listOf("plan"), order)
    }

    @Test
    fun `DRAFT 第一次坏第二次好会重试`() = runTest {
        var draftCalls = 0
        val result = chain({ prompt ->
            when (prompt) {
                "PLAN_PROMPT" -> planJson()
                "DRAFT_PROMPT" -> if (++draftCalls == 1) "坏草稿" else draftJson()
                else -> draftJson()
            }
        }).run(raw, base, null)

        assertTrue((result as? NovelChainResult.Failure)?.reason ?: "", result is NovelChainResult.Success)
        assertEquals(2, draftCalls)
    }

    @Test
    fun `POLISH 两次失败回退草稿并加 WARN 手记`() = runTest {
        val result = chain({ prompt ->
            when (prompt) {
                "PLAN_PROMPT" -> planJson()
                "DRAFT_PROMPT" -> draftJson()
                else -> "坏润色"
            }
        }).run(raw, base, null)

        assertTrue((result as? NovelChainResult.Failure)?.reason ?: "", result is NovelChainResult.Success)
        result as NovelChainResult.Success
        assertTrue(result.polishFallback)
        assertTrue(result.draft.notes.any { it.detail.contains("润色") })
        assertEquals(4, result.draft.notes.size)
    }
}
