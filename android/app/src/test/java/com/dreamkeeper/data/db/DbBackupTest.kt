package com.dreamkeeper.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `DbBackup` 单测（Task 4 收尾补测；brief §0.8 / D270；Plan 4 版本门闩泛化）。
 *
 * 为什么要单独测：真实升级路径只覆盖了「恰好是上一版本时拷了三件」这一条，而 `DbBackup`
 * 的失败分支（没有库、库读不开、已到目标版本 prune、integrity_check 不通过）都在 App 启动最先
 * 执行的地方，一旦这里抛异常就是**无法启动**——而它的契约恰恰是「失败只记日志，绝不拦启动」。
 *
 * 四个容易写错的点，测试逐条钉住：
 * 1. 备份必须连 `-wal` / `-shm` 一起拷（D274）：只拷 `.db` 会得到 4096 B 的空壳。
 * 2. 文件名必须带 `yyyyMMdd-HHmmss` 时间戳，且落在 `files/db-backup/`。
 * 3. 文件名前缀必须按**来源版本**生成（v2 用户升级 v3 前是 `dreamkeeper-v2-*`，Plan 4）。
 * 4. 已到目标版本（默认 v3）的库只清理多余备份、保留最新一份（计划 §0.8 原文「留一份」），
 *    且先 `integrity_check`；这里显式传 `targetVersion = 2` 锁定「已到目标」语义。
 *
 * 造库方式与 `MigrationTest` 一致：裸 `SQLiteDatabase.openOrCreateDatabase` + `db.version = N`，
 * **不用 `MigrationTestHelper`**（D271：schemas 没挂 test assets，helper 只会造成假红）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DbBackupTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var backupDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(DreamDatabase.DB_NAME)
        dbFile.parentFile?.mkdirs()
        deleteDbFiles()
        backupDir = DbBackup.backupDir(context)
        backupDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        deleteDbFiles()
        backupDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ 1. v1 三件

    @Test
    fun v1SnapshotCopiesDbAndPresentSidecarsWithTimestampedName() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.use { open ->
            open.execSQL("CREATE TABLE IF NOT EXISTS dreams (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
            open.execSQL("INSERT INTO dreams (id) VALUES ('dream-1')")
            open.version = 1

            // 尽量造出真实的 -wal/-shm（Robolectric 原生 sqlite 支持 WAL）；
            // 若该环境不支持 WAL，退化为两个空文件，至少把 copyIfExists 的三件契约钉住。
            val walEnabled = open.enableWriteAheadLogging()
            if (walEnabled) {
                open.execSQL("INSERT INTO dreams (id) VALUES ('dream-wal')")
            }
            val sourceWal = File(dbFile.path + "-wal")
            val sourceShm = File(dbFile.path + "-shm")
            if (!walEnabled) {
                sourceWal.writeBytes(ByteArray(0))
                sourceShm.writeBytes(ByteArray(0))
            }
            // 证据用：记录本环境是否真的支持 WAL（Robolectric 原生 sqlite 支持时三件都是真实内容）
            println(
                "DbBackupTest: walEnabled=$walEnabled dbBytes=${dbFile.length()} " +
                    "walBytes=${sourceWal.length()} shmBytes=${sourceShm.length()}",
            )

            val snapshot = DbBackup.snapshotBeforeMigration(context)
            assertNotNull("v1 库必须产出快照", snapshot)
            val target = snapshot!!
            assertEquals(
                "备份必须落在 files/db-backup/",
                backupDir.canonicalPath,
                target.parentFile?.canonicalPath,
            )
            assertTrue(
                "文件名必须带 yyyyMMdd-HHmmss 时间戳，实际 ${target.name}",
                target.name.matches(Regex("dreamkeeper-v1-[0-9]{8}-[0-9]{6}[.]db")),
            )
            assertEquals(".db 大小必须相同", dbFile.length(), target.length())
            assertTrue(".db 字节必须相同", dbFile.readBytes().contentEquals(target.readBytes()))

            assertTrue("v1 时 .db 的 -wal 必须存在才能测到三件契约", sourceWal.exists())
            assertTrue("v1 时 .db 的 -shm 必须存在才能测到三件契约", sourceShm.exists())
            val copiedWal = File(target.path + "-wal")
            val copiedShm = File(target.path + "-shm")
            assertTrue("-wal 必须一起拷", copiedWal.isFile)
            assertTrue("-shm 必须一起拷", copiedShm.isFile)
            assertTrue(
                "-wal 字节必须与源一致",
                sourceWal.readBytes().contentEquals(copiedWal.readBytes()),
            )
            assertTrue(
                "-shm 字节必须与源一致",
                sourceShm.readBytes().contentEquals(copiedShm.readBytes()),
            )
        }
    }

    // ------------------------------------------------------------------ 2. v2 版本前缀快照

    @Test
    fun v2SnapshotCopiesDbWithVersionedPrefixAndIdenticalContent() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.use { open ->
            open.execSQL("CREATE TABLE IF NOT EXISTS dreams (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
            open.execSQL("INSERT INTO dreams (id) VALUES ('v2-before-upgrade')")
            open.version = 2
        }

        // 默认 targetVersion 是 TARGET_SCHEMA_VERSION（Plan 4 的 3）；v2 仍低于目标，必须快照。
        val snapshot = DbBackup.snapshotBeforeMigration(context)

        assertNotNull("v2 库在目标 v3 之下必须产出快照", snapshot)
        val target = snapshot!!
        assertEquals(
            "备份必须落在 files/db-backup/",
            backupDir.canonicalPath,
            target.parentFile?.canonicalPath,
        )
        assertTrue(
            "前缀必须按来源版本生成，实际 ${target.name}",
            target.name.matches(Regex("dreamkeeper-v2-[0-9]{8}-[0-9]{6}[.]db")),
        )
        assertEquals("v2 快照大小必须与源库一致", dbFile.length(), target.length())
        assertTrue("v2 快照字节必须与源库一致", dbFile.readBytes().contentEquals(target.readBytes()))
    }

    // ------------------------------------------------------------------ 3. 已到目标版本 prune

    @Test
    fun v2LibraryPrunesOldSnapshotsKeepingOnlyNewest() {
        val live = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        live.use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS dreams (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("INSERT INTO dreams (id) VALUES ('after-migration')")
            db.version = 2
        }

        backupDir.mkdirs()
        val names = listOf(
            "dreamkeeper-v1-20260101-000000.db",
            "dreamkeeper-v1-20260102-000000.db",
            "dreamkeeper-v1-20260103-000000.db",
        )
        names.forEachIndexed { index, name ->
            File(backupDir, name).writeBytes(ByteArray(32) { (it + index).toByte() })
        }
        File(backupDir, names[0] + "-wal").writeText("wal-of-oldest")
        File(backupDir, names[0] + "-shm").writeText("shm-of-oldest")

        // 显式 targetVersion=2：把「v2 即目标版本」的历史语义固定住（默认 target 已随 Plan 4 升到 3）。
        val result = DbBackup.snapshotBeforeMigration(context, targetVersion = 2)

        assertNull("已到目标版本的库不需要新快照", result)
        val remaining = backupDir.listFiles { f -> f.name.startsWith("dreamkeeper-v1-") && f.name.endsWith(".db") }
            ?.map { it.name }?.toSet()
        assertEquals("只保留最新一份", setOf(names.last()), remaining)
        assertFalse("最旧的 .db 必须被删", File(backupDir, names[0]).exists())
        assertFalse("旧备份的 -wal 必须一起删", File(backupDir, names[0] + "-wal").exists())
        assertFalse("旧备份的 -shm 必须一起删", File(backupDir, names[0] + "-shm").exists())
        assertTrue("最新一份必须保留", File(backupDir, names.last()).exists())
    }

    // ------------------------------------------------------------------ 4. v3 不再生成快照

    @Test
    fun v3DatabaseAtTargetCreatesNoNewSnapshot() {
        val live = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        live.use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS dreams (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("INSERT INTO dreams (id) VALUES ('already-v3')")
            db.version = 3
        }
        assertFalse("前置：备份目录不存在", backupDir.exists())

        val result = DbBackup.snapshotBeforeMigration(context)

        assertNull("v3 已是目标版本，不该再生成快照", result)
        assertFalse("没有旧快照时不该建备份目录", backupDir.exists())
    }

    // ------------------------------------------------------------------ 5. 新装无库

    @Test
    fun missingDatabaseReturnsNullAndDoesNotCreateBackupDir() {
        assertFalse("前置：库里没有老库", dbFile.exists())
        assertFalse("前置：还没有备份目录", backupDir.exists())

        val result = DbBackup.snapshotBeforeMigration(context)

        assertNull("新装没有老库可保", result)
        assertFalse("没有老库时不该建备份目录", backupDir.exists())
    }

    // ------------------------------------------------------------------ 6. 探测失败不拦启动

    @Test
    fun unreadableDatabaseReturnsNullWithoutThrowing() {
        val corruptText = "这不是一个 sqlite 数据库"
        dbFile.writeText(corruptText)
        val expectedLen = dbFile.length()
        assertTrue("前置：非法库文件已写入", dbFile.isFile)

        // no-op DatabaseErrorHandler 的契约：SQLite 报 NOTADB/CORRUPT 时**不删文件**。
        // 这个探针跑在 Room 之前，删掉就等于备份动作本身毁掉要备份的库（§0.8）。
        val probed = DbBackup.readUserVersion(dbFile)
        assertNull("非法文件读不到 user_version", probed)
        println(
            "DbBackupTest: corruptProbe exists=${dbFile.exists()} len=${dbFile.length()} " +
                "expectedLen=$expectedLen",
        )
        assertTrue("探测后原文件必须仍在（no-op DatabaseErrorHandler）", dbFile.exists())
        assertEquals("探测不许改动原文件字节数", expectedLen, dbFile.length())

        val result = DbBackup.snapshotBeforeMigration(context)

        assertNull("探测失败必须返回 null", result)
        assertFalse("探测失败时不该建备份目录", backupDir.exists())
        assertTrue("快照流程结束后原文件仍必须存在", dbFile.exists())
        assertEquals("快照流程结束后原文件字节数不变", expectedLen, dbFile.length())
    }

    // ------------------------------------------------------------------ 辅助

    private fun deleteDbFiles() {
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-journal").delete()
    }
}
