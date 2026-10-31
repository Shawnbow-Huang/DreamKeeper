# Plan 6 — 多供应商（DeepSeek / MiMo / MiniMax / Qwen / GLM）+ Key 连通性测试

> 分支：`plan6-providers`（从 `plan5-polish` 的 `e1f34a1` 切出）
> 基线：52 类 / 443 例全绿；release 0.9.0（APK `f27bf225…`，证书 `cf1a5dce…`）
> 目标：LLM 供应商从「只有 DeepSeek」扩到五家，每家独立 Key；设置页可「测试连接」；生成链按所选供应商发请求。
> 用户口径（2026-10-31）：给 API Key 加**测试连通性**；供应商在 DeepSeek 之外加**小米 MiMo、MiniMax、Qwen、GLM**。

## §0.1 范围与不做

**做**
- 5 家供应商的 `ProviderSpec`（端点、鉴权、模型目录、能力位、错误形状）。
- 每家独立 Key 的加密存储（Keystore 同一把主密钥，per-provider 密文槽）+ 旧 DeepSeek Key **零重输迁移**。
- 设置页：「模型服务」区（供应商 chips + 该家 Key 卡 + 测试连接三态）+ 模型/思考档位随供应商联动 + 整页滚动。
- 传输层按 provider+model 能力位构造请求体；DeepSeek 请求体**字节级不回归**。
- 生成链按所选供应商与模型发请求；忠实/小说化/改稿三链语义不变。
- 成本诚实口径：非 DeepSeek 模型无价卡时 `costMicroYuan=0`、`formulaVersion="price-missing"`、`priceSnapshotJson=null`，UI 显示「费用未知」。
- 证据：`docs/evidence/plan6/p6-*`，host-only 头三行；Key 在目录/REPLAY/logcat 三段 grep = 0（含正对照）。

**不做（明确推迟）**
- 模型自动发现 / 探测 / 盲评；自定义 BaseUrl 与代理（SSRF 面，v1 固定端点）；流式输出。
- 每家官方价卡（未知价一律「费用未知」，不猜）；自动降级路由（A 家失败切 B 家）。
- DB 迁移（schema 保持 v3，设置只走 SharedPreferences/Keystore）、视频、iOS、账号、云同步。
- 提示词改动：`faithful.v2` / `novel.v2.1` / `novel_revise.v1.1` 三份正文不改，provider 差异全部收敛到传输层。

## §0.2 供应商矩阵（T0 冻结；标「需实测」的不得写成已验证）

统一假设：OpenAI 兼容 `POST {base}{path}`，`Authorization: Bearer <key>`，JSON body。任何一家实测不符 → 只改 `ProviderSpec`，不改调用方。

| id | label | base + path | v1 模型（默认加粗） | 思考参数 | effort | JSON mode | 陷阱 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `deepseek` | DeepSeek | `https://api.deepseek.com` + `/chat/completions` | **deepseek-flash**、deepseek-v4-pro | `thinking.type=enabled/disabled` | low/high/max | `response_format=json_object` | 思考开时 temperature 被忽略；默认思考开 |
| `mimo` | 小米 MiMo | `https://api.xiaomimimo.com/v1` + `/chat/completions` | **mimo-v2.6-pro**、mimo-v2.6-flash | `thinking.type=enabled/disabled` | 无（不发） | 支持 | 深度思考下 temperature/top_p 固定 1.0/0.95 → **不发 temperature**；v2.5 已下线 |
| `minimax` | MiniMax | `https://api.minimax.cn/v1` + `/chat/completions`（需实测） | **MiniMax-M3** | `thinking.type=adaptive/disabled` | M3 不发 | **不支持**（不发 response_format） | **HTTP 200 + `base_resp.status_code≠0` 是错误**；M3.1-Flash 强制思考，不可做忠实链；temperature 固定 1.0/0.95 → 不发 |
| `qwen` | 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` + `/chat/completions` | **qwen3.8-max**、qwen-plus | **`enable_thinking: true/false`**（不是 thinking.type） | qwen3.8-max: low/medium/xhigh（不得与 thinking_budget 同传） | 支持（json_object 需提示词明确要求 JSON） | Key 与地域绑定，跨区 401；effort 仅部分模型 |
| `glm` | 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` + `/chat/completions` | **glm-5.2** | `thinking.type=enabled/disabled` | glm-5.2: max/xhigh/high/medium/low/minimal/none（v1 只发 low/high/max） | 支持 | GLM-5.3 强制思考不可关（v1 不列）；思考时 temperature 不被忽略 |

