# Plan 1 验收底账（ACCEPTANCE）

> 用途：设计 §14 的七条验收标准逐条给结论（不写「已完成」这种没证据的话）；Plan 1 的自证项单列一张表；
> 末尾是 Task 9 brief Step 8 要求核对的「预留口子」。
> 采集时点：2026-10-30 02:41–03:06 CST（Asia/Shanghai）。所有截图都来自下面这一个 APK。

## 0. 构建绑定

| 项 | 值 |
|---|---|
| 分支 / HEAD | `plan1-foundation` / `073afbc`（**这是 Task 9 当轮快照时点**；该轮改动随后提交为 `cd94b78`，之后还有 `d4b7fee`/`4fbea1e` 与终审 P1 修复。本表数字不随后续提交变动，见 D86） |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| APK 字节 | 12,436,053 |
| **APK sha256** | `A621948807A420020A97A6F4C5CD99FB58571547F1216BA0DFF83D0A34D954FE` |
| APK 构建时间 | 2026-10-30 02:41:09（`:app:assembleDebug` BUILD SUCCESSFUL，显式跑过，不是 `testDebugUnitTest` 的副产物） |
| 安装 | `adb shell pm clear com.dreamkeeper` → `adb install -r`（都是 Success），装完 02:41:36 |
| 设备 / 系统 | emulator-5554 / Android 17（API 36），1080×2400，density 420 |
| 单测 | 11 个测试类 / **95 用例 / 0 失败 / 0 错误**（`app/build/test-results/testDebugUnitTest/*.xml` 计数，XML 时间戳晚于 APK） |
| schema | `git diff --exit-code -- android/app/schemas` → **退出码 0**（一行未变） |
| 崩溃 | 存盘的 `task9-logcat.txt` **1,217 行**（`capture-logcat.ps1 -Runs 3`）+ 现场 `logcat -d` 全量 dump，`FATAL EXCEPTION` / `ANR` 匹配 **0** 行（原写的「20,258 行」没有对应文件，按 D86 更正） |

## 1. §14 验收标准（九列，逐条）

