# 进度看板（DreamKeeper）

> 这个文件是 todo list 的长期版本：界面上的清单只在单轮里显示、容易看不到，所以把同一份清单写进仓库。**每完成一项我就改这里并 commit**，历史见 `git log -- docs/PROGRESS.md`。
>
> 最后更新：2026-10-31 03:18（**Plan 4 开工 3/8**：T1 数据库 v3 + 追加 CAS（含审计补修 D423）、T2 尾段转写与启动补跑已验收，全量 **44 类 395 例**绿；D415–D430 已登记。Plan 3 收尾：39 类 365 例绿、D275–D414 已全部登记）

## 一、当前在做

| # | 任务 | 状态 | 说明 |
|---|---|---|---|
| D1 | 设计升 v1.4：模式一改「理顺」+ 三级失败链、对话改稿与多版本、DeepSeek 单厂商、成本表 | ✅ `93029a2` | §6.1 / §6.3 / §5 / §7 / §9 / §14 已改，+121/−42 |
| D2 | 提示词 v2：`faithful.v2`（理顺）+ `novel.v2`（A1–A21 风格契约）+ `novel_revise.v1`（对话改稿） | ✅ `aa39b90` | 自检 36/46/40 条；三份 sha 5b9702ee / f189dad8 / 2ebba630；与真实梦境原文连续 8 字重叠 0 |
| D3 | `scripts/check-faithful.py`：五闸门 G1–G5 + 变异自测 | ✅ `dddf702`→`ba0aedf` | 自测 **11 条**（9 条变异红 + 单字数词红 + 只加标点绿）；G2 修正为只数正文，见 §七 |
| D4 | 合成回归语料 20 条（`docs/eval/synthetic.jsonl`，进 git） | ✅ `fdd4fd9` | `raw → good_clean` 五闸门 20/20 绿；与真样本连续 8 字重叠 0 |
| T9 | Plan 1 · Task 9：标签管理 + 库级验收 | ✅ 已验收 | 当轮 95 用例；终审修 P1 后 101 用例；`plan1-done` 已打 |
| T10 | Plan 3 · Task 10：导出 txt/docx/pdf + 生成链端到端验收 | ✅ 已验收（Lead 终审） | 全量 **39 类 365 例**绿；设备 E2E 0–13 步 + FINAL-REVIEW + A1–A24；证据 `p3-export-00..05`、`p3-e2e-00..14`；D406 修复 `a774ebe` |

## 二、Plan 1（骨架与梦境库）已完成 9/9

| 任务 | 状态 | 验收证据 |
|---|---|---|
| 1 工程可构建可安装 | ✅ 完成 | [task1.md](evidence/plan1/task1.md) |
| 2 梦匣色板与主题 | ✅ 完成 | [task2.md](evidence/plan1/task2.md) |
| 3 日历日期逻辑 | ✅ 完成 | [task3.md](evidence/plan1/task3.md) |
| 4 Room 数据层 | ✅ 完成 | [task4.md](evidence/plan1/task4.md) |
| 5 仓库层 + 5b save() 修复 | ✅ | [task5.md](evidence/plan1/task5.md) |
| 6 日历首屏 + 当日页 | ✅ | [task6.md](evidence/plan1/task6.md) |
| 7 全部列表 + 布局记忆 | ✅ | [task7.md](evidence/plan1/task7.md) |
| 8 详情四 tab + 搜索 | ✅ | [task8.md](evidence/plan1/task8.md) |
| 9 标签管理 + 库级验收 | ✅ | [task9.md](evidence/plan1/task9.md) · [ACCEPTANCE.md](evidence/plan1/ACCEPTANCE.md) |

**当前基线（Lead 独立复跑）**：11 个测试类 / **101 用例 / 0 失败**（含终审 P1 修复新增 6 条）· APK 12,482,840 B · schema v1 一行未变（`identityHash 3150a0bc…`）· 近白像素闸门全 0 · 冷启动 923 ms · 崩溃 0。

**现在装到手机上能干什么**：翻月历看哪天有梦（红点）、点日期看当天的梦、切「全部」看时间轴/卡片、全文搜索、点进详情看四 tab（原样 / 小说化 / 标签 / 素材占位）、管标签（改名/合并/删除）。

