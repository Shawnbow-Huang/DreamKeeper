# Plan 1 · 整支终审（whole-branch final review）

- 审查范围：`git diff a880cf0..4fbea1e`（Plan Base → 终审前 HEAD），分支 `plan1-foundation`
- 审查人：Lead（本文件）。另派过一个独立只读 reviewer subagent，**它的报告无法核实，故不作为证据**（见文末「未采信的证据」）
- 结论：**Plan 1 达到「可翻、可搜、可管标签」的验收线**；§14 七条里只有 §14.7 本轮可判绿，其余六条按 ACCEPTANCE 如实标未覆盖

## 1. 数字复核（我亲自跑，不转述实施者）

| 项 | 值 | 来源 |
|---|---|---|
| 单元测试 | 11 类 / **95 用例 / 0 失败 / 0 错误** | `android/app/build/test-results/testDebugUnitTest/*.xml` 逐文件累加 |
| 构建 | `assembleDebug` BUILD SUCCESSFUL；APK 12,436,053 B，sha256 前 8 位 **A6219488** | 本机 Gradle 9.8.2 + AGP 9.5.0 + JDK 25.0.4 |
| Room schema | `1.json` 自 `17d4831` 起 **零 diff**（`git diff --name-only 17d4831 HEAD -- android/app/schemas` 空） | 构建后复跑 |
| 冷启动 | 985 / 910 / 1002 / 1008 ms（`am start -W`，含首帧） | 现场实测 |
| 崩溃 | `FATAL EXCEPTION` / `ANR in com.dreamkeeper` **零命中** | `logcat -d` 过滤 |

## 2. 硬约束逐条实测

| 约束 | 实测 |
|---|---|
| 零权限 | `AndroidManifest.xml` 无任何 `uses-permission` |
| 无纯白 | `android/app/src/main/**` 无 `FFFFFF`、无 `Color.White` |
| 单 module、无重型框架 | 只有 `:app`；无 Hilt/Koin/DataStore/Roborazzi/Turbine（仅注释提及「不引 Hilt/Koin」） |
| 禁破坏性迁移 | 无 `fallbackToDestructiveMigration`（仅「永远不许加」的注释） |
| 导航 | 只有 `androidx.navigation:navigation-compose:2.9.8` |
| 包名与 SDK | `namespace`/`applicationId` = `com.dreamkeeper`；`compileSdk 37 / targetSdk 37 / minSdk 26` |
| 提示词不入 android/ | `prompts/` 在仓库根，`android/` 内零引用 |
| 证据 | `docs/evidence/plan1/` 覆盖 Task 1–9（task1–9 各一份 `.md`）+ `ACCEPTANCE.md` + `FINAL-WALKTHROUGH.md` |

## 3. 全链路走查（独立于实施者证据）

见 `FINAL-WALKTHROUGH.md`：干净安装 → `pm clear` 复位 → 手动 6 屏（日历/当日/详情/全部/标签/搜索），6 张截图 sha8 记录在案，近白闸门 6/6 为 0，中文检索走 `dk_prefill_search` 命中「电梯」。**未复用实施者的任何截图**。

## 4. 逐任务验收记录（见 ledger）

`.superpowers/sdd/2026-10-29-foundation-and-library/progress.md` 按 Task 1–9 记录 commit 列表、测试数、偏差区间与验收动作；Plan 1 结束里程标注 `PLAN 1 DONE: 9/9, a880cf0 -> d4b7fee`。

## 5. 偏差与已知限制（D1–D84）

计划文档 `docs/superpowers/plans/2026-10-29-foundation-and-library.md` 末尾偏差表共 84 条，全部标注「我以为的坑」还是「实测结论」。本轮新增 D82–D84：

- **D82**：近白闸门必须带 `-TopOffset 100`。`-TopOffset 0` 会把系统状态栏白字算进来（96/72,000 = 0.133%），它不是我们画的界面。
- **D83**：§14.7 用累计计数器口径（`dumpsys` 无 INTERNET + uid 在网络计数表整行缺席），只证「无网络能力」，**不覆盖** R3（系统 STT 联网回退）。
- **D84**：标签胶囊点击暂不筛选（弹提示），留给 Plan 2，避免做成假按钮。

## 6. 外部审查记录（两份，一份采信、一份作废）

**采信：只读整支审查 subagent（`4d317134`）。** 它给了带 `文件:行号` 的分级结论，我抽查了其中可验证的部分：`DreamDatabase.kt` 的 `exportSchema=true`、`TagDao.kt:14/:21` 的 UPDATE / UPDATE OR REPLACE、schema `identityHash 3150a0bccf0e3cb537faa8aac20e4186`、APK `12,436,053 B / A6219488…`、6 张走查图的 sha 与我表里逐位一致。结论是 **P0 为零**，P1 三条（当日页/全部页首帧假空态、搜索在防抖窗口内假报「没有找到」），P2 若干。三条 P1 我全部修了（见 §8）。

**作废：另一个扩张任务的 subagent 报告。** 它声称提交了 `9d3ce1c`/`9c4f523`，并新增 `docs/eval/dreams.medium.jsonl`、`prompts/faithful.v2.md`、`prompts/novel.v2.md`、`scripts/check-faithful.py`。核盘结果：

- `git log --all --oneline` 里不存在这两个 commit；
- 上述四个文件在 `D:\DreamKeeper` 全都不存在；
- 全盘也没有另建的仓库副本。

因此那份报告整体作废。教训与此前一致：**subagent 的报告是线索，不是事实；必须回到磁盘复核。**

## 7. 结论

Plan 1 可以收工并打 tag（`plan1-done`，已打）。下一阶段（Plan 2 录音与转写 / Plan 3 生成与提示词）开工前必须先做三件事：设计升 v1.4 承接用户六条新需求、提示词升 v2 承接「理顺」新定义、补齐 `scripts/check-faithful.py` 与 20 条评测语料。

## 8. 终审 P1 修复（本轮落地）

| # | 问题 | 修法 | 验证 |
|---|---|---|---|
| P1-1 | 当日页 `items.isEmpty()` 分不清「这天没梦」与「还没读到」 | `DayUiState` 末尾加 `loaded: Boolean = false`；两个分支都置 `true`；`DayScreen` 先判 `!loaded` 留白 | 新增 2 条用例（首帧未 loaded / 空日算 loaded）；变异验证：`initialValue` 假装 loaded → `3 tests completed, 1 failed`，改回即绿 |
| P1-2 | 全部页同型 | `LibraryUiState.loaded` + `items` 改 `Flow<List<LibraryItem>?>`（null=未读、empty=真没有） | 新增 2 条用例 |
| P1-3 | 搜索在 250ms 防抖窗口内就报「没有找到」 | `SearchViewModel` 加 `appliedQuery` + `searching`；`SearchScreen` 在 `searching` 时显示「在找…」 | 新增 2 条用例（窗口内在找 / 清空输入不算在找） |

修复后：**11 个测试类 / 101 用例 / 0 失败**；`assembleDebug` BUILD SUCCESSFUL，APK 12,482,840 B；干净安装后冷启 `TotalTime 923 ms`；三张复验图 `p1fix-day.png`（`91BBE614`）、`p1fix-library.png`（`99C2F093`）、`p1fix-search.png`（`0DAFACE2`）近白闸门全 0，`FATAL EXCEPTION`/`ANR` 零命中。

**P2 这轮不修**，已登记 D87，留到 Plan 2/3。
