# FINAL-REVIEW — Plan 3 · Task 10（导出与生成链端到端验收）

> 基线：`2fa4538`（Task 9b/9c 收尾）。唯一 E2E APK：debug，sha256 `8de9a0b46c44361417de79db0677e628adddc311377405ce200fec33b03d27b8`，设备 emulator-5554（1080x2400 @420dpi，Android 17 / sdk 37）。
> 设备时间线：`docs/evidence/plan3/p3-e2e-00-timeline.txt`（E2E_START=2026-10-31T01:05:08+08:00 / E2E_END=01:29:37+08:00）。
> 证据头三行规则：host-only 用 `APK_SHA256=n/a (host-only JVM test; no APK involved)` / `DEVICE_TIME=host-only <UTC>` / `REPLAY=<命令>`；设备证据用真实 APK sha、`adb shell date`、真实命令。

## 一、结论

- Phase A（导出实现 + JVM 测试 + 三变异 + 两脚本）：**GREEN**。全量 `classes=39 tests=362 failures=0 errors=0`（`p3-export-03-full.txt`）；红基线 8/12 失败（`p3-export-00-red.txt`，ExportNamingTest 在空壳阶段即绿、可证伪性由变异 3 补足）；三处变异各≥1 红且恢复后复绿（`p3-export-02-mutation.txt`）；`check-export.py --self-test`、`check-cost.py --self-test`/`--vectors` 全绿（`p3-export-04/05`）。
- Phase B（设备端到端 0–13 步）：**GREEN（含 2 条实现口径修正 + A5 打开态 PENDING + A14 近白 2 像素偏差候选）**。生成链 T0 真实入库、五闸门在真实产物上通过、版本线/选中/ASK 可复现、三格式导出并双侧 sha 相等、失败行记账与 logcat 对账一致、DB 三件合并后入库、三脚本对同一批文件 GREEN。
- 未闭环项只有环境限制型 PENDING（见 §五）：设备无 txt/docx 查看器、模拟器 PDF 查看器黑屏、无 API 26–28 镜像、release 未构建、STT 未使用（种子梦）。
- 一个需要 Lead 裁定的偏差候选：A14 截图 `p3-e2e-10-fontscale.png` 在严格近白闸门（`-MaxRatio 0`）下为 `nearWhite=2 ratio=0.0029%`，2 像素是系统输入法「隐藏键盘」chevron（(102,2332)/(108,2338)），App 区域 nearWhite=0。其余截图全部 nearWhite=0。

## 二、交付物与提交链

| 环节 | commit | 内容 |
|---|---|---|
| 基线 | `2fa4538` | Task 9b/9c 收尾（生成入口可见性口径修正 D394 + 9b 验收回填） |
| Task 10 代码 | `7a7e5d4` | export/ 四文件（Exporter/TxtExporter/DocxExporter/PdfExporter）、`ExportSheet.kt`、`DetailViewModel` 导出动作、`DetailScreen` 两处入口、`ChatModels.ChatRequest.kind`、`DeepSeekClient` DKLLM 日志、两 orchestrator 的 kind 透传、三个 export 测试类、`scripts/check-export.py`（新）、`scripts/check-cost.py`（D337/D366） |
| Task 10 Phase A 证据 | `97d841e` | `p3-export-00..05` |
| Task 10 Phase B 代码修正 + 证据 | 本次提交 | `scripts/check-export.py` PDF 字体锚兼容 Type3（偏差候选）；`docs/evidence/plan3/` 的 `p3-e2e-*` 与导出原件；`ACCEPTANCE.md` 的 `## 3d`；`PROGRESS.md`；本文件 |

- APK：`.scratch/e2e-apk/base.apk`（设备 pull），双侧 sha 相等 `8de9a0b4…d27b8`；`aapt2 badging`：`package: name='com.dreamkeeper' versionCode='1' versionName='0.1.0' compileSdkVersion='37'`、label「梦匣」、`launchable-activity: com.dreamkeeper.MainActivity`（`p3-e2e-01-install.txt`）。
- 导出原件（原文入库、D378 豁免命名/头三行）：`docs/evidence/plan3/20261008-教室-v4.{txt,docx,pdf}`；sha 见 `p3-e2e-06-export.txt` 的 `EXPORT_SHA256=`。

