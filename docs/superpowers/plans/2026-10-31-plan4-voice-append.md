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
  WHERE `rawAudioPath` IS NOT NULL AND `rawAudioMs` IS NOT NULL
    AND `transcriptStatus` = 'OK' AND TRIM(`rawTranscript`) <> ''
```

其余行保持 0（可重算，不假装已转写）。
- `3.json` 由 Room 导出（新增列后 `assembleDebug` 自动生成 schema JSON），`2.json` 保持只读。
- `DreamDatabase` `version = 3`，`create()` 的 `addMigrations(MIGRATION_1_2, MIGRATION_2_3)`。
- `DreamEntity`/`Dream`/`DreamRepository.toDomain`/`save()` 全链路带上新列；`save()` 的 read-modify-write
  不许把 `transcriptMs` 抹成 0。
- `@ColumnInfo(defaultValue = "0")` 与 ALTER 字面量必须与 3.json 的 createSql 同口径（`DEFAULT 0`，不是 `'0'`），
  否则打开即抛 `Migration didn't properly handle`。
- 既有 `MigrationTest.kt` 把 `user_version` 硬编码成 2，v3 后必红 → T1 必须把它改成 3（Files 已列）。
- 老 D202 用户若真有「已录未转」尾巴，回填会把它标成已覆盖：接受该限制，T4 的「重新整理」与
  「重试转写/这段不用转写」入口兜底，并在 §9 记一条（D 号由 Lead 分配）。
- 禁止 `fallbackToDestructiveMigration`。
- **DbBackup 快照必须泛化**（T1 内修，实测缺口）：现状只对 `user_version==1` 快照、前缀写死 `dreamkeeper-v1-`，
  而 v2→v3 也是一次不可回滚迁移 → 改成「当前 `user_version < 目标版本` 就快照，文件名带版本号」，
  并补 2 条用例（v2 库会快照、v3 库不重复快照）。

### 0.3 追加的状态机与 CAS

三个状态量：`rawAudioMs`（文件总时长）、`transcriptMs`（已转写到的位置）、`transcriptStatus`。

- **时序**：`beginAppend` 由录音服务在 `onRecordingFinished` 里、入队 STT **之前**落库（不是 UI 松手之后），
  避免 writer 的 `awaitRow` 窗口里 CAS 撞 0 行。

- 落录音：`beginAppend(id, newRawAudioMs)`

