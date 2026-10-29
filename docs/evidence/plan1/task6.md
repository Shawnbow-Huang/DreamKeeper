# Task 6 验收证据 · 日历首屏、当日列表与真路由

- 计划：`docs/superpowers/plans/2026-10-29-foundation-and-library.md` Task 6
- 任务书：`.superpowers/sdd/2026-10-29-foundation-and-library/task-6-brief.md`
- 分支：`plan1-foundation`
- commits：
  - `ea819e1` feat(data)：补 `observeDay` 与按当月播种的种子（D47/D48）
  - `7787b07` test(ui)：日历与当日列表 ViewModel 的 5 条用例
  - `485324b` feat(ui)：实现 `CalendarViewModel` 与 `DayViewModel`
  - `48f89f2` feat(ui)：日历首屏（月份切换、有梦红点、说梦大钮）
  - `9b1c901` fix(ui)：底部栏改自绘，去掉 M3 空指示器（本轮）
  - 本条证据归档提交见 `git log -- docs/evidence/plan1/task6.md` 的下一笔（本轮）
- 验收时间：2026-10-30（Asia/Shanghai）
- 设备：`emulator-5554`，Android 17 / API 37，1080×2400，density 2.625

## 本轮收尾修复（两处）

### 1. 底部栏不再用 M3 `NavigationBar`：`icon = {}` 的空药丸

第一轮按 brief 写的是：

```kotlin
NavigationBar(containerColor = Night) {
    NavigationBarItem(selected = ..., icon = {}, label = { Text("日历") })
    NavigationBarItem(selected = ..., icon = {}, label = { Text("全部") })
}
```

`NavigationBarItem` 的选中态由两层组成：**icon 槽位上方的 32×32 指示药丸** + 标签变色。
Plan 1 的底部栏只有文字、没有图标，`icon = {}` 之后药丸照画不误，于是「日历」上方
多出一块**没有内容的灰色胶囊**（第一轮 `task6-calendar.png` 里可以看到）。M3 没有提供
关掉指示器的开关，`NavigationBarItem` 也没有"只要文字"的形态，所以本轮直接自绘：

- 根节点 `Row`（`fillMaxWidth().background(Night).navigationBarsPadding()`），
  内放两个等宽（`Modifier.weight(1f)`）可点区域；
- 每项 `Column`：`heightIn(min = 48.dp)` + `clickable` + `semantics { contentDescription = label; selected = ... }`；
- 文字：选中 `Paper #E6EAF2` / 未选中 `InkDim #8B95A7`（`bodyMedium` 15sp）；
- 指示线：选中项下方 20dp 宽、2dp 高的 `Ember #C8873F` 短横线；未选中时同一位置画 `Night`（与栏同色、看不见），
  这样切换选中**文字不会上下跳**，也没有引入透明色——配色全部取自 `ui/theme/Color.kt` 现有常量；
- 显示条件不变：只有 `current == Calendar || current == Library` 才挂 `Scaffold.bottomBar`；
- `navigationBarsPadding()` 只垫这一次，Scaffold 会把底栏高度（含 inset）算进 `innerPadding`，
  子页不重复避让。

改动文件：`android/app/src/main/java/com/dreamkeeper/ui/DreamKeeperRoot.kt`
（`NavigationBar` / `NavigationBarItem` 的 import 一并删除）。

### 2. 模拟器时区是 GMT，导致「今天」圈画在 29 号

