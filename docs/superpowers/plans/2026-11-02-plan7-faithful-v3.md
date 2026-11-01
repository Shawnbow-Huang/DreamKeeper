# 原样整理 v3 实施计划（0.12.0）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让原样整理真的把 380 字的「呃…嗯…然后然后」流水账压成 170 字干净中文，同时一个事实都不丢；并给这个模式开放思考档位。

**Architecture:** 提示词从 v2.1 的「逐字保守」改成 v3 的「事实守恒、措辞重写」；校验闸门从 G1–G3（禁止改写）换成 F1 事实锚点 / F2 压缩率 / F3 口水清零 + 保留 G4 人称 / G5 长度，G1–G3 与 G6 只留在 revise 链路。思考档位与小说化共用同一套四档胶囊，但忠实链默认「低」（因为换词需要一点判断，不需要长篇推理）。

**Tech Stack:** Kotlin + Jetpack Compose + Room（无新增依赖）；校验脚本 Python 3（无第三方包）；JUnit4 + Robolectric；adb 真机（vivo V2405A / Android 16）。

---

## 0. Scope Check

本计划只覆盖 spec 的 §4「原样整理 v3」：提示词、闸门、思考档位、设置 UI、验证与发布。

**不做（属 Plan 8）：** 小说化 v3 的三段式、创作手记、N1–N3；**不改**：Room schema、导出格式、`novel_versions` 语义、revise 链路（G1–G6 原样保留）。

**已确认的产品定义（spec §4.1）：** v3 允许改词、调语序、并句、删空话；不允许增删事实、改数字/颜色/方位/专名、改结局或顺序、加入解释与情绪。

---

## 1. 文件地图

| 文件 | 责任 | 本计划动作 |
| --- | --- | --- |
| `prompts/faithful.v2.1.md` | 现役提示词（逐字保守） | 保留不动（回滚锚点） |
| `prompts/faithful.v3.md` | 新提示词 | 新建 |
| `android/app/src/main/java/com/dreamkeeper/llm/PromptSpec.kt` | `PromptSpec` / `PromptSpecs` | `FAITHFUL` 指 v3 |
| `scripts/check-faithful.py` | Python 闸门真源 | 加 F1/F2/F3、按 mode 选闸门 |
| `android/app/src/main/java/com/dreamkeeper/llm/gates/FaithfulGates.kt` | Python 的 Kotlin 移植 | 加 F1/F2/F3、按 mode 选闸门 |
| `android/app/src/main/java/com/dreamkeeper/llm/gates/GateResult.kt` | `Gate` 枚举 / `GateReport` | 加 F1/F2/F3 |
| `android/app/src/main/java/com/dreamkeeper/llm/GenerationOptions.kt` | 生成参数 | 加 `faithfulThinking` |
| `android/app/src/main/java/com/dreamkeeper/data/settings/GenerationPrefs.kt` | 偏好键 | 加 `dk_generation_faithful_thinking` |
| `android/app/src/main/java/com/dreamkeeper/llm/GenerationOrchestrator.kt` | 生成链 | `callFaithful` 用 `faithfulThinking` |
| `android/app/src/main/java/com/dreamkeeper/ui/settings/ProviderDetailViewModel.kt` | 设置页状态 | 暴露 `faithfulThinking` + setter |
| `android/app/src/main/java/com/dreamkeeper/ui/settings/ProviderDetailScreen.kt` | 设置页 UI | 「原样整理思考」四档 |
| `docs/evidence/plan7/*` | 证据 | 新建 |
| `docs/PROGRESS.md` / `CHANGELOG.md` / `QA-REAL-DEVICE.md` | 看板/记录 | 收尾更新 |

---

## 2. 契约（先写死，避免中途改主意）

```
faithful.v3 输入槽：待理顺转写（required, allowEmpty）
faithful.v3 标记：PUNCTUATION_ONLY, PREVIOUS_VIOLATIONS（与 v2.1 相同，T2 复用）
faithful 闸门（mode=FAITHFUL）：F1 事实锚点 → F2 压缩率 → F3 口水清零 → G4 人称 → G5 长度
revise 闸门（mode=REVISE）：G1 → G2 → G3 → G4 → G5 → G6（完全不动）
F2 区间：0.50 ≤ 输出内容字数 / 输入内容字数 ≤ 0.85
F1：输入里的数字（阿拉伯/中文数量词）、颜色、方位、引号专名、说话动词前的人名，输出必须逐个保留
F3：输入出现 FILLERS 任一项且输出仍有 → 失败；「我思考一下」「就是什么呢」等 meta 短语残留 → 失败；输出开头/结尾多余的空洞导语 → 失败
```

