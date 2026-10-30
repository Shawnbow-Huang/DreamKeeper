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
// 不建 PromptVersion 表（D267）：提示词以 prompts/ 文件 + sha256 为唯一真源
// 不建 generation_jobs 的「补列」版本（D268）：v1 没有这张表，Task 4 整表 CREATE
GenerationJob(id, dreamId, stage, attemptNo, error, createdAt)   // v1 无此表 → CREATE TABLE
```

- `DreamDatabase` version 1 → **2**，写 `MIGRATION_1_2`，`exportSchema = true`，提交
  `app/schemas/com.dreamkeeper.data.db.DreamDatabase/2.json`；**禁止 `fallbackToDestructiveMigration`**。
- 迁移测试**不用** `MigrationTestHelper`（D271）：`room-testing` 只在 `testImplementation` 且没有把 schemas 挂到 test assets，helper 找不到 `1.json`。替代：`1.json` 的 `createSql` 手搭 v1 库 + `room_master_table.identity_hash='3150a0bccf0e3cb537faa8aac20e4186'`，再用 `Room.databaseBuilder(...).addMigrations(MIGRATION_1_2)` 打开并由 Room 自校验；同测试里再做 DAO 往返。

## 4. 价格与成本（唯一真源 = 资产文件）

`android/app/src/main/assets/pricing/deepseek.json`（**文件不自带 sha256 字段**——自指固定点且 JSON 无规范序列化；`sha256` 由加载器算 `rawBytes` 得到，写进 `PriceCard.cardSha256` 与每次调用的 `priceSnapshotJson`）：

```json
{"version":"2026-10-30","effectiveFrom":"2026-10-30",
 "provenance":"project-convention",
 "provenanceNote":"峰谷时间窗取自 dsh-cost-meter 的工程约定；DeepSeek 官方定价页没有定义峰谷窗口",
 "sourceUrl":"https://api-docs.deepseek.com/quick_start/pricing",
 "currency":"CNY","unit":"microYuanPerMillionTokens",
 "models":{
   "deepseek-flash":{
     "peak":{"cacheHit":20000,"cacheMiss":1000000,"output":4000000},
     "offPeak":{"cacheHit":10000,"cacheMiss":500000,"output":2000000}},
   "deepseek-v4-pro":{
     "peak":{"cacheHit":150000,"cacheMiss":4500000,"output":13500000},
     "offPeak":{"cacheHit":75000,"cacheMiss":2250000,"output":6750000}}}}
