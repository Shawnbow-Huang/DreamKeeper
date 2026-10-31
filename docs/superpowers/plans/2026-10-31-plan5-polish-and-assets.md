# Plan 5：视觉资产与发布就绪 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用离线生成的静态美术资产（App 图标、四张空态插画、一张页头纹理）建立统一视觉气质，配一轮低风险 UI 打磨，并把工程推到「可签名 release + 可侧载」的成品状态；真机项保持 PENDING。

**Architecture:** 不引入任何运行时图像依赖。资产由 DSH 生图工具（seedream）离线生成母版 PNG，用 Pillow 压成 WebP 放进 `res/drawable-nodpi`，由 Compose `painterResource` 引用；图标走 adaptive icon + monochrome + Android 12 splash；发布用本机 keystore（不入库）+ `keystore.properties`（gitignore）条件签名。

**Tech Stack:** Kotlin 2.x / Jetpack Compose / Room / Android Gradle Plugin 9.5 / Pillow（仅构建机，进 APK 的没有它）。

**Branch:** `plan5-polish`（从 `plan4-voice-append` 的 `2735d57` 切出）。基线：**49 类 / 418 例 / 0 失败**，schema v3，提示词 faithful.v2 / novel.v2.1 / novel_revise.v1.1。

## Global Constraints

- G-1 构建前 `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`；adb 用绝对路径 `C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe`；二进制抓取用 `cmd /c "... exec-out run-as com.dreamkeeper cat <路径> > <本地>"`；本机一律 `./gradlew --no-daemon`（残留 daemon 会锁 `R.jar`，见 Plan 4 教训）。
- G-2 证据落 `docs/evidence/plan5/`，命名 `p5-<case>-<nn>-<state>`；host-only 证据首三行 `HOST_TIME=<UTC>` / `DEVICE_TIME=n/a` / `REPLAY=<命令>`，设备证据首三行 `APK_SHA256=<前16>` / `DEVICE_TIME=<设备 date 原文>` / `REPLAY=<步骤>`；证据目录与 REPLAY 里 Key 命中必须为 0。
- G-3 **零新依赖**：不加 Coil/Glide/Lottie/图片加载库；Compose 自带 `painterResource` + `Image` 足够。不加 R8/proguard 规则文件（minify 保持关闭）。
- G-4 资产必须离线、静态、可复现：母版 PNG + 生成提示词 + 两侧 sha256 全部入库；图内**不许出现文字/字母/水印/真人脸**；不许用网络图片。
- G-5 调色板锁定：底色 `Night #0B0E13`、抬升层 `NightRaised #12161F`、正文 `Paper #E6EAF2`、唯一强调色 `Ember #C8873F`。插画整体低亮度（均值亮度 ≤ 18%），近白像素（RGB 全部 ≥ 250）占比 ≤ 0.5%，深色主题下不刺眼。
- G-6 体积预算：单张 WebP ≤ 120,000 B，六张合计 ≤ 400,000 B；APK 相对 Plan 4 debug 包增量 ≤ 500 KB；以 `unzip -l` 实测为准。
- G-7 图标规格：adaptive icon 前景画布 108dp、安全区 66dp（文字/主体不得越界）；必须同时给 `monochrome` 层；`android:roundIcon` 同源；Android 12 splash 用同一前景图 + `#0B0E13` 底色。
- G-8 测试：JUnit4 纯 JVM（资产目录测试不需要 Robolectric）；先红后绿，每新测试类至少一次变异验证；期望基线 49 类/418 例 → 终态 **52 类/440 例**（逐任务对账，实测为准）。
- G-9 提交逐路径 `git add`，禁 `git add -A`；偏差编号从 **D461** 起，追加进计划 §0.9；实现缺陷不许记 PENDING。
- G-10 release 签名：keystore 与 `keystore.properties` 永不入库（`.gitignore` 已含 `*.jks`/`keystore.properties`）；`RELEASE.md` 只写步骤不写口令；release 包用本机 keystore 签名后装机验证，不宣称「应用商店签名」。

---

## §0.1 范围与不做

**做：**

1. 五件离线静态美术：手写自适应图标前景（vector）+ 四张空态插画（「还没有梦」「这天没有梦」「小说空稿」「素材页视频位」，WebP）；去掉页头纹理（D468）。
2. 图标系统：adaptive icon（前景/背景/monochrome）、round icon、Android 12 splash、旧 `ic_launcher_dark` 下线。
3. 空态组件化：`ui/common/EmptyState.kt`，接入「当日无梦」「全部为空」「小说稿为空」「素材页占位」四处；搜索/标签保持纯文字。
4. **模型与思考档位（需求 #1 补齐）**：设置页模型下拉（flash / v4-pro）+ 思考档位（关/低/高/极高），SharedPreferences 持久化，透传：faithful 恒关、novel 跟随档位（默认高、温度 0.9）、revise 低；写明「思考开启时温度被服务端忽略」（D463）。
5. **可靠性 P0（无真机可验）**：A1 追加后被杀 → 启动扫描把未覆盖尾巴置 PENDING；A2「重试转写」真正触发补跑；A3 生成闸门拒收未覆盖水位；A4 4h 硬上限 + 开录前剩余空间守卫（D465/D469）。
6. 轻量 UI 打磨：空态层级、间距一致性、A11y（装饰图 `contentDescription=null`、触控目标 ≥48dp）、大字体（1.3×）不溢出不遮挡。
7. 发布就绪：`versionCode 2 / versionName 0.9.0`（D464）、`keystore.properties` 条件签名、`docs/RELEASE.md`、`CHANGELOG 0.9.0`、release 构建/签名验证/装机/冷启动/体积证据。
8. 真机清单 `docs/evidence/plan5/p5-device-01-checklist.md`：Plan 4 P1–P9 可执行化，等设备到位一次性跑。