**能力位**（`ProviderCapabilities`，由 `ProviderSpec` 携带）：
`supportsThinkingParam: Boolean`、`disableThinkingAllowed: Boolean`、`thinkingStyle: THINKING_TYPE | ENABLE_THINKING | NONE`、`effortValues: Set<Effort>`、`supportsJsonMode: Boolean`、`sendsTemperature: Boolean`、`errorShape: HTTP_STATUS | BASE_RESP | MIXED`、`usageShape`。

**v1 只列 5 个模型族、6 个模型 id**；单模型供应商（MiniMax、GLM）UI 显示静态「模型：X」，不摆单选。

## §0.3 Key 存储与迁移（T1）

- per-provider pref 键：`api_key_packed_<providerId>`，密文格式不变（`KeystoreCipher` 现有实现，Base64(iv‖ct)，`commit()` 同步）。
- 旧键 `api_key_packed` **只读回退**：`load("deepseek")` 先读新键，读不到再读旧键（不删除、不重写）；`save("deepseek", …)` 成功后才清旧键（回滚旧版仍可读旧键）。
- 其他 provider 的 `load` 若旧键存在也**不回退**（防止把 DeepSeek Key 当别家 Key 发出去）。
- `ApiKeyStore` 接口加 provider 维度（具体签名由 T1 定，调用方显式传 providerId）；`maskApiKey` 不变。
- 迁移幂等：重复 `load` 不改写；解密失败按未配置处理并**不清除**其他家密文。

## §0.4 测试连接（T3）

- 独立于生成链：`ConnectionTester` 单次请求（`RetryPolicy(maxAttempts=1)`）、独立 client（`callTimeout=20s`、`readTimeout=15s`、`connectTimeout=10s`、`followRedirects(false)`）、不重试、不写 `llm_calls`、不写库、不进 `transcriptionScope`/`generationScope`。
- single-flight：一次只允许一个测试在飞；结果携带 `providerId + nonce`，回调时比对当前选中，不一致丢弃并取消旧请求。
- 生成 Running 时禁用测试按钮（避免同 Key 配额争用）。
- `ChatRequest` 增 `maxTokens: Int? = null`（null 不发，DeepSeek 老请求体逐字不变）；测试连接请求体只用最小公共集 `model + messages + stream=false + max_tokens=1`，**默认不发** thinking/reasoning_effort/response_format/temperature。
- 只测**已保存**的 Key：草稿存在但未保存时按钮禁用；只发一条最短用户消息（`content="ping"`，`max_tokens=1`），**不发任何梦境内容**。
- 思考：能关则关（`disableAllowed && thinkingStyle≠NONE`）；不支持思考的家不发思考字段；`jsonMode=false`；固定 temperature 的家不发 temperature。
- 成功定义：HTTP 2xx **且** 解析出非空 `content`；空 content / 非 JSON 都算失败（沿用生成链语义）。
- 结果：`ConnectionResult(ok, latencyMs, attempts, kind: LlmException.Kind?, httpStatus?)`；UI 用共享 `humanReason(kind)`（与 `DetailViewModel` 抽到同一份文案）。
- 错误映射沿用 8 类 + MiniMax `base_resp` 先判：1004/1001 鉴权类 → AUTH；1002/1008/1013 → RATE_LIMIT；其他非 0 → SERVER。

## §0.5 传输层（T2）

- `LlmClient` 接口**不改**（`suspend fun chat(req): ChatResponse`）；新增 `ProviderChatClient`（按 `ProviderSpec` 构造 body/解析 usage/错误），`DeepSeekClient` 保留为 deepseek 方言实现或改为 `ProviderChatClient(deepseekSpec)` 的包装。
- `AppContainer` 建**单例 OkHttpClient**（连接池/Dispatcher 共用），全部 provider client 复用它；测试连接用 `newBuilder()` 派生短超时 client。
- `ProviderSpec.baseUrl` 是常量（不可由用户编辑）；将来若开放必须 https-only + 拒私有 IP + `followRedirects(false)`（D499）。
- DeepSeek 请求体冻结：T0 先把当前 `buildRequest` 输出（八条用例覆盖的请求）抓成 `p6-contract-01-deepseek-baseline.json`，重构后逐字段 diff 为空。
- body 构造规则：只发该家文档化字段；不带 `stream` 以外的私有字段；`messages` 形状不变。
- usage 解析按 provider：DeepSeek `prompt_cache_hit_tokens/miss`；MiMo/Qwen/GLM `prompt_tokens_details.cached_tokens`；MiniMax 只有 `total_tokens` 时回退 0；缺字段不抛，记 0。
- 错误分类：HTTP 状态映射不变；MiniMax 200+`base_resp` 特殊；网络异常沿用 `transportKind`。