```sql
UPDATE dreams SET rawAudioMs = :newRawAudioMs, transcriptStatus = 'PENDING'
WHERE id = :id AND (rawAudioMs IS NULL OR rawAudioMs < :newRawAudioMs)   -- 严格小于：0 字节 take 不留 PENDING 死锁
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
- **空转写判失败**：追加段 STT 无有效文字 → 不前进水位、置 `FAILED`、保留旧文本；否则每次启动都会重试并堵死生成闸门。
- **跳过本轮**：`skipUncoveredAudio(id, expectedMs)` 把 `transcriptMs = rawAudioMs`（不动文本、状态 OK），
  给「这段不用转写」按钮用（静音/环境噪补录的唯一出路）。
- **崩溃恢复判据**：reconcile 扫的是 `rawAudioMs > transcriptMs`（**不依赖 `transcriptStatus`**），
  因为录音中被杀时 `beginAppend` 根本没机会执行；`AudioScan` 发现文件变长 → 更新 `rawAudioMs` 并置 `PENDING`。
- 手动补字后追加：如果用户在追加前改过 `rawTranscript`，`beginAppend` 不改文本，追加段拼在其后（不覆盖）；
  回归用例必须覆盖这条路径。

### 0.4 尾段转写（只转新增字节）

- 新增 `stt/TailWav.kt`：`fun sliceTo(source: File, fromMs: Long, out: File): File`，把
  `[44 + fromMs*32, EOF)` 的数据段加 44 字节标准头写成临时 WAV；`fromMs` 取 `transcriptMs`。
- `TranscriptWriter` 在 `append` 模式下用 `TailWav.sliceTo` 再调 `SttEngine.transcribe(temp)`；
  临时文件用 `cacheDir`，用完删除。
- 不扩 `SystemSttEngine` 的公开接口（它内部已有 range 泵送；本轮不改，减少风险）。
- `TailWav.sliceTo` 前置断言 `rawAudioMs - fromMs == (sliceBytes / 32)`（不符即失败，不许把 0 行 CAS 静默当真）；
  `fromMs` 越界或空段返回 0 → 走失败路径；临时文件 `finally` 删除，ENOSPC 走失败文案。
- 失败文案沿用 Plan 2 的 `FAILED` 三态；**PENDING/FAILED 必须有用户可见的重试入口**：
  详情页「重试转写」按钮（置 PENDING 后重跑 reconcile 单梦）与「这段不用转写」（`skipUncoveredAudio`）。
  两条动作各补 1 条用例（T4）。

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

- 小说版本线只显示 `promptId != "faithful"` 的版本；faithful 版本在「原样」tab 内单独呈现，
  并可在原样 tab 切换查看旧 faithful 版本（「可回退」的落点，不写 `novelSelectedVersion`）。
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

- 顺序：T1 → T2 → T3 → T4 → T5 → T6a → T6b → T7。T6 不能整体与 T5 并行（都会改 DetailScreen/DetailViewModel）；
  仅 T6a（Library/VersionStrip 纯 UI）可在 T5 期间先跑，T6b（app-scope + AppContainer）必须排在 T5 后。
- 不改 Plan 1/2/3 已有证据；`docs/evidence/plan3/*` 只读。
- §14.1（点亮屏幕到开始录音 ≤3 步/≤3 秒）：Plan 2 只证明了音频侧预算（冷启≈1.85s、按下→RECORDING 163ms），
  **不判绿**；Plan 4 不补测，ACCEPTANCE 记「未覆盖，Plan 5」（D415）。
- 锁屏磁贴/通知/常亮/夜间特则/完整设置页/回收站：Plan 3 计划曾列在 Plan 4 backlog，本计划明确推迟 Plan 5
  （D416，**需用户确认**）。
- 生图美学增强不在本计划。
- 提示词评测：仓库无真实样本评分（dreams.md 0/20、无 Key）。T5 只做「v2 与 v2.1 渲染差异 + 槽契约」的
  fixture 前后对比，真实质量记 PENDING（D417），不许写「评测通过」。

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
  WHERE id = :id AND (rawAudioMs IS NULL OR rawAudioMs < :newRawAudioMs)   -- 严格小于：0 字节 take 不留 PENDING 死锁
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
3. 写 `AppendTranscriptTest`（真 Room in-memory）与 `DreamRepositoryTranscriptWatermarkTest`（整行 save() 不回退水位）：首次写带水位；`appendTranscript` 命中 1 行且文本严格追加、
   水位更新；`fromMs` 或 `expectedMs` 不匹配 → 0 行且文本不变；手改文本后追加不覆盖；`beginAppend` 不改文本、
   置 PENDING、旧值更大时 0 行。
4. 变异：删掉 `AND transcriptMs = :fromMs` → 重复追加用例红；`save()` 改回 `dream.transcriptMs` → 水位回退用例红；恢复后 `cmp` 原文件一致。
5. 全量 `--rerun-tasks`，写证据 `p4-mig-00-red.txt` / `01-after.txt` / `02-diff.txt`（host-only 头）。

**Expected**
- 新 3 类：`MigrationV2ToV3Test`（3 例）、`AppendTranscriptTest`（10 例）、
  `DreamRepositoryTranscriptWatermarkTest`（2 例，save() 不回退水位）；`DbBackupTest` 4→6 例；
  全量 **42 类 / 382 例 / 0 失败**。`DbBackup` 对 v2 库也快照、v3 库不重复快照。
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
4. 证据 `p4-append-03-tailwriter.txt`（host-only；因 T2 由 Lead 直接实施，红=两处变异反证，见 D427）。

**Expected**
- 新 2 类：`TailWavTest`（6 例，含空段/越界/长度不符）、`TranscriptAppendTest`（7 例，含空文本判失败与 skip 水位）；
  全量 **44 类 / 395 例 / 0 失败**（T1 后 382 + 13；D428）。
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
- 加 4 例并**并入 `GenerationOrchestratorTest`**（类数不变 → **44 类 / 399 例**，D431）。
- `p3` 的 GenerationOrchestratorTest 全绿不退化。

## Task 4：「补一段」入口与原样 tab

**Files**
- 改：`ui/record/RecordScreen.kt`（append 模式复用）、`ui/record/RecordViewModel.kt`（`appendDreamId`）、
  `ui/DreamKeeperRoot.kt`（`append/{dreamId}` 路由）、`ui/nav/DreamRoute.kt`（`Append`）、
  `service/RecordingService.kt`（按 `createdThisTake` 分流追加收尾）、
  `stt/TranscriptWriter.kt`（`onAppendRecordingFinished`：先 beginAppend 再排队）、
  `ui/detail/DetailScreen.kt`（原样 tab 重写：整理稿正文 + 看原始转写 + 补一段 + 重新理顺 + 三态）、
  `ui/detail/DetailViewModel.kt`（`onAppendRecorded`、`canAppendNow`、重试/跳过、自动重理顺）
- 新测试：`ui/detail/AppendEntryTest.kt`（ViewModel 层，6 例：追加入口/互斥/闸门/重试转写/跳过/旧 faithful 版切换）
- 设备证据：`p4-append-05-ui.png`、`p4-append-06-append.png` + sidecar

**Steps**
1. 红 `AppendEntryTest`：进入 append 路由后松手 → `beginAppend` 被调一次；生成进行中入口禁用；转写 PENDING 时禁用「重新理顺」。
2. 实现 UI；原样 tab 显示 `dream.faithfulText ?: dream.rawTranscript`，无忠实稿标注「还没有整理稿」；
   追加完成回到详情 → 自动 `generateFaithful`（防重入，fake runner 恰好一次）。
3. 设备 smoke：seed-dream-4 追加 10 秒 → 截图 + DB 三件 + WAV 前后 sha（`p4-append-05/06/07`）。
4. 变异：去掉防重入 → 自动重跑用例如红；恢复。

**Expected**
- 全量 **45 类 / 405 例 / 0 失败**（AppendEntryTest 6 例：追加入口/互斥/闸门/重试转写/跳过/旧 faithful 版切换）。
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
- 全量 **46 类 / 409 例 / 0 失败**（PromptRepositoryTest 10 槽 +1 例、新增 NovelIdeaTest 3 例）。
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
- 全量 **48 类 / 413 例 / 0 失败**（T6a 验收值；T6b 完成后 **49 类 / 417 例**）。

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
- 无新增单测（全量保持 T6b 的 **49 类 / 418 例**）；release 与 API 证据入库；R1–R5 风险结论写进终审。

---

## 9. 偏差登记（从 D415 起）

| 编号 | 日期 | 任务 | 偏差 | 原因（必须可实测） |
| --- | --- | --- | --- | --- |
| D415 | 2026-10-31 | T0 | 设计 §14.1「音频预算」不补测、不判绿 | Plan 2 FINAL-REVIEW 只证「音频侧通、不判绿」，plan1:3412 仍记「Plan 4 未开始」；本计划不补测，ACCEPTANCE `## 3e` 记「未覆盖，Plan 5」 |
| D416 | 2026-10-31 | T0 | 锁屏磁贴/通知/常亮/夜间/完整设置页/回收站推迟 Plan 5 | Plan 4 范围只收用户明确要的「语音追加 + 小说化显式触发」；这些项在 plan1:3484 的 backlog 里无验收落点，**需用户确认** |
| D417 | 2026-10-31 | T0 | 真实模型质量「未评」 | 评测集 0/20 真实梦、当前无 Key；只做 v2 vs v2.1 的 fixture 渲染前后对比，不写「评测通过」 |
| D418 | 2026-10-31 | T1 | T1 由 Lead 直接实施，红证据是「变异反证 + 恢复复绿」而非严格先红 | 原 subagent 因 `scripts/check-migrate.py` 白名单卡点挂起且不可寻址（`active teammate not found`）；为避免继续空转，Lead 接手。诚实口径：红=删挂载变异必红，见 p4-mig-00/append-00 |
| D419 | 2026-10-31 | T1 | `AppendTranscriptTest` 10 例（计划 7 例） | 多出的 3 例兜住实测风险：等长/更短 no-op（0 字节追加死锁）、手动重试只从 FAILED、跳过转写幂等 |
| D420 | 2026-10-31 | T1 | `3.json` 由 Room 编译导出（`exportSchema=true`），MIGRATION_2_3 逐字对齐它 | 手工写 schema 是 Plan 3 已有的教训；`transcriptMs` 的 `INTEGER NOT NULL DEFAULT 0` 必须与 3.json 一致，否则 Room 自校验在老用户机上抛 Migration didn't properly handle |
| D421 | 2026-10-31 | T1 | `DbBackup.TARGET_SCHEMA_VERSION` 改为引用 `DreamDatabase.SCHEMA_VERSION` | 两处常量各写一个 3 会漂移；Builder 复核明确建议单源 |
| D423 | T1 审计：`save()` 写 `dream.transcriptMs` 会把水位回退 | 改 `existing?.transcriptMs ?: dream.transcriptMs`，补 `DreamRepositoryTranscriptWatermarkTest` 2 例并做变异反证（必红）；`p4-append-02-watermark.txt` |
| D424 | T1 证据 `DEVICE_TIME` 是模板常量（超前 20 分钟）、`p4-mig-02-diff` 的 REPLAY 套了 gradlew、`p4-full` 有两条 REPLAY | 全部改写为真实写入时刻 + 可执行命令，并加「时间口径」注；JVM-only 任务不再借用设备时间 |
| D425 | T1 证据命名偏离派工书（`-green` 而非 `-after`、`p4-full` 不在 glob） | 保留 `-green` 语义（绿跑而非 after 状态），名实一致优先于派工书笔误；glob 视为示例 |
| D426 | 计划 T1 Expected 仍写 7 例/377，T2–T7 冻结计数因此少算 | 统一为 42 类/382 例起，T2–T7 顺延为 44/392、45/396、46/402、47/406、49/410、49/410 |
| D427 | T2 由 Lead 直接实施（上一个实施者不可寻址且已挂起），无「先红」阶段 | 以变异反证代替红：删 `pendingRerun.add` → 补跑用例红；证据 `p4-append-03-tailwriter.txt` 记原文与恢复 sha |
| D428 | T2 实测新增 13 例（6+7），计划冻结的 392 例少算 3 例 | 全量口径改为 44 类/395 例，T3–T7 顺延为 45/399、46/405、47/409、49/413、49/413 |
| D429 | 尾段临时 WAV 若落 audio 目录，会被 AudioScan 当「孤儿录音」恢复成草稿 | 临时文件固定落 `cacheDir/stt-tail`，`finally` 删除；`AppContainer` 注入 `tempDir` |
| D430 | `TailWav.sliceTo` 源文件短于 44 字节时无法构造合法 WAV | 抛 `IOException`（不是静默 0）；越界/空段才写合法空 WAV 返回 0，由调用方判失败 |
| D431 | T3 的 4 例并入 `GenerationOrchestratorTest` 而非新建类，类数不变 | 记为 44 类/399 例；T4–T7 的类数预测同步减 1（45/405、46/409、48/413、48/413） |
| D432 | `commitSuccess` 增第 5 个参数会破现有 FakeStore 实现 | 参数带默认值 `null`；测试的 FakeStore/Commit 同步扩列，生产调用点只忠实链传值 |
| D433 | 忠实链 T3（保守档也失败）不建版本、不写 `dreams.faithfulText` | 返回 `text=input` 由调用方重跑；避免「失败也落库」，语义等价（raw 即正文） |
| D434 | `filterChangeLog` 由单基准改双基准（理顺稿+原始转写） | quote 命中任一即保留；只在忠实稿里的引文不再被误丢（新增 2 例锁定） |
| D422 | 2026-10-31 | T1 | `scripts/check-migrate.py` 的 `diff_2_3` 比较前需去 `i:`/`s:` 前缀 | 实测踩到：dump 值是 canon 字符串，`transcriptStatus == "OK"` 恒假 → 回填期望永远算成 0，真实数据必 RED。已加 `decanon()`；修后 fixture GREEN、负向 RED、v1→v2 回归逐字不变 |
| D435 | 2026-10-31 | T4 | T4 由 Lead 直接实施（无独立实施者），无严格「先红」 | 与 D418/D427 同口径：以变异反证代替红——关掉自动重理顺 → 2 例红（`6 tests completed, 2 failed`），恢复后 6/6 绿；证据 `p4-append-04-tests.txt` |
| D436 | 2026-10-31 | T4 | 追加录音复用 `RecordScreen` 并通过 `appendDreamId`/`onAppendSaved` 区分去向 | 新开一套录音 UI 会复制手势/权限/中断三条路径（Plan 2 的 6 个坑）；复用只加两个分支：`starter.start(既有 id)` 与保存后 `nav.popBackStack()` |
| D437 | 2026-10-31 | T4 | Service 用 `createdThisTake` 判定追加并走新入口 `enqueueAppend` | 追加时文件是 `markOpen` 复用，不能删、不能建新梦；分流点必须在 `onRecordingFinished` 的 afterRelease，否则整段重转会重复旧文字 |
| D438 | 2026-10-31 | T4 | `onAppendRecordingFinished` 先 `beginAppend` 再排队，0 行时等最多 5s | Voice AI 复核 ②：service 收尾早于 RecordViewModel 落库时，Writer 的 `awaitRow` 会 0 行静默丢段；等不到就交给启动 reconcile（T2）兜底 |
| D439 | 2026-10-31 | T4 | 自动重理顺只在「status==OK 且 transcriptMs≥rawAudioMs」时触发一次 | 用户要「随时可语音续写」；若转写没追平就重跑，整理稿会漏掉新段。`appendPending` 消费后清零，避免状态再发射时重复跑 |
| D440 | 2026-10-31 | T4 | 设备追加 demo 落在真实录音那条梦（`4f3b44fd…`）而非 seed-dream-4 | 该条是当前唯一有 WAV 的梦（rawAudioMs=2400）；实测追加后 140844B=44+4400×32、rawAudioMs=4400、status 回 PENDING |
| D441 | 2026-10-31 | T4 | 两张设备截图 `nearWhite≈0.13%`，非 0 | 白色像素来自状态栏（时钟/信号），正文区为 Night 底；判据改为「正文区无白底」，并记录像素数与坐标来源说明 |
| D442 | 2026-10-31 | T4 | 模拟器无中文离线包，追加段转写仍失败，行停在 PENDING | 这正是需求要覆盖的降级路径（T2 的 reconcile 会在下次启动补跑）；本任务不宣称设备转写成功，`transcriptMs==rawAudioMs` 与 faithful 新版本留 T7 真机/真实 STT 验收 |
| D443 | 2026-10-31 | T4 | 计划里 T4 的「旧 faithful 版切换」并入原样 tab 的「看原始转写/看整理稿」切换，未单开用例 | 用户要的是「能看原文」；旧版本行的选择已经由 T5/T6 的版本线承担，重复做会两套口径 |
| D444 | 2026-10-31 | T5 | 生成成功后 `dreams.novelVersion` 不推进（实测仍 7、版本表最大 8）；GENERATE 版本条显示「vN · 无说明」，丢改动数 | 导出/版本线都走 `resolveFinalNovel`/版本表，不依赖该列；「无说明」是 versionLabel 只认 assistant 摘要所致 → T6a 用 changeLog 计数兜底 |
| D445 | 2026-10-31 | T5 | 「想法非空」与 D408「纯提问→ASK」只有 JVM/提示词层证据，设备侧验证缺失 | 模拟器无中文输入法（`adb input text` 仅 ASCII），无法输入中文想法/提问；`NovelIdeaTest` 已覆盖 200 字截断与槽渲染，提示词 v1.1 §0 已写分流规则 → 设备验证归 T7，能打字时补 |
| D446 | 2026-10-31 | T5 | `GenerationActions` 改为常驻（`showNovel` 恒 true），与 D406「有小说稿即收起」不同 | 需求 B 要求「主动再小说化」：已有小说稿时仍允许带新想法重新生成；重入由 `Running` 防重入兜住 → T6a 复核视觉与误触 |
| D447 | 2026-10-31 | T5 | `p4-novel-01/02` 首三行用真实设备时间（非 host-only） | 这两份含设备操作（安装/点击/截图/拉库），按 G-2 用 `adb shell date` 抓取；纯 JVM 的 `p4-novel-00` 仍是 host-only |
| D448 | 2026-10-31 | T6a | `resolveFinalNovel()` 与「全部」页 SQL 都过滤掉 `promptId="faithful"` 的版本行（原来会把整理稿当成小说版导出/展示） | T3 起整理稿也落 `novel_versions`，旧口径没有 promptId 概念；导出文件名与「当前 vN」都必须指向小说版，否则 T3 的收益被旧查询吃掉 |
| D449 | 2026-10-31 | T6 | T6 拆成 T6a（版本线/列表/文案，已完成）与 T6b（成本行 + 生成提到 app-scope） | T6b 要改 `DetailScreen`/`DetailViewModel`/`GenerationRunner`，与 T6a 同文件；一次派工容易互相踩。T6b 期望 +1 类 4 例 → 49 类/417 例，T7 沿用 |
| D450 | 2026-10-31 | T6a | `observeAllWithPreview()` 的 SQL 没有单测 | Room 查询要靠真实库跑；`LibraryPreviewTest` 只锁纯函数兜底顺序，SQL 由设备 `p4-ui-01` 的现象佐证（列表正文切到选中版） |
| D451 | 2026-10-31 | T6a | 设备截图只能证明「可见项里没有 faithful」 | `LazyRow` 只渲染可见项，远端行不在截图里；过滤的确定性证据是 `VersionStripFilterTest` + 变异必红，设备图只作现象佐证 |

| D452 | 2026-10-31 | T6b | 成本行用四位小数（`累计 ¥%.4f`），不是计划写的两位 | 一次调用的量级常是 0.001–0.01 元，两位小数会永远显示 ¥0.00；单位是微元（1e-6），四位是能让「花了钱」可见的最小位数 |
| D453 | 2026-10-31 | T6b | 跨页进度表 `GenerationTracker` 由导航根 `remember` 持有，作用域由容器注入 `generationScope` | 请求必须活过 ViewModel；进度表必须活过 ViewModel 的进度字段。放容器会引入 ui→di 反向依赖，放 ViewModel 又活不过重建——导航根是两者共同祖先 |
| D454 | 2026-10-31 | T6b | 新增 5 例（4 例 `GenerationScopeTest` + 1 例 `AppendEntryTest`），不是计划写的 4 例 → 49 类/418 例 | 纯 tracker 单测杀不掉「VM 忘了写 tracker」这个变异；第 5 例用真 Room + 门闩 runner 证明 `publishPhase` 真的同步了两处 |
| D455 | 2026-10-31 | T6b | 设备截图 nearWhite≈0.13% 沿用 D441 的状态栏例外 | 两张图都是详情页深色背景，白像素集中在状态栏与手势条；正文本体 nearWhite=0 |
| D456 | 2026-10-31 | T6b | 设备证据用「离开再回来成本 0.0105→0.0114、版本 v8→v9」证明 app-scope，Running 瞬态未截到 | 本机生成约 25s，导航往返约 6s，回来时已完成；Running 语义由第 5 例与 `runningPhaseSurvivesOutsideTheViewModel` 守，不硬凑截图 |
## 10. 自审记录

### T0 契约冻结（Lead，2026-10-31）
- 交付：`724d13e`（计划 404 行）、`71cd30b`（Voice AI 复核收紧）、本文档后续修订。
- 结论：契约冻结，T1 开工；测试计数冻结为 48 类 / 413 例（终态；D431 顺延后）。

### Task 1 验收（Lead，2026-10-31）
- **交付**：`6 个主文件 + 4 个测试文件 + 3.json + 5 份 p4-* 证据`（本提交）。主改动：`DreamEntity` 追加
  `transcriptMs`（`@ColumnInfo(defaultValue = "0")`）、`DreamDatabase` version=3 + `MIGRATION_2_3`（ALTER + 回填）、
  `DreamDao` 三条新 CAS（`beginAppend`/`appendTranscript`/`markTranscriptionRetry`/`skipUncoveredAudio`）+
  `updateTranscript` 带水位、`DreamRepository` 五个包装 + save/toDomain 带新列、`DbBackup` 目标版本单源。
- **Lead 独立复跑**：`MigrationV2ToV3Test` 3/3、`AppendTranscriptTest` 10/10、`MigrationTest` 3/3、
  `DbBackupTest` 6/6、`DreamRepositoryTranscriptWatermarkTest` 2/2；全量 **42 类 / 382 例 / 0 / 0 / 0**（36s）。
- **变异**：删 `MIGRATION_2_3` 挂载 → 3/3 红（`A migration from 2 to 3 was required but not found`）；
  `appendTranscript` 拼串改覆盖 → 3 红（幂等/追加/手改保留）；两处恢复后 sha 与变异前逐字节相同并复绿。
- **证据**：`p4-mig-00-red.txt`、`p4-mig-01-green.txt`、`p4-mig-02-diff.txt`（含合成库 GREEN + 负向 RED +
  `diff_2_3` 去前缀修复）、`p4-append-00-red.txt`、`p4-append-01-green.txt`、`p4-full.txt`。
- **PENDING**：设备上的真实 v2→v3 迁移（当前模拟器库仍是 v2）→ T4/T7 E2E；`rawAudioMs` 大文件下的
  TailWav 边界（T2）；release 升级（T7）。
- **审计补记（D423）**：实施者复核实测「`save()` 直接写 `dream.transcriptMs`」会让旧 Dream 对象的整行保存把水位抹回 0
  （重复追加风险）→ 改 `existing?.transcriptMs ?: dream.transcriptMs` + 2 例 + 变异必红 + `p4-append-02-watermark.txt`；
  同时修正证据 `DEVICE_TIME` 模板值/REPLAY（D424）与命名偏离（D425）、计数口径（D426）。
- **结论**：通过。偏差 D418–D426 已登记；下一步 T2（写回/reconcile/TailWav）。

### Task 2 验收（Lead，2026-10-31）

- **交付**：`f1768fe` 之后的工作区（本提交）——`stt/TailWav.kt`、`stt/TranscriptWriter.kt`（在飞排队 + reconcile）、
  `DreamDao.markAppendFailed/pendingAppends`、`DreamRepository` 三个包装、`DreamKeeperApp.onCreate` 启动补跑、
  `AppContainer` 注入 `cacheDir/stt-tail`、`TailWavTest`（6 例）、`TranscriptAppendTest`（7 例）、
  `docs/evidence/plan4/p4-append-03-tailwriter.txt`。
- **Lead 独立复跑**：targeted `--rerun-tasks` → `13 tests completed, 0 failed`（59s）；
  全量 `--rerun-tasks` → **44 类 / 395 例 / 0 / 0 / 0**（1m11s）。
- **契约核对**：切片只含 `[transcriptMs, rawAudioMs)`、头 44 字节新 size；成功走后缀 CAS 且文本严格 `before + "\n" + suffix`；
  在飞重入排队一次、本轮结束**重读行**用新水位补跑；失败只 `markAppendFailed`（正文/水位不动）；
  启动 reconcile 只补 `PENDING && rawAudioMs > transcriptMs`，FAILED 不自动重跑；临时文件在 cache 且 `finally` 删除。
- **变异**：删 `pendingRerun.add` → `inFlightFinishQueuesOneRerunWithFreshWatermark` 红（7/1，BUILD FAILED 59s）；
  恢复后 `TranscriptWriter.kt` sha256 `55393ae1…002388` 与备份逐字同（D427 以变异代红）。
- **证据**：`p4-append-03-tailwriter.txt`（host-only 头；含为什么/改法/测试/绿跑/变异原文/恢复 sha/全量/未覆盖）。
- **PENDING**：设备真实追加录音（T4）、进程被杀后的启动补转写（T4/T7）、长录音性能（T7）。
- **结论**：通过。偏差 D427–D430 已登记；下一步 T3（忠实稿基座与整段重跑）。

### Task 3 验收（Lead，2026-10-31）

- **交付**：本提交——`GenerationStore.commitSuccess` 增 `faithfulTextForDream`（与版本行同事务）、
  `DreamDao.updateNovelAfterGeneration` 增 `COALESCE(:faithfulText, faithfulText)` 列、
  `GenerationOrchestrator.novel()` 两槽分离、`filterChangeLog` 双基准；
  `GenerationOrchestratorTest` +4 例；证据 `docs/evidence/plan4/p4-faithful-00-base.txt`。
- **Lead 独立复跑**：targeted `--rerun-tasks` → `12 tests completed, 0 failed`（57s）；
  全量 `--rerun-tasks` → **44 类 / 399 例 / 0 / 0 / 0**（1m14s）。
- **契约核对**：忠实链成功同事务写 `dreams.faithfulText`（真 Room 断言）；小说化原始槽=rawTranscript、
  理顺槽=faithfulText（空回退原文，两槽同值时原文出现 2 次）；changeLog 引文在任一基准命中即保留；
  小说化/改稿仍传 `null` 表示不动该列。
- **变异**：① `faithfulTextForDream = null` → 落库用例红（12/1，59s）；
  ② `SLOT_RAW to polished` → 两槽分离用例红（12/1，57s）；恢复后 `GenerationOrchestrator.kt`
  sha256 `07d2b7db…ce1e` 与备份逐字同。
- **证据**：`p4-faithful-00-base.txt`（host-only 头；为什么/改法/4 例/绿跑/两处变异原文/恢复 sha/全量/未覆盖）。
- **PENDING**：设备侧「重跑忠实链后原样 tab 显示新稿」（T4 一起验）；真实 LLM 下 faithful 与 raw 的差异质量（T7）。
- **结论**：通过。偏差 D431–D434 已登记；下一步 T4（「补一段」入口与原样 tab）。

### Task 4 验收（Lead，2026-10-31）

- **交付**：本提交——`DreamRoute.Append`、`RecordViewModel.appendDreamId`、`RecordScreen` append 模式、
  `RecordingService.enqueueAppend`、`TranscriptWriter.onAppendRecordingFinished`、
  `DetailViewModel.onAppendRecorded/canAppendNow/retryTranscription/skipTranscription`、
  `DetailScreen` 原样 tab 重写；新测试 `AppendEntryTest`（6 例）。
- **Lead 独立复跑**：targeted `--rerun-tasks` → `6 tests completed, 0 failed`（15s，XML tests=6/failures=0）；
  全量 `--rerun-tasks` → **45 类 / 405 例 / 0 / 0 / 0**（55s）。
- **契约核对**：追加模式用既有 dreamId 起录音（用例 1）；转写追平后只自动跑一次忠实链（用例 2）；
  未追平不跑、追平后补跑（用例 3）；生成在飞时 `canAppendNow()==false`（用例 4）；
  重试把 FAILED→PENDING 且水位不动（用例 5）；跳过把 `transcriptMs` 追平（用例 6）。
- **变异**：关掉自动重理顺 → 用例 2/3 红（`6 tests completed, 2 failed`，BUILD FAILED 24s），
  恢复后 `DetailViewModel.kt` sha256（全文件）`cd1a00c5…7325`，targeted 6/6 绿。
- **证据**：`p4-append-04-tests.txt`（host-only）、`p4-append-05-ui.png/.txt`（入口+三态，设备 03:36）、
  `p4-append-06-append.png/.txt`（追加后 WAV 140844B、rawAudioMs=4400、PENDING、DB 三件 sha）。
- **PENDING**：模拟器无中文离线包 → 追加段转写成功与「整理稿出现新版本」留 T7 真机/真实 STT；
  「补一段」权限收回/来电中断沿用录音页既有路径，未新增用例。
- **结论**：通过。偏差 D435–D443 已登记；下一步 T5（小说化显式触发与想法）。

每个 Task（含 T0）完成后由 Lead 追加：`### Task N 验收（Lead，YYYY-MM-DD）`，含交付 commit、Lead 独立复跑、
证据核对、变异、PENDING、结论六要素。Task 7 之后追加 `### Plan 4 终审`。

### Task 5 验收（Lead，2026-10-31）

- **交付**：提交随 T5 一并入库——`prompts/novel.v2.1.md`（`cc497720`）、`prompts/novel_revise.v1.1.md`（`45a17e0a`）、`PromptSpec`/`GenerationRunner`/`GenerationOrchestrator`/`ReviseOrchestrator`/`DetailViewModel`/`DetailScreen`（`NovelIdeaDialog`）、`PromptRepositoryTest`（+1 例）、`NovelIdeaTest`（3 例）、FakeRunner 签名同步、`prompts/README.md` 与 `CHANGELOG.md` 增量。
- **Lead 独立复跑**：targeted `PromptRepositoryTest`(11) + `NovelIdeaTest`(3) = **14/0**（47s）；全量 `--rerun-tasks` → **46 类 / 409 例 / 0 失败 / 0 错误 / 0 跳过**（54s）。
- **契约核对**：第 4 槽 required=false/allowEmpty=true 且空值整对删除（`PromptRepositoryTest` 新例）；v2.1 四槽顺序与提示行替换；想法 trim+截断 200、空白→null（`NovelIdeaTest`）；忠实链不产生小说调用（`NovelIdeaTest` 第 3 例）；`promptVersion` 由 `v2.1` 解析为 2（设备 v8 行实测 `promptVersion=2`、`promptHash` 64 hex）。
- **变异**：第 4 槽改 `required=true` → 仅 `novelV2_1HasFourSlotsAndIdeaSlotIsOptionalButAllowsEmpty` 红（`14 tests completed, 1 failed`），恢复后 `PromptSpec.kt` sha 前16 `b325e78b126602d3` 复绿。
- **证据**：`p4-novel-00-tests.txt`（host-only）、`p4-novel-01-dialog.{png,txt}`（弹框，nearWhite=0）、`p4-novel-02-generated.{png,txt}`（生成后 v8 行、DB 双侧 sha、合并 sha、nearWhite=0、Key 命中 0）。
- **PENDING**：真实梦质量评测（D417，无样本）；想法非空/D408 路由的设备验证（D445）；版本条与 `dreams.novelVersion` 修正（D444，转 T6a）；真机转写（T7）。
- **结论**：通过。下一步 T6a（版本线/列表/重试入口收口），再 T6b（app-scope/成本行）→ T7。

### Task 6a 验收（Lead，2026-10-31）

- **交付**：`VersionStrip.visibleVersions()` 过滤 faithful + `versionLabel` 改动数兜底（D444）；`DreamDao.observeAllWithPreview()` + `DreamRepository.observeAllWithPreview()`/`DreamPreview` + `LibraryItem.finalNovelText` + `DreamCard(previewText=…)` + `ui/library/LibraryPreview.kt`（D332）；`resolveFinalNovel` 过滤 faithful（D448）；`NOVEL_EMPTY` 文案；新测试 `VersionStripFilterTest`（2 例）、`LibraryPreviewTest`（2 例），既有 `VersionStripTest` 期望按 D444 更新。
- **Lead 独立复跑**：两个新类各 2/0（41s）；全量 `--rerun-tasks` → **48 类 / 413 例 / 0 失败 / 0 错误 / 0 跳过**（57s）。
- **契约核对**：版本线只出小说版本；列表预览优先选中版 → 整理稿 → 原始转写；导出/当前版号不含 faithful。
- **变异**：`visibleVersions` 去掉过滤 → `faithful rows are hidden from the version strip` 红（2/1，42s）；恢复后 `VersionStrip.kt` sha 前16 `f67f276562960f13`。
- **证据**：`p4-ui-00-tests.txt`（host-only）、`p4-ui-01-list.{png,txt}`、`p4-ui-02-version.{png,txt}`（均 nearWhite=0）。
- **PENDING**：SQL 无单测（D450）、远端 faithful 不可截图（D451）、成本行/app-scope 转 T6b（D449）。
- **结论**：通过。下一步 T6b（成本行 + 生成 app-scope，49 类/417 例）→ T7 终验。

### Task 6b 验收（Lead，2026-10-31）

- **交付**：`ui/detail/GenerationTracker.kt`（新，导航根持有的进度表）；`DetailViewModel` 增 `generationScope`/`tracker` 参数与 `publishPhase()` 收口、成本 `costMicroYuan`；`DetailScreen` 增两参数与成本行渲染、`NovelTab(costMicroYuan)`；`VersionStrip.costLabel()`；`LlmCallDao.sumCostByDream` + `DreamRepository.sumCostMicroYuan`；`DreamKeeperRoot`（`remember { GenerationTracker() }` + `generationScope` 透传）、`MainActivity` 传 `container.generationScope`；新测试 `GenerationScopeTest`（4 例）+ `AppendEntryTest` 第 7 例。
- **Lead 独立复跑**：`GenerationScopeTest` 4/0（26s）、`AppendEntryTest` 7/0（22s）；全量 `--rerun-tasks` → **49 类 / 418 例 / 0 失败 / 0 错误 / 0 跳过**（54s）。
- **契约核对**：`costLabel(0)==null`（无请求不占行）、四位小数；`publishPhase` 三态同步 tracker；launch 走 `generationScope ?: viewModelScope`；版本条仍不含 faithful。
- **变异**：`is Running -> tracker?.running(...)` 改 `Unit` → `AppendEntryTest > generation phase is published to the tracker FAILED`（7/1，27s）；恢复后 `DetailViewModel.kt` sha256 `eef4594e…8714`，targeted 复绿。
- **证据**：`p4-ui-05-tests.txt`（host-only）、`p4-ui-03-cost.{png,txt}`（成本行 ¥0.0105）、`p4-ui-04-scope.{png,txt}`（离开再回来：¥0.0114、v9、补写 4 处）。
- **PENDING**：进程被杀后的 Running 恢复（tracker 是内存态）；真实网络的长时间离开（T7 真机）；成本行在 0 请求梦上的设备截图（JVM 已锁）。
- **结论**：通过。Task 6 收口；下一步 T7（发布就绪与终验）。
