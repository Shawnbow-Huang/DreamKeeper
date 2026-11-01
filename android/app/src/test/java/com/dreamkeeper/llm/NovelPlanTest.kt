package com.dreamkeeper.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelPlanTest {

    private val raw = "我梦见去澳门玩，好像要坐宇宙飞船去别的星球一样，得坐一个类似的东西。" +
        "后来一家人纠结4.4点钟出发、6点到。画面一转，突然就到了澳门。"

    private fun scene(goal: String) = """
        {"goal":"$goal","conflict":"阻力","outcome":"但是没成",
         "sequel":{"reaction":"愣了一下","dilemma":"走还是留","decision":"还是走"}}
    """.trimIndent()

    private fun planJson(
        scenes: List<String> = listOf(scene("想去澳门"), scene("找飞船"), scene("进店被拦")),
        controllingIdea: String = "安全感败，因为澳门不再禁枪这件事让他更慌",
        coreImages: String = "[\"澳门\",\"宇宙飞船\"]",
        endingChange: String = "他从兴奋变成发怵",
        targetLength: Int = 1500,
    ) = """
        {
          "premise":"一个人梦见去澳门，却始终没真正出发",
          "want":"去成澳门",
          "obstacle":"攻略没做好，一家人一直纠结",
          "cost":"出发的兴奋被消磨",
          "controllingIdea":"$controllingIdea",
          "counterPremise":"安全感胜，因为家人最终一起出发",
          "pov":"FIRST",
          "targetLength":$targetLength,
          "scenes":[${scenes.joinToString(",")}],
          "endingChange":"$endingChange",
          "coreImages":$coreImages,
          "emotionalTruth":"想出发又怕真的出发"
        }
    """.trimIndent()

    @Test
    fun `合法 PLAN 可解析且 coreImages 都在原文里`() {
        val plan = NovelPlan.parse(planJson(), raw)!!
        assertEquals(3, plan.scenes.size)
        assertEquals(NovelPlan.Pov.FIRST, plan.pov)
        assertTrue(plan.coreImages.all { raw.contains(it) })
        assertTrue(plan.controllingIdea.contains("因为"))
    }

    @Test
    fun `围栏 JSON 也能解析`() {
        val fenced = "```json\n" + planJson() + "\n```"
        assertTrue(NovelPlan.parse(fenced, raw) != null)
    }

    @Test
    fun `缺 endingChange 或场景数为 2 直接判 null`() {
        assertNull(NovelPlan.parse(planJson(endingChange = ""), raw))
        assertNull(NovelPlan.parse(planJson(scenes = listOf(scene("一"), scene("二"))), raw))
    }

    @Test
    fun `coreImages 不是原文子串就判 null`() {
        assertNull(NovelPlan.parse(planJson(coreImages = "[\"香港\"]"), raw))
    }

    @Test
    fun `主题句没有因为或长度越界都判 null`() {
        assertNull(NovelPlan.parse(planJson(controllingIdea = "安全感败"), raw))
        assertNull(NovelPlan.parse(planJson(targetLength = 600), raw))
        assertNull(NovelPlan.parse(planJson(targetLength = 6000), raw))
    }
}
