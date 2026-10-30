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
- adb 一律绝对路径：`C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe`（**不在 PATH**）；本文件后面写成 `$adb = ``C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe`` 或直接用全路径，不许裸 `adb`。
- 构建：`$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`；`cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon --console=plain`
- 测试：`$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`；`cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain`
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

**Interfaces**（已按 Mobile App Builder 预审修订：单一写句柄、全 44 字节黄金断言、小端 helper）
```kotlin
object WavMath {
    const val HEADER_BYTES = 44
    const val PLACEHOLDER_SIZE = 0xFFFF_FFFFL
    const val SAMPLE_RATE = 16_000        // 本 Task 暂不调用，留给 AudioRecord（别删）
    const val FRAME_BYTES = 2             // 同上
    const val BYTES_PER_MS = 32L          // 16000Hz * 2B / 1000ms
    fun dataBytes(fileBytes: Long): Long                  // max(0, fileBytes - 44)
    fun durationMs(fileBytes: Long): Long                 // dataBytes / 32
    fun trailingPartialBytes(fileBytes: Long): Int         // (fileBytes-44) % 2 且 <0 时返回 0
    fun bytesForMs(ms: Long): Long                         // ms * 32
    fun writeU32(buf: ByteArray, offset: Int, value: Long)  // **小端**，内部 value.and(0xFFFFFFFFL)
    fun readU32(buf: ByteArray, offset: Int): Long          // **小端**，返回无符号
}

data class WavState(
    val fileBytes: Long,
    val riffSize: Long,
    val dataSize: Long,
    val placeholder: Boolean,     // 两个字段**任一**仍是 0xFFFFFFFF 就是 true
    val framesAligned: Boolean,   // fileBytes >= 44 && (fileBytes - 44) % 2 == 0
)

class WavStore(private val rootDir: File) {
    fun fileFor(dreamId: String): File
    fun create(dreamId: String): File      // rootDir.mkdirs()；已存在则截断重写 44 字节占位头
    fun openWriter(file: File): WavWriter  // 唯一写句柄；写完必须 finish()
    fun finish(file: File): WavState       // 扫描/离线用：等价 repair + sync
    fun repair(file: File): WavState       // 幂等；fileBytes < 44 抛 IllegalStateException
    fun state(file: File): WavState        // 文件不存在抛 java.nio.file.NoSuchFileException
}

