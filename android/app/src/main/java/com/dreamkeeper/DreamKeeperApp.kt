package com.dreamkeeper

import android.app.Application
import com.dreamkeeper.data.db.SeedData
import com.dreamkeeper.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application 只做两件事：建容器、灌种子数据。
 *
 * - 容器懒加载：`AppContainer` 里的 database/repository 都是 `by lazy`，Application 启动本身不碰磁盘。
 * - 种子数据放 `Dispatchers.IO` 且 `SupervisorJob`：不能卡主线程做 Room 写入；种子失败也不该
 *   拖垮整个进程（用户真正的数据在用户自己手里，种子只是首次安装的示例）。
 * - `BuildConfig.SEED_DEMO_DATA` 是 `build.gradle.kts` 里的开关，release 前可关。
 */
class DreamKeeperApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (BuildConfig.SEED_DEMO_DATA) {
            appScope.launch { SeedData.seedIfEmpty(container.database) }
        }
    }
}
