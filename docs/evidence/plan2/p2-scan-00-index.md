# Plan 2 Task 6 证据索引：启动扫描 WAV 头修复 + 孤儿恢复 + 反向孤儿

- 任务书：`.superpowers/sdd/2026-10-30-audio-and-transcription/task-6-brief.md`
- 计划：`docs/superpowers/plans/2026-10-30-audio-and-transcription.md` 的 `## Task 6`（§0 十六条优先）
- 报告：`.superpowers/sdd/2026-10-30-audio-and-transcription/task-6-report.md`
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`
  - sha256 `2cedc18cbf379d490b157ad75b024bee2f4a0f8f68954c35da85f105a1e45c63`（12958451 字节）
- 设备：`emulator-5554`，Android（Robolectric sdk=34 单测；真机 API level 见 `adb shell getprop ro.build.version.sdk`），时区 `Asia/Shanghai`
- 结论：**四态全部在生产路径实测通过**，校验脚本先红后绿，全套 `219 tests / 0 failures`，schema 一字节未动。

## 判据（三层，缺一不可）

1. **文件层**：`p2-scan-validate.py` 读 offset 4..8 / 40..44 与 `len(file)` 比对；要求 `riff == len-8`、`data == len-44`、两字段都不是 `0xFFFFFFFF`；再跑
   `ffmpeg -v warning -i x.wav -f null -`（stderr 只放行 ffmpeg 对合法 mono WAV 也会打的
   `Guessed Channel Layout: mono`，其余任何一行都算红）与 `ffprobe` 时长（与 `(len-44)//32` 差 < 100ms）。脚本 exit code 即结论，先在 before 上红一次、再在 after 上绿一次。
2. **库层**：`p2-scan-db.py` 用 `file:...?mode=ro` 只读打开 force-stop 后导出的 `dreamkeeper.db`（连同 `-wal`），输出
   `(bytes-44)//32 | rawAudioMs | diff` 三列，`diff != 0` 即红；同时打印 db sha256。
3. **设备/宿主一致性**：WAV 用 `cmd /c "<adb> exec-out run-as com.dreamkeeper cat … > …"` 拉取，设备 `sha256sum` 与宿主 `sha256sum` 逐字符比对（每个 txt 里的 `sha256_match: yes`）。

## 文件清单

| 文件 | 内容 | 结果 |
| --- | --- | --- |
| `p2-scan-capture.sh` | 一键复现四态（pm clear → grant → 录制/force-stop/删文件/造 0 字节 → 重启 → 取证） | 本目录全部 txt 均由它生成 |
| `p2-scan-validate.py` | §0-12 WAV 头 + ffmpeg + ffprobe 校验 | before exit 1 / after exit 0 |
| `p2-scan-db.py` | 只读 Room 导出 + 交叉表 | 四态 `cross_red_rows: 0`（除刻意的 before） |
| `p2-scan-01-before.txt` | 状态① before：占位头 + 库 ms 落后 | rawAudioMs 2900 vs 文件 4300，`diff 1400`（红） |
| `p2-scan-01-before.wav` | 状态① before 原始文件（137644 B，头 `…ffffffff…`） | sha256 `ef25b9b0…`，设备=宿主 |
| `p2-scan-01-validate.txt` | 对 before 跑 validator | `RESULT[before]: RED (exit 1)`，列出 3 条红因 |
| `p2-scan-02-after.txt` | 状态① after：重启扫描后同一文件 + 库 | rawAudioMs 4300，`diff 0`，updatedAt 未变 |
| `p2-scan-02-repaired.wav` | 状态① after 修复文件（同 137644 B，riff=137636/data=137600） | sha256 `f3cd77e7…`，设备=宿主 |
| `p2-scan-02-validate.txt` | 对 after 跑 validator | `RESULT[after]: GREEN (exit 0)` |
| `p2-scan-03-db.txt` | 状态①之后单独导出的库快照 | 与 02-after 一致 |
| `p2-scan-04-clean.txt` | 状态④：干净文件重启前后 | sha256 / mtime / updatedAt 三项 `identical: yes` |
| `p2-scan-06-orphan-before.txt` | 状态② before：有文件无行（48044 B，1500ms） | `db_has_row_for_orphan: no`，头仍占位 |
| `p2-scan-06-orphan-before.wav` | 状态② before 原始文件 | sha256 `829745c9…`（本次运行），设备=宿主 |
| `p2-scan-06-orphan-after.txt` | 状态② after：重启生成草稿行 | isDraft=1 / PENDING / rawAudioMs=1500 / dreamDate=设备今天 / createdAt=mtime |
| `p2-scan-04-file-missing.txt` | 状态③：`run-as rm` 后重启 | 行保留、rawAudioPath=NULL、rawAudioMs=NULL、transcriptStatus=FAILED |
| `p2-scan-05-zero-byte.txt` | 状态⑤：0 字节 UUID wav 无行 → 重启 | `file_exists_after_restart: no`、`row_exists_after_restart: no` |
| `p2-scan-07-ui.txt` + `.png` | 首页/30 号页截图 + 设备 date | 首页 `共 5 天有梦`（含恢复草稿），30 号页两张空草稿卡 |

## 四态实测数字

| 状态 | 造法（pre_state_by） | before | after |
| --- | --- | --- | --- |
| ① 有行 + 占位头 | 3s 录音 → 追问页补录 2s 中 force-stop | 137644 B，riff/data=FFFFFFFF，库 2900ms（diff 1400），validator exit 1 | 同 137644 B，riff=137636，data=137600，库 4300ms（diff 0），validator exit 0，updatedAt `1793355312106` 未变 |
| ② 有文件无行 | 长按 3000ms，录音约 1.5s 时 force-stop | 48044 B，头占位，库无该行 | 重启后草稿行 id=`2a27843f…`，ms=1500，dreamDate=2026-10-30，createdAt=mtime=1793355356412 |
| ③ 有行无文件 | `run-as rm` 状态② 的 wav 后重启 | 删前行来自状态②：path 有值、rawAudioMs=1500 | path=NULL、ms=NULL、status=FAILED，文本字段保留，updatedAt 未变 |
| ④ 干净 | 状态① 修复后的文件再重启 | sha `f3cd77e7…` / mtime `1793355312` / updatedAt `1793355312106` | 三项完全相同（扫描 Noop） |
| ⑤ 0 字节（补充） | `run-as touch` 0 字节 UUID 名 wav，库无行 | 0 B 文件存在 | 文件被删，未建行 |

复现：`bash docs/evidence/plan2/p2-scan-capture.sh`（自动 `pm clear`、申请权限、跑完四态并覆盖本目录 txt/wav）。

## 代码层证据（单测）

```bash
cd android
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --rerun-tasks
# 19 suites / 219 tests / 0 failures / 0 errors / 0 skipped
git -C .. diff --exit-code -- android/app/schemas   # exit 0
```

变异验证（写完已还原）：

- M1 去掉「跳过正在打开文件」（`if (store.isOpen(file))` → `if (false)`）：`AudioScanTest` 2 红
  （`skips a file that is being recorded right now`、`skips repairing a row whose file is being recorded`）。
- M2 `clearAudio` 只清 path 不清 status（`SET rawAudioPath = NULL`）：3 红
  （短文件有行、正好 44 字节有行、有行无文件）。
