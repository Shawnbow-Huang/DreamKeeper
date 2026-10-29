package com.dreamkeeper.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 梦 ⇄ 标签关联表的**唯一写入方**；`TagDao` 只管标签本身。
 *
 * **禁止给 `dream_tags` 加外键 CASCADE。**
 * Task 9 的标签合并走 `UPDATE OR REPLACE`（REPLACE 冲突 = 先删后插），
 * 一旦有 CASCADE，删那一行时会把它的关联行一起删掉——合并一次丢一批标注，而且不报错。
 * 关联只能显式清理：`deleteByDream` / `deleteByTag` / `TagDao.delete` 里的事务。
 */
@Dao
interface DreamTagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertAll(links: List<DreamTagEntity>)

    @Query("DELETE FROM dream_tags WHERE dreamId = :dreamId") suspend fun deleteByDream(dreamId: String)

    /** 详情页「标签」tab 的数据源：联表取标签，按 kind, name 排序 */
    @Query("""
        SELECT tags.* FROM tags
        INNER JOIN dream_tags ON tags.id = dream_tags.tagId
        WHERE dream_tags.dreamId = :dreamId
        ORDER BY tags.kind, tags.name
    """)
    fun observeTagsForDream(dreamId: String): Flow<List<TagEntity>>

    @Query("DELETE FROM dream_tags WHERE tagId = :tagId") suspend fun deleteByTag(tagId: String)
}
