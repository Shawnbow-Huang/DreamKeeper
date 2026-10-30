package com.dreamkeeper.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.domain.Tag
import com.dreamkeeper.ui.day.DayViewModel
import com.dreamkeeper.ui.theme.Ember
import com.dreamkeeper.ui.theme.ErrorText
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.InkFaint
import com.dreamkeeper.ui.theme.Line
import com.dreamkeeper.ui.theme.Night
import com.dreamkeeper.ui.theme.NightRaised
import com.dreamkeeper.ui.theme.Paper
import kotlinx.coroutines.launch

/**
 * 小说化 tab 的标题格式（D41 契约）：「小说化」或「小说化 · 补写 6 处」。
 *
 * **这几个字一个字都不许改**：设计 §6.2 对 tab 标题有 `补\s*写\s*(\d+)\s*处` 的正则依赖，
 * Plan 3 靠它决定要不要显示「逐段对照」入口。改成「改写 N 处」会让那个入口静默消失，
 * 而且不会有任何测试变红。
 *
 * Plan 1 的屏上按 brief 0.7 由内容区顶部的 caption 承载 N（四等分每格只有约 94dp，
 * 「小说化 · 补写 2 处」必被截断），但这个格式契约仍然由测试守着，Plan 3 恢复上屏。
 */
fun novelTabLabel(n: Int): String = if (n == 0) "小说化" else "小说化 · 补写 $n 处"

/**
 * 从 `novelChangeLog` 里数「补写 N 处」；数不出来就按 0（老数据 / 没生成过）。
 *
 * 中间允许空白：`补 写 3 处` 也要能抽出来（D41）。只是过渡桥——Plan 3 把该字段换成
 * JSON 数组后由 `changeLog.size` 直接取代，但 [novelTabLabel] 的格式不许动。
 */
