package com.dreamkeeper.audio

/**
 * 长录音守卫（Plan 5 T5 / Voice AI A4）。
 *
 * 为什么是「开录前查空间 + 到 4h 自动收尾」：
 * 16 kHz/mono/16 bit = 32 B/ms ≈ 1.92 MB/min，4h 约 460 MB；旧代码没有任何上限，
 * 存储写满时 AudioRecord 会静默停读、用户以为还在录。这里只加最小守卫，
 * 不做分段上传/压缩（留 P9 真机长录验证）。
 */
object RecordingLimits {
    /** 单次录音硬上限：4 小时。 */
    const val MAX_DURATION_MS = 4L * 60 * 60 * 1000

    /** 开录前要求的最小剩余空间：128 MB（约 1 小时录音）。 */
    const val MIN_FREE_BYTES = 128L * 1024 * 1024

    /** 空间是否够开一次录音。 */
    fun canStart(freeBytes: Long): Boolean = freeBytes >= MIN_FREE_BYTES

    /** 是否到了自动收尾点（录音线程每次进度回调只查这个纯函数）。 */
    fun shouldAutoStop(elapsedMs: Long): Boolean = elapsedMs >= MAX_DURATION_MS
}
