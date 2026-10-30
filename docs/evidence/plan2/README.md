# Plan 2 证据约定（录音与转写）

本目录放 Plan 2 的全部证据。**先读这一页再干活**，十个 Task 一律按此命名与判据，不许各写一套。

## 目录与命名

```
docs/evidence/plan2/
├── README.md                     ← 本文件
├── p2-<case>-<nn>-<state>.png    ← 截图（桌面/录音页/详情页/通知栏）
├── p2-<case>-<nn>-<state>.txt    ← hexdump / ffprobe / dumpsys / logcat 摘录
├── p2-<case>-<nn>-<state>.json   ← 结构化数据（若需要）
└── audio/                        ← 二进制：wav 原始产物、fixture
    └── p2-<case>-<nn>-<state>.wav
```

`<case>` 取值：`wav`（WAV 数学与 fixture）、`fgs`（前台服务与通知）、`ux`（录音页）、`followup`（追问窗口）、`scan`（启动扫描与头修复）、`stt`（转写）、`play`（播放）、`perm`（权限）、`e2e`（端到端）。
`<nn>` 两位序号；`<state>` 用一句话说清这张图/这个文件的状态（`before` / `after` / `idle` / `recording` / `appended`）。

## 每张截图必须带的三样东西

1. **APK sha256**（八个字符起）：`(Get-FileHash android\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256).Hash`
2. **设备时间**：`adb shell date`（时区已设 `Asia/Shanghai`）
3. **复跑命令**：能让人照着重跑一次的那一行

判别串（截图里必须能看见其一，证明不是旧包）：详情页四 tab 文案「原样/小说化/标签/素材」、种子日期 = 当月 1/8/15/22、卡片不重复首句。

## W1 / W2 / W3（录音证据三层，缺一层无效）

- **W1 字节在长**：`run-as com.dreamkeeper stat -c %s files/audio/<id>.wav` 两次采样，差值 ≈ 32000 × 秒。
- **W2 可解码且时长对得上**：
  ```
  ffprobe -v error -show_entries stream=codec_name,sample_rate,channels:format=duration,size -of compact <wav>
  ```
  期望 `pcm_s16le / 16000 / 1ch`；`duration ≈ (bytes-44)/32`（差 < 100ms）。
- **W2b 头必须按字节验（ffprobe 不算）**：`od -A d -t x1 -N 44 <wav>`，只看
  `offset 4..8 == bytes-8`、`offset 40..44 == bytes-44`、`(bytes-44) % 32 == 0`，且 44 字节整体逐字节等于黄金 hex。
  实测教训：把修好的文件改成**看起来合法但错误**的头（`riffSize=32036` / `dataSize=32000`），`ffprobe` 报
  `duration=1.000000` 且**零警告**；占位头那份只有加 `-v warning` 才吐 `Ignoring maximum wav data size`，`-v error` 一字不出。
  所以「ffprobe 没报错」不能当「头对了」。
- **W2c ffmpeg stderr 的容许行**：`ffmpeg -v warning` 对**任何**合法 mono PCM WAV 都会打一行
  `Guessed Channel Layout: mono`（WAV 头里没有 channel layout），这一行放行；
  其余任何行为判失败（占位头的 `Ignoring maximum wav data size` / `Estimating duration from bitrate` / `Packet corrupt` 照红）。
  校验脚本见 `docs/evidence/plan2/p2-scan-validate.py`（`BENIGN_STDERR` 正则精确匹配，before 4 行红、after 1 行绿）。
- **W3 信号来源**：`ffmpeg -hide_banner -nostats -i <wav> -af astats=metadata=1:reset=0 -f null -`，看 `RMS level dB`。模拟器默认灌零（除非以 `-allow-host-audio` 启动），`-inf` 说明**是环境静音，不是 App 坏**——必须在证据里写明 `信号来源 = seam / 静音 / host-mic`，不许含糊。

## 取文件与造故障（命令固定）

