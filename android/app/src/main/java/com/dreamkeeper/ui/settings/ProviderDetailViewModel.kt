package com.dreamkeeper.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.data.security.ApiKeyStore
import com.dreamkeeper.data.security.maskApiKey
import com.dreamkeeper.data.settings.GenerationPrefs
import com.dreamkeeper.ui.theme.Skin
import com.dreamkeeper.data.settings.ThemeController
import com.dreamkeeper.llm.ConnectionOutcome
import com.dreamkeeper.llm.ConnectionTester
import com.dreamkeeper.llm.GenerationOptions
import com.dreamkeeper.llm.ModelProbe
import com.dreamkeeper.llm.ModelSpec
import com.dreamkeeper.llm.ProviderId
import com.dreamkeeper.llm.ProviderRegistry
import com.dreamkeeper.llm.ThinkingLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页状态：供应商、Key 的保存/清除、测试连接、生成档位（Plan 6 Task 4）。
 *
 * 四条纪律：
 * 1. **明文只在 [UiState.draft] 里短暂存在**，落盘后立刻清空；[UiState.maskedKey] 只存
 *    `sk-…abcd` 掩码，截图/日志拿不到完整 Key。
 * 2. Key 的读写都带 [UiState.provider]：五家的 Key 互相隔离（Plan 6 D507）。
 * 3. [ApiKeyStore] 与 [ConnectionTester] 调用都走 [io]（Keystore 加密 + 网络都是阻塞/长耗时）。
 * 4. 「测试连接」只测**已保存的 Key**；测试中的结果回来时若用户已切走（provider 或 nonce 不匹配），
 *    直接丢弃，避免串台（T3 契约）。
 */