「今天」是 `LocalDate.now()`（设备本地日期），设备时区是 GMT 时系统日期还是 10-29，
所以第一轮截图里 Ember 圆圈套在 29 号。**没有为了截图改任何日期逻辑**，只改设备时区：

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb shell "settings put global auto_time_zone 0"        # 关自动时区
& $adb root                                                 # -> adbd cannot run as root in production builds
& $adb shell "cmd alarm set-timezone Asia/Shanghai"         # 走 system_server，成功
& $adb shell "setprop persist.sys.timezone Asia/Shanghai"   # -> Failed to set property ... See dmesg for error reason.
& $adb shell "getprop persist.sys.timezone"                 # Asia/Shanghai
& $adb shell date                                           # Fri Oct 30 01:29:03 CST 2026
```

说明：这个模拟器镜像是 **production build，`adb root` 不可用**，所以非 root 下
`setprop persist.sys.timezone` 被属性服务拒绝；真正生效的是 `cmd alarm set-timezone Asia/Shanghai`
（AlarmManagerService 在 system_server 里同时改运行时时区与 `persist.sys.timezone`）。
`date` 已显示 **CST（UTC+8）且日期与主机一致**（主机 `2026-10-30 01:29:05 +08:00`），
`getprop persist.sys.timezone` 也是 `Asia/Shanghai`，重启后不会回退。

## 测试

命令：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd D:\DreamKeeper\android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL in 32s`，**71 个用例，0 失败 0 错误 0 跳过**
（数字取自 `app/build/test-results/testDebugUnitTest/*.xml` 的 `tests/failures/errors` 求和）。

| 测试类 | 用例数 |
|---|---|
| `DreamDaoTest` | 17（原有 15 + 新增 `observeDay` 2） |
| `DreamRepositoryTest` | 22 |
| `DreamCalendarTest` | 23 |
| `CalendarViewModelTest` | 4（新增） |
| `DayViewModelTest` | 1（新增） |
| `ThemeContrastTest` | 4 |
| **合计** | **71** |

> 任务书正文的「新增 4 条 = 68」是旧口径：它只算了 ViewModel 的 4 条日历用例，
> 漏了 Step 1b 的当日列表 1 条与 Step 0 的 `observeDay` 2 条。按 brief 逐条执行后
> 新增是 2 + 4 + 1 = 7 条，64 + 7 = **71**；本轮改底栏后仍是 71/71 全绿，没有删改任何用例。

RED 证据（第一轮真实输出摘录）：

- Step 0a：`e: file:///D:/DreamKeeper/android/app/src/test/java/com/dreamkeeper/data/DreamDaoTest.kt:135:39 Unresolved reference 'observeDay' on receiver of type 'DreamDao'.` → `BUILD FAILED`
- Step 2：`e: file:///D:/DreamKeeper/android/app/src/test/java/com/dreamkeeper/ui/calendar/CalendarViewModelTest.kt:59:18 Unresolved reference 'CalendarViewModel'.` → `BUILD FAILED`
- 首次跑 Step 2 还暴露了 brief 测试骨架缺 `import kotlinx.coroutines.asExecutor`（`Unresolved reference 'asExecutor' on receiver of type 'TestDispatcher'`）；补 import 后才得到上面的「类不存在」红。

## Schema 未变（本任务不得改表结构）

- 文件：`android/app/schemas/com.dreamkeeper.data.db.DreamDatabase/1.json`，8,531 字节
- `version = 1`，`identityHash = 3150a0bccf0e3cb537faa8aac20e4186`，`entities = 4`
- `git diff --stat HEAD -- android/app/schemas` 为空（本轮改底栏与截图，仍未碰表结构）

## 构建与安装

