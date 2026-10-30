# 进度看板（DreamKeeper）

> 这个文件是 todo list 的长期版本：界面上的清单只在单轮里显示、容易看不到，所以把同一份清单写进仓库。**每完成一项我就改这里并 commit**，历史见 `git log -- docs/PROGRESS.md`。
>
> 最后更新：2026-10-30 15:35 —— **Plan 2 开工：Task 1 已验收（132 用例全绿，两份 WAV 逐字节一致）；设计 v1.4.1、提示词 v2、五闸门、20 条语料已就绪**

## 一、当前在做

| # | 任务 | 状态 | 说明 |
|---|---|---|---|
| D1 | 设计升 v1.4：模式一改「理顺」+ 三级失败链、对话改稿与多版本、DeepSeek 单厂商、成本表 | ✅ `93029a2` | §6.1 / §6.3 / §5 / §7 / §9 / §14 已改，+121/−42 |
| D2 | 提示词 v2：`faithful.v2`（理顺）+ `novel.v2`（A1–A21 风格契约）+ `novel_revise.v1`（对话改稿） | ✅ `aa39b90` | 自检 36/46/40 条；三份 sha 5b9702ee / f189dad8 / 2ebba630；与真实梦境原文连续 8 字重叠 0 |
| D3 | `scripts/check-faithful.py`：五闸门 G1–G5 + 变异自测 | ✅ `dddf702`→`ba0aedf` | 自测 **11 条**（9 条变异红 + 单字数词红 + 只加标点绿）；G2 修正为只数正文，见 §七 |
| D4 | 合成回归语料 20 条（`docs/eval/synthetic.jsonl`，进 git） | ✅ `fdd4fd9` | `raw → good_clean` 五闸门 20/20 绿；与真样本连续 8 字重叠 0 |
| T9 | Plan 1 · Task 9：标签管理 + 库级验收 | ✅ 已验收 | 当轮 95 用例；终审修 P1 后 101 用例；`plan1-done` 已打 |

## 二、Plan 1（骨架与梦境库）已完成 9/9

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
| 9 标签管理 + 库级验收 | ✅ | [task9.md](evidence/plan1/task9.md) · [ACCEPTANCE.md](evidence/plan1/ACCEPTANCE.md) |

**当前基线（Lead 独立复跑）**：11 个测试类 / **101 用例 / 0 失败**（含终审 P1 修复新增 6 条）· APK 12,482,840 B · schema v1 一行未变（`identityHash 3150a0bc…`）· 近白像素闸门全 0 · 冷启动 923 ms · 崩溃 0。

**现在装到手机上能干什么**：翻月历看哪天有梦（红点）、点日期看当天的梦、切「全部」看时间轴/卡片、全文搜索、点进详情看四 tab（原样 / 小说化 / 标签 / 素材占位）、管标签（改名/合并/删除）。

## 二·五、Plan 2（录音与转写）进行中 1/9

| 任务 | 状态 | 说明 |
|---|---|---|
| 1 WavStore 与 WAV 数学 | ✅ 完成 | `ea1d8a2`：`WavMath`/`WavStore` 单一写句柄、整 44 字节黄金断言、fixture 已回填头（D88–D91）；132 用例全绿，两份 WAV sha256 一致，ffprobe 无告警 |
| 2 RecorderCore 读循环与补零 | ⬜ | 假源 + 假时钟做 JVM 测；先补零再写新数据 |
| 3 前台录音服务 | ⬜ | microphone 类型 FGS、通知、START/STOP/CANCEL |
| 4 录音页 + RecordEntry | ⬜ | 按住录、松手停；开录前先存 `isDraft` 草稿 |
| 5 30 秒追问窗口 | ⬜ | 补录追加到同一 WAV |
| 6 启动扫描与头修复 | ⬜ | 孤儿恢复 / 反向孤儿 / 补回填 |
| 7 SttEngine 与写回 | ⬜ | 三态探测 + API 33+ 文件转写 + 窄更新写回 |
| 8 详情页播放 | ⬜ | 播前 repair；时长取 `rawAudioMs` |
| 9 端到端验收与证据 | ⬜ | W1/W2/W3 三层 + `ACCEPTANCE.md` 追加 `## 3b 音频证据表` |

