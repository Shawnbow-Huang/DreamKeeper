package com.dreamkeeper.stt

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.DreamEntity
import com.dreamkeeper.data.db.TranscriptStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Task 7 / brief 7.1–7.3 + dispatch §0.5–0.7：
 * 转写结果的写回必须是**窄更新**，并在三种情况下让位：
 * ① 用户已经在正文里打了字（CAS 的 `TRIM(rawTranscript)=''` 挡住）；
 * ② 录音与转写并发（占位头/正在写/数据段长度变了 → 丢弃，状态留 PENDING）；
 * ③ 空串、纯标点不算 Ok。
 *
 * 为什么用真内存 Room：CAS 的 `TRIM(rawTranscript)`、`COALESCE`、`rawAudioMs`
 * 是 SQL 行为，用假 DAO 断言不了。Robolectric 只是为了拿到 Context 建库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptWriterTest {

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
            "stt-test-${System.nanoTime()}",
        )
        audioStore = WavStore(root)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    // ------------------------------------------------------------ Ok / Failed

    @Test
    fun `ok result writes transcript and marks OK`() = runTest {
        seed("d1", ms = 1_000)
        val writer = TranscriptWriter(repo, audioStore, FakeEngine())

        val rows = writer.apply("d1", 1_000, SttResult.Ok("  我在电梯里  "))

        assertEquals(1, rows)
        val got = repo.findById("d1")!!
        assertEquals(TranscriptStatus.OK, got.transcriptStatus)
        assertEquals("我在电梯里", got.rawTranscript)
    }

    @Test
    fun `failed result marks FAILED and keeps existing text`() = runTest {
        seed("d1", ms = 1_000, text = "用户自己写的一句话")
        val writer = TranscriptWriter(repo, audioStore, FakeEngine())

        val rows = writer.apply("d1", 1_000, SttResult.Failed("没有中文离线包"))

        assertEquals(1, rows)
        val got = repo.findById("d1")!!
        assertEquals(TranscriptStatus.FAILED, got.transcriptStatus)
        assertEquals("用户自己写的一句话", got.rawTranscript)
    }

    @Test
    fun `empty ok text is treated as failed`() = runTest {
        seed("d1", ms = 1_000)
        val writer = TranscriptWriter(repo, audioStore, FakeEngine())

        writer.apply("d1", 1_000, SttResult.Ok("   "))

        val got = repo.findById("d1")!!
        assertEquals(TranscriptStatus.FAILED, got.transcriptStatus)
        assertEquals("", got.rawTranscript)
    }

    @Test
    fun `punctuation only ok text is treated as failed`() = runTest {
        seed("d1", ms = 1_000)
        val writer = TranscriptWriter(repo, audioStore, FakeEngine())

        writer.apply("d1", 1_000, SttResult.Ok("。，！？…"))

        val got = repo.findById("d1")!!
        assertEquals(TranscriptStatus.FAILED, got.transcriptStatus)
    }

    // ------------------------------------------------------------- CAS 让位

    @Test
    fun `user text typed during transcription wins`() = runTest {
        seed("d1", ms = 1_000, text = "用户刚刚手打的一句")
        val writer = TranscriptWriter(repo, audioStore, FakeEngine())

        val rows = writer.apply("d1", 1_000, SttResult.Ok("识别结果"))

        assertEquals(0, rows)
        val got = repo.findById("d1")!!
        assertEquals("用户刚刚手打的一句", got.rawTranscript)
        assertEquals(TranscriptStatus.PENDING, got.transcriptStatus)
    }

    @Test
    fun `stale rawAudioMs does not write`() = runTest {
        seed("d1", ms = 1_000)
        val writer = TranscriptWriter(repo, audioStore, FakeEngine())

        val rows = writer.apply("d1", 999, SttResult.Ok("识别结果"))

        assertEquals(0, rows)
        val got = repo.findById("d1")!!
        assertEquals("", got.rawTranscript)
        assertEquals(TranscriptStatus.PENDING, got.transcriptStatus)
    }

    // -------------------------------------------------- 与补录并发的状态闸门

    @Test
    fun `placeholder during transcription discards result`() = runTest {
        val file = wav("d1", dataBytes = 32_000)
        seed("d1", ms = 1_000)
        val engine = FakeEngine(duringTranscribe = { audioStore.markOpen(file) })
        val writer = TranscriptWriter(repo, audioStore, engine)

        writer.onRecordingFinished("d1", file, 1_000)

        val got = repo.findById("d1")!!
        assertEquals("", got.rawTranscript)
        assertEquals(TranscriptStatus.PENDING, got.transcriptStatus)
    }

    @Test
    fun `growing data segment during transcription discards result`() = runTest {
        val file = wav("d1", dataBytes = 32_000)
        seed("d1", ms = 1_000)
        val engine = FakeEngine(duringTranscribe = {
            FileOutputStream(file, true).use { it.write(ByteArray(3_200)) }
            audioStore.finish(file)
        })
        val writer = TranscriptWriter(repo, audioStore, engine)

        writer.onRecordingFinished("d1", file, 1_000)

        val got = repo.findById("d1")!!
        assertEquals("", got.rawTranscript)
        assertEquals(TranscriptStatus.PENDING, got.transcriptStatus)
    }

    // ------------------------------------------------------- 触发链路的成功

    @Test
    fun `recording finished transcribes and writes through`() = runTest {
        val file = wav("d1", dataBytes = 32_000)
        seed("d1", ms = 1_000)
        val engine = FakeEngine(result = SttResult.Ok("梦到电梯一直往上"))
        val writer = TranscriptWriter(repo, audioStore, engine)

        writer.onRecordingFinished("d1", file, 1_000)

        assertEquals(1, engine.transcribeCalls)
        val got = repo.findById("d1")!!
        assertEquals(TranscriptStatus.OK, got.transcriptStatus)
        assertEquals("梦到电梯一直往上", got.rawTranscript)
    }

    @Test
    fun `waits for row updated to new rawAudioMs before writing`() = runTest {
        val file = wav("d1", dataBytes = 32_000)
        // 追问补录：行已存在，但 rawAudioMs 还是上一段的 1_000；ViewModel 稍后才 attachAudio 到 2_000。
        seed("d1", ms = 1_000)
        val engine = FakeEngine(result = SttResult.Ok("补录之后的新内容"))
        val writer = TranscriptWriter(repo, audioStore, engine)

        val job = launch { writer.onRecordingFinished("d1", file, 2_000) }
        delay(300)
        db.dreamDao().setAudioMs("d1", 2_000)
        job.join()

        assertEquals(1, engine.transcribeCalls)
        val got = repo.findById("d1")!!
        assertEquals(TranscriptStatus.OK, got.transcriptStatus)
        assertEquals("补录之后的新内容", got.rawTranscript)
    }

    @Test
    fun `unavailable engine marks FAILED without transcribing`() = runTest {
        val file = wav("d1", dataBytes = 32_000)
        seed("d1", ms = 1_000)
        val engine = FakeEngine(availability = SttAvailability.Unavailable("只有在线识别"))
        val writer = TranscriptWriter(repo, audioStore, engine)

        writer.onRecordingFinished("d1", file, 1_000)

        assertEquals(0, engine.transcribeCalls)
        val got = repo.findById("d1")!!
        assertEquals(TranscriptStatus.FAILED, got.transcriptStatus)
        assertFalse(engine.transcribed)
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun seed(
        id: String,
        ms: Long,
        text: String = "",
        status: String = TranscriptStatus.PENDING,
    ) {
        db.dreamDao().upsert(
            DreamEntity(
                id = id,
                dreamDate = LocalDate.of(2026, 10, 30),
                createdAt = 0L,
                title = null,
                rawTranscript = text,
                faithfulText = null,
                novelText = null,
                isDraft = true,
                rawAudioPath = null,
                rawAudioMs = ms,
                transcriptStatus = status,
            ),
        )
    }

    private fun wav(id: String, dataBytes: Int): File {
        val file = audioStore.create(id)
        FileOutputStream(file, true).use { it.write(ByteArray(dataBytes)) }
        return audioStore.finish(file).let { file }
    }

    /** 只实现被 Writer 用到的两条；`duringTranscribe` 用来模拟「补录抢在写回前」。 */
    private class FakeEngine(
        private val availability: SttAvailability = SttAvailability.Available,
        private val result: SttResult = SttResult.Ok("我在电梯里"),
        private val duringTranscribe: (suspend () -> Unit)? = null,
    ) : SttEngine {
        var transcribeCalls = 0
        var transcribed = false

        override suspend fun availability(): SttAvailability = availability

        override suspend fun transcribe(file: File): SttResult {
            transcribeCalls++
            transcribed = true
            duringTranscribe?.invoke()
            return result
        }
    }
}
