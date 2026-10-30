package com.dreamkeeper.ui.detail

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.NovelMessageEntity
import com.dreamkeeper.data.db.NovelVersionEntity
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.domain.Tag
import com.dreamkeeper.export.ExportFormat
import com.dreamkeeper.export.ExportResult
import com.dreamkeeper.llm.GenerationKind
import com.dreamkeeper.llm.ReviseOrchestrator
import com.dreamkeeper.ui.day.DayViewModel
import com.dreamkeeper.ui.theme.Ember
import com.dreamkeeper.ui.theme.EmberDeep
import com.dreamkeeper.ui.theme.ErrorText
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.InkFaint
import com.dreamkeeper.ui.theme.Line
import com.dreamkeeper.ui.theme.Night
import com.dreamkeeper.ui.theme.NightRaised
import com.dreamkeeper.ui.theme.Paper
import java.io.File
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
 * 中间允许空白：`补 写 3 处` 也要能抽出来（D41）。
 *
 * Plan 3 / D318 起该字段存的是 JSON 数组文本（条目数 = 补写处数）；老数据仍是
 * 「补写 N 处」的自由文本，所以保留旧正则做回退。两条分支都不改 [novelTabLabel] 的格式。
 */
fun novelChangeCount(changeLog: String?): Int {
    val raw = changeLog?.trim().orEmpty()
    if (raw.startsWith("[")) {
        // 坏 JSON（半截写入 / 手改坏）按 0 处理：详情页宁可少一个角标，也不能崩。
        return runCatching { org.json.JSONArray(raw).length() }.getOrElse { 0 }
    }
    return Regex("补\\s*写\\s*(\\d+)\\s*处").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

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
 * 把真实播放器下发到 [PlaybackBar]，不经过详情页根 Composable 的 state 收集
 * （brief §0.7：`playback.state` 只允许在播放条内部 collect）。
 */
val LocalAudioPlayback = staticCompositionLocalOf<AudioPlayback?> { null }

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
    audioStore: WavStore,
    onBack: () -> Unit,
    orchestrator: ReviseOrchestrator? = null,
    /** Plan 4 §0.4：去追加录音（路由由宿主提供）。 */
    onAppend: (String) -> Unit = {},
) {
    val vm: DetailViewModel = viewModel {
        DetailViewModel(
            repo = repo,
            dreamId = dreamId,
            audioStore = audioStore,
            // Task 8 的生成链也挂在同一个容器编排器上；没有容器时两处都为 null，详情页只读。
            orchestrator = orchestrator?.generation,
            revise = orchestrator,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val generationPhase by vm.generationPhase.collectAsStateWithLifecycle()
    val novelIdeaOpen by vm.novelIdeaPrompt.collectAsStateWithLifecycle()
    val exportState by vm.export.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    val exportContext = LocalContext.current
    // §0.4 导出闸门：有最终稿，或至少有可回退的原文；两者都没有时按钮禁用并给文案。
    val exportableBody = state.finalNovel != null ||
        !(state.dream?.faithfulText ?: state.dream?.rawTranscript).isNullOrBlank()
    // D379：MediaStore.Downloads 需要 API 29+，低版本不导出（按钮禁用 + 文案）。
    val exportSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalAudioPlayback provides vm.playback) {
        Box(modifier = Modifier.fillMaxSize().background(Night)) {
        Column(modifier = Modifier.fillMaxSize()) {
            DetailTopBar(
                date = state.dream?.date?.let { DayViewModel.titleOf(it) }.orEmpty(),
                onBack = onBack,
                onAction = { message -> scope.launch { snackbarHost.showSnackbar(message) } },
                onExport = { exportOpen = true },
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
                                generationPhase = generationPhase,
                                onSave = { text -> vm.saveManualTranscript(text) },
                                onMessage = { message -> scope.launch { snackbarHost.showSnackbar(message) } },
                                onAppend = { state.dream?.let { onAppend(it.id) } },
                                onRepolish = vm::generateFaithful,
                                onRetryTranscription = vm::retryTranscription,
                                onSkipTranscription = vm::skipTranscription,
                            )

                            1 -> NovelTab(
                                count = state.novelChangeCount,
                                // §0.7：详情正文用最终稿（selected ?: maxVersion），不用 dreams.novelText。
                                novelText = state.finalNovel?.novelText ?: state.dream?.novelText,
                                // 是否有「小说稿」：只认 novel / novel_revise 版本，原样整理稿不算（D394）。
                                hasNovelVersion = hasNovelVersion(state.dream, state.versions),
                                // 是否有「原样稿」：决定「原样整理」按钮还露不露（D406，Task 10 E2E 复现双点）。
                                hasFaithfulVersion = hasFaithfulVersion(state.dream, state.versions),
                                versions = state.versions,
                                messages = state.messages,
                                selectedVersion = state.dream?.novelSelectedVersion,
                                currentVersion = state.finalNovel?.version ?: state.dream?.novelVersion,
                                revising = state.revising,
                                reviseError = state.reviseError,
                                revisionAck = state.revisionAck,
                                generationPhase = generationPhase,
                                onGenerateFaithful = vm::generateFaithful,
                                onGenerateNovel = vm::requestNovel,
                                novelIdeaOpen = novelIdeaOpen,
                                onNovelIdeaConfirm = vm::confirmNovelIdea,
                                onNovelIdeaDismiss = vm::dismissNovelIdea,
                                onRetryGeneration = vm::retryGeneration,
                                onSend = vm::revise,
                                onSelectVersion = vm::selectVersion,
                                onExport = { exportOpen = true },
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
        if (exportOpen) {
            ExportSheet(
                exporting = exportState.exporting,
                saved = exportState.saved,
                error = exportState.error,
                canExport = exportableBody,
                apiSupported = exportSupported,
                onExport = { format -> vm.export(format, exportContext.contentResolver) },
                onShare = { result -> shareExported(exportContext, result) },
                onDismiss = {
                    exportOpen = false
                    vm.resetExport()
                },
            )
        }
        }
    }
}

@Composable
private fun DetailTopBar(
    date: String,
    onBack: () -> Unit,
    onAction: (String) -> Unit,
    onExport: () -> Unit,
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
                // Task 10 导出入口 1：顶栏「更多」里；入口 2 在小说化 tab 顶部。
                DropdownMenuItem(
                    text = { Text("导出", style = MaterialTheme.typography.bodyMedium, color = Paper) },
                    onClick = {
                        menuOpen = false
                        onExport()
                    },
                )
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
    generationPhase: GenerationPhase,
    onSave: suspend (String) -> Int,
    onMessage: (String) -> Unit,
    onAppend: () -> Unit,
    onRepolish: () -> Unit,
    onRetryTranscription: () -> Unit,
    onSkipTranscription: () -> Unit,
) {
    if (dream == null) return
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    var showRaw by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val faithful = dream.faithfulText?.takeIf { it.isNotBlank() }
    val raw = dream.rawTranscript
    val text = if (showRaw || faithful == null) raw else faithful
    val status = dream.transcriptStatus
    val totalMs = dream.rawAudioMs ?: 0L
    val uncoveredMs = (totalMs - dream.transcriptMs).coerceAtLeast(0L)
    val polishing = generationPhase is GenerationPhase.Running &&
        generationPhase.kind == GenerationKind.FAITHFUL

    Column(modifier = Modifier.fillMaxSize()) {
        // 标题行：现在显示的是整理稿还是原始转写；有整理稿时给「看原始转写」（Plan 4 §0.6）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    polishing -> "正在理顺…"
                    faithful != null && !showRaw -> "整理稿"
                    else -> "原始转写"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = if (polishing) Ember else InkDim,
            )
            if (faithful != null && raw.isNotBlank()) {
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(
                        text = if (showRaw) "看整理稿" else "看原始转写",
                        style = MaterialTheme.typography.labelSmall,
                        color = Paper,
                    )
                }
            }
        }
        // 有没转成文字的尾部（rawAudioMs > transcriptMs）：给重试/跳过的显式出口（D421 之后）。
        if (uncoveredMs > 0L && status != TranscriptStatus.PENDING) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "还有 ${(uncoveredMs / 1000).coerceAtLeast(0)} 秒没转成文字",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkDim,
                )
                TextButton(onClick = onRetryTranscription) {
                    Text("重试转写", style = MaterialTheme.typography.labelSmall, color = Ember)
                }
                TextButton(onClick = onSkipTranscription) {
                    Text("这段不用转写", style = MaterialTheme.typography.labelSmall, color = InkDim)
                }
            }
        }
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
        // Plan 4 §0.4：追加录音入口常驻（不依赖有没有转写成功）；有整理稿时给「重新理顺」。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onAppend) {
                Text("补一段", color = Ember)
            }
            if (faithful != null) {
                TextButton(onClick = onRepolish, enabled = !polishing && uncoveredMs == 0L) {
                    Text("重新理顺", color = if (polishing || uncoveredMs > 0L) InkDim else Paper)
                }
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
private fun NovelTab(
    count: Int,
    novelText: String?,
    /**
     * 是否已有小说稿。不能拿 [novelText] 判：`finalNovel` 在没有版本时会回退到
     * `faithfulText` / `rawTranscript`（Task 9 的展示契约），这样每一条有原文的梦都会
     * 被当成「已有稿」而永远不显示生成入口。口径见 [hasNovelVersion]（D394）。
     */
    hasNovelVersion: Boolean,
    /**
     * 是否已有原样稿（`promptId == "faithful"` 的版本，或老数据的 `dreams.faithfulText`）。
     * 两枚按钮分别判：整理过的梦不该再露「原样整理」（D406，Task 10 E2E 双点复现）。
     */
    hasFaithfulVersion: Boolean,
    versions: List<NovelVersionEntity>,
    messages: List<NovelMessageEntity>,
    selectedVersion: Int?,
    currentVersion: Int?,
    revising: Boolean,
    reviseError: String?,
    revisionAck: Int,
    generationPhase: GenerationPhase,
    onGenerateFaithful: () -> Unit,
    onGenerateNovel: () -> Unit,
    novelIdeaOpen: Boolean,
    onNovelIdeaConfirm: (String?) -> Unit,
    onNovelIdeaDismiss: () -> Unit,
    onRetryGeneration: () -> Unit,
    onSend: (String) -> Unit,
    onSelectVersion: (Int) -> Unit,
    onExport: () -> Unit,
) {
    var opinion by rememberSaveable { mutableStateOf("") }
    // ASK 的助手气泡：只有 versionId==null 的 assistant 消息才是「没建版、只回话」的那条。
    val askMessages = remember(messages) {
        messages.filter { it.role == "ASSISTANT" && it.versionId == null }
    }
    val awaitingReply = messages.lastOrNull()?.let { it.role == "ASSISTANT" && it.versionId == null } == true
    LaunchedEffect(revisionAck) {
        if (revisionAck > 0) opinion = ""
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // Task 9b/9c/9d：两枚按钮各自按「有没有对应稿」收起（D394/D406）；都收起了整块不出现，
        // 只留版本线与改稿输入条——有稿之后入口再出现会让人误以为会覆盖当前选中版。
        // Plan 4 T5：入口区常驻——小说化必须由用户主动触发（可以带着新想法重新生成一版）；
        // 「原样整理」仍按 D406 收起（整理两遍只会得到近似重复的版本）。
        GenerationActions(
            phase = generationPhase,
            showFaithful = !hasFaithfulVersion,
            showNovel = true,
            onFaithful = onGenerateFaithful,
            onNovel = onGenerateNovel,
            onRetry = onRetryGeneration,
        )
        if (novelIdeaOpen) {
            NovelIdeaDialog(
                onConfirm = onNovelIdeaConfirm,
                onDismiss = onNovelIdeaDismiss,
            )
        }
        // brief 0.7：N 从 tab 标题挪到内容顶部这一行；「补写 N 处」的字样仍与 D41 一致。
        // Task 10：同一行右侧给第二个导出入口（顶栏「更多」里还有一个）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (count > 0) {
                Text(
                    text = "补写 $count 处 · 查看",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ember,
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            TextButton(onClick = onExport) {
                Text(
                    text = "导出",
                    style = MaterialTheme.typography.labelSmall,
                    color = Paper,
                )
            }
        }
        VersionStrip(
            versions = versions,
            messages = messages,
            selectedVersion = selectedVersion,
            currentVersion = currentVersion,
            onSelect = onSelectVersion,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val body = novelText?.takeIf { it.isNotBlank() }
            if (body == null) {
                Text(text = NOVEL_EMPTY, style = MaterialTheme.typography.bodyLarge, color = InkDim)
            } else {
                body.split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { paragraph ->
                    Text(
                        text = paragraph,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Paper,
                    )
                }
            }
            askMessages.forEach { message ->
                AskBubble(message.content)
            }
        }
        ReviseSheet(
            value = opinion,
            onValueChange = { opinion = it },
            onSend = { onSend(opinion) },
            sending = revising,
            error = reviseError,
            placeholder = if (awaitingReply) "回它一句" else "说说哪里想改…",
        )
    }
}

/**
 * 没有小说稿时 NovelTab 顶部的生成入口（Task 9b / D356）。
 *
 * 三态只影响这一块：Idle 只有两枚按钮；Running 显示正在哪条链的第几档（按钮禁用防重入）；
 * Failed 给「没生成成：{人话}」+「重试」。重试入口由 ViewModel 记着失败时的 kind，所以
 * 「原样整理」失败只会重试原样整理，「小说化」失败只会重试小说化。
 *
 * 有稿后整块不出现：版本线 + 改稿输入条已经能表达「继续改这一版」，再放生成按钮会让人
 * 误以为会覆盖当前选中版。
 */
/**
 * 小说化想法对话框（Plan 4 T5 / 用户需求 ②）。
 *
 * 「一定得用户主动去操作」的落点：点「小说化」先弹这张卡，用户可以写一句想法（可选），
 * 确认后才真正发请求。想法 ≤200 字，空白按「没有想法」处理（提示词第 4 槽整对删除）。
 */
@Composable
private fun NovelIdeaDialog(
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var idea by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("小说化", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    "想怎么改？可以不写，直接生成。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkDim,
                )
                OutlinedTextField(
                    value = idea,
                    onValueChange = { if (it.length <= NOVEL_IDEA_MAX) idea = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    placeholder = { Text("比如：多写点楼道的细节，少一点心理描写") },
                    minLines = 2,
                    maxLines = 4,
                    supportingText = { Text("${idea.length}/$NOVEL_IDEA_MAX", style = MaterialTheme.typography.labelSmall) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(idea) }) {
                Text("生成", color = Ember)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = InkDim)
            }
        },
        containerColor = NightRaised,
    )
}

