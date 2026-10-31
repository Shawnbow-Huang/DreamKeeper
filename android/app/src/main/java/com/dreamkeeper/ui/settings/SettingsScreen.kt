package com.dreamkeeper.ui.settings

import com.dreamkeeper.ui.theme.DreamColors
import androidx.compose.foundation.background
import com.dreamkeeper.ui.theme.paletteOf
import com.dreamkeeper.ui.theme.Skin
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamkeeper.BuildConfig
import com.dreamkeeper.llm.ModelSpec
import com.dreamkeeper.llm.ProviderId
import com.dreamkeeper.llm.ThinkingLevel

/**
 * 设置页（Plan 3 Task 2，Plan 6 Task 4 扩到五家供应商）。
 *
 * 结构：**顶部栏固定 + 正文独立滚动**。Plan 5 加「关于」卡后 1.3× 字号已到屏幕底，
 * 再来供应商 chips 与测试结果必然被裁（Plan 6 D518）。
 *
 * Key 区三种状态：**未配置** / **已配置**（显示 `sk-…abcd` 掩码） / **编辑中**。
 * 测试连接只测已保存的 Key；成功后显示毫秒，失败显示人话（不显示 URL）。
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(DreamColors.night)) {
        SettingsTopBar(onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            SectionLabel("模型供应商")
            ProviderRow(
                selected = state.provider,
                onSelect = vm::selectProvider,
            )

            Spacer(modifier = Modifier.height(12.dp))

            SectionLabel("${state.provider.displayName} Key")
            KeyCard(state = state, vm = vm)
            Text(
                text = "Key 只保存在本机安全存储里，不会写进日志或备份。",
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = DreamColors.inkFaint,
            )

            Spacer(modifier = Modifier.height(28.dp))

            SectionLabel("生成")
            GenerationCard(
                models = state.models,
                model = state.model,
                novelThinking = state.novelThinking,
                onModel = vm::setModel,
                onThinking = vm::setNovelThinking,
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Plan 7：外观。立即生效、只写 SharedPreferences，不动业务状态。
            SectionLabel("外观")
            SkinCard(selected = state.skin, onSelect = vm::setSkin)

            Spacer(modifier = Modifier.height(28.dp))

            SectionLabel("关于")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DreamColors.nightRaised, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
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

            Spacer(modifier = Modifier.height(24.dp))
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
            Text(text = "‹", style = MaterialTheme.typography.titleLarge, color = DreamColors.paper)
        }
        Text(
            text = "设置",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = DreamColors.paper,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = DreamColors.inkDim,
    )
}

/** 五家供应商：48dp 胶囊，选中 DreamColors.ember 边；点过 Key 的家带一个 DreamColors.ember 小点。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderRow(
    selected: ProviderId,
    onSelect: (ProviderId) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProviderId.entries.forEach { provider ->
            val active = provider == selected
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DreamColors.night)
                    .border(1.dp, if (active) DreamColors.ember else DreamColors.line, RoundedCornerShape(20.dp))
                    .clickable { onSelect(provider) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) DreamColors.paper else DreamColors.inkDim,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun KeyCard(state: SettingsViewModel.UiState, vm: SettingsViewModel) {
    val provider = state.provider
    val statusText = when {
        state.draft.isNotEmpty() -> "编辑中"
        state.testing -> "测试中…"
        state.connection?.ok == true -> "连接正常"
        state.keyConfigured -> "已保存"
        else -> "还没配置"
    }
    val statusColor = when {
        state.connection?.ok == true -> DreamColors.paper
        state.keyConfigured || state.draft.isNotEmpty() -> DreamColors.ember
        else -> DreamColors.inkDim
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DreamColors.nightRaised)
            .background(DreamColors.paperSurface, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${provider.displayName} API Key", style = MaterialTheme.typography.bodyMedium, color = DreamColors.paper)
            Spacer(modifier = Modifier.weight(1f))
            Text(text = statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
        }

        if (state.keyConfigured && state.draft.isEmpty() && state.maskedKey.isNotEmpty()) {
            Text(
                text = state.maskedKey,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = DreamColors.inkDim,
            )
        }

        OutlinedTextField(
            value = state.draft,
            onValueChange = vm::onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .heightIn(min = 48.dp),
            placeholder = {
                Text("粘贴 ${provider.displayName} 的 API Key", style = MaterialTheme.typography.labelSmall, color = DreamColors.inkFaint)
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        state.warning?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = DreamColors.ember,
            )
        }
        state.error?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = DreamColors.errorText,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = vm::save,
                enabled = !state.busy,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DreamColors.emberDeep,
                    contentColor = DreamColors.paper,
                    disabledContainerColor = DreamColors.line,
                    disabledContentColor = DreamColors.inkFaint,
                ),
            ) {
                Text("保存", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = vm::testConnection,
                enabled = !state.busy && !state.testing && state.keyConfigured,
                modifier = Modifier.padding(start = 8.dp).height(48.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DreamColors.paper, disabledContentColor = DreamColors.inkFaint),
            ) {
                Text(if (state.testing) "测试中…" else "测试连接", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = vm::clear,
                enabled = !state.busy && state.keyConfigured,
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = DreamColors.inkDim,
                    disabledContentColor = DreamColors.inkFaint,
                ),
            ) {
                Text("清除", style = MaterialTheme.typography.labelLarge)
            }
        }

        state.connection?.let { conn ->
            val text = if (conn.ok) {
                "连上了 · ${conn.latencyMs ?: 0} ms · ${conn.model ?: ""}"
            } else {
                "没连上：${conn.reasonText ?: "未知原因"}"
            }
            Text(
                text = text,
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (conn.ok) DreamColors.inkDim else DreamColors.errorText,
            )
        }
    }
}

/**
 * 模型与思考档位（Plan 5 Task 2；Plan 6 按供应商给模型）。
 *
 * 为什么只暴露「小说化的思考档位」：忠实整理恒关（逐字保守、更快更便宜），
 * 改稿的 low + 固定温度链属于另一条契约（D471），不在这张卡里给用户假开关。
 */
