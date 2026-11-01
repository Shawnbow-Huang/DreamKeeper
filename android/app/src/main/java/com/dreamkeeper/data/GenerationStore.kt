package com.dreamkeeper.data

import androidx.room.withTransaction
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.DreamEntity
import com.dreamkeeper.data.db.DreamTagEntity
import com.dreamkeeper.data.db.TagEntity
import com.dreamkeeper.data.db.TagSource
import com.dreamkeeper.data.db.GenerationJobEntity
import com.dreamkeeper.data.db.LlmCallEntity
import com.dreamkeeper.data.db.NovelMessageEntity
import com.dreamkeeper.data.db.NovelVersionEntity
import com.dreamkeeper.domain.Dream

/**
 * Task 8 的持久化边界（D314）。
 *
 * 只暴露生成流程需要的六件事，刻意不暴露整行写：`DreamRepository.save()` 是 REPLACE，
 * 会用调用方手里的旧快照覆盖转写等文本列（D202）。所有写路径都收敛到窄 UPDATE。
 */
interface GenerationStore {
    /** 读闸门与小说化基座要用的那一行；只读，不落 [Dream.generationStage]（domain 没这一列）。 */
    suspend fun dream(dreamId: String): Dream?

    /** 每次 HTTP 交换一行，按列表顺序落库（成功和失败混在同一批里）。 */
    suspend fun recordAttempts(calls: List<LlmCallEntity>)

    /** 一次生成一行任务记录（§0.10）。 */
    suspend fun recordJob(job: GenerationJobEntity)

    /**
     * 提交一次成功生成：**一个事务**里插 `novel_versions` 新版本 + 窄更新 `dreams`，返回新版本号。
     *
     * - [changeLogJson]：小说化的变更清单 JSON 数组文本；忠实链传 `null`。
     * - [stage]：写 `dreams.generationStage`；小说化传 `null` 表示不动这一列（D320 只在成功时写）。
     * - [faithfulTextForDream]：忠实链传整理后的正文，同事务写 `dreams.faithfulText`
     *   （Plan 4 T3；小说化/改稿传 `null` 表示不动这一列）。
     */
    suspend fun commitSuccess(
        dreamId: String,
        draft: NovelVersionEntity,
        changeLogJson: String?,
        stage: String?,
        faithfulTextForDream: String? = null,
    ): Int

    /** 只写档位（T3 回退用）。实现复用同一条窄 UPDATE，先读行再把文本列原值写回。 */
    suspend fun updateStage(dreamId: String, stage: String)

    /**
     * 手动改字：原样稿**就地覆盖**，不建版本（原样稿不在版本线上，版本线只属于小说化）。
     * 只改 `dreams.faithfulText` 一列。
     */
    suspend fun saveManualFaithful(dreamId: String, text: String) {
        throw UnsupportedOperationException("当前 Store 不支持手动改字")
    }

    /**
     * 手动改字：小说化稿**新建一个版本**（`origin = manual`），保留可回退，
     * 并把 `dreams.novelText / novelVersion` 指到这一版。
     */
    suspend fun saveManualNovel(dreamId: String, text: String): Int {
        throw UnsupportedOperationException("当前 Store 不支持手动改字")
    }

    /**
     * 自动打标签：替换这篇梦上来源为 AUTO 的旧标签，再写入新标签（同名复用 tags 行）。
     * 用户手动标签（source=USER）不动。
     */
    /** 用户手动删掉一个标签关联（只删 `dream_tags`，不动全局 `tags` 行）。 */
    suspend fun deleteTagLink(dreamId: String, tagId: String) {
        throw UnsupportedOperationException("当前 Store 不支持删除标签")
    }

    suspend fun saveAutoTags(dreamId: String, drafts: List<com.dreamkeeper.llm.tags.TagExtractor.Draft>) {
        throw UnsupportedOperationException("当前 Store 不支持自动打标签")
    }

    /** 该梦已记账的 HTTP 交换数。 */
    suspend fun callCount(dreamId: String): Int

    // ------------------------------------------------------------ Task 9 改稿

    /** 版本线（新→旧），供改稿基座与版本条读取。 */
    suspend fun versions(dreamId: String): List<NovelVersionEntity> = emptyList()

    /** 对话消息（旧→新），供最近三轮与气泡读取。 */
    suspend fun messages(dreamId: String): List<NovelMessageEntity> = emptyList()

