package com.dreamkeeper.ui.detail

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.FinalNovel
import com.dreamkeeper.data.db.NovelMessageEntity
import com.dreamkeeper.data.db.NovelVersionEntity
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.domain.MediaAsset
import com.dreamkeeper.domain.Tag
import com.dreamkeeper.llm.GenerationFailure
import com.dreamkeeper.llm.GenerationKind
import com.dreamkeeper.llm.GenerationOrchestrator
import com.dreamkeeper.llm.GenerationStage
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
     * 生成入口（Task 8）。默认 null 是为了不破既有单测与预览——没有容器时详情页只读不生成。
     * 不在这里 new 一个全局单例：编排器依赖网络/价卡/数据库，只有 [com.dreamkeeper.di.AppContainer]
     * 知道怎么装配它们。
     */
    private val orchestrator: GenerationOrchestrator? = null,
    /** 对话改稿入口（Task 9）。默认 null 同样只为不破既有两参单测；生产由根容器注入。 */
    private val revise: ReviseOrchestrator? = null,
) : ViewModel() {

    private val dreamId: String = dreamId

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

    // ------------------------------------------------------------ 生成入口（Task 8）

    private val _generation = MutableStateFlow<GenerationPhase>(GenerationPhase.Idle)

    /** 当前生成进度。成功后回到 [GenerationPhase.Idle]——正文已经由 `state` 的 Flow 刷新。 */
    val generation: StateFlow<GenerationPhase> = _generation.asStateFlow()

    fun generateFaithful() = generate(GenerationKind.FAITHFUL)

    fun generateNovel() = generate(GenerationKind.NOVEL)

    /**
     * 同一时刻只允许一条生成链（UI 防重入；真正的并发保护在编排器的 per-dream 锁里）。
     * 失败原因直接映射成用户可读串：Llm 给错误分类，Gate 给没过闸门的具体条目。
     */
    private fun generate(kind: GenerationKind) {
        val orchestrator = orchestrator ?: return
        if (_generation.value is GenerationPhase.Running) return
        _generation.value = GenerationPhase.Running(kind, 1)
        viewModelScope.launch {
            val progress: (GenerationStage, Int) -> Unit = { _, attempt ->
                _generation.value = GenerationPhase.Running(kind, attempt)
            }
            val result = when (kind) {
                GenerationKind.FAITHFUL -> orchestrator.generateFaithful(dreamId, progress)
                GenerationKind.NOVEL -> orchestrator.generateNovel(dreamId, progress)
            }
            _generation.value = when (val failure = result.failure) {
                null -> GenerationPhase.Idle
                is GenerationFailure.Llm -> GenerationPhase.Failed(kind, failure.kind.name)
                is GenerationFailure.Gate -> GenerationPhase.Failed(kind, failure.detail)
                is GenerationFailure.Protocol -> GenerationPhase.Failed(kind, failure.message)
            }
        }
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
}
