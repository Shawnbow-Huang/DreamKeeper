# Plan 2 · 录音与转写（Audio & Transcription）

**目标**：在梦匣里做到「醒来拿起手机，按住一个钮说梦 → 松手得到一条带音频的草稿 → 重启/崩溃后音频还在 → 详情页能播 → 有文字就显示文字，没有就明确告诉用户为什么没有」。本轮**不做**云转写、不做第三方 ASR、不做生成（那是 Plan 3）。

**分支**：`plan2-audio`（从 `plan1-foundation` 的 `e80631a` 切出）
**Plan Base**：`e80631a5e4e99fe870e579ddea944d1976a3af72`
**SDD 工作区**：`D:\DreamKeeper\.superpowers\sdd\2026-10-30-audio-and-transcription\`
**执行方式**：`superpowers:subagent-driven-development`，逐任务派全新 implementer + Lead 亲验。

---

## Global Constraints（每个 Task 都适用，违反即返工）

### G-1 命令纪律
- 任何 gradle 命令前必须：`$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`（不设会 `ERROR: JAVA_HOME is not set`）。
- adb 一律绝对路径：`C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe`（**不在 PATH**）。
- 构建：`cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon --console=plain`
- 测试：`cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain`
- 提交只用 `git add <本 Task Files 里列出的路径>`，**禁止 `git add -A`**（Plan 1 有前车之鉴：会把专家产出混进同一个 commit）。

### G-2 证据纪律
- 截图：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan2\<name>.png`
- 近白闸门：`scripts\check-near-white.ps1 -Path a.png,b.png -TopOffset 100 -MaxRatio 0`（`-TopOffset 0` 会被状态栏白字顶红，那是系统的不是我们的）
- 日志：`scripts\capture-logcat.ps1`；判定只看 `FATAL EXCEPTION` / `ANR in com.dreamkeeper`
- 证据目录：`docs/evidence/plan2/`，音频原始产物放 `docs/evidence/plan2/audio/`，命名 `p2-<case>-<nn>-<state>.{png,txt,json,wav}`
- 从设备取二进制**必须**用：`cmd /c "`"$adb`" exec-out run-as com.dreamkeeper cat files/audio/<id>.wav > <out>.wav"`（PowerShell 直接 `>` 会把二进制 UTF‑16LE 化，Plan 1 已踩过）

### G-3 产品与权限纪律
- `minSdk 26 / targetSdk 37 / compileSdk 37`，包名 `com.dreamkeeper`，单 module。
- **不引新依赖**：不加 WorkManager、不加第三方 ASR、不加 ffmpeg、不加音频库。`ServiceCompat` 已在 core-ktx 里，够用。
- **音频永不出机**：APK 保持零 `INTERNET` 权限。任何「顺手加个网络」的想法都直接否决。
- **不许伪造转写成功**：系统识别器在模拟器/无 GMS 机型上大概率不可用，Plan 2 只要求「探测 → 三种状态 → FAILED 有话说 → 手动补字可用」。任何写进证据的「转写成功」必须来自真实引擎返回。

### G-4 WAV 契约（设计 §8.1，逐字遵守，不许自行发挥）
1. 一条梦 = 一个**连续**文件；补录追加到同一文件末尾。
2. 路径固定 `files/audio/<dreamId>.wav`；`dreamId` 在**开录前**生成（先有 id 才有路径，否则进程被杀就是无名孤儿）。
3. PCM 16 kHz 单声道 WAV；头在开录时写好，两个长度字段写 `0xFFFFFFFF` 占位，**松手时回填**。
4. 回收站 30 天内音频保留；物理清除时文件与数据库行成对删除。
5. 启动扫描 `files/audio/`：有行则校验并修复头；没有行则能恢复就恢复成 `transcriptStatus=PENDING` 草稿；反向孤儿（有 `rawAudioPath` 无文件）置空并标 `FAILED`。
6. 焦点被抢（来电/闹钟）**补等长零字节**，`rawAudioMs` 固定为 `(文件字节数 - 44) / 32`，**不许用墙钟算时长**。

