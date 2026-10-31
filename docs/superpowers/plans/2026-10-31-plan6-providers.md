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
| D507 | 2026-10-31 | T1 | `ApiKeyStore.save(key, provider = DEEPSEEK)`：key 在前、provider 带默认值 | 保 40+ 处旧调用点（含测试）零改动；`load/clear` 只有 provider 参数 |
| D508 | 2026-10-31 | T1 | `AndroidKeystoreCipher.decrypt` 失败不再删除共用别名，只返回 null；坏 pref 由 store 单独清 | 别名被五家共用，原实现的 `deleteKey()` 会让一处坏密文连坐清掉所有 Key（P0 安全修复，Builder 预审） |
| D509 | 2026-10-31 | T1 | T1 由 Lead 直接实施；设置页在 T4 前仍只操作 DeepSeek 默认槽 | 实施者 subagent 不可寻址、等待成本高于改动本身；本任务只动存储层，不改接线 |
| D510 | 2026-10-31 | T2 | DeepSeek 不改成薄包装，继续用原 `DeepSeekClient`；新增 `ProviderChatClient` 只服务其余四家 | 请求体基线是历史契约，合并重构的回归风险高于 ~300 行重复；两条路径都由测试钉住 |
| D511 | 2026-10-31 | T2 | MiniMax 的 `base_resp` 假成功在**状态判定阶段**翻成 `BAD_REQUEST`（不重试）；解析阶段再兜一层 | 若只在解析处翻，`attempts` 会先被记为成功行，与 `llm_calls` 口径冲突 |
| D512 | 2026-10-31 | T2 | Qwen 的 `HIGH`/`MAX` 都映射到 `xhigh`；`LOW→low` | Qwen 官方 effort 只有 low/medium/xhigh，没有 high/max；发非法枚举会被服务端拒 |
| D513 | 2026-10-31 | T2 | T2 由 Lead 直接实施；`providerClients` 只在容器建映射，生成链仍走 DeepSeek | 派工/等待成本高于本层改动；按 provider 派发属于 T4/T5 的接线范围 |
| D514 | 2026-10-31 | T3 | 单飞用 `AtomicBoolean` 而非 Mutex：第二个调用立即返回 `UNKNOWN`，不挂起排队 | 连接测试是用户手动点的小操作，排队等待没有意义；立即返回也让「连点」可测 |
| D515 | 2026-10-31 | T3 | DeepSeek 的测试连接复用 `DeepSeekClient`（20s/15s、maxAttempts=1），不新建 client | DeepSeek 请求体已冻基线；独立短超时实例避免了 300s 挂死 |
| D516 | 2026-10-31 | T3 | T3 由 Lead 直接实施；结果分类 `ConnectionOutcome.Reason` 独立于 ui 层 `UserFacingReason` | llm 层不反向依赖 ui；T4 负责把 Reason 翻成人话 |
| D517 | 2026-10-31 | T4 | `warningFor` 去掉「必须 sk- 开头」软提示，只保留「偏短」 | 五家 Key 格式不同；前缀断言会误伤，且本机实测 DeepSeek Key 也无从靠前缀判断有效性 |
| D518 | 2026-10-31 | T4 | 设置页改为「顶部栏固定 + 正文滚动」(`weight(1f).verticalScroll().imePadding()`) | Plan 5 加「关于」卡后 1.3× 已顶到屏底，再叠供应商区必然被裁（UI 预审 P0） |
| D519 | 2026-10-31 | T4 | 供应商 chip 的「已配置」小点本轮未做 | 五家均未配 Key，没有可呈现的已配置状态；等真实 Key 到位再加不迟 |
| D520 | 2026-10-31 | T4 | 测试连接按钮在未保存 Key 时禁用；`NO_KEY` 分支只由单测覆盖 | UI 上不允许对空 Key 发请求；设备端未触发该分支（未配 Key） |
| D521 | 2026-10-31 | T4 | 设备上五家 Key 均未配置：T7 卸载重装清掉了 debug 期写入的 DeepSeek Key（AndroidKeyStore 别名随包卸载删除） | 需用户重新在设置页粘贴 Key 才能做真连通；本轮真连通保持 PENDING |
| D522 | 2026-10-31 | T5 | 每次生成在开始时快照 `provider/model/apiKey`（`newRun`），而不是每次 attempt 现读 prefs | 生成中切供应商会把新 model/key 记到旧请求上，`llm_calls` 的 model 与真实请求不符；快照后语义稳定 |
| D523 | 2026-10-31 | T5 | 非 DeepSeek 成功行记 `formulaVersion="price-missing"`、`costMicroYuan=0`、`priceSnapshotJson=NULL`；`check-cost.py` 白名单并短路复算 | 五家价卡未知，静默按 DeepSeek 价卡计费是假账；宁可标「未计价」也不编数字 |
| D526 | 2026-10-31 | T6 | 版本升到 `0.10.0 / versionCode 3` 并重出签名候选包（同签名覆盖 0.9.0） | 五家供应商是本轮用户可见能力，需要独立版本号便于真机区分；同签名可直接覆盖、保数据 |
| D527 | 2026-10-31 | T6 | 新增 `docs/QA-REAL-DEVICE.md` 真机清单（12 节 / 60+ 勾选项），作为交付物入库 | 用户要自己跑真机；清单必须可照做、可回传、含证据命名与判定口径，而不是口头步骤 |
| D528 | 2026-10-31 | T6 | 真机 APK 放 `dist/DreamKeeper-0.10.0.apk`，`dist/` 进 `.gitignore` 不入库 | 9.7MB 二进制不入仓；sha256 写在交付消息与 RELEASE.md，可校验 |
| D529 | 2026-11-01 | T7 | 视觉方向改为「灯下纸」：冷暖分工（结构冷、内容暖）、强调色提亮到 L* 71 | 用户 2026-10-31 反馈「美学没变化」；审计：Ember 与 InkDim 的 L* 61.6/61.5、对比 1.006×，冷色像素 98.3% |
| D530 | 2026-11-01 | T7 | 色板全量换值（Night/Paper/Ink/Ember + PaperSurface/PaperLit/Ash/OnLamp/ErrorContainer），旧 token 名保留 | 减少 60 余处调用点改动；`EmberDeep` 不再兼任 errorContainer |
| D531 | 2026-11-01 | T7 | `surfaceTint=Transparent`，elevation 不再被琥珀染色 | M3 默认会把 primary 混进带 elevation 的表面，深色下偏紫/偏橙 |
| D532 | 2026-11-01 | T7 | 宋体承载内容（displaySmall/headlineSmall/titleLarge/bodyLarge），无衬线承载操作 | 「暗室纸页」的纸感来自字；但宋体在小字号与国产 ROM fallback 上不可控，故门槛 ≥20sp |
| D533 | 2026-11-01 | T7 | 首页加 200sp 月份水印（Paper alpha 0.055、出血右缘），删日历琥珀点 | 首页最大连续空白 356.6dp；水印把空白变成「纸上的月份」，不新增信息 |
| D534 | 2026-11-01 | T7 | 「说梦」从砖红填充 + 描边改实心琥珀 + 深墨字 + 径向光晕；`shadow` 改 `Brush.radialGradient` | 砖红 2.57:1 靠描边救；Compose 圆形 shadow 在近黑底会露矩形边 |
| D535 | 2026-11-01 | T7 | 列表/详情/设置改为纸面（PaperSurface）承载，描边卡片退场 | 层级应从「面的差异」来，而不是 1dp 线；列表 4 行灰字有 39% 真空 |
| D525 | 2026-10-31 | T5 | 任务表把「生成链派发/成本」拆成 T5、把 FINAL-REVIEW/ACCEPTANCE 3g 顺延为 T6 | 生成链改动比预估大（快照 + 计价白名单 + check-cost），与终验证据分开提交更可审 |
| D524 | 2026-10-31 | T5 | 设备取证改用 `assembleRelease` 重装（release 与 debug 签名不同，`install -r` 会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`） | 保留设备上的 v3 数据库与真实梦；重建 release 只改签名不改数据 |

## §0.10 顺序与闸门

G0 T0 契约冻结（Lead 签字）→ T1 Key 存储与迁移 → T2 Provider 传输层（DeepSeek 基线不回归）→ T3 测试连接 → T4 设置页 UI 联动 → T5 生成链派发/成本诚实 → T6 证据/终验（FINAL-REVIEW / ACCEPTANCE 3g）。
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

## §10 验收记录

### T0 契约冻结（Lead，2026-10-31）
- 交付：`docs/superpowers/plans/2026-10-31-plan6-providers.md`（§0.1–§0.10 + D494–D506）、`docs/evidence/plan6/README.md`、`p6-contract-01-deepseek-baseline.json`、`DeepSeekRequestBaselineTest.kt`；commit `65ce99e`。
- 独立复跑：基线测试 4/4 绿（BUILD SUCCESSFUL in 47s），四组请求体逐字冻结（faithful 有 temperature 无 reasoning；novel 有 reasoning 无 temperature；jsonMode 有 response_format；无 temperature 变体）。
- 结论：通过；T2 重构后这 4 例必须仍绿（DeepSeek 不回归的硬约束）。

### T1 Key 存储与迁移（Lead，2026-10-31）
- 交付：`llm/ProviderId.kt`；`data/security/ApiKeyStore.kt`（per-provider 接口 + 实现）；`KeystoreCipher.kt`（失败不再删别名）；`PerProviderApiKeyTest` 6 例；`docs/evidence/plan6/p6-key-00-migration.txt`。
- 独立复跑：`--tests "com.dreamkeeper.data.security.*"` BUILD SUCCESSFUL 50s；全量 `--rerun-tasks` BUILD SUCCESSFUL 62s，XML 54 类 453 例 0 失败。
- 契约：DeepSeek 沿用 `api_key_packed`（零迁移）；其他四家 `api_key_packed.<wire>`；只读自己；clear 只清自己；坏密文只清自己。
- P0：共用别名不再被删（D508）。
- 结论：通过。PENDING：真 Keystore 设备侧（Robolectric 无 shadow）、五家 UI（T4）。

### T2 Provider 传输层（Lead，2026-10-31）
- 交付：`llm/ProviderSpec.kt`（ProviderSpec/ModelSpec/ThinkingStyle/Registry）、`llm/ProviderChatClient.kt`、`ChatModels.kt`（provider/maxTokens）、`di/AppContainer.kt`（providerClients 映射）、测试三类 16 例、`docs/evidence/plan6/p6-llm-01-providers.txt`。
- 独立复跑：`--tests "com.dreamkeeper.llm.*"` BUILD SUCCESSFUL 54s（含 DeepSeek 基线 4 例）；全量 62→68s，XML **57 类 469 例 0 失败**。
- 契约：MiMo 不发 effort；MiniMax 永不发 response_format；Qwen 用 `enable_thinking` 且 `HIGH/MAX→xhigh`；GLM/DeepSeek 用 thinking 对象；`max_tokens` 仅显式传入时出现；未知模型回落默认。
- 错误：MiniMax 200 假成功→BAD_REQUEST 且 attempts=1；401 单次；429 用满预算；空 content/非 JSON 不重试。
- 结论：通过。PENDING：五家真连通、强制思考模型、地域/端点实测。

### T3 测试连接（Lead，2026-10-31）
- 交付：`llm/ConnectionTester.kt`（单飞 / 空 Key 0 HTTP / 单次预算 / 结果带 providerId+nonce / Reason 分类）、`di/AppContainer.kt`（短超时 clientFor）、`ConnectionTesterTest` 6 例、`docs/evidence/plan6/p6-conn-01-tester.txt`。
- 独立复跑：targeted 53s 绿；全量 68s，XML **58 类 475 例 0 失败**。
- 契约：已保存 Key 才测；`maxAttempts=1`；20/15/10s；连点只发一次；失败分 AUTH / RATE_LIMITED / TIMEOUT / NETWORK / BAD_REQUEST / SERVER / BAD_CONTENT / NO_KEY。
- 结论：通过。PENDING：五家真连通（无真实 Key）、UI 三态（T4）。

### T6 终审（Lead，2026-10-31）
- 交付：`docs/evidence/plan6/FINAL-REVIEW.md`（基线对照 / 提交清单 / 证明索引 / 五家矩阵 / PENDING P1–P9 / 风险）、`docs/evidence/plan1/ACCEPTANCE.md` `## 3g`（B1–B14）、看板收口。
- 结论：离线契约通过；真实连通 PENDING（P1）。不宣称「已支持五家真实调用」，只宣称「五家请求体/错误/用量契约已证 + Key 按家隔离 + 测试连接可用」。
- PENDING：P1–P9（真实 Key、真价卡、MiniMax 路径、强制思考模型、Qwen 地域、切家竞态、chip 小点、主题/TalkBack、真机）。

