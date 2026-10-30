# Plan 2 Task 7 系统转写证据索引

- apk_sha256: 01c90d82608331400b053f0d45418b337af237a3468bf3103220398f1407df4a
- installed_apk_sha256: 01c90d82608331400b053f0d45418b337af237a3468bf3103220398f1407df4a（exec-out 拉回后比对一致）
- device: emulator-5554, API 37, 1080x2400 / 420dpi
- device_date: Fri Oct 30 18:57:30 CST 2026
- 结论: 本机 checkRecognitionSupport 四张语言表全空 -> `Unavailable("本机没有中文离线识别")`；
  未进入文件转写（33+ 路径未验证），但失败路径真实可复现、未伪造任何转写文本。

## 证据清单

| 文件 | 内容 | 采集方法 |
| --- | --- | --- |
| p2-stt-01-service.txt | 系统识别服务包名/uid/APK/INTERNET；DreamKeeper APK 权限 | settings + dumpsys package + aapt2 |
| p2-stt-02-availability.txt | 四张语言表原文 + availability 结论 | 真机录音 6s 后取 RecordingService/DreamStt/TranscriptWriter 日志 |
| p2-stt-03-attempt.txt | 未进入 transcribe 的时间线、字节/管道/SpeechRecognizer 均为 0 | 同上 |
| p2-stt-04-logcat.txt | 同窗口识别器 pid 日志为 0 行 | `adb logcat -d --pid=<tts pid>` |
| p2-stt-05-negative.txt | 坏文件负对照未执行（设备能力不足，不伪造） | — |
| p2-stt-06-detail-failed.png | 详情页 FAILED 分支（没有转写成功 + 手动补字入口） | 真机截图 sha256 5F73A738912849F752D4D3CFA1AE99622DFF931C0E08710172D468F8244FD022 |
| p2-stt-07-detail-manual.png | 手动补字 Dialog（已输入 manualnote，键盘已收起） | 真机截图 sha256 ACCF82C8036DF19DF76762467180955CABE3378DA87EE110B6762710DA3B5846 |

## 06 复跑

```bash
ADB=.../platform-tools/adb.exe
$ADB shell am force-stop com.dreamkeeper
$ADB shell am start -n com.dreamkeeper/.MainActivity
$ADB shell input tap 541 2053          # 首页「说梦」
$ADB shell input swipe 541 1542 541 1542 6000   # 按住录音 6s
# 等 writer 写回 FAILED（约 1s），在追问窗口结束前杀掉进程，避免 FollowUpViewModel 收尾覆盖状态
$ADB shell am force-stop com.dreamkeeper
$ADB shell am start -n com.dreamkeeper/.MainActivity
$ADB shell input tap 689 971           # 日历 10月30日
$ADB shell input tap 540 210           # 当日最新卡片
```

## 07 复跑

在 06 的页面点「手动补字」(540,1367) -> 点输入框 (400,1000) -> `input text manualnote`
-> `input keyevent 4` 收起键盘 -> 截图；再点「保存」验证 CAS 写回（本轮已验证）。

## 近白闸门（scripts/check-near-white.ps1 -TopOffset 100 -MaxRatio 0）

```
RESULT: OK p2-stt-06-detail-failed.png dims=1080x2400 nearWhite=0 ratio=0.0000%
RESULT: OK p2-stt-07-detail-manual.png dims=1080x2400 nearWhite=0 ratio=0.0000%
```

## 数据库核对（只读 pull + python sqlite3）

- 06 之前（转写失败写回后、手动补字前）:
  `c0f55f08-3e7c-4a64-b0e1-774472d042c4 rawAudioMs=6021 transcriptStatus=FAILED rawTranscript='' updatedAt=1793357805954`
  （updatedAt 是插入时间；窄更新没有写 updatedAt）
- 07 之后（手动补字保存，本轮实测）:
  `c0f55f08-3e7c-4a64-b0e1-774472d042c4 rawAudioMs=6021 transcriptStatus=OK rawTranscript='manualnote' updatedAt=1793357805954`
  （保存后状态 OK、文本落库；updatedAt 仍是插入时间，未被子句 CAS 改动）

## 已知限制（详见 task-7-report.md）

- FollowUpViewModel 收尾 `updateAudio(dreamId, ms, "PENDING")` 会把 writer 已写的 FAILED 覆盖回 PENDING；
  该文件不在 Task 7 写权限内。06 因此用「FAILED 写回后 force-stop、不让追问收尾」的路径采集，
  这是真实数据库状态，不是伪造。
- 33+ 文件转写/管道/分段路径在本机无 on-device 语言包，标未验证。
