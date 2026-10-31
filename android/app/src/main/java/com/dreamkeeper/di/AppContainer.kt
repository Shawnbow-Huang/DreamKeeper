package com.dreamkeeper.di

import android.content.Context
import android.os.Build
import com.dreamkeeper.BuildConfig
import com.dreamkeeper.audio.AudioScan
import com.dreamkeeper.audio.WavStore
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.GenerationStore
import com.dreamkeeper.data.RoomGenerationStore
import com.dreamkeeper.data.db.DbBackup
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.TagDao
import com.dreamkeeper.data.security.AndroidKeystoreCipher
import com.dreamkeeper.data.security.ApiKeyStore
import com.dreamkeeper.data.settings.GenerationPrefs
import com.dreamkeeper.data.settings.ThemeController
import com.dreamkeeper.data.settings.ThemePrefs
import com.dreamkeeper.data.security.KeystoreApiKeyStore
import com.dreamkeeper.llm.AssetPriceCardSource
import com.dreamkeeper.llm.AssetPromptSource
import com.dreamkeeper.llm.ConnectionTester
import com.dreamkeeper.llm.DeepSeekClient
import com.dreamkeeper.llm.ProviderChatClient
import com.dreamkeeper.llm.ProviderId
import com.dreamkeeper.llm.ProviderRegistry
import com.dreamkeeper.llm.GenerationOrchestrator
import com.dreamkeeper.llm.LlmClient
import com.dreamkeeper.llm.PriceCard
import com.dreamkeeper.llm.PromptRepository
import com.dreamkeeper.llm.PromptRepositoryAdapter
import com.dreamkeeper.service.RecordingSession
import com.dreamkeeper.stt.SttEngine
import com.dreamkeeper.stt.SystemSttEngine
import com.dreamkeeper.stt.TranscriptWriter
import com.dreamkeeper.ui.library.LayoutPrefs
import com.dreamkeeper.ui.library.SharedPreferencesLayoutPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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

    /**
     * 生成任务的作用域（Task 5 起把 LLM 请求扔进来）。
     *
     * 取消归属：**容器**持有并只随进程结束消失；不挂在 Activity / ViewModel / Service 上，
     * 用户切页或退出设置页不该掐掉已经在跑的请求。与 [transcriptionScope] 的区别是它不靠
     * `DreamKeeperApp.appScope` 传入——appScope 只跑启动任务，从不 cancel 别人；两者是并列的
     * SupervisorJob，互不负责取消对方。
     */
    val generationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /**
     * 详情页「重试转写」的落点：DAO 只把行拨回 PENDING，真正去跑 STT 的入口在 Writer。
     * 抛进 app 级 scope，点完立刻返回；失败/无 Key 由 Writer 自己落 FAILED（不冒泡到 UI 线程）。
     */
    fun requestTranscriptionRetry() {
        transcriptionScope.launch {
            runCatching { transcriptWriter.reconcilePending() }
        }
    }

    private val appContext: Context = context.applicationContext

    /**
     * Key 的加密存储（Task 2）：Keystore 里 AES-256/GCM/NoPadding，prefs 里只有
     * `Base64(iv‖ct)` 一个字符串。独立 `settings` 文件 + 单键 [KeystoreApiKeyStore]，
     * 密文被导出也解不开（密钥不可导出，且不随备份走）。
     *
     * `by lazy`：不打开设置页、不跑生成任务就不碰 Keystore。
     */
    val apiKeyStore: ApiKeyStore by lazy {
        KeystoreApiKeyStore(
            AndroidKeystoreCipher(),
            appContext.getSharedPreferences("settings", Context.MODE_PRIVATE),
        )
    }

    /**
     * 进程内唯一的 Room 实例；`DreamDatabase.create` 内部没有 `fallbackToDestructiveMigration`。
     *
     * 打开之前先进 [DbBackup]：v1 老库把 db / -wal / -shm 三件拷到 `files/db-backup/`（§0.8），
     * 这是迁移写坏时唯一的退路。放在 lazy 里而不是 `onCreate`：保证「第一次真正要库」与
     * 「快照」之间没有别的代码能先把库打开（`DreamKeeperApp` 的种子数据就在启动协程里）。
     */
    val database: DreamDatabase by lazy {
        DbBackup.snapshotBeforeMigration(appContext)
        DreamDatabase.create(appContext)
    }

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
     * 是否在 `Application.onCreate` 里灌演示种子数据（`BuildConfig.SEED_DEMO_DATA`）。
     *
     * Robolectric 下必须关：单测会反复删除/重建 `databases/dreamkeeper.db`
     * （`DbBackupTest` / `MigrationTest`），而后台 seed 协程可能正打开一个半成品库，
     * 抛出的 schema 校验失败会被 coroutines-test 记成「下一个用例开始前已有未捕获异常」，
     * 让全量单测随机红（实测 3 次全量里 1 次：`TranscriptWriterTest` 报
     * `UncaughtExceptionsBeforeTest`，栈顶 `DreamKeeperApp$onCreate$1.invokeSuspend(DreamKeeperApp.kt:35)`，
     * 原因 `Pre-packaged database has an invalid schema: dreams`）。生产 debug 照常灌。
     */
    val seedDemoOnStart: Boolean
        get() = BuildConfig.SEED_DEMO_DATA &&
            Build.FINGERPRINT != "robolectric" && Build.MODEL != "robolectric"

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
        // 尾段临时文件放 cache：audio 目录里的 *.wav 会被 AudioScan 当「孤儿录音」恢复成草稿。
        TranscriptWriter(repository, audioStore, sttEngine, tempDir = File(appContext.cacheDir, "stt-tail"))
    }

    // ---------------------------------------------------------------- 生成链（Plan 3 / Task 8）

    /** 随 APK 打包的价卡，进程内解析一次；[PriceCard.rawJson] 要原样进 `llm_calls` 快照。 */
    val priceCard: PriceCard by lazy { AssetPriceCardSource(appContext).load() }

    /** 提示词渲染器：读 `assets/prompts/` 下的提示词资产，每次渲染校验一次 sha256 前缀。 */
    val promptRepository: PromptRepository by lazy { PromptRepository(AssetPromptSource(appContext)) }

    /** DeepSeek 客户端（baseUrl/超时/RetryPolicy 都用 Task 3 的默认值）。 */
    val llmClient: LlmClient by lazy { DeepSeekClient() }

    /**
     * 五家客户端（Plan 6 T2）。
     *
     * DeepSeek 复用 [llmClient]（请求体基线不许回归）；其他四家走 spec 驱动的
     * [ProviderChatClient]。调用方按 `ChatRequest.provider` 取；本任务只建映射，
     * 真正按 provider 派发在 T4/T5 接进生成链。
     */
    val providerClients: Map<ProviderId, LlmClient> by lazy {
        ProviderId.entries.associateWith { provider ->
            if (provider == ProviderId.DEEPSEEK) {
                llmClient
            } else {
                ProviderChatClient(ProviderRegistry.of(provider))
            }
        }
    }

    /**
     * 生成档位（模型 + 小说化思考档位，Plan 5 Task 2）。
     *
     * 与排布偏好共用 `settings` 文件；编排器每次请求现取，所以设置页一保存就生效，
     * 不必重建容器。
     */
    /** 皮肤（Plan 7）：与生成档位共用 settings 文件，但键独立。 */
    val themePrefs: ThemePrefs by lazy {
        ThemePrefs(appContext.getSharedPreferences(ThemePrefs.PREFS_NAME, Context.MODE_PRIVATE))
    }

    val themeController: ThemeController by lazy { ThemeController(themePrefs) }

    val generationPrefs: GenerationPrefs by lazy {
        GenerationPrefs(appContext.getSharedPreferences(GenerationPrefs.PREFS_NAME, Context.MODE_PRIVATE))
    }

    /**
     * 「测试连接」（Plan 6 T3）：独立短超时 client、单次请求、只测已保存的 Key。
     *
     * 不复用 [providerClients]：生成链的 300s/180s 是给长文用的，测试连接 20s/15s/10s 才合理；
     * 也不复用 `RetryPolicy()` 的 4 次预算——这里 `maxAttempts=1`。
     */
    val connectionTester: ConnectionTester by lazy {
        ConnectionTester(
            clientFor = { provider ->
                if (provider == ProviderId.DEEPSEEK) {
                    DeepSeekClient(
                        callTimeoutMs = 20_000,
                        readTimeoutMs = 15_000,
                        retry = com.dreamkeeper.llm.RetryPolicy(maxAttempts = 1),
                    )
                } else {
                    ProviderChatClient(
                        spec = ProviderRegistry.of(provider),
                        callTimeoutMs = 20_000,
                        readTimeoutMs = 15_000,
                        connectTimeoutMs = 10_000,
                        retry = com.dreamkeeper.llm.RetryPolicy(maxAttempts = 1),
                    )
                }
            },
            keyOf = { provider -> apiKeyStore.load(provider) },
        )
    }

    val generationStore: GenerationStore by lazy { RoomGenerationStore(database) }

    /**
     * 生成编排器（Task 8 的唯一入口）。
     *
     * `apiKey` 每次调用现取：用户可能刚在设置页换过 key，缓存住旧值会让一整天都在用错 key。
     * `tagsFor` 也现查——标签可能在两个 tab 之间被改；编排器只拿拼接好的「标签 / 选填」素材，
     * 不把仓库漏进 llm 层。
     */
    val generationOrchestrator: GenerationOrchestrator by lazy {
        GenerationOrchestrator(
            llm = llmClient,
            prompts = PromptRepositoryAdapter(promptRepository),
            store = generationStore,
            priceCard = priceCard,
            options = { generationPrefs.options() },
            apiKey = { apiKeyStore.load().orEmpty() },
            clientFor = { provider -> providerClients[provider] ?: llmClient },
            apiKeyFor = { provider -> apiKeyStore.load(provider).orEmpty() },
            tagsFor = { dreamId ->
                repository.observeTagsForDream(dreamId).first().map { it.name }
            },
        )
    }
}