任务书：[plans/2026-10-30-audio-and-transcription.md](superpowers/plans/2026-10-30-audio-and-transcription.md) · 证据约定：[evidence/plan2/README.md](evidence/plan2/README.md)

**Plan 2 的目标**：按住一个钮说梦 → 松手得到带音频的草稿 → 崩溃/重启后音频还在、头被修复 → 详情页能播 → 有文字就显示，没有就明确说为什么（**不许伪造转写成功**）。

## 三、你提的需求（已收下，落点如下）

| # | 需求 | 落点 | 状态 |
|---|---|---|---|
| 1 | 只接 DeepSeek 官方 API；模型 + 思考强度按钮 | Plan 3 | ⬜ |
| 2 | 两种模式都调 LLM（理顺 = 必调） | 设计 v1.4 §6.1 | ✅ |
| 3 | 去 AI 味只走提示词，不依赖梦境样本 | 提示词 v2 | ⬜ |
| 4 | token 与金额计量栏目 | Plan 3（仿 dsh-cost-meter） | ⬜ |
| 5 | 导出 .txt（+.docx/.pdf） | Plan 3 | ⬜ |
| 6 | 美学增强（生图模型） | Plan 4 / 独立一轮 | ⬜ |

## 三·五、Plan 1 终审（whole-branch final review）

- **Lead 独立走查**（`docs/evidence/plan1/FINAL-WALKTHROUGH.md`，commit `4fbea1e`）：干净安装 + `pm clear` 复位，手动走 6 屏（日历 → 当日 → 详情 → 全部 → 标签 → 搜索），近白闸门 6/6 为 0，`FATAL EXCEPTION` / `ANR` 零命中，截图与 sha8 全部落库。中文检索走 `dk_prefill_search` 实测命中「电梯」。
- **硬约束自审**（逐条实测，非引用文档）：`AndroidManifest.xml` 零 `uses-permission`；`android/app/src/main` 零 `FFFFFF`/`Color.White`；无 Hilt/Koin/DataStore/Roborazzi/Turbine；无 `fallbackToDestructiveMigration`；导航只有 `navigation-compose 2.9.8`；`namespace/applicationId com.dreamkeeper`、`compileSdk 37 / targetSdk 37 / minSdk 26`。
- **复核数字**：11 测试类 / 95 用例 / 0 失败；schema v1 自 `17d4831` 起零 diff；`docs/superpowers/**`、`prompts/**`、`app/schemas/**` 零改动。
- ✅ **独立 reviewer subagent 已归档**（`4d317134`）：P0 零；P1 三条真 bug 已修 —— 当日页/全部页首帧假空态（`DayUiState.loaded` / `LibraryUiState.loaded`）、搜索在 250ms 防抖窗口内假报「没有找到」（`SearchViewModel.searching`）；补 6 条用例并做变异验证（把 `initialValue` 改成假装 loaded，新用例立刻红）。
- ✅ P2 五项**有意未修**，登记 D87（`createdAt` 不读已有值、时钟未注入、路由名手写、一处弱断言、标签 N+1）。
- ✅ 文档更正（D86）：`ACCEPTANCE.md` 的 HEAD 指向与 logcat 行数按实物改回，并保留原话加更正标记。
- ⚠️ 另有**一份 subagent 报告整体作废**：声称提交 `9d3ce1c`/`9c4f523` 并写了提示词 v2 与语料，磁盘上全不存在。教训写进 §6。

## 四、刚完成

