package com.dreamkeeper.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 录音读循环：从 [source] 读 PCM，按单调钟补齐丢失的等长零帧，写进 [sink]。
 *
 * 核心不变量（单位是**样本**，1 ms = 16 样本）：
 * ```
 * deficitSamples = (read 之前的 clock() - startMs) * 16 - (已写 PCM 字节 + 刚读到的块字节) / 2
 * ```
 * 当 `deficitSamples > minGapMs * 16` 时，先补 `deficitSamples * 2` 字节零，再写刚读到的真实块。
 * 时钟在调用 [PcmSource.read] **之前**采样，所以 read 阻塞的时间不算缺口；`read() == 0`
 * 表示暂时没数据，读循环继续。
 *
 * [rebase] 用于「暂停后继续」：只把窗口原点挪到当前 `clock()` / `sink.bytesWritten()`，
 * 不会改动 sink 已写字节数。
 *
 * @param clock 单调时钟（毫秒），由调用方注入，测试里用假钟。
 * @param minGapMs 补零门槛，默认 100 ms。
 * @param silenceFloorDb 静音块的振幅下限，默认 -60 dBFS。
 */
class RecorderCore(
    private val source: PcmSource,
    private val sink: PcmSink,
    private val clock: () -> Long,
    private val minGapMs: Long = 100L,
    private val silenceFloorDb: Int = -60,
) {

    init {
        require(minGapMs >= 0L) { "minGapMs 不能为负：$minGapMs" }
        require(silenceFloorDb <= 0) { "silenceFloorDb 必须 <= 0：$silenceFloorDb" }
    }

    /** 单次 read 的缓冲固定 100 ms；[PcmSource] 必须能接受任意大小（≥ 一块）的 buffer。 */
    private val buffer = ByteArray(READ_BUFFER_BYTES)

    private var startMs = 0L
    private var baseBytes = 0L
    private var userStopped = false

    /** 把当前窗口的原点挪到「现在」：`startMs = clock()`、`baseBytes = sink.bytesWritten()`。 */
    fun rebase() {
        startMs = clock()
        baseBytes = sink.bytesWritten()
    }

    /** 用户主动停止：置位 user 标记并转达给 [source]。 */
    fun stop() {
        userStopped = true
        source.stop()
    }

    /**
     * 跑读循环直到源结束/出错/用户停止，收尾时 sync + finish 各一次。
     *
     * [startedAtMs] 是本次录音开始时的单调钟读数；[onChunk] 每写入一块**真实** PCM 回调一次，
     * `bytes` 是本次 run 的真实字节累计（不含补零）。
     */
    fun run(startedAtMs: Long, onChunk: (bytes: Long, amplitudeDb: Int) -> Unit): RecorderStop {
        startMs = startedAtMs
        baseBytes = sink.bytesWritten()

        var realBytes = 0L
        var zeroBytes = 0L
        var syncedAtBytes = sink.bytesWritten()

        fun finishWith(reason: String): RecorderStop {
            var finalReason = reason
            // sync 与 finish 分开兜：sync 失败（磁盘满、IO 错）也必须尝试 finish 回填头，
            // 否则文件永远停在 0xFFFFFFFF 占位头，只能靠启动扫描补救。
            try {
                sink.sync()
            } catch (e: Exception) {
                finalReason = REASON_ERROR
            }
            try {
                sink.finish()
            } catch (e: Exception) {
                finalReason = REASON_ERROR
            }
            return RecorderStop(bytes = realBytes, zeroFillBytes = zeroBytes, reason = finalReason)
        }

        while (true) {
            if (userStopped) return finishWith(REASON_USER)
            when (source.state()) {
                SourceState.DEAD -> return finishWith(REASON_DEAD)
                SourceState.STOPPED -> return finishWith(REASON_STOPPED)
                SourceState.RECORDING -> Unit
            }

            // 契约：时钟必须在 read 之前采样，read 阻塞的时间不算缺口。
            val readStartedMs = clock()
            val readBytes = try {
                source.read(buffer)
            } catch (e: Exception) {
                return finishWith(REASON_ERROR)
            }

            if (readBytes < 0) return finishWith(REASON_DEAD)
            if (readBytes == 0) continue // 暂时没数据，不是 EOF
            if (readBytes % FRAME_BYTES != 0 || readBytes > buffer.size) return finishWith(REASON_ERROR)

            // 已写进文件的（含历史数据）+ 刚读到的这一块，才覆盖 [startMs, readStartedMs) 这个窗口。
            val deficitSamples = (readStartedMs - startMs) * SAMPLES_PER_MS -
                (sink.bytesWritten() - baseBytes + readBytes) / FRAME_BYTES

            if (deficitSamples > minGapMs * SAMPLES_PER_MS) {
                val bytes = deficitSamples * FRAME_BYTES
                try {
                    writeZeros(bytes)
                } catch (e: Exception) {
                    return finishWith(REASON_ERROR)
                }
                zeroBytes += bytes
            }

            try {
                sink.write(buffer, readBytes)
            } catch (e: Exception) {
                return finishWith(REASON_ERROR)
            }
            realBytes += readBytes

            val writtenNow = sink.bytesWritten()
            if (writtenNow - syncedAtBytes >= SYNC_BYTES) {
                try {
                    sink.sync()
                } catch (e: Exception) {
                    return finishWith(REASON_ERROR)
                }
                syncedAtBytes = writtenNow
            }

            val db = amplitudeDb(buffer, readBytes)
            try {
                onChunk(realBytes, db)
            } catch (e: Exception) {
                return finishWith(REASON_ERROR)
            }
        }
    }

    private fun writeZeros(bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val step = minOf(remaining, ZERO_CHUNK_BYTES.toLong()).toInt()
            sink.write(ZERO_CHUNK, step)
            remaining -= step
        }
    }

    /** 一块 PCM 的振幅（dBFS）：`20 * log10(rms / 32768)`，夹在 `[silenceFloorDb, 0]`。 */
    private fun amplitudeDb(buffer: ByteArray, length: Int): Int {
        var sumSquares = 0L
        var i = 0
        while (i + 1 < length) {
            val bits = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val sample = bits.toShort().toInt()
            sumSquares += sample.toLong() * sample
            i += FRAME_BYTES
        }
        val samples = length / FRAME_BYTES
        if (sumSquares == 0L || samples == 0) return silenceFloorDb // 全零块没有 -Infinity
        val rms = sqrt(sumSquares.toDouble() / samples)
        val db = (20.0 * log10(rms / 32768.0)).toInt()
        return db.coerceIn(silenceFloorDb, 0)
    }

    private companion object {
        /** 16 kHz → 每毫秒 16 个样本。 */
        const val SAMPLES_PER_MS = 16L

        /** 16 bit 单声道一帧 2 字节。 */
        const val FRAME_BYTES = 2

        /** 默认 read 缓冲 = 100 ms。 */
        const val READ_BUFFER_BYTES = 3_200

        /** 补零按 1 s 分块写，避免为长缺口分配大数组。 */
        const val ZERO_CHUNK_BYTES = 32_000

        /** 真实写入累计到这个量就 sync 一次（约 1 s），收尾时一定再 sync。 */
        const val SYNC_BYTES = 32_000L

        const val REASON_DEAD = "dead"
        const val REASON_STOPPED = "stopped"
        const val REASON_USER = "user"
        const val REASON_ERROR = "error"

        val ZERO_CHUNK = ByteArray(ZERO_CHUNK_BYTES)
    }
}
