promptId: tag
kind: TAG
version: v1
file: tag.v1.md
temperature: 0.2
outputSchema: application/json
sourceOfTruth: prompts/tag.v1.md（文件为唯一真源；版本 = 文件名 + 内容 sha256）
design: docs/superpowers/specs/2026-11-01-faithful-v31-design.md §3.1（标签为后续能力，本文件先定契约）
updated: 2026-11-01
---
<!-- 头部是元数据，运行时剥离后再送入模型；输出只有一段 JSON。 -->

## 输入

```
【原样稿】
<一段整理好的梦，第一人称，通常 80–600 字>
```

## 任务

给这段梦的原样稿打 3–6 个标签，用来事后翻找：
「那次去澳门的梦」「有宇宙飞船的那次」「和家人一起出门」。

## 规则

1. **3–6 个**，少于 3 个或多于 6 个都算不合格。
2. 每个标签 `name` **1–8 个字**，中文、可读、不复述整句。
3. `kind` 只能是：`PERSON`（人）、`PLACE`（地点）、`OBJECT`（物件）、`ACTION`（动作/事件）、`EMOTION`（情绪）、`OTHER`。
4. 混合取：优先「人物 + 地点 + 物件」，再补 1–2 个「主题/情绪」。不要全是情绪。
5. `evidence` 必须是原样稿里**逐字出现**的片段（2–12 字），用来回看依据。
6. 不编造原文没有的人、地、物；不用「梦境」「回忆」这类空标签。
7. 同名去重（大小写与空格视为同名）。

## 输出格式（只输出 JSON，不要代码围栏、不要解释）

```json
{"tags":[{"name":"澳门","kind":"PLACE","evidence":"去澳门"},{"name":"宇宙飞船","kind":"OBJECT","evidence":"宇宙飞船"},{"name":"家人","kind":"PERSON","evidence":"家里人"},{"name":"安检","kind":"ACTION","evidence":"不允许你带"},{"name":"紧张","kind":"EMOTION","evidence":"特别激动"}]}
```

## 自检

- 数量 3–6？
- 每个 name 1–8 字、不重复？
- kind 都在白名单？
- 每条 evidence 都能在原样稿里原样找到？
- 至少有一个 PERSON/PLACE/OBJECT，不是清一色情绪？