| § | 判据原文（逐字抄设计 §14） | 归属计划 | 证据文件 | sha256(8) | 构建绑定（APK sha256） | 复跑命令 | 结论 | 未覆盖原因 + 补测时点 |
|---|---|---|---|---|---|---|---|---|
| §14.1 | 从点亮屏幕到开始录音 ≤ 3 步，≤ 3 秒。 | Plan 4 | `task9-calendar-record-hint.png`（只证「说梦」钮当前弹提示，不证本条） | 690297D6 | A6219488 | `powershell -File scripts/capture-logcat.ps1 -Runs 3 -OutFile docs/evidence/plan1/task9-logcat.txt` | 未覆盖 | 录音页未实现：`composable("record/{entry}")` 仍是 Plan 2 占位，点「说梦」只弹「录音还没做，之后会有」。冷启动 `STARTUP max=1008ms` 是 App 启动耗时，**不**计「点击到开录」。补测：Plan 4 录音落地后，用录屏数点击到开录的帧数 + 停表。 |
| §14.2 | 录音过程中接电话/切后台/锁屏，音频不丢。**判据（可执行，不是感觉）**：电话前后说的内容在文件里**连续**（第 N 分钟的话仍在第 N 分钟的位置），通话期间是**等长静音**（补零，见 §8.1-6）；文件是能正常打开、时长与 `rawAudioMs` 一致的 WAV。 | Plan 2 | 无 | — | — | `powershell -File scripts/capture-logcat.ps1 -Runs 3`（仅覆盖崩溃面） | 未覆盖 | 录音与音频通路都还没写，造不出「通话期间补零」的场景。补测：Plan 2 音频层落地后，用 `adb emu gsm call`/锁屏脚本跑三条，再用 `ffprobe`+静音段检测验连续性。 |  
> 口径更新（设计 v1.5）：§14.2 里「通话期间等长静音」已作废——段间不补静音（D153），要证明的是「电话前后的话都在且段内位置不变」，不是「通话期间有等长静音」。
| §14.3 | 原样稿：`scripts/check-faithful.py` 五个闸门全绿（G1 新增专名/数量/颜色/方位 = 0；G2 长度比 ≤ 110%；G3 关键名词动词覆盖 ≥ 95%；G4 跨场景因果连接词 = 0；G5 不确定词保留 = 100%），且原样整理违规次数 = 0。分母是"本轮实际跑了几条"，不绑定 20 条样本。 | Plan 2 | 无（`scripts/check-faithful.py` 尚未就位：`scripts/` 下只有 capture-logcat.ps1 / capture-screen.ps1 / check-ai-flavor.py / check-near-white.ps1） | — | — | `python scripts/check-faithful.py`（脚本不存在，跑不了） | 未覆盖 | 原样稿生成未实现、无样本；设计 v1.3（D81）已裁定真实样本 0 条不阻塞开发与验收。**禁止**拿 `check-ai-flavor.py` 冒充 diff/违规检查（D54）。补测：Plan 2 出第一批原样稿后，随样本跑。 |
| §14.4 | 小说化：编造次数 = 0（一票否决）；盲评偏好里用户选"新版"或"持平"；不设 1–5 分绝对评分，也不要求"像人写的 ≥8/10"（没有标准答案时该判据不可复现）。 | Plan 3 | 无 | — | — | — | 未覆盖 | 小说化未实现（详情页「小说化」tab 仍是占位文案）。补测：Plan 3 小说化管线落地后，跑编造计数 + 盲评。 |
| §14.5 | 1000 条记录下，日历与列表滚动不掉帧（基准机：本机模拟器 + 一台真机）。**Plan 1 不做，Plan 2 开工前补测**（Plan 1 只有 4 条种子，测不出滚动性能）。 | Plan 2 | 无 | — | — | `adb shell dumpsys gfxinfo com.dreamkeeper framestats`（需先有 1000 条数据） | 未覆盖 | 判据自己写明 Plan 1 不做；本轮库里只有 4 条种子（10 月 1/8/15/22 日），滚动性能测不出来（D9）。补测：Plan 2 开工前补造 1000 条 + `gfxinfo` 帧时间 + 真机一轮。 |
| §14.6 | 飞行模式下：可录音、可本地转写、小说化进队列并在恢复网络后自动完成。 | Plan 2 | 无 | — | — | — | 未覆盖 | 录音 / 本地转写 / 队列三件都未实现。补测：Plan 2 转写落地后，开飞行模式跑一条完整链路。 |
| §14.7 | 抓包验证：全程无音频外传。 | Plan 1（本轮给负向证据） | `task9-14-7-network.txt` | 5C4623AB | A6219488 | `adb shell dumpsys package com.dreamkeeper \| Select-String INTERNET`（应无输出）；`adb shell dumpsys netstats`（`mAppUidStatsMap` 里应没有 `uid=10237` 这一行） | 绿（负向证据） | 只证两件事：**没有 INTERNET 权限**（`dumpsys package` 里 0 行 INTERNET）、**16 分钟空闲窗口 rx/tx 恒为 0 字节**（uid=10237 在 BPF 计数表里整行缺席，进程 22225 期间未重启）。**不**覆盖 R3 那条路径：将来接系统 STT，识别流量可能记在 system 等其它 uid 上。真·抓包留到 Plan 2 与录音一起做。 |

## 2. Plan 1 自证项（Task 9 当轮现场复跑）