```

- **每个 model 只认 `peak` / `offPeak` 两支**，加载时硬校验 `offPeak[f] * 2 == peak[f]`（峰价必须全偶，半价才整数精确）；`unit` / `currency` / `provenance` 不认识就拒绝加载。
- **数值状态**：`deepseek-flash` 的 20000/1000000/4000000（= 0.02/1/4 元每百万）来自我 2026-10-29 读到的官方价目，**未二次核对**，登记为待核：`D259`。

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
- 提示词版本**不记账**：以 `prompts/*.md` 的文件 sha256 为唯一真源（D267/D285），不建 `prompt_versions` 表；
  哈希漂移只在 logcat 打 WARN，不写库。

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

> 本节是早期设计；实现与验收以 **Task 10 §0.1–§0.3** 与 D339/D341/D342/D358 为准（口径差异见 D378/D379）。

| 格式 | 实现 | 硬约束 |
|---|---|---|
| txt | `contentResolver.openOutputStream` 直写 | UTF-8 无 BOM、统一 `\n`（LF；check-export 断言 `\r` 计数 0）；文件名剔除 `/ \ : * ? " < > \|` 与控制字符 |
| docx | 手搓最小 OOXML（zip 三个 part） | 先剔 `0x00–0x08/0x0B/0x0C/0x0E–0x1F` 再转义 `& < >`；`ZipEntry` 时间戳写死；`[Content_Types].xml` 必须在；`w:rFonts w:eastAsia="宋体"` |
| pdf | `android.graphics.pdf.PdfDocument` | 单位 pt、A4 595×842、textSize 10–11；中文用系统 CJK fallback（**不指定** MONOSPACE / 自带 ttf）；换行用 `Paint.breakText` 逐字；每页 `finishPage`、最后 `finish()` 再 `writeTo()` |

- 落盘走 `MediaStore.Downloads`（API 29+ 免权限）；**API 26–28 不做导出**：按钮禁用 + 提示「系统版本过低」，FINAL-REVIEW 记 PENDING（D379）；不引 FileProvider、不改 manifest。
- 导出内容 = 当前选中版（`novelSelectedVersion` 为空则最新版，`-raw` 后缀退回 `faithfulText ?: rawTranscript`）+ 元信息头**固定四行**（标题/日期/标签/空行），无「时长」、无原样稿附录（以 Task 10 §0.1 为准，D341/D342）。

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

> 0. 派工前修订（Lead 按 Mobile App Builder / Evidence Collector 预审裁定，覆盖下文任何冲突）
>
> **0.1 `KeystoreCipher` 必须是接口**（`interface KeystoreCipher { fun encrypt(plain: String): String; fun decrypt(packed: String): String? }`），
> 实现 `AndroidKeystoreCipher` 走真 Keystore；测试用 `FakeCipher`。理由：Robolectric 的 shadows/robolectric jar 里
> **没有任何 KeyStore/KeyGenerator/Cipher shadow，也搜不到 `AndroidKeyStore` 字符串**（预审实测 jar 静态核对），
> 真 Keystore 在 JVM 上必抛 `KeyStoreException`。接口化之后 `KeystoreApiKeyStore` 的 base64、持久化、容错全部可在 JVM 测。
>
> **0.2 存一个字符串，不存两个 pref**：`iv‖ct` 用 `Base64.encodeToString(..., NO_WRAP)` 拼成一个值，键名 `api_key_packed`，
> 用 `edit().putString(...).commit()` **一次落盘**（同 `LayoutPrefs.kt:33-35` 的教训：`apply()` 后立刻 `force-stop` 会丢）。
> 加密时**不要**传 `GCMParameterSpec`，让 Keystore 生成随机 IV，再用 `cipher.iv` 取出后拼进同一个字符串。
>
> **0.3 KeyGenParameterSpec**：别名 `dreamkeeper.api.key`（**不改名、不加版本号**，改名等于让老用户静默丢 Key）；
> `PURPOSE_ENCRYPT or PURPOSE_DECRYPT` + `setBlockModes(GCM)` + `setEncryptionPaddings(NONE)` + `setKeySize(256)`；
> **不设** `setUserAuthenticationRequired`（Task 8 的生成跑在 `generationScope`，用户不在场会抛 `UserNotAuthenticatedException`，
> 这是明示取舍）；**不关** `setRandomizedEncryptionRequired`。
>
> **0.4 解密失败一律当未配置**：`AEADBadTagException` / `GeneralSecurityException` / `UnrecoverableKeyException` /
> `ProviderException` / `IllegalArgumentException`（坏 base64）全部捕获 → 删别名、清 pref、返回 `null`，不崩不弹。
>
> **0.5 `save()` 要防「换 Key 后旧密文残留」**：每次 `save` 整体覆盖（新 IV、新密文），并有一条断言：
> 保存两次后 prefs 里只有一个值，且第二次的密文能解出第二个 Key、解不出第一个。
>
> **0.6 输入处理**：`save()` 内部 `trim()`（尾随换行是最高频真实故障）；空串等价 `clear()`；
> 长度 < 20 或不是 `sk-` 开头 → 只做**软提示**（`state.warning`），**不阻止保存**（DeepSeek 之外的兼容 Key 不该被拦）。
>
> **0.7 本任务不发任何网络请求**：可用性校验留 Task 5（`max_tokens=1` 打一发）。设置页文案写成「已保存（还没验证）」，
> 不许写「已验证」。
>
> **0.8 接线四处一个都不能少**（预审给的 文件:行号）：
> `di/AppContainer.kt` 加 `val apiKeyStore: ApiKeyStore by lazy { KeystoreApiKeyStore(AndroidKeystoreCipher(), appContext.getSharedPreferences("settings", MODE_PRIVATE)) }`（放在 `generationScope` 附近，用 `:51` 的 `appContext`）；
> `ui/DreamKeeperRoot.kt:75-90` 签名加 `apiKeyStore: ApiKeyStore`，`:198` 的 `viewModel { SettingsViewModel() }` 改成传入；
> `MainActivity.kt:84-108` 照 `:87` 补 `apiKeyStore = container.apiKeyStore`；
> `ui/settings/SettingsViewModel.kt:15` 构造函数接 `(apiKeyStore: ApiKeyStore, io: CoroutineDispatcher = Dispatchers.IO)`，
> `:17-20` 的 UiState 扩成 `keyConfigured / maskedKey / draft / warning / error / busy`，`load()` 走 `viewModelScope + io`。
>
> **0.9 `SettingsScreen` 要改**：`SettingsScreen.kt:111-127` 的 KeyCard 从两态扩成三态（未配置 / 已配置显示掩码 `sk-…abcd` / 编辑中）；
> 加 `OutlinedTextField`（`PasswordVisualTransformation`，48dp 高）+「保存」「清除」两个按钮；`:56` 的「保存与校验还没做」文案删掉换成真实状态；
> 不用 `Icons.*`（D225）。
>
> **0.10 `FakeApiKeyStore` 放 test 源集**（放 main 等于给发布包塞测试桩）。
>
> **0.11 仓库没有 androidTest 源集，也不许为本任务加**（`androidx.test` 依赖 + 新源集成本大于收益）。
> 为了拿到 prefs/logcat 的**实物证据**，允许加一个 **`BuildConfig.DEBUG` 门闩的临时写入口**：
> `MainActivity` 处理 intent extra `dk_debug_save_key` / `dk_debug_clear_key`（只在该 `if (BuildConfig.DEBUG)` 块里调 `container.apiKeyStore`），
> 并把这段 diff 另存 `docs/evidence/plan3/p3-key-00-harness.patch`；**验收后不要求还原**（它是 debug-only），
> 但必须有一条断言：`grep -c dk_debug_save_key app-release-unsigned.apk` = 0（release 包里不存在该字符串）。
>
> **0.12 证据 5+1 份，命名 `p3-key-0N-*`**，每份首三行 `APK_SHA256= / DEVICE_TIME= / REPLAY=`：
> `p3-key-00-red.txt`（TDD 红）、`p3-key-01-roundtrip.txt`（写读回环 + 掩码 + 清空）、
> `p3-key-02-prefs.txt`（`run-as … cat //data/data/com.dreamkeeper/shared_prefs/settings.xml`：只有 base64、无 needle）、
> `p3-key-03-logcat.log`（保存前后全量 logcat，`grep -c $NEEDLE` = 0）、
> `p3-key-04-backup.txt`（`aapt2 … | grep allowBackup` = `false`；`dumpsys package com.dreamkeeper | grep ALLOW_BACKUP` 空）、
> 可选 `p3-key-05-configured.png` + 同名 `.txt`（`PNG_SHA256=`）。
>
> **0.13 证据必须带正对照，否则同义反复**：每个「0 命中」都要配同一 needle 的「≥1 命中」对照——
> 扫描 `grep -rl $NEEDLE` 同时跑一遍 `grep -rl CARDS`（应命中 `layout_prefs.xml`）；
> logcat 对照用 `adb shell log -t QAPROBE $NEEDLE` 应 grep 到；
> 「无明文」必须与「有密文」在**同一份 xml、同一次 cat** 里同时成立；
> 再 `am force-stop` → 重进 → `keyConfigured=true`，把密文证成可解密的真密文。
>
> **0.14 单测里的「日志无明文」必须注入 logger 替身**（JVM 的 `Log.*` 未 mock 会抛异常，开了 `returnDefaultValues` 则恒真）：
> 先断言替身收到 ≥1 条，再断言其中不含 needle。
>
> **0.15 不碰 manifest**：`AndroidManifest.xml:16` 已是 `allowBackup="false"`，本轮不加 `dataExtractionRules`；
> D2D/云备份在模拟器上无法触发，写「未验证」。

- Files：`data/security/ApiKeyStore.kt`（`interface ApiKeyStore` + `KeystoreApiKeyStore`）、`data/security/KeystoreCipher.kt`（接口 + `AndroidKeystoreCipher`）、
  `test/.../data/security/ApiKeyStoreTest.kt`（含 `FakeCipher`、`FakeApiKeyStore`）、`ui/settings/SettingsViewModel.kt`、`ui/settings/SettingsScreen.kt`、
  `di/AppContainer.kt`、`ui/DreamKeeperRoot.kt`、`MainActivity.kt`、`AndroidManifest.xml`（**只加** `android:allowBackup` 已有则不动 → 预期不改）。
- Interfaces：
  `interface ApiKeyStore { fun load(): String?; fun save(key: String); fun clear() }`
  `interface KeystoreCipher { fun encrypt(plain: String): String; fun decrypt(packed: String): String? }`
  `class KeystoreApiKeyStore(cipher: KeystoreCipher, prefs: SharedPreferences) : ApiKeyStore`
- Steps：
  1. 红：写 `ApiKeyStoreTest`（回环 / 清空 / 坏 base64 → null / cipher 抛 AEADBadTag → null 且清残留 / 只有 iv 没 ct → null / `prefs.all` 无明文 / 换 Key 覆盖 / 掩码格式），跑红并把输出存 `p3-key-00-red.txt`。
  2. 实现 `KeystoreCipher` 接口 + `AndroidKeystoreCipher` + `KeystoreApiKeyStore`，绿。
  3. 接线四处（0.8）+ 设置页三态（0.9）+ debug 写入口（0.11）。
  4. 变异验证两条：① `save()` 改成明文写 prefs → 必须红；② 坏 base64 分支改成 `throw` → 必须红。
  5. 设备侧取证（0.12/0.13）→ 5+1 份证据。
- Expected：`prefs` 只有 `Base64(iv‖ct)` 一个值；全目录 `grep` needle = 0 且 CARDS 对照 = 1；logcat needle = 0 且 QAPROBE 对照 ≥1；
  `allowBackup=false`；force-stop 重启后 `keyConfigured=true`；release APK 里 `dk_debug_save_key` 计数 0；全量用例绿。

### Task 3 价卡与成本计算

> 0. 派工前修订（Lead 按 Mobile App Builder / Evidence Collector 预审裁定，覆盖下文任何冲突）
>
> **0.1 资产文件不带自哈希字段**；加载器 `PriceCardSource.load()` 返回 `PriceCard(version, effectiveFrom, provenance, peers)` 并附 `cardSha256 = sha256(rawBytes)`。
> 测试里用**内置期望常量**（`"deepseek-flash".peak.cacheMiss == 1000000L` 这类）钉住解析结果，另配一条 `cardSha256` 的黄金值常量（首次生成后人工抄入）。
>
> **0.2 `PriceCardSource` 必须是接口**：`interface PriceCardSource { fun load(): PriceCard }` + `AssetPriceCardSource(context)`；
> 测试用 `FakePriceCardSource`（照 `data/security/KeystoreCipher.kt:23-30` 的 D237 形制）。理由：`assets/` 在纯 JVM 单测里读不到（**未验证** Gradle 测试工作目录的相对路径）。
>
> **0.3 计价函数的签名**：
> `fun costMicroYuan(rates: Rates, hit: Long, miss: Long, out: Long): Long`（纯函数、无时钟、无 IO）
> + `fun price(card: PriceCard, model: String, hit: Long, miss: Long, out: Long, at: Instant): Priced`（判峰谷 + 选 rates + 组装结果）。
> 内部一律 `Math.multiplyExact` / `Math.addExact`，只在最后 `(sum + 500_000L) / 1_000_000L` 取整一次；参数**不许写 `Int`**（`20000/1_000_000` 在整数下等于 0 是典型事故）。
>
> **0.4 `Priced` 是 sealed**：`Ok(microYuan, isPeak, cardVersion, cardSha256)` / `UnknownModel(model)` / `CardNotApplicable(reason)` / `Overflow(detail)`。
> **未知 model 在调用前就按白名单拒**（`deepseek-flash` / `deepseek-v4-pro`）；响应里冒出未知 id → `cost_micro_yuan = NULL` + `error='unknown_model'`，**绝不写 0**（0 与「免费」不可区分）。
> 任何分支都仍必须写一行 `llm_calls`（设计「绝不静默丢弃」）。
>
> **0.5 陈旧价卡不是错误**：行内快照版本 ≠ 当前资产是**正常**（快照的全部意义）；只有「当前资产不可用」才 `CardNotApplicable` 硬失败，且**不回退旧卡、不用默认价**。
> `at < effectiveFrom` 判 `CardNotApplicable`，不是谷价。时钟回退（`now < lastPricedAt - 60s`）不抛异常、不丢生成，写 `error='clock_skew'` 但保留 `pricedAtEpochMs`。
>
> **0.6 峰谷判定**：
> `fun isPeak(at: Instant, zone: ZoneId = ZoneId.of("Asia/Shanghai")): Boolean`——`at.atZone(zone)`（不用 `LocalDateTime.atZone`），`dayOfWeek.value >= 6` 判谷，半开区间 `[09:00,12:00)` 与 `[14:00,18:00)`。
> **`zone` 显式参数是测试项**：现有代码用 `ZoneId.systemDefault()`（`data/DreamRepository.kt:259`、`ui/record/RecordViewModel.kt:266`），本任务不许照抄。夹具 Instant 全取 2026 年（避开 `Asia/Shanghai` 1986–91 历史 DST 与 1901 前 LMT）。
>
> **0.7 包与目录**：`com.dreamkeeper.llm`（新包）；`android/app/src/main/assets/pricing/deepseek.json`（`assets/` 目录本任务**首次创建**）。
> 与 Task 6 的 `assets.srcDir` Sync **不冲突**：Task 6 只 Sync `prompts/`，不许覆盖 `assets/pricing/`——这条写进 Task 6 §0。
>
> **0.8 「两倍差」的精确断言**：`hit = miss = out = 1_000_000` 时分子可被 1e6 整除，`peak == 2 * offPeak` 成立；
> 但**任意 token 组合下取整会让差落在 −1..0**，所以通用用例写 `assertTrue(peak - 2*off in -1L..0L)`，只有整除那组写等式。另加资产级不变量 `offPeak[f] * 2 == peak[f]`（每个 model × 每个字段）。
>
> **0.9 证据向量先行**：`docs/evidence/plan3/p3-cost-01-vectors.json` 只放「输入 + 人手算的期望 + 推导原文」，Kotlin 测试**读这个文件**而不是在测试里另抄常量；
> `git log --format='%h %ci %s' -- <vectors> <Pricing.kt>` 必须显示 vectors 先提交。JSON 不能带「首三行」，用同名 `.txt` 侧车放 `APK_SHA256=` / `DEVICE_TIME=host-only …` / `REPLAY=` / `JSON_SHA256=`。
>
> **0.10 边界向量 10 组**（`2026-10-30` 是周五）：`01:00Z`(=09:00 峰) / `08:59:59` / `03:59:59Z`(=11:59:59 峰) / `04:00:00Z`(=12:00 谷) / `05:00:00Z`(=13:00 谷，专抓「9–18 当连续」) / `05:59:59Z`(=13:59:59 谷) / `06:00:00Z`(=14:00 峰) / `09:59:59Z`(=17:59:59 峰) / `10:00:00Z`(=18:00 谷) / 周六 `02:00Z`(=10:00 谷)。
> 另加**时区反事实**向量：`2026-10-30T01:00:00Z` 按 UTC 判应是谷、按 `Asia/Shanghai` 是峰；测试里 `TimeZone.setDefault(New_York)` 后重跑仍须峰（抓「偷偷读默认时区」）。
>
> **0.11 `scripts/check-cost.py` 不读被测代码**：`--vectors <json>` 与 `--db-copy <db>`（`sqlite3` 只读），断言五条——
> ① `recomputed == costMicroYuan`；② `isPeak == isPeak(createdAt, Asia/Shanghai)`；③ `hit+miss==prompt_tokens` 且 `prompt+out==total_tokens`（取自 `usageJson`）；
> ④ `formulaVersion` 不在白名单 → **exit 2**（不许静默变绿）；⑤ 用 `fractions.Fraction` 走精确有理数交叉比对整数式（抓漏 `+500000`、二次取整、混浮点）。
> 复算真源是**行内** `priceSnapshotJson`（不是 `assets/pricing/deepseek.json`）。末行固定 `rows=N mismatches=M`，`--json`，退出码 0/1/2，形制照 `scripts/check-faithful.py:387-393`。
>
> **0.12 闸门的变红证明**（全在副本上做）：`costMicroYuan+1` → `mismatches=1` exit 1；`isPeak=1-isPeak` → 红；`priceSnapshotJson` 里 `cacheMiss` +1 → **必须红**（专抓「读资产不读快照」的实现）；未变异副本 → `mismatches=0` exit 0。
>
> **0.13 `--db-copy` 本轮跑不了**（`llm_calls` 表 Task 4 才建、行 Task 8 才写）：Task 3 的证据里这一条只能写 `PENDING: 待 Task 4 建表、Task 8 写行，Task 10 真跑`，**禁止用空库跑出 0 冒充通过**。
>
> **0.14 本任务无设备取证**：证据首三行写 `DEVICE_TIME=host-only 2026-…`，不伪造 adb 时间；截图不适用（纯逻辑，无可视界面）。

- Files：`android/app/src/main/assets/pricing/deepseek.json`、`llm/Pricing.kt`（`Rates`/`PriceCard`/`PriceCardSource`/`AssetPriceCardSource`/`Priced`/`isPeak`/`price`）、
  `llm/CostCalculator.kt`（`costMicroYuan`）、`test/.../llm/CostCalculatorTest.kt`、`test/.../llm/PricingTest.kt`（解析 + 不变量）、`scripts/check-cost.py`、
  `docs/evidence/plan3/p3-cost-*`。
- Interfaces：
  `interface PriceCardSource { fun load(): PriceCard }`
  `data class Rates(val cacheHit: Long, val cacheMiss: Long, val output: Long)`
  `data class PriceCard(val version: String, val effectiveFrom: java.time.LocalDate, val provenance: String, val models: Map<String, Pair<Rates, Rates>>, val cardSha256: String)`
  `fun costMicroYuan(rates: Rates, hit: Long, miss: Long, out: Long): Long`
  `fun isPeak(at: java.time.Instant, zone: java.time.ZoneId = java.time.ZoneId.of("Asia/Shanghai")): Boolean`
  `fun price(card: PriceCard, model: String, hit: Long, miss: Long, out: Long, at: java.time.Instant): Priced`
- Steps：
  1. 先写 `p3-cost-01-vectors.json`（10 组边界 + 2 组算术 + 2 组时区反事实，含人手算期望与推导原文）→ 提交（这一提交必须在实现之前）。
  2. 写 `CostCalculatorTest` / `PricingTest` 读该 JSON，跑红 → 存 `p3-cost-00-red.txt`。
  3. 实现 `Pricing.kt` / `CostCalculator.kt` / `deepseek.json` → 绿；`p3-cost-02-unit.txt`（XML 统计）。
  4. 咬合反证：手工把任一 `expect_micro` +1 重跑必红 → 存 `p3-cost-00-red.txt` 末尾。
  5. 写 `check-cost.py`（`--vectors` / `--db-copy` / `--self-test`）→ `p3-cost-03-gate.txt`；变异三条 → `p3-cost-04-mutation.txt`。
  6. 两倍差展示 → `p3-cost-05-peak2x.txt`；`--db-copy` 写 PENDING → `p3-cost-06-dbcopy.txt`。
- Expected：全部整数运算、无浮点、单次取整；`offPeak*2==peak` 与 `peak==2*offPeak`（整除组）都绿；
  `check-cost.py --vectors` → `rows=N mismatches=0` exit 0，三条变异各 exit 1；`--db-copy` 记为 PENDING。

### Task 4 数据库 v2 迁移

> 0. 派工前修订（Lead 按 Mobile App Builder / Evidence Collector 预审裁定，覆盖下文任何冲突）
>
> **0.1 必崩项：`create()` 必须挂迁移**。`android/app/src/main/java/com/dreamkeeper/data/db/DreamDatabase.kt:73-75` 现在没有任何 `.addMigrations(...)`，
> 老库一升级就抛 `IllegalStateException: A migration from 1 to 2 was required but not found`。写完 `MIGRATION_1_2` 后补 `.addMigrations(MIGRATION_1_2)`。
>
> **0.2 SQL 不许手写**：先写实体（`@Entity` + `indices`）→ 编译出 `app/schemas/com.dreamkeeper.data.db.DreamDatabase/2.json`
> → 把 2.json 里每张新表的 `createSql` **逐字**抄进 `MIGRATION_1_2`。手工 SQL 与实体漂移是这类 bug 的第一来源。
>
> **0.3 迁移内容**：
> `ALTER TABLE dreams ADD COLUMN novelSelectedVersion INTEGER;`（可空，免 DEFAULT）
> `ALTER TABLE dreams ADD COLUMN generationStage TEXT;`
> `generation_jobs` **整表新建**（v1 里没有，D268）+ `novel_versions` + `novel_messages` + `llm_calls`
> 索引：`index_novel_versions_dreamId_version`（UNIQUE）、`index_novel_messages_dreamId_createdAt`、`index_llm_calls_dreamId_createdAt`、
> **`index_dream_tags_tagId`**（D269：D27 当初不加的唯一理由是「加索引要付一次迁移」，这次已在付，边际成本 0）
> **`dreams` 不加索引**；**不加任何外键**（v1 四表零 FK，梦是软删除；删梦时在应用层同事务删三表行）。
> 新表索引必须在 `@Entity(indices=[...])` 同步声明，否则 Room 校验会说迁移结果与实体不符。
>
> **0.4 `novel_versions` 插入必须 `OnConflictStrategy.ABORT`，禁止 REPLACE**：`DreamDao.kt:12` 那套 REPLACE 会先删旧行，
> 等于静默删掉一个版本正文与它的 `novel_messages`。版本号用事务内 `SELECT MAX(version)+1`。
>
> **0.5 `save()` 必须带上新列**：`android/app/src/main/java/com/dreamkeeper/data/DreamRepository.kt:139-159` 是 read-modify-write，
> 新列不写会在每次保存时被抹成 null。补 `novelSelectedVersion = existing?.novelSelectedVersion`、`generationStage = existing?.generationStage`，
> 并加回归用例「设 `novelSelectedVersion=2` 后编辑正文，再读仍是 2」。
>
> **0.6 旧列不改类型**：`createdAt` 等保持 INTEGER epoch millis（设计 D7）；改类型 = 整表重建，风险最高收益为零。新表时间列一律 `Long`。
>
> **0.7 迁移测试**（见 §3 的替代方案）：手搭 v1 库 + `Room.databaseBuilder(...).addMigrations(MIGRATION_1_2)` 打开，Room 自校验 schema；
> 同一测试里做 DAO 往返：`NovelVersion` 插一条含中文与换行的正文 → 按 `dreamId` 读回**逐字相等**。
>
> **0.8 升级前留库副本**：打开库前只读探测 `PRAGMA user_version`，== 1 就把 `databases/dreamkeeper.db`（含存在的 `-wal`/`-shm`）
> 复制到 `files/db-backup/dreamkeeper-v1-<ts>.db`；v2 打开成功且 `integrity_check` 通过后再删多余备份、留一份。
> 接在 `DreamKeeperApp` 启动序列里、`audioScan` 之前。
>
> **0.9 设备侧取证形态**：设备上 `databases/dreamkeeper.db` 实测只有 4096 B、0 表、`user_version=0`，数据在 **201,912 B 的 `-wal`** 里 →
> 必须 `force-stop` 后**三件一起拉**（`.db`/`-wal`/`-shm`），宿主 python `sqlite3` 打开；**emulator API 37 无 sqlite3 二进制**。
> `run-as` 只对 debug 包有效；release 的「不丢数据」由同一份迁移 + `2.json` 校验承担（写进未验证）。
>
> **0.10 基线必须含非种子行**：只拿 4 条种子做基线会假绿（destructive 清库后会重新 seed 出同 id 的 4 行）。
> v1 包里要造 2 条非种子数据：① 改 `seed-dream-1` 的 `title`/`novelText`；② 走一次真实录制新建 1 条（带 `rawAudioPath`/`rawAudioMs`）。
>
> **0.11 比对口径**（`scripts/check-migrate.py --dump DIR --out J` / `--diff B A`）：
> `dreams` **20 列逐列等值**（id/ownerId/dreamDate/createdAt/title/rawTranscript/faithfulText/novelText/novelVersion/novelChangeLog/mood/isDraft/deletedAt/updatedAt/rawAudioPath/rawAudioMs/faithfulEditedByUser/novelEditedByUser/remoteId/transcriptStatus）；
> 新列必须全 NULL 且 NULL 计数 == 行数；**`updatedAt` 一个字都不许变**；`tags`/`dream_tags`/`media_assets` 全列（浮点按精确文本、中文按 UTF-8 原文，禁 `ensure_ascii`）；
> `sqlite_master` 表数 6 → 9（含 `room_master_table`）；`PRAGMA user_version` 1→2；`identity_hash` 必变（登记）；`integrity_check` 与 `foreign_key_check` 干净。
> 报告：首行 `tables=9 rows_before=N rows_after=N`，每表一行，末行 `RESULT: GREEN`，exit 0/1。
>
> **0.12 降级只能「留证据」不能「支持」**：`install -r` 回 v1 包 → `logcat -d` 原始崩溃串整段落盘（不许凭记忆写）；
> 结论只写「不可降级 + 升级前留副本」。
>
> **0.13 证据 `p3-mig-*`**：`00-red.txt`（含「因缺 schemas assets 而红」的更正记录）、`01-baseline.json`+`.txt`、`02-upgrade.txt`、`02-after.png`+同名 `.txt`（`PNG_SHA256=`）、
> `03-diff.txt`（`RESULT: GREEN`）、`04-newtables.txt`（DAO 往返 + 删表反证）、`05-downgrade.txt`（原始崩溃串）。
> 首三行：`APK_SHA256=<v2 前16> OLD_APK_SHA256=<v1 前16>` / `DEVICE_TIME=<adb shell date 原文>` / `REPLAY=<整串>`。
> **sha 必须来自 `pm path` → `adb pull` → `sha256sum`**，不许抄本地文件（实测本地三份 APK 互不相同）。
>
> **0.14 反证必须做**：删掉 `MIGRATION_1_2` 里某张新表的 `CREATE` 重跑迁移测试**必须红**。
>
> **0.15 `scripts/check-migrate.py` 落仓库**（纯 stdlib），sqlite3 连接形制照 `scripts/check-cost.py`。
>
> **0.16 顺手把 vectors 登记成 test input**（D266 的后果）：`app/build.gradle.kts` 加 `tasks.withType<Test> { inputs.file(...) }` 指向 `docs/evidence/plan3/p3-cost-01-vectors.json`。
> 这是本任务**允许**改 `build.gradle.kts` 的唯一理由。

- Files：`data/db/DreamEntity.kt`（尾部追加 2 列）、`data/db/{NovelVersionEntity,NovelMessageEntity,GenerationJobEntity,LlmCallEntity}.kt`（新建）、
  `data/db/{NovelVersionDao,NovelMessageDao,GenerationJobDao,LlmCallDao}.kt`（新建）、`data/db/DreamDatabase.kt`（version=2 + `MIGRATION_1_2` + `addMigrations`）、
  `data/DreamRepository.kt`（`save()` 带新列）、`di/AppContainer.kt`、`DreamKeeperApp.kt`（迁移前快照钩子）、`app/schemas/.../2.json`、
  `test/.../data/db/MigrationTest.kt`（手搭 v1 + DAO 往返）、`test/.../data/DreamRepositoryNovelSelectedTest.kt`、`scripts/check-migrate.py`、
  `app/build.gradle.kts`（只加 test inputs）、`docs/evidence/plan3/p3-mig-*`。
- Interfaces：
  `@Database(entities = [...], version = 2, exportSchema = true)`；`val MIGRATION_1_2: Migration`
  `interface NovelVersionDao { @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(v: NovelVersionEntity); @Query("SELECT * FROM novel_versions WHERE dreamId = :dreamId ORDER BY version DESC") suspend fun listByDream(dreamId: String): List<NovelVersionEntity> }`
  `scripts/check-migrate.py --dump DIR --out JSON` / `--diff BEFORE AFTER`
- Steps：
  1. 写实体与 DAO → 编译出 `2.json` → 抄 `createSql` 写 `MIGRATION_1_2`。
  2. 写 `MigrationTest.kt`（手搭 v1 库，含 1 条带 `rawAudioPath` 的梦）→ 未加迁移时**跑红** → `p3-mig-00-red.txt`。
  3. 补迁移 → 绿；DAO 往返（中文+换行）绿；`DreamRepository` 新列回归绿。
  4. 变异：删一张新表 `CREATE` → 必红（记进 `04-newtables.txt`）。
  5. 设备：v1 APK → 种子 + 2 条非种子 → 三件拉库 → `install -r` v2 → 再拉 → `check-migrate.py --diff` → `RESULT: GREEN`。
  6. 降级实验 + 原始崩溃串落盘。
- Expected：`user_version` 1→2；9 张表；20 列逐列等值、`updatedAt` 未变、新列全 NULL；`integrity_check`/`foreign_key_check` 干净；
  DAO 往返逐字相等；删表反证必红；`check-migrate.py` 未变异 GREEN exit 0、变异 exit 1。

### Task 5 LlmClient（OkHttp + org.json）

> **0.1–0.10（Lead 派工前修订，2026-10-30；D286 起登记）**
>
> 0.1 **可注入是硬要求**：生产仍用单例 `OkHttpClient`，但 `DeepSeekClient` 的构造参数必须能注入
> `baseUrl`（默认 `https://api.deepseek.com/`）、`callTimeoutMs`、`readTimeoutMs`、`client`、`sleeper: suspend (Long) -> Unit`（默认 `delay`）、
> `clock: () -> Long`、`jitter: () -> Long`、`RetryPolicy`。测试全部指向 `http://127.0.0.1:<port>/`，否则「超时取消」按 180s 不可测。**超时落地方式写死**：同时注入 `client` 与超时参数时，
> 必须在 `DeepSeekClient` 内 `client.newBuilder().callTimeout(...).readTimeout(...)` 派生，否则注入单例的 300s 会覆盖参数、超时用例挂 180s；
> `baseUrl` 结尾斜杠归一。
> 0.2 **重试留在 client 内，但每次尝试必须可记账**：`ChatResponse.attempts: List<Attempt>`（`attemptNo/httpStatus/latencyMs/errorKind/requestId/startedAtMs`），
> 失败时 `LlmException.attempts` 同样带全。**每个 HTTP 交换（含 429/5xx/`Retry-After`）与每个传输错误各 append 一条**，不是只记最后一次；
> 传输错误的 `httpStatus=null`、`usage=null`（Task 8 记 0 成本）。Task 8 要「每尝试一行 `llm_calls`、成本行数==尝试次数」，client 不暴露 attempts 就必然返工。
> 0.3 **usage 四字段**：`promptCacheHitTokens/promptCacheMissTokens/completionTokens/reasoningTokens`（`Pricing.kt` 的 hit/miss/out 需要），
> 另带 `totalTokens`；`ChatResponse` 还要有 `requestId`、`httpStatus`、`latencyMs`、`finishReason`。
> 0.4 **取消语义**：`suspendCancellableCoroutine` + `invokeOnCancellation { call.cancel() }`；`CancellationException` **一律 rethrow**
> （retry/parse 的 catch 不许把它包成 `LlmException`）；响应体 `use {}` 关闭；cancel 后不得再 resume。
> 0.5 **错误模型**：`LlmException(kind, httpStatus, requestId, attempts, cause)`，
> `kind ∈ {NETWORK, TIMEOUT, RATE_LIMIT, SERVER, AUTH, BAD_REQUEST, EMPTY_CONTENT, INVALID_JSON}`，`isRetryable = RATE_LIMIT|SERVER|NETWORK|TIMEOUT`；
> `EMPTY_CONTENT`/`INVALID_JSON` **不进重试链**（D290，覆盖设计 §9:374），坏返回交给 Task 7 闸门与 Task 8 修复。
> 0.6 **显式 import**：`com.sun.net.httpserver.HttpServer/HttpExchange` 与 `okhttp3.Request/Response` 不许通配。
> 两边都有 `Request`，通配导入直接编译失败（已复现）。
> 0.7 **测试框架**：`@RunWith(RobolectricTestRunner::class)` + `@Config(sdk=[34])`——纯 JUnit 下 `org.json` 是 android.jar stub（"not mocked"）；
> 网络用例用 `runBlocking`，不用虚拟时间（OkHttp 真 IO 在虚拟时间下不可控）。
> 0.8 **wire 格式**：`thinking={"type":"enabled"|"disabled"}`、`reasoning_effort` 只在 enabled 时发（low/high/max）且为顶层字段；
> enabled 时**不发 `temperature`**（API 会忽略，发了只会误导日志）；`response_format={"type":"json_object"}` 仅 `jsonMode=true`。
> 0.9 **边界**：本任务不写库、不改 schema、不碰 `prompts/`；API Key 由调用方传入，日志只许 `sk-…后四位`。
> 0.10 **退避**：基线 1000ms × 2、上限 30000ms、`Retry-After` 优先（`RetryPolicy(maxAttempts, baseDelayMs, factor, maxDelayMs, retryAfterMaxMs)`）；
> 测试注入 suspend sleeper（记录时长并立即返回，或真实 `delay` 小值）与 jitter=0，禁止真实 4s sleep；退避期间取消必须立即返回。

- Files：`llm/LlmClient.kt`（接口 `suspend fun chat(req: ChatRequest): ChatResponse`）、`llm/DeepSeekClient.kt`、
  `llm/ChatModels.kt`（`ChatRequest(model, messages, thinking, effort, temperature, jsonMode, apiKey)`、`ChatMessage(role, content)`、
  `Usage(...)`、`Attempt(...)`、`ChatResponse(content, reasoning, usage, requestId, httpStatus, latencyMs, finishReason, attempts)`、`LlmException(...)`）、
  `test/.../DeepSeekClientTest.kt`（用 `com.sun.net.httpserver.HttpServer` 起本地服务）。
- Interfaces：
  `interface LlmClient { suspend fun chat(req: ChatRequest): ChatResponse }`；
  `class DeepSeekClient(client: OkHttpClient, baseUrl: String, callTimeoutMs: Long, readTimeoutMs: Long, sleeper: suspend (Long) -> Unit, clock: () -> Long, jitter: () -> Long, retry: RetryPolicy) : LlmClient`。
- 要点：`OkHttpClient` 单例、`callTimeout(300s)`、`readTimeout(180s)`；取消用 `suspendCancellableCoroutine` +
  `invokeOnCancellation { call.cancel() }`；`thinking` 与 `reasoning_effort` 是**顶层字段**；`response_format: {type:"json_object"}`；
  **`content` 为空或非 JSON 一律判失败**（服务端会把非法 JSON 换成空格但 `finish_reason` 仍是 `stop`）；
  429/5xx 指数退避 3 次、尊重 `Retry-After`，时钟与 jitter 可注入。
- Steps：先写 8 条用例（200 正常 + 请求体逐字段断言、空 content、非 JSON、429×3 后成功、401 不重试、超时、飞行中取消、
  退避中取消/`Retry-After` 优先），红 → 实现 → 绿；再补 1 条变异（把 `CancellationException` 吞成 `LlmException`，取消用例必须红）。
- Expected：401 只发 1 次请求；429 共 4 次尝试且间隔 1000/2000/4000 ms（sleeper 收到的序列逐项相等）；`Retry-After` 覆盖指数退避；
  空 content 与非 JSON 都抛 `EMPTY_CONTENT`/`INVALID_JSON`；**取消断言用注入的 `EventListener.canceled(call)`**：命中次数≥1、`CancellationException` 原样透传、
  飞行中取消 <1s、退避中取消 <200ms 返回——`call.isCanceled()` 在测试侧拿不到内部 Call，「服务端收到连接断开」也不可观测
  （探针实测 `SERVER_DISCONNECTS=0`），两者都不作断言（D291）。

### Task 6 提示词资产与 PromptRepository

> **0.0（Lead 补充）**：`assets.srcDir` 的 Sync 只许拷 `prompts/`，**不许覆盖 `assets/pricing/`**（Task 3 建）；Sync 目标目录要 `delete false` 或只清 `prompts` 子目录。
>
> **0.1–0.8（Lead，2026-10-30，派工前修订；依据 prompt-engineer 两轮只读预审）**
> - **0.1 EOL 已钉**：`.gitattributes` 有 `prompts/*.md text eol=lf`（D284）。`5b9702ee`/`f189dad8`/`2ebba630`
>   只在 LF 工作区成立；实施者先在 fresh clone 复算（`git clone --no-checkout . /tmp/x && git -C /tmp/x reset --hard`，
>   CRLF 行数必须 0）再把三个 sha256_8 写死。任一不符 → 启动即抛 `IllegalStateException`（fail fast，不降级）。
> - **0.2 槽语法是【…】不是 `{{…}}`（D294）**：三份文件里占位符写作 `【名】`，全库无 `{{}}`（与 plan §5:103 同口径）。
>   9 槽 = faithful 1【待理顺转写】+ novel 3【原始转写】【理顺稿】【标签 / 选填】+ revise 5
>   【原始转写】【当前小说稿】【当前改动清单】【用户这一条意见】【最近三轮对话】；`PromptSpec.slots` 只装这 9 个。
> - **0.3 条件标记必须带载荷（D294）**：非槽【…】全库仅 4 处——faithful.v2:217【只做标点】、:220【上一版违规清单】、
>   :288（在尾部变更记录内，剥尾后消失）、revise.v1:240【上一版违规清单】。`【只做标点】`是纯开关；
>   `【上一版违规清单】`是「若附带则逐条读」的标题，**必须能送渲染好的清单文本**（`extraBlocks`/`markerPayload`），
>   且写死追加在输入块之后；只给 `Set<Marker>` 会让 Task 8 返工。
> - **0.4 槽位元数据**：`SlotSpec(name, required, allowEmpty)`；**7 required / 2 optional**
>   （optional 仅【标签 / 选填】【最近三轮对话】）；`【待理顺转写】` allowEmpty=true
>   （faithful.v2:212「只有输入为空时，才允许输出为空」）。required 槽为 null 抛 `IllegalArgumentException`；
>   optional 槽为 null 不抛。槽名与可空性照此表写死，不许实施者自定。
> - **0.5 剥头到 `## 输入`**：送出串从 `## 输入` 起；frontmatter（kind/version/file/temperature/outputSchema/
>   sourceOfTruth/design/updated）、`# 标题`（faithful:16/novel:15/revise:15）、`> 对齐口径`
>   （faithful:17-20、novel:18-19、revise:18-19）全部剥掉；断言 `startsWith("## 输入")` 且不含
>   `sourceOfTruth:`/`对齐口径`/`kind:`。
> - **0.6 三份统一剥尾「变更记录」（D295）**：faithful.v2:279、novel.v2:319、novel_revise.v1:301 都到 EOF；
>   断言三份送出串都不含「变更记录」；`novel.v2 §4.4` 的完整示例是**有意 few-shot，不要剥**。
> - **0.7 槽提示行一起替换**：9 槽各带一行 `<…>` 提示（如 faithful.v2:26）；渲染必须「替换【名】+其下提示行」，
>   否则发送串残留 9 行占位说明。`faithful.v2:158/160/218/230` 的「§x/设计 §y」是行内引用，可留。
> - **0.8 不写库 / README（D296）**：漂移只 `Log.w`（D285），不建 `prompt_versions`、不改 `DreamDatabase`；
>   `prompts/README.md:30-37` 删「20 条真实梦 + 1–5 分」（D81/d275f4a），`:3` 与 plan:98-99 相反的
>   sourceSets 说法改掉，补一句「槽=【名】，条件标记另行追加」。

- Files：`android/app/build.gradle.kts`（Sync 任务 + `assets.srcDir`）、`llm/PromptRepository.kt`、
  `llm/PromptSpec.kt`（槽位表 + 标记枚举 + 载荷参数）、`prompts/README.md`、`test/.../PromptRepositoryTest.kt`。
- 要点：三 sha256_8 内置断言（0.1）；从 `## 输入` 起（0.5）；三份剥尾（0.6）；9 槽替换（0.2/0.7）；
  required/allowEmpty（0.4）；标记开关+载荷（0.3）；不写库（0.8）；Sync 只拷 `prompts/`（0.0）。
- Steps：先写 9 条用例（哈希向量、`startsWith("## 输入")`、三份无「变更记录」、9 槽全部替换、
  缺 required 抛错、optional 为 null 不抛、`【只做标点】`开关、`【上一版违规清单】`载荷追加在输入块后、
  送出串无注释/无槽提示残留）→ 红 → 实现 → 绿；再跑 `processDebugAssets` + `assembleDebug` 做 0.0 证据。
- Expected：9/0 绿，且变异（不剥尾 / 把 `【上一版违规清单】`当纯开关）至少各红一次；`unzip -l app-debug.apk`
  里三件 `assets/prompts/*.md` 与 `assets/pricing/deepseek.json` 同现（后者 sha 不变）。

### Task 7 五闸门 Kotlin 移植

- Files：`llm/gates/GateResult.kt`（`enum Gate`、`enum GateMode`、`GateResult`、`GateReport`）、`llm/gates/FaithfulGates.kt`（`object FaithfulGates`）、`test/.../llm/gates/FaithfulGatesTest.kt`；向量真源 `docs/evidence/plan3/faithful-gate-vectors.json`（15 条，由 `scripts/check-faithful.py` 直接生成并冻结，`source_sha256=a3d84acbc2b49e33…`）。
- §0.1 API（写死，Task 8 依赖）：`FaithfulGates.check(input, output, mode: GateMode = GateMode.FAITHFUL): GateReport`；`GateReport.failed: Set<Gate>`、`.passed`、`.results`；`GateResult(gate, passed, value: Double?, detail: List<String>, advisory: List<String>)`；`contentChars(s): String`（先删空白、再删 34 字标点，按码点）、`contentCharCount(s): Int`、`isConservativeOk(input, output) = contentChars(input) == contentChars(output)`（T2 逐字等值）。
- §0.2 向量口径：**11 例与 `--self-test` 完全一致（9 红 + 2 绿）**，另加 2 条 revise 边界（正文比 112.5% → revise 绿 / faithful 红；122.5% → 两模式都红）+ 2 条已知误报锁定（输出新增「一下」「七点」→ G1 必红，**不许顺手修**），共 **15 条**。逐例断言 `assertEquals(expectedFailed, report.failed)`，禁止只比总 `pass`（现有 python 自测只比 pass，G1 写成空函数也全绿）。
- §0.3 测试是 **plain JUnit4**（不碰 `android.*`/`org.json`/`Log`），不要 `@RunWith(RobolectricTestRunner)`；向量用 `repoRoot()` 上溯定位（同 `CostCalculatorTest.kt:221`），并在 `app/build.gradle.kts` 加 `inputs.file(.../faithful-gate-vectors.json)`（D273 同款，防 Gradle 假绿）。
- §0.4 常量逐字：PUNCT 34 字（`，。！？；：、,.!?;:…—` + U+3000 + 半角空格 + `\n\r\t` + `“”"'（）()《》【】[]`，单测断言 `PUNCT.length == 34`）；`NEG_PREFIX=不没未别莫`；FILLERS 13 / UNCERTAIN 14 / CAUSAL 8 / SCENE_MARKERS 11 照抄脚本。数词向量是「一楼→三楼」与「过了三十→过了四十」（旧稿「一层→三层」是笔误）。
- §0.5 Python 语义：空白=Unicode 空白（显式 `Char.isWhitespace() || U+00A0 || U+001C..U+001F || U+0085`），不许裸 `Regex("\\s")`；`\d` 改 `\p{Nd}`；bigram/24 字窗/±6 上下文一律**按码点**；`extract_quoted` 取捕获组；G4 同词去重照抄 python 的 `"跨场景新增因果「c」" in join(bad)` 子串语义，每窗每词只取首个 `find`，ctx 保留换行；G5 非重叠计数 + `i > 0` 防 -1 回绕；G2 空输入判红（`value=null`）、G3 空源判绿（`value=1.0`）；detail 截断 G1 前 10 / G3-G5 前 6、G1 advisory 前 12。
- Steps：先落 `GateResult.kt` 可编译空壳 → 测试读向量跑 targeted 得 **15 failed**（红证据）→ 实现 `FaithfulGates` → 15/15 绿 → 变异（G1 恒真，至少 ③④⑤⑥⑦ 五条 G1 向量必红）→ 恢复绿 → 全量。
- Expected：targeted 15/15 绿；全量 30 类 / 326 例（301 + Task 6 的 10 + 本任务 15，以 Task 6 实际并入数为基线）；变异后 ≥5 例红且恢复后逐字相同再绿。

### Task 8 GenerationOrchestrator（含四级失败链与记账）

- Files：`llm/GenerationOrchestrator.kt`（新）、`llm/GenerationResult.kt`（新）、`data/GenerationStore.kt`（新）、
  `data/db/DreamDao.kt`（只加一条窄更新，不动既有语句）、`llm/Pricing.kt`（只在 `PriceCard` 末尾加一个默认参数）、
  `di/AppContainer.kt`（装配）、`ui/detail/DetailScreen.kt`（只给 `novelChangeCount` 加 JSON 分支）、
  `ui/detail/DetailViewModel.kt`（接生成入口与 `GenerationPhase` 映射）、
  `test/.../llm/GenerationOrchestratorTest.kt`（新）、`docs/evidence/plan3/p3-gen-*`。

- §0.1 落点与事务（D314）：新增 `data/GenerationStore.kt`——`interface GenerationStore` + `class RoomGenerationStore(db)`：
  `recordAttempts(List<LlmCallEntity>)`、`recordJob(GenerationJobEntity)`、`commitSuccess(dreamId, draft, changeLogJson, stage): Int`、
  `updateStage(dreamId, stage)`、`callCount(dreamId)`。
  `commitSuccess` 必须是**一个** `db.withTransaction { }`：`novelVersionDao().insertWithNextVersion(draft)` +
  `dreamDao().updateNovelAfterGeneration(dreamId, text, changeLogJson, stage)`，返回新版本号。
  `DreamDao` 新语句只改 `novelText/novelChangeLog/generationStage` 三列，**不写 `novelSelectedVersion`**（选择归 Task 9）。
  Task 8 不许调用 `DreamRepository.save()`——`upsert` 是 `@Insert(onConflict = REPLACE)`，会覆盖转写（D202/§六）。

- §0.2 可测缝（D315）：orchestrator 构造全注入——
  `llm: LlmClient`、`prompts: PromptRenderer`、`store: GenerationStore`、`priceCard: PriceCard`、
  `clock: () -> Long = System::currentTimeMillis`、`id: () -> String`、
  `gate: (input: String, output: String, mode: GateMode) -> GateReport = FaithfulGates::check`、
  `onProgress: (GenerationKind, GenerationStage, Int) -> Unit = { _, _, _ -> }`。
  `interface PromptRenderer { fun render(promptId: String, slots: Map<String, String?>, markers: Set<PromptMarker>, payloads: Map<PromptMarker, String>): RenderedPrompt }`
  + `PromptRepositoryAdapter(repo)` 放在 orchestrator 文件里（`PromptRepository` 本身是 concrete、会自校验指纹，不改成接口）。
  `GenerationKind { FAITHFUL, NOVEL }`、`GenerationStage { T0, T1, T2, T3 }`、gate mode 直接复用 Task 7 的 `GateMode`。

- §0.3 faithful 四级链：`rawTranscript` 为输入，逐档执行：
  - **T0**：`temperature = 0.1`、`thinking = DISABLED`、不带标记。`gate(input, out, GateMode.FAITHFUL)` 全绿且 `out` 非空 → 提交，`stage = T0`。
  - **T1**：`temperature = 0.0`，带 `PREVIOUS_VIOLATIONS` 载荷（由 T0 的 `GateReport` 生成：G1 引文至多 10 条、G4/G5 至多 6 条、其余 advisory 至多 12 条，每条一行）。
    T1 的**网络错误不退档**：同档把重试预算用完即整次失败（`failure = Llm`），**不降 T2**。
  - **T2**：`temperature = 0.0`，只带 `PUNCTUATION_ONLY`。判据只有一条：`FaithfulGates.isConservativeOk(input, output)`——
    即 `contentChars(input) == contentChars(output)` **字符串逐字相等**，不是长度相等、也不是多重集相等（plan:517）。
  - **T3**：不调模型，`faithfulText = rawTranscript`、`stage = T3`、`failure = null`（兜底成功，不是失败）。
  每次进入下一档前先 `onProgress(kind, stage, runAttempt)`；同一档内的 HTTP 重试由 `DeepSeekClient` 负责，
  `runAttempt` 是档序号（1..4），≠ HTTP `attemptNo`。

- §0.4 生成闸门（D319）：进入前必须 `transcriptStatus == OK && rawTranscript.trim().isNotEmpty()`；
  否则直接返回 `failure = Protocol(...)`，**不写任何 `llm_calls` / `generation_jobs` / `novel_versions`**，也不碰 `dreams`。
  NOVEL 的基座统一为 `base = faithfulText?.takeIf { it.isNotBlank() } ?: rawTranscript`，与渲染、校验、UI 同源。

- §0.5 小说化（NOVEL）：单次调用（`temperature = 0.1`、`thinking = DISABLED`、`jsonMode = true`），槽
  `【原始转写】 = base`、`【理顺稿】 = base`、`【标签/选填】 = 标签拼接`；要求返回
  `{"title":..., "body":..., "changeLog":[{"type","detail","quote"}], "mood":...}`。
  `body` 非空才收；`changeLog` 的 `quote` 必须是 `base` 里 **4–40 个码点**（`codePointCount`）的精确子串，找不到就丢弃并计数；
  全丢时存 `[]`（D318）。落库：`novelText = body`、`novelChangeLog = JSON 数组文本`、`novelVersion` 由事务自增。
  `title`/`mood` 只进 `GenerationResult`，**不写 `dreams`**（现有列口径冲突，见 D315；落点归 Task 9）。

- §0.6 记账（D316）：每次 HTTP 交换写**一行** `llm_calls`（`ChatResponse.attempts` 与传输错误逐条）。
  成功行：`usageJson` 写 `{"prompt_tokens","completion_tokens","total_tokens","prompt_cache_hit_tokens","prompt_cache_miss_tokens"}`，
  `promptCacheHitTokens/promptCacheMissTokens/completionTokens/reasoningTokens` 照抄 `Usage`，
  `costMicroYuan = price(...).microYuan`、`formulaVersion = "cost-v1"`、`priceSnapshotJson = priceCard.rawJson`、
  `httpStatus`、`requestId`、`attemptNo`、`latencyMs`、`createdAt = attempt.startedAtMs`、`isPeak` 按 `createdAt` 判定。
  失败行：`usageJson = null`、四个 token 列 `null`、`costMicroYuan = 0`（列 NOT NULL）、`formulaVersion = ""`、
  `error = kind.name`、`priceSnapshotJson` 仍写卡原文。**不允许只导成功行**。
  为此 Task 10 必须同步改 `scripts/check-cost.py`：`error IS NOT NULL` 的行跳过 usage/价卡复算，只断言 `cost == 0 && usageJson IS NULL`；
  summary 打 `failed=N`。Task 8 只负责把数据写成这样，不负责改脚本。

- §0.7 价卡快照（D317）：`PriceCard` 末尾加 `val rawJson: String = ""`（默认值保证既有命名参数构造与 `PricingTest` 不破），
  `parsePriceCardOrThrow` 返回处填入资产原文（`String(rawBytes, UTF_8)`，要能被 `check-cost.py` 读到六键，不是重新序列化）。

- §0.8 结果与进度（D319）：`data class GenerationResult(kind, finalStage, text, version: Int?, changeLogKept: Int,
  changeLogDropped: Int, attempts: Int, costMicroYuan: Long, failure: GenerationFailure?)`；
  `GenerationFailure = Llm(kind: LlmException.Kind, message: String) | Gate(failed: Set<Gate>, detail: String) | Protocol(message: String)`。
  T2/T3 成功时 `failure = null`（UI 只对 `failure != null` 显示失败）。`DetailViewModel` 把回调映射成
  `GenerationPhase { Idle, Running(kind, attempt), Failed(kind, reason) }`，**不新增 orchestrator 全局单例**。

- §0.9 单飞（D321）：`ConcurrentHashMap<String, Mutex>`，per-dream 锁必须包住「检查闸门 → 占位 → 启动」，
  第二个并发调用立即返回 `failure = Protocol("busy")`（不发请求、不写行）。
  `generationStage` 只在成功时写（D320）：失败只靠 `generation_jobs.error` 表达，因此启动时无需清扫遗留状态。

- §0.10 `generation_jobs`：每档一行——成功写 `stage = 最终档`、`attemptNo = 该档 runAttempt`、`error = null`；
  整次失败写 `stage = 失败所在档`、`error = failure` 描述；`createdAt = clock()`。保留历史，UI 取最新一行。

- §0.11 Steps（先红后绿，8 条用例，仅 #1/#8 用真 Room + `@Config(sdk=[34])`，其余 fake `GenerationStore` + fake `LlmClient`）：
  1. **T0 一次过**：gate 绿 → `commitSuccess` 调用一次、`stage = T0`、`llm_calls` 1 行、`generation_jobs` 1 行 `error = null`（真 Room）。
  2. **T0 红 → T1 绿**：T0 输出把「三十」改「四十」（`[G1, G3]`）→ T1 输出干净 → 共 2 次请求、`stage = T1`，
     且 T1 请求体含 `【上一版违规清单】` 与至少一条引文（断言标记载荷，不是断言开关）。
  3. **T1 网络错同档重试**：fake `LlmClient` 第一次抛 `LlmException(NETWORK)`、第二次成功 → 仍 `stage = T1`（不降 T2），`attempts ≥ 2`。
  4. **T1 红 → T2 等值**：T1 仍红 → T2 输出与输入 `contentChars` 逐字相等 → `stage = T2`。
  5. **T2 不等值 → T3**：T2 输出多一个字符 → 整次落 T3、`faithfulText == rawTranscript`、模型总调用数 = 3、`failure == null`。
  6. **quote 全丢**：novel 返回 3 条 quote 都不在 `base` 里 → `novelChangeLog == "[]"`、`dropped == 3`、`novelText` 仍落库、`version == 1`。
  7. **单飞**：同一 `dreamId` 两次并发 generate → 第二次 `failure = Protocol("busy")`、`llm` 只被调用 1 次。
  8. **记账**（真 Room）：构造 4 次 HTTP 交换（2 次 429、1 次传输错、1 次 200）→ `llm_calls` 行数 == 4、
     失败行 `costMicroYuan == 0 && usageJson == null && error != null`、成功行 `costMicroYuan > 0 && usageJson != null`、
     `generation_jobs` 行数 == 档数。
  变异：删掉 T1 的 `PREVIOUS_VIOLATIONS` 注入 → 用例 2 必红；把 T2 判据换成 `contentChars(in).length == contentChars(out).length` → 用例 5 必红。
  恢复后各复绿。

- Expected：`com.dreamkeeper.llm.GenerationOrchestratorTest` 8/8；targeted 绿；变异至少 1 红、恢复后复绿；
  全量 `33 类 / 334 例`（326 + 8）0 失败；证据 `p3-gen-00-red.txt`（8 failed）、`01-green.txt`、`02-mutation.txt`、`03-full.txt`，首三行按 README（host-only 例外）。
  红线：不改既有列定义、不改既有 DAO 语句、不动 `ui` 布局（除 `novelChangeCount`）、不写 `novelSelectedVersion`。

### Task 9 对话改稿 + 多版本 + 选最终稿

- Files：`llm/ReviseOrchestrator.kt`（新）、`llm/RevisionResult.kt`（新）、`data/GenerationStore.kt`（在 Task 8 的
  `commitSuccess` 旁补消息/改稿/选择三类方法）、`data/DreamRepository.kt`（`listVersions`/`listMessages`/`selectVersion`/
  `resolveFinalNovel` + `toDomain` 补 `novelSelectedVersion`）、`domain/Dream.kt`（加 `novelSelectedVersion: Int?`）、
  `ui/detail/ReviseSheet.kt`（新：输入条 + 6 快捷按钮 + 对话气泡）、`ui/detail/VersionStrip.kt`（新：`versionLabel` + 版本条）、
  `ui/detail/DetailScreen.kt`（只改 `NovelTab` 与其调用点、`DetailViewModel` 构造）、`ui/DreamKeeperRoot.kt`（只加一个 `orchestrator` 参数并传下去）、`MainActivity.kt`（只传 `container.orchestrator`，D352）、`ui/detail/DetailViewModel.kt`（对话/版本/选择状态与动作）、
  `test/.../llm/ReviseOrchestratorTest.kt`（新，8 例）、`test/.../ui/detail/VersionStripTest.kt`（新，2 例）、`docs/evidence/plan3/p3-revise-*`。

- §0.1 事务与落点（D330）：REVISE 成功 = **一个** `db.withTransaction`：插 user 消息 → `novelVersionDao().insertWithNextVersion`
  （`origin = "REVISE"`，`prompt*` 三元组取 `RenderedPrompt`）→ 窄 UPDATE `dreams(novelText, novelVersion, novelChangeLog)` →
  插 assistant 消息（`versionId = 新版本 id`）。ASK 成功 = 一个事务只插 user + assistant 两条消息（`versionId = null`）。
  **不使用** `DreamRepository.save()`；`novelSelectedVersion` 只由「选择/撤回」的窄 UPDATE 改。

- §0.2 ASK 还是 REVISE（D331）：改稿模型返回 JSON
  `{"action":"ask"|"revise", "question"?:..., "summary":..., "body"?:..., "changeLog"?:[...]}`。
  `action == "ask"` → assistant 消息 `content = question ?: summary`（单列，D354）、`versionId = null`、**不建版、不动正文**；
  `summary` 另存 `RevisionResult.summary` 供版本线用（不回写消息）；
  `action == "revise"` → `body` 非空才建版。判据只看 `versionId` 与 `action`，**不拿「正文变没变」判**（逐字相同版合法，见用例 3）。

- §0.3 改稿闸门：`gate(currentText, body, GateMode.REVISE)`（G2 在 REVISE 模式下阈值 115%，由 Task 7 的移植保证）。
  单基准口径：**五个闸门都以「当前版本正文」为基准**（D353，部分覆盖 D221 的「G1/G5 对 rawTranscript」——双基准要改
  Task 7 的 `check(input, output, mode)` 签名，越出 Task 9 白名单；当前版本本身已过忠实链 G1–G5）。
  不过 → `temperature = 0.0` 重试一次并带 `PREVIOUS_VIOLATIONS` 载荷 → 仍不过 → **保留旧版**、不建版、返回
  `RevisionResult.failure = Gate(failed, detail)`，旧版正文与 `novelSelectedVersion` 一字不动。

- §0.4 changeLog：`quote` 基准是**当前版本正文**（不是 `faithfulText`），4–40 码点精确子串，找不到丢弃并计数；
  全丢存 `[]`；`k = 保留条数` 用于版本线「改动 k 处」。

- §0.5 消息与历史：user 消息 `content = 用户这一条意见`；assistant 消息 `content = summary`（一句话，≤60 字，模型给）。
  历史取最近 **3 轮 = 6 条**消息，渲染成 `【最近三轮对话】` 槽（`USER:` / `ASSISTANT:` 前缀，跨轮用空行分隔）。
  6 个快捷按钮逐字：太文绉绉 / 细节太多 / 结尾太说教 / 人称不对 / 多点环境 / 少点形容；点一下填入输入框（空则填入、非空则
  末尾加「，」再拼），可继续编辑（D335）。

- §0.6 版本线（D334）：纯函数 `versionLabel(n: Int, summary: String, changeCount: Int): String`
  = `"v$n · $summary · 改动 $changeCount 处"`；`summary` 取该版本对应 assistant 消息的 `content`（不新增列）。
  无对应消息的版本（历史/种子）只用 `"v$n · 无说明"`，**不带改动数**（D355：版本级改动数只有新格式 changeLogJson 能算，
  种子 `SeedData.kt:112-117` 是旧文本，套正则会把这个 seed 的总改动数安到每个版本上）。
  详情页当前行：`selectedVersion != null` → 「当前 vN · 已选中」，否则「当前 vN」（vN = `resolveFinalNovel` 的版本号）。
  标签页「小说化 · 补写 N 处」继续走 `novelChangeCount`（Task 8 已加 JSON 分支 + 旧文本 regex 回退，D318）。

- §0.7 选择与撤回（D336）：`selectVersion(dreamId, version)` 只改 `dreams.novelSelectedVersion`（窄 UPDATE，校验版本存在）；
  撤回 = 选中上一版，**不删除任何版本行**；`resolveFinalNovel(dreamId) = selected ?: maxVersion`。
  列表页不读小说稿（D332：设计 §6.3「列表用挑中版」在 Plan 3 不落地，`DreamCard`/`LibraryScreen` 仍显示 `faithfulText ?: rawTranscript`），
  **详情正文与 Task 10 导出**用 `resolveFinalNovel`，**不要**用 `dreams.novelText`（它永远是最新生成版）。
  `title`/`mood` 无列可落，Task 9 直接丢弃、不写 `dreams.title`（D333）。

- §0.8 UI 三态（D330）：输入条常驻 `NovelTab` 内、正文滚动区之下、`PlaybackBar` 之上；发送中禁输入 + 一行 12sp「正在改…」；
  失败在输入框上方显示「没改成：{闸门人话} · 旧版还在」并保留原文可重发；ASK 后正文区出现助手气泡（无 `vN` 徽标、无版本芯片），
  placeholder 变「回它一句」。**不新增 BottomSheet**，`TagsScreen.kt:406` 的 `MergeSheet` 是 private，不重构它。
  6 chips 用单行 `LazyRow`（高 48dp、chip 高 36dp）；1080x2400@420 下必然横滚，截图只证首屏。

- §0.9 Steps（先红后绿；8 例 `ReviseOrchestratorTest` 用 fake `LlmClient` + fake `GenerationStore`/真 Room 混合，2 例 `VersionStripTest` 纯函数）：
  1. **ASK 分支**：模型返回 `action=ask` → 不建版、内存版号不变、消息两条且 assistant `versionId == null`；
  2. **REVISE 分支**：返回新正文 → 版本号 +1、`origin=REVISE`、`dreams.novelText` 更新、assistant `versionId` 指向新版本；
  3. **逐字相同也建版**：`body` 与旧稿完全一致 → 仍 +1 版（「没改也交一版」）；
  4. **超长判失败**：`body` 正文比 130%（不计标点空白）→ REVISE 模式 G2 红 → 重试仍红 → 保留旧版、旧正文与 `novelSelectedVersion` 不变；
  5. **闸门重试带载荷**：第一次输出新增专名（G1 红）→ 第二次干净 → 成功且第二次请求含 `【上一版违规清单】` 与引文；
  6. **选择生效**：`selectVersion(2)` → `dreams.novelSelectedVersion == 2`、`resolveFinalNovel == v2`（即使已有 v3）；
  7. **撤回**：v3 当前选中 → 撤回 → 选中 v2、版本表仍有 3 行；
  8. **历史只取 3 轮**：预置 5 轮消息 → 请求体 `【最近三轮对话】` 只含最后 3 轮（6 条）。
  变异：`selectVersion` 不落库（只改内存）→ 用例 6 红；ASK 分支改建成版 → 用例 1 红；各自恢复复绿。

- Expected：`ReviseOrchestratorTest` 8/8 + `VersionStripTest` 2/2；全量 **35 类 / 344 例**（334 + 10）0 失败；
  证据 `p3-revise-00-red.txt`、`01-green.txt`、`02-mutation.txt`、`03-full.txt`（host-only 头）。
  设备截图（输入条/chips、ASK 气泡、版本线、选中态、撤回、失败态）归 **Task 10 端到端**，本任务不做设备证据（D336）。
  本任务**不验证**：①生成入口 UI 可点性（由 Task 9b 关闭，D356/D363）；②D221 的残余风险——改稿闸门不再对 rawTranscript
  复查 G1/G5，更早版本里已混入的专名不会被重罚（D353，列为可接受并进 Task 9 验收的「未验证/可接受」段）。

### Task 9b 生成入口接线（UI 可点性，D356/D363）

- 前置：**Task 9 已提交验收**。`DetailViewModel.orchestrator` 参数与 `DreamKeeperRoot`/`MainActivity` 的传参已由 Task 9 落地，
  本任务**复用、不重复改签名**；只允许在参数已存在的前提下调用。
- Files：`llm/GenerationRunner.kt`（新：`interface GenerationRunner { suspend fun generateFaithful(dreamId: String,
  onProgress: ((GenerationStage, Int) -> Unit)? = null): GenerationResult; suspend fun generateNovel(dreamId: String,
  onProgress: ((GenerationStage, Int) -> Unit)? = null): GenerationResult }`——签名必须与
  `GenerationOrchestrator.kt:92-95/:116-119` 逐字一致，Kotlin 默认参数不参与 override 签名，少一个参数就编译不过）、`llm/GenerationOrchestrator.kt`（**只**加 `: GenerationRunner`
  与 `override`，不改任何逻辑）、`ui/detail/DetailViewModel.kt`（构造参数类型改 `GenerationRunner`，`generateFaithful()` /
  `generateNovel()` / `retryGeneration()` + `generationPhase: StateFlow<GenerationPhase>`）、`ui/detail/DetailScreen.kt`（NovelTab 顶部
  两枚按钮「原样整理」「小说化」+ `GenerationPhase` 三态 + 失败「重试」）、`test/.../ui/detail/GenerationPhaseMappingTest.kt`
  （`FakeGenerationRunner` 记录调用，纯逻辑）、`docs/evidence/plan3/p3-wire-*`、`task-9b-report.md`。
- 禁止：改 orchestrator **逻辑**（只许加接口实现）、DAO、数据库、prompts、export；改导航结构；新增依赖；对
  `transcriptStatus != OK` 或 `rawTranscript` 为空的梦发起生成（按钮禁用或一行 Protocol 文案）。
- Steps（先红后绿）：
  1. 先落可编译空壳（按钮 + 状态枚举 + 空回调）→ 跑红。
  2. 实现：按钮 → 对应入口；`Idle → Running → Idle`、失败 `Failed(kind)`；失败态「重试」再次调用同一入口；成功后刷新详情
     （复用 Task 9 的刷新路径，不新增状态源）。
  3. 单测 3 例（`FakeGenerationRunner`）：①两按钮分别命中 faithful / novel 入口（记录 dreamId 与入口名）；
     ②`GenerationPhase` 映射（Running/Failed/Idle）；③失败态「重试」命中第二次调用。
  4. 设备 smoke：`assembleDebug` → `install -r` → 打开一条种子梦（`rawTranscript` 非空、无小说稿）→ 截按钮可见
     `p3-wire-01-buttons.png` → **无 Key** 点「原样整理」→ 截失败态 `p3-wire-02-failed.png`（不要求真实生成，真实 API 归 Task 10）
     → logcat `FATAL EXCEPTION` 计数 0。
  5. 负向（JVM）：**ViewModel 先判** `transcriptStatus != OK` 或 `rawTranscript` 为空 → 直接置 `Failed(Protocol("…"))`、
     **不调 runner**（`FakeGenerationRunner` 调用次数 == 0）；这一层判断在 ViewModel，不在 orchestrator（orchestrator 的
     `generatable` 是第二道闸）；设备侧只截按钮禁用态（`p3-wire-04-disabled.png`，可选，不做 run-as 计数——
     fresh install 的种子梦全走默认 status，无非 OK 梦可点，见 `SeedData.kt:119-130`）。
- Expected：3/3 绿；全量 **36 类 / 347 例**（344 + 3）0 失败（若并入既有类则类数不变，报告须说明差 1 原因）；证据
  `p3-wire-00-red.txt`（host-only 头）、`01-green.txt`、`02-mutation.txt`（回调改成空 → 用例 1 红）、`03-full.txt`；设备两图 +
  同名 txt（`APK_SHA256`/`DEVICE_TIME`/`REPLAY`，截图 `nearWhite=0`）。
- 闸门：**Task 9b 验收段通过后才允许 Task 10 开工**；Task 9 验收段只能写「UI 可点性 PENDING → 9b」。

### Task 10 导出（txt/docx/pdf）+ 端到端验收与证据

- Files：`export/Exporter.kt`（`suspend fun export(dreamId: String, format: ExportFormat): ExportResult`）、`export/TxtExporter.kt`、
  `export/DocxExporter.kt`、`export/PdfExporter.kt`、`ui/detail/ExportSheet.kt`、`ui/detail/DetailViewModel.kt`（导出动作）、
  `ui/detail/DetailScreen.kt`（导出入口两处）、`data/DreamRepository.kt`（读 `resolveFinalNovel` 与选中版本）、
  `test/.../export/TxtExporterTest.kt`、`test/.../export/DocxExporterTest.kt`、`test/.../export/ExportNamingTest.kt`、
  `llm/DeepSeekClient.kt`（D370：只加一行每次尝试的 logcat 日志，供 `failed=N` 独立对账）、
  `scripts/check-export.py`（新）、`scripts/check-cost.py`（D337/D366：必须支持 error 行；新增 `--require-rows` 硬门，
  `.gitattributes`（D377：补 `docs/evidence/plan3/*.txt text eol=lf` 与 `*.db binary`；只加规则，不动已有条目）、
  输出 `failed=N` 与逐行 `[FAILED] <httpStatus> <error> cost=<n> usage=NULL|SET` 格式）、
  `docs/evidence/plan3/p3-export-*`、`docs/evidence/plan3/p3-e2e-*`、`docs/evidence/plan1/ACCEPTANCE.md` 追加 `## 3d 生成与成本证据表`、
  `docs/evidence/plan3/FINAL-REVIEW.md`、`docs/PROGRESS.md`。

- §0.1 导出内容（D342）：正文 = `resolveFinalNovel(dreamId)` 的 `novelText`（`selected ?: maxVersion`，与详情页/列表契约一致）；
  该梦没有小说稿 → 退回 `faithfulText ?: rawTranscript`，文件名带 `-raw` 后缀。头部元信息固定四行：
  `# <dream.title>`、`<dreamDate yyyy-MM-dd HH:mm>`、`标签：a、b、c`（空则省略该行）、空行。确定性口径（D373）：日期走
  **Asia/Shanghai**（D258 先例）；标签按 `tagId ASC` 排列、`、` 连接；头部 title 的换行压成空格（文件名另走清洗）；
  `共 N 字` 的 N 只算**正文**（`contentCharCount(正文)`，不含头部与 footer）。**不用** `dreams.novelText`
  （它永远是最新生成版，不是用户选中的那版）。

- §0.2 三格式（D340/D341）：
  - **txt**：UTF-8 无 BOM、`\n` 换行、正文写完追加一行 `\n共 <N> 字`（`contentCharCount` 不计标点空白）。
  - **docx**：手写 OOXML 三件套（`[Content_Types].xml` / `_rels/.rels` / `word/document.xml`），零新依赖；每个段落一个
    `w:p`（标题是**首段普通段落**，用 run 内 `<w:rPr><w:b/></w:rPr>` 加粗；不引 styles.xml、不写 `Heading1`，D358 口径）、
    正文每行一个 `w:p`；XML 转义 `& < > " '`、文本节点带 `xml:space="preserve"`，剔除 `\u0000-\u0008\u000B\u000C\u000E-\u001F`
    与孤立代理项。
  - **pdf**：`android.graphics.pdf.PdfDocument`（API 19+，仓库 minSdk 26）；A4 595x842pt、页边距 48pt、正文 11sp 自动分页，
    每页页脚 `第 n / N 页`；写完 `close()` 后 `File.length() > 0` 且头 5 字节 `%PDF-`。
  文件名 `<yyyyMMdd>-<title 清洗后前 20 字>-v<n>.{txt,docx,pdf}`，清洗规则：去 `\/:*?"<>|`、连续空白压一个、空标题用 `dream`。

- §0.3 落点与分享（D339）：用 `MediaStore.Downloads` 写入 `Download/DreamKeeper/`（API 29+ 无需权限、不新增 FileProvider、
  不改 manifest）；`ExportResult(uri, displayName, bytes)`；`ExportSheet` 三个按钮 + 成功后一行「已保存到 下载/DreamKeeper/<名>」
  + 「分享」按钮（`ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION`）。失败给可读文案（磁盘满/无存储/未生成小说稿），不静默吞。
  **API 26–28（minSdk 26 但无 `MediaStore.Downloads`）：不做导出**——三个按钮禁用 + 一行「系统版本过低，导出需要 Android 10 及以上」，FINAL-REVIEW 记 PENDING「API<29 未验证」（D379）。

- §0.4 导出闸门（D342）：只有 `resolveFinalNovel != null` 或 `faithfulText ?: rawTranscript` 非空才允许导出；正文为空 →
  `ExportSheet` 按钮禁用 + 一行「这篇还没有可导出的正文」。导出的 docx/txt 正文必须与详情页当前显示**逐字相同**（同一函数取文），
  这是 E2E 断言的锚。

- §0.5 `scripts/check-export.py`（纯标准库，D357）：
  - 前置（两个子命令共用）：`--db` 的 sha256 必须等于 `p3-e2e-08-db-sidecar.txt` 里记的 **`MERGED_SHA256=`**（合并后主库，D371）；
    `DEVICE_DB_SHA256=`（合并前设备主库）只作溯源、不参与断言；`PRAGMA integrity_check` == ok、`user_version` == 2、表数 == 9、`novel_versions` 与 `llm_calls` 均非空（D357 的真正锚，防「DB 与导出一起错」）。
  - `--txt <f> --file-name <name> --from-db <db> --dream-id <id>`：脚本按 `resolveFinalNovel(selected ?: maxVersion)` 取正文、
    按 §0.1 推导期望串 = 头部四行 + 正文 + `\n共 N 字`（含 `-v<n>`/`-raw` 文件名），与文件逐字节比对，并断言实际文件名 == 推导名；
    另断言无 BOM、`\r` 计数 0、footer 的 N 与按正文算出的 N 相等。`--expect-sha` 保留作辅助（只证明「没变」）。
  - `--docx <f> --from-db <db> --dream-id <id>`：zip 三件齐全、`word/document.xml` 可 `xml.etree` 解析；
    按 document 顺序把 `w:p` 归一为 `\n`、拼接 `w:t`（尊重 `xml:space="preserve"`）；docx 期望串 = 头部第 1–3 行（标题/日期/标签，
    标签行空则省）+ `\n` + 正文——**不含** §0.1 的头部空行（D374：空行不单独成段，故不入期望串）；
    首段 == `dream.title` 且首段 run 含 `<w:rPr><w:b/></w:rPr>`（唯一格式锚，必须断言）；段数 == 3（或 2）+ 正文行数；无非法控制字符、`_rels` 关系完整。
  - `--pdf <f>`：`%PDF-` 在 byte 0、尾 200 字节内 `%%EOF`、`/Type /Pages` 存在且 `/Count` == `/Type /Page` 对象数、
    每页 `/Contents` 非空、每页有 MediaBox、全文至少一处 `/BaseFont`（证明文本绘制真的发生，`/Contents` 非空会被页脚恒真满足，
    D374）、字节数 > 2KB、无 `/Encrypt`。**不做文本抽取断言**（流被压缩），也不做像素判定。
  - `--mutation` 自查：先把原件复制到 `.scratch/` 再改（改期望串一个字符、删 docx 一个 `w:p`）→ 必 RED；结束恢复副本并与
    原件 `cmp` 相同（D372：负向对照只在副本上做，不许污染已取证文件）。
  - 子命令统一 `RESULT: GREEN|RED` + 非零退出码，便于证据粘贴。

- §0.6 端到端设备流程（真机/模拟器 emulator-5554，APK 用本次 `assembleDebug` 产物；D360/D361）：
  0. 前置：在设置页**手工**输入 Key（禁用 `dk_debug_save_key`/adb 注入，不截 Key 输入框）；`adb devices` 设备在线；
     记下本次 `assembleDebug` 的 APK 路径与主机 sha256；A9 的 `grep` 命令先写好（D365）。
  1. 安装（`adb install -r`）→ 原文 `Success` → `pm path` → `adb pull` 出 base.apk → 主机 sha256 与设备 `sha256sum` 相等 →
     `aapt2 dump badging` 的包名/版本对齐；只认 pull 出来的 APK。
  2. 用**已有种子梦**（`rawTranscript` 非空、无小说稿）做生成链：点「原样整理」→ 等 T0 原样稿（截 `p3-e2e-01-generated.png`）。
     真实麦克风 STT 录制**不在本步**：若本轮要验 STT，另开一梦真实录制并在 A18 单列；STT 不可用就直接记 PENDING
     （D364：不引入 debug 播种入口，避免新开白名单）。
  3. 点「小说化」→ 等新版本（截 `p3-e2e-02-novel.png`，含版本线与「补写 N 处」）。
  4. 改稿一句（如「删掉最后一句」）→ 截新版本与对话气泡（`p3-e2e-03-revise.png`）；再发一句纯提问 → 截 ASK 气泡
     （`p3-e2e-04-ask.png`，版本数不变）。
  5. 选中 v2 → 截选中态与「当前 v2 · 已选中」（`p3-e2e-05-selected.png`）。
  6. 导出三种格式 → 截导出成功态（`p3-e2e-06-export.png`）→ 等 MediaStore `IS_PENDING` 清（`ls -l` 见 3 文件且 size>0、
     间隔 2s 两次 `sha256sum` 稳定）→ 设备 `ls -l` + `sha256sum` → `adb pull /sdcard/Download/DreamKeeper/` 三件 →
    主机 `ls -l` + `sha256sum`，两侧逐行相等（「N files pulled」原文入库）；三个导出原件**原样**存入 `docs/evidence/plan3/`（保留原文件名）；`EXPORT_SHA256=` 逐文件写在 `p3-e2e-06-export.txt`（不在原件内），按入库 blob 的 LF 字节计；第三方只凭仓库即可复跑 check-export（D378：导出原件豁免证据命名/头三行）。
    同时贴 `SELECT novelSelectedVersion FROM dreams` 与 `SELECT version FROM novel_versions WHERE dreamId=…` 原始行，证明文件名里的 `-v<n>` 就是选中版（D373）。
  7. 系统「文件」打开 txt/docx 截图；pdf 打开态记 PENDING（AOSP 无 viewer），`p3-e2e-07-pdf-open.png` 若拍到则单独说明、
     **排除**在 nearWhite=0 之外（D359）。
  8. 制造一次失败并验证重试：开飞行模式 → 点「小说化」→ 失败态截图（`p3-e2e-09-failed.png`）→ 点「重试」→ 仍失败
     （证明重试走的是同一入口，A23）→ 关飞行模式 → 再点「重试」→ 成功出新版本（截 `p3-e2e-09b-retry.png`）；这步保证 A7 的
     `failed>=1` 有真实行可验，且重试路径有设备证据（D361/D368）。点完失败先等 toast/失败态**渲染落地**再截图；
     该步结束时从 DB 现状确认 `novel_versions` 行数没增加（失败不建版），并在 step 9 拉库后复核。
  9. `adb shell am force-stop com.dreamkeeper` → `run-as ls -l databases/`（db/wal/shm 三个大小 + 设备 `sha256sum` 原文）→
     `cmd /c "... exec-out run-as com.dreamkeeper cat ..."` **三件齐拉**到 `.scratch/e2e-db/` → 主机 sha256 == 设备 sha256；
    然后主机 `python3`（内置 sqlite3）以 **rw** 打开复制出的三件、`PRAGMA wal_checkpoint(TRUNCATE)` 合并 → **合并后的 .db 才是
     `p3-e2e-08-db.db` 证据**；`p3-e2e-08-db-sidecar.txt` 记：三件合并前 bytes+sha（溯源）、`DEVICE_DB_SHA256=`（合并前 .db）、
     `MERGED_SHA256=`（合并后 .db，check-export 断言这个）+ 合并前后 bytes（D371）；所有 check-cost / check-export / 负向对照都只跑在合并后的副本上（D279「wal/shm 不入库」仍成立）。
 10. `check-cost.py --db-copy p3-e2e-08-db.db --require-rows` → 断言 `mismatches=0`、`failed=N`（N == `error IS NOT NULL`
     行数且 >=1），每个 error 行 `cost==0 && usageJson IS NULL`，逐行 `[FAILED]` 原文入库；`failed` 的独立来源 =
     `adb logcat -d | grep DKLLM | grep -c " kind="`（D370 的那行日志），两者必须相等；
     负向对照：**复制** `p3-e2e-08-db.db` 到 `.scratch/`、把一行 error 的 `costMicroYuan` 改成 1 → 必须 RED → 结束校验原证据
     sha256 未变、副本 `cmp` 原文入库（D372）。
 11. 同一批导出的 txt/docx/pdf 跑 `check-export.py` 三个子命令（含 `--mutation`，结束恢复 + `cmp` 原件）+ `scripts/check-faithful.py --input --output`
     （A1–A24 与生成相关项）+ `check-near-white.ps1 -TopOffset 100 -MaxRatio 0` 扫除 pdf-open 外的全部截图。
 12. 大字体与键盘：`adb shell settings put system font_scale 1.3` → 打开有小说稿的梦 → 点输入框弹键盘 → 截图
     `p3-e2e-10-fontscale.png`（输入条不被遮挡）→ `font_scale 1.0` 恢复；A14 以该图 + 目视结论为准（D369）。
 13. 时间线与日志顺序：每步执行时当场 `adb shell date` 落 `p3-e2e-00-timeline.txt`（步骤号 / 设备时间 / 主机时间），
     DEVICE_TIME 必须与该步窗口吻合（不许一批同值，Task 4 教训）；`adb logcat -d` 全量原文在 force-stop 之后、任何重装/清空
     之前抓一次（A15 的 `FATAL EXCEPTION` 计数与 A9 的 Key 命中 0 都从这份原文算）。截图同名 txt 带 `PNG_SHA256` 与
     `nearWhite=0 ratio=0.0000%` 原文。

- §0.7 验收清单（每项一行结论 + 证据文件）：A1 生成链路（T0 原样稿真实入库）；A2 五闸门全绿批次（T2/T3 降级链以 `GenerationOrchestratorTest` #4/#5 的 JVM 证据补充，设备只走 happy path，D367）；A3 版本线/选中/撤回；
  A4 ASK 不建版；A5 txt/docx 设备可打开（开工前先做 5 分钟查看器探针，打不开按同形制登记 PENDING，D376）+ pdf 结构 GREEN + pdf 打开态 PENDING（D359）；A6 成本复算 `mismatches=0` + `failed=N`；A7 失败行记账（`error`/`cost==0`/`usageJson NULL`，`failed>=1`，由飞行模式步骤制造，D361）；
  A8 提示词指纹与三件 sha 一致；A9 Key 只在 Keystore：本轮**手工**在设置页输入 Key（禁用 `dk_debug_save_key`/adb 注入），证据目录与 REPLAY 中 Key 命中 0
  （`grep -r` 原文），截图不含 Key 输入框（D365）；A10 无新依赖（`libs.versions.toml` 未增）；
  A11 表数 9 / `user_version=2` 不被 Task 10 改动；A12 列表页仍显示原样稿（D332 知情）；A13 导出文件名清洗；A14 大字体下输入条不被键盘遮挡（§0.6 步骤 12 的设备截图 `p3-e2e-10-fontscale.png`，D369）；
  A15 全程无崩溃（logcat `FATAL EXCEPTION` 计数 0）；A16 证据头三行齐全且 sha 可复算；A17 复跑命令逐条可执行（JVM/sha 类逐字节复现；E2E 类写「同步骤产生新证据且断言全绿」，D362）；
  A18 PENDING 清单与理由（白名单只收环境限制：无查看器 / 无麦克风 / 无网络代理；**实现缺陷一律 RED，不许 PENDING**，D376）；A19 计划 §9 偏差全部有 D 号；A20 看板 10/10 与验收段；A21 FINAL-REVIEW 结论可被第三方复现；A22 生成入口可点且失败可重试（Task 9b 证据 + §0.6 步骤 8）；
  A23 降级/兜底链有可查证据（T2/T3 走 JVM，`generation_jobs.stage` 与 `llm_calls` 行数对得上；STT 若未验则进 PENDING 白名单）。
  A24 导出下界：API 29+ 三格式 GREEN；API 26–28 只验按钮禁用与文案（若能起 API 28 模拟器则补一张截图，否则 PENDING，D379）。

- §0.8 `docs/evidence/plan3/FINAL-REVIEW.md` 结构固定六节：结论（Plan 3 是否可交付）→ 交付物与提交链 → 证据索引（文件 ↔ 断言）→
  偏差索引（D275–D379）→ PENDING 与理由（含 D379「API<29 未验证」、无 PDF 查看器、模拟器麦克风）→ 复跑命令（逐条可粘贴）。`ACCEPTANCE.md` 的 `## 3d` 只放一张表：能力 / 证据文件 / 结论。

- Expected：`TxtExporterTest` / `DocxExporterTest` / `ExportNamingTest` 全绿（JVM，无设备）；全量 **39 类 / 359 例** 0 失败
  （347 + 12；347 见 Task 9b）；设备端三格式文件全部 `check-export.py` GREEN；`check-cost.py --db-copy` `mismatches=0` 且 `failed=N`；
  截图 `nearWhite=0`（`p3-e2e-07-pdf-open.png` 若拍到则按 D359 排除）；`FINAL-REVIEW.md` 六节齐全、A1–A24 逐项有结论；证据首三行绑定本次 APK sha 与设备时间。

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
| D233 | T1-11：`network_security_config.xml` 的 `base-config` 用空元素 `<base-config />`，不显式写 `cleartextTrafficPermitted="false"` | **Lead 裁定**：以 dispatch 的「base-config 0 行」为准。targetSdk 37 下空 base-config 的默认就是禁止明文，行为等价；显式写反而让验收脚本的口径变成「允许出现一次」 |
| D234 | T1-12：实现基线是 `34b13d2`，不是派工书写的 `0706f31` | `34b13d2` 只在 `0706f31` 之上改了 `docs/PROGRESS.md`（+2/−2），无代码差异 |
| D235 | T1-13：截图没有同名 `.txt` 侧车 | 本任务白名单只列了 png。**Lead 补**：`p3-settings-01-idle.txt` 已按 README 要求补上绑定信息（PNG sha256 / APK sha256 / 设备与时间 / 复跑命令） |
| D236 | T1-14：证据提交 `652ebba` 触及 `docs/**` | **Lead 裁定**：保留。`docs/evidence/**` 是证据的合法落点；「禁改 `docs/**`」指的是计划与设计正文 |
| D237 | T2-1：`KeystoreCipher` 由具体类改成接口 | Robolectric 无任何 Keystore/Cipher shadow、无 `AndroidKeyStore` provider，接口化才能 JVM 测基类逻辑（预审 jar 静态核对） |
| D238 | T2-2：Files 补 `AppContainer`/`DreamKeeperRoot`/`MainActivity`/`SettingsScreen`，UiState 扩成 6 字段 | 原任务书只列 4 个文件，接线缺失会编译失败或 VM 拿不到依赖 |
| D239 | T2-3：加 `BuildConfig.DEBUG` 门闩的 `dk_debug_save_key` / `dk_debug_clear_key` 写入口 | 仓库无 androidTest 源集；没有写入口就拿不到 prefs/logcat 实物证据。debug-only，另附 `p3-key-00-harness.patch`，release 包断言该字符串计数 0 |
| D240 | T2-4：IV 与密文合成**一个** `api_key_packed` 字符串并 `commit()` 落盘 | 两个 pref key 会有「只写了一半」的中间态；`apply()` 后立刻 force-stop 会丢（`LayoutPrefs.kt:33-35` 同因） |
| D241 | T2-5：`save()` 软校验 `sk-` 前缀与长度，只给 warning 不拦保存 | 只支持 DeepSeek 官方但 Key 格式会演进；硬拦会把合法 Key 挡在门外 |
| D242 | T2-6：本任务不发网络请求，「已验证」文案禁止出现 | 可用性校验属 Task 5（`max_tokens=1`）；本任务无法区分「Key 对但被限流」与「Key 错」 |
| D243 | T2-7：不碰 manifest、不加 `dataExtractionRules` | `:16` 已 `allowBackup=false`；D2D/云备份在模拟器无法触发，改 manifest 只会引入未验证变更 |
| D244 | T2-8：证据 5+1 份且每个「0 命中」必须配正对照（CARDS / QAPROBE） | 没有正对照时「过滤器坏了」与「真的没有」不可区分，等于同义反复 |
| D245 | T2-9：证据改走 UI 真路径（`printf … \| adb shell "cat > /data/local/tmp/dk_key.txt"` → `input text "$(cat …)"`） | 实测 `am start --es dk_debug_save_key <key>` 会把 Key 写进 **adbd 自己的** logcat（`I adbd: adbd service requested '…am start … sk-…'`），「全量 logcat needle=0」永远不成立。改走 UI 后顺带证明 `SettingsViewModel`/`SettingsScreen` 真实链路；harness 保留并入库可复跑 |
| D246 | T2-10：多产出 `p3-key-06-release.txt` | release 负向是派工要求「必须自己跑」的项，单独成文件让 01–05 专指设备五证 |
| D247 | T2-11：`grep -c dk_debug_save_key app-release.apk` 对 zip 恒 0，属弱证据 | 补 dex 级 `unzip -p … classes*.dex \| grep -c` = 0，并用 debug `classes8.dex` = 1 做正对照；要更强需开 minify（本任务不开） |
| D248 | T2-12：输入框用 `heightIn(min = 48.dp)` 而非 `height(48.dp)` | 固定高度在大字体/多行会截断；实测输入框 147px（56dp）、按钮 126px（48dp） |
| D249 | T2-13：设置页保存空输入给 `error="先输入 Key 再保存"`，不静默 clear | 「空串 = clear」是 store 层语义（已实现且有用例）；UI 上误点保存就清掉已配置的 Key 是危险交互 |
| D250 | T2-14：`save()` 成功后清空 `draft`，`load()` 不把明文写进任何状态 | 明文只活在输入框草稿里；`maskedKey` 只存掩码。4 份 UI dump 里 needle=0 |
| D251 | T2-15：证据头 `DEVICE_TIME` 取证据窗口内时刻，非秒级单调时间戳 | 设备时钟与主机一致（CST）；`capture-screen.ps1` 不输出时间戳。该字段只用于「证据何时取」，不影响内容复现 |
| D252 | T2-16：首拍带输入法的截图在 `-MaxRatio 0` 下命中 2/69120 采样点（0.003%） | 键盘是系统 UI，不属于 App 主题；`input keyevent 4` 收起 IME 后重拍，四张全部 `nearWhite=0`；首版已删 |
| D253 | T3-1：资产文件删掉自哈希 `sha256` 字段，改由加载器算 `rawBytes` 得到 `cardSha256` 写进快照 | JSON 自指固定点 + 无规范序列化，任何校验器都必然报错 |
| D254 | T3-2：价卡结构改 `models[name] = {peak, offPeak}`，强制 `offPeak*2 == peak` | 原样例 `offPeak` 与 `peak` 同值，「峰谷两倍差」这条验收根本不成立；顶层裸字段与两支并存也说不清哪份是峰价 |
| D255 | T3-3：峰谷时间窗标 `provenance=project-convention` | 09–12 / 14–18 来自 dsh-cost-meter 的工程约定，DeepSeek 官方定价页没有定义峰谷窗口；原 `sourceUrl` 会让人误读成官方口径 |
| D256 | T3-4：新增 `PriceCardSource` 接口 + `AssetPriceCardSource`，测试用 Fake | 纯 JVM 单测读不到 `assets/`；也是 D237 同一形制 |
| D257 | T3-5：`Priced` 改 sealed（Ok/UnknownModel/CardNotApplicable/Overflow），未知 model 前置拒 + `cost=NULL, error='unknown_model'` | 返回裸 `Long` 无法表达失败；写 0 会与「免费」混淆，check-cost 也辨不出 |
| D258 | T3-6：`isPeak` 显式 `ZoneId` 参数；仓库现有 `ZoneId.systemDefault()` 不许照抄 | 换台机器答案就变；`Instant.atZone` 与半开区间才能与 10 组边界一一对应 |
| D259 | T3-7：`deepseek-flash` 的 0.02/1/4 元每百万 token **未二次核对**，待核 | 数值来自 2026-10-29 单次读取；`deepseek-v4-pro` 的 0.15/4.5/13.5 同样是待核值 |
| D260 | T3-8：`scripts/check-cost.py` 复算真源是行内 `priceSnapshotJson`，不是当前资产 | 调价后历史账目必须还能复算；变异实验里「改快照必须红、改资产不许变绿」专门抓这条 |
| D261 | T3-9：`--db-copy` 本轮只能写 PENDING（表 Task 4 建、行 Task 8 写） | 空库跑出 `mismatches=0` 是假绿；真跑排到 Task 10 |
| D262 | T3-10：证据向量 JSON 先于实现提交，测试读同一份 JSON，不另抄常量 | 同义反复的根源是「测试有自己的期望副本」；提交顺序本身即证据 |
| D263 | T3-11：brief §0.8 的通用断言 `peak−2·off ∈ −1..0` **不成立** | 取整是 round-half-up，差值可达 **+1**：arith-02（v4-pro, hit=miss=1, out=0）peak=5、off=2、delta=+1。测试与 `p3-cost-05` 改为断言 **−1..1**，并把这个反例留作见证 |
| D264 | T3-12：新增 `.gitattributes`（超出 Files 白名单） | 仓库 `core.autocrlf=true` 且此前无 `.gitattributes`，fresh clone 会把 JSON 转 CRLF，使内容寻址的 `cardSha256` / `JSON_SHA256` 失效。只固定 `assets/pricing/*.json` 与 `docs/evidence/plan3/*.json` 为 LF |
| D265 | T3-13：新增公开 `parsePriceCard(rawBytes)`（brief Interfaces 未列） | 加载器与单测共用同一解析入口；否则测试要么复制解析逻辑，要么只能靠 Robolectric AssetManager |
| D266 | T3-14：vectors JSON 不是 Gradle test 输入 | 改 JSON 后普通重跑报 UP-TO-DATE（BUILD SUCCESSFUL 1s，测试未执行）；本任务所有红/绿/咬合命令均加 `--rerun`。**Task 4 起把 vectors 登记为 test input** |
| D267 | T4-1：**不建 `prompt_versions` 表**（作废 §3 里该表与 Entity/Dao 要求） | 与 D5/D150 及 `DreamDatabase.kt:15` 注释「提示词以 `prompts/` 文件为唯一真源」直接冲突；审计只需 `promptId/promptVersion/promptHash` 三列 |
| D268 | T4-2：`generation_jobs` 必须**整表新建**，并补 `GenerationJobDao` | 计划 §3 原写「沿用现有表、补三列」，但 v1 只有 dreams/tags/dream_tags/media_assets（`1.json`），照写会全机型首启崩 |
| D269 | T4-3：本次迁移**补 `index_dream_tags_tagId`** | D27 当初不加的唯一理由是「加索引要付一次迁移」；这次迁移已在付，边际成本 0，`DreamTagDao.deleteByTag` 与 join 都需要 |
| D270 | T4-4：升级前把 v1 库（含 `-wal`/`-shm`）快照到 `files/db-backup/` | Room 迁移在事务内、失败即回滚，但之后 App 打不开库；release 无 `run-as` → 用户零回滚通道，只能靠升级前副本 |
| D271 | T4-5：迁移测试不用 `MigrationTestHelper`，改「手搭 v1 库 + `Room.databaseBuilder` 打开」 | `room-testing` 只在 `testImplementation` 且没有 `sourceSets` 把 schemas 挂到 test assets，helper 找不到 `1.json`；替代方案与 helper 核心断言等价 |
| D272 | T4-6：`scripts/check-migrate.py` 落仓库；首三行改用 `APK_SHA256` + `OLD_APK_SHA256`（两只 APK） | 迁移证据天然有两只包；README §2 只规定单 APK，属形态差异 |
| D273 | T4-7：`app/build.gradle.kts` 把 `p3-cost-01-vectors.json` 登记为 test input | D266：不登记则改 JSON 后 `testDebugUnitTest` 报 UP-TO-DATE，测试根本没跑 |
| D274 | T4-8：设备侧「只拉 `.db`」是假红，必须三件一起拉 | 实测 `databases/dreamkeeper.db` 只有 4096 B、0 表、`user_version=0`，数据在 201,912 B 的 `-wal` 里；emulator API 37 无 sqlite3 二进制 |
| D275 | T4-9：种子 `seed-dream-1` 的 title/novelText 是宿主 `sqlite3` 改已 pull 的库再推回，不是从 v1 UI 编辑 | v1 详情页没有标题/正文编辑入口（「编辑」只弹「编辑还没做」）；推回前已 checkpoint（.db 57344 B、-wal 0 B），`updatedAt` 未动，所以这行仍是「种子」而不是非种子行 |
| D276 | T4-10：`DreamTagEntity.kt` 不在 Task 4 白名单内但改了（只加 `indices=[Index(["tagId"], name="index_dream_tags_tagId")]`） | Room 只有实体声明 indices 才会把索引写进 `2.json` 并在迁移后校验；手写 DDL 改实体不改，schema 校验必红 |
| D277 | T4-11：派工书 §四.2 写「种子 4 条 + 7 条历史录音」，实测设备上并没有那 7 条 | 设备 dreams=4 行种子；基线改为「4 种子 + 1 条真实录制（`4f3b44fd…`，`rawAudioMs=2400`）+ 1 条宿主改过的种子」，共 5 行（`p3-mig-01` 标题与 D275 已同步更正） |
| D278 | T4-12：升级前快照钩子没进 `DreamKeeperApp.kt`，接在 `AppContainer.database` 的 lazy 首次求值前 | 比启动序列更贴近「打开库之前」，且不依赖 Application 的初始化顺序 |
| D279 | T4-13：`-wal`/`-shm` 已拉取核对但**不入库**（`.gitignore` 不做证据） | 两份 JSON 的 `files` 段各自记 bytes+sha256；空 WAL 下 `-shm` 头部全零属正常形态，不是拷贝造假 |
| D280 | T4-14：证据头 `DEVICE_TIME` 对 02–05 是 21:44 批次统一补记，只有 01 用基线真实设备时刻 21:28:03 | 与 D251 同口径：该字段只表示「证据何时取」，不影响内容复现；真实执行时刻见各文件正文 |
| D281 | T4-15：v1 APK（`.scratch/p3-v1.apk`）被 gitignore，包体不入库 | `p3-mig-05` 的 REPLAY 需先自备该包；其 sha256 前 16 位 `5ff054986f19fc9c` == 头部 `OLD_APK_SHA256`，构建自 plan3-generation HEAD |
| D282 | T4-16：`p3-mig-04-newtables.txt` 原只有变异反证的红，恢复后的绿补在文末；`cmp` 原始输出当时未落盘 | DAO 往返本就由 `fourNewTablesRoundTripChineseAndNewlines` 覆盖；补记归属避免「04 缺 §0.13 要求」的误判 |
| D283 | T4-17：新增 `scripts/check-schema-verbatim.py` 与 `p3-mig-07/08`，把「全量 28 类/289 例」与「SQL 逐字 6/6+8/8」从被忽略日志搬进仓库 | 落在 `.scratch/` 的结论在 clean checkout 无法复现；复跑 `python scripts/check-schema-verbatim.py`（A 6/6、B 8/8、C 全 True） |
| D284 | T6-1：`.gitattributes` 增 `prompts/*.md text eol=lf`（超出 Task 6 Files 白名单） | 仓库 `core.autocrlf=true`；实测 fresh clone 后三份提示词 sha256 从 `5b9702ee/f189dad8/2ebba630` 变成 `9bfb94b9/663bc09d/28685592`（CRLF 291/332/314 行），Task 6 的哈希断言必炸 |
| D285 | T6-2：作废 §5「`prompt_versions` 记账」这半句 | 与 D267 及 `DreamDatabase.kt:15`（提示词以文件为唯一真源）冲突；漂移只打 logcat WARN，不写库 |
| D286 | T5-1：`DeepSeekClient` 的 `baseUrl`/`callTimeoutMs`/`readTimeoutMs`/`client`/`sleeper`/`clock`/`jitter`/`RetryPolicy` 全部构造注入；`ChatResponse` 增 `attempts`（每次尝试一条） | 不注入则测试指不到本地 HttpServer，「超时取消」按 180s 不可测；不出 attempts 则 Task 8 的「每尝试一行 `llm_calls`、成本行数==尝试次数」无法落地（重试留在 client 内是计划原意） |
| D287 | T5-2：取消断言语义改为客户端侧（`call.isCanceled()` + `CancellationException` 透传）；删掉「服务端收到连接断开」 | 探针实测 `SERVER_DISCONNECTS=0`：OkHttp 的取消在客户端就断了，服务端 side 不可观测，原 Expected 写了一条永远无法断言的话 |
| D288 | T5-3：`DeepSeekClientTest` 必须 `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk=[34])`，且 `okhttp3.*`/`com.sun.net.httpserver.*` 显式 import | 纯 JUnit 下 `org.json` 是 android.jar stub（"not mocked"）；两边都有 `Request`，通配导入编译失败（已复现） |
| D289 | T5-4：Task 5 证据头是 host-only 例外（`APK_SHA256=n/a (host-only JVM test; no APK involved)`），文件命名 `p3-llm-*` | README 的首三行（APK sha/设备时间）是为设备证据定的；Task 5 是纯 JVM 单测，本机没有对应 APK，编一个 sha 就是造假 |
| D290 | T5-5：`EMPTY_CONTENT`/`INVALID_JSON` 不进 client 重试链，覆盖设计 §9:374「解析失败按一次失败计入重试链」 | 这两类大概率是确定性坏返回，重试 4 次=4 倍成本；修复归 Task 7 闸门与 Task 8 编排；用户侧有重试按钮兜底 |
| D291 | T5-6：`sleeper` 改 `suspend (Long)->Unit`（默认 `delay`）；超时用 `client.newBuilder()` 派生；取消断言用 `EventListener.canceled` 计数；`Attempt` 补 `requestId`/`startedAtMs` 且每交换必记 | Thread.sleep 版 sleeper 会让退避中取消等满 4s；不派生 builder 则注入单例超时覆盖参数（超时用例挂 180s）；测试取不到内部 Call；峰谷判定必须按尝试时刻（跨 12:00 的重试） |

| D292 | T4-18：Task 4 收尾补测发现 `DbBackup.readUserVersion` 对损坏库会删文件，改传 no-op `DatabaseErrorHandler`，并补 `DbBackupTest` 4 例 | 默认 `DefaultDatabaseErrorHandler` 在 NOTADB/CORRUPT 时 `deleteDatabase()`，而该探针跑在 Room 打开前——等于「备份动作先毁掉要备份的库」（违反 §0.8）；实测改前 `exists=false len=0`、改后 `exists=true len=32` |

| D293 | T4-19：Robolectric 下不灌种子（`AppContainer.seedDemoOnStart`），修掉全量单测随机红 | Application 的 IO seed 协程会与 `DbBackupTest`/`MigrationTest` 删建 `dreamkeeper.db` 抢同一个文件，schema 校验异常被 coroutines-test 记进「下一个用例开始前」；实测 3 次全量里 1 次 `TranscriptWriterTest` 报 `UncaughtExceptionsBeforeTest`（栈顶 `DreamKeeperApp.kt:35`）；与 `audioScanOnStart` 同一门闩模式，生产 debug 不受影响 |
| D294 | T6-1：Task 6 提示词渲染把「槽」与「条件标记」拆成两个入参，槽位写死 9 个【…】+ 7 required/2 optional 元数据，条件标记带载荷（`extraBlocks`） | prompt-engineer 逐行读三份提示词：占位符是 `【名】` 不是 `{{}}`；非槽【…】仅 4 处（faithful.v2:217/220/288、revise.v1:240）；`【上一版违规清单】`必须送渲染好的清单文本，纯 `Set<Marker>` 送不出；若统一当槽，可选槽会被强制填值或漏追加 |
| D295 | T6-2：送出串除剥头外还要剥 novel.v2 尾部「变更记录」整段 | R8：该段在文件末尾、无特殊标记，原计划只剥头会把内部变更记录整段送进模型 |
| D296 | T6-3：剥头锚点从「去掉 `<!-- -->` 块」改为「从 `## 输入` 起」，`prompts/README.md` 随 Task 6 一起对齐 | 只剥注释块会漏掉 frontmatter（kind/version/outputSchema/sourceOfTruth…）、`# 标题`、`> 对齐口径` 三段，全部会进模型；README `:30-37` 与 D81/d275f4a 冲突、`:3` 与 plan:98-99 相反 |
| D297 | T5-1：Task 5 按 8 条用例执行（派工书 Step 2/4 仍写「7 条 / 289+7=296」） | 派工书与计划 §0.1–§0.10 自相矛盾：`332bcd8` 已把契约定为 8 条（多「退避中取消 <200ms」）；干净基线全量应为 289+8=**297**；工作树实测 301 = 289 + `DbBackupTest` 4 例（D292）+ 8 |
| D298 | T5-2：`ChatResponse.latencyMs` 定为端到端（含退避），单次 HTTP 交换耗时看 `attempts[i].latencyMs` | 契约只写 `latencyMs` 未定义口径；Task 8 记账用 `attempts`（每尝试一行），响应级展示取端到端更符合用户感知；已在 KDoc 写明 |
| D299 | T5-3：超时用例显式 `RetryPolicy(maxAttempts=1)`，只验证「分类=TIMEOUT + 总耗时 <2s」，不验证「超时会重试」 | `LlmException.Kind.TIMEOUT.isRetryable=true`，默认 4 次尝试会突破验收里的「<2s」；要验证超时重试需放宽耗时或注入假超时，留给 Task 8 联调 |
| D300 | T5-4：`Retry-After` 只测秒值（`2`→2000ms），HTTP 日期（RFC1123）分支未测；注入的 `clock` 必须是 epoch ms（日期分支依赖它算差值） | 8 条用例只覆盖秒值；日期分支用 `ZonedDateTime.parse` − `clock()`，若 Task 8 传单调时钟（`nanoTime` 量级）会得到被 clamp 成 60s 的错误值 |
| D301 | T5-5：解析失败（`EMPTY_CONTENT`/`INVALID_JSON`）不新增 `Attempt`，而是把最后一条 200 的 `errorKind` 改写为该 kind；请求体额外带 `Accept: application/json`（契约未要求，无害） | 与 D290「解析失败不重试」一致：一次 HTTP 交换仍只有一条 Attempt；若 Task 8 需要「解析失败单独一行」需再约定 |
| D302 | T5-6：`p3-llm-00-red.txt` 对应用例 1 的早期断言（enabled 请求 `messages` 1→2 修正后未重跑红） | 红阶段 8/8 失败由 stub 的 `NotImplementedError` 决定，用例 1 断言修正只放宽了 `messages` 条数（1→2），不改变「8 条全红」结论；红证据保留原样以证明先红 |
| D303 | T5-7：Task 5 全程离线（本地 `HttpServer`），真实 `api.deepseek.com` 未访问；403/5xx/普通 `IOException` 重试、`readTimeout` 单独触发、jitter 随机性均无用例 | 契约 G-3 要求零真实调用；这些路径归 Task 8 真机联调 + Task 10 验收 PENDING |

| D304 | T6-1：Task 6 §0.4 补「allowEmpty 空串语义」——`required && !allowEmpty` 的 null 与空串都抛 IAE，`allowEmpty=true` 两者都放行；用例 9→10（第 10 条 faithful 空输入不抛） | 派工书只写了 null 行为，参数形同虚设；faithful.v2:212 明确空输入是合法场景（Task 8 的 T2 边界会用到） |
| D305 | T7-1：Task 7 向量口径修正为「11 例（9 红 + 2 绿，与 `--self-test` 一致）+ 2 条 revise 边界 + 2 条已知误报 = 15 条」，真源 `docs/evidence/plan3/faithful-gate-vectors.json`（`source_sha256=a3d84acbc2b49e33`，由 Python 直接生成后冻结）；旧稿「11 条变异 + 2 条边界」=13 与 `--self-test` 不符 | 实测 `python scripts/check-faithful.py --self-test` 共 11 例（2 绿 9 红）；旧稿数词向量「一层→三层」与脚本 :354-355 的「一楼→三楼」不符，「一下/七点」在脚本中无执行向量，现补成 G1 必红的两条锁定用例 |
| D306 | T7-2：Task 7 API 写死 `check(input, output, mode)` + `GateReport.failed: Set<Gate>` + `contentChars`/`isConservativeOk` 的落点与签名 | 旧 Files 没有 `mode`（G2 revise 1.15 与 Task 9「G2/G3 对当前版本」都要用），也没写 `Gate`/`GateReport`/两个纯函数的落点；Task 8 的 T2 等值与违规清单注入依赖这些签名 |
| D307 | T7-3：Task 7 端口语义与测试形态——Unicode 空白显式集合、`\p{Nd}`、码点下标、G4 子串去重、G5 回绕保护、plain JUnit + Gradle `inputs.file` 防假绿 | 实测 Java 默认 `\s` 不匹配 U+3000/U+00A0/U+001C、`\d` 不匹配全角３，`String.length` 按 UTF-16 会切开代理对；纯函数无 Android 依赖，20 个 Robolectric 类都是因为碰 Android/org.json，本测试不需要 |
| D308 | T6-2：派工书第 10 条用例（必填槽空串/纯空白抛 IAE）在实施者启动后才补入契约，交付实为 9 例；Lead 在验收时补第 10 条（`PromptRepositoryTest` 第 6b 节），红由变异提供：`isBlank()`→`isEmpty()` 时该例红 `expected java.lang.IllegalArgumentException to be thrown, but nothing was thrown` | 实测契约收紧晚于 subagent 启动（subagent 无法被 `send_message` 寻址，只能靠文件），交付 9/9 红绿可信但缺 §0.4 空值覆盖；补测后 targeted 10/10、全量 311 绿（`p3-prompt-05-empty-required.txt`） |
| D309 | T6-3：`processDebugAssets` 在 AGP 9.5 不存在（`Cannot locate tasks that match ':app:processDebugAssets'`），资产证据改用 `:app:mergeDebugAssets :app:assembleDebug --rerun-tasks` | 实测报错原文见 `p3-prompt-03-apk-assets.txt`；判定标准不变（解包看 `assets/prompts/*` + pricing sha） |
| D310 | T6-4：`assets/prompts/` 打进全部 10 个 md（含 CHANGELOG/README/旧版 `novel.v1`、`faithful_cleanup.v1`），非只三件，包体 +约 93KB | 计划 §5 原文即 Sync `prompts/*.md`，未筛是照计划执行；运行期只按 `PromptSpec.assetPath` 读三件，旧版文件不被引用，收窄只需在 Sync 加 include（P2，不阻断） |
| D311 | T6-5：§0.1「fail fast」与 D285 的取舍落地为「先 `Log.w` 再抛 `IllegalStateException`，不写库」 | 两条要求不冲突：D285 要求漂移可观测（logcat），§0.1 要求启动即失败（抛异常）；两者串行执行，未回退成静默 |
| D312 | T6-6：派工书「30 类 / 310 例」类数笔误，实际 31 类 / 310 例（新增 1 个测试类） | 实测 XML `classes=31 tests=310`；新增测试类必然 +1 类，310 = 301 + 9 |
| D313 | T6-7：Sync 接线试错未留在代码里——`addGeneratedSourceDirectory` 编译错（`Inapplicable candidate(s): var destinationDir: File`）、Provider 入 `assets.srcDir` 被 AGP 拒（`You cannot add Provider instances to the Android SourceSet API.`），最终 `File` + `preBuild` 依赖 | 实测报错原文见 `task-6-report.md` 与 `android/app/build.gradle.kts` 注释；两版方案都不可行，留注释避免后来者重踩 |

| D314 | T8-1：事务落点改为新增 `data/GenerationStore.kt`（`interface` + `RoomGenerationStore`），`commitSuccess` 用 `db.withTransaction` 包 `insertWithNextVersion` + 窄 UPDATE；`DreamDao` 新语句只改 `novelText/novelChangeLog/generationStage`，不写 `novelSelectedVersion` | 仓库已在 `DreamRepository.kt:80/105/260`、`SeedData.kt:48` 使用 room-ktx `withTransaction`，不需新依赖；`DreamRepository.save()` 走 `@Insert(onConflict = REPLACE)`（`DreamDao.kt:12-13`），Task 8 复用它会把转写覆盖成旧值（与 D202 同类风险） |
| D315 | T8-2：可测缝——新增 `GenerationStore` 接口；orchestrator 加 `PromptRenderer` 适配器、gate 函数参数、`clock`/`id` 注入；`title`/`mood` 不写 `dreams`，只进 `GenerationResult` | `PromptRepository` 是 concrete 且自校验指纹，改接口会牵连 Task 6 的 10 条用例；`dreams` 无 mood 列，设计 §4「三字段一次交回」与现有列口径冲突，落点归 Task 9 |
| D316 | T8-3：`llm_calls` 失败行口径：`costMicroYuan=0`、`formulaVersion=""`、`usageJson=null`、四个 token 列 `null`、`error=kind.name`、`priceSnapshotJson` 照写卡原文；Task 10 必须同步改 `check-cost.py` 跳过 `error IS NOT NULL` 行 | `2.json` 里 `costMicroYuan`/`formulaVersion` 是 NOT NULL，plan §4 的「未知 model 写 cost=NULL」无法落地；设计「绝不静默丢弃失败」要求每尝试一行；只导成功行会漏账 |
| D317 | T8-4：`PriceCard` 末尾加 `val rawJson: String = ""` 存价卡资产原文 | `check-cost.py:127-167` 需要六键原文复算，重新序列化会有键序/数字格式差；默认值保证既有命名参数构造与 `PricingTest.kt:101` 不破 |
| D318 | T8-5：`novelChangeLog` 新口径存 JSON 数组 `[{"type","detail","quote"}]`；全丢存 `[]`；`novelChangeCount` 加 JSON 分支并保留旧文本 regex 回退 | `DetailScreen.kt:105-108` 现在用正则从「补写 N 处」抽数，种子里是旧文本格式（`SeedData.kt:112-117`）；两格式并存必须靠前缀分支，否则种子行的「补写 2 处」会消失 |
| D319 | T8-6：生成闸门 = `transcriptStatus == OK && rawTranscript.trim().isNotEmpty()`；T3 兜底 `failure = null`（不算失败） | Plan 2 的 PENDING/FAILED 说明转写未定稿；T3 是设计里的保守兜底，记成失败会让 UI 把「已退回原始记录」显示成错误 |
| D320 | T8-7：`generationStage` 只在成功时写，失败只靠 `generation_jobs.error`；启动不清扫遗留状态 | 失败时若也写 `generationStage`，重启后 UI 无法区分「正在生成」与「上次失败」；`generation_jobs` 已带 `stage/error/createdAt` |
| D321 | T8-8：单飞用 per-dream `Mutex`，锁包住「检查闸门 → 占位 → 启动」；并发第二个调用返回 `Protocol("busy")`，不发请求不写行 | 先检查后占位会双开；Busy 不写 `llm_calls` 是为守住「行数 == 尝试数」的 Task 10 断言 |
| D322 | T8-9：Task 8 测试口径 8 例，仅 #1/#8 需真 Room + Robolectric，其余 fake；全量期望 33 类 / 334 例 | 与 Task 5「能 fake 就 fake」一致；真 Room 只用于原子性与记账两处不可替代的断言 |

| D323 | T7-1：`SPEECH_VERBS` 计划写「8 字」，脚本实测 7 字（说喊道问答叫嚷），实现从脚本 | `scripts/check-faithful.py` 是真源；Kotlin 与脚本逐字一致，计划常量清单为笔误 |
| D324 | T7-2：计划 Expected 写「30 类 / 326 例」，实际 **32 类 / 326 例** | 基线 31 类/311 例（Task 6 补测后），新增 1 个测试类 → 32；311+15=326。实测 XML `classes=32 tests=326` |
| D325 | T7-3：空白判定不用 Java 默认 `\s`，显式枚举 Python `\s` 全集（0x09–0x0D、0x1C–0x1F、0x20、0x85、0xA0、0x1680、0x2000–0x200A、0x2028/2029、0x202F、0x205F、0x3000，共 25 码点） | Java 默认 `\s` 只 6 个 ASCII 空白，漏 U+3000/U+00A0/U+2007/U+202F；会话里曾实测全角空格会漏判 |
| D326 | T7-4：`GateResult.advisory` 默认 `emptyList()`；验收只断 `failed` 集合/顺序与 `passed`，不断 `value/detail/advisory` 内容 | G1/G4/G5 无建议时字段可为空；内容级断言留到 Task 8 的提示载荷用例（#2）覆盖 |
| D327 | T7-5：15 条向量写成 15 个独立 `@Test`（`case01`..`case15`），不用参数化/循环 | 逐例失败在 XML 里可直接定位（红相 `15 tests completed, 15 failed`），变异时能精确数红例数（7 例） |
| D328 | T7-6：`p3-gates-01-green` 是变异恢复后的**二次**绿跑（首次绿后先做变异，恢复后再跑并留证） | 证据首三行 `DEVICE_TIME=host-only 2026-10-30T14:50:26Z` 对应第二次跑；恢复后 `sha256 a855bb9f…` 与备份一致 |
| D329 | T7-7：Task 8 计划 §0.3 的 T2 判据签名修正为 `FaithfulGates.isConservativeOk(input, output)`（Task 7 实作定双参），原稿 `isConservativeOk(report)` 不可编译 | Task 7 的 `isConservativeOk` 直接吃两个字符串并比 `contentChars` 全等；Task 8 orchestrator 传 `(input, out)` 即可 |

| D330 | T9-1：Files 补 `ui/detail/DetailScreen.kt`（`NovelTab` 集成与其调用点两处）；新增 `ReviseSheet.kt`/`VersionStrip.kt`，不用 BottomSheet | `NovelTab` 是 `DetailScreen.kt:486-503` 的私有 composable，调用点 `:177`；`TagsScreen.kt:406` 的 `MergeSheet` 是 private，复用只能整体重构，超出 Task 9 白名单 |
| D331 | T9-2：ASK/REVISE 判据 = 模型 JSON `action` + `novel_messages.versionId`（null=ASK），不拿「正文变没变」判 | 「逐字相同版」是合法 REVISE（D335/用例 3），用正文变化判会把「没改也交一版」误判成 ASK |
| D332 | T9-3：列表页仍显示 `faithfulText ?: rawTranscript`，设计 §6.3「列表用挑中版」不在 Plan 3 落地 | `DreamCard.kt:37`、`LibraryScreen.kt:204` 是 Plan 1 卡片契约，改它会连带改列表用例；Plan 3 范围内只让详情与导出读 `resolveFinalNovel`，列表留 Plan 4 |
| D333 | T9-4：小说化返回的 `title`/`mood` 直接丢弃，不写 `dreams.title` | `dreams` 无 mood 列、title 在 Plan 1 由用户填；`novel_versions` 也无这两列，加列要第三次迁移，收益不抵成本（与 D315 同一判断） |
| D334 | T9-5：版本线摘要取对应 assistant 消息的 `content`（≤60 字一句话），版本表不新增列 | `novel_versions` 12 列已冻结（Task 4），加列要迁移；消息表天然有 `versionId`，一对一可取 |
| D335 | T9-6：6 个快捷按钮点一下填入输入框（空则填、非空则末尾「，」追加），可编辑；撤回 = 选中上一版，不删版本行 | 设计 §6.3「点一下填入输入框，可再编辑」；删版本行会破坏 `(dreamId, version)` 唯一序与 export 引用 |
| D336 | T9-7：Task 9 只做 JVM 证据（8+2 例）；设备截图与「正在改」瞬态归 Task 10 端到端 | ASK 是模型概率行为、瞬态是毫秒级，设备上无法稳定复现；端到端真机流程（录制→生成→改稿→选版→导出）在 Task 10 一次取证更可信，也与 README 的 APK sha 首三行口径一致 |
| D337 | T9-8：Task 10 Files 补 `scripts/check-cost.py`，Expected 补 `failed=N` 与 error 行断言 | D316 已要求失败行落库；现有 `check-cost.py:60` 对未知 `formulaVersion` 直接 exit 2，不改脚本会在 Task 10 必红；只断 `mismatches=0` 会把 error 行漏成假绿 |

| D338 | T10-1：PDF 验收只做结构级（头/尾/页数/非加密/字节数），不做像素级非空白；视觉校验用设备「文件」打开截图，打不开记 PENDING | 像素级要设备端 `PdfRenderer`，Robolectric 对 `PdfDocument` 写真实 PDF 字节的支持不确定；与其假装绿，不如把「能打开」降级成可判的结构断言 + 一张截图 |
| D339 | T10-2：导出落点 `MediaStore.Downloads` → `Download/DreamKeeper/`，不新增 FileProvider、不改 manifest | API 29+ 自己插入的 MediaStore 行无需权限；`adb pull /sdcard/Download/...` 直接可取，比 `run-as` 方便；新增 provider 要动 manifest + res/xml，Task 10 白名单尽量别碰 |
| D340 | T10-3：docx 手写三件套 OOXML，零新依赖（不引 poi/docx4j） | Plan 3 全局约束「零新依赖」；仓库已有 zip 能力（`java.util.zip`），三件套足以生成可打开的 docx |
| D341 | T10-4：txt 用 UTF-8 无 BOM + `\n`；文件名清洗规则写死 | Windows 记事本打开无 BOM 的 UTF-8 中文会猜 GBK，但 Plan 3 只在 Android/PC 现代查看器验收；BOM 会破坏 `expect-sha` 逐字比对，所以选择无 BOM + sha 锚 |
| D342 | T10-5：导出正文统一取 `resolveFinalNovel`（selected ?: maxVersion），无小说稿退回 `faithfulText ?: rawTranscript` 且文件名带 `-raw` | 详情页 Task 9 已按此契约显示；导出若取 `dreams.novelText` 会导出「最新生成版」而不是用户选中的版本，与 D332 的已知偏差口径冲突 |
| D343 | T10-6：A1–A21 与五闸门、三个校验脚本对**同一批**导出文件各跑一次，结果写进 FINAL-REVIEW 的同一张表 | 分开跑会出现「脚本绿但对象不是同一份文件」；同批跑才能让第三方只凭证据目录复现结论 |

| D344 | T8-1：生成入口拆两个 `generateFaithful(dreamId)` / `generateNovel(dreamId)`，不合并单入口 | §0.11 #1「`llm_calls` 1 行」、#5「模型总调用数=3」、#8「4 次交换」只在两种生成独立调用时成立；合并会让一次忠实链多出一次 NOVEL 交换，与契约数字直接冲突 |
| D345 | T8-2：`GenerationStore` 增加 `dream(dreamId): Dream?` 只读方法 | §0.4 的生成闸门与 §0.9「锁包住检查闸门」必须在锁内重新读行，缓存旧 Dream 会漏掉转写完成/并发删除 |
| D346 | T8-3：§0.11 #5 拆成两个场景（T2 多一字符 + T2 等长换字），不是单纯「多一个字符」 | 原文场景在「T2 只比长度」变异下仍然不等值，变异杀不掉用例 5；补等长换字后变异必红（实测 1 failed） |
| D347 | T8-4：忠实链 T0/T1/T2 成功也写 `novel_versions`（`origin="GENERATE"`、`novelText=忠实稿`、`changeLog=null`） | 「T0 原样稿真实入库」与 Task 9 的版本读路径都要有行；只写 `dreams.faithfulText` 会让版本线在忠实稿上断档（T3 不写版本，`version=null`） |
| D348 | T8-5：NOVEL 复用 `"T0"` 作 `generation_jobs.stage`/`finalStage` 标签，提交时 `generationStage=null`（COALESCE 保留忠实链档位） | `generation_jobs`/`GenerationStage` 只有 T0–T3 四值，小说化是单次调用无档位语义；写 null 让 `dreams.generationStage` 仍表示「忠实整理走到哪一档」 |
| D349 | T8-6：编排器构造追加 `model`、`apiKey: () -> String?`、`tagsFor: (dreamId) -> List<String>` | `ChatRequest.apiKey` 必填；NOVEL 的「标签 / 选填」槽要标签列表；`model` 要能切 `deepseek-v4-pro`（D264 口径），三者都不能藏在深里 |
| D350 | T8-7：同档重试预算 `MAX_ATTEMPTS_PER_STAGE = 2`（首次 + 1 次重试，仅 `isRetryable`），与 `DeepSeekClient` 内部 4 次叠加，最坏 8 次交换/档 | §0.11 #3 要求「fake 客户端第一次抛 NETWORK、第二次成功」可观测，重试必须在编排层存在；生产里外层只在客户端耗尽 4 次后才触发，概率低。Task 10 的 `check-cost --db-copy` 按实际 `llm_calls` 行数统计，不会漏账 |
| D351 | T8-8：`DetailViewModel.orchestrator` 默认 `null`，导航层未接线，生成入口当前没有 UI 触发点 | `MainActivity`/`DreamKeeperRoot` 不在 Task 8 白名单；强行接线会改导航契约。PENDING 到 Task 9（见 D352） |
| D352 | T8-9/T9-1：Task 9 Files 增加 `ui/DreamKeeperRoot.kt`（只加 `orchestrator` 参数并传给 `DetailScreen`）与 `MainActivity.kt`（只传 `container.orchestrator`） | 生成/改稿入口必须能真点到，否则 Task 10 的 E2E 无法执行；这两处各只改一行传参，不碰导航结构 |
| D353 | T9-2：REVISE 闸门以「当前版本正文」为唯一基准（G1–G5），部分覆盖 D221 的「G1/G5 对 rawTranscript」 | Task 7 的 `check(input, output, mode)` 只有单一 input；双基准要改 Task 7 API（越出 Task 9 白名单），且当前版本本身已过忠实链 G1–G5。风险：更早版本已混入的专名不会被重罚（每轮闸门仍照跑，视为可接受） |
| D354 | T9-3：ASK 的 assistant 消息 `content = question ?: summary`（单列），`summary` 另存 `RevisionResult.summary` | `novel_messages` 只有 content 一列；question 与 summary 双写会让用例 1「两条消息」与气泡文案同时失真 |
| D355 | T9-4：无 changeLogJson 的历史/种子版本，版本线标 `v$n · 无说明`，不带「改动 N 处」 | 版本级改动数只有新格式 JSON 能算；种子 `SeedData.kt:112-117` 是旧文本，套正则会把这个 seed 的总改动数安到每个版本上 |
| D356 | T9-5：生成入口 UI 接线（原样整理/小说化按钮 + `GenerationPhase` 三态 + 失败重试）拆成 Task 9 之后的独立小步 | Task 9 已按 344 例派工，追加会打乱先红后绿基线；D351 的 PENDING 由该小步关闭，Task 10 的 E2E 前必须做完 |
| D357 | T10-7：`check-export.py` 期望值改由 DB 推导（`--from-db <p3-e2e-08-db.db> --dream-id`），`--expect-sha/--expect` 降为辅助 | 自签期望会让「导出错、期望也错」同时绿；DB 是第三方可独立读的真源，还能一并校验 footer「共 N 字」== contentCharCount |
| D358 | T10-8：pdf 断言加强为 `/Type /Pages` 的 `/Count` == 页对象数、每页 `/Contents` 非空、MediaBox 存在、`%PDF-` 在 byte 0；正文不做文本抽取断言 | 原「头+EOF+页数≥1」区分不了「结构对」与「内容空」；PDF 文本流压缩不可抽，只能靠 `p3-e2e-07-pdf-open.png` 目视标题+首句 |
| D359 | T10-9：`p3-e2e-07-pdf-open.png` 排除在 `nearWhite=0` 之外；A5 改为「txt/docx 设备可打开 + pdf 结构 GREEN + 打开态 PENDING」 | 白底 PDF 页自带大面积近白像素，套 nearWhite 必红；AOSP 模拟器无系统 PDF viewer，打开态属环境限制不是实现缺陷 |
| D360 | T10-10：设备取证前必须 force-stop，db/wal/shm 三件齐拉、双侧 sha256 相等才准跑检查；`llm_calls` 加 `rows>0` 硬门 | Task 4 已实测「只拉 .db 得 4096B/0 表」；WAL 未 checkpoint 会读到旧快照；空表假绿是 `scripts/check-cost.py:283-286` 的现存缺陷 |
| D361 | T10-11：E2E 增加「飞行模式制造一次失败」，A7 要求 `failed>=1` 且 error 行 `cost==0 && usageJson IS NULL`，并做负向对照（改一行 cost=1 必 RED） | happy path 下 failed=0，A7 无法被验证；负向对照证明检查器不是恒绿 |
| D362 | T10-12：`scripts/check-faithful-gates.py` 更正为 `scripts/check-faithful.py`（`--input/--output`）；A17 复跑口径分级 | 仓库里没有 check-faithful-gates.py，原命令会空转；JVM/sha 可复现，E2E 依赖录制与采样，只能承诺「同步骤产生新证据且断言全绿」 |
| D363 | T10-13：Task 9b 独立立项（生成入口 UI 接线），排在 Task 9 之后、Task 10 之前，验收通过才放行 Task 10 | 只登记一条偏差不留小节=未计划工作；Task 9 的白名单已含 `DreamKeeperRoot`/`MainActivity` 的传参（改稿导航用），9b 不能二次改签名，只能复用 |
| D364 | T10-14：E2E 的生成链改用**已有种子梦**（rawTranscript 非空、无小说稿），不引入预置 rawTranscript 的 debug 播种入口；真实 STT 录制改为可选项/ PENDING | Task 10 Files 里没有 debug 播种文件，原「预置 rawTranscript」降级不可执行；种子梦足以覆盖 A1/A2/A3/A6/A7 |
| D365 | T10-15：A9 的 Key 必须**手工**在设置页输入，禁用 `dk_debug_save_key`/adb 注入，并对证据目录与 REPLAY 做 Key 命中 0 的 grep | adb 注入会把 Key 写进 logcat/REPLAY，反而违反 A9/G-2 证据纪律 |
| D366 | T10-16：`check-cost.py` 增 `--require-rows`（rows>0 硬门）、输出 `failed=N` 与逐行 `[FAILED] <httpStatus> <error> cost=<n> usage=NULL|SET` | 现脚本 `:516-521` 无这些参数，空表/全绿会假绿；格式写进契约，Task 10 不会漏实现 |
| D367 | T10-17：T2/T3 降级链以 JVM 用例（`GenerationOrchestratorTest` #4/#5）为证据，设备只走 happy path；A22/A23 明确入口可点与兜底证据口径 | 设备上强制 T2 需要可控坏模型返回，E2E 不引入假 client；JVM 已覆盖判据（`isConservativeOk`、T3 不调模型） |
| D368 | T10-18：§0.6 步骤 8 增加「失败 → 重试仍失败 → 联网重试成功」三段，A22 一并验收 | happy path 下重试入口无证据；只测一次失败无法区分「重试没接上」与「重试接上但网络仍断」 |
| D369 | T10-19：A14 大字体遮挡改为设备步骤 12（`font_scale 1.3` + 键盘 + 截图 `p3-e2e-10-fontscale.png`） | 原 A14 在 §0.6 无对应步骤，不可机器判定；加一步即可留证 |
| D370 | T10-20：`llm/DeepSeekClient.kt` 只加一行每次尝试的 logcat 日志（`DKLLM attempt n=<i> status=<s> kind=<k>`），`failed=N` 用 logcat 计数做独立对账 | 同表自证（N == error 行数）不能证明「失败被观察到」；不加日志就没有第三方可读的失败次数真源 |
| D371 | T10-21：设备 DB 三件齐拉 → 主机 rw 打开 `wal_checkpoint(TRUNCATE)` 合并 → **合并后的 .db 才是入库证据**；`p3-e2e-08-db-sidecar.txt` 记三件合并前 bytes+sha、`DEVICE_DB_SHA256=`（合并前 .db）、`MERGED_SHA256=`（合并后 .db）、合并前后 bytes；check-export 前置断言 `--db` sha == `MERGED_SHA256` | 只拉 .db 会拿到 4096B 残缺快照（数据在 WAL），本地三件同目录能跑绿但第三方在证据上必 RED；sidecar 只记合并前 sha 会让 check-export 在正确产物上恒 RED（合并必然改写主库） |
| D372 | T10-22：负向对照一律在 `.scratch/` 的副本上做，结束后校验原证据 sha 未变、副本 `cmp` 相同 | 直接改证据会污染已取证文件；`--db-copy` 以 ro 打开也改不了，原流程无法执行 |
| D373 | T10-23：头部与文件名确定性——日期 Asia/Shanghai、标签 `tagId ASC` + `、`、title 换行压空格、「共 N 字」只算正文；`check-export --file-name` 断言文件名 == 推导名（含 `-v<n>`/`-raw`） | 时区/标签顺序/换行不钉死，exporter 与 checker 会各写一套（互红或一致地错）；不验文件名就无法证明导出的是选中版 |
| D374 | T10-24：docx 段映射定死（正文空行也生成空 `w:p`；头部第 4 行空行不单独成段，故**不进** docx 期望串，期望串 = 标题/日期/标签 + 正文），断言首段加粗 `<w:rPr><w:b/></w:rPr>`；pdf 补 `/BaseFont` 至少一处 | 不定义段映射会让「段数==期望行数」假绿/假红；头部空行若既不入段又留在期望串，字面实现必恒 RED；`/Contents` 非空会被页脚恒真满足 |
| D375 | T10-25：§0.6 步骤 6 等 MediaStore `IS_PENDING` 清且 sha 稳定两次再 pull；每步当场抓 `adb shell date` 落 timeline；`adb logcat -d` 在 force-stop 后、重装/清空前抓全量 | MediaStore 未落盘会 pull 到半截文件；DEVICE_TIME 事后补会重演 Task 4 的「一批同值」；logcat 清了就再也拿不到 A15/A9 原文 |
| D376 | T10-26：A5 的「txt/docx 设备可打开」开工前 5 分钟做查看器探针；PENDING 白名单只收环境限制，实现缺陷不许 PENDING | AOSP 镜像常常没有 txt/docx 查看器，当天才发现会让口径当场失效；PENDING 不设白名单会变成「红都记 PENDING」的后门 |
| D377 | T10-27：`.gitattributes` 补 `docs/evidence/plan3/*.txt text eol=lf` 与 `docs/evidence/plan3/*.db binary`；Task 10 Files 白名单加 `.gitattributes`；`EXPORT_SHA256` 按入库 LF 字节计 | 实测 `core.autocrlf=true` 下 fresh clone 把 `p3-mig-00-red.txt` 转 CRLF、sha256 由 `f6992210…` 漂到 `f604be2c…`；导出 txt 入库后第三方 clone 必在上 `\r` 计数 0 / 逐字节比对 / `EXPORT_SHA256` 上 RED，A21 失效（本机自测会假绿） |
| D378 | T10-28：导出原件平铺进 `docs/evidence/plan3/`，豁免 §1 命名与 §2 头三行；`EXPORT_SHA256=` 逐文件写在 `p3-e2e-06-export.txt` 里 | 导出原件是交付物、文件名是产品行为的一部分（`-v<n>`/`-raw`），按证据命名规则改名就没法证明导出文件名正确；sha 落点不写死，第三方不知道去哪对 |
| D379 | T10-29：导出下限定为 **API 29**（`MediaStore.Downloads`）；minSdk 26 的 26–28 不导出，按钮禁用 + 提示，FINAL-REVIEW 记 PENDING | `android/app/build.gradle.kts:13 minSdk = 26`，而 `MediaStore.Downloads` 是 API 29+；首版证据只在 API 37 模拟器，API 26–28 测不到；补 SAF fallback 会改 `adb pull /sdcard/Download/...` 证据形态，成本高于收益 |

> 规则：每发现一处与计划的偏离就追加一行；「原因」必须写能被别人复现的实测依据，并注明是「我以为的坑」还是「实测结论」。

## 10. 自审记录

- 覆盖设计 v1.5 的 §6.1（失败链）、§6.2（小说化契约）、§6.3（对话改稿）、§7（提示词与版本）、§9（接入层与档位表）、
  §5（v2 数据模型）、§14.8（对话改稿验收）。
- **未覆盖**：真机真实 API 调用（无 Key，全部标 fixture）、PDF 中文逐字抽取（零依赖下不可自动证明）、
  §14.3/§14.4 的盲评（需用户实际使用）、跨设备同步（设计明确不做）。

---

### Task 1 验收（Lead，2026-10-30）

- 交付：`059a821`（12 文件 +275，全部在白名单内）+ `652ebba`（证据 2 件）。
- Lead 独立复跑：`testDebugUnitTest + assembleDebug + assembleRelease` 全绿；**23 测试类 / 251 用例 / 0 失败**（XML 统计）。
- aapt2 复核（`build-tools/36.0.0/aapt2.exe`）：debug APK `3286734d…` → `INTERNET` 命中 1、`networkSecurityConfig` 1 行；release APK `24063848…` → `networkSecurityConfig` **0 行**、`INTERNET` 1 命中。
- 装机（emulator-5554）：`TotalTime 2567 ms`；设置入口 `uiautomator` bounds **126×126 px**；截图 `p3-settings-01-idle.png` 近白 `nearWhite=0/69,120`；`FATAL EXCEPTION`/`ANR in com.dreamkeeper` 0 命中。
- 变异验证：`Settings.route` 改成 `"calendar"` → `DreamRouteTest` 3 条红；改回即绿（实施者执行，Lead 认可其方法）。
- 结论：**Task 1 验收通过**。未证事项按 D229（未发真实请求）保留，等 Task 5 假服务器。

---

### Task 2 验收（Lead，2026-10-30）

- 交付：`19bb3d6`（8 文件 +700/−19）+ `6f188e1`（证据 15 件）；`AndroidManifest.xml` **零改动**（`git show --name-only` 计数 0）。
- Lead 独立复跑：全量 **24 测试类 / 262 用例 / 0 失败 / 0 错误**（XML 统计，基线 251 + `ApiKeyStoreTest` 11）。
- 我读证据核对：`settings.xml` 只有一个 `<string name="api_key_packed">`，base64 80 字符（12B IV + 31B ct + 16B tag），明文 needle 计数 0；全目录扫描 `CARDS` 正对照命中 `layout_prefs.xml`（证明扫描器不哑）。
- release APK（`9aa8a8a9…`）`classes*.dex` 里 `dk_debug_save_key`/`dk_debug_clear_key` 计数 **0**，debug `classes8.dex` 计数 1（正对照成立）。
- 截图 `p3-key-05-configured.png`：设置页显示 `已保存（还没验证）` + 掩码 `sk-…3056`；四张截图近白 `nearWhite=0`。
- D245 是本轮最有价值的发现：**`am start --es` 传敏感值会把明文写进 adbd 的 logcat**——以后所有「传 Key / 传口令」的证据脚本都不许走 intent extra。
- 结论：**Task 2 验收通过**。未验证项按报告 §4 保留（真机 TEE、`bmgr` 真跑、切进程原子性、非 ASCII Key、联网校验属 Task 5）。

---

### Task 3 验收（Lead 追溯，2026-10-30）

- 交付：Task 3 实现六个提交（`0218952` → `8af057e`，末条 `8af057e` 为证据与向量侧车）；证据 `p3-cost-00`–`p3-cost-06` 共 8 个文件全部入库。
- Lead 独立复跑（Task 4 期间补做）：`python scripts/check-cost.py --vectors docs/evidence/plan3/p3-cost-01-vectors.json` → `rows=14 mismatches=0`、exit 0；`--self-test` → `rows=6 mismatches=0`、exit 0。
- 证据锚点：vectors sha256 `cf8b2431…6440e`；APK 13342382 B sha256 `661d1d6b…ac093`，包内 `assets/pricing/deepseek.json` sha256 `becbdd14…1744d`。
- 当时测试基线：**26 类 / 284 例 / 0 失败**（XML 统计）；Task 4 的 28/289 比它多 2 类 5 例，增量全部来自 Task 4 新增（`MigrationTest`、`DreamRepositoryNovelSelectedTest`）。
- 未完成项：`--db-copy` 只能写 PENDING（D261），真跑排到 Task 10；`deepseek-flash` 价卡数值待二次核对（D259）。
- 结论：**Task 3 追溯验收通过**（本任务当时漏写验收段，补此段闭合交付链）。

---

### Task 4 验收（Lead，2026-10-30）

- 交付：`c800a87`（31 文件 +3088/−10，全部在白名单内）+ `d681247`（`p3-mig-06` 负向对照入库）+ `b2054ba`（证据补件 7 文件 +176/−5）。
- Lead 独立复跑：`:app:testDebugUnitTest --rerun-tasks` 全绿，补测后 XML **29 类 / 293 例 / 0 失败 / 0 错误 / 0 跳过**（`p3-mig-07-full-tests.txt` 是补测前 28 类/289 例的快照）；`MigrationTest` 单跑 3/3 PASS。
- 设备侧逐字节复核（evidence collector 独立做，Lead 复读）：装机包 sha256 `661d1d6b…ac093` == 证据 APK；设备 `databases/dreamkeeper.db` sha256 `30e8566c…230e` 与入库 `p3-mig-02-after.db` 相同；`files/db-backup/dreamkeeper-v1-20261030-214235.db` sha256 `ea73f2a4…c87e` 与入库 baseline 相同；live dump 对 baseline `--diff` → `RESULT: GREEN`。
- 迁移口径：`tables 5→9`、`user_version 1→2`、`dreams rows 5` 逐列 20 列等值、`updatedAt` 5/5 不变、两新列 10/10 为 NULL、`identity_hash 3150a0bc…→166666e5…`、integrity ok、FK 0。
- 变异反证：删 `novel_messages` 的 CREATE+索引 → 3/3 红（`Migration didn't properly handle: novel_messages(com.dreamkeeper.data.db.NovelMessageEntity)`）；恢复后 3/3 绿（`p3-mig-04`）。负向数据对照：改 `seed-dream-1` 的 title → `RESULT: RED: 1 处不一致`、exit 1（`p3-mig-06`）。
- SQL 同源：`MIGRATION_1_2` 的 8 条 CREATE 与 `2.json` 的 8 条新增**逐字一致**（A 6/6、B 8/8，`p3-mig-08-sql-verbatim.txt`）；`create()` 挂 `addMigrations` 1 次、`fallbackToDestructiveMigration` 代码调用 **0 处**（只在注释里出现 2 次字样）。
- 降级：装回 v1 包 → `IllegalStateException: A migration from 2 to 1 was required but not found`（`p3-mig-05`）；唯一兜底是升级前 `files/db-backup/` 副本（D270）。
- 收尾补测（D292）：`DbBackupTest` 4/4 绿——`v1SnapshotCopiesDbAndPresentSidecarsWithTimestampedName`（真 WAL 三件 `db=20480/wal=8272/shm=32768`）、`v2LibraryPrunesOldSnapshotsKeepingOnlyNewest`（留一份 + 连旧 `-wal/-shm` 一起清）、`missingDatabaseReturnsNullAndDoesNotCreateBackupDir`、`unreadableDatabaseReturnsNullWithoutThrowing`。补测暴露 `DbBackup.readUserVersion` 用默认 `DatabaseErrorHandler` 在 NOTADB/CORRUPT 时会**删掉原库文件**；已改 no-op handler（`NO_DELETE_ON_CORRUPTION`），实测 `corruptProbe exists=true len=32 expectedLen=32`（改前 `exists=false len=0`）。
- PENDING（不阻断验收，均有 D 号/归属）：release 包升级与物理真机（不可 `run-as`）、迁移耗时（§14.5 归 Plan 4）、WAL-resident 形态、`p3-mig-05` 的 v1 包需自备（D281）、`DbBackup.isIntact` 未同步硬化（不在 `readUserVersion` 调用链，P2 观察项，D292）。
- 结论：**Task 4 验收通过**（迁移逻辑 + 证据链闭合；上面 PENDING 项不改变「数据一字不丢」的结论）。

### Task 5 验收（Lead，2026-10-30）

- 交付：`05af3a2`（基于 `332bcd8`，8 文件 +1368）：`llm/LlmClient.kt`（`suspend fun chat` + `RetryPolicy`）、
  `llm/ChatModels.kt`（`Thinking`/`ReasoningEffort`/`ChatRequest`/`Usage`/`Attempt`/`ChatResponse`/`LlmException` 8 种 kind + `isRetryable`）、
  `llm/DeepSeekClient.kt`（345 行：派生超时、取消、指数退避 + `Retry-After`、usage 解析、Key 掩码）、
  `test/.../llm/DeepSeekClientTest.kt`（515 行 / 8 例，Robolectric sdk=34 + 本地 `com.sun.net.httpserver`）、
  `docs/evidence/plan3/p3-llm-{00-red,01-green,02-mutation,03-full}.txt`。
- Lead 独立复跑：`--tests "com.dreamkeeper.llm.DeepSeekClientTest" --rerun-tasks` → BUILD SUCCESSFUL 16s（8/8）；
  全量 `--rerun-tasks` **连续两次** BUILD SUCCESSFUL（24s），XML **30 类 / 301 例 / 0 失败 / 0 错误 / 0 跳过**（含 D292 的 `DbBackupTest` 4 例，同时验证 D293 的随机红门闩已生效）。
- 契约逐条：§0.1 注入 + `client.newBuilder().callTimeout/readTimeout`；§0.2 每次交换/传输错误各一条 `Attempt`；
  §0.3 usage 四字段 + `requestId/httpStatus/latencyMs/finishReason`；§0.4 `CancellationException` 透传 +
  `invokeOnCancellation { call.cancel() }` + `ensureActive` 守卫；§0.5 8 种 kind、D290（EMPTY/INVALID 不重试）；
  §0.6 显式 import；§0.7 Robolectric；§0.8 `thinking` 顶层、enabled 才发 `reasoning_effort` 且不发 temperature、
  `response_format` 仅 jsonMode；§0.9 无 DB/prompts 引用；§0.10 退避 1000/2000/4000 + `Retry-After` 优先并夹 60s。
- 变异：`invokeOnCancellation { call.cancel() }` → 空实现，用例 7 红（`EventListener.canceled` 实际 0，
  `8 tests completed, 1 failed`），恢复后 8/8 绿；原文 `p3-llm-02-mutation.txt`。
- 证据：`p3-llm-00`（8/8 失败，6×`NotImplementedError`）→ `01`（8/8 绿 + XML）→ `02`（变异 1/8 红）→
  `03`（全量 30 类 / 301 例 / 0 失败）；host-only 头 `APK_SHA256=n/a` / `DEVICE_TIME=host-only <UTC>` / `REPLAY=gradlew`。
- PENDING（D297–D303）：真实 API 联调、403/5xx/普通 IOException 重试、`Retry-After` 日期形式、`readTimeout`、
  jitter 随机性；`latencyMs` 口径与「解析失败是否单独占一行」在 Task 8 记账时确认。
- 结论：**Task 5 验收通过**。

---

### Task 6 验收（Lead，2026-10-30）

- 交付：`92ec146`（10 文件：`android/app/build.gradle.kts` +21 行 `syncPromptAssets`/`assets.srcDir`/`preBuild`，
  `llm/PromptSpec.kt` 110 行，`llm/PromptRepository.kt` 227 行，`PromptRepositoryTest.kt` 259 行 9 例，
  `prompts/README.md`，证据 `p3-prompt-00-red`/`01-green`/`02-mutation`/`03-apk-assets`/`04-full`）
  + Lead 验收补测第 10 例（`p3-prompt-05-empty-required.txt`）。
- Lead 独立复跑：`--tests "com.dreamkeeper.llm.PromptRepositoryTest" --rerun-tasks` → BUILD SUCCESSFUL 15s（补测后 **10/10**）；
  全量 `--rerun-tasks` → BUILD SUCCESSFUL 24s，XML **31 类 / 311 例 / 0 失败 / 0 错误 / 0 跳过**。
- 白名单核对：`git show --stat 92ec146` = 10 文件；`git diff 9f4367d 92ec146 -- prompts/` 只有 `README.md`（三份提示词本体零改动）；
  `android/gradle/libs.versions.toml` diff 空；未碰 `data/ ui/ service/ audio/ stt/ llm/` 既有文件、`docs/superpowers`、`scripts`、`AndroidManifest.xml`。
- 资产落包（Lead 解包复核）：`app-debug.apk` 13352646 B sha256 `3e7e1e7f…2929`；
  `assets/pricing/deepseek.json` 678 B sha256 `becbdd14…174d`（未被覆盖）；
  `assets/prompts/faithful.v2.md` sha256 `5b9702ee…2f57`、`novel.v2.md` `f189dad8…84e2`、`novel_revise.v1.md` `2ebba630…9a13`，与真源一致；
  另含 7 个非运行期 md（D310，P2）。
- 渲染契约：9 槽（7 必填 + 2 选填，`【待理顺转写】` allowEmpty）、剥头到 `## 输入`、剥尾到「变更记录」EOF、
  槽行 + `<…>` 提示行整体替换、标记块插在输入块闭合围栏之后、`【上一版违规清单】` 必须带载荷、指纹漂移先 `Log.w` 再抛（D311）。
- 变异（三处，各自恢复后复绿）：删剥尾循环 → 用例 3 红；`carriesPayload` 退化成纯开关 → 用例 8 红；
  Lead 补测的 `isBlank()`→`isEmpty()` → 用例 10 红（原文 `p3-prompt-05` 第 3 节）。
- PENDING（不阻断）：D309（`processDebugAssets` 在 AGP 9.5 不存在，改用 `mergeDebugAssets`）、D310（多打 7 个 md，约 +93KB）、
  D312（类数笔误）、D313（Sync 接线两种失败方案留注释）；真实 API 与设备侧行为归 Task 8/10。
- 结论：**Task 6 验收通过**。

---

### Task 7 验收（Lead，2026-10-30）

- 交付：`b4242b4`（父 `0ba8571`，8 文件 / +1152）：`llm/gates/GateResult.kt`（`Gate`/`GateMode`/`GateResult`/`GateReport`，
  `failed` 用 `LinkedHashSet` 保 G1→G5 序）、`llm/gates/FaithfulGates.kt`（412 行，G1–G5 全算不短路、按码点复刻 Python 语义）、
  `test/.../FaithfulGatesTest.kt`（310 行、15 个 `@Test`、plain JUnit4 + 手写 MiniJson）、
  `android/app/build.gradle.kts`（仅 +2 行 `inputs.file(faithful-gate-vectors.json)`）、
  `docs/evidence/plan3/p3-gates-{00-red,01-green,02-mutation,03-full}.txt`。
- Lead 独立复跑：targeted `--rerun-tasks` → **15/15**（XML `tests=15 failures=0 errors=0`）；
  全量 `--rerun-tasks` → XML **32 类 / 326 例 / 0 失败 / 0 错误 / 0 跳过**（31+1 类、311+15 例，与 §0.5 的 32/326 一致）。
- 语义核对（Lead 脚本逐字比 `scripts/check-faithful.py`）：9 组常量全部命中——`PUNCT` 34、`CN_NUM` 16、`MEASURE` 27、
  `SPEECH_VERBS` 7、`NEG_PREFIX` 5、`FILLERS` 13、`UNCERTAIN` 14、`CAUSAL` 8、`SCENE_MARKERS` 11、`DIRECTION_WORDS` 26；
  `PUNCT.length == 34` 由用例 01 自身断言；空白走显式 25 码点（D325）、数字 `\p{Nd}`、bigram/24 码点窗/±6 ctx 全 `IntArray`；
  15 条向量的期望 `failed` 集合与 `faithful-gate-vectors.json` 逐例一致（12/13 另跑 FAITHFUL 对照）。
- 变异：G1 恒返回空集 → **7 例红**（02/03/04/05/07/14/15，满足 ≥5），其余 8 例仍绿；恢复后 `sha256 a855bb9f…` 与备份一致、复绿
  （`p3-gates-02-mutation.txt`）。
- 证据：`p3-gates-00-red`（15/15 失败，全 `NotImplementedError`，XML `tests=15 failures=15`）→ `01-green`（15/15，D328 二次绿跑）
  → `02-mutation` → `03-full`（`class_count=32 test_count=326 failure_count=0`）；首三行 `APK_SHA256=n/a` / `DEVICE_TIME=host-only <UTC>` / `REPLAY=gradlew ...`。
  两真源未动：`git diff b4242b4^ b4242b4 -- scripts/check-faithful.py docs/evidence/plan3/faithful-gate-vectors.json` 为空。
- PENDING（不阻断）：15 向量外 fuzz；`value/detail/advisory` 内容未断言；未接运行时、未出 APK（归 Task 8）。
- 结论：**Task 7 验收通过**。

### Task 8 验收（Lead，2026-10-30）

- 交付：`cd297d8`（13 文件 +1869/−5，父 `7386f5f` 实为 `7386e5f`）：`llm/GenerationOrchestrator.kt`(658 行)、`llm/GenerationResult.kt`(60)、
  `data/GenerationStore.kt`(111)、`data/db/DreamDao.kt`(+27 一条 COALESCE 窄 UPDATE)、`llm/Pricing.kt`(+4 `rawJson`)、
  `di/AppContainer.kt`(+44)、`ui/detail/DetailScreen.kt`(+17 `novelChangeCount` JSON 分支)、`ui/detail/DetailViewModel.kt`(+63)、
  `test/.../GenerationOrchestratorTest.kt`(551 行/8 例)、证据四件。
- Lead 独立复跑：`--tests "com.dreamkeeper.llm.GenerationOrchestratorTest" --rerun-tasks` BUILD SUCCESSFUL 14s（8/8）；
  全量 `--rerun-tasks` BUILD SUCCESSFUL 23s → XML **33 类 / 334 例 / 0 失败 / 0 错误 / 0 跳过**（与 Task 7 的 32/326 差 1 类 8 例，符合预期）。
- 契约核对（逐行读码）：T0(token0.1)/T1(0.0+载荷)/T2(仅 `isConservativeOk`)/T3(不调模型) 与 §0.3 一致；`commitSuccess` 是**一个**
  `db.withTransaction`（`insertWithNextVersion` + `updateNovelAfterGeneration`）；`DreamDao` 新语句只写 `novelText/novelChangeLog/generationStage`
  且用 COALESCE（`null` 不覆盖）；失败行 `costMicroYuan=0`、`usageJson=null`、`formulaVersion=""`；`recordSuccess` 把
  `ChatResponse.attempts` 逐条落行（含成功前的 429/传输错）；单飞用 `locks.getOrPut(dreamId){Mutex()}.tryLock()`，busy 不发请求；
  `priceSnapshotJson = priceCard.rawJson` 原文入库；未调 `DreamRepository.save()`、未写 `novelSelectedVersion`、未改既有列/DAO 语句。
- 变异：①删 T1 `PREVIOUS_VIOLATIONS` 注入 → 8 tests / 2 failed（#2、#3），恢复 8/8 绿；②T2 判据换成只比长度 → 8 tests / 1 failed（#5），
  恢复 8/8 绿。原文在 `p3-gen-02-mutation.txt`。
- 证据：`p3-gen-00-red.txt`（8 failed、全 `NotImplementedError`）、`01-green.txt`、`02-mutation.txt`、`03-full.txt`，首三行均 host-only 例外头。
- PENDING（承接）：UI 接线（生成入口当前无触发点，归 Task 9，D351/D352）；真实 DeepSeek 网络/401/429/退避未跑；`check-cost.py` 失败行口径归 Task 10（D316/D337）；
  单飞仅覆盖同进程；价卡峰谷边界分钟未测；`novelChangeCount` 坏 JSON 分支无用例。
- 结论：**通过**（含上述 PENDING，均已在后续任务范围或计划偏差中登记）。
