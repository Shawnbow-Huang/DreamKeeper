package com.dreamkeeper.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `DreamCard` 的文案分派（D63）：任务 6/7 的截图里，没有标题的梦把同一句话画了两遍
 * （粗体标题一次、灰色正文一次，「一直在下楼梯，每一层都一样。」上下两行一模一样）。
 *
 * 这里不测 Compose，只测「哪段文字进哪一行」这个纯函数——
 * 界面长什么样靠截图，文案怎么分配到哪一行是可以断言的东西。
 */
class DreamCardTextTest {

    @Test
    fun `no title and a single sentence shows it once, without a duplicated body`() {
        val t = cardText(null, "一直在下楼梯，每一层都一样。")
        assertEquals("一直在下楼梯，每一层都一样。", t.heading)
        assertEquals("", t.body)
    }

    @Test
    fun `no title and several sentences uses the first sentence as heading and the rest as body`() {
        val t = cardText(null, "我在电梯里，电梯一直往上，按不到一楼。后来门开了，外面不是楼道。")
        assertEquals("我在电梯里，电梯一直往上，按不到一楼。", t.heading)
        assertEquals("后来门开了，外面不是楼道。", t.body)
    }

    @Test
    fun `a real title keeps the first sentence as body`() {
        val t = cardText("老房子", "老房子，走廊很长，尽头有光。")
        assertEquals("老房子", t.heading)
        assertEquals("老房子，走廊很长，尽头有光。", t.body)
    }

    @Test
    fun `a title with no text still renders the title only`() {
        val t = cardText("只有标题", null)
        assertEquals("只有标题", t.heading)
        assertEquals("", t.body)
    }

    @Test
    fun `blank title counts as no title`() {
        val t = cardText("   ", "教室最后一排，黑板上的字在动，像水。")
        assertEquals("教室最后一排，黑板上的字在动，像水。", t.heading)
        assertEquals("", t.body)
    }

    @Test
    fun `an ellipsis-truncated heading does not repeat its tail as body`() {
        val long = "字".repeat(60)
        val t = cardText(null, long)
        assertEquals(firstSentence(long), t.heading)
        assertEquals("", t.body)
    }

    @Test
    fun `empty text renders nothing at all`() {
        val t = cardText(null, "  ")
        assertEquals("", t.heading)
        assertEquals("", t.body)
    }
}