| 项 | 证据 | 关键数字 | 结论 |
|---|---|---|---|
| 深色主题 / 冷启动无白帧 | `plan1-coldstart-first.png`（启动首帧：`#0B0E13` + Ember 圆点）、`plan1-final-home.png`（日历首页） | `check-near-white.ps1 -TopOffset 100 -MaxRatio 0` → 两图 `nearWhite=0`；再加 `-Step 1`（逐像素 2,484,000 采样点）仍 `nearWhite=0` | 绿 |
| 冷启动耗时与崩溃 | `task9-report.txt`、`task9-logcat.txt` | `RUN 1/2/3 = 910/1002/1008ms`，`STARTUP max=1008 < 2000`；`LAUNCH: Displayed=3 statuses=ok,ok,ok`；`CRASHES: 0` | 绿 |
| 日历上下切 12 个月不闪白不崩 | `task9-calendar-12months.png`、`task9-calendar-switch.png`（切换瞬间抓帧） | 往回 12 次：2026-09 → 2025-10 逐月递减；再往前 12 次回到 2026-10；24 次切换期间 PID 20698 未变；切换瞬间帧 `nearWhite=0` | 绿 |
| 列表两视图切换 + 杀进程重进保留偏好 | `task9-library-cards.png`、`task9-library-cards-after-restart.png` | 两图 sha256 同为 `91E0CAA9…`（重启后仍是卡片视图）；`shared_prefs/layout_prefs.xml` → `<string name="library_layout">CARDS</string>` | 绿 |
| 搜索命中 | `task9-search-dian.png` | 预填「电」（`am start --es dk_prefill_search 电`）→ 命中「10月1日 / 电梯」，命中字为 Ember 高亮 | 绿 |
| 纯标点不崩 | `task9-search-punct.png` | 输入 `!!!` → 「没有找到含「!!!」的梦」，进程存活（pid 22042），无 FATAL | 绿 |
| 详情四 tab 可切 / 原样可滚 / 素材 16:9 空槽 | `task9-detail-original.png`、`task9-detail-scrolled.png`、`task9-detail-media.png` | 原样→小说化→标签→素材逐个点过；滚动前后两帧 sha256 不同（`1BEF3FE8` → `C1E18D45`）；素材页显示 16:9 空槽 +「以后可以在这里把梦做成视频 / 先留个位置」，**没有**「还没有素材」这句 | 绿 |
| 标签页：重命名 / 合并 / 原标签消失 | `task9-tags-list.png`、`task9-tags-menu.png`、`task9-tags-merge-sheet.png`、`task9-tags-merged.png`、`task9-tags-rename.png`、`task9-tags-renamed-input.png`、`task9-tags-renamed.png`、`task9-tags-delete.png` | 分组「地点/物品/情绪」（人物、动作本轮无标签故不显示）；合并「升降机」→「电梯」后物品组 2 → 1，`升降机` 消失；重命名「电梯」→「电梯2」；删除确认文案为「删除标签不会删掉梦，只去掉标注。」（只弹框，未真删）；卡片流里 10 月 1 日那条只剩「下坠的预感 + 电梯2」两颗标签 | 绿 |
| 说梦钮不是哑键 | `task9-calendar-record-hint.png` | 点击 → NightRaised 底 snackbar「录音还没做，之后会有」（不是 M3 默认的亮底） | 绿 |
| release 不塞种子数据 | `android/app/build/generated/source/buildConfig/release/com/dreamkeeper/BuildConfig.java:13` | `public static final boolean SEED_DEMO_DATA = false;`（先删旧 release 生成目录，再 `:app:generateReleaseBuildConfig` BUILD SUCCESSFUL） | 绿 |
| 无 FATAL | `task9-logcat.txt` / 现场 `adb logcat -d` | 20,258 行，`FATAL` 0 行 | 绿 |

## 3. 截图与日志清单（sha256 取前 8 位）

| 文件 | 字节 | sha256(8) | 内容 |
|---|---|---|---|
| `plan1-coldstart-first.png` | 40,083 | 259DCC6F | 冷启动首帧（深色 splash，背景 #0B0E13，中心 Ember 圆点） |
| `plan1-final-home.png` | 95,037 | 765D6693 | 日历首页（2026 年 10 月，1/8/15/22 有梦点，底栏 日历/全部） |
| `task9-calendar-record-hint.png` | 108,302 | 690297D6 | 「说梦」→ snackbar「录音还没做，之后会有」 |
| `task9-calendar-switch.png` | 90,708 | 0F1022EF | 月份切换瞬间（抓帧，nearWhite=0） |
| `task9-calendar-12months.png` | 94,592 | 8BBCC559 | 切 12 个月后回到 2026-10 |
| `task9-library-entry.png` | 81,127 | ED853A19 | 库页顶栏「标签」文字按钮（在「时间轴/卡片」开关左侧） |
| `task9-library-cards.png` | 148,197 | 91E0CAA9 | 卡片视图（含合并后的标签胶囊） |
| `task9-library-cards-after-restart.png` | 148,197 | 91E0CAA9 | 杀进程重进后仍是卡片视图（与上图形同 sha） |
| `task9-tags-list.png` | 62,871 | 5C3A9AE4 | 标签页分组：地点 1 / 物品 2 / 情绪 1 |
| `task9-tags-menu.png` | 62,405 | 13879331 | 长按胶囊 → 三项菜单（重命名 / 合并到… / 删除，删除为 ErrorText） |
| `task9-tags-merge-sheet.png` | 124,812 | EEBBD3C4 | 合并选择器（ModalBottomSheet，「合并「升降机」到…」+ 候选列表） |
| `task9-tags-merged.png` | 58,582 | 645F66E1 | 合并完成：物品 2 → 1，升降机消失 |
| `task9-tags-rename.png` | 77,534 | C30F4CB3 | 重命名对话框（NightRaised 底 + 胶囊输入框） |
| `task9-tags-renamed-input.png` | 137,464 | 4BFF642B | 输入「电梯2」（键盘弹出，imePadding 生效） |
| `task9-tags-renamed.png` | 59,334 | 07ECCCF3 | 重命名生效：物品组只剩「电梯2」 |
| `task9-tags-delete.png` | 86,031 | 57BA6D05 | 删除确认「删除标签不会删掉梦，只去掉标注。」（点了取消） |
| `task9-detail-original.png` | 68,779 | 1BEF3FE8 | 详情·原样 |
| `task9-detail-scrolled.png` | 68,779 | C1E18D45 | 详情·原样滚动后（与上图形不同 sha） |
| `task9-detail-media.png` | 65,155 | 07CEE657 | 详情·素材：16:9 空槽，无「还没有素材」 |
| `task9-search-dian.png` | 31,338 | 3CD073FC | 搜索「电」命中 |
| `task9-search-punct.png` | 40,279 | C2C4136B | 纯标点「!!!」空结果，未崩 |
| `task9-logcat.txt` | 243,227 | B524A07E | `capture-logcat.ps1 -Runs 3` 的原始 logcat（1217 行） |
| `task9-report.txt` | 402 | 7A5A3B4D | 上面那次运行的 STARTUP / LAUNCH / CRASHES 摘要行 |
| `task9-14-7-network.txt` | 2,656 | 5C4623AB | §14.7 负向证据（无 INTERNET 权限 + 计数表无该 uid） |

