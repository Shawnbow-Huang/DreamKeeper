package com.dreamkeeper.ui.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.DreamTagEntity
import com.dreamkeeper.data.db.TagEntity
import com.dreamkeeper.data.db.TagKind
import com.dreamkeeper.data.db.TagSource
import com.dreamkeeper.domain.Dream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DreamDatabase::class.java)
            .allowMainThreadQueries()
            // Room 的查询/事务跑在自己的 executor 上，不固定住就推不动、也等不到值（D53）
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .build()
        repo = DreamRepository(db)
    }
    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test fun `lists dreams newest date first`() = runTest(dispatcher) {
        repo.save(Dream("1", LocalDate.of(2026,10,1), null,"a","a",null,null,null,false))
        repo.save(Dream("2", LocalDate.of(2026,10,20), null,"b","b",null,null,null,false))
        val vm = LibraryViewModel(repo, FakeLayoutPrefs())
        // 必须有这一行（同 Task 6 Step 1 的原因）：state 用 WhileSubscribed 起上游，
        // 没有订阅者时 observeAll() 根本不启动，advanceUntilIdle() 之后 items 还是空表。
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        // 只 advanceUntilIdle() 会读到 initialValue（空表）；必须带超时等值（D53）
        val items = withTimeout(5_000) { vm.state.first { it.items.isNotEmpty() }.items }
        assertEquals(listOf("2","1"), items.map { it.dream.id })
    }

    @Test fun `layout choice is persisted`() = runTest(dispatcher) {
        // 必须有一条数据：下面那句「等 items 非空」是在等上游真的跑起来，空库时它永远等不到
        // （brief 原文此处没有种子，withTimeout 会直接超时；补一条数据，断言一个字没改）
        repo.save(Dream("1", LocalDate.of(2026,10,8), null,"a","a",null,null,null,false))
        val prefs = FakeLayoutPrefs()
        val vm = LibraryViewModel(repo, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        withTimeout(5_000) { vm.state.first { it.items.isNotEmpty() } }
        vm.setLayout(LibraryLayout.CARDS)
        assertEquals(LibraryLayout.CARDS, prefs.layout)
        assertEquals(LibraryLayout.CARDS, withTimeout(5_000) { vm.state.first { it.layout == LibraryLayout.CARDS } }.layout)
    }

    @Test fun `items carry their tags`() = runTest(dispatcher) {
        repo.save(Dream("1", LocalDate.of(2026,10,8), "电梯", "电梯一直往上", "电梯一直往上", null, null, null, false))
        db.tagDao().upsert(TagEntity("t1", "下坠的预感", TagKind.EMOTION, TagSource.AUTO, "电梯一直往上"))
        db.dreamTagDao().upsertAll(listOf(DreamTagEntity("1", "t1")))
        val vm = LibraryViewModel(repo, FakeLayoutPrefs())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        // 只等 items 非空会在「梦已进列表、标签还没联出来」的那一帧提前返回，必须等 tags 也到
        val item = withTimeout(5_000) {
            vm.state.first { s -> s.items.any { it.tags.isNotEmpty() } }.items.single()
        }
        assertEquals("1", item.dream.id)
        assertEquals(listOf("下坠的预感"), item.tags.map { it.name })
    }

    @Test fun `shared prefs implementation survives a new instance`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        SharedPreferencesLayoutPrefs(ctx).layout = LibraryLayout.CARDS
        // 新实例 = 重新读一次盘；Robolectric 的 SharedPreferences 是真的
        assertEquals(LibraryLayout.CARDS, SharedPreferencesLayoutPrefs(ctx).layout)
        SharedPreferencesLayoutPrefs(ctx).layout = LibraryLayout.TIMELINE
        assertEquals(LibraryLayout.TIMELINE, SharedPreferencesLayoutPrefs(ctx).layout)
    }
}

class FakeLayoutPrefs : LayoutPrefs { override var layout = LibraryLayout.TIMELINE }
