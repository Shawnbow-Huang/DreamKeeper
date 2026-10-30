# Plan 3 · 生成、成本、导出与对话改稿实施计划

> 依据：`docs/superpowers/specs/2026-10-29-dreamkeeper-design.md` **v1.5**（§6.1 失败链、§6.2 小说化、§6.3 对话改稿、§7 提示词、§9 LLM 接入层、§5 数据模型）
> 仓库根：`D:\DreamKeeper`；Gradle 工程根：`D:\DreamKeeper\android`（所有构建命令先 `cd android`）
> 分支：`plan3-generation`（从 `plan2-done` 的 `bb11fe2` 切出）

## 0. 本计划要回答的问题

Plan 1 只会「存和看」，Plan 2 只会「录和播」。Plan 3 让一条梦第一次**变成文字**：用户自己的 DeepSeek Key，
一次「理顺」得到忠于原梦的原样稿，一次「小说化」得到能读的小说稿，不满意可以对着小说稿说人话让它改，
每一分钱都记账，写完了能导出带走。

## 1. Global Constraints（每个任务都适用，覆盖下文任何冲突）

- **G-1 命令纪律**：构建前必设 `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`；adb 用绝对路径
  `C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe`；抓二进制文件一律
  `cmd /c "`"$adb`" exec-out run-as com.dreamkeeper cat <路径> > <本地文件>"`，**不许** PowerShell `>` 重定向。
- **G-2 证据纪律**：证据落 `docs/evidence/plan3/`，命名 `p3-<case>-<nn>-<state>.{png,txt,json,log,docx,pdf}`；
  每份证据首三行写 APK sha256 / `adb shell date` / 复跑命令；机器判定优先于肉眼；
  **无 Key 时一切生成证据必须标 `source=fixture`**，不许把回放结果写成「真实调用成功」。
- **G-3 产品纪律**：只接 DeepSeek 官方 `https://api.deepseek.com/chat/completions`；默认模型 `deepseek-flash`；
  Key 只进 Android Keystore（AES/GCM），不进日志、不进备份、不进证据；**思考开着时服务端忽略 temperature**，
  设置页必须写明；任何用户可见文案不许出现 `Plan N` 之类内部编号（D71）。
- **G-4 提示词纪律**：提示词真源是仓库根 `prompts/*.md`（不在 `android/` 里）；**版本哈希用原始整文件字节**
  （LF、无 BOM）算 sha256 前 8 位，实测应为 `faithful.v2=5b9702ee` / `novel.v2=f189dad8` /
  `novel_revise.v1=2ebba630`；剥头只影响「送进模型的正文」，不影响哈希。
- **G-5 闸门纪律**：原样稿必须过 G1–G5；**小说化不套 G1–G5**（只要求改动清单的 `quote` 都能在原文里找到 +
  编造次数 0 的抽检）；**T2 保守档不允许用五闸门当验收**，必须 `contentChars(out) == contentChars(in)` 逐字等值。
- **G-6 测试纪律**：业务逻辑与 DAO 用 JVM 单测（JUnit4 + Robolectric），Compose 界面靠「构建→安装→截图」；
  每个任务先红后绿，且**至少做一次变异验证**（把实现改坏必须让某条断言变红，改回必须变绿）；
  期望值**不许从被测代码的输出反推**；JVM 里的 HTTP 用 `com.sun.net.httpserver` 起本地服务（零新依赖）。

## 2. 三个必须先修的前提（专家的实测结论，不是假设）

1. **仓库现在没有 OkHttp，也没有 kotlinx.serialization**：`android/gradle/libs.versions.toml` 与
   `android/app/build.gradle.kts` 里都没有（设计 §13 写过，但 Plan 1 没落）。且 AGP 9.5 用内建 Kotlin，
   serialization 插件要挂 KGP，**兼容性未验证**。→ 决定：**只加 OkHttp**，JSON 用框架自带 `org.json`
   （`JSONObject`/`JSONArray`，零依赖、可单测），不引 serialization 插件。登记为偏差。
2. **`AndroidManifest.xml` 没有 `INTERNET`**：不加第一次请求就 `SecurityException`。同时本地假服务器
   （`adb reverse` + `http://127.0.0.1:18080`）需要 **debug-only** 的 `network_security_config` 允许明文，
   release 不许放行。
