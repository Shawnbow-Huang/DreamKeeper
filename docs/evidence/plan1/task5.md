# Plan 1 · Task 5 证据：仓库层（DreamRepository）

- 分支：`plan1-foundation`
- 实施 commit：`c24c6a2` test(data)（RED：129 条 `e:`、exit 1）→ `5e3f3b5` feat(data)
- 修补 commit：`a8db770` fix(data): `save()` 改 read-modify-write
- 日期：2026-10-30

> 说明：本文件是 Task 9 验收时补写的——Task 5 当时只把验收结论写进了 ledger，没落证据文件，导致 `task1..task4 / task6..task8` 都在、独缺 `task5.md`。补写依据是当时的测试输出与以下提交。

## 1. 交付内容

| 文件 | 作用 |
|---|---|
| `android/app/src/main/java/com/dreamkeeper/domain/Dream.kt` | 领域模型，16 个字段，**末尾追加**（不破坏既有位置参数调用） |
| `android/app/src/main/java/com/dreamkeeper/domain/Tag.kt` / `MediaAsset.kt` | 标签与素材模型 |
| `android/app/src/main/java/com/dreamkeeper/data/DreamRepository.kt` | 唯一翻译层：`observeMonth` / `observeAll` / `observe` / `save` / `search` / `observeTags` / `attachTags` / `observeTagsForDream` |
| `android/app/src/main/java/com/dreamkeeper/di/AppContainer.kt` | 手写容器：database + repository（不引 Hilt/Koin） |
| `android/app/src/main/java/com/dreamkeeper/DreamKeeperApp.kt` | 容器 + IO 线程播种 |

## 2. 关键行为（都有单测钉住）

- 搜索：`%` / `_` / `\` 全部转义；空查询与纯标点**短路返回空**，不许退化成「返回全部」；多词按空格切分逐词求交，首词决定排序。
- 编辑不毁数据：`save()` 用 `createdAt.takeIf { it > 0L } ?: now`，换时钟（1000 → 2000）后 `createdAt` 不变；软删的 `deletedAt = 500` 再 `save` 仍是 500。
- `ownerId` / `faithfulEditedByUser` / `novelEditedByUser` / `remoteId` 在保存时**原样保留**（`a8db770` 修的就是这条：原来全量重建实体会把它们抹成默认值——编辑一次就把用户的改稿标记和远端 id 丢掉）。
- 标签联表替换是幂等的；素材按 `dreamId` 映射。

## 3. 测试

```
cd D:\DreamKeeper\android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

- Task 5 交付时：**60 tests / 0 failures**（DreamRepositoryTest 18、DreamDaoTest 15、DreamCalendarTest 23、ThemeContrastTest 4）。
- `a8db770` 之后：**64 tests / 0 failures**（DreamRepositoryTest 22，其中新增 3 条覆盖 ownerId / 手改标记 / remoteId 不被覆盖，1 条覆盖 `getById`）。

## 4. 已知限制

- `observeById` 故意不过滤 `deletedAt`（回收站要能打开已删的梦）；Task 5/8 **不许**拿它做列表数据源（D26）。
- `dream_tags.tagId` 没有索引 → 标签合并是全表扫；千条内无感，加索引会触发 v2 迁移，**现在不加**（D27）。
