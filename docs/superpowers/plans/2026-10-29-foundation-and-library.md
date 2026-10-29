# Plan 1 · 骨架与梦境库（Foundation & Library）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做出一个装得上模拟器、打开就能看见日历、能翻能搜能管标签的「梦匣」，但还不会录音。

**Architecture:** 单 Gradle 工程置于 `android/`，单 module `:app` + 包边界（`data` / `domain` / `ui`）。数据用 Room，**不用 FTS4**——中文搜索走 4 列 `LIKE`（实测结论与理由见 Task 4「为什么不用 FTS4」）。UI 用 Compose + Material3，根界面用 `navigation-compose` 的真路由，深色为默认主题。本计划只做「读」的一半：录入与生成在 Plan 2/3。

**Tech Stack:** Kotlin 2.4.21 · AGP 9.5.0 · Gradle 9.8.2 · Compose BOM 2026.09.00 · Room 2.8.5 · JUnit4 + Robolectric

## Global Constraints

以下为本计划每个任务都隐含的要求，逐字照做：

- **包名/命名空间**：`com.dreamkeeper`；应用显示名 **梦匣**；`rootProject.name = "DreamKeeper"`。
- **SDK 版本**：`compileSdk = 37`、`targetSdk = 37`、`minSdk = 26`。本机已装 `platforms/android-37.0`（ApiLevel 37.0）与 `build-tools/36.0.0`。
- **仓库根是 `D:\DreamKeeper`，Gradle 工程根是 `D:\DreamKeeper\android`。** 任何构建命令都必须先 `cd android`。
- **提示词不放进 `android/`**：`prompts/` 在仓库根，通过 `sourceSets` 引入（本计划暂不涉及，Plan 3 处理）。
- **夜间体验是硬约束**：全流程深色、无白闪、不用纯白（`#FFFFFF` 禁止出现在主题与布局里）。
- **不使用多 module**；不引入 Hilt/Koin（用简单的手写容器）；不引入 Navigation-Compose 之外的导航框架。
- **导航必须是真路由**：根界面用 `androidx.navigation:navigation-compose` 的 `NavHost`，**不要再手搓 `when (tab)`**。底部栏只在 `Calendar` / `Library` 两个路由显示；`Record` / `FollowUp` / `Detail` / `Search` / `Tags` 路由现在就建好（Plan 1 允许先不导航过去，但路由定义必须在）。
- **测试策略（有意的取舍）**：业务逻辑与 DAO 用 JVM 单测（JUnit4 + Robolectric）；Compose 界面靠「构建 → 安装 → 截图」验收，不写 UI 单元测试。理由：Robolectric 下跑 Compose 断言收益低于维护成本。**视觉回归靠人工读截图**：本工程没有 Roborazzi、没有 Turbine、没有 golden 目录，也没有任何自动图像比对。每张截图都必须逐张打开看（agent 用 read_image），并把「看到了什么」写进证据文件（见 §2）。
- **机队纪律 · adb**：`adb` **不在 PATH**，绝对路径是 `C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe`。PowerShell 的 `>` 会把二进制重定向污染成 UTF-16LE——实测裸 `adb exec-out screencap -p > x.png` 产出 2,651,774 字节、头部 `FF FE FD FF 50 00 4E 00 ...`，PIL 报 `UnidentifiedImageError`。所以**禁止**裸 `adb ... > file`，截图必须走 `cmd /c` 的二进制安全重定向（每个 Task 的截图步骤都已写成这个形式）：

  ```powershell
  $adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
  cmd /c "`"$adb`" exec-out screencap -p > D:\DreamKeeper\docs\evidence\plan1\<name>.png"
  ```

- **机队纪律 · 截图必须过 PNG 魔数校验**：落盘后先断言前 8 字节是 `137,80,78,71,13,10,26,10`，再谈「图里有什么」。魔数不对就是截图失败（典型原因：又用了裸 `>` 重定向），不要当成「图片打不开」绕过去。

  ```powershell
  $b=[System.IO.File]::ReadAllBytes("D:\DreamKeeper\docs\evidence\plan1\<name>.png")[0..7]
  if (($b -join ',') -ne '137,80,78,71,13,10,26,10') { throw "PNG 魔数校验失败: $($b -join ',')" }
  ```

- **机队纪律 · 数据库演进**：① 新增字段**只许在 `DreamEntity` 末尾追加且带默认值**（`Dream(...)` 有 3 处位置参数调用，插在中间会全部错位）；② `schema version + 1` 的那次提交必须同时包含 **migration 代码** 与 **「覆盖安装旧 APK 后原记录仍在」的证据**（`adb install -r` 不清数据，再截图 + 查库）；③ **禁止** `fallbackToDestructiveMigration()`——用户的梦不能因为升级而消失。
- **每个任务结束必须提交（commit），且提交信息用 `feat:` / `test:` / `chore:` 前缀。**

---

## 0. 环境事实（已实测，2026-10-29）

| 项 | 值 |
|---|---|
| JDK | `C:\Program Files\Android\Android Studio\jbr`（OpenJDK **25.0.4**） |
| SDK | `C:\Users\17383\AppData\Local\Android\Sdk`（`platforms/android-37.0`、`build-tools/36.0.0`、`platform-tools`、`emulator`） |
| 缺 | 没有 `cmdline-tools`（sdkmanager 不可用）；**Gradle 未安装** |
| 模拟器 | AVD `Medium_Phone_API_37.0`（1080x2400） |
| 网络 | `dl.google.com`、`repo1.maven.org`、`mirrors.cloud.tencent.com` **可达**；`downloads.gradle.org` / `services.gradle.org` **超时**（必须走腾讯镜像） |
| 版本实查 | AGP 稳定版最新 **9.5.0**；Kotlin **2.4.21**；Compose BOM **2026.09.00**；Room **2.8.5** |

**已知风险**：
- **没有 JDK 17**：本机 JDK 就是 Android Studio 自带的 OpenJDK 25.0.4，既没有第二个 JDK，也没有 toolchain resolver。所以**禁止 `jvmToolchain(17)`**（必然 `No matching toolchains found for requested specification: {languageVersion=17}`）；正确写法是 `compilerOptions.jvmTarget = JvmTarget.JVM_17` + `compileOptions` 双写，用 JDK 25 编译到 17 字节码（Task 1 Step 3 已按此改）。
- `platforms` 目录名是 `android-37.0`（新版次要 API 命名），若 AGP 找不到 SDK 平台，先跑一次 `./gradlew :app:compileDebugKotlin`，按报错里的精确提示调整 `compileSdk`（报错会写出它需要的平台目录名）。
- 只有 `build-tools/36.0.0`；AGP 9.5 可能要求 37.x，会在首次构建时自动下载（`licenses/android-sdk-license` 已存在，`dl.google.com` 可达）。

---

## 1. 文件结构（先定边界，再拆任务）

```
D:\DreamKeeper\
├── android\
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   ├── gradle\libs.versions.toml
│   ├── gradle\wrapper\gradle-wrapper.properties
│   ├── local.properties            # 不进版本库
│   └── app\
│       ├── build.gradle.kts
│       ├── schemas\com.dreamkeeper.data.db.DreamDatabase\1.json   # Room 导出，必须入库
│       └── src\main\
│           ├── AndroidManifest.xml
│           ├── java\com\dreamkeeper\
│           │   ├── DreamKeeperApp.kt
│           │   ├── MainActivity.kt
│           │   ├── di\AppContainer.kt
│           │   ├── data\
│           │   │   ├── db\DreamDatabase.kt
│           │   │   ├── db\DreamEntity.kt
│           │   │   ├── db\TagEntity.kt
│           │   │   ├── db\DreamTagEntity.kt
│           │   │   ├── db\MediaAssetEntity.kt
│           │   │   ├── db\DreamDao.kt
│           │   │   ├── db\TagDao.kt
│           │   │   ├── db\DreamTagDao.kt
│           │   │   ├── db\MediaAssetDao.kt
│           │   │   ├── db\SeedData.kt
│           │   │   └── DreamRepository.kt
│           │   ├── domain\
│           │   │   ├── Dream.kt
│           │   │   ├── Tag.kt
│           │   │   ├── MediaAsset.kt
│           │   │   └── DreamCalendar.kt
│           │   └── ui\
│           │       ├── theme\Color.kt / Theme.kt / Type.kt / Night.kt
│           │       ├── nav\DreamRoute.kt
│           │       ├── DreamKeeperRoot.kt
│           │       ├── calendar\CalendarScreen.kt / CalendarViewModel.kt
│           │       ├── library\LibraryScreen.kt / LibraryViewModel.kt / LayoutPrefs.kt
│           │       ├── detail\DetailScreen.kt / DetailViewModel.kt
│           │       ├── search\SearchScreen.kt / SearchViewModel.kt
│           │       ├── record\RecordUiState.kt
│           │       └── tags\TagsScreen.kt / TagsViewModel.kt
│           └── res\values\strings.xml / themes.xml
│       └── src\test\java\com\dreamkeeper\
│           ├── domain\DreamCalendarTest.kt
│           ├── data\DreamDaoTest.kt
│           ├── data\DreamRepositoryTest.kt
│           ├── ui\calendar\CalendarViewModelTest.kt
│           ├── ui\library\LibraryViewModelTest.kt
│           ├── ui\detail\DetailViewModelTest.kt
│           └── ui\theme\ThemeContrastTest.kt
└── docs\
    ├── superpowers\plans\             # 本文件所在
    ├── superpowers\specs\             # 设计文档（最高依据）
    └── evidence\plan1\                # 每任务的证据：截图 + logcat + taskN.md（进版本库）
```

**职责一句话**：
- `data/db`：只做持久化，不含业务判断。
- `data/DreamRepository.kt`：领域 ⇄ 数据库的唯一翻译层，Upper 层只见 `Dream`/`Tag`/`MediaAsset`。
- `domain`：纯 Kotlin，无 Android 依赖，**可 100% 单测**。
- `ui/*/XxxViewModel.kt`：状态与动作，无 Compose 依赖。
- `ui/*/XxxScreen.kt`：只负责画，输入状态、输出事件。
- `docs/evidence/plan1/`：**证据目录，进版本库**。`.scratch/` 已被 `.gitignore` 忽略，截图放那里等于没留证据。

---

## 2. 证据目录约定（每个 Task 结束都要做）

`docs/evidence/plan1/` 是交付物的一部分，不是临时垃圾。每个 Task 的最后一个步骤都把本轮证据写进去并提交。

**每个 Task 产出 4 类东西**：

| 文件 | 内容 |
|---|---|
| `taskN.md` | 本轮证据说明（模板见下） |
| `taskN-*.png` | 截图，路径与 SHA256 在 `taskN.md` 里登记 |
| `taskN-logcat.txt` | 场景跑完后的 `adb logcat -d` 全量落盘 |
| （APK） | `app-debug.apk` 不进版本库，但**路径与 SHA256 必须写进 `taskN.md`** |

`docs/evidence/plan1/taskN.md` 模板（照抄填空，缺一项就等于这轮没验）：

```markdown
# Task N 证据

- 代码提交：<git rev-parse --short HEAD 的输出>
- 构建命令：cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon
- 构建结果：BUILD SUCCESSFUL <耗时>
- APK：D:\DreamKeeper\android\app\build\outputs\apk\debug\app-debug.apk
- APK SHA256：<Get-FileHash 输出>
- 截图：
  - docs/evidence/plan1/plan1-taskN-a.png  SHA256: <...>  人工读图结论：<一句话，写清看到了什么>
  - docs/evidence/plan1/plan1-taskN-b.png  SHA256: <...>  人工读图结论：<...>
- logcat：docs/evidence/plan1/taskN-logcat.txt（`adb logcat -c` 之后跑的，只含本场景）
- 复现步骤：
  1. <命令/操作，逐条，能照着复现>
- 已知异常：<没有就写「无」；有就写清现象与是否阻塞>
```

**跑场景前清空日志、跑完落盘**（不清日志，`logcat` 会混进上一轮的噪声，证据失效）：

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c
# ... 这里跑本任务的场景 ...
& $adb logcat -d | Out-File -Encoding utf8 D:\DreamKeeper\docs\evidence\plan1\taskN-logcat.txt
```

> 注意 logcat 是文本，用 `| Out-File -Encoding utf8` 落盘（不要用 `>`，`adb logcat -d > x.txt` 同样会变成 UTF-16LE，diff 起来一片乱码）。

**截图 / 冷启动 / 近白检查一律走仓库里的三个脚本**（Task 6/7/8/9 的证据步骤都按这个写，不要手抄 adb 命令）：

```powershell
cd D:\DreamKeeper
# ① 截图 + PNG 魔数/IHDR/IEND 三重校验，顺手打印 SHA256
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-taskN-a.png

# ② 冷启动 ×3 + 崩溃与耗时闸门（判据：TotalTime ≤ 2000ms、Displayed=3、CRASHES: 0）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-logcat.ps1 -Runs 3 -OutFile docs\evidence\plan1\taskN-logcat.txt

# ③ 近白像素闸门（判据：nearWhite 必须 = 0，故显式 -MaxRatio 0，不用脚本默认的 1%）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-near-white.ps1 docs\evidence\plan1\plan1-taskN-*.png -TopOffset 100 -MaxRatio 0
```

三个脚本的判据口径（跑完照抄这三个数字进 `taskN.md`）：
- `capture-screen.ps1` → `RESULT: OK ... dims=1080x2400 sha256=...`；`RESULT: FAIL` 即截图失败，**不要当成「图片打不开」绕过去**。
- `capture-logcat.ps1` → `STARTUP: runs=… max=…ms` 里 max ≤ 2000、`LAUNCH: Displayed=3`、`CRASHES: 0`、末行 `RESULT: OK`。
- `check-near-white.ps1` → 每张图 `nearWhite=0`（`-MaxRatio 0` 下任何一颗近白像素都会红）。

> **禁止裸 `adb exec-out screencap -p > 文件`**（这是那条禁令的唯一执行方式）：PowerShell 5.1 按 UTF-16LE 写重定向，PNG 直接变成 2.6MB 的坏文件（头部 `FF FE FD FF …`），PIL 报 `UnidentifiedImageError`。要手工兜底也只许用 Global Constraints 里那个 `cmd /c` 写法。三个脚本都在 `scripts/` 下、已自测（`-SelfTest` 有红/绿双向夹具），**不要改脚本去迁就命令**。

**算哈希**：

```powershell
Get-FileHash D:\DreamKeeper\android\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
Get-FileHash D:\DreamKeeper\docs\evidence\plan1\plan1-taskN-a.png -Algorithm SHA256
```

**两次提交**（顺序不能反，否则 `taskN.md` 里写不出本轮代码的 SHA）：

1. 提交本任务的代码/测试；
2. 把 `docs/evidence/plan1/` 下的新文件单独提交：`git add docs/evidence/plan1; git commit -m "docs(evidence): taskN 证据归档"`。

---

## Task 1: 工程能构建、能装上模拟器

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`, `android/local.properties`, `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/com/dreamkeeper/MainActivity.kt`, `android/app/src/main/java/com/dreamkeeper/DreamKeeperApp.kt`, `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values/themes.xml`
- Modify: `.gitignore`（增加 `.tools/`、`android/local.properties`）

**Interfaces:**
- Produces: 可执行的 `D:\DreamKeeper\android\gradlew.bat`；`applicationId = "com.dreamkeeper"`；后续所有任务都在这个工程里加文件。

- [ ] **Step 1: 下载 Gradle 到本地工具目录**

```powershell
$tools = "D:\DreamKeeper\.tools"
New-Item -ItemType Directory -Force $tools | Out-Null
if (-not (Test-Path "$tools\gradle-9.8.2\bin\gradle.bat")) {
  Invoke-WebRequest "https://mirrors.cloud.tencent.com/gradle/gradle-9.8.2-bin.zip" -OutFile "$tools\gradle-9.8.2-bin.zip" -TimeoutSec 600
  Expand-Archive "$tools\gradle-9.8.2-bin.zip" -DestinationPath $tools -Force
}
Test-Path "$tools\gradle-9.8.2\bin\gradle.bat"
```

Expected: `True`

- [ ] **Step 2: 写入版本目录与工程配置**

`android/gradle/libs.versions.toml`：

```toml
[versions]
agp = "9.5.0"
kotlin = "2.4.21"
composeBom = "2026.09.00"
room = "2.8.5"
coreKtx = "1.17.0"
lifecycle = "2.9.4"
activityCompose = "1.11.0"
coroutines = "1.10.2"
junit = "4.13.2"
robolectric = "4.16"
androidxTestCore = "1.7.0"
# 已实测（D34）：2.9.8 由腾讯镜像解析、编译通过，Lead 已手工补齐。
# **这个条目就是导航依赖的唯一权威口径**——计划正文里不再有「加依赖」的实现步骤。
navigationCompose = "2.9.8"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { module = "junit:junit", version.ref = "junit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTestCore" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version = "2.4.21-2.0.4" }
```

> KSP 版本必须与 Kotlin 主版本严格对应。若 `2.4.21-2.0.4` 解析失败，查
> `https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml`
> 取以 `2.4.21-` 开头的最新一条，填进 `[plugins] ksp`。

`android/settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "DreamKeeper"
include(":app")
```

`android/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

`android/gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
```

`android/local.properties`（不进版本库）：

```properties
sdk.dir=C\:\\Users\\17383\\AppData\\Local\\Android\\Sdk
```

- [ ] **Step 3: 写 app 模块与最小界面**

`android/app/build.gradle.kts`：

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dreamkeeper"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dreamkeeper"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    // 编译目标 17，但**不用 jvmToolchain(17)**：本机只有 Android Studio 自带的 JDK 25.0.4，
    // 没有 JDK 17、也没有 toolchain resolver。jvmToolchain(17) 会直接报
    // `No matching toolchains found for requested specification: {languageVersion=17}`。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

// 与 android { } 同级：用现有 JDK 25 编译，产出 17 字节码（与 compileOptions 成对出现，缺一个都不行）
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)   // 真路由（Task 6 的 NavHost）
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

> **版本口径**：这里只有「Kotlin 编译器目标 = 17」，没有「本机必须装 JDK 17」。以后若看到 `jvmToolchain` 被加回来，直接删掉——它不是更严格，它只是在本机会失败。
>
> **若 KSP/Room 编译报版本不匹配**（典型报错 `ksp-2.x.x is too old for kotlin-2.4.21` / `Unable to find method ... KspExtension`）：**先按 Kotlin 版本对齐 KSP**，即把 `[plugins] ksp` 改成 `<kotlin 版本>-<ksp 版本>`（`2.4.21-<ksp>`），去 `maven-metadata.xml` 取对应的那一条。**不要误改 Room 版本**——Room 2.8.5 与 Kotlin 版本无关，改它只会把问题挪到别处。

`android/app/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name=".DreamKeeperApp"
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.DreamKeeper">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.DreamKeeper">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android/app/src/main/res/values/strings.xml`：

```xml
<resources>
    <string name="app_name">梦匣</string>
</resources>
```

`android/app/src/main/res/values/themes.xml`：

```xml
<resources>
    <style name="Theme.DreamKeeper" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">#0B0E13</item>
        <item name="android:statusBarColor">#0B0E13</item>
        <item name="android:navigationBarColor">#0B0E13</item>
    </style>
</resources>
```

`android/app/src/main/java/com/dreamkeeper/DreamKeeperApp.kt`：

```kotlin
package com.dreamkeeper

import android.app.Application

class DreamKeeperApp : Application()
```

`android/app/src/main/java/com/dreamkeeper/MainActivity.kt`：

```kotlin
package com.dreamkeeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Placeholder() }
    }
}

/**
 * Task 1 的占位界面：深色写死，**不要套 MaterialTheme**。
 * 默认 MaterialTheme 是浅色主题，surface 近白（#FFFBFE 一档），
 * 与本步骤 Expected 的「背景近黑 #0B0E13」自相矛盾——截图会直接打脸。
 * Task 2 建好 DreamKeeperTheme 后再交棒。
 */
@Composable
private fun Placeholder() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0B0E13)),
        contentAlignment = Alignment.Center,
    ) {
        Text("梦匣", color = Color(0xFFE6EAF2))
    }
}
```

- [ ] **Step 4: 生成 wrapper（指向腾讯镜像）**

```powershell
cd D:\DreamKeeper\android
& "D:\DreamKeeper\.tools\gradle-9.8.2\bin\gradle.bat" wrapper --gradle-version 9.8.2 --distribution-type bin
(Get-Content gradle\wrapper\gradle-wrapper.properties) -replace 'https\\://services\.gradle\.org/distributions/','https\\://mirrors.cloud.tencent.com/gradle/' | Set-Content gradle\wrapper\gradle-wrapper.properties
Get-Content gradle\wrapper\gradle-wrapper.properties
```

Expected: 文件里 `distributionUrl` 以 `https\://mirrors.cloud.tencent.com/gradle/gradle-9.8.2-bin.zip` 结尾。

- [ ] **Step 5: 构建**

```powershell
cd D:\DreamKeeper\android
.\gradlew.bat :app:assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`，产物 `app\build\outputs\apk\debug\app-debug.apk`。
若报 SDK 平台/Build-Tools 缺失：读报错里的精确目录名，先试 `compileSdk = 37`+`buildToolsVersion = "36.0.0"`，仍失败则把报错原文贴进计划文档的「已知风险」并停下询问。
若报 `No matching toolchains found`：说明 `jvmToolchain(...)` 又被加回来了。删掉它，回到 Step 3 的 `compilerOptions.jvmTarget = JvmTarget.JVM_17` 写法——本机没有 JDK 17，这不是配置问题，是环境事实。

- [ ] **Step 6: 安装到模拟器并截图**

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ev="D:\DreamKeeper\docs\evidence\plan1"
New-Item -ItemType Directory -Force $ev | Out-Null

cd D:\DreamKeeper
& $adb devices
& $adb logcat -c
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.dreamkeeper/.MainActivity
Start-Sleep -Seconds 3

