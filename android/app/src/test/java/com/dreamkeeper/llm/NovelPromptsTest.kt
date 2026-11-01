package com.dreamkeeper.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelPromptsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prompts = NovelPrompts(context)

    @Test
    fun `PLAN 渲染含规划字段与英文原件`() {
        val text = prompts.renderPlan(raw = "呃，我梦见车站。", faithful = "我梦见车站。", idea = null).text
        assertTrue(text.contains("\"premise\""))
        assertTrue(text.contains("controllingIdea"))
        assertTrue("没注入 skill 原件", text.contains("参考资料"))
        assertTrue(text.contains("premise", ignoreCase = true))
    }

    @Test
    fun `DRAFT 与 POLISH 各自带自己的原件且不串段`() {
        val draft = prompts.renderDraft("{}", "我梦见车站。", "写得冷一点").text
        assertTrue(draft.contains("scene-sequel"))
        assertTrue(draft.contains("写得冷一点"))

        val polish = prompts.renderPolish("{}", "{\"body\":\"x\"}").text
        assertTrue(polish.contains("line-edit-checklist"))
        assertTrue("POLISH 不该出现 PLAN 的 premise 原件", polish.contains("premise-workshop").not())
    }

    @Test
    fun `组合 hash 稳定且为 8 位十六进制`() {
        val a = prompts.bundleSha8()
        assertEquals(a, prompts.bundleSha8())
        assertTrue(a, Regex("[0-9a-f]{8}").matches(a))
    }
}
