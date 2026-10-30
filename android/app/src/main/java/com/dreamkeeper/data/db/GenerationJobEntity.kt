package com.dreamkeeper.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次生成尝试的落点（Plan 3 §3，D268：v1 没有这张表，Task 4 整表 CREATE）。
 *
 * `stage`：`T0`–`T3` 四档里这次尝试走到/停在哪一档；`attemptNo` 从 1 起递增。
 * 失败必留一行并写 `error`（设计「绝不静默丢弃」），所以 `error` 可空但成功后不写。
 *
 * 不加索引：查询形制是「按 dreamId + createdAt 倒序取最近一条」，量级是单条 dream 几次尝试；
 * 索引留给真的需要时再加（改索引要再付一次迁移）。
 */
@Entity(tableName = "generation_jobs")
data class GenerationJobEntity(
    @PrimaryKey val id: String,
    val dreamId: String,
    val stage: String,
    val attemptNo: Int,
    val error: String? = null,
    val createdAt: Long,
)