```powershell
$adb = "C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
# 取设备文件（禁止用 PowerShell 的 > 重定向，会把二进制 UTF-16LE 化）
cmd /c "`"$adb`" exec-out run-as com.dreamkeeper cat files/audio/<id>.wav > docs\evidence\plan2\audio\<name>.wav"
# 杀掉进程（不走 onDestroy，验证启动扫描）
& $adb shell am force-stop com.dreamkeeper
# 权限两路
& $adb shell pm revoke com.dreamkeeper android.permission.RECORD_AUDIO
& $adb shell appops set com.dreamkeeper RECORD_AUDIO ignore
```

## 模拟器音频前提（先读这一条再看 W3）

- 本机 emulator 37.2.12.0 的 `-help` 原文：`-allow-host-audio  Allows sending of audio from audio input devices. Otherwise, zeroes out audio.`
  → **默认输入是零**，「文件有字节」不等于「录到声音」。
- AVD `Medium_Phone_API_37.0` 的 `config.ini` 里是 `hw.audioInput=yes`。
- **已实测（2026-10-30 18:00，Plan 2 Task 6 取证期间）**：宿主 `Get-CimInstance Win32_Process` 里 qemu 命令行
  **既无 `-allow-host-audio` 也无 `-audio`**，且拉回的 WAV `RMS level dB: -inf`（全零 PCM）→ 「本机默认灌零」由假设升为事实。
  启动命令（Lead 记于 `p2-env-01-emulator.txt`）：`emulator -avd Medium_Phone_API_37.0 -no-snapshot-save -no-boot-anim`。
- 因此在没有宿主麦克风证据时，W3 只能写 `信号来源 = seam`（全零 PCM，`RMS level dB: -inf`），
  **不许**写成「录音功能正常」。反过来，`-inf` 也不能证明 App 坏了——它证明的是这一路没有真实声源。

## 三路权限证据（三条路必须分开写，不许混）

| 路径 | 前置 | 命令 | 期望（判据） | 不能下的结论 |
|---|---|---|---|---|
| **A 录制中被吊销** | 正在录音，字节在涨 | `pm revoke com.dreamkeeper android.permission.RECORD_AUDIO` | 11+ 会**直接杀进程**：`pidof` 前后不同或为空、`dumpsys activity services` 里服务消失、两次 `stat` 字节冻结、WAV 头仍是占位（`offset 40..44 == FF FF FF FF`，留给 Task 6 扫描修复） | 不能宣称「App 优雅处理了吊销」——进程被杀时 App 没有机会反应 |
| **B 先吊销再从页面起** | 已吊销，UI 可操作 | 同上吊销后 `am start` 进录音页并按住 3 秒 | `snapshot.fail("没有录音权限")` → `stopSelf()`：无通知、无服务残留、无 `files/audio/` 新文件、`logcat` 无 `FATAL` | 不能拿这条路证明 A 的行为 |
| **C appops 软拒** | 权限仍是 granted | `appops set com.dreamkeeper RECORD_AUDIO ignore` 后录音 | **待实测**：可能走到 `AudioRecord` 创建/启动失败（`UnsupportedOperationException`/`SecurityException`/静音），也可能返回全零 | 现在**不许**在验收文档里给它写判据；先跑一次把异常类型记下来，再回来补 |

## 前台服务四连判据（Task 3 的 3.7，抄自计划）

1. `dumpsys activity services com.dreamkeeper` 同一段：`started=true` **且** `isForeground=true` **且** `foregroundId=42` **且**类型含 `microphone`。
2. `dumpsys notification --noredact`：本包 `id=42`、`flags` 含 `0x40`（FLAG_FOREGROUND_SERVICE）、ongoing。
3. 两次 `run-as com.dreamkeeper stat -c %s files/audio/<id>.wav` 间隔 ≥3 s，`Δbytes/Δt` 在 **32000 B/s ±2%**——这是「AudioRecord 真的在按硬件节奏供数」的唯一自动判据。
4. `logcat -d` 里 `FATAL` / `ForegroundServiceStartNotAllowed` / `DidNotStartInTime` 全空。

跑法固定为：`am start -n com.dreamkeeper/.MainActivity` → 点「说梦」→ `input swipe 540 1600 540 1600 3000`（或手按住 3 秒）。
**不要**用 `am start-foreground-service`（shell 是后台，14+ 必抛 `ForegroundServiceStartNotAllowedException`），证据目录里出现这条命令的失败日志就算违规。

## 硬规矩

- **不许伪造转写成功**：系统识别器不可用时，证据就是「探测结果 + FAILED 状态 + 手动补字可用」，写成「转写成功」等于造假。
- 二进制进 `audio/`，文本进顶层；txt 里用 `artifact: audio/xxx.wav` 交叉指路。
- 任何与计划不符的偏离，记进计划末尾的偏差表（**从 D88 起**），并注明是「我以为的坑」还是「实测结论」。