## 二·五、Plan 2（录音与转写）完成 9/9

| 任务 | 状态 | 说明 |
|---|---|---|
| 1 WavStore 与 WAV 数学 | ✅ 完成 | `ea1d8a2`：`WavMath`/`WavStore` 单一写句柄、整 44 字节黄金断言、fixture 已回填头（D88–D96）；132 用例全绿，两份 WAV sha256 一致，ffprobe 无告警 |
| 2 RecorderCore 读循环与补零 | ✅ 完成 | `397fbf3` + `e177cf0`：假钟假源、先补零再写新数据、`rebase`、四种收尾原因；Lead 变异验证 sync/finish 兜底（D115–D117） |
| 3 前台录音服务 | ✅ 完成 | `b083021` + `584aed8`：microphone 型 FGS、通知、START/STOP/CANCEL；四连判据实测通过（`isForeground=true`/`foregroundId=42`/`types=0x80`、通知 id=42、写入 31,611 B/s、崩溃 0）；Lead 修失败路径孤儿文件（D127）、通知栏近白判红原因定位并豁免（D128） |
| 4 录音页 + RecordEntry | ✅ 完成 | `c3777cd`：按住说梦、权限三态、入口接线；176 用例；真机同一次 take `stat` 48044→160044→220844（= 44+6900×32），松手后冻结；按下→RECORDING 163 ms（D141–D149） |
| 5 30 秒追问窗口 | ✅ 完成 | `e99b0d0`+`eb099a5`+`9e6ed4a`：追加走 `markOpen`+`openWriter`（`create` 会截断）、失败路径只在 `createdThisTake` 时删、窄更新只写时长与状态；实测差 60800 B = **1900 ms**、第一段 PCM md5 两边一致；真机抓到两个真 bug 并修掉（D163–D172） |
| 6 启动扫描与头修复 | ✅ 完成 | `cf31244`+`3b71511`+`9f63a6f`：四主态 + 第 5 态逐文件兜底、`isOpen` 跳过正在录的文件；五态真机实测 + M1/M2 变异；Lead 复跑 219 用例、schema 零 diff（D189–D195） |
| 7 SttEngine 与写回 | ✅ 完成（有未验证项） | `fe6e94f`+`5d1493c`：三态探测 + `mapSupport` + CAS 窄更新 + 详情页三分支与手动补字；真机四张语言表全空 → `Unavailable` → 写回 `FAILED`，手动补字后 `OK` 且 `updatedAt` 未变；**33+ 正路径设备未验证**、负对照 NOT_EXECUTED。Lead 修 `FollowUpViewModel` 把 FAILED 盖回 PENDING（D198，含变异）（D196–D203） |
| 8 详情页播放（播前 repair） | ✅ 完成 | `2dc9b86`：四缝可注入、`isOpen` 拒绝且不 repair、IO 线程、字节定时长；单 Canvas 波形、`m:ss.d`、压暗禁用、`Error` 可重试、音频焦点 + `ON_STOP` 暂停。证据：播放 0→3351 ms（96%）+ flinger Track SRate 16000 + Frames +175168；`chmod 000` → EACCES 无轨道；脏头 8 ms 修好、payload sha 不变（D204–D209） |
| 9 端到端验收与证据 | ✅ 完成 | `449fe186` + `27a5282`：e2e / perm / audit / coldstart 四目录 + `ACCEPTANCE.md` 的 `## 3b`（11 列 7 行）与 `## 3c`（§14 八条）。9.1 录 4 s→追问→补录 2 s→详情 `0:05.8`→播放；9.2 三路权限（C 路「待实测」）；9.3 崩溃恢复 6,011 ms 回填；9.4 7 行全 PASS-ENV；9.5 全库审计 GREEN（容差 0）；9.6 冷启 14 次 13 次 ≤2000（最差 2080）。Lead 独立自算 7/7 PASS、自跑 5 次冷启 1885–1996 ms，并修 D214 空白卡片 + 时间轴空行（D210–D217） |

