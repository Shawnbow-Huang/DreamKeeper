package com.dreamkeeper.audio

import android.util.Log
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.domain.Dream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 启动扫描对单个文件做过的处置，按文件出现顺序返回（文件名升序）。
 *
 * 返回值是**诊断信息**，不是 UI 事件：调用方不 await，也不该拿它写界面。
 */
sealed interface ScanOutcome {
    /** 有行 + 头不对（占位/尺寸错/半帧）或库里的时长过期：修好了。 */
    data class Repaired(val dreamId: String, val fileBytes: Long) : ScanOutcome

    /** 有文件无行：按文件恢复成一条草稿。 */
    data class RecoveredDraft(val dreamId: String, val fileBytes: Long) : ScanOutcome

    /** 有行无文件（或文件已无有效音频）：音频指针清掉、状态标 FAILED。 */
    data class OrphanCleared(val dreamId: String) : ScanOutcome

    /** 干净、正在录音、或不可修：一个字节都没动。 */
    data object Noop : ScanOutcome
}

/**
 * 进程启动时的一次性音频兜底扫描（Plan 2 Task 6）。
 *
 * 只依赖 [WavStore] 与 [DreamRepository]，不直接摸 DAO —— 「文件系统是存在与时长的唯一真源」
 * 这条规则才能被关在一个类里（§0-5）。
 *
 * 四主状态 + 一补充（§0-7）：
 * 1. 有行 + 占位头 → [WavStore.repair] + 窄更新时长（幂等）；
 * 2. 有文件无行 → 恢复草稿（`recoverDraft` 内部「不存在才插」）；
 * 3. 有行无文件 → [DreamRepository.markMissing]；
 * 4. 干净 → [ScanOutcome.Noop]，**不写库**（`updatedAt` 是写过的证据）；
 * 5. 0 字节 / < 44 / 截掉半帧后正好 44 → 无行删文件，有行 [DreamRepository.markMissing]。
 *
 * 正在录音的文件（[WavStore.isOpen]）一律跳过：录音先写占位头、行要 DONE 才落库，
 * 「文件在 + 占位头 + 库无行」在录音中是正常状态，插了行会把 DONE 吞掉（§0-3）。
 */