### G-5 测试纪律
- 业务逻辑用 JVM 单测（JUnit4；需要 Android 的用 Robolectric）；录音硬件层用**可注入的假源**在 JVM 测。
- 每个 Task 至少一次**变异验证**：故意改坏一处实现，确认对应测试真的变红，再改回。
- 界面不写 UI 单测，靠「构建 → 安装 → 截图」验收。
- 每个 Task 结束时 `testDebugUnitTest` 必须全绿，且要在报告里给出用例总数。

---

## Task 1：WavStore 与 WAV 数学（纯 JVM）

**Files**
- Create `android/app/src/main/java/com/dreamkeeper/audio/WavMath.kt`
- Create `android/app/src/main/java/com/dreamkeeper/audio/WavStore.kt`
- Create `android/app/src/test/java/com/dreamkeeper/audio/WavMathTest.kt`
- Create `android/app/src/test/java/com/dreamkeeper/audio/WavStoreTest.kt`

**Interfaces**
```kotlin
object WavMath {
    const val HEADER_BYTES = 44
    const val PLACEHOLDER_SIZE = 0xFFFF_FFFFL
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_MS = 32L          // 16000Hz * 2B / 1000ms
    const val FRAME_BYTES = 2
    fun dataBytes(fileBytes: Long): Long          // max(0, fileBytes - 44)
    fun durationMs(fileBytes: Long): Long         // dataBytes / 32
    fun trailingPartialBytes(fileBytes: Long): Int
    fun bytesForMs(ms: Long): Long                // ms * 32
}

data class WavState(
    val fileBytes: Long,
    val riffSize: Long,
    val dataSize: Long,
    val placeholder: Boolean,     // 两个字段是否仍是 0xFFFFFFFF
    val framesAligned: Boolean,   // (fileBytes - 44) % 2 == 0
)

class WavStore(private val rootDir: File) {
    fun fileFor(dreamId: String): File
    fun create(dreamId: String): File                       // 写 44 字节占位头
    fun append(stream: FileOutputStream, buffer: ByteArray, length: Int)
    fun finish(file: File): WavState                        // 回填 8 字节并返回状态
    fun repair(file: File): WavState                        // 幂等：已是正确值则原样返回
    fun state(file: File): WavState
}
```

**Steps**
- [ ] 1.1 写 `WavMathTest`（先红）：`durationMs(44)=0`、`durationMs(44+32000)=1000`、`dataBytes(43)=0`、`trailingPartialBytes(45)=1`、`bytesForMs(100)=3200`。
- [ ] 1.2 跑 `testDebugUnitTest --tests "*WavMathTest*"` → 期望 `Unresolved reference: WavMath`（红）。
- [ ] 1.3 实现 `WavMath` → 跑绿。
- [ ] 1.4 写 `WavStoreTest`（先红）：`create` 落盘 44 字节且 offset 4..8、40..44 都是 `0xFFFFFFFF`；`append` 后 `state().fileBytes` 增长；`finish` 回填后 `riffSize == fileBytes-8`、`dataSize == fileBytes-44`、`placeholder == false`；`repair` 对坏头纠正、对好头幂等（第二次调用不改字节）；`state()` 对不存在文件抛 `NoSuchFileException`。
- [ ] 1.5 实现 `WavStore` → 跑绿。
- [ ] 1.6 **变异验证**：把 `durationMs` 改成 `fileBytes / 32`（忘了减头）→ 1.1 必须红；把 `finish` 里的 `dataSize` 写成 `fileBytes` → 1.4 必须红。改回。
- [ ] 1.7 手工造一个 32044 字节的 WAV（44 头 + 1000ms 零 PCM），用 python 打印前 44 字节 hexdump 存 `docs/evidence/plan2/p2-wav-01-header.txt`。
- [ ] 1.8 提交：`feat(audio): WavStore 与 WAV 数学（占位头/回填/repair/时长公式）`

