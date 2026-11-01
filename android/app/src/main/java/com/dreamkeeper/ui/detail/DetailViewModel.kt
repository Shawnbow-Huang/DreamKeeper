package com.dreamkeeper.ui.detail

import android.content.ContentResolver
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.FinalNovel
import com.dreamkeeper.data.db.NovelMessageEntity
import com.dreamkeeper.data.db.NovelVersionEntity
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.domain.MediaAsset
import com.dreamkeeper.domain.Tag
import com.dreamkeeper.export.AndroidExporter
import com.dreamkeeper.export.ExportFormat
import com.dreamkeeper.export.ExportResult
import com.dreamkeeper.llm.GenerationFailure
import com.dreamkeeper.llm.GenerationKind
import com.dreamkeeper.llm.GenerationRunner
import com.dreamkeeper.llm.LlmException
import com.dreamkeeper.llm.ReviseOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 详情页状态。
 *
 * [assets] 只是签名占位（Plan 3 才接 `MediaAsset`）；Plan 1 的 ViewModel **不订阅**
 * `observeMediaAssets`，恒为空表——种子里那条 `VIDEO` 记录不该在素材 tab 渲染出来。
 *
 * [loaded] 是对 brief Produces 签名的**末尾追加**：`dream == null` 同时表示「还在读库」与
 * 「这个 id 根本不存在」，只靠它无法区分，界面会在加载的那一帧闪一下「找不到这条梦」，
 * 测试也永远绿（读的是 `stateIn` 的 initialValue）。加一个默认 false 的布尔值把两态分开。
 */
data class DetailUiState(
    val dream: Dream? = null,
    val tags: List<Tag> = emptyList(),
    val assets: List<MediaAsset> = emptyList(),
    val novelChangeCount: Int = 0,
    val loaded: Boolean = false,
    /** 详情正文/导出的最终正文（§0.7：`selected ?: maxVersion`，不是 `dreams.novelText`）。 */
    val finalNovel: FinalNovel? = null,
    val versions: List<NovelVersionEntity> = emptyList(),
    val messages: List<NovelMessageEntity> = emptyList(),
    val revising: Boolean = false,
    val reviseError: String? = null,
    /** 每成功一轮 +1；NovelTab 用它清空输入框（失败时保留原文可重发，D330）。 */
    val revisionAck: Int = 0,
)

/**
 * 详情页的生成进度（§0.8 / D319）。
 *
 * 只表达「现在在干什么」与「为什么没成」，不掺结果本身——结果已经落库，UI 重读
 * `state` 就能看到新的 `novelText` / `novelChangeLog`。
 */
sealed interface GenerationPhase {
    data object Idle : GenerationPhase

    /** [attempt] 是档位序号（T0=1 … T3=4），不是 HTTP 重试次数。 */
    data class Running(val kind: GenerationKind, val attempt: Int) : GenerationPhase

    data class Failed(val kind: GenerationKind, val reason: String) : GenerationPhase
}

/**
 * 导出面板状态（Task 10 §0.3）。
 *
 * ViewModel 不持有 Context：`ContentResolver` 由 Composable 在点击时传入；成功后把
 * [ExportResult] 交给 UI 做 `ACTION_SEND` 分享，失败原因必须回显，不能静默吞。
 */
data class ExportUiState(
    val exporting: ExportFormat? = null,
    val saved: ExportResult? = null,
    val error: String? = null,
)

/**
 * 详情页：把「这一条梦」和「它的标签」两条 Flow 合起来，并持有播放器。
 *
 * 标签必须走 `observeTagsForDream`（`tags INNER JOIN dream_tags`）：`Dream` 上没有
 * `tags` 字段，也不该有——详情页不碰 DAO，只认仓库翻译好的 `Tag`。
 *
 * 播放器放在 ViewModel 而不是 Composable 的 `remember`（brief §0.10）：旋转/重组时
 * 播放不断，离开页面时 `onCleared` 一定会 `release()`，MediaPlayer 不会漏。
 *
 * `audioStore` 默认 null 是为了让既有 `DetailViewModel(repo, dreamId)` 单测与
 * 预览继续编译；真正的详情页由 [DetailScreen] 从根容器传进来（brief §0.8）。
 */
