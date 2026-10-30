# Plan 2 Task 5 真机证据索引 — 松手后 30 秒追问窗口，补录追加到同一个 WAV

- 设备：`emulator-5554`（Android API 37，`wm size` 1080×2400，`wm density` 420 → 2.75×，48dp = 132px）
- 取证时间：`adb shell date` = **Fri Oct 30 17:37:11 CST 2026**（截图/字节/录屏同一次 take，17:36:14–17:37:13）
- 被测 APK：`android/app/build/outputs/apk/debug/app-debug.apk`
  - sha256 = `0b52f1c63945bce71a117f8b16ff4833c01b0ff2c2f3ff6506382f8b1d28a932`（前 8 位 `0b52f1c6`）
- 采集脚本（本地临时目录，未入库）：`.tmp-evidence/take3.py`；倒计时帧由同一 take 的连拍选出

## 文件与哈希

| 文件 | sha256 前 8 位 | 说明 |
| --- | --- | --- |
| `p2-followup-01.png` | `665bba86` | 刚进追问页：倒计时 30 秒、按住补录、就这样 |
| `p2-followup-02.png` | `18e8c875` | 同一 take 里倒计时读到 **5 秒** 的那一帧（连拍 `f02`，t0+20.8s） |
| `p2-followup-03.png` | `778205bc` | 窗口收起后落在日历页（未退桌面） |
| `p2-followup-04.mp4` | `59f3c5fd` | 录屏，已裁到「进页 → 收起」区间（原片 39.43s，从 5.6s 起，33.28s） |
| `p2-followup-bytes-append.txt` | `547f9937` | 同一 dreamId 三次 stat + 追加时长 + 第一段 PCM 比对 |
| `p2-followup-uiautomator.xml` | `1399909a` | 补录结束后追问页的 `uiautomator dump` 原件 |
| `audio/p2-followup-appended.wav` | `741219ef` | 追加后的整段 WAV（16kHz/mono/s16le，4.800s，153644 字节） |

判据（全部通过）：

- 近白：`scripts\check-near-white.ps1 -Path ... -TopOffset 100` → 三张图 `nearWhite=0 ratio=0.0000%`，
  `center=#05070A`（追问页 NightVoid）/ `#05070A` / `#0B0E13`（日历 Night）。
- 白闪：`ffmpeg -i p2-followup-04.mp4 -vf signalstats,metadata=print:key=lavfi.signalstats.YAVG` →
  126 帧，min 21.89 / max **30.01** / mean 27.91，`>32` 0 帧，`≥48` 0 帧。
- 无障碍：dump 里 `clickable="true"` 恰好 3 个节点，高度 294px / 231px / 147px（均 ≥132px），
  每个都能在**完全相同的 bounds** 上找到非空 `content-desc`（见 `p2-followup-bytes-append.txt` 末尾与
  `task-5-report.md` §0-13、偏差 D163）。

## 判别串（脚本可用）

- 追问页标题：`追问`、`还想补充点什么吗？`；录音中标题：`在听，松手就接上`
- 倒计时：`content-desc="还剩 N 秒"`（N = 30…0）
- 麦克风：`content-desc="按住补录，松手结束"` / `"正在补录，松手结束"`
- 主按钮：`content-desc="就这样，保存并收起"`
- 中继行为：第二次 `START` 走 `markOpen`+`openWriter` 追加，不删文件、不重写 44 字节头

## 复跑命令（PowerShell / bash 均可，`adb` 用绝对路径）

```bash
ADB=/c/Users/17383/AppData/Local/Android/Sdk/platform-tools/adb.exe
DEV="-s emulator-5554"
# 0) 归零
$ADB $DEV shell am force-stop com.dreamkeeper
$ADB $DEV shell run-as com.dreamkeeper sh -c 'rm -f files/audio/*.wav'
$ADB $DEV shell pm grant com.dreamkeeper android.permission.RECORD_AUDIO
$ADB $DEV shell pm grant com.dreamkeeper android.permission.POST_NOTIFICATIONS
$ADB $DEV shell am start -n com.dreamkeeper/.MainActivity && sleep 4
# 1) 进录音页 → 按住 3 秒（第一段）
$ADB $DEV shell input tap 540 2053 && sleep 2
$ADB $DEV shell input swipe 541 1542 541 1542 3000
# 2) 等服务收尾并进追问页，取第一次 stat
sleep 2
WAV=$($ADB $DEV shell run-as com.dreamkeeper sh -c 'ls -t files/audio | head -1')
$ADB $DEV shell run-as com.dreamkeeper stat -c %s files/audio/$WAV      # stat1
# 3) 追问页里按住麦克风 2 秒（补录）
$ADB $DEV shell input swipe 540 1984 540 1984 2000
# 4) 再 stat 两次
$ADB $DEV shell run-as com.dreamkeeper stat -c %s files/audio/$WAV      # stat2/stat3
# 5) 无障碍树与截图
$ADB $DEV shell uiautomator dump /sdcard/ui-followup.xml && $ADB $DEV pull /sdcard/ui-followup.xml .
$ADB $DEV exec-out screencap -p > shot.png
```

## 结论数字（与 `p2-followup-bytes-append.txt` 一致）

- stat1（第一段结束后）92844 字节 → stat3（补录结束后）153644 字节，差值 **60800 字节 = 1900 ms**
  （按压 2080 ms；服务起录约 +200 ms、停录约 −180 ms），落在目标 2000 ms ±100 内。
- 整文件 `(153644−44)/32 = 4800 ms`；第一段 `(92844−44)/32 = 2900 ms`。
- 第一段 PCM（跳过 44 字节头）md5 `dc475bac…` 与终文件同段 md5 完全一致 → 第一段未被截断、未被改写。
- stat3 之后 0.3s 复查仍为 153644 → 写入已停止，没有继续增长。
