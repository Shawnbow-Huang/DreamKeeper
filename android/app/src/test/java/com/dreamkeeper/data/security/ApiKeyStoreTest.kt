package com.dreamkeeper.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * `KeystoreApiKeyStore` 的 JVM 单测。
 *
 * 真 Keystore 在 Robolectric 下不可用（jar 里没有 KeyStore/KeyGenerator/Cipher 的 shadow），
 * 所以这里测的是**存取层**：单值持久化、trim、容错分支、日志替身。
 * 真加密壳 `AndroidKeystoreCipher` 由设备侧证据（`cat shared_prefs/settings.xml` 只有 base64）背书。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiKeyStoreTest {

    private val needle = "sk-test-needle-8c1f4a7b2e9d3056"

    private lateinit var prefs: SharedPreferences
    private lateinit var cipher: FakeCipher

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        cipher = FakeCipher()
    }

    private fun store(log: (String) -> Unit = {}) = KeystoreApiKeyStore(cipher, prefs, log)

    @Test
    fun `save then load round-trips and trims`() {
        val store = store()
        assertNull("空库必须读到 null", store.load())
        store.save("  $needle\n")
        assertEquals(needle, store.load())
    }

    @Test
    fun `clear removes the only pref entry`() {
        val store = store()
        store.save(needle)
        assertEquals(setOf(KEY), prefs.all.keys)
        store.clear()
        assertNull(store.load())
        assertTrue("clear 之后 prefs 必须为空", prefs.all.isEmpty())
    }

    @Test
    fun `blank save is equivalent to clear`() {
        val store = store()
        store.save(needle)
        store.save("   \n\t")
        assertNull(store.load())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `bad base64 is treated as not configured and residue is cleared`() {
        prefs.edit().putString(KEY, "not base64 !!!").commit()
        val store = store()
        assertNull("坏 base64 不能抛到界面", store.load())
        assertTrue("坏密文必须顺手清掉", prefs.all.isEmpty())
    }

    @Test
    fun `cipher AEADBadTag is treated as not configured and residue is cleared`() {
        val store = store()
        store.save(needle)
        cipher.failWith = AEADBadTagException("bad tag")
        assertNull(store.load())
        assertTrue(prefs.all.isEmpty())
        // 删别名是 cipher 的职责（真实现 AndroidKeystoreCipher.decrypt 的 catch 里做），
        // 这里断言契约：失败路径必须走到「删别名」。
        assertTrue("解密失败必须删别名", cipher.aliasDeleted)
    }

    @Test
    fun `packed value with iv only is treated as not configured`() {
        prefs.edit().putString(KEY, "${b64(ByteArray(12))}:").commit()
        val store = store()
        assertNull(store.load())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `prefs hold ciphertext and never the plaintext in one snapshot`() {
        val store = store()
        store.save(needle)
        // 同一份快照里同时成立：有且只有一条密文；整张表里搜不到明文。
        assertEquals(1, prefs.all.size)
        assertNotNull(prefs.getString(KEY, null))
        val dumped = prefs.all.toString()
        assertFalse("prefs 里不许出现明文 Key", dumped.contains(needle))
        assertFalse("连 sk- 前缀片段都不许出现", dumped.contains("sk-test"))
    }

    @Test
    fun `save overwrites the previous ciphertext with a fresh iv`() {
        val first = "sk-first-11111111111111111111"
        val second = "sk-second-222222222222222222"
        val store = store()
        store.save(first)
        val firstPacked = prefs.getString(KEY, null)!!
        store.save(second)
        val secondPacked = prefs.getString(KEY, null)!!
        assertEquals("换 Key 后仍然只有一个值", 1, prefs.all.size)
        assertNotEquals("每次保存必须换新 IV", firstPacked, secondPacked)
        assertEquals(second, store.load())
        assertEquals("第二次的密文解出第二个 Key", second, cipher.decrypt(secondPacked))
        assertNotEquals("旧密文已经不在 prefs 里", firstPacked, secondPacked)
    }

    @Test
    fun `masked key keeps sk- prefix and last four chars`() {
        val key = "sk-1234567890abcdefghij"
        val masked = maskApiKey(key)
        assertEquals("sk-…ghij", masked)
        assertFalse(masked.contains(key))
        assertFalse(masked.contains("abcdefghij"))
    }

    @Test
    fun `masked short key hides the tail`() {
        assertEquals("sho…", maskApiKey("short-key"))
    }

    @Test
    fun `logger really receives messages and none contain the key`() {
        val lines = mutableListOf<String>()
        val store = store { lines += it }
        store.save(needle)
        store.load()
        assertTrue("替身必须真的收到日志，否则「不含明文」恒真", lines.isNotEmpty())
        assertTrue("日志里不许出现完整 Key", lines.none { it.contains(needle) })
        assertTrue("日志里不许出现 sk- 前缀片段", lines.none { it.contains("sk-test") })
    }

    private companion object {
        const val KEY = "api_key_packed"

        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}

/**
 * 测试替身：可逆但不含明文的「密文」，行为按 brief 的契约实现（换 IV / 抛错时删别名）。
 * 用 XOR + Base64 而不是把明文拼进 packed —— 否则「prefs 无明文」那条断言会假红。
 */
class FakeCipher : KeystoreCipher {

    /** 非空时 [decrypt] 直接抛（模拟 AEADBadTag / 坏 base64）。 */
    var failWith: Throwable? = null

    /** 模拟 Keystore 里别名的存在状态：失败时必须被删掉。 */
    var aliasDeleted: Boolean = false
        private set

    private var counter = 0

    override fun encrypt(plain: String): String {
        counter++
        aliasDeleted = false
        val iv = ByteArray(12) { i -> (counter * 31 + i).toByte() }
        val ct = plain.toByteArray(Charsets.UTF_8).map { b -> (b.toInt() xor 0x5A).toByte() }.toByteArray()
        return b64(iv) + ":" + b64(ct)
    }

    override fun decrypt(packed: String): String? {
        failWith?.let { failure ->
            aliasDeleted = true
            throw failure
        }
        val parts = packed.split(':')
        require(parts.size == 2 && parts[0].isNotEmpty()) { "坏 base64 / 缺 IV" }
        require(parts[1].isNotEmpty()) { "只有 IV 没有密文" }
        val ct = Base64.getDecoder().decode(parts[1])
        return String(ct.map { b -> (b.toInt() xor 0x5A).toByte() }.toByteArray(), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

/** 界面/ViewModel 用的内存版（brief §0.10：测试桩只许放 test 源集）。 */
class FakeApiKeyStore(initial: String? = null) : ApiKeyStore {

    var stored: String? = initial
        private set
    var saveCount = 0
        private set
    var clearCount = 0
        private set

    override fun load(): String? = stored

    override fun save(key: String) {
        stored = key.trim()
        saveCount++
    }

    override fun clear() {
        stored = null
        clearCount++
    }
}
