package com.dreamkeeper.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长录音守卫的纯函数契约（Plan 5 T5 / A4）。
 *
 * 4h = 460 MB 是 WavMath 32 B/ms 的直接换算，写成字面量防止「用被测常量给自己出题」。
 */
class RecordingLimitsTest {

    @Test
    fun `canStart needs at least 128 MB free`() {
        assertFalse(RecordingLimits.canStart(0L))
        assertFalse(RecordingLimits.canStart(RecordingLimits.MIN_FREE_BYTES - 1))
        assertTrue(RecordingLimits.canStart(RecordingLimits.MIN_FREE_BYTES))
    }

    @Test
    fun `min free bytes is 128 MB`() {
        assertTrue(RecordingLimits.MIN_FREE_BYTES == 128L * 1024 * 1024)
    }

    @Test
    fun `shouldAutoStop only at the 4 hour hard cap`() {
        assertFalse(RecordingLimits.shouldAutoStop(0L))
        assertFalse(RecordingLimits.shouldAutoStop(3L * 60 * 60 * 1000))
        assertFalse(RecordingLimits.shouldAutoStop(RecordingLimits.MAX_DURATION_MS - 1))
        assertTrue(RecordingLimits.shouldAutoStop(RecordingLimits.MAX_DURATION_MS))
    }

    @Test
    fun `max duration is four hours`() {
        assertTrue(RecordingLimits.MAX_DURATION_MS == 4L * 60 * 60 * 1000)
    }
}
