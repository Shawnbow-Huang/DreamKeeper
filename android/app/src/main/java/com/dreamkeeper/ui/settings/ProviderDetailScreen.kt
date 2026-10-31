package com.dreamkeeper.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamkeeper.llm.ModelSpec
import com.dreamkeeper.llm.ProviderId
import com.dreamkeeper.llm.ThinkingLevel
import com.dreamkeeper.ui.theme.DreamColors

/**
 * 单家供应商详情（设置 → 模型服务 → 某一家）。
 *
 * 一家的所有事都在这一页：Key（保存 / 测试 / 清除）、模型、思考档位、设为当前。
 * 返回列表时由列表页重新读一次状态，不在页面之间传可变状态。
 *
 * Key 区三种状态：**未配置** / **已配置**（`sk-…abcd` 掩码） / **编辑中**。
 * 测试连接只测已保存的 Key；成功后显示毫秒，失败显示人话（不显示 URL）。
 */
@Composable
fun ProviderDetailScreen(
    vm: ProviderDetailViewModel,
    activeProvider: ProviderId,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isActive = state.provider == activeProvider

    Column(modifier = Modifier.fillMaxSize().background(DreamColors.night)) {
        SettingsTopBar(title = state.provider.displayName, onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            SectionLabel("API Key")
            KeyCard(state = state, vm = vm)
            Text(
                text = "Key 只保存在本机安全存储里，不会写进日志或备份。",
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = DreamColors.inkFaint,
            )

            Spacer(modifier = Modifier.height(28.dp))

            SectionLabel("模型与思考")
            GenerationCard(
                models = state.models,
                novelModel = state.model,
                faithfulModel = state.faithfulModel,
                novelThinking = state.novelThinking,
                discovering = state.discovering,
                discoveryNote = state.discoveryNote,
                onNovelModel = vm::setModel,
                onFaithfulModel = vm::setFaithfulModel,
                onDiscover = vm::discoverModels,
                onThinking = vm::setNovelThinking,
            )

            if (state.provider != ProviderId.DEEPSEEK) {
                Text(
                    text = "这家暂时没有价卡：生成按「未计价（0 元）」记账，不按 DeepSeek 的价目编数字。",
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = DreamColors.inkFaint,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = vm::makeActive,
                enabled = !isActive,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DreamColors.ember,
                    contentColor = DreamColors.onLamp,
                    disabledContainerColor = DreamColors.paperLit,
                    disabledContentColor = DreamColors.inkFaint,
                ),
            ) {
                Text(
                    text = if (isActive) "使用中" else "设为当前供应商",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (!isActive && !state.keyConfigured) {
                Text(
                    text = "还没配 Key：可以先设为当前，但生成会失败。",
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = DreamColors.inkFaint,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun KeyCard(state: ProviderDetailViewModel.UiState, vm: ProviderDetailViewModel) {
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
            .background(DreamColors.paperSurface, RoundedCornerShape(14.dp))
            .border(1.dp, DreamColors.inkFaint.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
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
            Text(text = it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = DreamColors.ember)
        }
        state.error?.let {
            Text(text = it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = DreamColors.errorText)
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
 * 模型与思考档位（按供应商给模型）。
 *
 * 为什么只暴露「小说化的思考档位」：忠实整理恒关（逐字保守、更快更便宜），
 * 改稿的 low + 固定温度链属于另一条契约（D471），不在这张卡里给用户假开关。
 */
@Composable
private fun GenerationCard(
    models: List<ModelSpec>,
    novelModel: String,
    faithfulModel: String,
    novelThinking: ThinkingLevel,
    discovering: Boolean,
    discoveryNote: String?,
    onNovelModel: (String) -> Unit,
    onFaithfulModel: (String) -> Unit,
    onDiscover: () -> Unit,
    onThinking: (ThinkingLevel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DreamColors.paperSurface, RoundedCornerShape(14.dp))
            .border(1.dp, DreamColors.inkFaint.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "小说化模型",
                style = MaterialTheme.typography.bodyMedium,
                color = DreamColors.paper,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (discovering) "检测中…" else "检测可用模型",
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, DreamColors.line, RoundedCornerShape(20.dp))
                    .clickable(enabled = !discovering) { onDiscover() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (discovering) DreamColors.inkFaint else DreamColors.inkDim,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        ChoiceRow(
            options = models.map { it.id },
            selected = novelModel,
            label = { id -> models.firstOrNull { it.id == id }?.label ?: id },
            onSelect = onNovelModel,
        )
        if (discoveryNote != null) {
            Text(
                text = discoveryNote,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = DreamColors.inkFaint,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("忠实整理模型", style = MaterialTheme.typography.bodyMedium, color = DreamColors.paper)
        Spacer(modifier = Modifier.height(8.dp))
        ChoiceRow(
            options = models.map { it.id },
            selected = faithfulModel,
            label = { id -> models.firstOrNull { it.id == id }?.label ?: id },
            onSelect = onFaithfulModel,
        )
        Text(
            text = "整理只做「补标点、顺语序」，思考固定关闭；可以单独选一个更便宜的模型。",
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = DreamColors.inkFaint,
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

/** 单行胶囊选择器：选中 ember 边 + paper 字，未选 line 边 + inkDim 字；大字号下自动换行。 */
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