# 截图一律走脚本（内部是 cmd.exe 二进制安全重定向 + 魔数/IHDR/IEND 校验 + 算 SHA256）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-task1.png
```

Expected: `capture-screen.ps1` 打印 `RESULT: OK path=… dims=1080x2400 sha256=…`（退出码 0）；截图里出现居中的「梦匣」两个字，文字是浅灰 `#E6EAF2`（**不是纯白**），背景是近黑（`#0B0E13`），状态栏也是近黑。**用 read_image 打开截图确认**，不要凭安装成功就宣称成功。

- [ ] **Step 7: 提交**

```powershell
cd D:\DreamKeeper
git add -A
git commit -m "chore(android): Gradle 9.8.2 + AGP 9.5.0 工程骨架，可构建可安装"
git rev-parse --short HEAD
```

- [ ] **Step 8: 归档证据**

按 §2 写 `docs/evidence/plan1/task1.md`：填上刚打印的 short SHA、构建命令与结果、APK 路径 + SHA256、`plan1-task1.png` 路径 + SHA256 + 人工读图结论、复现步骤、`task1-logcat.txt`（回到 Step 6 的 `adb logcat -c` 之前没法补，本轮如果忘了就写「未采集」并说明）。然后：

```powershell
cd D:\DreamKeeper
git add docs/evidence/plan1
git commit -m "docs(evidence): task1 证据归档"
```

---

## Task 2: 梦匣的色板与主题（深色为默认，且没有纯白）

**Files:**
- Create: `android/app/src/main/java/com/dreamkeeper/ui/theme/Color.kt`, `.../Theme.kt`, `.../Type.kt`
- Create: `android/app/src/main/res/values-v31/themes.xml`（API 31+ 的启动画面）
- Create: `android/app/src/main/res/drawable/ic_launcher_dark.xml`（深底临时图标）
- Modify: `android/app/src/main/java/com/dreamkeeper/MainActivity.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`（补 `android:icon`）
- Test: `android/app/src/test/java/com/dreamkeeper/ui/theme/ThemeContrastTest.kt`（纯 JVM，不写 UI 测试）

**Interfaces:**
- Produces: `@Composable fun DreamKeeperTheme(content: @Composable () -> Unit)`；颜色常量 `Night`、`NightRaised`、`Paper`、`Ink`、`InkDim`、`Ember`、`Line`、`EmberDeep`、`ErrorText`（后续所有界面只用这套）。
- Produces: 一个「从图标到第一帧都不白」的启动路径：`values/themes.xml` + `values-v31/themes.xml` + 深底图标。
- Produces: `ThemeContrastTest` —— 把「这个灰太暗了」从审美争论变成会红的断言（WCAG 实算，见 Step 2）。

- [ ] **Step 1: 写色板**

`ui/theme/Color.kt`：

```kotlin
package com.dreamkeeper.ui.theme

import androidx.compose.ui.graphics.Color

/** 夜色背景：冷灰（偏蓝调的墨黑），不是暖黑。这个值不动——整屏观感由它定调，改了所有对比度都得重算 */
val Night = Color(0xFF0B0E13)
/** 抬升层（卡片、弹窗） */
val NightRaised = Color(0xFF12161F)
/** 正文：冷灰的浅色（偏蓝调），不用纯白 */
val Paper = Color(0xFFE6EAF2)
/** 次要文字 */
val InkDim = Color(0xFF8B95A7)
/** 更弱的辅助信息。12sp 也属于正文，必须 ≥ 4.5:1 —— 旧值 #5A6474 只有 3.23:1 */
val InkFaint = Color(0xFF7C8698)
/** 分隔线：**只做装饰**。旧值拿它画"今天"的描边（1.34:1）等于看不见，今天改用 Ember */
val Line = Color(0xFF232A36)
/** 唯一强调色：琥珀，夜间不刺眼，也不与"红钮"抢注意力（Night 上 6.4:1，够做 1.5dp 描边） */
val Ember = Color(0xFFC8873F)
/** 录音状态专用**填充色**：暗红。不做文字色（Night 上只有 2.57:1），只用于说梦钮与错误容器的底色 */
val EmberDeep = Color(0xFF8C3B34)
/** 错误文字：EmberDeep 当 `error` 用时对比度不达标，M3 的 `error` 必须用这个（Night 上 7.18:1） */
val ErrorText = Color(0xFFE08578)
```

> **为什么动这三个值**（WCAG 2.x 实算，Night `#0B0E13` 为底）：
> | 颜色 | 旧值 | 旧对比度 | 新值 | 新对比度 | 用途 |
> |---|---|---|---|---|---|
> | `InkFaint` | `#5A6474` | **3.23:1**（12sp 正文不合格） | `#7C8698` | **5.27:1** | 辅助文字 |
> | 今天描边 | `Line #232A36` | **1.34:1**（等于没画） | `Ember #C8873F` | 6.4:1 | 1.5dp 圈住今天 |
> | `error` | `EmberDeep #8C3B34` | **2.57:1** | `ErrorText #E08578` | **7.18:1** | M3 错误文字 |
>
> **色温不动**：`Night` / `Paper` 一个字节都不改。要改的是注释——它们一直把冷灰写成"暖"，这是文档错误，不是配色方案。
> `Line` 从此只出现在装饰性分隔线（列表竖线、卡片分隔），**不许再用于任何"必须看见"的边界**。`EmberDeep` 只作填充：说梦钮的底色、错误容器底色。

- [ ] **Step 2: 写主题 + 对比度单测（WCAG 实算，纯 JVM）**

`ui/theme/Theme.kt`：

```kotlin
package com.dreamkeeper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DreamColorScheme = darkColorScheme(
    primary = Ember,
    onPrimary = Night,
    background = Night,
    onBackground = Paper,
    surface = NightRaised,
    onSurface = Paper,
    onSurfaceVariant = InkDim,
    // outline 是"必须看得见的边界"（输入框、可点容器），不能给 Line（1.34:1）；
    // 装饰性分隔线走 outlineVariant —— 这是 Line 在 M3 里的唯一去处
    outline = InkDim,
    outlineVariant = Line,
    error = ErrorText,
    // EmberDeep 只做填充：错误容器的底色，不是错误文字的颜色
    errorContainer = EmberDeep,
    onErrorContainer = Paper,
)

@Composable
fun DreamKeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DreamColorScheme, typography = DreamTypography, content = content)
}
```

`android/app/src/test/java/com/dreamkeeper/ui/theme/ThemeContrastTest.kt`（**纯 JVM 单测**：不渲染、不截图、无需 Robolectric）：

```kotlin
package com.dreamkeeper.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.0 对比度实算。Compose 的 `Color.luminance()` 就是 WCAG 相对亮度公式的实现，
 * 所以这里不再手写 0.2126/0.7152/0.0722——公式只该有一份，且不该抄错。
 *
 * 目的：把「这个灰太暗了」变成一条会红的断言。失败信息里带实测值，直接照着调色。
 */
class ThemeContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun assertContrast(name: String, fg: Color, bg: Color, min: Double) {
        val actual = contrast(fg, bg)
        assertTrue(
            "$name 实测 %.2f:1，要求 ≥ %.1f:1".format(actual, min),
            actual >= min,
        )
    }

    @Test fun `ink faint is readable on night`() =
        assertContrast("InkFaint on Night", InkFaint, Night, 4.5)

    @Test fun `error text is readable on night`() =
        assertContrast("ErrorText on Night", ErrorText, Night, 4.5)

    @Test fun `paper is readable on night`() =
        assertContrast("Paper on Night", Paper, Night, 4.5)

    @Test fun `ember ring is visible on night`() =
        assertContrast("Ember on Night", Ember, Night, 3.0)
}
```

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*ThemeContrastTest*" --no-daemon
```

Expected: 4 个测试通过。**这条测试先于界面存在**：色板改错时它在几秒内告诉你，而不是等截图出来靠肉眼猜。若它红了，改色值，不要放宽阈值（4.5 是 WCAG AA 的正文线）。

- [ ] **Step 3: 写字体尺度**

`ui/theme/Type.kt`（正文允许行高偏大——梦的文本需要呼吸感）：

```kotlin
package com.dreamkeeper.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val DreamTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 26.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
)
```

- [ ] **Step 4: 启动画面也不许白（values-v31 + 图标）**

Compose 主题只管 Activity 里的像素。**从点击图标到第一帧 Compose 出来之间**还有两段系统画的像素：窗口背景（`windowBackground`）与 API 31+ 的系统启动画面（SplashScreen）。两段默认都是浅色——不显式声明，冷启动一定白闪一下。

`android/app/src/main/res/values-v31/themes.xml`（同名 style 覆盖，只在 API 31+ 生效）：

```xml
<resources>
    <style name="Theme.DreamKeeper" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">#0B0E13</item>
        <item name="android:statusBarColor">#0B0E13</item>
        <item name="android:navigationBarColor">#0B0E13</item>
        <item name="android:windowSplashScreenBackground">#0B0E13</item>
        <item name="android:windowSplashScreenAnimatedIcon">@drawable/ic_launcher_dark</item>
    </style>
</resources>
```

`android/app/src/main/res/drawable/ic_launcher_dark.xml`（临时图标：深底 + 一枚琥珀点，取代系统默认白底图标）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#0B0E13" android:pathData="M0,0h108v108h-108z" />
    <path android:fillColor="#C8873F" android:pathData="M54,54m-14,0a14,14 0,1 1,28 0a14,14 0,1 1,-28 0" />
</vector>
```

`android/app/src/main/AndroidManifest.xml` 的 `<application>` 上补一行（原计划里没有 `android:icon`，系统会套默认白底图标，启动画面就白了一半）：

```xml
        android:icon="@drawable/ic_launcher_dark"
```

> `values-v31/` 的覆盖是资源系统层面的，不需要写代码分支。Task 9 会用「冷启动连拍 3 帧」验证这一条真的成立。

- [ ] **Step 5: 接进 Activity**

`MainActivity.kt` 内把 `Placeholder()` 改成 `DreamKeeperTheme { Box(...) { Text("梦匣") } }`（`Surface` 可去掉，主题已给背景）。

- [ ] **Step 6: 构建 + 安装 + 截图**

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon
cd D:\DreamKeeper
& $adb logcat -c
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am force-stop com.dreamkeeper
& $adb shell am start -n com.dreamkeeper/.MainActivity
Start-Sleep 3
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-task2.png
```

Expected: `RESULT: OK`；文字为浅灰 `#E6EAF2`（不是纯白），背景近黑。**read_image 确认**。

- [ ] **Step 7: 提交**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(ui): 梦匣深色色板与主题（含启动画面与深底图标），禁止纯白"
git rev-parse --short HEAD
```

- [ ] **Step 8: 归档证据**

按 §2 写 `docs/evidence/plan1/task2.md`（short SHA、构建命令、APK 与截图 SHA256、读图结论、复现步骤、`task2-logcat.txt`），并把 `ThemeContrastTest` 的 4 行通过输出贴进「构建结果」（它是三个符号对比度的唯一机器证据），再 `git add docs/evidence/plan1; git commit -m "docs(evidence): task2 证据归档"`。

---

## Task 3: 纯日期逻辑 —— 日历怎么排

先写不依赖 Android 的那一层，因为它是唯一能被快速、彻底测透的部分。

**Files:**
- Create: `android/app/src/main/java/com/dreamkeeper/domain/DreamCalendar.kt`
- Test: `android/app/src/test/java/com/dreamkeeper/domain/DreamCalendarTest.kt`

**Interfaces:**
- Produces: `object DreamCalendar { fun monthGrid(month: YearMonth, weekStart: DayOfWeek = DayOfWeek.MONDAY): List<LocalDate?> }`
  - 返回长度恒为 `month.lengthOfMonth() + leadingBlanks + trailingBlanks`，且为 7 的倍数；`null` 表示空白格。

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.dreamkeeper.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class DreamCalendarTest {

    @Test
    fun `month with 31 days starting on monday has no leading blanks`() {
        // 2026-06 从周一(2026-06-01)开始
        val grid = DreamCalendar.monthGrid(YearMonth.of(2026, 6))
        assertEquals(LocalDate.of(2026, 6, 1), grid.first())
        assertEquals(0, grid.size % 7)
    }

    @Test
    fun `leading blanks align first day under its weekday column`() {
        // 2026-10-01 是周四 -> 周一起算时前面有 3 个空
        val grid = DreamCalendar.monthGrid(YearMonth.of(2026, 10))
        assertNull(grid[0]); assertNull(grid[1]); assertNull(grid[2])
        assertEquals(LocalDate.of(2026, 10, 1), grid[3])
    }

    @Test
    fun `every real date appears exactly once in order`() {
        val month = YearMonth.of(2026, 2)
        val dates = DreamCalendar.monthGrid(month).filterNotNull()
        assertEquals(month.lengthOfMonth(), dates.size)
        assertEquals(dates, dates.sorted())
        assertEquals(LocalDate.of(2026, 2, 1), dates.first())
        assertEquals(LocalDate.of(2026, 2, 28), dates.last())
    }

    @Test
    fun `week start is configurable`() {
        val grid = DreamCalendar.monthGrid(YearMonth.of(2026, 10), DayOfWeek.SUNDAY)
        // 2026-10-01 周四，周日起算 -> 前面空 4 格
        assertEquals(LocalDate.of(2026, 10, 1), grid[4])
    }

    @Test
    fun `grid is always a multiple of seven`() {
        for (m in 1..12) {
            val g = DreamCalendar.monthGrid(YearMonth.of(2026, m))
            assertTrue("month $m size=${g.size}", g.size % 7 == 0)
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*DreamCalendarTest*" --no-daemon
```

Expected: 编译失败，`Unresolved reference: DreamCalendar`。

- [ ] **Step 3: 最小实现**

```kotlin
package com.dreamkeeper.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

object DreamCalendar {

    /** 返回月视图格子；null 为空白填充格。长度恒为 7 的倍数。 */
    fun monthGrid(month: YearMonth, weekStart: DayOfWeek = DayOfWeek.MONDAY): List<LocalDate?> {
        val first = month.atDay(1)
        val leading = ((first.dayOfWeek.value - weekStart.value) + 7) % 7
        val cells = mutableListOf<LocalDate?>()
        repeat(leading) { cells.add(null) }
        for (day in 1..month.lengthOfMonth()) cells.add(month.atDay(day))
        while (cells.size % 7 != 0) cells.add(null)
        return cells
    }

    /** 该月第一天与最后一天的 epoch-day 区间，供数据库查询使用。 */
    fun monthRange(month: YearMonth): Pair<LocalDate, LocalDate> =
        month.atDay(1) to month.atEndOfMonth()
}
```

- [ ] **Step 4: 跑测试确认通过**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*DreamCalendarTest*" --no-daemon
```

Expected: `BUILD SUCCESSFUL`，5 个测试通过。

- [ ] **Step 5: 提交**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(domain): 日历月视图排布逻辑与测试"
git rev-parse --short HEAD
```

- [ ] **Step 6: 归档证据**

纯 JVM 任务没有截图，证据就是**测试输出**：按 §2 写 `docs/evidence/plan1/task3.md`，把 `:app:testDebugUnitTest --tests "*DreamCalendarTest*"` 的完整末尾输出（`5 tests completed` 那几行）贴进 `已知异常`/`构建结果`，并记 short SHA。没有 APK/截图就写「本任务无」。然后 `git add docs/evidence/plan1; git commit -m "docs(evidence): task3 证据归档"`。

---

## Task 4: 数据层 —— Room 实体、LIKE 搜索与 DAO

**Files:**
- Create: `.../data/db/DreamEntity.kt`, `TagEntity.kt`, `DreamTagEntity.kt`, `MediaAssetEntity.kt`, `DreamDao.kt`, `TagDao.kt`, `DreamTagDao.kt`, `MediaAssetDao.kt`, `Converters.kt`, `SeedData.kt`, `DreamDatabase.kt`
- Modify: `android/app/build.gradle.kts`（`ksp { arg("room.schemaLocation", ...) }`、`room-testing`、`buildConfigField("SEED_DEMO_DATA")`）
- Modify: `android/gradle/libs.versions.toml`（加 `room-testing`）
- Commit（必须入库）: `android/app/schemas/com.dreamkeeper.data.db.DreamDatabase/1.json` —— `exportSchema = true` 的产物，是 migration 的唯一对照物，删了它以后任何 schema 变更都失去依据
- Test: `android/app/src/test/java/com/dreamkeeper/data/DreamDaoTest.kt`

> **本任务不建 `generation_jobs` 表**：`GenerationJobEntity`/`GenerationJobDao` 整个撤回（第一轮按旧指令加过，现在撤回）。该表由 **Plan 3 建**——迁移基建（`exportSchema` + schema json + `room-testing`）已在 Plan 1 就位，届时 v1→v2 有基线可验。同理 `PromptVersion` 也不建，提示词以 `prompts/` 文件为唯一真源。

**Interfaces:**
- Produces（后续任务全部依赖这些精确签名）:
  - `DreamEntity(id: String, ownerId: String, dreamDate: LocalDate, createdAt: Long, title: String?, rawTranscript: String, faithfulText: String?, novelText: String?, novelVersion: Int, novelChangeLog: String?, mood: String?, isDraft: Boolean, deletedAt: Long?, updatedAt: Long, rawAudioPath: String? = null, rawAudioMs: Long? = null, faithfulEditedByUser: Boolean = false, novelEditedByUser: Boolean = false, remoteId: String? = null, transcriptStatus: String = "OK")` —— `transcriptStatus` 是末尾追加字段，取值 `PENDING`/`OK`/`FAILED`
  - `MediaAssetEntity(id: String, dreamId: String, type: String, path: String?, prompt: String?, model: String?, status: String, createdAt: Long)`
  - `DreamDao.observeByMonth(fromIso: String, toIso: String): Flow<List<DreamEntity>>`
  - `DreamDao.observeDay(iso: String): Flow<List<DreamEntity>>` —— 当日列表层（日历 → 当日列表 → 详情，设计 §11 的第二层；D35）。**按单日精确匹配，不是区间**，跨月同一天号不许串
  - `DreamDao.observeAll(): Flow<List<DreamEntity>>`
  - `DreamDao.observeById(id: String): Flow<DreamEntity?>`
  - `DreamDao.upsert(dream: DreamEntity)`
  - `DreamDao.count(): Int`（种子幂等判据）
  - `DreamDao.searchTerm(term: String): List<DreamEntity>` —— `term` 必须是**已转义**的词（调用方唯一：`DreamRepository.search`；转义见 Task 5）
  - `TagDao.observeAll(): Flow<List<TagEntity>>`、`TagDao.upsert(tag: TagEntity)`、`TagDao.rename(id, newName)`、`TagDao.repointDreamTags(fromId, intoId)`、`TagDao.delete(id)`（事务：先删关联再删标签）、`TagDao.count(): Int`
  - `DreamTagDao.upsertAll(links: List<DreamTagEntity>)`、`DreamTagDao.deleteByDream(dreamId)`、`DreamTagDao.observeTagsForDream(dreamId): Flow<List<TagEntity>>`（`tags INNER JOIN dream_tags`，按 `kind, name` 排序；详情页标签 tab 用这个——`Dream` 上没有 `tags` 字段）、`DreamTagDao.deleteByTag(tagId)`
  - `MediaAssetDao.upsert(asset)`、`MediaAssetDao.observeByDream(dreamId): Flow<List<MediaAssetEntity>>`
  - `SeedData.seedIfEmpty(db: DreamDatabase)`（幂等：库里已有梦就返回）
  - `DreamDatabase.create(context: Context): DreamDatabase`

- [ ] **Step 1: 先改构建：schema 导出、room-testing、种子开关**

`android/app/build.gradle.kts` 里补三处（其余保持 Task 1 原样）：

```kotlin
android {
    // ...（namespace / compileSdk / defaultConfig 原有内容保持不变；
    //      **不要把 SEED_DEMO_DATA 写进 defaultConfig**，理由见下面 buildTypes）

    buildFeatures {
        compose = true
        buildConfig = true   // AGP 9.5 的默认值未实测；BuildConfig 里有手写字段，必须显式开
    }

    // 种子开关按 buildType 分（D37）。写在 defaultConfig 里就是"恒 true"——
    // release 包首启也会灌 4 条假梦，那是脏数据，不是演示。
    buildTypes {
        debug {
            buildConfigField("boolean", "SEED_DEMO_DATA", "true")
        }
        release {
            buildConfigField("boolean", "SEED_DEMO_DATA", "false")
        }
    }
}

// 与 android { } 同级，放在文件末尾
ksp {
    // Room schema 导出目录：产物必须提交入库（app/schemas/.../1.json）
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

> **这条配置的验收在 Task 9**：`.\gradlew.bat :app:assembleRelease --no-daemon` 之后，`android/app/build/generated/source/buildConfig/release/com/dreamkeeper/BuildConfig.java` 里 `SEED_DEMO_DATA` 必须是 `false`。只验 debug 等于没验——那个「release 也灌种子」的洞就是 defaultConfig 写出来的。

`android/gradle/libs.versions.toml` 的 `[libraries]` 补一行：

```toml
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
```

`android/app/build.gradle.kts` 的 `dependencies` 补一行（`room-testing` 提供 `MigrationTestHelper`——Plan 2 给 `dreams` 加字段时就靠它验 migration）：

```kotlin
    testImplementation(libs.room.testing)
