package com.dreamkeeper.llm.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.1 闸门行为：F1 事实锚点（含数字归一、新增实体）/ F2 长度异常报警 / F3 口水 /
 * G4 因果连接词计数（允许 1 处解释）/ G5 定性（不得把推测写成确定）。
 *
 * 关键用例是「用户手改的理想原样稿」——它必须全绿，否则闸门与产品目标又打架。
 */
class FaithfulGatesTest {

    private fun failed(input: String, output: String): Set<Gate> =
        FaithfulGates.check(input, output, GateMode.FAITHFUL).failed

    @Test
    fun golden_user_edited_passes_all() {
        val base = read("docs/evidence/plan8/baseline-faithful-2026-11-01.txt")
        val edited = read("docs/evidence/plan8/user-edited-faithful-2026-11-01.txt")
        val report = FaithfulGates.check(base, edited, GateMode.FAITHFUL)
        val detail = report.results.joinToString("; ") { "${it.gate}=${it.passed} ${it.detail}" }
        assertEquals("golden failed=${report.failed} detail=[$detail]", emptySet<Gate>(), report.failed)
    }

    @Test
    fun semantic_fix_by_whole_context_passes() {
        val input = "澳门不知道为什么就不进枪了，所以我标里也别的一把枪。走到一个店门口，店门口说不允许你带。"
        val output = "澳门不知道为什么就不禁枪了，所以我腰里也别着一把枪。走到一个店门口，店门口的人说不允许你带。"
        assertEquals(emptySet<Gate>(), failed(input, output))
    }

    @Test
    fun two_readings_and_collocation_are_left_alone() {
        assertEquals(emptySet<Gate>(), failed("他说开放时间是两点，开饭时间是六点。", "他说开放时间是两点了，开饭时间是六点。"))
        assertEquals(emptySet<Gate>(), failed("他就坐在座上，没动。", "他就坐在座上，没动。"))
    }

    @Test
    fun explanation_overflow_is_water_alert() {
        val input = "我特别激动。"
        val output = "我特别激动，因为这是我第一次坐飞船。那种感觉很新奇的体验。而且我从小就想飞。"
        assertTrue(failed(input, output).contains(Gate.F2))
    }

    @Test
    fun new_person_is_f1() {
        assertTrue(failed("我跟家里人一起出门。", "我跟我妈、舅舅一起出门。").contains(Gate.F1))
    }

    @Test
    fun dropped_fact_is_f1() {
        assertTrue(
            failed("比如4点钟出发、6点钟到，到底在这里吃饭还是到澳门再吃。", "比如4点钟出发，到底在这里吃饭还是到澳门再吃。")
                .contains(Gate.F1),
        )
    }

    @Test
    fun impossible_clock_is_normalized_before_anchor_compare() {
        assertEquals(emptySet<Gate>(), failed("比如4.4点钟出发，6点钟到。", "比如4点钟出发，6点钟到。"))
    }

    @Test
    fun filler_is_f3() {
        assertTrue(failed("呃，我做了个梦。", "呃，我做了个梦。").contains(Gate.F3))
    }

    @Test
    fun hedge_to_assertion_is_g5() {
        assertTrue(failed("画面一转，好像就到了澳门。", "画面一转，就到了澳门。").contains(Gate.G5))
    }

    @Test
    fun merging_duplicate_hedges_is_fine() {
        assertEquals(emptySet<Gate>(), failed("好像是去了澳门，好像又回来了。", "好像去了澳门，又回来了。"))
    }

    @Test
    fun up_to_two_explanations_allowed_but_three_is_not() {
        val input = "我特别激动。海是灰色的，天快黑了。我们在沙滩上走了很久，风很大，我一直没说话，后来就一起回家了。"
        val one = input.replace("我特别激动。", "我特别激动，因为这是我第一次看见海。")
        assertEquals(emptySet<Gate>(), failed(input, one))
        val three = one
            .replace("天快黑了", "所以天快黑了")
            .replace("后来就一起回家了", "于是后来就一起回家了")
        assertTrue(failed(input, three).contains(Gate.G4))
    }

    private fun read(path: String): String =
        File(repoRoot(), path).readText(Charsets.UTF_8).substringAfter("---\n")

    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "docs/evidence").exists()) {
            dir = dir.parentFile
        }
        return dir
    }
}