**不做（明确推迟）：**

- 不在 App 内接入任何图像模型（用户 2026-10-31 明确：生图模型 = 用 DSH 工具产资源）。
- 不做视频、iOS、账号、云同步、多端；不做锁屏磁贴/通知/常亮/夜间/完整设置页/回收站（Plan 5 之外的 D416 推迟项）。
- 不改数据模型（无需 v4 迁移）、不改提示词文本、不改生成链逻辑。
- 不做真机验收（无设备，PENDING 按白名单记录，不算实现缺陷）。

## §0.2 资产管线（生成 → 归一化 → WebP → 资源）

1. 生成：DSH `generate_image`（seedream 通道）出 1024 级母版；统一骨架：
   `哑光、低对比、为深色界面而作的静态插画；近黑 #0B0E13 底占画面 ≥50%，留白是纯色暗面；限定四色：夜底 #0B0E13、冷灰 #1A202B、雾灰 #5B6474、琥珀 #C8873F（琥珀≤5% 面积、不发光）；纸纹颗粒与木刻触感可见；主体偏置、不对称；单个画面只允许一个超现实元素；禁止文字/字母/水印/人脸/人手；禁止月亮、星星、梦网、蝴蝶、眼睛、时钟、漂浮岛、发光粒子、霓虹、镜头光晕、彩虹渐变、纯白 #FFFFFF` + 每个资产一句场景。
2. 母版入库：`docs/art/plan5/<name>.png`（原始字节）+ `docs/art/plan5/prompts.md`（骨架 + 场景句 + 母版 sha256）；被拒候选不入库，只在证据里记数量与理由。
3. 归一化（离线 Pillow，脚本 `.scratch/prep_art.py`，不入仓库）：角落取色与 `#0B0E13` 距离 ≤6 断言 → 亮度均值 ≤0.06（相对亮度 Y）→ 长边 720 降采样 → WebP q80 method=6 保 alpha。
4. 门槛（逐张，人工+脚本）：近白（RGB 全 ≥245）占比 ≤0.1%、全 ≥250 占比 0；Y 均值 ≤0.06、P99 ≤0.35；AI 味清单 12 项 0 fail（无梦境陈词/无人形/无伪文字/无 3D 高光/无粒子）。任一不达标重生成，不做局部修图。
5. 落点：`android/app/src/main/res/drawable-nodpi/illus_<name>.webp`；Compose 端 `painterResource` + `ContentScale.Fit`，不引 Coil。
6. 命名与体积：`illus_library_empty` / `illus_day_empty` / `illus_novel_empty` / `illus_media`；单张 ≤120,000 B、合计 ≤400,000 B。

## §0.3 图标与启动屏规格

**图标方案（D468）**：手写 vector，不用 raster —— 概念「余烬」：中心偏下一枚琥珀余烬点 + 一圈不闭合、略抖动的手刻弧线；不用月牙/梦网/眼睛；只用 `#0B0E13`/`#C8873F`/`#8C3B34`。

| 文件 | 内容 | 引用处 |
| --- | --- | --- |
| `res/drawable/ic_launcher_foreground.xml` | 108dp 矢量，主体落中心 66dp 安全区，透明底 | adaptive foreground / monochrome / splash |
| `res/values/colors.xml`（新增 `ic_launcher_background = #0B0E13`） | 背景色 | adaptive background |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | `<adaptive-icon>` 三层（background/foreground/monochrome） | Manifest `android:icon` |
| `res/mipmap-anydpi-v26/ic_launcher_round.xml` | 同上 | Manifest `android:roundIcon` |
| `res/mipmap-anydpi-v33/ic_launcher.xml` | 同上 + `<monochrome>`（透明底单色剪影） | Android 13 主题图标 |
| `values-v31/themes.xml` | `windowSplashScreenAnimatedIcon=@mipmap/ic_launcher`，背景 `#0B0E13` | 冷启动 |
| 旧 `res/drawable/ic_launcher_dark.xml` | 保留但不再被 Manifest 引用（兜底注释） | — |

Manifest 改：`android:icon="@mipmap/ic_launcher"`、`android:roundIcon="@mipmap/ic_launcher_round"`。验收：`aapt2 dump badging` 含 mipmap/anydpi-v26；桌面 48dp/108dp 截图；splash 冷启无白闪。

## §0.4 空态插画规格

| 资源 | 画布 | 场景提示词（追加在 §0.2 骨架上） | 用在哪 |
| --- | --- | --- | --- |
| `illus_day_empty` | 720×480 | 「一扇没有月亮的夜窗，玻璃蒙着雾，窗台一只旧搪瓷杯，杯沿一线极淡琥珀反光；室内只有窗框轮廓」 | 当日页无梦 |
| `illus_library_empty` | 720×480 | 「暗桌上几张空白厚纸卡片，边角微卷，纸面无字只有纸纹与压痕，最上面一张散开一小片灰雾」 | 全部页为空 |
| `illus_novel_empty` | 720×480 | 「一本摊开的空白笔记本，纸上只有几道将散未散的墨线，一支旧钢笔横在旁边，笔尖一点琥珀反光」 | 小说稿为空 |
| `illus_media` | 640×360 | 「昏暗房间墙上一个极简画框，框内只有一片夜雾与一道几乎看不见的琥珀弧光，无内容无文字」 | 素材页视频位 |