```

Expected: `cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon` 仍 `BUILD SUCCESSFUL`（此时 `app/schemas/` 还是空的，数据库类尚未写）。

- [ ] **Step 2: 写失败的 DAO 测试**

```kotlin
package com.dreamkeeper.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.db.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DreamDaoTest {

    private lateinit var db: DreamDatabase
    private lateinit var dao: DreamDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DreamDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.dreamDao()
    }

    @After
    fun tearDown() = db.close()

    private fun dream(id: String, date: String, text: String = "默认文字") = DreamEntity(
        id = id,
        ownerId = "local",
        dreamDate = LocalDate.parse(date),
        createdAt = 0L,
        title = null,
        rawTranscript = text,
        faithfulText = text,
        novelText = null,
        novelVersion = 0,
        novelChangeLog = null,
        mood = null,
        isDraft = false,
        deletedAt = null,
        updatedAt = 0L,
    )

    @Test
    fun `observeByMonth returns only dreams inside the range ordered by date desc`() = runTest {
        dao.upsert(dream("1", "2026-10-01"))
        dao.upsert(dream("2", "2026-10-15"))
        dao.upsert(dream("3", "2026-09-30"))
        val got = dao.observeByMonth("2026-10-01", "2026-10-31").first()
        assertEquals(listOf("2", "1"), got.map { it.id })
    }

    @Test
    fun `soft deleted dreams are excluded from month query`() = runTest {
        dao.upsert(dream("1", "2026-10-01").copy(deletedAt = 1L))
        dao.upsert(dream("2", "2026-10-02"))
        val got = dao.observeByMonth("2026-10-01", "2026-10-31").first()
        assertEquals(listOf("2"), got.map { it.id })
    }

    @Test
    fun `observeDay does not leak the same day number from another month`() = runTest {
        // 跨月不串：三条都是「8 号」或邻日，只有 2026-11-08 属于查的那天
        dao.upsert(dream("1", "2026-11-08"))
        dao.upsert(dream("2", "2026-12-08"))
        dao.upsert(dream("3", "2026-11-09"))
        val got = dao.observeDay("2026-11-08").first()
        assertEquals(listOf("1"), got.map { it.id })
    }

    @Test
    fun `observeDay excludes soft deleted dreams`() = runTest {
        dao.upsert(dream("1", "2026-11-08").copy(deletedAt = 1L))
        dao.upsert(dream("2", "2026-11-08"))
        assertEquals(listOf("2"), dao.observeDay("2026-11-08").first().map { it.id })
    }

    @Test
    fun `like search finds a word inside the transcript`() = runTest {
        dao.upsert(dream("1", "2026-10-01", "电梯里有人按了六楼"))
        dao.upsert(dream("2", "2026-10-02", "教室最后一排"))
        val got = dao.searchTerm("电梯")
        assertEquals(listOf("1"), got.map { it.id })
    }

    @Test
    fun `search also matches the novel text`() = runTest {
        dao.upsert(dream("1", "2026-10-01").copy(novelText = "他在楼道尽头回头看我"))
        val got = dao.searchTerm("楼道")
        assertEquals(listOf("1"), got.map { it.id })
    }

    @Test
    fun `media asset is stored against a dream`() = runTest {
        dao.upsert(dream("1", "2026-10-01"))
        db.mediaAssetDao().upsert(
            MediaAssetEntity("m1", "1", MediaType.IMAGE.name, null, "prompt", "model", "PENDING", 0L)
        )
        val assets = db.mediaAssetDao().observeByDream("1").first()
        assertEquals(1, assets.size)
        assertTrue(assets.first().type == MediaType.IMAGE.name)
    }

    @Test
    fun `transcript status defaults to OK and round trips PENDING then OK`() = runTest {
        // 默认构造仍编译，且默认值就是 OK：Plan 1 不录音，所有行都该是 OK
        assertEquals(TranscriptStatus.OK, dream("0", "2026-10-01").transcriptStatus)

        dao.upsert(dream("1", "2026-10-01").copy(transcriptStatus = TranscriptStatus.PENDING))
        assertEquals(TranscriptStatus.PENDING, dao.observeById("1").first()!!.transcriptStatus)

        dao.upsert(dream("1", "2026-10-01").copy(transcriptStatus = TranscriptStatus.OK))
        assertEquals(TranscriptStatus.OK, dao.observeById("1").first()!!.transcriptStatus)
    }

    @Test
    fun `tags for a dream come from the join table`() = runTest {
        dao.upsert(dream("1", "2026-10-01"))
        db.tagDao().upsert(TagEntity("t1", "电梯", TagKind.OBJECT))
        db.tagDao().upsert(TagEntity("t2", "坠落", TagKind.EMOTION))
        db.dreamTagDao().upsertAll(listOf(DreamTagEntity("1", "t1")))
        val tags = db.dreamTagDao().observeTagsForDream("1").first()
        assertEquals(listOf("t1"), tags.map { it.id })
    }

    @Test
    fun `deleting a tag also removes its dream links`() = runTest {
        dao.upsert(dream("1", "2026-10-01"))
        db.tagDao().upsert(TagEntity("t1", "电梯", TagKind.OBJECT))
        db.dreamTagDao().upsertAll(listOf(DreamTagEntity("1", "t1")))

        db.tagDao().delete("t1")   // 事务：先删 dream_tags，再删 tags

        assertEquals(0, db.dreamTagDao().observeTagsForDream("1").first().size)
        assertEquals(0, db.tagDao().observeAll().first().size)
    }

    @Test
    fun `seeding twice does not duplicate`() = runTest {
        SeedData.seedIfEmpty(db)
        SeedData.seedIfEmpty(db)
        assertEquals(4, dao.count())
        assertTrue(db.tagDao().count() >= 1)
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*DreamDaoTest*" --no-daemon
```

Expected: 编译失败（`Unresolved reference: DreamDatabase`）。

- [ ] **Step 4: 写实体与类型转换器**

`data/db/Converters.kt`：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.TypeConverter
import java.time.LocalDate

class Converters {
    @TypeConverter fun fromDate(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun toDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}
```

`data/db/DreamEntity.kt`：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

object MediaType {
    const val IMAGE = "IMAGE"
    const val VIDEO = "VIDEO"
}

/**
 * 转写状态。为什么不用「空串当哨兵」：断网首跑、本机无中文离线包、API 26–30 没有 on-device 识别时，
 * 必然出现「录音已落盘、文字还没出来」的中间态；而「真录到静音」也会产出空串。
 * 两种空串含义完全相反，必须由状态位解释，不能靠内容猜。
 */
object TranscriptStatus {
    const val PENDING = "PENDING"
    const val OK = "OK"
    const val FAILED = "FAILED"
}

@Entity(tableName = "dreams")
data class DreamEntity(
    @PrimaryKey val id: String,
    val ownerId: String = "local",
    val dreamDate: LocalDate,
    val createdAt: Long,
    val title: String?,
    val rawTranscript: String,
    val faithfulText: String?,
    val novelText: String?,
    val novelVersion: Int = 0,
    val novelChangeLog: String? = null,
    val mood: String? = null,
    val isDraft: Boolean = false,
    val deletedAt: Long? = null,
    val updatedAt: Long = 0L,

    // ↓↓↓ Plan 1 预留位：**只许在末尾追加，且必须带默认值** ↓↓↓
    // 理由：`Dream(id, date, title, ...)` 在 Task 6/7/8 的测试里有 3 处位置参数调用，
    // 插在中间会让它们全部错位，且不报编译错（类型凑巧兼容时更糟）。
    /** 原始音频文件路径（Plan 2 录音写入；null = 这条梦没有音频） */
    val rawAudioPath: String? = null,
    /** 原始音频时长，毫秒（播放条与时长文字用；null = 未知） */
    val rawAudioMs: Long? = null,
    /** 原样稿是否被用户手改过（设计 §4：手动编辑后标「已手改」） */
    val faithfulEditedByUser: Boolean = false,
    /** 小说化稿是否被用户手改过 */
    val novelEditedByUser: Boolean = false,
    /** 云同步预留（设计 §12：v1 不传输，只留字段） */
    val remoteId: String? = null,
    /**
     * 转写状态（设计 §5、§8）：`PENDING` = 已开录、文字还没出来；`OK` = 转写成功；`FAILED` = 转写失败。
     * 不能用空串当哨兵——空串分不清「还没转写」和「真的录到一段静音」。
     */
    val transcriptStatus: String = TranscriptStatus.OK,
)
```

`data/db/TagEntity.kt`：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

object TagKind { const val PERSON="PERSON"; const val PLACE="PLACE"; const val OBJECT="OBJECT"
                 const val ACTION="ACTION"; const val EMOTION="EMOTION"; const val OTHER="OTHER" }
object TagSource { const val AUTO="AUTO"; const val USER="USER" }

@Entity(tableName = "tags", indices = [androidx.room.Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String = TagKind.OTHER,
    val source: String = TagSource.AUTO,
    val evidenceText: String? = null,
)
```

`data/db/DreamTagEntity.kt`：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.Entity

@Entity(tableName = "dream_tags", primaryKeys = ["dreamId", "tagId"])
data class DreamTagEntity(
    val dreamId: String,
    val tagId: String,
    val confidence: Float = 1f,
)
```

`data/db/MediaAssetEntity.kt`：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_assets", indices = [androidx.room.Index("dreamId")])
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val dreamId: String,
    val type: String,
    val path: String?,
    val prompt: String?,
    val model: String?,
    val status: String,
    val createdAt: Long,
)
```

- [ ] **Step 5: 写 DAO**

`data/db/DreamDao.kt`：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DreamDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dream: DreamEntity)

    /** 种子注入的幂等判据：非空就不再写 */
    @Query("SELECT COUNT(*) FROM dreams") suspend fun count(): Int

    @Query("""
        SELECT * FROM dreams
        WHERE deletedAt IS NULL AND dreamDate BETWEEN :fromIso AND :toIso
        ORDER BY dreamDate DESC, createdAt DESC
    """)
    fun observeByMonth(fromIso: String, toIso: String): Flow<List<DreamEntity>>

    /**
     * 当日列表（设计 §11 的中间层，D35）。
     *
     * 用 `dreamDate = :iso` 精确匹配，**不是 `BETWEEN`**：`BETWEEN iso AND iso` 也对，
     * 但写成等值能让"跨月不串"这条意图直接长在 SQL 上——不同月份的同一天号是两个不同的字符串。
     * 排序是 `createdAt DESC`（同一天里后补录的在前），与月视图的 `dreamDate DESC, createdAt DESC` 不同：
     * 这里 dreamDate 全相同，再按它排没有意义。
     */
    @Query("""
        SELECT * FROM dreams
        WHERE deletedAt IS NULL AND dreamDate = :iso
        ORDER BY createdAt DESC
    """)
    fun observeDay(iso: String): Flow<List<DreamEntity>>

    @Query("SELECT * FROM dreams WHERE deletedAt IS NULL ORDER BY dreamDate DESC, createdAt DESC")
    fun observeAll(): Flow<List<DreamEntity>>

    @Query("SELECT * FROM dreams WHERE id = :id")
    fun observeById(id: String): Flow<DreamEntity?>

    /**
     * 4 列 LIKE 搜索。
     *
     * **term 必须是已转义的**：`\` → `\\`、`%` → `\%`、`_` → `\_`。否则用户搜 `100%`
     * 会被当成通配符命中全库。转义只该发生在一处：`DreamRepository.search`。
     *
     * 为什么不是 FTS：见下面「为什么不用 FTS4」——中文没有空格，FTS 的 tokenizer
     * 会把整段连续汉字当成一个 token，`MATCH "电梯"` 命中 0 行。
     */
    @Query("""
        SELECT * FROM dreams
        WHERE deletedAt IS NULL AND (
            title LIKE '%'||:term||'%' ESCAPE '\'
            OR rawTranscript LIKE '%'||:term||'%' ESCAPE '\'
            OR faithfulText LIKE '%'||:term||'%' ESCAPE '\'
            OR novelText LIKE '%'||:term||'%' ESCAPE '\'
        )
        ORDER BY dreamDate DESC, createdAt DESC
    """)
    suspend fun searchTerm(term: String): List<DreamEntity>
}
```

> **注意**：`dreamDate` 是 `LocalDate`，经 TypeConverter 存成 `yyyy-MM-dd` 字符串，所以 `BETWEEN :fromIso AND :toIso` 是字符串比较——ISO 日期按字典序比较与按时间比较结果一致，这是安全的。搜索的封装在 Repository（Task 5）：按空白切词、每词做 LIKE 转义、逐词调 `searchTerm`、按 id 求交集。

#### 为什么不用 FTS4（实测于 SQLite 3.40.1）

两个角色各自独立复现，结论一致——**这不是"可能有风险"，是已经确定会红**：

| 写法 | 对正文「电梯里有人按了六楼」搜「电梯」 | 结论 |
|---|---|---|
| `@Fts4`（默认 `simple` tokenizer） | `MATCH "电梯"` → **0 行** | 整段连续汉字是一个 token |
| `@Fts4` + `unicode61` | 同样 **0 行** | unicode61 不按中文词边界切分 |
| `MATCH "电梯*"` | 命中 | 只有前缀匹配可用 |

中文没有空格，FTS 的 tokenizer 拿不到"词"这个单位；拿整段当一个 token，结果就是"要么全中，要么不中"。
所以 Plan 1 的搜索是：**4 列 `LIKE` + 手写转义 + 逐词求交**（`title` / `rawTranscript` / `faithfulText` / `novelText`）。
`DreamDaoTest` 里那两条中文搜索断言**保持原样**——它们代表产品需求（搜「电梯」必须命中），实现迁就需求，不是需求迁就实现。

**性能**：1000 条规模、4 列短文本的 `LIKE '%x%'` 是毫秒级，够用。真到破万条、搜索明显变卡，再换"逐字空格索引"。

**升级路径（已探明，但现在不做）**：写入时按汉字加空格（`电梯里有` → `电 梯 里 有`），查询时按短语匹配。拦路的是 `@Fts4(contentEntity = DreamEntity::class)`——Room 自动同步的是**原文**，插不了空格。届时必须改成非 content 表（自己维护 FTS 表、写入切分后的文本），这是一次真正的迁移，不是换个 tokenizer 参数。等真有破万条数据再动。

`data/db/TagDao.kt`：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(tag: TagEntity)
    @Query("SELECT * FROM tags ORDER BY kind, name") fun observeAll(): Flow<List<TagEntity>>
    @Query("UPDATE tags SET name = :newName WHERE id = :id") suspend fun rename(id: String, newName: String)
    @Query("UPDATE OR REPLACE dream_tags SET tagId = :intoId WHERE tagId = :fromId")
    suspend fun repointDreamTags(fromId: String, intoId: String)
    @Query("SELECT COUNT(*) FROM tags") suspend fun count(): Int

    @Query("DELETE FROM dream_tags WHERE tagId = :tagId") suspend fun deleteLinksForTag(tagId: String)
    @Query("DELETE FROM tags WHERE id = :id") suspend fun deleteRow(id: String)

    /**
     * 删标签 = 先断关联、再删行，**必须在同一个事务里**。
     * 分两步裸写会在中间留下"指向不存在标签"的关联行，下次联表查询就踩空。
     */
    @Transaction
    suspend fun delete(id: String) {
        deleteLinksForTag(id)
        deleteRow(id)
    }
}
```

`data/db/DreamTagDao.kt`（梦 ⇄ 标签关联表的**唯一写入方**；`TagDao` 只管标签本身）：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * **禁止给 `dream_tags` 加外键 CASCADE。**
 * Task 9 的标签合并走 `UPDATE OR REPLACE`（REPLACE 冲突 = 先删后插），
 * 一旦有 CASCADE，删那一行时会把它的关联行一起删掉——合并一次丢一批标注，而且不报错。
 * 关联只能显式清理：`deleteByDream` / `deleteByTag` / `TagDao.delete` 里的事务。
 */
@Dao
interface DreamTagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertAll(links: List<DreamTagEntity>)

    @Query("DELETE FROM dream_tags WHERE dreamId = :dreamId") suspend fun deleteByDream(dreamId: String)

    /** 详情页「标签」tab 的数据源：联表取标签，按 kind, name 排序 */
    @Query("""
        SELECT tags.* FROM tags
        INNER JOIN dream_tags ON tags.id = dream_tags.tagId
        WHERE dream_tags.dreamId = :dreamId
        ORDER BY tags.kind, tags.name
    """)
    fun observeTagsForDream(dreamId: String): Flow<List<TagEntity>>

    @Query("DELETE FROM dream_tags WHERE tagId = :tagId") suspend fun deleteByTag(tagId: String)
}
```

`data/db/MediaAssetDao.kt`：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaAssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(asset: MediaAssetEntity)
    @Query("SELECT * FROM media_assets WHERE dreamId = :dreamId ORDER BY createdAt DESC")
    fun observeByDream(dreamId: String): Flow<List<MediaAssetEntity>>
}
```

- [ ] **Step 6: 写 Database**

`data/db/DreamDatabase.kt`：

```kotlin
package com.dreamkeeper.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [DreamEntity::class, TagEntity::class, DreamTagEntity::class,
                MediaAssetEntity::class],
    version = 1,
    exportSchema = true,      // 必须 true：schema json 是 migration 的唯一对照物
)
@TypeConverters(Converters::class)
abstract class DreamDatabase : RoomDatabase() {
    abstract fun dreamDao(): DreamDao
    abstract fun tagDao(): TagDao
    abstract fun dreamTagDao(): DreamTagDao
    abstract fun mediaAssetDao(): MediaAssetDao

    companion object {
        fun create(context: Context): DreamDatabase =
            Room.databaseBuilder(context, DreamDatabase::class.java, "dreamkeeper.db").build()
            // 注意这里没有、也永远不许加 fallbackToDestructiveMigration()：
            // 升级时宁可崩在 migration，也不能把用户的梦静默删掉。
    }
}
```

> **`exportSchema = true` 的验收动作**：跑完 Step 8 的测试后，确认 `android/app/schemas/com.dreamkeeper.data.db.DreamDatabase/1.json` 真的生成了，并**提交入库**。
> 它是"v1 的表结构长什么样"的唯一机器可读记录——Plan 2 给 `dreams` 加字段时，`MigrationTestHelper` 就靠它建出旧库；丢了它，migration 只能靠肉眼回忆。

- [ ] **Step 7: 写种子数据（SeedData）**

`data/db/SeedData.kt`（4 条固定 id 的梦，让 Plan 1 每个界面都有东西可看；其中 1 条带 `EMOTION` 标签 + 非空 `evidenceText`，1 条带 `VIDEO` 素材占位，1 条带现成的小说化稿）。**日期一律相对当前月，且写入必须在一个事务里**（D36）：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.withTransaction
import java.time.YearMonth

/**
 * 演示用种子数据。
 *
 * 日期**不许写死**（D36）：把某个月的 20/24/27/28 号写成字面量，过了那个月首屏就再也没有红点，
 * Task 6/7/8 的截图验收会集体作废，而且失败方式很隐蔽——界面一切正常，只是空。
 * 这里按「当月内的固定几天」生成（1/8/15/22 号），4 条种子**恒在同一个自然月**里，
 * 于是「当月 4 个有梦日」这条断言在任何月份跑都成立。
 *
 * 幂等：库里已有任何一条梦就整体跳过（`dao.count() == 0` 才写）。
 * 事务：`count()` 判断与写入必须在同一个 `withTransaction` 里（D36）——分开写的话，
 *       首启写到一半被杀，库里只剩 1–2 条种子，而 count 已经不是 0，剩下的永远不会补上。
 * 门闩：调用点用 `BuildConfig.SEED_DEMO_DATA` 包住（见 Task 5 的 DreamKeeperApp），
 *       该字段只在 debug 为 true（D37），所以 release 包不会灌种子。
 * 定位：这是"看得见的东西"，不是测试夹具——测试仍自己造数据，不依赖种子。
 */
object SeedData {

    const val DREAM_ELEVATOR = "seed-dream-1"
    const val DREAM_CLASSROOM = "seed-dream-2"
    const val DREAM_OLD_HOUSE = "seed-dream-3"
    const val DREAM_STAIRS = "seed-dream-4"
    const val TAG_EMOTION_FALL = "seed-tag-emotion-fall"
    const val MEDIA_VIDEO_CLASSROOM = "seed-media-1"

    /** 4 条种子分别落在当月第几天。改这里就要同步改验收里「当月 4 个有梦日」的表述 */
    private const val DAY_ELEVATOR = 1
    private const val DAY_CLASSROOM = 8
    private const val DAY_OLD_HOUSE = 15
    private const val DAY_STAIRS = 22

    suspend fun seedIfEmpty(db: DreamDatabase) {
        db.withTransaction {
            val dreams = db.dreamDao()
            if (dreams.count() != 0) return@withTransaction   // 幂等：只写一次
            val month = YearMonth.now()                        // 4 条共用同一个月

            // 1) 电梯（当月 1 号）：带一个 EMOTION 标签，evidenceText 非空（设计 §4 要求标签有出处）
            dreams.upsert(dream(DREAM_ELEVATOR, month, DAY_ELEVATOR, "电梯",
                "我在电梯里，电梯一直往上，按不到一楼。数字过了三十还在涨。"))
            db.tagDao().upsert(TagEntity(TAG_EMOTION_FALL, "下坠的预感", TagKind.EMOTION,
                TagSource.AUTO, "电梯一直往上，按不到一楼"))
            db.dreamTagDao().upsertAll(listOf(DreamTagEntity(DREAM_ELEVATOR, TAG_EMOTION_FALL)))

            // 2) 教室（当月 8 号）：带一个 VIDEO 素材占位（设计 §12「梦 → 视频」的预留口子）
            dreams.upsert(dream(DREAM_CLASSROOM, month, DAY_CLASSROOM, "教室",
                "教室最后一排，黑板上的字在动，像水。同桌递给我一张纸，上面只写了我的名字。"))
            db.mediaAssetDao().upsert(
                MediaAssetEntity(MEDIA_VIDEO_CLASSROOM, DREAM_CLASSROOM, MediaType.VIDEO,
                    path = null, prompt = null, model = null, status = "PENDING", createdAt = 0L)
            )

            // 3) 老房子（当月 15 号）：已有小说化稿，验证「小说化·补写N处」tab 非空时的排版
            dreams.upsert(dream(DREAM_OLD_HOUSE, month, DAY_OLD_HOUSE, "老房子",
                "老房子，走廊很长，尽头有光。我妈在厨房叫我，声音从楼上传来。").copy(
                novelText = "老房子的走廊比记忆里长。尽头有一扇窗，光从那里斜进来，把地板切成两半。厨房里有人在叫我的名字——可那个声音是从楼上来的。",
                novelVersion = 1,
                novelChangeLog = "补写 2 处：走廊的光、声音的方向",
            ))

            // 4) 楼梯（当月 22 号）：无标题、正文极短，验证列表里的短条目排版
            dreams.upsert(dream(DREAM_STAIRS, month, DAY_STAIRS, null, "一直在下楼梯，每一层都一样。"))
        }
    }

