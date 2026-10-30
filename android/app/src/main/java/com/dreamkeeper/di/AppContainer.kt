package com.dreamkeeper.di

import android.content.Context
import android.os.Build
import com.dreamkeeper.audio.AudioScan
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.TagDao
import com.dreamkeeper.service.RecordingSession
import com.dreamkeeper.stt.SttEngine
import com.dreamkeeper.stt.SystemSttEngine
import com.dreamkeeper.stt.TranscriptWriter
import com.dreamkeeper.ui.library.LayoutPrefs
import com.dreamkeeper.ui.library.SharedPreferencesLayoutPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
class AppContainer(
    context: Context,
    /**
     * 转写协程的作用域（§0.10）：由 [com.dreamkeeper.DreamKeeperApp] 传入进程级 appScope，
     * 不挂在 RecordingService 上——服务收尾就 stopSelf，转写会被一起取消。
     * 默认值只给单测/工具兜底，生产路径永远显式传。
     */
    val transcriptionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

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
     * 启动兜底扫描（Task 6）：进程被杀后修占位头、找回孤儿录音、清掉指向已删文件的指针。
     *
     * 只碰文件系统与窄更新，不读大文本；由 [DreamKeeperApp] 在 IO 线程跑，且必须排在
     * 种子数据之后（§0-10：先插草稿会让 `count() == 0` 的种子判断永远失败）。
     */
    val audioScan: AudioScan by lazy { AudioScan(audioStore, repository) }

    /**
     * 是否在 `Application.onCreate` 里跑启动扫描。
     *
     * 原计划用 `BuildConfig` 门闩，但 Task 6 的改动清单**禁止**改 `build.gradle.kts`，
     * 所以改成运行期识别 Robolectric：单测每个用例都会执行 `onCreate`，无条件扫描会把
     * 真实 `filesDir` 的 IO 混进既有 194 个用例；生产 debug/release 都照常扫描。
     */
    val audioScanOnStart: Boolean
        get() = Build.FINGERPRINT != "robolectric" && Build.MODEL != "robolectric"

    /**
     * 录音状态的唯一真相：前台服务写、录音页（Task 4）只读。
     *
     * 放在容器里而不是 Service 字段：Activity 与 Service 生命周期不同步，
     * 状态必须活过其中任何一个的销毁重建。
     */
    val recordingSession: RecordingSession by lazy { RecordingSession() }

    /**
     * 系统 on-device 识别器。构造本身不碰系统服务；真正的探测发生在第一次
     * `availability()`（结果进程内缓存），所以 Application 启动不会因此变慢。
     */
    val sttEngine: SttEngine by lazy { SystemSttEngine(appContext) }

    /** 录音收尾 → 一次性本地转写 → CAS 窄更新（§0.5–§0.7 全在里面）。 */
    val transcriptWriter: TranscriptWriter by lazy {
        TranscriptWriter(repository, audioStore, sttEngine)
    }
}
