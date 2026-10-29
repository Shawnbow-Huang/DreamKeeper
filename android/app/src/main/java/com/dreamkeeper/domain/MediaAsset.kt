package com.dreamkeeper.domain

/**
 * 详情页「素材」tab 的领域模型。
 *
 * 存在的意义：UI 只认 domain 类型，不直接摸 `MediaAssetEntity`。Plan 1 里这张表通常为空，
 * 空态文案由界面决定（设计 §12 的预留口子）。
 */
data class MediaAsset(
    val id: String,
    val dreamId: String,
    /** `IMAGE` / `VIDEO`，取值见 `data.db.MediaType` */
    val type: String,
    val path: String?,
    val prompt: String?,
    val model: String?,
    val status: String,
    val createdAt: Long,
)
