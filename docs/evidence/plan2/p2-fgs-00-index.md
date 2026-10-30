# Task 3 证据索引：RecordingService 前台服务 + 通知 + 权限

- 代码提交：`b083021 feat(record): 前台录音服务（microphone 类型、通知、START/STOP/CANCEL）`（10 文件 +841）
- Lead 修复提交：`fix(record): 失败路径不留占位头孤儿文件并关闭写句柄（D127）`
- 分支：`plan2-audio`；设备：`emulator-5554`（Android 17 / API 37），时区 Asia/Shanghai
- 证据 APK：sha256 `39dab6ec3fc469c76f7299455c0ee6a8c6d6821ad2b831f153b08ed2e860f090`（**带 D118 的 DEBUG 钩子**；钩子 diff 见 `p2-fgs-01-harness.patch`，源码已还原）
- 无钩子构建（提交后）APK：12,958,451 B

## 1. 四连判据（D105）

| 判据 | 期望 | 实测 | 文件 |
|---|---|---|---|
| ① 前台服务 | 同一 ServiceRecord 内 `startRequested=true` + `isForeground=true` + `foregroundId=42` + `types=0x00000080` | 全部命中（`:43` `:46`；API 37 字段名见 D120） | `p2-fgs-01-services.txt` |
| ② 通知 | id=42，flags 含 `FOREGROUND_SERVICE`/`ONGOING_EVENT`/`NO_CLEAR` | 命中；另有通知栏截图 | `p2-fgs-01-notify-dump.txt`、`p2-fgs-01-notify.png` |
| ③ 写入速率 | 32000 B/s ±2% | 4 次采样（文件字节 + `/proc/uptime` 单调钟）在区间内 | `p2-fgs-01-bytes.txt` |
| ④ 崩溃日志 | `FATAL` / `ForegroundServiceStartNotAllowed` / `DidNotStartInTime` 全空 | `FATAL=0`、其余 0 | `p2-fgs-01-logcat.txt` |

## 2. 松手后收尾

- 服务记录为空、通知记录为空（`ACTIVITY MANAGER SERVICES … (nothing)`）。
- WAV 头回填：`riffSize = fileBytes-8 = 473700`、`dataSize = fileBytes-44 = 473664`，前 44 字节逐字段核对过（16k/mono/16bit、byteRate 32000、`fmt ` 带尾空格）。
- 文件见 `p2-fgs-01-header.txt`。

## 3. Lead 独立复核（不只是读报告）

- 拉回真机文件 `files/audio/p2fgs01.wav`（473,708 B）：
  - `ffprobe` → `pcm_s16le / 16000 / 1ch / duration=14.802`，与 `(473664)/32 = 14802 ms` 一致；
  - 波形统计：`rms=4.83`、`peak=8`、**RMS −76.6 dBFS** → 信号接近静音。
- 结论：**能证明「AudioRecord 在供数、链路通、字节速率对」；不能证明「录到了声音」**——模拟器启动没加 `-allow-host-audio`，输入被灌成静音级信号（W3 只能写 `信号来源=seam`，见 `docs/evidence/plan2/README.md`）。
- 测试：`:app:testDebugUnitTest` → **161 用例 / 15 类 / 0 失败**（基线 152/14，+8 `RecordingCommandTest`、+1 `WavStoreTest`）。

## 4. 偏差

D118（证据钩子）、D119（`markFinishing`）、D120（API 37 `startRequested` 与 `types` 十六进制）、D121（非法命令不误杀）、D122（适配层 + `syncToDisk`）、D123（连续 50 次读到 0 转 DEAD）、D124（结束动作用 `getService`）、D125（证据由 Lead 提交）、D126（计数基线）、D127（失败路径不留孤儿文件）。详见计划偏差表。