3. **`check-near-white.ps1` 其实有 `-MaxRatio`**（默认 0.01，L125 使用），Plan 2 里「没有该参数」的登记是错的
   → 在偏差表里更正，后续统一用 `-MaxRatio 0`。

## 3. 数据模型增量（设计 §5，v1 → v2 迁移，**不许 destructive**）

```kotlin
// dreams 追加两列（末尾追加、带默认值）
val novelSelectedVersion: Int? = null      // null = 没挑，列表与导出用最新版
val generationStage: String? = null        // T0|T1|T2|T3 最后一次落在哪一档

// 新表
NovelVersion(id, dreamId, version, text, changeLogJson, origin /*GENERATE|REVISE*/, promptId,
             promptVersion, promptHash, model, reasoningEffort, createdAt)
NovelMessage(id, dreamId, role /*USER|ASSISTANT*/, content, versionId: String?, createdAt)
LlmCall(id, dreamId, kind, model, reasoningEffort, cacheHitTokens, cacheMissTokens, outputTokens,
        reasoningTokens, isPeak, priceSnapshotJson, costMicroYuan, formulaVersion,
        httpStatus, attemptNo, requestId, usageJson, error, latencyMs, createdAt)
PromptVersion(id, kind, version, promptHash, createdAt)   // 唯一键 (kind, version, promptHash)
GenerationJob：沿用现有表，补 stage / attemptNo / error 三列
```

- `DreamDatabase` version 1 → **2**，写 `MIGRATION_1_2`，`exportSchema = true`，提交
  `app/schemas/com.dreamkeeper.data.db.DreamDatabase/2.json`；**禁止 `fallbackToDestructiveMigration`**。
- 迁移测试用 `MigrationTestHelper`（`room-testing` 已加）：v1 库文件 → 跑迁移 → 断言新列默认值与新表可写。

## 4. 价格与成本（唯一真源 = 资产文件）

`android/app/src/main/assets/pricing/deepseek.json`：

```json
{"version":"2026-10-30","effectiveFrom":"2026-10-30","sourceUrl":"https://api-docs.deepseek.com/quick_start/pricing",
 "sha256":"<文件自身内容的 sha256，自举留空>" ,
 "currency":"CNY","unit":"microYuanPerMillionTokens",
 "models":{"deepseek-flash":{"cacheHit":20000,"cacheMiss":1000000,"output":4000000,
           "offPeak":{"cacheHit":20000,"cacheMiss":1000000,"output":4000000}},
           "deepseek-v4-pro":{...}}}
```

- 计价公式（整数微元，只在最后取整一次）：
  `costMicroYuan = (hit*Rhit + miss*Rmiss + out*Rout + 500000) / 1000000`，R 的单位是「微元 / 1M tokens」。
- 高峰判定：北京时间（`Asia/Shanghai`）工作日 `09:00–12:00`、`14:00–18:00`，**时钟可注入**。
- 每次调用把**整张价卡**复制进 `priceSnapshotJson`（只存时间戳不够：调价后历史账目必须还能复算）。
- 断言口径：`hit + miss == prompt_tokens`、`prompt + out == total_tokens`，两处不等即判解析失败（不入库）。

## 5. 提示词资产与版本

- Gradle：新建 `Sync` 任务把 `../../prompts/*.md` 拷到 `build/gen/prompts/`，再 `assets.srcDir` 指它
  （直接 `srcDir("../../prompts")` 会把 md 平铺到 assets 根，且会把仓库其它目录带进来）。
- 运行时：`assets.open("prompts/novel.v2.md")`（**不许** `openFd`，压缩项会抛 `FileNotFound`）；
  剥头 = 按行扫到第二个 `---` 为止，再剥紧邻的 `<!-- -->` 注释块；**不许** `split("---")`（正文里有分隔线和代码围栏）。
- 送模型的正文从 `## 输入` 起；单测断言送出串**不含** `promptId:`、不含 `<!--`。
- 变量填充用 9 个【…】槽替换（无 `{{}}` 语法），每个提示词一张槽位表，缺槽直接抛错（不许静默留空）。
- `prompt_versions` 记账：`(kind, version, promptHash)` 唯一；哈希漂移 = 新增一行 + WARN，**不覆盖旧行**。

## 6. 生成链路

### 6.1 原样稿（faithful）：四级失败链

