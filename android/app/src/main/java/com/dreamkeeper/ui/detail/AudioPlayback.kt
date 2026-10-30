package com.dreamkeeper.ui.detail

import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import com.dreamkeeper.audio.WavMath
import com.dreamkeeper.audio.WavStore
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 详情页播放条的状态（brief §0.1 / plan Task 8）。
 *
 * `Paused` **故意没有** `durationMs`：暂停位置的进度比例一律用调用方传进来的
 * `durationMs`（来自库/字节），否则暂停态和播放态会各算一套总长（§0.5）。
 */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Playing(val positionMs: Long, val durationMs: Long) : PlaybackState
    data class Paused(val positionMs: Long) : PlaybackState
    data class Error(val message: String) : PlaybackState
}

/** 播放条文案：`m:ss.d / m:ss.d`，一位小数才分得清 4.0s 与 4.3s（§0.5）。 */
fun barLabel(state: PlaybackState, durationMs: Long): String = when (state) {
    PlaybackState.Idle -> "0:00.0 / ${formatPlaybackMs(durationMs)}"
    is PlaybackState.Playing -> "${formatPlaybackMs(state.positionMs)} / ${formatPlaybackMs(durationMs)}"
    is PlaybackState.Paused -> "${formatPlaybackMs(state.positionMs)} / ${formatPlaybackMs(durationMs)}"
    is PlaybackState.Error -> state.message.ifBlank { "打不开" }
}

/** 波形填充比例。暂停态没有 durationMs，所以这里只认入参（§0.5）。 */
fun playbackProgress(state: PlaybackState, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    val position = when (state) {
        is PlaybackState.Playing -> state.positionMs
        is PlaybackState.Paused -> state.positionMs
        PlaybackState.Idle, is PlaybackState.Error -> 0L
    }
    return (position.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** 库里的 `rawAudioMs` 与字节时长差超过 100ms 才回写，避免每次播放都写库（§0.12）。 */
fun audioMsNeedsFix(dbMs: Long?, byteMs: Long): Boolean =
    dbMs != null && byteMs > 0L && abs(byteMs - dbMs) > 100L

internal fun formatPlaybackMs(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0L)) / 100L
    val minutes = tenths / 600L
    val seconds = (tenths / 10L) % 60L
    val tenth = tenths % 10L
    return "%d:%02d.%d".format(minutes, seconds, tenth)
}

/**
 * 一条梦的播放器：**先 repair 再 `setDataSource`**（残留的 0xFFFFFFFF 头会让
 * MediaPlayer 把时长算成 0 / 直接打不开）。
 *
 * 线程约定：
 * - `repair` 与 `setDataSource/prepare` 都是磁盘 IO，必须走 [ioDispatcher]（§0.3）；
 * - 状态更新与 MediaPlayer 的回调都在构造它的 scope/主线程上。
 *
 * 生命周期约定：
 * - [release] 之后所有回调静默返回（`released` 闸门），避免「页面已销毁，completion
 *   又把状态改回 Paused」；
 * - 录音中的文件（[WavStore.isOpen]）**绝不 repair**：那会把正在写的文件按当前长度
 *   回填成「已结束」，播到一半自己停（§0.2）。
 */