## 三、证据索引（文件 ↔ 断言）与 A1–A24 结论

| 断言 | 证据文件 | 关键原始值 | 结论 |
|---|---|---|---|
| A1 生成链 T0 真实入库 | `p3-e2e-00-timeline.txt`、`p3-e2e-09-check-cost.txt`、`p3-e2e-12-logcat.txt` | DKLLM 4 条 ok（FAITHFUL v1/v2、NOVEL v3、sd4 NOVEL v1）；llm_calls 14 行 | PASS |
| A2 五闸门全绿 | `p3-e2e-11-faithful.txt`、全量 JVM `p3-export-03-full.txt` | faithful：G1=0/G2=1.0/G3=1.0/G4=0/G5=0；revise：G1=0/G2=1.0093/G3=1.0/G4=0/G5=1；T2/T3 由 GenerationOrchestratorTest #4/#5 覆盖 | PASS |
| A3 版本线/选中/撤回 | `p3-e2e-03-revise.txt`、`p3-e2e-05-selected.txt`、`p3-e2e-08-db-sidecar.txt` | v1–v7；`当前 v4 · 已选中`；`novelSelectedVersion=4`；sd4 v1 | PASS |
| A4 ASK 不建版 | `p3-e2e-04-ask.txt` | ASK 前后当前版本均 v7；`novel_messages.versionId=NULL` | PASS（另见偏差候选：3 次纯提问被模型误路由为 REVISE 并建版） |
| A5 导出文件可打开 | `p3-export-04-check-export.txt`（Phase B 段）、`p3-e2e-07-viewer-probe.txt`、`p3-e2e-07-pdf-open.txt` | pdf 结构 GREEN；txt/docx 查看器 `No activities found`；HTML Viewer `net::ERR_FILE_NOT_FOUND`；PDF 查看器黑屏 | pdf 结构 PASS；设备打开态 PENDING（环境限制，见 §五） |
| A6 成本复算 | `p3-e2e-09-check-cost.txt` | `rows=14 mismatches=0 failed=10` exit 0；负向副本改 costMicroYuan=1 → `mismatches=1` exit 1，原库 sha 未变 | PASS |
| A7 失败行记账 | `p3-e2e-09-failed.txt`、`p3-e2e-09-check-cost.txt`、`p3-e2e-12-logcat.txt` | 本轮 8 条 `[FAILED] none NETWORK cost=0 usage=NULL`；logcat failed 8；飞行模式造失败；D316 四 token/cost 断言 | PASS |
| A8 提示词指纹 | `p3-e2e-14-misc-checks.txt` | faithful sha `5b9702ee…`、novel `f189dad8…`、novel_revise `2ebba630…`，同 promptId 一致 | PASS |
| A9 Key 安全 | `p3-e2e-12-logcat.txt` | DKKey 14 行全部 `load: 已配置`；Key 形态 grep 0；证据/REPLAY 无 Key | PASS |
| A10 无新依赖 | `p3-e2e-14-misc-checks.txt` | `git diff --stat 2fa4538..HEAD -- build.gradle.kts/libs.versions.toml` 为空 | PASS |
| A11 schema 不变 | `p3-e2e-08-db-sidecar.txt` | user_version=2；9 表（8 @Entity + room_master_table，排除 android_metadata）；integrity=ok | PASS |
| A12 列表页仍显示原样稿 | `p3-e2e-14-misc-checks.txt` | 重启后 10/8 卡片正文 `教室最后一排，黑板上的字在动，像水。` | PASS |
| A13 文件名清洗 | `p3-e2e-06-export.txt`、ExportNamingTest（全量） | `20261008-教室-v4.{txt,docx,pdf}`；清洗/空白折叠/码点截 20/空→dream 全绿 | PASS |
| A14 大字体 | `p3-e2e-10-fontscale.txt`、`p3-e2e-13-near-white.txt` | font_scale=1.3；mInputShown=true；输入条在键盘上方可见；nearWhite=2（IME chevron，App 区域 0） | PASS（近白例外待 Lead 裁定） |
| A15 logcat FATAL 0 | `p3-e2e-12-logcat.txt` | `FATAL_EXCEPTION_COUNT=0` | PASS |
| A16 证据头/sha 可复算 | 本目录全部 `p3-e2e-*`/`p3-export-*` | 设备证据 APK sha 同一；host-only 三行齐；PNG 同名 txt 带 PNG_SHA256 | PASS |
| A17 复跑命令可执行 | 各证据 REPLAY 行 | 见 §六 | PASS |
| A18 PENDING 白名单 | 本文件 §五 | 仅环境限制；无实现缺陷 PENDING | PASS |
| A19 §9 偏差有号 | 本文件 §四 | 既有 D275–D406 与本轮候选（待 Lead 编号） | PARTIAL（候选待编号） |
| A20 看板与验收段 | `docs/PROGRESS.md`、`docs/evidence/plan1/ACCEPTANCE.md` | Task 10 10/10 + `## 3d` 表 | PASS |
| A21 第三方可复现 | 本文件 + 证据 REPLAY | 从仓库证据即可复跑三脚本 | PASS |
| A22 生成入口可点/可重试 | `p3-e2e-09-failed.txt`、`p3-e2e-09b-retry.txt` | 失败态「网络没通」+重试仍失败+联网成功；novel_versions 行数未因失败增加 | PASS |
| A23 降级/兜底链证据 | `p3-e2e-14-misc-checks.txt` | jobs 8 ↔ llm_calls 14 的 attempt 对账；T2/T3 JVM；STT 未用 → PENDING | PASS（STT PENDING） |
| A24 API 下界 | `p3-e2e-06-export.txt`、`p3-e2e-14-misc-checks.txt` | sdk=37（API29+）三格式 GREEN；API26–28 无镜像 | PARTIAL（下界 PENDING） |

