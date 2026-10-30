package com.dreamkeeper.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * 版本线读写。
 *
 * **`insert` 一律 `ABORT`，禁止改成 `REPLACE`**（brief §0.4）：`(dreamId, version)` 撞唯一索引时，
 * REPLACE 会先删旧行再插——那一版的正文、以及 `novel_messages.versionId` 指向它的对话，
 * 会一起变成悬空引用，而且不报错。宁可让调用方拿到 `SQLiteConstraintException`。
 */
@Dao
interface NovelVersionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(v: NovelVersionEntity)

    /** 详情页/导出按版本号倒序（最新在前）。 */
    @Query("SELECT * FROM novel_versions WHERE dreamId = :dreamId ORDER BY version DESC")
    suspend fun listByDream(dreamId: String): List<NovelVersionEntity>

    @Query("SELECT MAX(version) FROM novel_versions WHERE dreamId = :dreamId")
    suspend fun maxVersion(dreamId: String): Int?

    /**
     * 版本号 = 事务内 `MAX(version) + 1`（brief §0.4）。
     *
     * `@Transaction` 让「读 MAX」与「插入」在同一个写事务里：两个协程同时给同一条梦出稿时，
     * 后者会撞唯一索引拿 ABORT，而不是两条都算出同一个号、其中一条被静默顶掉。
     * 返回值带上真正落库的 `version`，调用方不需要自己再算一遍。
     */
    @Transaction
    suspend fun insertWithNextVersion(v: NovelVersionEntity): NovelVersionEntity {
        val next = (maxVersion(v.dreamId) ?: 0) + 1
        val row = v.copy(version = next)
        insert(row)
        return row
    }
}
