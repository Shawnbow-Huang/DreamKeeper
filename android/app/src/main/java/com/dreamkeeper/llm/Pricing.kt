package com.dreamkeeper.llm

import android.content.Context
import java.time.Instant
import java.time.LocalDate
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
 * [cardSha256] 是资产原始字节的 sha256，由加载器算出来，不写在 JSON 里（brief §0.1）。
 */
data class PriceCard(
    val version: String,
    val effectiveFrom: LocalDate,
    val provenance: String,
    val models: Map<String, Pair<Rates, Rates>>,
    val cardSha256: String,
)

/** 价卡来源。接口化的原因是可测性：纯 JVM 单测读不到 `assets/`（brief §0.2）。 */
interface PriceCardSource {
    fun load(): PriceCard
}

/** 从 APK assets 的 `pricing/deepseek.json` 读价卡。 */
class AssetPriceCardSource(private val context: Context) : PriceCardSource {
    override fun load(): PriceCard = TODO("Task 3 实现待写：读 assets/pricing/deepseek.json → parsePriceCard")
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
 * `unit = microYuanPerMillionTokens`，且每个 model 只有 peak / offPeak 两支、
 * 逐字段 `offPeak * 2 == peak`；任何一条不满足都抛 [IllegalArgumentException]。
 */
fun parsePriceCard(rawBytes: ByteArray): PriceCard =
    TODO("Task 3 实现待写：org.json 解析 + 硬校验 + sha256(rawBytes)")

/**
 * 北京时间工作日的高峰判定。
 *
 * 必须显式接收 [zone]：现有代码里 `ZoneId.systemDefault()` 的用法（`DreamRepository.kt:259`）
 * 在非中国时区的设备上会把峰谷算错，本任务不许照抄（brief §0.6）。
 */
fun isPeak(at: Instant, zone: ZoneId = ZoneId.of("Asia/Shanghai")): Boolean =
    TODO("Task 3 实现待写：at.atZone(zone) + 工作日 + [09:00,12:00)/[14:00,18:00)")

/** 判峰谷、选费率、组装计价结果；不做 IO、不读时钟。 */
fun price(
    card: PriceCard,
    model: String,
    hit: Long,
    miss: Long,
    out: Long,
    at: Instant,
): Priced = TODO("Task 3 实现待写：白名单 → effectiveFrom → 选 rates → costMicroYuan → Ok/Overflow")
