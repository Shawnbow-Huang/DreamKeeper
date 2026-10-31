package com.dreamkeeper.data.security

import android.content.SharedPreferences
import android.util.Log
import com.dreamkeeper.llm.ProviderId

/**
 * API Key 的唯一读写口；界面与后续 LLM 客户端都只依赖这个接口。
 *
 * 从 Plan 6 起按供应商隔离：每家一把 Key，存进各自的 pref 键。默认参数是
 * [ProviderId.DEEPSEEK]，让 Plan 1–5 的所有旧调用点无需改动即可继续工作。
 */
interface ApiKeyStore {
    /**
     * 已配置则返回明文 Key，否则 `null`；解密失败按未配置处理，不抛。
     *
     * 注意：**只读自己那家**。DeepSeek 的旧键不会回退给其他供应商。
     */
    fun load(provider: ProviderId = ProviderId.DEEPSEEK): String?

    /** 保存（内部 trim；空串等价 [clear]）。 */
    fun save(key: String, provider: ProviderId = ProviderId.DEEPSEEK)

    /** 清除本机保存的 Key（只清这一家）。 */
    fun clear(provider: ProviderId = ProviderId.DEEPSEEK)
}

/**
 * 设置页显示的掩码，形如 `sk-…abcd`：只露前 3 位（正好是 `sk-`）与末 4 位。
 *
 * 短于 [MASK_MIN_LENGTH] 的 Key（与 ViewModel 的「长度 < 20」软提示同一阈值）只露前 3 位、
 * 不露末 4 位——已经偏短的值少露一点。
 */
fun maskApiKey(key: String): String {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) return ""
    return if (trimmed.length < MASK_MIN_LENGTH) {
        trimmed.take(MASK_HEAD) + "…"
    } else {
        trimmed.take(MASK_HEAD) + "…" + trimmed.takeLast(MASK_TAIL)
    }
}

private const val MASK_HEAD = 3
private const val MASK_TAIL = 4
private const val MASK_MIN_LENGTH = 20

/**
 * 生产实现：`Base64(iv‖ct)` 存进 SharedPreferences，**每家一个键**。
 *
 * 键的规则（与 Plan 6 §0.3 一致）：
 * - DeepSeek 继续用旧键 [KEY_PACKED_LEGACY] —— 老用户升级后 Key 零重输，也不需要迁移代码；
 * - 其他供应商用 `api_key_packed.<wire>`，互相隔离；任何一家都不会读到 DeepSeek 的旧键。
 *
 * 只存一个字符串而不是 IV + 密文两个 pref：两个 `put*` 之间被杀进程会留下「新 IV + 旧密文」
 * 这种永远解不开的组合；单值写入天然原子。`commit()` 同步落盘——`apply()` 之后立刻
 * `am force-stop` 可能丢（`LayoutPrefs` 踩过同款坑，brief §0.2）。
 *
 * [log] 是日志口（默认 `Log.d`，测试注入替身）：**只写元信息**，任何时候都不许把 Key 或其片段写进去。
 */
class KeystoreApiKeyStore(
    private val cipher: KeystoreCipher,
    private val prefs: SharedPreferences,
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) : ApiKeyStore {

    override fun load(provider: ProviderId): String? {
        val prefKey = prefKeyFor(provider)
        val packed = prefs.getString(prefKey, null)
        if (packed == null) {
            log("load(${provider.wire}): 未配置")
            return null
        }
        val plain = try {
            cipher.decrypt(packed)
        } catch (e: Exception) {
            // 接口契约要求 decrypt 自己吞掉失败；实现方漏吞时这里兜底，绝不让设置页崩。
            log("load(${provider.wire}): 解密抛 ${e.javaClass.simpleName}，按未配置处理")
            null
        }
        if (plain.isNullOrBlank()) {
            // 解不开的密文没有保留价值；留着会让下一次启动继续走失败路径。
            // 只清这一家的键，不碰其他供应商。
            prefs.edit().remove(prefKey).commit()
            log("load(${provider.wire}): 解密失败，已清掉残留密文")
            return null
        }
        log("load(${provider.wire}): 已配置")
        return plain
    }

    override fun save(key: String, provider: ProviderId) {
        // 尾随换行是最高频的真实故障（复制粘贴必带），先 trim 再判空
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            clear(provider)
            return
        }
        val packed = cipher.encrypt(trimmed)
        prefs.edit().putString(prefKeyFor(provider), packed).commit()
        log("save(${provider.wire}): 已加密写入（密文 ${packed.length} 字符）")
    }

    override fun clear(provider: ProviderId) {
        prefs.edit().remove(prefKeyFor(provider)).commit()
        log("clear(${provider.wire}): 已清除本机 Key")
    }

    private fun prefKeyFor(provider: ProviderId): String =
        if (provider == ProviderId.DEEPSEEK) KEY_PACKED_LEGACY else KEY_PACKED_PREFIX + provider.wire

    internal companion object {
        /** DeepSeek 的旧键：Plan 1 起就在用，**永不改名**（改名等于让老用户 Key 静默失效）。 */
        const val KEY_PACKED_LEGACY = "api_key_packed"
        const val KEY_PACKED_PREFIX = "api_key_packed."
        const val TAG = "DKKey"
    }
}