class ProviderDetailViewModel(
    private val apiKeyStore: ApiKeyStore,
    /** 生成档位（Plan 5 Task 2）：SharedPreferences 读写都是轻量同步操作，不必切 IO。 */
    private val generationPrefs: GenerationPrefs? = null,
    /** 测试连接（Plan 6 T3）；为 null 时按钮只提示「不可用」，不抛异常。 */
    private val connectionTester: ConnectionTester? = null,
    /** 皮肤（Plan 7）：切一次重组整棵树；为 null 时设置页不显示「外观」入口。 */
    private val themeController: ThemeController? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** 从「模型服务 → 某一家」进来时钉死这一家；为空则沿用设置里保存的当前供应商。 */
    private val initialProvider: ProviderId? = null,
    /** 「检测可用模型」；为空时按钮点了没反应（仅测试装配会为空）。 */
    private val discovery: ModelProbe? = null,
) : ViewModel() {

    /** 一次「测试连接」的界面结果；[nonce] 用于丢弃切走后回来的旧结果。 */
    data class ConnectionUi(
        val provider: ProviderId,
        val nonce: Long,
        val ok: Boolean,
        val latencyMs: Long? = null,
        val httpStatus: Int? = null,
        val model: String? = null,
        val reason: ConnectionOutcome.Reason? = null,
    ) {
        /** 失败原因的人话版本（UI 只读这一行）。 */
        val reasonText: String?
            get() = reason?.let {
                when (it) {
                    ConnectionOutcome.Reason.NO_KEY -> "还没填 Key"
                    ConnectionOutcome.Reason.AUTH -> "Key 不对，或没有这个模型的权限"
                    ConnectionOutcome.Reason.RATE_LIMITED -> "请求太频繁，稍后再试"
                    ConnectionOutcome.Reason.TIMEOUT -> "超时了，检查网络"
                    ConnectionOutcome.Reason.NETWORK -> "连不上，检查网络"
                    ConnectionOutcome.Reason.BAD_REQUEST -> "请求被服务端拒绝了（可能是模型名或参数不对）"
                    ConnectionOutcome.Reason.SERVER -> "服务端出错了，稍后再试"
                    ConnectionOutcome.Reason.BAD_CONTENT -> "服务端回了看不懂的内容"
                    ConnectionOutcome.Reason.UNKNOWN -> "测试没完成，再试一次"
                }
            }
    }

    data class UiState(
        /** 当前供应商 */
        val provider: ProviderId = ProviderId.DEEPSEEK,
        /** 当前皮肤（Plan 7） */
        val skin: Skin = Skin.LAMP,
        /** 当前供应商的模型清单（设置页只摆这一家） */
        val models: List<ModelSpec> = ProviderRegistry.of(ProviderId.DEEPSEEK).models,
        /** 本机是否存着这家能解开的 Key */
        val keyConfigured: Boolean = false,
        /** 已配置时给界面看的掩码（`sk-…abcd`），未配置为空串 */
        val maskedKey: String = "",
        /** 输入框里的原始草稿；保存成功后清空 */
        val draft: String = "",
        /** 软提示（偏短等），**不阻止保存** */
        val warning: String? = null,
        /** 硬错误（IO/Keystore 异常）；与 [warning] 分开，颜色也不同 */
        val error: String? = null,
        /** IO 进行中，按钮置灰 */
        val busy: Boolean = false,
        /** 测试连接进行中 */
        val testing: Boolean = false,
        /** 最近一次测试结果（只属于 [ConnectionUi.provider]） */
        val connection: ConnectionUi? = null,
        /** 小说化模型（各家自己的 id） */
        val model: String = ProviderRegistry.of(ProviderId.DEEPSEEK).defaultModel().id,
        /** 忠实整理模型；默认跟随小说化，可单独选（2026-11-01） */
        val faithfulModel: String = ProviderRegistry.of(ProviderId.DEEPSEEK).defaultModel().id,
        /** 「检测可用模型」进行中 */
        val discovering: Boolean = false,
        /** 上一次检测结果的人话（成功/降级都写这里） */
        val discoveryNote: String? = null,
        /** 小说化思考档位（faithful 恒关，改稿仍走关闭 + 温度，见 D471） */
        val novelThinking: ThinkingLevel = GenerationOptions.DEFAULT.novelThinking,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pendingNonce: Long = 0L

    init {
        _state.update { it.copy(skin = themeController?.skin?.value ?: Skin.LAMP) }
        val options = generationPrefs?.options()
        val picked = initialProvider ?: options?.provider
        if (picked != null) {
            val spec = ProviderRegistry.of(picked)
            val models = modelsFor(picked)
            val sameProvider = options?.provider == picked
            val novel = options?.model?.takeIf { sameProvider && models.any { m -> m.id == it } }
                ?: spec.defaultModel().id
            val faithful = options?.faithfulModel?.takeIf { sameProvider && models.any { m -> m.id == it } }
                ?: novel
            _state.update {
                it.copy(
                    provider = picked,
                    models = models,
                    model = novel,
                    faithfulModel = faithful,
                    novelThinking = options?.novelThinking ?: it.novelThinking,
                )
            }
        }
        refresh()
    }

    /** 内置清单 + 上次检测结果；未知 id 用 id 当标签（服务端说它有，就让它可选）。 */
    private fun modelsFor(provider: ProviderId): List<ModelSpec> {
        val builtin = ProviderRegistry.of(provider).models
        val builtinIds = builtin.map { it.id }.toSet()
        val extra = generationPrefs?.knownModels(provider).orEmpty()
            .filter { it !in builtinIds }
            .map { ModelSpec(id = it, label = it) }
        return builtin + extra
    }

    /** 换供应商：只换 Key 卡与模型清单，不动已保存的任何 Key。 */
    fun selectProvider(provider: ProviderId) {
        if (_state.value.provider == provider) return
        generationPrefs?.setProvider(provider)
        val spec = ProviderRegistry.of(provider)
        val models = modelsFor(provider)
        val model = generationPrefs?.options()?.model?.takeIf { id -> models.any { it.id == id } }
            ?: spec.defaultModel().id
        _state.update {
            it.copy(
                provider = provider,
                models = models,
                model = model,
                faithfulModel = model,
                discovering = false,
                discoveryNote = null,
                draft = "",
                warning = null,
                error = null,
                keyConfigured = false,
                maskedKey = "",
                connection = null,
                testing = false,
            )
        }
        refresh()
    }

    /** 换模型：白名单外的值由 [GenerationPrefs.setModel] 忽略，状态也不动。 */
    /** 换皮肤：立刻写 SharedPreferences 并重组界面（不等 IO）。 */
    fun setSkin(skin: Skin) {
        themeController?.set(skin)
        _state.update { it.copy(skin = skin) }
    }

    fun setModel(model: String) {
        val prefs = generationPrefs ?: return
        prefs.setNovelModel(model)
        _state.update { it.copy(model = prefs.options().model) }
    }

    fun setFaithfulModel(model: String) {
        val prefs = generationPrefs ?: return
        prefs.setFaithfulModel(model)
        _state.update { it.copy(faithfulModel = prefs.options().faithfulModel) }
    }

    /**
     * 请服务端报一次「这个 Key 能用哪些模型」（GET /models）。
     *
     * 失败不当鉴权错误：很多 OpenAI 兼容端点不实现 /models，降级用内置清单并把原因写在
     * [UiState.discoveryNote] 里。成功只扩大候选，不改当前选择。
     */
    fun discoverModels() {
        val discoverer = discovery ?: return
        val provider = _state.value.provider
        if (_state.value.discovering) return
        viewModelScope.launch(io) {
            _state.update { if (it.provider == provider) it.copy(discovering = true, discoveryNote = null) else it }
            val key = apiKeyStore.load(provider)
            val result = discoverer.list(provider, key.orEmpty())
            if (result.ok) generationPrefs?.setDetectedModels(provider, result.models)
            val models = modelsFor(provider)
            val opts = generationPrefs?.options()?.takeIf { it.provider == provider }
            _state.update {
                if (it.provider != provider) it else it.copy(
                    models = models,
                    model = opts?.model ?: it.model,
                    faithfulModel = opts?.faithfulModel ?: it.faithfulModel,
                    discovering = false,
                    discoveryNote = if (result.ok) {
                        "检测到 ${result.models.size} 个可用模型"
                    } else {
                        result.error ?: "检测失败，继续用内置清单"
                    },
                )
            }
        }
    }

    /**
     * 把这一家设为当前供应商。
     *
     * 只写 `GenerationPrefs`：生成链在每次开始时读 options()，不需要重启或通知别处；
     * 返回列表时列表页会重新 refresh()，所以这里不改任何跨页状态。
     */
    fun makeActive() {
        generationPrefs?.setProvider(_state.value.provider)
    }

    /** 换小说化思考档位；忠实整理与改稿不受影响（§0.2 / D471）。 */
    fun setNovelThinking(level: ThinkingLevel) {
        val prefs = generationPrefs ?: return
        prefs.setNovelThinking(level)
        _state.update { it.copy(novelThinking = prefs.options().novelThinking) }
    }

    /** 重读本机存储（当前供应商）。解密失败时 store 返回 null，界面自然回到「还没配置」。 */
    fun refresh() {
        val provider = _state.value.provider
        viewModelScope.launch(io) {
            val key = apiKeyStore.load(provider)
            _state.update {
                // 回来时用户可能已经切走：只更新同 provider 的状态
                if (it.provider != provider) it else it.copy(
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
        val provider = _state.value.provider
        viewModelScope.launch(io) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                apiKeyStore.save(key = trimmed, provider = provider)
                _state.update {
                    if (it.provider != provider) it else it.copy(
                        keyConfigured = true,
                        maskedKey = maskApiKey(trimmed),
                        draft = "",
                        warning = null,
                        busy = false,
                        connection = null,
                    )
                }
            } catch (e: Exception) {
                // 异常文本可能带输入片段，只记类名
                _state.update { it.copy(busy = false, error = "保存失败（${e.javaClass.simpleName}）") }
            }
        }
    }

    fun clear() {
        val provider = _state.value.provider
        viewModelScope.launch(io) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                apiKeyStore.clear(provider)
                _state.update {
                    if (it.provider != provider) it else it.copy(
                        keyConfigured = false,
                        maskedKey = "",
                        draft = "",
                        warning = null,
                        error = null,
                        busy = false,
                        connection = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "清除失败（${e.javaClass.simpleName}）") }
            }
        }
    }

    /**
     * 测试连接：只测已保存的 Key。未配置时本地直接给结论（0 HTTP），
     * 配置了才真的发一次最小请求。
     */
    fun testConnection() {
        val tester = connectionTester ?: return
        val provider = _state.value.provider
        val nonce = tester.nextNonce()
        pendingNonce = nonce
        if (!_state.value.keyConfigured) {
            _state.update {
                it.copy(connection = ConnectionUi(provider, nonce, ok = false, reason = ConnectionOutcome.Reason.NO_KEY))
            }
            return
        }
        _state.update { it.copy(testing = true, connection = null) }
        viewModelScope.launch {
            val outcome = tester.test(provider, nonce)
            _state.update {
                // 切走或又点了一次：旧结果直接丢弃
                if (it.provider != provider || pendingNonce != nonce) return@update it
                when (outcome) {
                    is ConnectionOutcome.Ok -> it.copy(
                        testing = false,
                        connection = ConnectionUi(
                            provider = provider,
                            nonce = nonce,
                            ok = true,
                            latencyMs = outcome.latencyMs,
                            httpStatus = outcome.httpStatus,
                            model = outcome.model,
                        ),
                    )
                    is ConnectionOutcome.Failed -> it.copy(
                        testing = false,
                        connection = ConnectionUi(provider, nonce, ok = false, reason = outcome.reason),
                    )
                }
            }
        }
    }

    /**
     * 只做「像不像一个 Key」的软提示。**不再要求 `sk-` 前缀**（Plan 6 D517）：
     * 五家的 Key 格式不同，前缀断言只会误伤；只剩「偏短」一条。
     * 空输入不提示——还没开始输入就红一片很吵。
     */
    private fun warningFor(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length < MIN_PLAUSIBLE_LENGTH) "只有 ${trimmed.length} 位，偏短（仍可保存）" else null
    }

    private companion object {
        /** 与 `maskApiKey` 的短 Key 阈值一致 */
        const val MIN_PLAUSIBLE_LENGTH = 20
    }
}
