package com.dreamkeeper.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dreamkeeper.DreamKeeperApp
import com.dreamkeeper.audio.PcmSource
import com.dreamkeeper.audio.RecorderCore
import com.dreamkeeper.audio.RecorderStop
import com.dreamkeeper.audio.SourceState
import com.dreamkeeper.audio.WavMath
import com.dreamkeeper.audio.WavSink
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.audio.WavWriter
import java.io.File

/**
 * 前台录音服务：`startForegroundService` → `startForeground(42, …, microphone)` → 读麦克风写 WAV。
 *
 * 生命周期只有三条命令（[RecordingCommands]）：START / STOP / CANCEL，
 * 全部由可见 Activity 的 Context 发起（`applicationContext` 起 FGS 会被系统拒绝）。
 *
 * 关键顺序（14+ 的硬约束，不能调换）：
 * 1. 解析命令，非法输入什么都不改；
 * 2. START 先查 `RECORD_AUDIO`——没有就 `fail("没有录音权限")` + `stopSelf()`，
 *    **不**调 `startForeground`（microphone 型服务无权限起前台会抛 SecurityException）；
 * 3. 落 44 字节占位头 + `session.begin`；
 * 4. 建通知渠道（渠道不存在时通知会被系统丢弃）；
 * 5. `startForeground(42, notif, FOREGROUND_SERVICE_TYPE_MICROPHONE)`；
 * 6. 才碰 `AudioRecord` 并 `startRecording()`。
 *
 * 线程：`onStartCommand` / 通知回调 / 收尾都在主线程；读循环在 `dk-recorder` 专用线程。
 * `RecorderCore.run` 阻塞到结束，之后把结果 post 回主线程做状态收尾。
 */
class RecordingService : Service() {

