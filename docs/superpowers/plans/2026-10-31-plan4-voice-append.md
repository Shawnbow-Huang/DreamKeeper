# Plan 4：随时语音追加 · 小说化显式触发 · 发布就绪

> **给执行者**：本计划按 Task 逐个交付，每个 Task 先红后绿、独立验收、独立提交。执行前读 §0（契约），
> 执行时只改本任务 Files 里列出的路径；偏差交 Lead 编号（§9 从 **D415** 起），不要自己发明编号。

**分支**：`plan4-voice-append`（从 `plan3-generation` 的 HEAD `00a9cf2` 切出）
**基线**：39 测试类 / 365 用例 / 0 失败（Plan 3 终审后）
**目标**：① 用户随时能用语音在「原样稿」尾部追加描述，系统追加音频、分段转写、整段重新理顺，
产出新的原样稿版本且可回退；② 小说化只能由用户显式点击触发，并可在生成前写下对小说的想法；
③ 收口 Plan 3 的可见性/路由/成本栏缺口并做到可发布。

**架构**：追加走「同一 WAV 文件尾部追加 → 只转写新增尾段 → 事务内把文本拼到 `rawTranscript` 末尾 →
复用 `GenerationOrchestrator` 的 T0–T3 四级链整段重跑 → 落一条新的 faithful 版本 + 窄更新
`dreams.faithfulText`」。不新增服务端、不新增依赖；DB 由 v2 → v3 只加一列转写水位。

**技术栈**：Kotlin + Jetpack Compose + Room 2.8.5（含 room-ktx）+ OkHttp 4.12.1 + org.json；
测试 JUnit4 + Robolectric（JVM）与本机模拟器（设备证据）。

## Global Constraints（继承 Plan 3，逐条有效）

- G-1 构建前 `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`；adb 用绝对路径
  `C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe`；二进制抓取只用
  `cmd /c "adb exec-out run-as com.dreamkeeper cat <路径> > <本地>"`。
- G-2 证据落 `docs/evidence/plan4/`，命名 `p4-<case>-<nn>-<state>`；首三行
  `APK_SHA256=<前16> OLD_APK_SHA256=<前16>|n/a` / `DEVICE_TIME=<设备 date 原文>|host-only <UTC>` /
  `REPLAY=<完整命令>`；无 Key 一律 `source=fixture`；负向结论必须真跑过。
- G-3 只接 `https://api.deepseek.com/chat/completions`；模型默认 `deepseek-flash`；Key 只进 Keystore，
  证据与 REPLAY 中 Key 片段命中必须为 0；思考开启时温度被忽略。
- G-4 不引入新依赖（room-ktx/okhttp/Robolectric/协程测试均已在册）；不改 `AndroidManifest.xml`
  权限、不改价卡资产、不改既有迁移（1.json/2.json 只读）。
- G-5 提交逐路径 `git add <文件…>`，禁止 `git add -A`；每个 Task 一次提交（文档/证据可合并）。
- G-6 每个 Task 先写失败用例（红），实现后绿，并至少做一次**变异**（改坏实现 → 对应用例红 →
  恢复 → 复绿；恢复后 `cmp` 或 `git diff` 必须为空）。期望值不许从被测代码反推。
- G-7 全量基线每任务结束复跑 `:app:testDebugUnitTest --rerun-tasks`，XML 计数写进证据；
  不许用「只跑 targeted」代替全量。
- G-8 设备证据必须先 `force-stop` 再拉 `.db/.db-wal/.db-shm` 三件，主机 `wal_checkpoint(TRUNCATE)`
  合并后入库，sidecar 记 `DEVICE_DB_SHA256` 与 `MERGED_SHA256`（D371 口径）。
- G-9 实现缺陷不许标 PENDING；只有环境限制（无查看器/无镜像/无真机/模拟器无中文语音包）可 PENDING，
  且必须写「未验证 + 环境原因 + 复现步骤」。

---

## 0. 契约（T0 冻结，开工前逐条确认）

### 0.1 范围与不做

- 做：语音追加（含崩溃/失败恢复）、忠实稿基座修正、小说化显式触发与想法输入、版本线/列表/成本栏
  可见性修复、发布就绪（release、API 26–28、真机清单）。
- 不做：视频生成、iOS、账号体系、云同步、多端、提示词评测集补齐（无样本/无 Key 时如实记 PENDING）、
  `dream_transcript_segments` 逐段表（将来需要逐段时间轴时再建）。