任务书：[plans/2026-10-30-audio-and-transcription.md](superpowers/plans/2026-10-30-audio-and-transcription.md) · 证据约定：[evidence/plan2/README.md](evidence/plan2/README.md) · 终审：[evidence/plan2/FINAL-REVIEW.md](evidence/plan2/FINAL-REVIEW.md)

**Plan 2 的目标**：按住一个钮说梦 → 松手得到带音频的草稿 → 崩溃/重启后音频还在、头被修复 → 详情页能播 → 有文字就显示，没有就明确说为什么（**不许伪造转写成功**）。


## 二·六、Plan 3（生成、成本、导出、对话改稿）已完成 10/10

| 任务 | 状态 | 说明 |
|---|---|---|
| 1 接线：权限、依赖、设置页骨架 | ✅ 完成 | `INTERNET` + debug 明文放行、OkHttp、`Settings` 路由、`AppContainer` 三个 lazy |
| 2 Key 加密存储 | ✅ 完成 | AES/GCM + Keystore，只存 base64 密文，日志零明文 |
| 3 价卡与成本计算 | ✅ 完成 | 资产 price card、峰谷判定、整数微元、`check-cost.py` 复算 |
| 4 数据库 v2 迁移 | ✅ 完成 | `c800a87`+`d681247`+`b2054ba`：四张新表 + dreams 两列 + `2.json` 入库 + MigrationTest + `check-migrate.py`；升级前 DbBackup 快照；设备实证 5→9 表、20→22 列逐列全等、降级必崩有原文（`p3-mig-0*`）；收尾补 `DbBackupTest` 4 例（D292） |
| 5 LlmClient（OkHttp + org.json） | ✅ 完成 | `05af3a2`：`ChatModels`/`LlmClient`/`DeepSeekClient`（派生超时、取消落 OkHttp Call、退避 1000×2 + `Retry-After`、usage 四字段、8 种错误分类）+ 8 例本地 `HttpServer` 单测（先红后绿 + 变异红）；全量 30 类/301 例绿（`p3-llm-0*`） |
| 6 提示词资产与 PromptRepository | ✅ 完成 | `92ec146`：`syncPromptAssets` 构建期把 `prompts/*.md` 拷进 `assets/prompts/`（pricing 未被覆盖，sha 不变）、`PromptSpec` 9 槽（7 必填 + 2 选填）+ 2 标记（违规清单必带载荷）、`PromptRepository` 剥头 `## 输入`/剥尾变更记录/槽行连提示行整体替换/标记块插输入块后；9 例先红后绿 + 两处变异；Lead 补第 10 例（空串/纯空白，D308）后 10/10、全量 31 类/311 例绿（`p3-prompt-0*`） |
| 7 五闸门 Kotlin 移植 | ✅ 完成 | `b4242b4`：`GateResult`/`FaithfulGates`（G1–G5 全算不短路、按码点复刻 `check-faithful.py`：25 码点空白、`\p{Nd}`、bigram/24 窗/±6 ctx、G4 跨场景、G5 防回绕）+ 15 条冻结向量 15/15 （plain JUnit + 手写 MiniJson）；变异 G1 恒空 → 7 红；全量 32 类/326 例绿（`p3-gates-0*`） |
| 8 GenerationOrchestrator | ✅ 完成 | `cd297d8`：T0–T3 失败链（T2 只认 `isConservativeOk`）、NOVEL 单次 JSON、单飞 `tryLock`、每次 HTTP 交换一行 `llm_calls`（失败行 cost=0/usageJson=null）、成功一个事务写 `novel_versions` + `dreams` 窄更新；8 例先红后绿 + 两处变异（2 红 / 1 红）；全量 33 类/334 例绿（`p3-gen-0*`）；UI 接线归 Task 9（D351/D352） |
| 9 对话改稿 + 多版本 | ✅ 完成 | `c1c8d75`：`ReviseOrchestrator`（ASK 只落两条消息不建版 / REVISE 必建版 + 闸门重试载荷）、`VersionStrip`、`ReviseSheet` 六快捷 chips、`resolveFinalNovel = selected ?: maxVersion`、`selectVersion` 只窄更新；10 例（8+2）先红后绿 + 两处变异（2 红/1 红）；全量 35 类/344 例绿（`p3-revise-0*`）；偏差 D380–D391 |
| 10 导出与验收证据 | ✅ 完成 | `7a7e5d4`+`97d841e`+`57eee1e`+`af88286`：导出 txt/docx/pdf（零依赖）、`check-export.py`、设备 E2E 0–13 步、A1–A24、FINAL-REVIEW；D406 修入口可见性（`a774ebe`），全量 39 类/365 例绿 |

