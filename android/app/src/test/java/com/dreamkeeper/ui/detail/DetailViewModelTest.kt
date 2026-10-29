package com.dreamkeeper.ui.detail

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Task 8 / brief 0.3：`DetailViewModel` 的三条。
 *
 * 为什么用 Robolectric 而不是纯 JVM：① 与 ③ 要证明「两条 Flow 都接上了」，
 * 必须真的建一次 Room 才能让 `observe(id)` / `observeTagsForDream` 发出值。
 * 纯函数 `novelTabLabel` / `novelChangeCount` 的断言也放在这里，一个 suite 覆盖整个详情契约。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetailViewModelTest {

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
            // 同 Task 6/7：Room 的 executor 必须是测试调度器，否则查询推不动（D53）
            .allowMainThreadQueries()
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

    /**
     * ① 种子 id 进 combine 后 `dream` 与 `tags` 都非空。
     * 只要有一条流没接上（漏 `combine`、漏 `observeTagsForDream`），这一条必红。
     */
    @Test
    fun `state combines dream and its tags`() = runTest(dispatcher) {
        repo.save(
            Dream(
                "seed-dream-1", LocalDate.of(2026, 10, 1), "电梯",
                "电梯一直往上，按不到一楼。", "电梯一直往上，按不到一楼。",
                null, "补写 2 处：走廊的光、声音的方向", null, false,
            )
        )
        db.tagDao().upsert(
            TagEntity("seed-tag-emotion-fall", "下坠的预感", TagKind.EMOTION, TagSource.AUTO, "电梯一直往上")
        )
        db.dreamTagDao().upsertAll(listOf(DreamTagEntity("seed-dream-1", "seed-tag-emotion-fall")))

        val vm = DetailViewModel(repo, "seed-dream-1")
        // WhileSubscribed：没有订阅者时两条上游都不会启动
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        val state = withTimeout(5_000) {
            // 必须等到「已加载 且 标签也到了」：只等 dream 非空会在标签还没联出来那一帧提前返回
            vm.state.first { it.loaded && it.dream != null && it.tags.isNotEmpty() }
        }
        assertEquals("电梯", state.dream?.title)
        assertEquals(listOf("下坠的预感"), state.tags.map { it.name })
        assertEquals(2, state.novelChangeCount)
        assertTrue(state.assets.isEmpty())
    }

    /**
     * ② `novelChangeCount` 的正则（D41 契约）：`补写 2 处` 与 `补 写 3 处` 都要抽得出数字。
     * 中间那个 `\s*` 就是被测对象——去掉它，第二条断言立刻红。
     */
    @Test
    fun `novel change count tolerates whitespace inside the phrase`() {
        assertEquals(2, novelChangeCount("补写 2 处：走廊的光、声音的方向"))
        assertEquals(3, novelChangeCount("补 写 3 处：走廊的光、声音的方向"))
        assertEquals(0, novelChangeCount(null))
        assertEquals(0, novelChangeCount("生成了一版，没有改动清单"))
        assertEquals(0, novelChangeCount("补写 处：数字缺失"))
        // tab 标题格式是 D41 契约，一个字都不许改
        assertEquals("小说化", novelTabLabel(0))
        assertEquals("小说化 · 补写 6 处", novelTabLabel(6))
    }

    /**
     * ③ 不存在的 id：加载完成后 `dream == null`（界面据此显示「找不到这条梦」+ 返回，不许白屏）。
     *
     * `loaded` 是 `DetailUiState` 末尾追加的默认字段：没有它，「还在加载」与「查无此梦」都是
     * `dream == null`，这条断言就永远绿（读的是 stateIn 的 initialValue），等于没测。
     */
    @Test
    fun `missing dream is null once loaded`() = runTest(dispatcher) {
        val vm = DetailViewModel(repo, "no-such-dream")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        val state = withTimeout(5_000) { vm.state.first { it.loaded } }
        assertNull(state.dream)
        assertTrue(state.tags.isEmpty())
        assertEquals(0, state.novelChangeCount)
    }
}
