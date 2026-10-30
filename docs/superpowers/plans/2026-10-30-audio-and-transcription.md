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
- Create `android/app/src/main/java/com/dreamkeeper/audio/WavSink.kt`
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
- [ ] 3.4e `PcmSource` 实现必须防忙循环：`read()` 返回 0 时 `RecorderCore` 会立刻再读，所以真源实现要**连续 50 次（约 5 秒）仍读不到数据就把 `state()` 报成 `DEAD`**；否则 AudioRecord 半死时 CPU 会空转、文件停滞而界面上还在「录音中」。
- [ ] 3.5 实现通知：常驻、`IMPORTANCE_LOW`、锁屏 `VISIBILITY_PRIVATE`、`setOngoing(true)`；`ContentIntent` → `MainActivity` + `entry=NOTIFICATION` + `FLAG_IMMUTABLE`；`ACTION_STOP` 走 `PendingIntent.getService(...)` 指向本 Service 的 STOP；渠道在 `RecordingNotifications.ensureChannel()` 里建。
- [ ] 3.6 `AppContainer` 加 `audioStore`（`WavStore(filesDir/audio)`）与 `recordingSession`。
- [ ] 3.6b 新建 `android/app/src/main/java/com/dreamkeeper/audio/WavSink.kt`：`class WavSink(private val writer: WavWriter) : PcmSink`，`write(buffer, length)` → `writer.write(buffer, length)`；`sync()` → `writer.flushToDisk()`（**注意**：Task 1 的 `WavWriter` 接口只有 `bytesWritten` / `write` / `finish`，**没有** `sync`——若确认缺失，就给 `WavWriter` 加一个 `fun syncToDisk()` 并在 `PcmWavWriter` 里实现为 `fd.sync()`，同时补一条 `WavStoreTest`）；`bytesWritten()` → `writer.bytesWritten`；`finish()` → `writer.finish()`。**禁止**把 `WavWriter` 直接当 `PcmSink` 用（`bytesWritten` 是属性不是方法、也没有 `sync()`），报告里要写明这一段是怎么接的。
- [ ] 3.7 构建 + 安装，**从可见页面起服务**：`adb shell am start -n com.dreamkeeper/.MainActivity`（shell 起 Activity 不受后台启动限制）→ 首页点「说梦」→ `adb shell input swipe 540 1600 540 1600 3000` 或手按住 3 秒 → 松手。判据四连：① `dumpsys activity services com.dreamkeeper` 同一段里 `started=true` 且 `isForeground=true` 且 `foregroundId=42` 且类型含 `microphone`；② `dumpsys notification --noredact` 里 id=42、`flags` 含 `0x40`、ongoing；③ 两次 `run-as com.dreamkeeper stat -c %s files/audio/<id>.wav` 间隔 ≥3 s，`Δbytes/Δt` 落在 32000 B/s ±2%（这是「AudioRecord 真在供数」的唯一自动判据）；④ `logcat -d` 里 `FATAL` / `ForegroundServiceStartNotAllowed` / `DidNotStartInTime` 全空。**不要**用 `adb shell am start-foreground-service …`（shell 是后台，14+ 必抛 `ForegroundServiceStartNotAllowedException`），证据目录里也不许出现该命令的失败日志。
- [ ] 3.8 证据（存 `docs/evidence/plan2/`）：`p2-fgs-01-notify.png`（`cmd statusbar expand-notifications` 后截图）、`p2-fgs-01-services.txt`（`dumpsys activity services com.dreamkeeper`）、`p2-fgs-01-notify-dump.txt`（`dumpsys notification --noredact` 里本包那段）、`p2-fgs-01-perms.txt`（`dumpsys package com.dreamkeeper | Select-String RECORD_AUDIO|FOREGROUND_SERVICE`）、`p2-fgs-01-bytes.txt`（两次 `stat` 的原始输出）。
- [ ] 3.9 提交：`feat(record): 前台录音服务（microphone 类型、通知、START/STOP/CANCEL）`
- [ ] 3.10 权限三分支要在 UI 侧各停在各处（本条只做 Service 侧与文案约定，界面接入在 Task 4）：① 还能问 → 停在录音页、**不调** `startForegroundService`，文案「需要麦克风权限才能录梦」+「再试一次」；② 永久拒绝（已拒且 `!shouldShowRequestPermissionRationale`）→ 停在录音页，只给「去设置」（`ACTION_APPLICATION_DETAILS_SETTINGS` + `package:com.dreamkeeper`）；③ 33+ 通知权限被关 → **不拦录音**（FGS 照起、录音照走，只是通知栏看不到、也就没有停止按钮），文案写明这一点并提示去开通知。

**Expected**：从可见页面按住 3 秒后，通知出现（`dumpsys notification --noredact` 有 id=42、flags 含 `0x40`）；`dumpsys activity services com.dreamkeeper` 里既有 `started=true` **又有** `isForeground=true`、`foregroundId=42`、类型含 `microphone`；两次 `stat` 的字节速率落在 32000 B/s ±2%；四条权限都在 `dumpsys package` 里；`logcat` 里 `FATAL` / `ForegroundServiceStartNotAllowed` / `DidNotStartInTime` 全空。松手后 WAV 头 `offset 4..8` 与 `40..44` 已回填（不再是 `FF FF FF FF`）。

---

## Task 4：录音页与 RecordEntry 解析

### 0. 派工前修订（Lead 按两位专家预审裁定，覆盖下文任何冲突）

以下 14 条是硬约束，与下文冲突时以本节为准。

