package com.dreamkeeper.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dreamkeeper.ui.theme.DreamColors

/**
 * 设置三个页共用的小零件：返回顶栏、分组小标题。
 *
 * 为什么抽出来：首页（设置）、二级（模型服务）、三级（单家详情）三处顶栏必须长得一模一样，
 * 复制三份的话，以后改一个字号就会三处不同。
 */
@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit) {
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
            Text(text = "‹", style = MaterialTheme.typography.titleLarge, color = DreamColors.paper)
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = DreamColors.paper,
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = DreamColors.inkDim,
    )
}

/** 右向箭头：列表行「可以点进去」的唯一提示，不额外放按钮。 */
@Composable
fun RowChevron() {
    Text(text = "›", style = MaterialTheme.typography.titleLarge, color = DreamColors.inkFaint)
}

/** 状态点：使用中=ember，已配置=paper，未配置=inkFaint。 */
@Composable
fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}
