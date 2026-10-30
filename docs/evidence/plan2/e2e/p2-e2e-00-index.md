apk_sha256=3979f0f61160cdc4
device_date=Fri Oct 30 19:34:40 CST 2026（首张截图时刻；末张 19:49:41）
rerun=bash docs/evidence/plan2/e2e/run-crash-recovery.sh && bash docs/evidence/plan2/e2e/run-live-take.sh（9.1 为手工序列，逐条命令见下表「命令」列）

# Task 9 §9.1 / §9.3 / §9.4（活录采样）证据索引

APK：`android/app/build/outputs/apk/debug/app-debug.apk`，12,958,510 B，
sha256=`3979f0f61160cdc432e8228756cdf6c6e401857a45bc0fa0ffd39401aa161c85`
（`:app:assembleDebug` 复跑 39 tasks 全 UP-TO-DATE ⇒ 与 HEAD `caa9e01` 代码一致）
设备：`emulator-5554`，API 37，1080×2400，Asia/Shanghai。

## 0. 前置（干净安装，命令原文）

```
adb install -r android/app/build/outputs/apk/debug/app-debug.apk   → Success
adb shell pm clear com.dreamkeeper                                  → Success
adb shell pm grant com.dreamkeeper android.permission.RECORD_AUDIO  → (无输出)
adb shell dumpsys package com.dreamkeeper | grep RECORD_AUDIO
  → android.permission.RECORD_AUDIO: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
```
`pm clear` 后 DB 为空，但 App 自带 seed（seed-dream-1..4 / seed-tag-*，identity_hash 3150a0bc…），
所以日历首屏是「共 4 天有梦」——下表读数把 seed 与新建分开算。

## 1. §9.1 端到端（录 → 追问 → 补录 → 收起 → 详情 → 播放）

按压用 `adb shell input swipe 539 Y 539 Y <ms>`（同点 down/up，等价长按）。

| # | 时刻(CST) | 画面 / 动作 | 证据文件 | sha256(前16) | 证明什么 |
|---|---|---|---|---|---|
| 1 | 19:34:40 | 日历首屏「共 4 天有梦」 | `p2-e2e-01-calendar.png` | `533f9980d8b05bc3` | 干净安装后 UI 起得来 |
| 2 | 19:35:56 | 点「说梦」→ 录音页 `0:00` | `p2-e2e-02-record-idle.png` | `6ee88225854d99b7` | 入口、空闲态文案 |
| 3 | 19:36:10 | 按住 4 s 中（计时 `0:04`） | `p2-e2e-03-recording.png` | `1edaa454c92193ec` | 按压→录音态 |
| 4 | 19:36:16 | 松手 → 追问页「24 秒」 | `p2-e2e-04-followup.png` | `d0a13f42b8ac8782` | 松手落库、追问窗口 |
| 5 | 19:36:28 | 补录 2 s（**该帧是松手后**，非录音中，见 §5 缺口） | `p2-e2e-05-append.png` | `9ee33e0e5da2b333` | 补录入口在（时序缺陷，另见 `p2-e2e-11`） |
| 6 | 19:36:34 | 补录后仍在追问页，剩 15 s | `p2-e2e-06-followup-after-append.png` | `75778ebb3c552925` | 追加未打断窗口 |
| 7 | 19:36:40 | 「就这样」→ 回日历「共 5 天有梦」，30 日出现圆点 | `p2-e2e-07-after-done.png` | `dc409f1b204ea7a8` | 收起、日历刷新 |
| 8 | 19:37:00 | 点 30 日 → 当日列表（1 张卡） | `p2-e2e-08-day.png` | `61b0d530cc4871ed` | 当日层 |
| 9 | 19:37:12 | 详情页「没有转写成功 / 手动补字」+ 播放条 `0:00.0 / 0:05.8` | `p2-e2e-09-detail.png` | `496f316f0f14d162` | 时长与文件一致 |
| 10 | 19:37:22 | 点播放 → `0:02.1 / 0:05.8`，图标变暂停 | `p2-e2e-10-playing.png` | `084f0f143d5541c1` | **能播**（进度在走） |

文件：`files/audio/b5249a66-76b0-4a15-8c0b-0535edc57f45.wav`
- 第一段 124,844 B（3,900 ms）；补录后 185,644 B（5,800 ms）
- 头字节 `52 49 46 46 24 d5 02 00 … 64 61 74 61 00 d5 02 00` → riffSize=185,636=bytes-8，dataSize=185,600=bytes-44，rem=0
- 落盘副本 `p2-e2e-09-raw.wav` sha256=`17d6dc6104f13f92…`
- DB 行（`p2-e2e-09-db.db` 只读查）：`rawAudioMs=5800`、`transcriptStatus=FAILED`、`isDraft=1`、`createdAt=1793360169486`
- ffprobe：`pcm_s16le/16000/1ch/duration=5.800000/size=185644`；`ffmpeg -v warning` stderr 仅 `Guessed Channel Layout: mono`（D192）

## 2. §9.3 崩溃恢复（D185 的正确构造）

