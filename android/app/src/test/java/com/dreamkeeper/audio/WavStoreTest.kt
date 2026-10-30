package com.dreamkeeper.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.NoSuchFileException

/**
 * 44 字节 canonical 头的落盘契约。
 *
 * 这里断言的是**整 44 字节**，不是只看 offset 4..8 / 40..44：
 * 只盯那两个字段的话，`fmt ` 尾空格写成 `fmt\0`、byteRate / blockAlign / bitsPerSample 写错也照样绿，
 * 而这类头 ffprobe 与播放器会直接不认。
 *
 * 两个黄金 hex 常量是独立的「标准答案」：生产代码不许引用它们，只能靠写出的字节去撞。
 */
class WavStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------------------- create：占位头

    @Test
    fun `create writes exactly 44 placeholder bytes`() {
        val store = WavStore(tmp.root)
        val file = store.create("dream-1")

        assertEquals("create 必须正好落 44 字节占位头", 44L, file.length())
        assertArrayEquals("整 44 字节必须逐字节等于占位头黄金 hex", PLACEHOLDER_HEADER, file.readBytes())
    }

    @Test
    fun `create truncates an existing file`() {
        val store = WavStore(tmp.root)
        val file = store.fileFor("dream-1")
        file.writeBytes(ByteArray(100) { 0x5A })

        val created = store.create("dream-1")

        assertEquals(44L, created.length())
        assertArrayEquals(PLACEHOLDER_HEADER, created.readBytes())
    }

    @Test
    fun `create makes nested parent directories`() {
        val store = WavStore(File(tmp.root, "files/audio"))
        val file = store.create("dream-1")

        assertTrue("rootDir 不存在时必须 mkdirs", file.exists())
        assertEquals(44L, file.length())
    }

    @Test
    fun `fileFor derives wav path without creating it`() {
        val store = WavStore(tmp.root)
        val file = store.fileFor("abc-123")

        assertEquals(File(tmp.root, "abc-123.wav"), file)
        assertFalse("fileFor 只算路径，不许落盘", file.exists())
    }

    // ---------------------------------------------------------------- writer：唯一写句柄

    @Test
    fun `writer counts only pcm bytes across writes`() {
        val store = WavStore(tmp.root)
        val file = store.create("dream-1")
        val writer = store.openWriter(file)
        val chunk = ByteArray(16_000)

        writer.write(chunk, chunk.size)
        assertEquals("写完第一块后只算 PCM", 16_000L, writer.bytesWritten)
        writer.write(chunk, chunk.size)
        assertEquals("第二块累加，不含 44 字节头", 32_000L, writer.bytesWritten)
        writer.finish()
    }

    @Test
    fun `syncToDisk flushes pcm but keeps the placeholder header`() {
        val store = WavStore(tmp.root)
        val file = store.create("dream-1")
        val writer = store.openWriter(file)
        val chunk = ByteArray(3_200) { 0x11 }

        writer.write(chunk, chunk.size)
        writer.syncToDisk()

        assertEquals("sync 后 PCM 必须已在文件里（fsync 本身看不见，长度先对上）", 3_244L, file.length())
        assertArrayEquals("头必须还是占位：回填只能发生在 finish", PLACEHOLDER_HEADER, readHeader(file))

        val state = writer.finish()

        assertFalse(state.placeholder)
        assertEquals(3_200L, state.dataSize)
        assertArrayEquals("finish 之后是 100 ms 那枚标准头", FILLED_HEADER_100MS, readHeader(file))
    }

    @Test
    fun `finish backfills the full canonical header`() {
        val store = WavStore(tmp.root)
        val file = store.create("dream-1")
        val writer = store.openWriter(file)
        writer.write(ByteArray(32_000), 32_000)

        val state = writer.finish()

        assertEquals(32_044L, file.length())
        assertEquals(32_036L, state.riffSize)
        assertEquals(32_000L, state.dataSize)
        assertFalse(state.placeholder)
        assertTrue(state.framesAligned)
        assertArrayEquals("整 44 字节必须逐字节等于回填头黄金 hex", FILLED_HEADER, readHeader(file))
    }

    @Test
    fun `finish is repeatable and keeps bytes stable`() {
        val store = WavStore(tmp.root)
        val file = store.create("dream-1")
        val writer = store.openWriter(file)
        writer.write(ByteArray(32_000), 32_000)
        writer.finish()
        val once = file.readBytes()

        val again = writer.finish()

        assertArrayEquals("重复 finish 不许再改动任何字节", once, file.readBytes())
        assertEquals(32_000L, again.dataSize)
    }

    @Test
    fun `close after finish does not disturb the file`() {
        val store = WavStore(tmp.root)
        val file = store.create("dream-1")
        val writer = store.openWriter(file)
        writer.write(ByteArray(1_000), 1_000)
        writer.finish()
        val finished = file.readBytes()

        writer.close()

        assertArrayEquals(finished, file.readBytes())
    }

    // ---------------------------------------------------------------- repair / state

    @Test
    fun `repair of a filled file is idempotent`() {
        val store = WavStore(tmp.root)
        val file = filledFile(store, "dream-1", dataBytes = 32_000)
        val once = file.readBytes()

        store.repair(file)
        store.repair(file)

        assertArrayEquals("repair 幂等：两次调用后字节完全不变", once, file.readBytes())
    }

    @Test
    fun `repair corrects a placeholder header`() {
        val store = WavStore(tmp.root)
        val file = store.create("dream-1")
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(44)
            raf.write(ByteArray(32_000))
        }

        val state = store.repair(file)

        assertEquals(32_000L, state.dataSize)
        assertEquals(32_036L, state.riffSize)
        assertFalse(state.placeholder)
        assertArrayEquals(FILLED_HEADER, readHeader(file))
    }

    @Test
    fun `repair fixes a stale non-placeholder header`() {
        // 断点续录/被杀进程可能留下「不是占位符但尺寸过期」的头，扫描时也要能纠正
        val store = WavStore(tmp.root)
        val file = filledFile(store, "dream-1", dataBytes = 32_000)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(byteArrayOf(0x39, 0x30, 0x00, 0x00))
        }

        val state = store.repair(file)

        assertEquals(32_036L, state.riffSize)
        assertArrayEquals(FILLED_HEADER, readHeader(file))
    }

    @Test
    fun `repair rejects a file shorter than the header`() {
        val store = WavStore(tmp.root)
        val short = File(tmp.root, "short.wav")
        short.writeBytes(ByteArray(43))

        assertThrows(IllegalStateException::class.java) { store.repair(short) }
    }

    @Test
    fun `store finish repairs and syncs`() {
        val store = WavStore(tmp.root)
        val file = store.create("dream-1")
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(44)
            raf.write(ByteArray(3_200))
        }

        val state = store.finish(file)

        assertEquals(3_200L, state.dataSize)
        assertEquals(100L, WavMath.durationMs(file.length()))
        assertArrayEquals(FILLED_HEADER_100MS, readHeader(file))
    }

    @Test
    fun `state reports placeholder fields`() {
        val store = WavStore(tmp.root)
        val file = store.create("dream-1")

        val state = store.state(file)

        assertEquals(44L, state.fileBytes)
        assertEquals(WavMath.PLACEHOLDER_SIZE, state.riffSize)
        assertEquals(WavMath.PLACEHOLDER_SIZE, state.dataSize)
        assertTrue(state.placeholder)
        assertTrue(state.framesAligned)
    }

    @Test
    fun `state detects a trailing partial frame`() {
        val store = WavStore(tmp.root)
        val file = filledFile(store, "dream-1", dataBytes = 32_000)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(byteArrayOf(0x7F))
        }

        val state = store.state(file)

        assertEquals(32_045L, state.fileBytes)
        assertEquals(1, WavMath.trailingPartialBytes(state.fileBytes))
        assertFalse("多出半个采样点必须判为未对齐", state.framesAligned)
    }

    @Test
    fun `state throws for a missing file`() {
        val store = WavStore(tmp.root)
        val missing = store.fileFor("never-recorded")

        assertThrows(NoSuchFileException::class.java) { store.state(missing) }
    }

    @Test
    fun `openWriter creates a placeholder header for a missing file`() {
        // 生产入口应当自己兜底：即使调用方忘了 create，也不能从第 0 字节开始写 PCM
        val store = WavStore(tmp.root)
        val file = store.fileFor("dream-2")

        val writer = store.openWriter(file)
        assertEquals(44L, file.length())
        writer.write(ByteArray(64), 64)
        val state = writer.finish()

        assertEquals(64L, state.dataSize)
    }

    // ---------------------------------------------------------------- helpers

    /** 只取前 44 字节（头），长度另行断言。 */
    private fun readHeader(file: File): ByteArray = file.readBytes().copyOf(WavMath.HEADER_BYTES)

    /** 造一枚已回填头的 fixture：create + 写 dataBytes 个零 + finish。 */
    private fun filledFile(store: WavStore, dreamId: String, dataBytes: Int): File {
        val file = store.create(dreamId)
        val writer = store.openWriter(file)
        writer.write(ByteArray(dataBytes), dataBytes)
        writer.finish()
        return file
    }

    companion object {
        /** 32044 字节样本的已回填头：riffSize=32036(24 7D 00 00)、dataSize=32000(00 7D 00 00)。 */
        private val FILLED_HEADER = hexOf(
            "52 49 46 46 24 7D 00 00 57 41 56 45 66 6D 74 20 " +
                "10 00 00 00 01 00 01 00 80 3E 00 00 00 7D 00 00 " +
                "02 00 10 00 64 61 74 61 00 7D 00 00",
        )

        /** 3244 字节样本（100ms）的已回填头：riffSize=3236(A4 0C 00 00)、dataSize=3200(80 0C 00 00)。 */
        private val FILLED_HEADER_100MS = hexOf(
            "52 49 46 46 A4 0C 00 00 57 41 56 45 66 6D 74 20 " +
                "10 00 00 00 01 00 01 00 80 3E 00 00 00 7D 00 00 " +
                "02 00 10 00 64 61 74 61 80 0C 00 00",
        )

        /** 44 字节占位头：riffSize / dataSize 均为 FF FF FF FF。 */
        private val PLACEHOLDER_HEADER = hexOf(
            "52 49 46 46 FF FF FF FF 57 41 56 45 66 6D 74 20 " +
                "10 00 00 00 01 00 01 00 80 3E 00 00 00 7D 00 00 " +
                "02 00 10 00 64 61 74 61 FF FF FF FF",
        )

        private fun hexOf(text: String): ByteArray =
            text.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
    }
}
