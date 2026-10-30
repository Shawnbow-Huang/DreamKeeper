package com.dreamkeeper.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.llm.gates.FaithfulGates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

/** 导出格式。`extension` 同时用于文件名与 MIME 选择，不在别处再写一遍。 */
enum class ExportFormat(val extension: String, val mimeType: String) {
    TXT("txt", "text/plain"),
    DOCX(
        "docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ),
    PDF("pdf", "application/pdf"),
}

/** 一次成功导出的结果。`uri` 是 MediaStore 里的 Downloads 项，可直接用于分享。 */
data class ExportResult(val uri: Uri, val displayName: String, val bytes: Long)

/** 导出失败的可读原因；`ExportSheet` 直接展示 [message]，不静默吞错。 */
class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 导出器（Task 10 §0.3）。
 *
 * 实现必须先把内容完整构造进内存再写 MediaStore：三件格式共用同一份 [ExportDocument]，
 * 这样 txt/docx/pdf 的头部与正文不可能各自走样（§0.4「与详情页逐字相同」）。
 */
interface Exporter {
    suspend fun export(dreamId: String, format: ExportFormat): ExportResult
}

/**
 * 待写出的导出文档：头部三行（标题/日期/标签）+ 正文。
 *
 * - [title] 已把换行压成空格、空标题兜底成 `dream`（D373）；
 * - [dateLine] 形如 `yyyy-MM-dd HH:mm`，时分来源见 [ExportDates.dateLine]；
 * - [tags] 已按 `tagId ASC` 排好，空列表时渲染层整行省略；
 * - [body] 与详情页用同一函数取出的正文（CR 由渲染层统一归一为 LF）。
 */
data class ExportDocument(
    val title: String,
    val dateLine: String,
    val tags: List<String>,
    val body: String,
    val date: LocalDate,
    val version: Int,
    val raw: Boolean,
) {
    /** `共 N 字` 的 N：只算正文内容字符（不含头部与空行），口径复用 G1 的 [FaithfulGates]。 */
    val charCount: Int get() = FaithfulGates.contentCharCount(body)

    fun fileName(format: ExportFormat): String =
        ExportNaming.fileName(date, title, version, raw, format)
}

/** 正文换行归一：txt 断言 `\r` 计数 0，docx 的「每行一段」也按归一后的行切。 */
object ExportText {
    fun normalizeLf(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
}

/**
 * 头部第 2 行的日期时间。
 *
 * 计划 §0.1 的 `<dreamDate yyyy-MM-dd HH:mm>` 在数据模型里没有直接来源：`Dream.date` 是
 * `LocalDate`。取「日期 = dreamDate；时分 = createdAt 在 Asia/Shanghai 的 HH:mm；
 * createdAt<=0（历史/种子行）写 00:00」。整行改用 createdAt 会让种子梦显示 1970，
 * 因此不采用。`check-export.py` 必须用同一规则从 DB 推导期望串。
 */
object ExportDates {
    val ASIA_SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun dateLine(date: LocalDate, createdAt: Long, zone: ZoneId = ASIA_SHANGHAI): String {
        val time = if (createdAt > 0L) {
            Instant.ofEpochMilli(createdAt).atZone(zone).toLocalTime().format(TIME_FORMAT)
        } else {
            "00:00"
        }
        return "$date $time"
    }
}

/**
 * 文件名与标题清洗（§0.2 / D373）。
 *
 * 规则必须与 `scripts/check-export.py` 逐字一致：
 * 1. 去掉 `\ / : * ? " < > |`，控制字符与空白折叠成一个 ASCII 空格；
 * 2. 去首尾空格；
 * 3. 按 **Unicode code point** 截前 20 个字符（不区分中文/emoji，不加省略号）；
 * 4. 空标题兜底 `dream`。
 *
 * 有小说稿：`<yyyyMMdd>-<title20>-v<n>.ext`；无小说稿：`<yyyyMMdd>-<title20>-raw.ext`
 * （无小说版本号，因此不硬编 `-v0`）。
 */
object ExportNaming {
    private const val MAX_TITLE_CODE_POINTS = 20
    private val ILLEGAL = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /** 头部第 1 行的标题部分：换行压成空格；空标题兜底 `dream`。 */
    fun headerTitle(raw: String?): String {
        val flat = (raw ?: "").replace('\r', ' ').replace('\n', ' ').trim()
        return flat.ifEmpty { "dream" }
    }

