# Plan 1 · Task 8 证据：详情页四 tab 与中文搜索

- 分支：`plan1-foundation`
- 实施 commit：`43e0576` feat(ui): 详情页四 tab 与中文搜索（LIKE + 防抖）
- 收尾 commit（Lead）：文案与夜间 snackbar 修正，见下「Lead 的改动」
- 日期：2026-10-30

## 1. 测试

```
cd D:\DreamKeeper\android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

结果：**BUILD SUCCESSFUL，88 tests / 0 failures / 10 suites**（本次新增 6 条：`DetailViewModelTest` 3、`SearchViewModelTest` 3）。
既有 82 条无回归（DreamDao 17、DreamRepository 22、DreamCalendar 23、CalendarViewModel 4、DayViewModel 1、ThemeContrast 4、LibraryViewModel 4、DreamCardText 7）。

## 2. 构建与安装（Lead 亲跑，收尾版构建）

| 项 | 值 |
|---|---|
| APK | `android/app/build/outputs/apk/debug/app-debug.apk`，12,390,264 B |
| APK sha256 | `D155141C8F49E6CD23D81472DE818F0E76FA7BEB6C3717EEE4A9CBFD8C06ADB7` |
| 安装 | `adb install -r` → `Success` |
| 冷启动 ×3 | `909 / 914 / 910 ms`，`Status: ok` |
| 崩溃 | `logcat -d` 过滤 `FATAL EXCEPTION\|ANR in\|signal 11` → **0 命中** |

## 3. 截图（`docs/evidence/plan1/`）

全部 1080×2400 PNG，魔数 `137,80,78,71,13,10,26,10`，经 `scripts/capture-screen.ps1` 抓取。

| 文件 | 字节 | sha256(8) | 内容 |
|---|---|---|---|
| `task8-tab1-original.png` | 68,197 | `08A22513` | 10月1日「电梯」，原样 tab |
| `task8-tab1-scrolled.png` | 68,580 | `8BA4DD73` | 同页滚动后（正文仅两行，无可滚位移；overscroll 边缘渐隐导致与上图不同字节） |
| `task8-tab2-novel.png` | 73,627 | `6D9DB961` | 小说化 tab · 无稿占位 |
| `task8-tab2-novel-dream3.png` | 113,420 | `52FA0621` | 小说化 tab · 有稿，顶部 caption「补写 2 处 · 查看」 |
| `task8-tab3-tags.png` | 52,168 | `8A63EEDA` | 标签 tab（胶囊「下坠的预感」） |
| `task8-tab4-media.png` | 64,598 | `35041A71` | 素材 tab · 16:9 空槽（Lead 重拍，新文案） |
| `task8-tab4-media-dream4.png` | 65,585 | `F378FC6F` | 素材 tab · 10月22日那条（Lead 重拍） |
| `task8-search.png` | 33,107 | `CB122F71` | 搜索页，query=电梯，命中词染 Ember |
| `task8-player-norecording.png` | 78,941 | `45DB61FA` | 点播放条 → snackbar「这条梦还没有录音」（Lead 新增证据） |

近白闸门（`-TopOffset 100 -MaxRatio 0`）：

```
RESULT: OK  image=… nearWhite=0 ratio=0.0000% center=#0B0E13   （9 张全部如此）
```

## 4. 交互与无障碍核对

- 详情页 tab 四等分整格可点，选中态 = `Paper` 文字 + 2dp `Ember` 短横线，带 `selected` 语义。
- 搜索入口是 48dp 假输入框（`contentDescription = "搜索"`）；实测在 `[42,200][1038,326]` 距左沿 12px（≈4.5dp）处点击仍能进搜索页。
- 搜索页三态：初始（提示输入）／无结果（「没有找到」）／有结果；结果行 = 日期 + `cardText`，命中词 Ember。
- 所有定位走 `uiautomator` 实时 bounds，无硬编码坐标；目标文案不在屏上即 ABORT（实施过程中真触发过一次并留日志）。

## 5. 实施者登记的偏差（已并入计划编号）

| 报告里的编号 | 已并入 | 说明 |
|---|---|---|
| D-1 | **D68** | `DetailUiState` 末尾追加 `loaded: Boolean = false`。否则 `stateIn` 初值与「查无此梦」无法区分，§0.3 第 ③ 条断言会永远假绿；`loaded == false` 时详情只画顶栏 + 空白，不闪「找不到」 |
| D-2 | **D69** | `novelTabLabel(n)` 实现并单测，但按 D66 不上屏；N 只出现在小说化 tab 顶部 caption |
| D-4 | — | `tab1-scrolled` 与 `original` 同屏：seed 正文只有两行，无可滚内容；早期两次运行两图逐字节相同，最终因 overscroll 残留而不同字节，属同一页面同一状态 |
| D-5 | — | coroutines 1.10.2 里 `debounce` 仍是 `@FlowPreview`，`SearchViewModel` 加了 `@OptIn(...)` |
| D-6 | — | `novelChangeCount` 用 `补\s*写\s*(\d+)\s*处`，由「补 写 3 处」用例强制（变异验证证明该断言会红） |
| D-7 | — | 见 D68 的 UI 侧配套 |

## 6. Lead 的改动（在 `43e0576` 之上）

实施者交付后我人工读图，发现两处「用户会当场看见」的问题，直接在 `DetailScreen.kt` 修掉：

1. **用户可见文案里漏出了内部计划号**（`Plan 2 实现`、`Plan 2 开始接模型`）——这是给开发者看的话，不该出现在梦的页面上。
   → 改成「编辑还没做，之后会有」「删除还没做，之后会有」「先留个位置」。
2. **播放条点了没反应**（Plan 1 没有音频，注释写着「点了不播、也不崩」）。按钮不响等于坏了。
   → 点它给一句人话「这条梦还没有录音」。
3. 顺带发现 M3 `Snackbar` 默认用 `inverseSurface`，在深色主题里是一块**发白的板子**（截图实证），夜里晃眼、也过不了近白闸门。
   → `SnackbarHost` 自定义：`containerColor = NightRaised`、`contentColor = Paper`、`actionColor = Ember`。

改完复跑：`testDebugUnitTest` 88/88 绿、`assembleDebug` BUILD SUCCESSFUL、上面 9 张图近白全 0。

## 7. 未做 / 已知限制

- 搜索只有 `LIKE` 子串匹配（Plan 1 定案，破万条再换索引方案）——**D72**。
- debug 钩子 `dk_prefill_search` 只在 ViewModel 构造时吃一次，没处理 `onNewIntent`（真机上从搜索页再搜索会走 UI 输入，不受影响）——**D72**。
- 播放条仍是纯装饰（不持有 `MediaPlayer`、时长 `--:--`），Plan 2 接录音后替换内部实现，签名不变。
- 逐段对照（点 caption 看改动清单原文）Plan 1 不做，属 Plan 3。
