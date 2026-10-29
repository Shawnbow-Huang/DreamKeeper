package com.dreamkeeper.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 日历月视图排布的唯一一份定义，用断言把它钉死。
 *
 * 夹具（周一起算；「格数」= 整月天数 + 前导空白 + 尾部空白）：
 *
 * | 月份    | 1 号 | 天数 | 前导空白 | 格数 | 尾部空白 | 备注             |
 * |---------|------|------|----------|------|----------|------------------|
 * | 2025-12 | 周一 | 31   | 0        | 35   | 4        | 跨年上界         |
 * | 2026-01 | 周四 | 31   | 3        | 35   | 1        | 跨年下界         |
 * | 2026-02 | 周日 | 28   | 6        | 35   | 1        | 平年 2 月        |
 * | 2026-03 | 周日 | 31   | 6        | 42   | 5        | 6 行月           |
 * | 2026-06 | 周一 | 30   | 0        | 35   | 5        | 前导 0           |
 * | 2026-10 | 周四 | 31   | 3        | 35   | 1        | 周日起算前导 4   |
 * | 2026-11 | 周日 | 30   | 6        | 42   | 6        | 前导 6 且 6 行   |
 * | 2021-02 | 周一 | 28   | 0        | 28   | 0        | 恰好 4 行,无空白 |
 * | 2024-02 | 周四 | 29   | 3        | 35   | 3        | 闰年 2 月        |
 * | 2027-01 | 周五 | 31   | 4        | 35   | 0        | 尾部 0           |
 *
 * 除任务书要求的 5 条,额外覆盖:跨年、闰年/平年/世纪闰年、前导 0 / 前导 6、尾部 0、
 * 恰好 4 行、6 行月、全长恒为 7 的倍数、空白格只能是 null 且只在两端、
 * 非周一起算时每个日期都必须落在自己的星期列,以及 monthRange 作为 DAO 查询窗口的语义。
 */
class DreamCalendarTest {

    // ---------------------------------------------------------------- 任务书要求的 5 条

    @Test
    fun `month starting on monday has no leading blanks`() {
        // 2026-06-01 是周一,6 月 30 天 -> 前导空白必须是 0(不是占位格)
        val month = YearMonth.of(2026, 6)
        assertEquals(30, month.lengthOfMonth())
        val grid = DreamCalendar.monthGrid(month)

        assertEquals("月首是周一时,第 0 格必须直接是 1 号", LocalDate.of(2026, 6, 1), grid.first())
        assertEquals("30 天 + 前导 0 + 尾部 5 = 35", 35, grid.size)
        assertTrue("前 30 格必须全是日期", grid.take(30).all { it != null })
        assertTrue("最后 5 格必须是空白", grid.drop(30).all { it == null })
        assertEquals(0, grid.size % 7)
        assertMonthInvariants(month, grid)
    }

    @Test
    fun `leading blanks align first day under its weekday column`() {
        // 2026-10-01 是周四 -> 周一起算时前面有 3 个空
        val grid = DreamCalendar.monthGrid(YearMonth.of(2026, 10))
        assertNull(grid[0]); assertNull(grid[1]); assertNull(grid[2])
        assertEquals(LocalDate.of(2026, 10, 1), grid[3])
    }

    @Test
    fun `every real date appears exactly once in order`() {
        val month = YearMonth.of(2026, 2)
        val dates = DreamCalendar.monthGrid(month).filterNotNull()
        assertEquals(month.lengthOfMonth(), dates.size)
        assertEquals(dates, dates.sorted())
        assertEquals(LocalDate.of(2026, 2, 1), dates.first())
        assertEquals(LocalDate.of(2026, 2, 28), dates.last())
    }

    @Test
    fun `week start is configurable`() {
        val grid = DreamCalendar.monthGrid(YearMonth.of(2026, 10), DayOfWeek.SUNDAY)
        // 2026-10-01 周四,周日起算 -> 前面空 4 格
        assertEquals(LocalDate.of(2026, 10, 1), grid[4])
    }

    @Test
    fun `grid is always a multiple of seven`() {
        for (m in 1..12) {
            val g = DreamCalendar.monthGrid(YearMonth.of(2026, m))
            assertTrue("month $m size=${g.size}", g.size % 7 == 0)
        }
    }

    // ---------------------------------------------------------------- 前导空白边界

