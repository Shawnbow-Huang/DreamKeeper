package com.dreamkeeper.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 设计 §12 的预留：v1 只有表结构和详情页占位，没有写入方。 */
@Entity(tableName = "media_assets", indices = [Index("dreamId")])
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val dreamId: String,
    val type: String,
    val path: String?,
    val prompt: String?,
    val model: String?,
    val status: String,
    val createdAt: Long,
)
