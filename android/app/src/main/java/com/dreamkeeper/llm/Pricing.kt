package com.dreamkeeper.llm

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 峰谷两档费率，单位「微元 / 1M tokens」。
 *
 * 用 `Long` 而不是 `Int`：`20000/1_000_000` 在整型下等于 0，是计价最典型的事故（brief §0.3）。
 */
data class Rates(val cacheHit: Long, val cacheMiss: Long, val output: Long)

/**
 * 一张价卡。
 *
 * [models] 的 value 约定 **first = peak、second = offPeak**（[PriceCard] 的公开签名是
 * `Map<String, Pair<Rates, Rates>>`，Pair 没有字段名，只能靠约定 + KDoc 固定）。
 * [cardSha256] 是资产原始字节的 sha256，由加载器算出来，不写在 JSON 里（brief §0.1）：
 * 自哈希字段是自指固定点，且 JSON 没有规范序列化。
 * [rawJson] 是资产原文（D317）：`llm_calls.price_snapshot_json` 要按原键、原数字格式复算，
 * 重新序列化会引入键序/数字格式差异，所以直接存字节原文。
 */
data class PriceCard(
    val version: String,
    val effectiveFrom: LocalDate,
    val provenance: String,
    val models: Map<String, Pair<Rates, Rates>>,
    val cardSha256: String,
    val rawJson: String = "",
)

/** 价卡来源。接口化的原因是可测性：纯 JVM 单测读不到 `assets/`（brief §0.2）。 */
interface PriceCardSource {
    fun load(): PriceCard
}

/** 从 APK assets 的 `pricing/deepseek.json` 读价卡；sha256 在 [parsePriceCard] 里按原始字节算。 */
class AssetPriceCardSource(private val context: Context) : PriceCardSource {
    override fun load(): PriceCard {
        val bytes = context.assets.open(ASSET_PATH).use { stream -> stream.readBytes() }
        return parsePriceCard(bytes)
    }

    private companion object {
        const val ASSET_PATH = "pricing/deepseek.json"
    }
}

/**
 * 计价结果。未知 model 在调用前就按白名单拒，绝不退化成 0 元（0 与「免费」不可区分）。
 */
sealed interface Priced {
    data class Ok(
        val microYuan: Long,
        val isPeak: Boolean,
        val cardVersion: String,
        val cardSha256: String,
    ) : Priced

    data class UnknownModel(val model: String) : Priced

    data class CardNotApplicable(val reason: String) : Priced

    data class Overflow(val detail: String) : Priced
}

/**
 * 解析价卡原始字节。
 *
 * 只认 `provenance = project-convention`、`currency = CNY`、
 * `unit = microYuanPerMillionTokens`；每个 model 只允许 peak / offPeak 两支，
 * 每支只允许 cacheHit / cacheMiss / output 三个正数，且逐字段 `offPeak * 2 == peak`
 * （峰价必须全偶，半价才整数精确）。任何一条不满足都抛 [IllegalArgumentException]——不猜、不回退。
 */
fun parsePriceCard(rawBytes: ByteArray): PriceCard = try {
    parsePriceCardOrThrow(rawBytes)
} catch (e: JSONException) {
    throw IllegalArgumentException("价卡 JSON 解析失败：${e.message}", e)
}

private fun parsePriceCardOrThrow(rawBytes: ByteArray): PriceCard {
    val json = JSONObject(String(rawBytes, Charsets.UTF_8))

    val version = json.getString("version")
    require(version.isNotBlank()) { "version 不能为空" }
    val effectiveFrom = LocalDate.parse(json.getString("effectiveFrom"))

    val provenance = json.getString("provenance")
    require(provenance == PROVENANCE) { "不认识的 provenance=$provenance（只认 $PROVENANCE）" }
    val currency = json.getString("currency")
    require(currency == CURRENCY) { "不认识的 currency=$currency（只认 $CURRENCY）" }
    val unit = json.getString("unit")
    require(unit == UNIT) { "不认识的 unit=$unit（只认 $UNIT）" }

    val modelsJson = json.getJSONObject("models")
    require(modelsJson.length() > 0) { "models 不能为空" }

    val models = LinkedHashMap<String, Pair<Rates, Rates>>()
    for (name in modelsJson.keys()) {
        val model = modelsJson.getJSONObject(name)
        require(model.length() == 2 && model.has("peak") && model.has("offPeak")) {
            "$name 只允许 peak/offPeak 两支，实际 ${model.keys().asSequence().toList()}"
        }
        val peak = model.getJSONObject("peak").toRates("$name.peak")
        val offPeak = model.getJSONObject("offPeak").toRates("$name.offPeak")
        requireHalf(name, "cacheHit", peak.cacheHit, offPeak.cacheHit)
        requireHalf(name, "cacheMiss", peak.cacheMiss, offPeak.cacheMiss)
        requireHalf(name, "output", peak.output, offPeak.output)
        models[name] = peak to offPeak
    }

    return PriceCard(
        version = version,
        effectiveFrom = effectiveFrom,
        provenance = provenance,
        models = models,
        cardSha256 = sha256Hex(rawBytes),
        rawJson = String(rawBytes, Charsets.UTF_8),
    )
}