private const val NOVEL_IDEA_MAX = 200

@Composable
private fun GenerationActions(
    phase: GenerationPhase,
    showFaithful: Boolean,
    showNovel: Boolean,
    onFaithful: () -> Unit,
    onNovel: () -> Unit,
    onRetry: () -> Unit,
) {
    val running = phase is GenerationPhase.Running
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showFaithful) {
                Button(
                    onClick = onFaithful,
                    enabled = !running,
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmberDeep,
                        contentColor = Paper,
                        disabledContainerColor = Line,
                        disabledContentColor = InkFaint,
                    ),
                ) {
                    Text("原样整理", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (showNovel) {
                OutlinedButton(
                    onClick = onNovel,
                    enabled = !running,
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Ember,
                        disabledContentColor = InkFaint,
                    ),
                ) {
                    Text("小说化", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        when (phase) {
            GenerationPhase.Idle -> Unit

            is GenerationPhase.Running -> Text(
                text = if (phase.kind == GenerationKind.FAITHFUL) {
                    "正在原样整理…（第 ${phase.attempt} 步）"
                } else {
                    "正在小说化…（第 ${phase.attempt} 步）"
                },
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Ember,
            )

            is GenerationPhase.Failed -> Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "没生成成：${phase.reason}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = ErrorText,
                )
                TextButton(onClick = onRetry, modifier = Modifier.height(40.dp)) {
                    Text("重试", color = Ember, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** ASK 的助手气泡：无 vN 徽标、无版本芯片（§0.8）。 */
@Composable
private fun AskBubble(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NightRaised, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(text = "助手", style = MaterialTheme.typography.labelSmall, color = Ember)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Paper,
            modifier = Modifier.padding(top = 4.dp),
        )
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
    val playback = LocalAudioPlayback.current
    // brief §0.7：state 只在这一层 collect；根 Composable 不订阅播放器。
    val current = playback?.state?.collectAsStateWithLifecycle()?.value ?: PlaybackState.Idle
    val hasAudio = audioPath != null
    val enabled = hasAudio && playback != null

    // Paused 不带时长（brief §0.5）；进度与标签一律用入参 durationMs，缺失才退回播放器解析值。
    val totalMs = when {
        durationMs != null && durationMs > 0L -> durationMs
        current is PlaybackState.Playing -> current.durationMs
        else -> 0L
    }
    val progress = playbackProgress(current, totalMs)
    val label = when {
        !hasAudio -> "这条梦没有录音"
        playback == null -> "播放器不可用"
        else -> barLabel(current, totalMs)
    }
    val labelColor = if (current is PlaybackState.Error) ErrorText else InkFaint
    val isPlaying = current is PlaybackState.Playing

    // 音频焦点：只在播放期间持有；来电/别的应用抢走焦点就暂停，而不是继续念。
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    DisposableEffect(isPlaying, playback, audioManager) {
        var request: AudioFocusRequest? = null
        if (isPlaying && playback != null) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    playback.pause()
                }
            }
            request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(listener)
                .build()
            audioManager.requestAudioFocus(request)
        }
        onDispose { request?.let { audioManager.abandonAudioFocusRequest(it) } }
    }

    // 离开页面（锁屏/切后台）就停：后台不该有声音，也避免 MediaPlayer 被系统收回后状态错乱。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, playback) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playback?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 进度在 draw 阶段读取；40 根柱子一次 Canvas 画完，不做 40 个 Box 重组。
    val progressState = rememberUpdatedState(progress)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(8.dp))
            .background(NightRaised)
            .border(1.dp, Line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) {
                when {
                    playback == null -> onAction("播放器不可用")
                    current is PlaybackState.Playing -> playback.pause()
                    current is PlaybackState.Paused -> playback.toggle()
                    // 已结束（Paused 停在 duration）再点：toggle 会先 seekTo(0)，从头播。
                    else -> audioPath?.let { path -> playback.prepare(File(path), totalMs) }
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 12.dp, height = 16.dp)) {
            if (isPlaying) {
                val barWidth = size.width * 0.34f
                drawRect(Paper, topLeft = Offset(0f, 0f), size = Size(barWidth, size.height))
                drawRect(Paper, topLeft = Offset(size.width - barWidth, 0f), size = Size(barWidth, size.height))
            } else {
                val triangle = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(triangle, Paper)
            }
        }
        Canvas(modifier = Modifier.weight(1f).height(24.dp)) {
            val playedX = size.width * progressState.value
            val gap = size.width / (WAVE_BARS - 1).coerceAtLeast(1)
            val barWidth = 1.dp.toPx()
            for (i in 0 until WAVE_BARS) {
                val heightFactor = (6 + (i * 7 + 3) % 15) / 21f
                val barHeight = size.height * heightFactor
                val x = i * gap
                drawRect(
                    color = if (x <= playedX) Ember else InkDim,
                    topLeft = Offset(x - barWidth / 2f, (size.height - barHeight) / 2f),
                    size = Size(barWidth, barHeight),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor)
    }
}

/** Task 10：导出成功后把 MediaStore Uri 分享出去；必须带读权限，否则接收方打不开。 */
private fun shareExported(context: Context, result: ExportResult) {
    val mime = when (result.displayName.substringAfterLast('.', "").lowercase()) {
        "txt" -> "text/plain"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pdf" -> "application/pdf"
        else -> "*/*"
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, result.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "分享导出文件"))
}
