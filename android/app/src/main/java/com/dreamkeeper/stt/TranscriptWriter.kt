package com.dreamkeeper.stt

import android.util.Log
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 把「一次录音结束」翻译成「一次转写 + 一次窄更新」。
 *
 * 三个反直觉的约束（Task 7 / §0.5–§0.7）都落在这里：
 * 1. **写回是 CAS**：只有 `rawAudioMs` 对得上、且 `TRIM(rawTranscript)=''` 才写；
 *    用户已经手打的字、或补录改过时长的旧行，一律让位（0 行只记日志）。
 * 2. **失败也不清空原文**：`Failed` 只改状态；已有文字保留。
 * 3. **与补录互斥**：转写前记下占位/打开状态与数据段长度，写回前重查；
 *    变了就丢弃结果，状态留在 PENDING（让下一次收尾再试）。
 *
 * 为什么单飞集合放在进程级：同一枚文件被 START/CANCEL/再 START 连续收尾时，
 * 两个协程同时识别只会写两次、还可能把后一次的结果覆盖成旧文本。
 */
class TranscriptWriter(
    private val repository: DreamRepository,
    private val audioStore: WavStore,
    private val engine: SttEngine,
) {

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Service 收尾后调用一次。异常一律吞掉——转写失败不能影响录音已经落盘这件事。
     *
     * `expectedMs` 必须与 `RecordingSession.finish(...)` 的 elapsedMs 同源，
     * 否则 CAS 的 `rawAudioMs = :expectedMs` 会永远对不上（行里是 ViewModel 存的那个值）。
     */
    suspend fun onRecordingFinished(dreamId: String, file: File, expectedMs: Long) {
        if (!inFlight.add(dreamId)) {
            Log.i(TAG, "已有同一条梦的转写在跑，跳过：$dreamId")
            return
        }
        try {
            val before = runCatching { audioStore.state(file) }.getOrElse { error ->
                Log.w(TAG, "读不到 WAV 状态，跳过转写：$dreamId（${error.message}）")
                return
            }
            if (before.placeholder || audioStore.isOpen(file)) {
                Log.i(TAG, "音频还在写（占位/打开中），跳过转写：$dreamId")
                return
            }
            val row = awaitRow(dreamId, expectedMs)
            if (row == null) {
                Log.w(TAG, "等不到匹配的行（rawAudioMs=$expectedMs），跳过转写：$dreamId")
                return
            }

            val availability = runCatching { engine.availability() }
                .getOrElse { error ->
                    Log.w(TAG, "可用性探测异常：${error.message}")
                    SttAvailability.Unavailable("可用性探测失败")
                }
            val result = when (availability) {
                SttAvailability.Available -> runCatching { engine.transcribe(file) }
                    .getOrElse { error ->
                        Log.w(TAG, "转写异常：${error.message}")
                        SttResult.Failed("转写异常")
                    }

                SttAvailability.AvailableButNoFileInput -> SttResult.Failed("系统不支持文件转写")
                is SttAvailability.NeedsDownload -> SttResult.Failed("需要下载中文离线包")
                is SttAvailability.Unavailable -> SttResult.Failed(availability.reason)
            }

            // §0.6：写回前重查。补录会在文件尾部追加并把两个尺寸字段改回占位；
            // 只要占位/打开/数据段长度任一变化，这一份结果就属于「上一段音频」，丢弃。
            val after = runCatching { audioStore.state(file) }.getOrNull()
            if (after == null ||
                after.placeholder ||
                audioStore.isOpen(file) ||
                after.dataSize != before.dataSize
            ) {
                Log.i(TAG, "转写期间音频变了，丢弃结果，状态保持 PENDING：$dreamId")
                return
            }

            apply(dreamId, expectedMs, result)
        } finally {
            inFlight.remove(dreamId)
        }
    }

    /**
     * 执行一次窄更新，返回受影响行数。0 = 让位（不是错误）。
     *
     * 测试直接从这一步断言 CAS 语义；[onRecordingFinished] 负责探测、管道与并发闸门。
     */
    suspend fun apply(dreamId: String, expectedMs: Long, result: SttResult): Int {
        val text = (result as? SttResult.Ok)?.text?.trim()
        if (text != null && isMeaningfulTranscript(text)) {
            val rows = repository.applyTranscriptText(dreamId, expectedMs, text)
            Log.i(TAG, "转写写回：$dreamId rows=$rows")
            return rows
        }
        val reason = (result as? SttResult.Failed)?.reason ?: "没有识别出可用文字"
        val rows = repository.markTranscriptFailed(dreamId, expectedMs)
        Log.i(TAG, "转写失败状态写回：$dreamId rows=$rows reason=${reason.take(40)}")
        return rows
    }

    /** 手动补字（详情页）：与自动写回同一条 CAS，成功才置 OK。 */
    suspend fun applyManual(dreamId: String, expectedMs: Long, text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        val rows = repository.applyTranscriptText(dreamId, expectedMs, trimmed)
        Log.i(TAG, "手动补字写回：$dreamId rows=$rows")
        return rows
    }

    private suspend fun awaitRow(dreamId: String, expectedMs: Long): Dream? {
        // 行可能先以旧的 rawAudioMs 存在（追问补录时 ViewModel 随后才 attachAudio 更新时长），
        // 所以不能「有行就返回」；要等到 rawAudioMs 也匹配，否则补录那一次的转写会被静默跳过。
        repeat(ROW_WAIT_ATTEMPTS) {
            val row = repository.findById(dreamId)
            if (row != null && row.rawAudioMs == expectedMs) return row
            delay(ROW_WAIT_INTERVAL_MS)
        }
        return repository.findById(dreamId)?.takeIf { it.rawAudioMs == expectedMs }
    }

    private companion object {
        const val TAG = "TranscriptWriter"

        /** RecordViewModel 在 DONE 之后才落行；5 秒是「UI 已经存了」的宽限。 */
        const val ROW_WAIT_ATTEMPTS = 50
        const val ROW_WAIT_INTERVAL_MS = 100L
    }
}