**Expected**：`testDebugUnitTest` 全绿且新增 ≥10 条；hexdump 里 `offset 4..8 = FFFFFFFF`、`offset 40..44 = FFFFFFFF`；`finish` 后两个字段等于实际值。

---

## Task 2：RecorderCore（读循环、补零、收尾）

**Files**
- Create `android/app/src/main/java/com/dreamkeeper/audio/PcmSource.kt`
- Create `android/app/src/main/java/com/dreamkeeper/audio/RecorderCore.kt`
- Create `android/app/src/test/java/com/dreamkeeper/audio/RecorderCoreTest.kt`

**Interfaces**
```kotlin
enum class SourceState { RECORDING, STOPPED, DEAD }

interface PcmSource {
    fun read(buffer: ByteArray): Int      // >0 字节数；<0 错误
    fun state(): SourceState
    fun stop()
}

interface PcmSink {
    fun write(buffer: ByteArray, length: Int)
    fun sync()
    fun bytesWritten(): Long              // 含头之后的 PCM 字节数
}

data class RecorderStop(val bytes: Long, val zeroFillBytes: Long, val reason: String)

class RecorderCore(
    private val source: PcmSource,
    private val sink: PcmSink,
    private val clock: () -> Long,        // elapsedRealtime 毫秒
    private val minGapMs: Long = 100L,
) {
    fun run(startedAtMs: Long, onChunk: (bytes: Long, amplitudeDb: Int) -> Unit): RecorderStop
}
```

**Steps**
- [ ] 2.1 写测试（先红）：正常三块 3200B → `bytes == 9600`、`zeroFillBytes == 0`；`read() == -1` → 返回 `reason="dead"`；`state() == STOPPED` → 结束；时钟前进 200ms 但只读到 3200B → `zeroFillBytes > 0` 且**零字节先写**（用记录写入顺序的假 sink 断言第 1 次写入长度 == deficit、第 2 次才是真实块）；缺口 ≤100ms 不补；`onChunk` 收到单调递增的 bytes。
- [ ] 2.2 跑测试 → 红。
- [ ] 2.3 实现 `RecorderCore` → 绿。
- [ ] 2.4 **变异验证**：把「先补零再写」改成「先写再补零」→ 顺序断言必须红；把 `deficit > minGapMs` 改成 `>= 0` → 「缺口 ≤100ms 不补」必须红。改回。
- [ ] 2.5 提交：`feat(audio): RecorderCore 读循环、按单调钟补等长零帧、收尾原因`

**Expected**：新增 ≥6 条用例；`zeroFillBytes` 与时钟缺口严格对应；写入顺序断言存在。

---

## Task 3：RecordingService（前台服务 + 通知 + 权限）

**Files**
- Modify `android/app/src/main/AndroidManifest.xml`
- Create `android/app/src/main/java/com/dreamkeeper/service/RecordingService.kt`
- Create `android/app/src/main/java/com/dreamkeeper/service/RecordingSession.kt`
- Create `android/app/src/main/java/com/dreamkeeper/service/RecordingNotifications.kt`
- Modify `android/app/src/main/java/com/dreamkeeper/di/AppContainer.kt`
- Create `android/app/src/test/java/com/dreamkeeper/service/RecordingCommandTest.kt`

**Interfaces**
```kotlin
enum class RecordingPhase { IDLE, RECORDING, FINISHING, DONE, ERROR }

data class RecordingSnapshot(
    val dreamId: String? = null,
    val filePath: String? = null,
    val elapsedMs: Long = 0,
    val amplitude: Float = 0f,
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val error: String? = null,
)

class RecordingSession {                       // 进程内单例，AppContainer 持有
    val state: StateFlow<RecordingSnapshot>
    fun begin(dreamId: String, filePath: String)  // 由 Service 调用
    fun update(elapsedMs: Long, amplitude: Float)
    fun finish(bytes: Long)
    fun fail(message: String)
    fun cancel()
}

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.dreamkeeper.action.START"
        const val ACTION_STOP = "com.dreamkeeper.action.STOP"
        const val ACTION_CANCEL = "com.dreamkeeper.action.CANCEL"
        const val EXTRA_DREAM_ID = "dreamId"
        const val EXTRA_ENTRY = "entry"
        const val NOTIFICATION_ID = 42
    }
}
```