---

## Task 1: faithful.v3 提示词与 PromptSpec

**Files:**
- Create: `prompts/faithful.v3.md`
- Create: `prompts/CHANGELOG.md`（若不存在则建）
- Modify: `android/app/src/main/java/com/dreamkeeper/llm/PromptSpec.kt:64-73`
- Test: `android/app/src/test/java/com/dreamkeeper/llm/PromptRepositoryTest.kt`

- [ ] **Step 1: 先写失败测试**

在 `PromptRepositoryTest` 里加一条（沿用现有 `AssetPromptSource(context)` 的读法）：

```kotlin
@Test
fun `faithful v3 可读且槽位与标记齐全`() {
    val spec = PromptSpecs.FAITHFUL
    assertEquals("v3", spec.version)
    assertEquals("prompts/faithful.v3.md", spec.assetPath)
    val text = PromptRepository(AssetPromptSource(context)).render(
        spec.promptId,
        mapOf("待理顺转写" to "呃，我昨天做了一个梦，然后然后就去海边了。"),
    ).text
    assertTrue(text.contains("事实守恒"))
    assertTrue(text.contains("压缩到原文的五六成"))
    assertTrue(text.contains("【只做标点】"))
    assertTrue(text.contains("【上一版违规清单】"))
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `cd android && ./gradlew --no-daemon :app:testDebugUnitTest --tests "com.dreamkeeper.llm.PromptRepositoryTest"`
Expected: FAIL（`faithful.v3.md` 不存在 / 版本还是 v2.1）

- [ ] **Step 3: 写提示词正文**

`prompts/faithful.v3.md` 必须包含四段：角色、**事实守恒**、**压缩与改写**、输出格式。要求逐字出现下面这些句子（测试依赖）：

```
## 2. 事实守恒（红线）
原文里的人、地点、时间、数字、颜色、方位、物件、动作顺序、结局，一个都不能少、一个都不能改。
不确定的细节保留原话，不要替我想。

## 3. 压缩与改写（要做的事）
把口水、重复、自我纠正去掉，把「然后然后」「就是就是」合并成一句。
允许改词、调语序、并句；目标是把原文压缩到五六成，但不要压掉任何事实。
口语里的「呃、嗯、啊、那个、就是说」一律不出现在输出里。
不做文学化：不加比喻、不加解释、不加情绪，不替我总结。
```

并在文件头写 `version: v3` 与两枚标记的占位说明；`T2` 段落保持「只做标点，不改字」的原句。

- [ ] **Step 4: 指向 v3 并钉 sha**

```bash
cd /d/DreamKeeper && python - <<'PY'
import hashlib,pathlib
b=pathlib.Path('prompts/faithful.v3.md').read_bytes()
print(hashlib.sha256(b).hexdigest()[:8], len(b))
PY
```

把打印出的前 8 位填进 `PromptSpecs.FAITHFUL.sha256_8`，同时把 `version = "v3"`、`assetPath = "prompts/faithful.v3.md"`。

- [ ] **Step 5: 同步资产并跑测试**

Run:
```bash
cd android && ./gradlew --no-daemon :app:syncPromptAssets :app:testDebugUnitTest --tests "com.dreamkeeper.llm.PromptRepositoryTest"
```
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
cd /d/DreamKeeper && git add prompts/faithful.v3.md prompts/CHANGELOG.md \
  android/app/src/main/java/com/dreamkeeper/llm/PromptSpec.kt \
  android/app/src/test/java/com/dreamkeeper/llm/PromptRepositoryTest.kt
git commit -m "feat(prompt): 原样整理 v3——事实守恒、措辞重写（D575）"
```

---

## Task 2: Python 闸门 F1/F2/F3

**Files:**
- Modify: `scripts/check-faithful.py`
- Modify/Create: `docs/evidence/plan7/faithful-gate-v3-vectors.json`
- Test: `scripts/check-faithful.py --self-test`（脚本自带 self-test 段）

- [ ] **Step 1: 先加向量文件（红）**

