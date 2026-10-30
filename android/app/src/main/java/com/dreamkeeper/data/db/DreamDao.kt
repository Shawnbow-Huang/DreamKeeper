package com.dreamkeeper.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DreamDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dream: DreamEntity)

    /** 种子注入的幂等判据：非空就不再写 */
    @Query("SELECT COUNT(*) FROM dreams") suspend fun count(): Int

    /**
     * 按月查询。区间是**闭区间**（`[fromIso, toIso]`）：调用方直接把
     * `DreamCalendar.monthRange(month)` 的 `first`/`last` 转成 ISO 字符串传进来即可，
     * 不需要 `+1`——`dreamDate` 经 TypeConverter 存成 `yyyy-MM-dd`，字典序比较与日期比较等价。
     */
    @Query("""
        SELECT * FROM dreams
        WHERE deletedAt IS NULL AND dreamDate BETWEEN :fromIso AND :toIso
        ORDER BY dreamDate DESC, createdAt DESC
    """)
    fun observeByMonth(fromIso: String, toIso: String): Flow<List<DreamEntity>>

    /**
     * 当日列表（设计 §11 的中间层，D35）。等值匹配 `dreamDate = :dateIso`，**不用 BETWEEN**：
     * 跨月不串靠的是「不同月份的同一天号是两个不同的字符串」，比区间更直接。
     * `deletedAt IS NULL` 必须有：软删的梦不进当日列表。
     * 排序按 `createdAt DESC`（同一天里后补录的在前）。
     */
    @Query("""
        SELECT * FROM dreams
        WHERE deletedAt IS NULL AND dreamDate = :dateIso
        ORDER BY createdAt DESC
    """)
    fun observeDay(dateIso: String): Flow<List<DreamEntity>>

    @Query("SELECT * FROM dreams WHERE deletedAt IS NULL ORDER BY dreamDate DESC, createdAt DESC")
    fun observeAll(): Flow<List<DreamEntity>>

    /** 不排除软删：回收站要能按 id 打开一条已删除的梦。 */
    @Query("SELECT * FROM dreams WHERE id = :id")
    fun observeById(id: String): Flow<DreamEntity?>

    /**
     * 读一行**当前落库状态**（一次性读取，不是 Flow）。
     *
     * `DreamRepository.save()` 的 read-modify-write 靠它拿回 `domain/Dream` 上不存在的列
     * （`ownerId` / `faithfulEditedByUser` / `novelEditedByUser` / `remoteId`）。
     * 那些列不参与「用户编辑」，但必须原样保留，否则每次保存都会被重置成默认值。
     */
    @Query("SELECT * FROM dreams WHERE id = :id")
    suspend fun getById(id: String): DreamEntity?

    /**
     * Task 5 追问窗口收尾：**只**窄更新音频时长与转写状态。
     *
     * 走整行 `upsert` 会把 `rawTranscript` 等文本列一起覆盖（追问页没有完整文本，
     * 用它保存等于把 Task 7 要转写的内容抹掉）。`updatedAt` 也刻意不动：
     * 补录不是一次「用户编辑」。
     */
    @Query("""
        UPDATE dreams
        SET rawAudioMs = :rawAudioMs, transcriptStatus = :transcriptStatus
        WHERE id = :id
    """)
    suspend fun updateAudio(id: String, rawAudioMs: Long, transcriptStatus: String)

    /**
     * 4 列 LIKE 搜索。
     *
     * **term 必须是已转义的**：`\` → `\\`、`%` → `\%`、`_` → `\_`。否则用户搜 `100%`
     * 会被当成通配符命中全库。转义只该发生在一处：`DreamRepository.search`（Task 5）。
     *
     * 为什么不是 FTS：中文没有空格，FTS 的 tokenizer 会把整段连续汉字当成一个 token，
     * `MATCH "电梯"` 命中 0 行。实测见计划 Task 4「为什么不用 FTS4」。
     */
    @Query("""
        SELECT * FROM dreams
        WHERE deletedAt IS NULL AND (
            title LIKE '%'||:term||'%' ESCAPE '\'
            OR rawTranscript LIKE '%'||:term||'%' ESCAPE '\'
            OR faithfulText LIKE '%'||:term||'%' ESCAPE '\'
            OR novelText LIKE '%'||:term||'%' ESCAPE '\'
        )
        ORDER BY dreamDate DESC, createdAt DESC
    """)
    suspend fun searchTerm(term: String): List<DreamEntity>

    // ---- 启动扫描（Task 6）：只加查询/窄更新，不动实体，schema 一个字节都不变 ----

    /**
     * 所有带音频指针的行，供启动扫描和文件系统对账。
     *
     * **不过滤 `deletedAt`**：回收站里的梦也可能带着音频文件，文件不认识软删除；
     * 扫描只负责「指针 ↔ 文件」的一致，删不删由用户决定。
     */
    @Query("SELECT * FROM dreams WHERE rawAudioPath IS NOT NULL")
    suspend fun audioRows(): List<DreamEntity>

    /** 扫描恢复草稿前的「按 id 不存在才插」判据。 */
    @Query("SELECT EXISTS(SELECT 1 FROM dreams WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    /**
     * 文件没了：清指针 + 标 FAILED，**不碰** `rawTranscript` 与 `updatedAt`。
     *
     * 不能走 `updateAudio`：那个方法会把 `transcriptStatus` 写成调用方给的值，
     * 扫描这里恰好需要反方向（把可能残留的 OK 改回 FAILED）。
     */
    @Query("""
        UPDATE dreams
        SET rawAudioPath = NULL, rawAudioMs = NULL, transcriptStatus = 'FAILED'
        WHERE id = :id
    """)
    suspend fun clearAudio(id: String)

    /** 只改时长：不许碰 `transcriptStatus`（`updateAudio` 会把 FAILED 悄悄改回 OK）。 */
    @Query("UPDATE dreams SET rawAudioMs = :rawAudioMs WHERE id = :id")
    suspend fun setAudioMs(id: String, rawAudioMs: Long)

    /**
     * 行已存在时补写本次 take 的路径与时长（§0-4）。
     *
     * 与 [updateAudio] 的分工：不动 `transcriptStatus`，也不动 `updatedAt` 与文本列。
     */
    @Query("""
        UPDATE dreams
        SET rawAudioPath = :rawAudioPath, rawAudioMs = :rawAudioMs
        WHERE id = :id
    """)
    suspend fun setAudio(id: String, rawAudioPath: String, rawAudioMs: Long)
}
