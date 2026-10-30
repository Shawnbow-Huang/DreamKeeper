package com.dreamkeeper.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/**
 * v1 → v2 迁移测试（brief §0.7 / D271）。
 *
 * **不用 `MigrationTestHelper`**：`room-testing` 只在 `testImplementation`，而且 schemas 目录没有挂到
 * test assets，helper 连 `1.json` 都找不到（红的原因会是「缺 assets」而不是「缺迁移」，等于假红）。
 * 替代做法：用 `1.json` 里四条 `createSql` + `room_master_table.identity_hash` **手搭一个真的 v1 库**，
 * 再用 app 真正用的 `DreamDatabase.create(context)` 打开——它走的就是 `RoomOpenHelper.onUpgrade`
 * 那条路径，缺迁移时抛的正是线上会抛的
 * `IllegalStateException: A migration from 1 to 2 was required but not found`。
 *
 * 第二条命是「迁移只加表不搬数据」：v1 里放两行**非种子**数据（其中一行带 `rawAudioPath`/`rawAudioMs`），
 * 迁移后逐列比对。若实现里混进 `fallbackToDestructiveMigration()` 或整表重建，这里会立刻红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private var db: DreamDatabase? = null

    private class LegacyDream(
        val id: String,
        val title: String?,
        val novelText: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val rawAudioPath: String?,
        val rawAudioMs: Long?,
        val transcriptStatus: String,
    )

    private val legacyDreams = listOf(
        LegacyDream(
            id = "legacy-dream-1",
            title = "老标题：电梯",
            novelText = "旧手稿第一行：电梯一直往上。\n旧手稿第二行：数字过了三十。",
            createdAt = 1_111L,
            updatedAt = 2_222L,
            rawAudioPath = "/data/user/0/com.dreamkeeper/files/audio/legacy-dream-1.wav",
            rawAudioMs = 2_400L,
            transcriptStatus = TranscriptStatus.FAILED,
        ),
        LegacyDream(
            id = "legacy-dream-2",
            title = null,
            novelText = null,
            createdAt = 3_333L,
            updatedAt = 4_444L,
            rawAudioPath = null,
            rawAudioMs = null,
            transcriptStatus = TranscriptStatus.OK,
        ),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(DreamDatabase.DB_NAME)
        dbFile.parentFile?.mkdirs()
        deleteDbFiles()
        buildV1Database()
    }

    @After
    fun tearDown() {
        db?.close()
        db = null
        deleteDbFiles()
    }

    // ---------------------------------------------------------------- 迁移

    @Test
    fun v1DatabaseMigratesToV2AndKeepsEveryLegacyValue() = runBlocking {
        val database = withContext(Dispatchers.IO) { DreamDatabase.create(context) }
        db = database

        // 第一句 DAO 查询就会真正打开库、触发 onUpgrade → MIGRATION_1_2
        val dream = withContext(Dispatchers.IO) { database.dreamDao().getById("legacy-dream-1") }
        assertNotNull("迁移后老行必须还在", dream)
        val row = dream!!

        assertEquals("legacy-dream-1", row.id)
        assertEquals("local", row.ownerId)
        assertEquals(LocalDate.parse("2026-10-01"), row.dreamDate)
        assertEquals(1_111L, row.createdAt)
        assertEquals("老标题：电梯", row.title)
        assertEquals("我在电梯里，电梯一直往上，按不到一楼。", row.rawTranscript)
        assertNull(row.faithfulText)
        assertEquals("旧手稿第一行：电梯一直往上。\n旧手稿第二行：数字过了三十。", row.novelText)
        assertEquals(3, row.novelVersion)
        assertNull(row.novelChangeLog)
        assertNull(row.mood)
        assertEquals(false, row.isDraft)
        assertNull(row.deletedAt)
        assertEquals("updatedAt 一个字都不许变", 2_222L, row.updatedAt)
        assertEquals("/data/user/0/com.dreamkeeper/files/audio/legacy-dream-1.wav", row.rawAudioPath)
        assertEquals(2_400L, row.rawAudioMs)
        assertEquals(true, row.faithfulEditedByUser)
        assertEquals(false, row.novelEditedByUser)
        assertNull(row.remoteId)
        assertEquals(TranscriptStatus.FAILED, row.transcriptStatus)

        // v2 新列：老行必须都是 NULL（不是 0、不是空串）
        assertNull(row.novelSelectedVersion)
        assertNull(row.generationStage)
        // v3 新列：FAILED 的老行不算「已转写」，水位必须是 0
        assertEquals("FAILED 行不许被回填成已覆盖", 0L, row.transcriptMs)

        val second = withContext(Dispatchers.IO) { database.dreamDao().getById("legacy-dream-2") }
        assertNotNull(second)
        assertEquals(4_444L, second!!.updatedAt)
        assertEquals("无音频老行水位也是 0", 0L, second.transcriptMs)

        withContext(Dispatchers.IO) {
            val raw = database.openHelper.readableDatabase
            raw.query("PRAGMA user_version").use { c ->
                c.moveToFirst()
                assertEquals("v1→v3 之后 user_version 必须是 3", 3, c.getInt(0))
            }
            raw.query("SELECT COUNT(*) FROM dreams").use { c ->
                c.moveToFirst()
                assertEquals("行数不许变（destructive 清库后会只剩重新 seed 的行）", 2, c.getInt(0))
            }
            raw.query("SELECT COUNT(novelSelectedVersion), COUNT(generationStage) FROM dreams").use { c ->
                c.moveToFirst()
                assertEquals("新列必须全 NULL", 0, c.getInt(0))
                assertEquals("新列必须全 NULL", 0, c.getInt(1))
            }
            raw.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { c ->
                c.moveToFirst()
                assertNotEquals(
                    "迁移后 identity_hash 必须变（说明 schema 真的换了）",
                    "3150a0bccf0e3cb537faa8aac20e4186",
                    c.getString(0),
                )
            }
            raw.query("PRAGMA integrity_check").use { c ->
                c.moveToFirst()
                assertEquals("ok", c.getString(0))
            }
            raw.query("PRAGMA foreign_key_check").use { c ->
                assertEquals("v1 四表零外键，v2 也不许加", 0, c.count)
            }
        }
    }

    @Test
    fun migrationCreatesTheFourNewTablesAndTagIdIndex() = runBlocking {
        val database = withContext(Dispatchers.IO) { DreamDatabase.create(context) }
        db = database
        withContext(Dispatchers.IO) {
            val raw = database.openHelper.readableDatabase
            val tables = mutableSetOf<String>()
            raw.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
            listOf("novel_versions", "novel_messages", "generation_jobs", "llm_calls").forEach {
                assertTrue("缺新表 $it（迁移里的 CREATE 被删就会这样）", it in tables)
            }
            val appTables = tables - setOf("android_metadata")
            assertEquals(
                "业务表 + room_master_table 应为 9 张，实际 $appTables",
                9,
                appTables.size,
            )

            val indices = mutableSetOf<String>()
            raw.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'").use { c ->
                while (c.moveToNext()) indices += c.getString(0)
            }
            listOf(
                "index_novel_versions_dreamId_version",
                "index_novel_messages_dreamId_createdAt",
                "index_llm_calls_dreamId_createdAt",
                "index_dream_tags_tagId",
            ).forEach { assertTrue("缺索引 $it", it in indices) }

            // dreams 上不许有索引（brief §0.3）
            raw.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'dreams' AND name NOT LIKE 'sqlite_%'").use { c ->
                assertEquals("dreams 不许加索引（sqlite_autoindex_* 是 TEXT 主键自带的不算）", 0, c.count)
            }
        }
    }

    // ------------------------------------------------------------ 新表往返

    @Test
    fun fourNewTablesRoundTripChineseAndNewlines() = runBlocking {
        val database = withContext(Dispatchers.IO) { DreamDatabase.create(context) }
        db = database
        val versions = database.novelVersionDao()
        val messages = database.novelMessageDao()
        val jobs = database.generationJobDao()
        val calls = database.llmCallDao()

        val body = "第一行：电梯一直往上。\n第二行：数字过了三十还在涨。\n第三行：我按不到一楼。"
        val v1 = versions.insertWithNextVersion(
            NovelVersionEntity(
                id = "nv-1",
                dreamId = "legacy-dream-1",
                version = 0, // 由 insertWithNextVersion 覆盖成 1
                text = body,
                changeLogJson = """{"changes":["删了两段描写"]}""",
                origin = "GENERATE",
                promptId = "novelize",
                promptVersion = 3,
                promptHash = "abc123",
                model = "deepseek-flash",
                reasoningEffort = "high",
                createdAt = 5_000L,
            )
        )
        assertEquals(1, v1.version)

        val v2 = versions.insertWithNextVersion(
            NovelVersionEntity(
                id = "nv-2",
                dreamId = "legacy-dream-1",
                version = 0,
                text = "改稿后的正文：我想按一楼。",
                changeLogJson = null,
                origin = "REVISE",
                promptId = "revise",
                promptVersion = 1,
                promptHash = "def456",
                model = "deepseek-flash",
                createdAt = 6_000L,
            )
        )
        assertEquals("同一 dream 的第二版必须是 2", 2, v2.version)

        val stored = versions.listByDream("legacy-dream-1")
        assertEquals(2, stored.size)
        assertEquals("最新版在前", 2, stored[0].version)
        assertEquals("中文与换行必须逐字相等", body, stored[1].text)

        // 撞 (dreamId, version) 唯一索引必须 ABORT：行还在、正文没被顶掉
        val duplicate = runCatching {
            versions.insert(
                NovelVersionEntity(
                    id = "nv-dup",
                    dreamId = "legacy-dream-1",
                    version = 1,
                    text = "偷偷替换掉的正文",
                    origin = "GENERATE",
                    promptId = "novelize",
                    promptVersion = 3,
                    promptHash = "abc123",
                    model = "deepseek-flash",
                    createdAt = 7_000L,
                )
            )
        }
        assertTrue(
            "重复版本号必须抛 SQLiteConstraintException（ABORT），实际=${duplicate.exceptionOrNull()}",
            duplicate.exceptionOrNull() is SQLiteConstraintException,
        )
        assertEquals("REPLACE 会删掉旧行；ABORT 必须保留", body, versions.listByDream("legacy-dream-1")[1].text)

        messages.insert(
            NovelMessageEntity(
                id = "nm-1",
                dreamId = "legacy-dream-1",
                role = "USER",
                content = "把第二段删掉，别那么华丽。",
                versionId = "nv-2",
                createdAt = 8_000L,
            )
        )
        messages.insert(
            NovelMessageEntity(
                id = "nm-2",
                dreamId = "legacy-dream-1",
                role = "ASSISTANT",
                content = "删好了。\n还改了三处形容。",
                versionId = null,
                createdAt = 8_000L,
            )
        )
        val msgs = messages.listByDream("legacy-dream-1")
        assertEquals(2, msgs.size)
        assertEquals("USER", msgs[0].role)
        assertEquals("删好了。\n还改了三处形容。", msgs[1].content)

        jobs.insert(
            GenerationJobEntity(
                id = "gj-1",
                dreamId = "legacy-dream-1",
                stage = "T2",
                attemptNo = 1,
                error = null,
                createdAt = 9_000L,
            )
        )
        jobs.insert(
            GenerationJobEntity(
                id = "gj-2",
                dreamId = "legacy-dream-1",
                stage = "T3",
                attemptNo = 2,
                error = "HTTP 503：上游过载",
                createdAt = 9_500L,
            )
        )
        assertEquals("T3", jobs.latest("legacy-dream-1")!!.stage)

        calls.insert(
            LlmCallEntity(
                id = "lc-1",
                dreamId = "legacy-dream-1",
                kind = "NOVELIZE",
                model = "deepseek-flash",
                reasoningEffort = "high",
                cacheHitTokens = 128,
                cacheMissTokens = 900,
                outputTokens = 700,
                reasoningTokens = null,
                isPeak = true,
                priceSnapshotJson = """{"version":"2026-10-30"}""",
                costMicroYuan = 4_321L,
                formulaVersion = "cost-v1",
                httpStatus = 200,
                attemptNo = 1,
                requestId = "req-1",
                usageJson = """{"prompt_tokens":1028}""",
                error = null,
                latencyMs = 1_234L,
                createdAt = 10_000L,
            )
        )
        calls.insert(
            LlmCallEntity(
                id = "lc-2",
                dreamId = "legacy-dream-1",
                kind = "NOVELIZE",
                model = "deepseek-flash",
                isPeak = false,
                formulaVersion = "cost-v1",
                httpStatus = 503,
                attemptNo = 2,
                error = "HTTP 503：上游过载",
                latencyMs = 88L,
                createdAt = 11_000L,
            )
        )
        val logged = calls.listByDream("legacy-dream-1")
        assertEquals(2, logged.size)
        assertEquals(4_321L, logged[0].costMicroYuan)
        assertTrue(logged[0].isPeak)
        assertEquals("HTTP 503：上游过载", logged[1].error)
        assertEquals(2, calls.countByDream("legacy-dream-1"))
    }

    // ------------------------------------------------------------ v1 手搭

    private fun buildV1Database() {
        val legacy = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        legacy.use { db ->
            V1_TABLES.forEach { (name, createSql) ->
                db.execSQL(createSql.replace("\${TABLE_NAME}", name))
            }
            db.execSQL(V1_TAGS_NAME_INDEX.replace("\${TABLE_NAME}", "tags"))
            db.execSQL(V1_MEDIA_DREAM_INDEX.replace("\${TABLE_NAME}", "media_assets"))
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                    "VALUES(42, '3150a0bccf0e3cb537faa8aac20e4186')"
            )

            legacyDreams.forEach { d ->
                db.execSQL(
                    """
                    INSERT INTO dreams (id, ownerId, dreamDate, createdAt, title, rawTranscript,
                        faithfulText, novelText, novelVersion, novelChangeLog, mood, isDraft,
                        deletedAt, updatedAt, rawAudioPath, rawAudioMs, faithfulEditedByUser,
                        novelEditedByUser, remoteId, transcriptStatus)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        d.id, "local", "2026-10-01", d.createdAt, d.title,
                        "我在电梯里，电梯一直往上，按不到一楼。",
                        null, d.novelText, 3, null, null, 0,
                        null, d.updatedAt, d.rawAudioPath, d.rawAudioMs,
                        if (d.id == "legacy-dream-1") 1 else 0, 0, null, d.transcriptStatus,
                    ),
                )
            }
            db.execSQL(
                "INSERT INTO tags (id, name, kind, source, evidenceText) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>("tag-1", "电梯", "OBJECT", "auto", "梦里的电梯一直往上"),
            )
            db.execSQL(
                "INSERT INTO dream_tags (dreamId, tagId, confidence) VALUES (?, ?, ?)",
                arrayOf<Any?>("legacy-dream-1", "tag-1", 0.85),
            )
            db.execSQL(
                "INSERT INTO media_assets (id, dreamId, type, path, prompt, model, status, createdAt) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "asset-1", "legacy-dream-1", "IMAGE",
                    "/data/user/0/com.dreamkeeper/files/media/asset-1.png",
                    "夜里的电梯井", "seedream", "READY", 12_345L,
                ),
            )
            db.version = 1
        }
    }

    private fun deleteDbFiles() {
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
    }

    private companion object {
        /** `1.json` 的 `createSql` 原文（`${'\u0024'}{TABLE_NAME}` 由 Room 在创建时替换成表名）。 */
        val V1_TABLES = linkedMapOf(
            "dreams" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `dreamDate` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `title` TEXT, `rawTranscript` TEXT NOT NULL, `faithfulText` TEXT, `novelText` TEXT, `novelVersion` INTEGER NOT NULL, `novelChangeLog` TEXT, `mood` TEXT, `isDraft` INTEGER NOT NULL, `deletedAt` INTEGER, `updatedAt` INTEGER NOT NULL, `rawAudioPath` TEXT, `rawAudioMs` INTEGER, `faithfulEditedByUser` INTEGER NOT NULL, `novelEditedByUser` INTEGER NOT NULL, `remoteId` TEXT, `transcriptStatus` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "tags" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `source` TEXT NOT NULL, `evidenceText` TEXT, PRIMARY KEY(`id`))",
            "dream_tags" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`dreamId` TEXT NOT NULL, `tagId` TEXT NOT NULL, `confidence` REAL NOT NULL, PRIMARY KEY(`dreamId`, `tagId`))",
            "media_assets" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `dreamId` TEXT NOT NULL, `type` TEXT NOT NULL, `path` TEXT, `prompt` TEXT, `model` TEXT, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        const val V1_TAGS_NAME_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `\${TABLE_NAME}` (`name`)"
        const val V1_MEDIA_DREAM_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_media_assets_dreamId` ON `\${TABLE_NAME}` (`dreamId`)"
    }
}
