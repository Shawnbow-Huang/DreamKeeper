package com.dreamkeeper.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** HTTP 尝试账本；一行一次请求，失败行也必须落库。 */
@Dao
interface LlmCallDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(call: LlmCallEntity)

    @Query("SELECT * FROM llm_calls WHERE dreamId = :dreamId ORDER BY createdAt ASC, rowid ASC")
    suspend fun listByDream(dreamId: String): List<LlmCallEntity>

    /** 这条梦已经打过几次请求（`attemptNo` 的下一号 = 它 + 1）。 */
    @Query("SELECT COUNT(*) FROM llm_calls WHERE dreamId = :dreamId")
    suspend fun countByDream(dreamId: String): Int
}
