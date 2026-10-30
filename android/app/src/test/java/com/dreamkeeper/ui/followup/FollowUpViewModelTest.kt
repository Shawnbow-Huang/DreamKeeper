package com.dreamkeeper.ui.followup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.service.RecordingPhase
import com.dreamkeeper.service.RecordingSession
import com.dreamkeeper.service.RecordingStarter
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 5：30 秒追问窗口。
 *
 * 倒计时只由注入的 `clock()` 驱动，测试里的时钟直接读测试调度器的虚拟时间——
 * 这样 `advanceTimeBy` 推进的是同一个时间源，不需要在断言里重算公式。
 *
 * **禁止 `advanceUntilIdle()`**（§0-9）：ticker 永不 idle，会一直推虚拟时间。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FollowUpViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository
    private lateinit var store: WavStore
    private lateinit var session: RecordingSession
    private lateinit var starter: FakeStarter
    private lateinit var root: File
    private val dreamId = "dream-followup"
    private val baseNow = 1_750_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, DreamDatabase::class.java)
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .allowMainThreadQueries()
            .build()
        repo = DreamRepository(db) { baseNow + dispatcher.scheduler.currentTime }
        root = File(context.cacheDir, "followup-${UUID.randomUUID()}").apply { mkdirs() }
        store = WavStore(root)
        session = RecordingSession()
        starter = FakeStarter(session, store)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    private fun viewModel(windowMs: Long = 30_000L, maxSegmentMs: Long = 120_000L) = FollowUpViewModel(
        repository = repo,
        session = session,
        starter = starter,
        audioStore = store,
        clock = { baseNow + dispatcher.scheduler.currentTime },
        dispatcher = dispatcher,
        windowMs = windowMs,
        maxSegmentMs = maxSegmentMs,
        dreamId = dreamId,
    )

    /** 先落一条 dream（Task 4 的草稿），再放第一段 3 s 录音的字节。 */
    private suspend fun seedDream(rawTranscript: String = "") {
        repo.save(
            Dream(
                id = dreamId,
                date = LocalDate.of(2026, 10, 30),
                title = null,
                rawTranscript = rawTranscript,
                faithfulText = null,
                novelText = null,
                novelChangeLog = null,
                mood = null,
                isDraft = true,
                transcriptStatus = "PENDING",
                rawAudioPath = store.fileFor(dreamId).absolutePath,
                rawAudioMs = 3_000L,
            ),
        )
    }

    /**
     * 造出「第一段已经录完」的落盘状态。字节数刻意选成 `% 32 == 16`：
     * 两次各自 `/32` 相加 = 3000 + 2000 = 5000，而整文件 = 160032 / 32 = 5001 —— 口径不同必须能测出来。
     */
    private fun writeFirstTake(dataBytes: Int = 96_016) {
        val file = store.create(dreamId)
        val writer = store.openWriter(file)
        writer.write(ByteArray(dataBytes), dataBytes)
        writer.finish()
    }

    /** 模拟服务端的第二次 START：markOpen → openWriter 追加 → finish（与 RecordingService 同一条路径）。 */
    private fun appendTake(dataBytes: Int = 64_016) {
        val file = store.ensurePlaceholder(dreamId)
        store.markOpen(file)
        val writer = store.openWriter(file)
        writer.write(ByteArray(dataBytes), dataBytes)
        writer.finish()
    }

    /** 反复跑当前时刻就绪的任务：Room 的 flow 是几跳调度，不用 advanceUntilIdle 也能推进到完成。 */
    private fun TestScope.settle() = repeat(20) { runCurrent() }

    // ---- 5.1 倒计时 ----

    @Test
    fun `initial state is not visible until loaded and starts at the injected window`() = runTest(dispatcher) {
        val vm = viewModel(windowMs = 30_000L)

        // 构造那一刻还没 load：不能先 visible=false 再 loaded=true，否则会「进页即收起」
        assertFalse(vm.state.value.loaded)
        assertFalse(vm.state.value.visible)
        assertEquals(0L, vm.state.value.remainingMs)

        runCurrent()

        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.visible)
        assertEquals("初始必须是构造注入的 windowMs，不许硬编码 30_000", 30_000L, vm.state.value.remainingMs)

        vm.onDoneNow()
        settle()
    }

    @Test
    fun `countdown follows the concrete wall clock numbers and freezes while recording`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        assertEquals(30_000L, vm.state.value.remainingMs)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("墙钟走 5 秒", 25_000L, vm.state.value.remainingMs)

        vm.onSpeakStart()
        runCurrent()
        assertTrue(vm.state.value.recording)
        assertEquals(1, starter.starts.size)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("录音中墙钟再跳 5 秒仍停在 25000（倒计时冻结，不许累加）", 25_000L, vm.state.value.remainingMs)

        vm.onSpeakStop()
        runCurrent()
        assertFalse(vm.state.value.recording)
        assertEquals("松手后从冻结的那一秒继续", 25_000L, vm.state.value.remainingMs)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(20_000L, vm.state.value.remainingMs)

        vm.onDoneNow()
        settle()
    }

    @Test
    fun `timeout at zero collapses, persists the whole file duration and leaves the text alone`() = runTest(dispatcher) {
        seedDream()
        writeFirstTake() // 96_016 bytes = 3000.5 s/32
        appendTake() // +64_016 bytes → 整文件 160_032 → 5001 ms
        val vm = viewModel()
        runCurrent()

        advanceTimeBy(30_000)
        runCurrent()
        settle()

        assertEquals(0L, vm.state.value.remainingMs)
        assertFalse("30 秒到点必须收起", vm.state.value.visible)
        val dream = repo.observe(dreamId).first()!!
        assertEquals("时长按最终整文件算（两次各自 /32 相加会得到 5000）", 5_001L, dream.rawAudioMs)
        assertEquals("PENDING", dream.transcriptStatus)
        assertEquals("Task 5 不许写 rawTranscript 文本", "", dream.rawTranscript)
    }

    @Test
    fun `onDoneNow collapses immediately and persists without waiting for the window`() = runTest(dispatcher) {
        seedDream()
        writeFirstTake()
        val vm = viewModel()
        runCurrent()

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(28_000L, vm.state.value.remainingMs)

        vm.onDoneNow()
        settle()

        assertFalse(vm.state.value.visible)
        val dream = repo.observe(dreamId).first()!!
        assertEquals("只有第一段时整文件 = 3000.5 → 3000", 3_000L, dream.rawAudioMs)
        assertEquals("PENDING", dream.transcriptStatus)
    }

    // ---- 5.1 文本 ----

    @Test
    fun `onEditText changes the transcript without touching the database`() = runTest(dispatcher) {
        seedDream()
        val vm = viewModel()
        runCurrent()

        vm.onEditText("我听见电梯在六楼停了一下")
        settle()

        assertEquals("我听见电梯在六楼停了一下", vm.state.value.transcript)
        assertEquals("编辑文本不落库（Task 7 才定覆盖策略）", "", repo.observe(dreamId).first()!!.rawTranscript)

        vm.onDoneNow()
        settle()
    }

    @Test
    fun `existing transcript is loaded into the state`() = runTest(dispatcher) {
        seedDream(rawTranscript = "刚才那句话")
        val vm = viewModel()
        settle()

        assertEquals("刚才那句话", vm.state.value.transcript)

        vm.onDoneNow()
        settle()
    }

    // ---- 5.2 / §0-3 START 门闩 ----

    @Test
    fun `speak start is only allowed from idle or done`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()

        // IDLE → 允许
        vm.onSpeakStart()
        runCurrent()
        assertEquals(1, starter.starts.size)
        vm.onSpeakStop()
        runCurrent()

        // FINISHING → 忽略并提示
        session.begin(dreamId, store.fileFor(dreamId).absolutePath)
        session.markFinishing()
        vm.onSpeakStart()
        runCurrent()
        assertEquals("FINISHING 时 START 会被服务丢掉，界面不许假装在录", 1, starter.starts.size)
        assertFalse(vm.state.value.recording)
        assertEquals("上一段还在收尾", vm.hint.value)

        // ERROR → 忽略
        session.fail("麦克风读失败")
        vm.onSpeakStart()
        runCurrent()
        assertEquals(1, starter.starts.size)

        // DONE → 允许
        session.cancel()
        session.begin(dreamId, store.fileFor(dreamId).absolutePath)
        session.finish(32_000)
        assertEquals(RecordingPhase.DONE, session.state.value.phase)
        vm.onSpeakStart()
        runCurrent()
        assertEquals(2, starter.starts.size)
        assertTrue(vm.state.value.recording)

        vm.onDoneNow()
        settle()
    }

    @Test
    fun `speak start records into the same file instead of creating a new one`() = runTest(dispatcher) {
        seedDream()
        writeFirstTake()
        val before = store.fileFor(dreamId).length()
        val vm = viewModel()
        runCurrent()

        vm.onSpeakStart()
        settle()

        val wavs = root.listFiles().orEmpty().filter { it.extension == "wav" }
        assertEquals("追问只许追加同一个文件", 1, wavs.size)
        assertEquals(store.fileFor(dreamId), wavs.single())
        assertEquals("START 本身不写音频，长度只由服务端追加改变", before, wavs.single().length())
        assertEquals(store.fileFor(dreamId).absolutePath, session.state.value.filePath)

        vm.onDoneNow()
        settle()
    }

    @Test
    fun `a start failure keeps the countdown running and does not lie about recording`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        starter.failure = IllegalStateException("ForegroundServiceStartNotAllowedException")

        vm.onSpeakStart()
        runCurrent()

        assertFalse(vm.state.value.recording)
        assertEquals(0, starter.starts.size)
        assertTrue(vm.hint.value?.isNotBlank() == true)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("没真正开录，倒计时不能冻住", 25_000L, vm.state.value.remainingMs)

        vm.onDoneNow()
        settle()
    }

    @Test
    fun `service failure while holding releases the frozen countdown`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.onSpeakStart()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(30_000L, vm.state.value.remainingMs)

        session.fail("录音权限被收回")
        runCurrent()
        settle()

        assertFalse("服务先结束不能把界面永远冻在录音态", vm.state.value.recording)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(30_000L - 5_000L, vm.state.value.remainingMs)

        vm.onDoneNow()
        settle()
    }

    // ---- §0-10 120 秒强制收起 ----

    @Test
    fun `holding longer than max segment stops recording and collapses`() = runTest(dispatcher) {
        seedDream()
        writeFirstTake()
        val vm = viewModel(windowMs = 30_000L, maxSegmentMs = 1_000L)
        runCurrent()

        vm.onSpeakStart()
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertTrue(vm.state.value.recording)

        advanceTimeBy(1)
        runCurrent()
        settle()

        assertEquals("到 120 s 上限必须替用户松手", 1, starter.stops)
        assertFalse(vm.state.value.recording)
        assertFalse("到点强制收起", vm.state.value.visible)
        assertTrue(repo.observe(dreamId).first()!!.rawAudioMs != null)
    }

    // ---- helpers ----

    private class FakeStarter(
        private val session: RecordingSession,
        private val store: WavStore,
    ) : RecordingStarter {
        val starts = mutableListOf<String>()
        var stops = 0
        var cancels = 0
        var failure: Throwable? = null

        override fun start(dreamId: String) {
            failure?.let {
                failure = null
                throw it
            }
            starts += dreamId
            // 与真服务同一条落盘路径：已有文件只 markOpen，绝不 create
            val file = store.ensurePlaceholder(dreamId)
            runCatching { store.markOpen(file) }
            session.begin(dreamId, file.path)
        }

        override fun stop() {
            stops++
            session.markFinishing()
        }

        override fun cancel() {
            cancels++
            session.cancel()
        }
    }
}