### T5 生成链按供应商派发 + 成本诚实（Lead，2026-10-31）
- 交付：`GenerationOrchestrator.kt`（`clientFor`/`apiKeyFor`/`keyFor`、`newRun` 快照 provider+model、faithful/novel 请求带 provider/model/对应 Key、`attemptRow` 按 provider 决定计价）、`ReviseOrchestrator.kt`（同一套派发）、`di/AppContainer.kt`（`providerClients`/`apiKeyStore.load(provider)` 接线）、`scripts/check-cost.py`（`price-missing` 白名单 + 短路复算）、`GenerationOrchestratorTest` 新增 3 例。
- 独立复跑：`--tests com.dreamkeeper.llm.GenerationOrchestratorTest` 19 例绿（54s）；全量 `--rerun-tasks` BUILD SUCCESSFUL 65s，XML **59 类 488 例 0 失败**。
- 契约：开始生成时快照 provider/model/key；请求派发到对应 `LlmClient`；DeepSeek 成功行照旧计价（`cost-v1` + 快照）；非 DeepSeek 成功行 `price-missing`/0 元/无快照；`check-cost.py --self-test` 6 例仍 0 mismatch。
- 结论：通过。PENDING：五家真连通与真实价卡、生成中切家的 UI 提示（本轮语义上不允许跨 run 串值）。

