package com.dreamkeeper.di

import android.content.Context
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.TagDao
import com.dreamkeeper.service.RecordingSession
import com.dreamkeeper.ui.library.LayoutPrefs
import com.dreamkeeper.ui.library.SharedPreferencesLayoutPrefs
import java.io.File

/**
 * 手写依赖容器：Plan 1 明确不引 Hilt/Koin（设计 §2「尽量少的依赖」），
 * 所以这里就是最朴素的 `by lazy` 单例，进程内一份。
 *
 * 只放**真正常驻**的依赖：数据库、仓库与界面偏好。`LayoutPrefs` 是 Task 7 才出现的，
 * 之前版本按「等它存在再加」处理（见 task-5-report.md 的偏差），现在补上。
 *
 * 用 `context.applicationContext` 而不是传进来的 Activity/Application：数据库与仓库的生命周期
 * 跟进程走，持有 Activity 引用就是内存泄漏的标准姿势。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 进程内唯一的 Room 实例；`DreamDatabase.create` 内部没有 `fallbackToDestructiveMigration` */
    val database: DreamDatabase by lazy { DreamDatabase.create(appContext) }

    /** 界面/ViewModel 只拿这一个入口，不自己 new DAO */
    val repository: DreamRepository by lazy { DreamRepository(database) }

    /**
     * 标签管理页要的 DAO。**不**从 [DreamRepository] 漏出去：仓库是「领域模型翻译层」，
     * 而标签页的重命名/删除本来就是单表操作，硬包一层只会让仓库多两个转发方法。
     * 合并（要动关联表 + 删行）仍然在仓库层，见 `DreamRepository.mergeTags`。
     */
    val tagDao: TagDao by lazy { database.tagDao() }

    /**
     * 「全部」页的排布偏好（时间轴 / 卡片流）。落盘在 SharedPreferences `layout_prefs`，
     * 进程重启后仍是上次选的那种排布——这是 Task 7 三条验收之一。
     */
    val layoutPrefs: LayoutPrefs by lazy { SharedPreferencesLayoutPrefs(appContext) }

    /**
     * WAV 落盘层：`files/audio/<dreamId>.wav`（不进 Room，音频是文件不是 BLOB）。
     *
     * 与 [recordingSession] 一样是**进程内唯一**的写入口：Service 写、启动扫描（Task 6）修、
     * 详情页（Task 8）读，都从这里拿同一个 `rootDir`。
     */
    val audioStore: WavStore by lazy { WavStore(File(appContext.filesDir, "audio")) }

    /**
     * 录音状态的唯一真相：前台服务写、录音页（Task 4）只读。
     *
     * 放在容器里而不是 Service 字段：Activity 与 Service 生命周期不同步，
     * 状态必须活过其中任何一个的销毁重建。
     */
    val recordingSession: RecordingSession by lazy { RecordingSession() }
}
