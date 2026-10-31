package com.dreamkeeper.llm

import com.dreamkeeper.data.GenerationStore
import com.dreamkeeper.data.db.NovelMessageEntity
import com.dreamkeeper.data.db.NovelVersionEntity
import com.dreamkeeper.di.AppContainer
import com.dreamkeeper.llm.gates.FaithfulGates
import com.dreamkeeper.llm.gates.GateMode
import com.dreamkeeper.llm.gates.GateReport
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 对话改稿编排器（Task 9 §0.1–§0.5）。
 *
 * 与 Task 8 的 [GenerationOrchestrator] 同一注入风格：LLM、提示词渲染、存储、时钟、id、
 * 闸门全部可替换，单测只 fake [LlmClient]。
 *
 * 同时它是详情页唯一的「生成 + 改稿」门面：D352 要求 `MainActivity` 只传
 * `container.orchestrator`，而容器里现有的是 [GenerationOrchestrator]，所以这里透传
 * [generateFaithful]/[generateNovel]；装配见文件末尾的 [orchestrator] 扩展属性。
 */
class ReviseOrchestrator(
    private val llm: LlmClient,
    private val prompts: PromptRenderer,
    private val store: GenerationStore,
    /** Task 8 的生成链；单测只测改稿时为 null。 */
    val generation: GenerationOrchestrator? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val id: () -> String = { UUID.randomUUID().toString() },
    private val gate: (String, String, GateMode) -> GateReport = FaithfulGates::check,
    /**
     * 生成档位（Plan 5 Task 2）：模型现取；改稿的思考档位暂仍为关闭 + 温度 0.3/0.0（D471）。
     */
    private val options: () -> GenerationOptions = { GenerationOptions() },
    private val apiKey: () -> String = { "" },
    /** Plan 6：按供应商取 client / Key；默认回退旧行为（DeepSeek）。 */
    private val clientFor: (ProviderId) -> LlmClient = { llm },
    private val apiKeyFor: ((ProviderId) -> String)? = null,
) {

    private fun keyFor(provider: ProviderId): String = apiKeyFor?.invoke(provider) ?: apiKey()

    suspend fun generateFaithful(
        dreamId: String,
        onProgress: ((GenerationStage, Int) -> Unit)? = null,
    ): GenerationResult = requireNotNull(generation) { "未装配生成编排器" }
        .generateFaithful(dreamId, onProgress)

    suspend fun generateNovel(
        dreamId: String,
        idea: String? = null,
        onProgress: ((GenerationStage, Int) -> Unit)? = null,
    ): GenerationResult = requireNotNull(generation) { "未装配生成编排器" }
        .generateNovel(dreamId, idea, onProgress)

    /**
     * 用户发一句话：模型先判 ASK / REVISE（§0.2）。
     *
     * - ASK：不建版、不动正文，一个事务只落 user + assistant 两条消息（assistant.versionId=null）。
     * - REVISE：过闸门后一个事务落两条消息 + 新版本 + 窄更新 `dreams`；不过闸门则一次
     *   temperature=0.0 重试（带【上一版违规清单】），仍不过就保留旧版、返回 [GenerationFailure.Gate]。
     *
     * 失败时**不写任何消息/版本**：失败的那句意见留在输入框里可重发（§0.8）。
     */
    suspend fun revise(dreamId: String, opinion: String): RevisionResult {
        val idea = opinion.trim()
        if (idea.isEmpty()) return failed(GenerationFailure.Protocol("意见为空"))

        val dream = store.dream(dreamId)
            ?: return failed(GenerationFailure.Protocol("dream 不存在"))
        val versions = store.versions(dreamId)
        val current = versions.firstOrNull { it.version == dream.novelSelectedVersion }
            ?: versions.firstOrNull()
        val baseText = current?.text
            ?: dream.novelText
            ?: dream.faithfulText
            ?: dream.rawTranscript
        if (baseText.isBlank()) {
            return failed(GenerationFailure.Protocol("还没有可改稿的正文"))
        }

        val history = store.messages(dreamId).takeLast(HISTORY_MESSAGES)
        val slots = mapOf(
            SLOT_RAW to dream.rawTranscript.ifBlank { baseText },
            SLOT_DRAFT to baseText,
            SLOT_CHANGELOG to (current?.changeLogJson ?: "[]"),
            SLOT_OPINION to idea,
            // 可选槽被 PromptRepository 抽走后不会留下标题行；测试与设计 §0.5 都要求
            // 模型能看见「最近三轮对话」这个分段，所以把标题放进槽值里。
            SLOT_HISTORY to renderHistory(history)?.let { renderedHistory ->
                "【最近三轮对话】\n$renderedHistory"
            },
        )
        val rendered = try {
            prompts.render(PromptSpecs.NOVEL_REVISE.promptId, slots)
        } catch (e: IllegalArgumentException) {
            return failed(GenerationFailure.Protocol(e.message ?: "提示词槽不完整"))
        } catch (e: IllegalStateException) {
            return failed(GenerationFailure.Protocol(e.message ?: "提示词资产漂移"))
        }

        val first = when (val outcome = call(rendered.text, FIRST_TEMPERATURE)) {
            is CallOutcome.Ok -> outcome.content
            is CallOutcome.Err -> return failed(outcome.failure)
        }
        val firstJson = parse(first)
            ?: return failed(GenerationFailure.Protocol("响应不是 JSON 对象"))

        when (firstJson.optString("action").trim().uppercase(Locale.ROOT)) {
            ACTION_ASK -> return commitAsk(dreamId, idea, firstJson)
            ACTION_REVISE -> Unit
            else -> return failed(GenerationFailure.Protocol("未知 action"))
        }

        val firstBody = firstJson.optString("body").trim()
        if (firstBody.isEmpty()) return failed(GenerationFailure.Protocol("响应缺少正文"))
        val firstReport = gate(baseText, firstBody, GateMode.REVISE)
        if (firstReport.passed) {
            return commitRevision(
                dreamId = dreamId,
                idea = idea,
                summary = summaryOf(firstJson),
                body = firstBody,
                changeLog = filterChangeLog(firstJson.optJSONArray("changeLog"), baseText),
                rendered = rendered,
            )
        }

        // §0.3：不过 → temperature=0.0 重试一次，带上上一版违规清单。
        val retryText = buildString {
            append(rendered.text)
            append("\n\n")
            append(PromptMarker.PREVIOUS_VIOLATIONS.token)
            append('\n')
            append(violationPayload(firstReport))
        }
        val second = when (val outcome = call(retryText, RETRY_TEMPERATURE)) {
            is CallOutcome.Ok -> outcome.content
            is CallOutcome.Err -> return failed(outcome.failure)
        }
        val secondJson = parse(second)
            ?: return failed(GenerationFailure.Protocol("重试响应不是 JSON 对象"))
        if (secondJson.optString("action").trim().uppercase(Locale.ROOT) != ACTION_REVISE) {
            return failed(GenerationFailure.Protocol("重试未产出改稿"))
        }
        val secondBody = secondJson.optString("body").trim()
        if (secondBody.isEmpty()) return failed(GenerationFailure.Protocol("重试响应缺少正文"))
        val secondReport = gate(baseText, secondBody, GateMode.REVISE)
        if (!secondReport.passed) {
            return failed(GenerationFailure.Gate(secondReport.failed, gateDetail(secondReport)))
        }
        return commitRevision(
            dreamId = dreamId,
            idea = idea,
            summary = summaryOf(secondJson),
            body = secondBody,
            changeLog = filterChangeLog(secondJson.optJSONArray("changeLog"), baseText),
            rendered = rendered,
        )
    }

    // ---------------------------------------------------------------- 落库

    private suspend fun commitAsk(dreamId: String, idea: String, json: JSONObject): RevisionResult {
        val summary = summaryOf(json)
        val question = json.optString("question").trim().take(QUESTION_MAX)
        val reply = listOf(summary, question)
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifBlank { DEFAULT_ASK_SUMMARY }
        val now = clock()
        val user = NovelMessageEntity(
            id = id(),
            dreamId = dreamId,
            role = ROLE_USER,
            content = idea,
            versionId = null,
            createdAt = now,
        )
        val assistant = NovelMessageEntity(
            id = id(),
            dreamId = dreamId,
            role = ROLE_ASSISTANT,
            content = reply.take(ASSISTANT_MAX),
            versionId = null,
            createdAt = now,
        )
        store.commitAsk(dreamId, user, assistant)
        return RevisionResult(
            action = RevisionAction.ASK,
            summary = summary,
        )
    }

    private suspend fun commitRevision(
        dreamId: String,
        idea: String,
        summary: String,
        body: String,
        changeLog: ChangeLog,
        rendered: RenderedPrompt,
    ): RevisionResult {
        val draft = NovelVersionEntity(
            id = id(),
            dreamId = dreamId,
            version = 0,
            text = body,
            changeLogJson = changeLog.json,
            origin = ORIGIN_REVISE,
            promptId = rendered.promptId,
            // PromptSpec 的 version 是 "v1" 形态（Task 8 的 promptVersion 列是 Int）。
            promptVersion = rendered.promptVersion.removePrefix("v").removePrefix("V")
                .substringBefore('.').toIntOrNull() ?: 0,
            promptHash = rendered.promptSha256,
            model = options().model,
            reasoningEffort = null,
            createdAt = clock(),
        )
        val assistantContent = summary.ifBlank { DEFAULT_REVISE_SUMMARY }.take(ASSISTANT_MAX)
        val now = clock()
        val user = NovelMessageEntity(
            id = id(),
            dreamId = dreamId,
            role = ROLE_USER,
            content = idea,
            versionId = null,
            createdAt = now,
        )
        val assistant = NovelMessageEntity(
            id = id(),
            dreamId = dreamId,
            role = ROLE_ASSISTANT,
            content = assistantContent,
            versionId = draft.id,
            createdAt = now,
        )
        val saved = store.commitRevision(dreamId, user, assistant, draft, changeLog.json)
        return RevisionResult(
            action = RevisionAction.REVISE,
            version = saved.version,
            text = saved.text,
            summary = summary,
            changeLogKept = changeLog.kept,
            changeLogDropped = changeLog.dropped,
        )
    }

    // ---------------------------------------------------------------- LLM 与解析

    private sealed interface CallOutcome {
        data class Ok(val content: String) : CallOutcome
        data class Err(val failure: GenerationFailure) : CallOutcome
    }

    private suspend fun call(text: String, temperature: Double): CallOutcome {
        val cfg = options()
        val request = ChatRequest(
            model = cfg.model,
            messages = listOf(ChatMessage("user", text)),
            thinking = Thinking.DISABLED,
            temperature = temperature,
            jsonMode = true,
            apiKey = keyFor(cfg.provider),
            kind = "REVISE",
            provider = cfg.provider,
        )
        return try {
            val response = clientFor(cfg.provider).chat(request)
            if (response.content.isBlank()) {
                CallOutcome.Err(GenerationFailure.Protocol("响应为空"))
            } else {
                CallOutcome.Ok(response.content)
            }
        } catch (e: LlmException) {
            CallOutcome.Err(GenerationFailure.Llm(e.kind, e.message ?: e.kind.name))
        }
    }

    private fun parse(content: String): JSONObject? = try {
        JSONObject(content)
    } catch (e: JSONException) {
        null
    }

    private fun failed(failure: GenerationFailure) = RevisionResult(failure = failure)

    // ---------------------------------------------------------------- 历史与文案

    /**
     * 【最近三轮对话】槽：最近 6 条消息，USER:/ASSISTANT: 前缀，轮与轮之间空行（§0.5）。
     */
    private fun renderHistory(messages: List<NovelMessageEntity>): String? {
        if (messages.isEmpty()) return null
        return messages.chunked(2).joinToString("\n\n") { round ->
            round.joinToString("\n") { message ->
                val prefix = if (message.role == ROLE_USER) "USER:" else "ASSISTANT:"
                "$prefix ${message.content}"
            }
        }.ifBlank { null }
    }

    private fun summaryOf(json: JSONObject): String =
        json.optString("summary").trim().take(SUMMARY_MAX)

    private fun violationPayload(report: GateReport): String {
        val lines = mutableListOf<String>()
        for (result in report.results) {
            if (result.passed) continue
            val picked = when (result.gate) {
                com.dreamkeeper.llm.gates.Gate.G1 -> result.detail.take(10)
                com.dreamkeeper.llm.gates.Gate.G4, com.dreamkeeper.llm.gates.Gate.G5 -> result.detail.take(6)
                com.dreamkeeper.llm.gates.Gate.G2, com.dreamkeeper.llm.gates.Gate.G3 ->
                    (result.detail + result.advisory).take(12)
            }
            lines += picked
        }
        if (lines.isEmpty()) {
            report.results.filter { !it.passed }.forEach { lines += it.detail }
        }
        if (lines.isEmpty()) lines += FALLBACK_VIOLATION
        return lines.joinToString("\n")
    }

    private fun gateDetail(report: GateReport): String {
        val detail = report.results
            .filter { !it.passed }
            .flatMap { it.detail }
            .take(12)
            .joinToString("；")
        return detail.ifBlank { report.failed.joinToString("、") }
    }

    /**
     * §0.4：quote 必须是**当前版本正文**的 4–40 码点精确子串；找不到就丢弃并计数，
     * 全丢也写 `[]`（不能写 null——「改稿但没清单」与「旧版清单」在库里分不开）。
     */
    private fun filterChangeLog(raw: JSONArray?, base: String): ChangeLog {
        if (raw == null) return ChangeLog("[]", kept = 0, dropped = 0)
        val keptArray = JSONArray()
        var kept = 0
        var dropped = 0
        for (index in 0 until raw.length()) {
            val item = raw.optJSONObject(index)
            val quote = item?.optString("quote").orEmpty()
            val codePoints = quote.codePointCount(0, quote.length)
            if (item != null && codePoints in MIN_QUOTE..MAX_QUOTE && base.contains(quote)) {
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

    private class ChangeLog(val json: String, val kept: Int, val dropped: Int)

    companion object {
        /** `novel_versions.origin`：对话改稿（区别于 Task 8 的 `GENERATE`）。 */
        const val ORIGIN_REVISE = "REVISE"

        const val ROLE_USER = "USER"
        const val ROLE_ASSISTANT = "ASSISTANT"

        private const val ACTION_ASK = "ASK"
        private const val ACTION_REVISE = "REVISE"
        private const val FIRST_TEMPERATURE = 0.3
        private const val RETRY_TEMPERATURE = 0.0
        private const val HISTORY_MESSAGES = 6
        private const val SUMMARY_MAX = 60
        private const val QUESTION_MAX = 40
        private const val ASSISTANT_MAX = 120
        private const val MIN_QUOTE = 4
        private const val MAX_QUOTE = 40
        private const val DEFAULT_ASK_SUMMARY = "我想先确认一下"
        private const val DEFAULT_REVISE_SUMMARY = "已改稿"
        private const val FALLBACK_VIOLATION = "上一版存在未通过的忠实闸门，请逐字对照原文修正。"

        private const val SLOT_RAW = "原始转写"
        private const val SLOT_DRAFT = "当前小说稿"
        private const val SLOT_CHANGELOG = "当前改动清单"
        private const val SLOT_OPINION = "用户这一条意见"
        private const val SLOT_HISTORY = "最近三轮对话"
    }
}

/**
 * D352 的装配补丁：`AppContainer` 不在 Task 9 白名单里，容器上也没有恰好叫 `orchestrator`
 * 的属性，所以在这里按容器缓存的扩展属性把「生成 + 改稿」门面挂上去。`MainActivity`
 * 只多一行 `orchestrator = container.orchestrator`。
 */
private val CONTEXT_ORCHESTRATORS = WeakHashMap<AppContainer, ReviseOrchestrator>()

val AppContainer.orchestrator: ReviseOrchestrator
    get() = synchronized(CONTEXT_ORCHESTRATORS) {
        CONTEXT_ORCHESTRATORS.getOrPut(this) {
            ReviseOrchestrator(
                llm = llmClient,
                prompts = PromptRepositoryAdapter(promptRepository),
                store = generationStore,
                generation = generationOrchestrator,
                options = { generationPrefs.options() },
                apiKey = { apiKeyStore.load().orEmpty() },
                clientFor = { provider -> providerClients[provider] ?: llmClient },
                apiKeyFor = { provider -> apiKeyStore.load(provider).orEmpty() },
            )
        }
    }