**Steps**
- [ ] 3.1 manifest 加四条权限 + `<service android:name=".service.RecordingService" android:exported="false" android:foregroundServiceType="microphone" android:stopWithTask="false"/>`。
- [ ] 3.2 写 `RecordingCommandTest`（纯逻辑，先红）：`ACTION_START` 缺 `dreamId` → 命令非法且不改状态；`STOP` 在 IDLE 时幂等；`CANCEL` 后状态回 IDLE 且 `error == null`。
- [ ] 3.3 实现命令解析（抽成 `RecordingCommand` 纯函数，Service 只做副作用）→ 绿。
- [ ] 3.4 实现 Service：`onStartCommand` 里 **先** `ServiceCompat.startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)`，**再**建 `AudioRecord` 并 `startRecording()`；权限不足 → `snapshot.fail("没有录音权限")` 并 `stopSelf()`；返回 `START_NOT_STICKY`。
- [ ] 3.5 实现通知：常驻、低优先级、`ContentIntent` 回详情、`ACTION_STOP` 按钮；channel 在 `RecordingNotifications` 里建（`IMPORTANCE_LOW`，锁屏可见性 `VISIBILITY_PRIVATE`）。
- [ ] 3.6 `AppContainer` 加 `audioStore`（`WavStore(filesDir/audio)`）与 `recordingSession`。
- [ ] 3.7 构建 + 安装，用 `adb shell am start-foreground-service -n com.dreamkeeper/.service.RecordingService -a com.dreamkeeper.action.START --es dreamId e2e-001` 验证：通知出现、`dumpsys activity services com.dreamkeeper` 有该服务、logcat 无 FATAL。
- [ ] 3.8 证据：`p2-fgs-01-notify.png`（通知栏截图）、`p2-fgs-01-services.txt`（`dumpsys activity services`）、`p2-fgs-01-perms.txt`（`dumpsys package com.dreamkeeper | Select-String FOREGROUND_SERVICE|RECORD_AUDIO`）。
- [ ] 3.9 提交：`feat(record): 前台录音服务（microphone 类型、通知、START/STOP/CANCEL）`

**Expected**：通知出现在状态栏；服务列表里有 `RecordingService`；四条权限都在 `dumpsys package` 里；崩溃 0。

---

## Task 4：录音页与 RecordEntry 解析

**Files**
- Create `android/app/src/main/java/com/dreamkeeper/ui/record/RecordScreen.kt`
- Create `android/app/src/main/java/com/dreamkeeper/ui/record/RecordViewModel.kt`
- Modify `android/app/src/main/java/com/dreamkeeper/ui/record/RecordUiState.kt`
- Modify `android/app/src/main/java/com/dreamkeeper/ui/DreamKeeperRoot.kt`（`Record` 空桩 → 真屏）
- Modify `android/app/src/main/java/com/dreamkeeper/ui/MainActivity.kt`（运行时权限 + 锁屏可见 + intent → `RecordEntry`）
- Create `android/app/src/test/java/com/dreamkeeper/ui/record/RecordViewModelTest.kt`

**Interfaces**
```kotlin
data class RecordUiState(
    val dreamId: String? = null,
    val filePath: String? = null,
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val elapsedMs: Long = 0,
    val amplitude: Float = 0f,
    val error: String? = null,
    val loaded: Boolean = false,
)

class RecordViewModel(
    private val repository: DreamRepository,
    private val session: RecordingSession,
    private val clock: () -> Long,
) {
    val state: StateFlow<RecordUiState>
    fun onPressStart(entry: RecordEntry)     // 生成 dreamId → save(isDraft) → 起服务
    fun onRelease()                          // 发 STOP
    fun onCancel()
    fun parseEntry(raw: String?): RecordEntry
}
```