空态结构：插画（`heightIn(max=160.dp)`、`ContentScale.Fit`、`alpha 0.92`、`contentDescription=null`）→ 24dp → 标题（`bodyLarge`/`Paper`）→ 6dp → 提示（`bodyMedium`/`InkFaint`，可空）；整块垂直居中、左右 16dp；只在 `loaded && empty` 渲染（防首帧闪）；搜索/标签保持纯文字。

## §0.5 UI 打磨清单（只做低风险项）

1. `ui/common/EmptyState.kt` 统一四处空态；插画 `contentDescription = null`（装饰），标题与提示进读屏。
2. 小说 tab：空态插画放在「小说化」按钮下方，按钮仍常驻（T5 契约不变）。
3. 空态组件统一后，图标/插画全部 `contentDescription = null`，文字仍由 Compose 渲染（不烘焙进图）。
4. 卡片/段落间距复核：`DreamCard` 内 8/12dp、正文段距 12dp、空态上下留白 24dp；不改字号（17/30 已定）。
5. A11y 审计：所有可点元素 ≥48dp、装饰图 `contentDescription=null`、`Icons` 有语义；用 grep + 一张 1.3× 字号截图证明不溢出。
6. 动效沿用 `DreamMotion`（≤200ms），不新增动画。

## §0.6 发布就绪

1. `android/app/build.gradle.kts`：`versionCode = 2`、`versionName = "0.9.0"`；在 `android {}` 前读 `keystore.properties`（存在才启用 release signingConfig）。
2. 本机生成 keystore：`keytool -genkeypair -v -keystore <repo>/.keys/dreamkeeper.jks -alias dreamkeeper -keyalg RSA -keysize 2048 -validity 10000`；口令写入 `android/keystore.properties`（已 gitignore）。
3. `docs/RELEASE.md`：签名生成、环境变量、构建命令、`apksigner verify --print-certs`、`adb install -r`、与 debug 包体积对照；写「本签名仅供自用侧载」。
4. `CHANGELOG.md` 新增 `0.9.0`：语音追加、显式小说化+想法、成本行、导出三格式、v3 迁移。
5. 构建 `:app:assembleRelease` → 用 `apksigner sign`（若 build.gradle 已配则直接用签名产物）→ 装机 → 冷启动 ×3 → `FATAL EXCEPTION` 0 → 包内资产清单。

## §0.7 证据与测试口径

证据（全部进 `docs/evidence/plan5/`）：

- `p5-asset-00-master.txt`：六张母版提示词全文、母版 sha256、WebP sha256、字节数、体积合计。
- `p5-icon-01-icon.png/.txt`、`p5-icon-02-splash.png/.txt`：桌面图标与冷启动截图（含设备 date、APK sha、nearWhite 例外口径）。
- `p5-ui-01-day-empty.png/.txt`、`p5-ui-02-library-empty.*`、`p5-ui-03-search-empty.*`、`p5-ui-04-novel-empty.*`、`p5-ui-05-fontscale.*`：四处空态 + 1.3× 字号。
- `p5-release-01-build.txt`、`p5-release-02-launch.png`、`p5-release-03-size.txt`：签名/构建/装机/冷启动/体积对照/包内资产。
- `p5-tests-00.txt`：先红、后绿、变异原文、全量 XML 统计。
- `p5-device-01-checklist.md`：真机 P1–P9 可执行清单（PENDING 模板）。

测试：新增 `app/src/test/java/com/dreamkeeper/assets/AssetCatalogTest.kt`（4 例，纯 JVM）：

1. 六个文件都存在且以 `RIFF....WEBP` 开头；
2. 单张 ≤ 120,000 B、合计 ≤ 400,000 B；
3. 目录里的 WebP 集合恰好等于清单（防孤儿/漏提交）；
4. 每个资源名都能在 `src/main/java` 的 Kotlin 源里找到 `R.drawable.<name>` 引用（防「生成了但没接」）。

变异：把 `p5_empty_novel.webp` 临时移走 → 用例 1/3 必红；恢复后复绿并逐字节比对 sha。

## §0.8 任务顺序与依赖

T0 契约冻结（Lead）→ T1 资产生成与压缩（Lead，DSH 生图）→ T2 档位 UI 与透传（Lead）→ T3 图标/启动屏（Builder）→ T4 空态组件与接入（Builder）→ T5 可靠性 A1–A4（Lead）→ T6 UI 打磨与 A11y（UI Designer 规格 + Builder 落地）→ T7 发布就绪（Lead）→ T8 终验（Lead + Evidence Collector 复核）。

T3/T4 同改 `res/` 与 Manifest，派工时约定先 T3 后 T4；T2/T5 都改 `llm/`，约定先 T2 后 T5；T4 依赖 T1 的插画；T6 依赖 T4；T7 依赖 T2–T6 全绿。

## §0.9 偏差登记（从 D461 起）
| D484 | 2026-10-31 | T6 | 版本线/快捷 chips 改「外层 48dp 点击区 + 内层 36dp 胶囊」，可视高度不变 | UI Designer 规格：触控 ≥48dp，但不改设计语言 |
| D485 | 2026-10-31 | T6 | 设置页模型 chips `Row` 改 `FlowRow`，1.3× 字号下允许换行而不是挤断 | 1.3× 实测「DeepSeek V4 Pro」被挤成两行，违反「不截断」 |
| D486 | 2026-10-31 | T6 | 设置页新增「关于」卡（版本/本地运行/无账号 + 隐私一句） | 需求 #1 收口 + 发布信息；版本号随 T7 升到 0.9.0 |
| D487 | 2026-10-31 | T6 | A11y 用 uiautomator bounds + 1.3× 截图取证；TalkBack 真机朗读明确未覆盖而非 PENDING | 模拟器无中文 TTS，不能假称通过 |

