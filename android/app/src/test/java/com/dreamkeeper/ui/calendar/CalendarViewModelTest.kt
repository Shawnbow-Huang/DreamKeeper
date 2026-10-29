package com.dreamkeeper.ui.calendar

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.domain.Dream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DreamDatabase::class.java)
            // Room 的查询/事务都在**它自己的 executor** 上跑，不在 `Dispatchers.Main` 上（D53）：
            // 不把这两个 executor 钉到 `dispatcher`，`advanceUntilIdle()` 推的是测试调度器，
            // 而 Room 的发射在另一条线程——等多久都等不到，断言只能读到 initialValue。
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .allowMainThreadQueries()
            .build()
        repo = DreamRepository(db)
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    // 每个用例里的这一行都不能删（D53）：
    //   backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    // `WhileSubscribed` 要「有人订阅」才启动上游；没有收集者，`repo.observeMonth()` 根本不会跑。
    // 它和 `withTimeout { state.first { … } }` 是**两件事**：这一行负责「启动上游」，
    // `first { 谓词 }` 负责「等到状态真的变成期望值」。只留前者 → 读到 initialValue；
    // 只留后者 → 上游没启动，`first` 一直等到超时。

    @Test fun `starts on the given month and shows dots only for that month`() = runTest(dispatcher) {
        repo.save(Dream("1", LocalDate.of(2026, 9, 30), null, "上月最后一天", "上月最后一天", null, null, null, false))
        repo.save(Dream("2", LocalDate.of(2026, 10, 1), null, "本月第一天", "本月第一天", null, null, null, false))
        val vm = CalendarViewModel(repo, YearMonth.of(2026, 10))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        // 等到「红点数 = 1」为止：初始值是 0，读到 1 说明 Room 的月度查询真的发射了
        val s = withTimeout(5_000) { vm.state.first { it.daysWithDreams.size == 1 } }
        assertEquals("2026年10月", s.title)
        assertEquals(31, s.grid.filterNotNull().size)
        assertEquals(1, s.daysWithDreams[LocalDate.of(2026, 10, 1)])
        assertFalse(LocalDate.of(2026, 9, 30) in s.daysWithDreams)   // 上个月的 30 号不许串进来
    }

    @Test fun `next and prev month change the title`() = runTest(dispatcher) {
        val vm = CalendarViewModel(repo, YearMonth.of(2026, 12))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        vm.nextMonth()
        withTimeout(5_000) { vm.state.first { it.title == "2027年1月" } }
        vm.prevMonth(); vm.prevMonth()
        withTimeout(5_000) { vm.state.first { it.title == "2026年11月" } }
    }

    @Test fun `goToday returns to the current month`() = runTest(dispatcher) {
        val vm = CalendarViewModel(repo, YearMonth.of(2020, 1))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        vm.goToday()
        withTimeout(5_000) { vm.state.first { it.month == YearMonth.now() } }
    }

    @Test fun `day with a dream is exposed in daysWithDreams`() = runTest(dispatcher) {
        repo.save(Dream("1", LocalDate.of(2026, 10, 15), null, "电梯", "电梯", null, null, null, false))
        val vm = CalendarViewModel(repo, YearMonth.of(2026, 10))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        // 这一条**必须**等 Room 发射：初始状态里 daysWithDreams 是空的，
        // `first { 15 号在里面 }` 会一直挂到数据真的从库里出来为止
        val s = withTimeout(5_000) { vm.state.first { LocalDate.of(2026, 10, 15) in it.daysWithDreams } }
        assertEquals(1, s.daysWithDreams[LocalDate.of(2026, 10, 15)])
        assertFalse(LocalDate.of(2026, 10, 16) in s.daysWithDreams)
    }
}
