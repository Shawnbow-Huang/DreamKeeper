package com.dreamkeeper.stt

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.audio.WavMath
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 追加录音 → 尾段转写 → 后缀 CAS 写回（Plan 4 §0.3–§0.4，T2）。
 *
 * 全部用真 Room（in-memory）+ 假 STT：这些断言的失败模式是**重复文字/丢文字**，
 * 用假 DAO 测不出来。假引擎只替掉「系统识别」这一跳，并把喂进去的临时文件留档。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptAppendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository
    private lateinit var store: WavStore
    private lateinit var engine: FakeEngine
    private lateinit var writer: TranscriptWriter

    private class FakeEngine(
        texts: List<String> = listOf("新的尾段"),
    ) : SttEngine {
        val queue = ArrayDeque(texts)
        val fed = mutableListOf<ByteArray>()
        var availabilityCalls = 0
        /** 非 null 时，transcribe 会先挂起等它放行（用于测「在飞期间又收尾」）。 */
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        override suspend fun availability(): SttAvailability {
            availabilityCalls++
            return SttAvailability.Available
        }

        override suspend fun transcribe(file: File): SttResult {
            fed += file.readBytes()
            entered.complete(Unit)
            gate?.await()
            val text = if (queue.isEmpty()) "兜底文本" else queue.removeFirst()
            return SttResult.Ok(text)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, DreamDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = DreamRepository(db) { 1_000L }
        store = WavStore(tmp.root)
        engine = FakeEngine()
        writer = TranscriptWriter(repo, store, engine, tempDir = tmp.root)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 造一条带音频的梦：`A` = 音频时长，`T` = 水位，文件按 A 写满。 */
    private fun newDream(
        id: String,
        text: String,
        audioMs: Long,
        watermarkMs: Long,
        status: String = TranscriptStatus.OK,
    ): File {
        val file = File(tmp.root, "$id.wav")
        RandomAccessFile(file, "rw").use { raf ->
            val header = ByteArray(WavMath.HEADER_BYTES)
            "RIFF".toByteArray().copyInto(header, 0)
            WavMath.writeU32(header, 4, WavMath.HEADER_BYTES - 8 + audioMs * 32)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            WavMath.writeU32(header, 16, 16)
            header[20] = 1; header[22] = 1
            WavMath.writeU32(header, 24, 16_000)
            WavMath.writeU32(header, 28, 32_000)
            header[32] = 2; header[34] = 16
            "data".toByteArray().copyInto(header, 36)
            WavMath.writeU32(header, 40, audioMs * 32)
            raf.write(header)
            raf.write(ByteArray((audioMs * 32).toInt()) { 7 })
        }
        runBlocking {
            repo.save(
                Dream(
                    id = id,
                    date = LocalDate.of(2026, 10, 31),
                    title = "t",
                    rawTranscript = text,
                    faithfulText = null,
                    novelText = null,
                    novelChangeLog = null,
                    mood = null,
                    isDraft = false,
                    transcriptStatus = status,
                    rawAudioPath = file.path,
                    rawAudioMs = audioMs,
                    transcriptMs = watermarkMs,
                )
            )
        }
        return file
    }

    private fun row(id: String) = runBlocking { db.dreamDao().getById(id)!! }

    @Test
    fun appendExtendsOnlyTheTail() = runBlocking {
        val file = newDream("d1", "旧句", audioMs = 2_000L, watermarkMs = 1_000L)
        writer.onAppendFinished("d1", file)

        val after = row("d1")
        assertEquals("旧句\n新的尾段", after.rawTranscript)
        assertEquals(2_000L, after.transcriptMs)
        assertEquals(TranscriptStatus.OK, after.transcriptStatus)
        // 喂给识别器的只有 1000ms 的尾段（32B/ms），不是整段 2000ms
        assertEquals(1, engine.fed.size)
        assertEquals(1_000L * 32, engine.fed[0].size - 44L)
    }

    @Test
    fun noUncoveredAudioSkipsWithoutCallingEngine() = runBlocking {
        val file = newDream("d2", "已齐", audioMs = 1_000L, watermarkMs = 1_000L)
        writer.onAppendFinished("d2", file)

        assertEquals("已齐", row("d2").rawTranscript)
        assertEquals(0, engine.fed.size)
        assertEquals(0, engine.availabilityCalls)
    }

    @Test
    fun garbledTailMarksFailedAndKeepsText() = runBlocking {
        engine = FakeEngine(listOf("。。。"))
        writer = TranscriptWriter(repo, store, engine, tempDir = tmp.root)
        val file = newDream("d3", "旧句", audioMs = 2_000L, watermarkMs = 1_000L)
        writer.onAppendFinished("d3", file)

        val after = row("d3")
        assertEquals("旧句", after.rawTranscript)
        assertEquals(1_000L, after.transcriptMs)
        assertEquals(TranscriptStatus.FAILED, after.transcriptStatus)
    }

    @Test
    fun blankTailMarksFailed() = runBlocking {
        engine = FakeEngine(listOf("   "))
        writer = TranscriptWriter(repo, store, engine, tempDir = tmp.root)
        val file = newDream("d4", "旧句", audioMs = 2_000L, watermarkMs = 1_000L)
        writer.onAppendFinished("d4", file)

        assertEquals(TranscriptStatus.FAILED, row("d4").transcriptStatus)
        assertEquals("旧句", row("d4").rawTranscript)
    }

    @Test
    fun inFlightFinishQueuesOneRerunWithFreshWatermark() = runBlocking {
        engine = FakeEngine(listOf("第一段", "第二段"))
        val gate = CompletableDeferred<Unit>()
        engine.gate = gate
        writer = TranscriptWriter(repo, store, engine, tempDir = tmp.root)
        val file = newDream("d5", "旧句", audioMs = 2_000L, watermarkMs = 1_000L)

        val first = async { writer.onAppendFinished("d5", file) }
        engine.entered.await()
        // 录音又长了一截（service 先落库再入队），并再次触发收尾
        runBlocking { repo.beginAppend("d5", 3_000L) }
        val second = async { writer.onAppendFinished("d5", file) }
        yield()
        gate.complete(Unit)
        first.await(); second.await()

        val after = row("d5")
        assertEquals(3_000L, after.transcriptMs)
        assertEquals(TranscriptStatus.OK, after.transcriptStatus)
        assertTrue("最终只应拼上最后一段：${after.rawTranscript}", after.rawTranscript.endsWith("第二段"))
        assertFalse("第一段属于旧水位，不能落库", after.rawTranscript.contains("第一段"))
        assertEquals(2, engine.fed.size)
    }

    @Test
    fun reconcilePicksPendingRows() = runBlocking {
        val file = newDream(
            "d6", "旧句", audioMs = 2_000L, watermarkMs = 1_000L,
            status = TranscriptStatus.PENDING,
        )
        val attempts = writer.reconcilePending()
        assertEquals(1, attempts)
        val after = row("d6")
        assertEquals("旧句\n新的尾段", after.rawTranscript)
        assertEquals(2_000L, after.transcriptMs)
        assertEquals(TranscriptStatus.OK, after.transcriptStatus)
        assertTrue(file.exists())
    }

    @Test
    fun reconcileSkipsCoveredFailedAndSkippedRows() = runBlocking {
        newDream("d7", "已齐", audioMs = 1_000L, watermarkMs = 1_000L)
        newDream("d8", "失败", audioMs = 2_000L, watermarkMs = 1_000L, status = TranscriptStatus.FAILED)
        newDream("d9", "跳过", audioMs = 2_000L, watermarkMs = 2_000L)

        assertEquals(0, writer.reconcilePending())
        assertEquals(0, engine.fed.size)
        assertEquals("已齐", row("d7").rawTranscript)
        assertEquals("失败", row("d8").rawTranscript)   // FAILED 不自动重跑
    }
}
