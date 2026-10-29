# Task 4 验收证据 · Room 实体、LIKE 搜索与 DAO

- 计划：`docs/superpowers/plans/2026-10-29-foundation-and-library.md` Task 4
- 分支：`plan1-foundation`
- commits：`ca198c6` chore(build)（schema 目录 / room-testing / SEED_DEMO_DATA）→ `33869a9` test(data)（RED）→ `715c4e2` feat(data)（11 个实现文件 + schema json）
- 验收时间：2026-10-30（Asia/Shanghai）
- 验收人：Lead（复跑全量单测 + 独立校验 schema JSON + 逐条核对硬约束）

## 测试

| 项 | 结果 |
|---|---|
| `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain` | `BUILD SUCCESSFUL in 8s`（Lead 复跑） |
| `DreamDaoTest` | **15 / 15** |
| `DreamCalendarTest` | 23 / 23（无回归） |
| `ThemeContrastTest` | 4 / 4（无回归） |
| 合计 | **42 个用例，0 失败 0 错误** |

RED 证据：先只提交测试、不提交实现 → 164 条 `e:` / `UNRESOLVED_IMPORT` / `BUILD FAILED in 12s`。

## Schema 导出（这是本任务最不能丢的东西）

- 文件：`android/app/schemas/com.dreamkeeper.data.db.DreamDatabase/1.json`，8,531 字节，**已入库**
- `version = 1`，`identityHash = 3150a0bccf0e3cb537faa8aac20e4186`
- 表：`dreams`、`tags`、`dream_tags`、`media_assets`（四处，与设计 §5 对齐）

独立校验结论：

- `dream_tags` **无外键、无索引**（「禁 CASCADE」有机器可读证据，不只是注释）
- `tags.name` 有唯一索引、`media_assets.dreamId` 有索引
- `dreams.transcriptStatus` 是最后一列（末尾追加契约成立）

## 硬约束逐条核对（Lead 用 grep 复核）

| 约束 | 状态 |
|---|---|
| `exportSchema = true` + `ksp { arg("room.schemaLocation", ...) }` | 成立，产物已入库 |
| **禁止** `fallbackToDestructiveMigration` | 成立（源码里只有一句「永远不许加」的注释说明） |
| 不建 `generation_jobs` / `prompt_versions` | 成立（schema 里 4 张表，没有这两张） |
| `monthRange` 闭区间 → DAO 自行 `+1` 转半开 | 成立，且有专门的区间边界用例守着 |
| `dream_tags` 禁止外键 CASCADE | 成立（schema 与源码双重证据） |
| LIKE 中文搜索 + `ESCAPE '\'` + 只查未删除 | 成立；转义责任按计划留给 Task 5 的 Repository |

## 版本风险解除

**KSP `2.3.12` + Kotlin `2.4.21` + Room `2.8.5` 实测可用**——这是 Plan 1 里第一次真正跑 Room 注解处理，D14 里挂了一整轮的未知量到此闭合。版本一个都没动。

## 本任务引入的偏差（已登记 D25–D27）

| # | 偏差 | 处理 |
|---|---|---|
| D25 | 计划测试骨架里的 `MediaType.IMAGE.name` **不成立**：`MediaType` 是 `object { const val IMAGE = "IMAGE" }`，String 没有 `.name` | 测试改为直接传 `MediaType.IMAGE`，断言仍要求列值精确等于 `"IMAGE"`。若改成 enum 会破坏 `SeedData` 与 Produces 契约，故选改测试 |
| D26 | `observeById` **不过滤 `deletedAt`** | 这是刻意的：回收站要能打开已删除的梦。**Task 5 / Task 8 不许拿它做列表**，列表走 `observeAll`/`observeByMonth` |
| D27 | `dream_tags` 没有 `tagId` 索引 | Task 9 的 `repointDreamTags`/`deleteByTag` 会全表扫。千条量级可接受；**现在不加索引**，因为加索引 = 触发 v2 迁移，为尚未测量到的性能问题付迁移成本不划算。破万条再谈 |

## 交接给 Task 5

1. `searchTerm(term)` 收到的 term **必须已转义**（`%`、`_`、`\`）；转义与逐词求交集逻辑都在 Task 5 的 `DreamRepository.search`。
2. `observeById` 不过滤软删（D26），别拿它做列表数据源。
3. 全库**无外键 → 无级联**：硬删除的收尾（媒体文件、关联行）要显式写，`MediaAssetDao` 目前没有 `deleteByDream`。
4. `Dream(...)` 构造是位置参数调用，新字段只能**末尾追加且带默认值**。