### 0.2 数据模型 v2 → v3（只加一列）

`dreams` 追加：

```sql
ALTER TABLE `dreams` ADD COLUMN `transcriptMs` INTEGER NOT NULL DEFAULT 0
```

- 语义：`rawTranscript` **已经覆盖到的音频时长（毫秒）**，口径 `(fileBytes-44)/32`（与 `rawAudioMs` 同一公式）。
- 迁移回填（同一迁移内第二条语句，Room 允许？否——`MIGRATION_2_3` 用 `db.execSQL` 手写）：

```sql
UPDATE `dreams` SET `transcriptMs` = `rawAudioMs`
  WHERE `rawAudioPath` IS NOT NULL AND `transcriptStatus` = 'OK' AND TRIM(`rawTranscript`) <> ''
```

其余行保持 0（可重算，不假装已转写）。
- `3.json` 由 Room 导出（新增列后 `assembleDebug` 自动生成 schema JSON），`2.json` 保持只读。
- `DreamDatabase` `version = 3`，`create()` 的 `addMigrations(MIGRATION_1_2, MIGRATION_2_3)`。
- `DreamEntity`/`Dream`/`DreamRepository.toDomain`/`save()` 全链路带上新列；`save()` 的 read-modify-write
  不许把 `transcriptMs` 抹成 0。
- 禁止 `fallbackToDestructiveMigration`；升级前 `DbBackup` 快照逻辑不改。

### 0.3 追加的状态机与 CAS

三个状态量：`rawAudioMs`（文件总时长）、`transcriptMs`（已转写到的位置）、`transcriptStatus`。

- 落录音（服务已经做完）：`beginAppend(id, newRawAudioMs)`

```sql
UPDATE dreams SET rawAudioMs = :newRawAudioMs, transcriptStatus = 'PENDING'
WHERE id = :id AND (rawAudioMs IS NULL OR rawAudioMs <= :newRawAudioMs)
```

- 首次转写（原有路径，补水位）：`UPDATE … SET rawTranscript=:text, transcriptStatus='OK',
  transcriptMs=:expectedMs WHERE id=:id AND rawAudioMs=:expectedMs AND TRIM(rawTranscript)=''`。
- 追加转写（新，F2 核心）：`appendTranscript(id, expectedMs, fromMs, suffix)`

```sql
UPDATE dreams
SET rawTranscript = COALESCE(rawTranscript, '') || :suffix,
    transcriptStatus = 'OK',
    transcriptMs = :expectedMs
WHERE id = :id AND rawAudioMs = :expectedMs AND transcriptMs = :fromMs
```

- `suffix` 由调用方以 `"\n"` 前缀拼好（保证分段可读，且 G2 不计空白）；CAS 命中 0 行 = 让位（已重跑/已手改），
  不是错误，但要 logcat 一行 `TRANSCRIPT append skipped fromMs=… expectedMs=…`。
- 失败/不可用：只改状态（沿用 `updateTranscriptStatus`），**不动** `rawAudioMs`/`transcriptMs`，next 重试仍可补。
- 手动补字后追加：如果用户在追加前改过 `rawTranscript`，`beginAppend` 不改文本，追加段拼在其后（不覆盖）；
  回归用例必须覆盖这条路径。

### 0.4 尾段转写（只转新增字节）

- 新增 `stt/TailWav.kt`：`fun sliceTo(source: File, fromMs: Long, out: File): File`，把
  `[44 + fromMs*32, EOF)` 的数据段加 44 字节标准头写成临时 WAV；`fromMs` 取 `transcriptMs`。
- `TranscriptWriter` 在 `append` 模式下用 `TailWav.sliceTo` 再调 `SttEngine.transcribe(temp)`；
  临时文件用 `cacheDir`，用完删除。
- 不扩 `SystemSttEngine` 的公开接口（它内部已有 range 泵送；本轮不改，减少风险）。
- 失败文案沿用 Plan 2 的 `FAILED` 三态；PENDING 必须有重试入口（见 0.5）。

### 0.5 生成闸门（防半截原文进模型）

`GenerationOrchestrator.generatable(...)` 追加两条：

- `transcriptStatus == "OK"` 且 `rawTranscript.trim().isNotEmpty()`（Plan 3 已有）；
- `rawAudioMs == null || rawAudioMs == transcriptMs`（没有「录了但没转」的尾巴）。
- 追加录音进行中（`transcriptStatus = PENDING`）禁用生成按钮；生成进行中禁用追加入口（互斥）。

