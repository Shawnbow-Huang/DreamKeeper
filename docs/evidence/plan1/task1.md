# Task 1 验收证据 · 工程能构建能装

- 计划：`docs/superpowers/plans/2026-10-29-foundation-and-library.md` Task 1
- 分支：`plan1-foundation`
- commit：`d32978c` chore(android): Gradle 9.8.2 + AGP 9.5.0 工程骨架，可构建可安装（14 files, +561）
- 验收时间：2026-10-29（Asia/Shanghai）
- 验收人：Lead（亲自复跑，不只采信 implementer 的自述）

## 构建

```powershell
cd D:\DreamKeeper\android
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

- 结果：`BUILD SUCCESSFUL`
- 耗时：首次 3m8s（含镜像下载）／增量 16s
- 产物：`android/app/build/outputs/apk/debug/app-debug.apk`
- 大小：11,764,936 字节
- SHA-256：`D0BB10F09BFB0801BF2D18076998C8D7AF1E13DA3A82CD0658E3E7642329ADE0`
- 唯一警告：`Unable to strip ... libandroidx.graphics.path.so`（本机未装 NDK，仅影响体积）

## 安装与启动

```powershell
$adb = "C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am force-stop com.dreamkeeper
& $adb shell am start -W -n com.dreamkeeper/.MainActivity
```

- `install -r` → `Success`
- `am start -W` → `Status: ok`，`TotalTime: 906`
- `pm list packages com.dreamkeeper` → `package:com.dreamkeeper`
- `dumpsys package` → `versionName=0.1.0`，`firstInstallTime=2026-10-29 15:19:35`，`lastUpdateTime=2026-10-29 15:22:22`

## 截图

- 文件：`docs/evidence/plan1/task1-home.png`
- 尺寸：1080x2400，大小 24,605 字节
- PNG 魔数校验（前 8 字节）：`137,80,78,71,13,10,26,10` ✅
- SHA-256：`C04093006D3678BBDB125BE7BF8FA918357EFC290D73E6E996BB60B1BD46DF38`
- 观感（人眼 + 像素采样）：整屏 `#0B0E13` 深底，居中显示「梦匣」，状态栏与导航条同为深色，无白闪

抓图命令（二进制安全，**不要**用 PowerShell 的 `>` 直接重定向）：

```powershell
cmd /c "`"$adb`" exec-out screencap -p > D:\DreamKeeper\docs\evidence\plan1\task1-home.png"
```

## 崩溃检查

```powershell
& $adb logcat -c
# 启动并停留 4 秒
& $adb logcat -d | Select-String "FATAL|AndroidRuntime|ANR"
```

- 结果：无命中

## 配置核对

- `applicationId` / `namespace` = `com.dreamkeeper`
- `compileSdk = 37`、`targetSdk = 37`、`minSdk = 26`
- `rootProject.name = "DreamKeeper"`，显示名「梦匣」
- 单 module `:app`，无 Hilt / Koin / 多余导航框架
- 未在 `android/` 内放提示词文件

## 偏差（已登记进计划 D14–D18）

| # | 偏差 | 处理 |
|---|---|---|
| D14 | 计划写的 KSP `2.4.21-2.0.4` 不存在 | 改 `ksp = "2.3.12"`，真正受验在 Task 4 |
| D15 | 显式 `kotlin-android` 插件 + `jvmToolchain(17)` 在本机构建失败 | 移除插件（AGP 9 内置 Kotlin），`jvmTarget` 取 `compileOptions.targetCompatibility = 17` |
| D16 | `repo.maven.apache.org` 在本机停滞 | 仓库前置腾讯 `maven-public` 镜像，google/mavenCentral 保留回退 |
| D17 | `MaterialTheme` + `Surface` 渲染成近白 | 占位界面改显式 `#0B0E13` / `#E6EAF2` 常量 |
| D18 | `themes.xml` 的 `statusBarColor`/`navigationBarColor` 是空操作 | 交 Task 2：改 `enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT))` |

## 交接给 Task 2（梦匣色板与主题）

1. `targetSdk 37` 强制 edge-to-edge — 系统栏由 Compose 背景覆盖，主题属性无效。
2. 现占位界面用的是临时常量，Task 2 要换成真正的 `DreamKeeperTheme` 色板对象。
3. 启动闪屏（`res/values-v31/themes.xml` 的 `windowSplashScreenBackground`）与 `android:icon` 尚未做，属 Task 2 范围。
4. 增量构建约 16s，可放心「构建 → 安装 → 截图」循环验收。
