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
  期望 `pcm_s16le / 16000 / 1ch`；`duration ≈ (bytes-44)/32`（差 < 100ms）；**并且 stderr 不出现 `[wav @`**（出现 `Ignoring maximum wav data size` / `Estimating duration from bitrate` / `Packet corrupt` 即判失败）。
  **ffprobe 不能用来验头**：占位头它也照样报一个 duration，头必须按字节读 `offset 4..8` 与 `40..44`。
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

## 硬规矩

- **不许伪造转写成功**：系统识别器不可用时，证据就是「探测结果 + FAILED 状态 + 手动补字可用」，写成「转写成功」等于造假。
- 二进制进 `audio/`，文本进顶层；txt 里用 `artifact: audio/xxx.wav` 交叉指路。
- 任何与计划不符的偏离，记进计划末尾的偏差表（**从 D88 起**），并注明是「我以为的坑」还是「实测结论」。