/** 生产路径与测试路径**共用**这一个写出入口，避免两套头各写各的。 */
interface WavWriter : Closeable {
    val bytesWritten: Long             // 只算 PCM，不含 44 字节头
    fun write(buffer: ByteArray, length: Int)
    fun finish(): WavState             // sync() → 回填两字段 → close()；可重复调用
}
```

**44 字节 canonical 头（全小端；漏一个空格就废）**

| offset | 字节 | 值（32044 字节样本时） |
|---|---|---|
| 0..4 | `RIFF` | 52 49 46 46 |
| 4..8 | riffSize | `fileBytes-8` = 32036 = `24 7D 00 00`（占位期 `FF FF FF FF`） |
| 8..12 | `WAVE` | 57 41 56 45 |
| 12..16 | `fmt ` | 66 6D 74 **20**（第 4 个字节是空格） |
| 16..20 | 16 | 10 00 00 00 |
| 20..22 | format=1 | 01 00 |
| 22..24 | channels=1 | 01 00 |
| 24..28 | sampleRate=16000 | 80 3E 00 00 |
| 28..32 | byteRate=32000 | 00 7D 00 00 |
| 32..34 | blockAlign=2 | 02 00 |
| 34..36 | bitsPerSample=16 | 10 00 |
| 36..40 | `data` | 64 61 74 61 |
| 40..44 | dataSize | `fileBytes-44` = 32000 = `00 7D 00 00`（占位期 `FF FF FF FF`） |

**注意**：Kotlin 的 `DataOutputStream.writeInt` 是**大端**，本 Task 一律用 `WavMath.writeU32/readU32`；`0xFFFFFFFF` 不要当 `Int` 比较（`Int == Long` 直接编译错）。

**Steps**
- [ ] 1.1 写 `WavMathTest`（先红）：`durationMs(44)=0`、`durationMs(44+32000)=1000`、`dataBytes(43)=0`、`trailingPartialBytes(43)=0`、`trailingPartialBytes(45)=1`、`trailingPartialBytes(46)=0`、`bytesForMs(100)=3200`、`writeU32`/`readU32` 往返 `0xFFFFFFFF` 得 `4294967295L`。
- [ ] 1.2 跑 `testDebugUnitTest --tests "*WavMathTest*"` → 期望 `Unresolved reference: WavMath`（红）。
- [ ] 1.3 实现 `WavMath` → 跑绿。
- [ ] 1.4 写 `WavStoreTest`（先红）：
  - `create` 落盘正好 44 字节，且**整 44 字节逐字节等于「占位头黄金 hex」常量**（不是只看 offset 4..8 和 40..44——只看那两处，`fmt ` 尾空格、byteRate、bits 全写错也会绿）；
  - `openWriter` 写 32000 字节零 → `bytesWritten == 32000`；`finish()` 后 `riffSize == fileBytes-8`、`dataSize == fileBytes-44`、`placeholder == false`，且**整 44 字节逐字节等于「回填头黄金 hex」常量**；
  - `repair` 对已回填文件**幂等**（调用两次字节完全不变）；对占位头的 32044 字节文件纠正成功；对 43 字节文件抛 `IllegalStateException`；`state()` 对不存在文件抛 `NoSuchFileException`。
- [ ] 1.5 实现 `WavStore`/`WavWriter` → 跑绿。
- [ ] 1.6 **变异验证**：把 `durationMs` 改成 `fileBytes / 32`（忘了减头）→ 1.1 必须红；把 `writeU32` 改成大端 → 1.4 的整头黄金断言必须红。改回。
- [ ] 1.7 **第三方交叉校验**（用 python 独立造一枚 32044 字节、**已回填**的 canonical WAV；注意 `riffSize = 32036` 不是 32044、`b"fmt "` 尾空格、`open(...,'wb')`、`struct.pack('<I',...)`）：
  ```powershell
  python -c "import struct;h=b'RIFF'+struct.pack('<I',32036)+b'WAVE'+b'fmt '+struct.pack('<I',16)+struct.pack('<HHIIHH',1,1,16000,32000,2,16)+b'data'+struct.pack('<I',32000);assert len(h)==44;f=open('docs/evidence/plan2/audio/p2-wav-01.wav','wb');f.write(h+b'\x00'*32000);f.close()"
  ```
  然后让本 Task 的 `create + writer.write(32000 零) + finish()` 产出另一枚文件，两者 **sha256 必须相同**；两个 sha256 都写进证据 txt。
- [ ] 1.8 ffprobe 校验（**必须连 stderr 一起看**）：期望 `pcm_s16le / 16000 Hz / 1 channel / duration=1.000000 / size=32044`，且 stderr **不出现** `[wav @`（出现 `Ignoring maximum wav data size` / `Estimating duration from bitrate` / `Packet corrupt` 即判失败）。存 `docs/evidence/plan2/p2-wav-01-ffprobe.txt`。
- [ ] 1.9 打印前 44 字节的 **xxd 风格** hexdump（带 offset 列 + ASCII 列，别用 `bytes.hex()` 单行，无法按 offset 引用）存 `docs/evidence/plan2/p2-wav-01-header.txt`，文件头 6 行写 `case / artifact / bytes / sha256 / generator / expect`。
- [ ] 1.10 提交（**带 4 个证据文件**）：`feat(audio): WavStore 与 WAV 数学（占位头/单一写句柄/回填/repair/时长公式）`

**Expected**：`testDebugUnitTest` 全绿且新增 ≥14 条；「占位头黄金 hex」与「回填头黄金 hex」两个常量都被逐字节断言；python fixture 与 `WavWriter` 产出 sha256 相同；ffprobe `duration=1.000000` 且 stderr 无 `[wav @` 行。**`(bytes-44)%32 != 0` 时契约未规定怎么处理——不许自行裁剪、补齐或四舍五入，先在偏差表记一行（D96）再问 Lead**。

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
    /** >0 = 读到的字节数；0 = 这一轮暂时没有数据（**不结束**，继续循环）；<0 = 读错误（必须结束）。 */
    fun read(buffer: ByteArray): Int
    fun state(): SourceState
    fun stop()
}

/** 与 WavWriter 的适配层：WavWriter 的 bytesWritten 是 val、且没有 sync()，不能直接当 PcmSink 用。 */
interface PcmSink {
    fun write(buffer: ByteArray, length: Int)   // length 必须偶数
    fun sync()
    fun bytesWritten(): Long                    // 绝对偏移：44 字节头之后的 PCM 总字节数
    fun finish()
}

data class RecorderStop(
    val bytes: Long,          // 本次 run 期间写入的**真实** PCM 字节（不含补零）
    val zeroFillBytes: Long,  // 本次 run 期间补进去的零字节（一定偶数）
    val reason: String,       // 只允许 "dead" | "stopped" | "user" | "error" 四个值
)

class RecorderCore(
    private val source: PcmSource,
    private val sink: PcmSink,
    private val clock: () -> Long,          // elapsedRealtime 毫秒；JVM 测试注入假钟，**禁止**默认 SystemClock
    private val minGapMs: Long = 100L,
    private val silenceFloorDb: Int = -60,  // 振幅下限
) {
    /** 前移两个原点：startMs = clock(); baseBytes = sink.bytesWritten()。暂停恢复、追问窗口超时都调它。 */
    fun rebase()

    fun run(startedAtMs: Long, onChunk: (bytes: Long, amplitudeDb: Int) -> Unit): RecorderStop
}
```

**补零与振幅契约（唯一允许的算法，别自己发明）**
- 不变量：`deficitSamples = (clock() - startMs) * 16 - (sink.bytesWritten() - baseBytes) / 2`（样本）。
- `deficitSamples > minGapMs * 16`（默认 1600 样本 = 100 ms）才补；**补进去的字节数 = deficitSamples * 2**（1600 样本 = 3200 B）。
- 先补等长零字节，**再**写刚读到的真实块；顺序反了会把声音推到静音之后。
- 时钟必须在**调用 `read` 之前**采样；在读之后采样会把刚读到的这块算进缺口、再前补一遍（音频加倍）。
- 缺口 ≤ 阈值时**不补、也不重置基线**：累计到下次超阈值一次补齐，不做逐轮漂移修正。
- `rebase()` **只**改 `startMs` 与 `baseBytes`，**不许**去动 `sink.bytesWritten()`（它是绝对偏移，回填 data size 依赖它）。
- 振幅：每块按 `20*log10(rms/32768)` 算；全零块直接取 `silenceFloorDb`（否则 `log10(0) = -Infinity`，`.toInt()` 会变成 `Int.MIN_VALUE`）；`amplitudeDb ∈ [silenceFloorDb, 0]`；UI 映射 `(db - silenceFloorDb) / -silenceFloorDb` → `0..1`。

**Steps**
- [ ] 2.1 写测试（先红），至少九组：
  - 对照组：三块各 3200 B、时钟不前进 → `bytes == 9600`、`zeroFillBytes == 0`、`write` 调用次数 == 3（**这条是「≤100 ms 不补」的唯一杀手**）。
  - 补零组：时钟前进 250 ms、只读到 3200 B → expected 4000 样本、actual 1600、deficit 2400 → 先写 **4800** 字节零、再写 3200 字节真实块；假 source 每块填可区分的值，断言 `out[0,4800)` 全 0 且 `out[4800,8000)` 逐字节等于第 1 块；期望常量在**测试侧**用 `BYTES_PER_MS` 算，**不许**拿 `result.zeroFillBytes` 反推。
  - 边界组：时钟前进 100 ms、读到 3200 B → deficit 恰 1600，**不补**（`> 1600` 才补）、`zeroFillBytes == 0`、`write` 次数 == 1。
  - `read() == -1` → `reason == "dead"`；`read() == 0` → 不结束、继续读（不得当 EOF）。
  - `state() == STOPPED` → `reason == "stopped"`；外部 `stop()` → `reason == "user"`。
  - 追加组：sink 起始 `bytesWritten() == 1044`（44 头 + 1000 B PCM）、`baseBytes == 1044`、首块 3200 B → deficit 为 0、不补（防止拿绝对偏移当分子）。
  - `rebase()` 组：补过一次之后 rebase → 新窗口 deficit 从 0 起算，不追溯 rebase 之前的缺口。
  - 振幅组：全零块 `amplitudeDb == silenceFloorDb`；正常块落在 `[silenceFloorDb, 0]`；UI 映射公式单测 `(db+60)/60`。
  - 形状组：每次补零字节数为偶数；`onChunk` 的 `bytes` 单调递增。
- [ ] 2.2 跑测试 → 红。
- [ ] 2.3 实现 `RecorderCore` → 绿。`deficitSamples` 用 `Long`；`read` 的 buffer 大小取 `max(getMinBufferSize, 3200)`（Task 3 决定实例值，这里按 `buffer.size` 自适应）。
- [ ] 2.4 **变异验证**（四条，每条都要真红再改回）：「先补零再写」→「先写后补零」；`deficitSamples > minGapMs * 16` → `>= 0`；补零字节 `deficitSamples * 2` → `deficitSamples`（只补一半）；时钟采样从 `read` 之前挪到之后。
- [ ] 2.5 提交：`feat(audio): RecorderCore 读循环、按单调钟补等长零帧、rebase 与收尾原因`

**Expected**：新增 ≥9 条用例；`zeroFillBytes` 与时钟缺口严格对应（样本→字节要 ×2）；写入顺序断言存在且期望值不取自被测输出；`reason` 只会是四个字面量之一；振幅不会出现 `Int.MIN_VALUE`；`(bytes-44)%32 != 0` 时**不许自行处理**，先记偏差表再问 Lead。

---

## Task 3：RecordingService（前台服务 + 通知 + 权限）

**Files**
- Modify `android/app/src/main/AndroidManifest.xml`
- Create `android/app/src/main/java/com/dreamkeeper/service/RecordingService.kt`
- Create `android/app/src/main/java/com/dreamkeeper/service/RecordingSession.kt`
- Create `android/app/src/main/java/com/dreamkeeper/service/RecordingNotifications.kt`
- Modify `android/app/src/main/java/com/dreamkeeper/di/AppContainer.kt`
- Create `android/app/src/main/res/drawable/ic_notification.xml`
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

**接口补充（Lead 按两位专家预审钉死，覆盖上面代码块里没写死的部分）**
```kotlin
/** 3.2/3.3 唯一被测对象：纯函数，不碰 Android。 */
sealed interface RecordingCommand {
    data class Start(val dreamId: String) : RecordingCommand
    data object Stop : RecordingCommand
    data object Cancel : RecordingCommand
}

object RecordingCommands {
    /** 非法输入（例如 START 缺 dreamId、未知 action）返回 null，调用方只 fail + stopSelf，不改状态。 */
    fun parse(action: String?, dreamId: String?): RecordingCommand?
}
```
- `RecordingSnapshot`（session）是**唯一真相**；`ui/record/RecordUiState.kt` 只是 UI 侧快照，Task 4 负责把 `session.state` 映射过去，**不许**两边各起一个计时器。
- `RecordingPhase` / `RecordingSnapshot` / `RecordingCommand` / `RecordingCommands` 都落 `RecordingSession.kt`；`RecordingService.kt` 只有 Service 与通知装配。
- 通知小图标用**新建**的 `res/drawable/ic_notification.xml`（`res/drawable/` 现在只有 `ic_launcher_dark.xml`，写 `R.drawable.ic_notification` 而没这个文件就是编译错）。
- 通知 `ContentIntent` → `MainActivity` + `entry=NOTIFICATION`（`DreamRoute.Detail` 没有 deep link，点通知最多只能到首页/录音页；详情深链留给 Plan 3，见 D103），`PendingIntent` 必须带 `FLAG_IMMUTABLE`。

**Steps**
- [ ] 3.1 manifest 加**四条**权限，名字逐字写对：`android.permission.RECORD_AUDIO`、`android.permission.POST_NOTIFICATIONS`、`android.permission.FOREGROUND_SERVICE`、`android.permission.FOREGROUND_SERVICE_MICROPHONE`（漏一个就是启动时 `SecurityException`）；四条插在 `<manifest>` 与 `<application>` 之间。再加 `<service android:name=".service.RecordingService" android:exported="false" android:foregroundServiceType="microphone" android:stopWithTask="false"/>`，插在 `<application>` 内。**服务只能由可见 Activity 起**（`startForegroundService` 用 Activity 的 Context，不能用 applicationContext）。
- [ ] 3.2 写 `RecordingCommandTest`（纯逻辑，先红）：`parse(START, null)` == null（缺 dreamId 即非法）；`parse(START, "d1")` == `Start("d1")`；`parse(STOP, null)` == `Stop`；`parse(CANCEL, null)` == `Cancel`；`parse("whatever", "d1")` == null；`parse(null, null)` == null。**断言不许读 Service 状态**（Service 在 JVM 测里跑不了）。
- [ ] 3.3 实现命令解析（抽成 `RecordingCommand` 纯函数，Service 只做副作用）→ 绿。
- [ ] 3.4 实现 Service 的 `onStartCommand`，顺序**钉死**：① 解析命令（非法 → `stopSelf()`，什么都不改）；② `RecordingCommands` 给出 START 时**先查 `RECORD_AUDIO`**，没有 → `snapshot.fail("没有录音权限")` + `stopSelf()`，**不调** `startForeground`（14+ 用 microphone 型且无权限时 `startForeground` 自己抛 `SecurityException`）；③ `recordingSession.begin(dreamId, filePath)`、`WavStore.create` 落占位头；④ `RecordingNotifications.ensureChannel()` **先建渠道**；⑤ `ServiceCompat.startForeground(42, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)`；⑥ 才 `AudioRecord` + `startRecording()`；返回 `START_NOT_STICKY`。
- [ ] 3.4b `AudioRecord` 参数**钉死**：`MediaRecorder.AudioSource.VOICE_RECOGNITION`、`16_000`、`AudioFormat.CHANNEL_IN_MONO`、`AudioFormat.ENCODING_PCM_16BIT`；buffer 取 `max(AudioRecord.getMinBufferSize(...), 3200)`；读循环放**专用线程**并先 `Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)`；写文件**不套 `BufferedOutputStream`**（多一次拷贝、崩溃时丢缓冲）；每约 1 秒 `fd.sync()` 一次。
- [ ] 3.4c `RecordingService` 把 `RecorderCore.run` 的 `onChunk(bytes, amplitudeDb)` 转成状态：`session.update(elapsedMs = bytes / 32, amplitude = (amplitudeDb + 60) / 60f)`（`silenceFloorDb = -60`，见 Task 2 的振幅契约；**不许**另起定时器或随机数算振幅，也不许在这里再算一次 RMS）。
- [ ] 3.4d 录音中途被吊销权限：`AudioRecord` 读循环捕获 `SecurityException` / `read()` 返负 → `snapshot.fail("录音权限被收回")` + 收尾（`finish()` 回填 + `stopSelf()`），不许把异常吞掉留在录音态。
- [ ] 3.5 实现通知：常驻、`IMPORTANCE_LOW`、锁屏 `VISIBILITY_PRIVATE`、`setOngoing(true)`；`ContentIntent` → `MainActivity` + `entry=NOTIFICATION` + `FLAG_IMMUTABLE`；`ACTION_STOP` 走 `PendingIntent.getService(...)` 指向本 Service 的 STOP；渠道在 `RecordingNotifications.ensureChannel()` 里建。
- [ ] 3.6 `AppContainer` 加 `audioStore`（`WavStore(filesDir/audio)`）与 `recordingSession`。
- [ ] 3.7 构建 + 安装，**从可见页面起服务**：`adb shell am start -n com.dreamkeeper/.MainActivity`（shell 起 Activity 不受后台启动限制）→ 首页点「说梦」→ `adb shell input swipe 540 1600 540 1600 3000` 或手按住 3 秒 → 松手。判据四连：① `dumpsys activity services com.dreamkeeper` 同一段里 `started=true` 且 `isForeground=true` 且 `foregroundId=42` 且类型含 `microphone`；② `dumpsys notification --noredact` 里 id=42、`flags` 含 `0x40`、ongoing；③ 两次 `run-as com.dreamkeeper stat -c %s files/audio/<id>.wav` 间隔 ≥3 s，`Δbytes/Δt` 落在 32000 B/s ±2%（这是「AudioRecord 真在供数」的唯一自动判据）；④ `logcat -d` 里 `FATAL` / `ForegroundServiceStartNotAllowed` / `DidNotStartInTime` 全空。**不要**用 `adb shell am start-foreground-service …`（shell 是后台，14+ 必抛 `ForegroundServiceStartNotAllowedException`），证据目录里也不许出现该命令的失败日志。
- [ ] 3.8 证据（存 `docs/evidence/plan2/`）：`p2-fgs-01-notify.png`（`cmd statusbar expand-notifications` 后截图）、`p2-fgs-01-services.txt`（`dumpsys activity services com.dreamkeeper`）、`p2-fgs-01-notify-dump.txt`（`dumpsys notification --noredact` 里本包那段）、`p2-fgs-01-perms.txt`（`dumpsys package com.dreamkeeper | Select-String RECORD_AUDIO|FOREGROUND_SERVICE`）、`p2-fgs-01-bytes.txt`（两次 `stat` 的原始输出）。
- [ ] 3.9 提交：`feat(record): 前台录音服务（microphone 类型、通知、START/STOP/CANCEL）`
- [ ] 3.10 权限三分支要在 UI 侧各停在各处（本条只做 Service 侧与文案约定，界面接入在 Task 4）：① 还能问 → 停在录音页、**不调** `startForegroundService`，文案「需要麦克风权限才能录梦」+「再试一次」；② 永久拒绝（已拒且 `!shouldShowRequestPermissionRationale`）→ 停在录音页，只给「去设置」（`ACTION_APPLICATION_DETAILS_SETTINGS` + `package:com.dreamkeeper`）；③ 33+ 通知权限被关 → **不拦录音**（FGS 照起、录音照走，只是通知栏看不到、也就没有停止按钮），文案写明这一点并提示去开通知。

**Expected**：从可见页面按住 3 秒后，通知出现（`dumpsys notification --noredact` 有 id=42、flags 含 `0x40`）；`dumpsys activity services com.dreamkeeper` 里既有 `started=true` **又有** `isForeground=true`、`foregroundId=42`、类型含 `microphone`；两次 `stat` 的字节速率落在 32000 B/s ±2%；四条权限都在 `dumpsys package` 里；`logcat` 里 `FATAL` / `ForegroundServiceStartNotAllowed` / `DidNotStartInTime` 全空。松手后 WAV 头 `offset 4..8` 与 `40..44` 已回填（不再是 `FF FF FF FF`）。

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
- [ ] 4.5 `RecordScreen`：全屏深色，中央大钮（`size(160.dp)`）**按住录、松手停**（`pointerInput` + `detectTapGestures(onPress/onRelease)`），计时文字由 `session.state.elapsedMs` 驱动（**不许自建计时器**）；振幅条读 `session.state.amplitude`（0..1，由 `RecorderCore.onChunk` 的 dBFS 归一化而来），**不许每帧随机**；底部「取消」48dp。
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
- [ ] 5.4b 追问录音前先 `rebase()`：把 `startMs` 与已写字节数一起前移，再起服务追加；超时收起时也前移一次。否则「松手到点麦克风」那几秒会按 32 B/ms 补成一大段静音，`rawAudioMs` 直接虚高。
- [ ] 5.5 `FollowUpScreen`：极暗界面（禁纯白）、大号文字显示刚转出来的话、`10..0` 倒计时、麦克风按钮、超时自动收起。倒计时只由 `clock()` 驱动，不用独立 Timer。
- [ ] 5.6 端到端：录 3 秒 → 松手 → 进追问页 → 再录 2 秒 → 超时收起 → 取文件比对 `durationMs` 增加约 2000ms。
- [ ] 5.7 证据：`p2-followup-01.png`、`docs/evidence/plan2/audio/p2-followup-02-appended.wav`（取回文件）+ `p2-followup-02-ffprobe.txt`。
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
- [ ] 6.5 端到端故障注入（选做其一即可，但必须做一次）：录音中 `& "C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell am force-stop com.dreamkeeper` → 重启 App → 断言文件头已被修复、`rawAudioMs` 与 `(bytes-44)/32` 一致。
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
- [ ] 8.4 `DetailScreen` 播放条接 `AudioPlayback`：**先 `WavStore.repair` 再 `MediaPlayer.setDataSource`**（残留 `0xFFFFFFFF` 头的文件 `MediaPlayer` 不一定能直接播）；`PlaybackBar` 的签名不动，只换内部实现；播放/暂停切换；进度跟手；时长显示用 `rawAudioMs`（不是 `MediaPlayer.duration`）；`rawAudioPath == null` 时按钮禁用并提示「这条梦没有录音」。
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

**Interfaces**
- Produces `docs/evidence/plan1/ACCEPTANCE.md` 的 `## 3b 音频证据表`，列固定为 `wav | bytes | RIFF/data 字段 | dataSize==bytes-44 | (bytes-44)/32 ms | ffprobe duration | Δms | RMS dBFS | 信号来源 | 结论`。
- Produces `docs/evidence/plan2/` 下的 `p2-<case>-<nn>-<state>.{png,txt,wav}`（音频进 `docs/evidence/plan2/audio/`）。
- Produces `docs/PROGRESS.md` 里的 Plan 2 进度段（9 个 Task 的完成状态 + 用例总数）。

**Steps**
- [ ] 9.1 干净安装 + `pm clear`，走完整链路：首页「说梦」→ 按住 4 秒 → 松手 → 追问页再录 2 秒 → 超时收起 → 详情页播放。
- [ ] 9.2 权限两路（做成三条）：
  1. `pm revoke com.dreamkeeper android.permission.RECORD_AUDIO` → 点「说梦」→ 应给出「没有录音权限」而不是崩溃。**权限变更会杀进程**，pid 变化甚至为空是正常的，不是 bug。
  2. 永久拒绝：`appops set com.dreamkeeper RECORD_AUDIO ignore` → 应给出去设置里打开的引导，不能死循环弹框。
  3. 恢复：`pm grant com.dreamkeeper android.permission.RECORD_AUDIO` + `appops set com.dreamkeeper RECORD_AUDIO allow` → 再走一遍完整链路，并把 `dumpsys package com.dreamkeeper` 里的 `RECORD_AUDIO: granted=` 行存进证据。
- [ ] 9.3 崩溃恢复：录音中 `am force-stop` → 重启 → 扫描修复 → 详情页能播。
- [ ] 9.4 把 `## 3b 音频证据表` 填满，每行要有：`wav | bytes | RIFF/data 字段 | dataSize==bytes-44 | (bytes-44)/32 ms | ffprobe duration | Δms | RMS dBFS | 信号来源 | 结论`。
  - `RIFF/data` 两列**必须按字节读 offset 4..8 与 40..44**，**不能只信 ffprobe**：实测占位头（两个字段 `FFFFFFFF`）的文件 ffprobe 照样报 `duration=1.000000`，它验不出头是脏的。
  - `ffprobe duration` 只作第三方交叉校验，还要与 `(真实字节数-44)/32` 比：头有效时 ffprobe 用的是头里的 data size，尾部落了多余字节它看不见。
  - 三层证据都要有：**W1** 字节在长 + 头回填正确；**W2** ffprobe 可解码且 duration 与公式一致；**W3** 信号来源（模拟器默认灌零，除非以 `-allow-host-audio` 启动——「文件有字节」不等于「录到声音」）。
- [ ] 9.5 在 `## 0 构建绑定` 补一行「模拟器启动参数：`-allow-host-audio` 未启用（输入为静音）」，并在证据里写明「本机录音的 W3 信号来源 = seam / 静音，不代表真人说话质量」。
- [ ] 9.6 冷启动 3 次 `am start -W`、`capture-logcat.ps1 -Runs 3`、近白闸门全图 `-TopOffset 100 -MaxRatio 0`。
- [ ] 9.7 更新 `docs/PROGRESS.md`，提交；**不要自己打 tag**（Lead 验收后打 `plan2-done`）。

**Expected**：链路走通；`(bytes-44)%32 == 0`；表格无空单元格；崩溃 0；`FATAL`/`ANR` 零命中；真出现余数不为 0 时**不自行处理**，记偏差表再问 Lead。

---

## 偏差表（接着 Plan 1，从 D88 起）

| 编号 | 原计划的写法 | 实际做法 | 原因 | 类型 |
|---|---|---|---|---|
| D88 | Task 1 Step 1.7：造一个「占位头 + 1000ms PCM」的 WAV 当 fixture | 改成造**已回填**的 canonical 头（`riffSize=32036`、`dataSize=32000`）；占位头只由单测证明（`create` 只落 44 字节） | Evidence Collector 实测：占位头会让 ffprobe 报 `Ignoring maximum wav data size, file may be invalid` 并改按 bitrate 估算时长——这种文件本身不是合法 WAV，做不了 W2 的基准 | 实测结论 |
| D89 | 初版 `WavStore` 的 `append(FileOutputStream, ...)` + `finish(File)` 两个入口 | 改成单一写句柄 `openWriter(file): WavWriter`（自带 `bytesWritten`/`write`/`finish`），`finish(file)`/`repair(file)` 只留作扫描与离线修复 | Mobile App Builder 预审：两个入口分裂时，调用方可能套缓冲或没 flush 就让 `finish` 回填，得到「短头」；且回填后仍能继续写，头又变脏。`placeholder` 语义同时钉死为「任一字段仍是 `0xFFFFFFFF` 即 true」 | 以为的坑 |
| D90 | Task 1 只断言 `offset 4..8` 与 `40..44` | 补「整 44 字节逐字节等于黄金 hex」两条常量（占位头、回填头） | Mobile App Builder：只查两个长度字段时，`fmt ` 尾空格丢失、`byteRate` 写成 0、`bitsPerSample` 写错都照样绿 | 以为的坑 |
| D91 | 契约只给 `rawAudioMs = (文件字节数-44)/32`，没规定余数不为 0 怎么办 | 余数不为 0 时**不裁剪、不补齐、不四舍五入**，先记偏差表再问 Lead | 契约没写的事不能由实现者当场决定，否则同一枚文件在不同机器上会算出两种时长 | 以为的坑 |
| D92 | 旧稿用 `adb shell am start-foreground-service` 验 microphone 型前台服务 | 改成由可见页面（「说梦」按钮）起服务 | Android 14+ 禁止后台启动 microphone 型 FGS，`ForegroundServiceStartNotAllowedException` 必然出现，拿它当验收会误判成代码 bug | 实测结论 |
| D93 | 旧稿说 `isOnDeviceRecognitionAvailable` 是 API 31+ | 改成 **API 33+** | 33 才有 `createOnDeviceSpeechRecognizer`/`checkRecognitionSupport`；按 31 写在 31/32 上会崩或退回网络识别（音频出机） | 实测结论 |
| D94 | 旧稿「`ParcelFileDescriptor` 先 seek 跳过 44 字节头」 | 自己 `skip(44)`，把剩下的 PCM 灌进 `ParcelFileDescriptor.createPipe()` 写端 | 传给识别器的 PFD 不能 seek | 以为的坑 |
| D95 | 权限只验 `pm revoke`/`pm grant` 两路 | 补 `appops set com.dreamkeeper RECORD_AUDIO ignore`（永久拒绝）并取 `dumpsys package` 授权行 | 只有 revoke 验不出「永久拒绝」分支；且 revoke 会杀进程，pid 变是正常的 | 实测结论 |
| D96 | Task 1 实作的四处细节原计划没写死：①`openWriter` 对缺失或 <44 字节文件兜底写占位头；②`state()` 对 <44 字节抛 `IllegalStateException`；③`repair` 顺带纠正过期但非占位的尺寸；④追加场景 `bytesWritten` 从已有 PCM 起算。另计划里那条 python fixture 命令原本含一个真正的 NUL 字节 | 四条行为全部保留并用测试钉住（`WavStoreTest` 17 条）；NUL 换成 `\x00` 字面量使命令可复制执行 ；Lead 复核 `WavStore.kt:63-116` 并修补 | 实测结论 |
| D97 | Task 2 的 `reason`、`read()` 返回 0 的语义、`rebase()` 三处都没定义 | 钉死 `reason ∈ {dead, stopped, user, error}`；`read()==0` 视为「暂时没数据、继续读」；新增 `rebase()` 只改 `startMs` 与 `baseBytes` | Mobile App Builder：没有这三条，STOPPED 分支与「暂停不补静音」在单测里根本无法表达，5.4b 要调的 `rebase()` 也不存在 | 以为的坑 |
| D98 | 原 2.1 的补零用例写「时钟前进 200 ms、读到 3200 B」 | 改成 250 ms（deficit 2400 样本、补 4800 B），并补「100 ms 恰好 1600 样本不补」的边界组 | Voice AI：200 ms 时 deficit 恰为 1600，`> 1600` 不成立，原断言 `zeroFillBytes > 0` 必红；门槛在边界上一格都不能差 | 实测结论 |
| D99 | `PcmSink` 直接复用 `WavWriter` | 加一层适配（`bytesWritten` 是 `val`、`WavWriter` 也没有 `sync()`）；`join()` 从接口里去掉（`run` 同步返回、没有读线程） | Mobile App Builder：两者签名不兼容，直传编译不过；`join` 无出处会让实现者自己发明线程模型 | 以为的坑 |
| D100 | 只写「振幅条读 `onChunk` 的 dBFS」 | 钉死 `20*log10(rms/32768)`、全零块取 `silenceFloorDb`、值域 `[-60, 0]`、UI 映射 `(db+60)/60` | Mobile App Builder：全零块 `log10(0) = -Infinity`，Kotlin 的 `(-Infinity).toInt()` 返回 `Int.MIN_VALUE` 且不抛，振幅条会恒空 | 实测结论 |
| D101 | rebase 写成「已写字节数一并前移」 | 改成保留独立的 `baseBytes` 参与减法，**不动** `sink.bytesWritten()` | Voice AI：只前移时间原点会让 deficit 变成负值、整段时间窗漏补；只归零字节计数会一次补出与已录等长的静音 | 实测结论 |
| D102 | Task 3 的 3.4 同时要求「先 startForeground」和「权限不足 → fail + stopSelf」 | 钉死顺序：先查权限（不足则不 startForeground，直接 fail + stopSelf）→ 建渠道 → startForeground → 建 AudioRecord | Mobile App Builder：14+ 用 microphone 型且无 RECORD_AUDIO 时，`startForeground` 自己就抛 SecurityException，字面照抄会先崩后报 | 实测结论 |
| D103 | 通知 `ContentIntent` 说「回详情」 | 改成 `MainActivity` + `entry=NOTIFICATION`（+`FLAG_IMMUTABLE`），详情深链留 Plan 3 | Mobile App Builder：`DreamRoute.Detail` 没有 deep link、`MainActivity` 只认 `dk_prefill_search`，写「回详情」实际只会打开首页 | 以为的坑 |
| D104 | `RecordingCommand` 只在 3.2/3.3 里被提到，没有签名也没有文件 | 钉成 `sealed interface RecordingCommand { Start/Stop/Cancel }` + `RecordingCommands.parse(action, dreamId): RecordingCommand?`，落 `RecordingSession.kt` | Mobile App Builder：3.2 的断言目前没有可编译契约，实现者会各自发明「非法」的表示 | 以为的坑 |
| D105 | 3.7 只写「通知出现、服务存在、无 FATAL」 | 改成四连判据：`started=true`+`isForeground=true`+`foregroundId=42`+类型含 microphone；通知 id=42 且 flags 含 0x40；两次 stat 的字节速率 32000 B/s ±2%；logcat 三类关键字全空 | Evidence Collector：只有 `started=true` 只是 startService 成功，证明不了前台服务与 AudioRecord 在供数 | 实测结论 |

> 规则：每发现一处与计划的偏离就追加一行；「原因」必须写能被别人复核的事实，不写「感觉」。