## §0.6 生成链（T2/T4 联动）

- `GenerationOrchestrator` / `ReviseOrchestrator` 从「模型」改为「provider + model」；`GenerationOptions` 升为 `ProviderOptions(providerId, model, novelThinking)`。
- 忠实链要求「思考可关」：`disableThinkingAllowed=false` 的模型不得做 faithful；UI 在 faithful 按钮处给出人话原因（「这家模型关不掉思考，先用 DeepSeek 整理原文」），不静默改 provider。
- v1 **五家都可生成**（v1 模型清单内的模型）；错方言的兜底是「切回 DeepSeek」，不是自动降级。
- 小说化/改稿：按所选 provider；`jsonMode=true`，仅 `supportsJsonMode` 的家发 `response_format`；MiniMax 不发但保留提示词 JSON 契约，解析失败仍 `INVALID_JSON` 不重试（D290）。
- 成本：`PriceCardSource` 只覆盖 DeepSeek；非 DeepSeek → `costMicroYuan=0`、`formulaVersion="price-missing"`、`priceSnapshotJson=null`，`llm_calls` 行如实落库；UI 成本行显示「费用未知（未内置价卡）」。
- 价格卡与 `check-cost.py`：允许 `formulaVersion=price-missing` 的行跳过单价复算，仅校验 `cost=0` 与字段一致性；其余行逻辑不变。

## §0.7 版本与兼容

- `versionCode=3`、`versionName=0.10.0`（候选版；真机通过后再谈 1.0.0）。
- DB 不变（v3）；设置迁移只涉及 SharedPreferences/Keystore；同 keystore 同签名覆盖安装验证（`apksigner verify --print-certs` 对齐 0.9.0 证书）。
- 回滚：旧版仍读旧 `api_key_packed`（迁移不删旧键），DB 不变 → 可直接装回 0.9.0。

## §0.8 证据（T5，全部落 `docs/evidence/plan6/`）

| 文件 | 内容 |
| --- | --- |
| `p6-contract-00-spec.txt` | 五家 baseUrl/path/auth/model 的官方来源 URL + 抓取日期 + 原文引文；「需实测确认」逐项列 |
| `p6-contract-01-deepseek-baseline.json` | T0 改码前抓取的 DeepSeek 请求体基线（8 用例覆盖） |
| `p6-contract-02-bodies.txt` | 5 家请求体 golden（不含 Key），逐字段断言「未支持字段不出现」 |
| `p6-contract-03-errors.txt` | 状态/异常 → `LlmException.Kind` 矩阵（含 MiniMax base_resp 200+1002/1004） |
| `p6-key-00-migration.txt` | 旧键→deepseek 只读回退、其他家 null、重复迁移幂等、保存新键后才清旧键 |
| `p6-conn-01-green.txt` 补充断言 | 断网/慢服务 25s 内出结果、attempts==1、连点 5 次每轮 1 请求、切 provider 后旧响应不串台 |
| `p6-key-01-no-request.txt` | 未配置/未保存草稿 → 0 请求（服务器 counter=0）；已保存 → counter=1 正对照 |
| `p6-key-02-leak-grep.txt` | 证据目录 + REPLAY + logcat 三段 grep `sk-[A-Za-z0-9]{16,}` = 0；故意假 Key 正对照 = 1 |
| `p6-conn-00-red.txt` / `01-green.txt` / `02-mutation.txt` | 测试连接先红后绿、5 类结果（成功/401/429/超时/网络）、≥3 变异 |
| `p6-llm-00-red.txt` / `01-green.txt` / `02-mutation.txt` | Provider 传输层 golden/错误映射/usage；先红后绿；变异「多发字段/漏 base_resp」 |
| `p6-ui-00..05` | 设置页默认/测试中/成功/401/切换/1.3×（png + sidecar nearWhite 与 PNG_SHA256） |
| `p6-a11y-01-bounds.txt` | uiautomator 全部 clickable ≥126px（1.3×） |
| `p6-full-01-tests.txt` | 全量 XML 重数（≥52 类/443 例 + 新增，0/0/0，逐类清单） |
| `p6-release-01-build.txt` | 若出候选包：双端 sha、证书、冷启、debug 入口 grep=0 |

## §0.9 偏差登记（从 D494 起）

