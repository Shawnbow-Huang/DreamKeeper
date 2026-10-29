package com.dreamkeeper.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object TagKind {
    const val PERSON = "PERSON"
    const val PLACE = "PLACE"
    const val OBJECT = "OBJECT"
    const val ACTION = "ACTION"
    const val EMOTION = "EMOTION"
    const val OTHER = "OTHER"
}

object TagSource {
    const val AUTO = "AUTO"
    const val USER = "USER"
}

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String = TagKind.OTHER,
    val source: String = TagSource.AUTO,
    /** 用户原话片段（`EMOTION` 必填：设计 §4 要求「不许机器归纳感受」时有出处可回看） */
    val evidenceText: String? = null,
)
