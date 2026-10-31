package com.dreamkeeper.audio

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 启动扫描的四主状态 + 一补充（§0-7）+ 半帧（§0-8）+ 44 字节（§0-9）+ 打开中（§0-3）。
 *
 * 期望的毫秒数一律写**字面量**（如 `44 + 3200` 字节 → `100` ms）：用 `WavMath.durationMs`
 * 算期望值等于让被测代码给自己出题，`durationMs` 写错时用例照样绿。
 *
 * fixture 用字节直接造（不走 [WavStore.create]），只有「正在录音」的两个用例故意走
 * `create` —— 它会把文件登记进「当前打开」集合，扫描必须跳过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioScanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: DreamDatabase
    private lateinit var repository: DreamRepository
    private lateinit var store: WavStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DreamDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = DreamRepository(db) { FIXED_NOW }
        store = WavStore(tmp.root)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- ① 有行 + 占位头

    @Test
    fun `repairs a placeholder file for an existing row and fixes the duration`() = runTest {
        val file = placeholderFile(ROW_ID, dataBytes = 3200)
        seedRow(ROW_ID, path = file.absolutePath, ms = 0L)

        val outcomes = scan()

        assertEquals(listOf<ScanOutcome>(ScanOutcome.Repaired(ROW_ID, 3244L)), outcomes)
        val state = store.state(file)
        assertFalse("两个尺寸字段都必须回填", state.placeholder)
        assertEquals("riffSize = 3244 - 8", 3236L, state.riffSize)
        assertEquals("dataSize = 3244 - 44", 3200L, state.dataSize)
        val dream = dream(ROW_ID)!!
        assertEquals("3200 字节 / 32 = 100 ms", 100L, dream.rawAudioMs)
        // A1（Plan 5 T5）：文件变长且水位没追平时要置 PENDING，启动 reconcile 才会补转写。
        assertEquals("有未覆盖音频时状态置 PENDING", TranscriptStatus.PENDING, dream.transcriptStatus)
        assertEquals("窄更新不许碰 updatedAt", FIXED_NOW, dream.updatedAt)
    }

    @Test
    fun `a second scan of a repaired row is a noop and changes neither bytes nor updatedAt`() = runTest {
        val file = placeholderFile(ROW_ID, dataBytes = 3200)
        seedRow(ROW_ID, path = file.absolutePath, ms = 0L)
        scan()

        val bytes = file.readBytes()
        val mtime = file.lastModified()
        val outcomes = scan()

        assertEquals(listOf<ScanOutcome>(ScanOutcome.Noop), outcomes)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(mtime, file.lastModified())
        assertEquals(FIXED_NOW, dream(ROW_ID)!!.updatedAt)
    }

    @Test
    fun `fixes a stale duration even when the header is already clean`() = runTest {
        // 上一次扫描修好了头、但 fixAudioMs 没落库（进程又挂了）：文件是唯一真源，必须补齐。
        val file = cleanFile(ROW_ID, dataBytes = 3200)
        seedRow(ROW_ID, path = file.absolutePath, ms = 0L)

        val outcomes = scan()

        assertEquals(listOf<ScanOutcome>(ScanOutcome.Repaired(ROW_ID, 3244L)), outcomes)
        assertEquals(100L, dream(ROW_ID)!!.rawAudioMs)
        assertEquals(FIXED_NOW, dream(ROW_ID)!!.updatedAt)
    }

    // -------------------------------------------------- A1：文件变长 → 置 PENDING

    @Test
    fun `a grown file with an uncovered tail is marked pending so reconcile picks it up`() = runTest {
        // 行里记的是 0ms（追加时被杀），文件已有 300ms：水位 0 < 300 → 必须置 PENDING。
        val file = cleanFile(ROW_ID, dataBytes = 9_600)
        seedRow(ROW_ID, path = file.absolutePath, ms = 0L, transcript = "旧的一段")

        scan()

        val dream = dream(ROW_ID)!!
        assertEquals("300 字节换算 = 300ms", 300L, dream.rawAudioMs)
        assertEquals(TranscriptStatus.PENDING, dream.transcriptStatus)
        assertEquals("已经转过的正文不许动", "旧的一段", dream.rawTranscript)
        assertEquals(FIXED_NOW, dream.updatedAt)
    }

    @Test
    fun `a grown file whose watermark already covers it keeps the old status`() = runTest {
        // 罕见但合法：行里 transcriptMs 已经等于文件长度（比如手动跳过），扫描不应把 OK 打回 PENDING。
        val file = cleanFile(ROW_ID, dataBytes = 9_600)
        seedRow(
            ROW_ID,
            path = file.absolutePath,
            ms = 100L,
            transcript = "旧的一段",
            watermarkMs = 300L,
        )

        scan()

        val dream = dream(ROW_ID)!!
        assertEquals(300L, dream.rawAudioMs)
        assertEquals("水位已追平，状态保持 OK", TranscriptStatus.OK, dream.transcriptStatus)
    }

    // ---------------------------------------------------------------- ② 有文件无行

    @Test
    fun `recovers an orphan placeholder file as a draft using the file mtime`() = runTest {
        val file = placeholderFile(ORPHAN_ID, dataBytes = 6400) // 200 ms
        file.setLastModified(ORPHAN_MTIME)

        val outcomes = scan()

        assertEquals(listOf<ScanOutcome>(ScanOutcome.RecoveredDraft(ORPHAN_ID, 6444L)), outcomes)
        val dream = dream(ORPHAN_ID) ?: error("孤儿文件必须恢复成草稿")
        assertTrue(dream.isDraft)
        assertEquals(TranscriptStatus.PENDING, dream.transcriptStatus)
        assertEquals("", dream.rawTranscript)
        assertNull(dream.title)
        assertEquals(file.absolutePath, dream.rawAudioPath)
        assertEquals("6400 字节 / 32 = 200 ms", 200L, dream.rawAudioMs)
        // §0-6：日期与 createdAt 取文件 mtime，不许用「今天」
        val expectedDate = Instant.ofEpochMilli(ORPHAN_MTIME)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals(expectedDate, dream.date)
        assertEquals(ORPHAN_MTIME, dream.createdAt)
        assertEquals(ORPHAN_MTIME, dream.updatedAt)
        // 文件头也要修好，证据脚本才能绿
        val state = store.state(file)
        assertFalse(state.placeholder)
        assertEquals(6436L, state.riffSize)
        assertEquals(6400L, state.dataSize)
    }

    @Test
    fun `recovery is idempotent and does not reset createdAt`() = runTest {
        val file = placeholderFile(ORPHAN_ID, dataBytes = 3200)
        file.setLastModified(ORPHAN_MTIME)
        scan()

        val outcomes = scan()

        assertEquals(listOf<ScanOutcome>(ScanOutcome.Noop), outcomes)
        assertEquals(1, db.dreamDao().count())
        val dream = dream(ORPHAN_ID)!!
        assertEquals(ORPHAN_MTIME, dream.createdAt)
        assertEquals(ORPHAN_MTIME, dream.updatedAt)
        assertEquals(100L, dream.rawAudioMs)
    }

    @Test
    fun `trims a trailing partial frame before repairing an orphan`() = runTest {
        val file = placeholderFile(ORPHAN_ID, dataBytes = 3201) // 3200 + 半帧
        file.setLastModified(ORPHAN_MTIME)

        val outcomes = scan()

        assertEquals("奇数尾字节先截掉，再按 3200 字节算", 3244L, file.length())
        assertEquals(listOf<ScanOutcome>(ScanOutcome.RecoveredDraft(ORPHAN_ID, 3244L)), outcomes)
        assertEquals(100L, dream(ORPHAN_ID)!!.rawAudioMs)
        assertEquals(3200L, store.state(file).dataSize)
    }

    @Test
    fun `trims a trailing partial frame so a later take can mark the file open`() = runTest {
        // §0-8 的真实后果：不截半帧，markOpen 会永久拒绝追加，这枚 dream 再也补录不了。
        val file = placeholderFile(ROW_ID, dataBytes = 3201)
        seedRow(ROW_ID, path = file.absolutePath, ms = 0L)

        assertEquals(listOf<ScanOutcome>(ScanOutcome.Repaired(ROW_ID, 3244L)), scan())

        store.markOpen(file) // 不抛就是通过
        assertTrue(store.state(file).placeholder)
        assertEquals(100L, dream(ROW_ID)!!.rawAudioMs)
    }

    // ---------------------------------------------------------------- ③ 有行无文件

    @Test
    fun `marks a row missing and keeps its text when the file is gone`() = runTest {
        val missing = File(tmp.root, "$ROW_ID.wav")
        seedRow(ROW_ID, path = missing.absolutePath, ms = 300L, transcript = "原来的文本")

        val outcomes = scan()

        assertEquals(listOf<ScanOutcome>(ScanOutcome.OrphanCleared(ROW_ID)), outcomes)
        val dream = dream(ROW_ID)!!
        assertNull("路径必须清掉，否则详情页会指向不存在的文件", dream.rawAudioPath)
        assertNull(dream.rawAudioMs)
        assertEquals(TranscriptStatus.FAILED, dream.transcriptStatus)
        assertEquals("窄更新不许碰文本", "原来的文本", dream.rawTranscript)
        assertEquals("窄更新不许碰 updatedAt", FIXED_NOW, dream.updatedAt)
        assertEquals("已经清干净，重跑不该再报一次", emptyList<ScanOutcome>(), scan())
    }

    // ---------------------------------------------------------------- ④ 干净：Noop

    @Test
    fun `a clean file is left byte for byte and the db is not written`() = runTest {
        val file = cleanFile(ROW_ID, dataBytes = 3200)
        seedRow(ROW_ID, path = file.absolutePath, ms = 100L)
        val bytes = file.readBytes()
        val mtime = file.lastModified()

        val outcomes = scan()

        assertEquals(listOf<ScanOutcome>(ScanOutcome.Noop), outcomes)
        assertArrayEquals("字节不许变", bytes, file.readBytes())
        assertEquals("mtime 不许变", mtime, file.lastModified())
        assertEquals("DB 也不许写（updatedAt 是写过的证据）", FIXED_NOW, dream(ROW_ID)!!.updatedAt)
        assertEquals(100L, dream(ROW_ID)!!.rawAudioMs)
    }

    // ---------------------------------------------------------------- ⑤ 0 字节 / < 44 / 正好 44

    @Test
    fun `a zero byte orphan is deleted without creating a row`() = runTest {
        val file = File(tmp.root, "$ORPHAN_ID.wav").apply { writeBytes(ByteArray(0)) }

        val outcomes = scan()

        assertEquals(listOf<ScanOutcome>(ScanOutcome.Noop), outcomes)
        assertFalse(file.exists())
        assertFalse(repository.hasDream(ORPHAN_ID))
    }

    @Test
    fun `a file shorter than the header with a row is deleted and the row marked missing`() = runTest {
        val file = File(tmp.root, "$ROW_ID.wav").apply { writeBytes(ByteArray(20)) }
        seedRow(ROW_ID, path = file.absolutePath, ms = 0L)

        val outcomes = scan()

        assertEquals(listOf<ScanOutcome>(ScanOutcome.OrphanCleared(ROW_ID)), outcomes)
        assertFalse(file.exists())
        assertNull(dream(ROW_ID)!!.rawAudioPath)
        assertEquals(TranscriptStatus.FAILED, dream(ROW_ID)!!.transcriptStatus)
    }

    @Test
    fun `exactly 44 bytes with no row is treated as empty`() = runTest {
        // §0-9：dataSize=0 的条目不许留成 0:00
        val file = placeholderFile(ORPHAN_ID, dataBytes = 0)

        val outcomes = scan()

        assertEquals(listOf<ScanOutcome>(ScanOutcome.Noop), outcomes)
        assertFalse(file.exists())
        assertFalse(repository.hasDream(ORPHAN_ID))
    }

    @Test
    fun `exactly 44 bytes with a row marks it missing`() = runTest {
        val file = placeholderFile(ROW_ID, dataBytes = 0)
        seedRow(ROW_ID, path = file.absolutePath, ms = 0L)

        val outcomes = scan()

        assertEquals(listOf<ScanOutcome>(ScanOutcome.OrphanCleared(ROW_ID)), outcomes)
        assertFalse(file.exists())
        assertNull(dream(ROW_ID)!!.rawAudioPath)
        assertEquals(TranscriptStatus.FAILED, dream(ROW_ID)!!.transcriptStatus)
    }

    @Test
    fun `a zero byte file with a row marks the row missing`() = runTest {
        val file = File(tmp.root, "$ROW_ID.wav").apply { writeBytes(ByteArray(0)) }
        seedRow(ROW_ID, path = file.absolutePath, ms = 0L)

        assertEquals(listOf<ScanOutcome>(ScanOutcome.OrphanCleared(ROW_ID)), scan())
        assertFalse(file.exists())
        assertNull(dream(ROW_ID)!!.rawAudioPath)
    }

    // ---------------------------------------------------------------- 打开中：必须跳过

    @Test
    fun `skips a file that is being recorded right now`() = runTest {
        // 正常录音中：占位头 + 已有 PCM + 库无行。扫描若把它当孤儿，会把行先插上，
        // 收尾时 RecordViewModel 的 alreadyPersisted 就会把 DONE 吞掉（§0-3）。
        val file = store.create(ORPHAN_ID)
        appendPcm(file, 3200)

        val outcomes = scan()

        assertEquals(listOf<ScanOutcome>(ScanOutcome.Noop), outcomes)
        assertEquals("正在写的文件一个字节都不许动", 3244L, file.length())
        assertTrue(store.state(file).placeholder)
        assertFalse("不许给正在录的 take 建行", repository.hasDream(ORPHAN_ID))
    }

    @Test
    fun `skips repairing a row whose file is being recorded`() = runTest {
        val file = store.create(ROW_ID)
        appendPcm(file, 3200)
        seedRow(ROW_ID, path = file.absolutePath, ms = 0L)

        val outcomes = scan()

        assertEquals(listOf<ScanOutcome>(ScanOutcome.Noop), outcomes)
        assertTrue("补录中头必须保持占位", store.state(file).placeholder)
        assertEquals("补录中不许改库里的时长", 0L, dream(ROW_ID)!!.rawAudioMs)
        assertEquals(FIXED_NOW, dream(ROW_ID)!!.updatedAt)
    }

    // ---------------------------------------------------------------- 多文件 / 空目录

    @Test
    fun `scans every file even when one of them is an empty orphan`() = runTest {
        File(tmp.root, "$ZERO_ID.wav").writeBytes(ByteArray(0))
        val orphan = placeholderFile(ORPHAN_ID, dataBytes = 3200)

        val outcomes = scan()

        assertEquals(
            listOf(
                ScanOutcome.Noop,
                ScanOutcome.RecoveredDraft(ORPHAN_ID, 3244L),
            ),
            outcomes,
        )
        assertEquals(1, db.dreamDao().count())
        assertFalse(File(tmp.root, "$ZERO_ID.wav").exists())
        assertFalse(store.state(orphan).placeholder)
    }

    @Test
    fun `an empty audio directory yields no outcomes`() = runTest {
        assertEquals(emptyList<ScanOutcome>(), scan())
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun scan(): List<ScanOutcome> = AudioScan(store, repository).run()

    private suspend fun dream(id: String): Dream? = repository.observe(id).first()

    private suspend fun seedRow(
        id: String,
        path: String?,
        ms: Long?,
        transcript: String = "",
        status: String = TranscriptStatus.OK,
        draft: Boolean = false,
        watermarkMs: Long = 0L,
    ) {
        repository.save(
            Dream(
                id = id,
                date = LocalDate.of(2026, 10, 29),
                title = null,
                rawTranscript = transcript,
                faithfulText = null,
                novelText = null,
                novelChangeLog = null,
                mood = null,
                isDraft = draft,
                transcriptStatus = status,
                rawAudioPath = path,
                rawAudioMs = ms,
                transcriptMs = watermarkMs,
            ),
        )
    }

    /** 44 字节占位头 + dataBytes 个零字节 PCM（不经 store，避免被登记成「打开中」）。 */
    private fun placeholderFile(id: String, dataBytes: Int): File =
        File(tmp.root, "$id.wav").apply { writeBytes(PLACEHOLDER_HEADER + ByteArray(dataBytes)) }

    /** 已回填头的干净文件：总长 44 + dataBytes。 */
    private fun cleanFile(id: String, dataBytes: Int): File {
        val total = 44L + dataBytes
        val header = PLACEHOLDER_HEADER.copyOf()
        WavMath.writeU32(header, 4, total - 8)
        WavMath.writeU32(header, 40, total - 44)
        return File(tmp.root, "$id.wav").apply { writeBytes(header + ByteArray(dataBytes)) }
    }

    private fun appendPcm(file: File, bytes: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(ByteArray(bytes))
        }
    }

    companion object {
        private const val FIXED_NOW = 1_000L

        /** 1_700_000_000_000 = 2023-11-14T22:13:20Z，与 FIXED_NOW 差得足够远，抓「用了今天」。 */
        private const val ORPHAN_MTIME = 1_700_000_000_000L

        private const val ROW_ID = "11111111-1111-4111-8111-111111111111"
        private const val ORPHAN_ID = "22222222-2222-4222-8222-222222222222"
        private const val ZERO_ID = "00000000-0000-4000-8000-000000000000"

        /** canonical 44 字节占位头，与 WavStoreTest 的黄金 hex 独立（生产代码不引用它）。 */
        private val PLACEHOLDER_HEADER = hexOf(
            "52 49 46 46 FF FF FF FF 57 41 56 45 66 6D 74 20 " +
                "10 00 00 00 01 00 01 00 80 3E 00 00 00 7D 00 00 " +
                "02 00 10 00 64 61 74 61 FF FF FF FF",
        )

        private fun hexOf(text: String): ByteArray =
            text.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
    }
}
