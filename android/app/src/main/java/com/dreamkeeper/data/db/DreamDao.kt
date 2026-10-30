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

    /**
     * 「全部」页专用：每行梦 + 该梦**当前选中版（没选就算最新版）**的小说正文。
     *
     * D332 要求列表显示「挑中的那一版」，而不是恒读 `dreams.novelText`。
     * 子查询口径与 `resolveFinalNovel(selected ?: max)` 保持一致；一个梦没有任何
     * `novel_versions` 行时为 NULL，交给预览兜底（faithful/raw，见 `previewText`）。
     */
    @Query(
        """
        SELECT d.*, (
            SELECT v.text FROM novel_versions v
            WHERE v.dreamId = d.id
              AND v.promptId <> 'faithful'
              AND v.version = COALESCE(
                  d.novelSelectedVersion,
                  (SELECT MAX(v2.version) FROM novel_versions v2
                   WHERE v2.dreamId = d.id AND v2.promptId <> 'faithful')
              )
            LIMIT 1
        ) AS finalNovelText
        FROM dreams d
        WHERE d.deletedAt IS NULL
        ORDER BY d.dreamDate DESC, d.createdAt DESC
        """
    )
    fun observeAllWithPreview(): Flow<List<DreamWithPreview>>

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
     * 追问窗口收尾：写整文件时长，并把状态「重置为 PENDING 以便重转写」——
     * 但**只在这一行还没转出文本、且没有失败过**时才重置。
     *
     * 起因（Task 7 反馈）：转写可能比收起更早结束并写下 `FAILED`；如果这里无条件写 PENDING，
     * 会把真实的失败盖成「正在转写…」，用户永远等不到结果。反过来，已经 OK 且有文本时也不重置：
     * 追加段的重转写策略属于 Plan 3，本轮宁可不假装。
     */
    @Query("""
        UPDATE dreams
        SET rawAudioMs = :rawAudioMs,
            transcriptStatus = CASE
                WHEN transcriptStatus = 'FAILED' THEN transcriptStatus
                WHEN TRIM(rawTranscript) = '' THEN 'PENDING'
                ELSE transcriptStatus
            END
        WHERE id = :id
    """)
    suspend fun finishFollowUp(id: String, rawAudioMs: Long)

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

    // ---- 转写写回（Task 7）：两列窄更新，绝不整行 upsert ----

    /**
     * 自动/手动转写落正文的 CAS（§0.7）。
     *
     * 三个 `WHERE` 缺一不可：`rawAudioMs` 保证结果属于**当前这一次**录音；`TRIM(rawTranscript)=''`
     * 保证不会盖掉用户已经手打或编辑过的原文；`COALESCE` 让 `null` 表示「只改状态」。
     * **不写 `updatedAt`**：转写不是一次用户编辑，写它会把列表排序打乱。
     *
     * 返回受影响行数：0 行 = 让位（用户先写了 / 又补录了），不是错误。
     */
    @Query("""
        UPDATE dreams
        SET rawTranscript = COALESCE(:rawTranscript, rawTranscript),
            transcriptStatus = :transcriptStatus,
            transcriptMs = CASE WHEN :rawTranscript IS NULL THEN transcriptMs ELSE :expectedMs END
        WHERE id = :id
          AND rawAudioMs = :expectedMs
          AND TRIM(rawTranscript) = ''
    """)
    suspend fun updateTranscript(
        id: String,
        expectedMs: Long,
        rawTranscript: String?,
        transcriptStatus: String,
    ): Int

    /**
     * 失败/不可用时只改状态，**保留**已有正文（§0.7 的补充，见 task-7-report 偏差）。
     *
     * 只允许从 `PENDING` 出发：用户已经手动补过字的行（`OK`）不能被迟到的失败覆盖。
     * 同样不写 `updatedAt`。
     */
    @Query("""
        UPDATE dreams
        SET transcriptStatus = :transcriptStatus
        WHERE id = :id
          AND rawAudioMs = :expectedMs
          AND transcriptStatus = 'PENDING'
    """)
    suspend fun updateTranscriptStatus(
        id: String,
        expectedMs: Long,
        transcriptStatus: String,
    ): Int

    // ---- 追加录音（Plan 4 §0.3）：时长先落库、文本按后缀 CAS ----

    /**
     * 追加录音开始：把这次录音的总时长先落库，并标 `PENDING`。
     *
     * 为什么必须「先落库再入队转写」：UI 的 `beginAppend` 在松手后才调用，service 收尾
     * 若抢在它前面入队，Writer 会等不到行而 0 行静默丢字（Voice AI 复核发现）。
     *
     * 用严格 `<`：0 字节的新增（打开又立刻关掉）不该把已经 `OK` 的行打回 `PENDING`，
     * 否则会留下一条永远等不到结果的死锁行。`rawAudioMs IS NULL` 表示这条梦之前没有音频，
     * 首次追加按「从 0 开始」处理。**不写 `updatedAt`**：录音不是文本编辑。
     */
    @Query("""
        UPDATE dreams
        SET rawAudioMs = :newRawAudioMs, transcriptStatus = 'PENDING'
        WHERE id = :id
          AND (rawAudioMs IS NULL OR rawAudioMs < :newRawAudioMs)
    """)
    suspend fun beginAppend(id: String, newRawAudioMs: Long): Int

    /**
     * 尾段转写写回（§0.3）：后缀 CAS。
     *
     * 两个 `WHERE` 是幂等的全部依据：`rawAudioMs = :toMs` 保证结果属于「当前这一次追加」，
     * `transcriptMs = :fromMs` 保证同一段不会拼两次（重跑最多 0 行，不会重复文本）。
     * 只拼后缀、不覆盖既有正文，所以用户手改过的部分天然不会被冲掉。
     */
    @Query("""
        UPDATE dreams
        SET rawTranscript = rawTranscript || :suffix,
            transcriptStatus = :transcriptStatus,
            transcriptMs = :toMs
        WHERE id = :id
          AND rawAudioMs = :toMs
          AND transcriptMs = :fromMs
    """)
    suspend fun appendTranscript(
        id: String,
        fromMs: Long,
        toMs: Long,
        suffix: String,
        transcriptStatus: String,
    ): Int

    /** 手动「重试转写」：把 `FAILED` 打回 `PENDING`，让 Writer 重新排队（0 行 = 已被别处推进）。 */
    @Query("""
        UPDATE dreams
        SET transcriptStatus = 'PENDING'
        WHERE id = :id
          AND rawAudioMs = :expectedMs
          AND transcriptStatus = 'FAILED'
    """)
    suspend fun markTranscriptionRetry(id: String, expectedMs: Long): Int

    /**
     * 追加段转写失败：只改状态，**不动** `rawAudioMs` 与水位（§0.4 失败重试口径）。
     *
     * 不能复用首写路径的 `updateTranscriptStatus`：那条只从 `PENDING` 出发且要求正文为空，
     * 追加行的正文必然非空。这里按 `rawAudioMs` 对齐即可，0 行=让位。
     */
    @Query("""
        UPDATE dreams
        SET transcriptStatus = 'FAILED'
        WHERE id = :id
          AND rawAudioMs = :expectedMs
    """)
    suspend fun markAppendFailed(id: String, expectedMs: Long): Int

    /** 启动/启动后补跑：PENDING 且还有未覆盖音频的行（与 status 之外的时长判据一致）。 */
    @Query("""
        SELECT * FROM dreams
        WHERE transcriptStatus = 'PENDING'
          AND rawAudioPath IS NOT NULL
          AND rawAudioMs IS NOT NULL
          AND rawAudioMs > transcriptMs
        ORDER BY createdAt
    """)
    suspend fun pendingAppends(): List<DreamEntity>

    /**
     * 「这段不用转写」：把水位直接推到当前音频末尾，保留旧正文。
     *
     * 只在确有未覆盖音频时生效（`transcriptMs < :expectedMs`），保证水位单调不减。
     */
    @Query("""
        UPDATE dreams
        SET transcriptMs = rawAudioMs, transcriptStatus = 'OK'
        WHERE id = :id
          AND rawAudioMs = :expectedMs
          AND transcriptMs < :expectedMs
    """)
    suspend fun skipUncoveredAudio(id: String, expectedMs: Long): Int

    /**
     * 生成成功后的窄更新（Plan 3 Task 8 / D314）。
     *
     * 只用一条语句、只碰三列：
     * - `novelText` / `novelChangeLog`：本次生成的最新正文与变更清单；
     * - `generationStage`：`COALESCE(:generationStage, generationStage)` —— 传 `null` 表示
     *   「不动这一列」。小说化提交用 `null` 保住忠实链写下的档位（D320：档位只在成功时写）。
     *
     * **不写 `novelVersion`**：版本号由 `novel_versions.insertWithNextVersion` 的事务自增；
     * **不写 `novelSelectedVersion`**：选择版本归 Task 9；**不走 upsert**：整行 REPLACE 会用
     * 内存里的旧值覆盖转写等列（D202）。`updateStage` 也复用这一条语句（先读行再把两列原值
     * 回填），因此 DreamDao 只新增这一条，不改既有语句。
     */
    @Query("""
        UPDATE dreams
        SET novelText = :novelText,
            novelChangeLog = :novelChangeLog,
            generationStage = COALESCE(:generationStage, generationStage),
            faithfulText = COALESCE(:faithfulText, faithfulText)
        WHERE id = :id
    """)
    suspend fun updateNovelAfterGeneration(
        id: String,
        novelText: String?,
        novelChangeLog: String?,
        generationStage: String?,
        faithfulText: String?,
    ): Int
}

/** 「全部」页的行模型：梦本体 + 选中版正文（可能为 NULL）。 */
data class DreamWithPreview(
    @androidx.room.Embedded val dream: DreamEntity,
    val finalNovelText: String?,
)