| 项 | 结果 |
|---|---|
| `:app:assembleDebug --no-daemon` | `BUILD SUCCESSFUL in 32s` |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk`，12,184,115 字节，SHA256 `979B96BA614E1D2AD3D25BD170BD138F8ECCCC531877727F9E51AFC4DA888155` |
| `adb uninstall com.dreamkeeper` | `Success`（截图前复位种子；旧库会让 `seedIfEmpty` 因 `count != 0` 早退） |
| `adb install -r app-debug.apk` | `Performing Streamed Install` / `Success` |
| `adb shell am start -n com.dreamkeeper/.MainActivity` | `mCurrentFocus=...com.dreamkeeper/com.dreamkeeper.MainActivity` |

## 截图与人工读图

三张图均由 `scripts/capture-screen.ps1 -OutPath <目标> -Serial emulator-5554` 生成
（脚本内部走 cmd 二进制安全重定向并校验 PNG，避开 PowerShell `>` 把 PNG 写成 UTF-16 的坑）。

| 文件 | 尺寸 / 字节 | SHA256 | MD5 |
|---|---|---|---|
| `task6-calendar.png` | 1080×2400 / 83,304 | `A5AE73BEA06798176A06F976F75B6FB0DEBD924D5F4356496D31E74504412897` | `5C4D878360393E0C437FEA811824E387` |
| `task6-day.png` | 1080×2400 / 49,569 | `288C1AA9183B31F549A84B3FE9FE49F7D7ABF4EF667221C42C77CE18AC8190A4` | `561D4666F211F122EE38B3E3F9B60510` |
| `task6-calendar-empty.png` | 1080×2400 / 77,331 | `A4A80DAF00960850F8CF3DF566B858A538245ADB15B3A7B461EB1931C036160A` | `8102F89CAD37BFCAD574322483989C99` |

读图结论：

- **`task6-calendar.png`**：标题 **2026年10月**、**共 4 天有梦**；红点 **4 个，正好在 1 / 8 / 15 / 22** 号；
  网格 **5 行**（10 月 1 日是周四，前 3 格空）；**「今天」Ember 圆圈套在 30 号**（时区修正后才对）；
  底部「说梦」圆钮完整、未压住网格；**底栏「日历」上方那块空灰药丸已经消失**——只剩一行 `Paper` 的「日历」
  和它下方 20×2dp 的 `Ember` 短横线，右侧「全部」是 `InkDim`，栏内没有任何图标、药丸或纯白块。
- **`task6-day.png`**：标题 **10月8日 周四**；**1 张**卡片「教室」，正文首句「教室最后一排，黑板上的字在动，像水。」；
  **没有标签行**；没有说梦钮、**没有底部栏**（底部只有系统手势条，无「日历 / 全部」）；左上角返回箭头可回日历，
  返回后标题仍是 **2026年10月** 且 4 个红点格还在（ViewModel 随 Calendar 的 back stack entry 保留）。
- **`task6-calendar-empty.png`**：标题 **2026年11月**、**共 0 天有梦**；**0 个红点**；
  网格 **6 行**（11 月 1 日是周日，前 6 格空；30 天跨 6 行）——行数随月份变化，说明红点与行数都是数据驱动。

`uiautomator dump` 复核（读屏文案与触控盒，坐标均来自 dump，不是猜的）：

- 日历页：`上个月 => [11,63][137,189]`、`回到今天 => [137,63][943,189]`、`下个月 => [943,63][1069,189]`
- 有梦日恰好 4 个：`2026年10月1日，1 条梦记 => [467,256][615,382]`、`8 日 => [467,382][615,508]`、
  `15 日 => [467,508][615,634]`、`22 日 => [467,634][615,760]`（每格 148×126 px → 56.4×48.0dp）
- 底栏两项：`contentDesc=日历 => [0,2211][540,2337]`、`contentDesc=全部 => [540,2211][1080,2337]`
  → 各 540×126 px = **205.7×48.0dp**，全宽 1080px 严格平分；底栏下沿到屏底还剩 63px（24dp）给手势导航条，
  说明 `navigationBarsPadding()` 生效且没有把内容顶出屏幕
- 当日页：只有 `contentDesc=返回日历 => [11,63][137,189]` 与文案 `10月8日 周四 / 教室 / 教室最后一排，……`，
  **没有「日历」「全部」节点** → 当日列表确认无底栏
- 11 月：`2026年11月`、`共 0 天有梦`、`条梦记` 0 次、日格 30 个（30 + 6 个前导空格 = 36 = 6 行）
- 切栏验证：点「全部」进入 Library 占位空屏，底栏仍在（`日历` / `全部` 两个节点）；
  点「日历」回到 `2026年10月` / `共 4 天有梦` → 自绘底栏的两个可点区域都能真导航

## 近白闸门

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-near-white.ps1 docs\evidence\plan1\task6-*.png -TopOffset 100 -MaxRatio 0
```

