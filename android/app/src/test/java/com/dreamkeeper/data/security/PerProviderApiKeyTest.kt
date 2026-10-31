package com.dreamkeeper.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.llm.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 6 §0.3：五家 Key 必须隔离，且 DeepSeek 的旧键零迁移。
 *
 * 这里不测真 Keystore（Robolectric 没有 shadow），只测**键的选择与隔离**：
 * 哪家读哪个 pref、坏密文只清自己、clear 不连坐。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerProviderApiKeyTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var cipher: FakeCipher
    private lateinit var store: KeystoreApiKeyStore

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        cipher = FakeCipher()
        store = KeystoreApiKeyStore(cipher, prefs, log = {})
    }

    @Test
    fun `deepseek keeps the legacy key while others use suffixed keys`() {
        store.save("sk-deepseek-legacy-0001", ProviderId.DEEPSEEK)
        store.save("sk-mimo-000000000002", ProviderId.MIMO)

        assertEquals(setOf("api_key_packed", "api_key_packed.mimo"), prefs.all.keys)
        assertEquals("sk-deepseek-legacy-0001", store.load(ProviderId.DEEPSEEK))
        assertEquals("sk-mimo-000000000002", store.load(ProviderId.MIMO))
    }

    @Test
    fun `a legacy deepseek key is readable without any migration step`() {
        prefs.edit()
            .putString("api_key_packed", cipher.encrypt("sk-old-user-key-000003"))
            .commit()

        assertEquals("sk-old-user-key-000003", store.load(ProviderId.DEEPSEEK))
        assertNull("旧键绝不能回退给别家", store.load(ProviderId.MIMO))
        assertNull(store.load(ProviderId.GLM))
    }

    @Test
    fun `all five providers are isolated`() {
        val keys = ProviderId.entries.associateWith { "sk-${it.wire}-isolated-0000" }
        keys.forEach { (provider, key) -> store.save(key, provider) }

        keys.forEach { (provider, key) -> assertEquals(key, store.load(provider)) }
        assertEquals(5, prefs.all.size)
    }

    @Test
    fun `clear removes only the selected provider`() {
        store.save("sk-deepseek-keep-000005", ProviderId.DEEPSEEK)
        store.save("sk-qwen-remove-000006", ProviderId.QWEN)

        store.clear(ProviderId.QWEN)

        assertEquals("sk-deepseek-keep-000005", store.load(ProviderId.DEEPSEEK))
        assertNull(store.load(ProviderId.QWEN))
        assertEquals(setOf("api_key_packed"), prefs.all.keys)
    }

    @Test
    fun `blank save clears only that provider`() {
        store.save("sk-deepseek-blank-000007", ProviderId.DEEPSEEK)
        store.save("sk-minimax-blank-000008", ProviderId.MINIMAX)

        store.save("   \n\t", ProviderId.MINIMAX)

        assertEquals("sk-deepseek-blank-000007", store.load(ProviderId.DEEPSEEK))
        assertNull(store.load(ProviderId.MINIMAX))
    }

    @Test
    fun `corrupt ciphertext removes only that provider residue`() {
        store.save("sk-deepseek-survivor-000009", ProviderId.DEEPSEEK)
        prefs.edit().putString("api_key_packed.glm", "not-base64-!!").commit()

        assertNull(store.load(ProviderId.GLM))

        assertEquals("sk-deepseek-survivor-000009", store.load(ProviderId.DEEPSEEK))
        assertTrue("只清坏掉的那一家", prefs.all.keys.none { it.endsWith(".glm") })
    }
}
