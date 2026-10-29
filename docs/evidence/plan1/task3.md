# Task 3 验收证据 · 日历月视图排布逻辑

- 计划：`docs/superpowers/plans/2026-10-29-foundation-and-library.md` Task 3
- 分支：`plan1-foundation`
- commits：`7e4596c` test(domain)（RED）→ `d25c2cd` feat(domain)（GREEN）→ `0b911f1` test(domain)（用例改名，无行为变化）
- 验收时间：2026-10-29（Asia/Shanghai）
- 验收人：Lead（复跑全量单测 + 通读实现与测试源码）

## 结果

| 项 | 结果 |
|---|---|
| `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain` | `BUILD SUCCESSFUL in 23s`（Lead 复跑） |
| `DreamCalendarTest` | **23 / 23 通过** |
| `ThemeContrastTest` | 4 / 4 通过 |
| 合计 | 27 个用例，0 失败 0 错误（Gradle 9 全绿不打印计数，数字取自 JUnit XML） |
| 新增依赖 | 无 |
| 改动文件 | 仅 2 个（+410 行）：`domain/DreamCalendar.kt`、`domain/DreamCalendarTest.kt` |

## 实现（28 行，全文见源码）

`object DreamCalendar`，只 import `DayOfWeek` / `LocalDate` / `YearMonth` 三个类型：

- `monthGrid(month, weekStart = MONDAY): List<LocalDate?>` — 前导空白数 `((first.dayOfWeek.value - weekStart.value) + 7) % 7`，末尾补 `null` 至 7 的倍数。
- `monthRange(month): Pair<LocalDate, LocalDate>` — 当月首日与末日（**闭区间**）。

## TDD 证据（先红后绿，三段）

1. **RED-1（实现不存在）**：`:app:compileDebugUnitTestKotlin FAILED`，`Unresolved reference 'DreamCalendar'.`，`BUILD FAILED in 14s`。
2. **RED-2（故意写错，证明断言有牙）**：错误实现下 `23 tests completed, 16 failed`，失败消息具体到值（`expected:<2024-02-29> but was:<2024-02-01>`、`expected:<42> but was:<35>`）。
3. **GREEN**：实现写对后 27/27 全绿。

RED-2 暴露了一个方法论问题，值得记下来：**全绿不等于正确**。错误实现下仍有 7 例通过，其中 3 例是计划原文给的用例 —— 也就是说计划里的部分断言抓不住「完全忽略 `weekStart`」这类错误。已登记为 D21–D23。

## 测试覆盖（23 例）

- 任务书要求的 5 条：月初周一无前导、前导对齐星期列、每个日期恰好出现一次、`weekStart` 可配置、长度恒为 7 的倍数。
- 边界：前导 0 / 前导 6 / 尾部 0、恰好 4 行无空白、6 行月。
- 2 月：2024 闰（29）、2026 平（28）、2000 整除 400 是闰、**2100 是平年**（手写 `%4` 会在这里挂掉，这条正好证明用的是 `java.time` 而不是自己算）。
- 跨年：2025-12 与 2026-01 互不串月。
- 全量扫描不变量：2020–2035 × 12 个月 × 7 种 `weekStart`，长度必为 7 的倍数且落在 28–42 之间；每个日期必须落在自己那一列；空白格只能是 `null` 且只在两端。
- **架构守卫**：有一例直接读源码文件，断言 `domain/DreamCalendar.kt` 的 import 只允许 `java.time.*`，且不得出现 `android` / `now(` / `System.` / `java.util` / `Locale` —— 纯函数层不许偷读时钟或平台 API。

## 本任务引入的偏差（已登记 D21–D24）

| # | 偏差 | 处理 |
|---|---|---|
| D21 | 计划里的用例名 `month with 31 days starting on monday` 用的是 2026-06，而 2026-06 是 **30 天** | 用例改名为 `month starting on monday has no leading blanks`，按 30 天断言 |
| D22 | 计划给的断言 `assertEquals(0, grid.size % 7)` **不能证明**「无前导空白」——35 格在「有 5 格前导」时同样满足 | 改成断言 `grid[0] == 当月 1 号` 且前 N 格全非空 |
| D23 | 计划的 `week start is configurable` 用例**没有判别力**：完全忽略 `weekStart` 的错误实现也能过（2026-10-01 的 `value=4` 与错误前导恰好相等） | 改用「月初是周一或周日」的月份，或直接采纳逐日列对齐断言 |
| D24 | `monthRange` 返回**闭区间**，而 DAO 按月份查询要半开区间 | Task 4 必须自己 `first.toEpochDay() until last.toEpochDay() + 1`，否则「月末差一天」的 off-by-one 是这类代码的经典坑 |

## 交接给 Task 4

1. `monthRange` 是闭区间 —— DAO 侧记得 `+1` 转成半开区间。
2. `DreamCalendarTest` 最后一例依赖 `user.dir` 向上找源码，是**有意的守卫**（不是偶发失败）；换工作目录会红，那是信号不是噪音。
3. 需要往 `data/` 层搬 `LocalDate` 与 DB 的互转，`Converters.kt` 已在本任务的下一个任务范围。
