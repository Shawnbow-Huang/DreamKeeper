package com.dreamkeeper.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v2 的 8 张表：v1 的 `dreams` / `tags` / `dream_tags` / `media_assets`，
 * 加上 Plan 3 的 `novel_versions` / `novel_messages` / `generation_jobs` / `llm_calls`。
 *
 * **没有** `prompt_versions`：提示词以 `prompts/` 文件 + sha256 为唯一真源（D267），
 * 库里只留 `promptId/promptVersion/promptHash` 三元组。
 *
 * 升级路径只有一条：`1.json` → `2.json`，由 [MIGRATION_1_2] 手写 SQL 完成，Room 在打开时
 * 自校验 schema。**永远不许** `fallbackToDestructiveMigration()`：升级失败宁可崩，
 * 也不能把用户的梦静默删掉。
 */
@Database(
    entities = [DreamEntity::class, TagEntity::class, DreamTagEntity::class,
                MediaAssetEntity::class, NovelVersionEntity::class, NovelMessageEntity::class,
                GenerationJobEntity::class, LlmCallEntity::class],
    version = 3,
    exportSchema = true,      // 必须 true：schema json 是 migration 的唯一对照物
)
@TypeConverters(Converters::class)
abstract class DreamDatabase : RoomDatabase() {
    abstract fun dreamDao(): DreamDao
    abstract fun tagDao(): TagDao
    abstract fun dreamTagDao(): DreamTagDao
    abstract fun mediaAssetDao(): MediaAssetDao
    abstract fun novelVersionDao(): NovelVersionDao
    abstract fun novelMessageDao(): NovelMessageDao
    abstract fun generationJobDao(): GenerationJobDao
    abstract fun llmCallDao(): LlmCallDao

    companion object {
        const val DB_NAME = "dreamkeeper.db"

        /** 当前 schema 版本；[com.dreamkeeper.data.db.DbBackup] 的升级前快照门闩引用它，避免两处漂移。 */
        const val SCHEMA_VERSION = 3

        fun create(context: Context): DreamDatabase =
            Room.databaseBuilder(context, DreamDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
            // 注意这里没有、也永远不许加 fallbackToDestructiveMigration()：
            // 升级时宁可崩在 migration，也不能把用户的梦静默删掉。

        /**
         * v1 → v2。SQL **逐字**抄自编译产物
         * `android/app/schemas/com.dreamkeeper.data.db.DreamDatabase/2.json` 的 `createSql`
         * （只把 `${TABLE_NAME}` 换成真表名）；改一个字都会让 Room 的 schema 自校验在
         * 老用户机器上抛 `Migration didn't properly handle ...`。
         *
         * 老数据一个字节都不搬：`dreams` 只 `ALTER TABLE ADD COLUMN` 追两列（可空、默认 NULL），
         * 四张新表从零建；`dream_tags` 只补一个 `tagId` 索引，答「这个标签下有哪些梦」时
         * 不用全表扫。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) dreams 末尾追加两列。必须是 ADD COLUMN（不能整表重建）：
                //    整表重建一不小心就把数据搬丢，是这类迁移最经典的假绿来源。
                db.execSQL("ALTER TABLE `dreams` ADD COLUMN `novelSelectedVersion` INTEGER")
                db.execSQL("ALTER TABLE `dreams` ADD COLUMN `generationStage` TEXT")

                // 2) 四张新表 —— createSql 与 2.json 完全一致（含反引号与列顺序）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `novel_versions` (`id` TEXT NOT NULL, " +
                        "`dreamId` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                        "`text` TEXT NOT NULL, `changeLogJson` TEXT, `origin` TEXT NOT NULL, " +
                        "`promptId` TEXT NOT NULL, `promptVersion` INTEGER NOT NULL, " +
                        "`promptHash` TEXT NOT NULL, `model` TEXT NOT NULL, " +
                        "`reasoningEffort` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                // 一个 dream 的版本号必须唯一：唯一索引是「并发两路生成只留一版」的硬保证
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_novel_versions_dreamId_version` " +
                        "ON `novel_versions` (`dreamId`, `version`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `novel_messages` (`id` TEXT NOT NULL, " +
                        "`dreamId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`versionId` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_novel_messages_dreamId_createdAt` " +
                        "ON `novel_messages` (`dreamId`, `createdAt`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `generation_jobs` (`id` TEXT NOT NULL, " +
                        "`dreamId` TEXT NOT NULL, `stage` TEXT NOT NULL, " +
                        "`attemptNo` INTEGER NOT NULL, `error` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `llm_calls` (`id` TEXT NOT NULL, " +
                        "`dreamId` TEXT NOT NULL, `kind` TEXT NOT NULL, `model` TEXT NOT NULL, " +
                        "`reasoningEffort` TEXT, `cacheHitTokens` INTEGER, " +
                        "`cacheMissTokens` INTEGER, `outputTokens` INTEGER, " +
                        "`reasoningTokens` INTEGER, `isPeak` INTEGER NOT NULL, " +
                        "`priceSnapshotJson` TEXT, `costMicroYuan` INTEGER NOT NULL, " +
                        "`formulaVersion` TEXT NOT NULL, `httpStatus` INTEGER, " +
                        "`attemptNo` INTEGER NOT NULL, `requestId` TEXT, `usageJson` TEXT, " +
                        "`error` TEXT, `latencyMs` INTEGER, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_llm_calls_dreamId_createdAt` " +
                        "ON `llm_calls` (`dreamId`, `createdAt`)"
                )

                // 3) 给 dream_tags 补 tagId 索引（v1 只有主键 (dreamId, tagId)，
                //    按 tagId 反查会全表扫）
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dream_tags_tagId` ON `dream_tags` (`tagId`)"
                )
            }
        }

        /**
         * v2 → v3（Plan 4 §0.2）：`dreams` 末尾追加 `transcriptMs`（转写水位），并把
         * 「已有音频 + 已转写成功 + 正文非空」的老行回填成「整段都已覆盖」。
         *
         * 回填必须带 `rawAudioMs IS NOT NULL`：列是 `NOT NULL DEFAULT 0`，
         * 把 NULL 写进去会让整个迁移 abort（教训见 Task 1 复核）。
         * 老 D202 的「录了但没转」尾巴会被回填当成已覆盖——这是接受的限制，见 §9 偏差。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `dreams` ADD COLUMN `transcriptMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE `dreams` SET `transcriptMs` = `rawAudioMs` " +
                        "WHERE `rawAudioPath` IS NOT NULL AND `rawAudioMs` IS NOT NULL " +
                        "AND `transcriptStatus` = 'OK' AND TRIM(`rawTranscript`) <> ''"
                )
            }
        }
    }
}
