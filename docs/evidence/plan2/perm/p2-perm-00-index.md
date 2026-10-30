apk_sha256=3979f0f61160cdc4
device_date=Fri Oct 30 19:41:22 CST 2026（A 路共用时间戳；三路共用同一次 `adb shell date` 的输出见各日志首段）
rerun=bash docs/evidence/plan2/e2e/run-perm-a.sh；run-perm-b.sh；run-perm-c.sh；run-perm-c2.sh；run-perm-recover.sh

# Task 9 §9.2 三路权限证据（A / B / C 分开跑，不混写）

为什么必须拆开：API 11+ 上 `pm revoke` 会**直接杀进程**（本轮实测原文
`ActivityManager: Killing 24436:com.dreamkeeper/u0a237 (adj 0): permissions revoked`），
所以「没崩溃」在 A 路永远为真、不构成判据；B 路才是「先没权限、再点说梦」的可观测面。

每路 before/after 四件套：`pidof` / `dumpsys package | RECORD_AUDIO` /
`files/audio/` 文件数 / `logcat -c` 后片段。截图均 1080×2400。

## A. 已授权 + **正在录音**时 `pm revoke`

日志 `p2-perm-01-A.log`，画面 `p2-perm-01-A-recording.png`（录音中）、`p2-perm-02-A-after-revoke.png`（桌面）。

| 时刻 | pidof | RECORD_AUDIO | files/audio | 备注 |
|---|---|---|---|---|
| before（idle） | 24436 | `granted=true` | 3 | |
| 录音中（revoke 前 1 s） | 24436 | `granted=true` | 3 | `isForeground=true foregroundId=42 types=0x00000080` |
| revoke 后（按压窗口内） | **空** | `granted=false` | **4** | 被杀进程留下半截 WAV |
| 按压窗口结束后 | 空 | `granted=false` | 4 | top activity 变成 launcher |

logcat 原文（`-c` 之后）：
```
19:41:27.227 I ActivityManager: Killing 24436:com.dreamkeeper/u0a237 (adj 0): permissions revoked
19:41:27.234 W AppOps  : Operation not started: uid=10237 pkg=com.dreamkeeper(null) op=RECORD_AUDIO
FATAL EXCEPTION count=0
```
结论：**进程被杀是系统行为（revoke），不是崩溃**；`FATAL EXCEPTION` 0 条不能当作「App 稳」的证据，
这一路只证明「录音中被撤权时不会给出 App 层异常」。孤儿文件 `dfdca4e7-…wav` 由重启扫描回填，见 §9.5 审计。

## B. 先 revoke（无权限）→ 冷启动 → 点「说梦」

日志 `p2-perm-03-B.log`；画面 04/05/06/07/08/09。

| 时刻 | pidof | RECORD_AUDIO | files/audio | 画面 |
|---|---|---|---|---|
| before（日历） | 24582 | `granted=false`（`USER_SENSITIVE_WHEN_GRANTED`） | 4 | `p2-perm-04-B-calendar.png` |
| 点「说梦」后 | 24582（不变） | `granted=false` | 4（不增） | `p2-perm-05-B-denied.png`：录音页 `0:00` + 灰字「需要麦克风权限才能录梦」 |
| 再点一次「说梦」 | 24582 | 同上 | 4 | `p2-perm-06-B-denied-twice.png`（与 05 的 sha256 完全相同 ⇒ 不死循环弹框） |

- 按一下大圆才浮出卡片：`p2-perm-07-B-press-askable.png` —— 标题「需要麦克风权限才能录梦」+ 按钮「允许麦克风」（ASKABLE 态）
- 点「允许麦克风」→ 系统 `GrantPermissionsActivity`（`topResumedActivity=…permission.ui.GrantPermissionsActivity`），
  三选一弹窗 `p2-perm-08-B-system-dialog.png`（"Allow 梦匣 to record audio?" / While using the app / Only this time / Don't allow）
