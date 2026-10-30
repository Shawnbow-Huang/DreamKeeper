package com.dreamkeeper.audio

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.NoSuchFileException

/**
 * 一份 WAV 在某一时刻的头/尺寸快照。
 *
 * @property fileBytes 整文件字节数（含 44 字节头）
 * @property riffSize 头里 offset 4..8 的无符号值
 * @property dataSize 头里 offset 40..44 的无符号值
 * @property placeholder 两个尺寸字段**任一**仍是 0xFFFFFFFF 就是 true
 * @property framesAligned fileBytes >= 44 且数据段是偶数（半个采样点不算一帧）
 */
data class WavState(
    val fileBytes: Long,
    val riffSize: Long,
    val dataSize: Long,
    val placeholder: Boolean,
    val framesAligned: Boolean,
)

/**
 * 生产路径与测试路径**共用**这一个写出入口，避免两套头各写各的。
 *
 * 头在 [WavStore.create] 时写成 44 字节占位（两个尺寸 = 0xFFFFFFFF），
 * 录音过程中只追加 PCM，收尾 [finish] 一次回填两个尺寸。
 */
interface WavWriter : Closeable {
    /** 只算 PCM，不含 44 字节头（追加已有录音时从已有 PCM 起算）。 */
    val bytesWritten: Long

    fun write(buffer: ByteArray, length: Int)

    /** sync() → 回填两字段 → close()；可重复调用。 */
    fun finish(): WavState
}

/**
 * 落盘层：一个 dream 一枚 `<dreamId>.wav`，全部放在 [rootDir] 下。
 *
 * 扫描/离线修复走 [repair] / [finish]；[state] 只读不写。
 * 空文件/半截头（< 44 字节）没有可读的头，抛 [IllegalStateException]；
 * 文件不存在抛 [NoSuchFileException]（与 java.nio 语义一致）。
 */
class WavStore(private val rootDir: File) {

    /** 只算路径，不落盘。 */
    fun fileFor(dreamId: String): File = File(rootDir, "$dreamId.wav")

    /** mkdirs + 截断重写 44 字节占位头（已存在也重来）。 */
    fun create(dreamId: String): File {
        val file = fileFor(dreamId)
        file.parentFile?.mkdirs()
        FileOutputStream(file, false).use { it.write(placeholderHeader()) }
        return file
    }

    /** 唯一写句柄；文件缺失或头不完整时先补占位头。写完必须 [WavWriter.finish]。 */
    fun openWriter(file: File): WavWriter {
        file.parentFile?.mkdirs()
        if (!file.exists() || file.length() < WavMath.HEADER_BYTES) {
            FileOutputStream(file, false).use { it.write(placeholderHeader()) }
        }
        return PcmWavWriter(file)
    }

    /** 扫描/离线用：等价 repair + sync。 */
    fun finish(file: File): WavState {
        val state = repair(file)
        RandomAccessFile(file, "rw").use { it.fd.sync() }
        return state
    }

    /** 幂等：两字段与 fileBytes 对不上才回填，对得上一个字节都不动。 */
    fun repair(file: File): WavState {
        if (!file.exists()) throw NoSuchFileException(file.path)
        val fileBytes = file.length()
        if (fileBytes < WavMath.HEADER_BYTES) {
            throw IllegalStateException("WAV 头不完整：只有 $fileBytes 字节，至少需要 ${WavMath.HEADER_BYTES} 字节（${file.path}）")
        }
        val header = readHeader(file)
        val expectedRiff = fileBytes - 8
        val expectedData = fileBytes - WavMath.HEADER_BYTES
        val needsRepair =
            WavMath.readU32(header, 4) != expectedRiff || WavMath.readU32(header, 40) != expectedData
        if (needsRepair) {
            RandomAccessFile(file, "rw").use { raf ->
                writeSizes(raf, fileBytes)
                raf.fd.sync()
            }
        }
        return state(file)
    }

    /** 只读；不对齐/占位都原样报告，修不修由调用方决定。 */
    fun state(file: File): WavState {
        if (!file.exists()) throw NoSuchFileException(file.path)
        val fileBytes = file.length()
        if (fileBytes < WavMath.HEADER_BYTES) {
            throw IllegalStateException("WAV 头不完整：只有 $fileBytes 字节，至少需要 ${WavMath.HEADER_BYTES} 字节（${file.path}）")
        }
        val header = readHeader(file)
        val riffSize = WavMath.readU32(header, 4)
        val dataSize = WavMath.readU32(header, 40)
        return WavState(
            fileBytes = fileBytes,
            riffSize = riffSize,
            dataSize = dataSize,
            placeholder = riffSize == WavMath.PLACEHOLDER_SIZE || dataSize == WavMath.PLACEHOLDER_SIZE,
            framesAligned = (fileBytes - WavMath.HEADER_BYTES) % WavMath.FRAME_BYTES == 0L,
        )
    }

    private fun readHeader(file: File): ByteArray {
        val header = ByteArray(WavMath.HEADER_BYTES)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        return header
    }

    /** 小端回填 offset 4..8 与 40..44。 */
    private fun writeSizes(raf: RandomAccessFile, fileBytes: Long) {
        val field = ByteArray(4)
        WavMath.writeU32(field, 0, fileBytes - 8)
        raf.seek(4)
        raf.write(field)
        WavMath.writeU32(field, 0, fileBytes - WavMath.HEADER_BYTES)
        raf.seek(40)
        raf.write(field)
    }

    /** canonical 44 字节占位头：两个尺寸字段都是 0xFFFFFFFF。 */
    private fun placeholderHeader(): ByteArray {
        val header = ByteArray(WavMath.HEADER_BYTES)
        val byteRate = WavMath.SAMPLE_RATE.toLong() * WavMath.FRAME_BYTES
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        WavMath.writeU32(header, 4, WavMath.PLACEHOLDER_SIZE)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        WavMath.writeU32(header, 16, 16) // fmt chunk 长度
        header[20] = 1 // PCM
        header[22] = 1 // 单声道
        WavMath.writeU32(header, 24, WavMath.SAMPLE_RATE.toLong())
        WavMath.writeU32(header, 28, byteRate)
        header[32] = WavMath.FRAME_BYTES.toByte() // blockAlign
        header[34] = 16 // bitsPerSample
        "data".toByteArray(Charsets.US_ASCII).copyInto(header, 36)
        WavMath.writeU32(header, 40, WavMath.PLACEHOLDER_SIZE)
        return header
    }

    /** 追加写 PCM；finish 时回填并关文件。 */
    private inner class PcmWavWriter(private val file: File) : WavWriter {
        private val raf = RandomAccessFile(file, "rw").also { it.seek(it.length()) }
        private var finished = false

        override var bytesWritten: Long = WavMath.dataBytes(raf.length())
            private set

        override fun write(buffer: ByteArray, length: Int) {
            require(length in 0..buffer.size) { "length ($length) 超出 buffer 大小 (${buffer.size})" }
            check(!finished) { "writer 已 finish，不能再写" }
            raf.write(buffer, 0, length)
            bytesWritten += length
        }

        override fun finish(): WavState {
            if (!finished) {
                raf.fd.sync() // 先把 PCM 落稳，再回填尺寸
                writeSizes(raf, raf.length())
                raf.fd.sync()
                raf.close()
                finished = true
            }
            return state(file)
        }

        /** 忘了显式 finish 也不许把占位头留在盘上。 */
        override fun close() {
            if (!finished) finish()
        }
    }
}