class AudioScan(
    private val store: WavStore,
    private val repository: DreamRepository,
) {

    suspend fun run(): List<ScanOutcome> {
        val outcomes = mutableListOf<ScanOutcome>()
        try {
            val rows = repository.dreamsWithAudio()
            val byPath = rows.mapNotNull { row -> row.rawAudioPath?.let { it to row } }.toMap()
            val byId = rows.associateBy { it.id }
            // 已在文件轮回里处理过的行 id：反向孤儿扫描不许重复报同一条。
            val matched = HashSet<String>()

            for (file in store.files()) {
                val outcome = if (store.isOpen(file)) {
                    // 正在写：跳过。这里仍产出一个 Noop，方便证据/测试数清「看过但没动」。
                    ScanOutcome.Noop
                } else {
                    runCatching { scanFile(file, byPath, byId, matched) }.getOrElse { error ->
                        // 单坏文件不许中断全扫（§0-7）。
                        Log.w(TAG, "扫描单文件失败：${file.name}", error)
                        ScanOutcome.Noop
                    }
                }
                outcomes += outcome
            }

            // ③ 有行无文件：文件轮看不到它，只能反过来查。
            for (row in rows) {
                if (row.id in matched) continue
                val path = row.rawAudioPath ?: continue
                if (File(path).exists()) continue
                outcomes += runCatching {
                    repository.markMissing(row.id)
                    ScanOutcome.OrphanCleared(row.id)
                }.getOrElse { error ->
                    Log.w(TAG, "标记音频丢失失败：${row.id}", error)
                    ScanOutcome.Noop
                }
            }
        } catch (error: Exception) {
            // 启动路径不许因为扫描把进程带崩；已经产出的结果照常返回。
            Log.w(TAG, "启动音频扫描整体失败", error)
        }
        return outcomes
    }

    private suspend fun scanFile(
        file: File,
        byPath: Map<String, Dream>,
        byId: Map<String, Dream>,
        matched: MutableSet<String>,
    ): ScanOutcome {
        val dreamId = file.name.removeSuffix(WAV_SUFFIX)
        // 归属：path 精确匹配优先；其次 id 匹配（行可能 path 为空/过期，但 `<id>.wav` 是规范路径）。
        val row = byPath[file.absolutePath] ?: byId[dreamId]
        // 截断/修复都会改 mtime，恢复草稿的日期必须在动手前取（§0-6：不许用「今天」）。
        val mtime = file.lastModified()

        val bytes = file.length()
        if (bytes < WavMath.HEADER_BYTES) return clearEmpty(file, row, matched)

        // §0-8：奇数尾字节（半帧）先截掉，否则 markOpen 会永久拒绝追加。
        val partial = WavMath.trailingPartialBytes(bytes)
        if (partial != 0) {
            RandomAccessFile(file, "rw").use { it.setLength(bytes - partial) }
        }
        if (file.length() == WavMath.HEADER_BYTES.toLong()) return clearEmpty(file, row, matched)

        val before = store.state(file)
        val expectedRiff = before.fileBytes - 8
        val expectedData = before.fileBytes - WavMath.HEADER_BYTES
        val headerDirty = before.placeholder ||
            before.riffSize != expectedRiff ||
            before.dataSize != expectedData
        val repaired = store.repair(file) // 幂等：干净时一个字节都不写
        val ms = WavMath.durationMs(repaired.fileBytes)

        if (row != null) {
            matched += row.id
            val dbDirty = row.rawAudioMs != ms
            if (!headerDirty && !dbDirty) return ScanOutcome.Noop
            repository.fixAudioMs(row.id, ms)
            return ScanOutcome.Repaired(row.id, repaired.fileBytes)
        }

        if (repository.hasDream(dreamId)) {
            // 行在、却连音频指针都没有：只修文件，不碰行，也不许建第二条。
            matched += dreamId
            val changed = headerDirty || partial != 0
            return if (changed) ScanOutcome.Repaired(dreamId, repaired.fileBytes) else ScanOutcome.Noop
        }

        val recovered = repository.recoverDraft(
            dreamId = dreamId,
            filePath = file.absolutePath,
            rawAudioMs = ms,
            lastModifiedMs = mtime,
        )
        return when {
            recovered -> ScanOutcome.RecoveredDraft(dreamId, repaired.fileBytes)
            headerDirty || partial != 0 -> ScanOutcome.Repaired(dreamId, repaired.fileBytes)
            else -> ScanOutcome.Noop
        }
    }

    /** ⑤：没有一帧有效音频。无行删文件，有行删文件 + 清指针。 */
    private suspend fun clearEmpty(
        file: File,
        row: Dream?,
        matched: MutableSet<String>,
    ): ScanOutcome {
        val dreamId = file.name.removeSuffix(WAV_SUFFIX)
        val rowWithAudio = row != null
        val hasRow = rowWithAudio || repository.hasDream(dreamId)
        deleteEmptyFile(file)
        if (!hasRow) return ScanOutcome.Noop
        if (!rowWithAudio) {
            // 行存在但本来就没有音频指针：没有可清的，保留它的文本与状态。
            matched += dreamId
            return ScanOutcome.Noop
        }
        matched += row!!.id
        repository.markMissing(row.id)
        return ScanOutcome.OrphanCleared(row.id)
    }

    private fun deleteEmptyFile(file: File) {
        if (!file.delete() && file.exists()) {
            throw IOException("删除空 WAV 失败：${file.path}")
        }
    }

    private companion object {
        const val TAG = "AudioScan"
        const val WAV_SUFFIX = ".wav"
    }
}