fun novelChangeCount(changeLog: String?): Int = changeLog
    ?.let { Regex("补\\s*写\\s*(\\d+)\\s*处").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    ?: 0

/** 四个 tab 的固定文案：不把「补写 N 处」塞进来（brief 0.7 / D66） */
private enum class DetailTab(val label: String) {
    ORIGINAL("原样"),
    NOVEL("小说化"),
    TAGS("标签"),
    MEDIA("素材"),
}

/** 小说化还没有稿子时的占位。等 Plan 2 接上大模型，这里换成正文 */
private const val NOVEL_EMPTY = "还没有生成。等接上大模型后，这里会有一篇读得通的版本。"

/** 波形根数：定值，别按宽度算——换个屏就换一个波形，截图对不上 */
private const val WAVE_BARS = 40

/**
 * 详情页（设计 §11 第三层）：原样 / 小说化 / 标签 / 素材。
 *
 * 播放条在滚动区**之外**常驻（brief 0.9）：正文再长也不会把它顶出屏幕，最后两行也不会被盖住。
 * 素材 tab 是纯 UI 占位，不读 `assets`——Plan 1 的 `DetailUiState.assets` 恒为空表。
 */
@Composable
fun DetailScreen(
    dreamId: String,
    repo: DreamRepository,
    onBack: () -> Unit,
) {
    val vm: DetailViewModel = viewModel { DetailViewModel(repo, dreamId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(Night)) {
        Column(modifier = Modifier.fillMaxSize()) {
            DetailTopBar(
                date = state.dream?.date?.let { DayViewModel.titleOf(it) }.orEmpty(),
                onBack = onBack,
                onAction = { message -> scope.launch { snackbarHost.showSnackbar(message) } },
            )

            when {
                // 还在读库：留白。此时 dream 也是 null，但**不能**画「找不到」——会让人以为梦丢了
                !state.loaded -> Box(modifier = Modifier.weight(1f).fillMaxWidth())

                state.dream == null -> MissingDream(
                    onBack = onBack,
                    modifier = Modifier.weight(1f),
                )

                else -> {
                    DetailTabs(selected = selectedTab, onSelect = { selectedTab = it })

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (selectedTab) {
                            0 -> TranscriptTab(
                                dream = state.dream,
                                onSave = { text -> vm.saveManualTranscript(text) },
                                onMessage = { message -> scope.launch { snackbarHost.showSnackbar(message) } },
                            )

                            1 -> NovelTab(
                                count = state.novelChangeCount,
                                novelText = state.dream?.novelText,
                            )

                            2 -> TagsTab(tags = state.tags)

                            else -> MediaTab()
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    ) {
                        PlaybackBar(
                            audioPath = state.dream?.rawAudioPath,
                            durationMs = state.dream?.rawAudioMs,
                            onAction = { message -> scope.launch { snackbarHost.showSnackbar(message) } },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        ) { data ->
            // M3 默认 Snackbar 用 inverseSurface，在深色主题里是一块发白的板子——
            // 夜里晃眼，也过不了近白像素闸门。这里显式压回夜间色。
            Snackbar(
                snackbarData = data,
                containerColor = NightRaised,
                contentColor = Paper,
                actionColor = Ember,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun DetailTopBar(
    date: String,
    onBack: () -> Unit,
    onAction: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable { onBack() }
                .semantics { contentDescription = "返回" },
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", style = MaterialTheme.typography.titleLarge, color = Paper)
        }
        Text(
            text = date,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            color = Paper,
            maxLines = 1,
        )
        Box {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable { menuOpen = true }
                    .semantics { contentDescription = "更多" },
                contentAlignment = Alignment.Center,
            ) {
                Text("⋮", style = MaterialTheme.typography.titleLarge, color = Paper)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // Plan 1 只给入口不给实现：点了必须给一句人话，不能什么也不发生
                DropdownMenuItem(
                    text = { Text("编辑", style = MaterialTheme.typography.bodyMedium, color = Paper) },
                    onClick = {
                        menuOpen = false
                        onAction("编辑还没做，之后会有")
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除", style = MaterialTheme.typography.bodyMedium, color = ErrorText) },
                    onClick = {
                        menuOpen = false
                        onAction("删除还没做，之后会有")
                    },
                )
            }
        }
    }
}

/**
 * tab 行：四等分、整格可点、`selected` 语义（读屏能念出「已选中」）。
 *
 * 选中 = `Paper` 文字 + 下方 2dp `Ember` 短横线，未选中 `InkDim`——和底部栏同一套语言。
 * 未选中时指示线画成 `Night`（与背景同色，看不见）而不是省略，tab 文字才不会上下跳 6dp。
 */
@Composable
private fun DetailTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        DetailTab.entries.forEachIndexed { index, tab ->
            val isSelected = index == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = isSelected,
                        onClick = { onSelect(index) },
                        role = Role.Tab,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) Paper else InkDim,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .width(20.dp)
                        .height(2.dp)
                        .background(if (isSelected) Ember else Night),
                )
            }
        }
    }
}

/**
 * 「原样」tab 的三种状态（§0.8）：已转写 / 转写中 / 转写失败。
 *
 * PENDING 也可能带着正文——追问窗口收尾会把状态改回 PENDING 但保留旧文本，
 * 所以这里不是「空则提示」，而是「有字就先显示字」。任何分支都不清空已有原文。
 */
@Composable
private fun TranscriptTab(
    dream: Dream?,
    onSave: suspend (String) -> Int,
    onMessage: (String) -> Unit,
) {
    if (dream == null) return
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val text = dream.rawTranscript
    val status = dream.transcriptStatus

    Column(modifier = Modifier.fillMaxSize()) {
        if (text.isBlank() && status != TranscriptStatus.OK) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = if (status == TranscriptStatus.PENDING) "正在转写…" else "没有转写成功",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Paper,
                    )
                    Text(
                        text = if (status == TranscriptStatus.PENDING) {
                            "录音已经存好了。本机转写完成后，原文会出现在这里。"
                        } else {
                            "可能是本机没有中文离线包，或识别服务不可用。先把听到的话补进来也行。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkDim,
                        textAlign = TextAlign.Center,
                    )
                    if (status == TranscriptStatus.FAILED) {
                        TextButton(onClick = { dialogOpen = true }) {
                            Text("手动补字", color = Ember)
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                BodyScroll(text = text, color = Paper)
            }
            when (status) {
                TranscriptStatus.PENDING -> Text(
                    text = "正在转写…",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint,
                )

                TranscriptStatus.FAILED -> Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "没能自动转写这段录音",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = InkDim,
                    )
                    TextButton(onClick = { dialogOpen = true }) {
                        Text("手动补字", color = Ember)
                    }
                }

                else -> Unit
            }
        }
    }

    if (dialogOpen) {
        ManualTranscriptDialog(
            initial = text,
            onDismiss = { dialogOpen = false },
            onSave = { draft ->
                scope.launch {
                    val rows = onSave(draft)
                    dialogOpen = false
                    // §0.8：0 行不是失败，是「已经写进去了」；让用户回去刷新，而不是假保存。
                    onMessage(if (rows == 0) "已经写进去了，请返回刷新" else "已保存")
                }
            },
        )
    }
}

@Composable
private fun ManualTranscriptDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NightRaised,
        titleContentColor = Paper,
        textContentColor = Paper,
        title = { Text("手动补字") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("把记得的那几句写下来…", color = InkFaint) },
                minLines = 6,
                maxLines = 12,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                enabled = draft.isNotBlank(),
            ) {
                Text("保存", color = Ember)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = InkDim) }
        },
    )
}

