package com.dreamkeeper.llm.gates

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 语义真源是 `scripts/check-novel.py`；这里逐条跑同一份向量，防止两边漂移。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelGatesTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "docs/evidence/plan8").isDirectory && File(dir, "android/app/build.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("找不到仓库根目录")
    }

    private fun cases(): List<JSONObject> {
        val text = File(repoRoot(), "docs/evidence/plan8/novel-gates-vectors.json").readText()
        val arr = JSONObject(text).getJSONArray("cases")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    @Test
    fun `v3 小说向量逐条命中`() {
        var checked = 0
        for (case in cases()) {
            val kind = case.getString("kind")
            if (kind == "n3") {
                val hints = NovelGates.hints(case.optString("text"))
                val expected = case.getJSONArray("expect_hints").let { a -> (0 until a.length()).map { a.getString(it) } }
                assertTrue("${case.getString("name")} hints=$hints", hints.containsAll(expected))
                checked++
                continue
            }
            val report = if (kind == "n1") {
                NovelGates.check(case.getJSONObject("novel").toString())
            } else {
                NovelGates.check("""{"title":"下不完的楼梯","body":${JSONObject.quote(case.getString("text"))},"notes":[{"type":"借用","detail":"x"},{"type":"舍弃","detail":"y"},{"type":"主题","detail":"z"}],"mood":"发怵"}""")
            }
            val expected = case.getJSONArray("expect_failed").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals(case.getString("name"), expected, report.failed)
            checked++
        }
        assertEquals(14, checked)
    }
}