    private fun dream(id: String, month: YearMonth, day: Int, title: String?, text: String) = DreamEntity(
        id = id,
        ownerId = "local",
        dreamDate = month.atDay(day),   // LocalDate；月份随运行日期走，不写死
        createdAt = 0L,
        title = title,
        rawTranscript = text,
        faithfulText = text,
        novelText = null,
        novelVersion = 0,
        novelChangeLog = null,
        mood = null,
        isDraft = false,
        deletedAt = null,
        updatedAt = 0L,
    )
}
```

> **写死日期的期望值也一并改掉**：从这一轮起，计划里任何验收/断言都不许出现具体年月日，只许写相对表述——「当月内 4 个有梦日」「标题『电梯』那条的 `dreamDate` = `YearMonth.now()` 的 1 号」「列表按当月 22/15/8/1 号倒序」。写死一次，下个月就有一整批验收静默失效。

> **注入点不在本任务**：Task 4 还没有 `AppContainer`，所以这里只定义 `seedIfEmpty`；真正的调用在 Task 5 的 `DreamKeeperApp.onCreate()`，条件是 `if (BuildConfig.SEED_DEMO_DATA) SeedData.seedIfEmpty(db)`（`count() == 0` 的判断已在 `seedIfEmpty` 里，两处不要重复写）。

- [ ] **Step 8: 跑测试确认通过**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*DreamDaoTest*" --no-daemon
```

Expected: `BUILD SUCCESSFUL`，**11 个测试通过**（原来的 9 条 + `observeDay` 的两条）；并确认 `android/app/schemas/com.dreamkeeper.data.db.DreamDatabase/1.json` 已生成（`git status` 里应看到它是未跟踪文件），把它一起提交。

- 若 `like search finds a word inside the transcript` 红：先确认查询里 `LIKE '%'||:term||'%'` 的 `%` 和 `ESCAPE '\'` 都在，再确认测试库里的 `rawTranscript` 真的含「电梯」。**不要去改测试迁就实现**（改测试等于把中文搜索的 bug 焊进基线）。
- 若 `deleting a tag also removes its dream links` 红：说明 `TagDao.delete` 没有包在 `@Transaction` 里，或者漏删了 `dream_tags`。

- [ ] **Step 9: 提交**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(data): Room 实体、LIKE 中文搜索与种子数据"
git rev-parse --short HEAD
```

- [ ] **Step 10: 归档证据**

按 §2 写 `docs/evidence/plan1/task4.md`：short SHA、测试命令与末尾输出（`11 tests completed`）、`app/schemas/.../1.json` 的路径与一句话说明（它是 v1 结构基线）、种子注入后的库快照（若有）、复现步骤。然后 `git add docs/evidence/plan1; git commit -m "docs(evidence): task4 证据归档"`。

---

## Task 5: 仓库层 —— 领域模型与数据库之间的唯一翻译

**Files:**
- Create: `.../domain/Dream.kt`, `.../domain/Tag.kt`, `.../domain/MediaAsset.kt`, `.../data/DreamRepository.kt`, `.../di/AppContainer.kt`
- Modify: `.../DreamKeeperApp.kt`（建容器 + 种子注入）
- Test: `android/app/src/test/java/com/dreamkeeper/data/DreamRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 4 的 DAO 与实体。
- Produces:
  - `data class Dream(val id: String, val date: LocalDate, val title: String?, val rawTranscript: String, val faithfulText: String?, val novelText: String?, val novelChangeLog: String?, val mood: String?, val isDraft: Boolean, val novelVersion: Int = 0, val createdAt: Long = 0L, val updatedAt: Long = 0L, val deletedAt: Long? = null, val transcriptStatus: String = "OK", val rawAudioPath: String? = null, val rawAudioMs: Long? = null)` —— **新增字段一律带默认值、一律追加在末尾**：Task 6/7/8 的测试用位置参数构造 `Dream(id, date, title, ...)`，插在中间会静默错位。
  - `data class Tag(val id: String, val name: String, val kind: String, val source: String, val evidenceText: String?)`
  - `data class MediaAsset(val id: String, val dreamId: String, val type: String, val path: String?, val prompt: String?, val model: String?, val status: String, val createdAt: Long)`
  - `class DreamRepository(private val db: DreamDatabase, private val clock: () -> Long = System::currentTimeMillis)`
    - `fun observeMonth(month: YearMonth): Flow<List<Dream>>`
    - `fun observeDay(date: LocalDate): Flow<List<Dream>>` —— 当日列表的数据源（D35），内部调 `DreamDao.observeDay(date.toString())`
    - `fun observeAll(): Flow<List<Dream>>`
    - `fun observe(id: String): Flow<Dream?>`
    - `suspend fun save(dream: Dream)` —— **不得覆盖调用方带来的 `createdAt` / `deletedAt`**（见 Step 4 的实现）
    - `suspend fun search(rawQuery: String): List<Dream>`
    - `fun observeTags(): Flow<List<Tag>>`
    - `fun observeTagsForDream(dreamId: String): Flow<List<Tag>>`（详情页标签 tab，走 `DreamTagDao` 联表）
    - `suspend fun attachTags(dreamId: String, tagIds: List<String>)`（Plan 3 自动打标与 Task 9 合并的写入口）
    - `fun observeMediaAssets(dreamId: String): Flow<List<MediaAsset>>`（详情页素材 tab）
  - `class AppContainer(context: Context) { val database: DreamDatabase; val repository: DreamRepository }`

- [ ] **Step 1: 写失败的测试（覆盖搜索转义、保存不篡改时间戳、标签联表）**

```kotlin
package com.dreamkeeper.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.domain.Dream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DreamRepositoryTest {

    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DreamDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = DreamRepository(db) { 1000L }
    }

    @After fun tearDown() = db.close()

    private fun d(id: String, date: String, text: String) =
        Dream(id = id, date = LocalDate.parse(date), title = null, rawTranscript = text,
              faithfulText = text, novelText = null, novelChangeLog = null, mood = null, isDraft = false)

    @Test fun `save then observeMonth returns it`() = runTest {
        repo.save(d("1", "2026-10-15", "电梯"))
        val got = repo.observeMonth(YearMonth.of(2026, 10)).first()
        assertEquals(listOf("1"), got.map { it.id })
    }

    @Test fun `ids are stable across save`() = runTest {
        repo.save(d("abc", "2026-10-15", "电梯"))
        assertEquals("abc", repo.observe("abc").first()!!.id)
    }

    @Test fun `search splits on spaces and requires all terms`() = runTest {
        repo.save(d("1", "2026-10-01", "电梯里有人按了六楼"))
        repo.save(d("2", "2026-10-02", "电梯停在负一层"))
        val got = repo.search("电梯 六楼")
        assertEquals(listOf("1"), got.map { it.id })
    }

    @Test fun `search tolerates quotes and punctuation without crashing`() = runTest {
        repo.save(d("1", "2026-10-01", "电梯里有人"))
        assertEquals(1, repo.search("\"电梯\"！").size)
    }

    @Test fun `empty query returns empty list`() = runTest {
        repo.save(d("1", "2026-10-01", "电梯"))
        assertEquals(0, repo.search("   ").size)
    }

    @Test fun `search treats percent as a literal character`() = runTest {
        repo.save(d("1", "2026-10-01", "进度 100% 就停下了"))
        repo.save(d("2", "2026-10-02", "完全无关的一条"))
        // 若 % 没被转义，LIKE '%%%' 会命中所有行——这条就是那个回归
        assertEquals(listOf("1"), repo.search("100%").map { it.id })
    }

    @Test fun `search matches a substring in the middle of the novel text`() = runTest {
        repo.save(d("1", "2026-10-01", "走廊很长").copy(
            novelText = "走廊很长，尽头是楼道，灯闪了一下。"))
        // 楼道 只出现在 novelText 的中间：4 列 LIKE 必须能捞出来
        assertEquals(listOf("1"), repo.search("楼道").map { it.id })
    }

    @Test fun `save preserves createdAt across updates`() = runTest {
        repo.save(d("1", "2026-10-15", "电梯"))
        val first = repo.observe("1").first()!!
        assertEquals(1000L, first.createdAt)

        // 换一个时钟再存一次：createdAt 是"首次录入时间"，不许被这次补录/编辑改写
        val later = DreamRepository(db) { 2000L }
        later.save(first.copy(title = "改过的标题"))

        val after = repo.observe("1").first()!!
        assertEquals(1000L, after.createdAt)
        assertEquals(2000L, after.updatedAt)
        assertEquals("改过的标题", after.title)
    }

    @Test fun `save preserves deletedAt`() = runTest {
        repo.save(d("1", "2026-10-15", "电梯"))
        // 直接置成回收站里的行：save 不能把它悄悄捞回"未删除"
        db.dreamDao().upsert(db.dreamDao().observeById("1").first()!!.copy(deletedAt = 500L))
        val trashed = repo.observe("1").first()!!
        assertEquals(500L, trashed.deletedAt)

        repo.save(trashed)
        assertEquals(500L, repo.observe("1").first()!!.deletedAt)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*DreamRepositoryTest*" --no-daemon
```

Expected: 编译失败（`Unresolved reference: DreamRepository`）。

- [ ] **Step 3: 写领域模型**

`domain/Dream.kt`、`domain/Tag.kt` 与 `domain/MediaAsset.kt` 按 **Interfaces** 里的字段逐字写（纯 data class，无 Android import）。

> `MediaAsset` 是为了让详情页「素材」tab 不必直接摸 `MediaAssetEntity`——UI 只认 `domain` 类型，这条边界从 Task 5 就开始守。

- [ ] **Step 4: 写仓库**

`data/DreamRepository.kt` 的关键实现（**`save()` 不篡改时间戳、`search()` 的 LIKE 转义与求交**是这段代码的全部价值）：

```kotlin
package com.dreamkeeper.data

import com.dreamkeeper.data.db.*
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.domain.MediaAsset
import com.dreamkeeper.domain.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth

class DreamRepository(
    private val db: DreamDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dreams = db.dreamDao()
    private val tags = db.tagDao()
    private val assets = db.mediaAssetDao()

    fun observeMonth(month: YearMonth): Flow<List<Dream>> {
        val from = month.atDay(1).toString()
        val to = month.atEndOfMonth().toString()
        return dreams.observeByMonth(from, to).map { list -> list.map(::toDomain) }
    }

    /**
     * 当日列表（D35）。`date.toString()` 就是 `yyyy-MM-dd`，与 `dreamDate` 的存法一致；
     * 不要在这里做 `atStartOfDay()` 之类的时间戳换算——`dreamDate` 是 LocalDate，不是 Instant。
     */
    fun observeDay(date: LocalDate): Flow<List<Dream>> =
        dreams.observeDay(date.toString()).map { list -> list.map(::toDomain) }

    fun observeAll(): Flow<List<Dream>> = dreams.observeAll().map { list -> list.map(::toDomain) }
    fun observe(id: String): Flow<Dream?> = dreams.observeById(id).map { it?.let(::toDomain) }
    fun observeTags(): Flow<List<Tag>> = tags.observeAll().map { list -> list.map(::tagToDomain) }

    /** 详情页标签 tab：联 dream_tags，绝不能读"Dream 上的 tags"（那个字段不存在也不该存在） */
    fun observeTagsForDream(dreamId: String): Flow<List<Tag>> =
        db.dreamTagDao().observeTagsForDream(dreamId).map { list -> list.map(::tagToDomain) }

    /** 打标 / 合并标签之后的重新关联：先清空这条梦的关联、再整体写入（幂等，可重放） */
    suspend fun attachTags(dreamId: String, tagIds: List<String>) {
        db.dreamTagDao().deleteByDream(dreamId)
        db.dreamTagDao().upsertAll(tagIds.distinct().map { DreamTagEntity(dreamId, it) })
    }

    /** 详情页素材 tab：Plan 1 里通常是空表，空态文案由 UI 决定 */
    fun observeMediaAssets(dreamId: String): Flow<List<MediaAsset>> =
        assets.observeByDream(dreamId).map { list -> list.map(::mediaToDomain) }

    /**
     * 写入一条梦。
     *
     * 两条不许动的规则（都是"静默改数据"的典型）：
     * 1. `createdAt` 是**首次录入时间**。调用方带了非 0 值就原样保留，只有新建（0L）才用 now。
     *    以前这里每次都写 `clock()`，于是"补录一句 / 改个标点"就把首次录入时间改成此刻——不可逆。
     * 2. `deletedAt` 原样透传。以前构造实体时漏了它，于是任何一次编辑都会把回收站里的梦悄悄捞回来。
     */
    suspend fun save(dream: Dream) {
        val now = clock()
        dreams.upsert(
            DreamEntity(
                id = dream.id, ownerId = "local", dreamDate = dream.date,
                createdAt = dream.createdAt.takeIf { it > 0L } ?: now,
                title = dream.title,
                rawTranscript = dream.rawTranscript, faithfulText = dream.faithfulText,
                novelText = dream.novelText, novelVersion = dream.novelVersion,
                novelChangeLog = dream.novelChangeLog,
                mood = dream.mood, isDraft = dream.isDraft,
                deletedAt = dream.deletedAt,
                updatedAt = now,
                rawAudioPath = dream.rawAudioPath, rawAudioMs = dream.rawAudioMs,
                transcriptStatus = dream.transcriptStatus,
            )
        )
    }

    /**
     * 用户输入 -> LIKE 查询：按空白切词，逐词查 4 列，按 id 求交集。
     *
     * 顺序即语义：第一个词的结果决定排序（`dreamDate DESC, createdAt DESC`），
     * 后面的词只做过滤。所以"电梯 六楼"返回的仍是按日期排的列表，不会因为求交而乱序。
     */
    suspend fun search(rawQuery: String): List<Dream> {
        val terms = rawQuery.trim().split(Regex("\\s+"))
            .map { normalizeTerm(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (terms.isEmpty()) return emptyList()

        var hits: List<Dream>? = null
        for (term in terms) {
            val matched = dreams.searchTerm(escapeLike(term)).map(::toDomain)
            val previous = hits
            hits = if (previous == null) matched else {
                val ids = matched.mapTo(HashSet()) { it.id }
                previous.filter { it.id in ids }   // 首词保序，后续词只做过滤
            }
        }
        return hits.orEmpty()
    }

    /**
     * 去掉标点与引号：用户搜 `"电梯"！` 时，引号和感叹号是输入习惯，不是要匹配的字符。
     * 但 `%` 与 `_` **必须留着**——它们是 LIKE 的通配符，要交给 [escapeLike] 转义，
     * 否则「转义测试」会因为输入被提前洗干净而永远测不到转义。
     */
    private fun normalizeTerm(raw: String): String =
        raw.trim().replace(Regex("[\\p{P}\\p{S}]")) { m ->
            if (m.value == "%" || m.value == "_") m.value else ""
        }

    /** `\` `%` `_` 在 LIKE 里都有特殊含义：先转义，再拼进 `'%'||:term||'%'`（SQL 侧配 `ESCAPE '\'`） */
    private fun escapeLike(term: String): String = term
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private fun toDomain(e: DreamEntity) = Dream(
        id = e.id, date = e.dreamDate, title = e.title, rawTranscript = e.rawTranscript,
        faithfulText = e.faithfulText, novelText = e.novelText,
        novelChangeLog = e.novelChangeLog, mood = e.mood, isDraft = e.isDraft,
        novelVersion = e.novelVersion, createdAt = e.createdAt, updatedAt = e.updatedAt,
        deletedAt = e.deletedAt, transcriptStatus = e.transcriptStatus,
        rawAudioPath = e.rawAudioPath, rawAudioMs = e.rawAudioMs,
    )

    private fun tagToDomain(e: TagEntity) = Tag(e.id, e.name, e.kind, e.source, e.evidenceText)

    private fun mediaToDomain(e: MediaAssetEntity) = MediaAsset(
        id = e.id, dreamId = e.dreamId, type = e.type, path = e.path,
        prompt = e.prompt, model = e.model, status = e.status, createdAt = e.createdAt,
    )
}
```

`di/AppContainer.kt`：

```kotlin
package com.dreamkeeper.di

import android.content.Context
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.ui.library.LayoutPrefs
import com.dreamkeeper.ui.library.SharedPreferencesLayoutPrefs

