package com.dreamkeeper.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 每一次 HTTP 尝试的账本（Plan 3 §4，设计「绝不静默丢弃」）。
 *
 * 一行 = 一次真实请求（含失败）。`costMicroYuan` 是整数微元，由 `llm/CostCalculator` 按
 * `priceSnapshotJson` 里的价卡算出来；`priceSnapshotJson` 必须存**当时那份价卡的原文**，
 * 这样以后价卡改了，历史账目的复算结果不变（`scripts/check-cost.py` 就是拿它复算的）。
 *
 * `isPeak`、`formulaVersion` 同样是复算输入：不存的话，跨过峰谷边界后账目无法自证。
 * `httpStatus`/`attemptNo`/`requestId`/`usageJson`/`error`/`latencyMs` 是审计与排障用的原始事实。
 *
 * 索引 `(dreamId, createdAt)`：按梦对账；没有反面查询需求（全局账本直接全表扫，量级不大）。
 */
@Entity(
    tableName = "llm_calls",
    indices = [
        Index(value = ["dreamId", "createdAt"], name = "index_llm_calls_dreamId_createdAt"),
    ],
)
data class LlmCallEntity(
    @PrimaryKey val id: String,
    val dreamId: String,
    /** 调用种类：生成 / 改稿 / 追问等，由调用方定义，数据库不枚举。 */
    val kind: String,
    val model: String,
    val reasoningEffort: String? = null,
    val cacheHitTokens: Int? = null,
    val cacheMissTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val isPeak: Boolean = false,
    val priceSnapshotJson: String? = null,
    val costMicroYuan: Long = 0L,
    val formulaVersion: String = "",
    val httpStatus: Int? = null,
    val attemptNo: Int = 1,
    val requestId: String? = null,
    val usageJson: String? = null,
    val error: String? = null,
    val latencyMs: Long? = null,
    val createdAt: Long,
)
