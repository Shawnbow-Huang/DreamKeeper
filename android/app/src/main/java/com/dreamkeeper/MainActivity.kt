package com.dreamkeeper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.dreamkeeper.llm.orchestrator
import com.dreamkeeper.service.AndroidRecordingStarter
import com.dreamkeeper.service.RecordingService
import com.dreamkeeper.ui.DreamKeeperRoot
import com.dreamkeeper.ui.nav.RecordEntry
import com.dreamkeeper.ui.record.RecordViewModel
import com.dreamkeeper.ui.theme.DreamKeeperTheme

class MainActivity : ComponentActivity() {

    /** 每次 RESUMED / 授权回调递增，让录音页 VM 重算权限三态（§0.4）。 */
    private val micPermissionEpoch = mutableIntStateOf(0)

    /** intent 里的 RecordEntry 是**一次性**事件：导航后由 DreamKeeperRoot 回调置 null。 */
    private val pendingRecordEntry = mutableStateOf<RecordEntry?>(null)

    /**
     * 必须在 STARTED 之前注册：字段初始化发生在构造期，早于 onCreate。
     * 用 RequestPermission 而非 RequestMultiplePermissions——本任务只要 RECORD_AUDIO
     * （POST_NOTIFICATIONS 被关不拦录音，只在屏内提示）。
     */
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        micPermissionEpoch.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 37 强制 edge-to-edge：themes.xml 里的 statusBarColor/navigationBarColor 是空操作，
        // 系统栏必须在这里显式声明深色 + 透明，再由下面的 Compose 背景铺满系统栏区域。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // D67 的 debug 钩子：`adb shell input text` 只映射 ASCII，中文检索没法手打，
        // 只能靠 intent extra 预填。**仅 DEBUG 生效**；这里只传「搜什么」，
        // 结果仍由 SearchViewModel → repo.search 实查，绝不写死任何一条。
        val prefillSearch = if (BuildConfig.DEBUG) {
            intent?.getStringExtra("dk_prefill_search").orEmpty()
        } else {
            ""
        }
        // 只有首次创建才消费启动 intent（§4.4）：旋转/进程恢复不能再跳一次录音页。
        // 没有 entry extra 时保持 null，正常冷启动仍落在日历。
        if (savedInstanceState == null) {
            pendingRecordEntry.value = intent
                ?.getStringExtra(RecordingService.EXTRA_ENTRY)
                ?.let { RecordViewModel.parseEntry(it) }
            applyLockScreenFlags(pendingRecordEntry.value)
        }
        // ENOSPC 在模拟器上没法真实触发；DEBUG 下允许用 `--es dk_record_error ...`
        // 把错误容器渲染出来拍证据（report 里记偏差 D142）。
        val debugRecordError = if (BuildConfig.DEBUG) {
            intent?.getStringExtra("dk_record_error")
        } else {
            null
        }
        // Task 2 的 debug 写入口（brief §0.11）：给 prefs/logcat 取实物证据用，release 里不存在。
        applyDebugKeyIntent(intent)

        // Activity 只做一件事：把根界面接进主题
        setContent {
            DreamKeeperTheme {
                val container = (application as DreamKeeperApp).container
                // Activity 是可见的 Context：startForegroundService 才带前台豁免；
                // 同一个实例跨旋转复用，避免每次重组都新建一个 starter。
                val starter = remember { AndroidRecordingStarter(this) }
                DreamKeeperRoot(
                    repo = container.repository,
                    // 排布偏好也由容器给：界面不自己 getSharedPreferences（否则换个 Context 就丢配置）
                    layoutPrefs = container.layoutPrefs,
                    // Key 的加密存储同理：设置页只认容器里的这一份
                    apiKeyStore = container.apiKeyStore,
                    // 标签页的重命名/删除走 DAO；合并仍在仓库层（要动关联表，必须事务）
                    tagDao = container.tagDao,
                    session = container.recordingSession,
                    audioStore = container.audioStore,
                    starter = starter,
                    // 每次现查，不缓存（§4.4）：用户可能在设置里刚关掉权限
                    hasPermission = {
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    },
                    hasAskedOnce = { hasAskedMicOnce() },
                    permissionEpoch = micPermissionEpoch.intValue,
                    onRequestPermission = {
                        markAskedMicOnce()
                        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    recordEntry = pendingRecordEntry.value,
                    onEntryConsumed = { pendingRecordEntry.value = null },
                    debugRecordError = debugRecordError,
                    initialSearchQuery = prefillSearch,
                    // D352：Task 9 只加这一行传参；装配细节在 ReviseOrchestrator.kt 的容器扩展里。
                    orchestrator = container.orchestrator,
                )
            }
        }
    }

    /** singleTop 复用同一个 Activity：通知点回 / 锁屏磁贴走这里，不再 create 新实例。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDebugKeyIntent(intent)
        val entry = intent
            .getStringExtra(RecordingService.EXTRA_ENTRY)
            ?.let { RecordViewModel.parseEntry(it) }
        pendingRecordEntry.value = entry
        applyLockScreenFlags(entry)
    }

    override fun onResume() {
        super.onResume()
        // 用户可能从「去设置」回来；每次 RESUMED 让 VM 重查一次（§0.4）
        micPermissionEpoch.intValue++
    }

    /**
     * 锁屏直达只在 LOCK_TILE / NOTIFICATION 两个入口打开（§4.4）：
     * 普通首页按钮不应该在锁屏上亮屏，那是打扰。
     */
    private fun applyLockScreenFlags(entry: RecordEntry?) {
        val locked = entry == RecordEntry.LOCK_TILE || entry == RecordEntry.NOTIFICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(locked)
            setTurnScreenOn(locked)
        } else {
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (locked) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    /**
     * Task 2 的 debug 写入口（brief §0.11）：
     * `adb shell am start -n com.dreamkeeper/.MainActivity --es dk_debug_save_key <key>`
     * 走容器真实保存，`--ez dk_debug_clear_key true` 清除——只为拿 prefs/logcat 实物证据。
     * **只在 DEBUG 包里生效**；release 用 `grep -c dk_debug_save_key app-release-unsigned.apk` = 0 把关。
     *
     * 同步执行：Keystore 加密 + `commit()` 都是毫秒级，且取证脚本随后就会读 prefs，
     * 放到异步线程反而要猜何时写完。
     */
    private fun applyDebugKeyIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val container = (application as DreamKeeperApp).container
        intent?.getStringExtra("dk_debug_save_key")?.let { container.apiKeyStore.save(it) }
        if (intent?.getBooleanExtra("dk_debug_clear_key", false) == true) {
            container.apiKeyStore.clear()
        }
    }

    private fun hasAskedMicOnce(): Boolean =
        getSharedPreferences(RECORD_PREFS, MODE_PRIVATE).getBoolean(KEY_MIC_ASKED, false)

    private fun markAskedMicOnce() {
        getSharedPreferences(RECORD_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MIC_ASKED, true)
            .apply()
    }

    private companion object {
        const val RECORD_PREFS = "record"
        const val KEY_MIC_ASKED = "mic_asked_once"
    }
}