任务书：[plans/2026-10-30-generation-and-cost.md](superpowers/plans/2026-10-30-generation-and-cost.md) · Plan 4 任务书待建 · 分支 `plan3-generation`（从 `plan2-done` 的 `bb11fe2` 切出）

**Plan 3 的目标**：用用户自己的 DeepSeek Key，「理顺」得到忠于原梦的原样稿（四级失败链 + 五闸门），
「小说化」得到能读的小说稿（改动清单可查），不满意能对着稿子说人话让它改（ASK 先问 / REVISE 必留版），
每一次尝试都按价卡快照记账（整数微元），两种文本都能导出 txt/docx/pdf 带走。

**先修的前提**（专家实测）：仓库没有 OkHttp 也没有 kotlinx.serialization；manifest 没有 INTERNET；
`check-near-white.ps1` 其实**有** `-MaxRatio`（Plan 2 的登记是错的）。

## 二·七、Plan 4（随时语音追加 · 小说化显式触发 · 发布就绪）进行中 3/8

| 任务 | 状态 | 说明 |
|---|---|---|
| T0 契约冻结 | ✅ 完成 | `724d13e`：计划 404 行，DB v2→v3 只加 `dreams.transcriptMs` 水位；三条追加 CAS；`stt/TailWav` 尾段转写；`faithfulText` 基座修正；`novel.v2.1` 第四可选槽；`novel_revise.v1.1` 分流（D408）；版本线/列表/成本栏/生成作用域 |
| T1 数据库 v3 + 追加 CAS | ✅ 完成 | `dreams.transcriptMs` 水位 + `MIGRATION_2_3`（回填带 `rawAudioMs IS NOT NULL`）+ 四条 CAS（beginAppend/append/retry/skip）+ 3.json；变异两处必红、恢复逐字节同；全量 **41 类 380 例**；证据 `p4-mig-00..02`、`p4-append-00..01`、`p4-full`；D418–D422 |
| T2 追加写回与重转写兜底 | ✅ 完成 | `stt/TailWav` 只切尾段 + writer 在飞排队/启动 reconcile（`PENDING && A>T`）+ 失败只置 FAILED；变异删排队必红、恢复逐字节同；全量 **44 类 395 例**；证据 `p4-append-03`；D427–D430 |
| T3 忠实稿基座与整段重跑 | ⬜ | `faithfulText` 同事务写 + `novel()` 两槽分离 + quote dual 基准 |
| T4「补一段」入口与原样 tab | ⬜ | append 路由复用录音页；原样 tab 显示忠实稿 + 看原始转写 + 重新理顺；设备 `p4-append-05..07` |
| T5 小说化显式触发与想法 | ⬜ | 常驻入口 + `NovelIdeaDialog` + `novel.v2.1` 四槽 + promptVersion 解析 + 路由 v1.1 |
| T6 可见性与体验缺陷 | ⬜ | 版本线过滤 faithful、列表用选中版、成本行、生成提到应用级作用域 |
| T7 发布就绪与终验 | ⬜ | release/API26–28/真机清单 + FINAL-REVIEW + ACCEPTANCE `## 3e` + 看板 8/8 |

任务书：[plans/2026-10-31-plan4-voice-append.md](superpowers/plans/2026-10-31-plan4-voice-append.md) · 分支 `plan4-voice-append`（从 `plan3-generation` 的 `00a9cf2` 切出）

**Plan 4 的目标**：用户随时能用语音在原样稿尾部补描述，系统只转写新增尾段、拼回正文、整段重新理顺并留下可回退的新版本；小说化只能由用户点「小说化」并写下想法后生成，绝不自动触发；顺带收口 Plan 3 的可见性/路由/成本栏缺口并做到可发布。