## 四、偏差索引（D275–D406 + 本轮候选）

已入册（Lead 已编号，沿用）：
- D337/D338–D343、D357–D379、D392、D405、D406 —— 见计划 §9；本轮按字面执行并在证据中引用。
- 表数口径（实现口径修正，**非偏差**）：Room 实体表 8 + `room_master_table` = 9；`android_metadata`/`sqlite_*` 排除（Lead m00550 已确认）。

本轮**待 Lead 编号**的偏差候选（报告与证据中亦称「偏差候选」）：
1. **纯提问被误路由为 REVISE 并建版**：`Why does the classroom feel so quiet?`→v5、`What does the paper with my name mean? …`→v6、`the paper?`→v7（`p3-e2e-04-ask.txt`）。模型行为，非 Task 10 实现缺陷；未删行。
2. **计划字面「选 v2」改为选 v4**：v2 是 D406 误双击「原样整理」的忠实稿，v4 是真实改稿版；导出 -v4 与库内 `novelSelectedVersion=4` 一致（`p3-e2e-05-selected.txt`、`p3-e2e-06-export.txt` SELECT_RAW）。
3. **PDF 字体锚**：计划 §0.5/D374 要求 `/BaseFont`，设备 `PdfDocument` 将 CJK 字形嵌入为 Type3（62 处 `/Subtype /Type3` + `/FontMatrix`，无 `/BaseFont`）；`check-export.py` 的 pdf 字体锚断言改为「/BaseFont 或 Type3（/Type /Font + /Subtype /Type3 + /FontMatrix）」，其余 pdf 断言不变（`p3-export-04-check-export.txt` Phase B 段）。
4. **A14 近白例外**：`p3-e2e-10-fontscale.png` 严格闸门下 nearWhite=2（ratio 0.0029%），为系统输入法隐藏键盘 chevron 2 像素；App 区域 0。
5. **Phase A 自测设计**：`check-export.py` 增加 `--sidecar`（默认指向 `p3-e2e-08-db-sidecar.txt`）与 `--self-test` 夹具，用于 Phase A 无设备时离线复跑；不改变设备侧断言（`p3-export-04`）。
6. **docx 首段文案**：实现为 `# <title>`（与 txt 头部一致），计划 §0.5 字面写「首段 == dream.title」；check-export 按「首段 == 头部第 1 行」断言。
7. **ExportSheet 形态**：用 Material3 `AlertDialog`（title「导出」+ 三按钮 + 关闭），计划称 sheet；交互与文案契约一致，未引底部 sheet 组件。
8. **ExportNamingTest 在空壳阶段即绿**：红基线为 8/12（Txt 4 + Docx 4），命名/日期实现先落；可证伪性由变异 3 证明（`p3-export-00/02`）。