| 编号 | 日期 | 任务 | 偏差 | 原因 |
| --- | --- | --- | --- | --- |
| D494 | 2026-10-31 | T0 | 独立 Plan 6，从 `e1f34a1` 切 `plan6-providers`；D 号从 D494 起 | Plan 5 已 9/9 封版；供应商能力属新范围 |
| D495 | 2026-10-31 | T0 | v1 模型清单冻结为 6 个 id（DeepSeek 2 / MiMo 2 / MiniMax 1 / Qwen 2 / GLM 1） | 单模型供应商不摆单选；强制思考模型（M3.1-Flash、GLM-5.3）不进 v1 |
| D496 | 2026-10-31 | T0 | 非 DeepSeek 成本口径 = `costMicroYuan=0 / formulaVersion="price-missing" / priceSnapshotJson=null` + UI「费用未知」 | 无官方价卡，静默记 0 会假账；改 check-cost 白名单 |
| D497 | 2026-10-31 | T0 | 测试连接只测已保存 Key、单次请求、10s 上限、不写库 | 防 Key 泄露与「连接中」挂数分钟；与生成链重试隔离 |
| D498 | 2026-10-31 | T0 | 旧 Key 只读回退迁移（不删旧键，保存新 DeepSeek Key 后才清） | 回滚旧版不丢 Key；其他家不回退防串 Key |
| D499 | 2026-10-31 | T0 | v1 固定端点，不做自定义 BaseUrl/代理 | SSRF 与明文面；用户未要求 |
| D500 | 2026-10-31 | T0 | 版本 `versionCode 3 / 0.10.0`，DB 保持 v3 | 候选版语义；设置迁移不碰 Room |
| D501 | 2026-10-31 | T0 | 「已保存（还没验证）」措辞改为「已保存」；验证只由测试连接结果行表达 | 旧文案与测试结果语义打架 |
| D502 | 2026-10-31 | T0 | 五家端点/模型标「需实测确认」，真连通 PENDING | 无真实 Key；官方文档为唯一依据 |
| D503 | 2026-10-31 | T0 | 测试连接独立 client：call/read/connect=20/15/10s、`followRedirects(false)`、single-flight、结果带 providerId+nonce | Voice AI 预审：复用生成链 300s×4 次会「连接中」挂数分钟、且会争抢同 Key 配额 |
| D504 | 2026-10-31 | T0 | `ChatRequest` 加 `maxTokens: Int? = null`（null 不发） | 测试连接要 `max_tokens=1` 控制消耗，又不能改动 DeepSeek 既有请求体 |
| D505 | 2026-10-31 | T0 | 空 Key 本地拦截（0 HTTP）；失败文案只给人话分类，不显示 URL/响应体 | `DeepSeekClient.kt:203` 会发 `Bearer ` 空头；OkHttp 报文含 URL |
| D506 | 2026-10-31 | T0 | 单例 OkHttpClient 注入全部 provider | 5 个默认 `OkHttpClient()` 会各自建连接池/Dispatcher，浪费且难统一 |

## §0.10 顺序与闸门

G0 T0 契约冻结（Lead 签字）→ T1 Key 存储与迁移 → T2 Provider 传输层（DeepSeek 基线不回归）→ T3 测试连接 → T4 设置页 UI 联动 → T5 证据/终验。
T3 与 T4 都改 `SettingsViewModel.kt`，必须串行；T2 与 T3 可并行读不同文件，但合入顺序 T1→T2→T3→T4。

G0 判据：矩阵每项有来源与「需实测」标记；DeepSeek 基线已抓取。
G1：迁移用例绿 + 旧键仍在 + Key grep 0（正对照 1）。
G2：443 例不退化 + DeepSeek 请求体 diff 空 + 5 家 body golden 绿。
G3：测试连接 5 类结果全绿（含 MiniMax 假 200）；未配置 0 请求。
G4：全量绿（实测计数）+ 证据齐 + 看板/偏差表/FINAL-REVIEW。

---

## T0 契约冻结（Lead）

**Files**：本计划（`docs/superpowers/plans/2026-10-31-plan6-providers.md`）、`docs/evidence/plan6/`（新建 + `.gitattributes` 规则）、DeepSeek 基线抓取脚本（`.scratch/`）。

**Steps**
1. 写本计划 §0.1–§0.10，五家矩阵逐行标来源与「需实测确认」。
2. 抓 DeepSeek 请求体基线：跑现有 8 条 `DeepSeekClientTest`，导出每次请求的 method/path/Auth 头存在性/body JSON，落 `p6-contract-01-deepseek-baseline.json`。
3. `.gitattributes` 增 `docs/evidence/plan6/*.txt text eol=lf`、`*.png binary`、`*.db binary`。
4. 建 `docs/evidence/plan6/README.md`（沿用 plan5 口径，补「provider 端点需实测确认」一段）。
5. commit `docs(plan6): T0 契约冻结（D494–D502）`。