| D478 | 2026-10-31 | T4 | Lead 直接实施、未先红（纯 UI 组合无逻辑可测）；设备截图推到 T7 release 空库一轮拍 | 空态组件只取材于四张已入库插画，行为由既有 UI 测试覆盖 |
| D479 | 2026-10-31 | T4 | T4 不新增单测；空态正确性由 T7 的 release 空库截图 + 资产目录测试覆盖 | 避免为「无逻辑组件」写假断言 |
| D480 | 2026-10-31 | T5 | A1 修复改变旧语义：占位头被修复且水位未追平时现在置 PENDING（原保持 OK） | 否则被杀的首次录音永远不会补转写；属有意的行为修正 |
| D481 | 2026-10-31 | T5 | 生成闸门分两层：编排器统一文案「转写未完成或原文为空」，详情页给「还有 N 秒…」 | 编排器不依赖 UI 文案，详情页负责可操作性；单测各覆盖一层 |
| D482 | 2026-10-31 | T5 | 计数修正：T1 后实为 51 类/431 例，+12 例 → 52 类/443 例（计划原写 52/441） | 4 限制 + 2 扫描 + 4 CAS + 1 回调 + 1 闸门 = 12 |
| D483 | 2026-10-31 | T4 | `DetailScreen` 的 `onRetryTranscription` 传参随 T4 提交（`dcfd732`）先落，T5 提交只含 VM/服务/DAO 与测试 | 两任务同改一个文件，按先提交者归属 |


| 编号 | 日期 | 任务 | 偏差 | 原因 |
| --- | --- | --- | --- | --- |
| D461 | 2026-10-31 | T0 | 「生图美学」= 离线生成静态美术资源，不是 App 内接入图像模型 | 用户 2026-10-31 当面澄清：要先在 DSH 里生成资源增强 App 美学，暂不在 App 里接图像模型 |
| D462 | 2026-10-31 | T0 | 真机验收整体 PENDING（P1–P9），只交付清单 | 用户回复「目前设备还空不出来，可以先搁置」 |
| D463 | 2026-10-31 | T0 | 需求 #1「模型 + 思考档位」纳入 Plan 5：设置页两组 chips + SharedPreferences 透传 | 需求表 #1 自 Plan 1 起未落地；设计 §9:365-372 要求默认档位，代码 `GenerationOrchestrator.kt:223/:298` 硬编码 DISABLED；Shepherd 判为 T0 遗漏项 |
| D464 | 2026-10-31 | T0 | 版本定为 `versionCode 2 / versionName 0.9.0`，1.0.0 留给真机通过后 | 无真机不得宣称 v1 验收；Shepherd 建议 0.9.0、Builder 建议 0.2.0，取「候选版」语义 |
| D465 | 2026-10-31 | T0 | 长录音守卫：4h 硬上限 + 开录前剩余空间 ≥128 MB；到限自动收尾并提示 | Voice AI 预审 A4；1.92MB/min×240min≈460MB，当前全仓无守卫 |
| D466 | 2026-10-31 | T0 | 云 STT 兜底推迟 Plan 6：本版只有本机 STT，不可用则手动补字；同步改设置文案 | 设计 §8:317-335 曾承诺云端兜底，但「文本只接 DeepSeek」（无 STT）；Voice AI R5 要求三选一 |
| D467 | 2026-10-31 | T0 | 回收站/软删除继续推迟（D416 归 Plan 6） | 涉及 DB v4 与音频保留策略，超出「打磨+发布」范围 |
| D468 | 2026-10-31 | T0 | 资产定为 1 个矢量图标前景 + 4 张空态插画（库/日/小说/素材位），页头纹理不做 | Builder/UI 预审：纹理是 P2 可选项；五处空态点收敛为四处，搜索/标签保持纯文字（低刺激） |
| D469 | 2026-10-31 | T0 | Voice AI 的 A1–A3 列为 Plan 5 P0 实现缺陷（不许 PENDING），A4 最小守卫同期做 | 三者都会让「半截原文」进入生成或尾巴永久丢失，属实现缺陷而非环境限制 |
| D470 | 2026-10-31 | T0 | 任务重排为 T0–T8（原 T0–T6 六任务 → 九任务），测试终态按 52 类/440 例对账 | 新增档位 UI（T2）与可靠性（T5）；原 T1–T6 映射为 T1/T3/T4/T6/T7/T8 |
| D471 | 2026-10-31 | T2 | 改稿（revise）思考档位保持 DISABLED + 0.3/0.0，未按 §0.6/设计「固定 LOW」改；模型仍跟随设置 | `ReviseOrchestratorTest:198-199` 断言 0.3/0.0，改 LOW 会同时废掉两条断言并改写「温度 0.0 重试」的确定性链；本任务只做需求 #1 的档位 UI，改稿采样参数另立评审 |
| D472 | 2026-10-31 | T2 | T2 由 Lead 直接实施、以变异代红；全量基线实为 423（T1 +5），+5 prefs +3 编排器 = 431，不是计划写的 430 | 单任务内 Lead 写实现再补测，先红等价物=变异（默认档位 HIGH→OFF 必红）；计数按 XML 实测对账 |
| D473 | 2026-10-31 | T2 | 设置页「思考开着时忽略 temperature」只保留在「生成」卡内；删掉列表尾重复的「说明」段 | 首版两处重复（卡内 + 独立说明段），截图复核实物后去重 |
| D474 | 2026-10-31 | T2 | 档位持久化用 debug 包 + force-stop/冷启在模拟器取证；截图取证后把档位改回默认 Flash/高 | 证明 UI 与 SharedPreferences；默认档位作为后续生成链验收的已知起点 |
| D475 | 2026-10-31 | T3 | 桌面图标截图不做 nearWhite 门槛，理由写进 sidecar | 截图含系统壁纸（浅色），白像素来自壁纸不是 App；门槛只对 App 自身画面有意义 |
| D476 | 2026-10-31 | T3 | splash 证据取冷启 ~0.3s 的过渡帧；静止 splash 帧改用 T8 release 冷启复现命令补 | 本机无 ffmpeg，screenrecord 抽帧不可用；过渡帧已能证明「深色无白闪 + 新图标」 |
| D477 | 2026-10-31 | T3 | 额外加 `windowSplashScreenIconBackgroundColor=#0B0E13`（计划未写） | 首版 splash 图标浮在透明底上、透出壁纸；补背景色后图标落在 Night 圆底上 |

