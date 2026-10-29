# Task 2 验收证据 · 梦匣色板与主题

- 计划：`docs/superpowers/plans/2026-10-29-foundation-and-library.md` Task 2
- 分支：`plan1-foundation`
- commit：`fd219ac` feat(ui): 梦匣深色色板与主题（含启动画面与深底图标），禁止纯白（8 files, +142/−8）
- commit：`2a7ad14` docs(evidence): task2 深色主题首帧与冷启动截图归档
- 验收时间：2026-10-29（Asia/Shanghai）
- 验收人：Lead（复跑单测 + 独立像素采样，不采信实现者自述）

## 构建与测试

| 命令 | 结果 |
|---|---|
| `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain` | `BUILD SUCCESSFUL in 8s`（Lead 复跑） |
| `ThemeContrastTest` | 4/4 通过（`tests=4 failures=0 errors=0`） |
| `.\gradlew.bat :app:assembleDebug --no-daemon --console=plain` | `BUILD SUCCESSFUL in 11s` |
| APK | 12,239,234 字节，SHA-256 `1293DD9C4826D8603563B4430439672750BF0B18416DE7DFEF3A313BE16AAEF6` |

`ThemeContrastTest` 的四条断言（阈值一个都没放宽：正文 4.5、描边 3.0）：

| 断言 | 实测 | 阈值 |
|---|---|---|
| `InkFaint #7C8698` on `Night #0B0E13` | 5.27:1 | ≥ 4.5 |
| `ErrorText #E08578` on `Night` | 7.18:1 | ≥ 4.5 |
| `Paper #E6EAF2` on `Night` | 16.03:1 | ≥ 4.5 |
| `Ember #C8873F` on `Night` | 6.44:1 | ≥ 3.0 |

TDD 证据（实现者留档，可复现）：先用旧值 `InkFaint = #5A6474`、`ErrorText = EmberDeep = #8C3B34` 跑，得到 `4 tests completed, 2 failed`，报错文本实测 **3.23:1** 与 **2.57:1** —— 与计划 D10 表格逐位相同，说明 WCAG 实算没抄错；换成最终值后 4/4 绿。这条测试不是「写完代码补个断言」，是真的先红过。

## 截图与像素读数（Lead 独立采样，采样步长 12px、y 从 100 起避开状态栏图标）

| 文件 | 中心像素 | 近白像素（R,G,B 全 ≥245） |
|---|---|---|
| `task2-home.png` | `#0B0E13` | **0 / 17,280（0.000%）** |
| `task2-coldstart-300ms.png` | `#C8873F` | **0 / 17,280（0.000%）** |
| `task2-coldstart-620ms.png` | `#0B0E13` | **0 / 17,280（0.000%）** |

三张 PNG 魔数与 SHA-256：

| 文件 | 字节 | 魔数 | SHA-256 |
|---|---|---|---|
| `task2-home.png` | 27,116 | `137,80,78,71,13,10,26,10` ✅ | `6328B78D7F05FEAC45CD8E8125A08A8073D8069058BCA4FFBD6A4638694A8C38` |
| `task2-coldstart-300ms.png` | 40,563 | ✅ | `2FFBFD4C4398575F6FB8444F5B0517D9405354BC3BB79EEA1D402F4CABA5637E` |
| `task2-coldstart-620ms.png` | 26,708 | ✅ | `DFBF2675BBE70842C71CF3C22868685F90FDC51C67ED5989376385016B0F8E61` |

**300ms 帧中心是 `#C8873F` 这件事就是关键证据**：冷启动那一刻，屏幕上出现的是我们自己的深色启动画面（`windowSplashScreenBackground = #0B0E13` + 琥珀点图标），而不是系统默认的白底。三帧全屏近白像素都是 0 —— 这一条比任何文字承诺都硬。

## 崩溃检查

`logcat -c` → 冷启动 → `logcat -d`：468 行里 `FATAL|AndroidRuntime|ANR` **无命中**。剩余噪声均来自模拟器/系统侧（x86 翻译层 warning、force-stop 时的 `dispatchAppVisibility` exception），与应用无关。

## 自检

`Get-ChildItem -Recurse -File android\app\src\main | Select-String "FFFFFF|Color\.White"` → **无命中**（10 个文件）。`Surface(` 在 `src/main` 中无使用（只有 scheme 槽位赋值 `surface = NightRaised`）。

## edge-to-edge 落地（D18 闭环）

同一张首帧在状态栏带与底部带的采样全部为 `#0B0E13` → 系统栏是由 Compose 背景铺满 + `enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT))` 实现的，确认 `themes.xml` 里的 `statusBarColor`/`navigationBarColor` 在 `targetSdk 37` 下确实是空操作。

## 本任务引入的偏差（已登记 D19–D20）

| # | 偏差 | 处理 |
|---|---|---|
| D19 | 计划里的自检命令 `Select-String -Path <目录>` 不递归、报「访问被拒绝」后返回空 → **假阴性**，看起来像「无命中」其实什么都没扫 | 后续所有同类自检统一改 `Get-ChildItem -Recurse -File <目录> \| Select-String` |
| D20 | M3 `colorScheme.error`（`#E08578`）画在 `errorContainer`（`EmberDeep #8C3B34`）上只有 **2.79:1** | 错误容器内的文字必须用 `onErrorContainer`（已设为 `Paper`，6.24:1）。Task 7 写错误态时照此执行 |

## 交接给 Task 3 及以后

1. 所有界面只准用 `DreamKeeperTheme` 的色板常量，不准再出现裸 `Color(0xFF…)`。
2. `InkDim #8B95A7`（6.40:1）是次要文字与 `outline` 的来源，尚无断言覆盖，真正用到时补一条阈值。
3. 桌面图标目前是临时方案（vector 直连 `android:icon`，没有 adaptive-icon 前景/背景层），系统可能在外面套一层圆形底盘；应用内截图不受影响，图标观感留给 Task 9 冷启动连拍时一起看。
4. 620ms 帧的「半透明梦匣」是系统 splash 退场淡入的正常中间态（3 秒后即为 `#E6EAF2`），别在 Task 9 连拍时误判成「首帧偏暗」。
