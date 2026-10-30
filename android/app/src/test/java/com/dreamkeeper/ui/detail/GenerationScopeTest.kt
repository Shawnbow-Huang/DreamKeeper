package com.dreamkeeper.ui.detail

import com.dreamkeeper.llm.GenerationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T6b：成本行与跨页进度表的纯逻辑断言。
 *
 * 不拉 Room / Robolectric：这两处是「文案口径」与「状态表语义」，
 * 端到端（请求→落库→重进详情仍 Running）已由设备证据覆盖。
 */
class GenerationScopeTest {

    @Test
    fun zeroCostHidesTheCostLine() {
        assertNull(costLabel(0L))
    }

    @Test
    fun microYuanIsShownWithFourDecimals() {
        assertEquals("累计 ¥0.0032", costLabel(3_200L))
        assertEquals("累计 ¥0.1235", costLabel(123_456L))
    }

    @Test
    fun runningPhaseSurvivesOutsideTheViewModel() {
        val tracker = GenerationTracker()
        assertEquals(GenerationPhase.Idle, tracker.phaseFor("d1"))
        tracker.running("d1", GenerationKind.NOVEL, 2)
        assertEquals(GenerationPhase.Running(GenerationKind.NOVEL, 2), tracker.phaseFor("d1"))
        // 另一个梦不受影响
        assertEquals(GenerationPhase.Idle, tracker.phaseFor("d2"))
    }

    @Test
    fun clearingAndFailingAreScopedPerDream() {
        val tracker = GenerationTracker()
        tracker.running("d1", GenerationKind.FAITHFUL)
        tracker.failed("d1", GenerationKind.FAITHFUL, "没联网")
        assertEquals(GenerationPhase.Failed(GenerationKind.FAITHFUL, "没联网"), tracker.phaseFor("d1"))
        tracker.clear("d1")
        assertEquals(GenerationPhase.Idle, tracker.phaseFor("d1"))
        tracker.running("d2", GenerationKind.NOVEL)
        tracker.clear("d1")
        assertEquals(GenerationPhase.Running(GenerationKind.NOVEL, 1), tracker.phaseFor("d2"))
    }
}