    fun cleanTitle(raw: String?): String {
        val source = raw ?: ""
        val sb = StringBuilder(source.length)
        var started = false
        var pendingSpace = false
        for (ch in source) {
            if (ch in ILLEGAL) continue
            if (ch.isWhitespace() || ch.isISOControl()) {
                if (started) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                sb.append(' ')
                pendingSpace = false
            }
            sb.append(ch)
            started = true
        }
        val collapsed = sb.toString()
        return if (collapsed.isEmpty()) "dream" else takeCodePoints(collapsed, MAX_TITLE_CODE_POINTS)
    }

    /** 按 code point 截断，避免把 emoji 的代理对切成半个字符。 */
    fun takeCodePoints(text: String, limit: Int): String {
        if (limit <= 0) return ""
        if (text.codePointCount(0, text.length) <= limit) return text
        return text.substring(0, text.offsetByCodePoints(0, limit))
    }

    fun fileName(
        date: LocalDate,
        title: String?,
        version: Int,
        raw: Boolean,
        format: ExportFormat,
    ): String {
        val name = cleanTitle(title)
        val datePart = date.format(DateTimeFormatter.BASIC_ISO_DATE)
        val tail = if (raw) "-raw" else "-v$version"
        return "$datePart-$name$tail.${format.extension}"
    }
}

/**
 * 生产导出器：读 DB → 构造 [ExportDocument] → 按格式渲染字节 → 写 `Download/DreamKeeper/`。
 *
 * 落点用 `MediaStore.Downloads`（API 29+ 免权限），不引 FileProvider、不改 manifest；
 * API 26–28 由 UI 层禁用按钮，这里再做一道硬闸（D379）。
 */
class AndroidExporter(
    private val repo: DreamRepository,
    private val resolver: ContentResolver,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
    private val zone: ZoneId = ExportDates.ASIA_SHANGHAI,
) : Exporter {

    override suspend fun export(dreamId: String, format: ExportFormat): ExportResult {
        if (apiLevel < Build.VERSION_CODES.Q) {
            throw ExportException("系统版本过低，导出需要 Android 10 及以上")
        }
        val document = buildDocument(dreamId)
        val bytes = when (format) {
            ExportFormat.TXT -> TxtExporter.bytes(document)
            ExportFormat.DOCX -> DocxExporter.render(document)
            ExportFormat.PDF -> PdfExporter.render(document)
        }
        val displayName = document.fileName(format)
        val uri = writeToDownloads(displayName, format.mimeType, bytes)
        return ExportResult(uri = uri, displayName = displayName, bytes = bytes.size.toLong())
    }

    private suspend fun buildDocument(dreamId: String): ExportDocument {
        val dream: Dream = repo.findById(dreamId) ?: throw ExportException("这篇梦找不到了")
        val versions = repo.listVersions(dreamId)
        val finalNovel = repo.resolveFinalNovel(dreamId)
        val body = ExportText.normalizeLf(
            finalNovel?.novelText
                ?: dream.faithfulText
                ?: dream.rawTranscript,
        )
        if (body.isBlank()) throw ExportException("这篇还没有可导出的正文")
        val tags = repo.observeTagsForDream(dreamId).first()
            .sortedBy { it.id }
            .map { it.name }
        val raw = !hasNovelVersion(dream, versions)
        return ExportDocument(
            title = ExportNaming.headerTitle(dream.title),
            dateLine = ExportDates.dateLine(dream.date, dream.createdAt, zone),
            tags = tags,
            body = body,
            date = dream.date,
            version = finalNovel?.version ?: dream.novelVersion,
            raw = raw,
        )
    }

    /** 与详情页同口径：有版本看 promptId，无版本回退旧字段（D394）。 */
    private fun hasNovelVersion(dream: Dream, versions: List<com.dreamkeeper.data.db.NovelVersionEntity>): Boolean =
        if (versions.isNotEmpty()) {
            versions.any { it.promptId != "faithful" }
        } else {
            dream.novelText != null || dream.novelVersion > 0
        }

    private fun writeToDownloads(displayName: String, mimeType: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/DreamKeeper",
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw ExportException("保存失败：没有可用的下载目录")
        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw ExportException("保存失败：写不进下载目录")
            stream.use {
                it.write(bytes)
                it.flush()
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw if (t is ExportException) t else ExportException("保存失败：${t.message ?: "写入下载目录出错"}", t)
        }
        return uri
    }
}
