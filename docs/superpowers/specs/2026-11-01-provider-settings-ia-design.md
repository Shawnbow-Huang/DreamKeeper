# 设置页模型服务 IA 设计（2026-11-01）

状态：已与用户对齐（2026-11-01 02:36–02:40），Lead 实施。

## 1. 问题

0.10.0 的「生成」卡把五家供应商 chips、Key 卡、模型、思考全铺在设置首页一屏里：

- 设置页越滚越长，用户真正的常用入口（外观、关于）被挤到下面；
- 五家的 Key 同时出现，语义上像「每家都要配」，与「只有一家参与生成」不符；
- 切换供应商（chips）与配置供应商（Key / 模型 / 思考）混在一起，容易误操作。

## 2. 参考与结论

参考同类实现（只读）：

- Cherry Studio：设置里有独立「模型服务」页，按服务商分组；每家是「Key + 检查按钮 + 模型管理 + 右上启用开关」；不启用不进模型列表。
- Chatbox：设置 → 模型 → 先选提供方，再填该家的 Key。
- Hermes #39020 的讨论方向一致：独立 Providers 段 + per-provider Key + 启用开关。

共同点：**索引与配置分离**——列表回答「有哪些、哪一家在用、配没配好」，详情才做配置。

## 3. 决策（用户逐项确认）

| # | 决策 | 说明 |
| --- | --- | --- |
| D1 | 三级层级：设置 → 模型服务（列表）→ 单家详情 | 首页只留一行摘要，不再铺五家 |
| D2 | 「当前供应商」单选，不做每家启用开关 | 生成链始终只用一个 provider，开关会引入「启用但不当前」的歧义 |
| D3 | 详情页 = Key + 测试连接 + 模型 + 思考 + 设为当前 | 生成偏好（模型 / 思考）随家记忆 |
| D4 | 未配 Key 允许设为当前，但明确提示 | 先选家再找 Key 是真实路径；不静默降级、不阻断 |
| D5 | 计价提示只放详情页 | 列表保持一屏可扫；非 DeepSeek 的「未计价」说明只在配置时出现 |

## 4. 界面结构

```
设置
├─ 模型服务        [当前供应商 · 模型 · 已/未配置 Key]  ›
├─ 外观            四套皮肤
└─ 关于

设置 / 模型服务
├─ DeepSeek        使用中    DeepSeek Flash · 已配置        ● ›
├─ 小米 MiMo       MiMo V2.6 Pro · 未配置                    ● ›
├─ MiniMax / 通义千问 / 智谱 GLM …
└─ 说明：每家独立 Key…测试连接在单家页面

设置 / 模型服务 / <供应商>
├─ API Key 卡：状态 / 掩码 / 输入 / 保存 · 测试连接 · 清除 / 结果行
├─ 模型与思考卡：模型 chips、思考档位 chips、temperature 说明
├─ （非 DeepSeek）暂无价卡，按「未计价（0 元）」记账
└─ 设为当前供应商 / 使用中（未配 Key 时附提示）
```

## 5. 状态与职责拆分

原 `SettingsViewModel` 同时管「列表 + 配置」，拆成两个：

- `ProviderListViewModel`：`rows(provider, configured, active, modelLabel)`、`activeProvider`、`modelLabel`、`skin`；`refresh()` 每次返回列表都重读 Key 存在性与当前供应商；`setSkin`。
- `ProviderDetailViewModel`：由旧 `SettingsViewModel` 演化；`initialProvider` 钉死当前页是哪一家，**打开别家详情不改全局 provider**；`save/clear/testConnection/setModel/setNovelThinking` 全部作用于这一家；`makeActive()` 只写 `GenerationPrefs.setProvider`。

不新增持久化字段：per-provider Key 已存在 `api_key_packed.<wire>`，当前供应商已存在 `dk_generation_provider`，模型记忆已存在 `dk_generation_model[.<wire>]`。

## 6. 路由

- `DreamRoute.Providers` = `settings/providers`
- `DreamRoute.ProviderDetail(wire)` = `settings/provider/{wire}`
- 列表返回时先 `onReturnFromDetail()`（刷新 Key 状态与使用中标记）再 `popBackStack()`；未知 wire 回退 DeepSeek 并记 WARN。

## 7. 不做（本轮）

- 每家「启用/停用」开关（D2）；
- 模型自动发现 / 自定义 BaseUrl（Plan 6 §0.1 已推迟）；
- 列表内直接编辑 Key（配置只在详情）；
- 价格计算（仍未计价，沿用 `price-missing`）。

