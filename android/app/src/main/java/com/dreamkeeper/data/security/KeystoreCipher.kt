package com.dreamkeeper.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Key 的加解密口。
 *
 * 做成接口的唯一原因是**可测性**：Robolectric 的 android-all jar 里没有任何
 * KeyStore / KeyGenerator / Cipher 的 shadow，真 Keystore 在 JVM 上必抛 `KeyStoreException`。
 * 接口化之后，`KeystoreApiKeyStore` 的「单值持久化 + 容错 + trim」全部能在 JVM 上验，
 * 真加密壳 [AndroidKeystoreCipher] 由设备侧证据（prefs 里只有 Base64(iv‖ct)）背书。
 */
interface KeystoreCipher {

    /** 加密并返回「Base64(iv‖ct)」；每次调用都必须生成新的随机 IV。 */
    fun encrypt(plain: String): String

    /**
     * 解密 [encrypt] 的产物。任何失败（AEADBadTag / GeneralSecurity / 坏 base64）都
     * **不抛**，返回 `null`，由调用方只清自己那一条 pref 并当未配置处理。
     *
     * **绝不删 Keystore 别名**：别名是五家供应商共用的，删一次会让所有已保存的 Key 一起失效。
     */
    fun decrypt(packed: String): String?
}

/**
 * 真实现：AndroidKeyStore 里的 AES-256/GCM/NoPadding。
 *
 * 三条刻意不改的取舍（brief §0.3）：
 * 1. 别名固定 `dreamkeeper.api.key`，**不加版本号**——改名等于让老用户的 Key 静默失效；
 * 2. **不设** `setUserAuthenticationRequired`：后续生成任务跑在 `generationScope`，用户不在场时
 *    会抛 `UserNotAuthenticatedException`，这是明示取舍（设计 §9）；
 * 3. **不关** `setRandomizedEncryptionRequired`：IV 由 Keystore 每次随机生成，加密时不传
 *    `GCMParameterSpec`，落盘时把 `cipher.iv` 与密文拼成同一个字符串。
 */
class AndroidKeystoreCipher : KeystoreCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // 第二个参数只给 key：不传 GCMParameterSpec，IV 交给 Keystore 随机生成
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // iv‖ct 拼成一个 Base64 值，NO_WRAP（带换行会让 prefs 里的值被 XML 转义成多行）
        return Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
    }

    override fun decrypt(packed: String): String? = try {
        val packedBytes = Base64.decode(packed, Base64.NO_WRAP)
        // GCM 的 IV 固定 12 字节（`setRandomizedEncryptionRequired` 下手动 IV 长度不可配），
        // 所以「Base64(iv‖ct)」不需要在值里再存长度；短于 IV 的值直接当坏数据。
        require(packedBytes.size > GCM_IV_BYTES) { "packed 太短，连 IV 都不够" }
        val iv = packedBytes.copyOfRange(0, GCM_IV_BYTES)
        val cipherText = packedBytes.copyOfRange(GCM_IV_BYTES, packedBytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    } catch (e: Exception) {
        // AEADBadTag / GeneralSecurity / UnrecoverableKey / Provider / 坏 base64 一律
        // 当「这份密文不可用」：**只返回 null**，不动别名——
        // 别名被五家供应商共用，删掉会让其他家的 Key 一起失效（D495）。
        // 由 KeystoreApiKeyStore 只清坏掉的那一条 pref。
        // 只记异常类名——异常文本可能带输入片段，不进日志。
        Log.w(TAG, "解密失败（保留别名）：${e.javaClass.simpleName}")
        null
    }

    /** 取既有别名；没有就按 §0.3 的参数生成一把。Keystore 里的 key 不可导出，只能这样拿。 */
    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }


    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "dreamkeeper.api.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val TAG = "DKKey"
    }
}
