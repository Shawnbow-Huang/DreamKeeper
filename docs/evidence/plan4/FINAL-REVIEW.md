# Plan 4 FINAL-REVIEW（语音追加 · 小说化显式触发 · 发布就绪）

> 分支：`plan4-voice-append`（基线 `00a9cf2`，Plan 3 终审）
> 时段：2026-10-31 01:54 – 04:20（Asia/Shanghai）
> 计划：`docs/superpowers/plans/2026-10-31-plan4-voice-append.md`
> 证据根：`docs/evidence/plan4/`（29 份：txt 20、png 9）
> 看板：`docs/PROGRESS.md` §二·七

## 一、基线与终态

| 维度 | 基线（00a9cf2，Plan 3 收尾） | 终态（本计划 T7） |
| --- | --- | --- |
| 全量单测 | 39 类 / 365 例 / 0 失败 | **49 类 / 418 例 / 0 失败 / 0 错误 / 0 跳过** |
| DB schema | v2（9 表，22 列 dreams） | **v3**（9 表 + `dreams.transcriptMs`，23 列；`MIGRATION_2_3`） |
| 提示词 | faithful v2 / novel v2 / novel_revise v1 | + **`novel.v2.1`**（第 4 可选槽「用户想法」）、**`novel_revise.v1.1`**（意见/问题分流） |
| 追加录音 | 不支持（再录会新建梦或走整段替换） | **同文件尾部追加 + 只转写尾段 + 水位幂等 + 启动补跑** |
| 小说化触发 | 生成入口自动出现，无「想法」输入 | **必须用户主动点「小说化」→ 可选想法 → 生成** |
| 成本可见性 | 无 | 小说 tab 成本行 `累计 ¥x.xxxx`（0 请求不显示） |
| 生成作用域 | ViewModel（离开即取消） | **容器 `generationScope` + `GenerationTracker`**（离开再回来可见终态） |
| 偏差 | D275–D414 | **D415–D459**（45 条，全部写入计划 §9） |
| 提交 | — | 14 个（见 §二） |

## 二、任务表（T0–T7）

| 任务 | 交付 commit | 证据 | 结论 |
| --- | --- | --- | --- |
| T0 契约冻结 | `724d13e` + 复核 `71cd30b`/`12315e1`/`a6ae177`/`2aaf300`/`cf6a8b6` | 计划 §0.1–§0.12、`lead-decisions.md` | ✅ |
| T1 DB v3 + 追加 CAS | `9c2f890`、`f1768fe`（save 水位修复） | `p4-mig-00..02`、`p4-append-00..02`、`p4-full` | ✅ 41→42 类 / 380→382 例 |
| T2 尾段转写 + 补跑 | `db3dd3f` | `p4-append-03` | ✅ 44 类 / 395 例 |
| T3 忠实稿基座 | `e0a3a71` | `p4-faithful-00` | ✅ 44 类 / 399 例 |
| T4「补一段」入口 | `560427d` | `p4-append-04..06` | ✅ 45 类 / 405 例 |
| T5 小说化显式触发 | `5e02d2e` | `p4-novel-00..02` | ✅ 46 类 / 409 例 |
| T6a 版本线/列表 | `6671bd0` | `p4-ui-00..02` | ✅ 48 类 / 413 例 |
| T6b 成本行/app-scope | `6a3444a` | `p4-ui-03..05` | ✅ 49 类 / 418 例 |
| T7 发布就绪/终验 | 本节提交 | `p4-release-01..02`、`p4-api26-01` | ✅（含 PENDING 白名单） |

## 三、用户需求对照

### 需求 A：随时语音在「第一模式文本」尾部追加，再继续整理
1. 入口：详情页「原样」tab 底部常驻「补一段」（`p4-append-05`），不经过 30s FollowUp。
2. 录音：复用录音服务同一 WAV 文件尾部追加（`WavStore.markOpen` / `createdThisTake=false`），
   不再新建梦；设备实测 `WAV 140844 B = 44 + 4400×32`，`rawAudioMs 2400→4400`。
3. 转写：只把**新增尾段**切片送 STT（`stt/TailWav`），不会把旧段重复拼进正文；
   写回用 `rawTranscript = rawTranscript || suffix` 的 CAS（`transcriptMs` 水位），重复跑不重复拼。
4. 整理：追加转写追平后自动重新整理一次（`p4-append-06` 的自动链），并写新的 faithful 版本。
5. 崩溃/失败：`rawAudioMs > transcriptMs` 即「有没转的尾巴」，启动 reconcile 补跑；
   详情页给「重试转写 / 这段不用转写」；手改正文后追加不覆盖（JVM 用例）。
6. **未验证**：模拟器无中文离线 STT → 追加段真实转写成功与「整理稿出现新版本」只能到真机
   （PENDING，见 §五）。

