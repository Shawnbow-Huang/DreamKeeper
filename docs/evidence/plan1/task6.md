# Task 6 验收证据 · 日历首屏、当日列表与真路由

- 计划：`docs/superpowers/plans/2026-10-29-foundation-and-library.md` Task 6
- 任务书：`.superpowers/sdd/2026-10-29-foundation-and-library/task-6-brief.md`
- 分支：`plan1-foundation`
- commits：
  - `ea819e1` feat(data)：补 `observeDay` 与按当月播种的种子（D47/D48）
  - `7787b07` test(ui)：日历与当日列表 ViewModel 的 5 条用例
  - `485324b` feat(ui)：实现 `CalendarViewModel` 与 `DayViewModel`
  - `48f89f2` feat(ui)：日历首屏（月份切换、有梦红点、说梦大钮）
  - 本条证据归档提交见 `git log -- docs/evidence/plan1/task6.md` 的下一笔
- 验收时间：2026-10-30（Asia/Shanghai）
- 设备：`emulator-5554`，Android 17 / API 37，1080×2400

## 测试

命令：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd D:\DreamKeeper\android
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`，**71 个用例，0 失败 0 错误**。

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
> 新增是 2 + 4 + 1 = 7 条，64 + 7 = **71**；所有用例均通过。

RED 证据（真实输出摘录）：

- Step 0a：`e: file:///D:/DreamKeeper/android/app/src/test/java/com/dreamkeeper/data/DreamDaoTest.kt:135:39 Unresolved reference 'observeDay' on receiver of type 'DreamDao'.` → `BUILD FAILED`
- Step 2：`e: file:///D:/DreamKeeper/android/app/src/test/java/com/dreamkeeper/ui/calendar/CalendarViewModelTest.kt:59:18 Unresolved reference 'CalendarViewModel'.` → `BUILD FAILED`
- 首次跑 Step 2 还暴露了 brief 测试骨架缺 `import kotlinx.coroutines.asExecutor`（`Unresolved reference 'asExecutor' on receiver of type 'TestDispatcher'`）；补 import 后才得到上面的「类不存在」红。

## Schema 未变（本任务不得改表结构）

- 文件：`android/app/schemas/com.dreamkeeper.data.db.DreamDatabase/1.json`，8,531 字节
- `version = 1`，`identityHash = 3150a0bccf0e3cb537faa8aac20e4186`，`entities = 4`
- `git diff --stat HEAD -- android/app/schemas` 为空

## 构建与安装

| 项 | 结果 |
|---|---|
| `:app:assembleDebug --no-daemon` | `BUILD SUCCESSFUL in 25s` |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk`，12,181,065 字节，SHA256 `7F458095D7F9CC4F938ED6B8D49D316EEC56E6AC8B0120BAD28C6ECE66C72289` |
| `adb uninstall com.dreamkeeper` | `Success`（截图前复位种子；旧库会让 `seedIfEmpty` 因 `count != 0` 早退） |
| `adb install app-debug.apk` | `Performing Streamed Install` / `Success` |

## 截图与人工读图

三张图均由 `scripts/capture-screen.ps1` 生成（脚本内部走 cmd 二进制安全重定向并校验 PNG），
PNG 魔数实测均为 `137,80,78,71,13,10,26,10`。

| 文件 | 尺寸 / 字节 | SHA256 | 读图结论 |
|---|---|---|---|
| `task6-calendar.png` | 1080×2400 / 83,436 | `0C4E8415BB94C8301B0AA5660A4C76A02D57A31EF17B010AA08F326D4E381AF7` | 标题 **2026年10月**、**共 4 天有梦**；红点 **4 个，正好在 1 / 8 / 15 / 22** 号；网格 **5 行**（10 月 1 日是周四，前 3 格空）；29 号带 Ember 描边（设备时区 GMT，今天=10-29）；底部「说梦」圆钮完整，未压住网格 |
| `task6-calendar-empty.png` | 1080×2400 / 77,023 | `290B4E607059E3E7D1327D02CE34CB0AF1F3AB4D3A684FBB4560D63B3DACEDFB` | 标题 **2026年11月**、**共 0 天有梦**；**0 个红点**；网格 **6 行**（11 月 1 日是周日，前 6 格空；30 天跨 6 行）——红点确为数据驱动，行数随月份变化 |
| `task6-day.png` | 1080×2400 / 49,640 | `E56AC5F9D79F9E47E6EDEE687C19F856A953EC98EAE493EB228162C363F570DA` | 标题 **10月8日 周四**；**1 张**卡片「教室」，正文首句「教室最后一排，黑板上的字在动，像水。」；**没有标签行**；没有说梦钮、没有底部栏；左上角返回箭头可回日历且月份保持在 2026年10月 |

`uiautomator dump` 复核（读屏文案与触控盒）：

- `上个月 => [11,63][137,189]`、`回到今天 => [137,63][943,189]`、`下个月 => [943,63][1069,189]`
- 日格：`2026年10月8日，1 条梦记 => [467,382][615,508]`（148×126 px，density 2.625 → **56.4×48.0dp**，高度正好卡住 48dp 下限）
- 有梦日恰好 4 个：`10月1日 / 8日 / 15日 / 22日，1 条梦记`
- 点返回后 `text="2026年10月"`，4 个红点格仍在

## 近白闸门

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-near-white.ps1 docs\evidence\plan1\task6-*.png -TopOffset 100 -MaxRatio 0
```

