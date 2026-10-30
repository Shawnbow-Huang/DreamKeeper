package com.dreamkeeper.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v2 → v3 迁移测试（Plan 4 §0.2）。
 *
 * 与 `MigrationTest` 同法：**不用 `MigrationTestHelper`**（schemas 没挂 test assets，helper 会因
 * 缺 2.json 假红），而是用 `2.json` 的 `createSql` + v2 的 `identity_hash` 手搭一个真的 v2 库，
 * 再由生产用的 [DreamDatabase.create] 打开——走的就是线上 `RoomOpenHelper.onUpgrade`。
 *
 * 两件事必须钉死：①老列一个字节都不许搬（整表重建/destructive 会立刻红）；
 * ②`transcriptMs` 回填口径：只有「有音频 + OK + 正文非空」的老行才算整段已覆盖，
 * 其余一律 0（`PENDING`/`FAILED`/无音频都不能被当成已转写，否则追加段永远不再转写）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV2ToV3Test {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private var db: DreamDatabase? = null

    private data class V2Dream(
        val id: String,
        val rawAudioPath: String?,
        val rawAudioMs: Long?,
        val transcriptStatus: String,
        val rawTranscript: String,
        val novelSelectedVersion: Int?,
        val generationStage: String?,
        val updatedAt: Long,
    )

    private val legacyDreams = listOf(
        V2Dream(
            "v2-ok-1", "/data/user/0/com.dreamkeeper/files/audio/v2-ok-1.wav", 2_400L, "OK",
            "我在电梯里，电梯一直往上。", 2, "T1", 2_222L,
        ),
        V2Dream(
            "v2-pending-2", "/data/user/0/com.dreamkeeper/files/audio/v2-pending-2.wav", 5_000L, "PENDING",
            "这一段还没转写完。", null, null, 3_333L,
        ),
        V2Dream(
            "v2-noaudio-3", null, null, "OK", "没有音频的一条梦。", null, null, 4_444L,
        ),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(DreamDatabase.DB_NAME)
        dbFile.parentFile?.mkdirs()
        deleteDbFiles()
        buildV2Database()
    }

    @After
    fun tearDown() {
        db?.close()
        db = null
        deleteDbFiles()
    }

    @Test
    fun v2DatabaseMigratesToV3AndBackfillsWatermark() = runBlocking {
        val database = withContext(Dispatchers.IO) { DreamDatabase.create(context) }
        db = database

        val ok = withContext(Dispatchers.IO) { database.dreamDao().getById("v2-ok-1") }
        assertNotNull("迁移后老行必须还在", ok)
        assertEquals("有音频 + OK + 正文非空 → 水位等于音频时长", 2_400L, ok!!.transcriptMs)

        val pending = withContext(Dispatchers.IO) { database.dreamDao().getById("v2-pending-2") }
        assertEquals("PENDING 不算已转写，水位必须是 0", 0L, pending!!.transcriptMs)

        val noAudio = withContext(Dispatchers.IO) { database.dreamDao().getById("v2-noaudio-3") }
        assertEquals("没有音频 → 0", 0L, noAudio!!.transcriptMs)

        withContext(Dispatchers.IO) {
            val raw = database.openHelper.readableDatabase
            raw.query("PRAGMA user_version").use { c ->
                c.moveToFirst()
                assertEquals("v2→v3 之后 user_version 必须是 3", 3, c.getInt(0))
            }
            raw.query("SELECT COUNT(*) FROM dreams").use { c ->
                c.moveToFirst()
                assertEquals("行数不许变（destructive 清库只剩 seed）", 3, c.getInt(0))
            }
            raw.query("PRAGMA integrity_check").use { c ->
                c.moveToFirst()
                assertEquals("ok", c.getString(0))
            }
            raw.query("PRAGMA foreign_key_check").use { c -> assertEquals(0, c.count) }
        }
    }

    @Test
    fun v2RowsKeepEveryLegacyValue() = runBlocking {
        val database = withContext(Dispatchers.IO) { DreamDatabase.create(context) }
        db = database
        val row = withContext(Dispatchers.IO) { database.dreamDao().getById("v2-ok-1") }!!

        assertEquals(TranscriptStatus.OK, row.transcriptStatus)
        assertEquals("我在电梯里，电梯一直往上。", row.rawTranscript)
        assertEquals("/data/user/0/com.dreamkeeper/files/audio/v2-ok-1.wav", row.rawAudioPath)
        assertEquals(2_400L, row.rawAudioMs)
        assertEquals("updatedAt 一个字都不许变", 2_222L, row.updatedAt)
        assertEquals("v2 列原样保留", 2, row.novelSelectedVersion)
        assertEquals("v2 列原样保留", "T1", row.generationStage)

        val columnCount = withContext(Dispatchers.IO) {
            database.openHelper.readableDatabase.query("PRAGMA table_info(dreams)").use { it.count }
        }
        assertEquals("dreams 从 22 列变 23 列（只多 transcriptMs）", 23, columnCount)

        withContext(Dispatchers.IO) {
            database.openHelper.readableDatabase.query(
                "SELECT identity_hash FROM room_master_table WHERE id = 42"
            ).use { c ->
                c.moveToFirst()
                assertNotEquals(
                    "迁移后 identity_hash 必须变",
                    "166666e50d40204e526403725c8302ab",
                    c.getString(0),
                )
            }
        }
    }

    @Test
    fun newRowsGetZeroWatermarkByDefault() = runBlocking {
        val database = withContext(Dispatchers.IO) { DreamDatabase.create(context) }
        db = database
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO dreams (id, ownerId, dreamDate, createdAt, rawTranscript, novelVersion, " +
                    "isDraft, updatedAt, faithfulEditedByUser, novelEditedByUser, transcriptStatus) " +
                    "VALUES ('fresh-1', 'local', '2026-10-31', 1, '新写的梦', 0, 0, 1, 0, 0, 'OK')"
            )
        }
        val fresh = withContext(Dispatchers.IO) { database.dreamDao().getById("fresh-1") }!!
        assertEquals("新行省略该列时必须拿到 NOT NULL DEFAULT 0", 0L, fresh.transcriptMs)
    }

    private fun buildV2Database() {
        val legacy = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        legacy.use { db ->
            V2_DDL.forEach { (_, createSql) -> db.execSQL(createSql) }
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                    "VALUES(42, '166666e50d40204e526403725c8302ab')"
            )
            legacyDreams.forEach { d ->
                db.execSQL(
                    """
                    INSERT INTO dreams (id, ownerId, dreamDate, createdAt, title, rawTranscript,
                        faithfulText, novelText, novelVersion, novelChangeLog, mood, isDraft,
                        deletedAt, updatedAt, rawAudioPath, rawAudioMs, faithfulEditedByUser,
                        novelEditedByUser, remoteId, transcriptStatus, novelSelectedVersion,
                        generationStage)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        d.id, "local", "2026-10-20", 1_000L, null, d.rawTranscript,
                        null, null, 0, null, null, 0,
                        null, d.updatedAt, d.rawAudioPath, d.rawAudioMs, 0,
                        0, null, d.transcriptStatus, d.novelSelectedVersion,
                        d.generationStage,
                    ),
                )
            }
            db.version = 2
        }
    }

    private fun deleteDbFiles() {
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
    }

    private companion object {
        /** `2.json` 的 `createSql` 原文（表与索引；`${TABLE_NAME}` 已在生成时替换成真表名）。 */
        val V2_DDL = linkedMapOf(
            "dreams" to "CREATE TABLE IF NOT EXISTS `dreams` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `dreamDate` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `title` TEXT, `rawTranscript` TEXT NOT NULL, `faithfulText` TEXT, `novelText` TEXT, `novelVersion` INTEGER NOT NULL, `novelChangeLog` TEXT, `mood` TEXT, `isDraft` INTEGER NOT NULL, `deletedAt` INTEGER, `updatedAt` INTEGER NOT NULL, `rawAudioPath` TEXT, `rawAudioMs` INTEGER, `faithfulEditedByUser` INTEGER NOT NULL, `novelEditedByUser` INTEGER NOT NULL, `remoteId` TEXT, `transcriptStatus` TEXT NOT NULL, `novelSelectedVersion` INTEGER, `generationStage` TEXT, PRIMARY KEY(`id`))",
            "tags" to "CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `source` TEXT NOT NULL, `evidenceText` TEXT, PRIMARY KEY(`id`))",
            "index_tags_name" to "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)",
            "dream_tags" to "CREATE TABLE IF NOT EXISTS `dream_tags` (`dreamId` TEXT NOT NULL, `tagId` TEXT NOT NULL, `confidence` REAL NOT NULL, PRIMARY KEY(`dreamId`, `tagId`))",
            "index_dream_tags_tagId" to "CREATE INDEX IF NOT EXISTS `index_dream_tags_tagId` ON `dream_tags` (`tagId`)",
            "media_assets" to "CREATE TABLE IF NOT EXISTS `media_assets` (`id` TEXT NOT NULL, `dreamId` TEXT NOT NULL, `type` TEXT NOT NULL, `path` TEXT, `prompt` TEXT, `model` TEXT, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "index_media_assets_dreamId" to "CREATE INDEX IF NOT EXISTS `index_media_assets_dreamId` ON `media_assets` (`dreamId`)",
            "novel_versions" to "CREATE TABLE IF NOT EXISTS `novel_versions` (`id` TEXT NOT NULL, `dreamId` TEXT NOT NULL, `version` INTEGER NOT NULL, `text` TEXT NOT NULL, `changeLogJson` TEXT, `origin` TEXT NOT NULL, `promptId` TEXT NOT NULL, `promptVersion` INTEGER NOT NULL, `promptHash` TEXT NOT NULL, `model` TEXT NOT NULL, `reasoningEffort` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "index_novel_versions_dreamId_version" to "CREATE UNIQUE INDEX IF NOT EXISTS `index_novel_versions_dreamId_version` ON `novel_versions` (`dreamId`, `version`)",
            "novel_messages" to "CREATE TABLE IF NOT EXISTS `novel_messages` (`id` TEXT NOT NULL, `dreamId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `versionId` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "index_novel_messages_dreamId_createdAt" to "CREATE INDEX IF NOT EXISTS `index_novel_messages_dreamId_createdAt` ON `novel_messages` (`dreamId`, `createdAt`)",
            "generation_jobs" to "CREATE TABLE IF NOT EXISTS `generation_jobs` (`id` TEXT NOT NULL, `dreamId` TEXT NOT NULL, `stage` TEXT NOT NULL, `attemptNo` INTEGER NOT NULL, `error` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "llm_calls" to "CREATE TABLE IF NOT EXISTS `llm_calls` (`id` TEXT NOT NULL, `dreamId` TEXT NOT NULL, `kind` TEXT NOT NULL, `model` TEXT NOT NULL, `reasoningEffort` TEXT, `cacheHitTokens` INTEGER, `cacheMissTokens` INTEGER, `outputTokens` INTEGER, `reasoningTokens` INTEGER, `isPeak` INTEGER NOT NULL, `priceSnapshotJson` TEXT, `costMicroYuan` INTEGER NOT NULL, `formulaVersion` TEXT NOT NULL, `httpStatus` INTEGER, `attemptNo` INTEGER NOT NULL, `requestId` TEXT, `usageJson` TEXT, `error` TEXT, `latencyMs` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "index_llm_calls_dreamId_createdAt" to "CREATE INDEX IF NOT EXISTS `index_llm_calls_dreamId_createdAt` ON `llm_calls` (`dreamId`, `createdAt`)",
        )
    }
}
