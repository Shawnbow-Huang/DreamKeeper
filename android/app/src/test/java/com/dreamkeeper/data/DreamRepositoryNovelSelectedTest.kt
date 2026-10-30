package com.dreamkeeper.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.DreamEntity
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * v2 迁移的第二个必崩点（dispatch 硬约束）：`DreamRepository.save()` 是
 * read-modify-write + `upsert`，而 `novelSelectedVersion` / `generationStage`
 * **不在 `domain/Dream` 上**。只要 save() 的实体构造漏了这两列，用户每编辑一次正文
 * （改个标点、补一句）就会把「我挑的是第 2 版」抹成 null，而且没有任何提示。
 *
 * 这里不测迁移，只钉住「保存不抹新列」这一条契约。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DreamRepositoryNovelSelectedTest {

    private lateinit var db: DreamDatabase
    private lateinit var repository: DreamRepository

    private val now = 1_000_000L
    private val date = LocalDate.parse("2026-10-30")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DreamDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = DreamRepository(db) { now }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun saveKeepsNovelSelectedVersionAndGenerationStage() = runTest {
        db.dreamDao().upsert(
            DreamEntity(
                id = "d1",
                dreamDate = date,
                createdAt = 111L,
                title = "电梯",
                rawTranscript = "我在电梯里，电梯一直往上。",
                faithfulText = null,
                novelText = "旧稿",
                novelVersion = 2,
                updatedAt = 222L,
                novelSelectedVersion = 2,
                generationStage = "T2",
            )
        )

        // 用户在详情页改了个标点：domain 里没有这两列，save() 必须自己从 existing 带回
        repository.save(
            Dream(
                id = "d1",
                date = date,
                title = "电梯",
                rawTranscript = "我在电梯里，电梯一直往上。",
                faithfulText = null,
                novelText = "旧稿，改了个标点。",
                novelChangeLog = null,
                mood = null,
                isDraft = false,
                createdAt = 111L,
                novelVersion = 2,
            )
        )

        val row = db.dreamDao().getById("d1")!!
        assertEquals("选中的版本号不许被保存抹掉", 2, row.novelSelectedVersion)
        assertEquals("生成档位不许被保存抹掉", "T2", row.generationStage)
        assertEquals("正文照常更新", "旧稿，改了个标点。", row.novelText)
        assertEquals("updatedAt 照常刷新", now, row.updatedAt)
        assertEquals("createdAt 原样保留", 111L, row.createdAt)
    }

    @Test
    fun newDreamStartsWithNullSelectionAndStaysNull() = runTest {
        repository.save(
            Dream(
                id = "d2",
                date = date,
                title = null,
                rawTranscript = "新录的一条",
                faithfulText = null,
                novelText = null,
                novelChangeLog = null,
                mood = null,
                isDraft = false,
            )
        )
        val first = db.dreamDao().getById("d2")!!
        assertNull(first.novelSelectedVersion)
        assertNull(first.generationStage)
        // domain 默认值就是 PENDING（「新录的一条」还没转写），save() 原样透传
        assertEquals(TranscriptStatus.PENDING, first.transcriptStatus)

        // 再存一次也不能凭空长出值
        repository.save(
            Dream(
                id = "d2",
                date = date,
                title = "补个标题",
                rawTranscript = "新录的一条",
                faithfulText = null,
                novelText = null,
                novelChangeLog = null,
                mood = null,
                isDraft = false,
            )
        )
        val second = db.dreamDao().getById("d2")!!
        assertNull(second.novelSelectedVersion)
        assertNull(second.generationStage)
        assertEquals("补个标题", second.title)
    }
}
