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
