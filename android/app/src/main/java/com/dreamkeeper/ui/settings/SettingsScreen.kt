package com.dreamkeeper.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import com.dreamkeeper.llm.GenerationOptions
import com.dreamkeeper.llm.ThinkingLevel
import com.dreamkeeper.BuildConfig
import com.dreamkeeper.ui.theme.Ember
import com.dreamkeeper.ui.theme.EmberDeep
import com.dreamkeeper.ui.theme.ErrorText
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.InkFaint
import com.dreamkeeper.ui.theme.Line
import com.dreamkeeper.ui.theme.Night
import com.dreamkeeper.ui.theme.NightRaised
import com.dreamkeeper.ui.theme.Paper

/**
 * 设置页（Plan 3 Task 2）。
 *
 * Key 区三种状态：**未配置** / **已配置**（显示 `sk-…abcd` 掩码，不显示明文）/
 * **编辑中**（输入框里有草稿）。输入框始终可见——已配置时粘贴新 Key 直接覆盖，
 * 不必先点「更换」。
 *
 * 顶栏沿用标签页的写法：48dp 圆形返回区 + 文字标题，不引 material-icons
 * （仓库没有该依赖，`Icons.*` 编译不过，D225）。
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(Night)) {
        SettingsTopBar(onBack = onBack)

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SectionLabel("DeepSeek Key")
            KeyCard(
                state = state,
                onDraftChange = vm::onDraftChange,
                onSave = vm::save,
                onClear = vm::clear,
            )
            Text(
                text = "Key 只保存在本机安全存储里，不会写进日志或备份。",
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
            )

            Spacer(modifier = Modifier.height(28.dp))

            SectionLabel("生成")
            GenerationCard(
                model = state.model,
                novelThinking = state.novelThinking,
                onModel = vm::setModel,
                onThinking = vm::setNovelThinking,
            )

            Spacer(modifier = Modifier.height(28.dp))

            SectionLabel("关于")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NightRaised, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Text(
                    text = "梦匣 ${BuildConfig.VERSION_NAME} · 本地运行 · 无账号",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Paper,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "所有记录只在本机；除你配置的模型服务外，不上传任何内容。",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint,
                )
            }

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
private fun KeyCard(
    state: SettingsViewModel.UiState,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    // 三态判定：有草稿就是「编辑中」；否则看本机到底有没有 Key
    val statusText = when {
        state.draft.isNotEmpty() -> "编辑中"
        state.keyConfigured -> "已保存（还没验证）"
        else -> "还没配置"
    }
    val statusColor = if (state.keyConfigured || state.draft.isNotEmpty()) Ember else InkDim

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NightRaised)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("API Key", style = MaterialTheme.typography.bodyMedium, color = Paper)
            Spacer(modifier = Modifier.weight(1f))
            Text(text = statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
        }

        // 已配置且不在编辑时，唯一露出的就是掩码；明文永不进 state
        if (state.keyConfigured && state.draft.isEmpty() && state.maskedKey.isNotEmpty()) {
            Text(
                text = state.maskedKey,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
            )
        }

        OutlinedTextField(
            value = state.draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                // 48dp 是硬性触控下限；M3 输入框自身最小 56dp，heightIn 只保证不低于 48
                .heightIn(min = 48.dp),
            placeholder = {
                Text("粘贴 API Key", style = MaterialTheme.typography.labelSmall, color = InkFaint)
            },
            // 明文绝不显示在屏幕上：截图/肩窥都只能看到圆点
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
                color = Ember,
            )
        }
        state.error?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = ErrorText,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onSave,
                enabled = !state.busy,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmberDeep,
                    contentColor = Paper,
                    disabledContainerColor = Line,
                    disabledContentColor = InkFaint,
                ),
            ) {
                Text("保存", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(
                onClick = onClear,
                enabled = !state.busy && state.keyConfigured,
                modifier = Modifier.padding(start = 8.dp).height(48.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = InkDim,
                    disabledContentColor = InkFaint,
                ),
            ) {
                Text("清除", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.weight(1f))
            if (state.busy) {
                Text("处理中…", style = MaterialTheme.typography.labelSmall, color = InkDim)
            }
        }
    }
}

/**
 * 模型与思考档位（Plan 5 Task 2）。
 *
 * 为什么只暴露「小说化的思考档位」：忠实整理恒关（逐字保守、更快更便宜），
 * 改稿的 low + 固定温度链属于另一条契约（D471），不在这张卡里给用户假开关。
 */
@Composable
private fun GenerationCard(
    model: String,
    novelThinking: ThinkingLevel,
    onModel: (String) -> Unit,
    onThinking: (ThinkingLevel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NightRaised)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text("小说化模型", style = MaterialTheme.typography.bodyMedium, color = Paper)
        Spacer(modifier = Modifier.height(8.dp))
        ChoiceRow(
            options = GenerationOptions.MODELS.mapIndexed { index, id -> index to id },
            selected = GenerationOptions.MODELS.indexOf(model),
            label = { GenerationOptions.modelLabel(it) },
            onSelect = { index -> GenerationOptions.MODELS.getOrNull(index)?.let(onModel) },
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("小说化思考", style = MaterialTheme.typography.bodyMedium, color = Paper)
        Spacer(modifier = Modifier.height(8.dp))
        ChoiceRow(
            options = ThinkingLevel.entries.mapIndexed { index, level -> index to level },
            selected = ThinkingLevel.entries.indexOf(novelThinking),
            label = { it.label },
            onSelect = { index -> ThinkingLevel.entries.getOrNull(index)?.let(onThinking) },
        )

        Text(
            text = "思考开着时，服务端会忽略 temperature；忠实整理固定关闭思考。",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = InkFaint,
        )
    }
}

/** 单行胶囊选择器：选中 Ember 边 + Paper 字，未选 Line 边 + InkDim 字。大字号下自动换行，不把文案挤断。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
    options: List<Pair<Int, T>>,
    selected: Int,
    label: (T) -> String,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, item) ->
            val active = value == selected
            Text(
                text = label(item),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (active) Night else Night)
                    .border(1.dp, if (active) Ember else Line, RoundedCornerShape(20.dp))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (active) Paper else InkDim,
                maxLines = 1,
            )
        }
    }
}