## 8. 验收

- 单测：`ProviderListViewModelTest` 4 例 + `ProviderDetailViewModelTest` 新增 2 例；全量不退化。
- 设备：四屏截图（设置首页 / 列表 / DeepSeek 详情 / MiMo 未配详情），nearWhite=0；
- 行为：切家后列表「使用中」跟随；返回列表能看到刚保存的 Key 状态；未配 Key 点「设为当前」有提示且不崩。

## 9. 模型发现与双模型（2026-11-01 追加）

用户追加需求：①Key 连通后要能自动看出「当前能用哪些模型」再选；②第一模式（忠实整理）也应有模型参数。两条都按「先检测、后选择」「忠实与小说化各一档」落地。

### 9.1 决策

| # | 决策 | 理由 |
| --- | --- | --- |
| D6 | 详情页放「检测可用模型」按钮，点一次 `GET {baseUrl}/models`（Bearer，10s，单次，无重试） | 各家兼容层大多实现该端点；放进详情页而不是列表，避免打开列表就打五家网络 |
| D7 | 探测失败一律降级：内置清单继续可用，按钮下写原因（HTTP 码 / 无 Key / 解析失败） | 很多兼容端点返回 404 或空体；把「端点没实现」写成「鉴权失败」会误导用户排查 Key |
| D8 | 检测结果只进候选（`dk_generation_models.<wire>`），当前选择不动 | 「检测」是问「有什么」；自动改选等于悄悄换模型和价格 |
| D9 | 忠实链有独立模型（`dk_generation_faithful[.<wire>]`，默认跟随小说化），请求体与记账都用它 | 忠实只补标点/顺语序，配便宜模型合理；`llm_calls.model` 必须记实际请求模型，否则成本与版本线错位 |
| D10 | 忠实链思考仍固定关闭，UI 只给说明 | 设计 §9 逐字保守：思考开启会改写句法，与「不动措辞」冲突 |

### 9.2 数据与接口

- 候选 = 内置 `ProviderSpec.models` ∪ `detectedModels(provider)`（去重、内置优先排序）；未知 id 直接以 id 作标签。
- 持久化：`dk_generation_models.<wire>` 逗号分隔；`detectedModels()` 只读；`setDetectedModels()` 只写候选。
- 探测：`interface ModelProbe { suspend fun list(provider, apiKey): ModelDiscovery.Result }`；`ModelDiscovery` 为默认实现（OkHttp 单例、`followRedirects=false`、10s 连接/读、`invokeOnCancellation` 时 `call.cancel()`）。
- 记账：`Run.faithfulModel`；`attemptRow(model=request.model)`；`recordSuccess/recordFailure` 带 `model`；价卡 `price(provider, model)`。

### 9.3 验收

- 单测：`ModelDiscoveryTest` 5 例（两种形状、404 降级、空 Key 0 请求、坏 JSON）+ `GenerationPrefsTest` 2 例 + `GenerationOrchestratorTest` 1 例（faithful/novel 分别发不同模型且 `llm_calls` 两行模型不同）+ `ProviderDetailViewModelTest` 2 例（检测不改选择 / 失败保留内置）。
- 设备：详情页「检测可用模型」与「忠实整理模型」可见；未配 Key 点击 → 本地提示、0 请求。
- PENDING：真实 `/models` 连通需有效 Key。

### 9.4 模型候选用下拉、顺序先忠实后小说化（2026-11-01 二次反馈）

| # | 决策 | 理由 |
| --- | --- | --- |
| D11 | 模型候选由「胶囊平铺」改为下拉（一行当前值 + ▾，菜单内当前项 ember + ✓） | 一家可能上架 3–4 个模型，胶囊会把卡片撑成两列换行；下拉与「选一个」的语义也更贴 |
| D12 | 卡内顺序改为 忠实整理模型 → 小说化模型 → 小说化思考 | 忠实是流程第一步、也是更省的一步；先出现符合使用顺序，也避免用户以为必须先小说化 |
| D13 | 下拉容器用 `paperSurface` + 发丝线 + 8dp 阴影，`tonalElevation=0` | M3 默认容器是淡紫 surface，与三套皮肤都不一致；纸面材质要延续到浮层 |
| D14 | 思考档位仍是 4 枚胶囊 | 固定四项、需要一眼看全，收进下拉反而多点一次 |
