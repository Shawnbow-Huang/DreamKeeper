package com.dreamkeeper.ui.detail

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.NovelVersionEntity
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.llm.GenerationFailure
import com.dreamkeeper.llm.GenerationKind
import com.dreamkeeper.llm.GenerationResult
import com.dreamkeeper.llm.GenerationRunner
import com.dreamkeeper.llm.GenerationStage
import com.dreamkeeper.llm.LlmException
import java.time.LocalDate
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 9b：生成入口接线（D356 / D363）的三条契约。
 *
 * 为什么测在 ViewModel 缝而不是 Compose 按钮上：本仓库没有（也不许为这个任务新增）
 * compose-ui-test 依赖，Robolectric 的 Compose 测试装不起来。于是把「按钮点什么」
 * 收敛成 `onClick = vm::generateFaithful` / `vm::generateNovel` / `vm::retryGeneration`
 * 三个方法——本文件钉死这三个方法的行为，按钮与它们的绑定由设备截图
 * （`p3-wire-01-buttons.png` / `p3-wire-02-failed.png`）与代码审阅共同证明。
 *
 * 三条用例各自独立建库：`FakeGenerationRunner` 只记录调用，不写任何库。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerationPhaseMappingTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DreamDatabase::class.java,
        )
            // 同 Task 6/7/8：Room 的 executor 必须是测试调度器，否则查询推不动（D53）
            .allowMainThreadQueries()
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .build()
        repo = DreamRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * ① 「原样整理」命中 faithful、「小说化」命中 novel，且都带对的 dreamId；
     * 同时折叠计划 Step 5 的负向：`transcriptStatus != OK` 与空原文都在 ViewModel 就被拦下，
     * runner 一次都不调（这条闸门如果漏了，真会拿空原文去发请求）。
     */
    @Test
    fun `both entries are hit with the dream id`() = runTest(dispatcher) {
        repo.save(dream("seed-dream-1"))
        val runner = FakeGenerationRunner()
        val vm = startVm("seed-dream-1", runner)

        vm.generateFaithful()
        advanceUntilIdle()
        assertEquals(listOf(Call("faithful", "seed-dream-1")), runner.calls)
        assertEquals(GenerationPhase.Idle, vm.generationPhase.value)

        vm.generateNovel()
        advanceUntilIdle()
        assertEquals(
            listOf(Call("faithful", "seed-dream-1"), Call("novel", "seed-dream-1")),
            runner.calls,
        )

        // 负向一：转写未完成（PENDING）。
        repo.save(dream("dream-pending", status = TranscriptStatus.PENDING))
        val pendingRunner = FakeGenerationRunner()
        val pendingVm = startVm("dream-pending", pendingRunner)
        pendingVm.generateFaithful()
        advanceUntilIdle()
        assertTrue(pendingRunner.calls.isEmpty())
        assertEquals(
            GenerationPhase.Failed(GenerationKind.FAITHFUL, "转写还没完成，先把原文补上再生成"),
            pendingVm.generationPhase.value,
        )

        // 负向二：原文只有空白。
        repo.save(dream("dream-blank", raw = "   "))
        val blankRunner = FakeGenerationRunner()
        val blankVm = startVm("dream-blank", blankRunner)
        blankVm.generateNovel()
        advanceUntilIdle()
        assertTrue(blankRunner.calls.isEmpty())
        assertEquals(
            GenerationPhase.Failed(GenerationKind.NOVEL, "原文是空的，先补上字再生成"),
            blankVm.generationPhase.value,
        )
    }

    /**
     * ② 三态映射：`Idle → Running(kind, attempt) → Idle`；失败落 `Failed(kind, 人话)`。
     *
     * `attempt` 取 orchestrator `onProgress` 传来的档位序号（T0=1…T3=4），不是 HTTP 重试次数；
     * 这里让假 runner 回调 `T1, 2`，断言 UI 能看到「第 2 步」而不是永远停在 1。
     */
    @Test
    fun `phase maps idle running and failed`() = runTest(dispatcher) {
        repo.save(dream("seed-dream-1"))
        val runner = FakeGenerationRunner()
        val gate = CompletableDeferred<Unit>()
        runner.gate = gate
        runner.progress = GenerationStage.T1 to 2
        val vm = startVm("seed-dream-1", runner)

        vm.generateNovel()
        runCurrent()
        assertEquals(GenerationPhase.Running(GenerationKind.NOVEL, 2), vm.generationPhase.value)

        runner.progress = null
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(GenerationPhase.Idle, vm.generationPhase.value)

        // 没配 Key 时真实链路会在 401 处返回 Llm(AUTH)；UI 必须给人话，不是枚举名。
        runner.failure = GenerationFailure.Llm(LlmException.Kind.AUTH, "401")
        vm.generateNovel()
        advanceUntilIdle()
        assertEquals(
            GenerationPhase.Failed(GenerationKind.NOVEL, "没配 Key 或 Key 不对"),
            vm.generationPhase.value,
        )
    }

    /**
     * ③ 失败后「重试」命中同一入口的**第二次调用**：novel 失败 → 重试仍是 novel，
     * dreamId 不变。把 `retryGeneration()` 改成另一入口或只改状态，这条立刻红。
     */
    @Test
    fun `retry after failure calls the same entry again`() = runTest(dispatcher) {
        repo.save(dream("seed-dream-1"))
        val runner = FakeGenerationRunner()
        runner.failure = GenerationFailure.Protocol("网络没通")
        val vm = startVm("seed-dream-1", runner)

        vm.generateNovel()
        advanceUntilIdle()
        assertEquals(
            GenerationPhase.Failed(GenerationKind.NOVEL, "网络没通"),
            vm.generationPhase.value,
        )

        runner.failure = null
        vm.retryGeneration()
        advanceUntilIdle()
        assertEquals(
            listOf(Call("novel", "seed-dream-1"), Call("novel", "seed-dream-1")),
            runner.calls,
        )
        assertEquals(GenerationPhase.Idle, vm.generationPhase.value)
    }

    // ------------------------------------------------ 9c：生成入口可见性口径（D394）

    /**
     * 原样整理成功会写一条 `promptId = "faithful"` 的版本；它**不算**小说稿，
     * 否则用户点完「原样整理」就再也找不到「小说化」入口。
     */
    @Test
    fun `faithful only version still offers the novel entry`() {
        val d = dream("seed-dream-4")
        assertEquals(false, hasNovelVersion(d, listOf(version(1, "faithful"))))
    }

    /** 小说化/改稿版本一出现，生成入口就该收起。 */
    @Test
    fun `novel or revise version hides the generation entries`() {
        val d = dream("seed-dream-4")
        assertTrue(hasNovelVersion(d, listOf(version(1, "novel"))))
        assertTrue(
            hasNovelVersion(
                d,
                listOf(version(1, "faithful"), version(2, "novel_revise")),
            ),
        )
    }

    /** Plan 1/2 的老数据没有版本行：退回 `novelText` / `novelVersion` 判。 */
    @Test
    fun `legacy dream without version rows falls back to novelText`() {
        val raw = dream("seed-dream-4")
        assertEquals(false, hasNovelVersion(raw, emptyList()))
        assertTrue(hasNovelVersion(raw.copy(novelText = "旧稿"), emptyList()))
        assertTrue(hasNovelVersion(raw.copy(novelVersion = 3), emptyList()))
    }

    // ---------------------------------------------- 9d：两枚按钮各自收起（D406）

    /** 原样稿已存在（faithful 版本行）时，「原样整理」不该再出现——否则会整理出重复版本。 */
    @Test
    fun `faithful version hides the faithful entry`() {
        val d = dream("seed-dream-4")
        assertTrue(hasFaithfulVersion(d, listOf(version(1, "faithful"))))
    }

    /** 只有小说/改稿版本、没有原样稿时，「原样整理」仍应出现（用户可能先要一版通顺原文）。 */
    @Test
    fun `novel version does not hide the faithful entry`() {
        val d = dream("seed-dream-4").copy(faithfulText = null)
        assertEquals(false, hasFaithfulVersion(d, listOf(version(1, "novel"))))
        assertTrue(hasNovelVersion(d, listOf(version(1, "novel"))))
    }

    /** 老数据没有版本行：`dreams.faithfulText` 非空即视为整理过；纯转写仍可整理。 */
    @Test
    fun `legacy faithfulText falls back to the dream column`() {
        val raw = dream("seed-dream-4").copy(faithfulText = null)
        assertEquals(false, hasFaithfulVersion(raw, emptyList()))
        assertTrue(hasFaithfulVersion(raw.copy(faithfulText = "整理过的原文"), emptyList()))
    }

    // ---------------------------------------------------------------- fixtures

    private fun version(no: Int, promptId: String): NovelVersionEntity = NovelVersionEntity(
        id = "v-$no-$promptId",
        dreamId = "seed-dream-4",
        version = no,
        text = "稿 $no",
        changeLogJson = if (promptId == "faithful") null else "[]",
        origin = "GENERATE",
        promptId = promptId,
        promptVersion = 2,
        promptHash = "hash",
        model = "deepseek-flash",
        createdAt = 0L,
    )

    private fun dream(
        id: String,
        status: String = TranscriptStatus.OK,
        raw: String = "我在电梯里，电梯一直往上，按不到一楼。",
    ): Dream = Dream(
        id = id,
        date = LocalDate.of(2026, 10, 1),
        title = "电梯",
        rawTranscript = raw,
        faithfulText = raw,
        novelText = null,
        novelChangeLog = null,
        mood = null,
        isDraft = false,
        transcriptStatus = status,
    )

    /**
     * 建 VM 并等首帧：`state` 是 `WhileSubscribed`，没人订阅时上游不启动，`state.value.dream`
     * 会一直是 null，ViewModel 的入口闸门就会把一切请求都误判成「梦还没读出来」。
     */
    private suspend fun TestScope.startVm(dreamId: String, runner: GenerationRunner): DetailViewModel {
        val vm = DetailViewModel(repo, dreamId, orchestrator = runner)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        withTimeout(5_000) { vm.state.first { it.loaded && it.dream != null } }
        return vm
    }

    private data class Call(val entry: String, val dreamId: String)

    /** 只记录「哪个入口、哪条梦」；可选地卡住（观察 Running）、回调进度、返回失败。 */
    private class FakeGenerationRunner : GenerationRunner {
        val calls = mutableListOf<Call>()
        var failure: GenerationFailure? = null
        var progress: Pair<GenerationStage, Int>? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun generateFaithful(
            dreamId: String,
            onProgress: ((GenerationStage, Int) -> Unit)?,
        ): GenerationResult {
            calls += Call("faithful", dreamId)
            return respond(GenerationKind.FAITHFUL, onProgress)
        }

        override suspend fun generateNovel(
            dreamId: String,
            onProgress: ((GenerationStage, Int) -> Unit)?,
        ): GenerationResult {
            calls += Call("novel", dreamId)
            return respond(GenerationKind.NOVEL, onProgress)
        }

        private suspend fun respond(
            kind: GenerationKind,
            onProgress: ((GenerationStage, Int) -> Unit)?,
        ): GenerationResult {
            progress?.let { (stage, attempt) -> onProgress?.invoke(stage, attempt) }
            gate?.await()
            return GenerationResult(
                kind = kind,
                finalStage = GenerationStage.T0,
                text = if (failure == null) "整理稿" else "",
                version = null,
                changeLogKept = 0,
                changeLogDropped = 0,
                attempts = 0,
                costMicroYuan = 0,
                failure = failure,
            )
        }
    }
}
