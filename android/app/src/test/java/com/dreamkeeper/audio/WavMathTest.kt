package com.dreamkeeper.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WAV 数学与 44 字节头字段的唯一定义，用断言把它钉死。
 *
 * 一套 16kHz / 单声道 / 16bit 的 PCM：每毫秒 32 字节（16000 * 2 / 1000），
 * 44 字节之后才是 PCM。所有「时长」「对齐」「字节 ↔ 毫秒」的换算都不许在别处另写一份。
 */
class WavMathTest {

    // ---------------------------------------------------------------- 时长与数据段

    @Test
    fun `duration of header only file is zero`() {
        // 只有头、没有 PCM 的文件时长为 0（不是 1，也不能是负数）
        assertEquals(0L, WavMath.durationMs(44))
    }

    @Test
    fun `duration of one second file is 1000ms`() {
        // 44 字节头 + 16000 帧 * 2 字节 = 32044 字节，正好 1 秒
        assertEquals(1000L, WavMath.durationMs(44L + 32_000L))
    }

    @Test
    fun `duration of half frame file is zero`() {
        // 一个不完整的采样点（1 字节）不足以构成 1ms，向下取整到 0
        assertEquals(0L, WavMath.durationMs(45))
    }

    @Test
    fun `dataBytes ignores header`() {
        assertEquals(32_000L, WavMath.dataBytes(32_044))
    }

    @Test
    fun `dataBytes clamps negative to zero`() {
        // 43 字节连头都不完整，不能算出 -1 这种鬼值
        assertEquals(0L, WavMath.dataBytes(43))
    }

    @Test
    fun `bytesForMs is thirty two bytes per millisecond`() {
        assertEquals(3_200L, WavMath.bytesForMs(100))
        assertEquals(0L, WavMath.bytesForMs(0))
        assertEquals(32L, WavMath.bytesForMs(1))
    }

    // ---------------------------------------------------------------- 帧对齐（尾部半帧）

    @Test
    fun `trailingPartialBytes of undersized file is zero`() {
        // 43 字节 < 44，按 0 处理而不是负数
        assertEquals(0, WavMath.trailingPartialBytes(43))
    }

    @Test
    fun `trailingPartialBytes of odd data length is one`() {
        assertEquals(1, WavMath.trailingPartialBytes(45))
    }

    @Test
    fun `trailingPartialBytes of aligned data is zero`() {
        assertEquals(0, WavMath.trailingPartialBytes(46))
        assertEquals(0, WavMath.trailingPartialBytes(44))
        assertEquals(0, WavMath.trailingPartialBytes(32_044))
    }

    // ---------------------------------------------------------------- 小端读写

    @Test
    fun `writeU32 writes little endian byte order`() {
        // 32000 = 0x00007D00，小端必须落成 00 7D 00 00（不是 00 7D 00 00 反着的大端）
        val buf = ByteArray(4)
        WavMath.writeU32(buf, 0, 32_000L)
        assertEquals(0x00, buf[0].toInt() and 0xFF)
        assertEquals(0x7D, buf[1].toInt() and 0xFF)
        assertEquals(0x00, buf[2].toInt() and 0xFF)
        assertEquals(0x00, buf[3].toInt() and 0xFF)
    }

    @Test
    fun `writeU32 truncates to low 32 bits`() {
        // 0x1_FFFF_FFFF 的低 32 位是 0xFFFFFFFF
        val buf = ByteArray(4)
        WavMath.writeU32(buf, 0, 0x1_FFFF_FFFFL)
        assertEquals(0xFF, buf[0].toInt() and 0xFF)
        assertEquals(0xFF, buf[1].toInt() and 0xFF)
        assertEquals(0xFF, buf[2].toInt() and 0xFF)
        assertEquals(0xFF, buf[3].toInt() and 0xFF)
    }

    @Test
    fun `writeU32 then readU32 round trips max unsigned value`() {
        // 0xFFFFFFFF 必须读回 4294967295L，不能变成 -1
        val buf = ByteArray(4)
        WavMath.writeU32(buf, 0, 0xFFFF_FFFFL)
        assertEquals(4_294_967_295L, WavMath.readU32(buf, 0))
    }

    @Test
    fun `readU32 reads little endian fields at nonzero offset`() {
        // 用回填头的真实字节：offset 4..8 = 32036，offset 24..28 = 16000
        val header = hex(
            "52 49 46 46 24 7D 00 00 57 41 56 45 66 6D 74 20 " +
                "10 00 00 00 01 00 01 00 80 3E 00 00 00 7D 00 00 " +
                "02 00 10 00 64 61 74 61 00 7D 00 00",
        )
        assertEquals(44, header.size)
        assertEquals(32_036L, WavMath.readU32(header, 4))
        assertEquals(16_000L, WavMath.readU32(header, 24))
        assertEquals(32_000L, WavMath.readU32(header, 28))
        assertEquals(32_000L, WavMath.readU32(header, 40))
    }

    // ---------------------------------------------------------------- 常量契约

    @Test
    fun `format constants match the recording contract`() {
        // AudioRecord 侧要用的常量，本 Task 不许删（16kHz / 2 字节帧 / 44 字节头）
        assertEquals(44, WavMath.HEADER_BYTES)
        assertEquals(16_000, WavMath.SAMPLE_RATE)
        assertEquals(2, WavMath.FRAME_BYTES)
        assertEquals(32L, WavMath.BYTES_PER_MS)
        assertEquals(4_294_967_295L, WavMath.PLACEHOLDER_SIZE)
    }

    private fun hex(text: String): ByteArray =
        text.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
}
