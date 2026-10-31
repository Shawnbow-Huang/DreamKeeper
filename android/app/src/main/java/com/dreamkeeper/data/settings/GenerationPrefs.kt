package com.dreamkeeper.data.settings

import android.content.SharedPreferences
import com.dreamkeeper.llm.GenerationOptions
import com.dreamkeeper.llm.ThinkingLevel

/**
 * 生成档位的持久化（Plan 5 Task 2）。
 *
 * 只碰一个 SharedPreferences 文件（`settings`，与排布偏好同一个），**不进 Room**：
 * 档位是 UI 偏好，不是业务数据；为它开一张表会把 v3 迁移、备份、导出全部拖下水。
 *
 * 读的时候一律**清洗**：存储里可能是上个版本写的 `deepseek-v4-pro`、也可能是用户手改
 * apk 后留下的垃圾值。遇到不认识的模型/档位就回默认，绝不让界面崩在一个坏字符串上。
 */
class GenerationPrefs(private val prefs: SharedPreferences) {

    fun options(): GenerationOptions = GenerationOptions(
        model = prefs.getString(KEY_MODEL, null)
            ?.takeIf { it in GenerationOptions.MODELS }
            ?: GenerationOptions.DEFAULT_MODEL,
        novelThinking = prefs.getString(KEY_THINKING, null)
            ?.let { raw -> ThinkingLevel.entries.firstOrNull { it.name == raw } }
            ?: GenerationOptions.DEFAULT.novelThinking,
    )

    /** 只接受白名单里的模型：未知值直接忽略，避免把错模型写进请求体。 */
    fun setModel(model: String) {
        if (model !in GenerationOptions.MODELS) return
        prefs.edit().putString(KEY_MODEL, model).apply()
    }

    fun setNovelThinking(level: ThinkingLevel) {
        prefs.edit().putString(KEY_THINKING, level.name).apply()
    }

    companion object {
        const val PREFS_NAME = "settings"
        const val KEY_MODEL = "dk_generation_model"
        const val KEY_THINKING = "dk_generation_thinking"
    }
}
