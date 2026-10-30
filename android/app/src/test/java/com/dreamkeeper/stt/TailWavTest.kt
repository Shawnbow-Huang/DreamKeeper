package com.dreamkeeper.stt

import com.dreamkeeper.audio.WavMath
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [TailWav] 的字节级契约（Plan 4 §0.4）。
 *
 * 这个类决定「追加转写会不会重复旧文字」，所以断言必须落在**字节**与**头字段**上，
 * 不能只断言「文件非空」。口径与 [WavMath] 同源：16kHz / mono / 16bit / 32B 每毫秒 / 44B 头。
 */
class TailWavTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeSource(dataBytes: Long): File {
        val f = tmp.newFile("src.wav")
        RandomAccessFile(f, "rw").use { raf ->
            val header = ByteArray(WavMath.HEADER_BYTES)
            "RIFF".toByteArray().copyInto(header, 0)
            WavMath.writeU32(header, 4, WavMath.HEADER_BYTES - 8 + dataBytes)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            WavMath.writeU32(header, 16, 16)
            header[20] = 1; header[22] = 1
            WavMath.writeU32(header, 24, 16_000)
            WavMath.writeU32(header, 28, 32_000)
            header[32] = 2; header[34] = 16
            "data".toByteArray().copyInto(header, 36)
            WavMath.writeU32(header, 40, dataBytes)
            raf.write(header)
            val data = ByteArray(dataBytes.toInt()) { i -> (i % 251).toByte() }
            raf.write(data)
        }
        return f
    }

    @Test
    fun slicesOnlyTailDataFromMidpoint() {
        val src = writeSource(3_200L)          // 100ms
        val out = tmp.newFile("tail.wav")
        val bytes = TailWav.sliceTo(src, fromMs = 40L, out = out)

        assertEquals(60L * WavMath.BYTES_PER_MS, bytes)                    // 60ms
        assertEquals(WavMath.HEADER_BYTES + bytes, out.length())
        assertEquals(60L, TailWav.durationMsOf(bytes))
    }

    @Test
    fun writesCanonicalHeaderFieldsForSlice() {
        val src = writeSource(3_200L)
        val out = tmp.newFile("tail.wav")
        val bytes = TailWav.sliceTo(src, fromMs = 40L, out = out)

        val header = out.readBytes().copyOf(WavMath.HEADER_BYTES)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        assertEquals(WavMath.HEADER_BYTES - 8 + bytes, WavMath.readU32(header, 4))
        assertEquals(bytes, WavMath.readU32(header, 40))
        assertEquals(16_000L, WavMath.readU32(header, 24))
        assertEquals(32_000L, WavMath.readU32(header, 28))
        assertEquals("blockAlign", 2, header[32].toInt())
        assertEquals("bitsPerSample", 16, header[34].toInt())
    }

    @Test
    fun sliceDataEqualsSourceRange() {
        val src = writeSource(3_200L)
        val out = tmp.newFile("tail.wav")
        val bytes = TailWav.sliceTo(src, fromMs = 40L, out = out)

        val srcBytes = src.readBytes()
        val outBytes = out.readBytes()
        assertTrue(srcBytes.size == 44 + 3_200)
        assertTrue(outBytes.size == 44 + bytes.toInt())
        val start = 44 + 40 * 32
        for (i in 0 until bytes.toInt()) {
            assertEquals("偏移 $i 不一致", srcBytes[start + i], outBytes[44 + i])
        }
    }

    @Test
    fun wholeFileSliceWhenFromZero() {
        val src = writeSource(640L)
        val out = tmp.newFile("tail.wav")
        val bytes = TailWav.sliceTo(src, fromMs = 0L, out = out)
        assertEquals(640L, bytes)
        assertEquals(20L, TailWav.durationMsOf(bytes))
    }

    @Test
    fun beyondEndReturnsZeroAndWritesEmptyWav() {
        val src = writeSource(640L)            // 20ms
        val out = tmp.newFile("tail.wav")
        val bytes = TailWav.sliceTo(src, fromMs = 200L, out = out)
        assertEquals(0L, bytes)
        assertEquals(WavMath.HEADER_BYTES.toLong(), out.length())
        val header = out.readBytes()
        assertEquals(0L, WavMath.readU32(header, 40))
    }

    @Test(expected = java.io.IOException::class)
    fun shortSourceThrows() {
        val f = tmp.newFile("short.wav")
        f.writeBytes(ByteArray(10))
        TailWav.sliceTo(f, fromMs = 0L, out = tmp.newFile("tail.wav"))
    }
}