## 三、你提的需求（已收下，落点如下）

| # | 需求 | 落点 | 状态 |
|---|---|---|---|
| 1 | 只接 DeepSeek 官方 API；模型 + 思考强度按钮 | Plan 3 | ✅ 接线 + Key 已完成（`059a821`/`19bb3d6`）；模型/思考档位随 Task 5 客户端落地（`ChatRequest.thinking/effort`），UI 档位选择待 Plan 4 |
| 2 | 两种模式都调 LLM（理顺 = 必调） | 设计 v1.4 §6.1 | ✅ |
| 3 | 去 AI 味只走提示词，不依赖梦境样本 | 提示词 v2 | ✅ 三份 v2 已定（`aa39b90`），Kotlin 闸门待 Task 7 移植 |
| 4 | token 与金额计量栏目 | Plan 3（仿 dsh-cost-meter） | ✅ 价卡 + 复算（Task 3）+ 每次尝试记账（Task 8 `llm_calls`）；账面栏 UI 拆到 Plan 4 |
| 5 | 导出 .txt（+.docx/.pdf） | Plan 3 | ✅ `7a7e5d4` 三格式 + 双侧 sha 证据 |
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

- ✅ **Task 9 对话改稿**（`c1c8d75`）：模型 JSON `action` 分流 ASK/REVISE；REVISE 首轮 0.3 → 闸门不过则 0.0 + 上一版违规清单重试一次 → 再不过旧稿不动；版本号取「版本表 MAX」与 `dreams.novelVersion` 的较大者 +1（种子 v3 → 改稿 v4）；改稿一个事务写消息/版本/窄更新。Lead 独立复跑 targeted 10/10、全量 35 类 344 例；变异 `selectVersion` 撤窄 UPDATE 杀 2 例、ASK 改走建版杀 1 例。
- ✅ **Task 9b 生成入口接线**（`c24a9f4`/`ea1ec20`）：`GenerationRunner` 接口 + `GenerationOrchestrator` 两处 override（逻辑零改动）；`DetailViewModel` 三态 `generationPhase`、ViewModel 前置闸门（非 OK/空原文不调 runner）、失败重试复用同一入口；`NovelTab` 顶部「原样整理/小说化」两按钮 + Running/Failed 文案 + 重试。3 例先红后绿、两变异（3 红 / 1 红）；设备 smoke：无稿梦两按钮可见、无 Key 失败态正确、有稿态入口不出现、logcat FATAL 0（`p3-wire-0*`）。
- ✅ **Task 9c 可见性口径修正**（D394，Lead）：原 `hasNovelDraft` 把原样整理稿算成小说稿，整理后「小说化」入口永久消失；改为 `hasNovelVersion(dream, versions)`（只认 `promptId != "faithful"` 的版本，无版本行退回 `novelText`/`novelVersion`）+ 3 例，变异只杀 faithful-only 用例（`p3-wire-05-visibility.txt`）。
- ✅ **Task 10 导出与端到端验收**（代码 `7a7e5d4` / Phase A 证据 `97d841e` / Phase B 证据见本次提交）：export/ 四文件（txt 手写头部+footer、docx 手写 OOXML 三件套零依赖、pdf PdfDocument A4 自动分页）＋ `ExportSheet` 三按钮/分享/失败文案＋详情页两处入口＋`resolveFinalNovel` 取文；`ChatRequest.kind` 透传 + DKLLM 每尝试一行日志（D392）；`scripts/check-export.py` 新建（DB 推导期望串/三格式结构断言/变异自测）＋ `check-cost.py` 的 `--require-rows`/`failed=N`/`[FAILED]`/error 行豁免（D337/D366）。
- ✅ **设备 E2E 0–13 步**：seed-dream-2「教室」→ 原样整理 v1（+误双击 v2）→ 小说化 v3 → 改稿 v4 → ASK（不建版）→ 选中 v4 → 三格式导出（双侧 sha 相等）→ 查看器探针（txt/docx 无查看器、pdf 黑屏 PENDING）→ 飞行模式失败/重试/联网成功（8 条 error 行）→ force-stop 三件 DB 合并（DEVICE_DB_SHA256==MERGED_SHA256）→ check-cost 对账（rows=14 mismatches=0 failed=10，M=8==logcat）→ check-export 三子命令 GREEN（PDF Type3 字体锚口径修正）→ check-faithful 双链五闸门绿 → font_scale 1.3 截图 → 全量 logcat（FATAL 0）→ `FINAL-REVIEW.md` / `ACCEPTANCE.md ## 3d`。
- ✅ **Task 10 验收（Lead）**：终审通过；偏差候选编号 **D407–D414**；A5/A24/分享接收端等环境限制项收进 PENDING（D376）；D406 入口可见性修复 `a774ebe`（两枚按钮各判各的 + 3 例 + 变异），**全量 39 类 365 例绿**。
- ⏭ 下一步：Plan 4（UI 打磨/真机/发布）——包括纯提问误路由（D408）、入口刷新时机、t2/t3 兜底文案、release 包与 API26–28 门禁。

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
- **转写两处缺口（Plan 2 遗留，Task 8 前必须决策）**：① 一次成功转写后再补录，补录段的写回 CAS 双 0 行、永远进不了 `rawTranscript`（D202）；② PENDING 卡死没有任何重试入口（单飞跳过 + dataSize 变化丢弃 + 进程被杀 → 详情页永久「正在转写…」）。两者都会让理顺/小说化拿半截原文 → Task 8 必须加闸门 `transcriptStatus==OK && rawTranscript.trim().isNotEmpty()`；修法 F1（启动补转写）/F3（单飞补跑）优先，F2（后缀追加 CAS）进 Task 8 契约。
- **`DreamRepository.save()` 是整行 `@Insert(onConflict=REPLACE)`**：Task 8/9 若拿旧 Dream 写稿，会把并发写回的转写文本覆盖成空 + PENDING（Plan 2 的 CAS 拦不住整行替换）→ 必须新增 `updateFaithfulText`/`updateNovelText` 窄更新 + stale-save 回归用例。
- **`DbBackup` 四分支已有单测（`DbBackupTest` 4 例，D292）**；补测顺带修掉「损坏库探测会删原文件」的副作用（`isIntact` 未同步硬化，P2 观察项）。
- **全量单测曾有 1/3 概率随机红**（`TranscriptWriterTest` 报 `UncaughtExceptionsBeforeTest`，根因是 `DreamKeeperApp` 的 IO seed 协程与 `DbBackupTest`/`MigrationTest` 删建 `dreamkeeper.db` 抢文件）→ 已加 Robolectric 门闩 `AppContainer.seedDemoOnStart`（D293），连跑 3 次全量绿。

