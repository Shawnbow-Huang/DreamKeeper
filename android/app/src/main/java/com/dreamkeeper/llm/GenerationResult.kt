package com.dreamkeeper.llm

import com.dreamkeeper.llm.gates.Gate as GateId

/** 一次生成操作的类型。也用作 `llm_calls.kind`。 */
enum class GenerationKind {
    /** 忠实整理链（T0→T3）。 */
    FAITHFUL,

    /** 小说化（单次）。 */
    NOVEL,
}

/** 忠实整理四级链的档位。T3 是「不调模型、回退原文」，但仍是链的一档。 */
enum class GenerationStage {
    T0,
    T1,
    T2,
    T3,

    // 小说化 v3 的三段（与 T 链无关，job 行按 stage.name 记）。
    PLAN,
    DRAFT,
    POLISH,
}

/**
 * 一次生成失败的原因。
 *
 * 三类的区别是**谁的问题**，决定 UI 文案和后续动作：
 * - [Llm]：模型/网络。可重试；`kind` 直接取 [LlmException.Kind]。
 * - [Gate]：模型答了但不忠实。`failed` 是没过的闸门，`detail` 给人看。
 * - [Protocol]：请求还没发出去就被拦下（闸门不满足、并发占用、响应不是合法 JSON）。
 */
sealed interface GenerationFailure {
    data class Llm(val kind: LlmException.Kind, val message: String) : GenerationFailure

    data class Gate(val failed: Set<GateId>, val detail: String) : GenerationFailure

    data class Protocol(val message: String) : GenerationFailure
}

/**
 * 一次生成的完整结果。
 *
 * [text] 是这次操作**产出的正文**（忠实链是整理稿/原文，小说化是 body）；
 * [version] 只有真正写了 `novel_versions` 才有值（T3 回退、失败都是 null）。
 * [attempts] 计的是 HTTP 交换次数（不是模型调用次数），与 `llm_calls` 行数一致。
 *
 * 末尾三个字段是为 UI/日志补的只读结果，不参与持久化（title/mood 不写 dreams，D315）。
 */
data class GenerationResult(
    val kind: GenerationKind,
    val finalStage: GenerationStage,
    val text: String,
    val version: Int?,
    val changeLogKept: Int,
    val changeLogDropped: Int,
    val attempts: Int,
    val costMicroYuan: Long,
    val failure: GenerationFailure?,
    val faithfulText: String? = null,
    val title: String? = null,
    val mood: String? = null,
)
