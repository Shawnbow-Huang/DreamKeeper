package com.dreamkeeper.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 小说化版本线（Plan 3 §3）。
 *
 * 一条梦可以有多个版本：`version` 从 1 起、按 dream 递增，`(dreamId, version)` 上唯一索引
 * `index_novel_versions_dreamId_version`。**插入必须 `OnConflictStrategy.ABORT`**（见
 * [NovelVersionDao.insert]）：REPLACE 是「先删后插」，撞唯一键时会把已有版本的正文
 * 连同指向它的 `novel_messages.versionId` 一起静默毁掉。
 *
 * `origin`：`GENERATE` = 生成出的新版本；`REVISE` = 在对话里改稿出的新版本。
 * `changeLogJson` 存改动清单的 JSON 原文（解析在仓库/UI 层，数据库只保证不丢字节）。
 * `promptId/promptVersion/promptHash` 是审计三元组：提示词以 `prompts/` 文件为唯一真源，
 * 这里只留「当时用的是哪份、内容哈希是多少」。
 */
@Entity(
    tableName = "novel_versions",
    indices = [
        Index(value = ["dreamId", "version"], unique = true, name = "index_novel_versions_dreamId_version"),
    ],
)
data class NovelVersionEntity(
    @PrimaryKey val id: String,
    val dreamId: String,
    val version: Int,
    val text: String,
    val changeLogJson: String? = null,
    val origin: String,
    val promptId: String,
    val promptVersion: Int,
    val promptHash: String,
    val model: String,
    val reasoningEffort: String? = null,
    val createdAt: Long,
)