---

### Task 0: 契约冻结（Lead）

**Goal**：把 D463–D470 的决策、任务重排与测试口径写死；此后任何偏离先进 §0.9 再动代码。

**Files**：本计划、`.superpowers/sdd/2026-10-31-plan5-polish/decisions.md`（不入库）、`docs/evidence/plan5/README.md`。

**Steps**：
1. 写 decisions.md（6 项拍板结论 + 引用专家报告）。
2. 建 `docs/evidence/plan5/` 与 README（命名、头三行、nearWhite 口径 `-TopOffset 100`、复跑命令）。
3. `git add` 具体文件并提交 `docs(plan5): T0 契约冻结（D461–D470）`。

**Expected**：无代码改动；计划任务链 T0→T8 与依赖无环；D470 计数口径与 §0.7 一致。

---

### Task 1: 四张空态插画 + 图标前景资产（Lead）

**Goal**：用 DSH 生图工具离线产出 4 张空态插画（WebP）与手写自适应图标前景（vector），全部入库、可复现、体积达标。

**Files**：
- 母版：`docs/art/plan5/*.png`、`docs/art/plan5/prompts.md`（提示词原文 + 母版 sha256）
- 资源：`android/app/src/main/res/drawable-nodpi/illus_library_empty.webp`、`illus_day_empty.webp`、`illus_novel_empty.webp`、`illus_media_empty.webp`
- 图标：`android/app/src/main/res/drawable/ic_launcher_foreground.xml`、`values/ic_launcher_background.xml`
- 测试：`android/app/src/test/java/com/dreamkeeper/assets/AssetCatalogTest.kt`

**Steps**：
1. 调 `generate_image`：方图、纯 `#0B0E13`（素材位 `#12161F`）底、低对比、无文字/人/纯白；内容各为「半开的门一条琥珀缝」「一张只由细线暗示的空床」「合上的一本书上一点琥珀微光」「极简画框与月影」。
2. Pillow 归一化脚本 `.scratch/prep_art.py`：角落取色断言（距底色 ≤ 6）→ 亮度均值 ≤ 20/255 → 512×512 降采样 → WebP q80 method=6 带 alpha；输出字节数表。
3. 写 `ic_launcher_foreground.xml`（108dp 画布、内容落 66dp 安全区、只用 `#0B0E13`/`#C8873F`/`#8C3B34`）与 `values/ic_launcher_background.xml`（`#0B0E13`）。
4. 写 `AssetCatalogTest`（4 例）：四张 WebP `RIFF....WEBP` 魔数；单张 ≤120,000 B、合计 ≤400,000 B；`drawable-nodpi` 的 WebP 集合恰好等于清单；每个资源名在 `src/main/java` 有 `R.drawable.<name>` 引用。
5. 先写空壳让测试红 → 放资源 → 绿；变异：改名一张资源 → 用例 1/3 必红，恢复后逐字节 cmp 相同再复绿。
6. 证据 `docs/evidence/plan5/p5-asset-00-master.txt`（提示词全文、母版/WebP sha256、字节数、四张真实尺寸）。

**Expected**：4 张 WebP 合计 ≤400 KB；`AssetCatalogTest` 4/4；全量 **50 类/422 例**。

---

### Task 2: 模型与思考档位（需求 #1）（Lead）

**Goal**：设置页可选模型与思考档位，真正透传到生成链；小说化默认「思考高 + 温度 0.9」，忠实整理恒「关」，改稿「低」。

**Files**：
- `android/app/src/main/java/com/dreamkeeper/data/settings/GenerationPrefs.kt`（新）
- `ui/settings/SettingsScreen.kt`、`ui/settings/SettingsViewModel.kt`
- `llm/GenerationOrchestrator.kt`、`llm/ReviseOrchestrator.kt`、`di/AppContainer.kt`
- 测试 `data/settings/GenerationPrefsTest.kt`（新）、`llm/GenerationOrchestratorTest.kt`（3 例追加）