### T4 设置页五家供应商（Lead，2026-10-31）
- 交付：`SettingsViewModel.kt`（供应商切换 / 按家 Key / 测试连接三态 + nonce 防串台 / 结果人话）、`SettingsScreen.kt`（固定顶栏 + 滚动正文 / 五家 chips / 按家 Key 卡 / 模型清单随家 / 测试连接按钮）、`GenerationOptions.kt`（+provider）、`GenerationPrefs.kt`（+provider 与按家模型记忆，DeepSeek 旧键零迁移）、`DreamKeeperRoot.kt`/`MainActivity.kt` 透传 `connectionTester`；`SettingsViewModelTest` 10 例。
- 独立复跑：targeted 55s 绿；全量 74s，XML **59 类 485 例 0 失败**。
- 设备（release `fd6c6872f7efc7ea`）：五家 chips、切家后卡标题/占位符/模型清单联动、未配 Key 时「测试连接」置灰、force-stop 后选中家仍保留；三张截图裁顶后 nearWhite=0。
- 结论：通过。PENDING：五家真连通（无真实 Key，D521）、chip 已配置小点（D519）。


### 外观改版（Lead，2026-11-01）
- 交付：`ui/theme/{Color,Theme,Type}.kt` 重写；`ui/calendar/CalendarScreen.kt`（宋体日期、删点、琥珀光球、月份水印）；`ui/library/LibraryScreen.kt`（纸卡片、PaperLit 分段控件）；`ui/detail/DetailScreen.kt`（正文纸面容器）；`ui/settings/SettingsScreen.kt`（面差替代描边）；`ui/common/EmptyState.kt`（168dp / headlineSmall）。
- 独立复跑：`:app:testDebugUnitTest --rerun-tasks` 1m21s，XML **59 类 488 例 0 失败**。
- 设备：debug 包（种子数据）四屏截图 `p6-ui-04..07`（nearWhite 全 0）；release 重打 9,728,730 B sha 前16 `0401f34122a384f5`，卸载重装冷启 478/447/493 ms、FATAL 0、`dk_debug_save_key` 0；对照图 `p6-ui-08..10`。
- 证据：`docs/evidence/plan6/p6-ui-04-aesthetic.txt`。
- 结论：通过（观感改由用户确认）。PENDING：宋体内嵌/国产 ROM 观感、空态插画按新纸色重绘、浅色主题未做。
