package com.dreamkeeper.ui.record

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.service.RecordingPhase
import com.dreamkeeper.service.RecordingSession
import com.dreamkeeper.service.RecordingStarter
import com.dreamkeeper.ui.nav.RecordEntry
import java.io.File
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
 * Task 4.1：RecordViewModel 只 mock [RecordingStarter]；session / WavStore / Room 都是真的。
 *
 * Room 的 query/transaction executor 必须钉到同一个测试调度器（D53 的教训）：
 * 否则 `advanceUntilIdle()` 推的是测试调度器，而 Room 的发射在别的线程，断言只能读到 initialValue。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository
    private lateinit var store: WavStore
    private lateinit var session: RecordingSession
    private lateinit var starter: FakeStarter
    private lateinit var root: File
    private var now = 1_750_000_000_000L
    private var granted = true
    private var askedOnce = false

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
        root = File(context.cacheDir, "audio-${UUID.randomUUID()}").apply { mkdirs() }
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
        hasPermission = { granted },
        clock = { now },
        hasAskedOnce = { askedOnce },
    )

    // ---- 4.1 入口解析 ----

    @Test
    fun `parseEntry accepts every RecordEntry and falls back to home button`() {
        RecordEntry.entries.forEach { entry ->
            assertEquals(entry, RecordViewModel.parseEntry(entry.name))
        }
        assertEquals(RecordEntry.HOME_BUTTON, RecordViewModel.parseEntry("NOT_A_ROUTE"))
        assertEquals(RecordEntry.HOME_BUTTON, RecordViewModel.parseEntry(null))
        // 大小写不宽容：route 由我们生成，别的值都是脏数据。
        assertEquals(RecordEntry.HOME_BUTTON, RecordViewModel.parseEntry("lock_tile"))
    }

    // ---- 4.7 权限三态 ----

    @Test
    fun `permission state is askable then blocked then granted`() = runTest(dispatcher) {
        granted = false
        askedOnce = false
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(PermissionState.ASKABLE, vm.state.value.permissionState)

        askedOnce = true
        vm.refreshPermission()
        advanceUntilIdle()
        assertEquals(PermissionState.BLOCKED, vm.state.value.permissionState)

        granted = true
        vm.refreshPermission()
        advanceUntilIdle()
        assertEquals(PermissionState.GRANTED, vm.state.value.permissionState)
    }

    @Test
    fun `granting permission clears the blocked prompt without another press`() = runTest(dispatcher) {
        granted = false
        askedOnce = false
        val vm = viewModel()
        advanceUntilIdle()

        vm.onPressStart(RecordEntry.HOME_BUTTON)
        assertTrue(vm.permissionBlocked.value)
        assertTrue(starter.starts.isEmpty())

        granted = true
        vm.refreshPermission()
        advanceUntilIdle()
        assertFalse(vm.permissionBlocked.value)
        assertEquals(PermissionState.GRANTED, vm.state.value.permissionState)
    }

    @Test
    fun `press without permission never starts the service`() = runTest(dispatcher) {
        granted = false
        val vm = viewModel()
        advanceUntilIdle()

        vm.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()

        assertTrue(starter.starts.isEmpty())
        assertEquals(RecordingPhase.IDLE, session.state.value.phase)
        assertTrue(vm.permissionBlocked.value)
    }

    // ---- 4.2 按下 ----

    @Test
    fun `press starts exactly one service with a uuid and session owns the path`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()

        assertEquals(1, starter.starts.size)
        val dreamId = starter.starts.single()
        assertEquals(dreamId, UUID.fromString(dreamId).toString())
        val state = vm.state.value
        assertEquals(dreamId, state.dreamId)
        assertEquals(store.fileFor(dreamId).absolutePath, state.filePath)
        assertEquals(RecordingPhase.RECORDING, state.phase)
        assertTrue(state.loaded)
    }

    @Test
    fun `second press while recording does not start again`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()
        vm.onPressStart(RecordEntry.LOCK_TILE)
        advanceUntilIdle()

        assertEquals(1, starter.starts.size)
    }

    // ---- 4.3 松手 / 4.4 DONE ----

    @Test
    fun `release asks the service to stop exactly once`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()

        vm.onRelease()
        vm.onRelease()

        assertEquals(1, starter.stops)
        assertEquals(RecordingPhase.FINISHING, session.state.value.phase)
    }

    @Test
    fun `release while idle does nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onRelease()

        assertEquals(0, starter.stops)
        assertEquals(RecordingPhase.IDLE, session.state.value.phase)
    }

    @Test
    fun `done with audio saves a draft with elapsed milliseconds`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()
        val dreamId = starter.starts.single()

        // 32_000 bytes / 32 bytes-per-ms = 1000 ms：VM 不许二次读文件，必须直接用 snapshot.elapsedMs。
        session.finish(32_000)
        advanceUntilIdle()

        val dream = withTimeout(5_000) { repo.observe(dreamId).first() }
        assertNotNull(dream)
        assertEquals(dreamId, dream!!.id)
        assertEquals(1_000L, dream.rawAudioMs)
        assertEquals(store.fileFor(dreamId).absolutePath, dream.rawAudioPath)
        assertTrue(dream.isDraft)
        assertEquals("PENDING", dream.transcriptStatus)
        assertEquals("", dream.rawTranscript)
        assertEquals(dreamId, vm.state.value.savedDreamId)
    }

    @Test
    fun `done without audio keeps no draft and deletes the empty file`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()
        val dreamId = starter.starts.single()
        assertTrue(store.fileFor(dreamId).exists())

        session.finish(0)
        advanceUntilIdle()

        assertNull(withTimeout(5_000) { repo.observe(dreamId).first() })
        assertFalse(store.fileFor(dreamId).exists())
        assertFalse(vm.state.value.error.isNullOrBlank())
        assertNull(vm.state.value.savedDreamId)
    }

    @Test
    fun `press after a saved take starts a new take`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()
        val firstId = starter.starts.single()
        session.finish(32_000)
        advanceUntilIdle()
        assertEquals(RecordingPhase.DONE, session.state.value.phase)

        vm.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()

        // DONE 只是「这一段录完了」，不是「这一页死了」：新 take 必须能开始。
        assertEquals(2, starter.starts.size)
        assertEquals(RecordingPhase.RECORDING, session.state.value.phase)
        assertTrue(starter.starts.last() != firstId)
        assertEquals(starter.starts.last(), vm.state.value.dreamId)
    }

    @Test
    fun `continue after interrupt returns to idle and allows a new take`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()
        session.finish(32_000)
        advanceUntilIdle()
        assertEquals(RecordingPhase.DONE, session.state.value.phase)

        vm.continueAfterInterrupt()
        advanceUntilIdle()

        assertEquals(RecordingPhase.IDLE, session.state.value.phase)
        assertNull(vm.state.value.savedDreamId)

        vm.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()
        assertEquals(2, starter.starts.size)
        assertEquals(RecordingPhase.RECORDING, session.state.value.phase)
    }

    @Test
    fun `reentering the page after done does not save the same take twice`() = runTest(dispatcher) {
        val first = viewModel()
        advanceUntilIdle()
        first.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()
        val dreamId = starter.starts.single()
        session.finish(32_000)
        advanceUntilIdle()
        assertEquals(dreamId, first.state.value.savedDreamId)
        first.consumeSavedDream()
        advanceUntilIdle()

        // 返回键/入口重入会新建一个 VM，但 session 仍停在 DONE：同一 take 不能再存一次，
        // 更不能再次发 savedDreamId 把用户弹去 FollowUp。
        val second = viewModel()
        advanceUntilIdle()

        assertNull(second.state.value.savedDreamId)
        assertEquals(1, withTimeout(5_000) { repo.observeAll().first() }.size)
    }

    // ---- 4.5 取消 ----
    @Test
    fun `cancel asks the service once and the file is gone when the session is idle`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onPressStart(RecordEntry.HOME_BUTTON)
        advanceUntilIdle()
        val dreamId = starter.starts.single()
        assertTrue(store.fileFor(dreamId).exists())

        vm.onCancel()
        advanceUntilIdle()

        assertEquals(1, starter.cancels)
        assertEquals(RecordingPhase.IDLE, session.state.value.phase)
        assertFalse(store.fileFor(dreamId).exists())
    }

    private class FakeStarter(
        private val session: RecordingSession,
        private val store: WavStore,
    ) : RecordingStarter {
        val starts = mutableListOf<String>()
        var stops = 0
        var cancels = 0

        override fun start(dreamId: String) {
            starts += dreamId
            store.create(dreamId)
            session.begin(dreamId, store.fileFor(dreamId).absolutePath)
        }

        override fun stop() {
            stops++
            // 真实 service 在收到 STOP 时同步 markFinishing()，再停 RecorderCore。
            session.markFinishing()
        }

        override fun cancel() {
            cancels++
            val dreamId = session.state.value.dreamId ?: return
            store.fileFor(dreamId).delete()
            session.cancel()
        }
    }
}