```
T0  temperature 0.1                           → 过 G1–G5 → 入库
T1  temperature 0.0 + 【上一版违规清单】        → 过 → 入库（记 stage=T1）
T2  temperature 0.0 + 【只做标点】              → 只校验 contentChars 等值 → 入库（stage=T2）
T3  不调用模型                                   → faithfulText = rawTranscript（stage=T3）
```

- 违规清单渲染器是**纯函数**，截断口径必须与提示词一致：G1 取 10 条、G4/G5 取 6 条、advisory 取 12 条；快照测试钉住。
- T1 撞网络错误（429/5xx/超时）**不算掉档**：先按退避重试同一档，仍失败才判整次生成失败（登记为 D2xx 口径）。
- UI 文案：`stage == T2` 显示「这段系统退回了保守处理，只加了标点」；`T3` 显示「这次没能整理，先给你原始记录」，
  并给「看原始转写 / 重试」两个出口。

### 6.2 小说化（novel）

- 输入 = `faithfulText`（为空回退 `rawTranscript`）；输出 JSON `{title, body, changeLog[{type,detail,quote}], mood}`。
- `changeLog` 每条 `quote` 必须是原文的**精确子串（4–40 字）**：入库前用 `indexOf` 校验，找不到的条目丢弃并计数；
  全丢时列表页不显示「补写 N 处」，改显示「改动清单解析失败」。
- tab 文案沿用 D66：「小说化」，N 下沉为内容顶部 caption。

### 6.3 对话改稿（revise）

- 输出二选一：`{"action":"ASK","question":...}` 或 `{"action":"REVISE","body":...,"changeLog":[...],"summary":...}`；
  `action` 只认大写精确匹配，其他值 = 一次失败（记 `llm_calls.error`），**不许**兜底成 REVISE。
- ASK：存 `novel_messages`（`versionId = null`），**不建版本**、版本号不动。
- REVISE：必建一版（`origin=REVISE`）；「没改也交一版」是显式例外（`body` 逐字相同、`summary` 说明无改动），
  校验器不许因 `ratio == 1.0` 拒收。
- **基线冲突必须解决**：`G1/G5` 对 `rawTranscript`，`G2/G3` 对**当前版本**（否则「细节太多」这类合法删除永远过不了 G3）。
- 写库前用 `db.withTransaction`；失败保留旧版并在 UI 说明哪条闸门没过。

## 7. 导出（零新依赖）

| 格式 | 实现 | 硬约束 |
|---|---|---|
| txt | `contentResolver.openOutputStream` 直写 | UTF-8 无 BOM、统一 `\r\n`；文件名剔除 `/ \ : * ? " < > \|` 与控制字符 |
| docx | 手搓最小 OOXML（zip 三个 part） | 先剔 `0x00–0x08/0x0B/0x0C/0x0E–0x1F` 再转义 `& < >`；`ZipEntry` 时间戳写死；`[Content_Types].xml` 必须在；`w:rFonts w:eastAsia="宋体"` |
| pdf | `android.graphics.pdf.PdfDocument` | 单位 pt、A4 595×842、textSize 10–11；中文用系统 CJK fallback（**不指定** MONOSPACE / 自带 ttf）；换行用 `Paint.breakText` 逐字；每页 `finishPage`、最后 `finish()` 再 `writeTo()` |

- 落盘走 SAF `ACTION_CREATE_DOCUMENT`（minSdk 26 拿不到 `MediaStore.Downloads`），不引 FileProvider。
- 导出内容 = 当前选中版（`novelSelectedVersion` 为空则最新版）+ 元信息头（日期、标签、时长）+ 原样稿可选附录。

## 8. 任务拆分（10 个 Task）

> 每个 Task 的固定形状：**Files / Interfaces / Steps（含先红后绿）/ Expected / 提交**。
> 提交一律 `git add <本任务 Files 里的具体路径>`；**禁 `git add -A`**。

### Task 1 接线：权限、依赖、设置页骨架