class AudioPlayback(
    private val scope: CoroutineScope,
    private val store: WavStore,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val playerFactory: () -> MediaPlayer = { MediaPlayer() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** repair 之后解析出的权威时长（字节口径）；0 表示还没准备好。 */
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private var player: MediaPlayer? = null
    private var prepared = false
    private var released = false
    private var duration = 0L
    private var lastPosition = 0L
    private var ticker: Job? = null
    private var prepareJob: Job? = null

    /**
     * 点一下播放条：repair → prepare → start，一步到位。
     *
     * 保留 plan 的 `prepare` 名字（调用方/测试按这个接口写），但语义是「准备好并开始播放」，
     * 这样 UI 一次点击就能响，不需要先 prepare 再 toggle 两次点击。
     */
    fun prepare(file: File, fallbackDurationMs: Long) {
        if (released) return
        prepareJob?.cancel()
        prepareJob = scope.launch {
            prepareInternal(file, fallbackDurationMs)
        }
    }

    /** 播放/暂停。Idle/Error 不在这里启动——首次播放与重试都走 [prepare]。 */
    fun toggle() {
        if (released) return
        when (val current = _state.value) {
            is PlaybackState.Playing -> pause()
            is PlaybackState.Paused -> resume(current)
            else -> Unit
        }
    }

    fun pause() {
        if (released) return
        val current = _state.value
        if (current !is PlaybackState.Playing) return
        val mp = player ?: return
        if (!prepared) return
        val position = readPosition(mp)
        stopTicker()
        runCatching { mp.pause() }
        _state.value = PlaybackState.Paused(position)
        Log.d(TAG, "pause positionMs=$position")
    }

    fun release() {
        released = true
        prepareJob?.cancel()
        prepareJob = null
        stopTicker()
        prepared = false
        val mp = player
        player = null
        if (mp != null) runCatching { mp.release() }
        _state.value = PlaybackState.Idle
        Log.d(TAG, "release")
    }

    private suspend fun prepareInternal(file: File, fallbackDurationMs: Long) {
        stopTicker()
        releasePlayer()
        prepared = false
        lastPosition = 0L
        _durationMs.value = 0L
        _state.value = PlaybackState.Idle

        val repaired = try {
            withContext(ioDispatcher) {
                // 顺序不能反：先判「正在录音」再 repair。反了就会把录音中的文件按当前
                // 长度回填，播放到一半自己结束（brief §0.2）。
                if (store.isOpen(file)) throw RecordingInProgressException()
                val before = runCatching { store.state(file) }.getOrNull()
                Log.d(TAG, "repair start file=${file.name} bytes=${file.length()} before=$before")
                val after = store.repair(file)
                Log.d(TAG, "repair done file=${file.name} after=$after")
                after
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.w(TAG, "prepare failed file=${file.name}", error)
            if (!released) {
                _state.value = PlaybackState.Error(
                    if (error is RecordingInProgressException) "正在录音" else "打不开",
                )
            }
            return
        }

        if (released) return
        val byteMs = WavMath.durationMs(repaired.fileBytes)
        val chosen = if (byteMs > 0L) byteMs else fallbackDurationMs.coerceAtLeast(0L)
        duration = chosen
        Log.d(
            TAG,
            "duration bytes=$byteMs db=$fallbackDurationMs diff=${byteMs - fallbackDurationMs} chosen=$chosen",
        )

        val mp = playerFactory()
        player = mp
        try {
            withContext(ioDispatcher) {
                mp.setDataSource(file.absolutePath)
                mp.prepare()
            }
        } catch (cancel: CancellationException) {
            if (player === mp) player = null
            runCatching { mp.release() }
            throw cancel
        } catch (error: Throwable) {
            Log.w(TAG, "setDataSource/prepare failed file=${file.absolutePath}", error)
            if (player === mp) player = null
            runCatching { mp.release() }
            if (!released) _state.value = PlaybackState.Error("打不开")
            return
        }

        if (released) {
            if (player === mp) player = null
            runCatching { mp.release() }
            return
        }

        mp.setOnCompletionListener {
            if (released) return@setOnCompletionListener
            stopTicker()
            lastPosition = duration
            _state.value = PlaybackState.Paused(duration)
            Log.d(TAG, "completed positionMs=$duration")
        }
        mp.setOnErrorListener { _, what, extra ->
            if (released) return@setOnErrorListener true
            stopTicker()
            _state.value = PlaybackState.Error("打不开")
            Log.w(TAG, "player error what=$what extra=$extra")
            true
        }

        val started = runCatching { mp.start() }
        if (started.isFailure) {
            Log.w(TAG, "start failed", started.exceptionOrNull())
            if (player === mp) player = null
            runCatching { mp.release() }
            if (!released) _state.value = PlaybackState.Error("打不开")
            return
        }

        prepared = true
        _durationMs.value = chosen
        _state.value = PlaybackState.Playing(0L, chosen)
        startTicker()
    }

    private fun resume(paused: PlaybackState.Paused) {
        val mp = player ?: return
        if (!prepared) return
        // 播完后再点：必须先 seekTo(0)，否则 MediaPlayer 停在终点，start() 也不响。
        val restart = duration > 0L && paused.positionMs >= duration
        val started = runCatching {
            if (restart) {
                mp.seekTo(0)
                lastPosition = 0L
            }
            mp.start()
        }
        if (started.isFailure) {
            Log.w(TAG, "resume failed", started.exceptionOrNull())
            _state.value = PlaybackState.Error("打不开")
            return
        }
        _state.value = PlaybackState.Playing(if (restart) 0L else paused.positionMs, duration)
        startTicker()
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive && !released) {
                val mp = player ?: break
                val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                if (!playing) break
                val position = readPosition(mp)
                // pause()/onError 可能刚把状态改掉：这一帧不能再把 Playing 盖回去。
                if (_state.value !is PlaybackState.Playing) break
                _state.value = PlaybackState.Playing(position, duration)
                Log.d(TAG, "t=${clock()} positionMs=$position durationMs=$duration")
                delay(TICK_MS)
            }
        }
    }

    private fun readPosition(mp: MediaPlayer): Long {
        val raw = runCatching { mp.currentPosition.toLong() }.getOrDefault(lastPosition)
        val position = raw.coerceIn(lastPosition, duration.coerceAtLeast(lastPosition))
        lastPosition = position
        return position
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun releasePlayer() {
        stopTicker()
        prepared = false
        val mp = player
        player = null
        if (mp != null) runCatching { mp.release() }
    }

    private class RecordingInProgressException : IllegalStateException("正在录音")

    private companion object {
        const val TAG = "DkPlayback"
        const val TICK_MS = 100L
    }
}