**Steps**
- [ ] 4.1 写 `RecordViewModelTest`（先红）：`parseEntry("LOCK_TILE")=LOCK_TILE`、`parseEntry(null)=HOME_BUTTON`、`parseEntry("bogus")=HOME_BUTTON`；`onPressStart` 后仓库里出现一条 `isDraft=true` 且 `rawAudioPath` 指向 `files/audio/<id>.wav` 的记录；`onPressStart` 期间 session 状态为 `RECORDING`。
- [ ] 4.2 跑 → 红。
- [ ] 4.3 实现 ViewModel → 绿。
- [ ] 4.4 `MainActivity`：请求 `RECORD_AUDIO`（+ 33+ 的 `POST_NOTIFICATIONS`）；`setShowWhenLocked(true)`/`setTurnScreenOn(true)`，minSdk 26 分支补 `FLAG_SHOW_WHEN_LOCKED`；把 `intent.getStringExtra("entry")` 交给 `RecordRoute`。
- [ ] 4.5 `RecordScreen`：全屏深色，中央大钮（`size(160.dp)`）**按住录、松手停**（`pointerInput` + `detectTapGestures(onPress/onRelease)`），计时文字由 `session.state.elapsedMs` 驱动（**不许自建计时器**），底部「取消」48dp。
- [ ] 4.6 `DreamKeeperRoot` 里 `DreamRoute.Record` 桩替换成真屏，`.route` 用 `RecordEntry.name`。
- [ ] 4.7 构建安装 → 点首页「说梦」→ 按住 3 秒松手 → 截图三态。
- [ ] 4.8 证据：`p2-ux-01-idle.png`、`p2-ux-02-recording.png`、`p2-ux-03-finishing.png`；`files/audio/` 里文件存在且 `stat -c %s` 两次不同（W1）。
- [ ] 4.9 提交：`feat(record): 录音页按住录/松手停，RecordEntry 解析与开录前草稿`

**Expected**：三张截图非白（近白闸门 0）、文件字节在增长、草稿行 `isDraft=1`、松手后状态回 IDLE 且不崩。

---

## Task 5：30 秒追问窗口

**Files**
- Create `android/app/src/main/java/com/dreamkeeper/ui/followup/FollowUpScreen.kt`
- Create `android/app/src/main/java/com/dreamkeeper/ui/followup/FollowUpViewModel.kt`
- Modify `android/app/src/main/java/com/dreamkeeper/ui/record/RecordUiState.kt`（`FollowUpUiState` 去掉硬编码 `30_000`，改由构造注入）
- Modify `android/app/src/main/java/com/dreamkeeper/ui/DreamKeeperRoot.kt`（`FollowUp` 空桩 → 真屏）
- Create `android/app/src/test/java/com/dreamkeeper/ui/followup/FollowUpViewModelTest.kt`

**Interfaces**
```kotlin
data class FollowUpUiState(
    val dreamId: String? = null,
    val remainingMs: Long = 30_000,
    val recording: Boolean = false,
    val visible: Boolean = false,
    val loaded: Boolean = false,
)

class FollowUpViewModel(
    private val repository: DreamRepository,
    private val session: RecordingSession,
    private val clock: () -> Long,
    private val windowMs: Long = 30_000,
) {
    val state: StateFlow<FollowUpUiState>
    fun onSpeakStart()          // 追问录音：追加到同一文件
    fun onSpeakStop()
    fun onTimeout()             // 收起 + 写 rawTranscript + transcriptStatus=PENDING
    fun onEditText(text: String)
}
```