/**
 * 组合根：这里是唯一"知道所有实现"的地方，UI 只拿接口。
 * `layoutPrefs` 放在这里（而不是让界面自己 new）是为了测试能整体替换，
 * 也避免视图层直接摸 `SharedPreferences`。
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: DreamDatabase by lazy { DreamDatabase.create(appContext) }
    val repository: DreamRepository by lazy { DreamRepository(database) }
    val layoutPrefs: LayoutPrefs by lazy { SharedPreferencesLayoutPrefs(appContext) }
}
```

`DreamKeeperApp.kt` 改为（建容器 + 种子注入）：

```kotlin
package com.dreamkeeper

import android.app.Application
import com.dreamkeeper.data.db.SeedData
import com.dreamkeeper.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DreamKeeperApp : Application() {
    lateinit var container: AppContainer
        private set

    /** 应用级作用域：只给「启动后补一刀」的活（种子注入）用，不承载界面状态 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 种子注入：开关在 build.gradle.kts 的 buildConfigField；
        // 幂等判断（count() == 0）在 seedIfEmpty 内部，这里不要再写一遍
        if (BuildConfig.SEED_DEMO_DATA) {
            appScope.launch { SeedData.seedIfEmpty(container.database) }
        }
    }
}
```

> 种子是异步写的：冷启动第一帧可能还没插完。验收截图前多等 1–2 秒，或用 `adb shell pm clear` 后重跑（Task 7 的验收会用到这条）。

- [ ] **Step 5: 跑测试确认通过**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*DreamRepositoryTest*" --no-daemon
```

Expected: `BUILD SUCCESSFUL`，9 个测试通过（尤其 `search treats percent as a literal character`、`save preserves createdAt across updates` 必须过——它们盯的是两类会被后续 Plan 悄悄踩回来的坑：通配符注入、时间戳被改写）。

- [ ] **Step 6: 提交**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(data): 仓库层、LIKE 搜索转义与种子注入"
git rev-parse --short HEAD
```

- [ ] **Step 7: 归档证据**

按 §2 写 `docs/evidence/plan1/task5.md`：short SHA、`DreamRepositoryTest` 的 9 个测试通过输出、`Dream`/`Tag`/`MediaAsset` 三个领域类的字段清单（一句话核对与 Task 4 实体一致）、复现步骤。然后 `git add docs/evidence/plan1; git commit -m "docs(evidence): task5 证据归档"`。

---

## Task 6: 日历首屏（梦匣真正的脸）

**Files:**
- Create: `.../ui/calendar/CalendarViewModel.kt`, `.../ui/calendar/CalendarScreen.kt`, `.../ui/day/DayViewModel.kt`, `.../ui/day/DayScreen.kt`, `.../ui/DreamKeeperRoot.kt`, `.../ui/nav/DreamRoute.kt`, `.../ui/theme/Night.kt`, `.../ui/record/RecordUiState.kt`
- Modify: `.../MainActivity.kt`
- Test: `android/app/src/test/java/com/dreamkeeper/ui/calendar/CalendarViewModelTest.kt`、`android/app/src/test/java/com/dreamkeeper/ui/day/DayViewModelTest.kt`

**Interfaces:**
- Consumes: `DreamRepository`、`DreamCalendar.monthGrid`、`Dream`。
- Produces:
  - `data class CalendarUiState(val month: YearMonth, val grid: List<LocalDate?>, val daysWithDreams: Set<LocalDate>, val title: String)`
  - `class CalendarViewModel(repo: DreamRepository, initial: YearMonth) : ViewModel()`，方法 `fun nextMonth()`、`fun prevMonth()`、`fun goToday()`，暴露 `val state: StateFlow<CalendarUiState>`
  - `@Composable fun CalendarScreen(vm: CalendarViewModel, onDayClick: (LocalDate) -> Unit, onSearch: () -> Unit, onRecord: () -> Unit)`（`onSearch` 本轮不实现，见 D39）
  - `data class DayUiState(val date: LocalDate, val title: String, val items: List<DayItem>)`、`data class DayItem(val dream: Dream, val tags: List<Tag>)`
  - `class DayViewModel(repo: DreamRepository, date: LocalDate) : ViewModel()`，暴露 `val state: StateFlow<DayUiState>`
  - `@Composable fun DayScreen(vm: DayViewModel, onOpen: (String) -> Unit, onBack: () -> Unit)`
  - `@Composable fun DreamKeeperRoot(repo: DreamRepository)` —— 根界面是**真路由**：`NavHost(startDestination = DreamRoute.Calendar.route)`，底部栏只在「日历 / 全部」两个路由显示
  - `sealed interface DreamRoute { data object Calendar; data object Library; data class Day(dateIso: String); data class Detail(dreamId: String); data object Search; data object Tags; data class Record(entry: RecordEntry); data class FollowUp(dreamId: String) }`
  - `enum class RecordEntry { HOME_BUTTON, LOCK_TILE, NOTIFICATION, SHORTCUT }`（说梦的四个入口；Plan 1 只用到 `HOME_BUTTON`）
  - `data class RecordUiState(val elapsedMs: Long = 0, val paused: Boolean = false, val amplitude: Float = 0f)`（Plan 2 填充）
  - `data class FollowUpUiState(val remainingMs: Int = 30_000, val recording: Boolean = false, val visible: Boolean = false)`（Plan 2/4 填充）
  - `ui/theme/Night.kt` 的 `NightVoid` / `NightScrim` / `DreamMotion`

> **路由不是"以后再说"**：`Day` / `Record` / `FollowUp` / `Detail` / `Search` / `Tags` 六个路由在 Plan 1 就要存在（可以为空屏或占位）。Plan 2 加录音、Plan 4 接通知，都只是往既有路由里填内容；等到那时再从 `when (tab)` 改路由，等于把根界面重写一遍。
> **导航依赖已经落地，本任务不再有「加依赖」这一步**（D34）：`androidx.navigation:navigation-compose:2.9.8` 已由 Lead 手工补进 `android/gradle/libs.versions.toml` 与 `app/build.gradle.kts`，并验证过编译。版本只有一处权威口径——`libs.versions.toml` 的 `navigation-compose` 条目；**不要**在这份计划里再抄版本号，也不要重复加依赖。Plan 1 的 `onRecord` 仍可为空实现，但**路由必须已经能跳**。

- [ ] **Step 1: 写 ViewModel 的失败测试**

```kotlin
package com.dreamkeeper.ui.calendar

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.domain.Dream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DreamDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = DreamRepository(db)
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    // ↓ 下面每个用例里的这一行都不能删（D-实现写法-1）：
    //   backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    //   原因见代码块后面的说明。

    @Test fun `starts on the given month and shows dots only for that month`() = runTest(dispatcher) {
        val vm = CalendarViewModel(repo, YearMonth.of(2026, 10))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals("2026年10月", vm.state.value.title)
        assertEquals(31, vm.state.value.grid.filterNotNull().size)
    }

    @Test fun `next and prev month change the title`() = runTest(dispatcher) {
        val vm = CalendarViewModel(repo, YearMonth.of(2026, 12))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        vm.nextMonth(); advanceUntilIdle()
        assertEquals("2027年1月", vm.state.value.title)
        vm.prevMonth(); vm.prevMonth(); advanceUntilIdle()
        assertEquals("2026年11月", vm.state.value.title)
    }

    @Test fun `goToday returns to the current month`() = runTest(dispatcher) {
        val vm = CalendarViewModel(repo, YearMonth.of(2020, 1))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        vm.goToday(); advanceUntilIdle()
        assertEquals(YearMonth.now(), vm.state.value.month)
    }

    @Test fun `day with a dream is exposed in daysWithDreams`() = runTest(dispatcher) {
        repo.save(Dream("1", LocalDate.of(2026, 10, 15), null, "电梯", "电梯", null, null, null, false))
        val vm = CalendarViewModel(repo, YearMonth.of(2026, 10))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        assertTrue(LocalDate.of(2026, 10, 15) in vm.state.value.daysWithDreams)
        assertFalse(LocalDate.of(2026, 10, 16) in vm.state.value.daysWithDreams)
    }
```

> **为什么必须有 `backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }` 这一行**：生产代码的 `state` 是 `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)` —— `WhileSubscribed` 的意思是「**有人订阅**才启动上游」。测试里只 `advanceUntilIdle()` 然后读 `vm.state.value`，**从来没有人订阅**，上游那串 `repo.observeMonth()` 根本不会启动，`state.value` 永远是 `initialValue`。于是用例会红在「`daysWithDreams` 是空集 / 标题还是初始月」这种地方，看起来像业务逻辑写错，实际是没订阅。
>
> 修法有两条，**只许选第一条**：① 生产代码保留 `WhileSubscribed`，测试里挂一个后台收集者（上面这行 `backgroundScope` + `UnconfinedTestDispatcher`，后者保证收集立刻生效、不用再 `advance`）；② 把生产代码改成 `SharingStarted.Eagerly` —— **禁止**，那是为了测试变绿而改生产语义，`WhileSubscribed` 存在的意义就是「界面不可见时不订阅数据库」。
>
> 同理，**不要**把断言删掉或改成 `assertTrue(true)` 让它变绿。这一行是测试的脚手架，不是被测行为。

- [ ] **Step 1b: 写当日列表的失败测试**

`android/app/src/test/java/com/dreamkeeper/ui/day/DayViewModelTest.kt`（一个用例就够：这一层只做"过滤 + 排序 + 标题"，过滤和排序的正确性主要压在 `DreamDaoTest` 的 `observeDay` 两条上）：

```kotlin
package com.dreamkeeper.ui.day

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.domain.Dream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DreamDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = DreamRepository(db)
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test fun `shows only that day and formats the title in chinese`() = runTest(dispatcher) {
        repo.save(Dream("1", LocalDate.of(2026, 11, 8), "电梯", "电梯里有人按了六楼", "电梯里有人按了六楼", null, null, null, false))
        repo.save(Dream("2", LocalDate.of(2026, 11, 9), null, "第二天", "第二天", null, null, null, false))
        val vm = DayViewModel(repo, LocalDate.of(2026, 11, 8))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(listOf("1"), vm.state.value.items.map { it.dream.id })
        assertEquals("11月8日 周日", vm.state.value.title)
    }
}
```

> 2026-11-08 是周日（2026-11-01 是周日），标题里的「周日」不是随手写的——`titleOf()` 用 `"一二三四五六日"[date.dayOfWeek.value - 1]` 取单字，周日是 `value = 7` → 下标 6。

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*CalendarViewModelTest*" --no-daemon
```

Expected: 编译失败（`Unresolved reference: CalendarViewModel`）。

- [ ] **Step 3: 写 ViewModel**

要点：月份状态用 `MutableStateFlow<YearMonth>`，与仓库 `flatMapLatest` 组合；标题格式 `"${year}年${monthValue}月"`。**`WhileSubscribed(5_000)` 是刻意的，不要为了测试方便改成 `Eagerly`**（理由见 Step 1 的说明）。

```kotlin
package com.dreamkeeper.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.domain.DreamCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class CalendarUiState(
    val month: YearMonth,
    val title: String,
    val grid: List<LocalDate?>,
    val daysWithDreams: Set<LocalDate>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val repo: DreamRepository,
    initialMonth: YearMonth = YearMonth.now(),
) : ViewModel() {

    private val month = MutableStateFlow(initialMonth)

    val state: StateFlow<CalendarUiState> = month
        .flatMapLatest { m ->
            repo.observeMonth(m).map { dreams ->
                CalendarUiState(
                    month = m,
                    title = "${m.year}年${m.monthValue}月",
                    grid = DreamCalendar.monthGrid(m),
                    daysWithDreams = dreams.map { it.date }.toSet(),
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CalendarUiState(
                month = initialMonth,
                title = "${initialMonth.year}年${initialMonth.monthValue}月",
                grid = DreamCalendar.monthGrid(initialMonth),
                daysWithDreams = emptySet(),
            ),
        )

    fun nextMonth() { month.value = month.value.plusMonths(1) }
    fun prevMonth() { month.value = month.value.minusMonths(1) }
    fun goToday() { month.value = YearMonth.now() }
}
```

- [ ] **Step 3b: 写当日列表的 ViewModel**

`ui/day/DayViewModel.kt`。这一层只做三件事：过滤（仓库的 `observeDay` 已经做了）、拼标题、把每条梦的标签并上来。

```kotlin
package com.dreamkeeper.ui.day

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.domain.Dream
import com.dreamkeeper.domain.Tag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.time.LocalDate

data class DayItem(val dream: Dream, val tags: List<Tag>)

data class DayUiState(
    val date: LocalDate,
    val title: String,
    val items: List<DayItem> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModel(
    private val repo: DreamRepository,
    private val date: LocalDate,
) : ViewModel() {

    val state: StateFlow<DayUiState> = repo.observeDay(date)
        .flatMapLatest { dreams ->
            if (dreams.isEmpty()) {
                // combine() 不接受空列表，必须走这个分支
                flowOf(DayUiState(date, titleOf(date), emptyList()))
            } else {
                combine(dreams.map { repo.observeTagsForDream(it.id) }) { tagLists ->
                    DayUiState(
                        date = date,
                        title = titleOf(date),
                        items = dreams.mapIndexed { i, d -> DayItem(d, tagLists[i]) },
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DayUiState(date, titleOf(date)),
        )

    companion object {
        /** 「11月8日 周日」——单字周几，和日历页的极简文案一致 */
        fun titleOf(date: LocalDate): String =
            "${date.monthValue}月${date.dayOfMonth}日 周${"一二三四五六日"[date.dayOfWeek.value - 1]}"
    }
}
```

> **标签为什么是逐条订阅而不是一条 SQL 联查**：D35 只裁定加 `observeDay`，没有裁定加「带标签的当日查询」。这一天最多几条梦，`combine` 几个 flow 完全够用；**不要**为了少一次订阅而在 Plan 1 里新增 DAO 查询——那会让 Task 4 的 schema 与测试口径跟着变。等标签量真的上来了再优化。

- [ ] **Step 4: 跑测试确认通过**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*CalendarViewModelTest*" --tests "*DayViewModelTest*" --no-daemon
```

Expected: `BUILD SUCCESSFUL`，5 个测试通过（日历 4 条 + 当日列表 1 条）。

- [ ] **Step 5: 写日历界面 + 真路由**

`ui/theme/Night.kt`（**只放夜间界面要用的 token，不要在这里重定义色板**）：

```kotlin
package com.dreamkeeper.ui.theme

import androidx.compose.ui.graphics.Color

/** 录音中 / 追问全屏页的底：比 Night 更深一档，让全屏页与首页有层级差 */
val NightVoid = Color(0xFF05070A)
/** 全屏页之上盖内容时的遮罩（80% 不透明的 NightVoid） */
val NightScrim = Color(0xCC05070A)

/** 动画时长口径：夜间不做长动画，超过 200ms 的过渡一律砍掉 */
object DreamMotion {
    const val Quick = 120      // 月份切换、tab 内容替换
    const val Standard = 200   // 上下限
}
```

`ui/record/RecordUiState.kt`（Plan 2 填内容，Plan 1 只定义类型——先把状态形状定下来，界面才好写）：

```kotlin
package com.dreamkeeper.ui.record

/** 录音页状态（Plan 2 填充：计时、暂停、振幅） */
data class RecordUiState(
    val elapsedMs: Long = 0,
    val paused: Boolean = false,
    val amplitude: Float = 0f,
)

/** 追问窗口状态（Plan 2 录音 + Plan 4 通知栏入口共用；remainingMs 走完 = 追问超时） */
data class FollowUpUiState(
    val remainingMs: Int = 30_000,
    val recording: Boolean = false,
    val visible: Boolean = false,
)
```

`ui/nav/DreamRoute.kt`（**根界面的唯一导航真源**，Plan 2/4 只往里填内容，不再改结构）：

```kotlin
package com.dreamkeeper.ui.nav

/** 说梦的四个入口：首页大钮 / 锁屏小组件 / 通知 / 快捷方式（Plan 1 只用到 HOME_BUTTON） */
enum class RecordEntry { HOME_BUTTON, LOCK_TILE, NOTIFICATION, SHORTCUT }

sealed interface DreamRoute {
    val route: String

    data object Calendar : DreamRoute { override val route = "calendar" }
    data object Library : DreamRoute { override val route = "library" }

    /**
     * 当日列表（设计 §11 的中间层，D35）：`iso` 形如 `2026-11-08`。
     * 参数就存在 route 里、不放进 nav arguments 的 Bundle 里也没关系——
     * `composable("day/{dateIso}")` 从 `arguments` 取到的就是这个字符串，解析时用 `LocalDate.parse`。
     */
    data class Day(val dateIso: String) : DreamRoute { override val route = "day/$dateIso" }

    data object Search : DreamRoute { override val route = "search" }
    data object Tags : DreamRoute { override val route = "tags" }
    data class Detail(val dreamId: String) : DreamRoute { override val route = "detail/$dreamId" }
    data class Record(val entry: RecordEntry) : DreamRoute { override val route = "record/${entry.name}" }
    data class FollowUp(val dreamId: String) : DreamRoute { override val route = "followup/$dreamId" }
}
```

`ui/calendar/CalendarScreen.kt` 的结构要求（实现细节自由，但这些必须做到）：

- 顶部：左箭头 `‹`、中间 `state.title`、右箭头 `›`；标题下面一行小字「共 N 天有梦」。
- 星期表头：一 二 三 四 五 六 日，`labelSmall` + `InkFaint`。
- 7 列网格：有梦的日子在数字下方画一个 4dp 的 `Ember` 圆点；**今天用 1.5dp `Ember` 描边圈住**（不要用 `Line`——`Line` 在 Night 上只有 1.34:1，等于看不见，它只配做装饰分隔线）；空白格不画。
- 点有梦的格子 → `onDayClick(date)`；点没梦的格子 → 什么都不发生（不弹"添加"——添加只有一个入口，就是红钮）。
- **本轮不做搜索入口**（D39）：设计 §11 要求搜索固定在日历顶部，但 Task 6 还没有搜索页。Task 8 再往这个顶栏加搜索图标（命中区 ≥48dp）并接到 `Search` 路由。**现在不要放一个点了没反应的假放大镜**——那是"看起来坏了"，比没有更糟。
- 整屏可上下滑动；网格的 `verticalScroll` 必须带 `contentPadding = PaddingValues(bottom = 112.dp)`（72 大钮 + 24 底距 + 16 呼吸），否则最后一行会被大钮压住。
- 底部：一大颗圆钮，文案「说梦」，`onRecord()` 现在先留空实现，Plan 2 接管：
  ```kotlin
  Box(
      modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 24.dp)
          .size(72.dp)
          .border(1.5.dp, Ember, CircleShape)   // 外环：EmberDeep 只有 2.57:1，靠 Ember 边界把按钮"描"出来
          .background(EmberDeep, CircleShape)
          .clickable { onRecord() },
      contentAlignment = Alignment.Center,
  ) { Text("说梦", color = Paper, style = MaterialTheme.typography.titleMedium) }
  ```
  **不要在这里再叠 `navigationBarsPadding()`**：root 的 `Scaffold` 已经把底栏和导航栏 inset 算进 `innerPadding`，再叠一次就是双重避让，按钮会浮在半空。
- 月份切换用 `AnimatedContent`，`transitionSpec` 用 `tween(DreamMotion.Quick)`——**不许出现裸的魔法数字**（120 只许在 `Night.kt` 里出现一次）。
- 屏幕文案只留四个：月份标题、`共 N 天有梦`、星期表头、`说梦`。**不加任何说明文字**（没有"点红点查看""长按录音"之类的提示）。这条验收在 Task 8 加搜索图标后仍然成立——图标不是文字。
- 屏幕常亮：`Modifier` 层面不加；常亮在 Plan 4 统一处理。

`ui/DreamKeeperRoot.kt`（`NavHost` + 只在两个主路由显示底部栏）：

```kotlin
package com.dreamkeeper.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.ui.calendar.CalendarScreen
import com.dreamkeeper.ui.calendar.CalendarViewModel
import com.dreamkeeper.ui.nav.DreamRoute
import com.dreamkeeper.ui.theme.Night

@Composable
fun DreamKeeperRoot(repo: DreamRepository) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val showBottomBar = current == DreamRoute.Calendar.route || current == DreamRoute.Library.route

    Scaffold(
        containerColor = Night,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = Night) {
                    NavigationBarItem(
                        selected = current == DreamRoute.Calendar.route,
                        onClick = { nav.navigate(DreamRoute.Calendar.route) { launchSingleTop = true } },
                        icon = {}, label = { Text("日历") },
                    )
                    NavigationBarItem(
                        selected = current == DreamRoute.Library.route,
                        onClick = { nav.navigate(DreamRoute.Library.route) { launchSingleTop = true } },
                        icon = {}, label = { Text("全部") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = DreamRoute.Calendar.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(DreamRoute.Calendar.route) {
                val vm: CalendarViewModel = viewModel { CalendarViewModel(repo) }
                CalendarScreen(
                    vm = vm,
                    // 点格子进「当日列表」，**不是**进 Detail（D35）：
                    // 把 date.toString() 当 dreamId 传给详情，详情按 id 查库必然查不到 → 必空白。
                    onDayClick = { date -> nav.navigate(DreamRoute.Day(date.toString()).route) },
                    onSearch = { /* Task 8：nav.navigate(DreamRoute.Search.route) */ },
                    onRecord = { /* Plan 2：这里换成 nav.navigate(DreamRoute.Record(RecordEntry.HOME_BUTTON).route) */ },
                )
            }
            composable("day/{dateIso}") { backStackEntry ->
                val iso = backStackEntry.arguments?.getString("dateIso")!!.also { require(it.isNotBlank()) }
                val date = LocalDate.parse(iso)   // 格式错了就直接炸，不要兜底成"今天"——静默兜底会掩盖路由参数写错
                val vm: DayViewModel = viewModel { DayViewModel(repo, date) }
                DayScreen(
                    vm = vm,
                    onOpen = { dreamId -> nav.navigate(DreamRoute.Detail(dreamId).route) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(DreamRoute.Library.route) { /* Task 7：LibraryScreen(...) */ }
            // 下面五个路由 Plan 1 允许是空屏/占位——重点是**路径已经存在**，
            // Plan 2 的录音与 Plan 4 的通知只往里填内容，不会再动根界面结构。
            composable(DreamRoute.Search.route) { /* Task 8 */ }
            composable(DreamRoute.Tags.route) { /* Task 9 */ }
            composable("detail/{dreamId}") { /* Task 8 */ }
            composable("record/{entry}") { /* Plan 2：RecordScreen */ }
            composable("followup/{dreamId}") { /* Plan 2：FollowUpScreen */ }
        }
    }
}
```

> 路由参数用 `navBackStackEntry.arguments?.getString("dreamId")` 取；`DreamRoute.Detail(...).route` 只负责拼路径，解析在各自的 `composable` 块里做。

`ui/day/DayScreen.kt` 的结构要求（设计 §11 的第二层；D35）：

- 顶部：左箭头 `‹` 返回（`onBack`）、中间 `state.title`（如「11月8日 周日」）。**没有月份切换**——这里是一天，不是一个月。
- 列表：`LazyColumn`，每条梦一张卡片，卡片内容三行：
  1. 标题：`dream.title`；无标题时用正文首句（种子里的「楼梯」那条就是无标题，必须被覆盖到）。
  2. 正文首句：`firstSentence(dream.rawTranscript)`（取到第一个 `。`/`！`/`？`/`；` 或满 40 字截断，末尾补 `…`）。抽成一个纯函数放在本文件顶层，**Task 7 的行组件直接复用**。
  3. 标签：`item.tags` 逐个画小 chip（`InkDim` 文字 + `NightRaised` 底）；没有标签就不画这一行、也不留空白。
- 卡片：圆角 12dp、`NightRaised` 底、内边距 16dp、卡片间距 12dp，整张卡可点 → `onOpen(dream.id)`。
- 空态：居中两行——`InkDim` 文案「这天没有梦」+ 更小的副标题「回日历页看看别的日子」。**不要箭头**（理由同 Task 7 的空态）。
- 底部：**没有**说梦钮，也**没有**底部栏（`showBottomBar` 只在 Calendar/Library 为真）。
- 文案只留：日期标题、卡片里的标题/首句/标签、「这天没有梦」。不加"共 N 条梦"之类——一天几条梦，数卡片比读数字快。

> **Task 7 的行组件可复用 `DayScreen` 的卡片**：两处都是「标题 / 正文首句 / 标签」，样式完全一致。Task 7 允许直接把卡片抽成 `@Composable fun DreamCard(dream, tags, onClick)` 放到 `ui/components/`，DayScreen 改为调用它；如果 Task 6 先写完卡片，Task 7 只做"把卡片拔出来"这一件事，**不要写第二份长得一样的卡片**。

> `MainActivity` 仍只做一件事：`setContent { DreamKeeperTheme { DreamKeeperRoot(container.repository) } }`。**Plan 1 不解析 intent extra**——从通知进录音是 Plan 4 的活，现在读 extra 只会读到 null 然后写一堆死分支。

- [ ] **Step 6: 接进 MainActivity 并截图验收**

构建与安装还是手工命令（脚本不管安装），**截图与冷启动一律走脚本**：

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon
cd D:\DreamKeeper
& $adb logcat -c
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am force-stop com.dreamkeeper

# ① 冷启动 ×3（脚本内部起 Activity、抓 logcat、判 TotalTime/Displayed/崩溃，红了 exit 1）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-logcat.ps1 -Runs 3 -OutFile docs\evidence\plan1\task6-logcat.txt

# ② 日历首屏截图（脚本内部走 cmd.exe 二进制安全重定向 + 魔数/IHDR/IEND 校验）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-task6-a.png
```

Expected：
- `capture-logcat.ps1` 末行 `RESULT: OK`；`STARTUP: runs=3 … max=…ms` 里 **max ≤ 2000**；`LAUNCH: Displayed=3`；`CRASHES: 0`。
- `plan1-task6-a.png` 的 `RESULT: OK … dims=1080x2400 sha256=…`；图上是当月标题、星期表头、网格、底部「说梦」钮。
- 种子 4 条（Task 4 的 `SeedData`，**相对当月**）让当月 **4 个有梦日**＝`1/8/15/22` 号（`SeedData.DAY_ELEVATOR/DAY_CLASSROOM/DAY_OLD_HOUSE/DAY_STAIRS`），标题行是「**共 4 天有梦**」。**不是 3 天、也不是 5 天**——4 条种子同月是硬约束（D36），这条断言就是它的守门人。
- 点右箭头后再截一张 `plan1-task6-b.png`，标题变成下个月，**且网格行数随之变化**（有的月份 5 行、有的 6 行）。
- 点有梦的格子（当月 8 号）进当日列表，再截一张 `plan1-task6-c.png`：标题「X月8日 周X」、**一张**卡片「教室」（正文首句「教室最后一排…」）、没有标签行（教室那条没打标签）；左上角返回箭头能回到日历且**月份不丢**。

```powershell
& $adb shell input tap 950 250   # 右箭头大致位置，按实际截图调整
Start-Sleep 1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-task6-b.png

# 回到当月，点 8 号那个格子（坐标按 a 图实际位置调整）——这一张验的是 D35 那一层
& $adb shell input tap 950 250   # 假设"再点一次"回到当月，实际按截图调整
& $adb shell input tap 300 700   # 8 号格子
Start-Sleep 1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-task6-c.png

