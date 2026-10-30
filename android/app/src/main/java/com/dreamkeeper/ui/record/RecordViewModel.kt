package com.dreamkeeper.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.service.RecordingPhase
import com.dreamkeeper.service.RecordingSession
import com.dreamkeeper.service.RecordingSnapshot
import com.dreamkeeper.service.RecordingStarter
import com.dreamkeeper.ui.nav.RecordEntry
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Task 4 的录音页 ViewModel。
 *
 * 状态真源是 [RecordingSession.state]（由 RecordingService 写入）；本 VM 只做三件事：
 * 1. 把 session 快照映射成 UI 状态（+ 一次性事件 / 权限三态）；
 * 2. 发三条录音命令（start / stop / cancel），命令结果全部等 session 回报；
 * 3. 收到 DONE 时把草稿落库（`rawAudioMs` 直接用 service 算好的 elapsedMs，**不二次读文件**）。
 */
class RecordViewModel(
    private val repository: DreamRepository,
    private val session: RecordingSession,
    private val audioStore: WavStore,
    private val starter: RecordingStarter,
    /** Plan 4 §0.4：非 null 时是「往这条梦的既有 WAV 追加」，开始录音复用同一 id。 */
    private val appendDreamId: String? = null,
    private val hasPermission: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val hasAskedOnce: () -> Boolean = { false },
) : ViewModel() {

    /** 不属于 RecordingSession、但 UI 需要的一次性/派生信息。 */
    private data class Overlay(
        val loaded: Boolean = false,
        val savedDreamId: String? = null,
        val ignoreSaved: Boolean = false,
        val localError: String? = null,
        val permissionState: PermissionState = PermissionState.ASKABLE,
    )

    private val overlay = MutableStateFlow(Overlay(permissionState = currentPermission()))

    /** 这次「按下」被权限拦下的一次性提示；授予后自动清掉，不要求用户再点一次。 */
    private val _permissionBlocked = MutableStateFlow(false)
    val permissionBlocked: StateFlow<Boolean> = _permissionBlocked.asStateFlow()

    val state: StateFlow<RecordUiState> =
        combine(session.state, overlay) { snapshot, extra ->
            RecordUiState(
                dreamId = snapshot.dreamId,
                filePath = snapshot.filePath,
                phase = snapshot.phase,
                elapsedMs = snapshot.elapsedMs,
                amplitude = snapshot.amplitude,
                error = extra.localError ?: snapshot.error,
                loaded = extra.loaded,
                savedDreamId = extra.savedDreamId,
                permissionState = extra.permissionState,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = RecordUiState(permissionState = overlay.value.permissionState),
        )

    /** 同一次 take 的 DONE 只处理一次；新 take 的 dreamId 不同，不会误吞。 */
    private var handledDoneFor: String? = null

    /**
     * 本 VM 亲眼见过 RECORDING/FINISHING 的 take。
     *
     * 重入录音页会新建 VM 而 session 仍停在 DONE（D148）：那种 VM 可以补写路径/时长，
     * 但不能把用户弹去 FollowUp。只有真正参与过这次录音的 VM 才发 `savedDreamId`。
     */
    private var sawTakeInProgress: String? = null

    /** FINISHING 卡死的 5s 看门狗（service 被系统杀掉时 session 不会再到 DONE）。 */
    private var watchdog: Job? = null

    init {
        // 独立于 UI 收集：即使录音页已经被返回键退出，DONE 也必须落库（brief 4.4）。
        viewModelScope.launch {
            session.state.collect { onSnapshot(it) }
        }
    }

    // ---- 4.2 按下 ----

    fun onPressStart(entry: RecordEntry) {
        // 重复 START 防护：唯一真源是 session，本地不另存「正在录」的布尔量。
        // DONE/ERROR 是「上一段已结束」，不是「这一页死了」：先拨回 IDLE，否则一次保存后
        // 主钮永远按不动（报告偏差 D146；§0.8 的防护意图是拦住 RECORDING/FINISHING 的重复 START）。
        when (session.state.value.phase) {
            RecordingPhase.RECORDING, RecordingPhase.FINISHING -> return
            RecordingPhase.DONE, RecordingPhase.ERROR -> session.cancel()
            RecordingPhase.IDLE -> Unit
        }
        if (!hasPermission()) {
            _permissionBlocked.value = true
            refreshPermission()
            return
        }
        _permissionBlocked.value = false
        overlay.update {
            it.copy(
                localError = null,
                savedDreamId = null,
                ignoreSaved = false,
                permissionState = PermissionState.GRANTED,
            )
        }
        starter.start(appendDreamId ?: UUID.randomUUID().toString())
    }

    // ---- 4.3 松手 ----

    fun onRelease() {
        // 只有真正在录才发 STOP：FINISHING 说明 service 已经收到过（重复松手不能发第二条）。
        if (session.state.value.phase != RecordingPhase.RECORDING) return
        watchdog?.cancel()
        starter.stop()
        watchdog = viewModelScope.launch {
            delay(SAVE_TIMEOUT_MS)
            if (session.state.value.phase == RecordingPhase.FINISHING) {
                overlay.update { it.copy(localError = "保存超时，请重试") }
            }
        }
    }

    // ---- 4.5 取消 ----

    fun onCancel() {
        val phase = session.state.value.phase
        if (phase != RecordingPhase.RECORDING && phase != RecordingPhase.FINISHING) return
        watchdog?.cancel()
        // 只发命令；删文件是 service 的职责，VM 不碰。
        starter.cancel()
    }

    /**
     * 「再试一次」：把 ERROR / 空 DONE 留下的 session 状态拨回 IDLE，再让用户重新按住。
     * 不自动开始录音——录音必须是用户主动按住的手势。
     */
    fun retry() {
        val phase = session.state.value.phase
        if (phase == RecordingPhase.ERROR || phase == RecordingPhase.DONE) {
            session.cancel()
        }
        overlay.update { it.copy(localError = null) }
    }

    // ---- 4.7 权限 ----

    /** Activity 每次 onResume 递增的 epoch 会让界面调它一次；授予后这里自动清掉拦截提示。 */
    fun refreshPermission() {
        val next = currentPermission()
        overlay.update { it.copy(permissionState = next) }
        if (next == PermissionState.GRANTED) {
            _permissionBlocked.value = false
        }
    }

    // ---- 4.6 一次性事件 ----

    fun consumeSavedDream() {
        overlay.update { it.copy(savedDreamId = null) }
    }

    /**
     * 中断横幅里的「继续录」：这次被打断的 take 仍会落库（草稿不会丢），但不再自动跳到
     * FollowUp；用户回到可按态录下一段。若落库还没完成，ignoreSaved 会拦住那次导航。
     */
    fun continueAfterInterrupt() {
        overlay.update { it.copy(savedDreamId = null, ignoreSaved = true) }
        // Task 4 没有「追加录音」：继续录 = 保留这段草稿、回到可按态，下一次按住是新的 take。
        when (session.state.value.phase) {
            RecordingPhase.DONE, RecordingPhase.ERROR -> session.cancel()
            RecordingPhase.FINISHING -> starter.cancel()
            RecordingPhase.RECORDING, RecordingPhase.IDLE -> Unit
        }
    }

    private suspend fun onSnapshot(snapshot: RecordingSnapshot) {
        if (!overlay.value.loaded) {
            overlay.update { it.copy(loaded = true) }
        }
        when (snapshot.phase) {
            RecordingPhase.DONE -> {
                val dreamId = snapshot.dreamId ?: return
                if (handledDoneFor == dreamId) return
                handledDoneFor = dreamId
                watchdog?.cancel()
                if (snapshot.elapsedMs > 0L) {
                    // 行已存在不等于「这段 take 已经落好」：启动扫描可能先把文件恢复成草稿，
                    // 指针与时长仍是旧的/空的（§0-4）。这里改成窄更新，绝不再整行 save；
                    // 但「重入页」只补数据、不导航（D148，见 sawTakeInProgress）。
                    persistTake(snapshot, existing = alreadyPersisted(dreamId))
                } else {
                    dropEmptyTake(dreamId)
                }
            }
            RecordingPhase.IDLE -> {
                handledDoneFor = null
                sawTakeInProgress = null
                watchdog?.cancel()
            }
            RecordingPhase.ERROR -> watchdog?.cancel()
            RecordingPhase.RECORDING, RecordingPhase.FINISHING -> {
                sawTakeInProgress = snapshot.dreamId ?: sawTakeInProgress
            }
        }
    }

    private suspend fun alreadyPersisted(dreamId: String): Boolean =
        runCatching { repository.observe(dreamId).first() != null }.getOrDefault(false)

    /**
     * DONE 落库：行不存在就整条写草稿；行已存在就只补 `rawAudioPath`/`rawAudioMs`。
     *
     * 「行已存在」有两种来源：重入页（D148）与启动扫描恢复的草稿（§0-3）。前者不许导航，
     * 后者若发生在本 VM 录音期间，说明扫描抢先插了行——本 VM 仍要照常跳到 FollowUp。
     */
    private fun persistTake(snapshot: RecordingSnapshot, existing: Boolean) {
        // Plan 4 §0.4：追加录音不建新梦、不改路径，只推进 rawAudioMs 并置 PENDING。
        if (appendDreamId != null) {
            val dreamId = snapshot.dreamId ?: return
            viewModelScope.launch {
                runCatching { repository.beginAppend(dreamId, snapshot.elapsedMs) }
                    .onSuccess {
                        if (!overlay.value.ignoreSaved) {
                            overlay.update { it.copy(savedDreamId = dreamId) }
                        }
                    }
                    .onFailure { error ->
                        overlay.update {
                            it.copy(localError = "录音保存失败：${error.message ?: "未知错误"}")
                        }
                    }
            }
            return
        }
        if (!existing) {
            saveDraft(snapshot)
            return
        }
        val dreamId = snapshot.dreamId ?: return
        val filePath = snapshot.filePath ?: return
        viewModelScope.launch {
            runCatching { repository.attachAudio(dreamId, filePath, snapshot.elapsedMs) }
                .onSuccess {
                    if (sawTakeInProgress == dreamId && !overlay.value.ignoreSaved) {
                        overlay.update { it.copy(savedDreamId = dreamId) }
                    }
                }
                .onFailure { error ->
                    overlay.update {
                        it.copy(localError = "录音保存失败：${error.message ?: "未知错误"}")
                    }
                }
        }
    }

    private fun saveDraft(snapshot: RecordingSnapshot) {
        val dreamId = snapshot.dreamId ?: return
        viewModelScope.launch {
            runCatching {
                repository.save(
                    Dream(
                        id = dreamId,
                        date = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate(),
                        title = null,
                        rawTranscript = "",
                        faithfulText = null,
                        novelText = null,
                        novelChangeLog = null,
                        mood = null,
                        isDraft = true,
                        transcriptStatus = "PENDING",
                        rawAudioPath = snapshot.filePath,
                        // service 的 finish(dataSize) 已经把字节换成毫秒；这里再读文件就会和
                        // 文件的实际大小产生第二套真源（brief 4.4）。
                        rawAudioMs = snapshot.elapsedMs,
                    ),
                )
            }.onSuccess {
                if (!overlay.value.ignoreSaved) {
                    overlay.update { it.copy(savedDreamId = dreamId) }
                }
            }.onFailure { error ->
                overlay.update {
                    it.copy(localError = "录音保存失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 空 take（例如按下就松）不落库；service 已建好的 44 字节占位文件由 VM 删掉。 */
    private fun dropEmptyTake(dreamId: String) {
        runCatching { audioStore.fileFor(dreamId).delete() }
        overlay.update { it.copy(localError = "没有录到声音，再按一次试试") }
        // 回到 IDLE，主钮才能重新按（否则 DONE 会被重复 START 防护挡住）。
        session.cancel()
    }

    private fun currentPermission(): PermissionState = when {
        hasPermission() -> PermissionState.GRANTED
        hasAskedOnce() -> PermissionState.BLOCKED
        else -> PermissionState.ASKABLE
    }

    companion object {
        private const val SAVE_TIMEOUT_MS = 5_000L

        /** 路由参数只认枚举名；脏数据/空值退回首页来源，不崩。 */
        fun parseEntry(raw: String?): RecordEntry =
            RecordEntry.entries.firstOrNull { it.name == raw } ?: RecordEntry.HOME_BUTTON
    }
}
