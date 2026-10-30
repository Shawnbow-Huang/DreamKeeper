package com.dreamkeeper.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

object MediaType {
    const val IMAGE = "IMAGE"
    const val VIDEO = "VIDEO"
}

/**
 * 转写状态。为什么不用「空串当哨兵」：断网首跑、本机无中文离线包、API 26–30 没有 on-device 识别时，
 * 必然出现「录音已落盘、文字还没出来」的中间态；而「真录到静音」也会产出空串。
 * 两种空串含义完全相反，必须由状态位解释，不能靠内容猜。
 */
object TranscriptStatus {
    const val PENDING = "PENDING"
    const val OK = "OK"
    const val FAILED = "FAILED"
}

@Entity(tableName = "dreams")
data class DreamEntity(
    @PrimaryKey val id: String,
    val ownerId: String = "local",
    val dreamDate: LocalDate,
    val createdAt: Long,
    val title: String?,
    val rawTranscript: String,
    val faithfulText: String?,
    val novelText: String?,
    val novelVersion: Int = 0,
    val novelChangeLog: String? = null,
    val mood: String? = null,
    val isDraft: Boolean = false,
    val deletedAt: Long? = null,
    val updatedAt: Long = 0L,

    // ↓↓↓ Plan 1 预留位：**只许在末尾追加，且必须带默认值** ↓↓↓
    // 理由：`Dream(id, date, title, ...)` 在 Task 6/7/8 的测试里有 3 处位置参数调用，
    // 插在中间会让它们全部错位，且不报编译错（类型凑巧兼容时更糟）。
    /** 原始音频文件路径（Plan 2 录音写入；null = 这条梦没有音频） */
    val rawAudioPath: String? = null,
    /** 原始音频时长，毫秒（播放条与时长文字用；null = 未知） */
    val rawAudioMs: Long? = null,
    /** 原样稿是否被用户手改过（设计 §4：手动编辑后标「已手改」） */
    val faithfulEditedByUser: Boolean = false,
    /** 小说化稿是否被用户手改过 */
    val novelEditedByUser: Boolean = false,
    /** 云同步预留（设计 §12：v1 不传输，只留字段） */
    val remoteId: String? = null,
    /**
     * 转写状态（设计 §5、§8）：`PENDING` = 已开录、文字还没出来；`OK` = 转写成功；`FAILED` = 转写失败。
     * 不能用空串当哨兵——空串分不清「还没转写」和「真的录到一段静音」。
     */
    val transcriptStatus: String = TranscriptStatus.OK,

    // ↓↓↓ Plan 3（v1→v2）追加位：仍然只许在末尾追加，且必须带默认值 ↓↓↓
    /**
     * 用户挑中的小说化版本号（`novel_versions.version`）；null = 没挑，列表/导出用最新版。
     *
     * **不在 `domain/Dream` 上**——所以 `DreamRepository.save()` 必须 read-modify-write 原样带回，
     * 否则用户每次编辑正文都会把「我挑的是第 2 版」悄悄抹成「用最新版」。
     */
    val novelSelectedVersion: Int? = null,
    /** 生成链最后一次落在哪一档：`T0`/`T1`/`T2`/`T3`；null = 没跑过。 */
    val generationStage: String? = null,

    // ↓↓↓ Plan 4（v2→v3）追加位：仍然只许在末尾追加，且必须带默认值 ↓↓↓
    /**
     * 转写水位：`rawTranscript` 已经覆盖到音频的哪一毫秒（0 = 一个字都还没转写）。
     *
     * 为什么必须有这一列：追加录音后「文字覆盖到哪」是唯一能跨进程崩溃存活的依据。
     * `rawAudioMs > transcriptMs` 即「还有没转写的尾段」，重启时据此补转写；
     * 只靠 `transcriptStatus` 分不出「整段没转」和「旧段已转、新段还没转」。
     */
    @ColumnInfo(defaultValue = "0") val transcriptMs: Long = 0,
)
