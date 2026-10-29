package com.dreamkeeper.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.domain.Dream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * 搜索页：250ms 防抖 + 最近一次输入优先。
 *
 * 手打中文时每个字都会触发一次 `onQueryChange`，不防抖就是每个字查一次库；
 * `mapLatest` 保证旧查询的结果晚到时不会覆盖新查询的结果（`flatMapLatest` 在这里编译不过：
 * `repo.search` 是 `suspend fun`，返回 `List<Dream>` 而不是 `Flow`，见 brief 0.1）。
 *
 * [dispatcher] 必须能从构造参数换：防抖链上的 `flowOn(dispatcher)` 决定那 250ms 跑在哪。
 * 内部写死 `Dispatchers.IO` 的话，单测的虚拟时钟推不动它（brief 第 74 行），
 * `advanceTimeBy(300)` 推了个寂寞——这不是测试写得不对，是生产代码不可测。
 */
class SearchViewModel(
    repo: DreamRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialQuery: String = "",
) : ViewModel() {

    private val _query = MutableStateFlow(initialQuery)

    /** 输入框当前内容。界面用它画光标/占位文案，结果只从 [results] 来。 */
    val query: StateFlow<String> = _query.asStateFlow()

    // debounce 在 coroutines 1.10.2 仍是 @FlowPreview；mapLatest 是 @ExperimentalCoroutinesApi
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val results: StateFlow<List<Dream>> = _query
        .debounce(250)
        // 空查询不是「搜全部」：repo.search("") 本身也返回空表，这里提前短路省一次查库
        .mapLatest { q -> if (q.isBlank()) emptyList() else repo.search(q.trim()) }
        .flowOn(dispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun onQueryChange(q: String) {
        _query.value = q
    }
}
