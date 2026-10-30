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
    /** 尾段临时文件目录；null = 与音频同目录（生产默认）。测试注入临时目录。 */
    private val tempDir: File? = null,
) {

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 在飞期间又来了一次收尾：置位，当前这轮结束后补跑一次（不丢最后一段）。 */
    private val pendingRerun = ConcurrentHashMap.newKeySet<String>()

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

    /**
     * 追加录音收尾（Plan 4 §0.4）：只转写 `[transcriptMs, rawAudioMs)` 的尾段。
     *
     * 三条规则：
     * 1. 先读行再决定范围——`beginAppend` 已经把新总时长落库，Writer 不再等 UI；
     * 2. 尾段用 [TailWav] 切成临时小 WAV 再转写，绝不整段重转（否则重复旧文字）；
     * 3. 在飞期间又收到一次收尾 → 记 [pendingRerun]，当前轮结束后重读行补跑；
     *    同一段最多拼一次（后缀 CAS 保证幂等）。
     */
    suspend fun onAppendRecordingFinished(dreamId: String, file: File, totalMs: Long) {
        // ① 先落库再排队（Voice AI 复核 ②）：beginAppend 可能因为行还没被 VM 落库而 0 行，
        // 此时最多等 5s 让 RecordViewModel 把行写出来；等不到就交给启动 reconcile 兜底。
        val rows = runCatching { repository.beginAppend(dreamId, totalMs) }.getOrDefault(0)
        var ready = rows > 0
        var tries = 0
        while (!ready && tries < 50) {
            val row = runCatching { repository.findById(dreamId) }.getOrNull()
            ready = row != null && row.rawAudioMs == totalMs && !audioStore.isOpen(file)
            if (!ready) {
                delay(100)
                tries++
            }
        }
        if (!ready) {
            Log.w(TAG, "追加收尾等不到匹配的行（rawAudioMs=$totalMs），交给启动补跑：$dreamId")
            return
        }
        onAppendFinished(dreamId, file)
    }

    suspend fun onAppendFinished(dreamId: String, file: File) {
        if (!inFlight.add(dreamId)) {
            pendingRerun.add(dreamId)
            Log.i(TAG, "同一条梦的尾段转写在飞，已排队补跑：$dreamId")
            return
        }
        try {
            while (true) {
                runCatching { appendOnce(dreamId, file) }
                    .onFailure { Log.w(TAG, "尾段转写异常：$dreamId（${it.message}）") }
                if (!pendingRerun.remove(dreamId)) break
            }
        } finally {
            inFlight.remove(dreamId)
        }
    }

    /**
     * 启动补转写（F1）：扫「PENDING 且 `rawAudioMs > transcriptMs`」的行，按追加路径补跑。
     *
     * 判据**不依赖** `transcriptStatus` 之外的状态机：进程被杀的现场往往就是
     * 「时长已经落库、正文还没拼上」。`FAILED` 不自动重跑（等用户在详情页点重试）。
     *
     * @return 实际发起补跑的行数（诊断口径，不是 UI 事件）
     */
    suspend fun reconcilePending(): Int {
        var attempts = 0
        for (dream in repository.pendingAppends()) {
            val path = dream.rawAudioPath ?: continue
            val file = File(path)
            if (!file.exists() || audioStore.isOpen(file)) continue
            attempts++
            runCatching { onAppendFinished(dream.id, file) }
                .onFailure { Log.w(TAG, "补转写失败：${dream.id}（${it.message}）") }
        }
        if (attempts > 0) Log.i(TAG, "启动补转写：attempts=$attempts")
        return attempts
    }

    private suspend fun appendOnce(dreamId: String, file: File) {
        val row = repository.findById(dreamId) ?: run {
            Log.w(TAG, "补转写找不到行：$dreamId")
            return
        }
        val toMs = row.rawAudioMs ?: return
        val fromMs = row.transcriptMs
        if (toMs <= fromMs) {
            Log.i(TAG, "没有未覆盖音频，跳过：$dreamId（A=$toMs T=$fromMs）")
            return
        }
        if (audioStore.isOpen(file)) {
            Log.i(TAG, "音频还在写，跳过尾段转写：$dreamId")
            return
        }

        val dir = tempDir ?: file.parentFile
        val temp = File(dir, "tail-${file.nameWithoutExtension}.wav")
        val result = try {
            val dataBytes = TailWav.sliceTo(file, fromMs, temp)
            if (dataBytes <= 0L) {
                Log.w(TAG, "尾段为空，标记失败等重试：$dreamId")
                repository.markAppendFailed(dreamId, toMs)
                return
            }
            transcribe(temp)
        } finally {
            temp.delete()
        }

        val text = (result as? SttResult.Ok)?.text?.trim()
        if (text != null && isMeaningfulTranscript(text)) {
            val rows = repository.appendTranscribed(dreamId, "\n" + text, toMs, fromMs)
            Log.i(TAG, "尾段写回：$dreamId rows=$rows from=$fromMs to=$toMs")
        } else {
            val reason = (result as? SttResult.Failed)?.reason ?: "没有识别出可用文字"
            repository.markAppendFailed(dreamId, toMs)
            Log.i(TAG, "尾段转写失败写回：$dreamId reason=${reason.take(40)}")
        }
    }

    /** 探测 + 一次性转写；把四档可用性折成 [SttResult]。 */
    private suspend fun transcribe(file: File): SttResult {
        val availability = runCatching { engine.availability() }
            .getOrElse { error ->
                Log.w(TAG, "可用性探测异常：${error.message}")
                SttAvailability.Unavailable("可用性探测失败")
            }
        return when (availability) {
            SttAvailability.Available -> runCatching { engine.transcribe(file) }
                .getOrElse { error ->
                    Log.w(TAG, "转写异常：${error.message}")
                    SttResult.Failed("转写异常")
                }

            SttAvailability.AvailableButNoFileInput -> SttResult.Failed("系统不支持文件转写")
            is SttAvailability.NeedsDownload -> SttResult.Failed("需要下载中文离线包")
            is SttAvailability.Unavailable -> SttResult.Failed(availability.reason)
        }
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
