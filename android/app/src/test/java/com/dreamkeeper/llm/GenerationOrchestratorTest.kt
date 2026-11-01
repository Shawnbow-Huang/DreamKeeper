package com.dreamkeeper.llm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.GenerationStore
import com.dreamkeeper.data.RoomGenerationStore
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.DreamEntity
import com.dreamkeeper.data.db.GenerationJobEntity
import com.dreamkeeper.data.db.LlmCallEntity
import com.dreamkeeper.data.db.NovelVersionEntity
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.llm.LlmException.Kind
import com.dreamkeeper.llm.gates.FaithfulGates
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 8 / §0.11 的 8 条验收用例。
 *
 * 只有 #1（成功路径整条落库）与 #8（记账口径）用真 Room；其余用 FakeStore + FakeLlm，
 * 因为要断言的是「编排顺序 / 请求体 / 重试 / 单飞」，不是 SQL。提示词渲染一律用**生产实现**
 * （`PromptRepository` + assets），这样「违规清单真的写进了请求体」才是可证伪的断言——
 * 若换成记录参数的假渲染器，删掉载荷注入的变异也会通过。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerationOrchestratorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prompts: PromptRenderer by lazy {
        PromptRepositoryAdapter(PromptRepository(AssetPromptSource(context)))
    }
    private val priceCard: PriceCard by lazy { AssetPriceCardSource(context).load() }

    /** 真 Room 只在 #1 / #8 里建；其余用例不碰数据库。 */
    private var database: DreamDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
    }

    // ------------------------------------------------------------ §0.11 #1

    @Test
    fun `T0 一次过时提交一次且只记一行成功调用`() = runTest {
        val db = room()
        db.dreamDao().upsert(dreamEntity())

        val llm = FakeLlm().apply { enqueue(ok(CLEAN_T1)) }
        val result = newOrchestrator(llm, RoomGenerationStore(db)).generateFaithful(DREAM_ID)

        assertNull(result.failure)
        assertEquals(GenerationStage.T0, result.finalStage)
        assertEquals(CLEAN_T1, result.text)
        assertEquals(1, result.version)
        assertEquals(1, result.attempts)
        assertTrue(result.costMicroYuan > 0)

        // 请求形态：T0 = temp 0.1、无标记——与「不传 markers 的渲染结果」逐字相等。
        // 不能断言「不含【上一版违规清单】」：提示词正文本身就在解释这两个标记。
        val request = llm.requests.single()
        assertEquals(0.1, request.temperature!!, 1e-9)
        assertEquals(
            prompts.render(PromptSpecs.FAITHFUL.promptId, mapOf("待理顺转写" to RAW)).text,
            request.messages.single().content,
        )

        // 一个事务落两处：Dreams 窄更新 + novel_versions 自增版本。
        val stored = db.dreamDao().getById(DREAM_ID)!!
        assertEquals("T0", stored.generationStage)
        assertEquals(CLEAN_T1, stored.novelText)
        assertEquals(1, db.novelVersionDao().maxVersion(DREAM_ID))

        // 每次 HTTP 交换一行，一次生成一行 job，成功 job 的 error 为 null。
        val calls = db.llmCallDao().listByDream(DREAM_ID)
        assertEquals(1, calls.size)
        assertEquals("FAITHFUL", calls.single().kind)
        assertEquals(1, calls.single().attemptNo)
        assertNull(calls.single().error)
        assertNotNull(calls.single().usageJson)
        val jobs = db.generationJobDao().listByDream(DREAM_ID)
        assertEquals(1, jobs.size)
        assertEquals("T0", jobs.single().stage)
        assertEquals(1, jobs.single().attemptNo)
        assertNull(jobs.single().error)
        assertNull("D320：失败不写、成功才写 stage，但绝不写 novelSelectedVersion", stored.novelSelectedVersion)
    }

    // ------------------------------------------------------------ §0.11 #2

    @Test
    fun `T0 红之后把违规清单回写进 T1 请求并转绿`() = runTest {
        val llm = FakeLlm()
        llm.enqueue(ok(T0_BAD))
        llm.enqueue(ok(CLEAN_T1))
        val store = FakeStore(dreamDomain())

        val result = newOrchestrator(llm, store).generateFaithful(DREAM_ID)

        assertNull(result.failure)
        assertEquals(GenerationStage.T1, result.finalStage)
        assertEquals(CLEAN_T1, result.text)

        // 两次模型调用：T0 失败、T1 成功。
        assertEquals(2, llm.requests.size)

        // T1 的请求体里必须有「上一版违规清单」这个标记载荷，而且载荷里有具体引文（不是开关）。
        val t1Body = llm.requests[1].messages.single().content
        assertTrue("T1 请求体缺少违规清单标记", t1Body.contains("【上一版违规清单】"))
        assertTrue("T1 载荷里没有 T0 的具体引文", t1Body.contains("四十"))
        assertFalse("T1 不是保守档，不该带只做标点的指令", t1Body.contains(PUNCTUATION_DIRECTIVE))
        assertEquals(0.0, llm.requests[1].temperature!!, 1e-9)

        // 只提交一版，档位是 T1。
        assertEquals(1, store.commits.size)
        assertEquals("T1", store.commits.single().stage)
        assertEquals(CLEAN_T1, store.commits.single().draft.text)
        assertEquals("T1", store.jobs.single().stage)
    }

    // ------------------------------------------------------------ §0.11 #3

    @Test
    fun `T1 网络错在同档重试且不降 T2`() = runTest {
        val llm = FakeLlm()
        llm.enqueue(ok(T0_BAD))
        llm.enqueueFailure(LlmException(Kind.NETWORK, null, null, listOf(transportAttempt())))
        llm.enqueue(ok(CLEAN_T1))
        val store = FakeStore(dreamDomain())

        val result = newOrchestrator(llm, store).generateFaithful(DREAM_ID)

        assertNull(result.failure)
        assertEquals(GenerationStage.T1, result.finalStage)
        assertEquals(CLEAN_T1, result.text)
        assertEquals("T0 一次 + T1 失败一次 + T1 重试一次", 3, llm.requests.size)
        assertTrue(result.attempts >= 2)
        // 第三次仍是 T1（同档重试），不是 T2。
        // 同档重试仍带 T0 的具体引文；且不是保守档。
        assertTrue(llm.requests[2].messages.single().content.contains("四十"))
        assertFalse(llm.requests[2].messages.single().content.contains(PUNCTUATION_DIRECTIVE))
        assertEquals("T1", store.commits.single().stage)
    }

    // ------------------------------------------------------------ §0.11 #4

    @Test
    fun `T1 仍红时 T2 只要求逐字等值即可提交`() = runTest {
        val llm = FakeLlm()
        llm.enqueue(ok(T0_BAD))
        llm.enqueue(ok(T1_BAD))
        llm.enqueue(ok(CLEAN_T1)) // 与输入内容字完全相同（只差标点）
        val store = FakeStore(dreamDomain())

        val result = newOrchestrator(llm, store).generateFaithful(DREAM_ID)

        assertNull(result.failure)
        assertEquals(GenerationStage.T2, result.finalStage)
        assertEquals(CLEAN_T1, result.text)
        assertEquals(3, llm.requests.size)

        val t2Body = llm.requests[2].messages.single().content
        assertTrue("T2 必须带只做标点的指令", t2Body.contains(PUNCTUATION_DIRECTIVE))
        assertFalse("T2 不该再带 T1 的违规载荷", t2Body.contains("四十"))
        assertEquals(0.0, llm.requests[2].temperature!!, 1e-9)

        assertEquals("T2", store.commits.single().stage)
        assertTrue(FaithfulGates.isConservativeOk(RAW, CLEAN_T1))
    }

    // ------------------------------------------------------------ §0.11 #5

    @Test
    fun `T2 不等值时整次落 T3 并回退原文`() = runTest {
        val store = FakeStore(dreamDomain())

        // 场景 a：T2 多一个内容字（§0.11 原文向量）。
        val extra = FakeLlm()
        extra.enqueue(ok(T0_BAD))
        extra.enqueue(ok(T1_BAD))
        extra.enqueue(ok(CLEAN_T1 + "嗯"))
        val extraResult = newOrchestrator(extra, store).generateFaithful(DREAM_ID)

        assertNull(extraResult.failure)
        assertEquals(GenerationStage.T3, extraResult.finalStage)
        assertEquals(RAW, extraResult.text)
        assertEquals("T3 是原文回退，没有新版本", null, extraResult.version)
        assertEquals(RAW, extraResult.faithfulText)
        assertEquals("T0/T1/T2 各一次，T3 不调模型", 3, extra.requests.size)
        assertEquals(3, extraResult.attempts)

        // 场景 b：等长但换字（内容字 22→22，「三十」→「四十」）。
        // 这一条才是「逐字相等」与「只比长度」的判别式：只比长度会误判为保守成功。
        val swapped = FakeLlm()
        swapped.enqueue(ok(T0_BAD))
        swapped.enqueue(ok(T1_BAD))
        swapped.enqueue(ok(T2_SUBSTITUTED))
        val swappedResult = newOrchestrator(swapped, store).generateFaithful(DREAM_ID)

        assertNull(swappedResult.failure)
        assertEquals(GenerationStage.T3, swappedResult.finalStage)
        assertEquals(RAW, swappedResult.text)
        assertEquals(3, swapped.requests.size)
        assertTrue("等长换字必须被逐字判据拦住", FaithfulGates.isConservativeOk(RAW, T2_SUBSTITUTED).not())
        assertEquals(RAW.length, T2_SUBSTITUTED.length)

        // T3 不建版本，只写档位（两次都落到 T3）。
        assertTrue(store.commits.isEmpty())
        assertEquals(listOf("T3", "T3"), store.stages)
        assertTrue(store.jobs.all { it.stage == "T3" && it.error == null })
    }

    // ------------------------------------------------------------ §0.11 #6

    @Test
    fun `novel 的 quote 全部找不到时存空数组且正文仍落库`() = runTest {
        val llm = FakeLlm().apply {
            enqueue(ok(novelJson(body = "改写后的正文。", quotes = listOf("不存在的甲", "不存在的乙", "不存在的丙"))))
        }
        val store = FakeStore(dreamDomain())

        val result = newOrchestrator(llm, store).generateNovel(DREAM_ID)

        assertNull(result.failure)
        val commit = store.commits.single()
        assertEquals("[]", commit.changeLogJson)
        assertEquals("改写后的正文。", commit.draft.text)
        assertEquals(0, result.changeLogKept)
        assertEquals(3, result.changeLogDropped)
        assertEquals(1, result.version)
        assertEquals("novel", commit.draft.promptId)
        assertEquals("GENERATE", commit.draft.origin)
        // 小说化提交不动 generationStage（D320）。
        assertNull("NOVEL 提交不应写档位", commit.stage)
    }

    // ------------------------------------------------------------ §0.11 #7

    @Test
    fun `同一 dream 并发时第二个直接 busy 且不发请求`() = runTest {
        val llm = FakeLlm().apply {
            gate = CompletableDeferred()
            enqueue(ok(CLEAN_T1))
        }
        val store = FakeStore(dreamDomain())
        val orchestrator = newOrchestrator(llm, store)

        var first: GenerationResult? = null
        val job = launch { first = orchestrator.generateFaithful(DREAM_ID) }
        runCurrent() // 让第一个协程跑到 FakeLlm 的闸门

        assertEquals(1, llm.requests.size)
        val second = orchestrator.generateFaithful(DREAM_ID)
        val busy = second.failure
        assertTrue("第二个应当是 Protocol 失败，实际 ${second.failure}", busy is GenerationFailure.Protocol)
        assertEquals("busy", (busy as GenerationFailure.Protocol).message)
        assertNull(second.version)

        llm.gate!!.complete(Unit)
        advanceUntilIdle()
        job.join()

        assertEquals("busy 的那次不能发请求", 1, llm.requests.size)
        assertNull(first!!.failure)
        assertEquals(1, store.commits.size)
    }

    // ------------------------------------------------------------ §0.11 #8

    @Test
    fun `每次 HTTP 交换都落一行且失败行不记成本`() = runTest {
        val db = room()
        db.dreamDao().upsert(dreamEntity())

        // 一次 chat() 内部已经发生了 4 次交换（2×429、1 传输错、1×200），
        // ChatResponse.attempts 把它们逐条带回来——编排器必须逐条落 llm_calls。
        val attempts = listOf(
            attempt(1, httpStatus = 429, startedAtMs = NOW, kind = Kind.RATE_LIMIT),
            attempt(2, httpStatus = 429, startedAtMs = NOW + 10, kind = Kind.RATE_LIMIT),
            attempt(3, httpStatus = null, startedAtMs = NOW + 20, kind = Kind.NETWORK),
            attempt(4, httpStatus = 200, startedAtMs = NOW + 30, kind = null),
        )
        val response = ChatResponse(
            content = CLEAN_T1,
            reasoning = null,
            usage = Usage(10, 20, 5, 0, 35),
            requestId = "req-ok",
            httpStatus = 200,
            latencyMs = 44,
            finishReason = "stop",
            attempts = attempts,
        )
        val llm = FakeLlm().apply { enqueue(response) }

        val result = newOrchestrator(llm, RoomGenerationStore(db)).generateFaithful(DREAM_ID)

        assertNull(result.failure)
        assertEquals("4 次交换都要计入 attempts", 4, result.attempts)

        val calls = db.llmCallDao().listByDream(DREAM_ID)
        assertEquals(4, calls.size)

        val failed = calls.filter { it.error != null }
        assertEquals(3, failed.size)
        failed.forEach {
            assertEquals(0L, it.costMicroYuan)
            assertNull(it.usageJson)
            assertNull(it.cacheHitTokens)
            assertNull(it.cacheMissTokens)
            assertNull(it.outputTokens)
            assertEquals("", it.formulaVersion)
            assertNotNull("失败行也要留价卡快照", it.priceSnapshotJson)
        }
        assertEquals(listOf(1, 2, 3), failed.map { it.attemptNo }.sorted())

        val ok = calls.single { it.error == null }
        assertEquals(4, ok.attemptNo)
        assertEquals(200, ok.httpStatus)
        assertEquals("req-ok", ok.requestId)
        assertTrue(ok.costMicroYuan > 0)
        assertEquals("cost-v1", ok.formulaVersion)
        assertEquals("deepseek-flash", ok.model)
        assertEquals(10, ok.cacheHitTokens)
        assertEquals(20, ok.cacheMissTokens)
        assertEquals(5, ok.outputTokens)
        assertEquals("FAITHFUL", ok.kind)

        val usage = JSONObject(ok.usageJson!!)
        assertEquals(30, usage.getInt("prompt_tokens"))
        assertEquals(5, usage.getInt("completion_tokens"))
        assertEquals(35, usage.getInt("total_tokens"))
        assertEquals(10, usage.getInt("prompt_cache_hit_tokens"))
        assertEquals(20, usage.getInt("prompt_cache_miss_tokens"))

        // 一次生成一行 job（4 次交换仍是同一次 T0）。
        val jobs = db.generationJobDao().listByDream(DREAM_ID)
        assertEquals(1, jobs.size)
        assertEquals("T0", jobs.single().stage)
        assertNull(jobs.single().error)
    }

    // ------------------------------------------------------------ Plan 5 T2

    @Test
    fun `小说化默认发思考高且不发 temperature`() = runTest {
        val llm = FakeLlm().apply { enqueue(ok(novelJson(body = "改写后的正文。", quotes = emptyList()))) }
        val store = FakeStore(dreamDomain())

        newOrchestrator(llm, store).generateNovel(DREAM_ID)

        val request = llm.requests.single()
        assertEquals(Thinking.ENABLED, request.thinking)
        assertEquals(ReasoningEffort.HIGH, request.effort)
        assertNull("思考开着时温度必须留空（服务端会忽略它）", request.temperature)
        assertEquals(GenerationOptions.DEFAULT_MODEL, request.model)
    }

    @Test
    fun `小说化档位关掉时退回 disabled 与 0点9 温度`() = runTest {
        val llm = FakeLlm().apply { enqueue(ok(novelJson(body = "改写后的正文。", quotes = emptyList()))) }
        val store = FakeStore(dreamDomain())

        newOrchestrator(llm, store, options = {
            GenerationOptions(model = GenerationOptions.PRO, novelThinking = ThinkingLevel.OFF)
        }).generateNovel(DREAM_ID)

        val request = llm.requests.single()
        assertEquals(GenerationOptions.PRO, request.model)
        assertEquals(Thinking.DISABLED, request.thinking)
        assertNull(request.effort)
        assertEquals(0.9, request.temperature!!, 1e-9)
    }

    @Test
    fun `忠实整理不吃小说化思考档位`() = runTest {
        val llm = FakeLlm().apply { enqueue(ok(CLEAN_T1)) }
        val store = FakeStore(dreamDomain())

        newOrchestrator(llm, store, options = {
            GenerationOptions(model = GenerationOptions.PRO, novelThinking = ThinkingLevel.MAX)
        }).generateFaithful(DREAM_ID)

        val request = llm.requests.single()
        assertEquals(GenerationOptions.PRO, request.model)
        assertEquals(Thinking.DISABLED, request.thinking)
        assertNull(request.effort)
        assertEquals(0.1, request.temperature!!, 1e-9)
    }

    // -------------------------------------------------------------- fixtures

    private fun room(): DreamDatabase = (database ?: Room.inMemoryDatabaseBuilder(
        context,
        DreamDatabase::class.java,
    ).allowMainThreadQueries().build().also { database = it })

    private fun newOrchestrator(
        llm: LlmClient,
        store: GenerationStore,
        onProgress: (GenerationKind, GenerationStage, Int) -> Unit = { _, _, _ -> },
        options: () -> GenerationOptions = { GenerationOptions() },
        clientFor: ((ProviderId) -> LlmClient)? = null,
        apiKeyFor: ((ProviderId) -> String)? = null,
    ): GenerationOrchestrator = GenerationOrchestrator(
        llm = llm,
        prompts = prompts,
        store = store,
        priceCard = priceCard,
        clock = { NOW },
        id = ids(),
        gate = FaithfulGates::check,
        onProgress = onProgress,
        options = options,
        apiKey = { "sk-test-key" },
        clientFor = clientFor ?: { llm },
        apiKeyFor = apiKeyFor,
    )

    /** 每行一个稳定 id，方便断言「哪条记账是哪次交换」。 */
    private fun ids(): () -> String {
        var next = 0
        return { "call-${++next}" }
    }

    private fun ok(content: String, usage: Usage = Usage(10, 20, 5, 0, 35)): ChatResponse =
        ChatResponse(
            content = content,
            reasoning = null,
            usage = usage,
            requestId = "req-ok",
            httpStatus = 200,
            latencyMs = 11,
            finishReason = "stop",
            attempts = listOf(attempt(1, 200, NOW, null)),
        )

    private fun attempt(
        no: Int,
        httpStatus: Int?,
        startedAtMs: Long,
        kind: Kind?,
    ): Attempt = Attempt(no, httpStatus, latencyMs = 5, errorKind = kind, requestId = null, startedAtMs = startedAtMs)

    private fun transportAttempt(): Attempt = attempt(1, null, NOW, Kind.NETWORK)

    // ------------------------------------------------------------ A3：半截原文不许进模型

    @Test
    fun `coverage gate blocks generation when audio tail is not transcribed`() = runTest {
        val db = room()
        db.dreamDao().upsert(
            dreamEntity().copy(rawAudioMs = 3_000L, transcriptMs = 2_400L),
        )
        val llm = FakeLlm()

        val result = newOrchestrator(llm, RoomGenerationStore(db)).generateFaithful(DREAM_ID)

        val failure = result.failure
        assertTrue("必须失败，实际=$result", failure is GenerationFailure.Protocol)
        assertEquals(
            "编排器这一层用统一闸门文案（详情页那一层才说具体几秒）",
            "转写未完成或原文为空",
            (failure as GenerationFailure.Protocol).message,
        )
        assertTrue("被闸门拦住时一次 HTTP 都不许发", llm.requests.isEmpty())
    }

    // ------------------------------------------------------------ Plan 6 T5：按供应商派发 + 成本诚实

    @Test
    fun `generation snapshots provider model and key at start`() = runTest {
        val db = room()
        db.dreamDao().upsert(dreamEntity())
        val deepseek = FakeLlm()
        val qwen = FakeLlm()
        qwen.enqueue(ok(RAW))
        val providerCalls = mutableListOf<ProviderId>()
        val orchestrator = newOrchestrator(
            llm = deepseek,
            store = RoomGenerationStore(db),
            options = { GenerationOptions(provider = ProviderId.QWEN, model = "qwen3.8-max") },
            clientFor = { provider ->
                providerCalls += provider
                if (provider == ProviderId.QWEN) qwen else deepseek
            },
            apiKeyFor = { provider -> if (provider == ProviderId.QWEN) "sk-qwen-key" else "sk-ds-key" },
        )

        val result = orchestrator.generateFaithful(DREAM_ID)
        assertNull(result.failure)
        assertEquals("只该问通义千问", listOf(ProviderId.QWEN), providerCalls)
        assertTrue("DeepSeek client 一次都不该被调用", deepseek.requests.isEmpty())
        val request = qwen.requests.single()
        assertEquals(ProviderId.QWEN, request.provider)
        assertEquals("qwen3.8-max", request.model)
        assertEquals("sk-qwen-key", request.apiKey)
    }

    @Test
    fun `non deepseek success row is marked price missing with zero cost`() = runTest {
        val db = room()
        db.dreamDao().upsert(dreamEntity())
        val qwen = FakeLlm()
        qwen.enqueue(ok(RAW, usage = Usage(10, 20, 5, 0, 35)))
        val orchestrator = newOrchestrator(
            llm = qwen,
            store = RoomGenerationStore(db),
            options = { GenerationOptions(provider = ProviderId.QWEN, model = "qwen3.8-max") },
        )

        val result = orchestrator.generateFaithful(DREAM_ID)
        assertNull(result.failure)
        assertEquals(0L, result.costMicroYuan)

        val row = db.llmCallDao().listByDream(DREAM_ID).single()
        assertEquals("price-missing", row.formulaVersion)
        assertEquals(0L, row.costMicroYuan)
        assertNull("没有价卡就不许假装有快照", row.priceSnapshotJson)
        assertEquals("qwen3.8-max", row.model)
    }

    @Test
    fun `deepseek success row still priced with formula v1`() = runTest {
        val db = room()
        db.dreamDao().upsert(dreamEntity())
        val llm = FakeLlm()
        llm.enqueue(ok(RAW, usage = Usage(10, 20, 5, 0, 35)))
        val orchestrator = newOrchestrator(
            llm = llm,
            store = RoomGenerationStore(db),
            options = { GenerationOptions(provider = ProviderId.DEEPSEEK, model = GenerationOptions.FLASH) },
        )

        val result = orchestrator.generateFaithful(DREAM_ID)
        assertNull(result.failure)
        assertTrue("DeepSeek 有用量就该算钱", result.costMicroYuan > 0)

        val row = db.llmCallDao().listByDream(DREAM_ID).single()
        assertEquals("cost-v1", row.formulaVersion)
        assertTrue(row.costMicroYuan > 0)
        assertNotNull(row.priceSnapshotJson)
    }

    // ------------------------------------------------------------ 真机回归（2026-11-01）

    @Test
    fun `有录音的梦：RoomGenerationStore 必须带回 transcriptMs 否则永远过不了闸门`() = runTest {
        val db = room()
        db.dreamDao().upsert(dreamEntity(rawAudioMs = 92_300, transcriptMs = 92_300))

        val dream = RoomGenerationStore(db).dream(DREAM_ID)!!

        assertEquals("漏映射会让 generatable() 恒假", 92_300L, dream.transcriptMs)
        // 端到端再确认一次：闸门能过，链路正常跑到 T0。
        val llm = FakeLlm().apply { enqueue(ok(CLEAN_T1)) }
        val result = newOrchestrator(llm, RoomGenerationStore(db)).generateFaithful(DREAM_ID)
        assertNull(result.failure)
        assertEquals(1, llm.requests.size)
    }

    private fun dreamEntity(        id: String = DREAM_ID,
        raw: String = RAW,
        faithful: String? = null,
        stage: String? = null,
        rawAudioMs: Long? = null,
        transcriptMs: Long = 0L,
    ): DreamEntity = DreamEntity(
        id = id,
        dreamDate = LocalDate.of(2026, 10, 30),
        createdAt = NOW,
        title = null,
        rawTranscript = raw,
        faithfulText = faithful,
        novelText = null,
        generationStage = stage,
        transcriptStatus = TranscriptStatus.OK,
        rawAudioMs = rawAudioMs,
        transcriptMs = transcriptMs,
    )

    private fun dreamDomain(
        id: String = DREAM_ID,
        raw: String = RAW,
        faithful: String? = null,
    ): Dream = Dream(
        id = id,
        date = LocalDate.of(2026, 10, 30),
        title = null,
        rawTranscript = raw,
        faithfulText = faithful,
        novelText = null,
        novelChangeLog = null,
        mood = null,
        isDraft = false,
        transcriptStatus = TranscriptStatus.OK,
    )

    /**
     * 小说化的四键 JSON。quote 逐条传入，测试用它构造「全部不在 base 里」的场景。
     */
    private fun novelJson(body: String, quotes: List<String>): String = buildString {
        append("{\"title\":\"车站\",\"body\":").append(JSONObject.quote(body))
        append(",\"changeLog\":[")
        quotes.forEachIndexed { index, quote ->
            if (index > 0) append(',')
            append("{\"type\":\"补写\",\"detail\":\"细节").append(index)
                .append("\",\"quote\":").append(JSONObject.quote(quote)).append('}')
        }
        append("],\"mood\":\"怅然\"}")
    }

    // ------------------------------------------------------------ T3 忠实稿基座

    @Test
    fun `忠实链成功时同事务把整理稿写进 dreams_faithfulText`() = runTest {
        val db = room()
        db.dreamDao().upsert(dreamEntity())

        val result = newOrchestrator(FakeLlm().apply { enqueue(ok(CLEAN_T1)) }, RoomGenerationStore(db))
            .generateFaithful(DREAM_ID)

        assertNull(result.failure)
        val stored = db.dreamDao().getById(DREAM_ID)!!
        assertEquals("T3：忠实链成功必须落 dreams.faithfulText", CLEAN_T1, stored.faithfulText)
        assertEquals(CLEAN_T1, stored.novelText)
        assertEquals("T0", stored.generationStage)
        assertEquals(1, db.novelVersionDao().maxVersion(DREAM_ID))
    }

    @Test
    fun `小说化两槽分离且忠实稿为空时回退原始转写`() = runTest {
        // 忠实稿是「理顺后的另一种写法」：与 RAW 语义同、文字不同，便于断言两槽不是同一个值。
        val faithful = "梦里我在车站等车，等了很久车都没来，我数了三十次呼吸才醒。"
        val llm = FakeLlm().apply { enqueue(ok(novelJson(body = "改写后的正文。", quotes = emptyList()))) }
        newOrchestrator(llm, FakeStore(dreamDomain(faithful = faithful))).generateNovel(DREAM_ID)

        val first = llm.requests[0].messages.single().content
        assertTrue("原始槽必须放 rawTranscript 原文", first.contains(RAW))
        assertTrue("理顺槽必须放 faithfulText", first.contains("车都没来"))
        assertTrue("理顺槽不能等于原始转写", !faithful.contains(RAW))

        // 第二跑：faithfulText 为空 → 两槽都回退原文。
        val fallbackLlm = FakeLlm().apply { enqueue(ok(novelJson(body = "回退后的正文。", quotes = emptyList()))) }
        newOrchestrator(fallbackLlm, FakeStore(dreamDomain(faithful = null))).generateNovel(DREAM_ID)
        val second = fallbackLlm.requests[0].messages.single().content
        assertEquals(
            "fallback：两槽同值，原文整段出现两次（原始槽 + 理顺槽）",
            2,
            Regex(Regex.escape(RAW)).findAll(second).count(),
        )
    }

    @Test
    fun `changeLog 引文只在原始转写里也保留`() = runTest {
        val faithful = "梦里我在车站等车，等了很久车都没来，我数了三十次呼吸才醒。"
        val quoteOnlyInRaw = "数着三十次呼吸"
        val llm = FakeLlm().apply {
            enqueue(ok(novelJson(body = "改写后的正文。", quotes = listOf(quoteOnlyInRaw))))
        }
        val store = FakeStore(dreamDomain(faithful = faithful))

        val result = newOrchestrator(llm, store).generateNovel(DREAM_ID)

        assertEquals("引文在双基准里任一命中即保留", 1, result.changeLogKept)
        assertEquals(0, result.changeLogDropped)
        assertTrue(store.commits.single().changeLogJson!!.contains(quoteOnlyInRaw))
    }

    @Test
    fun `changeLog 引文只在忠实稿里也保留`() = runTest {
        val faithful = "梦里我在车站等车，等了很久车都没来，我数了三十次呼吸才醒。"
        val quoteOnlyInFaithful = "车都没来"
        assertFalse("题目要求引文只能在忠实稿里命中", RAW.contains(quoteOnlyInFaithful))
        val llm = FakeLlm().apply {
            enqueue(ok(novelJson(body = "改写后的正文。", quotes = listOf(quoteOnlyInFaithful))))
        }
        val store = FakeStore(dreamDomain(faithful = faithful))

        val result = newOrchestrator(llm, store).generateNovel(DREAM_ID)

        assertEquals(1, result.changeLogKept)
        assertEquals(0, result.changeLogDropped)
        assertTrue(store.commits.single().changeLogJson!!.contains(quoteOnlyInFaithful))
    }

    // ---------------------------------------------------------------- fakes

    /** 只记账、按队列逐次返回的假网络。队列耗尽即失败（漏配调用会立刻暴露）。 */
    private class FakeLlm : LlmClient {
        val requests = mutableListOf<ChatRequest>()
        private val steps = ArrayDeque<suspend () -> ChatResponse>()

        /** 非空时每次 chat 都先等这个闸门——#7 用它把第一次调用卡在锁里。 */
        var gate: CompletableDeferred<Unit>? = null

        fun enqueue(response: ChatResponse) {
            steps.addLast { response }
        }

        fun enqueueFailure(error: LlmException) {
            steps.addLast { throw error }
        }

        override suspend fun chat(req: ChatRequest): ChatResponse {
            requests += req
            gate?.await()
            val step = steps.removeFirstOrNull() ?: error("unexpected llm call #${requests.size}")
            return step()
        }
    }

    private data class Commit(
        val draft: NovelVersionEntity,
        val changeLogJson: String?,
        val stage: String?,
        val faithfulText: String?,
    )

    /** 内存版 GenerationStore：只记录写入了什么，不模拟 SQL。 */
    private class FakeStore(private val dream: Dream?) : GenerationStore {
        val calls = mutableListOf<LlmCallEntity>()
        val jobs = mutableListOf<GenerationJobEntity>()
        val commits = mutableListOf<Commit>()
        val stages = mutableListOf<String>()
        private var nextVersion = 1

        override suspend fun dream(dreamId: String): Dream? = dream

        override suspend fun recordAttempts(calls: List<LlmCallEntity>) {
            this.calls += calls
        }

        override suspend fun recordJob(job: GenerationJobEntity) {
            jobs += job
        }

        override suspend fun commitSuccess(
            dreamId: String,
            draft: NovelVersionEntity,
            changeLogJson: String?,
            stage: String?,
            faithfulTextForDream: String?,
        ): Int {
            commits += Commit(draft, changeLogJson, stage, faithfulTextForDream)
            return nextVersion++
        }

        override suspend fun updateStage(dreamId: String, stage: String) {
            stages += stage
        }

        override suspend fun callCount(dreamId: String): Int = calls.size
    }

    private companion object {
        const val DREAM_ID = "dream-1"
        const val NOW = 1_793_500_000_000L

        /** 22 个内容字 / 21 个 bigram，G3 的分母。 */
        const val RAW = "我梦见车站。等了好久，车一直不来，我数着三十次呼吸。"

        /** T0/T1 的坏输出：把「三十」换成「四十」——G1（数量）与 G3（bigram 覆盖 0.9048）都红。 */
        const val T0_BAD = "我梦见车站。等了好久，车一直不来，我数着四十次呼吸。"

        /** T1 又坏一次，只有「四十」这一处。 */
        const val T1_BAD = "我梦见车站。等了好久，车一直不来，我数着四十次呼吸。"

        /** 干净输出：内容字与 [RAW] 完全相同，只改了标点（故意让 G2/G3 绿）。 */
        const val CLEAN_T1 = "我梦见车站，等了好久，车一直不来，我数着三十次呼吸。"

        /** 等长换字：内容字仍是 22 个，但「三十」变「四十」——逐字判据红、长度判据绿。 */
        const val T2_SUBSTITUTED = "我梦见车站，等了好久，车一直不来，我数着四十次呼吸。"

        /** `PromptRepository` 给【只做标点】自动追加的指令；用它区分「真带了标记」与「正文提到标记」。 */
        const val PUNCTUATION_DIRECTIVE = "本轮按保守档输出"
    }

    @Test
    fun `faithful chain uses its own model while novel uses the novel one`() = runTest {
        val db = room()
        db.dreamDao().upsert(dreamEntity())
        val llm = FakeLlm().apply {
            enqueue(ok(CLEAN_T1))
            enqueue(ok(novelJson(body = "改写后的正文。", quotes = emptyList())))
        }
        val orch = newOrchestrator(
            llm = llm,
            store = RoomGenerationStore(db),
            options = { GenerationOptions(model = "deepseek-v4-pro", faithfulModel = "deepseek-flash") },
        )

        orch.generateFaithful(DREAM_ID)
        assertEquals("deepseek-flash", llm.requests.single().model)

        orch.generateNovel(DREAM_ID)
        assertEquals("deepseek-v4-pro", llm.requests[1].model)

        // 记账行也要写真实使用的模型（faithful 那行不能跟着小说化模型走）。
        val calls = db.llmCallDao().listByDream(DREAM_ID)
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), calls.map { it.model })
    }
}
