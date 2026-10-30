package com.dreamkeeper.ui.record

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.service.RecordingPhase
import com.dreamkeeper.service.RecordingSession
import com.dreamkeeper.service.RecordingStarter
import com.dreamkeeper.ui.nav.RecordEntry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §0-4：`onSnapshot(DONE)` 发现行已存在时，不许直接 return。
 *
 * 启动扫描可能先按文件把这次 take 恢复成草稿（或用户重入页触发了补写），此时行存在但
 * `rawAudioPath` / `rawAudioMs` 还是旧的或空的。旧实现「已存在就 return」会让 DONE 的
 * 路径与时长永远丢失，界面也不会跳追问页。
 *
 * 独立文件而不是塞进 `RecordViewModelTest`：Task 6 的改动清单只允许改 VM 的「已存在」分支，
 * 不动既有 4.x 用例（见 task-6-report.md 偏差 D191）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordViewModelTakeRecoveryTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository
    private lateinit var store: WavStore
    private lateinit var session: RecordingSession
    private lateinit var starter: FakeStarter
    private lateinit var root: File
    private var now = 1_750_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, DreamDatabase::class.java)
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .allowMainThreadQueries()
            .build()
        repo = DreamRepository(db) { now }
        root = File(context.cacheDir, "audio-recovery-${UUID.randomUUID()}").apply { mkdirs() }
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

    private fun viewModel() = RecordViewModel(
        repository = repo,
        session = session,
        audioStore = store,
        starter = starter,
        hasPermission = { true },
        clock = { now },
        hasAskedOnce = { false },
    )

    @Test
    fun `done with an existing row writes the take pointer and still navigates`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()
        val dreamId = starter.starts.single()

        // 模拟「扫描抢先按文件恢复了草稿」：行在，但指针是旧的、时长是空的。
        repo.save(
            Dream(
                id = dreamId,
                date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate(),
                title = null,
                rawTranscript = "扫描恢复的文本",
                faithfulText = null,
                novelText = null,
                novelChangeLog = null,
                mood = null,
                isDraft = true,
                transcriptStatus = TranscriptStatus.PENDING,
                rawAudioPath = "/stale/old.wav",
                rawAudioMs = 0L,
            ),
        )
        val updatedAtBefore = withTimeout(5_000) { repo.observe(dreamId).first() }!!.updatedAt

        session.finish(64_000)
        advanceUntilIdle()

        val saved = withTimeout(5_000) { repo.observe(dreamId).first() }!!
        assertEquals("行已存在也必须补写本次 take 的路径", store.fileFor(dreamId).absolutePath, saved.rawAudioPath)
        assertEquals("行已存在也必须补写本次 take 的时长", 2_000L, saved.rawAudioMs)
        assertEquals("窄更新不许覆盖扫描恢复的文本", "扫描恢复的文本", saved.rawTranscript)
        assertEquals("窄更新不许碰转写状态", TranscriptStatus.PENDING, saved.transcriptStatus)
        assertEquals("窄更新不许改 updatedAt", updatedAtBefore, saved.updatedAt)
        assertEquals(1, db.dreamDao().count())

        // 本 VM 亲历了这段录音，必须照常跳追问页；重入页才不许跳（D148/D190）。
        assertEquals(dreamId, vm.state.value.savedDreamId)
        assertEquals(RecordingPhase.DONE, session.state.value.phase)
    }

    @Test
    fun `reentering after done updates the pointer but does not navigate`() = runTest(dispatcher) {
        val first = viewModel()
        advanceUntilIdle()
        first.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()
        val dreamId = starter.starts.single()
        session.finish(64_000)
        advanceUntilIdle()
        first.consumeSavedDream()

        // 用户从详情页返回/重入录音页：新 VM 在构造时 session 已是 DONE（D148）。
        val second = viewModel()
        advanceUntilIdle()

        assertNull("重入页不许把用户再弹去追问页", second.state.value.savedDreamId)
        val saved = withTimeout(5_000) { repo.observe(dreamId).first() }!!
        assertEquals(store.fileFor(dreamId).absolutePath, saved.rawAudioPath)
        assertEquals(2_000L, saved.rawAudioMs)
        assertEquals(1, db.dreamDao().count())
    }

    private class FakeStarter(
        private val session: RecordingSession,
        private val store: WavStore,
    ) : RecordingStarter {
        val starts = mutableListOf<String>()

        override fun start(dreamId: String) {
            starts += dreamId
            store.create(dreamId)
            session.begin(dreamId, store.fileFor(dreamId).absolutePath)
        }

        override fun stop() = session.markFinishing()

        override fun cancel() {
            val dreamId = session.state.value.dreamId ?: return
            store.fileFor(dreamId).delete()
            session.cancel()
        }
    }
}
