# Task 7 证据 —— 全部梦境列表（时间轴 / 卡片流切换并记忆）

日期：2026-10-30
分支：`plan1-foundation`

## 提交

| SHA | 说明 |
|---|---|
| `85d5394` | test(ui): 全部页排序 / 排布记忆 / 标签传递与落盘单测（先红） |
| `a98b967` | feat(ui): 全部梦境列表（时间轴 / 卡片流切换并记忆），6 files +442/−5 |
| `bb276c3` | fix(ui): 无标题的梦不再把首句画两遍（`cardText` 分派 + 7 条单测，D63） |

## 测试（Lead 独立复跑，不看实施者报告）

```
cd android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

- 结果：**82 tests / 0 failures / 8 suites**（Task 7 新增 `LibraryViewModelTest` 4 条、`DreamCardTextTest` 7 条）
- 红：`Unresolved reference 'LibraryViewModel'`（BUILD FAILED in 15s）
- 实施者另做 4 次变异验证：去掉 prefs 写入 → `expected:<CARDS> but was:<TIMELINE>`；tags 传 `emptyList()` → 超时；去掉 `layout.value` → 超时；倒序 → `expected:<[2, 1]> but was:<[1, 2]>`。四条断言都能真的变红，不是空断言。

## APK

- `android/app/build/outputs/apk/debug/app-debug.apk`
- 12,228,539 字节，sha256 `A40899D5FDA2205B767959F61EE1601F778A10403138240BBC5A132C88D3D908`

## 截图（1080×2400，全部 `nearWhite=0`）

| 文件 | 字节 | sha256 | 人工读图结论 |
|---|---|---|---|
| `task7-timeline.png` | 78,038 | `234494D5…396C` | 4 条，10月 22 / 15 / 8 / 1 日倒序；左侧 **1.5dp `Ember` 竖线**（4px）与 4 个圆点对齐；每条 58.7dp 高（>48dp） |
| `task7-cards.png` | 136,750 | `C5DF73E5…FE1D` | 4 张卡；「电梯」卡带 `下坠的预感` 标签 chip（D62 生效）；**「一直在下楼梯」卡只有一行**（D63 修复后不再重复正文） |
| `task7-cards-after-restart.png` | 136,750 | `C5DF73E5…FE1D` | 与上图**字节完全相同**：`am force-stop` 后重进，切到「全部」仍是卡片视图 |

两张卡片截图 sha 相同是本次最强的一条证据——不是「看起来像卡片」，而是同一帧画面。

偏好落盘实测：

```
$ adb shell run-as com.dreamkeeper cat shared_prefs/layout_prefs.xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="library_layout">CARDS</string>
</map>
```

## 近白闸门

```
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-near-white.ps1 <图> -TopOffset 100 -MaxRatio 0
```

三张图：`samples=69120 nearWhite=0 ratio=0.0000% center=#0B0E13`，exit 0。

## 冷启动与崩溃（最终构建）

`scripts/capture-logcat.ps1 -Runs 3 -OutFile docs\evidence\plan1\task7-logcat.txt`

- `RUN 1/2/3: TotalTime=923 / 952 / 909 ms`（min 909 / median 923 / max 952）
- `CRASHES: 0`、`LAUNCH: Displayed=3`、`RESULT: OK`
- 日志 1,479 行 / 304,613 字节

## 可点区（uiautomator 实测）

| 控件 | 尺寸 |
|---|---|
| 分段控件（每段） | 75 × 48 dp |
| 时间轴条目 | 379 × 58.7 dp |
| 卡片 | 379 × 88 dp |

## 复现步骤

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
cd D:\DreamKeeper\android; $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug --no-daemon
cd D:\DreamKeeper
& $adb shell pm clear com.dreamkeeper
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.dreamkeeper/.MainActivity
Start-Sleep 5
& $adb shell input tap 810 2274        # 底栏「全部」
Start-Sleep 2
& $adb shell input tap 924 155         # 分段控件「卡片」（x≈765 是「时间轴」）
```

## 未验收 / 已知限制（诚实清单）

1. **空态没有截图**（D59）：debug 恒播种，`pm clear` 后立刻重播 4 条，本轮进不去空态。
2. **分段控件定宽 152dp**（D65）：未做 1.3x 大字号走查，「时间轴」三个字在超大字号下可能被截断。
3. **时间轴依赖 `IntrinsicSize.Min`**：以后若把它塞进 `LazyRow`/`SubcomposeLayout` 会崩。
4. 行内标签只显示名字，不支持点击筛选（Plan 2 的标签页负责）。
