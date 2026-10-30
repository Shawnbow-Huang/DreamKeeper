package com.dreamkeeper.domain

import java.time.LocalDate

/**
 * 一条梦的领域模型：界面层只认这个类型，不直接摸 `DreamEntity`。
 *
 * **字段顺序是契约**：前 9 个（`id` … `isDraft`）是 Task 6/7/8 测试里位置参数构造
 * `Dream(id, date, title, ...)` 的部分；之后一律带默认值、只许在**末尾追加**。
 * 插在中间会让位置参数调用静默错位——类型凑巧兼容时编译器不报错，数据却串了列。
 *
 * 时间字段语义（`DreamRepository.save()` 必须遵守）：
 * - [createdAt] 是「首次录入时间」：`0L` 表示新建、由仓库用当前时钟补；非 0 值必须原样保留。
 *   补录或编辑一条梦，不能把它的录入时间改成今天。
 * - [updatedAt] 由仓库写入（每次 save 取当前时钟），调用方给什么都不作数。
 * - [deletedAt] 是软删除标记：`null` = 未删除，非 `null` = 在回收站。必须原样透传，
 *   任何一次编辑都不能把它悄悄清掉。
 *
 * 注意 domain 层**不反向依赖** `data.db`，所以 [transcriptStatus] 用字符串字面量
 * （取值 `PENDING` / `OK` / `FAILED`，与 `TranscriptStatus` 常量一致）。
 * 默认值是 `PENDING`：只有转写结果真的写回（或用户手动补字）之后才允许是 `OK`；
 * 默认 `OK` 会把「还没转写」伪装成「已经成功」。`DreamEntity` 的默认值仍是 OK
 * （禁改实体），所以新建行的 PENDING 必须由调用方显式给出。
 */
data class Dream(
    val id: String,
    val date: LocalDate,
    val title: String?,
    val rawTranscript: String,
    val faithfulText: String?,
    val novelText: String?,
    val novelChangeLog: String?,
    val mood: String?,
    val isDraft: Boolean,
    val novelVersion: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null,
    val transcriptStatus: String = "PENDING",
    val rawAudioPath: String? = null,
    val rawAudioMs: Long? = null,
    /** 用户挑中的小说化版本号（`novel_versions.version`）；null = 没挑，详情/导出用最新版。 */
    val novelSelectedVersion: Int? = null,
    /**
     * 转写水位：`rawTranscript` 已覆盖到的音频毫秒数（Plan 4 §0.2）。
     * 不在 UI 上直接展示；`rawAudioMs > transcriptMs` 表示还有没转写的追加段。
     */
    val transcriptMs: Long = 0,
)
