package com.dreamkeeper.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * 梦 ⇄ 标签关联表。
 *
 * **禁止加外键 CASCADE**：Task 9 的标签合并走 `UPDATE OR REPLACE`（REPLACE 冲突 = 先删后插），
 * 一旦有 CASCADE，删那一行时会把它的关联行一起删掉——合并一次丢一批标注，而且不报错。
 * 关联只能显式清理：`DreamTagDao.deleteByDream` / `deleteByTag` / `TagDao.delete` 里的事务。
 */
@Entity(
    tableName = "dream_tags",
    primaryKeys = ["dreamId", "tagId"],
    // D269：按 tagId 反查某标签下所有梦，v1 只能全表扫；这次既然已经在迁移，边际成本 0。
    indices = [Index(value = ["tagId"], name = "index_dream_tags_tagId")],
)
data class DreamTagEntity(
    val dreamId: String,
    val tagId: String,
    val confidence: Float = 1f,
)