### 0.6 忠实稿基座（修 Plan 3 缺口）

- `faithful` 链成功后，除写 `novel_versions(promptId="faithful")` 外，**同事务**窄更新
  `dreams.faithfulText = <稿>`（`GenerationStore.commitSuccess` 增可选参数 `faithfulTextForDream`）。
- `novel()` 两槽分离：`【原始转写】= rawTranscript`、`【理顺稿】= faithfulText ?: rawTranscript`；
  `filterChangeLog` 的 quote 基准允许「faithfulText 或 rawTranscript 任一精确子串」。
- 「原样」tab 显示 `faithfulText`（无则显示 `rawTranscript` 并标注「还没有整理稿」），并提供
  「看原始转写」展开与「重新理顺」。

### 0.7 小说化显式触发 + 想法

- 生成入口常驻（不再只在小说的空态显示）；「小说化」按钮必须由用户点击，任何路径都不得自动触发
  NOVEL（回归用例：faithful 成功后 `llm_calls` 里 `kind=NOVEL` 行数 = 0）。
- 点「小说化」先弹 `NovelIdeaDialog`：多行输入，可留空，≤200 字；想法只进 ViewModel 暂存，
  **不落库、不进 logcat**；取消 = 不生成。
- 提示词升 `prompts/novel.v2.1.md`（纯增量）：新增第 4 槽 `【用户想法 / 选填】`（required=false,
  allowEmpty=true）+ 规则 4 条：只当偏好、与 A1–A21 冲突时静默忽略、想法不是 quote 来源、留空时与 v2 完全一致。
  同步 `PromptSpecs.NOVEL`（assetPath/version=`v2`/sha256_8/slots 4 槽）、`PromptRepositoryTest`（9→10 槽断言）、
  `prompts/CHANGELOG.md`、`prompts/README.md`。
- `promptVersion` 解析修正：`removePrefix("v").substringBefore(".").toIntOrNull() ?: 0` → v2.1 落 2；
  版本行断言 `promptVersion==2 && promptHash` 为 64 hex。

### 0.8 对话路由修复（D408）

- `prompts/novel_revise.v1.1.md`：在分流行加 3 条判定——纯提问/寒暄/评价且没提出改动要求 → `action=ASK`；
  明确要求改正文 → `REVISE`；拿不准 → `ASK`。同步 `PromptSpecs` 与测试。
- 不改 `ReviseOrchestrator` 的解析与事务；只换资产与 `PromptSpecs`（sha/version），并补 2 条解析用例。
- 真实模型行为无法离线复现：验收只做「资产+解析+分流契约」JVM 证据；真实分流质量记 PENDING（需 Key 与样本）。

### 0.9 版本线 / 列表 / 成本栏

- 小说版本线只显示 `promptId != "faithful"` 的版本；faithful 版本在「原样」tab 内单独呈现。
- 列表页显示 `resolveFinalNovel`（D332），不再是 `faithfulText ?: rawTranscript`。
- 详情顶部成本行：`这条花了 ≈ x 元`（数据来自 `llm_calls` 汇总，0 调用不显示）。
- `NOVEL_EMPTY` 文案改为「还没有小说版，点上面生成」。

### 0.10 生成任务的生存期

- 生成 job 从 `viewModelScope` 提升到应用级 scope（`AppContainer` 持有 `GenerationRunner` + 单飞），
  ViewModel 只订阅 `GenerationPhase`；离开详情页不取消、回来能看到 Running/Failed 终态。

### 0.11 测试与证据

- 每个 Task 的新用例数在 Expected 里写死；全量期望见各 Task（基线 39 类 / 365 例）。
- 设备证据：`p4-mig-*`（v2→v3）、`p4-append-*`（音频/DB/忠实稿/成本/UI）、`p4-novel-*`、
  `p4-route-*`、`p4-ui-*`、`p4-e2e-*`；截图必须带同名 txt（PNG_SHA256 + nearWhite 原文）。
- 追加 E2E 必须证明：WAV 旧字节是新文件前缀（sha 前缀比对）、`rawTranscript` 严格追加、
  `rawAudioMs` 增量 == 新增字节/32、`transcriptMs == rawAudioMs`、faithful 新版本可回退、
  `check-cost.py --db-copy --require-rows` GREEN、`check-faithful.py` 对新旧文本 GREEN。

