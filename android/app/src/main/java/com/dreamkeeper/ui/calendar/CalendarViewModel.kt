package com.dreamkeeper.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.domain.DreamCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class CalendarUiState(
    val month: YearMonth,
    val title: String,
    val grid: List<LocalDate?>,
    val daysWithDreams: Map<LocalDate, Int>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val repo: DreamRepository,
    initialMonth: YearMonth = YearMonth.now(),
) : ViewModel() {

    private val month = MutableStateFlow(initialMonth)

    val state: StateFlow<CalendarUiState> = month
        .flatMapLatest { m ->
            repo.observeMonth(m).map { dreams ->
                CalendarUiState(
                    month = m,
                    title = "${m.year}年${m.monthValue}月",
                    grid = DreamCalendar.monthGrid(m),
                    daysWithDreams = dreams.groupingBy { it.date }.eachCount(),
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CalendarUiState(
                month = initialMonth,
                title = "${initialMonth.year}年${initialMonth.monthValue}月",
                grid = DreamCalendar.monthGrid(initialMonth),
                daysWithDreams = emptyMap(),
            ),
        )

    fun nextMonth() { month.value = month.value.plusMonths(1) }
    fun prevMonth() { month.value = month.value.minusMonths(1) }
    fun goToday() { month.value = YearMonth.now() }
}
