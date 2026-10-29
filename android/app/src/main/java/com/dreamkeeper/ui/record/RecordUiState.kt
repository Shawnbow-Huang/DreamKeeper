package com.dreamkeeper.ui.record

/** 录音页状态（Plan 2 填充：计时、暂停、振幅） */
data class RecordUiState(
    val elapsedMs: Long = 0,
    val paused: Boolean = false,
    val amplitude: Float = 0f,
)

/** 追问窗口状态（Plan 2 录音 + Plan 4 通知栏入口共用；remainingMs 走完 = 追问超时） */
data class FollowUpUiState(
    val remainingMs: Int = 30_000,
    val recording: Boolean = false,
    val visible: Boolean = false,
)