新建 `docs/evidence/plan7/faithful-gate-v3-vectors.json`，至少 6 条，字段与 plan6 向量一致（`name/input/output/mode/expect_failed`）：

```json
[
  {"name":"v3 正常压缩：事实都在","input":"呃，我昨天做了一个梦，然后然后我们就去海边了，一共四个人。","output":"昨天我梦见我们四个人去了海边。","mode":"faithful","expect_failed":[]},
  {"name":"F1 数字丢了","input":"我们4点4分出发，6点到澳门。","output":"我们很早就出发，中午到了澳门。","mode":"faithful","expect_failed":["F1"]},
  {"name":"F2 压得太狠","input":"我梦见自己在一间很旧的教室里，黑板上写着字，字在动。","output":"梦见了教室。","mode":"faithful","expect_failed":["F2"]},
  {"name":"F2 没压缩","input":"呃，我昨天做了一个梦，然后然后我们就去海边了。","output":"呃，我昨天做了一个梦，然后然后我们就去海边了。","mode":"faithful","expect_failed":["F2","F3"]},
  {"name":"F3 口水没清","input":"嗯，我思考一下，就是什么呢，哦对，我要去澳门。","output":"嗯，我想了想，就是要去澳门。","mode":"faithful","expect_failed":["F3"]},
  {"name":"revise 不受 F 闸门影响","input":"原来的小说稿。","output":"改过的小说稿，句子更长了一些。","mode":"revise","expect_failed":[]}
]
```

- [ ] **Step 2: 跑 self-test 确认红**

Run: `python scripts/check-faithful.py --self-test`
Expected: FAIL（未实现 F1/F2/F3，或向量路径未加载）

- [ ] **Step 3: 实现三个闸门**

在 `check-faithful.py` 里新增（保持纯函数、便于移植）：

```python
def gate_f1(input_text, output_text):
    """事实锚点：数字/颜色/方位/引号专名/说话人，输入有输出也必须有。"""
    missing = []
    for tok in extract_anchors(input_text):
        if tok not in output_text:
            missing.append(tok)
    return ok() if not missing else fail("F1", f"丢了事实：{'、'.join(missing)}")

def gate_f2(input_text, output_text):
    """压缩率：输出内容字 / 输入内容字，必须落在 [0.50, 0.85]。"""
    i, o = len(content_chars(input_text)), len(content_chars(output_text))
    ratio = 0.0 if i == 0 else o / i
    if ratio < 0.50:
        return fail("F2", f"压得太狠（{ratio:.2f}）")
    if ratio > 0.85:
        return fail("F2", f"几乎没压缩（{ratio:.2f}）")
    return ok()

def gate_f3(input_text, output_text):
    """口水清零：输入有口水词则输出必须没有；meta 短语一律不许出现。"""
    hits = []
    for w in FILLERS:
        if w in input_text and w in output_text:
            hits.append(w)
    for w in META_PHRASES:
        if w in output_text:
            hits.append(w)
    if hits:
        return fail("F3", f"还有口水：{'、'.join(dict.fromkeys(hits))}")
    return ok()
```

`extract_anchors` 复用现成的 `ARABIC_NUM_RE / CN_NUM_RE / COLOR_RE / DIRECTION_WORDS / QUOTED_RE / SPEECH_NAME_RE`。`content_chars` 必须与 Kotlin 的 `contentChars` 逐字一致（去 PUNCT + 空白）。

- [ ] **Step 4: 按 mode 选闸门**

```python
def gates_for(mode):
    if mode == "revise":
        return [gate_g1, gate_g2, gate_g3, gate_g4, gate_g5, gate_g6]
    return [gate_f1, gate_f2, gate_f3, gate_g4, gate_g5]
```

`run()` 只遍历 `gates_for(mode)`；输出打印 `failed=[...]`（顺序稳定）。

- [ ] **Step 5: 跑 self-test 与向量**

Run:
```bash
python scripts/check-faithful.py --self-test
python scripts/check-faithful.py --vectors docs/evidence/plan7/faithful-gate-v3-vectors.json
```
Expected: 全部 PASS。

- [ ] **Step 6: 提交**

```bash
cd /d/DreamKeeper && git add scripts/check-faithful.py docs/evidence/plan7/faithful-gate-v3-vectors.json
git commit -m "feat(gate): 原样整理 v3 闸门 F1/F2/F3 与按 mode 选闸门（D576）"
```

