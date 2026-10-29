package com.dreamkeeper.ui.tags

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.TagEntity
import com.dreamkeeper.data.db.TagKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 标签页的行为契约（设计 §12 标签管理 / brief §0.3–§0.5）。
 *
 * 合并那两条测试的**数据形状**是本文件的重点：`dream_tags` 主键是 `(dreamId, tagId)`，
 * 只有造出「同一篇梦同时挂着 from 与 into」的那条（dream-3），才逼得实现必须写
 * `UPDATE OR REPLACE`——普通 `UPDATE` 会在这一行上撞唯一约束。只造「dream-1 挂 t2」的话，
 * 写成漏 `WHERE`、甚至整表删都能绿，等于没测。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagsViewModelTest {
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

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /** 三条梦 + 两个同义标签：别的测试都从这里起步 */
    private suspend fun seedThreeDreams() {
        db.tagDao().upsert(TagEntity("t1", "电梯", TagKind.OBJECT))
        db.tagDao().upsert(TagEntity("t2", "升降机", TagKind.OBJECT))
        // dream-1 只挂源标签；dream-2 已挂目标；dream-3 两个都挂（唯一能逼出 OR REPLACE 的那条）
        repo.attachTags("dream-1", listOf("t2"))
        repo.attachTags("dream-2", listOf("t1"))
        repo.attachTags("dream-3", listOf("t1", "t2"))
    }

    @Test fun `merging moves every dream to the target and deletes the source`() = runTest(dispatcher) {
        seedThreeDreams()
        // 前置断言：造的数据真的长成这样，否则后面的「收敛」可能本来就成立
        assertEquals(listOf("t2"), db.dreamTagDao().countTagsOn("dream-1"))
        assertEquals(listOf("t1"), db.dreamTagDao().countTagsOn("dream-2"))
        assertEquals(setOf("t1", "t2"), db.dreamTagDao().countTagsOn("dream-3").toSet())

        val vm = TagsViewModel(repo, db.tagDao())
        vm.merge(fromId = "t2", intoId = "t1")
        advanceUntilIdle()

        assertEquals(listOf("t1"), db.dreamTagDao().countTagsOn("dream-1"))
        assertEquals(listOf("t1"), db.dreamTagDao().countTagsOn("dream-2"))
        // 两个都挂的那条必须收敛成一行，不是两行
        assertEquals(listOf("t1"), db.dreamTagDao().countTagsOn("dream-3"))
        // 源标签行已删、目标标签还在
        assertNull(db.tagDao().findById("t2"))
        assertNotNull(db.tagDao().findById("t1"))
    }

    @Test fun `repository reports whether a merge actually happened`() = runTest(dispatcher) {
        seedThreeDreams()

        // ① from == into：早退。真跑下去会先「更新自己」再把自己删掉，关联全成孤儿
        assertFalse(repo.mergeTags("t1", "t1"))
        assertNotNull(db.tagDao().findById("t1"))
        // ② 目标不存在：不改库、返回 false
        assertFalse(repo.mergeTags("t2", "nope"))
        assertNotNull(db.tagDao().findById("t2"))
        assertEquals(listOf("t2"), db.dreamTagDao().countTagsOn("dream-1"))
        // ③ 正常合并才返回 true
        assertTrue(repo.mergeTags("t2", "t1"))
        assertNull(db.tagDao().findById("t2"))
    }

    @Test fun `merging a tag into itself keeps every link`() = runTest(dispatcher) {
        seedThreeDreams()
        val vm = TagsViewModel(repo, db.tagDao())

        vm.merge(fromId = "t1", intoId = "t1")
        advanceUntilIdle()

        // 自己并自己 = 什么都不该发生：两个标签都在，dream-3 的两条关联也一条不少
        assertEquals(setOf("t1", "t2"), db.dreamTagDao().countTagsOn("dream-3").toSet())
        assertNotNull(db.tagDao().findById("t1"))
        assertEquals(2, db.tagDao().count())
    }

    @Test fun `renaming into an existing name asks first and keeps both tags`() = runTest(dispatcher) {
        seedThreeDreams()
        val vm = TagsViewModel(repo, db.tagDao())

        // 「电梯」改名为「升降机」——后者已存在。绝不允许 upsert/REPLACE：
        // 那会删掉 t2 那一行、把它的关联留成孤儿，界面上只是某个梦少了个标签（D75）
        vm.rename(tagId = "t1", rawName = "升降机")
        val conflict = withTimeout(5_000) { vm.state.first { it.conflict != null }.conflict!! }

        assertEquals("t1", conflict.from.id)
        assertEquals("t2", conflict.into.id)
        assertEquals("升降机", conflict.into.name)
        // 还没确认，库里一个字都不许动
        assertEquals(2, db.tagDao().count())
        assertEquals("电梯", db.tagDao().findById("t1")?.name)

        vm.confirmConflict()
        advanceUntilIdle()

        // 确认 = 把 t1 的关联并进 t2；t1 消失，t2 留着
        assertNull(db.tagDao().findById("t1"))
        assertEquals("升降机", db.tagDao().findById("t2")?.name)
        assertEquals(listOf("t2"), db.dreamTagDao().countTagsOn("dream-1"))
        assertEquals(listOf("t2"), db.dreamTagDao().countTagsOn("dream-3"))
    }

    @Test fun `renaming to a fresh name just renames`() = runTest(dispatcher) {
        seedThreeDreams()
        val vm = TagsViewModel(repo, db.tagDao())

        vm.rename(tagId = "t2", rawName = "  货梯  ")
        advanceUntilIdle()

        // 两侧空白去掉，冲突不弹
        assertEquals("货梯", db.tagDao().findById("t2")?.name)
        assertEquals(2, db.tagDao().count())
        assertNull(withTimeout(5_000) { vm.state.first { it.loaded } }.conflict)
    }

    @Test fun `deleting a tag removes its links but keeps the dreams`() = runTest(dispatcher) {
        seedThreeDreams()
        val vm = TagsViewModel(repo, db.tagDao())

        vm.delete("t2")
        advanceUntilIdle()

        assertNull(db.tagDao().findById("t2"))
        // dream-1 本来就只挂 t2：它现在一个标签都没有，但行还在（删标签不删梦）
        assertEquals(emptyList<String>(), db.dreamTagDao().countTagsOn("dream-1"))
        assertEquals(listOf("t1"), db.dreamTagDao().countTagsOn("dream-3"))
    }

    @Test fun `groups follow the design order not the alphabetical one`() = runTest(dispatcher) {
        // 故意乱序插入：`ORDER BY kind` 是字符串序，会从「动作」开头（设计要的是人物在最前）
        listOf(
            TagEntity("k6", "说不清", TagKind.OTHER),
            TagEntity("k5", "下坠的预感", TagKind.EMOTION),
            TagEntity("k4", "跑", TagKind.ACTION),
            TagEntity("k3", "钥匙", TagKind.OBJECT),
            TagEntity("k2", "教室", TagKind.PLACE),
            TagEntity("k1", "妈妈", TagKind.PERSON),
        ).forEach { db.tagDao().upsert(it) }
        val vm = TagsViewModel(repo, db.tagDao())

        val groups = withTimeout(5_000) { vm.state.first { it.groups.isNotEmpty() }.groups }

        assertEquals(
            listOf("人物", "地点", "物品", "动作", "情绪", "其他"),
            groups.map { it.title },
        )
        assertEquals(listOf("妈妈"), groups.first().tags.map { it.name })
        assertEquals(listOf("说不清"), groups.last().tags.map { it.name })
    }
}
