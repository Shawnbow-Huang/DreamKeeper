package com.dreamkeeper.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 整行 `save()` 与转写水位（`transcriptMs`）的关系（Plan 4 T1 · D423）。
 *
 * 水位只能由追加 CAS 推进；`save()` 是编辑器/补录共用的整行写回路径，调用方手里
 * 可能是一个**不认识新列的旧 Dream 对象**（默认 `transcriptMs=0`）。若 `save()`
 * 直接写 `dream.transcriptMs`，这类调用会把水位抹回 0 —— 后果是下次追加把已经
 * 转写过的段落重新拼进正文（重复文本），且 UI 会认为整段音频都还没转写。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DreamRepositoryTranscriptWatermarkTest {

    private lateinit var context: Context
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, DreamDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = DreamRepository(db) { 1_000L }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun dream(
        id: String,
        text: String,
        watermarkMs: Long,
        audioMs: Long,
    ) = Dream(
        id = id,
        date = LocalDate.of(2026, 10, 31),
        title = "t",
        rawTranscript = text,
        faithfulText = null,
        novelText = null,
        novelChangeLog = null,
        mood = null,
        isDraft = false,
        transcriptStatus = TranscriptStatus.OK,
        rawAudioPath = "/data/audio/$id.wav",
        rawAudioMs = audioMs,
        transcriptMs = watermarkMs,
    )

    @Test
    fun legacyDreamObjectCannotResetWatermark() = runBlocking {
        repo.save(dream("d1", "旧转写", watermarkMs = 2_400L, audioMs = 2_400L))
        val loaded = repo.findById("d1")
        assertNotNull(loaded)
        assertEquals(2_400L, loaded!!.transcriptMs)

        // 旧 caller 的等价形态：拿到 domain 后 copy 出新对象，认不得新列 → 默认 0。
        repo.save(loaded.copy(title = "改个标题", transcriptMs = 0L))

        val after = db.dreamDao().getById("d1")
        assertNotNull(after)
        assertEquals("水位必须保留", 2_400L, after!!.transcriptMs)
        assertEquals("改个标题", after.title)
    }

    @Test
    fun newRowKeepsExplicitWatermark() = runBlocking {
        repo.save(dream("d2", "带水位的首行", watermarkMs = 1_200L, audioMs = 1_200L))
        assertEquals(1_200L, db.dreamDao().getById("d2")!!.transcriptMs)
    }
}
