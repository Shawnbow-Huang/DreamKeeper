package com.dreamkeeper.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * 计价与峰谷判定的单测，数据全部来自 `docs/evidence/plan3/p3-cost-01-vectors.json`
 * （brief §0.9：测试读向量，不在测试里另抄期望）。
 *
 * 向量文件先于实现提交；任一 `expect_micro` 被改 +1，这里必红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CostCalculatorTest {

    private val doc: JSONObject by lazy {
        JSONObject(String(vectorsFile().readBytes(), Charsets.UTF_8))
    }

    @Test
    fun `14 组向量的微元价与峰谷判定逐条相符`() {
        val card = cardFromVectors()
        val vectors = vectors()
        assertEquals("向量条数", 14, vectors.size)
        for (v in vectors) {
            val id = v.getString("id")
            val result = price(
                card,
                v.getString("model"),
                v.getLong("hit"),
                v.getLong("miss"),
                v.getLong("out"),
                Instant.parse(v.getString("at")),
            )
            assertTrue("$id 期望 Ok，实际 $result", result is Priced.Ok)
            val ok = result as Priced.Ok
            assertEquals("$id microYuan", v.getLong("expect_micro"), ok.microYuan)
            assertEquals("$id isPeak", v.getBoolean("expect_peak"), ok.isPeak)
            assertEquals("$id cardVersion", card.version, ok.cardVersion)
            assertEquals("$id cardSha256", card.cardSha256, ok.cardSha256)
        }
    }

    @Test
    fun `isPeak 逐组与向量期望一致`() {
        for (v in vectors()) {
            assertEquals(
                v.getString("id"),
                v.getBoolean("expect_peak"),
                isPeak(Instant.parse(v.getString("at"))),
            )
        }
    }

    @Test
    fun `时区反事实-同一 Instant 按 UTC 判会翻转`() {
        var tzCases = 0
        for (v in vectors()) {
            if (v.getString("kind") != "tz") continue
            tzCases++
            val at = Instant.parse(v.getString("at"))
            val id = v.getString("id")
            assertEquals("$id 上海", v.getBoolean("expect_peak"), isPeak(at))
            assertEquals("$id UTC", v.getBoolean("expect_peak_utc"), isPeak(at, ZoneId.of("UTC")))
        }
        assertEquals("tz 反事实向量条数", 2, tzCases)
    }

    @Test
    fun `默认时区改成纽约后仍按 Asia-Shanghai 判峰`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            // 01:00Z = 上海 09:00（峰），纽约 21:00（谷）；若实现偷读默认时区就会翻成谷
            assertEquals(true, isPeak(Instant.parse("2026-10-30T01:00:00Z")))
            assertEquals(false, isPeak(Instant.parse("2026-10-30T10:30:00Z")))
            val result = price(
                cardFromVectors(),
                "deepseek-flash",
                1L,
                1L,
                1L,
                Instant.parse("2026-10-30T01:00:00Z"),
            )
            assertTrue("期望 Ok，实际 $result", result is Priced.Ok)
            val ok = result as Priced.Ok
            assertEquals(5L, ok.microYuan)
            assertEquals(true, ok.isPeak)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `两倍差-整除组写等式-通用组合每股漂移不超过 1 微元`() {
        val card = cardFromVectors()
        val peakAt = Instant.parse("2026-10-30T02:00:00Z") // 周五 10:00 上海
        val offAt = Instant.parse("2026-10-30T04:00:00Z") // 周五 12:00 上海
        assertEquals(true, isPeak(peakAt))
        assertEquals(false, isPeak(offAt))

        val peak1M = price(card, "deepseek-flash", 1_000_000L, 1_000_000L, 1_000_000L, peakAt) as Priced.Ok
        val off1M = price(card, "deepseek-flash", 1_000_000L, 1_000_000L, 1_000_000L, offAt) as Priced.Ok
        assertEquals(5_020_000L, peak1M.microYuan)
        assertEquals(2_510_000L, off1M.microYuan)
        assertEquals("整除组必须严格两倍", peak1M.microYuan, 2 * off1M.microYuan)

        for (v in vectors()) {
            val id = v.getString("id")
            val p = price(
                card, v.getString("model"), v.getLong("hit"), v.getLong("miss"), v.getLong("out"), peakAt,
            ) as Priced.Ok
            val o = price(
                card, v.getString("model"), v.getLong("hit"), v.getLong("miss"), v.getLong("out"), offAt,
            ) as Priced.Ok
            val delta = p.microYuan - 2 * o.microYuan
            // brief §0.8 的 -1..0 有反例（arith-02 的 4.65→5 对 2.325→2，差 +1），见 D263
            assertTrue("$id 峰谷漂移 $delta 应在 -1..1", delta in -1L..1L)
        }
    }

    @Test
    fun `未知模型前置拒绝-绝不退化成 0 元`() {
        val result = price(
            cardFromVectors(),
            "deepseek-chat",
            1_000L,
            0L,
            0L,
            Instant.parse("2026-10-30T01:00:00Z"),
        )
        assertEquals(Priced.UnknownModel("deepseek-chat"), result)
    }

    @Test
    fun `生效日之前判 CardNotApplicable-不是谷价`() {
        val card = cardFromVectors()
        val before = price(
            card, "deepseek-flash", 1L, 1L, 1L, Instant.parse("2026-10-29T15:59:59Z"),
        )
        assertTrue("应 CardNotApplicable，实际 $before", before is Priced.CardNotApplicable)
        assertTrue(
            "reason 应提到 effectiveFrom，实际 ${(before as Priced.CardNotApplicable).reason}",
            before.reason.contains("effectiveFrom"),
        )

        // 生效日当天 00:00（Asia/Shanghai）= 前一日 16:00Z，属可计价的最早时刻
        val atStart = price(
            card, "deepseek-flash", 1L, 1L, 1L, Instant.parse("2026-10-29T16:00:00Z"),
        )
        assertTrue("生效起点应可计价，实际 $atStart", atStart is Priced.Ok)
        val ok = atStart as Priced.Ok
        assertEquals(false, ok.isPeak)
        assertEquals(3L, ok.microYuan)
    }

    @Test
    fun `溢出抛 ArithmeticException-价格层归为 Overflow`() {
        try {
            costMicroYuan(Rates(1L, 1L, 1L), Long.MAX_VALUE, 0L, 0L)
            fail("应在 +500_000 处溢出")
        } catch (_: ArithmeticException) {
            // expected
        }
        val result = price(
            cardFromVectors(),
            "deepseek-flash",
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Instant.parse("2026-10-30T01:00:00Z"),
        )
        assertTrue("应 Overflow，实际 $result", result is Priced.Overflow)
    }

    @Test
    fun `只在最后取整一次-4-65-微元得 5 而非 4`() {
        // v4-pro peak 1/1/0：分子 4,650,000；+500,000 后 5.15 → 5；漏半格直接截断会得 4
        val micro = costMicroYuan(Rates(150_000L, 4_500_000L, 13_500_000L), 1L, 1L, 0L)
        assertEquals(5L, micro)
        assertTrue("漏 +500_000 会得 4", micro != 4L)
    }

    @Test
    fun `零用量成本为 0`() {
        assertEquals(0L, costMicroYuan(Rates(20000L, 1_000_000L, 4_000_000L), 0L, 0L, 0L))
    }

    @Test
    fun `向量 usage 与 hit-miss-out 自洽`() {
        for (v in vectors()) {
            val id = v.getString("id")
            val hit = v.getLong("hit")
            val miss = v.getLong("miss")
            val out = v.getLong("out")
            val usage = v.getJSONObject("usage")
            assertEquals("$id prompt_tokens", usage.getLong("prompt_tokens"), hit + miss)
            assertEquals("$id completion_tokens", usage.getLong("completion_tokens"), out)
            assertEquals(
                "$id total_tokens",
                usage.getLong("total_tokens"),
                usage.getLong("prompt_tokens") + out,
            )
            assertEquals("$id prompt_cache_hit_tokens", usage.getLong("prompt_cache_hit_tokens"), hit)
            assertEquals("$id prompt_cache_miss_tokens", usage.getLong("prompt_cache_miss_tokens"), miss)
        }
    }

    private fun vectorsFile(): File =
        File(repoRoot(), "docs/evidence/plan3/p3-cost-01-vectors.json")

    private fun vectors(): List<JSONObject> {
        val array = doc.getJSONArray("vectors")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun cardFromVectors(): PriceCard {
        val card = doc.getJSONObject("card")
        val modelsJson = card.getJSONObject("models")
        val models = modelsJson.keys().asSequence().associateWith { name ->
            val model = modelsJson.getJSONObject(name)
            model.getJSONObject("peak").toRates() to model.getJSONObject("offPeak").toRates()
        }
        return PriceCard(
            version = card.getString("version"),
            effectiveFrom = LocalDate.parse(card.getString("effectiveFrom")),
            provenance = card.getString("provenance"),
            models = models,
            cardSha256 = "vectors-card",
        )
    }

    private fun JSONObject.toRates(): Rates = Rates(
        cacheHit = getLong("cacheHit"),
        cacheMiss = getLong("cacheMiss"),
        output = getLong("output"),
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "docs/evidence/plan3").isDirectory &&
                File(dir, "android/app/build.gradle.kts").isFile
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        fail("找不到仓库根（从 ${File("").absolutePath} 上溯）")
        throw IllegalStateException("unreachable")
    }
}
