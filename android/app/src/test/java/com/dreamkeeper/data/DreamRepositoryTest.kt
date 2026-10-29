package com.dreamkeeper.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.MediaAssetEntity
import com.dreamkeeper.data.db.MediaType
import com.dreamkeeper.data.db.TagEntity
import com.dreamkeeper.data.db.TagKind
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import java.time.YearMonth

/**
 * 仓库层的行为契约：domain 模型与 Room 之间的**唯一**翻译点。
 *
 * 这里用 Robolectric + 真 SQLite（内存库）跑，不打 mock——仓库的价值全在
 * 「拼出来的 SQL / 实体到底落了什么」，mock 掉数据库就什么也验不到。
 *
 * 四类会被后续 Plan 悄悄踩回来的坑，各有用例钉住：
 * 1. `save()` 改写 `createdAt` / 清掉 `deletedAt`（用户数据完整性）；
 * 2. `search()` 的 LIKE 通配符未转义（`100%` 命中全库）与空查询退化成全量；
 * 3. `observeMonth` 的闭区间在月末差一天；
 * 4. 拿 `observeById` 当列表数据源，把回收站里的梦混进正常列表（D26）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DreamRepositoryTest {

    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DreamDatabase::class.java,
        )
            .allowMainThreadQueries() // 仅测试：生产路径全走 suspend/Flow
            .build()
        repo = DreamRepository(db) { 1000L }
    }

    @After
    fun tearDown() = db.close()

    private fun d(id: String, date: String, text: String) =
        Dream(
            id = id,
            date = LocalDate.parse(date),
            title = null,
            rawTranscript = text,
            faithfulText = text,
            novelText = null,
            novelChangeLog = null,
            mood = null,
            isDraft = false,
        )

    // ---------- 读写与月份区间 ----------

    @Test
    fun `save then observeMonth returns it`() = runTest {
        repo.save(d("1", "2026-10-15", "电梯"))
        val got = repo.observeMonth(YearMonth.of(2026, 10)).first()
        assertEquals(listOf("1"), got.map { it.id })
    }

    @Test
    fun `observeMonth keeps both month boundaries and drops neighbours`() = runTest {
        // 仓库这一层也必须守住闭区间：月首、月末都在，9-30 / 11-01 都不在。
        // 若实现把月末漏掉（或把两端写成半开区间没 +1），10-31 这条会消失。
        repo.save(d("prev", "2026-09-30", "上个月最后一天"))
        repo.save(d("first", "2026-10-01", "本月第一天"))
        repo.save(d("last", "2026-10-31", "本月最后一天"))
        repo.save(d("next", "2026-11-01", "下个月第一天"))

        val got = repo.observeMonth(YearMonth.of(2026, 10)).first()

        assertEquals(listOf("last", "first"), got.map { it.id })
    }

    @Test
    fun `ids are stable across save`() = runTest {
        repo.save(d("abc", "2026-10-15", "电梯"))
        assertEquals("abc", repo.observe("abc").first()!!.id)
    }

    // ---------- save：时间戳 / 全字段往返 ----------

    @Test
    fun `save fills createdAt and updatedAt from the injected clock for a new dream`() = runTest {
        repo.save(d("1", "2026-10-15", "电梯"))

        val got = repo.observe("1").first()!!

        assertEquals(1000L, got.createdAt)
        assertEquals(1000L, got.updatedAt)
        assertNull(got.deletedAt)
    }

    @Test
    fun `save round trips every domain field`() = runTest {
        // 全字段往返：save()/toDomain() 各漏一个字段都会在这里打红。
        val full = Dream(
            id = "1",
            date = LocalDate.parse("2026-10-15"),
            title = "电梯",
            rawTranscript = "原样转写",
            faithfulText = "原样稿",
            novelText = "小说化稿",
            novelChangeLog = "补写 2 处：走廊的光、声音的方向",
            mood = "压抑",
            isDraft = true,
            novelVersion = 3,
            createdAt = 123L,          // 非 0：必须原样保留
            updatedAt = 456L,          // updatedAt 由仓库接管，见下面的断言
            deletedAt = 789L,          // 软删标记：必须原样透传
            transcriptStatus = TranscriptStatus.FAILED,
            rawAudioPath = "/audio/1.m4a",
            rawAudioMs = 4321L,
        )

        repo.save(full)
        val got = repo.observe("1").first()!!

        assertEquals("1", got.id)
        assertEquals(LocalDate.parse("2026-10-15"), got.date)
        assertEquals("电梯", got.title)
        assertEquals("原样转写", got.rawTranscript)
        assertEquals("原样稿", got.faithfulText)
        assertEquals("小说化稿", got.novelText)
        assertEquals("补写 2 处：走廊的光、声音的方向", got.novelChangeLog)
        assertEquals("压抑", got.mood)
        assertTrue(got.isDraft)
        assertEquals(3, got.novelVersion)
        assertEquals(123L, got.createdAt)        // 首次录入时间：不许被 clock() 覆盖
        assertEquals(1000L, got.updatedAt)       // 更新时间：仓库写 now
        assertEquals(789L, got.deletedAt)        // 删除标记：原样透传
        assertEquals(TranscriptStatus.FAILED, got.transcriptStatus)
        assertEquals("/audio/1.m4a", got.rawAudioPath)
        assertEquals(4321L, got.rawAudioMs)
    }

    @Test
    fun `save preserves createdAt across updates`() = runTest {
        repo.save(d("1", "2026-10-15", "电梯"))
        val first = repo.observe("1").first()!!
        assertEquals(1000L, first.createdAt)

        // 换一个时钟再存一次：createdAt 是"首次录入时间"，不许被这次补录/编辑改写
        val later = DreamRepository(db) { 2000L }
        later.save(first.copy(title = "改过的标题"))

        val after = repo.observe("1").first()!!
        assertEquals(1000L, after.createdAt)
        assertEquals(2000L, after.updatedAt)
        assertEquals("改过的标题", after.title)
    }

    @Test
    fun `save preserves deletedAt`() = runTest {
        repo.save(d("1", "2026-10-15", "电梯"))
        // 直接置成回收站里的行：save 不能把它悄悄捞回"未删除"
        db.dreamDao().upsert(db.dreamDao().observeById("1").first()!!.copy(deletedAt = 500L))
        val trashed = repo.observe("1").first()!!
        assertEquals(500L, trashed.deletedAt)

        repo.save(trashed)
        assertEquals(500L, repo.observe("1").first()!!.deletedAt)
    }

    // ---------- 列表 vs 按 id（D26） ----------

    @Test
    fun `soft deleted dreams are excluded from lists but reachable by id`() = runTest {
        // D26：observeById 不过滤 deletedAt（回收站要能打开），
        // 但列表一律走 observeAll / observeMonth，不能把回收站的梦混进去。
        repo.save(d("1", "2026-10-15", "电梯"))
        db.dreamDao().upsert(db.dreamDao().observeById("1").first()!!.copy(deletedAt = 500L))

        assertEquals(0, repo.observeAll().first().size)
        assertEquals(0, repo.observeMonth(YearMonth.of(2026, 10)).first().size)
        assertEquals(500L, repo.observe("1").first()!!.deletedAt)
    }

    // ---------- 搜索：切词、转义、空查询 ----------

    @Test
    fun `search splits on spaces and requires all terms`() = runTest {
        repo.save(d("1", "2026-10-01", "电梯里有人按了六楼"))
        repo.save(d("2", "2026-10-02", "电梯停在负一层"))
        val got = repo.search("电梯 六楼")
        assertEquals(listOf("1"), got.map { it.id })
    }

    @Test
    fun `search tolerates quotes and punctuation without crashing`() = runTest {
        repo.save(d("1", "2026-10-01", "电梯里有人"))
        assertEquals(1, repo.search("\"电梯\"！").size)
    }

    @Test
    fun `empty query returns empty list`() = runTest {
        repo.save(d("1", "2026-10-01", "电梯"))
        assertEquals(0, repo.search("   ").size)
    }

    @Test
    fun `a punctuation only query returns nothing instead of everything`() = runTest {
        repo.save(d("1", "2026-10-01", "电梯"))
        // 清洗后一个词都不剩：若没有这个短路，searchTerm("") 的 LIKE '%%' 会返回全库
        assertEquals(0, repo.search("！！！").size)
    }

    @Test
    fun `search treats percent as a literal character`() = runTest {
        repo.save(d("1", "2026-10-01", "进度 100% 就停下了"))
        repo.save(d("2", "2026-10-02", "完全无关的一条"))
        // 若 % 没被转义，LIKE '%%%' 会命中所有行——这条就是那个回归
        assertEquals(listOf("1"), repo.search("100%").map { it.id })
    }

    @Test
    fun `search treats underscore as a literal character`() = runTest {
        repo.save(d("1", "2026-10-01", "电梯_a"))
        repo.save(d("2", "2026-10-02", "电梯ab"))
        // `_` 是 LIKE 的单字符通配符：不转义的话两行都会命中
        assertEquals(listOf("1"), repo.search("电梯_a").map { it.id })
    }

    @Test
    fun `search matches a substring in the middle of the novel text`() = runTest {
        repo.save(
            d("1", "2026-10-01", "走廊很长").copy(
                novelText = "走廊很长，尽头是楼道，灯闪了一下。"
            )
        )
        // 楼道 只出现在 novelText 的中间：4 列 LIKE 必须能捞出来
        assertEquals(listOf("1"), repo.search("楼道").map { it.id })
    }

    // ---------- 标签联表 / 素材 ----------

    @Test
    fun `observeTagsForDream returns joined tags ordered by kind then name`() = runTest {
        repo.save(d("1", "2026-10-01", "电梯"))
        db.tagDao().upsert(TagEntity("a", "电梯", TagKind.OBJECT))
        db.tagDao().upsert(TagEntity("b", "教室", TagKind.PLACE))
        db.tagDao().upsert(TagEntity("c", "坠落", TagKind.EMOTION))

        repo.attachTags("1", listOf("a", "b", "c"))

        val tags = repo.observeTagsForDream("1").first()
        assertEquals(listOf("c", "a", "b"), tags.map { it.id })   // EMOTION < OBJECT < PLACE
        assertEquals(listOf(TagKind.EMOTION, TagKind.OBJECT, TagKind.PLACE), tags.map { it.kind })
    }

    @Test
    fun `attachTags replaces the previous links and dedupes ids`() = runTest {
        repo.save(d("1", "2026-10-01", "电梯"))
        db.tagDao().upsert(TagEntity("t1", "电梯", TagKind.OBJECT))
        db.tagDao().upsert(TagEntity("t2", "坠落", TagKind.EMOTION))
        db.tagDao().upsert(TagEntity("t3", "教室", TagKind.PLACE))

        repo.attachTags("1", listOf("t1", "t2", "t1"))
        assertEquals(setOf("t1", "t2"), repo.observeTagsForDream("1").first().map { it.id }.toSet())

        // 再打一次只带 t3：旧关联必须被整体替换（幂等、可重放）
        repo.attachTags("1", listOf("t3"))
        assertEquals(listOf("t3"), repo.observeTagsForDream("1").first().map { it.id })
    }

    @Test
    fun `observeMediaAssets maps entities to domain`() = runTest {
        repo.save(d("1", "2026-10-01", "电梯"))
        db.mediaAssetDao().upsert(
            MediaAssetEntity(
                id = "m1", dreamId = "1", type = MediaType.VIDEO,
                path = "/media/1.mp4", prompt = "走廊尽头的光", model = "fake-video",
                status = "PENDING", createdAt = 42L,
            )
        )

        val assets = repo.observeMediaAssets("1").first()

        assertEquals(1, assets.size)
        val asset = assets.first()
        assertEquals("m1", asset.id)
        assertEquals("1", asset.dreamId)
        assertEquals(MediaType.VIDEO, asset.type)
        assertEquals("/media/1.mp4", asset.path)
        assertEquals("走廊尽头的光", asset.prompt)
        assertEquals("fake-video", asset.model)
        assertEquals("PENDING", asset.status)
        assertEquals(42L, asset.createdAt)
    }
}