1. **`RecordViewModel` 必须 `: ViewModel()`**；`state = session.state.stateIn(viewModelScope, SharingStarted.Eagerly, RecordUiState())`（删掉「Eagerly 或测试直接断言」的二选一）。
2. **`RecordingStarter` 只用 action 起服务**：`start` → `startForegroundService(ACTION_START + EXTRA_DREAM_ID)`；`stop` → `startService(ACTION_STOP)`；`cancel` → `startService(ACTION_CANCEL)`。**禁止 `stopService`**——它绕过 `onRecordingFinished`，session 永远到不了 DONE，一条梦都不会落库。
3. **接线补全**：`DreamKeeperRoot` 增参 `session: RecordingSession, audioStore: WavStore, starter: RecordingStarter, recordEntry: RecordEntry?, onEntryConsumed: () -> Unit`；`MainActivity` 用 `mutableStateOf<RecordEntry?>` 装 intent 并在 `LaunchedEffect` 里 `navigate(Record(it))` 后 `onEntryConsumed()`；`RecordScreen` 的 entry 从 `backStackEntry.arguments?.getString("entry")` 取；**首页说梦钮（`DreamKeeperRoot.kt:134` 的假按钮）必须换成 `nav.navigate(Record(HOME_BUTTON))`**。不许在 ViewModel 里掏 `applicationContext`。
4. **权限三态**：`RecordUiState` 加 `permissionState: ASKABLE | BLOCKED | GRANTED`，由「持久化 `askedOnce` + 当前是否授予」决定，**不靠 `shouldShowRequestPermissionRationale` 单独判**（首装它也返回 false）。Activity 每次 RESUMED 递增 `permissionEpoch` 强制重算；按钮文案 ASKABLE=「允许麦克风」、BLOCKED=「去设置」；授予后自动回到可按态，不要求用户再点一次。VM 的 `permissionBlocked` 降级为「这次按下被拦」的一次性提示。
5. **测试写法**：`RecordingSession` / `DreamRepository` 都是 final 且无可注入 seam → **用真的**：Robolectric `@Config(sdk = [34])` + `Room.inMemoryDatabaseBuilder` + `Dispatchers.setMain(StandardTestDispatcher())`，照 `CalendarViewModelTest` 的范式；只 fake 接口 `RecordingStarter`，`hasPermission`/`clock` 用 lambda。不要写「假 session」。
6. **落库取数**：`rawAudioMs = snapshot.elapsedMs`（服务 `finish(dataSize)` 内部已是 `bytes/32`），**禁止**在 VM 里二次读文件（文件缺失会抛 `NoSuchFileException`，把一次成功 DONE 变崩溃）。空判定用 `snapshot.elapsedMs > 0L`。
7. **`DONE && elapsedMs == 0`（快速点按）**：不落库，并 `runCatching { audioStore.fileFor(dreamId).delete() }` 删掉服务建过的 44 字节占位头文件（否则 Task 6 扫描会把它恢复成一条 0 秒空梦）；补一条「DONE(0) 后文件不存在」的测试。
8. **重复 START 防护**：`onPressStart` 开头 `if (session.state.value.phase != RecordingPhase.IDLE) return`；`dreamId/filePath` 以 `session.state` 为唯一真源（不用 VM 本地 UUID），否则进程内已在录音时会落一条指向不存在文件的草稿。
9. **手势收尾必须兜底**：`pointerInput` 用 `try { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onStart(); val up = waitForUpOrCancellation(); if (up == null) onInterrupted() else onStop() } } finally { if (pressed) onStop() }`。`up == null`（来电/系统手势/锁屏抢焦）= **中断**：置 `interrupted = true`，顶部一行 `Paper`「录音被系统打断（0:37）」+ 两个动作「继续录」/「就这样」；`elapsedMs < 200` 的中断直接 `onCancel()`（不落库、不报错）。
10. **取消不删文件**：`onCancel()` 只发 `starter.cancel()`，文件由服务端删；VM 要等 `phase == IDLE` 再刷新。测试写成「调了 `starter.cancel` 一次」+「收到 IDLE 后 `!file.exists()`」，不要把「VM 删文件」当契约。
11. **收尾与返回**：`FINISHING` 期间主钮 `enabled = false`、环改 `InkFaint`、圈下 `Paper 16sp`「正在保存…」，计时冻结；`BackHandler(enabled = phase == RECORDING || phase == FINISHING)`，FINISHING 里按返回走 `onCancel()`。
12. **跳 FollowUp 的导航**：`navigate(FollowUp(id)) { popUpTo(recordRoute) { inclusive = true }; launchSingleTop = true }`，`savedDreamId` 当一次性事件消费掉；本任务的 FollowUp 是空桩，**验收以当前 route 与日志为准，不以「看到追问页」为判据**。
13. **视觉与坐标**（实算，420dpi → 1080px=411.4dp）：页面底色用 `Night #0B0E13`（`NightVoid` 与它只差 1.04:1，白多一个色值）；主钮 `size(160.dp)` = 420px、圆心放在屏高 **65%**（约 594dp，单手可达）；填充 `EmberDeep` + **1.5dp `Ember` 描边** + `Paper` 文字（`EmberDeep` 直接放在 `Night` 上只有 2.68:1，没描边圆边不可辨）；计时放圆**上方 32dp**（`Paper 40sp` 等宽数字），振幅做 r≈88–96dp 的 `Ember` 环**环绕圆外**；按下瞬间先用本地 `pressed` 出按压态（scale 0.96 + `Ember` 环，`DreamMotion.Quick=120`），`starting` 窗口显示 `InkDim`「正在启动麦克风…」；「取消」`sizeIn(minWidth = 48.dp, minHeight = 48.dp)`（126×126px），**不再叠 `navigationBarsPadding()`**（Scaffold 已算），并提供一个同手可达的取消（录音中上滑或双击主钮）。错误容器 `NightRaised` + **1dp `Ember` 描边**（6.03:1），文案两行 + 两个出口（「再试一次」「返回首页（这段录音留着）」）；`ENOSPC` 时主按钮改「空间不足，先去清理」并禁用重试。另在 `ThemeContrastTest` 加 `assertContrast("EmberDeep on Night", EmberDeep, Night, 3.0)`（它会红，正好把「必须靠描边」钉成断言）。
14. **证据**（一次 take 拍成链，命名 `p2-ux-*`）：`-01-idle.png`、`-02-recording-t1.png`（按压态 + 0:01）、`-03-recording-t4.png`（同一 take，0:04，振幅不同）、`-04-finishing.png`（松手后计时冻结 + 「正在保存…」+ 主钮禁用）、`-05-notif-ongoing.png`、`-06-perm-askable.png`、`-07-perm-blocked.png`、`-08-notif-off.png`、`-09-error-enospc.png`、`-10-interrupted.png`、`p2-ux-hold.mp4`（`screenrecord --time-limit 20`，同一 take，截图证明不了时序、录像才算）、`p2-ux-bytes-hold.txt`（三次 `stat`：按住中两次间隔 ≥3 s、松手后一次冻结，三个采样必须同一 `dreamId`）、`p2-ux-latency.txt`（logcat 里 START/STOP 与 `adb shell date` 的时差）、`p2-ux-index.md`（每行：文件 / APK sha256 前 8 位 / `adb shell date` / 复跑命令 / 判别串）。**`check-near-white.ps1` 没有 `-MaxRatio` 参数**（只有 `-Path`/`-TopOffset`），命令写成 `-Path docs/evidence/plan2/p2-ux-XX.png -TopOffset 100`，把 nearWhite=0 写进 txt 当判据；`FINISHING` 亚秒级，用 `-04-finishing.png` + `dumpsys`/日志取证，不用手工抢截。


**Files**
- Create `android/app/src/main/java/com/dreamkeeper/ui/record/RecordScreen.kt`
- Create `android/app/src/main/java/com/dreamkeeper/ui/record/RecordViewModel.kt`
- Create `android/app/src/main/java/com/dreamkeeper/service/RecordingStarter.kt`（把 `startForegroundService`/`stopService`/`cancel` 收成一个可注入接口，真实现放这里）
- Modify `android/app/src/main/java/com/dreamkeeper/ui/record/RecordUiState.kt`
- Modify `android/app/src/main/java/com/dreamkeeper/ui/DreamKeeperRoot.kt`（`Record` 空桩 → 真屏）
- Modify `android/app/src/main/java/com/dreamkeeper/MainActivity.kt`（**注意不是在 `ui/` 下**：运行时权限、锁屏可见、intent → `RecordEntry`、`singleTop`）
- Modify `android/app/src/main/AndroidManifest.xml`（`MainActivity` 加 `android:launchMode="singleTop"`）
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
    val savedDreamId: String? = null,   // 落库成功才有值，用于导航
)

/** 只做「把命令发给 Service」，不持有业务状态，便于在 JVM 测试里换成假实现。 */
interface RecordingStarter {
    fun start(dreamId: String)
    fun stop()
    fun cancel()
}

