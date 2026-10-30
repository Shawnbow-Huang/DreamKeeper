package com.dreamkeeper.audio

/**
 * 16kHz / 单声道 / 16bit PCM 的 WAV 数学与 44 字节 canonical 头。
 *
 * 全项目只许有这一份「字节 ↔ 毫秒 / 数据段 / 头字段」的定义，
 * 生产路径（AudioRecord 写盘）与测试、扫描、修复共用它，避免两套头各写各的。
 *
 * 一律小端：[writeU32] / [readU32] 是唯一入口。
 * 注意 [java.io.DataOutputStream.writeInt] 是**大端**，不要拿它写头。
 */
object WavMath {

    /** canonical WAV 头固定 44 字节。 */
    const val HEADER_BYTES = 44

    /** 占位期 riffSize / dataSize 的哨兵值（无符号 32 位最大值）。 */
    const val PLACEHOLDER_SIZE = 0xFFFF_FFFFL

    /** 采样率 16kHz；本 Task 暂不调用，留给 AudioRecord（别删）。 */
    const val SAMPLE_RATE = 16_000

    /** 16bit 单声道一帧 2 字节；本 Task 暂不调用，留给 AudioRecord（别删）。 */
    const val FRAME_BYTES = 2

    /** 16000Hz * 2B / 1000ms = 32 字节每毫秒。 */
    const val BYTES_PER_MS = 32L

    /** PCM 数据段字节数：max(0, fileBytes - 44)。 */
    fun dataBytes(fileBytes: Long): Long = (fileBytes - HEADER_BYTES).coerceAtLeast(0L)

    /** 时长毫秒：dataBytes / 32（向下取整）。 */
    fun durationMs(fileBytes: Long): Long = dataBytes(fileBytes) / BYTES_PER_MS

    /** 尾部凑不满一帧的字节数：(fileBytes-44) % 2；fileBytes < 44 时返回 0。 */
    fun trailingPartialBytes(fileBytes: Long): Int {
        val data = fileBytes - HEADER_BYTES
        if (data <= 0L) return 0
        return (data % FRAME_BYTES).toInt()
    }

    /** 给定时长需要的 PCM 字节数：ms * 32。 */
    fun bytesForMs(ms: Long): Long = ms * BYTES_PER_MS

    /** 小端写 32 位无符号值；内部截到低 32 位（value.and(0xFFFFFFFFL)）。 */
    fun writeU32(buf: ByteArray, offset: Int, value: Long) {
        val v = value and 0xFFFF_FFFFL
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /** 小端读 32 位无符号值，返回 0..4294967295。 */
    fun readU32(buf: ByteArray, offset: Int): Long {
        val b0 = buf[offset].toLong() and 0xFF
        val b1 = buf[offset + 1].toLong() and 0xFF
        val b2 = buf[offset + 2].toLong() and 0xFF
        val b3 = buf[offset + 3].toLong() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