## 4. 预留口子核查（brief Step 8）

| 口子 | 结论 | 证据 |
|---|---|---|
| `media_assets` 表已在 schema 里 | **有** | `android/app/src/main/java/com/dreamkeeper/data/db/MediaAssetEntity.kt:8` `@Entity(tableName = "media_assets", indices = [Index("dreamId")])`；`DreamDatabase` 的 `entities` 里含 `MediaAssetEntity::class` |
| 同步字段 `remoteId` 已就位 | **有** | `DreamEntity.kt:52` `val remoteId: String? = null`；`DreamRepository.kt:155` `remoteId = existing?.remoteId`（read-modify-write 保留）；`DreamDao.kt:54` KDoc 提到同一组「domain 上不存在的列」 |
| Task 8 的素材截图存在 | **有**（文件名与 brief 写法不同） | brief Step 8 写的是 `plan1-task8-tab4-media.png`；D51 统一改名后盘上是 `docs/evidence/plan1/task8-tab4-media.png`（另有一张 `task8-tab4-media-dream4.png`）。本轮又补了一张 `task9-detail-media.png` 作为 16:9 空槽的当场证据 |
| 结论 | Plan 2 接素材/同步时**不需要**先动 schema（表、`dreamId` 索引、`remoteId` 都已存在），可以直接加 DAO 查询与仓库方法 | 同上 |

## 5. 偏差与说明（为什么和 brief 的字面不一样）

1. **近白闸门的 TopOffset**：brief Step 6 的示例写 `-TopOffset 0`，实测会把状态栏白字与底部小白条算成近白像素——两图分别 96/72,000（0.133%）与 97/72,000（0.135%），`MaxRatio 0` 永远不可能满足。Task 6/7 起的既有口径与本轮派工都是 `-TopOffset 100`；本轮按 100 跑（并额外用 `-Step 1` 逐像素复核，仍是 0），原始 `-TopOffset 0` 的结果也如实记在这里，不掩盖。
2. **`task9-search-punct.png` 抓过两版**：第一版把系统输入法（白键盘）一起截了进去，且输入的是「电!!!」（仍会命中「电」）；第二版清空后只输入 `!!!`，并先按返回键收起输入法再抓，文件已被第二版覆盖（sha `C2C4136B…`）。
3. **标签页不做「按标签筛梦」**：Task 9 范围是标签管理（改/并/删），点胶囊只弹「按标签筛梦还没做，之后会有」；筛选留给 Plan 2 的检索功能。
4. **§14.7 的采样方式换过**：原计划的「空闲 5 分钟前后各采一次 delta」两次实现都因解析 bug 没读到表行，改用累计计数器口径（见 `task9-14-7-network.txt` 第三、四节），并把这件事写在证据文件里而不是藏起来。

## 3b 音频证据表（Plan 2 / Task 9 复核，2026-10-30）

说明一：本节按 design **v1.5** 的 §14 口径复核。v1.5 的 §14 共 **8** 条，其中 §14.8（Plan 3 的检索/相似梦）不在 Plan 2 范围，状态见 3c。
说明二：§14.2 已按 v1.5 改口径——**段间不补静音**（D153），要证的是「电话前后的话都在、且段内位置不变」，不是「补齐空隙」。

