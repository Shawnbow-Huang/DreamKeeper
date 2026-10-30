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
> `baseUrl`（默认 `https://api.deepseek.com/`）、`callTimeoutMs`、`readTimeoutMs`、`client`、`sleeper: (Long) -> Unit`、
> `clock: () -> Long`、`jitter: () -> Long`、`RetryPolicy`。测试全部指向 `http://127.0.0.1:<port>/`，否则「超时取消」按 180s 不可测。
> 0.2 **重试留在 client 内，但每次尝试必须可记账**：`ChatResponse.attempts: List<Attempt>`（`attemptNo/httpStatus/latencyMs/errorKind`），
> 失败时 `LlmException.attempts` 同样带全。Task 8 要「每尝试一行 `llm_calls`、成本行数==尝试次数」，client 不暴露 attempts 就必然返工。
> 0.3 **usage 四字段**：`promptCacheHitTokens/promptCacheMissTokens/completionTokens/reasoningTokens`（`Pricing.kt` 的 hit/miss/out 需要），
> 另带 `totalTokens`；`ChatResponse` 还要有 `requestId`、`httpStatus`、`latencyMs`、`finishReason`。
> 0.4 **取消语义**：`suspendCancellableCoroutine` + `invokeOnCancellation { call.cancel() }`；`CancellationException` **一律 rethrow**
> （retry/parse 的 catch 不许把它包成 `LlmException`）；响应体 `use {}` 关闭；cancel 后不得再 resume。
> 0.5 **错误模型**：`LlmException(kind, httpStatus, requestId, attempts, cause)`，
> `kind ∈ {NETWORK, TIMEOUT, RATE_LIMIT, SERVER, AUTH, BAD_REQUEST, EMPTY_CONTENT, INVALID_JSON}`，`isRetryable = RATE_LIMIT|SERVER|NETWORK|TIMEOUT`。
> 0.6 **显式 import**：`com.sun.net.httpserver.HttpServer/HttpExchange` 与 `okhttp3.Request/Response` 不许通配。
> 两边都有 `Request`，通配导入直接编译失败（已复现）。
> 0.7 **测试框架**：`@RunWith(RobolectricTestRunner::class)` + `@Config(sdk=[34])`——纯 JUnit 下 `org.json` 是 android.jar stub（"not mocked"）；
> 网络用例用 `runBlocking`，不用虚拟时间（OkHttp 真 IO 在虚拟时间下不可控）。
> 0.8 **wire 格式**：`thinking={"type":"enabled"|"disabled"}`、`reasoning_effort` 只在 enabled 时发（low/high/max）且为顶层字段；
> enabled 时**不发 `temperature`**（API 会忽略，发了只会误导日志）；`response_format={"type":"json_object"}` 仅 `jsonMode=true`。
> 0.9 **边界**：本任务不写库、不改 schema、不碰 `prompts/`；API Key 由调用方传入，日志只许 `sk-…后四位`。
> 0.10 **退避**：基线 1000ms × 2、上限 30000ms、`Retry-After` 优先；测试注入 sleeper 与 jitter=0，禁止真实 sleep。

- Files：`llm/LlmClient.kt`（接口 `suspend fun chat(req: ChatRequest): ChatResponse`）、`llm/DeepSeekClient.kt`、
  `llm/ChatModels.kt`（`ChatRequest(model, messages, thinking, effort, temperature, jsonMode, apiKey)`、`ChatMessage(role, content)`、
  `Usage(...)`、`Attempt(...)`、`ChatResponse(content, reasoning, usage, requestId, httpStatus, latencyMs, finishReason, attempts)`、`LlmException(...)`）、
  `test/.../DeepSeekClientTest.kt`（用 `com.sun.net.httpserver.HttpServer` 起本地服务）。
- Interfaces：
  `interface LlmClient { suspend fun chat(req: ChatRequest): ChatResponse }`；
  `class DeepSeekClient(client: OkHttpClient, baseUrl: String, callTimeoutMs: Long, readTimeoutMs: Long, sleeper: (Long) -> Unit, clock: () -> Long, jitter: () -> Long, retry: RetryPolicy) : LlmClient`。
