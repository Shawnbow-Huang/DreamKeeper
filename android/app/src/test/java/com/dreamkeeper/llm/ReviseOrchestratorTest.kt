package com.dreamkeeper.llm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.RoomGenerationStore
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.DreamEntity
import com.dreamkeeper.data.db.NovelMessageEntity
import com.dreamkeeper.data.db.NovelVersionEntity
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.llm.gates.FaithfulGates
import com.dreamkeeper.llm.gates.Gate
import com.dreamkeeper.llm.gates.GateMode
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 9 §0.9 的 8 条验收用例。
 *
 * 全部用真 Room + 生产 `PromptRepository`，只 fake `LlmClient`：这样 FTS 以外的落库、
 * 唯一版本号、事务顺序、提示词槽渲染都是生产路径；闸门重试用例断言的是**真实请求体**，
 * 删掉载荷注入会红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviseOrchestratorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prompts: PromptRenderer by lazy {
        PromptRepositoryAdapter(PromptRepository(AssetPromptSource(context)))
    }

    private lateinit var db: DreamDatabase
    private lateinit var store: RoomGenerationStore
    private lateinit var repo: DreamRepository

    private var now: Long = 1_800_000_000_000L
    private var seq: Int = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, DreamDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomGenerationStore(db)
        repo = DreamRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- 用例 1

    @Test
    fun `ASK 只落两条消息、不建版、不动正文`() = runTest {
        seedDream()
        val llm = FakeLlm(
            ask("你希望用第几人称？", "先确认人称"),
        )

        val result = orchestrator(llm).revise(DREAM_ID, "人称换成第一人称")

        assertEquals(RevisionAction.ASK, result.action)
        assertNull(result.version)
        assertNull(result.failure)
        assertTrue(db.novelVersionDao().listByDream(DREAM_ID).isEmpty())
        val dream = db.dreamDao().getById(DREAM_ID)!!
        assertEquals(3, dream.novelVersion)
        assertEquals(CURRENT, dream.novelText)
        assertNull(dream.novelSelectedVersion)

        val messages = db.novelMessageDao().listByDream(DREAM_ID)
        assertEquals(2, messages.size)
        assertEquals(ReviseOrchestrator.ROLE_USER, messages[0].role)
        assertEquals("人称换成第一人称", messages[0].content)
        assertEquals(ReviseOrchestrator.ROLE_ASSISTANT, messages[1].role)
        assertNull(messages[1].versionId)
        assertTrue(messages[1].content.contains("先确认人称"))
        assertTrue(messages[1].content.contains("你希望用第几人称？"))
    }

    // ---------------------------------------------------------------- 用例 2

    @Test
    fun `REVISE 建新版本、写正文与消息、assistant 指向新版本`() = runTest {
        seedDream()
        val llm = FakeLlm(
            revise(
                body = NEW,
                changeLog = JSONArray().put(
                    JSONObject().put("type", "节奏").put("detail", "补一个动作").put("quote", "窗外的雨声"),
                ),
                summary = "补动作",
            ),
        )

        val result = orchestrator(llm).revise(DREAM_ID, "结尾加个动作")

        assertEquals(RevisionAction.REVISE, result.action)
        assertEquals(4, result.version)
        assertEquals(NEW, result.text)
        assertEquals(1, result.changeLogKept)
        assertEquals(0, result.changeLogDropped)
        assertNull(result.failure)

        val versions = db.novelVersionDao().listByDream(DREAM_ID)
        assertEquals(1, versions.size)
        val saved = versions.single()
        assertEquals(4, saved.version)
        assertEquals(NEW, saved.text)
        assertEquals(ReviseOrchestrator.ORIGIN_REVISE, saved.origin)
        assertEquals("novel_revise", saved.promptId)
        assertEquals(1, saved.promptVersion)
        assertTrue(saved.changeLogJson!!.contains("窗外的雨声"))

        val dream = db.dreamDao().getById(DREAM_ID)!!
        assertEquals(NEW, dream.novelText)
        assertEquals(4, dream.novelVersion)

        val messages = db.novelMessageDao().listByDream(DREAM_ID)
        assertEquals(2, messages.size)
        assertEquals("结尾加个动作", messages[0].content)
        assertEquals(saved.id, messages[1].versionId)
        assertTrue(messages[1].content.contains("补动作"))
    }

    // ---------------------------------------------------------------- 用例 3

    @Test
    fun `正文与旧稿逐字相同也建版`() = runTest {
        seedDream()
        val llm = FakeLlm(revise(body = CURRENT, summary = "没改"))

        val result = orchestrator(llm).revise(DREAM_ID, "保持原样再交一版")

        assertEquals(RevisionAction.REVISE, result.action)
        assertEquals(4, result.version)
        assertEquals(CURRENT, result.text)
        assertEquals(1, db.novelVersionDao().listByDream(DREAM_ID).size)
        assertEquals(4, db.dreamDao().getById(DREAM_ID)!!.novelVersion)
    }

    // ---------------------------------------------------------------- 用例 4

    @Test
    fun `超长正文两次都失败且旧正文与选中版不变`() = runTest {
        seedDream(selected = 2)
        db.novelVersionDao().insert(version(2, CURRENT))
        val oversized = CURRENT + "超".repeat(30)
        val llm = FakeLlm(revise(body = oversized), revise(body = oversized))

        val result = orchestrator(llm).revise(DREAM_ID, "加长一点")

        assertEquals(2, llm.requests.size)
        val failure = result.failure
        assertTrue("期望闸门失败，实际 $failure", failure is GenerationFailure.Gate)
        assertTrue((failure as GenerationFailure.Gate).failed.contains(Gate.G2))

        val dream = db.dreamDao().getById(DREAM_ID)!!
        assertEquals(2, dream.novelSelectedVersion)
        assertEquals(3, dream.novelVersion)
        assertEquals(CURRENT, dream.novelText)
        assertEquals(1, db.novelVersionDao().listByDream(DREAM_ID).size)
        assertEquals(CURRENT, db.novelVersionDao().listByDream(DREAM_ID).single().text)
        assertTrue(db.novelMessageDao().listByDream(DREAM_ID).isEmpty())
    }

    // ---------------------------------------------------------------- 用例 5

    @Test
    fun `闸门重试带上一版违规清单与专名引文`() = runTest {
        seedDream()
        val dirty = CURRENT + "她叫“阿澄”。"
        val llm = FakeLlm(revise(body = dirty, summary = "加人物"), revise(body = CURRENT, summary = "收敛"))

        val result = orchestrator(llm).revise(DREAM_ID, "加个人物")

        assertEquals(2, llm.requests.size)
        assertEquals(0.3, llm.requests[0].temperature!!, 0.0001)
        assertEquals(0.0, llm.requests[1].temperature!!, 0.0001)
        val retryBody = llm.requests[1].messages.single().content
        assertTrue(retryBody.contains("【上一版违规清单】"))
        assertTrue(retryBody.contains("阿澄"))
        assertNull(result.failure)
        assertEquals(4, result.version)
    }

    // ---------------------------------------------------------------- 用例 6

    @Test
    fun `选择版本落库并让 resolveFinalNovel 指向它`() = runTest {
        seedVersions()
        repo.selectVersion(DREAM_ID, 2)

        assertEquals(2, db.dreamDao().getById(DREAM_ID)!!.novelSelectedVersion)
        val resolved = repo.resolveFinalNovel(DREAM_ID)
        assertNotNull(resolved)
        assertEquals(2, resolved!!.version)
        assertEquals("第二版正文", resolved.novelText)
        assertTrue(resolved.selected)
    }

    // ---------------------------------------------------------------- 用例 7

    @Test
    fun `撤回改选上一版且不删版本行`() = runTest {
        seedVersions()
        repo.selectVersion(DREAM_ID, 3)
        repo.selectVersion(DREAM_ID, 2)

        assertEquals(2, db.dreamDao().getById(DREAM_ID)!!.novelSelectedVersion)
        assertEquals(3, db.novelVersionDao().listByDream(DREAM_ID).size)
        assertEquals(2, repo.resolveFinalNovel(DREAM_ID)!!.version)
    }

    // ---------------------------------------------------------------- 用例 8

    @Test
    fun `历史只取最近三轮共六条`() = runTest {
        seedDream()
        seedHistory()
        val llm = FakeLlm(ask("还要改哪里？", "等你补充"))

        orchestrator(llm).revise(DREAM_ID, "再润色一下")

        val body = llm.requests.single().messages.single().content
        assertTrue("缺少槽标题：$body", body.contains("【最近三轮对话】"))
        assertTrue(body.contains("第3轮意见"))
        assertTrue(body.contains("第3轮回答"))
        assertTrue(body.contains("第4轮意见"))
        assertTrue(body.contains("第4轮回答"))
        assertTrue(body.contains("第5轮意见"))
        assertTrue(body.contains("第5轮回答"))
        assertFalse(body.contains("第1轮"))
        assertFalse(body.contains("第2轮"))
    }

    // ---------------------------------------------------------------- 夹具

    private fun orchestrator(llm: LlmClient): ReviseOrchestrator = ReviseOrchestrator(
        llm = llm,
        prompts = prompts,
        store = store,
        clock = {
            now += 1_000
            now
        },
        id = { "x-${seq++}" },
        gate = FaithfulGates::check,
        apiKey = { "sk-test" },
    )

    private suspend fun seedDream(selected: Int? = null) {
        db.dreamDao().upsert(
            DreamEntity(
                id = DREAM_ID,
                dreamDate = LocalDate.of(2026, 10, 30),
                createdAt = 1L,
                title = null,
                rawTranscript = RAW,
                faithfulText = null,
                novelText = CURRENT,
                novelVersion = 3,
                novelSelectedVersion = selected,
                transcriptStatus = TranscriptStatus.OK,
            ),
        )
    }

    private suspend fun seedVersions() {
        db.novelVersionDao().insert(version(1, "第一版正文"))
        db.novelVersionDao().insert(version(2, "第二版正文"))
        db.novelVersionDao().insert(version(3, "第三版正文"))
        db.dreamDao().upsert(
            DreamEntity(
                id = DREAM_ID,
                dreamDate = LocalDate.of(2026, 10, 30),
                createdAt = 1L,
                title = null,
                rawTranscript = RAW,
                faithfulText = null,
                novelText = "第三版正文",
                novelVersion = 3,
                transcriptStatus = TranscriptStatus.OK,
            ),
        )
    }

    private suspend fun seedHistory() {
        for (round in 1..5) {
            db.novelMessageDao().insert(
                NovelMessageEntity(
                    id = "m-$round-u",
                    dreamId = DREAM_ID,
                    role = ReviseOrchestrator.ROLE_USER,
                    content = "第${round}轮意见",
                    createdAt = 100L + round * 10,
                ),
            )
            db.novelMessageDao().insert(
                NovelMessageEntity(
                    id = "m-$round-a",
                    dreamId = DREAM_ID,
                    role = ReviseOrchestrator.ROLE_ASSISTANT,
                    content = "第${round}轮回答",
                    versionId = "v-$round",
                    createdAt = 101L + round * 10,
                ),
            )
        }
    }

    private fun version(number: Int, text: String): NovelVersionEntity = NovelVersionEntity(
        id = "seed-v$number",
        dreamId = DREAM_ID,
        version = number,
        text = text,
        origin = "GENERATE",
        promptId = "novel",
        promptVersion = 1,
        promptHash = "deadbeef",
        model = "deepseek-flash",
        createdAt = 10L + number,
    )

    private fun revise(
        body: String,
        changeLog: JSONArray = JSONArray(),
        summary: String = "改好了",
    ): String = JSONObject()
        .put("action", "REVISE")
        .put("body", body)
        .put("changeLog", changeLog)
        .put("summary", summary)
        .toString()

    private fun ask(question: String, summary: String): String = JSONObject()
        .put("action", "ASK")
        .put("question", question)
        .put("summary", summary)
        .toString()

    private class FakeLlm(vararg replies: String) : LlmClient {
        private val replies = replies.toList()
        val requests = mutableListOf<ChatRequest>()
        private var index = 0

        override suspend fun chat(req: ChatRequest): ChatResponse {
            requests += req
            val content = this.replies[minOf(index, this.replies.lastIndex)]
            index++
            return ChatResponse(
                content = content,
                reasoning = null,
                usage = Usage(10, 20, 5, 0, 35),
                requestId = "req-${requests.size}",
                httpStatus = 200,
                latencyMs = 5,
                finishReason = "stop",
                attempts = emptyList(),
            )
        }
    }

    private companion object {
        const val DREAM_ID = "dream-revise"
        const val RAW = "昨晚我梦见自己站在一条很长的走廊里，灯一盏一盏地灭。"
        const val CURRENT = "我走进房间，窗外的雨声很轻，桌上那盏台灯还亮着，好像有人刚刚离开。"
        const val NEW = "我走进房间，窗外的雨声很轻，桌上那盏台灯还亮着，好像有人刚刚离开。我停下"
    }
}
