package com.dreamkeeper.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 对话改稿的消息流；`versionId` 为空的是「只问不改」的那几轮。 */
@Dao
interface NovelMessageDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(m: NovelMessageEntity)

    /**
     * 按时间正序（对话是顺序语义）。`createdAt` 相同（同一毫秒写入）时用 `rowid` 兜底，
     * 保证同一份库两次读出来顺序一致——否则 UI 每次刷新都可能把两句交换。
     */
    @Query(
        """
        SELECT * FROM novel_messages
        WHERE dreamId = :dreamId
        ORDER BY createdAt ASC, rowid ASC
        """
    )
    suspend fun listByDream(dreamId: String): List<NovelMessageEntity>
}