### 0.12 范围控制与依赖

- 顺序：T1 → T2 → T3 → T4 → T5 → T6 → T7；T6 可与 T5 并行（文件不重叠）。
- 不改 Plan 1/2/3 已有证据；`docs/evidence/plan3/*` 只读。
- §14.1（从点亮屏幕到开始录音 ≤3 步/≤3 秒）沿用 Plan 2 结论，不在 Plan 4 重做；锁屏磁贴/通知/常亮/
  夜间特则/回收站延后到 Plan 5。
- 生图美学增强不在本计划。

---

## 任务总览

| Task | 主题 | 主要文件 | 设备证据 |
| --- | --- | --- | --- |
| T1 | 数据库 v3 + 追加 CAS | `data/db/**`、`domain/Dream.kt`、`data/DreamRepository.kt` | 无（JVM） |
| T2 | 追加写回与重转写兜底 | `stt/**`、`data/DreamRepository.kt` | 无（JVM） |
| T3 | 忠实稿基座与重跑 | `llm/GenerationOrchestrator.kt`、`llm/GenerationStore.kt` | 无（JVM） |
| T4 | 「补一段」入口与原样 tab | `ui/record/**`、`ui/detail/**`、`ui/DreamKeeperRoot.kt` | `p4-append-05/06` |
| T5 | 小说化显式触发与想法 | `prompts/novel.v2.1.md`、`llm/**`、`ui/detail/**` | `p4-novel-*` |
| T6 | 可见性与体验缺陷 | `ui/**`、`llm/GenerationRunner.kt`、`di/AppContainer.kt` | `p4-ui-*` |
| T7 | 发布就绪与终验 | build/证据/文档 | `p4-e2e-*` |

## Task 1：数据库 v3 与追加 CAS

**Files**
- 改：`android/app/src/main/java/com/dreamkeeper/data/db/DreamEntity.kt`、`DreamDao.kt`、`DreamDatabase.kt`、
  `android/app/src/main/java/com/dreamkeeper/domain/Dream.kt`、`data/DreamRepository.kt`
- 生成：`android/app/schemas/com.dreamkeeper.data.db.DreamDatabase/3.json`（Room 自动导出，必须入库）
- 新：`android/app/src/test/java/com/dreamkeeper/data/db/MigrationV2ToV3Test.kt`、`AppendTranscriptTest.kt`

**Interfaces**
```kotlin
@Entity(tableName = "dreams", indices = [...])
data class DreamEntity(..., @ColumnInfo(defaultValue = "0") val transcriptMs: Long = 0)

// DreamDao
@Query("""
  UPDATE dreams SET rawAudioMs = :newRawAudioMs, transcriptStatus = 'PENDING'
  WHERE id = :id AND (rawAudioMs IS NULL OR rawAudioMs <= :newRawAudioMs)
""")
suspend fun beginAppend(id: String, newRawAudioMs: Long): Int

@Query("""
  UPDATE dreams
  SET rawTranscript = COALESCE(rawTranscript, '') || :suffix,
      transcriptStatus = 'OK',
      transcriptMs = :expectedMs
  WHERE id = :id AND rawAudioMs = :expectedMs AND transcriptMs = :fromMs
""")
suspend fun appendTranscript(id: String, expectedMs: Long, fromMs: Long, suffix: String): Int

@Query("UPDATE dreams SET rawTranscript = :text, transcriptStatus = 'OK', transcriptMs = :expectedMs WHERE id = :id AND rawAudioMs = :expectedMs AND TRIM(rawTranscript) = ''")
suspend fun updateTranscript(id: String, text: String, expectedMs: Long): Int

@Query("UPDATE dreams SET rawAudioMs = :newRawAudioMs WHERE id = :id")
suspend fun updateRawAudioMs(id: String, newRawAudioMs: Long): Int
```
- `DreamDatabase`：`version = 3`，`create()` 里 `addMigrations(MIGRATION_1_2, MIGRATION_2_3)`；`MIGRATION_2_3` 的
  ALTER 与回填 UPDATE 各一条 `db.execSQL`，禁止 fallback。
- `Dream.transcriptMs`，`toDomain`/`save()` 全链路带列；`save()` 不许把 `transcriptMs` 归零。