**Steps**
- [ ] 5.1 写测试（先红）：初始 `remainingMs == windowMs`；`onSpeakStart` 期间不递减（录音中不超时）；`onSpeakStop` 后恢复递减；`onTimeout` 后 `visible == false` 且仓库记录 `transcriptStatus == PENDING`、`rawTranscript` 非空；`onEditText` 直接改文本不改状态。
- [ ] 5.2 跑 → 红；5.3 实现 → 绿。
- [ ] 5.4 **变异验证**：把「录音中不递减」去掉 → 5.1 对应用例必须红。
- [ ] 5.5 `FollowUpScreen`：极暗界面（禁纯白）、大号文字显示刚转出来的话、`10..0` 倒计时、麦克风按钮、超时自动收起。倒计时只由 `clock()` 驱动，不用独立 Timer。
- [ ] 5.6 端到端：录 3 秒 → 松手 → 进追问页 → 再录 2 秒 → 超时收起 → 取文件比对 `durationMs` 增加约 2000ms。
- [ ] 5.7 证据：`p2-followup-01.png`、`p2-followup-02-appended.wav`（取回文件）+ `p2-followup-02-ffprobe.txt`。
- [ ] 5.8 提交：`feat(record): 30 秒追问窗口，补录追加到同一 WAV`

**Expected**：追加后 `(bytes-44)/32` 增加约 2000；ffprobe duration 与之相差 < 100ms（W2）；无崩溃。

---

## Task 6：启动扫描与 WAV 头修复

**Files**
- Create `android/app/src/main/java/com/dreamkeeper/audio/AudioScan.kt`
- Modify `android/app/src/main/java/com/dreamkeeper/data/db/DreamDao.kt`（只加 `@Query`，不动 schema）
- Modify `android/app/src/main/java/com/dreamkeeper/data/DreamRepository.kt`
- Modify `android/app/src/main/java/com/dreamkeeper/DreamKeeperApp.kt`（启动时 IO 协程跑一次扫描）
- Create `android/app/src/test/java/com/dreamkeeper/audio/AudioScanTest.kt`

**Interfaces**
```kotlin
sealed interface ScanOutcome {
    data class Repaired(val dreamId: String, val fileBytes: Long) : ScanOutcome
    data class RecoveredDraft(val dreamId: String, val fileBytes: Long) : ScanOutcome
    data class OrphanCleared(val dreamId: String) : ScanOutcome
    data object Noop : ScanOutcome
}

class AudioScan(
    private val store: WavStore,
    private val repository: DreamRepository,
) {
    suspend fun run(): List<ScanOutcome>
}
```

**Steps**
- [ ] 6.1 写测试（Robolectric + 临时目录；先红）：①库里有行 + 文件头是 `0xFFFFFFFF` → 修好后回填正确且库里 `rawAudioMs` 等于新时长；②文件存在但库里没有行 → 恢复成草稿（`isDraft=true`、`transcriptStatus=PENDING`）；③库里有 `rawAudioPath` 但文件丢了 → 置空并标 `FAILED`；④干净文件 → `Noop` 且不写库。
- [ ] 6.2 跑 → 红；6.3 实现 → 绿。
- [ ] 6.4 **变异验证**：把「文件丢了 → 标 FAILED」改成「什么都不做」→ 对应用例红。
- [ ] 6.5 端到端故障注入（选做其一即可，但必须做一次）：录音中 `adb shell am force-stop com.dreamkeeper` → 重启 App → 断言文件头已被修复、`rawAudioMs` 与 `(bytes-44)/32` 一致。
- [ ] 6.6 证据：`p2-scan-01-before.txt`（48 字节 hexdump，两个字段 `FFFFFFFF`）、`p2-scan-02-after.txt`（修复后 hexdump）、`p2-scan-03-db.txt`（sqlite 查询该行）。
- [ ] 6.7 提交：`feat(audio): 启动扫描——孤儿恢复、反向孤儿清空、WAV 头修复`

**Expected**：故障注入后两个长度字段不再是 `FFFFFFFF`，且 `dataSize == fileBytes - 44`；数据库 `rawAudioMs == (fileBytes-44)/32`。

---

## Task 7：SttEngine（可用性探测 + 一次性转写 + 窄更新写回）

