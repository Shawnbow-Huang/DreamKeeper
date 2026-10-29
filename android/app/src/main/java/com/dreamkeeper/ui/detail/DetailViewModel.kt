package com.dreamkeeper.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.domain.MediaAsset
import com.dreamkeeper.domain.Tag
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 详情页状态。
 *
 * [assets] 只是签名占位（Plan 3 才接 `MediaAsset`）；Plan 1 的 ViewModel **不订阅**
 * `observeMediaAssets`，恒为空表——种子里那条 `VIDEO` 记录不该在素材 tab 渲染出来。
 *
 * [loaded] 是对 brief Produces 签名的**末尾追加**：`dream == null` 同时表示「还在读库」与
 * 「这个 id 根本不存在」，只靠它无法区分，界面会在加载的那一帧闪一下「找不到这条梦」，
 * 测试也永远绿（读的是 `stateIn` 的 initialValue）。加一个默认 false 的布尔值把两态分开。
 */
data class DetailUiState(
    val dream: Dream? = null,
    val tags: List<Tag> = emptyList(),
    val assets: List<MediaAsset> = emptyList(),
    val novelChangeCount: Int = 0,
    val loaded: Boolean = false,
)

/**
 * 详情页：把「这一条梦」和「它的标签」两条 Flow 合起来。
 *
 * 标签必须走 `observeTagsForDream`（`tags INNER JOIN dream_tags`）：`Dream` 上没有
 * `tags` 字段，也不该有——详情页不碰 DAO，只认仓库翻译好的 `Tag`。
 */
class DetailViewModel(
    repo: DreamRepository,
    dreamId: String,
) : ViewModel() {

    val state: StateFlow<DetailUiState> = combine(
        repo.observe(dreamId),
        repo.observeTagsForDream(dreamId),
    ) { dream, tags ->
        DetailUiState(
            dream = dream,
            tags = tags,
            assets = emptyList(),
            novelChangeCount = novelChangeCount(dream?.novelChangeLog),
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DetailUiState(),
    )
}
