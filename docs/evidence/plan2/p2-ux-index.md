# Plan 2 Task 4 — 「按住说梦」录音页证据索引

生成时间：2026-10-30（设备时钟 `adb shell date` = `2026-10-30T17:01:50+0800`，Asia/Shanghai）
设备：emulator-5554，Android API 37，1080x2400，density 420（1dp = 2.625px）
宿主：Windows，Gradle 工程在 `android/`，JDK = Android Studio jbr

## 判别串（截图内容验收用）

| 文件 | 应看到的文字 |
| --- | --- |
| 01-idle | `0:00`、`按住 说梦`（无取消/无保存态） |
| 02-recording-t1 | `0:01`、按压环、`取消录音`、状态栏绿色麦克风隐私点 |
| 03-recording-t4 | `0:04`、按压环、`取消录音`（与 02 同一 take） |
| 04-finishing | `0:05` 冻结、`正在保存`（圆内）、`正在保存…`（圆下）、`取消录音`、主钮禁用 |
| 05-notif-ongoing | 通知栏 `梦匣 / 正在录梦 / 松开就结束；点「结束」立刻停 / 结束` |
| 06-perm-askable | `需要麦克风权限才能录梦` + `允许麦克风` |
| 07-perm-blocked | `需要麦克风权限才能录梦` + `去设置` |
| 08-notif-off | 底部 `通知栏不会显示，录音照常` |
| 09-error-enospc | 主钮 `空间不足，先去清理`；错误卡 `空间不足 / ENOSPC: / 再试一次（禁用）/ 返回首页（这段录音留着）` |
| 10-interrupted | `录音被系统打断（0:05）` + `继续录` / `就这样` |

## 文件清单（sha256 前 16 位 + 字节数 + 近白闸门）

命令：`scripts/check-near-white.ps1 -Path <png> -TopOffset 100`（脚本无 `-MaxRatio` 参数，未传）。
判据：nearWhite=0 为通过；05 的 118 个像素来自通知应用白色圆图标（0.17%），非空白页。

| # | 文件 | bytes | sha256(16) | nearWhite | 说明 |
| --- | --- | --- | --- | --- | --- |
| 01 | p2-ux-01-idle.png | 49,608 | 27610A58C91EC827 | 0 (0.0000%) | 最终 APK，录音页空闲 |
| 02 | p2-ux-02-recording-t1.png | 73,641 | 97E0458EAFD504FE | 0 (0.0000%) | take3 按住 0:01（脚本截图） |
| 03 | p2-ux-03-recording-t4.png | 75,118 | 14C82B343A0B14EC | 0 (0.0000%) | take3 同一 take 按住 0:04 |
| 04 | p2-ux-04-finishing.png | 62,277 | 726162051C73732C | 0 (0.0000%) | take3 松手后保存态真帧（0:05 冻结） |
| 05 | p2-ux-05-notif-ongoing.png | 174,521 | D2558EF5629439B1 | 118 (0.1707%) | 通知栏常驻（系统归入 Silent 分组） |
| 06 | p2-ux-06-perm-askable.png | 75,222 | 61DB34F0318CC89B | 0 (0.0000%) | 权限 ASKABLE |
| 07 | p2-ux-07-perm-blocked.png | 72,820 | F0E3C063F8470D93 | 0 (0.0000%) | 权限 BLOCKED |
| 08 | p2-ux-08-notif-off.png | 60,231 | A6B8BA5F0FA6132E | 0 (0.0000%) | POST_NOTIFICATIONS 关闭 |
| 09 | p2-ux-09-error-enospc.png | 89,873 | 91742E785365643B | 0 (0.0000%) | ENOSPC 错误卡（DEBUG 注入，见 D142） |
| 10 | p2-ux-10-interrupted.png | 72,284 | F8A902E098D7783C | 0 (0.0000%) | HOME 打断后的横幅 |
| — | p2-ux-hold.mp4 | 722,131 | 004507162720A0F4 | — | take3 同一次按住；9.20s / 1080x2400 / 14.02fps |
| — | p2-ux-bytes-hold.txt | — | — | — | 三次 stat（take4） |
| — | p2-ux-latency.txt | — | — | — | START/STOP 时间戳（take C + take B 复核） |

