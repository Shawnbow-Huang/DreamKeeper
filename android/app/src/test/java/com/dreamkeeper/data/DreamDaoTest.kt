package com.dreamkeeper.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.db.DreamDao
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.DreamEntity
import com.dreamkeeper.data.db.DreamTagEntity
import com.dreamkeeper.data.db.MediaAssetEntity
import com.dreamkeeper.data.db.MediaType
import com.dreamkeeper.data.db.SeedData
import com.dreamkeeper.data.db.TagEntity
import com.dreamkeeper.data.db.TagKind
import com.dreamkeeper.data.db.TranscriptStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * DAO 层的行为契约。用 Robolectric + 内存库跑真实 SQLite——Room 生成的 SQL 在真库里过一遍，
 * 而不是对着 mock 断言「我调用过这个方法」。
 *
 * 两条最容易踩的坑，各自有用例钉住：
 * 1. `observeByMonth` 的区间是**闭区间**（`DreamCalendar.monthRange` 返回月首/月末两天），
 *    月末少一天（`until` 少 +1）或月末多一天都会让「10 月 31 日的梦」消失/串月；
 * 2. 中文 LIKE 搜索不能用 FTS（见计划 Task 4「为什么不用 FTS4」），且 `term` 由调用方转义，
 *    所以 `ESCAPE '\'` 必须真的在 SQL 里——否则用户搜 `100%` 会命中全库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DreamDaoTest {

    private lateinit var db: DreamDatabase
    private lateinit var dao: DreamDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DreamDatabase::class.java,
        )
            .allowMainThreadQueries() // 仅测试：生产路径走 suspend/Flow
            .build()
        dao = db.dreamDao()
    }

    @After
    fun tearDown() = db.close()

    private fun dream(id: String, date: String, text: String = "默认文字") = DreamEntity(
        id = id,
        ownerId = "local",
        dreamDate = LocalDate.parse(date),
        createdAt = 0L,
        title = null,
        rawTranscript = text,
        faithfulText = text,
        novelText = null,
        novelVersion = 0,
        novelChangeLog = null,
        mood = null,
        isDraft = false,
        deletedAt = null,
        updatedAt = 0L,
    )

    // ---------- 按月查询 ----------

    @Test
    fun `observeByMonth returns only dreams inside the range ordered by date desc`() = runTest {
        dao.upsert(dream("1", "2026-10-01"))
        dao.upsert(dream("2", "2026-10-15"))
        dao.upsert(dream("3", "2026-09-30"))
        val got = dao.observeByMonth("2026-10-01", "2026-10-31").first()
        assertEquals(listOf("2", "1"), got.map { it.id })
    }

    @Test
    fun `observeByMonth boundary excludes the neighbouring months' edges`() = runTest {
        // 闭区间的两端都必须落在区间内，而相邻月份的最后一天 / 第一天必须落在区间外。
        // 这条直接验 D24 那类 off-by-one：`until` 少 +1 会丢 10-31，`until` 多 +1 会放进 11-01。
        dao.upsert(dream("prev", "2026-09-30"))
        dao.upsert(dream("first", "2026-10-01"))
        dao.upsert(dream("last", "2026-10-31"))
        dao.upsert(dream("next", "2026-11-01"))

        val got = dao.observeByMonth("2026-10-01", "2026-10-31").first()

        assertEquals(listOf("last", "first"), got.map { it.id })
    }

    @Test
    fun `same dream date orders by createdAt desc`() = runTest {
        dao.upsert(dream("old", "2026-10-05").copy(createdAt = 100L))
        dao.upsert(dream("new", "2026-10-05").copy(createdAt = 200L))

        val got = dao.observeByMonth("2026-10-01", "2026-10-31").first()

        assertEquals(listOf("new", "old"), got.map { it.id })
    }

    @Test
    fun `soft deleted dreams are excluded from month query`() = runTest {
        dao.upsert(dream("1", "2026-10-01").copy(deletedAt = 1L))
        dao.upsert(dream("2", "2026-10-02"))
        val got = dao.observeByMonth("2026-10-01", "2026-10-31").first()
        assertEquals(listOf("2"), got.map { it.id })
    }

    @Test
    fun `soft deleted dreams are excluded from observeAll`() = runTest {
        dao.upsert(dream("1", "2026-10-01").copy(deletedAt = 1L))
        dao.upsert(dream("2", "2026-10-02"))

        assertEquals(listOf("2"), dao.observeAll().first().map { it.id })
    }

    // ---------- 搜索 ----------

    @Test
    fun `like search finds a word inside the transcript`() = runTest {
        dao.upsert(dream("1", "2026-10-01", "电梯里有人按了六楼"))
        dao.upsert(dream("2", "2026-10-02", "教室最后一排"))
        val got = dao.searchTerm("电梯")
        assertEquals(listOf("1"), got.map { it.id })
    }

    @Test
    fun `search also matches the novel text`() = runTest {
        dao.upsert(dream("1", "2026-10-01").copy(novelText = "他在楼道尽头回头看我"))
        val got = dao.searchTerm("楼道")
        assertEquals(listOf("1"), got.map { it.id })
    }

    @Test
    fun `search skips soft deleted dreams`() = runTest {
        dao.upsert(dream("1", "2026-10-01", "电梯里有人按了六楼").copy(deletedAt = 1L))
        dao.upsert(dream("2", "2026-10-02", "电梯停在了不存在的楼层"))

        val got = dao.searchTerm("电梯")

        assertEquals(listOf("2"), got.map { it.id })
    }

    @Test
    fun `search treats a pre escaped percent as a literal`() = runTest {
        // 调用方（Task 5 的 DreamRepository.search）负责转义：搜 `100%` 传进来的是 `100\%`。
        // 这条钉住 SQL 里的 `ESCAPE '\'` 真的在——去掉它，`\` 会被当普通字符，两行都查不到。
        dao.upsert(dream("hit", "2026-10-01", "进度 100% 完成"))
        dao.upsert(dream("miss", "2026-10-02", "进度 100 完成"))

        val got = dao.searchTerm("100\\%")

        assertEquals(listOf("hit"), got.map { it.id })
    }

    // ---------- 素材 ----------

    @Test
    fun `media asset is stored against a dream`() = runTest {
        dao.upsert(dream("1", "2026-10-01"))
        db.mediaAssetDao().upsert(
            // 计划原文这里写的是 `MediaType.IMAGE.name`——`MediaType.IMAGE` 是 String 常量，
            // String 没有 `.name`（只有 enum 有），直接传常量，断言强度不变。
            MediaAssetEntity("m1", "1", MediaType.IMAGE, null, "prompt", "model", "PENDING", 0L)
        )
        val assets = db.mediaAssetDao().observeByDream("1").first()
        assertEquals(1, assets.size)
        assertTrue(assets.first().type == MediaType.IMAGE)
    }

    // ---------- 转写状态 ----------

    @Test
    fun `transcript status defaults to OK and round trips PENDING then OK`() = runTest {
        // 默认构造仍编译，且默认值就是 OK：Plan 1 不录音，所有行都该是 OK
        assertEquals(TranscriptStatus.OK, dream("0", "2026-10-01").transcriptStatus)

        dao.upsert(dream("1", "2026-10-01").copy(transcriptStatus = TranscriptStatus.PENDING))
        assertEquals(TranscriptStatus.PENDING, dao.observeById("1").first()!!.transcriptStatus)

        dao.upsert(dream("1", "2026-10-01").copy(transcriptStatus = TranscriptStatus.OK))
        assertEquals(TranscriptStatus.OK, dao.observeById("1").first()!!.transcriptStatus)
    }

    // ---------- 标签 ----------

    @Test
    fun `tags for a dream come from the join table`() = runTest {
        dao.upsert(dream("1", "2026-10-01"))
        db.tagDao().upsert(TagEntity("t1", "电梯", TagKind.OBJECT))
        db.tagDao().upsert(TagEntity("t2", "坠落", TagKind.EMOTION))
        db.dreamTagDao().upsertAll(listOf(DreamTagEntity("1", "t1")))
        val tags = db.dreamTagDao().observeTagsForDream("1").first()
        assertEquals(listOf("t1"), tags.map { it.id })
    }

    @Test
    fun `tags for a dream are ordered by kind then name`() = runTest {
        // 详情页「标签」tab 的数据源：同 kind 按 name，跨 kind 按 kind 字典序。
        dao.upsert(dream("1", "2026-10-01"))
        db.tagDao().upsert(TagEntity("a", "电梯", TagKind.OBJECT))
        db.tagDao().upsert(TagEntity("b", "教室", TagKind.PLACE))
        db.tagDao().upsert(TagEntity("c", "坠落", TagKind.EMOTION))
        db.dreamTagDao().upsertAll(
            listOf(
                DreamTagEntity("1", "a"),
                DreamTagEntity("1", "b"),
                DreamTagEntity("1", "c"),
            )
        )

        val tags = db.dreamTagDao().observeTagsForDream("1").first()

        assertEquals(listOf(TagKind.EMOTION, TagKind.OBJECT, TagKind.PLACE), tags.map { it.kind })
        assertEquals(listOf("c", "a", "b"), tags.map { it.id })
    }

    @Test
    fun `deleting a tag also removes its dream links`() = runTest {
        dao.upsert(dream("1", "2026-10-01"))
        db.tagDao().upsert(TagEntity("t1", "电梯", TagKind.OBJECT))
        db.dreamTagDao().upsertAll(listOf(DreamTagEntity("1", "t1")))

        db.tagDao().delete("t1")   // 事务：先删 dream_tags，再删 tags

        assertEquals(0, db.dreamTagDao().observeTagsForDream("1").first().size)
        assertEquals(0, db.tagDao().observeAll().first().size)
    }

    // ---------- 种子 ----------

    @Test
    fun `seeding twice does not duplicate`() = runTest {
        SeedData.seedIfEmpty(db)
        SeedData.seedIfEmpty(db)
        assertEquals(4, dao.count())
        assertTrue(db.tagDao().count() >= 1)
    }
}
