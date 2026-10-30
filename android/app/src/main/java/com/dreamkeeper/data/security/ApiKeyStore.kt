package com.dreamkeeper.data.security

import android.content.SharedPreferences
import android.util.Log

/** API Key 的唯一读写口；界面与后续 LLM 客户端都只依赖这个接口。 */
interface ApiKeyStore {
    /** 已配置则返回明文 Key，否则 `null`；解密失败按未配置处理，不抛。 */
    fun load(): String?

    /** 保存（内部 trim；空串等价 [clear]）。 */
    fun save(key: String)

    /** 清除本机保存的 Key。 */
    fun clear()
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
 * 生产实现：`Base64(iv‖ct)` 存进 SharedPreferences 的**唯一**键 [KEY_PACKED]。
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

    override fun load(): String? {
        val packed = prefs.getString(KEY_PACKED, null)
        if (packed == null) {
            log("load: 未配置")
            return null
        }
        val plain = try {
            cipher.decrypt(packed)
        } catch (e: Exception) {
            // 接口契约要求 decrypt 自己吞掉失败；实现方漏吞时这里兜底，绝不让设置页崩。
            log("load: 解密抛 ${e.javaClass.simpleName}，按未配置处理")
            null
        }
        if (plain.isNullOrBlank()) {
            // 解不开的密文没有保留价值；留着会让下一次启动继续走失败路径。
            prefs.edit().remove(KEY_PACKED).commit()
            log("load: 解密失败，已清掉残留密文")
            return null
        }
        log("load: 已配置")
        return plain
    }

    override fun save(key: String) {
        // 尾随换行是最高频的真实故障（复制粘贴必带），先 trim 再判空
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            clear()
            return
        }
        val packed = cipher.encrypt(trimmed)
        prefs.edit().putString(KEY_PACKED, packed).commit()
        log("save: 已加密写入（密文 ${packed.length} 字符）")
    }

    override fun clear() {
        prefs.edit().remove(KEY_PACKED).commit()
        log("clear: 已清除本机 Key")
    }

    private companion object {
        const val KEY_PACKED = "api_key_packed"
        const val TAG = "DKKey"
    }
}
