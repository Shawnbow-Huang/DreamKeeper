package com.dreamkeeper.assets

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 5 T1（D468）：四张空态插画与图标前景资产。
 *
 * 这些是静态资源，不需要 Robolectric；直接从仓库根解析 android/app/src/main/res。
 * 用例只证明「文件在、是 WebP、体积在预算内、目录集合与清单一致」；
 * 「真的被 UI 引用」由 Task 4 的设备截图证据把关（R.drawable 引用无法脱离 Compose 编译单元断言）。
 */
class AssetCatalogTest {

    private val expected = listOf(
        "illus_library_empty",
        "illus_day_empty",
        "illus_novel_empty",
        "illus_media",
    )

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            if (File(dir, "android/app/src/main/res").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到仓库根（从 ${System.getProperty("user.dir")} 上溯）")
    }

    private fun res(): File = File(repoRoot(), "android/app/src/main/res")

    private fun webp(name: String): File =
        File(res(), "drawable-nodpi/$name.webp")

    @Test
    fun fourIllustrationsExistAndAreWebp() {
        expected.forEach { name ->
            val f = webp(name)
            assertTrue("缺少资源: $f", f.isFile)
            val head = f.inputStream().use { it.readNBytes(12) }
            assertEquals("RIFF", String(head, 0, 4, Charsets.US_ASCII))
            assertEquals("WEBP", String(head, 8, 4, Charsets.US_ASCII))
        }
    }

    @Test
    fun eachIllustrationFitsSingleFileBudget() {
        expected.forEach { name ->
            val size = webp(name).length()
            assertTrue("$name 体积 $size B 超过 120,000 B", size <= 120_000L)
        }
    }

    @Test
    fun totalIllustrationBudgetIs400kb() {
        val total = expected.sumOf { webp(it).length() }
        assertTrue("四张合计 $total B 超过 400,000 B", total <= 400_000L)
    }

    @Test
    fun drawableNodpiWebpSetEqualsCatalog() {
        val files = File(res(), "drawable-nodpi")
            .listFiles { f -> f.name.endsWith(".webp") }
            ?.map { it.name.removeSuffix(".webp") }
            ?.sorted()
            .orEmpty()
        assertEquals("drawable-nodpi 的 WebP 集合与清单不一致（防孤儿/漏提交）", expected.sorted(), files)
    }

    @Test
    fun launcherForegroundIs108dpVector() {
        val f = File(res(), "drawable/ic_launcher_foreground.xml")
        assertTrue("缺少图标前景: $f", f.isFile)
        val text = f.readText()
        assertTrue("前景必须是 108dp 画布", text.contains("android:viewportWidth=\"108\""))
        assertTrue("前景必须是透明底（不得有整块填充背景）", !text.contains("android:width=\"108dp\"\n    android:height=\"108dp\"\n    >"))
        assertTrue("前景必须使用 Ember 主色", text.contains("#C8873F"))
    }
}
