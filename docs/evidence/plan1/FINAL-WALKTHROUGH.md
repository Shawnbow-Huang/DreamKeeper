# Plan 1 · Lead 独立走查（final walkthrough）

**目的**：不接受实施者自证。用干净安装 + 手动点击，把「可翻、可搜、可管标签」这条链子在真机（模拟器）上走一遍，逐屏截图存证。

- 时间：2026-10-30 03:08–03:12（设备时间 CST，`adb shell date` 复核）
- 构建：`android/app/build/outputs/apk/debug/app-debug.apk`，12,436,053 B，sha256 前 8 位 **A6219488**
- 安装方式：`adb install -r` → Success；随后 `pm clear com.dreamkeeper` 复位（保证种子按当月重播）→ `logcat -c` → `am start -W`
- 近白闸门：全部 6 屏 `scripts/check-near-white.ps1 -TopOffset 100 -MaxRatio 0` → `nearWhite=0`（每屏 69,120 采样点）
- 崩溃：`logcat -d` 过滤 `FATAL EXCEPTION` 与 `ANR in com.dreamkeeper` → **none**

## 走查路径与结果

| # | 动作 | 结果 | 截图 | sha8 |
|---|---|---|---|---|
| 1 | 冷启动 | `Status: ok`，`TotalTime 985 ms`；月历显示「共 4 天有梦」，红点在 1/8/15/22，今天圈在 30 号；顶部有搜索框、底部两 tab、说梦钮在位 | [final-walk-1-calendar.png](final-walk-1-calendar.png) | `AEC74806` |
| 2 | 点 10 月 8 日格子 | 进入当日页，标题「10月8日 周四」，1 条梦（教室） | [final-walk-2-day.png](final-walk-2-day.png) | `BE526CFA` |
| 3 | 点当日页卡片 | 进入详情页，四个 tab（原样/小说化/标签/素材），默认停在「原样」 | [final-walk-3-detail.png](final-walk-3-detail.png) | `8D5ADF81` |
| 4 | 返回 → 切「全部」 | 时间轴视图，4 条倒序；顶部出现「标签」入口与「时间轴/卡片」分段控件 | [final-walk-4-library.png](final-walk-4-library.png) | `042D74DC` |
| 5 | 点顶栏「标签」 | 标签页：地点 1 / 物品 2 / 情绪 1，按 `TagKind` 常量顺序分组，胶囊 36dp、边框 `InkDim` | [final-walk-5-tags.png](final-walk-5-tags.png) | `D634E8AF` |
| 6 | 冷启动带 `--es dk_prefill_search 电梯` | 直接落到搜索页，输入框预填「电梯」，命中 1 条「10月1日 / 电梯」；即中文检索真的走 `repo.search`，不是写死 | [final-walk-6-search.png](final-walk-6-search.png) | `1C552924` |

## 复现命令（PowerShell）

```powershell
$adb = "C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell pm clear com.dreamkeeper
& $adb logcat -c
& $adb shell am start -W -n com.dreamkeeper/.MainActivity
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\final-walk-1-calendar.png
# 中文检索（`input text` 打不了中文，走 D67 的 debug 钩子）
& $adb shell am force-stop com.dreamkeeper
& $adb shell am start -n com.dreamkeeper/.MainActivity --es dk_prefill_search "电梯"
```

## 与实施者证据的关系

实施者另有 21 张 `task9-*` 图（覆盖改名/合并/删除/12 月切换/重启保持等），本文件**不复用它们的截图**，是独立一次走查。两者交叉一致的部分：标签分组、合并弹层、重启后布局偏好保持。

## 未覆盖（诚实标注）

- 交互手感（动画、拇指热区）：模拟器上无法判定，需真机。
- 锁屏直入与录音链路：Plan 2/4，本轮未实现。
- 1000 条数据下的滚动性能：Plan 2 开工前补测（§14.5）。
