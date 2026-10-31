package com.dreamkeeper.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.llm.GenerationOptions
import com.dreamkeeper.llm.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 5 Task 2：档位持久化的 5 条用例。
 *
 * 用真 SharedPreferences（Robolectric 提供内存实现），不 mock——要证的正是「写进去、
 * 换个实例读出来还是那个值」；mock 掉存储等于把被测行为替换成断言本身。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerationPrefsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun prefs(): GenerationPrefs = GenerationPrefs(
        context.getSharedPreferences("settings-test", Context.MODE_PRIVATE),
    )

    @Test
    fun `默认是 flash 加思考高`() {
        val options = prefs().options()

        assertEquals(GenerationOptions.FLASH, options.model)
        assertEquals(ThinkingLevel.HIGH, options.novelThinking)
    }

    @Test
    fun `模型写进去新实例读出来仍生效`() {
        prefs().setModel(GenerationOptions.PRO)

        assertEquals(GenerationOptions.PRO, prefs().options().model)
    }

    @Test
    fun `白名单外的模型被忽略且不覆盖旧值`() {
        prefs().setModel(GenerationOptions.PRO)

        prefs().setModel("deepseek-o1-没见过")

        assertEquals(GenerationOptions.PRO, prefs().options().model)
    }

    @Test
    fun `思考档位写进去新实例读出来仍生效`() {
        prefs().setNovelThinking(ThinkingLevel.MAX)

        assertEquals(ThinkingLevel.MAX, prefs().options().novelThinking)
    }

    @Test
    fun `存储里是垃圾值时回默认且不崩`() {
        context.getSharedPreferences("settings-test", Context.MODE_PRIVATE).edit()
            .putString(GenerationPrefs.KEY_MODEL, "gpt-梦里")
            .putString(GenerationPrefs.KEY_THINKING, "VERY_HIGH")
            .commit()

        val options = prefs().options()

        assertEquals(GenerationOptions.FLASH, options.model)
        assertEquals(ThinkingLevel.HIGH, options.novelThinking)
    }

    @Test
    fun `忠实整理模型默认跟随小说化且可单独设置`() {
        assertEquals(GenerationOptions.FLASH, prefs().options().faithfulModel)
        prefs().setModel(GenerationOptions.PRO)
        assertEquals(GenerationOptions.PRO, prefs().options().faithfulModel)

        prefs().setFaithfulModel(GenerationOptions.FLASH)
        assertEquals(GenerationOptions.PRO, prefs().options().model)
        assertEquals(GenerationOptions.FLASH, prefs().options().faithfulModel)
    }

    @Test
    fun `检测到的模型进入候选并跨实例保留`() {
        val first = prefs()
        first.setDetectedModels(com.dreamkeeper.llm.ProviderId.MIMO, listOf("mimo-v9-experimental"))
        first.setProvider(com.dreamkeeper.llm.ProviderId.MIMO)

        val second = prefs()
        assertTrue(second.knownModels(com.dreamkeeper.llm.ProviderId.MIMO).contains("mimo-v9-experimental"))
        second.setModel("mimo-v9-experimental")
        assertEquals("mimo-v9-experimental", second.options().model)
    }
}