**Steps**：
1. 先写 `GenerationPrefsTest` 5 例：默认 `deepseek-flash` + `HIGH`；写入/读回；非法值回退默认；`thinking` 关闭时 effort 忽略；模型白名单只允许 flash/v4-pro。
2. 实现 `GenerationPrefs`（SharedPreferences，`dk_model`、`dk_thinking`；不碰 ApiKeyStore、不引依赖）。
3. 设置页加两组 chips（模型：`deepseek-flash` / `deepseek-v4-pro`；思考：关/低/高/极高）+ 说明「思考开启时温度设置不会生效」+ 现值回显。
4. 编排器：`faithful` 恒 `Thinking.DISABLED` + 0.1；`novel` 跟随档位（默认 HIGH、温度 0.9，思考开时服务端忽略温度，注释说明）；`revise` 固定 LOW + 0.3 但模型跟随设置；`AppContainer` 构造时读 prefs 注入。
5. 追加 3 例编排器测试：novel 请求体 `thinking.type=enabled` 且 `reasoning_effort` 与档位一致；faithful 请求体恒 `disabled`；改模型后请求体 `model` 变化。
6. 证据 `docs/evidence/plan5/p5-settings-00-tiers.txt`（先红/后绿/变异/全量）。

**Expected**：`GenerationPrefsTest` 5/5；全量 **51 类/431 例**（D472 实测对账）。

---

### Task 3: 图标系统与启动屏（Mobile App Builder）

**Goal**：自适应图标（前景/背景/`monochrome`）+ `roundIcon` + Android 12 splash，废弃 `ic_launcher_dark` 作为 launcher 入口。

**Files**：`res/mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml`、`res/mipmap-anydpi-v33/ic_launcher.xml`（monochrome）、`AndroidManifest.xml`、`values-v31/themes.xml`。

**Steps**：建 adaptive icon（背景 `@color/ic_launcher_background`、前景 Task 1 的 vector、`<monochrome>` 同前景）；Manifest 改 `android:icon="@mipmap/ic_launcher"` + `android:roundIcon`；splash 的 `windowSplashScreenAnimatedIcon` 指向同一 mipmap；`ic_launcher_dark.xml` 保留但不再被 Manifest 引用（注释说明兜底）。
**Evidence**：`p5-icon-01-icon.png`（桌面图标 48dp/108dp 同屏）、`p5-icon-02-splash.png`（冷启首帧）、`p5-icon-03-badging.txt`（`aapt2 dump badging` 含 mipmap/anydpi-v26/roundIcon）。
**Expected**：模拟器 API 37 上图标不受遮罩裁切、splash 无白闪；全量不退化（51 类/431 例，D472 对账）。

---

### Task 4: 空态组件与四处接入（Mobile App Builder）

**Goal**：把空态统一为「插画 + 主文案 + 副文案」组件，且只在数据加载完成后渲染（不闪）。

**Files**：`ui/common/EmptyState.kt`（新）、`ui/library/LibraryScreen.kt`、`ui/day/DayScreen.kt`、`ui/detail/DetailScreen.kt`（NOVEL_EMPTY 与素材 tab）、`ui/search/SearchScreen.kt`、`ui/tags/TagsScreen.kt`（后两者保持纯文字，只复用文案样式）。

**Steps**：`EmptyState(illustration: Painter?, title, hint)` 居中列；插画 112–120dp、与标题 24dp、副文案 6dp；`contentDescription = null`；Library/Day 只用 `loaded && empty` 分支；NovelTab 空态与素材 tab 16:9 占位接入；不改任何网络/数据逻辑。
**Evidence**：`p5-ui-01-library-empty.png`、`p5-ui-02-day-empty.png`、`p5-ui-03-novel-empty.png`、`p5-ui-04-media.png`（需 release 新装空库，见 Task 7）。
**Expected**：四处空态一致；首帧无插画闪烁（冷启连拍 2 帧）；全量不退化。

---

### Task 5: 可靠性 A1–A4（Lead）

**Goal**：堵住「半截原文进 LLM / 尾巴永久不补 / 重试是死按钮 / 无长录守卫」四条实现缺陷。

**Files**：`data/db/DreamDao.kt`、`data/DreamRepository.kt`、`stt/AudioScan.kt`、`stt/TranscriptWriter.kt`、`stt/RecorderCore.kt`（或 `RecordingService.kt`）、`ui/detail/DetailViewModel.kt`、`llm/GenerationOrchestrator.kt`、`di/AppContainer.kt`；测试 `AppendTranscriptTest.kt`、`TranscriptAppendTest.kt`、`AppendEntryTest.kt`、`AudioScanTest.kt`、`RecordingLimitsTest.kt`（新）。

**Steps**：
1. A1：`AudioScan` 发现 `rawAudioMs` 变大改调新 DAO `markAppendDetected(id, ms)`（`transcriptStatus=CASE WHEN transcriptMs<:ms THEN 'PENDING' ELSE transcriptStatus END`）；启动顺序保持 scan → reconcile。
2. A2：`markTranscriptionRetry` CAS 放宽到 `transcriptStatus IN ('OK','FAILED') AND transcriptMs < :expectedMs`；`DetailViewModel` 注入 `retryPending` 回调，点按钮时真正触发 `writer.reconcilePending()`。
3. A3：`DetailViewModel` 与 `GenerationOrchestrator` 两处闸门都加 `transcriptMs >= rawAudioMs`，否则 `Failed("还有 N 秒没转成文字…")`。
4. A4：`RecordingLimits.canStart(freeBytes)`（≥128 MB）与 `reachedCap(elapsedMs)`（4h）+ 到限自动收尾；`RecordingService` 起录前检查、超限停止并提示。
5. 测试：A1 2 例、A2 DAO 2 例 + VM 1 例、A3 1 例、A4 4 例；两处变异（删置 PENDING、去掉闸门）必红，恢复后 sha 逐字同。
6. 证据 `docs/evidence/plan5/p5-reli-00-a1a4.txt`（先红/后绿/变异原文/全量）。

