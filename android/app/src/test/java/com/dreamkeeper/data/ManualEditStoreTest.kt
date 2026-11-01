package com.dreamkeeper.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.DreamEntity
import com.dreamkeeper.data.db.TranscriptStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Plan 8 追加：手动改字的落盘边界（原样稿就地改、小说稿建新版本）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualEditStoreTest {

    private lateinit var db: DreamDatabase
    private lateinit var store: GenerationStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, DreamDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomGenerationStore(db)
        runBlocking { db.dreamDao().upsert(
            DreamEntity(
                id = "d1",
                dreamDate = LocalDate.of(2026, 11, 1),
                createdAt = 1_793_500_000_000L,
                title = null,
                rawTranscript = "原文",
                faithfulText = "标里也备了一把枪",
                novelText = null,
                transcriptStatus = TranscriptStatus.OK,
                transcriptMs = 0L,
            ))
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `改原样稿只动 faithfulText 一列`() = runTest {
        store.saveManualFaithful("d1", "包里也备了一把枪")

        val row = db.dreamDao().getById("d1")!!
        assertEquals("包里也备了一把枪", row.faithfulText)
        assertEquals("原文", row.rawTranscript)
        assertEquals(null, row.novelText)
        assertEquals(0, db.novelVersionDao().listByDream("d1").size)
    }

    @Test
    fun `改小说稿新建 manual 版本并指向当前稿`() = runTest {
        val version = store.saveManualNovel("d1", "改过的小说正文。")

        assertEquals(1, version)
        val row = db.dreamDao().getById("d1")!!
        assertEquals("改过的小说正文。", row.novelText)
        // novelVersion 列按设计不由 commitSuccess 维护（版本号以 novel_versions 的自增为准）。

        val saved = db.novelVersionDao().listByDream("d1").single()
        assertEquals("manual", saved.origin)
        assertEquals("改过的小说正文。", saved.text)
        assertNotNull(saved.id)
    }
}
