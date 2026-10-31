package com.dreamkeeper.ui.detail

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.llm.GenerationFailure
import com.dreamkeeper.llm.GenerationKind
import com.dreamkeeper.llm.GenerationResult
import com.dreamkeeper.llm.GenerationRunner
import com.dreamkeeper.llm.GenerationStage
import com.dreamkeeper.service.RecordingPhase
import com.dreamkeeper.service.RecordingSession
import com.dreamkeeper.service.RecordingStarter
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 4 T4：追加入口与「追加后自动重新理顺」的六条契约。
 *
 * 用真 Room（in-memory）+ 真 [DreamRepository]，只把 LLM runner 与录音 starter 换成 fake：
 * 自动理顺必须证明「真的读到了水位追平的那一行」，纯 fake repo 测不出这条。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppendEntryTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository
    private lateinit var root: File
    private lateinit var runner: FakeRunner

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, DreamDatabase::class.java)
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .allowMainThreadQueries()
            .build()
        repo = DreamRepository(db) { 1_750_000_000_000L }
        root = File(context.cacheDir, "append-${UUID.randomUUID()}").apply { mkdirs() }
        runner = FakeRunner()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    // 1) 追加模式复用既有 dreamId（不新建梦）
    @Test
    fun `append mode starts recording for the existing dream id`() = runTest(dispatcher) {
        val session = RecordingSession()
        val store = WavStore(root)
        val starter = FakeStarter(session, store)
        val vm = com.dreamkeeper.ui.record.RecordViewModel(
            repository = repo,
            session = session,
            audioStore = store,
            starter = starter,
            hasPermission = { true },
            clock = { 1_750_000_000_000L },
            hasAskedOnce = { true },
            appendDreamId = "seed-dream-3",
        )
        vm.onPressStart(com.dreamkeeper.ui.nav.RecordEntry.HOME_BUTTON)
        runCurrent()
        assertEquals(listOf("seed-dream-3"), starter.startedIds)
    }

    // 2) 转写已追平时，回调后自动跑一次「原样整理」
    @Test
    fun `append recorded polishes once when watermark already caught up`() = runTest(dispatcher) {
        seed(dream("d1", rawAudioMs = 1_000, transcriptMs = 1_000))
        val vm = startVm("d1")
        vm.onAppendRecorded()
        advanceUntilIdle()
        assertEquals(listOf("faithful"), runner.calls)

        // 没有新的追加：再点一次不会重复跑（appendPending 已被消费且状态没变）
        vm.onAppendRecorded()
        advanceUntilIdle()
        assertEquals(listOf("faithful"), runner.calls)

        // 第二次真实追加（水位推进 + 转写落库）后才会再跑一次
        assertEquals(1, repo.beginAppend("d1", 2_000))
        assertEquals(1, repo.appendTranscribed("d1", "\n又想起来一段", 2_000, 1_000).compareTo(false).let { if (it > 0) 1 else 0 })
        advanceUntilIdle()
        vm.onAppendRecorded()
        advanceUntilIdle()
        assertEquals(listOf("faithful", "faithful"), runner.calls)
    }

    // 3) 尾段还没转成文字时不跑（A > T）
    @Test
    fun `no polish while uncovered audio remains`() = runTest(dispatcher) {
        seed(dream("d2", rawAudioMs = 1_000, transcriptMs = 1_000))
        val vm = startVm("d2")
        repo.beginAppend("d2", 3_000)
        advanceUntilIdle()
        vm.onAppendRecorded()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), runner.calls)

        repo.appendTranscribed("d2", "\n补的一段", 3_000, 1_000)
        advanceUntilIdle()
        assertEquals(listOf("faithful"), runner.calls)
    }

    // 4) 生成在飞时不许进追加入口
    @Test
    fun `append is blocked while generation is running`() = runTest(dispatcher) {
        seed(dream("d3", rawAudioMs = 1_000, transcriptMs = 1_000))
        val vm = startVm("d3")
        assertTrue(vm.canAppendNow())
        runner.gate = CompletableDeferred()
        vm.generateFaithful()
        runCurrent()
        assertFalse(vm.canAppendNow())
        runner.gate?.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.canAppendNow())
    }

    // 5) 重试转写把 FAILED 拨回 PENDING
    @Test
    fun `retry transcription flips failed to pending`() = runTest(dispatcher) {
        seed(dream("d4", status = TranscriptStatus.FAILED, rawAudioMs = 2_000, transcriptMs = 1_000))
        val vm = startVm("d4")
        assertEquals(TranscriptStatus.FAILED, repo.findById("d4")?.transcriptStatus)
        vm.retryTranscription()
        advanceUntilIdle()
        assertEquals(TranscriptStatus.PENDING, repo.findById("d4")?.transcriptStatus)
        assertEquals(1_000L, repo.findById("d4")?.transcriptMs)
    }

    // 6) 「这段不用转写」把水位追平
    // 5b) A2：重试转写把行拨回 PENDING 后，必须真的让 Writer 去跑（恰一次）
    @Test
    fun `retry transcription invokes the writer exactly once when it flipped the row`() = runTest(dispatcher) {
        seed(dream("d4b", status = TranscriptStatus.FAILED, rawAudioMs = 2_000, transcriptMs = 1_000))
        var retried = 0
        val vm = DetailViewModel(
            repo,
            "d4b",
            orchestrator = runner,
            onRetryTranscription = { retried += 1 },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        withTimeout(5_000) { vm.state.first { it.loaded && it.dream != null } }

        vm.retryTranscription()
        advanceUntilIdle()
        assertEquals(TranscriptStatus.PENDING, repo.findById("d4b")?.transcriptStatus)
        assertEquals("拨回 PENDING 后必须补跑一次", 1, retried)
    }

    @Test
    fun `skip transcription catches the watermark up`() = runTest(dispatcher) {
        seed(dream("d5", rawAudioMs = 2_000, transcriptMs = 1_000))
        val vm = startVm("d5")
        vm.skipTranscription()
        advanceUntilIdle()
        val row = repo.findById("d5")
        assertEquals(2_000L, row?.transcriptMs)
        assertEquals(TranscriptStatus.OK, row?.transcriptStatus)
    }

    // 7) 生成进度写进跨页进度表（T6b）：请求还没回来时 tracker 必须已是 Running，
    // 否则用户离开再回来会以为没在跑、又能点一次。
    @Test
    fun `generation phase is published to the tracker`() = runTest(dispatcher) {
        seed(dream("d7", rawAudioMs = 1_000, transcriptMs = 1_000))
        val tracker = GenerationTracker()
        runner.gate = CompletableDeferred()
        val vm = DetailViewModel(repo, "d7", orchestrator = runner, tracker = tracker)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        withTimeout(5_000) { vm.state.first { it.loaded && it.dream != null } }
        assertEquals(GenerationPhase.Idle, tracker.phaseFor("d7"))
        vm.generateFaithful()
        advanceUntilIdle()
        assertEquals(GenerationPhase.Running(GenerationKind.FAITHFUL, 1), tracker.phaseFor("d7"))
        runner.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(GenerationPhase.Idle, tracker.phaseFor("d7"))
    }

    // ---------------------------------------------------------------- harness

    private fun dream(
        id: String,
        status: String = TranscriptStatus.OK,
        rawAudioMs: Long = 1_000,
        transcriptMs: Long = 1_000,
    ): Dream = Dream(
        id = id,
        date = LocalDate.of(2026, 10, 1),
        title = "电梯",
        rawTranscript = "我在电梯里，按不到一楼。",
        faithfulText = null,
        novelText = null,
        novelChangeLog = null,
        mood = null,
        isDraft = false,
        transcriptStatus = status,
        rawAudioPath = File(root, "$id.wav").path,
        rawAudioMs = rawAudioMs,
        transcriptMs = transcriptMs,
    )

    private suspend fun seed(value: Dream) {
        repo.save(value)
    }

    private suspend fun TestScope.startVm(dreamId: String): DetailViewModel {
        val vm = DetailViewModel(repo, dreamId, orchestrator = runner)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        withTimeout(5_000) { vm.state.first { it.loaded && it.dream != null } }
        return vm
    }

    private class FakeStarter(
        private val session: RecordingSession,
        private val store: WavStore,
    ) : RecordingStarter {
        val startedIds = mutableListOf<String>()

        override fun start(dreamId: String) {
            startedIds += dreamId
            store.create(dreamId)
            session.begin(dreamId, store.fileFor(dreamId).absolutePath)
        }

        override fun stop() = Unit
        override fun cancel() = Unit
    }

    private class FakeRunner : GenerationRunner {
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun generateFaithful(
            dreamId: String,
            onProgress: ((GenerationStage, Int) -> Unit)?,
        ): GenerationResult = respond(GenerationKind.FAITHFUL)

        override suspend fun generateNovel(
            dreamId: String,
            idea: String?,
            onProgress: ((GenerationStage, Int) -> Unit)?,
        ): GenerationResult = respond(GenerationKind.NOVEL)

        private suspend fun respond(kind: GenerationKind): GenerationResult {
            calls += if (kind == GenerationKind.FAITHFUL) "faithful" else "novel"
            gate?.await()
            return GenerationResult(
                kind = kind,
                finalStage = GenerationStage.T0,
                text = "整理稿",
                version = null,
                changeLogKept = 0,
                changeLogDropped = 0,
                attempts = 0,
                costMicroYuan = 0,
                failure = null as GenerationFailure?,
            )
        }
    }
}
