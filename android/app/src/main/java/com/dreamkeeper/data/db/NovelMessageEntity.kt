package com.dreamkeeper.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 对话改稿消息（Plan 3 §3/§6）。
 *
 * `role`：`USER` / `ASSISTANT`。`versionId` 为空 = 这轮对话（ASK）没有产出新版本；
 * 非空 = 这条助手消息对应 [NovelVersionEntity] 里的一版正文。
 *
 * 表上不建外键（v1 四表零外键，梦是软删除）：删梦/删版本时在应用层同事务清理。
 * 索引 `(dreamId, createdAt)` 给「按梦拉一段对话」用，排序同时带 `rowid` 兜同毫秒写入。
 */
@Entity(
    tableName = "novel_messages",
    indices = [
        Index(value = ["dreamId", "createdAt"], name = "index_novel_messages_dreamId_createdAt"),
    ],
)
data class NovelMessageEntity(
    @PrimaryKey val id: String,
    val dreamId: String,
    val role: String,
    val content: String,
    val versionId: String? = null,
    val createdAt: Long,
)