- 选 **Don't allow** → `granted=false flags=[USER_SET|…]`，卡片按钮变「去设置」：`p2-perm-09-B-blocked.png`（BLOCKED 态）
- 全程 `FATAL EXCEPTION count=0`，`topResumedActivity` 始终是本 App（除系统弹窗那一刻）

结论：无权限时不崩、不空转、不死循环弹框；ASKABLE→系统框→BLOCKED 三态齐（D132 设计口径）。

## C. `appops set RECORD_AUDIO ignore`（永久拒绝）

**第一趟 `p2-perm-10-C.log`：命令不生效。** `adb shell appops set com.dreamkeeper RECORD_AUDIO ignore` 返回 0，
但 `cmd appops get` 仍是 `Uid mode: RECORD_AUDIO: foreground`，录音照常（新文件 `1dbe3423-…wav` 80,332 B = 2,509 ms）。
→ 偏差 T9-2（D211）：API 37 上必须带 `--uid` 才改得动这个 op。

**第二趟 `p2-perm-12-C2.log`：`cmd appops set --uid com.dreamkeeper RECORD_AUDIO ignore` 生效**
（`Uid mode: RECORD_AUDIO: ignore`，`granted=true` 不变）：

| 观测 | 实测值 |
|---|---|
| app 侧异常类名 | **无**（无 `SecurityException` / `UnsupportedOperationException` / `IllegalStateException`） |
| 系统侧原文 | `audioserver: App op 27 missing, silencing record AttributionSourceState{pid: 24943, uid: 10237, packageName: com.dreamkeeper…}` |
| 产物 | `3fc451d7-…wav` 80,204 B、40,080 帧、`max_abs=0`、`nonzero=0`、**RMS=-inf**、ffprobe `duration=2.505000` |
| 业务流 | `RecordingService: 转写入队 dreamId=3fc451d7… elapsedMs=2505`（正常落库，成了一条「全零梦」） |
| 进程 | pid 24943 前后不变，`FATAL EXCEPTION count=0` |

证据：`p2-perm-13-C2-appops-uid-ignore.png`、`p2-perm-13-C2-silenced.wav`。
结论（按简报 0.8）：**待实测，不写判据**——三选一命中「仍录到全零」，App 不会报错也不会提示用户。

## D. 恢复（必须落盘 `granted=true`）

`p2-perm-14-recovery.log`：`appops set --uid … allow` + `pm grant` 后
```
android.permission.RECORD_AUDIO: granted=true, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
Uid mode: RECORD_AUDIO: foreground
```
再走一遍「说梦 → 按住 2 s → 松手 → 追问页（`p2-perm-15-recovery-followup.png`）→ 就这样（`p2-perm-16-recovery-done.png`）」，
新文件 `0b0b270e-…wav` 65,228 B = 2,037 ms，`FATAL EXCEPTION count=0`。

## 截图 sha256（前 16 位）

| 文件 | sha256 |
|---|---|
| `p2-perm-01-A-recording.png` | `a4a724a1ac783ff8` |
| `p2-perm-02-A-after-revoke.png` | `4801af16e23a54ae` |
| `p2-perm-04-B-calendar.png` | `3b03ed723a7ff525` |
| `p2-perm-05-B-denied.png` | `0a926628cb39ca9b` |
| `p2-perm-06-B-denied-twice.png` | `0a926628cb39ca9b`（与 05 相同） |
| `p2-perm-07-B-press-askable.png` | `7f6dbdbc999ba87b` |
| `p2-perm-08-B-system-dialog.png` | `1416462664cbd0b8` |
| `p2-perm-09-B-blocked.png` | `eefacef29570a5cc` |
| `p2-perm-11-C-appops-ignore.png` | `8c2002f71c5394e7` |
| `p2-perm-13-C2-appops-uid-ignore.png` | `25fe7093df270368` |
| `p2-perm-15-recovery-followup.png` | `293b64a1c6b56478` |
| `p2-perm-16-recovery-done.png` | `4d30455c9c8aac9e` |
