package com.dreamkeeper.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamkeeper.ui.theme.Ember
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.InkFaint
import com.dreamkeeper.ui.theme.Line
import com.dreamkeeper.ui.theme.Night
import com.dreamkeeper.ui.theme.NightRaised
import com.dreamkeeper.ui.theme.Paper

/**
 * 设置页骨架（Plan 3 Task 1）。
 *
 * 这一版只**占位**：Key 的保存/校验要等 Task 2 的 ApiKeyStore，所以 Key 区固定显示
 * 「还没配置」；不放输入框、不放保存按钮，避免出现一个点了没反应的假入口。
 *
 * 顶栏沿用标签页的写法：48dp 圆形返回区 + 文字标题，不引 material-icons
 * （仓库没有该依赖，`Icons.*` 编译不过）。
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(Night)) {
        SettingsTopBar(onBack = onBack)

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SectionLabel("DeepSeek Key")
            KeyCard(keyConfigured = state.keyConfigured)
            Text(
                text = "Key 只保存在本机安全存储里，不会写进日志或备份。保存与校验还没做。",
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
            )

            Spacer(modifier = Modifier.height(28.dp))

            SectionLabel("说明")
            Text(
                text = "思考模式开着时，服务端会忽略 temperature。",
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
            )
        }
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable { onBack() }
                .semantics { contentDescription = "返回" },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "‹", style = MaterialTheme.typography.titleLarge, color = Paper)
        }
        Text(
            text = "设置",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = Paper,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = InkDim,
    )
}

@Composable
private fun KeyCard(keyConfigured: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NightRaised)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text("API Key", style = MaterialTheme.typography.bodyMedium, color = Paper)
        Text(
            text = if (keyConfigured) "已配置" else "还没配置",
            style = MaterialTheme.typography.labelSmall,
            color = if (keyConfigured) Ember else InkDim,
        )
    }
}
