package com.dreamkeeper.llm

/**
 * 详情页「生成」按钮的可测缝（Task 9b / D356、D363）。
 *
 * 为什么 ViewModel 不直接依赖 [GenerationOrchestrator]：编排器的构造要接网络、价卡、
 * 提示词、数据库，单测要证明的却只有「按钮 → 哪个入口」「重试 → 同一入口」两件事。
 * 只声明两个 suspend 方法的接口，测试就能用一个记录调用的假实现把这条缝钉死；
 * 生产实现仍是 [GenerationOrchestrator]，逻辑零改动。
 *
 * 两个方法的签名与 [GenerationOrchestrator.generateFaithful] /
 * [GenerationOrchestrator.generateNovel] **逐字一致**（含默认参数；T5 起 novel 多一个 `idea`）：Kotlin 的默认参数
 * 不参与 override 签名，少一个参数编译不过。
 */
interface GenerationRunner {
    suspend fun generateFaithful(
        dreamId: String,
        onProgress: ((GenerationStage, Int) -> Unit)? = null,
    ): GenerationResult

    suspend fun generateNovel(
        dreamId: String,
        idea: String? = null,
        onProgress: ((GenerationStage, Int) -> Unit)? = null,
    ): GenerationResult
}