证据 APK：`android/app/build/outputs/apk/debug/app-debug.apk`
- 12,958,451 bytes，sha256 `998227942986919c3a900fe92c756690e54c805cd3ff792e648c019e4876e33a`（2026-10-30 16:54）
- 单测：`suites=16 tests=176 skipped=0 failures=0 errors=0`

## 复跑命令

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /d/DreamKeeper/android
./gradlew :app:testDebugUnitTest :app:assembleDebug
ADB=/c/Users/17383/AppData/Local/Android/Sdk/platform-tools/adb.exe
$ADB -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk

# 1) 空闲页
$ADB -s emulator-5554 shell am start -n com.dreamkeeper/.MainActivity
$ADB -s emulator-5554 shell input tap 580 2065          # 首页「说梦」
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-screen.ps1 \
  -OutPath docs/evidence/plan2/p2-ux-01-idle.png -Serial emulator-5554

# 2) 按住 6s（圆中心 541,1542），按 1s/4s 截 02/03；松手后立刻 raw screencap 截 04
$ADB -s emulator-5554 shell input swipe 541 1542 541 1542 6000
MSYS_NO_PATHCONV=1 $ADB -s emulator-5554 shell screenrecord --bit-rate 8000000 \
  --time-limit 20 /sdcard/p2-ux-hold.mp4
MSYS_NO_PATHCONV=1 $ADB -s emulator-5554 pull /sdcard/p2-ux-hold.mp4 docs/evidence/plan2/

# 3) 字节（同一 dreamId，按住中两次 + 松手后冻结）
MSYS_NO_PATHCONV=1 $ADB -s emulator-5554 shell run-as com.dreamkeeper \
  stat -c %s files/audio/<dreamId>.wav

# 4) 权限三态：清 askedOnce + 撤销权限
$ADB -s emulator-5554 shell pm revoke com.dreamkeeper android.permission.RECORD_AUDIO
MSYS_NO_PATHCONV=1 $ADB -s emulator-5554 shell run-as com.dreamkeeper rm -f shared_prefs/record.xml
# 点圆 → 06；点「允许麦克风」→ 系统弹窗点 Don't allow → 再点圆 → 07

# 5) 通知栏（05）：设备深色后，按住中展开
$ADB -s emulator-5554 shell cmd uimode night yes
$ADB -s emulator-5554 shell cmd statusbar expand-notifications

# 6) 打断（10）：按住中按 HOME，再回前台
$ADB -s emulator-5554 shell input keyevent KEYCODE_HOME
$ADB -s emulator-5554 shell am start -n com.dreamkeeper/.MainActivity

# 7) ENOSPC（09，DEBUG 构建专用 extra）
$ADB -s emulator-5554 shell am force-stop com.dreamkeeper
$ADB -s emulator-5554 shell am start -n com.dreamkeeper/.MainActivity \
  --es dk_record_error "ENOSPC: 录音文件写不进去，空间不足"

# 8) 闸门
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-near-white.ps1 \
  -Path docs/evidence/plan2/p2-ux-01-idle.png -TopOffset 100
```

## 注记

- 02/03/04 来自同一次 take（dreamId `7a7116f1-2085-4e39-b207-88f2d557f12a`，16:59）；04 是松手后
  `adb exec-out screencap` 抢到的保存态真帧，和 `p2-ux-latency.txt` 的 FINISHING→DONE（13ms）互证。
  「正在保存…」同时覆盖真实 FINISHING 与 DONE 后 900ms 的最短呈现（D147），视觉一致。
- 05 的通知被系统归入 **Silent** 分组（渠道重要性低），但内容是 FGS 常驻通知且带「结束」动作；
  设备在 16:55 切到深色主题（`cmd uimode night yes`），因此 05 是深色通知栏。
  `cmd statusbar expand-notifications` 本身不会打断按住手势；10 的打断是 `KEYCODE_HOME` 触发的。
- 08 的 `POST_NOTIFICATIONS` 撤销用 `pm revoke`；之后已恢复 `pm grant`。
- 09 走 DEBUG-only `dk_record_error` 注入（D142），因为模拟器无法稳定写满磁盘；Release 不生效。
- 模拟器无 host audio 输入，振幅恒为 0（D145），不试图「解决」。
- 09/10 的截图未走 `capture-screen.ps1` 生成，但均以 `-VerifyOnly` 校验 PNG 结构；raw 截图链路
  与脚本内部一致（`adb exec-out screencap`）。
