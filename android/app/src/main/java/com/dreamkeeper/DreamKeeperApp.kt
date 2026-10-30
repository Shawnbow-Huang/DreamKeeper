package com.dreamkeeper

import android.app.Application
import android.util.Log
import com.dreamkeeper.data.db.SeedData
import com.dreamkeeper.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application 只做三件事：建容器、灌种子数据（首次安装）、跑一次音频兜底扫描。
 *
 * - 容器懒加载：`AppContainer` 里的 database/repository 都是 `by lazy`，Application 启动本身不碰磁盘。
 * - 种子与扫描放 `Dispatchers.IO` 且共享 `SupervisorJob`：不能卡主线程做 Room/文件 IO；
 *   失败也只记日志，不拖垮整个进程。
 * - **顺序**：扫描必须排在种子之后。种子用 `count() == 0` 判断是否首次安装，若扫描先插了
 *   一条恢复草稿，种子就永远不会出现（§0-10）。
 * - 单测里 `audioScanOnStart` 为 false（Robolectric 识别，见 `AppContainer`），不给既有用例加 IO。
 */
class DreamKeeperApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 转写协程必须活过 RecordingService（服务收尾即销毁），所以共用进程级 appScope。
        container = AppContainer(this, appScope)
        appScope.launch {
            if (BuildConfig.SEED_DEMO_DATA) {
                SeedData.seedIfEmpty(container.database)
            }
            if (container.audioScanOnStart) {
                runCatching { container.audioScan.run() }
                    .onFailure { Log.w(TAG, "启动音频扫描失败：${it.message}") }
            }
        }
    }

    private companion object {
        const val TAG = "DreamKeeperApp"
    }
}