- 要点：`OkHttpClient` 单例、`callTimeout(300s)`、`readTimeout(180s)`；取消用 `suspendCancellableCoroutine` +
  `invokeOnCancellation { call.cancel() }`；`thinking` 与 `reasoning_effort` 是**顶层字段**；`response_format: {type:"json_object"}`；
  **`content` 为空或非 JSON 一律判失败**（服务端会把非法 JSON 换成空格但 `finish_reason` 仍是 `stop`）；
  429/5xx 指数退避 3 次、尊重 `Retry-After`，时钟与 jitter 可注入。
- Steps：先写 7 条服务端用例（200 正常 + 请求体逐字段断言、空 content、非 JSON、429×3 后成功、401 不重试、超时/取消、
  `Retry-After` 优先），红 → 实现 → 绿；再补 1 条变异（把 `CancellationException` 吞成 `LlmException`，取消用例必须红）。
- Expected：401 只发 1 次请求；429 共 4 次尝试且间隔 1000/2000/4000 ms（sleeper 收到的序列逐项相等）；`Retry-After` 覆盖指数退避；
  空 content 与非 JSON 都抛 `EMPTY_CONTENT`/`INVALID_JSON`；**取消的断言在客户端侧**：`call.isCanceled()` 为 true 且
  `CancellationException` 原样透传——「服务端收到连接断开」不可观测（探针实测 `SERVER_DISCONNECTS=0`），不作断言。

### Task 6 提示词资产与 PromptRepository

> **0.0（Lead 补充）**：`assets.srcDir` 的 Sync 只许拷 `prompts/`，**不许覆盖 `assets/pricing/`**（Task 3 建）；Sync 目标目录要 `delete false` 或只清 `prompts` 子目录。

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
- Lead 独立复跑：`:app:testDebugUnitTest --rerun-tasks` 全绿，XML **28 类 / 289 例 / 0 失败 / 0 错误 / 0 跳过**（`p3-mig-07-full-tests.txt`）；`MigrationTest` 单跑 3/3 PASS。
- 设备侧逐字节复核（evidence collector 独立做，Lead 复读）：装机包 sha256 `661d1d6b…ac093` == 证据 APK；设备 `databases/dreamkeeper.db` sha256 `30e8566c…230e` 与入库 `p3-mig-02-after.db` 相同；`files/db-backup/dreamkeeper-v1-20261030-214235.db` sha256 `ea73f2a4…c87e` 与入库 baseline 相同；live dump 对 baseline `--diff` → `RESULT: GREEN`。
- 迁移口径：`tables 5→9`、`user_version 1→2`、`dreams rows 5` 逐列 20 列等值、`updatedAt` 5/5 不变、两新列 10/10 为 NULL、`identity_hash 3150a0bc…→166666e5…`、integrity ok、FK 0。
- 变异反证：删 `novel_messages` 的 CREATE+索引 → 3/3 红（`Migration didn't properly handle: novel_messages(com.dreamkeeper.data.db.NovelMessageEntity)`）；恢复后 3/3 绿（`p3-mig-04`）。负向数据对照：改 `seed-dream-1` 的 title → `RESULT: RED: 1 处不一致`、exit 1（`p3-mig-06`）。
- SQL 同源：`MIGRATION_1_2` 的 8 条 CREATE 与 `2.json` 的 8 条新增**逐字一致**（A 6/6、B 8/8，`p3-mig-08-sql-verbatim.txt`）；`create()` 挂 `addMigrations` 1 次、`fallbackToDestructiveMigration` 代码调用 **0 处**（只在注释里出现 2 次字样）。
- 降级：装回 v1 包 → `IllegalStateException: A migration from 2 to 1 was required but not found`（`p3-mig-05`）；唯一兜底是升级前 `files/db-backup/` 副本（D270）。
- PENDING（不阻断验收，均有 D 号/归属）：release 包升级与物理真机（不可 `run-as`）、迁移耗时（§14.5 归 Plan 4）、WAL-resident 形态、`p3-mig-05` 的 v1 包需自备（D281）、`DbBackup` 四分支单测（Lead 指派 Mobile App Builder 补测中）。
- 结论：**Task 4 验收通过**（迁移逻辑 + 证据链闭合；上面 PENDING 项不改变「数据一字不丢」的结论）。