## 五、PENDING 与理由

1. **A5 txt/docx 设备打开态**：模拟器镜像无 txt/docx 查看器（`query-activities` No activities found）；HTML Viewer 不认 `content://`（`net::ERR_FILE_NOT_FOUND`，白底错误页 nearWhite 0.1056%，未入库）。环境限制，非实现缺陷；pdf 结构级 GREEN，pdf 打开态另因模拟器 PDF 查看器黑屏 PENDING（D359 排除 nearWhite）。
2. **A24 API 26–28 下界**：本机仅有 sdk 37 模拟器，无 API26–28 镜像，无法验证「三按钮禁用 + 系统版本过低文案」；API29+ 三格式已实测。
3. **release 构建未验证**：本轮 E2E 只装 debug APK（`SEED_DEMO_DATA=true`），release 未构建、未安装（计划与 dispatch 未要求装 release）。
4. **STT 未使用**：E2E 使用已有种子梦（D364 允许），未走录音→转写；STT 仍按 D364 记 PENDING。
5. **A19 偏差编号**：§四 的 8 条候选等 Lead 分配 D 号后回填；`p3-e2e-12-visibility.png`（seed-dream-2 只显示「小说化」）需新 APK（D406），本轮不拍。

## 六、复跑命令

- 全量 JVM：`cd android && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest --rerun-tasks --console=plain` → 期望 `classes=39 tests=362 failures=0`（XML 聚合见 `p3-export-03-full.txt`）。
- 导出单包：`./gradlew testDebugUnitTest --tests "com.dreamkeeper.export.*" --console=plain`。
- 成本：`python scripts/check-cost.py --self-test`；`python scripts/check-cost.py --db-copy docs/evidence/plan3/p3-e2e-08-db.db --require-rows` → `rows=14 mismatches=0 failed=10`；负向在 `.scratch/` 副本改一行 cost → 必 RED。
- 导出三格式：`python scripts/check-export.py --txt docs/evidence/plan3/20261008-教室-v4.txt --file-name 20261008-教室-v4.txt --from-db docs/evidence/plan3/p3-e2e-08-db.db --dream-id seed-dream-2 --mutation`；`--docx` / `--pdf` 同理；`python scripts/check-export.py --self-test`。
- 五闸门：`python scripts/check-faithful.py --input .scratch/faithful-in-raw.txt --output .scratch/faithful-v1.txt`；`--kind revise --input .scratch/faithful-v3.txt --output .scratch/faithful-v4.txt`（DB 提取命令见 `p3-e2e-11-faithful.txt`）。
- 近白：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-near-white.ps1 -Path "docs/evidence/plan3/*.png" -TopOffset 100 -MaxRatio 0`（fontscale 例外见 §四.4）。
- DB 校验：`sha256sum docs/evidence/plan3/p3-e2e-08-db.db` 应等于 sidecar 的 `MERGED_SHA256=ed62b2bd…466cc`；`sqlite3` 打开后 `PRAGMA integrity_check`/`user_version`。
- DKLLM 对账：`adb logcat -d | grep "DKLLM attempt" | grep "status=failed" | grep -E "kind=(FAITHFUL|NOVEL)" | wc -l`（本轮 8）。
