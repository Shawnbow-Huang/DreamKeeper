package com.dreamkeeper.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * v1 的 4 张表：`dreams` / `tags` / `dream_tags` / `media_assets`。
 *
 * **没有** `generation_jobs`、**没有** `prompt_versions`：前者由 Plan 3 建（届时 v1→v2 靠本任务的
 * `1.json` 做 migration 基线），后者不建库——提示词以 `prompts/` 文件为唯一真源。
 */
@Database(
    entities = [DreamEntity::class, TagEntity::class, DreamTagEntity::class,
                MediaAssetEntity::class],
    version = 1,
    exportSchema = true,      // 必须 true：schema json 是 migration 的唯一对照物
)
@TypeConverters(Converters::class)
abstract class DreamDatabase : RoomDatabase() {
    abstract fun dreamDao(): DreamDao
    abstract fun tagDao(): TagDao
    abstract fun dreamTagDao(): DreamTagDao
    abstract fun mediaAssetDao(): MediaAssetDao

    companion object {
        fun create(context: Context): DreamDatabase =
            Room.databaseBuilder(context, DreamDatabase::class.java, "dreamkeeper.db").build()
            // 注意这里没有、也永远不许加 fallbackToDestructiveMigration()：
            // 升级时宁可崩在 migration，也不能把用户的梦静默删掉。
    }
}
