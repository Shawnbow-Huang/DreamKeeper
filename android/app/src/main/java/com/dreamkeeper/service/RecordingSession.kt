package com.dreamkeeper.service

import com.dreamkeeper.audio.WavMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 一次录音的生命周期。UI 只读 [RecordingSnapshot.phase]，不自己推断。 */
enum class RecordingPhase { IDLE, RECORDING, FINISHING, DONE, ERROR }

/**
 * 录音的唯一真相（single source of truth）。
 *
 * `elapsedMs` / `amplitude` 只由 `RecorderCore.run` 的 `onChunk` 回调推进
 * （约每 100 ms 一块），**任何地方都不要再起第二个计时器**；录音页只是它的只读快照。
 *
 * @property dreamId 正在录的梦；[RecordingPhase.IDLE] 时为 null
 * @property filePath `files/audio/<dreamId>.wav` 的绝对路径
 * @property elapsedMs 已录时长，由真实字节数换算（1 ms = 32 字节）
 * @property amplitude 0f..1f，由 dBFS 线性映射（-60 dB → 0f，0 dB → 1f）
 */
data class RecordingSnapshot(
    val dreamId: String? = null,
    val filePath: String? = null,
    val elapsedMs: Long = 0,
    val amplitude: Float = 0f,
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val error: String? = null,
)

/**
 * 进程内单例式的录音状态容器（由 `AppContainer` 持有，Service 与 UI 共用同一实例）。
 *
 * 线程模型：[MutableStateFlow] 本身线程安全；调用方不需要额外加锁。
 * 状态迁移是单调的：IDLE → RECORDING →（DONE | ERROR），[cancel] 是唯一回到 IDLE 的路。
 * 乱序回调（例如收尾时 `update` 与 `finish` 竞争）一律被忽略，不会把 DONE 改回 RECORDING。
 */
class RecordingSession {

    private val _state = MutableStateFlow(RecordingSnapshot())

    val state: StateFlow<RecordingSnapshot> = _state.asStateFlow()

    /** START 成功后调用：清空上一次的错误，进入 RECORDING。 */
    fun begin(dreamId: String, filePath: String) {
        _state.value = RecordingSnapshot(
            dreamId = dreamId,
            filePath = filePath,
            phase = RecordingPhase.RECORDING,
        )
    }

    /** 录音中的进度回调。只在 RECORDING 阶段生效。 */
    fun update(elapsedMs: Long, amplitude: Float) {
        _state.update { snapshot ->
            if (snapshot.phase != RecordingPhase.RECORDING) {
                snapshot
            } else {
                snapshot.copy(
                    elapsedMs = elapsedMs.coerceAtLeast(0L),
                    amplitude = amplitude.coerceIn(0f, 1f),
                )
            }
        }
    }

    /**
     * STOP / CANCEL 已收到、读循环还没退出的那一小段（约 100 ms）。
     *
     * 计划列出的方法里没有这一步，但 [RecordingPhase.FINISHING] 需要有人置位：
     * 录音页的「收尾中」态就靠它，UI 不用自己猜。
     */
    fun markFinishing() {
        _state.update { snapshot ->
            if (snapshot.phase == RecordingPhase.RECORDING) {
                snapshot.copy(phase = RecordingPhase.FINISHING, amplitude = 0f)
            } else {
                snapshot
            }
        }
    }

    /**
     * 正常收尾：WAV 已回填头、writer 已关闭后调用。
     *
     * 时长以**真实写入字节数**为准（补零也算时长，因为文件里确实有这些帧：
     * `bytes` 传 `WavState.dataSize`，即整段音频的字节数）。
     */
    fun finish(bytes: Long) {
        _state.update { snapshot ->
            if (snapshot.phase != RecordingPhase.RECORDING && snapshot.phase != RecordingPhase.FINISHING) {
                snapshot
            } else {
                snapshot.copy(
                    elapsedMs = bytes.coerceAtLeast(0L) / WavMath.BYTES_PER_MS,
                    amplitude = 0f,
                    phase = RecordingPhase.DONE,
                    error = null,
                )
            }
        }
    }

    /**
     * 失败收尾：权限被收回、麦克风打不开、磁盘写不进去。
     *
     * 与 [finish] 不同，这里**不**改 `dreamId` / `filePath`：录音页要能显示
     * 「哪一次录音失败了」，启动扫描（Task 6）也靠文件路径去修占位头。
     */
    fun fail(message: String) {
        _state.update { snapshot ->
            if (snapshot.phase == RecordingPhase.DONE) {
                snapshot
            } else {
                snapshot.copy(
                    amplitude = 0f,
                    phase = RecordingPhase.ERROR,
                    error = message,
                )
            }
        }
    }

    /** 用户取消：回到 IDLE（WAV 清理由调用方做，这里只管状态）。 */
    fun cancel() {
        _state.value = RecordingSnapshot()
    }
}

/** 用户在录音页按下的那一下，翻译成 Service 能执行的三条命令之一。 */
sealed interface RecordingCommand {
    data class Start(val dreamId: String) : RecordingCommand
    data object Stop : RecordingCommand
    data object Cancel : RecordingCommand
}

/**
 * `onStartCommand` 的唯一解析入口：`action` + `dreamId` → 命令。
 *
 * 线上格式只在这里定义一次，[RecordingService] 的常量是它的别名；
 * 任何无法识别的输入（含 START 缺 dreamId）返回 null，由调用方 `stopSelf()`。
 */
object RecordingCommands {
    const val ACTION_START = "com.dreamkeeper.action.START"
    const val ACTION_STOP = "com.dreamkeeper.action.STOP"
    const val ACTION_CANCEL = "com.dreamkeeper.action.CANCEL"

    fun parse(action: String?, dreamId: String?): RecordingCommand? = when (action) {
        ACTION_START -> dreamId?.trim()?.takeIf { it.isNotEmpty() }?.let { RecordingCommand.Start(it) }
        ACTION_STOP -> RecordingCommand.Stop
        ACTION_CANCEL -> RecordingCommand.Cancel
        else -> null
    }
}