**Files**
- Create `android/app/src/main/java/com/dreamkeeper/stt/SttEngine.kt`
- Create `android/app/src/main/java/com/dreamkeeper/stt/SystemSttEngine.kt`
- Create `android/app/src/main/java/com/dreamkeeper/stt/TranscriptWriter.kt`
- Modify `android/app/src/main/java/com/dreamkeeper/data/db/DreamDao.kt`（加窄更新 `@Query`）
- Modify `android/app/src/main/java/com/dreamkeeper/data/DreamRepository.kt`（`updateTranscript`）
- Modify `android/app/src/main/java/com/dreamkeeper/service/RecordingService.kt`（收尾后触发一次转写）
- Create `android/app/src/test/java/com/dreamkeeper/stt/TranscriptWriterTest.kt`

**Interfaces**
```kotlin
sealed interface SttAvailability {
    data object Available : SttAvailability
    data class NeedsDownload(val locale: String) : SttAvailability
    data class Unavailable(val reason: String) : SttAvailability
}

sealed interface SttResult {
    data class Ok(val text: String) : SttResult
    data class Failed(val reason: String) : SttResult
}

interface SttEngine {
    fun availability(): SttAvailability
    suspend fun transcribe(file: File): SttResult
}

class TranscriptWriter(private val repository: DreamRepository) {
    suspend fun apply(dreamId: String, result: SttResult)   // Ok(非空)→OK；其余→FAILED
}
```

**Steps**
- [ ] 7.1 写 `TranscriptWriterTest`（先红）：`Ok("我在电梯里")` → `transcriptStatus=OK` 且 `rawTranscript` 被替换；`Failed("无中文离线包")` → `FAILED` 且**原文不被清空**（已有文字保留）；`Ok("")` → `FAILED`（空串不算成功）。
- [ ] 7.2 跑 → 红；7.3 实现 → 绿。
- [ ] 7.4 `DreamDao` 加 `@Query("UPDATE dreams SET rawTranscript = :text, transcriptStatus = :status, updatedAt = :now WHERE id = :id") suspend fun updateTranscript(id: String, text: String, status: String, now: Long)`；`DreamRepository.updateTranscript(...)` 包一层。**不许**用整行 `save()` 写转写结果（会覆盖用户刚输入的文字）。
- [ ] 7.5 `SystemSttEngine`：`availability()` 用 `isOnDeviceRecognitionAvailable`（API 31+）/`isRecognitionAvailable`；`transcribe` 必须**主线程**创建 `SpeechRecognizer`，API 33+ 才用 `RecognizerIntent.EXTRA_AUDIO_SOURCE`（`ParcelFileDescriptor`，**先 seek 跳过 44 字节头**）+ `EXTRA_SEGMENTED_SESSION`，`checkRecognitionSupport` 不过则返回 `Failed`；**API < 33 直接返回 `Failed("系统不支持文件转写")`**，不试着抢麦。超时 60 秒；取消在 `finally` 里 `destroy()`。
- [ ] 7.6 Service 收尾后调一次 `TranscriptWriter.apply`（IO 协程），失败不影响录音落盘。
- [ ] 7.7 设备证据：跑一次真实探测，把 `availability()` 结果写进 `p2-stt-01-availability.txt`；若 `Unavailable/NeedsDownload`，再截图详情页的「没转出文字」提示与「手动补字」入口。
- [ ] 7.8 提交：`feat(stt): 系统转写引擎（可用性探测、API33+ 文件转写、窄更新写回）`

**Expected**：`TranscriptWriterTest` 全绿；探测结果入档；`FAILED` 路径用户看得到一句话与可点的手动补字；**没有任何伪造的成功转写证据**。

---

## Task 8：详情页播放（播前 repair）

**Files**
- Create `android/app/src/main/java/com/dreamkeeper/ui/detail/AudioPlayback.kt`
- Modify `android/app/src/main/java/com/dreamkeeper/ui/detail/DetailScreen.kt`
- Create `android/app/src/test/java/com/dreamkeeper/ui/detail/AudioPlaybackStateTest.kt`

