package com.dreamkeeper.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 生成尝试落点；失败也必留一行（`error` 非空），所以这里只增不删。 */
@Dao
interface GenerationJobDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: GenerationJobEntity)

    @Query("SELECT * FROM generation_jobs WHERE dreamId = :dreamId ORDER BY createdAt DESC, rowid DESC")
    suspend fun listByDream(dreamId: String): List<GenerationJobEntity>

    /** 「这条梦最后一次落在哪一档」——恢复 UI 状态时读它。 */
    @Query("SELECT * FROM generation_jobs WHERE dreamId = :dreamId ORDER BY createdAt DESC, rowid DESC LIMIT 1")
    suspend fun latest(dreamId: String): GenerationJobEntity?
}