```text
RESULT: OK image=...task6-calendar-empty.png dims=1080x2400 samples=69120 nearWhite=0 ratio=0.0000% center=#0B0E13
RESULT: OK image=...task6-calendar.png dims=1080x2400 samples=69120 nearWhite=0 ratio=0.0000% center=#0B0E13
RESULT: OK image=...task6-day.png dims=1080x2400 samples=69120 nearWhite=0 ratio=0.0000% center=#0B0E13
```

## 冷启动 logcat

`scripts/capture-logcat.ps1 -Runs 3 -OutFile docs/evidence/plan1/task6-logcat.txt`
（先 `adb logcat -c`；日志 1,254 行 / 245,380 字节）：

- `RUN 1: status=ok TotalTime=1309ms`、`RUN 2: 1029ms`、`RUN 3: 1252ms` → `max=1309ms ≤ 2000`
- `LAUNCH: Displayed=3 statuses=ok,ok,ok`
- `CRASHES: 0`
- 对日志 grep `FATAL|AndroidRuntime|ANR`：**0 命中**

## 复现步骤（坐标来自 `uiautomator dump`，不是猜的）

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb uninstall com.dreamkeeper
& $adb install android\app\build\outputs\apk\debug\app-debug.apk
& $adb logcat -c
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-logcat.ps1 -Runs 3 -OutFile docs\evidence\plan1\task6-logcat.txt
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\task6-calendar.png -Serial emulator-5554
& $adb shell input tap 1006 126   # 下个月 → 2026年11月（红点 0）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\task6-calendar-empty.png -Serial emulator-5554
& $adb shell input tap 540 126    # 月标题 = 回到今天
& $adb shell input tap 541 445    # 8 号格子（1 条梦记）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\task6-day.png -Serial emulator-5554
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-near-white.ps1 docs\evidence\plan1\task6-*.png -TopOffset 100 -MaxRatio 0
& $adb shell input tap 74 126     # 返回，月份仍为 2026年10月
```

## 本任务登记的偏差与说明

1. **测试数 71 而非 68**：原因见上（brief 要求 2 条 DAO + 5 条 ViewModel 新用例）。
2. **`asExecutor` 的 import**：brief 的测试骨架只 import `kotlinx.coroutines.test.*`，
   但 `asExecutor()` 在 `kotlinx.coroutines` 包；两个测试文件都补了
   `import kotlinx.coroutines.asExecutor`，测试语义未改。
3. **设备时区是 GMT**：系统日期显示 2026-10-29，`YearMonth.now()` 仍是 2026-10，
   所以种子日期与「2026年10月」标题一致；「今天」描边落在 29 号，不是 30 号。
   这是设备时钟，不是界面逻辑。
4. **底部「日历」tab 的选中指示条**：`NavigationBarItem(icon = {})` 按 brief 写空图标，
   M3 仍会画选中指示 pill；截图上表现为标签上方一小条浅色胶囊。按 brief 未改。
5. **`check-near-white.ps1` 只接受一个路径参数**，三张图要用通配符 `task6-*.png` 一次传，
   分成三个位置参数会报 `A positional parameter cannot be found`。