class RecordViewModel(
    private val repository: DreamRepository,
    private val session: RecordingSession,
    private val audioStore: WavStore,
    private val starter: RecordingStarter,
    private val hasPermission: () -> Boolean,
    private val clock: () -> Long,
) {
    val state: StateFlow<RecordUiState>          // 由 session.state 映射，Eagerly 或由测试直接断言 session
    val permissionBlocked: StateFlow<Boolean>    // true = 缺 RECORD_AUDIO，UI 只显示「再试一次/去设置」
    fun onPressStart(entry: RecordEntry)         // 无权限 → 不动任何东西；有权限 → 起服务
    fun onRelease()                              // 发 STOP；启动 5 秒看门狗
    fun onCancel()                               // 发 CANCEL + 删文件，不落库
    fun parseEntry(raw: String?): RecordEntry    // entries.firstOrNull { it.name == raw } ?: HOME_BUTTON
}
```

**参数与时机钉死（覆盖任何含糊处）**
- `dreamId` 在**按下时**用 `UUID.randomUUID().toString()` 生成，路径 `audioStore.fileFor(dreamId)`（= `files/audio/<dreamId>.wav`）。
- **按下不落库**：不 `save` 草稿。录像结束后（`phase == DONE` 且 `bytes > 0`）才 `repository.save(...)`，一次写全 `rawAudioPath`、`rawAudioMs = bytes / 32`、`transcriptStatus = PENDING`、`isDraft = true`。崩溃在「松手后、落库前」只会留下孤儿 WAV，由 Task 6 启动扫描恢复成草稿（这是设计 §8.1-5 的本意）。
- 落库成功后置 `savedDreamId`，UI 据此导航到 `DreamRoute.FollowUp(id)`。
- **不许乐观改 phase**：UI 一律跟 `session.state`；`FINISHING` 超过 5 秒没变 `DONE` → `error = "保存超时"`。

**Steps**
- [ ] 4.0 前置检查（贴进证据）：`ls android/app/src/main/java/com/dreamkeeper/service/` 必须已有 `RecordingService.kt`/`RecordingSession.kt`/`RecordingNotifications.kt`（Task 3 的产物）。缺任何一个就**停手回信**，不要在本任务里补服务。
- [ ] 4.1 写 `RecordViewModelTest`（先红，只测纯逻辑）：
  - `parseEntry("LOCK_TILE") == LOCK_TILE`、`parseEntry("bogus") == HOME_BUTTON`、`parseEntry(null) == HOME_BUTTON`；
  - 无权限时 `onPressStart` **不调** `starter.start`、`session.state.phase` 仍是 `IDLE`；
  - 有权限时 `onPressStart` 调 `starter.start(dreamId)` 一次、`dreamId` 是合法 UUID、`filePath == audioStore.fileFor(dreamId).absolutePath`；
  - 假 session 推到 `DONE(bytes = 32_000)` → 仓库里出现一条 `isDraft=true`、`rawAudioPath` 正确、`rawAudioMs == 1000` 的记录，且 `savedDreamId` 有值；
  - `phase == DONE(bytes = 0)` → **不落库**、`error` 非空；
  - `onCancel()` → 调 `starter.cancel` 且文件被删（用临时目录断言 `!file.exists()`）。
  **断言不许从被测输出反推**；不碰 `Service`、不碰 `Context`。
- [ ] 4.2 跑 `:app:testDebugUnitTest --tests "*RecordViewModelTest*"` → 红（`Unresolved reference` 或断言失败都算）。
- [ ] 4.3 实现 `RecordViewModel` + `RecordingStarter` → 绿。
- [ ] 4.4 `MainActivity` 接线：`registerForActivityResult(RequestMultiplePermissions())` 必须在 `onCreate` 里注册（早于 STARTED）；权限判断每次现查 `checkSelfPermission`，**不缓存**；`repeatOnLifecycle(RESUMED)` 只用于重读 intent / 重查权限，不用来驱动手势。`launchMode="singleTop"` + `onNewIntent` 把 `entry` 放进 `mutableStateOf` 供 Compose 消费；**只有 `savedInstanceState == null` 才消费首次 intent**（否则旋转/进程恢复会再跳一次）。锁屏开关只在 `entry ∈ {LOCK_TILE, NOTIFICATION}` 时打开：`setShowWhenLocked(true)`/`setTurnScreenOn(true)`，minSdk 26 分支补 `window.addFlags(FLAG_SHOW_WHEN_LOCKED or FLAG_TURN_SCREEN_ON)`。
- [ ] 4.5 `RecordScreen` 按住录/松手停，**手势必须这么写**（`detectTapGestures` 没有 `onRelease` 参数，写 `onPress/onRelease` 直接编译不过）：
  ```kotlin
  Modifier.pointerInput(Unit) {
      awaitEachGesture {
          awaitFirstDown()
          onStart()
          val up = waitForUpOrCancellation()   // null = 被系统手势/来电打断，也必须停
          onStop()
      }
  }
  ```
  本地 `starting` 布尔挡住连点/第二根手指发第二个 START；松手**先发 STOP 再 navigate**。
- [ ] 4.6 `RecordScreen` 视觉：底 `NightVoid`；中央 `size(160.dp)` 主钮；计时文字只由 `session.state.elapsedMs` 驱动（**不许自建计时器**）；振幅条只读 `session.state.amplitude`（**不许每帧随机**）；底部「取消」48dp；进入 `RECORDING` 时**只轻震一次**（`HapticFeedbackType.LongPress`），**不发声**；屏内不再重复「正在录音」大字（第二证据是通知栏常驻）。
- [ ] 4.7 权限三分支（Task 3 的 3.10 在这里落地）：① 还没问过/还能问 → 按下去时**不建草稿、不起服务**，显示「需要麦克风权限才能录梦」+「再试一次」（点了才 request）；② 永久拒绝（`!shouldShowRequestPermissionRationale` 且已问过）→ 只给「去设置」（`ACTION_APPLICATION_DETAILS_SETTINGS` + `package:com.dreamkeeper`）；③ 33+ `POST_NOTIFICATIONS` 被关 → **不拦录音**，屏内一行小字「通知栏不会显示，录音照常」。
- [ ] 4.8 失败与取消要有出口：`error != null` 渲染成 `NightRaised` 容器（文字用 `Paper`，**不要** M3 `error` 默认色）+「再试一次」；`ENOSPC`（写满）文案单列；`BackHandler(enabled = phase == RECORDING) { onCancel() }`，别让返回键把录音页 pop 掉而服务还在录。
- [ ] 4.9 构建安装 → 四条路径各走一遍并截图：① 首页「说梦」按住 3 秒松手；② `am start -n com.dreamkeeper/.MainActivity --es entry LOCK_TILE`；③ 通知点回（`entry=NOTIFICATION`）；④ 先拒绝权限再按（走 4.7 ①）。
- [ ] 4.10 证据（存 `docs/evidence/plan2/`）：`p2-ux-01-idle.png`、`p2-ux-02-recording.png`、`p2-ux-03-finishing.png`、`p2-ux-04-perm-denied.png`、`p2-ux-05-cancel.txt`（取消后 `ls files/audio/` 为空 + 无新增行）、`p2-ux-06-bytes.txt`（两次 `stat`）；每张图旁记 APK sha256 + `adb shell date`；近白闸门 `-TopOffset 100 -MaxRatio 0` 全 0。
- [ ] 4.11 提交：`feat(record): 录音页按住录/松手停，RecordEntry 四入口接线与落库时机`

**Expected**：按首页「说梦」按住 3 秒 → 计时与振幅在动（只来自 session）、通知出现、松手后落一条 `isDraft=true` 的梦并跳追问页；`LOCK_TILE` / `NOTIFICATION` 两种 entry 都能直达录音页且锁屏可见；无权限时按下去**不建草稿、不起服务**；取消后不留文件不留行；四张截图近白 0；`logcat` 无 `FATAL`；不崩、录音不丢字节。


---

## Task 5：30 秒追问窗口

### 0. 派工前修订（Lead 按三位专家预审裁定，覆盖下文任何冲突）

以下 14 条是硬约束，与下文冲突时以本节为准。

1. **追加必须走 `openWriter`，禁止 `create`**：`RecordingService` 的 START 分支改成「文件不存在或 `<44` 字节 → `audioStore.create(dreamId)`；否则 `audioStore.markOpen(file)` 后 `audioStore.openWriter(file)` 追加」。`WavStore` 新增两个方法：`ensurePlaceholder(dreamId): File`（仅当不存在或 `<44` 时写占位头）与 `markOpen(file)`（把 offset `4..8` 与 `40..44` 写回 `0xFFFFFFFF`，让 `placeholder == true` 成为唯一的「正在写」标记，崩溃后扫描照样可修）。`create()` 的「截断」语义保留（它是新建专用），但**第二次 START 绝不许调它**——`WavStore.kt` 里是 `FileOutputStream(file, false)`，会把前一段整条抹成 44 字节。
2. **失败路径不得删已存在的录音**：`ActiveRecording` 加 `createdThisTake: Boolean`；三条失败路径（`startForeground` / `AudioRecord` 打开 / `startCapture`）与 `CANCEL` 都只在 `createdThisTake == true` 时 `file.delete()`，否则保留文件（补录那趟失败不能连带删掉第一段）。
3. **`FollowUpViewModel` 依赖注入**：`FollowUpViewModel(repository, session, starter: RecordingStarter, audioStore: WavStore, clock: () -> Long, dispatcher: CoroutineDispatcher = Dispatchers.IO, windowMs: Long = 30_000L, maxSegmentMs: Long = 120_000L, dreamId: String)`。`onSpeakStart()` 先判 `session.state.value.phase ∈ {IDLE, DONE}` 才发 `starter.start(dreamId)`，否则忽略并给一行提示「上一段还在收尾」——服务在 `FINISHING` 时收到 START 只 `Log.w` 就 return，若不判就会出现「界面在录、实际零字节」。
4. **不补中间静音，直接拼接**（Lead 裁定）：所以 `rawAudioMs` ≠「从按下到窗口超时」的墙钟跨度，这是对设计 §8.1-1 的有意偏差。理由两条：补 30 秒静音会把识别器的端点检测提前打掉（长静音 → 会话结束 → 后半段丢），且 30 s × 32 KB/s ≈ 960 KB 白占空间。设计文档要记这条偏差。
5. **Task 5 不写 `rawTranscript` 文本**：只通过窄更新写 `rawAudioMs` 与 `transcriptStatus = PENDING`。新增 `DreamDao.updateAudio(id: String, rawAudioMs: Long, transcriptStatus: String)`（`@Query`）+ `DreamRepository.updateAudio(...)`；**禁止**用 `save()` 整行 upsert（会按内存里的旧值覆盖用户改过的文本）。「用户手改后不许被机器覆盖」的最终方案留到 Task 7（可能需要 `rawTranscriptEditedByUser` 列 + v1→v2 迁移，到那时再定）。
6. **时长口径**：`rawAudioMs = state(file).dataSize / 32`（**最终整文件**），不许把两次 run 的 `elapsedMs` 相加（各自 `/32` 会漂）。
7. **尾部分帧**：追加前断言 `trailingPartialBytes == 0`；不为 0 则拒绝追加、记日志与本任务偏差，**不许**自动补零或裁剪（一字节错位会让此后整条时间线变噪声）。
8. **追加期间禁止转写**：`placeholder == false` 才允许起 STT；本任务 `markOpen` 之后即为 `true`。设计 §8.1 补一条「同一 dream 的转写单一 in-flight（Mutex），追加前先 cancel 掉在跑的识别器」（Task 7 实现，本任务只保证标记语义）。
9. **倒计时**：`remainingMs = windowMs - (clock() - openedAt)`，不硬编码、不累加；UI 全程显示 `30..0`（无动画、不变色）。测试**禁止**用 `advanceUntilIdle()`（ticker 永不 idle 会挂死），用 `advanceTimeBy + runCurrent`；期望值写具体数字（`30000 → 25000`，录音中墙钟再跳 5 秒仍 `25000`），别在测试里重算公式。
10. **交互与状态字段**：`FollowUpUiState` 改为 `dreamId: String? = null, loaded: Boolean = false, remainingMs: Long = 0L, recording: Boolean = false, transcript: String = ""`；回调补齐 `onSpeakStart() / onSpeakStop() / onDoneNow() / onTimeout() / onEditText(String)`（原 brief 缺 `onDoneNow`，「就这样」落不了地）。`loaded` 门闩防「进页即收起」。按住超过 `maxSegmentMs`（120 s）到点强制 `onSpeakStop()` + 收起（防按住不放写整夜）。`BackHandler` 覆盖本页（record 路由已被 `popUpTo(inclusive)` 清掉，没有返回键会退到桌面）。
11. **视觉（实算，不许改）**：底 `NightVoid #05070A`；正文 `#B8C1CF`（11.11:1，**不用** `Paper` 16.73:1——整屏最亮面积太大会刺眼）；倒计时 `Ember #C8873F`（6.72:1，亮度只有 `Paper` 的 37%）；按钮边界 `InkDim #8B95A7`（6.68:1，**禁** `Line #232A36` 1.40:1）；主钮 `EmberDeep` 底 + `Paper` 字（6.02:1）；进页 `fadeIn(tween(DreamMotion.Quick))`（120ms），收起落 `Night` 底页、不要退到桌面；进页**只轻震一次**（复用 `RecordScreen.kt` 那套），收起时不震。
12. **屏幕亮度**：应用没有 `DEVICE_POWER` 权限，做不到真「熄屏」——本轮做法：进页 `window.attributes.screenBrightness = 0.01f`、离开恢复原值，且**不**持 `KEEP_SCREEN_ON`。设计 §11 里「设置里可关（默认开）」本轮没有设置页 → 固定为开，记偏差。
13. **无障碍**：麦克风点区 `heightIn(min = 88.dp)`（在 48dp 下限之上），三个可点节点都带 `contentDescription`（照 `RecordScreen.kt` 的写法），倒计时加 `semantics { contentDescription = "还剩 N 秒" }` 但**不许**加 `liveRegion`（每秒播报会把人吵醒），进页时 announce 一次。
14. **证据（一次 take）**：`p2-followup-01.png`（刚进页）、`-02.png`（剩 5 秒）、`-03.png`（收起后落地页）、`-04.mp4`（进页→收起 `screenrecord`，截图证明不了白闪与时序）、`p2-followup-bytes-append.txt`（同一 `dreamId` 的三次 `stat`：第一段结束后、补录中、补录结束后，差值要等于 `(bytes2-bytes1)/32` 秒）、`p2-followup-index.md`（每行：文件 / APK sha256 前 8 位 / `adb shell date` / 复跑命令 / 判别串）。判据：三张图过 `scripts\check-near-white.ps1 -Path … -TopOffset 100`（**没有 `-MaxRatio` 参数**）；`ffmpeg -vf signalstats` 逐帧 `YAVG` 最大值 ≤ 32、录屏里出现 ≥48 的帧即判白闪失败；`uiautomator dump` 里 `clickable="true"` 且 `content-desc` 非空的节点数 == 3、每个 `bounds` 高 ≥ 132 px（48dp × density 2.75）。


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

