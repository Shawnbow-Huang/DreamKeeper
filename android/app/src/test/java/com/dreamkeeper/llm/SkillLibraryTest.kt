package com.dreamkeeper.llm

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillLibraryTest {

    private val library: SkillLibrary
        get() = SkillLibrary(ApplicationProvider.getApplicationContext<android.content.Context>().assets)

    @Test
    fun `每个阶段都能取到非空英文原文且不超预算`() {
        SkillStage.entries.forEach { stage ->
            val files = library.filesFor(stage)
            assertTrue("$stage 为空", files.isNotEmpty())
            assertTrue("$stage 有空文件", files.all { it.text.isNotBlank() })
            val total = files.sumOf { it.text.length }
            assertTrue("$stage 超预算：$total", total <= SkillLibrary.DEFAULT_BUDGET)
        }
        val plan = library.filesFor(SkillStage.PLAN)
        assertTrue("PLAN 第一份应是 premise 原件", plan.first().text.contains("premise", ignoreCase = true))
    }

    @Test
    fun `组合 hash 稳定且为 8 位十六进制`() {
        val a = library.bundleSha8()
        val b = library.bundleSha8()
        assertEquals(a, b)
        assertTrue(a, Regex("[0-9a-f]{8}").matches(a))
    }

}
