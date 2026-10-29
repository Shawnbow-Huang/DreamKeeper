package com.dreamkeeper.ui.day

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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DreamDatabase::class.java)
            // 与 CalendarViewModelTest 同理：Room 的发射在真实 executor 上，必须钉到测试调度器（D53）
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .allowMainThreadQueries()
            .build()
        repo = DreamRepository(db)
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test fun `shows only that day and formats the title in chinese`() = runTest(dispatcher) {
        repo.save(Dream("1", LocalDate.of(2026, 11, 8), "电梯", "电梯里有人按了六楼", "电梯里有人按了六楼", null, null, null, false))
        repo.save(Dream("2", LocalDate.of(2026, 11, 9), null, "第二天", "第二天", null, null, null, false))
        val vm = DayViewModel(repo, LocalDate.of(2026, 11, 8))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        // 初始状态 items 是空的，等到「列表非空」= 等 Room 真的把 11-08 那条发射出来
        val s = withTimeout(5_000) { vm.state.first { it.items.isNotEmpty() } }
        assertEquals(listOf("1"), s.items.map { it.dream.id })
        assertEquals("11月8日 周日", s.title)
    }
}
