package com.dreamkeeper.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月视图排布：给定月份与一周的起始日，返回按 7 列排好的格子，`null` 为空白填充格。
 *
 * 纯函数层：只用 java.time 计算，不读时钟，也不依赖任何平台 API。
 */
object DreamCalendar {

    /** 返回月视图格子；null 为空白填充格。长度恒为 7 的倍数。 */
    fun monthGrid(month: YearMonth, weekStart: DayOfWeek = DayOfWeek.MONDAY): List<LocalDate?> {
        val first = month.atDay(1)
        val leading = ((first.dayOfWeek.value - weekStart.value) + 7) % 7
        val cells = mutableListOf<LocalDate?>()
        repeat(leading) { cells.add(null) }
        for (day in 1..month.lengthOfMonth()) cells.add(month.atDay(day))
        while (cells.size % 7 != 0) cells.add(null)
        return cells
    }

    /** 该月第一天与最后一天，供数据库按月份查询时换算区间使用。 */
    fun monthRange(month: YearMonth): Pair<LocalDate, LocalDate> =
        month.atDay(1) to month.atEndOfMonth()
}
