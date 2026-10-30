package com.dreamkeeper.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 7 / dispatch §0.1：四张语言表的映射必须是纯函数，且**只有在线语言命中时
 * 绝不能报 Available**——那是把「要联网」伪装成「本机可用」。
 */
class SystemSttEngineSupportTest {

    @Test
    fun `installed on-device language means available`() {
        assertEquals(
            SttAvailability.Available,
            mapSupport(
                installed = listOf("zh-CN"),
                supported = emptyList(),
                pending = emptyList(),
                online = emptyList(),
            ),
        )
    }

    @Test
    fun `supported but not installed means needs download`() {
        assertEquals(
            SttAvailability.NeedsDownload("zh-CN"),
            mapSupport(
                installed = listOf("en-US"),
                supported = listOf("zh-CN"),
                pending = emptyList(),
                online = listOf("zh-CN"),
            ),
        )
    }

    @Test
    fun `pending language means needs download`() {
        assertEquals(
            SttAvailability.NeedsDownload("zh-CN"),
            mapSupport(
                installed = emptyList(),
                supported = emptyList(),
                pending = listOf("zh-CN"),
                online = emptyList(),
            ),
        )
    }

    @Test
    fun `online only language is unavailable never available`() {
        assertEquals(
            SttAvailability.Unavailable("只有在线识别"),
            mapSupport(
                installed = listOf("en-US"),
                supported = emptyList(),
                pending = emptyList(),
                online = listOf("zh-CN"),
            ),
        )
    }

    @Test
    fun `empty tables are unavailable`() {
        val got = mapSupport(emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(got is SttAvailability.Unavailable)
        assertTrue((got as SttAvailability.Unavailable).reason.isNotBlank())
    }

    @Test
    fun `zh prefix in any installed tag counts as installed`() {
        assertEquals(
            SttAvailability.Available,
            mapSupport(
                installed = listOf("zh-Hans-CN"),
                supported = emptyList(),
                pending = emptyList(),
                online = emptyList(),
            ),
        )
    }
}
