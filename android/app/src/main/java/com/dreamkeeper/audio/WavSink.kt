package com.dreamkeeper.audio

/**
 * 把 [WavWriter] 接到 [PcmSink] 上的适配层。
 *
 * 两个接口只差三处，所以不值得让 `WavWriter` 去实现 `PcmSink`：
 * - `bytesWritten` 在 writer 上是**属性**、在 sink 上是**方法**；
 * - writer 没有 `sync()`（录音中要每秒落盘），由 [WavWriter.syncToDisk] 补上；
 * - `finish()` 在 writer 上返回 [WavState]，sink 侧不关心。
 *
 * 约束：**一个 writer 只配一个 sink**，`write`/`sync`/`finish` 只在录音线程调用。
 */
class WavSink(private val writer: WavWriter) : PcmSink {

    override fun write(buffer: ByteArray, length: Int) {
        writer.write(buffer, length)
    }

    override fun sync() {
        writer.syncToDisk()
    }

    override fun bytesWritten(): Long = writer.bytesWritten

    override fun finish() {
        writer.finish()
    }
}
