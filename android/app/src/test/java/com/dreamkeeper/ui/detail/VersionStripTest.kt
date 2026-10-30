package com.dreamkeeper.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 9 §0.6 / D355：版本线文案是纯函数，不依赖 Compose 也不依赖 Room。
 */
class VersionStripTest {

    @Test
    fun `versionLabel 拼出版本号、摘要与改动处数`() {
        assertEquals("v2 · 改成第一人称 · 改动 3 处", versionLabel(2, "改成第一人称", 3))
    }

    @Test
    fun `versionLabel 无说明时不带改动数且选中态按版本号判定`() {
        assertEquals("v4 · 无说明", versionLabel(4, "  ", 0))
        assertEquals("v9 · 无说明", versionLabel(9, null, 5))
        assertTrue(isSelectedVersion(4, 4))
        assertFalse(isSelectedVersion(4, null))
        assertFalse(isSelectedVersion(4, 3))
    }
}
