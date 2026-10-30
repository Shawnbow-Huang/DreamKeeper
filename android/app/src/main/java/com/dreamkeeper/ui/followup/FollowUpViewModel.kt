package com.dreamkeeper.ui.followup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.audio.WavMath
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.service.RecordingPhase
import com.dreamkeeper.service.RecordingSession
import com.dreamkeeper.service.RecordingSnapshot
import com.dreamkeeper.service.RecordingStarter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Task 5 的追问状态。**倒计时只有这一个源**：`remainingMs` 由 [FollowUpViewModel] 的 ticker
 * 按注入的 `clock()` 算，页面只读，不许自己再跑一个 `LaunchedEffect + delay` 的副本。
 *
 * [visible] 表达「这层还在屏幕上演」：收起后为 false，页面据此播放退出动画再退出路由。
 * [loaded] 是门闩：load 之前不许 visible，否则进页第一帧就收起。
 */
data class FollowUpUiState(
    val dreamId: String,
    val loaded: Boolean = false,
    val remainingMs: Long = 0L,
    val recording: Boolean = false,
    val visible: Boolean = false,
    val transcript: String = "",
) {
    /** 展示用秒数：向上取整，29.9 s 仍显示 30（§0-9：显示 30..0）。 */
    val remainingSeconds: Int get() = ((remainingMs + 999L) / 1_000L).toInt()
}

/**
 * 松手后的 30 秒追问窗口。
 *
 * 三件事各自只有一个来源，别再加第二份：
 * 1. 倒计时 = `windowMs - (clock() - openedAt)`，录音期间不推进（[openedAt] 在松手时补上本段时长）；
 * 2. 补录走 [RecordingStarter.start]，服务端对已有文件只 markOpen 追加，本类不碰文件；
 * 3. 收尾只写 `rawAudioMs`（走 [DreamRepository.finishFollowUp]），是否把状态重置成 `PENDING`
 *    由 DAO 里的 CASE 决定——**不许把转写写下的 `FAILED` 盖回 `PENDING`**，
 *    时长取**最终整文件** `dataSize / 32`，不是两次 run 的 elapsed 相加。
 */