class DetailViewModel(
    private val repo: DreamRepository,
    dreamId: String,
    audioStore: WavStore? = null,
    playerFactory: () -> MediaPlayer = { MediaPlayer() },
    /**
     * 生成入口（Task 8 / Task 9b）。默认 null 是为了不破既有单测与预览——没有容器时详情页只读不生成。
     * 不在这里 new 一个全局单例：编排器依赖网络/价卡/数据库，只有 [com.dreamkeeper.di.AppContainer]
     * 知道怎么装配它们。类型是 [GenerationRunner] 而不是具体编排器：按钮只需要两个 suspend 入口。
     */
    private val orchestrator: GenerationRunner? = null,
    /** 「重试转写」成功把行拨回 PENDING 后，真正让 Writer 去补跑（A2）。 */
    private val onRetryTranscription: (() -> Unit)? = null,
    /** 对话改稿入口（Task 9）。默认 null 同样只为不破既有两参单测；生产由根容器注入。 */
    private val revise: ReviseOrchestrator? = null,
    /**
     * 生成任务的协程作用域（Plan 4 T6b / §0.10）。生产传容器的 `generationScope`：
     * 用户退出详情页不该掐掉已经在跑的请求；null 时退化为 `viewModelScope`（单测/预览）。
     */
    private val generationScope: kotlinx.coroutines.CoroutineScope? = null,
    /** 跨 ViewModel 的进度表（T6b）：重进详情页要能看见 Running / Failed。 */
    private val tracker: GenerationTracker? = null,
) : ViewModel() {

    private val dreamId: String = dreamId

    /** 导出三态：面板打开期间订阅；关闭时 [resetExport] 清空。 */
    private val _export = MutableStateFlow(ExportUiState())
    val export: StateFlow<ExportUiState> = _export.asStateFlow()

    /** 版本线/消息/最终稿不是 `Dream` 上的列，单独一条流；任何写操作后重读。 */
    private data class Conversation(
        val versions: List<NovelVersionEntity> = emptyList(),
        val messages: List<NovelMessageEntity> = emptyList(),
        val finalNovel: FinalNovel? = null,
        val revising: Boolean = false,
        val reviseError: String? = null,
        val revisionAck: Int = 0,
    )

    private val conversation = MutableStateFlow(Conversation())

    val state: StateFlow<DetailUiState> = combine(
        repo.observe(dreamId),
        repo.observeTagsForDream(dreamId),
        conversation,
    ) { dream, tags, convo ->
        DetailUiState(
            dream = dream,
            tags = tags,
            assets = emptyList(),
            novelChangeCount = novelChangeCount(dream?.novelChangeLog),
            loaded = true,
            finalNovel = convo.finalNovel,
            versions = convo.versions,
            messages = convo.messages,
            revising = convo.revising,
            reviseError = convo.reviseError,
            revisionAck = convo.revisionAck,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DetailUiState(),
    )

    /** null 表示这次构造没拿到落盘层（单测/无音频场景），播放条据此禁用。 */
    val playback: AudioPlayback? = audioStore?.let { store ->
        AudioPlayback(scope = viewModelScope, store = store, playerFactory = playerFactory)
    }

    /** 同一份字节时长只回写一次；换文件（长度变了）才允许再写。 */
    private var fixedByteMs: Long = Long.MIN_VALUE

    init {
        viewModelScope.launch {
            runCatching { _costMicroYuan.value = repo.sumCostMicroYuan(dreamId) }
        }
        viewModelScope.launch { refreshConversation() }
        val playback = playback
        if (playback != null) {
            // brief §0.12：字节是时长的唯一真源。repair 后解析出的时长与库里
            // `rawAudioMs` 差 >100ms 时，用字节值回写，并把偏差打进 logcat。
            viewModelScope.launch {
                combine(state, playback.durationMs) { ui, byteMs -> ui.dream to byteMs }
                    .collect { (dream, byteMs) ->
                        if (dream == null || byteMs <= 0L) return@collect
                        if (!audioMsNeedsFix(dream.rawAudioMs, byteMs)) return@collect
                        if (fixedByteMs == byteMs) return@collect
                        fixedByteMs = byteMs
                        repo.fixAudioMs(dream.id.ifBlank { dreamId }, byteMs)
                    }
            }
        }
    }

    /**
     * 详情页「手动补字」：与自动写回走同一条 CAS（§0.8）。
     *
     * 返回 0 表示让位：通常是对面已经写进去了（转写结果先到），或者 `rawAudioMs` 已变。
     * 调用方据此提示「已经写进去了，请返回刷新」，而不是假装保存成功。
     */
    suspend fun saveManualTranscript(text: String): Int {
        val dream = state.value.dream ?: return 0
        val expectedMs = dream.rawAudioMs ?: return 0
        return repo.updateManualTranscript(dream.id, expectedMs, text)
    }

    // ------------------------------------------------------------ 生成入口（Task 8 / 9b）

    private val _generationPhase = MutableStateFlow<GenerationPhase>(
        tracker?.phaseFor(dreamId) ?: GenerationPhase.Idle,
    )

    /** 当前生成进度。成功后回到 [GenerationPhase.Idle]——正文已经由 `state` 的 Flow 刷新。 */
    val generationPhase: StateFlow<GenerationPhase> = _generationPhase.asStateFlow()

    /** 写本地 + 跨页进度表（T6b）：只写一处，避免两条来源打架。 */
    private fun publishPhase(phase: GenerationPhase) {
        _generationPhase.value = phase
        when (phase) {
            GenerationPhase.Idle -> tracker?.clear(dreamId)
            is GenerationPhase.Running -> tracker?.running(dreamId, phase.kind, phase.attempt)
            is GenerationPhase.Failed -> tracker?.failed(dreamId, phase.kind, phase.reason)
        }
    }

    /**
     * 小说化想法对话框是否打开（Plan 4 T5）。
     *
     * 为什么不直接生成：用户要的是「先说说自己的想法，再点生成」——对话框是这一步的唯一入口，
     * 关掉就等于放弃了「显式触发」的语义（不许随整理稿自动生成，见 D344/D395）。
     */
    /** 成本行（T6b）：0 表示没有真实请求，UI 不显示。 */
    private val _costMicroYuan = MutableStateFlow(0L)
    val costMicroYuan: StateFlow<Long> = _costMicroYuan.asStateFlow()

    private val _novelIdeaPrompt = MutableStateFlow(false)
    val novelIdeaPrompt: StateFlow<Boolean> = _novelIdeaPrompt.asStateFlow()

    /**
     * 追加录音后的自动重理顺（Plan 4 §0.4）：等尾段转写落库（status OK 且水位追平）后才跑，
     * 且只跑一次。防重入交给 [generate] 的 Running 判断；这里不清空用户输入。
     */
    private val appendPending = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            runCatching { _costMicroYuan.value = repo.sumCostMicroYuan(dreamId) }
        }
        viewModelScope.launch {
            state.collect { state ->
                if (!appendPending.value) return@collect
                val dream = state.dream ?: return@collect
                val total = dream.rawAudioMs ?: return@collect
                if (total <= 0L) return@collect
                if (dream.transcriptStatus == TranscriptStatus.OK && dream.transcriptMs >= total) {
                    appendPending.value = false
                    generateFaithful()
                }
            }
        }
    }

    /** 录音页追加保存后的回调：等转写追平再自动重新理顺（幂等，不重复触发）。 */
    fun onAppendRecorded() {
        appendPending.value = true
    }

    /** 生成/改稿在飞时不放行追加录音（§0.4 互斥）。 */
    fun canAppendNow(): Boolean =
        _generationPhase.value !is GenerationPhase.Running && !state.value.revising

    /** 尾部没转成文字：把 FAILED 拨回 PENDING，交给收尾/启动路径补跑。 */
    fun retryTranscription() {
        val total = state.value.dream?.rawAudioMs ?: return
        viewModelScope.launch {
            val rows = runCatching { repo.markTranscriptionRetry(dreamId, total) }.getOrDefault(0)
            // 0 行 = 水位已追平/状态不对，没必要再排一次；>0 才让 Writer 真正去转写（A2）。
            if (rows > 0) onRetryTranscription?.invoke()
        }
    }

    /**
     * 僵住的 PENDING 行：不再改状态，直接把「启动补跑」再执行一次。
     *
     * 与 [retryTranscription] 的区别：那条只对 FAILED 生效（先把状态拨回 PENDING），
     * 对一条卡在 PENDING 的旧行返回 0 行、什么也不会发生。
     */
    fun restartTranscription() {
        onRetryTranscription?.invoke()
    }

    /** 用户决定「这段不用转写」：把水位追平，生成闸门放行。 */
    fun skipTranscription() {
        val total = state.value.dream?.rawAudioMs ?: return
        viewModelScope.launch { runCatching { repo.skipUncoveredAudio(dreamId, total) } }
    }

    fun generateFaithful() = generate(GenerationKind.FAITHFUL)

    fun generateNovel() = generate(GenerationKind.NOVEL)

    /** 点「小说化」：先开想法对话框，确认后才真正调模型（Plan 4 T5 / 用户需求 ②）。 */
    fun requestNovel() {
        if (_generationPhase.value is GenerationPhase.Running) return
        _novelIdeaPrompt.value = true
    }

    fun dismissNovelIdea() {
        _novelIdeaPrompt.value = false
    }

    /**
     * 对话框确认：想法可选。空白 → `null`（提示词第 4 槽整对删除）；
     * ≤200 字（超出截断，避免把对话框变成小作文）。
     */
    fun confirmNovelIdea(idea: String?) {
        _novelIdeaPrompt.value = false
        val trimmed = idea?.trim()?.take(NOVEL_IDEA_MAX)?.takeIf { it.isNotEmpty() }
        generate(GenerationKind.NOVEL, idea = trimmed)
    }

    /**
     * 失败后「重试」：仍走失败时的同一个入口，不换方法、不新建一份状态。
     * 只看得到 [GenerationPhase.Failed]——运行中/空闲时点它没有意义。
     */
    fun retryGeneration() {
        val failed = _generationPhase.value as? GenerationPhase.Failed ?: return
        generate(failed.kind)
    }

    /**
     * 两个按钮共用的唯一路径。
     *
     * 先在本层拦「转写没完成 / 原文是空的」：这两态没有任何可整理的字，发出去只会白花一次
     * 请求；`GenerationOrchestrator` 的 `generatable` 是第二道闸，不替代这一层。
     * 成功不自己拼结果，只重读版本线（与 Task 9 的改稿走同一条 `refreshConversation`）。
     */
    private fun generate(kind: GenerationKind, idea: String? = null) {
        if (_generationPhase.value is GenerationPhase.Running) return
        val dream = state.value.dream ?: return
        if (dream.transcriptStatus != TranscriptStatus.OK) {
            publishPhase(
                GenerationPhase.Failed(
                    kind,
                    "转写还没完成，先把原文补上再生成",
                ),
            )
            return
        }
        if (dream.rawTranscript.trim().isEmpty()) {
            publishPhase(
                GenerationPhase.Failed(
                    kind,
                    "原文是空的，先补上字再生成",
                ),
            )
            return
        }

        // A3：还有没转成文字的音频时也不许生成——半截原文进模型只会得到残稿。
        val total = dream.rawAudioMs ?: 0L
        if (total > dream.transcriptMs) {
            val seconds = ((total - dream.transcriptMs) / 1000).coerceAtLeast(1)
            publishPhase(
                GenerationPhase.Failed(
                    kind,
                    "还有 $seconds 秒没转成文字，先重试转写或点「这段不用转写」",
                ),
            )
            return
        }
        // 闸门先于 runner 判空：没有容器时也不能把「转写没完成」悄悄吞成 Idle。
        val runner = orchestrator ?: return
        val id = dream.id.ifBlank { dreamId }
        publishPhase(GenerationPhase.Running(kind, 1))
        (generationScope ?: viewModelScope).launch {
            val result = when (kind) {
                GenerationKind.FAITHFUL -> runner.generateFaithful(id) { _, attempt ->
                    onGenerationProgress(kind, attempt)
                }
                GenerationKind.NOVEL -> runner.generateNovel(id, idea) { _, attempt ->
                    onGenerationProgress(kind, attempt)
                }
            }
            val failure = result.failure
            if (failure != null) {
                publishPhase(GenerationPhase.Failed(kind, humanReason(failure)))
            } else {
                refreshConversation()
                runCatching { _costMicroYuan.value = repo.sumCostMicroYuan(id) }
                publishPhase(GenerationPhase.Idle)
            }
        }
    }

    /** 进度只更新同一入口的 `attempt`；已经失败/切走的旧回调不许把状态拉回来。 */
    private fun onGenerationProgress(kind: GenerationKind, attempt: Int) {
        val current = _generationPhase.value
        if (current is GenerationPhase.Running && current.kind == kind) {
            _generationPhase.value = current.copy(attempt = attempt)
        }
    }

    /** 失败文案给人看，不把枚举名甩到界面上（D363）。 */
    private fun humanReason(failure: GenerationFailure): String = when (failure) {
        is GenerationFailure.Llm -> when (failure.kind) {
            LlmException.Kind.AUTH -> "没配 Key 或 Key 不对"
            LlmException.Kind.NETWORK -> "网络没通"
            LlmException.Kind.TIMEOUT -> "等太久了，模型没回"
            LlmException.Kind.RATE_LIMIT -> "请求太密，歇一会儿再试"
            LlmException.Kind.SERVER -> "模型服务那边出错了"
            LlmException.Kind.BAD_REQUEST -> "这次请求模型不认"
            LlmException.Kind.EMPTY_CONTENT -> "模型回了空内容"
            LlmException.Kind.INVALID_JSON -> "模型回的内容读不懂"
        }
        is GenerationFailure.Gate -> "这条梦现在还不能生成：${failure.detail}"
        is GenerationFailure.Protocol -> failure.message
    }

    // ------------------------------------------------------------ 对话改稿（Task 9）

    /**
     * 发一条意见：成功则重读版本线/消息并 +1 ack（UI 清空输入框）；
     * 失败把原因留在 `reviseError`，输入框原文不动（D330）。
     */
    fun revise(opinion: String) {
        val text = opinion.trim()
        if (text.isEmpty()) return
        val revise = revise ?: return
        if (conversation.value.revising) return
        conversation.value = conversation.value.copy(revising = true, reviseError = null)
        viewModelScope.launch {
            val result = revise.revise(dreamId, text)
            refreshConversation()
            val after = conversation.value
            conversation.value = after.copy(
                revising = false,
                reviseError = result.failure?.let(::describeFailure),
                revisionAck = if (result.failure == null) after.revisionAck + 1 else after.revisionAck,
            )
        }
    }

    /** 选中/撤回：只窄更新 `dreams.novelSelectedVersion`，不删任何版本行（D336）。 */
    fun selectVersion(version: Int) {
        viewModelScope.launch {
            runCatching { repo.selectVersion(dreamId, version) }
                .onFailure { e ->
                    conversation.value = conversation.value.copy(
                        reviseError = e.message ?: "选择版本失败",
                    )
                }
            refreshConversation()
        }
    }

    /**
     * 导出到 `Download/DreamKeeper/`（§0.3 / D339）。
     *
     * 正文/标签/版本号全部由 [AndroidExporter] 从仓库现取，和详情页显示同源（§0.4）；
     * 失败保留可读文案（MediaStore 拒绝、磁盘、正文为空等），面板不会假装成功。
     */
    fun export(format: ExportFormat, resolver: ContentResolver) {
        if (_export.value.exporting != null) return
        _export.value = ExportUiState(exporting = format)
        viewModelScope.launch {
            _export.value = runCatching {
                AndroidExporter(repo, resolver).export(dreamId, format)
            }.fold(
                onSuccess = { ExportUiState(saved = it) },
                onFailure = { ExportUiState(error = it.message ?: "导出失败") },
            )
        }
    }

    /** 面板关闭时清空成功/失败提示，下次打开是干净的。 */
    fun resetExport() {
        _export.value = ExportUiState()
    }

    private suspend fun refreshConversation() {
        val versions = runCatching { repo.listVersions(dreamId) }.getOrDefault(emptyList())
        val messages = runCatching { repo.listMessages(dreamId) }.getOrDefault(emptyList())
        val finalNovel = runCatching { repo.resolveFinalNovel(dreamId) }.getOrNull()
        conversation.value = conversation.value.copy(
            versions = versions,
            messages = messages,
            finalNovel = finalNovel,
        )
    }

    private fun describeFailure(failure: GenerationFailure): String = when (failure) {
        is GenerationFailure.Llm -> "模型调用失败（${failure.kind.name}）"
        is GenerationFailure.Gate -> "闸门未过（${failure.failed.joinToString("、")}）：${failure.detail}"
        is GenerationFailure.Protocol -> failure.message
    }

    override fun onCleared() {
        playback?.release()
        super.onCleared()
    }

    private companion object {
        /** 想法长度上限（与 novel.v2.1 的槽说明一致）。 */
        const val NOVEL_IDEA_MAX = 200
    }
}