> **派工前修订（Lead 按 Mobile App Builder + Evidence Collector 预审裁定，覆盖下文任何冲突）**
> 1. **本任务只加 `generationScope`**；`apiKeyStore` 放到 Task 2、`llmClient` 放到 Task 5——原写法会引用尚不存在的类型，Task 1 当场编译不过（D223）。
> 2. **明文放行只能放 debug 源集**：main 只加 `INTERNET`；`android:networkSecurityConfig` 与 `res/xml/network_security_config.xml` 都放 `src/debug/`，并新增 `src/debug/AndroidManifest.xml`。放 main 会让 `assembleRelease` 因资源缺失失败；「修」成 main 有资源则等于 release 也放行（D224）。
> 3. **不许用 `Icons.*`**：仓库没有 material-icons 依赖（`CalendarScreen.kt:171` 已有同样教训），入口照 `CalendarScreen.kt:200-210` 的 `ArrowButton` 写文字盒 + `contentDescription = "设置"`（D225）。
> 4. **不加 Compose 测试依赖**：仓库没有 `ui-test-junit4`，导航只做 `DreamRoute.Settings.route` 的纯单测（D226）。
> 5. **OkHttp 钉 `4.12.1`**（纯 JVM jar）；5.5.1 的 `.module` 是 KMP root，AGP 9.5 内建 Kotlin、未挂 `kotlin-android` 时有变体解析风险（D227）。
> 6. `Settings` **不许**加进底部栏：`DreamKeeperRoot.kt:92` 的 `showBottomBar` 按路由等值判定，加进去会变第三个 tab。
> 7. 本任务**只证「config 挂上了」**，不证「明文真的能通」——后者要等第一个 `127.0.0.1:18080` 请求（D229）。

- Files：
  - `android/app/src/main/AndroidManifest.xml`（**只加** `<uses-permission android:name="android.permission.INTERNET"/>`）
  - `android/app/src/debug/AndroidManifest.xml`（**新建**：`<application android:networkSecurityConfig="@xml/network_security_config"/>`）
  - `android/app/src/debug/res/xml/network_security_config.xml`（**新建**：`cleartextTrafficPermitted="false"` 的 base-config + **恰好两个** `<domain>`：`127.0.0.1`、`10.0.2.2`）
  - `android/gradle/libs.versions.toml`（`okhttp = "4.12.1"` + library 条目）
  - `android/app/build.gradle.kts`（`implementation(libs.okhttp)`）
  - `android/app/src/main/java/com/dreamkeeper/ui/nav/DreamRoute.kt`（+`Settings`）
  - `android/app/src/main/java/com/dreamkeeper/ui/settings/SettingsScreen.kt`、`SettingsViewModel.kt`（新建；**不碰 ApiKeyStore**，Key 区显示「还没配置」）
  - `android/app/src/main/java/com/dreamkeeper/di/AppContainer.kt`（**只加** `generationScope`，形制照 `:38` 的 `transcriptionScope`，并在注释里写清与 `DreamKeeperApp.appScope` 谁负责取消谁）
  - `android/app/src/main/java/com/dreamkeeper/ui/DreamKeeperRoot.kt`（+Settings 路由、把 `onSettings` 传给 `CalendarScreen`）
  - `android/app/src/main/java/com/dreamkeeper/ui/calendar/CalendarScreen.kt`（`MonthHeader` 加 `onSettings`，右侧第 4 个 48dp 文字盒）
  - `android/app/src/test/java/com/dreamkeeper/ui/nav/DreamRouteTest.kt`（新建）
- Steps：
  1. 先写 `DreamRouteTest`：断言 `DreamRoute.Settings.route` 唯一且非空 → 跑红（`Unresolved reference 'Settings'`）。
  2. 接线至绿；`assembleDebug` 成功。
  3. **包内证据**：`aapt2 dump permissions app-debug.apk` 命中 `android.permission.INTERNET`；`aapt2 dump xmltree --file AndroidManifest.xml app-debug.apk` 里 `networkSecurityConfig` **恰 1 行**；同一份 APK 的 `cleartextTrafficPermitted` 只有 domain 级、`base-config` **0** 行。
  4. **release 负向**：`assembleRelease` 必须 `BUILD SUCCESSFUL` 并留下 `app-release-unsigned.apk` 的 sha256；对其跑同一 `xmltree` 断言 `networkSecurityConfig` **0 行**。
  5. 真机（**emulator-5554**，1080x2400@420dpi）：点设置入口 → 出现设置页独有文案 → 截图
     `docs/evidence/plan3/p3-settings-01-idle.png` + `check-near-white.ps1 -TopOffset 100 -MaxRatio 0`；
     `uiautomator dump` 取入口 bounds，宽高均 **≥126px**（48dp）。
- Expected：路由单测绿；debug APK 有 INTERNET 与唯一一处 `networkSecurityConfig`；release APK 构建成功且**没有**该属性；
  设置页可达、全深色、入口触控 48dp；`adb logcat` 无 FATAL。
  **不许**写「明文已验证可用」——本任务没发过任何请求。