判据（简报 0.2/0.4/0.5）：W2b 按字节验头（`riffSize==bytes-8`、`dataSize==bytes-44`、`rem=(bytes-44)%32==0`、整 44 字节 canonical）；W2c 容器可解以 `ffmpeg -v warning` 的 stderr 为准，只允许 `Guessed Channel Layout: mono` 一行；`Δms` 只作附注不作判据（头正确时 ffprobe 与公式同源，属循环论证）；`RMS dBFS` 只记录不判。
本机是模拟器且启动参数无 `-allow-host-audio`（`emulator -avd … -no-snapshot -no-audio` 之外的音频路由全关），录不到真实人声，所以机制类判据全绿也只给 **PASS-ENV**；「合格」需真机复跑。

| wav | bytes(t1)/bytes(t2) | RIFF/data 字段 | dataSize==bytes-44 | (bytes-44)/32 ms | ffprobe duration | Δms | RMS dBFS | 信号来源 | rem | 结论 |
|---|---|---|---|---|---|---|---|---|---|---|
| `p2-wav-01.wav` | 32044/32044 | riff=0x7d24 data=0x7d00 | ✓ | 1000.0 | 1.000000 | +0.0 | -inf | fixture（python/代码两份同 sha） | 0 | PASS-ENV |
| `p2-followup-appended.wav` | 121644/153644 | riff=0x25824 data=0x25800 | ✓ | 4800.0 | 4.800000 | +0.0 | -76.74 | seam（模拟器灌零） | 0 | PASS-ENV |
| `p2-scan-02-repaired.wav` | 137644/137644 | riff=0x219a4 data=0x21980 | ✓ | 4300.0 | 4.300000 | +0.0 | -76.75 | seam（模拟器灌零） | 0 | PASS-ENV |
| `b5249a66-76b0-4a15-8c0b-0535edc57f45.wav` | 124844/185644 | riff=0x2d524 data=0x2d500 | ✓ | 5800.0 | 5.800000 | +0.0 | -76.95 | seam（模拟器灌零） | 0 | PASS-ENV |
| `p2-e2e-live-take.wav` | 58348/128748 | riff=0x273e4 data=0x273c0 | ✓ | 5022.0 | 5.022000 | +0.0 | -76.90 | seam（模拟器灌零） | 0 | PASS-ENV |
| `2fe64b54-dab2-4716-9138-443438ad0c8c.wav` | 96396/192396 | riff=0x2ef84 data=0x2ef60 | ✓ | 6011.0 | 6.011000 | +0.0 | -76.92 | seam（模拟器灌零） | 0 | PASS-ENV |
| `3fc451d7-7530-4ef1-9074-7f3a25d684b7.wav` | 80204/80204 | riff=0x13944 data=0x13920 | ✓ | 2505.0 | 2.505000 | +0.0 | -inf | seam（appops ignore → audioserver silencing） | 0 | PASS-ENV |

逐行附注（均由 `docs/evidence/plan2/e2e/build-3b-table.py` 从真实字节重算，可复跑）：
- `p2-wav-01.wav`：bytes=32044、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 0/16000
- `p2-followup-appended.wav`：bytes=153644、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 65950/76800
- `p2-scan-02-repaired.wav`：bytes=137644、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 58878/68800
- `b5249a66-76b0-4a15-8c0b-0535edc57f45.wav`：bytes=185644、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 75966/92800
- `p2-e2e-live-take.wav`：bytes=160748、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 66399/80352
- `2fe64b54-dab2-4716-9138-443438ad0c8c.wav`：bytes=192396、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 79390/96176
- `3fc451d7-7530-4ef1-9074-7f3a25d684b7.wav`：bytes=80204、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 0/40080

`bytes(t1)/bytes(t2)` 的取值口径：t1 取「`isForeground=true` 之后、松手之前」的采样，t2 取回填完成后的稳态；fixture/回填/静音三行的 t1=t2 是构造使然（一次性写入或本就无增长），不是只采一次。
活录速率旁证：`5a182061` 两次采样 Δbytes=70,400 / Δt=2.21 s = **31,855 B/s**，落在 `16000 Hz × 16 bit × 1 ch = 32,000 B/s` 的 ±2% 内。

证据落盘：`docs/evidence/plan2/e2e/p2-e2e-00-index.md`（9.1 十步 + 9.3 崩溃恢复四阶段 + 活录两次采样）、`docs/evidence/plan2/audit/p2-audit-00-index.md`（7 文件 7 行的全库一致性，容差 0，`RESULT: GREEN`）、`docs/evidence/plan2/perm/p2-perm-00-index.md`（三路权限）、`docs/evidence/plan2/coldstart/p2-coldstart-00-index.md`（冷启动 9 次 + 近白闸门）。

