package com.dreamkeeper.stt

import com.dreamkeeper.audio.WavMath
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 把「追加录音文件」切成「只含新增尾段」的临时 WAV（Plan 4 §0.4）。
 *
 * 为什么需要它：系统的文件转写器（[SttEngine.transcribe]）只接受整文件、内部按整段
 * `<data>` 处理；把整段再转一次会**重复**已经转写过的旧文字。做法是复制 canonical 头，
 * 只取 `[44 + fromMs*32, EOF)` 的 PCM 数据段，写出一个最多 44 字节头 + 新增数据的小文件。
 *
 * 口径与 [WavMath] 完全一致：16kHz / 单声道 / 16bit / 32 字节每毫秒 / 44 字节头。
 * 越界或无新增数据时返回 `0`（调用方按「无有效文字」处理，不得伪造成功）。
 */
object TailWav {

    /**
     * 生成 [out]（覆盖写）：44 字节 canonical 头 + `[fromMs, EOF)` 的数据段。
     *
     * @return 写出的**数据**字节数（不含头）；`0` 表示没有增量（`fromMs` 已到或超过末尾）。
     * @throws java.io.IOException 源文件不存在、短于 44 字节、或目标写失败时抛出。
     */
    fun sliceTo(source: File, fromMs: Long, out: File): Long {
        if (!source.exists()) throw java.io.FileNotFoundException(source.path)
        val fileBytes = source.length()
        if (fileBytes < WavMath.HEADER_BYTES) {
            throw java.io.IOException("WAV 头不完整：$fileBytes 字节（${source.path}）")
        }
        val from = (fromMs * WavMath.BYTES_PER_MS).coerceAtLeast(0L)
        val start = WavMath.HEADER_BYTES + from
        val dataBytes = (fileBytes - start).coerceAtLeast(0L)
        if (dataBytes <= 0L) {
            // 没有增量：仍写出一个合法空 WAV（44 字节、dataSize=0），让调用方拿到 0。
            FileOutputStream(out, false).use { it.write(emptyWavHeader()) }
            return 0L
        }

        val header = ByteArray(WavMath.HEADER_BYTES)
        RandomAccessFile(source, "r").use { src ->
            src.seek(0)
            src.readFully(header)
            src.seek(start)
            // 覆盖 RIFF chunkSize（4..7）与 data chunkSize（40..43）：只按本次数据段重写，
            // 源文件占位期可能是 0xFFFFFFFF，不能原样抄。
            WavMath.writeU32(header, 4, WavMath.HEADER_BYTES - 8 + dataBytes)
            WavMath.writeU32(header, 40, dataBytes)

            FileOutputStream(out, false).use { dst ->
                var written = 0L
                dst.write(header)
                val buf = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = src.read(buf)
                    if (read <= 0) break
                    dst.write(buf, 0, read)
                    written += read
                }
                if (written != dataBytes) {
                    throw java.io.IOException("尾段长度不符：期望 $dataBytes 实际 $written（${source.path}）")
                }
            }
        }
        return dataBytes
    }

    /** 时长口径：数据字节 / 32（与 `WavMath.durationMs` 一致）。 */
    fun durationMsOf(dataBytes: Long): Long = dataBytes / WavMath.BYTES_PER_MS

    private fun emptyWavHeader(): ByteArray {
        val header = ByteArray(WavMath.HEADER_BYTES)
        // "RIFF" + size 36 + "WAVEfmt " + 16 + PCM(1) + channels(1) + rate 16000 + byteRate 32000
        // + blockAlign 2 + bits 16 + "data" + size 0 —— 与 WavStore 的 canonical 头逐字节同构。
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        WavMath.writeU32(header, 4, (WavMath.HEADER_BYTES - 8).toLong())
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        WavMath.writeU32(header, 16, 16L)
        header[20] = 1.toByte(); header[21] = 0   // PCM
        header[22] = 1.toByte(); header[23] = 0   // mono
        WavMath.writeU32(header, 24, WavMath.SAMPLE_RATE.toLong())
        WavMath.writeU32(header, 28, WavMath.SAMPLE_RATE.toLong() * WavMath.FRAME_BYTES)
        header[32] = WavMath.FRAME_BYTES.toByte(); header[33] = 0
        header[34] = 16.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        WavMath.writeU32(header, 40, 0L)
        return header
    }

    private const val BUFFER_BYTES = 8 * 1024
}
