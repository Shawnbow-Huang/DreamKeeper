package com.dreamkeeper.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.data.security.ApiKeyStore
import com.dreamkeeper.data.security.maskApiKey
import com.dreamkeeper.data.settings.GenerationPrefs
import com.dreamkeeper.llm.GenerationOptions
import com.dreamkeeper.llm.ThinkingLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页状态与 Key 的保存/清除。
 *
 * 三条纪律：
 * 1. **明文只在 [UiState.draft] 里短暂存在**，落盘后立刻清空；[UiState.maskedKey] 只存
 *    `sk-…abcd` 这种掩码，保证截图/日志永远拿不到完整 Key。
 * 2. 所有 [ApiKeyStore] 调用都走 [io]（Keystore 加密 + `commit()` 都是阻塞 IO），
 *    主线程只负责状态更新。
 * 3. 本任务**不校验 Key 可用性**（§0.7：可用性校验留 Task 5），所以文案只能是
 *    「已保存（还没验证）」，不许出现「已验证」。
 */
class SettingsViewModel(
    private val apiKeyStore: ApiKeyStore,
    /** 生成档位（Plan 5 Task 2）：SharedPreferences 读写都是轻量同步操作，不必切 IO。 */
    private val generationPrefs: GenerationPrefs? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    data class UiState(
        /** 本机是否存着一份能解开的 Key */
        val keyConfigured: Boolean = false,
        /** 已配置时给界面看的掩码（`sk-…abcd`），未配置为空串 */
        val maskedKey: String = "",
        /** 输入框里的原始草稿；保存成功后清空 */
        val draft: String = "",
        /** 软提示（长度 < 20 / 不是 sk- 开头），**不阻止保存** */
        val warning: String? = null,
        /** 硬错误（IO/Keystore 异常）；与 [warning] 分开，颜色也不同 */
        val error: String? = null,
        /** IO 进行中，按钮置灰 */
        val busy: Boolean = false,
        /** 当前模型（`deepseek-flash` / `deepseek-v4-pro`） */
        val model: String = GenerationOptions.DEFAULT_MODEL,
        /** 小说化思考档位（faithful 恒关，改稿仍走关闭 + 温度，见 D471） */
        val novelThinking: ThinkingLevel = GenerationOptions.DEFAULT.novelThinking,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // 进页面就去读一次：Task 2 之后 keyConfigured 是真实状态，不再恒为 false
        refresh()
        val options = generationPrefs?.options()
        if (options != null) {
            _state.update { it.copy(model = options.model, novelThinking = options.novelThinking) }
        }
    }

    /** 换模型：白名单外的值由 [GenerationPrefs.setModel] 忽略，状态也不动。 */
    fun setModel(model: String) {
        val prefs = generationPrefs ?: return
        prefs.setModel(model)
        _state.update { it.copy(model = prefs.options().model) }
    }

    /** 换小说化思考档位；忠实整理与改稿不受影响（§0.2 / D471）。 */
    fun setNovelThinking(level: ThinkingLevel) {
        val prefs = generationPrefs ?: return
        prefs.setNovelThinking(level)
        _state.update { it.copy(novelThinking = prefs.options().novelThinking) }
    }

    /** 重读本机存储。解密失败时 store 返回 null，界面自然回到「还没配置」。 */
    fun refresh() {
        viewModelScope.launch(io) {
            val key = apiKeyStore.load()
            _state.update {
                it.copy(
                    keyConfigured = key != null,
                    maskedKey = key?.let(::maskApiKey).orEmpty(),
                    busy = false,
                )
            }
        }
    }

    fun onDraftChange(text: String) {
        _state.update { it.copy(draft = text, warning = warningFor(text), error = null) }
    }

    fun save() {
        val trimmed = _state.value.draft.trim()
        if (trimmed.isEmpty()) {
            // 存到 store 层时空串 = clear，但界面上「点保存」不该悄悄删掉已有 Key
            _state.update { it.copy(error = "先输入 Key 再保存", warning = null) }
            return
        }
        viewModelScope.launch(io) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                apiKeyStore.save(trimmed)
                _state.update {
                    it.copy(
                        keyConfigured = true,
                        maskedKey = maskApiKey(trimmed),
                        draft = "",
                        warning = null,
                        busy = false,
                    )
                }
            } catch (e: Exception) {
                // 异常文本可能带输入片段，只记类名
                _state.update { it.copy(busy = false, error = "保存失败（${e.javaClass.simpleName}）") }
            }
        }
    }

    fun clear() {
        viewModelScope.launch(io) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                apiKeyStore.clear()
                _state.update { UiState() }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "清除失败（${e.javaClass.simpleName}）") }
            }
        }
    }

    /**
     * 只做「像不像一个 Key」的软提示：DeepSeek 之外的中转 Key 不该被拦（§0.6）。
     * 空输入不提示——还没开始输入就红一片很吵。
     */
    private fun warningFor(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val notes = buildList {
            if (!trimmed.startsWith("sk-")) add("通常以 sk- 开头")
            if (trimmed.length < MIN_PLAUSIBLE_LENGTH) add("只有 ${trimmed.length} 位，偏短")
        }
        return if (notes.isEmpty()) null else notes.joinToString("；") + "（仍可保存）"
    }

    private companion object {
        /** 与 `maskApiKey` 的短 Key 阈值一致 */
        const val MIN_PLAUSIBLE_LENGTH = 20
    }
}