### Task 2 Key 加密存储

- Files：`data/security/ApiKeyStore.kt`（接口 + `KeystoreApiKeyStore` + `FakeApiKeyStore`）、
  `data/security/KeystoreCipher.kt`、`test/.../ApiKeyStoreTest.kt`、`ui/settings/SettingsViewModel.kt`。
- Interfaces：`interface ApiKeyStore { fun load(): String?; fun save(key: String); fun clear() }`；
  `KeystoreCipher`：AES/GCM/NoPadding，密钥别名叫 `dreamkeeper.api.key`，**不设** `setUserAuthenticationRequired`，
  存 `iv + ciphertext` 两段 base64 到 `SharedPreferences("settings")`。
- Steps：红（无实现）→ 实现 → 绿；用例：写读回环、清空、脏数据不崩返回 null、**日志不出现明文**（挂一个
  记录 `Log.*` 的测试替身断言无 Key 子串）。
- Expected：`prefs` 里只有 base64 密文；`adb logcat` 过滤 Key 前缀零命中。

### Task 3 价卡与成本计算

- Files：`android/app/src/main/assets/pricing/deepseek.json`、`llm/Pricing.kt`（`PriceCard`/`PriceTable`/`PeakPolicy`）、
  `llm/CostCalculator.kt`、`test/.../CostCalculatorTest.kt`、`scripts/check-cost.py`（host，复算闸门）。
- Interfaces：`fun costMicroYuan(card: PriceCard, model: String, hit: Long, miss: Long, out: Long, at: Instant): Long`；
  `fun isPeak(at: Instant, zone: ZoneId = ZoneId.of("Asia/Shanghai")): Boolean`。
- Steps：先写 8 个高峰边界用例（08:59:59 / 09:00 / 11:59:59 / 12:00 / 13:59:59 / 14:00 / 17:59:59 / 18:00 与周末）
  与 4 个算术用例，红 → 实现 → 绿；再写 `check-cost.py` 对导出库复算（见 Task 8 的证据）。
- Expected：全部整数运算、无浮点；同一批 token 在峰/谷算出两倍差；`check-cost.py --db-copy` 报 `mismatches=0`。

### Task 4 数据库 v2 迁移

- Files：`data/db/DreamEntity.kt`（+两列，末尾追加带默认值）、`data/db/NovelVersionEntity.kt`、`NovelMessageEntity.kt`、
  `LlmCallEntity.kt`、`PromptVersionEntity.kt`、`GenerationJobEntity.kt`（补三列）、`data/db/DreamDatabase.kt`（version=2 + `MIGRATION_1_2`）、
  `data/db/NovelVersionDao.kt`、`LlmCallDao.kt`、`PromptVersionDao.kt`、`test/.../MigrationTest.kt`、`app/schemas/.../2.json`。
- Steps：先写迁移测试（v1 库 → 迁移 → 新表可写、旧数据不丢、新列默认值正确），红 → 实现 → 绿；
  `git diff --exit-code` 确认 `1.json` 未被改写、`2.json` 入库。
- Expected：老库平滑升级；`identityHash` 变化记录在案；无 destructive。

### Task 5 LlmClient（OkHttp + org.json）

- Files：`llm/LlmClient.kt`（接口 `suspend fun chat(req: ChatRequest): ChatResponse`）、`llm/DeepSeekClient.kt`、
  `llm/ChatModels.kt`（`ChatRequest(model, messages, thinking, effort, temperature, jsonMode)`、`ChatResponse(content, reasoning, usage)`）、
  `test/.../DeepSeekClientTest.kt`（用 `com.sun.net.httpserver.HttpServer` 起本地服务）。
- 要点：`OkHttpClient` 单例、`callTimeout(300s)`、`readTimeout(180s)`；取消用 `suspendCancellableCoroutine` +
  `invokeOnCancellation { call.cancel() }`；`thinking` 与 `reasoning_effort` 是**顶层字段**；`response_format: {type:"json_object"}`；
  **`content` 为空或非 JSON 一律判失败**（服务端会把非法 JSON 换成空格但 `finish_reason` 仍是 `stop`）；
  429/5xx 指数退避 3 次、尊重 `Retry-After`，时钟与 jitter 可注入。