private fun JSONObject.toRates(path: String): Rates {
    require(length() == 3) {
        "$path 只允许 cacheHit/cacheMiss/output，实际 ${keys().asSequence().toList()}"
    }
    fun field(name: String): Long {
        require(has(name)) { "$path 缺少 $name" }
        val value = getLong(name)
        require(value > 0) { "$path.$name 必须为正：$value" }
        return value
    }
    return Rates(
        cacheHit = field("cacheHit"),
        cacheMiss = field("cacheMiss"),
        output = field("output"),
    )
}

private fun requireHalf(model: String, field: String, peak: Long, offPeak: Long) {
    require(offPeak in 1..(Long.MAX_VALUE / 2)) {
        "$model.offPeak.$field 越界：$offPeak"
    }
    require(offPeak * 2 == peak) {
        "$model.offPeak.$field*2 != $model.peak.$field（$offPeak*2 != $peak）"
    }
}

/**
 * 北京时间工作日的高峰判定：`[09:00,12:00)` 与 `[14:00,18:00)`，周六/周日全天谷。
 *
 * 必须显式接收 [zone]：现有代码里 `ZoneId.systemDefault()` 的用法（`DreamRepository.kt:259`、
 * `ui/record/RecordViewModel.kt:266`）在非中国时区的设备上会把峰谷算错，本任务不许照抄（brief §0.6）。
 */
fun isPeak(at: Instant, zone: ZoneId = ZoneId.of("Asia/Shanghai")): Boolean {
    val local = at.atZone(zone)
    if (local.dayOfWeek.value >= 6) return false
    val time = local.toLocalTime()
    return (time >= MORNING_START && time < MORNING_END) ||
        (time >= AFTERNOON_START && time < AFTERNOON_END)
}

/**
 * 判峰谷、选费率、组装计价结果；不做 IO、不读时钟。
 *
 * 顺序是刻意的：先白名单（未知 model 绝不落成 0 元账目），再 `effectiveFrom`
 * （早于生效日判 [Priced.CardNotApplicable]，**不是**按谷价），最后才算钱。
 * 取整只在 [costMicroYuan] 里做一次；算术溢出归为 [Priced.Overflow]，不回绕、不静默。
 */
fun price(
    card: PriceCard,
    model: String,
    hit: Long,
    miss: Long,
    out: Long,
    at: Instant,
): Priced {
    if (model !in SUPPORTED_MODELS) return Priced.UnknownModel(model)

    val effectiveAt = card.effectiveFrom.atStartOfDay(SHANGHAI).toInstant()
    if (at < effectiveAt) {
        return Priced.CardNotApplicable(
            "at=$at 早于 effectiveFrom=${card.effectiveFrom}（$effectiveAt）",
        )
    }

    val branches = card.models[model]
        ?: return Priced.CardNotApplicable("价卡 ${card.version} 缺少 model=$model")
    val peak = isPeak(at, SHANGHAI)
    val rates = if (peak) branches.first else branches.second

    return try {
        Priced.Ok(
            microYuan = costMicroYuan(rates, hit, miss, out),
            isPeak = peak,
            cardVersion = card.version,
            cardSha256 = card.cardSha256,
        )
    } catch (e: ArithmeticException) {
        Priced.Overflow("costMicroYuan 溢出：${e.message}")
    }
}

private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
private val SUPPORTED_MODELS = setOf("deepseek-flash", "deepseek-v4-pro")
private const val CURRENCY = "CNY"
private const val UNIT = "microYuanPerMillionTokens"
private const val PROVENANCE = "project-convention"
private val MORNING_START: LocalTime = LocalTime.of(9, 0)
private val MORNING_END: LocalTime = LocalTime.of(12, 0)
private val AFTERNOON_START: LocalTime = LocalTime.of(14, 0)
private val AFTERNOON_END: LocalTime = LocalTime.of(18, 0)

/** `sha256(rawBytes)` 的小写十六进制；资产不带自哈希字段（brief §0.1）。 */
private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