- ✅ **设计 v1.4**（`93029a2`）：模式一从「零 LLM」改为「必调 LLM 的理顺」+ 三级失败链（重试温度 0.0 并附违规清单 → 保守版 → 原始转写）；新增 §6.3 对话改稿（最小改动 ≤115% / 歧义先问不改 / 多版本 + 挑选最终稿）；§5 增 `novel_versions` / `novel_messages` / `llm_calls`；§9 收敛 DeepSeek 单厂商 + flash 默认 + 思考档位表 + 「思考开着时温度被忽略」；§14 增对话改稿验收。
- ✅ **五闸门脚本**（`dddf702`）：`scripts/check-faithful.py`，G1 数量/颜色/方位/专名、G2 长度比、G3 2-gram 覆盖率、G4 跨场景因果、G5 不确定词；9 条变异自测全绿；用真实梦境「只清理 `[em]` 残留」验证判绿（清理格式不算违规）。
- ✅ **收到 9 条真实梦境原文**（用户 2026-10-30 提供）：落地 `docs/eval/dreams-user.local.md`（`.gitignore:28` 挡住，不进 git），只作参考样本，不作标准答案。
- ✅ **Plan 1 收尾**（`f208d00` + `cd94b78`）：标签页、`mergeTags` 事务、种子补到 4 个标签（电梯/升降机同义）、「说梦」钮不再是哑键；Lead 独立复跑 95/95、schema 无 diff、禁区零改动。
- ✅ **评价体系改造**（`d275f4a`）：删掉「用户例子 = 金标」与 1–5 分绝对评分，改为规则闸门 + 盲评偏好 + 下限基线。
- ✅ Task 6b / 7 / 8 的夜间体验修复（底部栏自绘、卡片重复首句、Snackbar 发白、文案漏出内部计划号）。

## 五、待你裁定的小事

- [ ] `.gitattributes` 加 `*.md eol=lf`（否则提示词 sha 会随换行漂移）
- ✅ 理顺兜底已定：**重试一次（温度 0.0 + 附违规清单）→ 保守版 → 原始转写**；原始转写页常驻「看原始转写」入口
- ✅ 垃圾字符（`[em]...[/em]`、多余空格、乱码）：清理，并在改动清单记一条「清理格式」
- ✅ 小说化多版本：全部保留，用户挑一个当最终稿（`novelSelectedVersion`）

## 六、已知风险（写在明面上）

- 思考模式下 `temperature` 被忽略 → 理顺默认「思考关 + 0.1」，小说默认「思考开」。
- 真实梦境 9 条已收到（`docs/eval/dreams-user.local.md`，gitignore 不进库，只作参考、**不作标准答案**）→ 真实生成质量**还没跑过**，仍只能标「未评」。
- §14 七条验收里，Plan 1 只覆盖 §14.7（负向证据：无网络权限、uid 无收发包）；其余六条都在 Plan 2/3/4，已逐条写明原因与补测时点。

## 七、这一轮的真实收获（不是计划内的）

- **闸门被自己的合法样本打穿了一次**：G2 第一版把标点算进长度比，20 条合成语料里 19 条被判红（比值 1.12–1.16）——而「插标点」正是理顺**唯一被允许的操作**。修正为只数正文字数，并补一条自测「只加标点、正文不变必须判绿」。教训：闸门必须能被合法样本打穿一次，否则只证明它有牙，不证明它准。
- 设计因此升到 **v1.4.1**（只澄清口径，不改决策）：全文所有「长度比」统一指正文字数比、不计标点与空白。
- **另外两个闸门缺陷，是「拿真数据跑一遍」才暴露的**：
  - `CN_NUM_RE` 写成了 `"[%s]+(?:%s)"`——量词没进字符类，这条正则**永远命不中**，于是「一楼 → 三楼」这种单字数词改动 G1 看不见（只有「三十」这类多位数词靠另一条正则兜住）。已改成 `"[%s]+[%s]"` 并补自测。
  - G5 把「**不可能**」里的「可能」当成不确定词，属于计数假阳性（会让闸门更松）。已加否定前缀护栏（不/没/未/别/莫）。
- 现在 `--self-test` 是 **11 条**：合法样本必须绿、9 条变异必须红、单字数词改动必须红、只加标点必须绿。
