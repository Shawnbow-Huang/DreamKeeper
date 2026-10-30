package com.dreamkeeper.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/**
 * 价卡解析与资产加载的单测。
 *
 * 钉的是「计划 §4 的常量」——测试里写死期望值（brief §0.1），不拿实现当自己的答案。
 * 资产在纯 JVM 单测里读不到 `assets/`，所以这里直接读**仓库文件**字节调 `parsePriceCard`；
 * `AssetPriceCardSource` 那一条则靠 Robolectric 合并后的 assets 走真实路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PricingTest {

    /** 黄金值：`sha256(android/app/src/main/assets/pricing/deepseek.json)`，首次生成后人工抄入。 */
    private val goldenSha256 = "becbdd149105561fdf10e2a4c778328ca174dd1ead99bb624511826363e7174d"

    @Test
    fun `资产解析出计划钉死的版本-生效日-来源`() {
        val card = parseAsset()
        assertEquals("2026-10-30", card.version)
        assertEquals(LocalDate.of(2026, 10, 30), card.effectiveFrom)
        assertEquals("project-convention", card.provenance)
        assertEquals(2, card.models.size)
    }

    @Test
    fun `deepseek-flash 峰谷价与计划常量逐字段一致`() {
        val (peak, offPeak) = parseAsset().models.getValue("deepseek-flash")
        assertEquals(Rates(20000L, 1_000_000L, 4_000_000L), peak)
        assertEquals(Rates(10000L, 500_000L, 2_000_000L), offPeak)
        assertEquals(5_020_000L, costMicroYuan(peak, 1_000_000L, 1_000_000L, 1_000_000L))
        assertEquals(2_510_000L, costMicroYuan(offPeak, 1_000_000L, 1_000_000L, 1_000_000L))
    }

    @Test
    fun `deepseek-v4-pro 峰谷价与计划常量逐字段一致`() {
        val (peak, offPeak) = parseAsset().models.getValue("deepseek-v4-pro")
        assertEquals(Rates(150_000L, 4_500_000L, 13_500_000L), peak)
        assertEquals(Rates(75_000L, 2_250_000L, 6_750_000L), offPeak)
    }

    @Test
    fun `每个模型每个字段 offPeak 恰为 peak 之半`() {
        for ((name, branches) in parseAsset().models) {
            val (peak, offPeak) = branches
            assertEquals("$name.cacheHit", peak.cacheHit, offPeak.cacheHit * 2)
            assertEquals("$name.cacheMiss", peak.cacheMiss, offPeak.cacheMiss * 2)
            assertEquals("$name.output", peak.output, offPeak.output * 2)
        }
    }

    @Test
    fun `cardSha256 是资产原始字节的哈希`() {
        assertEquals(goldenSha256, parseAsset().cardSha256)
        assertEquals(goldenSha256, sha256Hex(assetBytes()))
    }

    @Test
    fun `不认识的 currency 拒绝加载`() {
        assertRejected(assetText().replace("\"CNY\"", "\"USD\""), "currency")
    }

    @Test
    fun `不认识的 unit 拒绝加载`() {
        assertRejected(
            assetText().replace("\"microYuanPerMillionTokens\"", "\"yuanPerMillionTokens\""),
            "unit",
        )
    }

    @Test
    fun `不认识的 provenance 拒绝加载`() {
        assertRejected(assetText().replace("\"project-convention\"", "\"official-page\""), "provenance")
    }

    @Test
    fun `offPeak 不是 peak 之半时拒绝加载`() {
        // flash.offPeak.cacheMiss 500000 → 500001，*2 != 1000000（value 在 JSON 里只出现一次）
        assertRejected(assetText().replace("\"cacheMiss\":500000", "\"cacheMiss\":500001"), "cacheMiss")
    }

    @Test
    fun `AssetPriceCardSource 从 assets 读出与仓库文件相同的卡`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loaded = AssetPriceCardSource(context).load()
        assertEquals(goldenSha256, loaded.cardSha256)
        assertEquals(parseAsset(), loaded)
    }

    @Test
    fun `PriceCardSource 接口可被测试替身替换`() {
        val card = parseAsset()
        val source: PriceCardSource = FakePriceCardSource(card)
        assertSame(card, source.load())
    }

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

    private fun assetBytes(): ByteArray =
        File(repoRoot(), "android/app/src/main/assets/pricing/deepseek.json").readBytes()

    private fun assetText(): String = String(assetBytes(), Charsets.UTF_8)

    private fun parseAsset(): PriceCard = parsePriceCard(assetBytes())

    private fun assertRejected(json: String, needle: String) {
        try {
            parsePriceCard(json.toByteArray(Charsets.UTF_8))
            fail("应当拒绝，异常信息里应有 $needle")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常信息应提到 $needle，实际：${e.message}", e.message.orEmpty().contains(needle))
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class FakePriceCardSource(private val card: PriceCard) : PriceCardSource {
        override fun load(): PriceCard = card
    }
}
