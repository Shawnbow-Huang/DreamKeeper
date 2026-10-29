package com.dreamkeeper.ui.day

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.domain.Tag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.time.LocalDate

data class DayItem(val dream: Dream, val tags: List<Tag>)

data class DayUiState(
    val date: LocalDate,
    val title: String,
    val items: List<DayItem> = emptyList(),
    /**
     * 是否已经真正读过库。`items` 为空有两种含义：「这天确实没有梦」与「还没读到」，
     * 只看 `isEmpty()` 会让进入有梦的日子先闪一下「这天没有梦」（终审 P1-1）。
     */
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModel(
    private val repo: DreamRepository,
    private val date: LocalDate,
) : ViewModel() {

    val state: StateFlow<DayUiState> = repo.observeDay(date)
        .flatMapLatest { dreams ->
            if (dreams.isEmpty()) {
                // combine() 不接受空列表，必须走这个分支
                flowOf(DayUiState(date, titleOf(date), emptyList(), loaded = true))
            } else {
                combine(dreams.map { repo.observeTagsForDream(it.id) }) { tagLists ->
                    DayUiState(
                        date = date,
                        title = titleOf(date),
                        items = dreams.mapIndexed { i, d -> DayItem(d, tagLists[i]) },
                        loaded = true,
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DayUiState(date, titleOf(date)),
        )

    companion object {
        /** 「11月8日 周日」——单字周几，和日历页的极简文案一致 */
        fun titleOf(date: LocalDate): String =
            "${date.monthValue}月${date.dayOfMonth}日 周${"一二三四五六日"[date.dayOfWeek.value - 1]}"
    }
}
