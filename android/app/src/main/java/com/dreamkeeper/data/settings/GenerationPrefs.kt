package com.dreamkeeper.data.settings

import android.content.SharedPreferences
import com.dreamkeeper.llm.GenerationOptions
import com.dreamkeeper.llm.ProviderId
import com.dreamkeeper.llm.ProviderRegistry
import com.dreamkeeper.llm.ThinkingLevel

/**
 * 生成档位的持久化（Plan 5 Task 2；Plan 6 扩多供应商；2026-11-01 拆双模型）。
 *
 * 只碰一个 SharedPreferences 文件（`settings`，与排布偏好同一个），**不进 Room**：
 * 档位是 UI 偏好，不是业务数据；为它开一张表会把 v3 迁移、备份、导出全部拖下水。
 *
 * 读的时候一律**清洗**：存储里可能是上个版本写的 `deepseek-v4-pro`、也可能是用户手改
 * apk 后留下的垃圾值。遇到不认识的供应商/模型/档位就回默认，绝不让界面崩在一个坏字符串上。
 *
 * 键：
 * - `dk_generation_provider`：当前供应商 wire 名；
 * - `dk_generation_model`：**DeepSeek 小说化模型遗留键**（Plan 5 写入，升级后必须还能读，D509）；
 * - `dk_generation_model.<wire>`：其余供应商的小说化模型；
 * - `dk_generation_faithful[.<wire>]`：忠实整理模型（2026-11-01 新增，默认跟随小说化）；
 * - `dk_generation_models.<wire>`：上一次「检测可用模型」的结果（逗号分隔，只作候选；
 *   不覆盖当前选择，也不参与清洗期的白名单判定之外的事）；
 * - `dk_generation_thinking`：小说化思考档位（与供应商无关）；
 * - `dk_generation_faithful_thinking`：原样整理思考档位（与供应商无关，默认低）。
 */
class GenerationPrefs(private val prefs: SharedPreferences) {

    fun options(): GenerationOptions {
        val provider = provider()
        val known = knownModels(provider)
        val novel = prefs.getString(modelKey(provider, Mode.NOVEL), null)
            ?.takeIf { it in known }
            ?: ProviderRegistry.of(provider).defaultModel().id
        val faithful = prefs.getString(modelKey(provider, Mode.FAITHFUL), null)
            ?.takeIf { it in known }
            ?: novel
        val thinking = prefs.getString(KEY_THINKING, null)
            ?.let { raw -> ThinkingLevel.entries.firstOrNull { it.name == raw } }
            ?: GenerationOptions.DEFAULT.novelThinking
        val faithfulThinking = prefs.getString(KEY_FAITHFUL_THINKING, null)
            ?.let { raw -> ThinkingLevel.entries.firstOrNull { it.name == raw } }
            ?: GenerationOptions.DEFAULT.faithfulThinking
        return GenerationOptions(
            provider = provider,
            model = novel,
            faithfulModel = faithful,
            novelThinking = thinking,
            faithfulThinking = faithfulThinking,
        )
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

    /** 内置清单 + 上一次检测到的模型；未知值不进候选。 */
    fun knownModels(provider: ProviderId): List<String> {
        val builtin = ProviderRegistry.of(provider).models.map { it.id }
        return (builtin + detectedModels(provider)).distinct()
    }

    /** 上一次「检测可用模型」的结果（可能为空）。 */
    fun detectedModels(provider: ProviderId): List<String> =
        prefs.getString(detectedKey(provider), null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /**
     * 保存检测结果。
     *
     * 不校验白名单：这些 id 是服务端刚返回的，比内置清单新；但也**不改当前选择**——
     * 点「检测」只是想知道有什么，选不选是下一步。
     */
    fun setDetectedModels(provider: ProviderId, models: List<String>) {
        val clean = models.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
        prefs.edit().putString(detectedKey(provider), clean).apply()
    }

    /** 小说化模型（兼容旧调用名）。 */
    fun setModel(model: String) = setNovelModel(model)

    fun setNovelModel(model: String) {
        val provider = provider()
        if (model !in knownModels(provider)) return
        prefs.edit().putString(modelKey(provider, Mode.NOVEL), model).apply()
    }

    fun setFaithfulModel(model: String) {
        val provider = provider()
        if (model !in knownModels(provider)) return
        prefs.edit().putString(modelKey(provider, Mode.FAITHFUL), model).apply()
    }

    fun setNovelThinking(level: ThinkingLevel) {
        prefs.edit().putString(KEY_THINKING, level.name).apply()
    }

    fun setFaithfulThinking(level: ThinkingLevel) {
        prefs.edit().putString(KEY_FAITHFUL_THINKING, level.name).apply()
    }

    private fun modelKey(provider: ProviderId, mode: Mode): String {
        val base = if (mode == Mode.FAITHFUL) KEY_FAITHFUL else KEY_MODEL
        // DeepSeek 的小说化沿用 Plan 5 的旧键（零迁移）；其余一律带 wire 后缀。
        return when {
            provider != ProviderId.DEEPSEEK -> "$base.${provider.wire}"
            mode == Mode.FAITHFUL -> "$base.deepseek"
            else -> base
        }
    }

    private fun detectedKey(provider: ProviderId): String = "$KEY_DETECTED.${provider.wire}"

    private enum class Mode { NOVEL, FAITHFUL }

    companion object {
        const val PREFS_NAME = "settings"
        const val KEY_PROVIDER = "dk_generation_provider"
        const val KEY_MODEL = "dk_generation_model"
        const val KEY_FAITHFUL = "dk_generation_faithful"
        const val KEY_DETECTED = "dk_generation_models"
        const val KEY_THINKING = "dk_generation_thinking"
        const val KEY_FAITHFUL_THINKING = "dk_generation_faithful_thinking"
    }
}