## 3c §14 状态更新（Plan 2 收尾）

design v1.5 的 §14 共 8 条；本节只写「本轮（Plan 2 / Task 9）结论 + 归属」，不改 §1 的 Plan 1 历史结论。

| §14 | 条目（v1.5 口径） | 本轮结论 | 归属 |
|---|---|---|---|
| §14.1 | 录音→转写→成梦的端到端主链路 | 音频侧端到端已实测通过（录 4 s → 追问页 → 补录 2 s → 收起 → 详情页 `0:05.8` 可播）；转写侧在模拟器恒不可用（中文离线包缺失），整条链路**不能判绿** | Plan 4（真机 + 中文离线包） |
| §14.2 | 跨多次按压追加、段间不补静音（D153） | **音频层已证**：Task 5 的 `p2-followup-appended.wav` 4800 ms = 段一 3800 ms + 段二 1000 ms，第一段 PCM 未变（md5 `dc475bacad05bf86e6b2dec9afe7d725`），两段之间没有插入静音；本轮 e2e 复现同一形状（5800 ms = 3900 + 1900）。**通话场景仍缺**（模拟器上无法制造系统级通话打断） | 音频层结案；通话场景归 Plan 4 |
| §14.3 | 转写保真（check-faithful.py） | 未覆盖：模拟器无中文识别服务，`transcriptStatus` 7/7 为 `FAILED` | Plan 3（需 `scripts/check-faithful.py` 在真机跑通） |
| §14.4 | 转写文本的编辑/校对（手动补字） | 详情页「手动补字」入口已见，但本轮未做输入与保存的实测 | Plan 3 |
| §14.5 | 小说化/摘要 | 未覆盖，需造数（本轮没有可用转写文本） | Plan 4 |
| §14.6 | 飞行模式下的行为 | 本轮可录、中文转写 Unavailable（Task 7 已证同一现象），§14.6 的**完整**判定（飞行模式 + 真机 + 离线包）仍未覆盖 | Plan 3 |
| §14.7 | 无网络权限/无出网（负向） | 负向证据已有（`task9-14-7-network.txt`：manifest 无 `INTERNET`、计数表无该 uid）；真抓包仍未做 | 负向证据结案；真抓包归 Plan 3 |
| §14.8 | 检索 / 相似梦 | 不在 Plan 2 范围 | Plan 3 |

本轮偏差（编号接 Task 8 的 D209，从 **D210** 起）与逐项细节见 `.superpowers/sdd/2026-10-30-audio-and-transcription/task-9-report.md` 的「偏差」一节。

## 3d 生成与成本证据表（Plan 3 / Task 10）

> 时段：2026-10-31 01:05–01:31（设备 CST）；APK sha256 `8de9a0b46c44361417de79db0677e628adddc311377405ce200fec33b03d27b8`；基线 `2fa4538`，代码 `7a7e5d4`，Phase A 证据 `97d841e`，Phase B 修正与证据见本次提交。逐项细节与 PENDING 见 `docs/evidence/plan3/FINAL-REVIEW.md`。

