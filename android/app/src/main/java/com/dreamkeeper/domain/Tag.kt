package com.dreamkeeper.domain

/**
 * 标签的领域模型。
 *
 * `kind` / `source` 是字符串而不是 enum：取值由 `data.db` 的 `TagKind` / `TagSource` 常量定义，
 * domain 层不反向依赖 data 层；数据库里本来就是 TEXT，转成 enum 只会在未知取值时炸掉旧数据。
 */
data class Tag(
    val id: String,
    val name: String,
    val kind: String,
    val source: String,
    /** 用户原话片段；`kind = EMOTION` 时设计 §4 要求必有出处 */
    val evidenceText: String?,
)