**Steps（先红后绿）**
1. 写 `MigrationV2ToV3Test`：手搭 v2 库（`2.json` 的 createSql + `room_master_table` 的 v2 identity hash）→
   `DreamDatabase.create()` 打开 → 断言 `user_version=3`、`dreams` 多 `transcriptMs`、回填正确
   （OK+有文本 → =rawAudioMs；PENDING/空文本/无音频 → 0）、旧行其余 20 列不变。**此时必红**（缺迁移）。
2. 实现迁移 + 3.json + entity/dao/domain/repo，跑绿；`check-migrate.py --diff` 对 v2/v3 dump GREEN。
3. 写 `AppendTranscriptTest`（真 Room in-memory）：首次写带水位；`appendTranscript` 命中 1 行且文本严格追加、
   水位更新；`fromMs` 或 `expectedMs` 不匹配 → 0 行且文本不变；手改文本后追加不覆盖；`beginAppend` 不改文本、
   置 PENDING、旧值更大时 0 行。
4. 变异：删掉 `AND transcriptMs = :fromMs` → 重复追加用例红；恢复后 `cmp` 原文件一致。
5. 全量 `--rerun-tasks`，写证据 `p4-mig-00-red.txt` / `01-after.txt` / `02-diff.txt`（host-only 头）。

**Expected**
- 新 2 类：`MigrationV2ToV3Test`（3 例）、`AppendTranscriptTest`（7 例）；全量 **41 类 / 375 例 / 0 失败**。
- `3.json` 入库且与 `DreamDatabase` 版本一致；`2.json`/`1.json` 字节未变（`sha256sum` 写进证据）。

## Task 2：追加写回与重转写兜底（stt 层）

**Files**
- 新：`android/app/src/main/java/com/dreamkeeper/stt/TailWav.kt`
- 改：`stt/TranscriptWriter.kt`、`stt/SttEngine.kt`（如需暴露 `transcribe(file)` 的唯一入口，不改语义）、
  `data/DreamRepository.kt`（`appendTranscribed(suffix, expectedMs, fromMs)`）
- 新测试：`stt/TailWavTest.kt`、`stt/TranscriptAppendTest.kt`

**Interfaces**
```kotlin
object TailWav {
    /** 取 [fromMs, EOF) 的数据段，加 44 字节头写成 out；返回写出的字节数。 */
    fun sliceTo(source: File, fromMs: Long, out: File): Long
}

// DreamRepository
suspend fun appendTranscribed(dreamId: String, suffix: String, expectedMs: Long, fromMs: Long): Boolean
suspend fun reconcilePendingTranscriptions(): Int   // 启动补转写（F1）
```
- `TranscriptWriter`：单飞改为「在飞任务结束时若 `dataSize` 变化 → 排一次补跑」；append 模式用
  `TailWav.sliceTo(file, dream.transcriptMs, temp)` 再转写；成功后 `appendTranscribed("\n" + text, …)`；
  CAS 0 行 → 不覆盖，logcat `TRANSCRIPT append skipped`。
- 失败：只 `updateTranscriptStatus(FAILED)`，`rawAudioMs`/`transcriptMs` 不动；下次可重试。
- 启动 reconcile：`PENDING` 且 `rawAudioMs > transcriptMs` 的梦，按追加路径补跑；`FAILED` 不自动重跑。

**Steps（先红后绿）**
1. 红 `TailWavTest`：空段/半段/整段、头部 44 字节与 RIFF/`WAVE`/`fmt `/`data` 字段、数据字节等于源文件对应区间、
   `durationMs == (bytes-44)/32`。
2. 红 `TranscriptAppendTest`（fake SttEngine，真 Room）：已有转写 → 追加 → 文本严格拼接、水位前进、状态 OK；
   转写中 dataSize 再变 → 补跑一次且只拼一次；被杀遗留 PENDING → reconcile 补上；手改文本 → 追加不覆盖。
3. 实现，跑绿；变异：把「补跑排队」改回「直接丢弃」→ 补跑用例红；恢复复绿。
4. 证据 `p4-append-00-red.txt`、`p4-append-01-jvm.txt`（host-only）。

**Expected**
- 新 2 类：`TailWavTest`（5 例）、`TranscriptAppendTest`（6 例）；全量 **43 类 / 386 例 / 0 失败**。
- 旧 `TranscriptWriterTest` 全绿；不新增依赖。

## Task 3：忠实稿基座与整段重跑

