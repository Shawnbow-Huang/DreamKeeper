package com.dreamkeeper.ui.search

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.domain.Dream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Task 8 / brief 0.2：防抖必须卡边界，否则把 `debounce(250)` 删掉或改成 2000 一样绿。
 *
 * 两条边界断言（249ms 前不许有结果 / 251ms 必须出结果）方向相反：
 * - 删掉 debounce → 第一条红（249ms 时结果已到）；
 * - 改成 `debounce(2_000)` → 第二条红（251ms 时结果还没到）。
 *
 * 调度器：VM 的 `dispatcher` 与 `runTest` 共用同一个 testScheduler，`flowOn(dispatcher)`
 * 才推得动那 250ms；写成内部 `Dispatchers.IO` 时虚拟时钟管不到（见 brief 第 74 行）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DreamDatabase::class.java,
        )
            .allowMainThreadQueries()
            // Room 查询/事务也跑在测试调度器上，虚拟时间才推得动（D53）
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .build()
        repo = DreamRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun dream(id: String, day: Int, title: String, text: String) = Dream(
        id, LocalDate.of(2026, 10, day), title, text, text, null, null, null, false,
    )

    @Test
    fun `no results before the full 250ms window, results right after`() = runTest(dispatcher) {
        repo.save(dream("seed-dream-1", 1, "电梯", "电梯里有人"))

        // 关键：把测试调度器交给 ViewModel，虚拟时间才能推动它自己的 debounce
        val vm = SearchViewModel(repo, StandardTestDispatcher(testScheduler))
        // WhileSubscribed 的 StateFlow：没有订阅者时上游根本不启动
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.results.collect {} }

        vm.onQueryChange("电梯")
        advanceTimeBy(249)
        runCurrent()
        assertTrue(vm.results.value.isEmpty())   // 250ms 之前不许出结果

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("seed-dream-1"), vm.results.value.map { it.id })
    }

    /**
     * 每敲一个字都要重新计时：200ms 时改词，必须再等满 250ms 才出结果。
     * 没有 debounce（或用了 `sample` / 固定窗口）时，300ms 处已经出结果，第一条断言就红。
     */
    @Test
    fun `debounce window restarts on every keystroke`() = runTest(dispatcher) {
        repo.save(dream("1", 1, "电梯", "电梯里有人"))
        repo.save(dream("2", 2, "教室", "教室里考试"))

        val vm = SearchViewModel(repo, StandardTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.results.collect {} }

        vm.onQueryChange("电梯")
        advanceTimeBy(200)
        vm.onQueryChange("教室")
        // 距第一次输入已经 300ms，但距第二次只有 100ms，还在窗口内
        advanceTimeBy(100)
        runCurrent()
        assertTrue(vm.results.value.isEmpty())

        advanceTimeBy(151)
        runCurrent()
        assertEquals(listOf("2"), vm.results.value.map { it.id })
    }

    /** 手打中文时每个字都会触发 onQueryChange，最终只允许跑最后一个词（brief Step 1） */
    @Test
    fun `typing quickly only runs the last query`() = runTest(dispatcher) {
        repo.save(dream("1", 1, "电梯", "电梯里有人"))
        repo.save(dream("2", 2, "教室", "教室里考试"))

        val vm = SearchViewModel(repo, StandardTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.results.collect {} }

        vm.onQueryChange("电")
        vm.onQueryChange("电梯")
        advanceTimeBy(300)
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("1"), vm.results.value.map { it.id })
    }

    /**
     * 终审 P1-3：防抖窗口内必须是「在找…」，不能是「没有找到」。
     * 只看 `results.isEmpty()` 的话，从敲第一个字起就会假报 250ms 以上的「没有找到」。
     */
    @Test
    fun `searching is true inside the debounce window and false once results land`() = runTest(dispatcher) {
        repo.save(dream("1", 1, "电梯", "电梯里有人"))

        val vm = SearchViewModel(repo, StandardTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.searching.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.results.collect {} }

        assertFalse("初始没在搜", vm.searching.value)

        vm.onQueryChange("电梯")
        runCurrent()
        assertTrue("输入了但还没查完，必须算「在找」", vm.searching.value)

        advanceTimeBy(251)
        runCurrent()
        assertEquals(listOf("1"), vm.results.value.map { it.id })
        assertFalse("结果到了就不在找了", vm.searching.value)
    }

    /** 清空输入框 = 回到「搜一个词」，不算在找，也不算没找到。 */
    @Test
    fun `clearing the field stops searching`() = runTest(dispatcher) {
        repo.save(dream("1", 1, "电梯", "电梯里有人"))

        val vm = SearchViewModel(repo, StandardTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.searching.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.results.collect {} }

        vm.onQueryChange("电梯")
        advanceTimeBy(251)
        runCurrent()
        assertFalse(vm.searching.value)

        vm.onQueryChange("")
        advanceTimeBy(251)
        runCurrent()
        assertFalse(vm.searching.value)
        assertTrue(vm.results.value.isEmpty())
    }
}
