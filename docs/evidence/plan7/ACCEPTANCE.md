# Plan 7 验收（原样整理 v3）

- 版本：**0.13.2 / versionCode 13**（0.13.0 首发、0.13.1 同音字「同篇一致性」、0.13.2 T2 保守档守卫）
- 基线：全量 **67 类 / 530 例** 绿（`./gradlew :app:testDebugUnitTest`）
- 证据：`p7-faithful-01-vivo.txt`、`p7-faithful-01-settings.png`、`p7-faithful-02-result.png`、`p7-faithful-03-raw.png`

## 1. 交付物

| 项 | 位置 | 状态 |
|---|---|---|
| 提示词 v3 | `prompts/faithful.v3.md`（sha `82c6e1f1`） | ✅ 事实守恒 8 条、压缩五六成、口水清零、§2.9 同音字（唯一读法 + 同篇一致性，≤2 处） |
| 闸门 F1/F2/F3 | `scripts/check-faithful.py` + `llm/gates/FaithfulGates.kt` | ✅ Python 自测 10 例、Kotlin 向量 7 条 |
| 编排链 | `GenerationOrchestrator` T0→T1→T1b→T2→T3 | ✅ 单测覆盖 T1b 通过/失败两路 |
| 思考档位 | `GenerationOptions.faithfulThinking`、`dk_generation_faithful_thinking` | ✅ 四档，默认 HIGH |
| 设置页 | `ProviderDetailScreen` 原样整理模型/思考 | ✅ 与小说化互相独立 |

## 2. 真机验收（vivo V2405A / Android 16）

| 检查 | 期望 | 实测 |
|---|---|---|
| 92.3 秒录音转写 | 出正文 | ✅ 380 内容字（SenseVoice Small 离线） |
| T0 守门 | 口水/丢词被拦 | ✅ `F3,G5`，明细 `F3[还有口水：那个]`、`G5[好像 5→3, 不知道 2→1]` |
| T1 修复 | 落库且达标 | ✅ `faithful ok stage=T1 in=380 out=257` |
| 压缩率 | 0.50–0.85 | ✅ 0.676 |
| 口水 | 0 | ✅ 呃/嗯/我思考一下/那个 均无 |
| 事实 | 数字/地点/物件全在 | ✅ 4.4点钟、6点到、澳门、宇宙飞船、家人、枪、店门口 |
| 同音字 | 前文「坐宇宙飞船」后文同字 | ✅ 0.13.2 复测：「得做一个类似的东西」→「得坐一个类似的东西」 |
| 默认档位 | HIGH | ✅ 设置页显示「高」 |

## 3. 未覆盖 / 已知限制

- T1b 在真机样本上未被触发（T1 一次过）；由单测「T1 红但 T1b 修干净时按 T1 提交」守。
- T2 仍是「只加标点」的保守回退，真机若走到 T2 会看到原文（不是静默失败，UI 显示原文）。
- 同音字只在前文有依据或唯一读法时才改，不保证覆盖所有口语同音错。
- 0.13.2 结果里仍有模型自造错字（包里→标里、攻略→工略）；F 系列闸门抓不到「输出多出原文没有的字」，需在后续版本重挂 G6 才能守。
