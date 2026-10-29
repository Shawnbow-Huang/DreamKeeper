package com.dreamkeeper.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(tag: TagEntity)
    @Query("SELECT * FROM tags ORDER BY kind, name") fun observeAll(): Flow<List<TagEntity>>
    @Query("UPDATE tags SET name = :newName WHERE id = :id") suspend fun rename(id: String, newName: String)

    /**
     * 合并标签：把 `fromId` 的全部关联指向 `intoId`。
     * `dream_tags` 主键是 (dreamId, tagId)，同一篇梦上两个标签并存时 `REPLACE` 先删后插、收敛成一行；
     * 这也是 `dream_tags` **不能加外键 CASCADE** 的原因——CASCADE 会把被删那行的关联一起删掉。
     */
    @Query("UPDATE OR REPLACE dream_tags SET tagId = :intoId WHERE tagId = :fromId")
    suspend fun repointDreamTags(fromId: String, intoId: String)

    @Query("SELECT * FROM tags WHERE id = :id") suspend fun findById(id: String): TagEntity?

    /**
     * 按名字查重（`tags.name` 有唯一索引）。
     *
     * 重命名前**必须**先查这一下：撞名时改走 [upsert] 会触发 REPLACE，把旧标签行删掉、
     * 把它的 `dream_tags` 留成孤儿，`INNER JOIN` 之后那个标签在界面上静默消失（D75）。
     */
    @Query("SELECT * FROM tags WHERE name = :name") suspend fun findByName(name: String): TagEntity?

    @Query("SELECT COUNT(*) FROM tags") suspend fun count(): Int

    @Query("DELETE FROM dream_tags WHERE tagId = :tagId") suspend fun deleteLinksForTag(tagId: String)
    @Query("DELETE FROM tags WHERE id = :id") suspend fun deleteRow(id: String)

    /**
     * 删标签 = 先断关联、再删行，**必须在同一个事务里**。
     * 分两步裸写会在中间留下"指向不存在标签"的关联行，下次联表查询就踩空。
     */
    @Transaction
    suspend fun delete(id: String) {
        deleteLinksForTag(id)
        deleteRow(id)
    }
}