/**
 * 滚动正文：左右 16dp、行高 30sp（`bodyLarge` 自带）、段间距 12dp，底部留 16dp 给播放条。
 *
 * 段落按换行切：原样稿是语音转写，句读靠换行；空行不单独占位，统一 12dp 段距。
 */
@Composable
private fun BodyScroll(text: String, color: Color) {
    val paragraphs = remember(text) {
        text.split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        paragraphs.forEach { paragraph ->
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyLarge,
                color = color,
            )
        }
    }
}

@Composable
private fun NovelTab(count: Int, novelText: String?) {
    Column(modifier = Modifier.fillMaxSize()) {
        // brief 0.7：N 从 tab 标题挪到内容顶部这一行；「补写 N 处」的字样仍与 D41 一致
        if (count > 0) {
            Text(
                text = "补写 $count 处 · 查看",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Ember,
            )
        }
        if (novelText.isNullOrBlank()) {
            BodyScroll(text = NOVEL_EMPTY, color = InkDim)
        } else {
            BodyScroll(text = novelText, color = Paper)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsTab(tags: List<Tag>) {
    if (tags.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无标签", style = MaterialTheme.typography.bodyLarge, color = InkDim)
        }
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            // 只读胶囊：Plan 1 不在详情页增删标签
            Text(
                text = tag.name,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(NightRaised)
                    .border(1.dp, Line, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Paper,
            )
        }
    }
}

@Composable
private fun MediaTab() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // NightRaised 与 Night 只有约 1.07:1：不给 1dp InkDim 边就等于没画（brief 0.8）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(NightRaised)
                .border(1.dp, InkDim, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "以后可以在这里把梦做成视频",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkDim,
                )
                Text(
                    "先留个位置",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint,
                )
            }
        }
    }
}

@Composable
private fun MissingDream(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("找不到这条梦", style = MaterialTheme.typography.bodyLarge, color = InkDim)
            // 顶栏的「‹」也能返回；这里再给一个明确的文字入口，不让用户猜
            Text(
                text = "返回",
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Ember,
            )
        }
    }
}

/**
 * 常驻播放条。**签名就此定死**：Plan 2 只换内部实现，不改调用方。
 *
 * Plan 1 传 `null, null`：不创建 `MediaPlayer`（空路径会崩），时长显示 `--:--`。
 * 波形高度由固定公式生成，不能每帧随机——那看着像坏了。
 */
@Composable
fun PlaybackBar(audioPath: String?, durationMs: Long?, onAction: (String) -> Unit = {}) {
    val label = durationMs?.let { ms -> "%d:%02d".format(ms / 60_000, (ms / 1000) % 60) } ?: "--:--"
    val barHeights = remember { List(WAVE_BARS) { i -> (6 + (i * 7 + 3) % 15).dp } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NightRaised)
            .border(1.dp, Line, RoundedCornerShape(8.dp))
            .clickable {
                // Plan 2：audioPath != null 时在这里接 MediaPlayer。
                // Plan 1 点了必须给一句人话——按钮不响等于坏了。
                onAction(if (audioPath == null) "这条梦还没有录音" else "播放还没做，之后会有")
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 12.dp, height = 16.dp)) {
            val triangle = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(triangle, Paper)
        }
        Row(
            modifier = Modifier.weight(1f).height(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            barHeights.forEach { barHeight ->
                Box(modifier = Modifier.width(1.dp).height(barHeight).background(InkDim))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = InkFaint)
    }
}