**Expected**：全量 **52 类/440 例**（实测对账）。

---

### Task 6: UI 打磨与 A11y（UI Designer 规格 + Builder 落地）

**Goal**：48dp 触控、1.3× 字号不溢出、间距一致、丝滑但不喧闹。

**Files**：`ui/detail/VersionStrip.kt`、`ui/detail/ReviseSheet.kt`、`ui/settings/SettingsScreen.kt`（关于行）、`ui/theme/Type.kt`（如需）。

**Steps**：版本线 chip 与快捷 chips 改 `heightIn(min = 48.dp)`（可视高度不变）；设置页加「关于」行（`梦匣 0.9.0 · 本地运行 · 无账号`）+ 隐私一句；`font_scale 1.3` 下过一遍空态/版本线/成本行/chips；补 `p5-ui-05-fontscale.png`、`p5-a11y-01-bounds.txt`（uiautomator bounds ≥126px）。
**Expected**：关键点击目标 ≥48dp；1.3× 无截断；全量不退化。

---

### Task 7: 发布就绪（Lead）

**Goal**：可装可验的候选版 release（0.9.0），签名/版本/体积/冷启动证据齐全。

**Files**：`android/app/build.gradle.kts`（signingConfig 条件加载、versionCode 2/versionName 0.9.0）、`docs/RELEASE.md`、`CHANGELOG.md`、`.gitignore`（确认 keystore 排除）。

**Steps**：
1. 本机生成 `dreamkeeper-release.jks`（口令走 env，不入库），写 `keystore.properties`（gitignore）。
2. `assembleRelease` → `apksigner verify --print-certs`（证书 sha256 落证据）→ `adb install -r` → 冷启 ×3（`am start -W`）→ `logcat` FATAL 0 → `grep dk_debug_save_key` 0 → 首启空库（release `SEED_DEMO_DATA=false`）截空态图。
3. 体积对照：`unzip -l` 美术条目 + APK 总字节 vs 9,612,189 B 基线，硬上限 +1.2 MiB。
4. 写 `RELEASE.md`（密钥保管/构建/签名/装机/回滚）与 `CHANGELOG 0.9.0`。
5. 证据 `p5-release-01-build.txt`、`p5-release-02-launch.png`、`p5-release-03-size.txt`。

**Expected**：release 装得上、冷启 ≤1.2s、FATAL 0、体积达标；真机仍 PENDING。

---

### Task 8: 终验与真机清单（Lead + Evidence Collector）

**Goal**：终态对账、证据闭包、文档收口；明确「模拟器验收的候选版」，不宣称真机通过。

**Steps**：全量复跑（期望 52 类/440 例）；FINAL-REVIEW 六节（基线→终态、提交清单、任务表、需求对照、PENDING 白名单、风险结论）；`ACCEPTANCE.md` 追加 `## 3f`；看板 Plan 5 8/8（修 Plan 4 §二·七 标题与需求表 #6）；`p5-device-01-checklist.md`；D471 起收尾偏差。
**Expected**：证据目录自洽、sha 齐全、结论「候选版通过（真机 PENDING）」。


### Task 3 验收（Lead，2026-10-31）
- 交付（本提交）：`res/mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml`；`res/mipmap-anydpi-v33/` 同名两枚（含 `<monochrome>`）；Manifest 改 `android:icon="@mipmap/ic_launcher"` + `android:roundIcon="@mipmap/ic_launcher_round"`；`values-v31/themes.xml` 的 splash 图标改 `@mipmap/ic_launcher` 并补 `windowSplashScreenIconBackgroundColor=#0B0E13`；`ic_launcher_dark.xml` 保留作兜底、不再被 launcher 引用。
- 复核：`aapt2 dump badging` → `application-icon-160/240/320/65534/65535` 全部指向 `res/mipmap-anydpi-v33/ic_launcher.xml`；`aapt2 dump resources` 含 `mipmap/ic_launcher`、`mipmap/ic_launcher_round`、`drawable/ic_launcher_foreground`（XML 矢量）、`color/ic_launcher_background`。
- 设备（APK `0384e526…c00fd`，13,929,546 B，install Success）：桌面图标显示正常（圆形遮罩、无裁切，`p5-icon-01-icon.png`）；冷启 splash 深色无白闪（`p5-icon-02-splash.png`，nearWhite 0）。
- 证据 `p5-icon-01-icon.{png,txt}`、`p5-icon-02-splash.{png,txt}`、`p5-icon-03-badging.txt`。
- PENDING：themed/monochrome 在系统主题图标下的外观（模拟器无主题图标开关，P3 保持 PENDING）；静止 splash 帧（D476）。
- 结论：通过。

## §10 验收记录

### Task 6 验收（Lead，2026-10-31）