    /**
     * 提交一次成功改稿：**一个事务**里插 user 消息 → 新版本 → 窄更新 `dreams`
     * （novelText / novelVersion / novelChangeLog）→ 插 assistant 消息（versionId=新版本 id）。
     */
    suspend fun commitRevision(
        dreamId: String,
        userMessage: NovelMessageEntity,
        assistantMessage: NovelMessageEntity,
        draft: NovelVersionEntity,
        changeLogJson: String,
    ): NovelVersionEntity = throw UnsupportedOperationException("当前 Store 不支持改稿")

    /** 提交一次 ASK：一个事务只插 user + assistant 两条消息，不建版、不动正文。 */
    suspend fun commitAsk(
        dreamId: String,
        userMessage: NovelMessageEntity,
        assistantMessage: NovelMessageEntity,
    ) {
        throw UnsupportedOperationException("当前 Store 不支持改稿")
    }

    /** 选择/撤回：只窄更新 `dreams.novelSelectedVersion`，校验该版本存在。 */
    suspend fun selectVersion(dreamId: String, version: Int) {
        throw UnsupportedOperationException("当前 Store 不支持选择版本")
    }
}

/** Room 实现。所有多表写都在 `db.withTransaction` 里，避免「版本插了但 dreams 没更新」的半截状态。 */
class RoomGenerationStore(private val db: DreamDatabase) : GenerationStore {

    override suspend fun dream(dreamId: String): Dream? = db.dreamDao().getById(dreamId)?.toDream()

    override suspend fun recordAttempts(calls: List<LlmCallEntity>) {
        if (calls.isEmpty()) return
        // 一批交换一个事务：一条生成链的记账要么全在要么全不在，方便按 createdAt 对账。
        db.withTransaction { calls.forEach { db.llmCallDao().insert(it) } }
    }

    override suspend fun recordJob(job: GenerationJobEntity) {
        db.generationJobDao().insert(job)
    }

    override suspend fun commitSuccess(
        dreamId: String,
        draft: NovelVersionEntity,
        changeLogJson: String?,
        stage: String?,
        faithfulTextForDream: String?,
    ): Int = db.withTransaction {
        // 顺序不能反：版本号由 insertWithNextVersion 自增，窄 UPDATE 用落库后的最终正文。
        val saved = db.novelVersionDao().insertWithNextVersion(draft)
        db.dreamDao().updateNovelAfterGeneration(
            id = dreamId,
            novelText = saved.text,
            novelChangeLog = changeLogJson,
            generationStage = stage,
            faithfulText = faithfulTextForDream,
        )
        saved.version
    }

    override suspend fun updateStage(dreamId: String, stage: String) {
        // 复用唯一那条窄 UPDATE：读一行把 novelText/novelChangeLog 原值写回（null 也原样回填），
        // 因此「只改三列」的约束不用再加第二条 SQL。
        val current = db.dreamDao().getById(dreamId) ?: return
        db.dreamDao().updateNovelAfterGeneration(
            id = dreamId,
            novelText = current.novelText,
            novelChangeLog = current.novelChangeLog,
            generationStage = stage,
            faithfulText = null,
        )
    }

    override suspend fun saveManualFaithful(dreamId: String, text: String) {
        db.dreamDao().updateFaithfulTextOnly(dreamId, text)
    }

    override suspend fun saveManualNovel(dreamId: String, text: String): Int {
        val dream = requireNotNull(db.dreamDao().getById(dreamId)) { "dream 不存在：$dreamId" }
        val draft = NovelVersionEntity(
            id = java.util.UUID.randomUUID().toString(),
            dreamId = dreamId,
            version = 0, // 由 insertWithNextVersion 自增
            text = text,
            changeLogJson = dream.novelChangeLog,
            origin = "manual",
            promptId = "manual",
            promptVersion = 0,
            promptHash = "manual",
            model = "manual",
            reasoningEffort = null,
            createdAt = System.currentTimeMillis(),
        )
        return commitSuccess(
            dreamId = dreamId,
            draft = draft,
            changeLogJson = dream.novelChangeLog,
            stage = null,
            faithfulTextForDream = null,
        )
    }

    override suspend fun deleteTagLink(dreamId: String, tagId: String) {
        db.dreamTagDao().deleteLink(dreamId, tagId)
    }

    override suspend fun saveAutoTags(
        dreamId: String,
        drafts: List<com.dreamkeeper.llm.tags.TagExtractor.Draft>,
    ) {
        val existing = db.dreamTagDao().listTagsForDream(dreamId)
        existing.filter { it.source == TagSource.AUTO }.forEach {
            db.dreamTagDao().deleteLink(dreamId, it.id)
        }
        val links = mutableListOf<DreamTagEntity>()
        for (d in drafts) {
            val row = db.tagDao().findByName(d.name) ?: TagEntity(
                id = java.util.UUID.randomUUID().toString(),
                name = d.name,
                kind = d.kind,
                source = TagSource.AUTO,
                evidenceText = d.evidence,
            ).also { db.tagDao().upsert(it) }
            links += DreamTagEntity(dreamId = dreamId, tagId = row.id)
        }
        db.dreamTagDao().upsertAll(links)
    }