## 七、这一轮的真实收获（不是计划内的）

- **闸门被自己的合法样本打穿了一次**：G2 第一版把标点算进长度比，20 条合成语料里 19 条被判红（比值 1.12–1.16）——而「插标点」正是理顺**唯一被允许的操作**。修正为只数正文字数，并补一条自测「只加标点、正文不变必须判绿」。教训：闸门必须能被合法样本打穿一次，否则只证明它有牙，不证明它准。
- 设计因此升到 **v1.4.1**（只澄清口径，不改决策）：全文所有「长度比」统一指正文字数比、不计标点与空白。
- **另外两个闸门缺陷，是「拿真数据跑一遍」才暴露的**：
  - `CN_NUM_RE` 写成了 `"[%s]+(?:%s)"`——量词没进字符类，这条正则**永远命不中**，于是「一楼 → 三楼」这种单字数词改动 G1 看不见（只有「三十」这类多位数词靠另一条正则兜住）。已改成 `"[%s]+[%s]"` 并补自测。
  - G5 把「**不可能**」里的「可能」当成不确定词，属于计数假阳性（会让闸门更松）。已加否定前缀护栏（不/没/未/别/莫）。
- 现在 `--self-test` 是 **11 条**：合法样本必须绿、9 条变异必须红、单字数词改动必须红、只加标点必须绿。
