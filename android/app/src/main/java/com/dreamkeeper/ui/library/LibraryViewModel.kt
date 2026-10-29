package com.dreamkeeper.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.domain.Tag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * 列表条目：梦 + 它自己的标签。
 *
 * `tags` 不是可选装饰：`DreamCard(dream, tags, onClick)` 的第二个参数要 `List<Tag>`，
 * 只给 `List<Dream>` 的实现者会被迫传 `emptyList()`，于是「下坠的预感」那张卡的标签
 * 在「全部」页静默消失，和当日列表长得不一样（D62）。
 */
data class LibraryItem(val dream: Dream, val tags: List<Tag>)

data class LibraryUiState(
    val layout: LibraryLayout,
    val items: List<LibraryItem> = emptyList(),
    /**
     * 是否已经真正读过库。`items` 为空有两种含义：「真的还没有梦」与「还没读到」，
     * 只看 `isEmpty()` 会让进入「全部」的第一帧闪一下空态（终审 P1-2）。
     */
    val loaded: Boolean = false,
)

/**
 * 「全部」页：全部未删除的梦，按日期倒序（排序在 DAO 里，`observeAll` 已是
 * `dreamDate DESC, createdAt DESC`），同时提供时间轴 / 卡片流两种排布。
 *
 * 结构照抄 [com.dreamkeeper.ui.day.DayViewModel]：标签用 `observeTagsForDream` 逐条拼，
 * 与当日列表共用同一套数据装配，两个列表才不会对同一条梦给出不同的标签。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repo: DreamRepository,
    private val prefs: LayoutPrefs,
) : ViewModel() {

    /**
     * 偏好初值在构造时同步读一次，之后由 [setLayout] 推。
     * 用 StateFlow 而不是每次直读 `prefs.layout`：切换时要让 Compose 收到重绘信号。
     */
    private val layout = MutableStateFlow(prefs.layout)

    /**
     * `null` = 还没读到；`emptyList()` = 读到了、确实没有梦。
     * 两者在界面上的含义完全相反，所以不在这一层就把空表当成「空态」。
     */
    private val items: Flow<List<LibraryItem>?> = repo.observeAll().flatMapLatest { dreams ->
        if (dreams.isEmpty()) {
            // combine() 不接受空列表，必须走这个分支
            flowOf(emptyList())
        } else {
            combine(dreams.map { repo.observeTagsForDream(it.id) }) { tagLists ->
                dreams.mapIndexed { i, d -> LibraryItem(d, tagLists[i]) }
            }
        }
    }

    /**
     * `WhileSubscribed(5000)` 与 Task 6 一致：离开页面 5 秒后停掉上游查询。
     * 代价是**没有订阅者时 `observeAll()` 不会启动**——单测里必须先起一个收集者，
     * 否则等到超时也只有 initialValue。
     */
    val state: StateFlow<LibraryUiState> = combine(layout, items) { l, list ->
        LibraryUiState(layout = l, items = list.orEmpty(), loaded = list != null)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(layout = prefs.layout),
    )

    /**
     * 切换布局：先落盘（`commit()` 同步写），再更新内存状态。
     *
     * 顺序是有意的：先写盘意味着「界面已经切了但盘上还是旧值」这个窗口不存在；
     * 反过来（先改 state 再写盘）一旦写盘失败，重启后就回退到旧布局，
     * 而用户刚刚明明切过。
     */
    fun setLayout(l: LibraryLayout) {
        prefs.layout = l
        layout.value = l
    }
}
