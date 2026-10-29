# Plan 1 验收底账（ACCEPTANCE）

> 用途：设计 §14 的七条验收标准逐条给结论（不写「已完成」这种没证据的话）；Plan 1 的自证项单列一张表；
> 末尾是 Task 9 brief Step 8 要求核对的「预留口子」。
> 采集时点：2026-10-30 02:41–03:06 CST（Asia/Shanghai）。所有截图都来自下面这一个 APK。

## 0. 构建绑定

| 项 | 值 |
|---|---|
| 分支 / HEAD | `plan1-foundation` / `073afbc`（本文件与 Task 9 改动随后一次性提交，见 `git log -1`） |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| APK 字节 | 12,436,053 |
| **APK sha256** | `A621948807A420020A97A6F4C5CD99FB58571547F1216BA0DFF83D0A34D954FE` |
| APK 构建时间 | 2026-10-30 02:41:09（`:app:assembleDebug` BUILD SUCCESSFUL，显式跑过，不是 `testDebugUnitTest` 的副产物） |
| 安装 | `adb shell pm clear com.dreamkeeper` → `adb install -r`（都是 Success），装完 02:41:36 |
| 设备 / 系统 | emulator-5554 / Android 17（API 36），1080×2400，density 420 |
| 单测 | 11 个测试类 / **95 用例 / 0 失败 / 0 错误**（`app/build/test-results/testDebugUnitTest/*.xml` 计数，XML 时间戳晚于 APK） |
| schema | `git diff --exit-code -- android/app/schemas` → **退出码 0**（一行未变） |
| 崩溃 | `adb logcat -d` 20,258 行，`FATAL` 匹配 **0** 行 |

## 1. §14 验收标准（九列，逐条）

| § | 判据原文（逐字抄设计 §14） | 归属计划 | 证据文件 | sha256(8) | 构建绑定（APK sha256） | 复跑命令 | 结论 | 未覆盖原因 + 补测时点 |
|---|---|---|---|---|---|---|---|---|
| §14.1 | 从点亮屏幕到开始录音 ≤ 3 步，≤ 3 秒。 | Plan 4 | `task9-calendar-record-hint.png`（只证「说梦」钮当前弹提示，不证本条） | 690297D6 | A6219488 | `powershell -File scripts/capture-logcat.ps1 -Runs 3 -OutFile docs/evidence/plan1/task9-logcat.txt` | 未覆盖 | 录音页未实现：`composable("record/{entry}")` 仍是 Plan 2 占位，点「说梦」只弹「录音还没做，之后会有」。冷启动 `STARTUP max=1008ms` 是 App 启动耗时，**不**计「点击到开录」。补测：Plan 4 录音落地后，用录屏数点击到开录的帧数 + 停表。 |
| §14.2 | 录音过程中接电话/切后台/锁屏，音频不丢。**判据（可执行，不是感觉）**：电话前后说的内容在文件里**连续**（第 N 分钟的话仍在第 N 分钟的位置），通话期间是**等长静音**（补零，见 §8.1-6）；文件是能正常打开、时长与 `rawAudioMs` 一致的 WAV。 | Plan 2 | 无 | — | — | `powershell -File scripts/capture-logcat.ps1 -Runs 3`（仅覆盖崩溃面） | 未覆盖 | 录音与音频通路都还没写，造不出「通话期间补零」的场景。补测：Plan 2 音频层落地后，用 `adb emu gsm call`/锁屏脚本跑三条，再用 `ffprobe`+静音段检测验连续性。 |
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
