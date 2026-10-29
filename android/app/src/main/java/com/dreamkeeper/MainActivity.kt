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
        // Activity 只做一件事：把根界面接进主题。Plan 1 不解析 intent extra（Plan 4 的活）
        setContent {
            DreamKeeperTheme {
                DreamKeeperRoot((application as DreamKeeperApp).container.repository)
            }
        }
    }
}
