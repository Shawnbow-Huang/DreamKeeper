# Plan 6 终审：多供应商 Key 与测试连接

> 日期：2026-10-31 · 分支 `plan6-providers` · 基线 `e1f34a1`（Plan 5 完成 9/9，52 类 443 例）

## 一、结论

**离线契约通过；真实连通 PENDING。**

本轮把「只有 DeepSeek」改成「五家可选 + 按家存 Key + 测试连接三态 + 生成链按家派发」，
并用 JVM 本地假服务器把**请求体形状、错误映射、用量解析、成本记账**都钉死；
但五家 Key 在本机都没有真实配置，因此**不宣称任何一家真实连通成功**（D521）。

## 二、基线 → 终态对照

| 维度 | 基线 `e1f34a1` | 终态 |
| --- | --- | --- |
| 供应商 | DeepSeek（硬编码） | DeepSeek / 小米 MiMo / MiniMax / 通义千问 / 智谱 GLM |
| Key 存储 | 单键 `api_key_packed` | 按家 5 键；DeepSeek 沿用旧键零迁移 |
| 传输层 | `DeepSeekClient` 单一实现 | `ProviderSpec` + `ProviderChatClient`（spec 驱动，DeepSeek 保留原文实现） |
| 测试连接 | 无 | `ConnectionTester`：单飞 / 空 Key 0 请求 / 20-15-10s / 失败 8 分类 |
| 设置页 | 单 Key 卡 | 五家 chips + 按家 Key 卡 + 模型随家 + 测试三态 + 整页滚动 |
| 生成链 | 全部发 DeepSeek | 按 provider 派发；开始生成时快照 provider/model/key |
| 成本记账 | 全按 DeepSeek 价卡 | 非 DeepSeek 成功行 `price-missing`（0 元、无快照），DeepSeek 不变 |
| 全量测试 | 52 类 / 443 例 | **59 类 / 488 例 / 0 失败** |
| 证据 | plan5 24 份 | plan6 7 份（contract / key / llm×2 / conn / ui / full） |

## 三、提交清单（`e1f34a1..HEAD`）

| 提交 | 内容 |
| --- | --- |
| `65ce99e` | T0 契约冻结（D494–D506）+ DeepSeek 请求体基线 4 例 + `p6-contract-01` |
| `16217f3` | T1 per-provider Key + 旧键零迁移 + 别名连坐修复（D507–D509） |
| `eeab11a` | T2 传输层：ProviderSpec/ProviderChatClient + 16 例（D510–D513） |
| `7157d6b` | T3 测试连接：单飞/短超时/失败分类 + 6 例（D514–D516） |
| `2b55185` | T4 设置页五家 UI + 按家 Key/模型/记忆 + 10 例（D517–D521） |
| `756e7cc` | T5 生成链按家派发 + 成本诚实 + 3 例（D522–D525） |
| 本提交 | T6 终审：FINAL-REVIEW / ACCEPTANCE 3g / 看板收口 |

## 四、证据索引（`docs/evidence/plan6/`）

| 文件 | 证明什么 |
| --- | --- |
| `README.md` | 本轮证据纪律：host-only 头、Key 零命中并带正对照、端点需实测、成本不许编 |
| `p6-contract-01-deepseek-baseline.json` | 改动前 DeepSeek 4 组请求体逐字基线（T2 后仍绿） |
| `p6-key-00-migration.txt` | 旧键只读回退、五家隔离、清理只清一家、坏密文不再连坐 |
| `p6-llm-01-providers.txt` | 五家请求体矩阵 / MiniMax 假 200 / usage 三形状 |
| `p6-llm-02-generation-routing.txt` | 生成链按家派发、开始快照、非 DeepSeek 未计价 |
| `p6-conn-01-tester.txt` | 测试连接单飞、空 Key 0 请求、401/429/超时分类 |
| `p6-ui-00-settings.txt` | 五家 chips、按家 Key 卡、模型随家、置灰与持久化（3 张截图） |

## 五、五家能力矩阵（离线契约）

| 供应商 | baseUrl | 思考开关 | effort | response_format | 备注 |
| --- | --- | --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com` | `thinking.type` | low/high/max | ✅ | 请求体有逐字基线 |
| 小米 MiMo | `https://api.xiaomimimo.com/v1` | `thinking.type` | 不发 | ✅ | 深度思考下温度被服务端固定 |
| MiniMax | `https://api.minimax.cn/v1`（需实测） | `thinking.type=adaptive/disabled` | 不发 | ❌ 不发 | HTTP 200 + `base_resp` 假成功已映射成 BAD_REQUEST |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `enable_thinking` | LOW→low / HIGH·MAX→xhigh | ✅ | Key 与地域绑定 |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `thinking.type` | low/high/max | ✅ | 强制思考模型不进本轮模型清单 |

## 六、复跑命令

```bash
cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew --no-daemon :app:testDebugUnitTest --rerun-tasks --console=plain
python scripts/check-cost.py --self-test
python scripts/check-migrate.py --self-test
```

期望：XML `classes=59 tests=488 failures=0`；两个自检 `mismatches=0` / 全绿。

## 七、PENDING（环境/外部依赖，不是实现缺陷）

| ID | 项 | 为什么 PENDING | 补齐条件 |
| --- | --- | --- | --- |
| P1 | 五家真实连通 | 本机没有五家 Key（D521） | 用户逐家粘贴 Key → 点「测试连接」→ 记毫秒与模型 |
| P2 | 非 DeepSeek 真实价卡 | 厂商计价未核实 | 拿到官方价目后加 `PRICE_VERSION` 与快照 |
| P3 | MiniMax 端点路径 | 官方文档抓取失败 | 真实 Key 下探 `/chat/completions` vs `/text/chatcompletion_v2` |
| P4 | 强制思考模型（GLM-5.3 / MiniMax-M3.1-Flash） | 不能做忠实整理，本轮不进模型清单 | 单独设计「只做小说化」的模型能力位 |
| P5 | 地域差异（Qwen workspace 域名） | 无企业 workspace | 用 workspace Key 实测 |
| P6 | 生成中切供应商的 UI 提示 | 语义已由快照隔离，未造设备竞态 | 设备上边生成边切家，观察旧 run 不被污染 |
| P7 | chip「已配置」小点 | 五家都没有 Key 可展示（D519） | 至少一家配置后补 |
| P8 | 深色/浅色主题、TalkBack | Plan 5 已有记录（D487/D463） | 后续计划 |
| P9 | 真机整体 | 用户设备未空出（D462） | 真机到位后统一回归 |

## 八、风险与已知限制

- **R1 端点漂移**：MiniMax 与 GLM 的路径来自惯例而非本轮抓取正文；真连通若失败先怀疑路径（P3）。
- **R2 假 200**：MiniMax 用 `base_resp.status_code` 表达错误，已处理，但真实返回体若有第三种形态仍需补映射。
- **R3 计价空白**：非 DeepSeek 行明确「未计价」，导出/UI 成本行不会假装有数；真价卡到位后需一次性回填策略。
- **R4 Key 安全**：五家 Key 分键存储、坏密文不再连坐；但 AndroidKeyStore 别名随卸载删除，卸载即需重录（D521）。
- **R5 测试连接语义**：它只证明「网络 + 鉴权 + 模型名可用」，不证明提示词质量，也不消耗可观测成本口径。