- **交付**：`VersionStrip`/`ReviseSheet` 触控区 48dp（内层胶囊 36dp）、`SettingsScreen` 关于卡 + `ChoiceRow` 改 `FlowRow`。
- **Lead 独立复跑**：`:app:testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL 57s，**52 类/443 例/0/0/0**（T6 未改测试，计数不退化）。
- **设备证据**：APK sha 前16 `9d3fdce8b75d9749`，`font_scale=1.3`；`p5-ui-05-fontscale.png`（设置页，模型 chips 单行不截断）、`p5-ui-06-version.png`（版本线/成本行/正文不截断）、`p5-a11y-01-bounds.txt`（17 个 clickable 全部 ≥126px）。
- **契约**：截图全 nearWhite=0；触控尺寸按 420dpi 换算 48dp=126px；未新增依赖、未改主题 token。
- **PENDING**：TalkBack 真机朗读（无中文 TTS）、2.0× 字号（超出 v1 承诺）。
- **结论**：通过。


### Task 4 验收（Lead，2026-10-31）

- **交付**：`dcfd732` — `ui/common/EmptyState.kt` + `LibraryScreen`/`DayScreen`/`DetailScreen`(NOVEL_EMPTY)/`MediaTab` 四处接入。
- **Lead 独立复跑**：`assembleDebug` BUILD SUCCESSFUL 26s；全量 51 类/431 例 0 失败（当时尚无 T5 用例）。
- **契约**：插画只来自 `drawable-nodpi` 四张（AssetCatalogTest 守）；`EmptyState` 只在 `loaded && empty` 调用；空态文案与原两行文案一致。
- **证据**：设备空态必须 release 空库，按 T7 一轮拍（D478/D479）。
- **PENDING**：空态设备截图（T7）。
- **结论**：通过（组件与接入成立，视觉证据待 T7）。

### Task 5 验收（Lead，2026-10-31）

- **交付**：T5 改动（A1–A4）+ `docs/evidence/plan5/p5-t5-reliability.txt`；DetailScreen 接线参数在 T4 提交里（D483）。
- **Lead 独立复跑**：targeted 5 类 62 例（修断言后 21s 全绿）；全量 `--rerun-tasks` BUILD SUCCESSFUL 57s，**52 类/443 例/0/0/0**。
- **契约**：A1 文件变长 → `markAppendDetected`（PENDING 仅当水位未追平）；A2 CAS 放宽到 OK/FAILED 且水位未追平，命中后触发 Writer（回调恰一次）；A3 编排器与详情页双层闸门；A4 4h + 128MB。
- **变异**：`AudioScan` 改回 `fixAudioMs` → 2 例红（BUILD FAILED 23s），恢复 sha `6219e02d…` 与备份逐字节同。
- **证据**：`p5-t5-reliability.txt`（含绿跑/变异原文/未覆盖）。
- **PENDING**：真机 4h 长录与低存储（P9）、进程被杀窗口的设备复现（P7/P9）。
- **结论**：通过。


### Task 0 验收（Lead，2026-10-31）
- 交付 `9f3f742`：计划 §0.1–§0.9 定稿（T0–T8、D461–D470）、`docs/evidence/plan5/README.md`、`.gitattributes` plan5 规则、决策记录 `.superpowers/sdd/2026-10-31-plan5-polish/decisions.md`。
- 证据口径：命名 `p5-<area>-<nn>-<state>`；txt 首三行 host-only 或设备头；艺术图 nearWhite 例外写进 README（阈值 250、≤0.5%）；release 空态必须 release 空库。
- 结论：契约冻结，T1 起可开工。

### Task 1 验收（Lead，2026-10-31）
- 交付 `f9d5236`：4 张插画（`illus_library_empty/day/novel/media`）+ 图标前景 vector + 背景色；母版 `docs/art/plan5/`；`AssetCatalogTest` 5 例。
- 实测：WebP 合计 72,220 B（预算 400 KB）、单张最大 31,728 B（预算 120 KB）；近白 0.0000%、meanY ≤0.018、P99 ≤0.124。
- 复跑：`AssetCatalogTest` 5/5 绿（XML failures=0）。
- 证据 `p5-asset-00-master.txt`（逐张 sha256/尺寸/门槛判定）。
- 结论：通过；设备渲染在 T4/T6 复用。

### Task 2 验收（Lead，2026-10-31）
- 交付（本提交）：`GenerationOptions.kt`、`GenerationPrefs.kt`、设置页「生成」卡、`GenerationOrchestrator`/`ReviseOrchestrator`/`AppContainer`/`DreamKeeperRoot`/`MainActivity` 透传；测试 `GenerationPrefsTest` 5 例 + `GenerationOrchestratorTest` +3 例。
- Lead 独立复跑：targeted 2 类/20 例/0 失败；全量 **51 类/431 例/0 失败**（1m4s）；变异（默认 HIGH→OFF）只杀 `小说化默认发思考高且不发 temperature`，恢复后 `GenerationOptions.kt` sha256 `40f37f46…62562` 逐字节同。
- 证据 `p5-settings-00-tiers.txt`、`p5-settings-01-default.{png,txt}`、`p5-settings-02-persist.{png,txt}`（APS sha、设备时刻、nearWhite TopOffset=100 口径 0）。
- 契约：faithful 恒 DISABLED+0.1；novel 默认 ENABLED/HIGH 且不发 temperature、思考关时发 0.9；模型跟随设置；改稿档位按 D471 延后。
- PENDING：真实 API 用 v4-pro 端到端（T7/T8）；改稿思考档位（D471）。
- 结论：通过。

## Self-Review（写完后自查）

- **Spec 覆盖**：需求 #6（离线资源）→ T1/T2/T3/T4；发布就绪 → T5；真机 PENDING → T6；UI 打磨 → T31/T4。无遗漏。
- **占位符**：无 TBD/TODO；每个代码步骤给了可直接粘贴的代码。
- **类型一致**：`EmptyState(art, title, hint, modifier)` 在 T3 定义、T3 四处调用一致；资源名 T1 产出、T2/T3/T4 引用一致；`AssetCatalogTest` 的清单与 §0.4 表一致。
- **测试计数**：49/418 + T1 5 例 + T2 8 例 = **51 类/431 例**（D472 实测）；T3/T4 新增用例时按 XML 重算。