- Steps：先写 6 个服务端用例（200 正常、空 content、非 JSON、429×3 后成功、401 不重试、超时取消），红 → 实现 → 绿。
- Expected：401 只发 1 次请求；429 共 4 次尝试且间隔递增；取消后服务端收到连接断开。

### Task 6 提示词资产与 PromptRepository

- Files：`android/app/build.gradle.kts`（Sync 任务 + `assets.srcDir`）、`llm/PromptRepository.kt`、
  `llm/PromptSpec.kt`（槽位表）、`test/.../PromptRepositoryTest.kt`。
- 要点：三个提示词的 sha256_8 作为**内置断言向量**（`5b9702ee`/`f189dad8`/`2ebba630`，任一不符即启动即失败）；
  剥头、槽位替换、缺槽抛错；送出串不含 `promptId:` 与 `<!--`。
- Steps：先写（哈希向量、剥头、槽位、缺槽、无注释残留）5 条，红 → 实现 → 绿；`processDebugAssets` 后
  `unzip -l app-debug.apk | grep prompts/` 显示 `assets/prompts/*.md`。

### Task 7 五闸门 Kotlin 移植

- Files：`llm/gates/FaithfulGates.kt`、`llm/gates/GateResult.kt`、`test/.../FaithfulGatesTest.kt`。
- 移植 `scripts/check-faithful.py` 的 G1–G5；**自测断言「失败的闸门集合」**（现有 python 只比总通过，
  把 G1 写成空函数也能全绿）；保留两个数词向量（一层→三层、过了三十）与「一下/七点」的**已知误报**（别顺手修）；
  G5 的否定前缀 `不没未别莫` 与逐词非重叠计数照搬；2-gram 先 strip_ws 再删 34 字 PUNCT 集合（**不许** `Char.isPunctuation`）。
- Steps：11 条变异向量 + 2 条边界（只加标点必须绿；删语气词必须绿）先红 → 实现 → 绿 → 变异（把 G1 改恒真，集合断言必须红）。
- 另加：`contentChars(s)` 纯函数 + `isConservativeOk(in, out)`（T2 等值校验）。

### Task 8 GenerationOrchestrator（含四级失败链与记账）

- Files：`llm/GenerationOrchestrator.kt`、`llm/GenerationResult.kt`、`ui/detail/DetailViewModel.kt`（接生成）、
  `ui/detail/NovelTab.kt`（改动清单 + caption）、`test/.../GenerationOrchestratorTest.kt`。
- 要点：per-dream 单飞；每次尝试写一行 `llm_calls`（`attemptNo` 递增、`stage` 落 `generation_jobs`）；
  T0–T3 状态机；T1 的网络错误不退档；`changeLog` 的 `quote` 校验与丢弃计数；成功后写 `novel_versions` + `dreams.novelText`。
- Steps：先写 8 条（T0 一次过、T0 红→T1 过、T1 网络错→重试同档、T1 红→T2 等值、T2 不等值→T3、quote 全丢、
  单飞拦截、成本行数==尝试次数），红 → 实现 → 绿；变异（把 T1 的「清单注入」去掉，必须红）。

### Task 9 对话改稿 + 多版本 + 选最终稿

- Files：`ui/detail/ReviseSheet.kt`、`ui/detail/VersionStrip.kt`、`ui/detail/DetailViewModel.kt`、
  `data/DreamRepository.kt`（`listVersions/selectVersion/appendVersion`）、`test/.../ReviseFlowTest.kt`。
- 要点：ASK 不建版；REVISE 必建版；「没改也交一版」允许；快捷按钮 6 个（太文绉绉 / 细节太多 / 结尾太说教 /
  人称不对 / 多点环境 / 少点形容）；版本线 `v2 · 按你说的删了那两段描写 · 改动 3 处`；`novelSelectedVersion` 决定
  列表与导出用哪一版；`db.withTransaction`。
- Steps：先写 6 条（ASK 分支、REVISE 分支、逐字相同版、选择后列表生效、失败保留旧版、G2/G3 对当前版本），红 → 实现 → 绿。

### Task 10 导出（txt/docx/pdf）+ 验收与证据

- Files：`export/Exporter.kt`（`export(dream, format): Uri`）、`export/TxtExporter.kt`、`DocxExporter.kt`、`PdfExporter.kt`、
  `ui/detail/ExportSheet.kt`、`test/.../DocxStructureTest.kt`、`scripts/check-export.py`、
  `docs/evidence/plan3/**`、`docs/evidence/plan1/ACCEPTANCE.md` 追加 `## 3d 生成与成本证据表`、
  `docs/evidence/plan3/FINAL-REVIEW.md`。
