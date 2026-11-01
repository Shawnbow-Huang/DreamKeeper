package com.dreamkeeper.llm

import com.dreamkeeper.data.GenerationStore
import com.dreamkeeper.data.db.GenerationJobEntity
import com.dreamkeeper.data.db.LlmCallEntity
import com.dreamkeeper.data.db.NovelVersionEntity
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.llm.gates.FaithfulGates
import com.dreamkeeper.llm.gates.Gate
import com.dreamkeeper.llm.gates.GateMode
import com.dreamkeeper.llm.gates.GateReport
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

/**
 * 提示词渲染的可测缝（D315）。
 *
 * 线上实现在 [PromptRepositoryAdapter] 里包 `PromptRepository`；测试用同一个接口记账每次渲染的
 * 槽位/标记/载荷，不碰 assets。返回 [RenderedPrompt] 而不是裸字符串：`promptVersion` /
 * `promptSha256` 要进 `novel_versions`，可追溯「这一版是哪个提示词产出的」。
 */
interface PromptRenderer {
    fun render(
        promptId: String,
        slots: Map<String, String?>,
        markers: Set<PromptMarker> = emptySet(),
        payloads: Map<PromptMarker, String> = emptyMap(),
    ): RenderedPrompt
}

/** 生产适配器：一行转发，参数原样传给 [PromptRepository.render]。 */
class PromptRepositoryAdapter(private val repository: PromptRepository) : PromptRenderer {
    override fun render(
        promptId: String,
        slots: Map<String, String?>,
        markers: Set<PromptMarker>,
        payloads: Map<PromptMarker, String>,
    ): RenderedPrompt = repository.render(promptId, slots, markers, payloads)
}

/**
 * 生成编排器（Plan 3 Task 8）。
 *
 * 两个公共入口，对应设计 §6 的两条独立管线：
 * - [generateFaithful]：忠实整理 T0→T1→T2→T3，产出可入库的整理稿；
 * - [generateNovel]：小说化单次生成，产出正文 + 变更清单。
 *
 * 分开而不是合并成一个 `generate()`：§0.11 的用例按「一次生成 = 一次操作」计
 * `llm_calls` / 模型调用数（T0 一次过 = 1 行、整次落 T3 = 3 次调用），合并会让每条链
 * 都多出另一条链的交换，契约数字直接对不上（详见 task-8-report「偏差候选」）。
 *
 * 构造全注入（D315）：网络、提示词、持久化、价卡、时钟、id、闸门、进度回调都可替换，
 * 因此单测不需要 OkHttp/Keystore，也不需要真 sleep。
 *
 * 线程模型：本类不切调度器。调用方可以在任意 dispatcher 上调；`DeepSeekClient.chat` 用
 * OkHttp `enqueue`（不阻塞线程），Room 的 suspend DAO 自己也切执行器。唯一要注意的是
 * [locks] 里的锁是协程 Mutex，必须在同一线程/事件循环上 `tryLock`（同一 dream 的两个调用
 * 天然在同一 UI scope 上）。
 */
