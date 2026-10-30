package com.dreamkeeper.audio

/** 音频源的生命周期状态。 */
enum class SourceState {
    /** 正在采集。 */
    RECORDING,

    /** 被正常停止（不是错误）。 */
    STOPPED,

    /** 采集设备/线程已死，读循环必须收尾。 */
    DEAD,
}

/**
 * [RecorderCore] 唯一的音频来源。
 *
 * 实现约定：
 * - [read] 返回写进 buffer 的**字节数**（16 bit 单声道，必须为偶数）：
 *   - `> 0`：读到一块 PCM；
 *   - `== 0`：暂时没有数据，**不是**结束，读循环要继续；
 *   - `< 0`：读错误，读循环必须结束。
 * - [state] 用于区分「被外部停止」（[SourceState.STOPPED]）与「设备已死」（[SourceState.DEAD]）。
 * - [stop] 只请求停止，不保证立即返回。
 */
interface PcmSource {
    fun read(buffer: ByteArray): Int

    fun state(): SourceState

    fun stop()
}

/**
 * [RecorderCore] 唯一的写出目标。
 *
 * [bytesWritten] 是**文件内 PCM 的绝对字节数**（不含 44 B WAV 头），不是本次 run 的增量。
 * 补零同样通过 [write] 写出，因此也会推进 [bytesWritten]。
 */
interface PcmSink {
    /** 写出 [buffer] 的前 [length] 字节；[length] 必须为偶数。 */
    fun write(buffer: ByteArray, length: Int)

    /** 把已写内容刷到磁盘。 */
    fun sync()

    /** 文件内 PCM 的绝对字节数（不含 WAV 头）。 */
    fun bytesWritten(): Long

    /** 收尾：回填 WAV 头并关闭文件。 */
    fun finish()
}

/**
 * 一次录音的收尾结果。
 *
 * @param bytes 本次 run 的真实 PCM 字节数，不含补零。
 * @param zeroFillBytes 本次 run 补的零字节数，恒为偶数。
 * @param reason 结束原因，只有 `dead` / `stopped` / `user` / `error` 四种。
 */
data class RecorderStop(
    val bytes: Long,
    val zeroFillBytes: Long,
    val reason: String,
)
