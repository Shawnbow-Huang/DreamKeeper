package com.dreamkeeper.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.data.security.ApiKeyStore
import com.dreamkeeper.data.settings.GenerationPrefs
import com.dreamkeeper.data.settings.ThemeController
import com.dreamkeeper.llm.ProviderId
import com.dreamkeeper.llm.ProviderRegistry
import com.dreamkeeper.ui.theme.Skin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「模型服务」二级页的状态：五家供应商各自的配置状态 + 当前使用的那一家。
 *
 * 为什么单独拆一个 VM：设置首页只需要一行摘要（当前供应商、配置状态），
 * 供应商列表需要五行的状态点；两处都不需要明文 Key，也不在本地持有任何 Key 草稿。
 * Key 的读写、测试连接留在 [ProviderDetailViewModel]，进入某一家之后才创建。
 */
class ProviderListViewModel(
    private val apiKeyStore: ApiKeyStore,
    private val generationPrefs: GenerationPrefs,
    private val themeController: ThemeController? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** 一行供应商：名称、是否配过 Key、是否是当前使用的那家、当前模型的人话名。 */
    data class Row(
        val provider: ProviderId,
        val configured: Boolean,
        val active: Boolean,
        val modelLabel: String,
    )

    data class UiState(
        val activeProvider: ProviderId = ProviderId.DEEPSEEK,
        val modelLabel: String = "",
        val skin: Skin = Skin.LAMP,
        val rows: List<Row> = emptyList(),
        val loading: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(skin = themeController?.skin?.value ?: Skin.LAMP) }
        refresh()
    }

    /** 重读五家的「有没有 Key」与当前供应商；Key 只需存在性，不读明文。 */
    fun refresh() {
        viewModelScope.launch(io) {
            val options = generationPrefs.options()
            val rows = ProviderId.entries.map { provider ->
                val spec = ProviderRegistry.of(provider)
                val configured = !apiKeyStore.load(provider).isNullOrBlank()
                val modelId = if (provider == options.provider) options.model else spec.defaultModel().id
                Row(
                    provider = provider,
                    configured = configured,
                    active = provider == options.provider,
                    modelLabel = spec.models.firstOrNull { it.id == modelId }?.label ?: modelId,
                )
            }
            _state.update {
                it.copy(
                    activeProvider = options.provider,
                    modelLabel = rows.firstOrNull { r -> r.active }?.modelLabel.orEmpty(),
                    rows = rows,
                    loading = false,
                )
            }
        }
    }

    fun setSkin(skin: Skin) {
        themeController?.set(skin)
        _state.update { it.copy(skin = skin) }
    }

    /** 从详情页回来时调用：可能刚换了供应商或刚存了 Key。 */
    fun onReturnFromDetail() = refresh()
}
