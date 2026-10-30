package com.dreamkeeper.ui.record

import com.dreamkeeper.service.RecordingPhase

/**
 * Task 4.7 的权限三态。
 *
 * 由「持久化 askedOnce + 现查是否授予」决定，**不依赖** shouldShowRequestPermissionRationale：
 * 用户第一次拒绝后第二次系统可能不再弹框，靠 rationale 判断会把「还能问」误判成「要去设置」。
 */
enum class PermissionState { ASKABLE, BLOCKED, GRANTED }

/**
 * 录音页唯一的 UI 状态；由 [RecordViewModel] 从 [com.dreamkeeper.service.RecordingSession.state] 派生。
 *
 * [loaded] 之前显示的是初始值（0:00 / IDLE），不能拿它做「没有录音」的判断。
 * [savedDreamId] 是一次性事件：RecordScreen 导航到 FollowUp 后必须 consume。
 */
data class RecordUiState(
    val dreamId: String? = null,
    val filePath: String? = null,
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val elapsedMs: Long = 0L,
    val amplitude: Float = 0f,
    val error: String? = null,
    val loaded: Boolean = false,
    val savedDreamId: String? = null,
    val permissionState: PermissionState = PermissionState.ASKABLE,
)

/**
 * Task 5 的追问状态。Task 4 不读取、不修改它——先原样留着，避免两个任务的字段定义打架。
 */
data class FollowUpUiState(
    val remainingMs: Int = 30_000,
    val recording: Boolean = false,
    val visible: Boolean = false,
)
