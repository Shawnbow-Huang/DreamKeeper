package com.dreamkeeper.data.db

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 升级前的本地快照（brief §0.8）。
 *
 * 为什么值得单独一个文件：Room 的迁移一旦写错，最坏情况是**用户的老库被就地改坏**，
 * 而且这种坏是不可逆的。保险只能是「动手之前先把整个库拷走」，并且要赶在 Room
 * 打开数据库（`RoomOpenHelper.onUpgrade`）**之前**——打开之后再拷，拷到的已经是半迁移状态。
 *
 * 两个反直觉的细节，写错就等于没备份：
 * 1. **必须连 `-wal` / `-shm` 一起拷**（D274）。联网/录音之后数据大量还在 WAL 里，
 *    只拷 `.db` 会得到一个 4096 B、0 张表、`user_version=0` 的空壳，看着像「备份成功，
 *    但恢复出来什么都没了」。
 * 2. 探测版本要用 SQLite 打开一次读 `PRAGMA user_version`，**不能**读文件头第 60 字节：
 *    同样是「user_version 还在 WAL 里」的坑，文件头会读到 0。
 *
 * 版本已经是 2（或更高）时什么都不备份，只顺手清理多余备份（保留最新一份，§0.8 要求
 * 迁移成功且 `integrity_check` 通过后不必留一堆几十 KB 的副本）。
 */
object DbBackup {

    private const val TAG = "DbBackup"

    /**
     * 打开数据库时注入的错误处理器：**只吞掉，不删文件**。
     *
     * Android 默认的 `DefaultDatabaseErrorHandler` 在 SQLite 报 NOTADB/CORRUPT 时会
     * `deleteDatabase()`。备份探针跑在 Room 之前，绝不能因为「库读不开」先把库删了（§0.8）。
     */
    private val NO_DELETE_ON_CORRUPTION = DatabaseErrorHandler { /* no-op */ }

    /** `files/db-backup/`：与 Room 的 `databases/` 分开，卸载重装就一起没了，不额外泄漏隐私 */
    const val DIR_NAME = "db-backup"

    private const val PREFIX = "dreamkeeper-v1-"

    fun backupDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /**
     * 打开数据库前调用：v1 老库 → 拷 db + wal + shm 三件；v2 库 → 清多余备份。
     *
     * @return 这次新建的备份文件；不需要备份或探测失败时 null（失败只记日志，绝不能因此拦住启动——
     *         备份是保险，不是前置条件）。
     */
    fun snapshotBeforeMigration(context: Context): File? {
        val dbFile = context.getDatabasePath(DreamDatabase.DB_NAME)
        if (!dbFile.exists()) return null // 新装：没有老库可保
        val version = readUserVersion(dbFile)
        if (version == null) {
            Log.w(TAG, "读不到 user_version，跳过快照（不拦启动）: ${dbFile.path}")
            return null
        }
        return when {
            version == 1 -> copyTrio(context, dbFile)
            version >= 2 -> {
                pruneOldBackups(context, dbFile)
                null
            }
            else -> null
        }
    }

    /**
     * 只读探测 `PRAGMA user_version`（会顺带读到 WAL 里的最新页）。
     *
     * **必须自带 no-op [DatabaseErrorHandler]**：`openDatabase(path, null, flags)` 用的是 Android
     * 默认 `DefaultDatabaseErrorHandler`，SQLite 一旦报 NOTADB/CORRUPT 它会把库文件**直接删掉**。
     * 这个探针跑在 Room 打开库之前，删掉就等于「备份动作本身毁掉要备份的东西」——违反 §0.8
     * 「备份前不动原库」。带 handler 后打开失败仍抛，由下面的 `runCatching` 兜成 `null`。
     */
    fun readUserVersion(dbFile: File): Int? {
        val flags = SQLiteDatabase.OPEN_READONLY
        return runCatching {
            SQLiteDatabase
                .openDatabase(dbFile.path, null, flags, NO_DELETE_ON_CORRUPTION)
                .use { db ->
                    db.rawQuery("PRAGMA user_version", null).use { c ->
                        if (c.moveToFirst()) c.getInt(0) else null
                    }
                }
        }.recoverCatching {
            // 个别设备上只读打开 WAL 库会失败（-shm 不可写），退回读写打开但只执行 PRAGMA
            SQLiteDatabase
                .openDatabase(
                    dbFile.path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                    NO_DELETE_ON_CORRUPTION,
                )
                .use { db ->
                    db.rawQuery("PRAGMA user_version", null).use { c ->
                        if (c.moveToFirst()) c.getInt(0) else null
                    }
                }
        }.onFailure { Log.w(TAG, "探测 user_version 失败: ${dbFile.path}", it) }.getOrNull()
    }

    private fun copyTrio(context: Context, dbFile: File): File? = runCatching {
        val dir = backupDir(context)
        if (!dir.exists() && !dir.mkdirs()) error("建不了备份目录: ${dir.path}")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(dir, "$PREFIX$stamp.db")
        dbFile.copyTo(target, overwrite = false)
        // -wal / -shm 不存在是正常的（干净关闭后 SQLite 会删），存在就必须一起拷
        copyIfExists(File(dbFile.path + "-wal"), File(target.path + "-wal"))
        copyIfExists(File(dbFile.path + "-shm"), File(target.path + "-shm"))
        Log.i(
            TAG,
            "v1 库已快照: ${target.name} (${target.length()} B, wal=" +
                File(target.path + "-wal").length() + " B)",
        )
        target
    }.onFailure { Log.w(TAG, "v1 库快照失败（不拦启动）", it) }.getOrNull()

    private fun copyIfExists(from: File, to: File) {
        if (from.exists()) from.copyTo(to, overwrite = false)
    }

    /**
     * v2 库已能打开时清理旧备份：只留最新一份 v1 快照。
     *
     * 先 `PRAGMA integrity_check`：迁移写坏但还能打开时，唯一干净的那份可能是刚被覆盖前的备份，
     * 这时删副本等于把退路删了。检查不通过就一份都不删。
     */
    private fun pruneOldBackups(context: Context, liveDb: File) {
        val dir = backupDir(context)
        val backups = dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".db") }
            ?: return
        if (backups.size <= 1) return
        if (!isIntact(liveDb)) {
            Log.w(TAG, "integrity_check 未通过，保留全部 v1 备份不删")
            return
        }
        backups.sortedByDescending { it.name }.drop(1).forEach { old ->
            File(old.path + "-wal").delete()
            File(old.path + "-shm").delete()
            if (old.delete()) Log.i(TAG, "清理旧备份: ${old.name}")
        }
    }

    private fun isIntact(dbFile: File): Boolean = runCatching {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                c.moveToFirst() && c.getString(0).equals("ok", ignoreCase = true)
            }
        }
    }.getOrDefault(false)
}