class FollowUpViewModel(
    private val repository: DreamRepository,
    private val session: RecordingSession,
    private val starter: RecordingStarter,
    private val audioStore: WavStore,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxSegmentMs: Long = DEFAULT_MAX_SEGMENT_MS,
    private val dreamId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(FollowUpUiState(dreamId = dreamId))
    val state: StateFlow<FollowUpUiState> = _state.asStateFlow()

    private val _hint = MutableStateFlow<String?>(null)

    /** 一次性提示（不自动消：下一次操作会覆盖），由页面决定怎么展示。 */
    val hint: StateFlow<String?> = _hint.asStateFlow()

    /** 倒计时起点。录音结束时会被推后「本段时长」，保证录音期间窗口不流逝。 */
    private var openedAt: Long = 0L
    private var recordStartedAt: Long? = null
    private var tickJob: Job? = null
    private var segmentJob: Job? = null

    @Volatile
    private var collapsed = false

    init {
        tickJob = viewModelScope.launch(dispatcher) {
            val loaded = runCatching { repository.observe(dreamId).first() }.getOrNull()
            openedAt = clock()
            _state.update { current ->
                current.copy(
                    loaded = true,
                    visible = true,
                    remainingMs = windowMs,
                    transcript = current.transcript.ifEmpty { loaded?.rawTranscript.orEmpty() },
                )
            }
            while (!collapsed) {
                delay(TICK_MS)
                tickNow()
            }
        }
        // 服务侧先结束（无权限 / 打不开麦克风 / 被系统杀）时不许把界面永久冻在录音态。
        viewModelScope.launch(dispatcher) {
            session.state.collect(::onSnapshot)
        }
    }

    // ---------------------------------------------------------------- 倒计时

    private fun tickNow() {
        if (collapsed || !_state.value.loaded) return
        if (_state.value.recording) return // 录音期间冻结，松手时由 onSpeakStop 把这段时长补进 openedAt
        val remaining = (windowMs - (clock() - openedAt)).coerceIn(0L, windowMs)
        _state.update { it.copy(remainingMs = remaining) }
        if (remaining <= 0L) collapse()
    }

    // ---------------------------------------------------------------- 补录

    fun onSpeakStart() {
        val current = _state.value
        if (collapsed || !current.loaded || current.recording) return
        // 服务对非 IDLE/DONE 的 START 直接丢弃：界面必须先判，否则会假装在录。
        val phase = session.state.value.phase
        if (phase != RecordingPhase.IDLE && phase != RecordingPhase.DONE) {
            _hint.value = HINT_PREVIOUS_SEGMENT
            return
        }
        _hint.value = null
        recordStartedAt = clock()
        _state.update { it.copy(recording = true) }
        try {
            starter.start(dreamId)
        } catch (e: Throwable) {
            // 服务没起来：不许谎报录音，也不许把倒计时冻住。
            recordStartedAt = null
            _state.update { it.copy(recording = false) }
            _hint.value = e.message?.takeIf { it.isNotBlank() } ?: HINT_START_FAILED
            tickNow()
            return
        }
        segmentJob = viewModelScope.launch(dispatcher) {
            delay(maxSegmentMs)
            if (_state.value.recording) {
                _hint.value = HINT_SEGMENT_LIMIT
                onSpeakStop()
                collapse()
            }
        }
    }

    fun onSpeakStop() {
        if (!_state.value.recording) return
        val now = clock()
        openedAt += (now - (recordStartedAt ?: now)).coerceAtLeast(0L)
        recordStartedAt = null
        segmentJob?.cancel()
        segmentJob = null
        _state.update { it.copy(recording = false) }
        runCatching { starter.stop() }
        tickNow()
    }

    private fun onSnapshot(snapshot: RecordingSnapshot) {
        if (!_state.value.recording) return
        if (snapshot.phase == RecordingPhase.RECORDING) return
        // 本段被服务侧结束了（DONE / ERROR / IDLE）：解冻倒计时，别等用户松手。
        val now = clock()
        openedAt += (now - (recordStartedAt ?: now)).coerceAtLeast(0L)
        recordStartedAt = null
        segmentJob?.cancel()
        segmentJob = null
        _state.update { it.copy(recording = false) }
        tickNow()
    }

    // ---------------------------------------------------------------- 收尾

    fun onEditText(text: String) {
        _state.update { it.copy(transcript = text) }
    }

    fun onDoneNow() {
        if (collapsed) return
        if (_state.value.recording) onSpeakStop()
        collapse()
    }

    fun onTimeout() {
        if (collapsed) return
        if (_state.value.recording) onSpeakStop()
        collapse()
    }

    private fun collapse() {
        if (collapsed) return
        collapsed = true
        segmentJob?.cancel()
        segmentJob = null
        tickJob?.cancel()
        _state.update { it.copy(visible = false, remainingMs = 0L) }
        viewModelScope.launch(dispatcher) { persistDuration() }
    }

    /** 时长取最终整文件：dataSize / 32；占位头还没回填时按 fileBytes 算，绝不用 0xFFFFFFFF。 */
    private suspend fun persistDuration() {
        val file = audioStore.fileFor(dreamId)
        val ms = runCatching {
            val wav = audioStore.state(file)
            if (wav.placeholder) WavMath.durationMs(wav.fileBytes) else wav.dataSize / WavMath.BYTES_PER_MS
        }.getOrNull() ?: return // 文件还不存在：宁可什么都不写，也不要把已知时长清成 0
        runCatching { repository.finishFollowUp(dreamId, ms) }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 30_000L
        const val DEFAULT_MAX_SEGMENT_MS = 120_000L

        /** 200 ms 一次：数字每秒才变一次，但这样「还剩 N 秒」最多晚 0.2 s 刷新。 */
        private const val TICK_MS = 200L

        const val HINT_PREVIOUS_SEGMENT = "上一段还在收尾"
        const val HINT_SEGMENT_LIMIT = "单段最长 120 秒，已替你保存"
        const val HINT_START_FAILED = "麦克风没能启动"
    }
}
