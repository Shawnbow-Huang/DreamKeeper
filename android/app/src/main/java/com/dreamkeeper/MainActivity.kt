package com.dreamkeeper

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dreamkeeper.ui.DreamKeeperRoot
import com.dreamkeeper.ui.theme.DreamKeeperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 37 强制 edge-to-edge：themes.xml 里的 statusBarColor/navigationBarColor 是空操作，
        // 系统栏必须在这里显式声明深色 + 透明，再由下面的 Compose 背景铺满系统栏区域。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // D67 的 debug 钩子：`adb shell input text` 只映射 ASCII，中文检索没法手打，
        // 只能靠 intent extra 预填。**仅 DEBUG 生效**；这里只传「搜什么」，
        // 结果仍由 SearchViewModel → repo.search 实查，绝不写死任何一条。
        val prefillSearch = if (BuildConfig.DEBUG) {
            intent?.getStringExtra("dk_prefill_search").orEmpty()
        } else {
            ""
        }
        // Activity 只做一件事：把根界面接进主题
        setContent {
            DreamKeeperTheme {
                val container = (application as DreamKeeperApp).container
                DreamKeeperRoot(
                    repo = container.repository,
                    // 排布偏好也由容器给：界面不自己 getSharedPreferences（否则换个 Context 就丢配置）
                    layoutPrefs = container.layoutPrefs,
                    initialSearchQuery = prefillSearch,
                )
            }
        }
    }
}