    @Test
    fun `month starting on monday fills columns from cell zero`() {
        // 2025-12-01 是周一且 31 天 -> 35 格,前 31 格是日期,尾部 4 空
        val month = YearMonth.of(2025, 12)
        val grid = DreamCalendar.monthGrid(month)
        assertEquals(35, grid.size)
        assertEquals(LocalDate.of(2025, 12, 1), grid[0])
        assertEquals(LocalDate.of(2025, 12, 31), grid[30])
        assertTrue(grid.drop(31).all { it == null })
        assertEquals(4, grid.count { it == null })
    }

    @Test
    fun `month starting on sunday gets six leading blanks`() {
        // 2026-11-01 是周日 -> 周一起算时前面整 6 格空白,30 天 => 6 行 42 格
        val month = YearMonth.of(2026, 11)
        val grid = DreamCalendar.monthGrid(month)
        assertEquals(42, grid.size)
        for (i in 0..5) assertNull("第 $i 格应是空白,而不是占位日期", grid[i])
        assertEquals(LocalDate.of(2026, 11, 1), grid[6])
        assertEquals(LocalDate.of(2026, 11, 30), grid[35])
        assertTrue(grid.drop(36).all { it == null })
        assertEquals(6 + 6, grid.count { it == null })
    }

    @Test
    fun `a month starting on sunday needs six rows`() {
        // 2026-03-01 是周日,31 天 -> 6 + 31 = 37 -> 42 格
        val month = YearMonth.of(2026, 3)
        val grid = DreamCalendar.monthGrid(month)
        assertEquals(42, grid.size)
        assertTrue(grid.take(6).all { it == null })
        assertEquals(LocalDate.of(2026, 3, 1), grid[6])
        assertEquals(LocalDate.of(2026, 3, 31), grid[36])
        assertTrue(grid.drop(37).all { it == null })
        assertEquals(6 + 5, grid.count { it == null })
    }

    @Test
    fun `month ending on sunday needs no trailing blanks`() {
        // 2027-01-01 是周五,31 天 -> 4 + 31 = 35,正好整除 7
        val month = YearMonth.of(2027, 1)
        val grid = DreamCalendar.monthGrid(month)
        assertEquals(35, grid.size)
        assertTrue(grid.take(4).all { it == null })
        assertEquals(LocalDate.of(2027, 1, 1), grid[4])
        assertEquals(LocalDate.of(2027, 1, 31), grid.last())
        assertEquals(4, grid.count { it == null })
    }

    @Test
    fun `a 28 day february starting on monday is exactly four rows without blanks`() {
        // 2021-02-01 是周一,28 天 -> 28 格,一个空白都没有
        val month = YearMonth.of(2021, 2)
        val grid = DreamCalendar.monthGrid(month)
        assertEquals(28, grid.size)
        assertEquals("整月不该出现任何空白格", 0, grid.count { it == null })
        assertEquals((1..28).map { LocalDate.of(2021, 2, it) }, grid)
    }

    // ---------------------------------------------------------------- 2 月 / 闰年

    @Test
    fun `february 2024 is a leap february with 29 days`() {
        val month = YearMonth.of(2024, 2)
        assertEquals(29, month.lengthOfMonth())
        val grid = DreamCalendar.monthGrid(month)
        assertEquals(35, grid.size)                       // 3 + 29 + 3
        assertTrue("前 3 格是空白", grid.take(3).all { it == null })
        assertEquals(LocalDate.of(2024, 2, 1), grid[3])
        assertEquals(LocalDate.of(2024, 2, 29), grid[31])
        assertTrue("最后 3 格是空白", grid.takeLast(3).all { it == null })
        assertEquals(29, grid.filterNotNull().size)
        assertEquals(3 + 3, grid.count { it == null })
    }

    @Test
    fun `february 2026 is a common february with 28 days`() {
        val month = YearMonth.of(2026, 2)
        assertEquals(28, month.lengthOfMonth())
        val grid = DreamCalendar.monthGrid(month)
        assertEquals(35, grid.size)                       // 6 + 28 + 1
        assertTrue(grid.take(6).all { it == null })
        assertEquals(LocalDate.of(2026, 2, 1), grid[6])
        assertEquals(LocalDate.of(2026, 2, 28), grid[33])
        assertEquals(28, grid.filterNotNull().size)
    }

    @Test
    fun `century leap rules come from java time not hand rolled arithmetic`() {
        // 2000 是 400 的倍数 -> 闰年;2100 不是 -> 平年。手写 `%4` 会在这里挂掉。
        val g2000 = DreamCalendar.monthGrid(YearMonth.of(2000, 2))
        assertEquals(29, g2000.filterNotNull().size)
        assertEquals(LocalDate.of(2000, 2, 29), g2000.filterNotNull().last())

        val g2100 = DreamCalendar.monthGrid(YearMonth.of(2100, 2))
        assertEquals(28, g2100.filterNotNull().size)
        assertEquals(LocalDate.of(2100, 2, 28), g2100.filterNotNull().last())
        assertEquals("2100-02-01 是周一 + 28 天,恰好 28 格", 28, g2100.size)
    }

