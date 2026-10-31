package com.dreamkeeper.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.security.ApiKeyStore
import com.dreamkeeper.data.settings.GenerationPrefs
import com.dreamkeeper.llm.Attempt
import com.dreamkeeper.llm.ChatMessage
import com.dreamkeeper.llm.ChatRequest
import com.dreamkeeper.llm.ChatResponse
import com.dreamkeeper.llm.ConnectionOutcome
import com.dreamkeeper.llm.ConnectionTester
import com.dreamkeeper.llm.LlmClient
import com.dreamkeeper.llm.LlmException
import com.dreamkeeper.llm.ProviderId
import com.dreamkeeper.llm.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan 6 T4：设置页的供应商切换 / Key 隔离 / 测试连接三态 / 结果防串台。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderDetailViewModelTest {

    private val main = StandardTestDispatcher()
    private val io = UnconfinedTestDispatcher()
    private lateinit var prefs: GenerationPrefs
    private lateinit var store: FakeKeyStore

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = GenerationPrefs(context.getSharedPreferences(GenerationPrefs.PREFS_NAME, Context.MODE_PRIVATE))
        store = FakeKeyStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        client: LlmClient? = null,
        onRequest: ((ProviderId) -> Unit)? = null,
        probe: com.dreamkeeper.llm.ModelProbe? = null,
    ): ProviderDetailViewModel {
        val tester = client?.let {
            ConnectionTester(
                clientFor = { provider -> onRequest?.invoke(provider); it },
                keyOf = { provider -> store.load(provider) },
            )
        }
        return ProviderDetailViewModel(
            apiKeyStore = store,
            generationPrefs = prefs,
            connectionTester = tester,
            io = io,
            discovery = probe,
        )
    }

    @Test
    fun `defaults to deepseek and its default model`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(ProviderId.DEEPSEEK, vm.state.value.provider)
        assertEquals("deepseek-flash", vm.state.value.model)
        assertEquals(2, vm.state.value.models.size)
    }

    @Test
    fun `switching provider swaps models and remembers each provider model`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.setModel("deepseek-v4-pro")
        vm.selectProvider(ProviderId.MIMO)
        advanceUntilIdle()

        assertEquals(ProviderId.MIMO, vm.state.value.provider)
        assertEquals("mimo-v2.6-pro", vm.state.value.model)
        assertEquals(listOf("mimo-v2.6-pro", "mimo-v2.6-flash"), vm.state.value.models.map { it.id })

        vm.selectProvider(ProviderId.DEEPSEEK)
        advanceUntilIdle()
        assertEquals("deepseek-v4-pro", vm.state.value.model)
    }

    @Test
    fun `saving a key only writes the selected provider`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.selectProvider(ProviderId.GLM)
        advanceUntilIdle()
        vm.onDraftChange("sk-glm-0123456789abcdef")
        vm.save()
        advanceUntilIdle()

        assertEquals("sk-glm-0123456789abcdef", store.stored[ProviderId.GLM])
        assertNull(store.stored[ProviderId.DEEPSEEK])
    }

    @Test
    fun `switching provider shows that provider key state`() = runTest {
        store.stored[ProviderId.QWEN] = "sk-qwen-0123456789abcdef"
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.state.value.keyConfigured)

        vm.selectProvider(ProviderId.QWEN)
        advanceUntilIdle()
        assertTrue(vm.state.value.keyConfigured)
        assertTrue(vm.state.value.maskedKey.endsWith("cdef"))
    }

    @Test
    fun `clear removes only the selected provider key`() = runTest {
        store.stored[ProviderId.DEEPSEEK] = "sk-ds-0123456789abcdef"
        store.stored[ProviderId.MINIMAX] = "sk-mm-0123456789abcdef"
        val vm = vm()
        advanceUntilIdle()

        vm.clear()
        advanceUntilIdle()

        assertNull(store.stored[ProviderId.DEEPSEEK])
        assertEquals("sk-mm-0123456789abcdef", store.stored[ProviderId.MINIMAX])
    }

    @Test
    fun `test without key makes zero requests`() = runTest {
        var requests = 0
        val vm = vm(client = OkClient(), onRequest = { requests++ })
        advanceUntilIdle()
        vm.testConnection()
        advanceUntilIdle()

        val conn = vm.state.value.connection
        assertNotNull(conn)
        assertEquals(ConnectionOutcome.Reason.NO_KEY, conn!!.reason)
        assertEquals(0, requests)
    }

    @Test
    fun `test success reports latency http status and model`() = runTest {
        store.stored[ProviderId.QWEN] = "sk-qwen-0123456789abcdef"
        val vm = vm(client = OkClient())
        advanceUntilIdle()
        vm.selectProvider(ProviderId.QWEN)
        advanceUntilIdle()
        vm.testConnection()
        advanceUntilIdle()

        val conn = vm.state.value.connection
        assertNotNull(conn)
        assertTrue(conn!!.ok)
        assertEquals(200L, conn.httpStatus?.toLong())
        assertEquals("qwen3.8-max", conn.model)
    }

    @Test
    fun `auth failure maps to a human readable reason`() = runTest {
        store.stored[ProviderId.GLM] = "sk-glm-0123456789abcdef"
        val vm = vm(client = ThrowingClient(LlmException.Kind.AUTH, httpStatus = 401))
        advanceUntilIdle()
        vm.selectProvider(ProviderId.GLM)
        advanceUntilIdle()
        vm.testConnection()
        advanceUntilIdle()

        val conn = vm.state.value.connection!!
        assertFalse(conn.ok)
        assertEquals(ConnectionOutcome.Reason.AUTH, conn.reason)
        assertTrue(conn.reasonText!!.contains("Key"))
    }

    @Test
    fun `result that arrives after switching provider is dropped`() = runTest {
        store.stored[ProviderId.DEEPSEEK] = "sk-ds-0123456789abcdef"
        val vm = vm(client = SlowClient(delayMs = 1_000))
        advanceUntilIdle()
        vm.testConnection()
        main.scheduler.advanceTimeBy(10)
        vm.selectProvider(ProviderId.MIMO)
        advanceUntilIdle()

        assertNull(vm.state.value.connection)
        assertFalse(vm.state.value.testing)
    }

    @Test
    fun `short key warning no longer requires sk prefix`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onDraftChange("abcdefghijklmnopqrstu") // 21 位，无 sk- 前缀
        assertNull(vm.state.value.warning)

        vm.onDraftChange("short")
        assertTrue(vm.state.value.warning!!.contains("偏短"))
    }

    internal class FakeKeyStore : ApiKeyStore {
        val stored = mutableMapOf<ProviderId, String>()
        override fun load(provider: ProviderId): String? = stored[provider]
        override fun save(key: String, provider: ProviderId) {
            if (key.isBlank()) stored.remove(provider) else stored[provider] = key
        }
        override fun clear(provider: ProviderId) {
            stored.remove(provider)
        }
    }

    private open class OkClient : LlmClient {
        override suspend fun chat(request: ChatRequest): ChatResponse = ChatResponse(
            content = "OK",
            reasoning = null,
            usage = Usage(0, 0, 0, 0, 0),
            requestId = "req-1",
            httpStatus = 200,
            latencyMs = 12,
            finishReason = "stop",
            attempts = listOf(Attempt(1, 200, 12, null, "req-1", 0)),
        )
    }

    private class SlowClient(private val delayMs: Long) : OkClient() {
        override suspend fun chat(request: ChatRequest): ChatResponse {
            delay(delayMs)
            return super.chat(request)
        }
    }

    private class ThrowingClient(
        private val kind: LlmException.Kind,
        httpStatus: Int?,
    ) : OkClient() {
        private val failure = LlmException(kind = kind, httpStatus = httpStatus, requestId = null, attempts = emptyList())
        override suspend fun chat(request: ChatRequest): ChatResponse = throw failure
    }

    @Test
    fun `detail opens the requested provider regardless of saved current`() = runTest {
        prefs.setProvider(ProviderId.GLM)
        val vm = ProviderDetailViewModel(
            apiKeyStore = store,
            generationPrefs = prefs,
            connectionTester = null,
            io = io,
            initialProvider = ProviderId.MIMO,
        )
        advanceUntilIdle()
        assertEquals(ProviderId.MIMO, vm.state.value.provider)
        assertEquals("mimo-v2.6-pro", vm.state.value.model)
        // 打开别人家的详情不应偷偷改当前供应商
        assertEquals(ProviderId.GLM, prefs.options().provider)
    }

    @Test
    fun `make active writes the provider preference`() = runTest {
        val vm = ProviderDetailViewModel(
            apiKeyStore = store,
            generationPrefs = prefs,
            connectionTester = null,
            io = io,
            initialProvider = ProviderId.QWEN,
        )
        advanceUntilIdle()
        vm.makeActive()
        assertEquals(ProviderId.QWEN, prefs.options().provider)
    }

    @Test
    fun `discover models adds candidates without changing the current pick`() = runTest {
        prefs.setDetectedModels(ProviderId.DEEPSEEK, emptyList())
        val probe = object : com.dreamkeeper.llm.ModelProbe {
            override suspend fun list(provider: ProviderId, apiKey: String) =
                com.dreamkeeper.llm.ModelDiscovery.Result(listOf("deepseek-next", "deepseek-flash"))
        }
        val vm = vm(probe = probe)
        advanceUntilIdle()

        vm.discoverModels()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("deepseek-flash", state.model)
        assertTrue(state.models.any { it.id == "deepseek-next" })
        assertEquals("检测到 2 个可用模型", state.discoveryNote)
        assertEquals(listOf("deepseek-next", "deepseek-flash"), prefs.detectedModels(ProviderId.DEEPSEEK))
    }

    @Test
    fun `failed discovery keeps the builtin list and explains why`() = runTest {
        val probe = object : com.dreamkeeper.llm.ModelProbe {
            override suspend fun list(provider: ProviderId, apiKey: String) =
                com.dreamkeeper.llm.ModelDiscovery.Result(emptyList(), "DeepSeek 不支持模型列表（HTTP 404），已用内置清单")
        }
        val vm = vm(probe = probe)
        advanceUntilIdle()

        vm.discoverModels()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(2, state.models.size)
        assertTrue(state.discoveryNote!!.contains("404"))
    }
}