| 能力/断言 | 证据文件 | 结论 |
|---|---|---|
| A1 生成链 T0 真实入库 | `p3-e2e-00-timeline.txt`、`p3-e2e-09-check-cost.txt`、`p3-e2e-12-logcat.txt` | ✅ PASS |
| A2 五闸门全绿（含 T2/T3 JVM） | `p3-e2e-11-faithful.txt`、`p3-export-03-full.txt` | ✅ PASS |
| A3 版本线/选中/撤回 | `p3-e2e-03-revise.txt`、`p3-e2e-05-selected.txt`、`p3-e2e-08-db-sidecar.txt` | ✅ PASS |
| A4 ASK 不建版 | `p3-e2e-04-ask.txt` | ✅ PASS（纯提问误路由另记偏差候选） |
| A5 导出文件可打开（pdf 结构级 + 查看器） | `p3-export-04-check-export.txt`、`p3-e2e-07-viewer-probe.txt`、`p3-e2e-07-pdf-open.txt` | ⚠️ pdf 结构 ✅；txt/docx/pdf 打开态 PENDING（环境限制） |
| A6 成本复算 | `p3-e2e-09-check-cost.txt` | ✅ PASS（rows=14 mismatches=0 failed=10） |
| A7 失败行记账 | `p3-e2e-09-failed.txt`、`p3-e2e-09-check-cost.txt`、`p3-e2e-12-logcat.txt` | ✅ PASS（8 条 error 行 cost=0/usage NULL） |
| A8 提示词指纹 | `p3-e2e-14-misc-checks.txt` | ✅ PASS |
| A9 Key 安全 | `p3-e2e-12-logcat.txt` | ✅ PASS（Key 形态 grep 0） |
| A10 无新依赖 | `p3-e2e-14-misc-checks.txt` | ✅ PASS |
| A11 schema 不变 | `p3-e2e-08-db-sidecar.txt` | ✅ PASS（9 表 / user_version=2 / integrity=ok） |
| A12 列表页仍显示原样稿 | `p3-e2e-14-misc-checks.txt` | ✅ PASS |
| A13 文件名清洗 | `p3-e2e-06-export.txt`、全量 JVM | ✅ PASS |
| A14 大字体输入条不被遮挡 | `p3-e2e-10-fontscale.txt`、`p3-e2e-13-near-white.txt` | ✅ PASS（nearWhite=2 为 IME chevron，Lead 裁定接受，**D410**） |
| A15 logcat FATAL EXCEPTION 0 | `p3-e2e-12-logcat.txt` | ✅ PASS |
| A16 证据头三行/sha | 本目录全部 `p3-e2e-*` / `p3-export-*` | ✅ PASS |
| A17 复跑命令可执行 | `FINAL-REVIEW.md` §六 与各 REPLAY | ✅ PASS |
| A18 PENDING 白名单只收环境限制 | `FINAL-REVIEW.md` §五 | ✅ PASS |
| A19 §9 偏差都有 D 号 | `FINAL-REVIEW.md` §四 | ✅ 本轮候选已由 Lead 编号 **D407–D414** 并写入计划 §9 |
| A20 看板 10/10 + 验收段 | `docs/PROGRESS.md`、本节 | ✅ PASS |
| A21 FINAL-REVIEW 第三方可复现 | `docs/evidence/plan3/FINAL-REVIEW.md` | ✅ PASS |
| A22 生成入口可点、失败可重试 | `p3-e2e-09-failed.txt`、`p3-e2e-09b-retry.txt` | ✅ PASS |
| A23 降级/兜底链证据 | `p3-e2e-14-misc-checks.txt`、全量 JVM | ✅ PASS（STT 未用 → PENDING） |
| A24 导出下界 API29+ | `p3-e2e-06-export.txt`、`p3-e2e-14-misc-checks.txt` | ✅ API29+ 三格式；⚠️ API26–28 下界 PENDING（无镜像） |

## 3e 语音追加与小说化显式触发证据表（Plan 4 / T7）

> 分支 `plan4-voice-append`，基线 `00a9cf2`；终态全量 **49 类 / 418 例 / 0 失败**。
> 逐项细节见 `docs/evidence/plan4/FINAL-REVIEW.md`；PENDING 白名单见其 §五。

| 能力/断言 | 证据文件 | 结论 |
|---|---|---|
| B1 详情页「补一段」入口常驻（原样 tab） | `p4-append-05-ui.png/.txt` | ✅ PASS |
| B2 追加复用同一 WAV、`rawAudioMs` 推进 | `p4-append-06-append.png/.txt`（140844 B = 44+4400×32） | ✅ PASS |
| B3 只转写尾段、不重复旧段 | `p4-append-03-tailwriter.txt`、`TranscriptAppendTest` 7 例 | ✅ PASS（真实中文 STT PENDING，P1） |
| B4 水位 CAS 幂等 + save() 不回退水位 | `p4-append-00..02`、`AppendTranscriptTest` 10 例、`DreamRepositoryTranscriptWatermarkTest` 2 例 | ✅ PASS（变异必红） |
| B5 启动 reconcile + 重试/跳过入口 | `p4-append-03`、`p4-append-05`（「还有 2 秒没转成文字」） | ✅ PASS（设备转写失败停 PENDING 交补跑） |
| B6 忠实稿落库 + 小说化两槽分离 | `p4-faithful-00-base.txt`（变异 2 处必红） | ✅ PASS |
| B7 小说化显式触发 + 想法对话框 | `p4-novel-00..02`（弹框 / 生成 v8 / 无自动触发用例） | ✅ PASS（中文想法输入 PENDING，P6） |
| B8 `novel.v2.1` 第 4 槽 + `novel_revise.v1.1` 分流 | `PromptRepositoryTest` 11 例、APK 资产清单 | ✅ PASS（真实质量未评，D417） |
| B9 版本线过滤 faithful + 列表用选中版 | `p4-ui-01..02`、`VersionStripFilterTest`、`LibraryPreviewTest` | ✅ PASS |
| B10 成本行 + 生成 app-scope | `p4-ui-03-cost`（¥0.0105）、`p4-ui-04-scope`（离开再回 ¥0.0114/v9）、`GenerationScopeTest` 4 例 + `AppendEntryTest` 第 7 例 | ✅ PASS（进程被杀恢复 PENDING，P7） |
| B11 DB v2→v3 迁移与回填 | `p4-mig-00..02`、`MigrationV2ToV3Test` 3 例、`check-migrate --diff` v1→v2 逐字不变 | ✅ PASS |
| B12 release 构建/装机/冷启动 ×3 | `p4-release-01-build.txt`、`p4-release-02-launch.png` | ✅ PASS（全新安装种子 PENDING，P3） |
| B13 API 26–28 导出门禁 | `p4-api26-01.txt` | ⚠️ PENDING（无镜像，P4） |
| B14 三格式打开态 | `p4-api26-01.txt`（只 htmlviewer） | ⚠️ PENDING（环境，P5） |
| B15 全量绿 + 证据头/Key/偏差纪律 | 全量 XML 49/418/0；`FINAL-REVIEW.md` §四 | ✅ PASS（D415–D460 全部登记） |

