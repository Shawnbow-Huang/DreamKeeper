package com.dreamkeeper.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.dreamkeeper.data.settings.ThemeController
import com.dreamkeeper.data.settings.ThemePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 皮肤的可读性门槛与持久化。
 *
 * 门槛不是"好看"的主观判断，而是可计算的下限：
 * - 正文（paper on night）≥ 7:1（AAA 正文）
 * - 次要文字（inkDim on night）≥ 4.5:1
 * - 强调色（ember on night）≥ 3:1（非文本图形/大字）
 * 任何一套皮肤跌破任一条，就是"为了好看牺牲可读性"，测试直接红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkinTest {

    private fun ratio(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return (maxOf(la, lb) / minOf(la, lb))
    }

    @Test
    fun everySkinKeepsBodyTextReadable() {
        Skin.entries.forEach { skin ->
            val p = paletteOf(skin)
            assertTrue(
                "${skin.id}: paper/night = ${ratio(p.paper, p.night)}",
                ratio(p.paper, p.night) >= 7.0,
            )
        }
    }

    @Test
    fun everySkinKeepsSecondaryTextReadable() {
        Skin.entries.forEach { skin ->
            val p = paletteOf(skin)
            assertTrue(
                "${skin.id}: inkDim/night = ${ratio(p.inkDim, p.night)}",
                ratio(p.inkDim, p.night) >= 4.5,
            )
        }
    }

    @Test
    fun everySkinKeepsAccentVisible() {
        Skin.entries.forEach { skin ->
            val p = paletteOf(skin)
            assertTrue(
                "${skin.id}: ember/night = ${ratio(p.ember, p.night)}",
                ratio(p.ember, p.night) >= 3.0,
            )
        }
    }

    @Test
    fun skinsAreActuallyDifferent() {
        assertNotEquals(paletteOf(Skin.LAMP).night, paletteOf(Skin.MORNING).night)
        assertTrue("晨光必须是浅色皮肤", paletteOf(Skin.MORNING).isDark.not())
        assertTrue(paletteOf(Skin.LAMP).isDark)
        assertTrue(paletteOf(Skin.INDIGO).isDark)
    }

    @Test
    fun systemSkinFollowsSystemDark() {
        // 「跟随系统」不引入第四套色板：白天用晨光纸、夜里用灯下纸。
        assertTrue(paletteOf(Skin.SYSTEM, systemDark = true).isDark)
        assertTrue(paletteOf(Skin.SYSTEM, systemDark = false).isDark.not())
    }

    @Test
    fun unknownIdFallsBackToLamp() {
        assertEquals(Skin.LAMP, Skin.of("does-not-exist"))
        assertEquals(Skin.LAMP, Skin.of(null))
        assertEquals(Skin.MORNING, Skin.of("morning"))
    }
}

/** 皮肤选择的持久化 + 控制器广播。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeControllerTest {

    private fun prefs() = ThemePrefs(
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0),
    )

    @Test
    fun defaultsToLampThenPersistsSelection() {
        val controller = ThemeController(prefs())
        assertEquals(Skin.LAMP, controller.skin.value)

        controller.set(Skin.MORNING)
        assertEquals(Skin.MORNING, controller.skin.value)
        // 新控制器（模拟进程重启）读回同一套皮肤
        assertEquals(Skin.MORNING, ThemeController(prefs()).skin.value)
    }

    @Test
    fun garbageValueInPrefsFallsBackToLamp() {
        val p = prefs()
        p.setSkin(Skin.INDIGO)
        // 别人写坏的 id：不应该崩，也不应该套用不存在的皮肤
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("settings", 0)
            .edit().putString(ThemePrefs.KEY_SKIN, "neon").commit()
        assertEquals(Skin.LAMP, p.skin())
    }
}