# ③ 近白像素闸门：三张图一起过，判据 nearWhite 必须 = 0
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-near-white.ps1 docs\evidence\plan1\plan1-task6-*.png -TopOffset 100 -MaxRatio 0
```

Expected：`check-near-white.ps1` 对三张图各自打印 `RESULT: OK … nearWhite=0 …`，退出码 0。**任何一张 `nearWhite>0` 都是深夜体验破了**（`-MaxRatio 0` 就是不许有例外，别的任务用的是脚本默认 1%，这里更严）。

**三张图都要 read_image 确认**，不能只看安装成功——写下"看到了几个红点、标题是几月、当日列表有几张卡片"。

- [ ] **Step 7: 提交**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(ui): 日历首屏（月份切换、有梦红点、说梦大钮）"
git rev-parse --short HEAD
```

- [ ] **Step 8: 归档证据**

按 §2 写 `docs/evidence/plan1/task6.md`：short SHA、APK 与三张截图（`-a` 日历 / `-b` 下个月 / `-c` 当日列表）的 SHA256、三张图各自的人工读图结论（写下"看到了几个红点、标题是几月、网格几行、当日列表几张卡片"，不是"看起来正常"）、`task6-logcat.txt` 的三个数（max TotalTime / Displayed / CRASHES）、`check-near-white.ps1` 的三行 `nearWhite=0`、复现步骤（含点右箭头与 8 号格子的坐标）。然后 `git add docs/evidence/plan1; git commit -m "docs(evidence): task6 证据归档"`。

---

## Task 7: 全部梦境列表（时间轴 / 卡片流切换）

**Files:**
- Create: `.../ui/library/LibraryViewModel.kt`, `.../ui/library/LibraryScreen.kt`, `.../ui/library/LayoutPrefs.kt`
- Modify: `.../ui/DreamKeeperRoot.kt`, `.../di/AppContainer.kt`（加 `layoutPrefs`）
- Test: `android/app/src/test/java/com/dreamkeeper/ui/library/LibraryViewModelTest.kt`

**Interfaces:**
- Produces:
  - `enum class LibraryLayout { TIMELINE, CARDS }`
  - `interface LayoutPrefs { var layout: LibraryLayout }`（测试用 `FakeLayoutPrefs`，生产用 `SharedPreferencesLayoutPrefs`）
  - `class SharedPreferencesLayoutPrefs(context: Context) : LayoutPrefs`（`SharedPreferences` 同步读写，**不新增任何依赖**）
  - `data class LibraryUiState(val layout: LibraryLayout, val items: List<Dream>)`
  - `class LibraryViewModel(repo, prefs) : ViewModel()`，方法 `fun setLayout(l: LibraryLayout)`
  - `@Composable fun LibraryScreen(vm: LibraryViewModel, onOpen: (String) -> Unit, onSearch: () -> Unit)`

- [ ] **Step 1: 写失败测试（只测纯逻辑与仓库组合）**

```kotlin
package com.dreamkeeper.ui.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.domain.Dream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: DreamDatabase
    private lateinit var repo: DreamRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DreamDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = DreamRepository(db)
    }
    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test fun `lists dreams newest date first`() = runTest(dispatcher) {
        repo.save(Dream("1", LocalDate.of(2026,10,1), null,"a","a",null,null,null,false))
        repo.save(Dream("2", LocalDate.of(2026,10,20), null,"b","b",null,null,null,false))
        val vm = LibraryViewModel(repo, FakeLayoutPrefs())
        // 必须有这一行（同 Task 6 Step 1 的原因）：state 用 WhileSubscribed 起上游，
        // 没有订阅者时 observeAll() 根本不启动，advanceUntilIdle() 之后 items 还是空表。
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(listOf("2","1"), vm.state.value.items.map { it.id })
    }

    @Test fun `layout choice is persisted`() = runTest(dispatcher) {
        val prefs = FakeLayoutPrefs()
        val vm = LibraryViewModel(repo, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        vm.setLayout(LibraryLayout.CARDS)
        advanceUntilIdle()
        assertEquals(LibraryLayout.CARDS, vm.state.value.layout)
        assertEquals(LibraryLayout.CARDS, prefs.layout)
    }
}
```

`FakeLayoutPrefs` 定义在同文件末尾：`class FakeLayoutPrefs : LayoutPrefs { override var layout = LibraryLayout.TIMELINE }`

- [ ] **Step 2: 跑测试确认失败**

Expected: 编译失败（`Unresolved reference: LibraryViewModel`）。

- [ ] **Step 3: 实现**

**不新增依赖**：布局偏好只有一个枚举值，`SharedPreferences` 就够，同步读写、没有首帧抖动、没有协程与超时。

> 第一轮这里用的是 DataStore + `runBlocking(100ms)` 读首值——为了一个枚举引入一个依赖、再为它的异步读法加一层阻塞超时，是明显的坏味道。回退到 `SharedPreferences`。

再核对 `buildFeatures`：

```kotlin
android {
    buildFeatures {
        compose = true
        // Task 4 已开；AGP 9.5 对 buildConfig 的默认值未验证，而 BuildConfig.DEBUG
        // 与 BuildConfig.SEED_DEMO_DATA 都在用，所以这里必须是显式 true。
        buildConfig = true
    }
}
```

**`LayoutPrefs` 接口 + 生产实现**，一起放在 `ui/library/LayoutPrefs.kt`：

```kotlin
package com.dreamkeeper.ui.library

import android.content.Context

interface LayoutPrefs { var layout: LibraryLayout }

/**
 * 生产实现：`SharedPreferences` 同步读写。
 *
 * 为什么不用 DataStore：`LayoutPrefs.layout` 是同步属性（ViewModel 构造时就要拿到初值），
 * 而 DataStore 的读是 suspend 的——上一版为此在 init 里塞了 `runBlocking(100ms)`，
 * 一个布局偏好不值得让主线程冒这个险。`SharedPreferences` 首次读盘由系统异步预加载，
 * 这里的 `getString` 命中内存缓存，代价可忽略。
 */
class SharedPreferencesLayoutPrefs(context: Context) : LayoutPrefs {

    private val prefs = context.applicationContext
        .getSharedPreferences("layout_prefs", Context.MODE_PRIVATE)

    override var layout: LibraryLayout
        get() = LibraryLayout.entries.firstOrNull { it.name == prefs.getString(KEY_LAYOUT, null) }
            ?: LibraryLayout.TIMELINE
        set(value) {
            // apply() 异步落盘、立刻更新内存值：杀进程重进仍能读到（Task 7 的验收就是盯这个）
            prefs.edit().putString(KEY_LAYOUT, value.name).apply()
        }

    private companion object { const val KEY_LAYOUT = "library_layout" }
}
```

`LibraryViewModel` 组合 `repo.observeAll()` 与一个 `MutableStateFlow<LibraryLayout>`（初值取 `prefs.layout`），`setLayout` 同时写回 `prefs`；`state` 用 `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue)`——**与 Task 6 一致**，所以 Step 1 的测试里同样要有 `backgroundScope` 那一行收集者，否则 `observeAll()` 不会启动。

> `FakeLayoutPrefs` 只出现在测试里（Step 1 已给），不要在生产代码里造第二个实现。

- [ ] **Step 4: 跑测试确认通过**

Expected: 2 个测试通过。

- [ ] **Step 5: 写界面**

要求：
- 顶部一条：标题「全部梦」（`titleLarge`）+ 右侧搜索图标（`onSearch()`）。
- 右侧一个分段控件切 `时间轴 / 卡片`（两段，选中态用 `Ember` 描边，不用填充色块）。
- **时间轴**：左侧一条 1dp `Line` 竖线，每条一个 `Ember` 圆点 + 日期（`labelSmall`，`InkFaint`）+ 标题或首行（`bodyMedium`，`Paper`），条目间距 20dp。
- **卡片流**：圆角 12dp 的 `NightRaised` 卡片，内边距 16dp，日期在上、正文取前两行（`maxLines = 2`，`TextOverflow.Ellipsis`）。
- **复用 Task 6 的卡片**：Task 6 的 `DayScreen` 已经有「标题 / 正文首句 / 标签」的卡片。把它抽成 `ui/components/DreamCard.kt` 的 `@Composable fun DreamCard(dream: Dream, tags: List<Tag>, onClick: () -> Unit)`，两个界面都用这一个。**不要写第二份长得一样的卡片**——两份样式迟早会分叉。
- 空状态：居中一行 `InkDim` 文案「**还没有梦**」+ 下一行更小的副标题「去日历页，按那个橙色的钮」。**不要箭头**（D38）：这个页面没有说梦钮（唯一的说梦钮在日历页），画一支指向不存在的按钮的箭头是自相矛盾；原稿「按下面那个钮」也是错的，下面只有底部栏。
- 点任意条目 → `onOpen(dream.id)`。

- [ ] **Step 6: 装到模拟器截图验收**

数据不用临时造了：Task 4 的 `SeedData` + Task 5 的启动注入已经给了 4 条固定 id 的种子梦（**日期相对当月，1/8/15/22 号**）。这里只需先清一次应用数据，确认「空库 → 注入 4 条」这条路真的通。

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon
cd D:\DreamKeeper
& $adb shell pm clear com.dreamkeeper          # 清库，制造"首次启动"
& $adb logcat -c
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk

# ① 冷启动 ×3（第一次冷启动同时完成"空库 → 注入 4 条"）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-logcat.ps1 -Runs 3 -OutFile docs\evidence\plan1\task7-logcat.txt

# ② 切到"全部"页并截图
& $adb shell am start -n com.dreamkeeper/.MainActivity
Start-Sleep 3
& $adb shell input tap 400 230                   # 底部"全部"入口，按实际截图调整
Start-Sleep 2
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-task7-timeline.png
```

Expected：
- `capture-logcat.ps1`：`RESULT: OK`、max TotalTime ≤ 2000、`LAUNCH: Displayed=3`、`CRASHES: 0`。**第一次冷启动比后两次慢是正常的**（要建库 + 写种子），但 max 仍然不许超 2000。
- `plan1-task7-timeline.png`：**4 条**（不是 3 条——种子在 Task 4 定的是 4 条），左侧竖线与圆点对齐，日期按 **当月 22 / 15 / 8 / 1 号**倒序（`SeedData.DAY_STAIRS/DAY_OLD_HOUSE/DAY_CLASSROOM/DAY_ELEVATOR`）。**不要写死具体某月某日**——种子的月份跟着运行当天走。
- 点分段控件切卡片后 `plan1-task7-cards.png`：卡片出现圆角与内边距，仍 4 条。
- 杀掉进程重进（`am force-stop` 再 `am start`），**仍是卡片视图**（证明偏好已落盘，不是只活在内存里）。
- 再跑一次 `pm clear` + 重启，列表回到 4 条而不是 8 条（幂等，证明 `count() == 0` 判断生效；`seedIfEmpty` 的写入在 `withTransaction` 里，见 D36）。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-task7-cards.png

& $adb shell am force-stop com.dreamkeeper
& $adb shell am start -n com.dreamkeeper/.MainActivity
Start-Sleep 4
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-task7-cards-after-restart.png

# ③ 近白像素闸门：判据 nearWhite 必须 = 0
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-near-white.ps1 docs\evidence\plan1\plan1-task7-*.png -TopOffset 100 -MaxRatio 0
```

Expected：`check-near-white.ps1` 对三张图各自 `nearWhite=0`，退出码 0。

- [ ] **Step 7: 提交**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(ui): 全部梦境列表（时间轴/卡片流切换并记忆）"
git rev-parse --short HEAD
```

- [ ] **Step 8: 归档证据**

按 §2 写 `docs/evidence/plan1/task7.md`：short SHA、`SharedPreferences` 的文件名与键名（`layout_prefs` / `library_layout`）、三张截图路径 + SHA256、人工读图结论（4 条、日期顺序、卡片圆角、重启后仍是卡片）、`task7-logcat.txt`、`pm clear` 复现步骤（含证明幂等的第二次清库结果）。然后 `git add docs/evidence/plan1; git commit -m "docs(evidence): task7 证据归档"`。

---

## Task 8: 详情页 + 搜索（LIKE 中文检索）

**Files:**
- Create: `.../ui/detail/DetailScreen.kt`, `.../ui/detail/DetailViewModel.kt`, `.../ui/search/SearchScreen.kt`, `.../ui/search/SearchViewModel.kt`
- Modify: `.../ui/DreamKeeperRoot.kt`
- Test: `android/app/src/test/java/com/dreamkeeper/ui/detail/DetailViewModelTest.kt`

**Interfaces:**
- Consumes: `DreamRepository.observe(id)`、`DreamRepository.search(q)`、`DreamRepository.observeTagsForDream(dreamId)`（标签 tab 的唯一数据源）。**素材不动数据库**：`MediaAsset` 的读取留到 Plan 3，Plan 1 的 `DetailViewModel` 不订阅 `observeMediaAssets`。
- Produces:
  - `data class DetailUiState(val dream: Dream? = null, val tags: List<Tag> = emptyList(), val assets: List<MediaAsset> = emptyList(), val novelChangeCount: Int = 0)` —— `assets` 只是签名占位，Plan 1 恒为 `emptyList()`
  - `class DetailViewModel(repo: DreamRepository, dreamId: String) : ViewModel()`，暴露 `val state: StateFlow<DetailUiState>`（combine 两条 Flow：`observe(id)` / `observeTagsForDream`，`assets` 固定空表）
  - `@Composable fun DetailScreen(dreamId: String, repo: DreamRepository, onBack: () -> Unit)`
  - `@Composable fun PlaybackBar(audioPath: String?, durationMs: Long?)` —— **Plan 1 传 `null, null`，时长显示 `--:--`**；Plan 2 才接 `MediaPlayer`
  - `fun novelTabLabel(n: Int): String`（纯函数，放 `DetailScreen.kt` 顶层，便于单测）
  - `@Composable fun SearchScreen(repo: DreamRepository, onOpen: (String) -> Unit, onBack: () -> Unit)`
  - `class SearchViewModel(repo: DreamRepository, dispatcher: CoroutineDispatcher = Dispatchers.IO) : ViewModel()`，`fun onQueryChange(q: String)`，`val results: StateFlow<List<Dream>>`（250ms 防抖）

> **`SearchViewModel` 的 `dispatcher` 参数不是可选的装饰**：防抖链上必须 `flowOn(dispatcher)`，而这个 `dispatcher` 必须能从外面换。测试里传 `StandardTestDispatcher(testScheduler)`，`advanceTimeBy(300)` 才推得动那 250ms 的 `debounce`；如果内部写死 `Dispatchers.IO`，测试的虚拟时钟管不到 IO 线程，`advanceTimeBy` 推了个寂寞，用例要么超时要么拿到上一次的结果。

- [ ] **Step 1: 写搜索防抖的失败测试**

```kotlin
@Test fun `typing quickly only runs the last query`() = runTest(dispatcher) {
    repo.save(Dream("1", LocalDate.of(2026,10,1), null, "电梯里有人", "电梯里有人", null,null,null,false))
    repo.save(Dream("2", LocalDate.of(2026,10,2), null, "教室里考试", "教室里考试", null,null,null,false))
    // 关键：把测试调度器交给 ViewModel，虚拟时间才能推动它自己的 debounce（见上面的说明）
    val vm = SearchViewModel(repo, StandardTestDispatcher(testScheduler))
    // results 若是 WhileSubscribed 的 StateFlow，这一行同样不能删（同 Task 6 Step 1）
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.results.collect {} }
    vm.onQueryChange("电")
    vm.onQueryChange("电梯")
    advanceTimeBy(300)      // > 250ms 防抖窗口
    advanceUntilIdle()
    assertEquals(listOf("1"), vm.results.value.map { it.id })
}
```

> 如果实现写成 `debounce(250).flowOn(Dispatchers.IO)` 而**没有**构造参数注入，上面这条用例必红：`advanceTimeBy(300)` 只推进测试调度器的虚拟时间，IO 线程上的 250ms 是真实时间，测试不会等它。这不是"测试写得不对"，是生产代码不可测——修实现，不要修测试。

- [ ] **Step 2: 确认失败 → [ ] **Step 3: 实现（`debounce(250)` + `flatMapLatest` + `flowOn(dispatcher)`，`dispatcher` 由构造参数传入、默认 `Dispatchers.IO`）→ [ ] **Step 4: 确认通过**

  实现骨架（`SearchViewModel`）：

  ```kotlin
  private val query = MutableStateFlow("")

  @OptIn(ExperimentalCoroutinesApi::class)
  val results: StateFlow<List<Dream>> = query
      .debounce(250)                       // 250ms 防抖：手打中文时每个字都会触发 onQueryChange
      .flatMapLatest { q -> repo.search(q.trim()) }
      .flowOn(dispatcher)                  // ← 就是这一行让测试能把 dispatcher 换成 StandardTestDispatcher
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  fun onQueryChange(q: String) { query.value = q }
  ```

  `repo.search("")` 要返回空表（空查询不是"搜全部"），否则进搜索页会先闪一下全部 4 条。


- [ ] **Step 5: 写详情页**

必须做到：

- 顶部返回箭头 + 居中日期（如「10月29日 周三」）。
- **四个 tab**（设计 §11 原文就是四个）：**原样** / **小说化**（有补写时带角标） / **标签** / **素材**。
- tab 内容：
  - 原样：`dream.rawTranscript`，`bodyLarge`，**行高 30sp**，段落间距 12dp，可滚动。
  - 小说化：有 `novelText` 就显示正文；没有就显示占位文案「还没有生成。等接上大模型后，这里会有一篇读得通的版本。」
  - 标签：**不能读 `dream.tags`（`Dream` 没有这个字段，也不该有）**，必须走仓库的联表查询：`DetailViewModel.state.tags`（数据源是 `DreamTagDao.observeTagsForDream` 的 `tags INNER JOIN dream_tags`）；空表显示「暂无标签」。每个标签一个只读胶囊（Plan 1 不在详情页增删标签）。
  - 素材：**纯 UI 占位，不接 DAO、不接播放器**。一个 16:9 的空槽 + 一行说明：
    ```kotlin
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(NightRaised, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) { Text("以后可以在这里把梦做成视频", color = InkDim, style = MaterialTheme.typography.bodyMedium) }
    ```
    `DetailUiState.assets` 只是签名占位（Plan 3 用），Plan 1 **恒为空表、不订阅 `observeMediaAssets`、一个也不渲染**——种子里那条 `VIDEO` 记录只是给数据库留的，不显示。
- tab 标题里的 N 是改动处数，来自 `novelChangeLog`。用**纯函数**算，才能不启动模拟器就测：

```kotlin
/** 小说化 tab 的标题：「小说化」或「小说化 · 补写 6 处」 */
fun novelTabLabel(n: Int): String = if (n == 0) "小说化" else "小说化 · 补写 $n 处"