## 3f UI 打磨 / 离线美术 / 档位 UI / 发布就绪证据表（Plan 5 / T8）

> 分支 `plan5-polish`，基线 `2735d57`；终态全量 **52 类 / 443 例 / 0 失败**（0.9.0 / versionCode 2）。
> 逐项细节见 `docs/evidence/plan5/FINAL-REVIEW.md`；PENDING 白名单见其 §五（P1–P11）。

| 能力/断言 | 证据文件 | 结论 |
|---|---|---|
| B1 四张离线插画入库、体积/近白达标 | `p5-asset-00-master.txt`（合计 72,220 B、单张 ≤31,728 B、近白 0、meanY ≤0.018） | ✅ PASS |
| B2 图标前景/自适应/monochrome/splash | `p5-icon-01..03`（桌面图标、splash 抽帧、aapt2 badging/resources） | ✅ PASS |
| B3 空态组件接入库/日/小说/素材 | `p5-empty-00..03`（nearWhite 全 0；日视图不可达登记 D489） | ✅ PASS（日视图 PENDING，P10） |
| B4 设置页模型 + 思考档位 | `p5-settings-00-tiers.txt`、`p5-settings-01/02`（变异必红、持久化） | ✅ PASS |
| B5 请求体透传（novel/faithful） | `GenerationOrchestratorTest` 3 例（思考 high 不带 temperature、faithful 恒关） | ✅ PASS |
| B6 可靠性 A1 追加尾巴不漏 | `p5-t5-reliability.txt`、`AudioScanTest` 2 例、变异 2 红 | ✅ PASS（真机长录 P5/P9） |
| B7 可靠性 A2 重试转写可用 | 同上、`AppendEntryTest` 第 7 例、`markTranscriptionRetry` 放宽 | ✅ PASS |
| B8 可靠性 A3 半截原文不进生成 | 同上、`GenerationOrchestratorTest` 覆盖闸门 0 次 HTTP | ✅ PASS |
| B9 可靠性 A4 4h/128MB 守卫 | `RecordingLimitsTest` 4 例 | ✅ PASS（真机 4h P9） |
| B10 触控 ≥48dp（1.3× 字号） | `p5-a11y-01-bounds.txt`（17 个 clickable ≥126px）、`p5-ui-05/06` | ✅ PASS（TalkBack P9） |
| B11 release 0.9.0 签名包 | `p5-release-01-build.txt`（证书 `cf1a5dce…`、APK 9,695,958 B） | ✅ PASS |
| B12 冷启动 ×3 + FATAL 0 + 无 Key 泄漏 | `p5-release-02-coldstart.txt`（440/436/431ms、`dk_debug_save_key`=0） | ✅ PASS |
| B13 首启为空（release 无种子） | `p5-release-02` + `p5-empty-00`（0 天有梦） | ✅ PASS（真机全新安装 P2） |
| B14 API 26–28 / 导出打开态 | 复用 Plan 4 `p4-api26-01.txt` | ⚠️ PENDING（P3/P4） |
| B15 证据/偏差/密钥纪律 | 全量 XML 52/443/0；`git ls-files` 密钥文件 0；D461–D491 全部登记 | ✅ PASS |