**Files**
- 改：`llm/GenerationOrchestrator.kt`（`novel()` 两槽分离、quote 基准 dual、参数透传）、
  `llm/GenerationStore.kt`（`commitSuccess` 增 `faithfulTextForDream: String?`）、
  `data/db/DreamDao.kt`（窄 UPDATE 带 `faithfulText`）、`test/.../GenerationOrchestratorTest.kt`（加 4 例）
- 新测试：`llm/FaithfulBaseTest.kt`（可并入现有类，若并入则 Expected 调整需先报 Lead）

**Interfaces**
```kotlin
suspend fun commitSuccess(
    dreamId: String, draft: NovelVersionEntity, changeLogJson: String?, stage: String,
    faithfulTextForDream: String? = null,
): Int
```
- faithful 链成功 → 同事务写 `dreams.faithfulText`；novel 链不写。
- `novel()`：`SLOT_RAW = dream.rawTranscript`、`SLOT_POLISHED = dream.faithfulText?.takeIf { it.isNotBlank() } ?: dream.rawTranscript`；
  `filterChangeLog` 接受「faithfulText 或 rawTranscript」任一精确子串。
- 原文有更新（`rawAudioMs > transcriptMs`）时「重新理顺」按钮文案提示；重跑生成新 faithful 版本、旧版不删。

**Steps（先红后绿）**
1. 红：faithful 成功后查库 `faithfulText` 非空且等于正文；novel 的槽 1 取 raw、槽 2 取 faithful（构造 raw≠faithful）；
   quote 只在 raw 里的片段不被丢。
2. 实现，绿；变异：`faithfulTextForDream` 传 null → 基座用例红；恢复复绿。
3. 全量 + 证据 `p4-faithful-00-red.txt` / `01-green.txt` / `02-mutation.txt`。

**Expected**
- 加 4 例（并入现有类则类数不变；若新建类则 **44 类 / 390 例**，实施者先报 Lead 再定）。
- `p3` 的 GenerationOrchestratorTest 全绿不退化。

## Task 4：「补一段」入口与原样 tab

**Files**
- 改：`ui/record/RecordScreen.kt`（append 模式复用）、`ui/DreamKeeperRoot.kt`（`append/{dreamId}` 路由）、
  `ui/detail/DetailScreen.kt`（原样 tab 重写：忠实稿正文 + 看原始转写 + 补一段 + 重新理顺 + 三态）、
  `ui/detail/DetailViewModel.kt`（`appendState`、`onRecordingFinished`、互斥）
- 新测试：`ui/detail/AppendEntryTest.kt`（ViewModel 层，3 例）
- 设备证据：`p4-append-05-ui.png`、`p4-append-06-append.png` + sidecar

**Steps**
1. 红 `AppendEntryTest`：进入 append 路由后松手 → `beginAppend` 被调一次；生成进行中入口禁用；转写 PENDING 时禁用「重新理顺」。
2. 实现 UI；原样 tab 显示 `dream.faithfulText ?: dream.rawTranscript`，无忠实稿标注「还没有整理稿」；
   追加完成回到详情 → 自动 `generateFaithful`（防重入，fake runner 恰好一次）。
3. 设备 smoke：seed-dream-4 追加 10 秒 → 截图 + DB 三件 + WAV 前后 sha（`p4-append-05/06/07`）。
4. 变异：去掉防重入 → 自动重跑用例如红；恢复。

**Expected**
- 全量 **44 类 / 393 例 / 0 失败**（含 Task 3 的新类时按实际报告）。
- 设备：追加后 `rawTranscript` 严格追加、`transcriptMs == rawAudioMs`、faithful 新版本出现、旧版仍可选。

## Task 5：小说化显式触发与想法

**Files**
- 新：`prompts/novel.v2.1.md`、`prompts/novel_revise.v1.1.md`
- 改：`prompts/CHANGELOG.md`、`prompts/README.md`、`llm/PromptSpec.kt`、`llm/GenerationOrchestrator.kt`
  （`novel(dreamId, idea: String?, onProgress)`、`SLOT_IDEA`）、`llm/GenerationRunner.kt`、`llm/ReviseOrchestrator.kt`
  （路由解析若需）、`ui/detail/DetailViewModel.kt`、`ui/detail/DetailScreen.kt`（常驻入口 + `NovelIdeaDialog`）
- 改测试：`test/.../llm/PromptRepositoryTest.kt`（9→10 槽）；新 `ui/detail/NovelIdeaTest.kt`（3 例）