### 0. 派工前修订（Lead 按两位专家预审裁定，覆盖下文任何冲突）

以下 16 条是硬约束，与下文冲突时以本节为准。

1. **编译级缺口先补齐**：`WavStore` 现在拿不到文件列表 → 加 `fun files(): List<File>`（`rootDir.listFiles` 过滤 `*.wav`，且 stem 必须是 UUID 形，忽略 `.tmp/.part/`垃圾名）；`DreamDao` 加两条 `@Query`：`SELECT * FROM dreams WHERE rawAudioPath IS NOT NULL` 与「按 id 是否存在」；`DreamDao` 再加**两条窄更新**：`clearAudio(id)`（path 置 NULL + `rawAudioMs` 置 NULL + status 置 `FAILED`）与 `setAudioMs(id, ms)`（**只**改 `rawAudioMs`，不许碰 `transcriptStatus`——现成的 `updateAudio` 会把 `FAILED` 悄悄改成 `OK`）。
2. **事务放仓库层**：`DreamRepository` 加 `recoverDraft(...)`、`markMissing(id)`、`fixAudioMs(id, ms)` 三个带 `db.withTransaction` 的方法；`AudioScan` 只依赖 `WavStore` + `DreamRepository`，**不许**直接碰 DAO（`db` 是私有字段，扫描里没有事务边界）。
3. **别把正在录的文件当孤儿（最高优先级）**：`WavStore` 维护一份「当前打开的文件」集合（`create`/`markOpen`/`openWriter` 注册，`finish`/`close` 注销），扫描**跳过集合内的文件**。理由：录音前先写占位头、数据库行要等 DONE 之后才写 → 「文件在 + `placeholder=true` + 库无行」正是正常录音中；`onCreate` 的异步扫描会把它当孤儿插一条草稿（而 `RecordViewModel.alreadyPersisted()` 随后会直接 return，导致 DONE 不再保存、也不跳追问页——可见回归，不只是脏数据）。
4. **修 `RecordViewModel` 的「已存在就跳过」**：行已存在时不再 `return`，改为窄更新 `rawAudioPath`/`rawAudioMs` 并照常发 `savedDreamId`，否则补录后的时长永远停在扫描值。
5. **文件系统是「存在与时长」的唯一真源**：`repair()` 只碰文件、不写库，扫描必须在 `repair` 成功后自己走 `fixAudioMs` 写 `(bytes-44)/32`；**禁止**用 `save()`（整行 upsert 会改 `updatedAt`、覆盖并发编辑）。
6. **恢复草稿必须写全字段**：`isDraft=true`、`transcriptStatus=PENDING`、`rawTranscript=""`、`rawAudioPath=文件绝对路径`、`rawAudioMs=WavMath.durationMs(len)`，并且 `dreamDate` 与 `createdAt` **取 `file.lastModified()`**（不许用 `today`，否则三天前录的梦会落到今天）。
7. **四条主状态 + 一条补充状态，逐文件 try/catch**：①有行+占位头 → `repair` + `fixAudioMs`（幂等）；②有文件无行 → 恢复草稿（不存在才插，重跑不重置 `createdAt`）；③有行无文件 → `markMissing`（path 置 NULL + `FAILED`）；④干净 → `Noop` 且**字节与 mtime 都不许变**；⑤0 字节或 `<44`：库无行则**删文件、不建行**（0ms 草稿会成永久幽灵），有行则 `markMissing`。**每个文件都必须 `runCatching`**，单个坏文件不许中断全扫；外层再加一层 try/catch（`SupervisorJob` 不兜未捕获异常，漏一个就崩进程）。
8. **尾部半帧**：扫描先截掉奇数尾字节再 `repair`，并单独出用例（否则 `markOpen` 会永久拒绝追加）。
9. **正好 44 字节**（`dataSize=0`）：按第 ⑦ 条「无行删文件、有行标 FAILED」处理，不许留下 `0:00` 条目。
10. **启动时机**：扫描挂在 `DreamKeeperApp` 已有的 `appScope(IO)` 上，**不 await、不占主线程**；但必须排在种子之后（`SeedData` 以 `count()==0` 整体跳过，扫描先插草稿会让演示种子永久不出现）。
11. **别污染测试**：扫描入口收进 `di/AppContainer`（`val audioScan by lazy`）+ `BuildConfig` 门闩，`Application.onCreate` 只调容器；Robolectric 每个用例都会跑 `onCreate`，无条件扫描会给现有测试加真实 `filesDir` IO。
12. **证据必须能反驳自己**：写一个 python 校验脚本进证据目录，读 offset `4..8` 与 `40..44` 与 `len(file)` 比对，并跑 `ffmpeg -v warning -i x.wav -f null -`——**占位头会打 `[wav @ Ignoring maximum wav data size`，stderr 非空即失败**；`ffprobe` duration 与 `(bytes-44)/32` 差 <100ms；**脚本 exit code 就是结论**，不许用 hexdump + 肉眼当判据。脚本必须先在 `p2-scan-01-before.wav` 上**红一次**（贴 stderr 与 exit code），再在 `02` 上绿，成对存在。
13. **四种状态怎么造（真路径，不许只跑单测）**：
    - ①（有行+占位头）：录 3 s → 松手 → 追问页补录 2 s（`markOpen` 把两字段写回 `FFFFFFFF`）→ 补录中 `am force-stop` → 重启断言修复。
    - ②（有文件无行）：`am start` → 长按录 3 s → 录制中 `am force-stop` → 重启；断言出现「今天的草稿」且 `rawAudioMs == (bytes-44)/32`。
    - ③（有行无文件）：正常存一条 → `run-as rm files/audio/<id>.wav` → 重启。
    - ④（干净）：存一条 → force-stop → 重启，断言文件 sha256 与 mtime 前后一致。
    - 证据里每一态都必须写一行 `pre_state_by:`（说明这个状态是 App 造成的还是你用 adb 造的），脚本进证据目录（照 `p2-fgs-01-capture.sh` 先例）。
