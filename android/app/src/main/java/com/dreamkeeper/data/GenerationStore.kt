package com.dreamkeeper.data

import androidx.room.withTransaction
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.DreamEntity
import com.dreamkeeper.data.db.GenerationJobEntity
import com.dreamkeeper.data.db.LlmCallEntity
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
     */
    suspend fun commitSuccess(
        dreamId: String,
        draft: NovelVersionEntity,
        changeLogJson: String?,
        stage: String?,
    ): Int

    /** 只写档位（T3 回退用）。实现复用同一条窄 UPDATE，先读行再把文本列原值写回。 */
    suspend fun updateStage(dreamId: String, stage: String)

    /** 该梦已记账的 HTTP 交换数。 */
    suspend fun callCount(dreamId: String): Int
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
    ): Int = db.withTransaction {
        // 顺序不能反：版本号由 insertWithNextVersion 自增，窄 UPDATE 用落库后的最终正文。
        val saved = db.novelVersionDao().insertWithNextVersion(draft)
        db.dreamDao().updateNovelAfterGeneration(
            id = dreamId,
            novelText = saved.text,
            novelChangeLog = changeLogJson,
            generationStage = stage,
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
        )
    }

    override suspend fun callCount(dreamId: String): Int = db.llmCallDao().countByDream(dreamId)
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
)
