package com.dreamkeeper.ui.detail

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.llm.GenerationKind
import com.dreamkeeper.llm.GenerationResult
import com.dreamkeeper.llm.GenerationRunner
import com.dreamkeeper.llm.GenerationStage
import java.io.File
import java.time.LocalDate
import java.util.UUID
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
 * Plan 4 T5：小说化必须「用户主动触发 + 可以带想法」。
 *
 * 三条契约都钉在 ViewModel 这层缝上（真的 Room + 真 repository，只 fake runner）：
 * 1) 想法透传且 ≤200 字；
 * 2) 空想法按 `null` 传（提示词第 4 槽整对删除）；
 * 3) 忠实整理成功**不会**顺带跑小说化（用户需求 ②：不许自动生成）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelIdeaTest {

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
        root = File(context.cacheDir, "idea-${UUID.randomUUID()}").apply { mkdirs() }
        runner = FakeRunner()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    // 1) 想法trimmed 后透传，超 200 字截断
    @Test
    fun `idea is trimmed and truncated to 200 chars before reaching the runner`() = runTest(dispatcher) {
        seed(dream("n1"))
        val vm = startVm("n1")

        vm.requestNovel()
        assertTrue("点小说化必须先弹想法框", vm.novelIdeaPrompt.value)

        val long = "多" + "写".repeat(230)
        vm.confirmNovelIdea(long)
        assertFalse("确认后对话框要关", vm.novelIdeaPrompt.value)
        advanceUntilIdle()

        assertEquals(listOf("novel"), runner.calls)
        val idea = runner.ideas.single()
        assertEquals(200, idea!!.length)
        assertEquals("写".repeat(199), idea.drop(1))
    }

    // 2) 空白想法按 null 传（槽整对删除）
    @Test
    fun `blank idea is passed as null`() = runTest(dispatcher) {
        seed(dream("n2"))
        val vm = startVm("n2")
        vm.requestNovel()
        vm.confirmNovelIdea("   ")
        advanceUntilIdle()
        assertEquals(listOf("novel"), runner.calls)
        assertNull("空白想法必须传 null（提示词槽整对删除）", runner.ideas.single())
    }

    // 3) 忠实整理不会顺带跑小说化
    @Test
    fun `faithful generation never auto-calls novel`() = runTest(dispatcher) {
        seed(dream("n3"))
        val vm = startVm("n3")
        vm.generateFaithful()
        advanceUntilIdle()
        assertEquals(listOf("faithful"), runner.calls)
        assertTrue("没有用户点小说化，就不许有 NOVEL 调用", runner.ideas.isEmpty())
    }

    // ---------------------------------------------------------------- harness

    private fun dream(id: String): Dream = Dream(
        id = id,
        date = LocalDate.of(2026, 10, 1),
        title = "电梯",
        rawTranscript = "我在电梯里，按不到一楼。",
        faithfulText = "电梯里没有别人，我按不到一楼。",
        novelText = null,
        novelChangeLog = null,
        mood = null,
        isDraft = false,
        transcriptStatus = TranscriptStatus.OK,
        rawAudioPath = File(root, "$id.wav").path,
        rawAudioMs = 1_000,
        transcriptMs = 1_000,
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

    private class FakeRunner : GenerationRunner {
        val calls = mutableListOf<String>()
        val ideas = mutableListOf<String?>()

        override suspend fun generateFaithful(
            dreamId: String,
            onProgress: ((GenerationStage, Int) -> Unit)?,
        ): GenerationResult {
            calls += "faithful"
            return result(GenerationKind.FAITHFUL)
        }

        override suspend fun generateNovel(
            dreamId: String,
            idea: String?,
            onProgress: ((GenerationStage, Int) -> Unit)?,
        ): GenerationResult {
            calls += "novel"
            ideas += idea
            return result(GenerationKind.NOVEL)
        }

        private fun result(kind: GenerationKind) = GenerationResult(
            kind = kind,
            finalStage = GenerationStage.T0,
            text = "整理稿",
            version = 1,
            changeLogKept = 0,
            changeLogDropped = 0,
            attempts = 0,
            costMicroYuan = 0,
            failure = null,
        )
    }
}
