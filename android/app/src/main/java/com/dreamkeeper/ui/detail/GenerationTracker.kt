package com.dreamkeeper.ui.detail

import com.dreamkeeper.llm.GenerationKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 进程级（更准确说：导航根级）的生成进度表，Plan 4 T6b / §0.10。
 *
 * 为什么不能只放在 [DetailViewModel] 里：生成请求挂在容器的 `generationScope` 上，
 * 用户离开详情页再回来时 ViewModel 是新的；如果没有一个活在 ViewModel 之外的地方，
 * 回来后看到的永远是 Idle——请求还在跑，界面却可以再点一次，等于双重生成。
 *
 * 只按 dreamId 存「在跑/失败」，不存结果：结果已经落库，正文由 `state` 的 Flow 刷新。
 */
class GenerationTracker {

    private val _phases = MutableStateFlow<Map<String, GenerationPhase>>(emptyMap())

    /** 全量快照；测试与诊断用，UI 只读单条（[phaseFor]）。 */
    val phases: StateFlow<Map<String, GenerationPhase>> = _phases.asStateFlow()

    fun phaseFor(dreamId: String): GenerationPhase = _phases.value[dreamId] ?: GenerationPhase.Idle

    fun running(dreamId: String, kind: GenerationKind, attempt: Int = 1) {
        _phases.update { it + (dreamId to GenerationPhase.Running(kind, attempt)) }
    }

    fun failed(dreamId: String, kind: GenerationKind, reason: String) {
        _phases.update { it + (dreamId to GenerationPhase.Failed(kind, reason)) }
    }

    /** 结束（成功或被新一次生成覆盖后回到空）：直接删 key，避免表无限增长。 */
    fun clear(dreamId: String) {
        _phases.update { it - dreamId }
    }
}
