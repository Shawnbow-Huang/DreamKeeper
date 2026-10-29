package com.dreamkeeper

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dreamkeeper.ui.theme.DreamKeeperTheme
import com.dreamkeeper.ui.theme.Night
import com.dreamkeeper.ui.theme.Paper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 37 强制 edge-to-edge：themes.xml 里的 statusBarColor/navigationBarColor 是空操作，
        // 系统栏必须在这里显式声明深色 + 透明，再由下面的 Compose 背景铺满系统栏区域。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { Placeholder() }
    }
}

@Composable
private fun Placeholder() {
    DreamKeeperTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Night),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "梦匣",
                style = MaterialTheme.typography.displaySmall,
                color = Paper,
            )
        }
    }
}