    // ---------------------------------------------------------------- 跨年

    @Test
    fun `december and january never borrow each other's dates`() {
        val dec = DreamCalendar.monthGrid(YearMonth.of(2025, 12))
        val jan = DreamCalendar.monthGrid(YearMonth.of(2026, 1))

        assertEquals(35, dec.size)
        assertEquals(35, jan.size)
        assertEquals(LocalDate.of(2025, 12, 1), dec.first())
        assertEquals(LocalDate.of(2025, 12, 31), dec[30])
        assertEquals(LocalDate.of(2026, 1, 1), jan[3])
        assertEquals(LocalDate.of(2026, 1, 31), jan[33])   // 3 + 31 = 34,尾部还留 1 格空白

        assertTrue("12 月的格子里不许出现 2026 年的日期", dec.filterNotNull().all { it.year == 2025 })
        assertTrue("1 月的格子里不许出现 2025 年的日期", jan.filterNotNull().all { it.year == 2026 })
        assertMonthInvariants(YearMonth.of(2025, 12), dec)
        assertMonthInvariants(YearMonth.of(2026, 1), jan)
    }

    // ---------------------------------------------------------------- 不变量(全量扫描)

    @Test
    fun `grid length is always a multiple of seven and fits four to six rows`() {
        for (year in 2020..2035) for (m in 1..12) for (weekStart in DayOfWeek.values()) {
            val month = YearMonth.of(year, m)
            val size = DreamCalendar.monthGrid(month, weekStart).size
            val tag = "$month/$weekStart"
            assertTrue("$tag 长度 $size 不是 7 的倍数", size % 7 == 0)
            assertTrue("$tag 长度 $size 装不下 ${month.lengthOfMonth()} 天", size >= month.lengthOfMonth())
            assertTrue("$tag 长度 $size 超出 4~6 行(28~42)", size in 28..42)
        }
    }

    @Test
    fun `every date sits under its own weekday column for all week starts`() {
        for (month in sweepMonths) for (weekStart in DayOfWeek.values()) {
            DreamCalendar.monthGrid(month, weekStart).forEachIndexed { index, date ->
                if (date == null) return@forEachIndexed
                val expectedColumn = (date.dayOfWeek.value - weekStart.value + 7) % 7
                assertEquals(
                    "$month/$weekStart:$date 应落在第 $expectedColumn 列,实际第 ${index % 7} 列(index=$index)",
                    expectedColumn,
                    index % 7,
                )
            }
        }
    }

    @Test
    fun `blank cells are null and only pad the two ends`() {
        for (month in sweepMonths) for (weekStart in DayOfWeek.values()) {
            val grid = DreamCalendar.monthGrid(month, weekStart)
            val tag = "$month/$weekStart"
            val firstDate = grid.indexOfFirst { it != null }
            val lastDate = grid.indexOfLast { it != null }

            assertEquals("$tag 第一格必须是当月 1 号", month.atDay(1), grid[firstDate])
            assertEquals("$tag 最后一格必须是当月末日", month.atEndOfMonth(), grid[lastDate])
            for (i in firstDate..lastDate) {
                assertTrue("$tag 第 $i 格夹在日期中间,不该是空白", grid[i] != null)
            }
            assertEquals(
                "$tag 空白数应为前导 + 尾部",
                grid.size - (lastDate - firstDate + 1),
                grid.count { it == null },
            )
            assertEquals(
                "$tag 日期数必须正好等于当月天数(没有占位日期)",
                month.lengthOfMonth(),
                grid.filterNotNull().size,
            )
        }
    }

    @Test
    fun `default week start is monday`() {
        for (month in sweepMonths) {
            assertEquals(
                "$month 不传 weekStart 时必须等价于显式传 MONDAY",
                DreamCalendar.monthGrid(month, DayOfWeek.MONDAY),
                DreamCalendar.monthGrid(month),
            )
        }
    }

    // ---------------------------------------------------------------- monthRange(DAO 查询窗口)

