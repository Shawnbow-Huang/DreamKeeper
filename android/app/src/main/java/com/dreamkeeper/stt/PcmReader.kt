package com.dreamkeeper.stt

import com.dreamkeeper.audio.WavMath
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV → 16kHz 单声道 float 采样的读法（纯 Kotlin，没有 native）。
 *
 * 单独拆出来的原因有两个：
 * 1. 内置识别器（[SenseVoiceEngine]）按段喂采样，段边界算错一个采样点，
 *    识别结果就会在接缝处吞字；这段数学必须能在 JVM 单测里被钉住。
 * 2. 系统识别器走的是「整个文件推进管道」，不需要这个类；两条链路各有各的容器假设，
 *    不要为了复用把一个的假设塞给另一个。
 *
 * 只接受本项目自己写的 canonical WAV：44 字节头、16bit、单声道。
 * 头里的字段一律不信——长度以文件实际字节为准（占位期文件头写过哨兵值）。
 */
internal object PcmReader {

    /** 一帧 2 字节（16bit 单声道）。 */
    private const val FRAME_BYTES = 2

    /** 采样总数：数据段字节数 / 2。 */
    fun sampleCount(file: File): Int {
        val dataBytes = WavMath.dataBytes(file.length())
        return (dataBytes / FRAME_BYTES).toInt()
    }

    /**
     * 读 `[startSample, startSample + count)` 这一段，转成 `[-1, 1]` 的 float。
     *
     * 越界一律夹到文件末尾：录音正在写、最后一段不足一帧、调用方算大了 count，
     * 都只该拿到「实际有的那部分」，不该抛异常也不该补零——补零会让识别器
     * 把静音尾巴当成语气停顿，反而更慢。
     */
    fun read(file: File, startSample: Int, count: Int): FloatArray {
        if (startSample < 0 || count <= 0) return FloatArray(0)
        val total = sampleCount(file)
        if (startSample >= total) return FloatArray(0)
        val n = minOf(count, total - startSample)
        val bytes = ByteArray(n * FRAME_BYTES)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(WavMath.HEADER_BYTES.toLong() + startSample.toLong() * FRAME_BYTES)
            raf.readFully(bytes)
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { buffer.short / 32768f }
    }
}