```text
RESULT: OK image=...task6-calendar-empty.png dims=1080x2400 samples=69120 nearWhite=0 ratio=0.0000% center=#0B0E13
RESULT: OK image=...task6-calendar.png dims=1080x2400 samples=69120 nearWhite=0 ratio=0.0000% center=#0B0E13
RESULT: OK image=...task6-day.png dims=1080x2400 samples=69120 nearWhite=0 ratio=0.0000% center=#0B0E13
```

三张图 `nearWhite = 0`（`-MaxRatio 0` 仍通过），中心像素 `#0B0E13` = `Night`。

## PNG 完整性

三个文件首 8 字节实测均为 `137,80,78,71,13,10,26,10`（`89 50 4E 47 0D 0A 1A 0A`），
`capture-screen.ps1` 同时校验了 IHDR 与 IEND（`RESULT: OK ... dims=1080x2400`），不存在
PowerShell 重定向造成的 UTF-16 损坏。

## 冷启动 logcat

**注意**：`docs/evidence/plan1/task6-logcat.txt`（1,254 行 / 245,380 字节）是**本轮 UI 修复之前**的构建采的，
本轮只改了底栏绘制，没有重跑 `capture-logcat.ps1`，故该文件内容未变、不要当作新 APK 的日志读。
第一轮结论仍记录在此：`RUN 1/2/3 = 1309 / 1029 / 1252ms`（max 1309ms ≤ 2000）、
`Displayed=3 statuses=ok,ok,ok`、`CRASHES: 0`、grep `FATAL|AndroidRuntime|ANR` 0 命中。

## 复现步骤（坐标来自 `uiautomator dump`，不是猜的）

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb shell "settings put global auto_time_zone 0"
& $adb shell "cmd alarm set-timezone Asia/Shanghai"       # 直到 adb shell date 显示 CST 且日期与主机一致
& $adb uninstall com.dreamkeeper
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.dreamkeeper/.MainActivity
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\task6-calendar.png -Serial emulator-5554
& $adb shell input tap 541 445     # 8 号格子（1 条梦记）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\task6-day.png -Serial emulator-5554
& $adb shell input tap 74 126      # 返回，月份仍为 2026年10月
& $adb shell input tap 1006 126    # 下个月 → 2026年11月（红点 0，6 行）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\task6-calendar-empty.png -Serial emulator-5554
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-near-white.ps1 docs\evidence\plan1\task6-*.png -TopOffset 100 -MaxRatio 0
& $adb shell input tap 270 2274    # 底栏「日历」（左半 540px 的中心）；810 是「全部」
```

## 本任务登记的偏差与说明

1. **测试数 71 而非 68**：原因见上（brief 要求 2 条 DAO + 5 条 ViewModel 新用例）。
2. **`asExecutor` 的 import**：brief 的测试骨架只 import `kotlinx.coroutines.test.*`，
   但 `asExecutor()` 在 `kotlinx.coroutines` 包；两个测试文件都补了
   `import kotlinx.coroutines.asExecutor`，测试语义未改。
3. **底部栏偏离 brief 的 `NavigationBar` 写法（本轮已修）**：brief 的代码片段用 M3
   `NavigationBarItem(icon = {})`，实测会画空灰药丸，属视觉缺陷；本轮改为 `Row` 自绘（见上）。
   这是**有意偏离 brief 的实现细节**，路由与显示条件未变。
4. **`adb root` 不可用**：该镜像 `adbd cannot run as root in production builds`，时区靠
   `cmd alarm set-timezone` 修正（`setprop persist.sys.timezone` 会失败），详见上文命令块。
5. **`check-near-white.ps1` 只接受一个路径参数**，三张图要用通配符 `task6-*.png` 一次传，
   分成三个位置参数会报 `A positional parameter cannot be found`。
