package com.dreamkeeper.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamkeeper.BuildConfig
import com.dreamkeeper.ui.theme.DreamColors
import com.dreamkeeper.ui.theme.Skin
import com.dreamkeeper.ui.theme.paletteOf

/**
 * 设置首页（2026-11-01 改版）：只当索引，不铺配置。
 *
 * 四块：模型服务（一行摘要）→ 外观 → 语音识别 → 关于。
 * 供应商列表与 Key/模型/测试都挪到二级页；这里不再出现任何 Key 输入框——
 * 「设置」是导航页，配置面板应该待在自己的页面里。
 */
@Composable
fun SettingsScreen(
    vm: ProviderListViewModel,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(DreamColors.night)) {
        SettingsTopBar(title = "设置", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionLabel("模型服务")
            CommonCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable { onOpenProviders() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.activeProvider.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = DreamColors.paper,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = buildString {
                                append(state.modelLabel.ifEmpty { "未选择模型" })
                                append(" · ")
                                append(
                                    if (state.rows.firstOrNull { it.active }?.configured == true) "已配置"
                                    else "未配置 Key",
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = DreamColors.inkFaint,
                        )
                    }
                    RowChevron()
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            SectionLabel("外观")
            SkinCard(selected = state.skin, onSelect = vm::setSkin)

            Spacer(modifier = Modifier.height(28.dp))

            SectionLabel("语音识别")
            CommonCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "内置 SenseVoice Small",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DreamColors.paper,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "离线转写随包安装，不联网、不依赖系统语音包；识别全在本机完成。",
                        style = MaterialTheme.typography.labelSmall,
                        color = DreamColors.inkFaint,
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            SectionLabel("关于")
            CommonCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "梦匣 ${BuildConfig.VERSION_NAME} · 本地运行 · 无账号",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DreamColors.paper,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "所有记录只在本机；除你配置的模型服务外，不上传任何内容。",
                        style = MaterialTheme.typography.labelSmall,
                        color = DreamColors.inkFaint,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 纸面卡：面差 + 一根几乎看不见的发丝线（浅色皮肤下纸/桌 ΔL 很小）。 */
@Composable
fun CommonCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DreamColors.paperSurface, RoundedCornerShape(14.dp))
            .border(1.dp, DreamColors.inkFaint.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
    ) {
        content()
    }
}

/**
 * 外观卡：四套皮肤，每套给三枚色点（桌 / 纸 / 灯）让用户在点之前先看见。
 *
 * 不摆「浅色/深色」开关：皮肤是「场景」不是亮度二值；「跟随系统」是第四种场景。
 */
@Composable
private fun SkinCard(selected: Skin, onSelect: (Skin) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DreamColors.paperSurface, RoundedCornerShape(14.dp))
            .border(1.dp, DreamColors.inkFaint.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Skin.entries.forEach { skin ->
            val palette = paletteOf(skin)
            val active = skin == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) DreamColors.paperLit else Color.Transparent)
                    .clickable { onSelect(skin) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.width(54.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(palette.night, palette.paperSurface, palette.ember).forEach { c ->
                        Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(c))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = skin.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (active) DreamColors.paper else DreamColors.inkDim,
                    )
                    Text(
                        text = skin.blurb,
                        style = MaterialTheme.typography.labelSmall,
                        color = DreamColors.inkFaint,
                    )
                }
                if (active) {
                    Text("✓", style = MaterialTheme.typography.titleMedium, color = DreamColors.ember)
                }
            }
        }
    }
}
