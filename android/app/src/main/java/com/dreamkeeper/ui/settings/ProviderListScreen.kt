package com.dreamkeeper.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamkeeper.llm.ProviderId
import com.dreamkeeper.ui.theme.DreamColors

/**
 * 「模型服务」列表（设置 → 模型服务）。
 *
 * 只回答三个问题：**有哪些家、哪家在用、这家配没配好**。
 * 具体怎么填 Key、选哪个模型，点进去再说——索引页不做配置。
 *
 * 空 Key 的家也可以设为当前（用户可以先选家再找 Key），列表里用「未配置」直接说清楚后果，
 * 不做静默降级，也不在这里阻止导航。
 */
@Composable
fun ProviderListScreen(
    vm: ProviderListViewModel,
    onBack: () -> Unit,
    onOpenProvider: (ProviderId) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(DreamColors.night)) {
        SettingsTopBar(title = "模型服务", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            CommonCard {
                Column {
                    state.rows.forEachIndexed { index, row ->
                        ProviderRow(
                            row = row,
                            onClick = { onOpenProvider(row.provider) },
                        )
                        if (index != state.rows.lastIndex) {
                            Text(
                                text = "",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .height(1.dp)
                                    .background(DreamColors.inkFaint.copy(alpha = 0.12f)),
                            )
                        }
                    }
                }
            }

            Text(
                text = "每家一个独立 Key，互不串用；当前只有一家参与生成。「测试连接」在单家页面里。",
                modifier = Modifier.padding(start = 4.dp, top = 10.dp, end = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = DreamColors.inkFaint,
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ProviderRow(row: ProviderListViewModel.Row, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = DreamColors.paper,
                )
                if (row.active) {
                    Text(
                        text = "  使用中",
                        style = MaterialTheme.typography.labelSmall,
                        color = DreamColors.ember,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(row.modelLabel)
                    append(" · ")
                    append(
                        when {
                            row.active && !row.configured -> "未配置 Key，生成会失败"
                            row.configured -> "已配置"
                            else -> "未配置"
                        },
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = DreamColors.inkFaint,
            )
        }
        StatusDot(
            color = when {
                row.active -> DreamColors.ember
                row.configured -> DreamColors.paper
                else -> DreamColors.inkFaint.copy(alpha = 0.5f)
            },
        )
        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
        RowChevron()
    }
}
