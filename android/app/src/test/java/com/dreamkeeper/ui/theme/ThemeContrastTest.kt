package com.dreamkeeper.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.0 对比度实算。Compose 的 `Color.luminance()` 就是 WCAG 相对亮度公式的实现，
 * 所以这里不再手写 0.2126/0.7152/0.0722——公式只该有一份，且不该抄错。
 *
 * 目的：把「这个灰太暗了」变成一条会红的断言。失败信息里带实测值，直接照着调色。
 */
class ThemeContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun assertContrast(name: String, fg: Color, bg: Color, min: Double) {
        val actual = contrast(fg, bg)
        assertTrue(
            "$name 实测 %.2f:1，要求 ≥ %.1f:1".format(actual, min),
            actual >= min,
        )
    }

    @Test fun `ink faint is readable on night`() =
        assertContrast("InkFaint on Night", InkFaint, Night, 4.5)

    @Test fun `error text is readable on night`() =
        assertContrast("ErrorText on Night", ErrorText, Night, 4.5)

    @Test fun `paper is readable on night`() =
        assertContrast("Paper on Night", Paper, Night, 4.5)

    @Test fun `ember ring is visible on night`() =
        assertContrast("Ember on Night", Ember, Night, 3.0)
}
