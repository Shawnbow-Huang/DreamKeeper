package com.dreamkeeper.ui.detail

import android.media.MediaPlayer
import com.dreamkeeper.audio.WavMath
import com.dreamkeeper.audio.WavStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/**
 * Task 8 / brief §0.1 §0.2 §0.9 §0.12：播放状态机的纯单测。
 *
 * 这里**绝不**构造真的 `MediaPlayer`：播放器由 `playerFactory` 注入
 * [FakeMediaPlayer]，否则 JVM 单测会在 native 层直接炸，或者悄悄依赖 Robolectric 的
 * shadow 行为。`clock` 与 `ioDispatcher` 也都注入，波形/进度的每一次推进都可复现。
 *
 * 为什么用 Robolectric：`AudioPlayback` 的字段类型是 `android.media.MediaPlayer`，
 * 纯 JVM 下连类加载都过不去；`@Config(sdk = 34)` 与 `DetailViewModelTest` 保持一致。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioPlaybackStateTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var root: File
    private lateinit var store: WavStore
    private var now = 0L
    private val players = mutableListOf<FakeMediaPlayer>()
    private var factoryCalls = 0

    @Before
    fun setUp() {
        root = File.createTempFile("dk-play-", "").let { tmp ->
            tmp.delete()
            tmp.mkdirs()
            tmp
        }
        store = WavStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---- 0.12：时长只认字节；repair 必须在播放前发生 ----

    @Test
    fun `prepare repairs a dirty header and reports duration from bytes`() = runTest(dispatcher) {
        val file = wav(1_234)
        dirtyHeader(file)
        val playback = newPlayback(backgroundScope)

        playback.prepare(file, fallbackDurationMs = 4_300)
        runCurrent()

        val repaired = store.state(file)
        assertFalse("repair 后不该再有占位尺寸", repaired.placeholder)
        assertEquals(file.length() - WavMath.HEADER_BYTES, repaired.dataSize)
        assertEquals("时长必须取 (fileBytes-44)/32，而不是库里的 rawAudioMs", 1_234L, playback.durationMs.value)

        val state = playback.state.value
        assertTrue("第一次播放应直接进 Playing，实际 $state", state is PlaybackState.Playing)
        assertEquals(1_234L, (state as PlaybackState.Playing).durationMs)
        assertEquals(1, factoryCalls)
        assertEquals(file.absolutePath, players.single().lastDataSource)
        playback.release()
    }

    @Test
    fun `prepare never repairs while the recorder still has the file open`() = runTest(dispatcher) {
        val file = wav(2_000)
        dirtyHeader(file)
        store.markOpen(file)
        val playback = newPlayback(backgroundScope)

        playback.prepare(file, fallbackDurationMs = 2_000)
        runCurrent()

        val state = playback.state.value
        assertTrue("录音中必须直接报错，实际 $state", state is PlaybackState.Error)
        assertEquals("正在录音", (state as PlaybackState.Error).message)
        assertEquals("录音中的文件不许被 repair 回填", 0xFFFF_FFFFL, store.state(file).dataSize)
        assertEquals("录音中不创建播放器", 0, factoryCalls)
        playback.release()
    }

    @Test
    fun `missing file becomes a retryable error instead of a crash`() = runTest(dispatcher) {
        val playback = newPlayback(backgroundScope)

        playback.prepare(File(root, "missing.wav"), fallbackDurationMs = 1_000)
        runCurrent()

        val state = playback.state.value
        assertTrue("缺文件应是 Error，实际 $state", state is PlaybackState.Error)
        assertEquals("打不开", (state as PlaybackState.Error).message)
        assertEquals(0, factoryCalls)
        playback.release()
    }

    // ---- 0.11：一次播放内位置单调；播完 toggle 允许 seekTo(0) 那一次回跳 ----

    @Test
    fun `position is monotonic within one play run`() = runTest(dispatcher) {
        val playback = newPlayback(backgroundScope)
        playback.prepare(wav(5_000), fallbackDurationMs = 5_000)
        runCurrent()
        val player = players.single()

        player.position = 3_000
        advanceTimeBy(100)
        runCurrent()
        assertEquals(3_000L, (playback.state.value as PlaybackState.Playing).positionMs)

        // 播放器回吐一个更小的位置（seek/缓冲抖动）：同一个 run 里不许画回去
        player.position = 1_200
        advanceTimeBy(100)
        runCurrent()
        assertEquals(3_000L, (playback.state.value as PlaybackState.Playing).positionMs)

        playback.release()
    }

    @Test
    fun `pause keeps the position and resume continues from it`() = runTest(dispatcher) {
        val playback = newPlayback(backgroundScope)
        playback.prepare(wav(5_000), fallbackDurationMs = 5_000)
        runCurrent()
        val player = players.single()
        player.position = 2_400
        advanceTimeBy(100)
        runCurrent()

        playback.toggle()
        runCurrent()
        assertEquals(PlaybackState.Paused(2_400), playback.state.value)

        playback.toggle()
        runCurrent()
        assertEquals(PlaybackState.Playing(2_400, 5_000), playback.state.value)
        assertTrue("中途 resume 不该 seekTo(0)", player.seekTos.isEmpty())

        playback.release()
    }

    @Test
    fun `completion becomes paused at the end and replay seeks to zero exactly once`() = runTest(dispatcher) {
        val playback = newPlayback(backgroundScope)
        playback.prepare(wav(1_000), fallbackDurationMs = 1_000)
        runCurrent()
        val player = players.single()

        player.complete()
        assertEquals(PlaybackState.Paused(1_000), playback.state.value)

        playback.toggle()
        runCurrent()
        assertEquals(PlaybackState.Playing(0, 1_000), playback.state.value)
        assertEquals(listOf(0), player.seekTos)

        playback.release()
    }

    // ---- 0.9：release 之后回调不许再改状态 ----

    @Test
    fun `release stops completion callbacks from touching state`() = runTest(dispatcher) {
        val playback = newPlayback(backgroundScope)
        playback.prepare(wav(1_000), fallbackDurationMs = 1_000)
        runCurrent()
        val player = players.single()

        playback.release()
        assertEquals(PlaybackState.Idle, playback.state.value)

        player.complete()
        assertEquals("release 后 completion 必须被忽略", PlaybackState.Idle, playback.state.value)
        assertTrue(player.released)
    }

    // ---- 0.4 / 0.5：纯标签与进度，Paused 没有 durationMs，一律用入参 ----

    @Test
    fun `bar label shows tenths and uses the supplied duration for paused`() {
        assertEquals("0:00.0 / 0:04.3", barLabel(PlaybackState.Idle, 4_300))
        assertEquals("0:04.0 / 0:04.3", barLabel(PlaybackState.Paused(4_000), 4_300))
        assertEquals("0:03.5 / 0:04.3", barLabel(PlaybackState.Playing(3_500, 4_300), 4_300))
        assertEquals("打不开", barLabel(PlaybackState.Error("打不开"), 4_300))
    }

    @Test
    fun `playback progress uses the duration argument and clamps`() {
        assertEquals(0.5f, playbackProgress(PlaybackState.Paused(2_150), 4_300), 0.001f)
        assertEquals(0f, playbackProgress(PlaybackState.Idle, 4_300), 0.001f)
        assertEquals(1f, playbackProgress(PlaybackState.Paused(9_999), 4_300), 0.001f)
    }

    @Test
    fun `audio ms needs fixing only when the byte duration differs by more than 100ms`() {
        assertFalse(audioMsNeedsFix(dbMs = null, byteMs = 4_300))
        assertFalse(audioMsNeedsFix(dbMs = 4_300, byteMs = 0))
        assertFalse(audioMsNeedsFix(dbMs = 4_300, byteMs = 4_200))
        assertFalse(audioMsNeedsFix(dbMs = 4_300, byteMs = 4_400))
        assertTrue(audioMsNeedsFix(dbMs = 2_900, byteMs = 4_300))
    }

    // ---- helpers ----

    private fun newPlayback(scope: CoroutineScope): AudioPlayback = AudioPlayback(
        scope = scope,
        store = store,
        clock = { now },
        playerFactory = {
            factoryCalls++
            FakeMediaPlayer().also { players += it }
        },
        ioDispatcher = dispatcher,
    )

    private fun wav(ms: Long): File {
        val file = File(root, "dream-${players.size}-${System.nanoTime()}.wav")
        val bytes = ByteArray((WavMath.HEADER_BYTES + ms * WavMath.BYTES_PER_MS).toInt())
        file.writeBytes(bytes)
        return file
    }

    private fun dirtyHeader(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            repeat(2) { index ->
                raf.seek(if (index == 0) 4L else 40L)
                raf.write(byteArrayOf(-1, -1, -1, -1))
            }
        }
    }

    private class FakeMediaPlayer : MediaPlayer() {
        var lastDataSource: String? = null
        var prepared = false
        var playing = false
        var released = false
        var position = 0
        var completion: MediaPlayer.OnCompletionListener? = null
        val seekTos = mutableListOf<Int>()

        override fun setDataSource(path: String) {
            lastDataSource = path
        }

        override fun prepare() {
            prepared = true
        }

        override fun start() {
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun isPlaying(): Boolean = playing

        override fun getCurrentPosition(): Int = position

        override fun seekTo(msec: Int) {
            seekTos += msec
            position = msec
        }

        override fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) {
            completion = listener
        }

        override fun release() {
            released = true
            playing = false
        }

        fun complete() {
            playing = false
            completion?.onCompletion(this)
        }
    }
}