14. **取文件与查库的纪律**：拉 WAV 用 `cmd /c "<adb> exec-out run-as com.dreamkeeper cat … > …"`，并**比对设备 `sha256sum` 与宿主 `Get-FileHash` 逐字符相同**（PS 直接重定向会毁二进制）；查库前先 `am force-stop`（Room 是 WAL，边跑边 cat 是撕裂读），导出 `dreamkeeper.db`（有 `-wal` 一起取）后宿主 python 只读打开，并把 db 的 sha256 记进 txt；交叉表必须输出 `(bytes-44)//32 | rawAudioMs | diff` 三列，`diff != 0` 即红。
15. **证据命名与必带字段**：`p2-scan-00-index.md` + `p2-scan-01-before.txt` / `-01-before.wav` / `-01-validate.txt`(红) / `-02-after.txt` / `-02-repaired.wav` / `-02-validate.txt`(绿) / `-03-db.txt` / `-04-file-missing.txt` / `-04-clean.txt` / `-05-zero-byte.txt`；每个 txt 头必须有 `apk_sha256 / device_date / replay / artifact / file_sha256 / pre_state_by`。截图必须带判别串（「原样/小说化/标签/素材」之一）+ `adb shell date`，否则判废。
16. **挪给 Task 9**：恢复出来的草稿在 UI 里可见/可播放/时长显示正确、权限路径 A（录制中 revoke → 占位头交给扫描）的跨任务整链、100+ 文件目录的扫描耗时、连续多次重启的幂等——这些是端到端与性能，不塞进本任务。


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
| D106 | Task 4 的 Files 写 `ui/MainActivity.kt` | 实际路径是 `android/app/src/main/java/com/dreamkeeper/MainActivity.kt`（不在 `ui/` 下） | Mobile App Builder：照抄会新建出第二个 Activity，Manifest 里注册的却还是原来那个 | 实测结论 |
| D107 | 原 4.1 要求「onPressStart 后仓库里出现 isDraft=true 的记录」 | 改为**按下不落库**、`phase == DONE && bytes > 0` 才一次性 `save`；崩溃在松手后落库前只留孤儿 WAV，由 Task 6 扫描恢复 | Mobile App Builder：`repository.save` 是 suspend，松手早于 save 返回会让 STOP 先到 START 后到，服务起来后没人停、文件无限长；同时避免误触落一堆空草稿 | 实测结论 |
| D108 | 原 4.5 写 `detectTapGestures(onPress/onRelease)` | 钉成 `awaitEachGesture { awaitFirstDown(); onStart(); waitForUpOrCancellation(); onStop() }`，`up == null`（系统手势/来电）也必须 stop | Mobile App Builder：`detectTapGestures` 没有 `onRelease` 参数，原写法编译不过；`tryAwaitRelease()` 返 false 时原计划没有分支 → 录音永不停 | 实测结论 |
| D109 | 四个入口只写「把 intent 的 entry 交给 RecordRoute」 | 补：`MainActivity` 加 `launchMode=singleTop` + `onNewIntent` + `entry` 用 `mutableStateOf`；只在 `savedInstanceState == null` 消费首次 intent；锁屏开关只在 `LOCK_TILE`/`NOTIFICATION` 时打开 | Mobile App Builder：点通知会再创建一层 MainActivity（返回退到空壳）；旋转/进程恢复会二次导航；无条件 `setShowWhenLocked` 会让主页也占锁屏 | 实测结论 |
| D110 | 静态 `shortcuts.xml` 快捷方式入口 | **本轮不实现**，登记推迟：静态 shortcut 的 `<intent>` 不能带 extras，entry 只能靠专用 action 或 data URI；Plan 2 只做「主页按钮 / 锁屏磁贴 / 通知」三条真路 | Mobile App Builder + UI Designer：nowhere 能用真机证据证明第四条路可用，写了就是假入口 | 以为的坑 |
| D111 | design §11 提到录音页「暂停/继续 + 音量键结束」 | **本轮不实现**，登记推迟到 Plan 4；`RecordUiState` 里旧的 `paused` 字段随重写删掉 | UI Designer：brief 与 plan 都没有这两条的实现步骤与证据，留在文档里只会让实现者猜 | 以为的坑 |
| D112 | 42dp/160dp 等尺寸口径 | 仓内唯一有据的下限是 **48dp**（`CalendarScreen.kt:238-240`、`LibraryScreen.kt:127`）；主钮 `160.dp` 保留，但补「最小按住 200ms 才算一次录音」防蹭碰；文档里出现的 42dp 属笔误，不改 | UI Designer：`42dp` 在全仓 docs 里 0 命中，无出处 | 实测结论 |
| D113 | 权限被拒后仍可能「先建草稿再让服务 fail」 | 钉死：`hasPermission()` 为 false 时 `onPressStart` 什么都不做（不生成 id、不建文件、不起服务），只把 `permissionBlocked` 置 true | UI Designer：原 4.1/4.4 顺序会让空草稿堆积、并在启动服务时才炸 | 实测结论 |
| D114 | Task 2 的 `PcmSink` 与 Task 1 的 `WavWriter` 对不上（前者 `bytesWritten()`/`sync()`，后者 `bytesWritten` 属性/无 `sync`） | Task 3 增加 `audio/WavSink.kt` 适配层；若 `WavWriter` 确实缺落盘方法，就给它加 `syncToDisk()` 并补 `WavStoreTest` 一条 | Lead 复核 Task 2 代码时发现：两个接口在不同任务里各自成型，中间没有适配器，Task 3 照 3.4c 直接 `RecorderCore(source, writer)` 会编译不过 | 实测结论 |
| D115 | `RecorderCore.finishWith` 把 `sink.sync()` 与 `sink.finish()` 放在同一个 `try` 里 | 拆成两个 `try`：sync 失败也要尝试 finish 回填头；补 2 条用例（`sync failure still finishes the header` / `finish failure ends with error and keeps real bytes`） | Lead 复核 Task 2 代码时发现：磁盘满/IO 错导致 sync 抛异常时，头会永远停在 `0xFFFFFFFF`，只能靠启动扫描补救；已做变异验证（改回合一 → 那条用例红，20 tests 1 failed） | 实测结论 |
| D116 | 简报的 `RecorderCore` 没有停止入口，`user` 与 `stopped` 无法区分 | 实现者新增 `RecorderCore.stop()`（置 `userStopped` 并转达 `source.stop()`）；**一个 `RecorderCore` 只 run 一次**（停止标记不重置），Task 3 每次录音新建实例 | 实施者报告 + Lead 复核：`run()` 里没有复位 `userStopped` 的地方，复用实例会立刻按 `user` 收尾 | 实测结论 |
| D117 | `read()` 返回 0 时 `RecorderCore` 走 `continue`，无 sleep | Task 3 的 `PcmSource` 真实现必须「连续 50 次（≈5 秒）读不到就报 `DEAD`」；JVM 测试里的假源不受影响 | 实施者主动披露（concern 5）：真设备半死时读循环会空转，界面仍显示录音中 | 以为的坑 |
| D118 | 证据需要「可见 Activity 起前台服务」，但 Task 4 之前没有录音页入口 | 临时给 `MainActivity` 加 DEBUG-only intent 钩子（`dk_debug_record` / `dk_debug_stop` / `dk_debug_cancel`、`dk_debug_dream_id`），用 `window.decorView.post` 等 Activity 可见后 `startForegroundService`；证据 APK sha256 `39dab6ec3fc469c76f7299455c0ee6a8c6d6821ad2b831f153b08ed2e860f090`；**钩子不入库**，diff 留在 `docs/evidence/plan2/p2-fgs-01-harness.patch`，工作树已 `git checkout --` 还原 | `git diff HEAD -- android/app/src/main/java/com/dreamkeeper/MainActivity.kt` 为空 |
| D119 | `RecordingSnapshot` 没有「正在收尾」态，按 STOP 到读循环退出之间 UI 会一直显示录音中 | 新增 `RecordingSession.markFinishing()`（RECORDING → FINISHING，保留 elapsed 与 amplitude），UI 可显示「正在保存」 | `grep -n markFinishing android/app/src/main/java/com/dreamkeeper/service/RecordingSession.kt` |
| D120 | D105 写的判据字段 `started=true` 在 API 37 的 dumpsys 里实际叫 `startRequested`，类型打十六进制 `types=0x00000080` | 证据按实际字段名记录，并把 `0x80 = FOREGROUND_SERVICE_TYPE_MICROPHONE` 写进说明；判据内容不变 | `docs/evidence/plan2/p2-fgs-01-services.txt` 第 43/46 行 |
| D121 | 收到非法命令就 `stopSelf` 会误杀正在进行的录音 | 只有 `active == null` 时才 `stopSelf(startId)`；有录音在跑时非法命令只记日志 | `RecordingService.kt` 的 `onStartCommand` 首段 |
| D122 | `PcmSink` 与 `WavWriter` 签名对不上（`bytesWritten` 是属性不是方法、没有 `sync()`） | 两层都做：新增适配层 `audio/WavSink.kt`（签名翻译），并给 `WavWriter` 加 `syncToDisk()`（真的刷盘）；补 `WavStoreTest` 第 18 条钉住「sync 不提前回填头」 | `android/app/src/main/java/com/dreamkeeper/audio/WavSink.kt`、`WavStoreTest.kt` |
| D123 | `read()==0` 在 `RecorderCore` 里是 `continue`（D117 留下的口子） | `AudioRecordSource` 连续 50 次（≈5 秒）读到 0 → `failureMessage="麦克风没有数据"`、返回 -1 → reason=dead → 收尾回填头 | `RecordingService.kt` 的 `AudioRecordSource` |
| D124 | 通知「结束」动作用 `PendingIntent.getForegroundService` 在部分机型会被判非法 | 改 `PendingIntent.getService(ACTION_STOP)`；两个 PendingIntent 都是 `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE` | `RecordingNotifications.kt` |
| D125 | 本任务的证据文件按仓库规矩由 Lead 复核提交，不由实现者提交 | 实现者只落盘 `docs/evidence/plan2/p2-fgs-01-*` 并在报告里列清单，Lead 复核四连判据后一次性提交 | 证据提交与代码分开两个 commit |
| D126 | 计数基线：Task 2 末 152 用例 / 14 类；本任务 +8（`RecordingCommandTest`）+1（`WavStoreTest`）= **161 用例 / 15 类** | 一律以 `test-results/testDebugUnitTest/*.xml` 统计为准；无钩子构建 APK 12,958,451 B | Lead 复跑 `:app:testDebugUnitTest` 并从 XML 统计 |
| D127 | `startForeground` 失败 / `AudioRecord` 打开失败 / `startCapture` 失败三条路径会留下 44 字节占位头孤儿文件，`startCapture` 失败还漏关写句柄 | 三条失败路径都 `file.delete()`；`startCapture` 失败额外 `writer.close()`（close 兜底 finish）避免 fd 泄漏 | `git show HEAD -- android/app/src/main/java/com/dreamkeeper/service/RecordingService.kt` |
| D128 | 通知栏截图的近白闸门 `-TopOffset 100 -MaxRatio 0` 判红：深色系统主题下 `nearWhite=118/69120=0.171%`，浅色主题下 `0.234%` | 白像素**全部**落在 SystemUI 给 app 图标垫的白圆底包围盒内（深色那轮定位：`nearwhite_total=1044`、`in_icon_box=1044`、`outside=0`、`bbox x=90..182 y=746..838`），不是 app 渲染；故本用例判红属预期，截图内容有效，并在证据里写明「换主题只能减少不能消除」 | `docs/evidence/plan2/p2-fgs-01-notify.txt`（含 `p2-fgs-01-whitepixels.ps1` 输出）、`p2-fgs-01-theme.txt` |
| D129 | `RecordViewModel` 简报没写 `: ViewModel()`，且 `stateIn` 的共享策略给了二选一 | 必须 `) : ViewModel()` + `stateIn(viewModelScope, Eagerly, RecordUiState())` | 计划 Task 4 §0 第 1 条 |
| D130 | `RecordingStarter.stop()/cancel()` 最自然的写法是 `stopService`，会绕过 `onRecordingFinished` → 永不到 DONE、一条梦都不落库 | 三者都走 action（`startForegroundService(ACTION_START)` / `startService(ACTION_STOP)` / `startService(ACTION_CANCEL)`），禁用 `stopService` | 计划 Task 4 §0 第 2 条 |
| D131 | Task 4 的接线断层：`DreamKeeperRoot` 只收四个参数、首页 `:134` 是「录音还没做」假按钮、entry 没人消费 | `DreamKeeperRoot` 增 5 个参数、`MainActivity` 用 `mutableStateOf` + `LaunchedEffect`、假按钮改真导航、entry 从 `backStackEntry` 取 | 计划 Task 4 §0 第 3 条 |
| D132 | 权限只有 `hasPermission: () -> Boolean` + 一个布尔，表达不出 ASKABLE/BLOCKED/GRANTED；首装 `shouldShowRequestPermissionRationale` 也 false，会把新用户直接送到「去设置」 | `RecordUiState.permissionState` 三态 + 持久化 `askedOnce` + 每次 RESUMED 递增 `permissionEpoch`；文案「允许麦克风」/「去设置」 | 计划 Task 4 §0 第 4 条 |
| D133 | 简报要求「假 session」，但 `RecordingSession`/`DreamRepository` 都是 final 无可注入 seam | 用真的：Robolectric + 内存 Room + `setMain(StandardTestDispatcher())`，只 fake `RecordingStarter` | 计划 Task 4 §0 第 5 条 |
| D134 | 简报写 `bytes > 0` 与 `rawAudioMs = bytes/32`，但 `RecordingSnapshot` 没有 `bytes` 字段；二次读文件在文件缺失时会抛异常 | `rawAudioMs = snapshot.elapsedMs`、空判定 `elapsedMs > 0L`、禁止二次读文件 | 计划 Task 4 §0 第 6 条 |
| D135 | `DONE(0)`（快速点按）不落库，但服务已建 44 字节占位头文件 → Task 6 会把它恢复成一条 0 秒空梦 | DONE 且 `elapsedMs == 0` 时删文件，并补「文件不存在」测试 | 计划 Task 4 §0 第 7 条 |
| D136 | 进程内已在录音时再按一次：服务静默丢弃重复 START，VM 却用本地新 UUID 落库 → 草稿指向不存在的文件 | `phase != IDLE` 直接 return；`dreamId/filePath` 以 `session.state` 为唯一真源 | 计划 Task 4 §0 第 8 条 |
| D137 | 手势只处理正常抬起：来电/系统手势/协程取消都没有 up，`onStop` 永不执行 → 录音停不下来 | `try/finally` 兜底 + `up == null` 走中断分支（`interrupted` 标记 + 「继续录」/「就这样」）、`<200ms` 直接取消、`awaitFirstDown(requireUnconsumed = false)` | 计划 Task 4 §0 第 9 条 |
| D138 | 简报把「取消立即删文件」写成契约，但 CANCEL 是异步的、fd 还开着 → 可能 DONE 一条指向已删文件的草稿 | VM 只发 `starter.cancel()`，删文件归服务；测试改成「收到 IDLE 后文件不存在」 | 计划 Task 4 §0 第 10 条 |
| D139 | `BackHandler` 只挡 RECORDING，FINISHING 按返回会丢草稿；跳 FollowUp 无 `popUpTo` → 返回死循环 | `BackHandler(enabled = RECORDING || FINISHING)`；导航用 `popUpTo(inclusive) + launchSingleTop`，`savedDreamId` 当一次性事件 | 计划 Task 4 §0 第 11–12 条 |
| D140 | 视觉与坐标不自洽：`EmberDeep` 直接放 `Night` 上只有 2.68:1、`NightVoid` 与 `Night` 只差 1.04:1、圆心在 50% 单手够不到、`check-near-white.ps1` 没有 `-MaxRatio` 参数、FINISHING 亚秒级截不到图 | 底色 `Night`；主钮 `EmberDeep` + 1.5dp `Ember` 描边 + `Paper` 字；圆心 65%；`ThemeContrastTest` 加 `EmberDeep on Night ≥ 3.0`（预期红，逼出描边）；证据改 `-Path … -TopOffset 100` 并把 nearWhite=0 写进 txt；FINISHING 用冻结帧 + 日志 | 计划 Task 4 §0 第 13–14 条 |
| D141 | §0.13 要求 `assertContrast("EmberDeep on Night", …, 3.0)`（预期红），与本任务「测试全绿」的完成门槛互相矛盾 | 改成两条：`Ember on Night ≥ 3.0`（证明描边可辨）+ `EmberDeep on Night < 3.0`（钉住「必须靠描边」的事实）；哪天 EmberDeep 被调亮会红，提醒复查方案 | 计划 Task 4 §0.13、`android/app/src/test/java/com/dreamkeeper/ui/theme/ThemeContrastTest.kt` |
| D142 | 要拍 ENOSPC 错误态，但模拟器磁盘写不满 | 加 DEBUG-only 注入开关（仅 `BuildConfig.DEBUG` 生效），release 不编入 | `ui/record/RecordScreen.kt`、`ui/record/RecordViewModel.kt` |
| D143 | 手势做了两层：进页 300ms 吞触摸 + 内层按住手势 | 保留（防「松手点正好落在麦克风上」误录一段静音） | `ui/record/RecordScreen.kt` |
| D144 | 10 张截图不全来自同一次 take（04 是保存态真帧，finishing 原生仅 14ms） | 在 `p2-ux-index.md` 逐图标注 take 与时间戳；phase 由日志定，不靠截图断言 | `docs/evidence/plan2/p2-ux-index.md` |
| D145 | 模拟器输入灌零 → 振幅恒 0 dBFS 下限，振幅环视觉未验证 | 如实记录；真机验收时补一张有信号振幅的图 | `docs/evidence/plan2/` |
| D146 | DONE/ERROR 状态先 `cancel()` 再收页面 | 保留（避免状态机在 DONE 上再收到 START） | `ui/record/RecordViewModel.kt` |
| D147 | DONE 后额外停留 900ms 展示「已保存」，属规格外 UX | 保留（4 点醒来需要一个「存住了」的确认）；**FollowUp 的 30 秒窗口从进入 FollowUp 起算，不吃这 900ms**，Task 5 落实 | `ui/record/RecordScreen.kt`、Task 5 §0 第 9 条 |
| D148 | 同一按压事件可能重入（`onPressStart` 被多次回调） | 加去重：`phase != IDLE` 直接 return，`dreamId` 以 session 为唯一真源 | `ui/record/RecordViewModel.kt` |
| D149 | `p2-ux-05-notif-ongoing.png` 近白闸门 118/69,120 = 0.17% 判红 | 白像素全部来自 SystemUI 给通知应用图标垫的白圆底（与 D128 同因）；判据改为「除该图标包围盒外为 0」，原始数字写进证据 | `docs/evidence/plan2/p2-ux-index.md` |
| D150 | Task 5 的 START 会走 `audioStore.create()`（`RecordingService.kt` START 分支），而 `WavStore.create` 是 `FileOutputStream(file,false)` → 补录把第一段截成 44 字节 | 新增 `WavStore.ensurePlaceholder(dreamId)` 与 `markOpen(file)`；START 分支改「不存在或 <44 → create；否则 markOpen + openWriter 追加」；`create` 的截断语义只留给新建 | 计划 Task 5 §0 第 1 条 |
| D151 | 三条失败路径与 CANCEL 都无条件 `file.delete()` → 补录那趟失败会把第一段一起删掉 | `ActiveRecording` 带 `createdThisTake`，只有本趟新建的文件才删 | 计划 Task 5 §0 第 2 条 |
| D152 | `FollowUpViewModel` 缺 `starter`/`audioStore`/`dreamId`，物理上发不出 START、也读不到最终字节数 | 构造注入 `repository, session, starter, audioStore, clock, dispatcher, windowMs=30_000, maxSegmentMs=120_000, dreamId` | 计划 Task 5 §0 第 3 条 |
| D153 | 设计 §8.1-1 写「一条梦＝从按下到窗口超时的连续文件（含中间静音）」，与「补录直接拼接」冲突 | **Lead 裁定：直接拼接，不补静音**。理由：补 30 秒静音会触发识别器端点检测提前收尾（后半段丢字），且 30s≈960 KB 白占空间。代价：`rawAudioMs` ≠ 墙钟跨度，设计记偏差 | 计划 Task 5 §0 第 4 条、设计 §8.1 |
| D154 | Task 5 若用 `save()` 整行 upsert 写时长，会覆盖用户改过的文本 | 新增窄更新 `DreamDao.updateAudio(id, rawAudioMs, transcriptStatus)` + `DreamRepository.updateAudio`；**本任务不写 `rawTranscript` 文本** | 计划 Task 5 §0 第 5 条 |
| D155 | 时长若把两次 run 的 `elapsedMs` 相加，各自 `/32` 截断会漂 | `rawAudioMs = state(file).dataSize / 32`（最终整文件） | 计划 Task 5 §0 第 6 条 |
| D156 | 文件尾部若残留半个采样帧，追加后整条时间线错位成噪声 | 追加前断言 `trailingPartialBytes == 0`；不为 0 则拒绝追加 + 记日志 + 记偏差，不许自动补/裁 | 计划 Task 5 §0 第 7 条 |
| D157 | 追加期间若有 STT 在读，识别器会把当前尾当 EOF，出偏短结果且不报错 | `placeholder == true` 期间禁止起转写；同一 dream 单一 in-flight（Mutex），Task 7 实现 | 计划 Task 5 §0 第 8 条、Task 7 |
| D158 | 倒计时若用 `while(true){delay}` ticker，`advanceUntilIdle()` 永不 idle 会挂死测试 | `remainingMs = windowMs - (clock() - openedAt)`；测试用 `advanceTimeBy + runCurrent`，期望值写具体数字 | 计划 Task 5 §0 第 9 条 |
| D159 | brief 缺 `onDoneNow`（「就这样」落不了地）、缺 `transcript` 字段、`visible` 初值会「进页即收起」 | `FollowUpUiState(dreamId, loaded, remainingMs, recording, transcript)` + 五个回调；`loaded` 门闩；`maxSegmentMs=120_000` 到点强制停；`BackHandler` 覆盖 | 计划 Task 5 §0 第 10 条 |
| D160 | 夜间可读性与对比度：正文若用 `Paper` 16.73:1 整屏最亮面积过大、`Line` 边框 1.40:1 等于无边界、倒计时用 Paper 太亮 | 正文 `#B8C1CF`（11.11:1）、倒计时 `Ember`（6.72:1）、按钮边界 `InkDim`（6.68:1）、主钮 `EmberDeep`+`Paper`（6.02:1）；进页 `fadeIn(120ms)`，收起落 `Night` 底页 | 计划 Task 5 §0 第 11 条 |
| D161 | 设计 §11 要「自动熄屏」与「设置里可关」，但应用没有 `DEVICE_POWER` 权限、本轮也没有设置页 | 本轮做法：`window.screenBrightness = 0.01f`（离开恢复）、不持 `KEEP_SCREEN_ON`；「可关」无落点 → 固定为开，待 Plan 4 设置页 | 计划 Task 5 §0 第 12 条、设计 §11 |
| D162 | 30 秒窗口缺无障碍与证据判据（倒计时加 liveRegion 会每秒播报、把人吵醒） | 麦克风 `heightIn(min=88.dp)`、三个可点节点带 `contentDescription`、倒计时 `contentDescription="还剩 N 秒"` 但不加 liveRegion；证据卡 YAVG ≤32、近白 `-TopOffset 100`、uiautomator 三节点 bounds 高 ≥132px | 计划 Task 5 §0 第 13–14 条 |
| D163 | §0-14 的字面谓词「`clickable="true"` 且 `content-desc` 非空」在本机恒为 0：API 37 的 Compose 把 `clickable` 与 `content-desc` 拆成两个同 bounds 的节点 | 改用等价且更强的几何关联判据：`clickable=true` 节点恰好 3 个（高 294/231/147px，全 ≥132），每个在**完全相同的 bounds** 上都有非空 `content-desc` | `docs/evidence/plan2/p2-followup-uiautomator.xml`、`p2-followup-index.md` |
| D164 | 未按计划 5.4b 显式调 `RecorderCore.rebase()` | 每次 take 都新建 `RecorderCore`，其 `run()` 自己把 `baseBytes` 设为 `sink.bytesWritten()`（已有 PCM 字节），`deficit` 恒从 0 起算——追加场景不存在「按松手前空档补静音」的膨胀。`rebase()` 保留给同一 core 跨暂停（Plan 4） | `audio/RecorderCore.kt`、`service/RecordingService.kt` |
| D165 | 录屏裁掉了进页前 5.6s：原片 208 帧里唯一 >32 的帧（YAVG 32.01 @0.57s）属于**录音页**「0:02 正在保存」的亮字 | "进页→收起"段单独裁出（126 帧，max 30.01、无 ≥48 帧）；原片留在 `.tmp-evidence/` 不入库，判据只在追问页区间上生效 | `docs/evidence/plan2/p2-followup-04.mp4` |
| D166 | 追加实测量 1900ms（按压 2080ms），落在 2000±100 内但贴下界 | 起录 ≈+200ms、停录 ≈−180ms；`stat2`（按住 1.10s）已写 900ms 可交叉印证这 200ms 是服务启动延迟、不是丢字节。验收阈值沿用 ±100，不为此放宽 | `docs/evidence/plan2/p2-followup-bytes-append.txt` |
| D167 | `FollowUpUiState` 保留了 `visible`（§0-10 字段表没有它） | `loaded` 防「进页即收起」，`visible` 驱动 `AnimatedVisibility` 与收起后 120ms 再出栈，二者分工明确，不算多余状态 | `ui/record/RecordUiState.kt` |
| D168 | `#B8C1CF` 未加进 `ui/theme/Color.kt`（该文件不在本任务允许清单） | 在 `FollowUpScreen.kt` 内以私有 `FollowUpBody` 定义并注明出处；下次动主题时再上收 | `ui/followup/FollowUpScreen.kt` |
| D169 | 屏幕亮度固定为 0.01f、没有「设置里可关」 | §0-12 已预告：无设置页，本阶段固定开；Plan 4 设置页落地后再给开关 | 计划 Task 5 §0-12 |
| D170 | 收起后 `popBackStack()` 回日历（不是回详情页） | followup 入栈时 record 已被 `popUpTo(inclusive)` 清掉，pop 即回日历；pop 失败再 `navigate(日历)` 兜底 | `ui/followup/FollowUpViewModel.kt` |
| D171 | 120s 上限提示用内联中文「单段最长 120 秒，已替你保存」 | 全仓库同类文案均为内联，本阶段不抽 `strings.xml`（国际化不在范围内） | `ui/followup/FollowUpScreen.kt` |
| D172 | `onTimeout` 后不写 `rawTranscript` 文本（计划 5.1 要求「rawTranscript 非空」被 §0-5 覆盖） | 只置 `transcriptStatus="PENDING"` 与 `rawAudioMs`；`FollowUpViewModelTest` 显式断言原文未被改动——文本由 Task 7 的 STT 写 | `ui/followup/FollowUpViewModel.kt`、Task 7 |
| D173 | `WavStore` 没有暴露文件列表、`DreamDao` 没有「列出所有带 audioPath 的行」的查询 → 扫描两个方向都枚举不了（编译级缺口） | `WavStore.files(): List<File>`（只认 UUID 形 `*.wav`）+ `DreamDao.audioRows()` / `existsById(id)` | 计划 Task 6 §0 第 1 条 |
| D174 | 清理孤儿与写毫秒若复用 `updateAudio`，会把 `transcriptStatus` 一起改成 `PENDING`（`FAILED` 会被悄悄改回） | 新增两条窄更新：`clearAudio(id)`、`setAudioMs(id, ms)`；`updateAudio` 留给 Task 5 的场景 | 计划 Task 6 §0 第 1 条 |
| D175 | `DreamRepository.db` 是私有字段，扫描层拿不到事务边界 | `recoverDraft` / `markMissing` / `fixAudioMs` 三个带 `db.withTransaction` 的仓库方法；`AudioScan` 不碰 DAO | 计划 Task 6 §0 第 2 条 |
| D176 | **最危险的一条**：录音前先写占位头、行要 DONE 后才写 → 启动扫描会把正在录的文件当孤儿插草稿；随后 `RecordViewModel.alreadyPersisted()` 直接 return，导致 DONE 不保存、不跳追问页 | `WavStore` 维护「当前打开文件」集合，扫描跳过它；同时改 `RecordViewModel`：行已存在也窄更新 path/ms 并发 `savedDreamId` | 计划 Task 6 §0 第 3–4 条 |
| D177 | `repair()` 只修文件不写库，扫描若忘了写 `rawAudioMs`，库里时长会一直是旧值 | `repair` 成功后由扫描调 `fixAudioMs(id, (bytes-44)/32)`；禁用 `save()`（会改 `updatedAt`、覆盖并发编辑） | 计划 Task 6 §0 第 5 条 |
| D178 | 恢复草稿只写了 `isDraft`/`PENDING` 两个字段，`dreamDate`/`createdAt` 无默认值 | 补 `rawTranscript=""`、`rawAudioPath`、`rawAudioMs`，且日期取 `file.lastModified()`（不许用今天） | 计划 Task 6 §0 第 6 条 |
| D179 | 0 字节 / `<44` 字节文件会让 `WavStore` 抛异常；不逐文件 `runCatching` 会在启动 IO 协程里整段炸掉（连其它状态一起不修） | 四条主状态 + 第 5 态（0/<44）逐文件 `runCatching`，外层再加一层；无行→删文件不建行，有行→`markMissing` | 计划 Task 6 §0 第 7 条 |
| D180 | 尾部奇数半帧没处理：`repair` 会把奇数 `dataSize` 写进头，此后 `markOpen` 永久拒绝追加 | 扫描先截掉奇数尾字节再 `repair`，单独出用例 | 计划 Task 6 §0 第 8 条 |
| D181 | 正好 44 字节（`dataSize=0`）会变成 `0:00` 幽灵条目 | 按第 5 态处理（无行删文件、有行标 `FAILED`） | 计划 Task 6 §0 第 9 条 |
| D182 | 扫描若早于 `SeedData`（`count()==0` 整体跳过），先插草稿会让演示种子永久不出现 | 扫描排在种子之后，且挂 `appScope(IO)`、不 await、不占主线程 | 计划 Task 6 §0 第 10 条 |
| D183 | `Application.onCreate` 里无条件启动扫描，会让 Robolectric 每个用例都跑真实 `filesDir` IO（隐式耦合） | 扫描入口收进 `AppContainer`（lazy）+ `BuildConfig` 门闩，App 只调容器 | 计划 Task 6 §0 第 11 条 |
| D184 | 「头修好了」没有机器判据：`ffprobe` 对占位头也照样报 duration，hexdump + 肉眼无法反驳 | 写 python 校验脚本（读 offset 4..8 / 40..44 + `ffmpeg` **stderr 必须为空** + 时长差 <100ms），exit code 即结论；先在 before 文件上红一次、再在 after 上绿 | 计划 Task 6 §0 第 12 条 |
| D185 | `am force-stop` 造不出状态①：`rawAudioPath` 只在 DONE 之后写，录制中被杀根本没有行 | 用真路径造：录 3 s → 松手 → 补录 2 s（`markOpen` 写回 `FFFFFFFF`）→ 补录中 force-stop → 重启 | 计划 Task 6 §0 第 13 条 |
| D186 | 四种状态若只靠手改 DB/文件造出来，无法区分「App 自己发现的」与「人摆出来的」 | 每态证据强制一行 `pre_state_by:` + 造数脚本入库；干净态用「前后 sha256 + mtime 不变」证 `Noop`，不靠日志自证 | 计划 Task 6 §0 第 13 条 |
| D187 | 拉 WAV 与查库的取证方法会自己骗自己：PS 重定向毁二进制、边跑边 cat 是撕裂读、拿 App 写的 ms 当判据 | 拉文件必须 `cmd /c` + 设备/宿主 sha256 逐字符比对；查库先 force-stop 再导出、宿主 python 只读打开；交叉表输出 `(bytes-44)//32 | rawAudioMs | diff`，`diff != 0` 即红 | 计划 Task 6 §0 第 14 条 |
| D188 | 要把「恢复的草稿能看能播、权限路径 A 整链、100+ 文件扫描耗时、多次重启幂等」这些端到端项塞进本任务，会让证据变成截图堆 | 这些挪给 Task 9；本任务只留四态 + 第 5 态矩阵、变异验证、clean 态字节不变 | 计划 Task 6 §0 第 16 条 |

> 规则：每发现一处与计划的偏离就追加一行；「原因」必须写能被别人复核的事实，不写「感觉」。