    companion object {
        /** 与 [RecordingCommands] 同一份常量：这里只是给调用方一个更顺手的名字。 */
        const val ACTION_START = RecordingCommands.ACTION_START
        const val ACTION_STOP = RecordingCommands.ACTION_STOP
        const val ACTION_CANCEL = RecordingCommands.ACTION_CANCEL
        const val EXTRA_DREAM_ID = "dreamId"
        const val EXTRA_ENTRY = "entry"

        /** 固定通知 id：3.7 的判据靠它定位（`foregroundId=42` / `id=42`）。 */
        const val NOTIFICATION_ID = 42

        private const val TAG = "RecordingService"
        private const val RECORDER_THREAD = "dk-recorder"

        /** AudioRecord 内部缓冲下限 = 100 ms（与 RecorderCore 的读缓冲一致）。 */
        private const val MIN_BUFFER_BYTES = 3_200

        /** STOP 后等读循环退出的上限；只为回填 WAV 头，等不到就交给启动扫描修。 */
        private const val FINISH_JOIN_MS = 1_000L

        private const val ERROR_NO_PERMISSION = "没有录音权限"
        private const val ERROR_PERMISSION_REVOKED = "录音权限被收回"
        private const val ERROR_AUDIO_OPEN = "打不开麦克风"
        private const val ERROR_AUDIO_DEAD = "麦克风没有数据"
        private const val ERROR_STORAGE = "写不进录音文件"
        private const val ERROR_FOREGROUND = "无法进入前台录音"

        /** `RecorderStop.reason` 的取值（RecorderCore 里是 private，这里按契约比较）。 */
        private const val REASON_DEAD = "dead"
        private const val REASON_ERROR = "error"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val session: RecordingSession by lazy { (application as DreamKeeperApp).container.recordingSession }
    private val audioStore: WavStore by lazy { (application as DreamKeeperApp).container.audioStore }

    /** 当前这次录音；只在主线程读写。 */
    private var active: ActiveRecording? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = RecordingCommands.parse(intent?.action, intent?.getStringExtra(EXTRA_DREAM_ID))
        if (command == null) {
            Log.w(TAG, "忽略非法命令：action=${intent?.action}")
            // 录音中不许因为一条脏命令把服务停掉：文件头会停在占位状态。只有空闲才收工。
            if (active == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        when (command) {
            is RecordingCommand.Start -> startRecording(command.dreamId)
            RecordingCommand.Stop -> stopRecording()
            RecordingCommand.Cancel -> cancelRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val recording = active
        if (recording != null) {
            // 系统杀服务（不是 STOP 路径）：让读循环尽快退出，并给它一点时间 finish 回填头。
            Log.w(TAG, "onDestroy 时仍在录音，尽力收尾")
            recording.core.stop()
            recording.worker.join(FINISH_JOIN_MS)
            active = null
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- START

    private fun startRecording(dreamId: String) {
        if (active != null) {
            Log.w(TAG, "已在录音，忽略重复 START：dreamId=$dreamId")
            return
        }
        // ② 先查权限：没有就只改状态 + 收工，绝不 startForeground。
        if (!hasMicrophonePermission()) {
            Log.w(TAG, "没有 RECORD_AUDIO，拒绝启动前台录音")
            session.fail(ERROR_NO_PERMISSION)
            stopSelf()
            return
        }
        // ③ 占位头先落盘：进程被杀也留得下一份可修复的文件。
        // 追问补录写同一枚文件：已有录音只 markOpen，**绝不 create**（FileOutputStream(file,false)
        // 会把前一段整段截成 44 字节）。createdThisTake 记住「这枚文件是我这次新建的」，
        // 后面三条失败路径与 CANCEL 只许删自己新建的文件，不许删掉用户已有的录音。
        val existing = audioStore.fileFor(dreamId)
        val reusable = existing.exists() && existing.length() >= WavMath.HEADER_BYTES
        val file = try {
            if (reusable) {
                audioStore.markOpen(existing) // 尾部有半帧时这里会抛：拒绝追加，不补零不裁剪
                existing
            } else {
                audioStore.create(dreamId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "准备 WAV 失败：dreamId=$dreamId（reusable=$reusable）", e)
            session.fail(ERROR_STORAGE)
            stopSelf()
            return
        }
        val createdThisTake = !reusable
        session.begin(dreamId, file.path)

        // ④ 渠道必须先于 startForeground 建好。
        RecordingNotifications.ensureChannel(this)

        // ⑤ 前台化：类型必须与 manifest 的 microphone 一致。
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                RecordingNotifications.build(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败", e)
            // 还没开始录音就失败了：只在**本次新建**了文件时删掉，别把前一段追问补录的录音删了（D127）。
            if (createdThisTake) runCatching { file.delete() }
            session.fail(ERROR_FOREGROUND)
            stopForegroundAndSelf()
            return
        }

        // ⑥ 现在才碰 AudioRecord。
        val record = openAudioRecord()
        val writer = record?.let { openWriter(file) }
        if (record == null || writer == null) {
            record?.release()
            if (createdThisTake) runCatching { file.delete() }
            session.fail(ERROR_AUDIO_OPEN)
            stopForegroundAndSelf()
            return
        }
        val recording = ActiveRecording(dreamId, file, record, writer, createdThisTake)
        if (!recording.startCapture()) {
            recording.release()
            // 句柄必须关（close 兜底 finish）；同样只删本次新建的文件。
            runCatching { writer.close() }
            if (createdThisTake) runCatching { file.delete() }
            session.fail(ERROR_AUDIO_OPEN)
            stopForegroundAndSelf()
            return
        }
        active = recording
        recording.worker.start()
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 麦克风输入：VOICE_RECOGNITION@16 kHz/mono/16 bit，缓冲取 min 与 100 ms 的较大者。 */
    private fun openAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            WavMath.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBuffer, MIN_BUFFER_BYTES)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WavMath.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord 构造失败（minBuffer=$minBuffer）", e)
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 未初始化：state=${record.state}")
            record.release()
            return null
        }
        return record
    }

    private fun openWriter(file: File): WavWriter? = try {
        audioStore.openWriter(file)
    } catch (e: Exception) {
        Log.e(TAG, "打开 WAV 写句柄失败：${file.path}", e)
        null
    }

    // ---------------------------------------------------------------- STOP / CANCEL

    private fun stopRecording() {
        val recording = active
        if (recording == null) {
            Log.w(TAG, "STOP 时没有在录音，忽略")
            return
        }
        session.markFinishing()
        recording.core.stop() // 内部会 source.stop() → AudioRecord.stop()，读循环随即退出
    }

    private fun cancelRecording() {
        val recording = active
        if (recording == null) {
            session.cancel()
            stopSelf()
            return
        }
        recording.cancelRequested = true
        session.markFinishing()
        recording.core.stop()
    }

    /** 读循环结束后在主线程收尾：状态、通知、服务一起收。 */
    private fun onRecordingFinished(recording: ActiveRecording, stop: RecorderStop) {
        if (active !== recording) return
        active = null
        try {
            if (recording.cancelRequested) {
                // 取消只丢弃**本次**录进来的那一段；追问补录是在用户已有录音上追加的，
                // 整删会把前一段也弄丢，所以保留文件（PCM 已落盘，finish 已回填头）。
                if (recording.createdThisTake) runCatching { recording.file.delete() }
                session.cancel()
            } else if (stop.reason == REASON_DEAD || stop.reason == REASON_ERROR) {
                session.fail(
                    recording.failureMessage
                        ?: if (stop.reason == REASON_DEAD) ERROR_AUDIO_DEAD else ERROR_STORAGE,
                )
            } else {
                // 以文件真实数据段为准（含补零帧）：DONE 的时长就是播放器会看到的长。
                val dataSize = runCatching { audioStore.state(recording.file).dataSize }.getOrNull()
                session.finish(dataSize ?: (stop.bytes + stop.zeroFillBytes))
            }
        } finally {
            recording.release()
            stopForegroundAndSelf()
        }
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- 一次录音

    /**
     * 一次录音的全部句柄。**一个实例只对应一次 [RecorderCore.run]**（D116）。
     *
     * `cancelRequested` 与 [core] 的停止都由主线程发起，读循环在 [worker] 里；
     * 两边只通过 volatile 标记 + AudioRecord.stop() 交接。
     */
    private inner class ActiveRecording(
        val dreamId: String,
        val file: File,
        private val record: AudioRecord,
        private val writer: WavWriter,
        /** true = 这枚文件是本次 START 新建的；CANCEL / 失败收尾只许删自己新建的文件。 */
        val createdThisTake: Boolean,
    ) {
        private val sink = WavSink(writer)
        private val source = AudioRecordSource(record)
        val core = RecorderCore(source, sink, SystemClock::elapsedRealtime)

        @Volatile
        var cancelRequested = false

        /** 读失败时源给的文案（权限被收回 / 没有数据），收尾时优先用它。 */
        val failureMessage: String?
            get() = source.failureMessage

        val worker = Thread({ readLoop() }, RECORDER_THREAD)

        /** `AudioRecord.startRecording()`：失败就返回 false，由调用方统一收尾。 */
        fun startCapture(): Boolean = try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "startRecording 后状态不是 RECORDING：${record.recordingState}")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "startRecording 抛异常", e)
            false
        }

        fun release() {
            runCatching { record.release() }
        }

        private fun readLoop() {
            // 音频线程优先级：先抬优先级再进读循环（否则补零/写盘会被调度挤掉）。
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            val startedAtMs = SystemClock.elapsedRealtime()
            val stop = try {
                // 3.4c：进度只用真实字节算，不另起计时器；振幅来自 RecorderCore 的 dBFS。
                core.run(startedAtMs) { bytes, amplitudeDb ->
                    session.update(
                        elapsedMs = bytes / WavMath.BYTES_PER_MS,
                        amplitude = (amplitudeDb + 60) / 60f,
                    )
                }
            } catch (e: Throwable) {
                // RecorderCore 内部已兜住所有常规异常；这里只防 Error 级炸裂，仍要尝试回填头。
                Log.e(TAG, "录音线程异常退出", e)
                runCatching { sink.sync() }
                runCatching { sink.finish() }
                RecorderStop(bytes = 0, zeroFillBytes = 0, reason = REASON_ERROR)
            }
            mainHandler.post { onRecordingFinished(this, stop) }
        }
    }
}

/**
 * [AudioRecord] → [PcmSource]。
 *
 * 契约：`read()` 返回 >0 是真实数据、0 是暂时没数据（读循环继续）、<0 是结束。
 * 3.4d：`SecurityException` 或负返回 → 记下「录音权限被收回」并报 DEAD，
 * 由 RecorderCore 走 finish 收尾（回填 WAV 头），绝不留在一个假的录音态。
 *
 * 3.4e：连续 [MAX_EMPTY_READS] 次 `read()==0`（约 5 秒）也报 DEAD，防止忙循环。
 */
private class AudioRecordSource(private val record: AudioRecord) : PcmSource {

    @Volatile
    private var stopRequested = false

    @Volatile
    private var dead = false

    /** 只有读循环线程会改。 */
    private var emptyReads = 0

    @Volatile
    var failureMessage: String? = null
        private set

    override fun read(buffer: ByteArray): Int {
        if (stopRequested) return 0
        val read = try {
            record.read(buffer, 0, buffer.size)
        } catch (e: SecurityException) {
            failureMessage = "录音权限被收回"
            dead = true
            return -1
        } catch (e: Exception) {
            failureMessage = "麦克风读失败"
            dead = true
            return -1
        }
        // AudioRecord.stop() 之后 read 会以 0/负数返回，那是用户停止，不是故障。
        if (stopRequested) return 0
        return when {
            read > 0 -> {
                emptyReads = 0
                read
            }
            read < 0 -> {
                failureMessage = "录音权限被收回"
                dead = true
                -1
            }
            else -> {
                emptyReads += 1
                if (emptyReads >= MAX_EMPTY_READS) {
                    failureMessage = "麦克风没有数据"
                    dead = true
                    -1
                } else {
                    0
                }
            }
        }
    }

    override fun state(): SourceState = when {
        stopRequested -> SourceState.STOPPED
        dead -> SourceState.DEAD
        else -> SourceState.RECORDING
    }

    /** 置停止标记并打断阻塞中的 `read`。 */
    override fun stop() {
        stopRequested = true
        runCatching {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }
    }

    private companion object {
        /** 50 × 100 ms ≈ 5 秒读不到数据就判定源已死（3.4e）。 */
        const val MAX_EMPTY_READS = 50
    }
}
