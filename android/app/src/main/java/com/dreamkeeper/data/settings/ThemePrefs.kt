package com.dreamkeeper.data.settings

import android.content.SharedPreferences
import com.dreamkeeper.ui.theme.Skin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 皮肤选择。
 *
 * 和 [GenerationPrefs] 共用 `settings` 这个 SharedPreferences 文件，但键独立
 * （`dk_theme_skin`）——换皮肤不应该牵动生成档位。
 */
class ThemePrefs(private val prefs: SharedPreferences) {

    fun skin(): Skin = Skin.of(prefs.getString(KEY_SKIN, null))

    fun setSkin(skin: Skin) {
        prefs.edit().putString(KEY_SKIN, skin.id).apply()
    }

    companion object {
        const val PREFS_NAME = "settings"
        const val KEY_SKIN = "dk_theme_skin"
    }
}

/**
 * 把「当前皮肤」变成一个可观察的源，给 `setContent` 与设置页共用。
 *
 * 单例放在 AppContainer 里（不进 Room、不进 ViewModel），这样换皮肤时整个 Activity
 * 重组一次，而不用重建任何业务对象。
 */
class ThemeController(private val prefs: ThemePrefs) {

    private val _skin = MutableStateFlow(prefs.skin())
    val skin: StateFlow<Skin> = _skin.asStateFlow()

    fun set(skin: Skin) {
        _skin.value = skin
        prefs.setSkin(skin)
    }
}
