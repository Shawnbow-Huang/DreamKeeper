package com.dreamkeeper.data.settings

import android.content.SharedPreferences
import com.dreamkeeper.llm.GenerationOptions
import com.dreamkeeper.llm.ProviderId
import com.dreamkeeper.llm.ProviderRegistry
import com.dreamkeeper.llm.ThinkingLevel

/**
 * 生成档位的持久化（Plan 5 Task 2，Plan 6 扩到多供应商）。
 *
 * 只碰一个 SharedPreferences 文件（`settings`，与排布偏好同一个），**不进 Room**：
 * 档位是 UI 偏好，不是业务数据；为它开一张表会把 v3 迁移、备份、导出全部拖下水。
 *
 * 读的时候一律**清洗**：存储里可能是上个版本写的 `deepseek-v4-pro`、也可能是用户手改
 * apk 后留下的垃圾值。遇到不认识的供应商/模型/档位就回默认，绝不让界面崩在一个坏字符串上。
 *
 * 键：
 * - `dk_generation_provider`：当前供应商 wire 名；
 * - `dk_generation_model`：**DeepSeek 遗留键**（Plan 5 写入，升级后必须还能读，D509）；
 * - `dk_generation_model.<wire>`：其余供应商各自的模型记忆；
 * - `dk_generation_thinking`：小说化思考档位（与供应商无关）。
 */
class GenerationPrefs(private val prefs: SharedPreferences) {

    fun options(): GenerationOptions {
        val provider = provider()
        val model = prefs.getString(modelKey(provider), null)
            ?.takeIf { id -> ProviderRegistry.of(provider).models.any { it.id == id } }
            ?: ProviderRegistry.of(provider).defaultModel().id
        val thinking = prefs.getString(KEY_THINKING, null)
            ?.let { raw -> ThinkingLevel.entries.firstOrNull { it.name == raw } }
            ?: GenerationOptions.DEFAULT.novelThinking
        return GenerationOptions(provider = provider, model = model, novelThinking = thinking)
    }

    fun provider(): ProviderId = prefs.getString(KEY_PROVIDER, null)
        ?.let { wire -> ProviderId.entries.firstOrNull { it.wire == wire } }
        ?: ProviderId.DEEPSEEK

    /**
     * 换供应商：只写供应商名，不动各家的模型记忆；
     * 若这家从没选过模型，[options] 会回落到它的默认模型。
     */
    fun setProvider(provider: ProviderId) {
        prefs.edit().putString(KEY_PROVIDER, provider.wire).apply()
    }

    /** 只接受当前供应商白名单里的模型：未知值直接忽略，避免把错模型写进请求体。 */
    fun setModel(model: String) {
        val provider = provider()
        if (ProviderRegistry.of(provider).models.none { it.id == model }) return
        prefs.edit().putString(modelKey(provider), model).apply()
    }

    fun setNovelThinking(level: ThinkingLevel) {
        prefs.edit().putString(KEY_THINKING, level.name).apply()
    }

    private fun modelKey(provider: ProviderId): String =
        if (provider == ProviderId.DEEPSEEK) KEY_MODEL else "$KEY_MODEL.${provider.wire}"

    companion object {
        const val PREFS_NAME = "settings"
        const val KEY_PROVIDER = "dk_generation_provider"
        const val KEY_MODEL = "dk_generation_model"
        const val KEY_THINKING = "dk_generation_thinking"
    }
}