    override suspend fun callCount(dreamId: String): Int = db.llmCallDao().countByDream(dreamId)

    override suspend fun versions(dreamId: String): List<NovelVersionEntity> =
        db.novelVersionDao().listByDream(dreamId)

    override suspend fun messages(dreamId: String): List<NovelMessageEntity> =
        db.novelMessageDao().listByDream(dreamId)

    override suspend fun commitRevision(
        dreamId: String,
        userMessage: NovelMessageEntity,
        assistantMessage: NovelMessageEntity,
        draft: NovelVersionEntity,
        changeLogJson: String,
    ): NovelVersionEntity = db.withTransaction {
        val dream = requireNotNull(db.dreamDao().getById(dreamId)) { "dream 不存在：$dreamId" }
        require(userMessage.dreamId == dreamId) { "user 消息的 dreamId 不一致" }
        require(assistantMessage.dreamId == dreamId) { "assistant 消息的 dreamId 不一致" }
        db.novelMessageDao().insert(userMessage)
        // 版本号取「版本表最大值」与「dreams.novelVersion」的较大者 +1：真实写入两条线始终同步，
        // 但历史种子/人工修库可能只有 dreams 侧有版本号，不能从 v1 重新数（§0.9 用例 2 期望 3→4）。
        val next = maxOf(db.novelVersionDao().maxVersion(dreamId) ?: 0, dream.novelVersion) + 1
        val row = draft.copy(version = next)
        db.novelVersionDao().insert(row)
        // §0.1 要求同时写 novelVersion；DreamDao 的唯一窄 UPDATE 不写这一列（Task 8 契约），
        // 而 DreamDao/DreamDatabase 都在 Task 9 白名单外，所以这里在事务内用一条等价窄 SQL。
        db.openHelper.writableDatabase.execSQL(
            "UPDATE dreams SET novelText = ?, novelChangeLog = ?, novelVersion = ? WHERE id = ?",
            arrayOf<Any?>(row.text, changeLogJson, row.version, dreamId),
        )
        db.novelMessageDao().insert(
            assistantMessage.copy(versionId = row.id),
        )
        row
    }

    override suspend fun commitAsk(
        dreamId: String,
        userMessage: NovelMessageEntity,
        assistantMessage: NovelMessageEntity,
    ) {
        db.withTransaction {
            requireNotNull(db.dreamDao().getById(dreamId)) { "dream 不存在：$dreamId" }
            require(userMessage.dreamId == dreamId) { "user 消息的 dreamId 不一致" }
            require(assistantMessage.dreamId == dreamId) { "assistant 消息的 dreamId 不一致" }
            db.novelMessageDao().insert(userMessage)
            db.novelMessageDao().insert(assistantMessage)
        }
    }

    override suspend fun selectVersion(dreamId: String, version: Int) {
        db.withTransaction {
            val exists = db.novelVersionDao().listByDream(dreamId).any { it.version == version }
            require(exists) { "版本 v$version 不存在" }
            // D336：选择/撤回只动 novelSelectedVersion 一列；DreamDao 在白名单外，用等价窄 SQL。
            db.openHelper.writableDatabase.execSQL(
                "UPDATE dreams SET novelSelectedVersion = ? WHERE id = ?",
                arrayOf<Any?>(version, dreamId),
            )
        }
    }
}

private fun DreamEntity.toDream() = Dream(
    id = id,
    date = dreamDate,
    title = title,
    rawTranscript = rawTranscript,
    faithfulText = faithfulText,
    novelText = novelText,
    novelChangeLog = novelChangeLog,
    mood = mood,
    isDraft = isDraft,
    novelVersion = novelVersion,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    transcriptStatus = transcriptStatus,
    rawAudioPath = rawAudioPath,
    rawAudioMs = rawAudioMs,
    novelSelectedVersion = novelSelectedVersion,
    // 真机事故（2026-11-01）：漏了这一列，Dream.transcriptMs 恒为 0，
    // 于是 generatable() 的「rawAudioMs <= transcriptMs」永远不成立，
    // 有录音的梦一律被判「转写未完成」——生成链在真机上完全打不开。
    transcriptMs = transcriptMs,
)