class GenerationOrchestrator(
    private val llm: LlmClient,
    private val prompts: PromptRenderer,
    private val store: GenerationStore,
    private val priceCard: PriceCard,
    private val clock: () -> Long = System::currentTimeMillis,
    private val id: () -> String = { UUID.randomUUID().toString() },
    private val gate: (String, String, GateMode) -> GateReport = FaithfulGates::check,
    private val onProgress: (GenerationKind, GenerationStage, Int) -> Unit = { _, _, _ -> },
    /**
     * 生成档位（Plan 5 Task 2）：每次请求现取，设置页改完立即生效。
     * 默认值 = flash + 小说化思考高。
     */
    private val options: () -> GenerationOptions = { GenerationOptions() },
    private val apiKey: () -> String = { "" },
    /** Plan 6：按供应商取 client；默认全部走注入的 [llm]（DeepSeek 旧行为不变）。 */
    private val clientFor: (ProviderId) -> LlmClient = { llm },
    /** Plan 6：按供应商取 Key；null 时回退 [apiKey]（旧测试与旧调用点零改动）。 */
    private val apiKeyFor: ((ProviderId) -> String)? = null,
    /** 标签名列表（§0.5 的「标签 / 选填」槽）；空列表表示不传这一槽。 */
    private val tagsFor: suspend (String) -> List<String> = { emptyList() },
    /**
     * 小说化 v3 三段提示词。生产由容器注入；null 时退回旧的单次 `novel.v2.1`（旧测试/无 Context 装配）。
     */
    private val novelPrompts: NovelPrompts? = null,
    /** 原样整理成功后自动打标签（生产开、单测默认关，避免旧测试的请求队列被多打一次）。 */
    private val autoTag: Boolean = false,
) : GenerationRunner {
    /** 每个 dream 一把锁：同一梦的两次生成不能交错写 `novel_versions`（§0.9 / D321）。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 忠实整理：T0（temp 0.1，无标记）→ T1（temp 0.0 + 上一版违规清单）→ T2（只做标点）
     * → T3（不调模型，回退原文）。
     *
     * 成功（T0/T1/T2）时把整理稿写成 `novel_versions` 的一版（`origin=GENERATE`）并窄更新
     * `dreams.novelText`——`faithfulText` 列不在允许写的三列里，整理稿若不落 `novel_versions`
     * 就会被丢弃，Task 9 的版本列表也会缺这一版。
     *
     * [onProgress] 是给 UI 的每调用细粒度回调（阶段, 档序号）；构造参数里的同名回调是全局观察点。
     */
    override suspend fun generateFaithful(
        dreamId: String,
        onProgress: ((GenerationStage, Int) -> Unit)?,
    ): GenerationResult {
        val mutex = locks.getOrPut(dreamId) { Mutex() }
        if (!mutex.tryLock()) return protocol(GenerationKind.FAITHFUL, BUSY)
        try {
            // 闸门在锁内重新读行（D319）：不能拿调用方手里的旧快照判断，否则并发的第二次
            // 调用可能带着过期状态进入生成。
            val dream = store.dream(dreamId)
                ?: return protocol(GenerationKind.FAITHFUL, "dream 不存在")
            if (!generatable(dream)) {
                logGateBlocked(dream)
                return protocol(GenerationKind.FAITHFUL, GATE_MESSAGE)
            }
            return faithfulChain(dream, newRun(dreamId), onProgress)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * 小说化：单次生成（temp 0.1 / thinking disabled / jsonMode），输入基座取
     * `dreams.faithfulText`，空则回退 `rawTranscript`（§0.4）。
     *
     * 这是**单次**：失败不重试、不降级（设计 §6 的降级链只属于忠实整理）。
     */
    override suspend fun generateNovel(
        dreamId: String,
        idea: String?,
        onProgress: ((GenerationStage, Int) -> Unit)?,
    ): GenerationResult {
        val mutex = locks.getOrPut(dreamId) { Mutex() }
        if (!mutex.tryLock()) return protocol(GenerationKind.NOVEL, BUSY)
        try {
            val dream = store.dream(dreamId)
                ?: return protocol(GenerationKind.NOVEL, "dream 不存在")
            if (!generatable(dream)) {
                logGateBlocked(dream)
                return protocol(GenerationKind.NOVEL, GATE_MESSAGE)
            }
            return novel(dream, newRun(dreamId), idea, onProgress)
        } finally {
            mutex.unlock()
        }
    }

    // ------------------------------------------------------------ 忠实整理链

    private fun keyFor(provider: ProviderId): String = apiKeyFor?.invoke(provider) ?: apiKey()

    private fun newRun(dreamId: String): Run {
        val cfg = options()
        return Run(dreamId, cfg.provider, cfg.model, cfg.faithfulModel, cfg.faithfulThinking)
    }

    private suspend fun faithfulChain(
        dream: Dream,
        run: Run,
        onProgress: ((GenerationStage, Int) -> Unit)?,
    ): GenerationResult {
        val input = dream.rawTranscript

        // ---- T0：正常整理。温度 0.1 是设计 §6.1 的「正常档」。
        emit(GenerationKind.FAITHFUL, GenerationStage.T0, 1, onProgress)
        val (t0, t0Error) = retrying(run, GenerationKind.FAITHFUL) {
            callFaithful(run, input, temperature = 0.1, markers = emptySet(), payloads = emptyMap())
        }
        if (t0 == null) return llmFailure(GenerationKind.FAITHFUL, GenerationStage.T0, run, t0Error!!)
        if (t0.response.content.isNotBlank() && t0.report.passed) {
            return commitFaithful(run, GenerationStage.T0, t0, input)
        }

        // ---- T1：把 T0 的具体违规项写回提示词。温度 0.0，让模型尽量只做定点修正。
        emit(GenerationKind.FAITHFUL, GenerationStage.T1, 2, onProgress)
        val payload = violationPayload(t0.report)
        val (t1, t1Error) = retrying(run, GenerationKind.FAITHFUL) {
            callFaithful(
                run,
                input,
                temperature = 0.0,
                markers = setOf(PromptMarker.PREVIOUS_VIOLATIONS),
                payloads = mapOf(PromptMarker.PREVIOUS_VIOLATIONS to payload),
            )
        }
        // 同档重试预算用完 = 这次生成失败，不降 T2（§0.3）。
        if (t1 == null) return llmFailure(GenerationKind.FAITHFUL, GenerationStage.T1, run, t1Error!!)
        if (t1.response.content.isNotBlank() && t1.report.passed) {
            return commitFaithful(run, GenerationStage.T1, t1, input)
        }

        // ---- T1b：一次修不干净时，再喂一次 T1 的违规项（同档重试，不降级）。
        // 真机 380 字样本上，模型常在 T1 只修掉一半：T0 丢一个不确定词、T1 修回来又漏掉另一个。
        val payload1b = violationPayload(t1.report)
        val (t1b, t1bError) = retrying(run, GenerationKind.FAITHFUL) {
            callFaithful(
                run,
                input,
                temperature = 0.0,
                markers = setOf(PromptMarker.PREVIOUS_VIOLATIONS),
                payloads = mapOf(PromptMarker.PREVIOUS_VIOLATIONS to payload1b),
            )
        }
        if (t1b != null && t1b.response.content.isNotBlank() && t1b.report.passed) {
            return commitFaithful(run, GenerationStage.T1, t1b, input)
        }
        if (t1bError != null && t1b == null) {
            // 网络/接口硬失败：按 T1 失败处理，继续走 T2 保守档（T2 也会走同一个 retrying）。
            android.util.Log.d("DreamGen", "faithful t1b error: " + t1bError.message)
        }

        // ---- T2：保守档，只允许加标点。判据只有「内容字逐字相等」。
        emit(GenerationKind.FAITHFUL, GenerationStage.T2, 3, onProgress)
        val (t2, t2Error) = retrying(run, GenerationKind.FAITHFUL) {
            callFaithful(
                run,
                input,
                temperature = 0.0,
                markers = setOf(PromptMarker.PUNCTUATION_ONLY),
                payloads = emptyMap(),
            )
        }
        if (t2 == null) return llmFailure(GenerationKind.FAITHFUL, GenerationStage.T2, run, t2Error!!)
        // T2 只在「原文本来就没有口水」时才算改进：输入满是「呃/嗯/那个」时，
        // 只加标点的输出等于退回原文，会把上一版更好的原样稿冲掉（真机 21:10 就发生过）。
        val t2Clean = FaithfulGates.check(input, t2.response.content, GateMode.FAITHFUL)
            .results.first { it.gate == Gate.F3 }.passed
        if (t2Clean && FaithfulGates.isConservativeOk(input, t2.response.content)) {
            return commitFaithful(run, GenerationStage.T2, t2, input)
        }

        // ---- T3：连保守版都不行，整次回退原文，不调模型、不建版本（D319 failure=null）。
        emit(GenerationKind.FAITHFUL, GenerationStage.T3, 4, onProgress)
        store.updateStage(run.dreamId, GenerationStage.T3.name)
        store.recordJob(job(run.dreamId, GenerationStage.T3, 4, error = null))
        return GenerationResult(
            kind = GenerationKind.FAITHFUL,
            finalStage = GenerationStage.T3,
            text = input,
            version = null,
            changeLogKept = 0,
            changeLogDropped = 0,
            attempts = run.attempts,
            costMicroYuan = run.costMicroYuan,
            failure = null,
            faithfulText = input,
        )
    }

    private suspend fun callFaithful(
        run: Run,
        input: String,
        temperature: Double,
        markers: Set<PromptMarker>,
        payloads: Map<PromptMarker, String>,
    ): FaithfulCall {
        val rendered = prompts.render(
            PromptSpecs.FAITHFUL.promptId,
            mapOf(SLOT_TRANSCRIPT to input),
            markers,
            payloads,
        )
        val response = chat(
            run,
            GenerationKind.FAITHFUL,
            ChatRequest(
                model = run.faithfulModel,
                messages = listOf(ChatMessage("user", rendered.text)),
                thinking = run.faithfulThinking.thinking,
                effort = run.faithfulThinking.effort,
                temperature = if (run.faithfulThinking == ThinkingLevel.OFF) temperature else null,
                jsonMode = false,
                apiKey = keyFor(run.provider),
                kind = "FAITHFUL",
                provider = run.provider,
            ),
        )
        val report = gate(input, response.content, GateMode.FAITHFUL)
        if (!report.passed) {
            // 只打闸门代号与命中项：真机校准时不用再猜是哪个词/哪个锚点红了（不打印正文）。
            android.util.Log.d("DreamGen", "faithful gate red: " + report.failed.joinToString(","))
            val detail = report.results.filter { !it.passed }.joinToString(" | ") { r ->
                r.gate.name + "(" + (r.value?.let { "v=" + it } ?: "v=-") + ")" + r.detail.take(4)
            }
            android.util.Log.d("DreamGen", "faithful gate detail: " + detail)
        } else {
            android.util.Log.d("DreamGen", "faithful audit " + homophoneAudit(input, response.content))
        }
        return FaithfulCall(rendered, response, report)
    }


    /**
     * 原样整理成功后的自动打标签：**尽力而为**，任何失败都不能影响整理结果。
     * 一次小调用（thinking 关、temperature 0.2、jsonMode），失败只记日志。
     */
    private suspend fun bestEffortTags(run: Run, faithfulText: String) {
        try {
            val rendered = prompts.render(
                PromptSpecs.TAG.promptId,
                mapOf("原样稿" to faithfulText),
            )
            val response = chat(
                run,
                GenerationKind.TAG,
                ChatRequest(
                    model = run.faithfulModel,
                    messages = listOf(ChatMessage("user", rendered.text)),
                    thinking = Thinking.DISABLED,
                    temperature = 0.2,
                    jsonMode = true,
                    apiKey = keyFor(run.provider),
                    kind = "TAG",
                    provider = run.provider,
                ),
            )
            when (val parsed = com.dreamkeeper.llm.tags.TagExtractor.parse(response.content, faithfulText)) {
                is com.dreamkeeper.llm.tags.TagExtractor.Result.Ok -> {
                    store.saveAutoTags(run.dreamId, parsed.drafts)
                    android.util.Log.d("DreamGen", "tags ok n=" + parsed.drafts.size)
                }
                is com.dreamkeeper.llm.tags.TagExtractor.Result.Bad ->
                    android.util.Log.d("DreamGen", "tags skip " + parsed.reason)
            }
        } catch (e: Exception) {
            android.util.Log.d("DreamGen", "tags failed " + e.javaClass.simpleName)
        }
    }

    private suspend fun commitFaithful(
        run: Run,
        stage: GenerationStage,
        call: FaithfulCall,
        input: String,
    ): GenerationResult {
        // 真机校准时唯一能自证的数：输入几个内容字、输出几个。只打长度，不打正文。
        val inputChars = com.dreamkeeper.llm.gates.FaithfulGates.contentCharCount(input)
        val outputChars = com.dreamkeeper.llm.gates.FaithfulGates.contentCharCount(call.response.content)
        android.util.Log.d("DreamGen", "faithful ok stage=$stage in=$inputChars out=$outputChars")
        val version = store.commitSuccess(
            dreamId = run.dreamId,
            draft = draft(
                dreamId = run.dreamId,
                text = call.response.content,
                promptId = call.rendered.promptId,
                promptVersion = call.rendered.promptVersion,
                promptHash = call.rendered.promptSha256,
                model = run.faithfulModel,
            ),
            changeLogJson = null,
            stage = stage.name,
            faithfulTextForDream = call.response.content,
        )
        store.recordJob(job(run.dreamId, stage, attemptOf(stage), error = null))
        if (autoTag) bestEffortTags(run, call.response.content)
        return GenerationResult(
            kind = GenerationKind.FAITHFUL,
            finalStage = stage,
            text = call.response.content,
            version = version,
            changeLogKept = 0,
            changeLogDropped = 0,
            attempts = run.attempts,
            costMicroYuan = run.costMicroYuan,
            failure = null,
            faithfulText = call.response.content,
        )
    }

    // ---------------------------------------------------------------- 小说化

    /**
     * 三段式小说化：每条 job 一行，阶段失败即整次失败；POLISH 失败回退 DRAFT（NovelChain 负责）。
     */
    private suspend fun novelThreeStage(
        dream: Dream,
        run: Run,
        idea: String?,
        prompts: NovelPrompts,
        onProgress: ((GenerationStage, Int) -> Unit)?,
    ): GenerationResult {
        val raw = dream.rawTranscript
        val base = dream.faithfulText?.takeIf { it.isNotBlank() } ?: raw
        val cfg = options()
        val level = cfg.novelThinking
        val stageNo = mapOf(
            GenerationStage.PLAN to 1,
            GenerationStage.DRAFT to 2,
            GenerationStage.POLISH to 3,
        )
        val chain = NovelChain(
            renderPlan = { r, b, i -> prompts.renderPlan(r, b, i).text },
            renderDraft = { plan, b, i -> prompts.renderDraft(plan, b, i).text },
            renderPolish = { plan, d -> prompts.renderPolish(plan, d).text },
            chat = { stage, prompt ->
                emit(GenerationKind.NOVEL, stage, stageNo.getValue(stage), onProgress)
                chat(
                    run,
                    GenerationKind.NOVEL,
                    ChatRequest(
                        model = run.model,
                        messages = listOf(ChatMessage("user", prompt)),
                        thinking = level.thinking,
                        effort = level.effort,
                        temperature = if (level.effort == null) NOVEL_TEMPERATURE_NO_THINKING else null,
                        jsonMode = true,
                        apiKey = keyFor(run.provider),
                        kind = "NOVEL",
                        provider = run.provider,
                    ),
                ).content
            },
        )
        val result = chain.run(raw, base, idea?.trim()?.takeIf { it.isNotEmpty() })
        val stages = when (result) {
            is NovelChainResult.Success -> result.stages
            is NovelChainResult.Failure -> result.stages
        }
        stages.forEach { stage ->
            store.recordJob(job(run.dreamId, stage, stageNo.getValue(stage), error = null))
        }
        if (result is NovelChainResult.Failure) {
            return protocol(GenerationKind.NOVEL, result.reason)
        }
        result as NovelChainResult.Success
        val notesJson = org.json.JSONArray().apply {
            result.draft.notes.forEach { note ->
                put(org.json.JSONObject().put("type", note.type).put("detail", note.detail))
            }
        }.toString()
        val version = store.commitSuccess(
            dreamId = run.dreamId,
            draft = draft(
                dreamId = run.dreamId,
                text = result.draft.body,
                promptId = "novel_v3",
                promptVersion = "3",
                promptHash = prompts.bundleSha8(),
                model = run.model,
            ),
            changeLogJson = notesJson,
            stage = null,
        )
        return GenerationResult(
            kind = GenerationKind.NOVEL,
            finalStage = GenerationStage.POLISH,
            text = result.draft.body,
            version = version,
            changeLogKept = result.draft.notes.size,
            changeLogDropped = 0,
            attempts = run.attempts,
            costMicroYuan = run.costMicroYuan,
            failure = null,
            faithfulText = base,
            title = result.draft.title,
            mood = result.draft.mood,
        )
    }

    private suspend fun novel(
        dream: Dream,
        run: Run,
        idea: String?,
        onProgress: ((GenerationStage, Int) -> Unit)?,
    ): GenerationResult {
        // v3：三段式（规划→写作→润色）。没有注入三段提示词时仍走下面的旧单次路径。
        novelPrompts?.let { prompts ->
            return novelThreeStage(dream, run, idea, prompts, onProgress)
        }
        emit(GenerationKind.NOVEL, GenerationStage.T0, 1, onProgress)
        // §0.4 两槽分离：【原始转写】永远是 rawTranscript；【理顺稿】优先用忠实链落库的 faithfulText，
        // 没有才回退原文（回退时两槽同值，与 Plan 3 行为一致）。
        val raw = dream.rawTranscript
        val polished = dream.faithfulText?.takeIf { it.isNotBlank() } ?: raw
        val tags = tagsFor(dream.id).takeIf { it.isNotEmpty() }?.joinToString(TAG_SEPARATOR)
        val rendered = prompts.render(
            PromptSpecs.NOVEL.promptId,
            mapOf(
                SLOT_RAW to raw,
                SLOT_POLISHED to polished,
                SLOT_TAGS to tags,
                // T5：空想法传 null → 渲染器整对删槽；非空才送模型（≤200 字由调用方截断）。
                SLOT_IDEA to idea?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )

        // 设计 §9：小说化默认「思考高、不发 temperature」；只有用户把档位调到「关」时
        // 才退回 temperature=0.9（思考开着时服务端会忽略该字段，发出去只会让请求体难以断言）。
        val cfg = options()
        val level = cfg.novelThinking
        val response = try {
            chat(
                run,
                GenerationKind.NOVEL,
                ChatRequest(
                    model = run.model,
                    messages = listOf(ChatMessage("user", rendered.text)),
                    thinking = level.thinking,
                    effort = level.effort,
                    temperature = if (level.effort == null) NOVEL_TEMPERATURE_NO_THINKING else null,
                    jsonMode = true,
                    apiKey = keyFor(run.provider),
                    kind = "NOVEL",
                    provider = run.provider,
                ),
            )
        } catch (e: LlmException) {
            return llmFailure(GenerationKind.NOVEL, GenerationStage.T0, run, e)
        }

        val json = try {
            JSONObject(response.content)
        } catch (e: org.json.JSONException) {
            return protocol(GenerationKind.NOVEL, "响应不是 JSON 对象")
        }
        val body = json.optString("body").trim()
        // body 是唯一必需项：空正文收下只会把 dreams.novelText 清空。
        if (body.isEmpty()) return protocol(GenerationKind.NOVEL, "响应缺少正文")

        val changeLog = filterChangeLog(json.optJSONArray("changeLog"), listOf(polished, raw))
        val version = store.commitSuccess(
            dreamId = run.dreamId,
            draft = draft(
                dreamId = run.dreamId,
                text = body,
                promptId = rendered.promptId,
                promptVersion = rendered.promptVersion,
                promptHash = rendered.promptSha256,
                model = run.model,
            ),
            changeLogJson = changeLog.json,
            // 小说化不动 generationStage（D320）：传 null 让窄 UPDATE 的 COALESCE 保留原档位。
            stage = null,
        )
        store.recordJob(job(run.dreamId, GenerationStage.T0, 1, error = null))
        return GenerationResult(
            kind = GenerationKind.NOVEL,
            finalStage = GenerationStage.T0,
            text = body,
            version = version,
            changeLogKept = changeLog.kept,
            changeLogDropped = changeLog.dropped,
            attempts = run.attempts,
            costMicroYuan = run.costMicroYuan,
            failure = null,
            faithfulText = polished,
            title = json.optString("title").ifBlank { null },
            mood = json.optString("mood").ifBlank { null },
        )
    }

    // ---------------------------------------------------------------- 记账

    /**
     * 一次 HTTP 交换一行（D316）。
     *
     * 成功行照抄 `Usage` 四个 token 列 + 五键 usageJson，成本按价卡原文快照算；
     * 失败行四个 token 列全 null、`usageJson=null`、`costMicroYuan=0`、`formulaVersion=""`，
     * 只留 `error=错误分类`——Task 10 的对账脚本要能跳过它们。
     */
    private suspend fun recordSuccess(run: Run, kind: GenerationKind, response: ChatResponse, model: String) {
        val usage = response.usage
        val attempts = response.attempts.ifEmpty {
            listOf(Attempt(1, response.httpStatus, response.latencyMs, null, response.requestId, clock()))
        }
        // 一次成功的 chat() 内部可能已经重试过：attempts 里失败的那几次也必须各自成行，
        // 否则「每次 HTTP 交换落一行」在成功响应里会漏账（§0.6 / 用例 #8）。
        val rows = attempts.map { attempt ->
            attemptRow(
                run = run,
                kind = kind,
                model = model,
                attempt = attempt,
                usage = usage,
                errorKind = attempt.errorKind,
                requestId = attempt.requestId ?: response.requestId,
            )
        }
        run.attempts += rows.size
        store.recordAttempts(rows)
    }

    private suspend fun recordFailure(run: Run, kind: GenerationKind, error: LlmException, model: String) {
        val attempts = error.attempts.ifEmpty {
            listOf(Attempt(1, error.httpStatus, 0L, error.kind, error.requestId, clock()))
        }
        val rows = attempts.map { attempt ->
            attemptRow(
                run = run,
                kind = kind,
                model = model,
                attempt = attempt,
                usage = null,
                errorKind = attempt.errorKind ?: error.kind,
                requestId = attempt.requestId ?: error.requestId,
            )
        }
        run.attempts += rows.size
        store.recordAttempts(rows)
    }

    /**
     * 一条 `llm_calls` 行。成功/失败共用构造，靠 [errorKind] 分支：
     * 失败行四列 token 全 null、`usageJson=null`、`costMicroYuan=0`、`formulaVersion=""`（D316）。
     */
    private fun attemptRow(
        run: Run,
        kind: GenerationKind,
        model: String,
        attempt: Attempt,
        usage: Usage?,
        errorKind: LlmException.Kind?,
        requestId: String?,
    ): LlmCallEntity {
        val failed = errorKind != null
        // Plan 6：价卡只覆盖 DeepSeek；其他家成功行也「诚实未计价」（D522）。
        val unpriced = run.provider != ProviderId.DEEPSEEK
        val priced = if (failed || usage == null || unpriced) {
            null
        } else {
            price(
                card = priceCard,
                model = model,
                hit = usage.promptCacheHitTokens,
                miss = usage.promptCacheMissTokens,
                out = usage.completionTokens,
                at = Instant.ofEpochMilli(attempt.startedAtMs),
            ) as? Priced.Ok
        }
        val microYuan = priced?.microYuan ?: 0L
        if (!failed) run.costMicroYuan += microYuan
        return LlmCallEntity(
            id = id(),
            dreamId = run.dreamId,
            kind = kind.name,
            model = model,
            cacheHitTokens = if (failed) null else usage?.promptCacheHitTokens?.toInt(),
            cacheMissTokens = if (failed) null else usage?.promptCacheMissTokens?.toInt(),
            outputTokens = if (failed) null else usage?.completionTokens?.toInt(),
            reasoningTokens = if (failed) null else usage?.reasoningTokens?.toInt(),
            isPeak = priced?.isPeak ?: isPeak(Instant.ofEpochMilli(attempt.startedAtMs)),
            priceSnapshotJson = if (unpriced) null else priceCard.rawJson,
            costMicroYuan = microYuan,
            formulaVersion = when {
                failed -> ""
                unpriced -> UNPRICED_FORMULA
                else -> FORMULA_VERSION
            },
            httpStatus = attempt.httpStatus,
            attemptNo = attempt.attemptNo,
            requestId = requestId,
            usageJson = if (failed) null else usage?.let(::usageJson),
            error = errorKind?.name,
            latencyMs = attempt.latencyMs,
            createdAt = attempt.startedAtMs,
        )
    }

    private suspend fun chat(run: Run, kind: GenerationKind, request: ChatRequest): ChatResponse =
        try {
            val response = clientFor(run.provider).chat(request)
            recordSuccess(run, kind, response, request.model)
            response
        } catch (e: LlmException) {
            recordFailure(run, kind, e, request.model)
            throw e
        }

    // ---------------------------------------------------------------- 工具

    private suspend fun retrying(
        run: Run,
        kind: GenerationKind,
        block: suspend () -> FaithfulCall,
    ): Pair<FaithfulCall?, LlmException?> {
        var last: LlmException? = null
        var attempt = 0
        while (attempt < MAX_ATTEMPTS_PER_STAGE) {
            attempt++
            try {
                return block() to null
            } catch (e: LlmException) {
                last = e
                // 不可重试的错误（鉴权/坏请求/空内容/坏 JSON）立刻失败，省一次计费。
                if (!e.kind.isRetryable) return null to e
            }
        }
        return null to last
    }

    private suspend fun llmFailure(
        kind: GenerationKind,
        stage: GenerationStage,
        run: Run,
        error: LlmException,
    ): GenerationResult {
        val failure = GenerationFailure.Llm(error.kind, error.message ?: error.kind.name)
        store.recordJob(job(run.dreamId, stage, attemptOf(stage), describe(failure)))
        return GenerationResult(
            kind = kind,
            finalStage = stage,
            text = "",
            version = null,
            changeLogKept = 0,
            changeLogDropped = 0,
            attempts = run.attempts,
            costMicroYuan = run.costMicroYuan,
            failure = failure,
        )
    }

    private fun protocol(kind: GenerationKind, message: String): GenerationResult = GenerationResult(
        kind = kind,
        finalStage = GenerationStage.T0,
        text = "",
        version = null,
        changeLogKept = 0,
        changeLogDropped = 0,
        attempts = 0,
        costMicroYuan = 0,
        failure = GenerationFailure.Protocol(message),
    )

    private fun emit(
        kind: GenerationKind,
        stage: GenerationStage,
        attempt: Int,
        local: ((GenerationStage, Int) -> Unit)?,
    ) {
        local?.invoke(stage, attempt)
        onProgress(kind, stage, attempt)
    }

    private fun draft(
        dreamId: String,
        text: String,
        promptId: String,
        promptVersion: String,
        promptHash: String,
        model: String,
    ): NovelVersionEntity = NovelVersionEntity(
        id = id(),
        dreamId = dreamId,
        version = 0, // 真实版本号由 insertWithNextVersion 在事务里自增，这里只是占位。
        text = text,
        changeLogJson = null,
        origin = ORIGIN_GENERATE,
        promptId = promptId,
        // Plan 4 T5：版本形如 v2 / v2.1，主版本号进 Int 列（v2.1 -> 2）。
        promptVersion = promptVersion.removePrefix("v").substringBefore('.').toIntOrNull() ?: 0,
        promptHash = promptHash,
        model = model,
        reasoningEffort = null,
        createdAt = clock(),
    )

    private fun job(
        dreamId: String,
        stage: GenerationStage,
        attemptNo: Int,
        error: String?,
    ): GenerationJobEntity = GenerationJobEntity(
        id = id(),
        dreamId = dreamId,
        stage = stage.name,
        attemptNo = attemptNo,
        error = error,
        createdAt = clock(),
    )

    private fun generatable(dream: Dream): Boolean =
        dream.transcriptStatus == TranscriptStatus.OK &&
            dream.rawTranscript.trim().isNotEmpty() &&
            // A3：半截原文不许进模型（rawAudioMs > transcriptMs 说明还有没转成文字的尾巴）。
            (dream.rawAudioMs ?: 0L) <= dream.transcriptMs

    /**
     * 闸门拒绝时把「为什么」打进日志。
     *
     * 真机排查（2026-11-01）时，界面只有一句「转写未完成或原文为空」，
     * 到底是状态没到 OK、正文为空、还是水位没追平，全靠猜；这三个字段都是非隐私元数据。
     */
    private fun logGateBlocked(dream: Dream) {
        android.util.Log.w(
            "DreamGen",
            "gate blocked: status=${dream.transcriptStatus} rawMs=${dream.rawAudioMs} " +
                "transcriptMs=${dream.transcriptMs} textLen=${dream.rawTranscript.length}",
        )
    }

    private fun attemptOf(stage: GenerationStage): Int = stage.ordinal + 1

    private fun describe(failure: GenerationFailure): String = when (failure) {
        is GenerationFailure.Llm -> "${failure.kind.name}: ${failure.message}"
        is GenerationFailure.Gate -> "GATE ${failure.failed.joinToString(",")}: ${failure.detail}"
        is GenerationFailure.Protocol -> "PROTOCOL: ${failure.message}"
    }

    /**
     * 把 [GateReport] 里没过闸门的具体条目拍平成「每条一行」（§0.3）：
     * G1 的引文至多 10 条、G4/G5 至多 6 条、其余闸门的说明/建议至多 12 条。
     *
     * 兜底：万一所有闸门都只回了空 detail（例如输出为空），退回人类可读说明，
     * 因为 `PromptRepository` 对有载荷的标记要求载荷非空（否则直接抛）。
     */
    private fun violationPayload(report: GateReport): String = formatViolations(report)

    private fun unusedViolationPayload(report: GateReport): String {
        val lines = mutableListOf<String>()
        for (result in report.results) {
            if (result.passed) continue
            val picked = when (result.gate) {
                Gate.G1 -> result.detail.take(10)
                Gate.G4, Gate.G5 -> result.detail.take(6)
                Gate.G6 -> result.detail.take(6)
                Gate.F1, Gate.F2, Gate.F3 -> result.detail.take(6)
                Gate.G2, Gate.G3 -> (result.detail + result.advisory).take(12)
            }
            // 带闸门代号：T1 的模型要能直接看出这是 F2 还是 F1，而不是只有一句中文。
            picked.forEach { lines += "${result.gate.name}: $it" }
        }
        if (lines.isEmpty()) {
            report.results.filter { !it.passed }.forEach { lines += it.detail }
        }
        if (lines.isEmpty()) lines += "上一版存在未通过的忠实闸门，请逐字对照原文修正。"
    return lines.joinToString("\n")
    }

    /**
     * 小说化变更清单过滤（§0.5 / D318）：`quote` 必须是基座文本的 4–40 码点**精确子串**。
     *
     * 找不到就丢弃并计数；全部丢光也照样写入 `[]`（不能写成 null，否则「生成成功但没清单」
     * 和「旧版清单」在库里分不开）。
     */
    private fun filterChangeLog(raw: JSONArray?, bases: List<String>): ChangeLog {
        if (raw == null) return ChangeLog("[]", kept = 0, dropped = 0)
        val keptArray = JSONArray()
        var kept = 0
        var dropped = 0
        for (index in 0 until raw.length()) {
            val item = raw.optJSONObject(index)
            val quote = item?.optString("quote").orEmpty()
            val codePoints = quote.codePointCount(0, quote.length)
            if (item != null && codePoints in MIN_QUOTE..MAX_QUOTE && bases.any { it.contains(quote) }) {
                keptArray.put(
                    JSONObject()
                        .put("type", item.optString("type"))
                        .put("detail", item.optString("detail"))
                        .put("quote", quote),
                )
                kept++
            } else {
                dropped++
            }
        }
        return ChangeLog(keptArray.toString(), kept, dropped)
    }

    private fun usageJson(usage: Usage): String = JSONObject()
        .put("prompt_tokens", usage.promptCacheHitTokens + usage.promptCacheMissTokens)
        .put("completion_tokens", usage.completionTokens)
        .put("total_tokens", usage.totalTokens)
        .put("prompt_cache_hit_tokens", usage.promptCacheHitTokens)
        .put("prompt_cache_miss_tokens", usage.promptCacheMissTokens)
        .toString()

    // ---------------------------------------------------------------- 内部类型

    private class Run(
        val dreamId: String,
        /** Plan 6：本次生成用的供应商与模型在开始时快照，避免中途切设置把新值记到旧请求上（D504）。 */
        val provider: ProviderId,
        val model: String,
        /** 忠实整理单独选择的模型；为 null 时不使用，统一走 [model]。 */
        val faithfulModel: String,
        /** 原样整理思考档位（v3）：开思考时不发 temperature。 */
        val faithfulThinking: ThinkingLevel,
    ) {
        var attempts: Int = 0
        var costMicroYuan: Long = 0
    }

    private class FaithfulCall(
        val rendered: RenderedPrompt,
        val response: ChatResponse,
        val report: GateReport,
    )

    private class ChangeLog(val json: String, val kept: Int, val dropped: Int)

    companion object {
        const val DEFAULT_MODEL = GenerationOptions.FLASH

        /** 思考关掉时小说化的温度（设计 §9 的 0.9）。 */
        const val NOVEL_TEMPERATURE_NO_THINKING = 0.9

        /** 同档 HTTP 重试预算：只对 [LlmException.Kind.isRetryable] 生效，用完即失败、不降档。 */
        const val MAX_ATTEMPTS_PER_STAGE = 2

        /** 计价公式版本（Task 10 的 `check-cost.py` 认这个串）。 */
        const val FORMULA_VERSION = "cost-v1"

        /** 非 DeepSeek 供应商没有价卡：成功行也记 0 元，但公式版本写 `price-missing`（D522）。 */
        const val UNPRICED_FORMULA = "price-missing"

        /** `novel_versions.origin`：生成（区别于 Task 9 的改稿 `REVISE`）。 */
        const val ORIGIN_GENERATE = "GENERATE"

        private const val BUSY = "busy"
        private const val GATE_MESSAGE = "转写未完成或原文为空"
        private const val SLOT_TRANSCRIPT = "待理顺转写"
        private const val SLOT_RAW = "原始转写"
        private const val SLOT_POLISHED = "理顺稿"
        private const val SLOT_TAGS = "标签 / 选填"
        private const val SLOT_IDEA = "用户想法 / 选填"
        private const val TAG_SEPARATOR = "、"
        private const val MIN_QUOTE = 4
        private const val MAX_QUOTE = 40
    }
}

/** 仅 debug：列出输出比输入多出来的字（同音改动/新增字），排查过度改字用；不写库。 */
internal fun homophoneAudit(input: String, output: String): String {
    val inCounts = FaithfulGates.contentChars(input).groupingBy { it }.eachCount()
    val surplus = FaithfulGates.contentChars(output).groupingBy { it }.eachCount()
        .flatMap { (ch, c) -> List((c - (inCounts[ch] ?: 0)).coerceAtLeast(0)) { ch } }
    return if (surplus.isEmpty()) "多出字=无" else "多出字=" + surplus.take(8).joinToString("")
}

/** 把闸门失败项排成 T1 的违规清单；带闸门代号，模型能直接看出 F2 还是 F1。 */
internal fun formatViolations(report: GateReport): String {
    val lines = mutableListOf<String>()
    for (result in report.results) {
        if (result.passed) continue
        val picked = when (result.gate) {
            Gate.G1 -> result.detail.take(10)
            Gate.G4, Gate.G5 -> result.detail.take(6)
            Gate.G6 -> result.detail.take(6)
            Gate.F1, Gate.F2, Gate.F3 -> result.detail.take(6)
            Gate.G2, Gate.G3 -> (result.detail + result.advisory).take(12)
        }
        picked.forEach { lines += "${result.gate.name}: $it" }
    }
    return lines.joinToString("\n")
}
