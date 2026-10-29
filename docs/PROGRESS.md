# 进度看板（DreamKeeper）

> 这个文件就是 todo list 的长期版本。界面上的清单只在单轮对话里显示、容易看不到，所以把同一份清单写进仓库：**每完成一项我就改这里并 commit**，历史可在 `git log -- docs/PROGRESS.md` 里查到。
>
> 最后更新：2026-10-30 02:45（Plan 1 收尾中）

## 一、当前在做

| # | 任务 | 状态 | 说明 |
|---|---|---|---|
| T9 | Plan 1 · Task 9：标签管理页 + 库级验收 | 🔄 进行中 | 实施者在改 `DreamRepository.kt` / `DreamTagDao.kt` / `TagDao.kt`，新建 `ui/tags/`；Lead 复验后打 `plan1-done` |

## 二、Plan 1（骨架与梦境库）逐任务

| 任务 | 状态 | 验收证据 |
|---|---|---|
| 1 工程可构建可安装 | ✅ | [task1.md](evidence/plan1/task1.md) |
| 2 梦匣色板与主题 | ✅ | [task2.md](evidence/plan1/task2.md) |
| 3 日历日期逻辑 | ✅ | [task3.md](evidence/plan1/task3.md) |
| 4 Room 数据层 | ✅ | [task4.md](evidence/plan1/task4.md) |
| 5 仓库层 + 5b save() 修复 | ✅ | [task5.md](evidence/plan1/task5.md) |
| 6 日历首屏 + 当日页 | ✅ | [task6.md](evidence/plan1/task6.md) |
| 7 全部列表 + 布局记忆 | ✅ | [task7.md](evidence/plan1/task7.md) |
| 8 详情四 tab + 搜索 | ✅ | [task8.md](evidence/plan1/task8.md) |
| 9 标签管理 + 库级验收 | 🔄 | 待生成 `ACCEPTANCE.md` |

**当前基线**：88 个测试全绿（10 个测试类）· APK 12,390,264 B · schema v1 未变（`identityHash 3150a0bc…`）· 截图闸门（近白像素）全 0。

## 三、你新提的需求（已收下，排期如下）

| # | 需求 | 落点 | 状态 |
|---|---|---|---|
| 1 | 只接 DeepSeek 官方 API；模型选择 + 思考强度按钮 | Plan 3 | ⬜ 待做 |
| 2 | 两种模式都调 LLM（理顺 = 必调） | 设计 §6/§7 改 v1.3 | ⬜ 待做 |
| 3 | 去 AI 味只走提示词，不依赖梦境样本 | 提示词 v2 | ⬜ 待做 |
| 4 | token 与金额计量栏目 | Plan 3（仿 dsh-cost-meter） | ⬜ 待做 |
| 5 | 导出 .txt（+.docx/.pdf） | Plan 3 | ⬜ 待做 |
| 6 | 美学增强（生图模型） | Plan 4 / 独立一轮 | ⬜ 待做 |

## 四、刚完成

- ✅ **评价体系改造**（commit `d275f4a`）：删掉「用户例子 = 金标」与 1–5 分绝对评分，改为**规则闸门 G1–G5 + 盲评偏好 + 下限基线**。你的两条例子在文档里被明确写成「只当下限尺子与口味参照，绝非对照答案」。
- ✅ Task 6b 视觉修复（底部栏自绘、时区修正，commit `9b1c901` / `a28af99`）
- ✅ Task 7 卡片重复首句修复（`bb276c3`）
- ✅ Task 8 三处夜间体验修复（`eea6216`）

## 五、待你裁定的小事

- [ ] `.gitattributes` 加 `*.md eol=lf`，否则提示词 sha256 会随换行漂移（1 行命令，做了就钉死版本号）
- [ ] 理顺模式的兜底策略：已按「先降级成保守版，再不行退原始转写」执行（你未明确回复，暂定）

## 六、已知风险（写在明面上）

- 思考模式下 `temperature` 被忽略 → 理顺默认「思考关 + 0.1」，小说默认「思考开」，按钮可改。
- 真实梦境样本仍是 0 条 → 「生成质量」这一项只能标「未评」，不许写成通过。
- iOS 需要重写 UI 与录音层，不是移植；不建议为此现在换 Flutter/KMP。