### 需求 B：小说化必须用户主动触发，可先写想法
1. 「原样整理」不再自动接小说化（`NovelIdeaTest` 第 3 例：忠实链只产生 faithful 调用）。
2. 「小说化」按钮常驻（可带新想法重生成一版），点击先弹 `NovelIdeaDialog`：
   「想怎么改？可以不写，直接生成。」200 字、空想法直接生成（`p4-novel-01-dialog`）。
3. 想法进 `novel.v2.1` 第 4 可选槽（`cc497720`），为空时整对删除、等同 v2。
4. 生成基底修正：小说化以**整理稿**为底本（`dreams.faithfulText` 由忠实链同事务写入），
   原始转写与整理稿分两槽，不再同值（T3）。
5. **未验证**：想法非空的中文输入与 D408 路由（意图分流）的设备验证（无中文 IME，
   规则变更由 `novel_revise.v1.1` + 单测/渲染对比覆盖；记 PENDING）。

## 四、偏差与证据纪律

- 45 条偏差全部有 D 号并入计划 §9：D415–D459（T0 契约 5 条、T1 12 条、T2 4 条、T3 4 条、
  T4 9 条、T5 4 条、T6a 4 条、T6b 5 条）。
- 所有 host-only 证据头三行齐备；设备类证据带 `adb shell date`、PNG sha256、nearWhite；
  Key 在证据与 REPLAY 中命中 0（`p4-append-05/06`、`p4-novel-02` 的 grep 记录）。
- 新增脚本只扩既有：`check-migrate.py` 增 `diff_2_3`（v1→v2 输出逐字不变）、无新依赖。
- 证据 txt 经 `.gitattributes` 固定 `eol=lf`、`*.db binary`、`*.png binary`（`2aaf300`）。

## 五、PENDING 白名单（只收环境限制，实现缺陷不算）

| # | 项 | 为什么 PENDING | 补齐条件 |
| --- | --- | --- | --- |
| P1 | 追加段中文 STT 成功 | 模拟器无中文离线识别包；FakeEngine JVM 已覆盖切段/写回语义 | 真机（系统识别可用） |
| P2 | 真机 5 项：STT 中文、长录音（上限/成本）、Key 保存、导出打开、通知/音量键 | 无物理设备 | 真机清单逐项跑 |
| P3 | release 全新安装的种子行为 | release 不可 `run-as`，卸载会丢本机证据库 | 真机/独立测试机 |
| P4 | API 26–28 导出门禁 | SDK 只有 `android-37.0` 镜像 | 装 API 28 镜像建 AVD |
| P5 | txt/docx/pdf 打开态 | AOSP 无 PDF/Office 查看器（仅 htmlviewer） | 真机装查看器 |
| P6 | D408 路由（意见 vs 提问）设备验证 | 无中文 IME，无法在设备输入指令 | 真机中文输入 |
| P7 | 进程被杀后的 Running 恢复 | `GenerationTracker` 是内存态，进程重启归 Idle | Plan 5 评估是否落库 |
| P8 | 追加与生成并发（服务层/多页面） | VM 层互斥有单测；跨进程路径未测 | Plan 5 压力测试 |
| P9 | 长录音上限（4h ≈ 460MB）与成本线性 | 需求未定上限，设备未跑长录音 | Plan 5 产品决策 |

## 六、复跑口径（第三方可复现）

```bash
# 全量（约 55s）
cd android && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
  ./gradlew --no-daemon :app:testDebugUnitTest --rerun-tasks --console=plain

# v2→v3 迁移口径（v1→v2 输出必须逐字不变）
python scripts/check-migrate.py --diff <v2-dump.json> <v3-dump.json>
python scripts/check-migrate.py --diff docs/evidence/plan3/p3-mig-01-baseline.json docs/evidence/plan3/p3-mig-02-after.json
```

## 七、风险结论（R1–R5）

- **R1 DB v3 不可逆**：单列迁移 + 回填口径可复算；降级仍会崩（Plan 3 D 已证），发布前备份
  由 `DbBackup` 泛化到任意旧版本快照（T1 完成，D421）。
- **R2 §14.1 音频预算归属**：按 D415 记「Plan 2 只证音频侧、不判绿」，未补测。
- **R3 评测集未冻结**：真实梦 0 条、无 Key 批量；提示词质量按 D417 记「未评」，只给规则/单测。
- **R4 环境缺口**：见 §五 P1–P9，全部收在白名单，不冒充 PASS。
- **R5 T4/T5 早于基座返工**：实际顺序为 T1→T2→T3→T4→T5，未发生；T3 基座先行是本计划与
  Shepherd 复核共同定的顺序。

## 八、结论

**Plan 4 通过**（含 §五 PENDING 白名单）。用户两条需求均已落地到可验证代码与证据；
剩余未证项全部是环境/真机限制，不是已知实现缺陷。下一步 Plan 5（UI 打磨/生图/真机/发布）。