@Composable
private fun GenerationCard(
    models: List<ModelSpec>,
    model: String,
    novelThinking: ThinkingLevel,
    onModel: (String) -> Unit,
    onThinking: (ThinkingLevel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DreamColors.nightRaised)
            .background(DreamColors.paperSurface, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text("小说化模型", style = MaterialTheme.typography.bodyMedium, color = DreamColors.paper)
        Spacer(modifier = Modifier.height(8.dp))
        ChoiceRow(
            options = models.map { it.id },
            selected = model,
            label = { id -> models.firstOrNull { it.id == id }?.label ?: id },
            onSelect = onModel,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("小说化思考", style = MaterialTheme.typography.bodyMedium, color = DreamColors.paper)
        Spacer(modifier = Modifier.height(8.dp))
        ChoiceRow(
            options = ThinkingLevel.entries,
            selected = novelThinking,
            label = { it.label },
            onSelect = onThinking,
        )

        Text(
            text = "思考开着时，服务端会忽略 temperature；忠实整理固定关闭思考。",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = DreamColors.inkFaint,
        )
    }
}

/**
 * 外观卡：三套皮肤，每套给三枚色点（桌 / 纸 / 灯）让用户在点之前先看见。
 *
 * 不摆「浅色/深色」开关：皮肤是「场景」不是亮度二值，三套并列更接近选纸。
 */
@Composable
private fun SkinCard(selected: Skin, onSelect: (Skin) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DreamColors.paperSurface, RoundedCornerShape(14.dp))
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
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(c),
                        )
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

/** 单行胶囊选择器：选中 DreamColors.ember 边 + DreamColors.paper 字，未选 DreamColors.line 边 + DreamColors.inkDim 字。大字号下自动换行，不把文案挤断。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { item ->
            val active = item == selected
            Text(
                text = label(item),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DreamColors.night)
                    .border(1.dp, if (active) DreamColors.ember else DreamColors.line, RoundedCornerShape(20.dp))
                    .clickable { onSelect(item) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (active) DreamColors.paper else DreamColors.inkDim,
                maxLines = 1,
            )
        }
    }
}
