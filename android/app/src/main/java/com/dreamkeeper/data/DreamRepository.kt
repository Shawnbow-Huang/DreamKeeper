package com.dreamkeeper.data

import androidx.room.withTransaction
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.DreamEntity
import com.dreamkeeper.data.db.DreamTagEntity
import com.dreamkeeper.data.db.MediaAssetEntity
import com.dreamkeeper.data.db.TagEntity
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.domain.DreamCalendar
import com.dreamkeeper.domain.MediaAsset
import com.dreamkeeper.domain.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 领域模型与数据库之间的**唯一**翻译层。
 *
 * 界面/ViewModel 只拿 [Dream] / [Tag] / [MediaAsset]，不接触任何 `*Entity` 或 DAO；
 * 仓库负责三件容易出人命的事：
 * 1. 写入时守住「首次录入时间」与软删除标记（见 [save]）；
 * 2. 用户搜索词 → LIKE 安全参数的转义与切词（见 [search]）；
 * 3. 列表查询一律走 `observeAll` / `observeMonth`，`observeById` 只用于详情（含回收站，见 D26）。
 *
 * [clock] 可注入是为了让单测做确定性断言；仓库内部**不许**直接调 `System.currentTimeMillis()`。
 */
class DreamRepository(
    private val db: DreamDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dreams = db.dreamDao()
    private val tags = db.tagDao()
    private val assets = db.mediaAssetDao()

    /**
     * 按月查询（闭区间）。`DreamCalendar.monthRange` 返回「月首、月末」两天，
     * 而 `DreamDao.observeByMonth` 的 `dreamDate BETWEEN :fromIso AND :toIso` 是闭区间
     * （列存 `yyyy-MM-dd`，字典序等价日期序），所以两端原样传下去即可。
     *
     * **不要**改写成 `first..<last` 或 epochDay 的 `until`：那会丢掉每月最后一天（D24）。
     */
    fun observeMonth(month: YearMonth): Flow<List<Dream>> {
        val (first, last) = DreamCalendar.monthRange(month)
        return dreams.observeByMonth(first.toString(), last.toString())
            .map { list -> list.map(::toDomain) }
    }

    /** 全部未删除的梦（列表数据源之一） */
    fun observeAll(): Flow<List<Dream>> = dreams.observeAll().map { list -> list.map(::toDomain) }

    /** 当日列表。`date.toString()` 就是 `dreamDate` 列的 `yyyy-MM-dd` 存储格式，不需要再格式化 */
    fun observeDay(date: LocalDate): Flow<List<Dream>> =
        dreams.observeDay(date.toString()).map { list -> list.map(::toDomain) }

    /**
     * 按 id 观察单条梦。**不过滤 `deletedAt`**：回收站要能打开一条已删除的梦（D26）。
     * 因此它只能用于详情页；列表必须走 [observeAll] / [observeMonth]，否则回收站的梦会混进正常列表。
     */
    fun observe(id: String): Flow<Dream?> = dreams.observeById(id).map { it?.let(::toDomain) }

    fun observeTags(): Flow<List<Tag>> = tags.observeAll().map { list -> list.map(::tagToDomain) }

    /** 详情页「标签」tab：联 `dream_tags` 取标签；`Dream` 上没有也不该有 `tags` 字段 */
    fun observeTagsForDream(dreamId: String): Flow<List<Tag>> =
        db.dreamTagDao().observeTagsForDream(dreamId).map { list -> list.map(::tagToDomain) }

    /**
     * 打标 / 合并标签之后的重新关联：先清空这条梦的关联、再整体写入（幂等，可重放）。
     *
     * 两步**必须在同一事务里**：中途失败会留下「这条梦一个标签都没有」的半截状态，
     * 而标签是用户手动整理过的数据，不能因为一次写失败就丢光。
     */
    suspend fun attachTags(dreamId: String, tagIds: List<String>) {
        val unique = tagIds.distinct()
        db.withTransaction {
            db.dreamTagDao().deleteByDream(dreamId)
            db.dreamTagDao().upsertAll(unique.map { DreamTagEntity(dreamId, it) })
        }
    }

    /**
     * 合并标签：把 [fromId] 的关联全部改指 [intoId]，然后删掉源标签行。真的合并了才返回 true。
     *
     * 「UPDATE **OR REPLACE**」不是手滑：一条梦可能同时挂着两个待合并的标签（先自动打了
     * 「升降机」，后来又手动加了「电梯」），普通 UPDATE 会撞 `dream_tags` 的联合主键
     * `(dreamId, tagId)` 直接抛异常；OR REPLACE 会删掉那一行再写指向目标的行，
     * 结果正好是「这条梦只剩一个标签」。**改成普通 UPDATE 会让合并直接崩**。
     *
     * 三条护栏：
     * 1. [fromId] == [intoId] 立刻返回 false —— 否则最后一步会把标签自己删掉；
     * 2. 目标不存在时**一个字都不写**并返回 false —— 否则源标签被删、关联指向不存在的 id，
     *    `observeTagsForDream` 是 INNER JOIN，这些标注会静默消失；
     * 3. 源不存在也返回 false（没什么可并的），不把「删了个寂寞」当成功。
     *
     * 两步必须在同一事务里：只改关联不删源，下次合并会重复；只删源不改关联，
     * 梦上的标注全丢。也不许加外键 CASCADE（`tags.upsert` 是 REPLACE 语义，级联会连带删关联，D75）。
     */
    suspend fun mergeTags(fromId: String, intoId: String): Boolean {
        if (fromId == intoId) return false
        return db.withTransaction {
            if (tags.findById(fromId) == null || tags.findById(intoId) == null) {
                false
            } else {
                tags.repointDreamTags(fromId, intoId)
                tags.delete(fromId)
                true
            }
        }
    }

    /** 详情页「素材」tab：Plan 1 里通常是空表，空态文案由 UI 决定 */
    fun observeMediaAssets(dreamId: String): Flow<List<MediaAsset>> =
        assets.observeByDream(dreamId).map { list -> list.map(::mediaToDomain) }

    /**
     * 写入一条梦。
     *
     * 三条不许动的规则（都是「静默改数据」的典型，用户不会收到任何提示）：
     * 1. [Dream.createdAt] 是**首次录入时间**：调用方带了非 0 值就原样保留，只有新建（0L）才用 now。
     *    每次都写 `clock()` 的话，「补录一句 / 改个标点」就会把首次录入时间改成此刻——不可逆。
     * 2. [Dream.deletedAt] 原样透传：构造实体时漏掉它，任何一次编辑都会把回收站里的梦悄悄捞回来。
     * 3. `DreamEntity` 里凡**在 [Dream] 上没有对应字段**的列（当前是 `ownerId`、
     *    `faithfulEditedByUser`、`novelEditedByUser`、`remoteId`），必须先 `getById` 读回现有行再
     *    原样写回（read-modify-write）。**新增任何「domain 上不存在」的列，必须在这里显式保留，
     *    否则第一次编辑就会被抹掉**——用户手改过稿的标记、本地用户归属、云同步 id 都会静默丢失，
     *    而且没有任何提示。新建一条梦时没有 existing，才走 `"local"` / `false` / `null` 的默认值。
     *
     * `updatedAt` 则相反：它表示「最后一次写入」，每次 save 都由 [clock] 覆盖。
     */
    suspend fun save(dream: Dream) {
        val now = clock()
        val existing = dreams.getById(dream.id)
        dreams.upsert(
            DreamEntity(
                id = dream.id,
                ownerId = existing?.ownerId ?: "local",
                dreamDate = dream.date,
                createdAt = dream.createdAt.takeIf { it > 0L } ?: now,
                title = dream.title,
                rawTranscript = dream.rawTranscript,
                faithfulText = dream.faithfulText,
                novelText = dream.novelText,
                novelVersion = dream.novelVersion,
                novelChangeLog = dream.novelChangeLog,
                mood = dream.mood,
                isDraft = dream.isDraft,
                deletedAt = dream.deletedAt,
                updatedAt = now,
                rawAudioPath = dream.rawAudioPath,
                rawAudioMs = dream.rawAudioMs,
                faithfulEditedByUser = existing?.faithfulEditedByUser ?: false,
                novelEditedByUser = existing?.novelEditedByUser ?: false,
                remoteId = existing?.remoteId,
                transcriptStatus = dream.transcriptStatus,
            )
        )
    }

    /**
     * 追问窗口收尾的窄更新：只写音频时长与转写状态。
     *
     * 与 [save] 的分工：补录后时长变了，但文本还没转写（Task 7 才写），
     * 走整行 [save] 会拿一个 `rawTranscript` 不完整的 [Dream] 覆盖掉老文本。
     * 这里不碰 `createdAt` / `deletedAt` / `updatedAt` / 编辑标记。
     */
    suspend fun updateAudio(id: String, rawAudioMs: Long, transcriptStatus: String) {
        dreams.updateAudio(id, rawAudioMs, transcriptStatus)
    }

    // ---- 音频兜底扫描（Task 6）：文件系统是「存在与时长」的唯一真源 ----

    /** 所有带音频指针的行（含回收站）；扫描拿它和 `files/audio/` 下的 wav 对账。 */
    suspend fun dreamsWithAudio(): List<Dream> = dreams.audioRows().map(::toDomain)

    /** 扫描的一次性存在判据（`observe` 是 Flow，不适合逐文件判重）。 */
    suspend fun hasDream(id: String): Boolean = dreams.existsById(id)

    /** 只改音频时长：不碰 `transcriptStatus`，也不动 `updatedAt`。 */
    suspend fun fixAudioMs(id: String, rawAudioMs: Long) {
        dreams.setAudioMs(id, rawAudioMs)
    }

    /** 文件已丢：清指针并标 FAILED；文本、编辑标记、`updatedAt` 一律不动。 */
    suspend fun markMissing(id: String) {
        db.withTransaction { dreams.clearAudio(id) }
    }

    /**
     * 行已存在时补写本次 take 的路径/时长（`RecordViewModel` 的「已存在」分支）。
     *
     * 不走 [save]：整行写会把扫描刚恢复的 `createdAt` 改成此刻，还会覆盖并发编辑。
     */
    suspend fun attachAudio(id: String, rawAudioPath: String, rawAudioMs: Long) {
        dreams.setAudio(id, rawAudioPath, rawAudioMs)
    }

    /**
     * 把一枚孤儿 WAV 恢复成草稿；**行已存在就返回 false**（重跑不许重置 `createdAt`）。
     *
     * 日期与时间取文件 mtime 而不是 [clock]：录音发生在过去，用「今天」会把跨夜的
     * 录音记到错误的一天（§0-6）。事务内先查后插，避免同一 id 被并发扫描插两次。
     */
    suspend fun recoverDraft(
        dreamId: String,
        filePath: String,
        rawAudioMs: Long,
        lastModifiedMs: Long,
    ): Boolean = db.withTransaction {
        if (dreams.existsById(dreamId)) return@withTransaction false
        val date = Instant.ofEpochMilli(lastModifiedMs).atZone(ZoneId.systemDefault()).toLocalDate()
        dreams.upsert(
            DreamEntity(
                id = dreamId,
                ownerId = "local",
                dreamDate = date,
                createdAt = lastModifiedMs,
                title = null,
                rawTranscript = "",
                faithfulText = null,
                novelText = null,
                novelVersion = 0,
                novelChangeLog = null,
                mood = null,
                isDraft = true,
                deletedAt = null,
                updatedAt = lastModifiedMs,
                rawAudioPath = filePath,
                rawAudioMs = rawAudioMs,
                faithfulEditedByUser = false,
                novelEditedByUser = false,
                remoteId = null,
                transcriptStatus = TranscriptStatus.PENDING,
            ),
        )
        true
    }

    /**
     * 用户输入 → LIKE 查询：按空白切词，逐词查 4 列（title / rawTranscript / faithfulText /
     * novelText），按 id 求**交集**（每个词都要命中，任一列命中即可）。
     *
     * 顺序即语义：第一个词的结果决定排序（DAO 里是 `dreamDate DESC, createdAt DESC`），
     * 后面的词只做过滤。所以「电梯 六楼」返回的仍是按日期排的列表，不会因为求交而乱序。
     *
     * 转义责任在这里：DAO 的 `searchTerm` 只负责 `LIKE '%'||:term||'%' ESCAPE '\'`，
     * 传进去的 term 必须是已经转义的。空查询（或清洗后一个词都不剩）直接返回空列表，
     * **不许**退化成「返回全部」——那是搜索框里最常见的「看着像坏了」的 bug。
     */
    suspend fun search(rawQuery: String): List<Dream> {
        val terms = rawQuery.trim()
            .split(TERM_SEPARATOR)
            .map(::normalizeTerm)
            .filter { it.isNotEmpty() }
            .distinct()
        if (terms.isEmpty()) return emptyList()

        var hits: List<Dream>? = null
        for (term in terms) {
            val matched = dreams.searchTerm(escapeLike(term)).map(::toDomain)
            val previous = hits
            hits = if (previous == null) {
                matched
            } else {
                val ids = matched.mapTo(HashSet()) { it.id }
                previous.filter { it.id in ids }   // 首词保序，后续词只做过滤
            }
        }
        return hits.orEmpty()
    }

    /**
     * 去掉标点与引号：用户搜 `"电梯"！` 时，引号和感叹号是输入习惯，不是要匹配的字符。
     *
     * 但 `%` 与 `_` **必须留着**——它们是 LIKE 的通配符，要交给 [escapeLike] 转义；
     * 否则「通配符注入」的回归用例会因为输入被提前洗干净而永远测不到转义。
     */
    private fun normalizeTerm(raw: String): String =
        raw.trim().replace(PUNCTUATION_OR_SYMBOL) { match ->
            if (match.value == "%" || match.value == "_") match.value else ""
        }

    /**
     * `\`、`%`、`_` 在 LIKE 里都有特殊含义：先转义，再拼进 `'%'||:term||'%'`（SQL 侧配 `ESCAPE '\'`）。
     *
     * 顺序不能反：反斜杠必须**最先**转义，否则后面 `%` → `\%` 新插入的反斜杠会被二次转义成 `\\%`，
     * 语义就从「字面百分号」变成「字面反斜杠 + 通配符」。
     */
    private fun escapeLike(term: String): String = term
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private fun toDomain(e: DreamEntity) = Dream(
        id = e.id,
        date = e.dreamDate,
        title = e.title,
        rawTranscript = e.rawTranscript,
        faithfulText = e.faithfulText,
        novelText = e.novelText,
        novelChangeLog = e.novelChangeLog,
        mood = e.mood,
        isDraft = e.isDraft,
        novelVersion = e.novelVersion,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        deletedAt = e.deletedAt,
        transcriptStatus = e.transcriptStatus,
        rawAudioPath = e.rawAudioPath,
        rawAudioMs = e.rawAudioMs,
    )

    private fun tagToDomain(e: TagEntity) = Tag(e.id, e.name, e.kind, e.source, e.evidenceText)

    private fun mediaToDomain(e: MediaAssetEntity) = MediaAsset(
        id = e.id,
        dreamId = e.dreamId,
        type = e.type,
        path = e.path,
        prompt = e.prompt,
        model = e.model,
        status = e.status,
        createdAt = e.createdAt,
    )

    private companion object {
        /**
         * 空白切词。Java/Kotlin 的 `\s` 只覆盖 ASCII 空白，不含全角空格 U+3000
         * （中文输入法偶尔会产出的那个），所以单独列出，免得「电梯　六楼」被当成一个词。
         */
        val TERM_SEPARATOR = Regex("[\\s\\u3000]+")

        /** Unicode 的「标点 + 符号」；`%`（Po）与 `_`（Pc）由 [normalizeTerm] 单独放行 */
        val PUNCTUATION_OR_SYMBOL = Regex("[\\p{P}\\p{S}]")
    }
}