/**
 * 「这篇梦是不是已经有小说稿」——决定 NovelTab 顶部还要不要露出生成入口。
 *
 * 两条都不能用：
 * - **不能拿界面显示文本判**：`finalNovel` 在没有版本行时会回退 `novelText ?: faithfulText ?:
 *   rawTranscript`（Task 9 展示契约），那样每一条有原文的梦都会被当成「已有稿」。
 * - **不能只看「有没有版本」**：原样整理（faithful 链）成功也会写一条 `promptId = "faithful"`
 *   的版本；把它算成小说稿，用户点一次「原样整理」后就再也找不到「小说化」入口（D394）。
 *
 * 口径：有版本行时看提示词归属（只有 `novel` / `novel_revise` 版本算小说稿）；没有版本行时
 * 退回 Plan 1/2 的老数据（种子/历史记录只有 `dreams.novelText` 与 `novelVersion`）。
 */
internal fun hasNovelVersion(dream: Dream?, versions: List<NovelVersionEntity>): Boolean {
    if (versions.isNotEmpty()) return versions.any { it.promptId != "faithful" }
    return dream?.novelText != null || (dream?.novelVersion ?: 0) > 0
}

/**
 * 「这篇梦是不是已经有原样稿」——决定「原样整理」按钮还该不该露出来（D406）。
 *
 * 为什么需要第二个判据：只按「有没有小说稿」收起整块入口，忠实稿写完之后两枚按钮会
 * 一起重新出现——Task 10 的设备 E2E 实测到了这一点（「原样整理」被点了第二次，多出
 * 一条 v2 忠实版本）。原样整理的语义是「把转写整理成通顺原文」，同一篇梦整理第二遍
 * 只会得到近似重复的版本，所以有过忠实版本就该收起这枚按钮。
 *
 * 判据：有版本行时认 `promptId == "faithful"`；没有版本行时退回老数据的
 * `dreams.faithfulText`（Plan 1/2 的种子与历史记录都只有这一列）。
 */
internal fun hasFaithfulVersion(dream: Dream?, versions: List<NovelVersionEntity>): Boolean {
    if (versions.any { it.promptId == "faithful" }) return true
    return dream?.faithfulText != null

}
