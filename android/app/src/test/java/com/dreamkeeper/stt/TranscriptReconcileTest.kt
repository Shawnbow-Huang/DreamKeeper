package com.dreamkeeper.stt

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.DreamEntity
import com.dreamkeeper.data.db.TranscriptStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

/**
 * 启动补跑（reconcile）的两条真机事故（2026-11-01，0.11.2）：
 *
 * ① `cacheDir/stt-tail` 从未创建 → `FileOutputStream` ENOENT → 异常被外层吞掉，
 *    行永远停在 PENDING，界面一直「正在转写…」（vivo 92 秒录音的现场）；
 * ② 源音频丢失时同样只剩一条日志，状态还是 PENDING，用户没有重试出口。
 *
 * 修法：切分前 `mkdirs()`；切分/转写异常一律 `markAppendFailed`（→ FAILED）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptReconcileTest {

    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository
    private lateinit var audioStore: WavStore
    private lateinit var root: File

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            DreamDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = DreamRepository(db) { 1_000L }
        root = File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "reconcile-test-${System.nanoTime()}",
        )
        audioStore = WavStore(root)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private suspend fun seed(id: String, ms: Long, path: String?) {
        db.dreamDao().upsert(
            DreamEntity(
                id = id,
                dreamDate = LocalDate.of(2026, 11, 1),
                createdAt = 0L,
                title = null,
                rawTranscript = "",
                faithfulText = null,
                novelText = null,
                isDraft = true,
                rawAudioPath = path,
                rawAudioMs = ms,
                transcriptStatus = TranscriptStatus.PENDING,
            ),
        )
    }

    /** 2 秒的合法 WAV（16k/单声道/16bit）。 */
    private fun wav(id: String, ms: Long): File {
        val file = audioStore.create(id)
        FileOutputStream(file, true).use { it.write(ByteArray((ms * 32).toInt())) }
        audioStore.finish(file)
        return file
    }

    private class FakeEngine(private val result: SttResult = SttResult.Ok("补跑出来的文字")) : SttEngine {
        var calls = 0
        override suspend fun availability(): SttAvailability = SttAvailability.Available
        override suspend fun transcribe(file: File): SttResult {
            calls++
            assertTrue("交给引擎的尾段文件必须真的存在", file.exists())
            return result
        }
    }

    @Test
    fun `临时目录不存在也能补跑并写回`() = runTest {
        val ms = 2_000L
        val file = wav("d1", ms)
        seed("d1", ms, file.path)
        // 关键：父目录故意不创建，复现 cacheDir/stt-tail 缺失的现场。
        val tempDir = File(root, "stt-tail-never-created")
        assertTrue(!tempDir.exists())
        val engine = FakeEngine()
        val writer = TranscriptWriter(repo, audioStore, engine, tempDir = tempDir)

        val attempts = writer.reconcilePending()

        assertEquals(1, attempts)
        assertEquals(1, engine.calls)
        assertTrue("补跑必须自己把临时目录建出来", tempDir.isDirectory)
        val got = repo.findById("d1")!!
        assertEquals(TranscriptStatus.OK, got.transcriptStatus)
        assertEquals("补跑出来的文字", got.rawTranscript.trim())
    }

    @Test
    fun `源音频丢失时标 FAILED 而不是永远 PENDING`() = runTest {
        seed("d1", 2_000L, File(root, "gone/missing.wav").path)
        val engine = FakeEngine()
        val writer = TranscriptWriter(repo, audioStore, engine, tempDir = File(root, "tail"))

        writer.reconcilePending()

        val got = repo.findById("d1")!!
        assertEquals("切分失败不能停留在 PENDING", TranscriptStatus.FAILED, got.transcriptStatus)
        assertEquals(0, engine.calls)
    }
}