**Steps**
1. 红：`PromptRepositoryTest` 断言 novel.v2.1 有四槽、第 4 槽可选可空、缺槽语义不变；
   `NovelIdeaTest`：想法 ≤200 字透传、空想法传 null（槽整对删除）、faithful 成功后 `llm_calls` 零条 `kind=NOVEL`。
2. 写提示词资产（纯增量），同步 PromptSpecs sha/version/slots 与 CHANGELOG/README；实现 idea 透传与对话框；
   `promptVersion` 解析修正并加断言（`v2.1` → 2）。
3. 变异：把第 4 槽标成 required → 空想法用例红；恢复复绿。
4. 设备 smoke：点「小说化」弹框 → 输想法 → 生成 → 版本行 `promptVersion=2`、`promptHash` 64 hex；
   不输想法直接生成也可（槽被删）。证据 `p4-novel-01/02/03`。
5. D408：`novel_revise.v1.1` 分流 3 条规则 + 2 条解析用例（纯提问→ASK、明确改动→REVISE、拿不准→ASK）。

**Expected**
- 全量 **46 类 / 403 例 / 0 失败**（PromptRepositoryTest 10 槽、新增 NovelIdeaTest 3 例）。
- 资产进包：`unzip -l app-debug.apk | grep prompts/` 含 v2.1 与 v1.1 旧版仍在。

## Task 6：可见性与体验缺陷

**Files**
- 改：`ui/detail/VersionStrip.kt`（过滤 faithful）、`ui/detail/DetailScreen.kt`（NOVEL_EMPTY 文案、成本行）、
  `ui/library/LibraryScreen.kt`、`ui/library/DreamCard.kt`（列表用 `resolveFinalNovel`）、
  `ui/detail/DetailViewModel.kt`（生成订阅 app-scope）、`llm/GenerationRunner.kt`、`di/AppContainer.kt`
- 新测试：`ui/detail/VersionStripFilterTest.kt`（2 例）、`ui/library/LibraryPreviewTest.kt`（2 例）
- 设备：`p4-ui-01/02/03.png`

**Steps**
1. 红：版本线只出 `promptId != "faithful"`；列表卡片显示选中小说版而不是忠实稿；成本行 0 调用不显示、>0 显示 2 位小数。
2. 实现 app-scope 生成：`DetailViewModel` 不再 `viewModelScope.launch` 生成；离开页再回来能读到 Running/Failed 终态
   （用 fake runner + 两次 ViewModel 实例断言）。
3. 设备 smoke：小说 tab 版本线不含 v1、列表卡片文案、成本行。
4. 变异：版本线不过滤 → 用例如红。

**Expected**
- 全量 **48 类 / 407 例 / 0 失败**。

## Task 7：发布就绪与终验

**Files**
- 改：`android/app/build.gradle.kts`（如 minify 需保留 keep 规则）、`docs/PROGRESS.md`、`docs/evidence/plan4/FINAL-REVIEW.md`、
  `docs/evidence/plan1/ACCEPTANCE.md`（新增 `## 3e`）
- 证据：`p4-e2e-00..12`、`p4-release-01`、`p4-api26-01`

**Steps**
1. release 包（未签名或 debug key）离线构建、安装、冷启动 3 次、导出三格式打开态（有查看器则截图）。
2. API 26–28 镜像可用性探测：无镜像 → A24 保持 PENDING 并写环境证据。
3. 真机清单：列出必须真机验证的 5 项（STT 中文、长录音、Key、导出打开、通知/音量键），逐项标 PENDING 或 PASS。
4. FINAL-REVIEW：baseline ↔ final、A1–A24 判定、偏差 D415–、PENDING 白名单；ACCEPTANCE `## 3e`。
5. 看板 8/8、最后更新、下一步 Plan 5。

**Expected**
- 无新增单测（全量保持 Task 6 的 **48 类 / 407 例**）；release 与 API 证据入库；R1–R5 风险结论写进终审。

---

## 9. 偏差登记（从 D415 起）

| 编号 | 日期 | 任务 | 偏差 | 原因（必须可实测） |
| --- | --- | --- | --- | --- |
| （待填） | | | | |

## 10. 自审记录

每个 Task 完成后由 Lead 追加：`### Task N 验收（Lead，YYYY-MM-DD）`，含交付 commit、Lead 独立复跑、
证据核对、变异、PENDING、结论六要素。Task 7 之后追加 `### Plan 4 终审`。
