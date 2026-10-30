package com.dreamkeeper.ui.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置页状态。Task 1 只做骨架：Key 存取（Task 2 的 ApiKeyStore）接进来之前，
 * [UiState.keyConfigured] 恒为 false，页面如实显示「还没配置」。
 *
 * 这里**不碰** ApiKeyStore / LlmClient——它们在本任务还不存在；先留一个可替换的状态口，
 * 免得 Task 2 再改页面结构。
 */
class SettingsViewModel : ViewModel() {

    data class UiState(
        /** 是否已在本机安全存储里存过 Key；Task 1 永远是 false */
        val keyConfigured: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
}
