# prompts/ · 提示词与评测集

跨平台共享资产，真源就是这些文件本身。

Android 侧在**构建期**由 Gradle 任务 `syncPromptAssets` 把 `prompts/*.md` 拷进
`android/app/build/generated/promptAssets/prompts/`，再用 `assets.srcDir` 把这个目录并进 main sourceSet，
随 APK 一起打进 `assets/prompts/`。运行时用 `assets.open("prompts/<文件名>")` 读——
这些条目在 APK 里是 deflate 压缩的，**不能用 `openFd`**。iOS 侧直接读仓库里这同一批文件。
手写资产 `android/app/src/main/assets/pricing/deepseek.json` 不走这条链，Sync 只写自己的生成目录，不会覆盖它。

## 约定

1. **提示词不进 Kotlin 代码。** 改一句话不该重编译，也不该翻遍源码找字符串。
2. **改了就是新版本。** `novel.v1.md` → `novel.v2.md`，旧版本保留，可回滚、可对比。
3. **四段式**，缺一段不算写完：

   ```
   角色     你是谁，在替谁做什么
   事实契约  哪些能改、哪些一个字都不许动
   风格契约  语言层面的硬要求（含禁用词表）
   输出格式  JSON schema，字段固定
   ```

## 槽与条件标记（运行时注入）

- **槽 = 正文里的 `【名】`**，例如 `【原始转写】`、`【当前小说稿】`。每个槽下面紧贴一行 `<…>` 提示，
  渲染时**槽名连同这行提示一起**替换成真实内容，不残留占位符。
- 必填槽缺值直接抛 `IllegalArgumentException`；选填槽传 `null` 时两行一并删掉。
- **条件标记不是槽**：`【只做标点】` 是纯开关，`【上一版违规清单】` 必须带载荷（缺载荷即抛）。
  标记在渲染后追加到输入块闭合围栏之后，不参与槽替换；替换顺序错了会把标记当槽误伤。
- 运行时契约（槽名、必填性、sha256_8、行锚点）在
  `android/app/src/main/java/com/dreamkeeper/llm/PromptSpec.kt` 与 `PromptRepository.kt`。
  文件字节一变，前 8 位哈希就对不上：先 `Log.w` 报漂移，再抛 `IllegalStateException`（不写库、不记账）。

## 文件清单

| 文件 | 用途 | 状态 |
|---|---|---|
| `faithful.v2.md` | 口述转写 → 理顺稿（只补标点/断句、去口癖，事实一字不动） | **现行** |
| `novel.v2.md` | 理顺稿 → 小说化改写（含少量 few-shot 示例） | **现行** |
| `novel_revise.v1.md` | 在既有小说稿上按用户这一条意见增量改 | **现行** |
| `faithful_cleanup.v1.md` / `faithful_cleanup.v1.1.md` | 早期「口述极乱时的断句清理」 | 历史，保留不覆盖 |
| `novel.v1.md` / `novel.v1.1.md` / `novel.v1.2.md` | 早期小说化版本 | 历史，保留不覆盖 |
| `CHANGELOG.md` | 版本表与每一版的改动理由 | 维护中 |

现行三份的 `sha256_8` 分别是 `5b9702ee` / `f189dad8` / `2ebba630`，由 `PromptSpec` 钉死；
动文件就必须同步改钉死值，否则校验会在渲染时直接抛。

## 评测

改任何提示词，都必须跑 `docs/eval/` 下的评测流程，口径见 [docs/eval/README.md](../docs/eval/README.md)：

- `docs/eval/dreams.md` —— 20 条梦境的**占位表**（编号 + 不泄露内容的标签；真实原文不进仓库）
- `docs/eval/results.md` —— 每轮提示词/模型/参数变更的结果登记

评价**不是 1–5 分**，三层都不依赖「标准答案文本」：规则闸门（`scripts/check-faithful.py`，任一项红即本轮不通过）、
盲评偏好（两版遮标选一，只记选择与理由）、下限基线（只判「不比用户那两条亲笔例子更差」）。

没有跑评测的提示词改动，等于没改。