---

## Task 3: Kotlin 闸门与 F 系列对齐

**Files:**
- Modify: `android/app/src/main/java/com/dreamkeeper/llm/gates/GateResult.kt`
- Modify: `android/app/src/main/java/com/dreamkeeper/llm/gates/F
- Modify: `android/app/src/main/java/com/dreamkeeper/llm/gates/FaithfulGates.kt:69-110`
- Test: `android/app/src/test/java/com/dreamkeeper/llm/gates/FaithfulGatesTest.kt`

- [ ] **Step 1: 先改测试（红）**

`FaithfulGatesTest` 改为读 `docs/evidence/plan7/faithful-gate-v3-vectors.json`，并断言：

```kotlin
@Test
fun `v3 向量逐条命中失败的闸门`() {
    vectors.forEach { v ->
        val report = FaithfulGates.check(v.input, v.output, mode = GateMode.valueOf(v.mode.uppercase()))
        assertEquals(v.name, v.expectFailed, report.failed.map { it.code })
    }
}

@Test
fun `faithful 不再有 G1-G3 和 G6`() {
    val report = FaithfulGates.check("我4点出发。", "我很早就出发了。")
    assertTrue(report.failed.none { it.code in setOf("G1", "G2", "G3", "G6") })
    assertTrue(report.failed.any { it.code == "F1" })
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `cd android && ./gradlew --no-daemon :app:testDebugUnitTest --tests "com.dreamkeeper.llm.gates.FaithfulGatesTest"`
Expected: FAIL（未知 Gate / 仍跑 G 系列）

- [ ] **Step 3: 加枚举与分流**

`GateResult.kt`：

```kotlin
enum class Gate { G1, G2, G3, G4, G5, G6, F1, F2, F3 }
```

`FaithfulGates.check` 改成：

```kotlin
val results = if (mode == GateMode.REVISE) {
    listOf(gateG1(input, output), gateG2(input, output, mode), gateG3(input, output),
        gateG4(input, output), gateG5(input, output), gateG6(input, output))
} else {
    listOf(gateF1(input, output), gateF2(input, output),
        gateF3(input, output), gateG4(input, output), gateG5(input, output))
}
```

- [ ] **Step 4: 移植 F1/F2/F3**

逐字照 Python 实现；`contentChars` 已存在，直接复用。F1 的锚点抽取抽成 `private fun anchors(s: String): List<String>`，便于单测。

- [ ] **Step 5: 补三条纯函数单测**

```kotlin
@Test fun `F1 数字缺失会报`() = assertEquals(listOf("F1"), FaithfulGates.check("4点出发，6点到。", "很早就出发了。").failed.map { it.code })
@Test fun `F2 区间边界`() = assertTrue(FaithfulGates.check(TEN, TEN.take(5)).failed.any { it.code == "F2" })
@Test fun `F3 meta 短语残留会报`() = assertTrue(FaithfulGates.check("我思考一下。", "我想了想。").failed.any { it.code == "F3" })
```

- [ ] **Step 6: 跑测试并提交**

Run: `cd android && ./gradlew --no-daemon :app:testDebugUnitTest --tests "com.dreamkeeper.llm.gates.*"`
Expected: PASS。

```bash
cd /d/DreamKeeper && git add android/app/src/main/java/com/dreamkeeper/llm/gates android/app/src/test/java/com/dreamkeeper/llm/gates
git commit -m "feat(gate): Kotlin 侧对齐 F1/F2/F3 与 mode 分流（D577）"
```

---

## Task 4: 编排器与违规清单适配

**Files:**
- Modify: `android/app/src/main/java/com/dreamkeeper/llm/GenerationOrchestrator.kt`（`violationPayload`、`faithfulChain` 注释）
- Modify: `android/app/src/main/java/com/dreamkeeper/llm/ReviseOrchestrator.kt`（只确认 G 分支仍在）
- Test: `android/app/src/test/java/com/dreamkeeper/llm/GenerationOrchestratorTest.kt`

- [ ] **Step 1: 先写一条会红的集成测试**

```kotlin
@Test
fun `T0 压太狠时把 F2 清单写进 T1 请求`() = runTest {
    val db = room(); db.dreamDao().upsert(dreamEntity(raw = LONG_380))
    val llm = FakeLlm().apply {
        enqueue(ok(TOO_SHORT_120))   // F2 红
        enqueue(ok(CLEAN_180))       // 落区间
    }
    val result = newOrchestrator(llm, RoomGenerationStore(db)).generateFaithful(DREAM_ID)
    assertEquals(GenerationStage.T1, result.finalStage)
    assertTrue(llm.requests[1].messages.single().content.contains("【上一版违规清单】"))
    assertTrue(llm.requests[1].messages.single().content.contains("F2"))
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `cd android && ./gradlew --no-daemon :app:testDebugUnitTest --tests "com.dreamkeeper.llm.GenerationOrchestratorTest"`
Expected: FAIL（清单里没有 F2 / 仍用 G 描述）

- [ ] **Step 3: 改 violationPayload**

`violationPayload` 需同时处理 `G*` 与 `F*`，输出格式统一为：

```
- F2 压得太狠（0.42）：请保留全部事实，把原文压缩到五六成。
```

每条 violation 自带 `code`，直接取 `it.code`；未知 code 用 `it.message` 兜底。T1 渲染仍只注入 `PREVIOUS_VIOLATIONS`。

- [ ] **Step 4: T2/T3 保持不变**

确认代码路径：T2 仍用 `isConservativeOk`（内容字逐字相等）判断，T3 仍回退 `input` 且 `failure=null`。加注释：

```kotlin
// v3 的 T2 仍是「只做标点」：用户要的是「再差也得是可读的原话」，
// 只是 T0/T1 现在允许改写，T2 是最后一道保守网。
```

- [ ] **Step 5: 跑整个 llm 包测试**

Run: `cd android && ./gradlew --no-daemon :app:testDebugUnitTest --tests "com.dreamkeeper.llm.*"`
Expected: PASS（含 revise 旧例）。

- [ ] **Step 6: 提交**

```bash
cd /d/DreamKeeper && git add android/app/src/main/java/com/dreamkeeper/llm/GenerationOrchestrator.kt \
  android/app/src/test/java/com/dreamkeeper/llm/GenerationOrchestratorTest.kt
git commit -m "feat(orchestrator): 违规清单支持 F 系列并锁定 T2 保守语义（D578）"
```

---

## Task 5: 原样整理思考档位（数据层）

**Files:**
- Modify: `android/app/src/main/java/com/dreamkeeper/llm/GenerationOptions.kt`
- Modify: `android/app/src/main/java/com/dreamkeeper/data/settings/GenerationPrefs.kt`
- Modify: `android/app/src/main/java/com/dreamkeeper/llm/GenerationOrchestrator.kt`（`callFaithful`）
- Test: `android/app/src/test/java/com/dreamkeeper/data/settings/GenerationPrefsTest.kt`

- [ ] **Step 1: 先写偏好测试（红）**

```kotlin
@Test
fun `原样整理思考默认低且可改`() {
    val prefs = GenerationPrefs(ctx)
    assertEquals(ThinkingLevel.LOW, prefs.options().faithfulThinking)
    prefs.setFaithfulThinking(ThinkingLevel.HIGH)
    assertEquals(ThinkingLevel.HIGH, GenerationPrefs(ctx).options().faithfulThinking)
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `cd android && ./gradlew --no-daemon :app:testDebugUnitTest --tests "com.dreamkeeper.data.settings.GenerationPrefsTest"`
Expected: FAIL（`faithfulThinking` 不存在）

- [ ] **Step 3: 加字段与键**

```kotlin
// GenerationOptions.kt
data class GenerationOptions(
    val provider: ProviderId,
    val model: String,
    val faithfulModel: String = model,
    val novelThinking: ThinkingLevel = ThinkingLevel.HIGH,
    val faithfulThinking: ThinkingLevel = ThinkingLevel.LOW,
)
```

```kotlin
// GenerationPrefs.kt
private const val KEY_FAITHFUL_THINKING = "dk_generation_faithful_thinking"
fun faithfulThinking(): ThinkingLevel = ThinkingLevel.entries
    .firstOrNull { it.name == prefs.getString(KEY_FAITHFUL_THINKING, null) }
    ?: ThinkingLevel.LOW
fun setFaithfulThinking(level: ThinkingLevel) {
    prefs.edit().putString(KEY_FAITHFUL_THINKING, level.name).apply()
}
```

`options()` 返回里补 `faithfulThinking = faithfulThinking()`。

- [ ] **Step 4: 请求体用真实档位**

```kotlin
// callFaithful
val thinking = run.faithfulThinking
ChatRequest(
    model = run.faithfulModel,
    messages = listOf(ChatMessage("user", rendered.text)),
    thinking = thinking.thinking,
    temperature = if (thinking == ThinkingLevel.OFF) temperature else null,
    jsonMode = false,
    apiKey = keyFor(run.provider),
    kind = "FAITHFUL",
    provider = run.provider,
)
```

`Run` 增加 `faithfulThinking`；`newRun()` 从 `options()` 取。

- [ ] **Step 5: 编排器测试**

```kotlin
@Test fun `原样整理 HIGH 时请求带 thinking 且不带 temperature`() = runTest {
    // prefs 设 HIGH、跑一次成功链，断言 request.thinking == Thinking.ENABLED && request.temperature == null
}
```

- [ ] **Step 6: 提交**

```bash
cd /d/DreamKeeper && git add android/app/src/main/java/com/dreamkeeper/llm/GenerationOptions.kt \
  android/app/src/main/java/com/dreamkeeper/data/settings/GenerationPrefs.kt \
  android/app/src/main/java/com/dreamkeeper/llm/GenerationOrchestrator.kt \
  android/app/src/test/java/com/dreamkeeper/data/settings/GenerationPrefsTest.kt \
  android/app/src/test/java/com/dreamkeeper/llm/GenerationOrchestratorTest.kt
git commit -m "feat(llm): 原样整理思考档位（默认低，开时不发 temperature）（D579）"
```

---

## Task 6: 设置页 UI 与状态

**Files:**
- Modify: `android/app/src/main/java/com/dreamkeeper/ui/settings/ProviderDetailViewModel.kt`
- Modify: `android/app/src/main/java/com/dreamkeeper/ui/settings/ProviderDetailScreen.kt:268-360`
- Test: `android/app/src/test/java/com/dreamkeeper/ui/settings/ProviderDetailViewModelTest.kt`

- [ ] **Step 1: 先写 VM 测试（红）**

```kotlin
@Test
fun `改原样整理思考只写偏好`() = runTest {
    val vm = ProviderDetailViewModel(deps(), provider = ProviderId.DEEPSEEK)
    vm.setFaithfulThinking(ThinkingLevel.MAX)
    assertEquals(ThinkingLevel.MAX, vm.state.value.faithfulThinking)
    assertEquals(ThinkingLevel.MAX, prefs.options().faithfulThinking)
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `cd android && ./gradlew --no-daemon :app:testDebugUnitTest --tests "com.dreamkeeper.ui.settings.ProviderDetailViewModelTest"`
Expected: FAIL。

- [ ] **Step 3: VM 状态与 setter**

`UiState` 加 `faithfulThinking: ThinkingLevel`；`setFaithfulThinking()` 写 prefs；`refresh()` 时读回。

- [ ] **Step 4: UI 四档**

在「原样整理模型」说明下、小说化模型之前插入：

```kotlin
Text("原样整理思考", style = MaterialTheme.typography.bodyMedium, color = DreamColors.paper)
ChoiceRow(
    options = ThinkingLevel.entries,
    selected = state.faithfulThinking,
    label = { it.label },
    onSelect = onFaithfulThinking,
)
Text(
    "默认「低」：原样整理要的是换句不换事，不需要长篇推理；开思考时服务端会忽略 temperature。",
    style = MaterialTheme.typography.bodySmall,
    color = DreamColors.inkDim,
)
```

把原来的「原样整理只做补标点、顺语序」整段删掉（v3 已经允许改写，旧文案会误导）。

- [ ] **Step 5: 跑测试与编译**

Run: `cd android && ./gradlew --no-daemon :app:testDebugUnitTest --tests "com.dreamkeeper.ui.settings.*" :app:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
cd /d/DreamKeeper && git add android/app/src/main/java/com/dreamkeeper/ui/settings \
  android/app/src/test/java/com/dreamkeeper/ui/settings
git commit -m "feat(settings): 原样整理思考四档与 v3 文案（D580）"
```

---

## Task 7: 真实样本校准

**Files:**
- Modify: `prompts/faithful.v3.md`（只在证据要求时改）
- Create: `docs/evidence/plan7/p7-prompt-tuning.txt`

- [ ] **Step 1: 准备三条真实样本**

从真机 `llm_calls` 或用户原话里取：①380 字口水稿（已有）；②普通 200 字短稿；③含大量专名/数字的稿。存为 `docs/evidence/plan7/samples/*.txt`（不入库，`.gitignore` 加 `docs/evidence/plan7/samples/`）。

- [ ] **Step 2: 跑闸门**

```bash
python scripts/check-faithful.py --input docs/evidence/plan7/samples/a.txt \
  --output docs/evidence/plan7/samples/a.v3.txt
```

- [ ] **Step 3: 记录结果**

`p7-prompt-tuning.txt` 记：样本字数、输出字数、压缩率、F1/F2/F3 结果、人工读感（事实完整性 / 口水清零 / 可读性），以及是否要再调提示词。

- [ ] **Step 4: 提交**

```bash
cd /d/DreamKeeper && git add docs/evidence/plan7/p7-prompt-tuning.txt .gitignore
git commit -m "docs(evidence): 原样整理 v3 真实样本校准（D581）"
```

---

## Task 8: 真机端到端、文档与 0.12.0 发布

**Files:**
- Modify: `android/app/build.gradle.kts`（versionCode 10 / 0.12.0）
- Modify: `CHANGELOG.md` / `docs/PROGRESS.md` / `docs/QA-REAL-DEVICE.md`
- Create: `docs/evidence/plan7/p7-faithful-01-vivo.{txt,png}`

- [ ] **Step 1: 全量测试**

Run: `cd android && ./gradlew --no-daemon :app:testDebugUnitTest --rerun-tasks`
Expected: classes ≥ 68，failures 0。

- [ ] **Step 2: 装机并跑真实 92 秒梦**

```bash
adb install -r android/app/build/outputs/apk/release/app-release.apk
adb logcat -c && adb shell am start -n com.dreamkeeper/.MainActivity
# 打开 11月1日的梦 → 原样 tab → 原样整理
adb logcat -d | grep -E "DreamGen|DKLLM|Gate"
```

验收：`kind=FAITHFUL status=ok http=200`；正文从四百多字压到二百字左右，数字/地名都在；日志 `failed=[]`。

- [ ] **Step 3: 截三张图**

整理前（原始转写）、整理后（原样稿）、设置页思考档位。命名 `p7-faithful-01-before.png` / `-after.png` / `-thinking.png`，写 `p7-faithful-01-vivo.txt`（host-only 头 + sha/字节 + 冷启 + 日志摘录）。

- [ ] **Step 4: 改版本与文档**

`versionCode = 10`、`versionName = "0.12.0"`；CHANGELOG 写「原样整理 v3：事实守恒、口水清零、压缩 50–85%；思考档位」；看板加一行；QA 文档把所有「原样整理只补标点」的描述改掉。

- [ ] **Step 5: 出包**

```bash
cd android && ./gradlew --no-daemon :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk ../dist/DreamKeeper-0.12.0.apk
```

- [ ] **Step 6: 提交与交付**

```bash
cd /d/DreamKeeper && git add android/app/build.gradle.kts CHANGELOG.md docs/PROGRESS.md \
  docs/QA-REAL-DEVICE.md docs/evidence/plan7
git commit -m "release: 0.12.0 原样整理 v3（D582）"
```

交付说明写：v3 与 v2.1 的差别、实测压缩率、思考默认低的原因、回滚办法（`PromptSpecs.FAITHFUL` 指回 v2.1 并跑全量测试）。

---

## 3. 验收清单（对应 spec §4）

- [ ] 380 字口水稿 → 150–260 字，且 F1 全绿
- [ ] 「呃/嗯/那个/就是什么呢/我思考一下」在输出里为 0
- [ ] 数字、颜色、方位、专名、说话人一个不缺
- [ ] T0 失败 → T1 带 F 清单重试 → T2 只标点 → T3 回退原文，链路有测试
- [ ] revise 仍跑 G1–G6，旧用例全绿
- [ ] 设置页能选四档，默认低，开时不发 temperature
- [ ] 真机 92 秒梦能出稿，成本 ≤ ¥0.01/次
- [ ] 文档与 0.12.0 包齐备
