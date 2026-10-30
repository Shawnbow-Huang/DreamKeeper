package com.dreamkeeper.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * 追加录音的数据层契约（Plan 4 §0.3）。
 *
 * 三条 CAS 的语义都用真 Room（in-memory）测，不 fake DAO——它们是 SQL 语义，
 * 假的实现测不出 `WHERE` 少一个条件、或者拼串变成覆盖。
 *
 * 记号：`T` = transcriptMs（水位），`A` = rawAudioMs（音频总时长）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppendTranscriptTest {

    private lateinit var context: Context
    private lateinit var db: DreamDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, DreamDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun dao() = db.dreamDao()

    /** 造一行：控制 A / T / 状态 / 正文，其余列给稳定默认值。 */
    private fun newRow(
        id: String,
        audioMs: Long?,
        watermarkMs: Long = 0,
        status: String = TranscriptStatus.OK,
        text: String = "",
    ) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO dreams (id, ownerId, dreamDate, createdAt, rawTranscript, novelVersion, " +
                "isDraft, updatedAt, rawAudioPath, rawAudioMs, faithfulEditedByUser, " +
                "novelEditedByUser, transcriptStatus, transcriptMs) " +
                "VALUES (?, 'local', '2026-10-31', 1, ?, 0, 0, 1, ?, ?, 0, 0, ?, ?)",
            arrayOf<Any?>(
                id, text,
                if (audioMs == null) null else "/data/user/0/com.dreamkeeper/files/audio/$id.wav",
                audioMs, status, watermarkMs,
            ),
        )
    }

    private suspend fun row(id: String): DreamEntity {
        val r = dao().getById(id)
        assertNotNull("行 $id 必须存在", r)
        return r!!
    }

    // ------------------------------------------------------------ 首次写回

    @Test
    fun firstTranscriptWriteFillsWatermarkAndText() = runBlocking {
        newRow("d1", audioMs = 2_400L)
        assertEquals(1, dao().updateTranscript("d1", 2_400L, "我把整段梦说出来。", TranscriptStatus.OK))
        val r = row("d1")
        assertEquals("我把整段梦说出来。", r.rawTranscript)
        assertEquals(TranscriptStatus.OK, r.transcriptStatus)
        assertEquals("首写必须把水位推到这次的 A", 2_400L, r.transcriptMs)
    }

    @Test
    fun statusOnlyWriteKeepsWatermarkAtZero() = runBlocking {
        newRow("d2", audioMs = 2_400L, status = TranscriptStatus.PENDING)
        assertEquals(1, dao().updateTranscript("d2", 2_400L, null, TranscriptStatus.FAILED))
        val r = row("d2")
        assertEquals("", r.rawTranscript)
        assertEquals(TranscriptStatus.FAILED, r.transcriptStatus)
        assertEquals("只改状态时必须保持水位不动（null = 只改状态）", 0L, r.transcriptMs)
    }

    // ------------------------------------------------------------ 追加

    @Test
    fun beginAppendMovesAudioMsAndSetsPending() = runBlocking {
        newRow("d3", audioMs = 2_400L, watermarkMs = 2_400L, text = "旧段。")
        assertEquals(1, dao().beginAppend("d3", 3_000L))
        val r = row("d3")
        assertEquals(3_000L, r.rawAudioMs)
        assertEquals(TranscriptStatus.PENDING, r.transcriptStatus)
        assertEquals("水位只由转写推进，beginAppend 不许动它", 2_400L, r.transcriptMs)
        assertEquals("旧正文一个字都不许被 beginAppend 改", "旧段。", r.rawTranscript)
    }

    @Test
    fun beginAppendWithEqualOrSmallerMsIsNoOp() = runBlocking {
        newRow("d4", audioMs = 2_400L, watermarkMs = 2_400L, text = "旧段。")
        assertEquals("等长 = 0 字节追加，不许把 OK 行打回 PENDING", 0, dao().beginAppend("d4", 2_400L))
        assertEquals("更短更不许", 0, dao().beginAppend("d4", 2_000L))
        assertEquals(TranscriptStatus.OK, row("d4").transcriptStatus)
    }

    @Test
    fun appendExtendsTextAndWatermark() = runBlocking {
        newRow("d5", audioMs = 2_400L, watermarkMs = 2_400L, text = "旧段。")
        dao().beginAppend("d5", 3_000L)
        assertEquals(1, dao().appendTranscript("d5", 2_400L, 3_000L, "\n新的一段。", TranscriptStatus.OK))
        val r = row("d5")
        assertEquals("旧段。\n新的一段。", r.rawTranscript)
        assertEquals(3_000L, r.transcriptMs)
        assertEquals(TranscriptStatus.OK, r.transcriptStatus)
    }

    @Test
    fun appendIsIdempotentOnSecondRun() = runBlocking {
        newRow("d6", audioMs = 2_400L, watermarkMs = 2_400L, text = "旧段。")
        dao().beginAppend("d6", 3_000L)
        assertEquals(1, dao().appendTranscript("d6", 2_400L, 3_000L, "\n新的一段。", TranscriptStatus.OK))
        assertEquals("同一段重跑必须 0 行", 0, dao().appendTranscript("d6", 2_400L, 3_000L, "\n新的一段。", TranscriptStatus.OK))
        assertEquals("重跑不许拼第二遍", "旧段。\n新的一段。", row("d6").rawTranscript)
    }

    @Test
    fun appendAfterAnotherAppendMatchesNothing() = runBlocking {
        newRow("d7", audioMs = 2_400L, watermarkMs = 2_400L, text = "旧段。")
        dao().beginAppend("d7", 3_000L)
        assertEquals(0, dao().appendTranscript("d7", 2_400L, 2_900L, "\n错位的段。", TranscriptStatus.OK))
        assertEquals("旧段。", row("d7").rawTranscript)
    }

    @Test
    fun manualEditSurvivesAppend() = runBlocking {
        newRow("d8", audioMs = 2_400L, watermarkMs = 2_400L, text = "")
        // 用户先在手动补字入口写下自己的版本（首写 CAS）
        assertEquals(1, dao().updateTranscript("d8", 2_400L, "我自己改过的原文。", TranscriptStatus.OK))
        dao().beginAppend("d8", 3_000L)
        assertEquals(1, dao().appendTranscript("d8", 2_400L, 3_000L, "\n机器追加的尾段。", TranscriptStatus.OK))
        assertEquals("旧正文原样保留，只在尾部追加", "我自己改过的原文。\n机器追加的尾段。", row("d8").rawTranscript)
    }

    // ------------------------------------------------------------ 重试 / 跳过

    @Test
    fun markTranscriptionRetryOnlyFromFailed() = runBlocking {
        newRow("d9", audioMs = 3_000L, watermarkMs = 2_400L, status = TranscriptStatus.FAILED, text = "旧段。")
        assertEquals(1, dao().markTranscriptionRetry("d9", 3_000L))
        assertEquals(TranscriptStatus.PENDING, row("d9").transcriptStatus)
        assertEquals("已经 OK 的行不许被手动重试打回 PENDING", 0, dao().markTranscriptionRetry("d9", 3_000L))
    }

    @Test
    fun skipUncoveredAudioMovesWatermarkAndKeepsText() = runBlocking {
        newRow("d10", audioMs = 3_000L, watermarkMs = 2_400L, text = "旧段。")
        assertEquals(1, dao().skipUncoveredAudio("d10", 3_000L))
        val r = row("d10")
        assertEquals("跳到音频末尾", 3_000L, r.transcriptMs)
        assertEquals(TranscriptStatus.OK, r.transcriptStatus)
        assertEquals("跳过不转写，正文一个字都不动", "旧段。", r.rawTranscript)
        assertEquals("幂等：再点一次必须 0 行", 0, dao().skipUncoveredAudio("d10", 3_000L))
    }
}