`pre_state_by:` 先录 3 s **松手**（DONE 回填头 + 写库）→ 追问页补录 2 s（markOpen 把两个长度字段写回 `FF FF FF FF`）→ **补录进行中** `am force-stop` → 重启扫描。全程见 `p2-e2e-run-crash-recovery.log`。

| 阶段 | 时刻 | 字节 | 头（offset 4..8 / 40..44） | 证据 |
|---|---|---|---|---|
| take1 松手后 | 19:39:35 | 96,396 | `84 78 01 00` / `60 78 01 00`（已回填，3,011 ms） | 日志 |
| 补录中 | 19:39:39 | 150,796 | `ff ff ff ff` / `ff ff ff ff` | `p2-e2e-14-crash-mid-append.png` |
| force-stop 后 | 19:39:41 | 192,396 | 仍 `ff ff ff ff`；`pidof` 退出码 1 | 日志 |
| 重启 | 19:39:45 | 192,396 | `84 ef 02 00` / `60 ef 02 00` = 192,388 / 192,352 ⇒ **6,011 ms**、rem=0 | `p2-e2e-15-crash-after-restart.png` |

- `am start -W`：`LaunchState: COLD`、`TotalTime: 1945`
- DB `2fe64b54-dab2-4716-9138-443438ad0c8c`：`rawAudioMs=6011` = `(192396-44)/32` 容差 0；
  `createdAt=1793360375469`（19:39:35）**早于** 19:39:45 的重启 ⇒ 行在崩溃前已存在，正是「有行 + 占位头」态
- 详情页 `p2-e2e-20-crash-detail-6s.png`：`0:00.0 / 0:06.0`；播放 `p2-e2e-21-crash-detail-playing-6s.png`：`0:01.9 / 0:06.0`
- 观测：repair 只走 `DreamDao.setAudioMs`（`UPDATE dreams SET rawAudioMs = :rawAudioMs WHERE id = :id`，`DreamDao.kt:124-125`），
  **不动 `updatedAt`**——注释写明「只改时长」，属设计，不计偏差

## 3. §9.4 活录两次采样（D166 口径）

`run-live-take.sh` 在一个 shell 内「后台按住 + 前台采样」，避开工具调用延迟；见 `p2-e2e-launch-live-take.log`。

- 按下后 1.0 s 文件出现 `5a182061-fd4a-49fc-9054-8a6f8d9f8e8d.wav`
- **t1 之前**已有 `isForeground=true foregroundId=42 types=0x00000080 startRequested=true`（D166：起录晚约 200 ms）
- t1 19:38:59 `bytes=58,348` `uptime=14305.54`；t2 19:39:02 `bytes=128,748` `uptime=14307.75`
  → Δbytes=70,400 / Δt=2.21 s = **31,855 B/s**（区间 31,360..32,640，+0.4%）
- 松手后 160,748 B；头 `riff=0x273E4=160,740`、`data=0x273C0=160,736`、rem=0、formula=5,022 ms
- 真·录音中帧：`p2-e2e-11-live-take-mid.png`（`0:04` + 振幅环 + 取消录音）；松手 → `p2-e2e-12-live-take-followup.png`
- 落盘 `audio/p2-e2e-live-take.wav` sha256=`217590751c50c2b8…`

## 4. 当日列表顺序（复算用）

`DreamDao.observeDay` 是 `ORDER BY createdAt DESC`（`DreamDao.kt:36-41`），本轮 7 条（全部 `transcriptStatus=FAILED`、`isDraft=1`，卡片因无文本渲染为空白条）：

| 卡片序 | id | createdAt | rawAudioMs |
|---|---|---|---|
| 1 | 0b0b270e | 19:43:59 | 2037 |
| 2 | 3fc451d7 | 19:43:31 | 2505 |
| 3 | 1dbe3423 | 19:42:58 | 2509 |
| 4 | dfdca4e7 | 19:41:27 | 2916 |
| 5 | 2fe64b54 | 19:39:35 | 6011 |
| 6 | 5a182061 | 19:39:03 | 5022 |
| 7 | b5249a66 | 19:36:09 | 5800 |

（`p2-e2e-22-day4.png` 是 7 卡的当日层截图；`p2-e2e-18/19-*` 是第 6 张卡 5,022 ms 的详情/播放，留作旁证。）

## 5. 缺口与观察（不粉饰）

1. `p2-e2e-05-append.png` 拍在松手之后（工具调用延迟），**不是**补录录音中的帧；
   真·录音中帧用 §3 的 `p2-e2e-11` 补齐。→ 偏差 T9-6（D215）。
2. 中文转写在本模拟器不可用（详情页「没有转写成功」），与 Task 7 结论一致，**不是本轮回归**。
3. 无转写文本时当日卡片渲染为空白圆角条（7 张全空白），可点但无法辨认是哪条 → 偏差 T9-5（D214）。
4. 采样输入是模拟器 seam（无 `-allow-host-audio`）：RMS 实测 −76.7…−76.9 dBFS（约 8 LSB 底噪，非真实声源），
   `p2-wav-01` fixture 与 appops 静音那条才是 −inf。→ 3b 表内一律 `PASS-ENV`。