/** 从 novelChangeLog 里数「补写 N 处」；数不出来就按 0 处理（老数据/没生成过） */
fun novelChangeCount(changeLog: String?): Int = changeLog
    ?.let { Regex("补写\\s*(\\d+)\\s*处").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    ?: 0
```

  `DetailViewModel` 里 `novelChangeCount = novelChangeCount(entity.novelChangeLog)`，界面直接 `Text(novelTabLabel(state.novelChangeCount))`。

> **「补写 N 处」这几个字是契约，一个字都不许改**（D41）：设计 §6.2 的 UI 对 tab 标题有正则依赖（`补写\s*(\d+)\s*处`），Plan 3 就是靠它决定要不要显示「逐段对照」的入口。把文案改成「改写 N 处」「润色 N 处」会让那个入口**静默消失**，而且不会有任何测试红。
>
> **D41 的 Plan 3 口径**（记在这里，免得将来又对不上）：`changeLog` 的每一条除 `type`/`detail` 外必须带 `quote` ——**必须是原样稿里的精确子串，4–40 字**；界面用 `rawTranscript.indexOf(quote)` 定位并高亮**首个**匹配，**不引入字符偏移**（偏移会在原样稿被编辑后全部错位）。tab 标题的 N = `changeLog` **数组长度**，不是"模型自称改了几处"。
>
> 因此 Plan 1 的 `novelChangeCount(changeLog: String?)`（从存储的文本里正则数「补写 N 处」）只是**过渡桥**：种子里那行 `novelChangeLog = "补写 2 处：走廊的光、声音的方向"` 是字符串、不是数组。Plan 3 把该字段换成 JSON 数组后，这个函数由 `changeLog.size` 直接取代 ——**但 `novelTabLabel` 的格式不许动**。Plan 1 不实现「逐段对照」界面，只保证标题格式正确。

  配两条单测（`DetailViewModelTest`，纯 JVM，不用 Robolectric）：

  ```kotlin
  @Test fun `novel tab label without rewrites`() = assertEquals("小说化", novelTabLabel(0))
  @Test fun `novel tab label counts rewrites`() = assertEquals("小说化 · 补写 6 处", novelTabLabel(6))
  ```
- 底部常驻一条播放条：`PlaybackBar(audioPath = state.dream?.rawAudioPath, durationMs = state.dream?.rawAudioMs)`。Plan 1 这两个值都是 `null`，所以显示播放三角 + 一条占位波形（40 根 1dp 竖线，高度用固定种子生成，**不要每帧抖动**）+ 时长 `--:--`。**签名就此定死**，Plan 2 只换内部实现，不改调用方：
  ```kotlin
  @Composable
  fun PlaybackBar(audioPath: String?, durationMs: Long?) {
      val label = durationMs?.let { ms -> "%d:%02d".format(ms / 60_000, (ms / 1000) % 60) } ?: "--:--"
      // Plan 1：不创建 MediaPlayer，点了也不播（避免空路径崩溃）；Plan 2：audioPath != null 时接 MediaPlayer
      Row(Modifier.fillMaxWidth().background(NightRaised, RoundedCornerShape(8.dp)).padding(12.dp)) { /* 三角 + 波形 + label */ }
  }
  ```
- 右上角一个 `⋮` 菜单，目前只有「编辑」「删除」两项，点了给 `Snackbar` 提示「Plan 2 实现」。
- 两个 Flow 的初值给空表，不要 `!!`（`DetailViewModel` 内部用 `stateIn(initialValue = DetailUiState())` 统一给）。

- [ ] **Step 5b: 往日历顶栏加搜索入口**（D39）

设计要求搜索固定在日历顶部，但 Task 6 刻意没做入口（那时还没有搜索页）。现在补上：

- `CalendarScreen` 顶栏右侧（与右箭头同一行，`Row` 右端）加一个 `Icons.Outlined.Search`，`tint = InkDim`。
- **命中区 ≥48dp**：`Box(Modifier.size(48.dp).clickable { onSearch() }, contentAlignment = Alignment.Center) { Icon(…, Modifier.size(24.dp)) }` —— `clickable` 必须包在 48dp 的 `Box` 上，**不能**包在 24dp 的 `Icon` 上（那是肉眼看不见的窄条，深夜闭眼点必点空）。
- `DreamKeeperRoot` 里把 `onSearch = { nav.navigate(DreamRoute.Search.route) }` 接上。
- Task 6 的「屏上仅 4 类文案」验收**仍然成立**：图标不是文字，不新增任何提示语。

- [ ] **Step 6: 装到模拟器，用种子数据走一遍**

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon
cd D:\DreamKeeper
& $adb logcat -c
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk

# ① 冷启动 ×3（本任务改了 CalendarScreen 顶栏，启动路径也要重新过闸）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-logcat.ps1 -Runs 3 -OutFile docs\evidence\plan1\task8-logcat.txt

& $adb shell am start -n com.dreamkeeper/.MainActivity
Start-Sleep 3
```

Expected：
- `capture-logcat.ps1`：`RESULT: OK`、max TotalTime ≤ 2000、`LAUNCH: Displayed=3`、`CRASHES: 0`。
- **日历顶栏有搜索图标**（D39）：点它在 48dp 范围内任意位置都能进搜索页（故意点图标外沿 4dp 处试试——这是命中区验收，不是看图标）。
- 进详情 → **四个 tab** 可切，原样正文可滚动。
- 打开 `seed-dream-1`（电梯）：标签 tab 里有「下坠的预感」胶囊（`EMOTION`，种子里的 `evidenceText` 非空）。
- 打开 `seed-dream-2`（教室）：小说化 tab 显示占位文案；**素材 tab 仍是那个 16:9 空槽 + 「以后可以在这里把梦做成视频」**——种子里的 `VIDEO` 记录不渲染（Plan 1 的素材 tab 是纯 UI 占位）。
- 打开 `seed-dream-3`：小说化 tab 标题是「小说化 · 补写 2 处」（注意分隔符两边各一个空格，和 `novelTabLabel` 一致；D41 的格式契约就靠这一条一字一字地对），正文非空。
- 打开 `seed-dream-4`：素材 tab 同样是那个空槽（**不存在「还没有素材」这句文案了**——素材 tab 在 Plan 1 只有一种样子）。
- 点播放条 → 无崩溃（Plan 1 不创建 `MediaPlayer`）。
- 搜索「电梯」→ 命中 1 条；搜索「电梯 六楼」→ 命中更少或 0；搜索 `"！` 之类标点 → 不崩溃。

四个 tab 各截一张（**第 4 张 `plan1-task8-tab4-media.png` 是 Task 9「预留口子核对」要引用的那一张，别漏**）：

```powershell
& $adb shell input tap 540 620            # 列表第一条，进详情
Start-Sleep 2
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-task8-tab1-original.png

& $adb shell input tap 400 300            # 切到"小说化"，坐标按截图调整
Start-Sleep 1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-task8-tab2-novel.png

& $adb shell input tap 600 300            # 切到"标签"
Start-Sleep 1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-task8-tab3-tags.png

& $adb shell input tap 800 300            # 切到"素材"
Start-Sleep 1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-task8-tab4-media.png

# ② 近白像素闸门：判据 nearWhite 必须 = 0
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-near-white.ps1 docs\evidence\plan1\plan1-task8-tab*.png -TopOffset 100 -MaxRatio 0
```

> 四个 tab 的点击坐标随布局变化，按截图实际位置调整；每切一个 tab 截一张，文件名 `plan1-task8-tab1..4`，四张都要 read_image 确认。`check-near-white.ps1` 必须四张全 `nearWhite=0`、退出码 0——详情页背景如果叠了浅色，这里会立刻抓出来。

- [ ] **Step 7: 提交**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(ui): 详情页四 tab 与中文搜索（LIKE + 防抖）"
git rev-parse --short HEAD
```

- [ ] **Step 8: 归档证据**

按 §2 写 `docs/evidence/plan1/task8.md`：short SHA、四张 tab 截图路径 + SHA256、三组测试的通过输出（`DetailViewModelTest` 两条 `novelTabLabel`、`SearchViewModelTest` 的防抖一条、`LibraryViewModelTest`/`CalendarViewModelTest` 全绿）、人工读图结论（写了哪个梦、哪个 tab、看到了什么文案/胶囊/空槽）、**搜索图标命中区的点击坐标与结果**（D39）、`task8-logcat.txt` 的三个数、`check-near-white.ps1` 的四行 `nearWhite=0`、复现步骤。**特别写明素材 tab 在两条不同的梦上截到的是同一张空槽**（证明它真的没接数据，不是渲染了种子里的 VIDEO）。然后 `git add docs/evidence/plan1; git commit -m "docs(evidence): task8 证据归档"`。

---

## Task 9: 标签管理页 + Plan 1 库级验收

**Files:**
- Create: `.../ui/tags/TagsScreen.kt`, `.../ui/tags/TagsViewModel.kt`
- Modify: `.../ui/DreamKeeperRoot.kt`（设置入口/二级页）, `.../ui/library/LibraryScreen.kt`
- Test: `android/app/src/test/java/com/dreamkeeper/ui/tags/TagsViewModelTest.kt`

- [ ] **Step 1: 写「合并标签」的失败测试**

```kotlin
@Test fun `merging moves dream tags to the target and deletes the source`() = runTest(dispatcher) {
    db.tagDao().upsert(TagEntity("t1", "电梯", TagKind.OBJECT))
    db.tagDao().upsert(TagEntity("t2", "升降机", TagKind.OBJECT))
    db.tagDao().upsert(TagEntity("t3", "电梯", TagKind.OBJECT).copy(id = "t3", name = "电梯井"))
    repo.attachTags("dream-1", listOf("t2"))   // 生产读写路径：Task 5 的 attachTags（写 dream_tags 只许走它）
    assertEquals(1, db.tagDao().countTagsOn("dream-1").count { it == "t2" })
    val vm = TagsViewModel(repo, db.tagDao())
    vm.merge(fromId = "t2", intoId = "t1")
    advanceUntilIdle()
    assertEquals(listOf("t1"), db.tagDao().countTagsOn("dream-1"))
    assertNull(db.tagDao().findById("t2"))
}
```

> 需要在 `TagDao` 补两个查询（`upsert` Task 4 已有，别重复定义；写 `dream_tags` 的 `linkDreamTag` 已删除，统一走 `repo.attachTags`）：
> `@Query("SELECT tagId FROM dream_tags WHERE dreamId = :dreamId") suspend fun countTagsOn(dreamId: String): List<String>` 与
> `@Query("SELECT * FROM tags WHERE id = :id") suspend fun findById(id: String): TagEntity?`。
> `countTagsOn` 故意返回 id 列表而不是 `COUNT(*)`——测试要断言「剩哪一个」，只给数字断不出来。
>
> 合并的实现是 `db.withTransaction { dao.repointDreamTags(from, into); dao.delete(from) }`。
> **`dream_tags` 上绝对不能有外键 CASCADE**：`repointDreamTags` 是 `UPDATE OR REPLACE`，REPLACE 冲突时会先删后插，CASCADE 会顺着把关联一起删掉——合并一次就把这条梦的标签全清空，而且不报错。Task 4 的 `DreamTagDao` KDoc 已经把这条写死了。

- [ ] **Step 2: 确认失败 → **Step 3: `merge` 实现为事务**（`db.withTransaction { dao.repointDreamTags(from, into); dao.delete(from) }`）→ **Step 4: 确认通过**

- [ ] **Step 5: 写标签页**

要求：按 `kind` 分组（人物/地点/物品/动作/情绪/其他），每组一个标题；每个标签是一个可点的胶囊；长按弹菜单：重命名、合并到…（弹一个搜索框选目标）、删除。重命名后若重名，提示「已有同名标签，要合并吗？」。

- [ ] **Step 6: Plan 1 库级验收（这一步是交付判据，逐条打勾）**

> **验收范围**：只验 Plan 1 交付的「库」——日历、列表、详情、搜索、标签、种子、深色主题与启动画面。
> **不验**：录音、转写、小说化生成、夜间入口、设置页、回收站（这些根本没有代码，见 ACCEPTANCE.md 的归属表）。
> 名字从「全链路验收」改成「库级验收」就是为此——不写没做过的验收。

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --no-daemon
cd D:\DreamKeeper
& $adb shell pm clear com.dreamkeeper
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk

# ① 冷启动 ×3（判据：max TotalTime ≤ 2000ms、Displayed=3、CRASHES: 0）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-logcat.ps1 -Runs 3 -OutFile docs\evidence\plan1\task9-logcat.txt

# ② 首屏截图
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-final-home.png
```

**冷启动无白帧**：原稿是"连拍 0/150/300ms 三帧"，那三帧只能靠裸 `adb exec-out screencap -p > 文件` 抓，而 PowerShell 5.1 会把 PNG 写成 UTF-16 坏文件（见 §2 与 Global Constraints 的禁令），所以**这条路已经封了**。改成三条能真正执行的证据：

1. `capture-logcat.ps1` 的 `STARTUP: runs=3 … max=…ms ≤ 2000` + `LAUNCH: Displayed=3`——`Displayed` 是系统自己量的"第一帧真正画出来的时刻"，比截图的时刻更准。
2. `capture-screen.ps1` 抓启动后第一张图，再过 `check-near-white.ps1 -TopOffset 0 -MaxRatio 0`——`-TopOffset 0` 是**从屏幕最顶上**开始采样（别的任务用 `-TopOffset 100` 跳过状态栏），近白像素必须 = 0。
3. 真要逐帧，用录屏而不是连拍截图：`& $adb shell screenrecord --time-limit 5 /sdcard/cold.mp4`，再 `& $adb pull /sdcard/cold.mp4 docs\evidence\plan1\`，人眼看前 1 秒。录屏不涉及 PS 重定向，不受上面的禁令约束。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-screen.ps1 -OutPath docs\evidence\plan1\plan1-coldstart-first.png
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-near-white.ps1 docs\evidence\plan1\plan1-coldstart-first.png docs\evidence\plan1\plan1-final-home.png -TopOffset 0 -MaxRatio 0
```

> 判据：两张图 `nearWhite=0`，操作者仍要 read_image 看一遍——背景应是 `#0B0E13`（`NightVoid`），Splash 与首屏同色。截图时刻做不到毫秒级对齐，这一点如实写进证据文件，不要假装抓到了 0ms。

**release 包不许灌种子**（D37，`SEED_DEMO_DATA` 只在 debug 为 true）：

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleRelease --no-daemon
cd D:\DreamKeeper
Select-String -Path android\app\build\generated\source\buildConfig\release\com\dreamkeeper\BuildConfig.java -Pattern "SEED_DEMO_DATA"
```

> 必须看到 `public static final boolean SEED_DEMO_DATA = false;`。看到 `true` 就说明 `buildConfigField` 又跑回 `defaultConfig` 去了——那样 release 包首启会凭空多出 4 条假梦，而 debug 包一切正常，最容易被漏掉。
> `assembleRelease` 若因缺签名配置失败，改用 `:app:compileReleaseJavaWithJavac`（或 `:app:generateReleaseBuildConfig`）同样能生成上面那个文件；**证的是生成的常量，不是 APK 能装**。

逐条确认（每条都要截图或命令输出作证）：

- [ ] 冷启动 2 秒内看到日历（`capture-logcat.ps1 -Runs 3` 的 `STARTUP max` < 2000；**不要**再用 `am start -W` 的单次 TotalTime，一次的数字不算数）
- [ ] 冷启动无白帧（`plan1-coldstart-first.png` + `plan1-final-home.png` 经 `check-near-white.ps1 -TopOffset 0 -MaxRatio 0` 全 `nearWhite=0`，且人工看过背景是 `#0B0E13`）
- [ ] **release 的 `BuildConfig.SEED_DEMO_DATA == false`**（D37；`Select-String` 输出贴进证据）
- [ ] 日历上下切 12 个月，不闪白、不崩
- [ ] 全部列表两种视图切换正常，杀进程重进保留偏好
- [ ] 搜索「电」命中，搜索纯标点不崩
- [ ] 详情**四个 tab** 可切（原样 / 小说化·补写N处 / 标签 / 素材）、原样正文可滚动、素材 tab 显示 16:9 空槽占位（与 Task 8 一致：只有空槽 +「以后可以在这里把梦做成视频」，**没有「还没有素材」这句文案**）
- [ ] 标签页可重命名、可合并、合并后原标签消失
- [ ] `adb logcat -d | Select-String "FATAL"` 无输出

- [ ] **Step 7: 写 `docs/evidence/plan1/ACCEPTANCE.md`**

这份文件的唯一职责：**说清楚设计 §14 的七条验收，各自归哪个计划、现在什么状态**。Plan 1 只可能让其中 0 条变绿，这不算失败，但必须写明白，否则下一个人会以为「验收都过了」。

表一：设计 §14 七条 ←→ 归属计划

| 设计 §14 | 原文（节选） | 归属计划 | 状态 |
|---|---|---|---|
| §14.1 | 点亮屏幕到开始录音 ≤ 3 步、≤ 3 秒 | Plan 4（锁屏入口/磁贴） | 未开始 |
| §14.2 | 接电话/切后台/锁屏，音频不丢 | Plan 2（前台服务） | 未开始 |
| §14.3 | 原样稿 diff 违规次数 = 0 | Plan 2（diff 硬校验） | 未开始 |
| §14.4 | 小说化 ≥ 8/10「像人写的」，编造 ≤ 10% | Plan 3（生成与评测集）；先 10 条试跑，攒到 20 条再定线 16/20 | 未开始 |
| §14.5 | 1000 条记录日历/列表滚动不掉帧 | **Plan 2**：开工前补一轮造数压测；Plan 1 **未覆盖**（只有 4 条种子，测不出滚动性能） | 已定推迟（D9） |
| §14.6 | 飞行模式可录音/转写/入队，恢复网络自动完成 | Plan 3（队列与重试） | 未开始 |
| §14.7 | 抓包验证全程无音频外传 | Plan 2（隐私验收） | 未开始 |

> §14.3 与 §14.4 的评测集条数在设计文档里一个是 20 条、一个是 10 条（`≥ 8/10`）。**已定口径**：先按 10 条试跑、攒到 20 条再把通过线提到 16/20（见偏差 D8）。§14.5 的千条性能验收：Plan 1 **未覆盖**，推迟到 Plan 2 开工前补（见偏差 D9）。

表二：Plan 1 自己做到的（可勾的写 ✅ 并附证据文件）

| 项 | 证据 | 状态 |
|---|---|---|
| 深色主题 + 无白启动画面 | `task2-*.png`、`plan1-coldstart-first.png`、`plan1-final-home.png` + 两行 `nearWhite=0` | §14 之外的 Plan 1 目标 |
| 日历首屏 + 有梦红点 + 当日列表 | `task6-a/b/c.png` | 同上 |
| 全部列表两视图 + 偏好持久化 | `plan1-task7-*.png` | 同上 |
| 详情四 tab + 中文搜索（LIKE） | `plan1-task8-tab1..4.png` | 同上 |
| 标签重命名/合并/删除 | `task9-*.png` | 同上 |
| 种子幂等（`pm clear` 后仍 4 条，且日期都在当月） | `task7/task9` 复现步骤 + logcat | 同上 |
| release 包不带假种子 | `BuildConfig.java` 的 `SEED_DEMO_DATA = false` 输出 | D37 |

- [ ] **Step 8: 预留口子核对（设计 §12，防止 Plan 2 开工才发现没留）**

```powershell
cd D:\DreamKeeper
# ① MediaAsset 表真的建出来了（不是只在文档里）
Select-String -Path android\app\src\main\java\com\dreamkeeper\data\db\*.kt -Pattern "tableName = .media_assets."
# ② 数据库里有这张表（运行期实证）
& $adb shell "run-as com.dreamkeeper sqlite3 databases/dreamkeeper.db '.tables'" 2>$null
# 或：把 db 拉出来看
& $adb exec-out run-as com.dreamkeeper cat databases/dreamkeeper.db | Set-Content -Encoding Byte .\dreamkeeper.db
# ③ remoteId 字段真的在代码里
git grep -n "remoteId" android/app/src/main
```

核对清单：
- [ ] `MediaAssetEntity` 的 `tableName = "media_assets"` 命中，且 `DreamDatabase` 的 `entities` 里有它
- [ ] `git grep -n "remoteId" android/app/src/main` **有命中**（`DreamEntity` 与 `domain/Dream.kt` 至少各一处）
- [ ] 详情第 4 个 tab 的截图 `plan1-task8-tab4-media.png` 存在且已人工看过
- [ ] 结论写进 `ACCEPTANCE.md` 的「预留口子」小节：每个口子一行「有 / 无 + 证据路径」

- [ ] **Step 9: 提交 + 打 tag**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(ui): 标签管理页（重命名/合并/删除）与 Plan 1 库级验收"
git tag plan1-done
git rev-parse --short HEAD
& $adb logcat -d | Out-File -Encoding utf8 "D:\DreamKeeper\docs\evidence\plan1\task9-logcat.txt"
```

- [ ] **Step 10: 归档证据**

按 §2 写 `docs/evidence/plan1/task9.md`：short SHA + tag、`ACCEPTANCE.md` 的链接、8 条验收各自的自证材料（截图文名或命令输出）、冷启动三帧、预留口子核对的三条命令输出、`task9-logcat.txt`、复现步骤。然后 `git add docs/evidence/plan1; git commit -m "docs(evidence): task9 证据归档 + Plan 1 验收状态"`。

---

## 自审记录（写完计划后自查并已修正）

1. **规格覆盖**：设计文档 §5 数据模型 → Task 4；§11 界面结构中的日历/全部/详情/搜索/标签 → Task 6-9；§13 技术选型 → Task 1-2。**未覆盖**（有意留给后续计划）：录音、STT、LLM、夜间特则、锁屏入口、回收站、设置页。
2. **占位符扫描**：Task 6/7/8 的界面步骤给的是「必须做到」的清单而非完整代码——这是**有意**的，因为 Compose 布局的逐行代码在这里会是 300 行噪声，而清单里的每一条都可被截图验证。其余步骤均含可直接粘贴的命令或代码。
3. **类型一致性**：`Dream` 的字段在 Task 5 定义、Task 6/7/8 的测试里使用；`DreamEntity` 与 `Dream` 的字段名差异（`dreamDate` vs `date`）只存在于 `toDomain`/`save` 两处，已明确。`DreamEntity` 的预留字段（`rawAudioPath`/`rawAudioMs`/`faithfulEditedByUser`/`novelEditedByUser`/`remoteId`/`transcriptStatus`）与 `Dream` 的诊断字段（`createdAt`/`updatedAt`/`novelVersion`/`deletedAt`）全部带默认值且只在末尾追加——因为 3 处 `Dream(...)` 位置参数调用不能错位。`LayoutPrefs` 在 Task 7 定义为接口 + `SharedPreferencesLayoutPrefs` 生产实现（**不引入 DataStore**），测试用 `FakeLayoutPrefs`。
4. **静默改数据的两处已堵**：`save()` 不再覆盖 `createdAt`（首次录入时间）与 `deletedAt`（回收站状态），各配一条回归单测；搜索不再依赖 FTS4，`search("100%")` 与 `search("楼道")` 两条回归覆盖通配符转义与「中间子串」。
4. **验收范围诚实性**：Task 9 原名「全链路验收」，但 Plan 1 没有录音、生成、夜间入口的代码，叫「全链路」是虚假交付。已改名「Plan 1 库级验收」，并把设计 §14 七条各自归属哪个计划写进 `docs/evidence/plan1/ACCEPTANCE.md`——不把「还没做」写成「已通过」。

## 后续计划（不写进本文件）

| 计划 | 内容 | 依赖 |
|---|---|---|
| Plan 2 · 录音与转写 | 前台服务、AudioRecord、权限、本地 STT、原样稿生成与 diff 硬校验、播放条接真实音频 | Plan 1 |
| Plan 3 · 生成与提示词 | LlmClient 适配器、Key 加密存储、小说化、改动清单、自动标签、`prompts/` 接入、评测集 | Plan 2 |
| Plan 4 · 夜间与入口 | 锁屏磁贴/通知按钮、30 秒追问窗口、屏幕常亮、夜间特则打磨、设置页、回收站 | Plan 3 |

---

## 与设计文档的偏差登记

设计文档（`docs/superpowers/specs/2026-10-29-dreamkeeper-design.md`，已批准）是最高依据。下面是**计划与设计不一致的全部已知地方**，要么改计划、要么等 Lead 裁定、要么回写设计——不允许让它们悄悄漂着。

| # | 偏差 | 设计文档怎么写 | 计划怎么做 | 状态 |
|---|---|---|---|---|
| D1 | SDK 版本 | §13：`compileSdk/targetSdk 36，minSdk 26` | `compileSdk = 37`、`targetSdk = 37`、`minSdk = 26`（Task 1） | **计划为准**（本机 SDK 37 已装、36 未装）；设计 §13 待回写，`minSdk` 定义一致 |
| D2 | 视觉回归手段 | §13 测试行：`JUnit + Turbine + Robolectric；UI 用 Roborazzi 截图` | 只有 JUnit + Robolectric；**没有 Turbine、没有 Roborazzi、没有 golden 目录**，视觉回归靠人工读截图 + 证据文件（见 Global Constraints「测试策略」） | **已定（用户口径）：不搭 Roborazzi**。Plan 1 的界面验收 = 截图 + `read_image` 人眼读 + SHA256 存档；不引入 golden 基建，也不为此加 Task |
| D3 | 详情页 tab 数 | §11：原样 / 小说化·补写N处 / 标签 / 素材（占位）——**四个** | 原计划写「三个 tab」（漏了素材），已按设计改为四个（Task 8 Step 5），且素材 tab 是纯 UI 占位 | **已消除，不是偏差**：设计原文就是四个，是计划写错了。此行的作用只剩"记录过一次笔误" |
| D4 | 设置页 / 回收站 | §11 底部导航含「设置」，表格含「设置」「标签管理」两行；§5 有 `deletedAt` 软删除 | Plan 1 只做标签管理；设置页与回收站均不做，归 Plan 4（`deletedAt` 字段已建但无 UI 无写入） | **已声明范围**，非缺陷；读者不应把「Plan 1 完成」理解成设计 §11 全做完 |
| D5 | 提示词存哪 / 生成任务表 | §5：`PromptVersion(id, kind, version, body, createdAt) // 提示词进库，便于 A/B 与回滚`；§5 另有 `GenerationJob` 表 | **Plan 1 两张表都不建**。`generation_jobs` 与 `prompt_versions` 由 **Plan 3 建表**——迁移基建（`exportSchema` + `schemas/1.json` + `room-testing`）已在 Plan 1 就位，届时 v1→v2 有真实基线可验。提示词以 `prompts/` 下的文件为唯一真源，版本 = 文件名 + sha256（溯源靠 `(promptId, promptVersion, promptHash)`） | **已定（用户口径）**。省两张空表换来"提示词改动走 git diff 而不是 SQL"；A/B 与回滚靠版本文件 + 生成记录里的 hash |
| D6 | 生成任务的枚举与字段 | §5：`kind = FAITHFUL_CLEANUP \| NOVEL \| TAGGING \| TITLE \| CHANGELOG`；`status = PENDING \| RUNNING \| OK \| FAILED \| CANCELLED`；字段 `provider, model, promptVersion, tokensIn, tokensOut, error, createdAt` | Plan 1 不定义 `GenerationJobEntity`；表与 DAO 全部在 **Plan 3** 建（见 D5）。Plan 3 建表时按此表对齐字段名 | **已定**：Plan 3 建表；枚举常量只许新增、不许改名，否则历史行读不出来 |
| D7 | 时间字段类型 | §5：`deletedAt: Instant?`、`updatedAt: Instant` | 全部用 `Long`（epoch millis）：`createdAt`/`deletedAt`/`updatedAt` | **计划为准**（Room 存 `Instant` 要额外 TypeConverter，`LocalDate` 已有一个 converter，不叠加）；语义等价，精度到毫秒 |
| D8 | 评测集条数 | §14.3 写「评测集 **20** 条」；§14.4 写「≥ **8/10** 条」——设计文档自相矛盾 | **先跑 10 条试跑，攒到 20 条再提高门槛**：`docs/eval/dreams.md` 建 20 个空槽，Plan 3 先用前 10 条跑通评分流程；样本到 20 条后把通过线从 8/10 提到 16/20 | **已定（用户口径）**。设计 §14.3/§14.4 待回写为「10 条试跑 → 20 条定线」 |
| D9 | §14.5 千条性能验收归属 | §14.5：1000 条记录滚动不掉帧（模拟器 + 真机） | Plan 1 **显式标「未覆盖」**（只有 4 条种子，测不出滚动性能）；推迟到 **Plan 2 开工前**补一轮造数压测 | **已定（用户口径）**：Plan 1 不验、不造数；Plan 2 的第一件事是补这个验收 |
| D10 | 色温与色值 | 设计未写具体色值，只说"深色为默认" | 保持**冷灰**：Night `#0B0E13` / Paper `#E6EAF2` 不动；只修三处对比度不达标（`InkFaint` → `#7C8698`、"今天"描边 `Line` → `Ember`、M3 `error` → `ErrorText #E08578`），并把注释里的「暖黑/微暖」改成「冷灰」 | **已定（用户口径）**：不动色温；`ThemeContrastTest` 把四条阈值焊进测试 |
| D11 | 录音编码 | §8 只写"录音"，未指定容器与编码 | **PCM 16kHz 单声道 WAV**，单文件连续录（按红钮 → 追问窗口超时，含静音），路径 `files/audio/<dreamId>.wav` | **已定（用户口径）**：崩溃可恢复优先，接受 ≈1.92MB/分钟；理由见设计 §8.1「音频存储契约」 |
| D12 | 导航实现 | 设计未指定导航方案 | 引入 `androidx.navigation:navigation-compose`，根界面用 `NavHost`（Task 6），底部栏只在 Calendar/Library 显示 | **已定（用户口径）**：多一个依赖可接受。版本号在 Task 1 标了「未验证」，实现者按仓库可用版本填 |
| D13 | `transcriptStatus` 字段 | 设计 §5 的 `dreams` 表没有这个字段 | 新增 `transcriptStatus`（`PENDING`/`OK`/`FAILED`，默认 `OK`），用来表达「录音已落盘、文字还没出来」的中间态 | **已回写设计 §5**（本轮同步改了设计文档），因此不再是偏差，登记于此备查 |
| D14 | KSP 版本 | 计划初稿写 `ksp = "2.4.21-2.0.4"` | 实际改 `ksp = "2.3.12"`：双段命名在 Maven 上止于 `2.2.21-2.0.5`，2.3.0 起 KSP 改单段版本号，`2.4.21-2.0.4` 不存在 | **Task 1 实测**。KSP 2.3.12 与 Kotlin 2.4.21 的兼容性要到 Task 4（Room/KSP 真正跑注解处理）才受验；若红则降 Kotlin 到 2.3.x 或升 KSP |
| D15 | Kotlin 插件与 toolchain | 计划写显式 `kotlin-android` 插件 + `jvmToolchain(17)` | 移除 `kotlin-android`（AGP 9 内置 Kotlin 支持，显式加会构建失败）、移除 `jvmToolchain(17)`（本机只有 JDK 25，toolchain 会去找不存在的 JDK 17）；`jvmTarget` 取 `compileOptions.targetCompatibility = 17` | **Task 1 实测，已验证可构建**。以后新增模块照此写 |
| D16 | Maven 仓库顺序 | 计划只依赖默认 `gradlePluginPortal()`/`google()`/`mavenCentral()` | `settings.gradle.kts` 两处 `repositories` 前置 `https://mirrors.cloud.tencent.com/nexus/repository/maven-public/`，`google()`/`mavenCentral()` 保留作回退 | **Task 1 实测**：`repo.maven.apache.org` 在本机停滞，腾讯镜像实测 8294 KB/s |
| D17 | 占位界面主题 | 计划 Task 1 写「占位界面用 `MaterialTheme`」 | 改为显式常量 `Color(0xFF0B0E13)` / `Color(0xFFE6EAF2)`，不用 `MaterialTheme` / `Surface` | **Task 1 实测**：Material3 `Surface` 不透明近白，覆盖 `windowBackground`，白闪直接违反夜间硬约束 |
| D18 | 系统栏颜色 | 计划 Task 2 写「在 `themes.xml` 设 `statusBarColor`/`navigationBarColor`」 | `targetSdk 37` 强制 edge-to-edge，这两个属性是**空操作**；Task 2 必须用 `enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT))` 显式声明，并靠 Compose 背景铺满系统栏区域 | **Task 1 实测**：截图状态栏近黑是 Compose 背景铺过去的结果，不是主题属性生效 |
| D19 | 自检命令写法 | 计划多处写 `Select-String -Path android\app\src\main -Pattern "FFFFFF|Color.White"` | 统一改 `Get-ChildItem -Recurse -File <目录> \| Select-String -Pattern …` | **Task 2 实测**：`-Path` 传目录时不递归、报「访问被拒绝」并返回空 → 是假阴性，看起来「无命中」其实一个文件都没扫 |
| D20 | 错误态配色 | 计划与 M3 惯例：容器内文字用 `colorScheme.error` | `error`（`ErrorText #E08578`）只能画在 `Night` 底上；**错误容器（`EmberDeep`）里的文字必须用 `onErrorContainer`（`Paper`）** | **Task 2 实算**：`ErrorText` on `EmberDeep` 仅 2.79:1，不达标；`Paper` on `EmberDeep` 6.24:1。Task 7 写错误态照此执行 |
| D21 | Task 3 用例名与夹具不符 | 计划写 `month with 31 days starting on monday has no leading blanks`，夹具却是 `YearMonth.of(2026, 6)`（30 天） | 用例改名为 `month starting on monday has no leading blanks`，断言按 30 天 | **Task 3 实测**：名字错了会误导后来者，以为在测 31 天 |
| D22 | Task 3 断言强度不足 | 计划用 `assertEquals(0, grid.size % 7)` 证明「无前导空白」 | 改为断言 `grid[0] == 当月 1 号` 且前 N 格全非空 | **Task 3 实测**：35 格在「有 5 格前导空白」时同样满足 `35 % 7 == 0`，原断言抓不住 |
| D23 | Task 3 用例无判别力 | 计划自带 `week start is configurable` | 换成「月初是周一或周日」的月份，或采纳逐日列对齐断言（Task 3 已实现后者） | **Task 3 实测**：故意写「完全忽略 `weekStart`」的错误实现，该用例仍 PASS（2026-10-01 的 `value=4` 与错误前导巧合相等）。**推论：全绿 ≠ 正确，计划里的断言要能被错误实现打红** |
| D24 | `monthRange` 区间语义 | 设计只说「按月查询」，计划返回 `Pair<LocalDate, LocalDate>` | **原以为要给 DAO 加 `+1` 转半开，实际不需要**：Task 4 的 DAO 用 ISO 字符串闭区间 `dreamDate BETWEEN :fromIso AND :toIso`，`BETWEEN` 本身闭区间，语义已一致。**只有将来改成 epochDay 数值比较时才需要 `until last + 1`** | **Task 5 实测修正**：本行原先写成「DAO 必须自己 +1」，与 Task 4 的实现正好相反。教训：偏差表写的是「我以为的坑」，不是「实测的结论」，登记时就该分清 |
| D25 | Task 4 测试骨架的 `MediaType.IMAGE.name` | 计划写 `MediaType.IMAGE.name` | 改为直接传 `MediaType.IMAGE` | **Task 4 实测**：`MediaType` 是 `object { const val IMAGE = "IMAGE" }`，String 没有 `.name`。改 enum 会破坏 `SeedData` 与 Produces 契约，故改测试 |
| D26 | `observeById` 不软删过滤 | 计划未写明 | **保持不过滤**（回收站要能打开已删除的梦），但列表查询一律走 `observeAll`/`observeByMonth` | **Task 4 约定**：Task 5/8 若拿 `observeById` 做列表数据源，会把回收站的梦混进正常列表 |
| D27 | `dream_tags.tagId` 无索引 | 计划未写索引 | 暂不加索引，接受 Task 9 合并/删除时全表扫 | **Task 4 判断**：千条量级无感；现在加索引会触发 v2 迁移，为未测到的性能问题付迁移成本不划算，破万条再说 |
| D28 | `gradle-daemon-jvm.properties` 不入库 | Android Studio 同步时自动生成，含 `toolchainVersion=25` 与 10 个平台的 JDK 远程下载地址 | 保留在磁盘（AS 需要），但**加进 `.gitignore`**；JDK 由 `JAVA_HOME` / AS 的 Gradle JDK 设置决定 | **环境相关文件**：它把「用哪个 JDK」钉成 25 并附远程地址，换一台装 JDK 17/21 的机器会触发下载。这类文件不该进公共仓库 |
| D29 | `save()` 全量重建会重置残留字段 | 计划只点名 `createdAt`/`deletedAt` 两条要保 | 追加：`ownerId`、`faithfulEditedByUser`、`novelEditedByUser`、`remoteId` 也必须 read-modify-write 保留 | **Task 5 实测**：`save()` 里 `ownerId = "local"` 写死、三处编辑标记根本没传，任何一次保存都会把它们重置成默认值。Plan 1 无写入方，所以是「埋着的雷」，Plan 2 接上录音就会炸 |
| D30 | 偏好存储 | §13「设置」行写 `DataStore (Proto/Preferences)`，理由「类型安全」 | 用 `SharedPreferences`（文件 `layout_prefs`、键 `library_layout`，同步读写，**不新增任何依赖**），Task 7 起按此实现 | **已回写设计 §13**（`DataStore` → `SharedPreferences`）：只存一个列表布局枚举，`DataStore` 的读是 suspend 的，为拿首值要么 `runBlocking` 要么首帧抖一下——为一个枚举引框架不划算 |
| D31 | `VideoClient` 归属 | §12「梦 → 视频」行写「`LlmClient` 同级再放一个 `VideoClient` 接口」，读起来像 Plan 1 就要建 | Plan 1 **不建** `VideoClient`；与 `LlmClient` 同批在 **Plan 3** 建空接口。Plan 1 只落 `media_assets` 表与详情页素材位 | **已回写设计 §12**（注明归 Plan 3）：Plan 1 没有任何生成链路，先建一个只有签名、没有调用方的接口，等于留一个没人验的空壳 |
| D32 | 设置页与 API Key 录入归属 | §11 底部导航含「设置」、§9/§10 要求用户自填 Key 并存入 Keystore；计划原先写设置页整体归 Plan 4 | **拆分**：**Plan 3 做最小 Key 录入**（provider / baseUrl / model / Key 四栏 + Keystore 保存 + 首次填写时明说「消耗的是你自己账号的额度」），**Plan 4 做完整设置页**（主题、默认参数、用量、回收站入口等） | **Lead 裁定**：Key 是生成功能的前置条件，不能等到 Plan 4——Plan 3 要验「真实调用出稿」，没有录入入口就只能把 Key 硬编码进代码，那不叫端到端验收。完整设置页是打磨，可以等 |
| D33 | Task 9 验收描述与 Task 8 实现不符 | 计划 Task 9 验收清单写「素材 tab 空态文案『还没有素材』出现过」 | Task 8 的素材 tab 只有 16:9 空槽 +「以后可以在这里把梦做成视频」，**没有「还没有素材」这句文案**；验收改为「素材 tab 显示 16:9 空槽占位」 | **按实现改验收**（不改界面去迁就文档）：为一句不存在的文案加一个分支，是为了打勾造需求。验收描述只许描述已经存在的东西 |

| D34 | 导航依赖从未落地 | §13 技术选型只写「语言/UI/SDK/…」，**没有导航行**（`Navigation-Compose` 一直缺席，D12 记的是「要引入」而非「已引入」） | `androidx.navigation:navigation-compose:2.9.8` 已由 **Lead 手工补齐并验证编译**（来自腾讯镜像）。计划正文**不再有「加依赖」的实现步骤**；Task 1 的 `libs.versions.toml` 里 `navigation-compose` 条目是唯一权威口径（版本号只写在那里，正文不重复抄） | **已落地（不是待办）**。D12 的「版本未验证」状态到此关闭。教训：计划里写「引入 X」时，要么同一步给出可粘贴的依赖坐标，要么在偏差表标「未落地」——只写「见 libs.versions.toml」而那个文件里根本没有，等于把一条必红的路留给执行者 |
| D35 | 缺少「当日列表」层 | §11 明确三层：`日历 ──点格子──▶ 当日列表 ──▶ 详情` | 原计划跳过中间层：日历 `onDayClick` 直接把 `date.toString()` 当 `dreamId` 传给 `Detail` 路由 → 详情按 id 查库必然查不到，**打开必空白**。裁定 **Plan 1 补这一层**：`DreamRoute.Day(dateIso)`、`DreamDao.observeDay(iso)`（`SELECT * FROM dreams WHERE deletedAt IS NULL AND dreamDate = :iso ORDER BY createdAt DESC`）、`DreamRepository.observeDay(iso)`、`DayViewModel`、`DayScreen`（卡片：标题 / 正文首句 / 标签；空态「这天没有梦」）；`onDayClick` 改跳 `Day` | **已补进计划**（Task 4 DAO+仓库+两条单测、Task 5 仓库、Task 6 Step 1b/3b/5）。测试口径特意加了「跨月不串」（不同月份的同一天号只回当月那天）与「软删排除」。Task 7 的卡片复用 `DayScreen` 的 `DreamCard`，不写第二份 |
| D36 | 种子日期写死 + 非事务 | 设计未规定种子的日期策略 | 原 `SeedData` 写死 `2026-10-20/24/27/28`：**11 月 1 号起首屏 0 红点**，Task 6/7/8 的截图验收全部作废（界面正常、只是空，最难查）。改为按「当月内的固定几天」生成——`YearMonth.now()` 的 `1/8/15/22` 号，4 条种子**恒在同一个自然月**内；`seedIfEmpty` 整体包进 `db.withTransaction { }` | **已改**。原写法 count 判断与写入不在同一事务里，首启被杀会留下**永久半套种子**（count 已非 0 → 永远不补齐）。同时计划里所有写死日期的期望值改成相对表述（「当月内 4 个有梦日」「电梯那条 = `YearMonth.now()` 的 1 号」「列表按 22/15/8/1 倒序」），Task 6 的「共 N 天有梦」按 4 条同月种子算 |
| D37 | `SEED_DEMO_DATA` 恒为 true | 设计未提该开关 | 原写在 `defaultConfig` 里恒 true：**release 包首启也灌 4 条假梦**。改为 `buildTypes { debug { "true" } release { "false" } }` | **已改计划**（Task 4 Step 1 + Task 9 验收新增一条：`assembleRelease` 后 `BuildConfig.SEED_DEMO_DATA == false`）。代码侧由 Lead 改，计划只写口径。这类「debug 才该有的门闩」写进 `defaultConfig` 是最常见的错——`defaultConfig` 不是「默认」，是「所有变体」 |
| D38 | Task 7 空态自相矛盾 | 设计未规定全部列表的空态 | 原验收要求「空状态画一支指向说梦钮的箭头」，但**该页没有说梦钮**（唯一的说梦钮在日历页底部），箭头指向一个不存在的东西；原稿文案「按下面那个钮」同样错（下面只有底部栏） | **已改**：居中 `InkDim` 文案「还没有梦」+ 副标题「去日历页，按那个橙色的钮」，**不要箭头**。教训：验收里出现「指向 X」时，先确认 X 在这一页存在 |
| D39 | 搜索入口归属 | §11：搜索固定在日历顶部 | Task 6 不建搜索页，但计划没写入口怎么办，读者会以为 Task 6 要放一个假放大镜 | **裁定**：Task 6 **不做搜索假壳**；Task 8 往日历顶栏加搜索图标（命中区 ≥48dp，`clickable` 包 48dp 的 `Box` 而非 24dp 的 `Icon`）并接到 `Search` 路由。Task 6 的「屏上仅 4 类文案」验收保留 | **已补进计划**（Task 6 显式写"本轮不做"、Task 8 Step 5b）。假壳比没有更糟：点了没反应会被当成 bug 报回来 |
| D40 | 录音中断语义（G1/G2） | §8.1-5 原写「启动扫描 `files/audio/`……有对应行：正常，什么都不做」；§14.2 只写「音频不丢」，没有判据 | **G1**：来电/焦点被抢期间读不到 PCM → **补等长零字节**，时间轴不断；`rawAudioMs` 定义为 `(文件字节数 - 44) / 32`（文件真实时长，含补零），**不用墙钟**。**G2**：WAV 头开录写 `0xFFFFFFFF` 占位，**松手时与启动扫描时都要回填**；扫描对「已有对应行」的文件也必须**校验并修复头**（原文「什么都不做」会漏修） | **已回写设计 §8.1-5 与 §14.2 判据**（「通话前后内容连续、通话期间为等长静音」）。**Plan 2 待验风险（各一行）**：R1 `targetSdk 37` 下 microphone 型前台服务**不能**从锁屏/磁贴后台直起，须先拉起可见 Activity，验收用 `logcat` 过滤 `ForegroundServiceStartNotAllowed`；R2 长录音 1.92MB/min、1h≈115MB，需剩余空间闸门；R3 系统 STT 无 on-device 中文包时会退回**网络识别**（=音频出机），Plan 2 必须抽象 `SttEngine` 接口 + API 33+ 可用性检查，不可用就 `PENDING` 排队 |
| D41 | 逐段对照不引入字符偏移 | §6.2：`changeLog: [{type, detail}]`；tab 标题「小说化 · 补写 6 处」 | Plan 3 的 `changeLog` 每条**增加 `quote` 字段**：**必须是原样稿里的精确子串，4–40 字**；界面用 `rawTranscript.indexOf(quote)` 定位并高亮**首个**匹配；tab 标题的 N = `changeLog` **数组长度** | **已回写设计 §6.2**。三条理由：①字符偏移在原样稿被编辑后全部错位，而 `quote` 是自校验的（找不到就不高亮，而不是高亮错行）；②长度区间 4–40 字保证「可唯一命中」又不至于整段复述；③**「补写 N 处」字样必须保留**——UI 有正则依赖（`补写\s*(\d+)\s*处`），改文案会让「逐段对照」入口静默消失。Plan 1 里 `novelChangeCount()` 的正则只是过渡桥，Plan 3 换成 `changeLog.size` |
| D42 | `docs/eval/` 口径不统一 | §7.2 ④ 三条评分写成「1–5 / 有·无 / 有·无」 | `docs/eval/README.md` 是「三项各 1–5 分」，`results.md` 的列名与表头却是「有没有…」+ 缺一列「原样整理违规次数」 | **已改 `docs/eval/results.md`**：三项统一 **1–5 分**（不再混用「有/无」），并加一列「原样整理违规次数（目标 0）」；与 `README.md` 的说法逐句核对过。设计 §7.2 ④ 同步改成 1–5 | **已改**。同一份口径在三处（设计 / README / results）漂移是文档类项目最常见的腐烂方式：改一处的读者以为改全了 |

> 登记规则：以后每次发现计划与设计不一致，先在这里加一行，再决定改哪边。**不允许**只在代码里悄悄偏离。