    @Test
    fun `month range is the first and the last day of the month`() {
        assertEquals(
            LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 31),
            DreamCalendar.monthRange(YearMonth.of(2026, 10)),
        )
        assertEquals(
            LocalDate.of(2026, 6, 1) to LocalDate.of(2026, 6, 30),
            DreamCalendar.monthRange(YearMonth.of(2026, 6)),
        )
    }

    @Test
    fun `month range knows its february`() {
        assertEquals(LocalDate.of(2024, 2, 29), DreamCalendar.monthRange(YearMonth.of(2024, 2)).second)
        assertEquals(LocalDate.of(2026, 2, 28), DreamCalendar.monthRange(YearMonth.of(2026, 2)).second)
        assertEquals(LocalDate.of(2000, 2, 29), DreamCalendar.monthRange(YearMonth.of(2000, 2)).second)
        assertEquals(LocalDate.of(2100, 2, 28), DreamCalendar.monthRange(YearMonth.of(2100, 2)).second)
    }

    @Test
    fun `month range crosses the year boundary without borrowing a day`() {
        val (decFirst, decLast) = DreamCalendar.monthRange(YearMonth.of(2025, 12))
        val (janFirst, janLast) = DreamCalendar.monthRange(YearMonth.of(2026, 1))

        assertEquals(LocalDate.of(2025, 12, 1), decFirst)
        assertEquals(LocalDate.of(2025, 12, 31), decLast)
        assertEquals(LocalDate.of(2026, 1, 1), janFirst)
        assertEquals(LocalDate.of(2026, 1, 31), janLast)
        assertEquals("12 月末日的下一天就是 1 月 1 日", janFirst, decLast.plusDays(1))
    }

    @Test
    fun `month range is a half open epoch day window for dao queries`() {
        for (year in 2020..2035) for (m in 1..12) {
            val month = YearMonth.of(year, m)
            val (first, last) = DreamCalendar.monthRange(month)
            val start = first.toEpochDay()
            val endExclusive = last.toEpochDay() + 1
            val window = start until endExclusive

            assertEquals("$month 窗口天数", month.lengthOfMonth().toLong(), endExclusive - start)
            assertTrue("$month 上月末日不该落进窗口", first.minusDays(1).toEpochDay() !in window)
            assertTrue("$month 下月首日不该落进窗口", last.plusDays(1).toEpochDay() !in window)

            val gridDates = DreamCalendar.monthGrid(month).filterNotNull()
            assertEquals(
                "$month 月视图里的每一天都必须在查询窗口内",
                gridDates.size,
                gridDates.count { it.toEpochDay() in window },
            )
        }
    }

    // ---------------------------------------------------------------- 纯函数约束

    @Test
    fun `domain source imports only java time and never touches a clock`() {
        val source = domainSourceFile().readText()
        val imports = source.lines().map { it.trim() }.filter { it.startsWith("import ") }

        assertTrue("domain 层必须只依赖 java.time", imports.isNotEmpty())
        imports.forEach { line ->
            assertTrue("domain 层只允许 import java.time.*,发现:$line", line.startsWith("import java.time."))
        }
        listOf("android", "now(", "System.", "java.util", "Locale").forEach { forbidden ->
            assertTrue(
                "domain 层不得出现 `$forbidden`(纯函数,不读时钟、不碰平台 API)",
                !source.contains(forbidden, ignoreCase = true),
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    private val sweepMonths: List<YearMonth> =
        (2024..2027).flatMap { year -> (1..12).map { m -> YearMonth.of(year, m) } }

    private fun assertMonthInvariants(month: YearMonth, grid: List<LocalDate?>) {
        val dates = grid.filterNotNull()
        val tag = "$month 网格"
        assertEquals("$tag 日期数", month.lengthOfMonth(), dates.size)
        assertEquals("$tag 不许有重复日期", dates.size, dates.toSet().size)
        assertEquals("$tag 日期必须严格递增", dates.sorted(), dates)
        assertTrue(
            "$tag 混入了别的月份:${dates.firstOrNull { YearMonth.from(it) != month }}",
            dates.all { YearMonth.from(it) == month },
        )
        assertEquals("$tag 长度必须是 7 的倍数", 0, grid.size % 7)
        assertTrue("$tag 长度装不下整月", grid.size >= month.lengthOfMonth())
    }

    /** 从测试工作目录向上找到 main 源码,用于断言 domain 层的依赖是干净的。 */
    private fun domainSourceFile(): File {
        val relative = "com/dreamkeeper/domain/DreamCalendar.kt"
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (prefix in listOf("src/main/java/", "app/src/main/java/")) {
                val candidate = File(dir, prefix + relative)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 DreamCalendar.kt,user.dir=${System.getProperty("user.dir")}")
    }
}