**Expected**：计划入库；基线 JSON 可复跑；无代码改动；工作树干净。

## T1 Key 存储与迁移

**Files**：`data/security/ApiKeyStore.kt`、`KeystoreApiKeyStore`、`AppContainer.kt`、`test/.../ApiKeyMigrationTest.kt`。

**Steps**
1. 先写红：`ApiKeyMigrationTest`（旧键可读、五家互不串、clear 只清当前、保存 deepseek 新键后旧键才清、重复迁移幂等、其他家不回退旧键）。
2. 接口加 provider 维度；实现 per-provider pref 键；`AppContainer` 装配不变但注入 provider 常量。
3. 绿后跑定向与全量；三条变异（迁移删旧键 / 其他家回退旧键 / clear 误清全部）各必红。

**Expected**：新增 1 类 + 8~10 例；全量 443+ 不退化；Key 不出现在日志。

## T2 Provider 传输层

**Files**：`llm/ProviderSpec.kt`（新）、`llm/ProviderRegistry.kt`（新）、`llm/ProviderChatClient.kt`（新或改造 `DeepSeekClient`）、`llm/ChatModels.kt`（如需 `ProviderId`）、`di/AppContainer.kt`、测试 `ProviderBodiesTest` / `ProviderErrorsTest` / `ProviderUsageTest`。

**Steps**
1. 先红：五家 body golden（路径/鉴权/键集/未支持字段不出现）、MiniMax 200+base_resp、usage 三形状、DeepSeek 基线 diff。
2. 实现 `ProviderSpec` 与 body/usage/error 三函数；DeepSeek 方言逐字节等价。
3. 绿 + 全量；变异：多发 `reasoning_effort`、漏 base_resp 判定、usage 字段错位。

**Expected**：新增 3 类 + 15~18 例；443 例不退化；DeepSeek diff 空。

## T3 测试连接

**Files**：`llm/ConnectionTester.kt`（新）、`data/settings/` 或 `llm/` 结果类型、`ui/settings/SettingsViewModel.kt`、测试 `ConnectionTesterTest`。

**Steps**
1. 先红：未配置不发请求（counter=0）、已配置发 1 次（counter=1）、成功含 latency、401/429/超时/网络四类映射、MiniMax 假 200、草稿未保存禁用、生成链 429 不受影响。
2. 实现 `ConnectionTester`（单次、10s、不写库）+ VM 状态。
3. 绿；变异：复用 4 次重试、只判 200、未保存 Key 也发。

**Expected**：新增 1 类 + 8 例；不写 `llm_calls`；日志无 Key。

## T4 设置页 UI

**Files**：`ui/settings/SettingsScreen.kt`、`SettingsViewModel.kt`、`ui/common/`（共享 `humanReason`）、`test/.../SettingsUiStateTest.kt`。

**Steps**
1. 先红：VM 状态（5 家初始、切 provider、保存/掩码、canTest、成功/失败、draft 清结果、单模型/无思考字段）。
2. 改 UI：整页滚动 + 「模型服务」chips（48dp、状态点）+ per-provider Key 卡 + 保存/测试/清除 + 结果行（成功含毫秒、失败人话）+ 模型/思考联动 + 删除「还没验证」。
3. 绿；设备证据（模拟器）：默认/测试中/成功/401/切换/1.3× 六图 + a11y bounds；变异：canTest 忽略 keyConfigured、401 文案通用化。

**Expected**：新增 1 类 + 10 例；全量绿；1.3× 无截断、clickable ≥126px。

## T5 证据与终验

**Files**：`docs/evidence/plan6/*`、`docs/evidence/plan1/ACCEPTANCE.md`（`## 3g`）、`docs/superpowers/plans/2026-10-31-plan6-providers.md`（§0.9/§10）、`docs/PROGRESS.md`。

**Steps**
1. 汇总 T1–T4 证据，补 `p6-full-01-tests.txt`（XML 重数）。
2. 写 `FINAL-REVIEW.md`（基线/终态/提交链/PENDING：五家真连通、真机、每家价卡、流式、模型发现）。
3. 更新看板（Plan 6 表、需求表 #1 供应商与测试连接）、偏差表、验收记录。
4. commit + 向用户汇报（明确「五家离线契约已证、真连通 PENDING」）。

**Expected**：证据齐；全量绿；工作树干净；用户能看到「支持哪几家、哪里还没验证」。
