package com.dreamkeeper.ui.detail

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.domain.MediaAsset
import com.dreamkeeper.domain.Tag
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    private val dreamId: String = dreamId

    val state: StateFlow<DetailUiState> = combine(
        repo.observe(dreamId),
        repo.observeTagsForDream(dreamId),
    ) { dream, tags ->
        DetailUiState(
            dream = dream,
            tags = tags,
            assets = emptyList(),
            novelChangeCount = novelChangeCount(dream?.novelChangeLog),
            loaded = true,
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

    override fun onCleared() {
        playback?.release()
        super.onCleared()
    }
}