- Steps：docx 结构测试（zip 三件、XML 可解析、`w:t` 文本与期望一致、非法字符被剔除）；pdf 用
  `PdfRenderer.pageCount ≥ 1` + 逐页渲染非空白；txt 编码断言；真机导出三个文件 → `adb pull` →
  `scripts/check-export.py` 硬校验；成本复算 `scripts/check-cost.py`；五闸门与 A1–A21 对同一批输出各跑一次。
- Expected：三个导出文件全部可打开；docx 文本 sha256 与源一致；`check-export.py` 退出码 0；
  `check-cost.py` `mismatches=0`；证据首三行绑定 APK sha 与设备时间。

## 9. 偏差登记（从 D218 起）

| 编号 | 内容 |
|---|---|
| D218 | `check-near-white.ps1` **有** `-MaxRatio`（默认 0.01），Plan 2 里「无该参数」的登记错误，此处更正 |
| D219 | 只加 OkHttp、不引 kotlinx.serialization 插件（AGP 9.5 内建 Kotlin 的兼容性未验证），JSON 用框架 `org.json` |
| D220 | 提示词资产用 Gradle `Sync` 拷到 `build/gen/prompts` 再 `assets.srcDir`（直接 srcDir 会平铺且带入多余目录） |
| D221 | 对话改稿的 G1/G5 对 `rawTranscript`、G2/G3 对当前版本（否则「细节太多」类合法删除永远过不了 G3） |
| D222 | T1 档撞网络错误不视为掉档（设计未写，按「先重试同档、再判失败」实现） |
| D223 | T1-1：Task 1 只加 `generationScope`，`apiKeyStore`/`llmClient` 分别在 Task 2/5 建（原 brief 会在 Task 1 引用不存在的类型，编译必红） |
| D224 | T1-2：`networkSecurityConfig` 与放行 xml 一律放 `src/debug/`，并新增 `src/debug/AndroidManifest.xml`；放 main 会导致 `assembleRelease` 资源缺失失败，或把 release 一起放开（专家实测判断，构建验证见 T1 Expected） |
| D225 | T1-3：不用 `Icons.*`（仓库无 material-icons），入口用文字盒 + `contentDescription`，照 `CalendarScreen.kt:200-210` |
| D226 | T1-4：不加 `ui-test-junit4`，导航只做 `DreamRoute` 纯单测 |
| D227 | T1-5：OkHttp 钉 `4.12.1`；5.5.1 是 KMP root，AGP 9.5 内建 Kotlin 下的变体解析风险未验证 |
| D228 | T1-6：证据极性相反——Plan 1 的 `dumpsys … INTERNET` 是**期望为空**（负向），Task 1 是**期望命中**；且 `dumpsys` 只能说「装上了」，要证「这份 sha 的包」必须 `aapt2 dump permissions` + `lastUpdateTime ≥ APK mtime` |
| D229 | T1-7：Task 1 只证 config 挂上，不证明文可用（第一发请求要等 Task 5 的假服务器） |
| D230 | T1-8：release 负向必须先 `assembleRelease` 成功并留 APK sha256，否则是「真空通过」 |
| D231 | T1-9：截图设备写「emulator-5554（1080x2400@420dpi）」；48dp = 126px；截图统一 `-TopOffset 100 -MaxRatio 0`（D82） |
| D232 | T1-10：先落 `docs/evidence/plan3/README.md`（照 plan2 那份模板），再开始拍图 |

> 规则：每发现一处与计划的偏离就追加一行；「原因」必须写能被别人复现的实测依据，并注明是「我以为的坑」还是「实测结论」。

## 10. 自审记录

- 覆盖设计 v1.5 的 §6.1（失败链）、§6.2（小说化契约）、§6.3（对话改稿）、§7（提示词与版本）、§9（接入层与档位表）、
  §5（v2 数据模型）、§14.8（对话改稿验收）。
- **未覆盖**：真机真实 API 调用（无 Key，全部标 fixture）、PDF 中文逐字抽取（零依赖下不可自动证明）、
  §14.3/§14.4 的盲评（需用户实际使用）、跨设备同步（设计明确不做）。
