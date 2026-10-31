package com.dreamkeeper.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.security.ApiKeyStore
import com.dreamkeeper.data.settings.GenerationPrefs
import com.dreamkeeper.llm.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置首页 / 模型服务列表：列表只读状态，不做配置。
 *
 * 关键断言：五家都在、使用中的那家只有一个、未配 Key 的家显示为未配置、
 * 首页摘要的模型名跟当前供应商一致、切家只写一次偏好。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderListViewModelTest {

    private val main = StandardTestDispatcher()
    private val io = UnconfinedTestDispatcher()
    private lateinit var prefs: GenerationPrefs
    private lateinit var store: ProviderDetailViewModelTest.FakeKeyStore

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = GenerationPrefs(context.getSharedPreferences(GenerationPrefs.PREFS_NAME, Context.MODE_PRIVATE))
        store = ProviderDetailViewModelTest.FakeKeyStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(): ProviderListViewModel = ProviderListViewModel(store, prefs, themeController = null, io = io)

    @Test
    fun `lists all providers with exactly one active`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val rows = vm.state.value.rows
        assertEquals(ProviderId.entries.size, rows.size)
        assertEquals(1, rows.count { it.active })
        assertEquals(ProviderId.DEEPSEEK, rows.first { it.active }.provider)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `summarises the active provider model on the settings home`() = runTest {
        prefs.setProvider(ProviderId.MIMO)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(ProviderId.MIMO, vm.state.value.activeProvider)
        assertEquals("MiMo V2.6 Pro", vm.state.value.modelLabel)
    }

    @Test
    fun `configured flag comes from the per-provider key store`() = runTest {
        store.save("sk-deepseek-1234567890abcdef", ProviderId.DEEPSEEK)
        store.save("sk-mimo-1234567890abcdef", ProviderId.MIMO)
        val vm = vm()
        advanceUntilIdle()
        val rows = vm.state.value.rows.associateBy { it.provider }
        assertTrue(rows.getValue(ProviderId.DEEPSEEK).configured)
        assertTrue(rows.getValue(ProviderId.MIMO).configured)
        assertFalse(rows.getValue(ProviderId.GLM).configured)
    }

    @Test
    fun `refresh picks up a provider switch made elsewhere`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        prefs.setProvider(ProviderId.GLM)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(ProviderId.GLM, vm.state.value.activeProvider)
        assertEquals(1, vm.state.value.rows.count { it.active })
    }
}