**Interfaces**
```kotlin
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Playing(val positionMs: Long, val durationMs: Long) : PlaybackState
    data class Paused(val positionMs: Long) : PlaybackState
    data class Error(val message: String) : PlaybackState
}

class AudioPlayback(private val scope: CoroutineScope) {
    val state: StateFlow<PlaybackState>
    fun prepare(file: File, fallbackDurationMs: Long)   // 播前先 WavStore.repair
    fun toggle()
    fun release()
}
```

**Steps**
- [ ] 8.1 写测试（先红）：`Playing.positionMs` 单调不减；`durationMs` 优先取 `rawAudioMs`，为 null 时用 `WavMath.durationMs(fileBytes)`；`Error` 时进度条显示「打不开」而不是 0。
- [ ] 8.2 跑 → 红；8.3 实现 → 绿。
- [ ] 8.4 `DetailScreen` 播放条接 `AudioPlayback`：**先 repair 再 `MediaPlayer.setDataSource`**；播放/暂停切换；进度跟手；`rawAudioPath == null` 时按钮禁用并提示「这条梦没有录音」。
- [ ] 8.5 端到端：录 5 秒 → 进详情 → 点播放 → 3 秒后截图（进度条非零）→ 点暂停 → 截图。
- [ ] 8.6 证据：`p2-play-01-playing.png`、`p2-play-02-paused.png`、`p2-play-01-logcat.txt`（无 `MediaPlayer error`）。
- [ ] 8.7 提交：`feat(detail): 详情页播放真实 WAV（播前 repair、时长取 rawAudioMs）`

**Expected**：进度条随播放前进；暂停后位置保持不变；无 `MediaPlayer error(...)`；`rawAudioMs` 与 `(bytes-44)/32` 一致。

---

## Task 9：端到端验收与证据

**Files**
- Modify `docs/evidence/plan1/ACCEPTANCE.md`（**只追加** `## 3b 音频证据表`，不改原有九列）
- Create `docs/evidence/plan2/` 下全部产物
- Modify `docs/PROGRESS.md`（Plan 2 进度）

**Steps**
- [ ] 9.1 干净安装 + `pm clear`，走完整链路：首页「说梦」→ 按住 4 秒 → 松手 → 追问页再录 2 秒 → 超时收起 → 详情页播放。
- [ ] 9.2 权限两路：`pm revoke com.dreamkeeper android.permission.RECORD_AUDIO` → 点「说梦」→ 应给出「没有录音权限」而不是崩溃；`pm grant` 恢复后再走一遍。
- [ ] 9.3 崩溃恢复：录音中 `am force-stop` → 重启 → 扫描修复 → 详情页能播。
- [ ] 9.4 把 `## 3b 音频证据表` 填满，每行要有：`wav | bytes | RIFF/data 字段 | dataSize==bytes-44 | (bytes-44)/32 ms | ffprobe duration | Δms | RMS dBFS | 信号来源 | 结论`。
- [ ] 9.5 在 `## 0 构建绑定` 补一行「模拟器启动参数：`-allow-host-audio` 未启用（输入为静音）」，并在证据里写明「本机录音的 W3 信号来源 = seam / 静音，不代表真人说话质量」。
- [ ] 9.6 冷启动 3 次 `am start -W`、`capture-logcat.ps1 -Runs 3`、近白闸门全图 `-TopOffset 100 -MaxRatio 0`。
- [ ] 9.7 更新 `docs/PROGRESS.md`，提交；**不要自己打 tag**（Lead 验收后打 `plan2-done`）。

**Expected**：链路走通；`(bytes-44)%32 == 0`；表格无空单元格；崩溃 0；`FATAL`/`ANR` 零命中。

---

## 偏差表（接着 Plan 1，从 D88 起）

| 编号 | 原计划的写法 | 实际做法 | 原因 | 类型 |
|---|---|---|---|---|
| D88 | （执行中追加） | | | 以为的坑 / 实测结论 |

> 规则：每发现一处与计划的偏离就追加一行；「原因」必须写能被别人复核的事实，不写「感觉」。

