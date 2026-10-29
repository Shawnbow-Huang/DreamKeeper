# Plan 1 · 骨架与梦境库（Foundation & Library）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做出一个装得上模拟器、打开就能看见日历、能翻能搜能管标签的「梦匣」，但还不会录音。

**Architecture:** 单 Gradle 工程置于 `android/`，单 module `:app` + 包边界（`data` / `domain` / `ui`）。数据用 Room（含 FTS4 全文索引），UI 用 Compose + Material3，深色为默认主题。本计划只做「读」的一半：录入与生成在 Plan 2/3。

**Tech Stack:** Kotlin 2.4.21 · AGP 9.5.0 · Gradle 9.8.2 · Compose BOM 2026.09.00 · Room 2.8.5 · JUnit4 + Robolectric

## Global Constraints

以下为本计划每个任务都隐含的要求，逐字照做：

- **包名/命名空间**：`com.dreamkeeper`；应用显示名 **梦匣**；`rootProject.name = "DreamKeeper"`。
- **SDK 版本**：`compileSdk = 37`、`targetSdk = 37`、`minSdk = 26`。本机已装 `platforms/android-37.0`（ApiLevel 37.0）与 `build-tools/36.0.0`。
- **仓库根是 `D:\DreamKeeper`，Gradle 工程根是 `D:\DreamKeeper\android`。** 任何构建命令都必须先 `cd android`。
- **提示词不放进 `android/`**：`prompts/` 在仓库根，通过 `sourceSets` 引入（本计划暂不涉及，Plan 3 处理）。
- **夜间体验是硬约束**：全流程深色、无白闪、不用纯白（`#FFFFFF` 禁止出现在主题与布局里）。
- **不使用多 module**；不引入 Hilt/Koin（用简单的手写容器）；不引入 Navigation-Compose 之外的导航框架。
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
│           │   │   ├── db\DreamFtsEntity.kt
│           │   │   ├── db\MediaAssetEntity.kt
│           │   │   ├── db\GenerationJobEntity.kt
│           │   │   ├── db\DreamDao.kt
│           │   │   ├── db\TagDao.kt
│           │   │   ├── db\MediaAssetDao.kt
│           │   │   ├── db\GenerationJobDao.kt
│           │   │   ├── db\SeedData.kt
│           │   │   └── DreamRepository.kt
│           │   ├── domain\
│           │   │   ├── Dream.kt
│           │   │   ├── Tag.kt
│           │   │   ├── MediaAsset.kt
│           │   │   └── DreamCalendar.kt
│           │   └── ui\
│           │       ├── theme\Color.kt / Theme.kt / Type.kt
│           │       ├── DreamKeeperRoot.kt
│           │       ├── calendar\CalendarScreen.kt / CalendarViewModel.kt
│           │       ├── library\LibraryScreen.kt / LibraryViewModel.kt
│           │       ├── detail\DetailScreen.kt
│           │       ├── search\SearchScreen.kt
│           │       └── tags\TagsScreen.kt
│           └── res\values\strings.xml / themes.xml
│       └── src\test\java\com\dreamkeeper\
│           ├── domain\DreamCalendarTest.kt
│           └── data\DreamDaoTest.kt
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

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { jvmToolchain(17) }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
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
cmd /c "`"$adb`" exec-out screencap -p > $ev\plan1-task1.png"

# PNG 魔数校验：不过就是截图失败，不是"图片打不开"
$b=[System.IO.File]::ReadAllBytes("$ev\plan1-task1.png")[0..7]
if (($b -join ',') -ne '137,80,78,71,13,10,26,10') { throw "PNG 魔数校验失败: $($b -join ',')" }
Get-FileHash "$ev\plan1-task1.png" -Algorithm SHA256
```

Expected: 魔数校验通过；截图里出现居中的「梦匣」两个字，文字是浅灰 `#E6EAF2`（**不是纯白**），背景是近黑（`#0B0E13`），状态栏也是近黑。**用 read_image 打开截图确认**，不要凭安装成功就宣称成功。

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

**Interfaces:**
- Produces: `@Composable fun DreamKeeperTheme(content: @Composable () -> Unit)`；颜色常量 `Night`、`Paper`、`Ink`、`InkDim`、`Ember`、`Line`（后续所有界面只用这套）。
- Produces: 一个「从图标到第一帧都不白」的启动路径：`values/themes.xml` + `values-v31/themes.xml` + 深底图标。

- [ ] **Step 1: 写色板**

`ui/theme/Color.kt`：

```kotlin
package com.dreamkeeper.ui.theme

import androidx.compose.ui.graphics.Color

/** 夜色背景：比纯黑略暖一点，避免 OLED 上的"空洞感" */
val Night = Color(0xFF0B0E13)
/** 抬升层（卡片、弹窗） */
val NightRaised = Color(0xFF12161F)
/** 正文：不用纯白，用微暖的浅灰 */
val Paper = Color(0xFFE6EAF2)
/** 次要文字 */
val InkDim = Color(0xFF8B95A7)
/** 更弱的辅助信息 */
val InkFaint = Color(0xFF5A6474)
/** 分隔线 */
val Line = Color(0xFF232A36)
/** 唯一强调色：琥珀，夜间不刺眼，也不与"红钮"抢注意力 */
val Ember = Color(0xFFC8873F)
/** 录音状态专用：暗红 */
val EmberDeep = Color(0xFF8C3B34)
```

- [ ] **Step 2: 写主题**

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
    outline = Line,
    error = EmberDeep,
)

@Composable
fun DreamKeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DreamColorScheme, typography = DreamTypography, content = content)
}
```

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
$ev="D:\DreamKeeper\docs\evidence\plan1"
cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon
cd D:\DreamKeeper
& $adb logcat -c
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am force-stop com.dreamkeeper
& $adb shell am start -n com.dreamkeeper/.MainActivity
Start-Sleep 3
cmd /c "`"$adb`" exec-out screencap -p > $ev\plan1-task2.png"
$b=[System.IO.File]::ReadAllBytes("$ev\plan1-task2.png")[0..7]
if (($b -join ',') -ne '137,80,78,71,13,10,26,10') { throw "PNG 魔数校验失败: $($b -join ',')" }
```

Expected: 魔数校验通过；文字为浅灰 `#E6EAF2`（不是纯白），背景近黑。**read_image 确认**。

- [ ] **Step 7: 提交**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(ui): 梦匣深色色板与主题（含启动画面与深底图标），禁止纯白"
git rev-parse --short HEAD
```

- [ ] **Step 8: 归档证据**

按 §2 写 `docs/evidence/plan1/task2.md`（short SHA、构建命令、APK 与截图 SHA256、读图结论、复现步骤、`task2-logcat.txt`），再 `git add docs/evidence/plan1; git commit -m "docs(evidence): task2 证据归档"`。

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

## Task 4: 数据层 —— Room 实体、FTS 全文索引、DAO

**Files:**
- Create: `.../data/db/DreamEntity.kt`, `TagEntity.kt`, `DreamTagEntity.kt`, `DreamFtsEntity.kt`, `MediaAssetEntity.kt`, `GenerationJobEntity.kt`, `DreamDao.kt`, `TagDao.kt`, `MediaAssetDao.kt`, `GenerationJobDao.kt`, `Converters.kt`, `SeedData.kt`, `DreamDatabase.kt`
- Modify: `android/app/build.gradle.kts`（`ksp { arg("room.schemaLocation", ...) }`、`room-testing`、`buildConfigField("SEED_DEMO_DATA")`）
- Modify: `android/gradle/libs.versions.toml`（加 `room-testing`）
- Commit（必须入库）: `android/app/schemas/com.dreamkeeper.data.db.DreamDatabase/1.json` —— `exportSchema = true` 的产物，是 migration 的唯一对照物，删了它以后任何 schema 变更都失去依据
- Test: `android/app/src/test/java/com/dreamkeeper/data/DreamDaoTest.kt`

**Interfaces:**
- Produces（后续任务全部依赖这些精确签名）:
  - `DreamEntity(id: String, ownerId: String, dreamDate: LocalDate, createdAt: Long, title: String?, rawTranscript: String, faithfulText: String?, novelText: String?, novelVersion: Int, novelChangeLog: String?, mood: String?, isDraft: Boolean, deletedAt: Long?, updatedAt: Long, rawAudioPath: String? = null, rawAudioMs: Long? = null, faithfulEditedByUser: Boolean = false, novelEditedByUser: Boolean = false, remoteId: String? = null)`
  - `MediaAssetEntity(id: String, dreamId: String, type: String, path: String?, prompt: String?, model: String?, status: String, createdAt: Long)`
  - `GenerationJobEntity(id: String, dreamId: String, kind: String, status: String, provider: String?, model: String?, promptId: String?, promptVersion: Int?, promptHash: String?, temperature: Double?, tokensIn: Int?, tokensOut: Int?, error: String?, createdAt: Long)`
  - `DreamDao.observeByMonth(fromIso: String, toIso: String): Flow<List<DreamEntity>>`
  - `DreamDao.observeAll(): Flow<List<DreamEntity>>`
  - `DreamDao.observeById(id: String): Flow<DreamEntity?>`
  - `DreamDao.upsert(dream: DreamEntity)`
  - `DreamDao.count(): Int`（种子幂等判据）
  - `DreamDao.searchRaw(ftsQuery: String): List<DreamEntity>`
  - `TagDao.observeAll(): Flow<List<TagEntity>>`、`TagDao.upsert(tag: TagEntity)`、`TagDao.rename(id, newName)`、`TagDao.repointDreamTags(fromId, intoId)`、`TagDao.delete(id)`、`TagDao.count(): Int`
  - `TagDao.observeTagsForDream(dreamId: String): Flow<List<TagEntity>>`（联 `dream_tags`，详情页标签 tab 用这个——`Dream` 上没有 `tags` 字段）
  - `MediaAssetDao.upsert(asset)`、`MediaAssetDao.observeByDream(dreamId): Flow<List<MediaAssetEntity>>`
  - `GenerationJobDao.insert(job: GenerationJobEntity)`、`GenerationJobDao.observeByDream(dreamId: String): Flow<List<GenerationJobEntity>>`
  - `SeedData.seedIfEmpty(db: DreamDatabase)`（幂等：库里已有梦就返回）
  - `DreamDatabase.create(context: Context): DreamDatabase`

- [ ] **Step 1: 先改构建：schema 导出、room-testing、种子开关**

`android/app/build.gradle.kts` 里补三处（其余保持 Task 1 原样）：

```kotlin
android {
    // ...（namespace / compileSdk / defaultConfig 原有内容保持不变）

    defaultConfig {
        // ... 原有内容保持不变，加一行：
        // 种子数据开关：true 时首次启动写入 4 条示例梦境（见本任务 Step 7）
        buildConfigField("boolean", "SEED_DEMO_DATA", "true")
    }

    buildFeatures {
        compose = true
        buildConfig = true   // AGP 9.5 的默认值未实测；BuildConfig 里有手写字段，必须显式开
    }
}

// 与 android { } 同级，放在文件末尾
ksp {
    // Room schema 导出目录：产物必须提交入库（app/schemas/.../1.json）
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

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
    fun `full text search finds a word inside the transcript`() = runTest {
        dao.upsert(dream("1", "2026-10-01", "电梯里有人按了六楼"))
        dao.upsert(dream("2", "2026-10-02", "教室最后一排"))
        val got = dao.search("电梯")
        assertEquals(listOf("1"), got.map { it.id })
    }

    @Test
    fun `search also matches the novel text`() = runTest {
        dao.upsert(dream("1", "2026-10-01").copy(novelText = "他在楼道尽头回头看我"))
        val got = dao.search("楼道")
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
    fun `generation job is stored against a dream`() = runTest {
        dao.upsert(dream("1", "2026-10-01"))
        db.generationJobDao().insert(
            GenerationJobEntity(
                id = "g1", dreamId = "1", kind = "NOVELIZE", status = "PENDING",
                provider = null, model = null, promptId = "novelize", promptVersion = 1,
                promptHash = "abc123", temperature = 0.7, tokensIn = null, tokensOut = null,
                error = null, createdAt = 0L,
            )
        )
        val jobs = db.generationJobDao().observeByDream("1").first()
        assertEquals(1, jobs.size)
        assertEquals("NOVELIZE", jobs.first().kind)
    }

    @Test
    fun `tags for a dream come from the join table`() = runTest {
        dao.upsert(dream("1", "2026-10-01"))
        db.tagDao().upsert(TagEntity("t1", "电梯", TagKind.OBJECT))
        db.tagDao().upsert(TagEntity("t2", "坠落", TagKind.EMOTION))
        db.tagDao().linkDreamTag(DreamTagEntity("1", "t1"))
        val tags = db.tagDao().observeTagsForDream("1").first()
        assertEquals(listOf("t1"), tags.map { it.id })
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

`data/db/GenerationJobEntity.kt`（生成任务台账。Plan 1 只建表，不产生任务；Plan 3 写入）：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

object GenKind { const val NOVELIZE = "NOVELIZE"; const val TAGS = "TAGS"; const val STT = "STT" }
object GenStatus { const val PENDING = "PENDING"; const val RUNNING = "RUNNING"
                   const val DONE = "DONE"; const val FAILED = "FAILED" }

/**
 * 每次调模型都留一条记录：花了多少 token、用的哪个提示词版本、失败原因是什么。
 * 设计 §9 要求「成本可见」与「绝不静默丢弃」，所以这张表是审计面，不是缓存。
 *
 * 注意：**不建 PromptVersion 表**。提示词的真源是仓库根的 `prompts/` 文件，
 * 表里只存 (promptId, promptVersion, promptHash) 三元组——这样提示词改动走 git diff，
 * 而不是走数据库迁移；也不会出现「库里那份提示词和文件里那份不一致」的分裂。
 */
@Entity(tableName = "generation_jobs", indices = [androidx.room.Index("dreamId")])
data class GenerationJobEntity(
    @PrimaryKey val id: String,
    val dreamId: String,
    val kind: String,                 // GenKind
    val status: String,               // GenStatus
    val provider: String? = null,     // 失败时可能还没选到 provider
    val model: String? = null,
    val promptId: String? = null,
    val promptVersion: Int? = null,
    val promptHash: String? = null,   // 提示词内容哈希，用于追溯"当时发的到底是哪版"
    val temperature: Double? = null,
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    val error: String? = null,
    val createdAt: Long = 0L,
)
```

`data/db/DreamFtsEntity.kt`（FTS4 内容表，Room 自动与 `dreams` 同步）：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

@Fts4(contentEntity = DreamEntity::class, order = FtsOptions.Order.DESC)
@Entity(tableName = "dreams_fts")
data class DreamFtsEntity(
    val title: String?,
    val rawTranscript: String,
    val faithfulText: String?,
    val novelText: String?,
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

    @Query("SELECT * FROM dreams WHERE deletedAt IS NULL ORDER BY dreamDate DESC, createdAt DESC")
    fun observeAll(): Flow<List<DreamEntity>>

    @Query("SELECT * FROM dreams WHERE id = :id")
    fun observeById(id: String): Flow<DreamEntity?>

    @Query("""
        SELECT dreams.* FROM dreams
        JOIN dreams_fts ON dreams.rowid = dreams_fts.rowid
        WHERE dreams_fts MATCH :ftsQuery AND dreams.deletedAt IS NULL
        ORDER BY dreams.dreamDate DESC
    """)
    suspend fun searchRaw(ftsQuery: String): List<DreamEntity>
}
```

> **注意**：`dreamDate` 是 `LocalDate`，经 TypeConverter 存成 `yyyy-MM-dd` 字符串，所以 `BETWEEN :fromIso AND :toIso` 是字符串比较——ISO 日期按字典序比较与按时间比较结果一致，这是安全的。中文检索必须给每段加引号：`search()` 的封装放在 Repository（Task 5），把用户输入切成 `"词1" AND "词2"`。

> **已知风险（中文 FTS，开工前就要知道）**：SQLite 的 FTS4 默认 tokenizer（`simple`）**不按词切分中文**，它把一整段连续汉字当成一个 token。后果是 `search("电梯")` 对正文「电梯里有人按了六楼」**可能直接失配**（整条正文是一个 token，`"电梯"` 这个短语匹配不到它），`search("电")` 更必然失配。这是 FTS 的中文通病，不是我们代码写错。
>
> 处置顺序（别提前优化，但亮红灯时别改错方向）：
> 1. 先跑 `DreamDaoTest` 里的 `full text search finds a word inside the transcript`。**绿了就继续，一行都不要动**——不同 SQLite/Room 版本对 CJK 的实际行为有出入，实测优先于推理。
> 2. 若红灯，兜底方案是**写入前切分**：入库 FTS 的那份文本按「汉字双字切分」处理（`电梯里有人` → `电梯 梯里 里有 有人`），`dreams` 表本身仍存原文（原文一个字都不能改）；查询时对 query 做同样的双字切分，再拼成短语 `"电梯" AND "梯里"` 匹配。
> 3. 这条兜底会动两处：`DreamFtsEntity`（`@Fts4(contentEntity = ...)` 的自动同步做不了文本变换，必须改成手动维护 FTS 表或在实体上加"切分后"的列）与 Repository 的匹配函数 `search()`。具体形态等红灯再定，**定了必须写进 task4 的证据文件**，并在设计文档 §15 登记这条决策。

`data/db/TagDao.kt`：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(tag: TagEntity)
    @Query("SELECT * FROM tags ORDER BY kind, name") fun observeAll(): Flow<List<TagEntity>>
    @Query("UPDATE tags SET name = :newName WHERE id = :id") suspend fun rename(id: String, newName: String)
    @Query("UPDATE OR REPLACE dream_tags SET tagId = :intoId WHERE tagId = :fromId")
    suspend fun repointDreamTags(fromId: String, intoId: String)
    @Query("DELETE FROM tags WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT COUNT(*) FROM tags") suspend fun count(): Int

    /** 建立 梦 ⇄ 标签 关联（Plan 3 的自动打标与 Plan 1 的种子数据都走这里） */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun linkDreamTag(link: DreamTagEntity)

    /**
     * 详情页「标签」tab 的数据源。
     * 不能用 `dream.tags`——`Dream` 领域模型上没有这个字段，也不该有：
     * 标签是多对多关系，塞进 Dream 会让每次读梦都多一次联表（设计 §5 的关联表就是为此存在）。
     */
    @Query("""
        SELECT tags.* FROM tags
        JOIN dream_tags ON tags.id = dream_tags.tagId
        WHERE dream_tags.dreamId = :dreamId
        ORDER BY tags.kind, tags.name
    """)
    fun observeTagsForDream(dreamId: String): Flow<List<TagEntity>>
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

`data/db/GenerationJobDao.kt`（Plan 1 只要两条：写得进、按 dream 查得出）：

```kotlin
package com.dreamkeeper.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GenerationJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(job: GenerationJobEntity)
    @Query("SELECT * FROM generation_jobs WHERE dreamId = :dreamId ORDER BY createdAt DESC")
    fun observeByDream(dreamId: String): Flow<List<GenerationJobEntity>>
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
                MediaAssetEntity::class, GenerationJobEntity::class, DreamFtsEntity::class],
    version = 1,
    exportSchema = true,      // 必须 true：schema json 是 migration 的唯一对照物
)
@TypeConverters(Converters::class)
abstract class DreamDatabase : RoomDatabase() {
    abstract fun dreamDao(): DreamDao
    abstract fun tagDao(): TagDao
    abstract fun mediaAssetDao(): MediaAssetDao
    abstract fun generationJobDao(): GenerationJobDao

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

`data/db/SeedData.kt`（4 条固定 id 的梦，让 Plan 1 每个界面都有东西可看；其中 1 条带 `EMOTION` 标签 + 非空 `evidenceText`，1 条带 `VIDEO` 素材占位，1 条带现成的小说化稿）：

```kotlin
package com.dreamkeeper.data.db

import java.time.LocalDate

/**
 * 演示用种子数据。
 *
 * 幂等：库里已有任何一条梦就整体跳过（`dao.count() == 0` 才写）。
 * 门闩：调用点用 `BuildConfig.SEED_DEMO_DATA` 包住（见 Task 5 的 DreamKeeperApp），
 *       把 buildConfigField 改成 "false" 就整体消失，不需要删代码。
 * 定位：这是"看得见的东西"，不是测试夹具——测试仍自己造数据，不依赖种子。
 */
object SeedData {

    const val DREAM_ELEVATOR = "seed-dream-1"
    const val DREAM_CLASSROOM = "seed-dream-2"
    const val DREAM_OLD_HOUSE = "seed-dream-3"
    const val DREAM_STAIRS = "seed-dream-4"
    const val TAG_EMOTION_FALL = "seed-tag-emotion-fall"
    const val MEDIA_VIDEO_CLASSROOM = "seed-media-1"

    suspend fun seedIfEmpty(db: DreamDatabase) {
        val dreams = db.dreamDao()
        if (dreams.count() != 0) return   // 幂等：只写一次

        // 1) 电梯：带一个 EMOTION 标签，evidenceText 非空（设计 §4 要求标签有出处）
        dreams.upsert(dream(DREAM_ELEVATOR, "2026-10-20", "电梯",
            "我在电梯里，电梯一直往上，按不到一楼。数字过了三十还在涨。"))
        db.tagDao().upsert(TagEntity(TAG_EMOTION_FALL, "下坠的预感", TagKind.EMOTION,
            TagSource.AUTO, "电梯一直往上，按不到一楼"))
        db.tagDao().linkDreamTag(DreamTagEntity(DREAM_ELEVATOR, TAG_EMOTION_FALL))

        // 2) 教室：带一个 VIDEO 素材占位（设计 §12「梦 → 视频」的预留口子）
        dreams.upsert(dream(DREAM_CLASSROOM, "2026-10-24", "教室",
            "教室最后一排，黑板上的字在动，像水。同桌递给我一张纸，上面只写了我的名字。"))
        db.mediaAssetDao().upsert(
            MediaAssetEntity(MEDIA_VIDEO_CLASSROOM, DREAM_CLASSROOM, MediaType.VIDEO,
                path = null, prompt = null, model = null, status = "PENDING", createdAt = 0L)
        )

        // 3) 老房子：已有小说化稿，验证「小说化·补写N处」tab 非空时的排版
        dreams.upsert(dream(DREAM_OLD_HOUSE, "2026-10-27", "老房子",
            "老房子，走廊很长，尽头有光。我妈在厨房叫我，声音从楼上传来。").copy(
            novelText = "老房子的走廊比记忆里长。尽头有一扇窗，光从那里斜进来，把地板切成两半。厨房里有人在叫我的名字——可那个声音是从楼上来的。",
            novelVersion = 1,
            novelChangeLog = "补写 2 处：走廊的光、声音的方向",
        ))

        // 4) 楼梯：无标题、正文极短，验证列表里的短条目排版
        dreams.upsert(dream(DREAM_STAIRS, "2026-10-28", null, "一直在下楼梯，每一层都一样。"))
    }

    private fun dream(id: String, date: String, title: String?, text: String) = DreamEntity(
        id = id,
        ownerId = "local",
        dreamDate = LocalDate.parse(date),
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

> **注入点不在本任务**：Task 4 还没有 `AppContainer`，所以这里只定义 `seedIfEmpty`；真正的调用在 Task 5 的 `DreamKeeperApp.onCreate()`，条件是 `if (BuildConfig.SEED_DEMO_DATA) SeedData.seedIfEmpty(db)`（`count() == 0` 的判断已在 `seedIfEmpty` 里，两处不要重复写）。

- [ ] **Step 8: 跑测试确认通过**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*DreamDaoTest*" --no-daemon
```

Expected: `BUILD SUCCESSFUL`，**8 个测试通过**；并确认 `android/app/schemas/com.dreamkeeper.data.db.DreamDatabase/1.json` 已生成（`git status` 里应看到它是未跟踪文件），把它一起提交。

- 若 FTS 查询报 `no such column: dreams_fts.rowid`：把 `@Fts4` 改为 `@Fts4(contentEntity = DreamEntity::class)` 并**去掉** `order` 参数后重试。
- 若 `full text search finds a word inside the transcript` 红：按 Step 5 的「已知风险」走双字切分兜底，**不要去改测试迁就实现**（改测试等于把中文搜索的 bug 焊进基线）。

- [ ] **Step 9: 提交**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(data): Room 实体、FTS4 全文索引、生成任务台账与种子数据"
git rev-parse --short HEAD
```

- [ ] **Step 10: 归档证据**

按 §2 写 `docs/evidence/plan1/task4.md`：short SHA、测试命令与末尾输出（`8 tests completed`）、`app/schemas/.../1.json` 的路径与一句话说明（它是 v1 结构基线）、种子注入后的库快照（若有）、复现步骤。然后 `git add docs/evidence/plan1; git commit -m "docs(evidence): task4 证据归档"`。

---

## Task 5: 仓库层 —— 领域模型与数据库之间的唯一翻译

**Files:**
- Create: `.../domain/Dream.kt`, `.../domain/Tag.kt`, `.../domain/MediaAsset.kt`, `.../data/DreamRepository.kt`, `.../di/AppContainer.kt`
- Modify: `.../DreamKeeperApp.kt`（建容器 + 种子注入）
- Test: `android/app/src/test/java/com/dreamkeeper/data/DreamRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 4 的 DAO 与实体。
- Produces:
  - `data class Dream(val id: String, val date: LocalDate, val title: String?, val rawTranscript: String, val faithfulText: String?, val novelText: String?, val novelChangeLog: String?, val mood: String?, val isDraft: Boolean)`
  - `data class Tag(val id: String, val name: String, val kind: String, val source: String, val evidenceText: String?)`
  - `data class MediaAsset(val id: String, val dreamId: String, val type: String, val path: String?, val prompt: String?, val model: String?, val status: String, val createdAt: Long)`
  - `class DreamRepository(private val db: DreamDatabase, private val clock: () -> Long = System::currentTimeMillis)`
    - `fun observeMonth(month: YearMonth): Flow<List<Dream>>`
    - `fun observeAll(): Flow<List<Dream>>`
    - `fun observe(id: String): Flow<Dream?>`
    - `suspend fun save(dream: Dream)`
    - `suspend fun search(rawQuery: String): List<Dream>`
    - `fun observeTags(): Flow<List<Tag>>`
    - `fun observeTagsForDream(dreamId: String): Flow<List<Tag>>`（详情页标签 tab）
    - `fun observeMediaAssets(dreamId: String): Flow<List<MediaAsset>>`（详情页素材 tab）
  - `class AppContainer(context: Context) { val database: DreamDatabase; val repository: DreamRepository }`

- [ ] **Step 1: 写失败的测试（重点测 FTS 查询的转义）**

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

`data/DreamRepository.kt` 的关键实现（FTS 转义的细节是这段代码的全部价值）：

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

    fun observeAll(): Flow<List<Dream>> = dreams.observeAll().map { list -> list.map(::toDomain) }
    fun observe(id: String): Flow<Dream?> = dreams.observeById(id).map { it?.let(::toDomain) }
    fun observeTags(): Flow<List<Tag>> = tags.observeAll().map { list -> list.map(::tagToDomain) }

    /** 详情页标签 tab：联 dream_tags，绝不能读"Dream 上的 tags"（那个字段不存在也不该存在） */
    fun observeTagsForDream(dreamId: String): Flow<List<Tag>> =
        tags.observeTagsForDream(dreamId).map { list -> list.map(::tagToDomain) }

    /** 详情页素材 tab：Plan 1 里通常是空表，空态文案由 UI 决定 */
    fun observeMediaAssets(dreamId: String): Flow<List<MediaAsset>> =
        assets.observeByDream(dreamId).map { list -> list.map(::mediaToDomain) }

    suspend fun save(dream: Dream) {
        dreams.upsert(
            DreamEntity(
                id = dream.id, ownerId = "local", dreamDate = dream.date,
                createdAt = clock(), title = dream.title,
                rawTranscript = dream.rawTranscript, faithfulText = dream.faithfulText,
                novelText = dream.novelText, novelChangeLog = dream.novelChangeLog,
                mood = dream.mood, isDraft = dream.isDraft, updatedAt = clock(),
            )
        )
    }

    /** 用户输入 -> FTS4 查询串：按空白切词，每词加引号以转义标点，词间 AND。 */
    suspend fun search(rawQuery: String): List<Dream> {
        val terms = rawQuery.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        val fts = terms.joinToString(" AND ") { "\"" + it.replace("\"", "") + "\"" }
        return dreams.searchRaw(fts).map(::toDomain)
    }

    private fun toDomain(e: DreamEntity) = Dream(
        id = e.id, date = e.dreamDate, title = e.title, rawTranscript = e.rawTranscript,
        faithfulText = e.faithfulText, novelText = e.novelText,
        novelChangeLog = e.novelChangeLog, mood = e.mood, isDraft = e.isDraft,
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

class AppContainer(context: Context) {
    val database: DreamDatabase by lazy { DreamDatabase.create(context.applicationContext) }
    val repository: DreamRepository by lazy { DreamRepository(database) }
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

Expected: `BUILD SUCCESSFUL`，5 个测试通过（尤其 `search tolerates quotes and punctuation` 必须过）。

- [ ] **Step 6: 提交**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(data): 仓库层、FTS 查询转义与种子注入"
git rev-parse --short HEAD
```

- [ ] **Step 7: 归档证据**

按 §2 写 `docs/evidence/plan1/task5.md`：short SHA、`DreamRepositoryTest` 的 5 个测试通过输出、`Dream`/`Tag`/`MediaAsset` 三个领域类的字段清单（一句话核对与 Task 4 实体一致）、复现步骤。然后 `git add docs/evidence/plan1; git commit -m "docs(evidence): task5 证据归档"`。

---

## Task 6: 日历首屏（梦匣真正的脸）

**Files:**
- Create: `.../ui/calendar/CalendarViewModel.kt`, `.../ui/calendar/CalendarScreen.kt`, `.../ui/DreamKeeperRoot.kt`
- Modify: `.../MainActivity.kt`
- Test: `android/app/src/test/java/com/dreamkeeper/ui/calendar/CalendarViewModelTest.kt`

**Interfaces:**
- Consumes: `DreamRepository`、`DreamCalendar.monthGrid`、`Dream`。
- Produces:
  - `data class CalendarUiState(val month: YearMonth, val grid: List<LocalDate?>, val daysWithDreams: Set<LocalDate>, val title: String)`
  - `class CalendarViewModel(repo: DreamRepository, initial: YearMonth) : ViewModel()`，方法 `fun nextMonth()`、`fun prevMonth()`、`fun goToday()`，暴露 `val state: StateFlow<CalendarUiState>`
  - `@Composable fun CalendarScreen(vm: CalendarViewModel, onDayClick: (LocalDate) -> Unit, onRecord: () -> Unit)`
  - `@Composable fun DreamKeeperRoot(repo: DreamRepository)` —— 底部两 tab（日历 / 全部）+ 全局搜索入口

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

    @Test fun `starts on the given month and shows dots only for that month`() = runTest(dispatcher) {
        val vm = CalendarViewModel(repo, YearMonth.of(2026, 10))
        advanceUntilIdle()
        assertEquals("2026年10月", vm.state.value.title)
        assertEquals(31, vm.state.value.grid.filterNotNull().size)
    }

    @Test fun `next and prev month change the title`() = runTest(dispatcher) {
        val vm = CalendarViewModel(repo, YearMonth.of(2026, 12))
        advanceUntilIdle()
        vm.nextMonth(); advanceUntilIdle()
        assertEquals("2027年1月", vm.state.value.title)
        vm.prevMonth(); vm.prevMonth(); advanceUntilIdle()
        assertEquals("2026年11月", vm.state.value.title)
    }

    @Test fun `goToday returns to the current month`() = runTest(dispatcher) {
        val vm = CalendarViewModel(repo, YearMonth.of(2020, 1))
        advanceUntilIdle()
        vm.goToday(); advanceUntilIdle()
        assertEquals(YearMonth.now(), vm.state.value.month)
    }

    @Test fun `day with a dream is exposed in daysWithDreams`() = runTest(dispatcher) {
        repo.save(Dream("1", LocalDate.of(2026, 10, 15), null, "电梯", "电梯", null, null, null, false))
        val vm = CalendarViewModel(repo, YearMonth.of(2026, 10))
        advanceUntilIdle()
        assertTrue(LocalDate.of(2026, 10, 15) in vm.state.value.daysWithDreams)
        assertFalse(LocalDate.of(2026, 10, 16) in vm.state.value.daysWithDreams)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*CalendarViewModelTest*" --no-daemon
```

Expected: 编译失败（`Unresolved reference: CalendarViewModel`）。

- [ ] **Step 3: 写 ViewModel**

要点：月份状态用 `MutableStateFlow<YearMonth>`，与仓库 `flatMapLatest` 组合；标题格式 `"${year}年${monthValue}月"`。

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

- [ ] **Step 4: 跑测试确认通过**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*CalendarViewModelTest*" --no-daemon
```

Expected: `BUILD SUCCESSFUL`，4 个测试通过。

- [ ] **Step 5: 写日历界面**

`ui/calendar/CalendarScreen.kt` 的结构要求（实现细节自由，但这些必须做到）：

- 顶部：左箭头 `‹`、中间 `state.title`、右箭头 `›`；标题下面一行小字「共 N 天有梦」。
- 星期表头：一 二 三 四 五 六 日，`labelSmall` + `InkFaint`。
- 7 列网格：有梦的日子在数字下方画一个 4dp 的 `Ember` 圆点；今天用 1dp `Line` 描边圈住；空白格不画。
- 点有梦的格子 → `onDayClick(date)`；点没梦的格子 → 什么都不发生（不弹"添加"——添加只有一个入口，就是红钮）。
- 底部：一大颗圆钮（直径 72dp，`EmberDeep`），文案「说梦」。`onRecord()` 现在先留空实现，Plan 2 接管。
- 整屏可上下滑动，月份切换用 `AnimatedContent`，**动画时长 ≤ 200ms**（夜间不要长动画）。
- 屏幕常亮：`Modifier` 层面不加；常亮在 Plan 4 统一处理。

`ui/DreamKeeperRoot.kt`：

```kotlin
@Composable
fun DreamKeeperRoot(repo: DreamRepository) {
    var tab by remember { mutableStateOf(0) }
    val calendarVm: CalendarViewModel = viewModel { CalendarViewModel(repo) }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Night) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = {}, label = { Text("日历") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = {}, label = { Text("全部") })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> CalendarScreen(calendarVm, onDayClick = {}, onRecord = {})
                else -> LibraryScreen(...)   // Task 7 实现
            }
        }
    }
}
```

- [ ] **Step 6: 接进 MainActivity 并截图验收**

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ev="D:\DreamKeeper\docs\evidence\plan1"
cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon
cd D:\DreamKeeper
& $adb logcat -c
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am force-stop com.dreamkeeper
& $adb shell am start -n com.dreamkeeper/.MainActivity
Start-Sleep 3
cmd /c "`"$adb`" exec-out screencap -p > $ev\plan1-task6-a.png"
$b=[System.IO.File]::ReadAllBytes("$ev\plan1-task6-a.png")[0..7]
if (($b -join ',') -ne '137,80,78,71,13,10,26,10') { throw "PNG 魔数校验失败: $($b -join ',')" }
```

Expected：
- 静态图 `plan1-task6-a.png` 显示当月标题、星期表头、网格、底部「说梦」钮。
- 种子数据（Task 4/5 注入的 4 条，日期在 2026-10）让当月 **4 个红点** 出现在 20/24/27/28 四天。
- 点右箭头后再截图 `plan1-task6-b.png`，标题变成下个月，**且网格行数随之变化**（有的月份 5 行、有的 6 行）。

```powershell
& $adb shell input tap 950 250   # 右箭头大致位置，按实际截图调整
Start-Sleep 1
cmd /c "`"$adb`" exec-out screencap -p > $ev\plan1-task6-b.png"
$b=[System.IO.File]::ReadAllBytes("$ev\plan1-task6-b.png")[0..7]
if (($b -join ',') -ne '137,80,78,71,13,10,26,10') { throw "PNG 魔数校验失败: $($b -join ',')" }
& $adb logcat -d | Out-File -Encoding utf8 "$ev\task6-logcat.txt"
```

**两张图都要 read_image 确认**，不能只看安装成功。

- [ ] **Step 7: 提交**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(ui): 日历首屏（月份切换、有梦红点、说梦大钮）"
git rev-parse --short HEAD
```

- [ ] **Step 8: 归档证据**

按 §2 写 `docs/evidence/plan1/task6.md`：short SHA、APK 与两张截图的 SHA256、两张图各自的人工读图结论（写下"看到了几个红点、标题是几月、网格几行"，不是"看起来正常"）、`task6-logcat.txt`、复现步骤（含点右箭头的坐标）。然后 `git add docs/evidence/plan1; git commit -m "docs(evidence): task6 证据归档"`。

---

## Task 7: 全部梦境列表（时间轴 / 卡片流切换）

**Files:**
- Create: `.../ui/library/LibraryViewModel.kt`, `.../ui/library/LibraryScreen.kt`, `.../ui/library/LayoutPrefs.kt`
- Modify: `.../ui/DreamKeeperRoot.kt`, `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`
- Test: `android/app/src/test/java/com/dreamkeeper/ui/library/LibraryViewModelTest.kt`

**Interfaces:**
- Produces:
  - `enum class LibraryLayout { TIMELINE, CARDS }`
  - `interface LayoutPrefs { var layout: LibraryLayout }`（测试用 `FakeLayoutPrefs`，生产用 `DataStoreLayoutPrefs`）
  - `class DataStoreLayoutPrefs(context: Context) : LayoutPrefs`（DataStore Preferences 落盘，**不是只有接口**）
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
        advanceUntilIdle()
        assertEquals(listOf("2","1"), vm.state.value.items.map { it.id })
    }

    @Test fun `layout choice is persisted`() = runTest(dispatcher) {
        val prefs = FakeLayoutPrefs()
        val vm = LibraryViewModel(repo, prefs)
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

**先加依赖与开关**（否则 `preferencesDataStore` 编译不过）：

```toml
# android/gradle/libs.versions.toml 的 [versions]
datastore = "1.1.7"

# [libraries]
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
```

```kotlin
// android/app/build.gradle.kts 的 dependencies { } 里
implementation(libs.androidx.datastore.preferences)
```

> 版本号如果解析失败，去 `https://maven.google.com/androidx/datastore/datastore-preferences/maven-metadata.xml` 挑当前稳定版，别猜。

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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

interface LayoutPrefs { var layout: LibraryLayout }

private val Context.layoutDataStore: DataStore<Preferences> by preferencesDataStore(name = "layout_prefs")

private val KEY_LAYOUT = stringPreferencesKey("library_layout")

/**
 * 生产实现：值放内存缓存里同步读写，落盘走 IO。
 *
 * 为什么 init 里要 runBlocking：`LayoutPrefs.layout` 是同步属性（ViewModel 构造时就要拿到初值），
 * 而 DataStore 的读是 suspend 的。这里用 100ms 上限把首值读出来，读不到就退回 TIMELINE——
 * 一个偏好值不值得让首屏闪一下视图切换。Plan 2 若发现首帧抖动，改成异步首帧校正。
 */
class DataStoreLayoutPrefs(context: Context) : LayoutPrefs {
    private val dataStore = context.applicationContext.layoutDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cached: LibraryLayout = runBlocking {
        withTimeoutOrNull(100) {
            val raw = runCatching { dataStore.data.first()[KEY_LAYOUT] }.getOrNull()
            LibraryLayout.entries.firstOrNull { it.name == raw }
        } ?: LibraryLayout.TIMELINE
    }

    override var layout: LibraryLayout
        get() = cached
        set(value) {
            cached = value
            scope.launch { dataStore.edit { it[KEY_LAYOUT] = value.name } }
        }
}
```

`LibraryViewModel` 组合 `repo.observeAll()` 与一个 `MutableStateFlow<LibraryLayout>`（初值取 `prefs.layout`），`setLayout` 同时写回 `prefs`。

> `FakeLayoutPrefs` 只出现在测试里（Step 1 已给），不要在生产代码里造第二个实现。

- [ ] **Step 4: 跑测试确认通过**

Expected: 2 个测试通过。

- [ ] **Step 5: 写界面**

要求：
- 顶部一条：标题「全部梦」（`titleLarge`）+ 右侧搜索图标（`onSearch()`）。
- 右侧一个分段控件切 `时间轴 / 卡片`（两段，选中态用 `Ember` 描边，不用填充色块）。
- **时间轴**：左侧一条 1dp `Line` 竖线，每条一个 `Ember` 圆点 + 日期（`labelSmall`，`InkFaint`）+ 标题或首行（`bodyMedium`，`Paper`），条目间距 20dp。
- **卡片流**：圆角 12dp 的 `NightRaised` 卡片，内边距 16dp，日期在上、正文取前两行（`maxLines = 2`，`TextOverflow.Ellipsis`）。
- 空状态：居中一段 `InkDim` 文字「还没有梦。醒来的时候，按下面那个钮。」+ 一个向下指的小箭头。
- 点任意条目 → `onOpen(dream.id)`。

- [ ] **Step 6: 装到模拟器截图验收**

数据不用临时造了：Task 4 的 `SeedData` + Task 5 的启动注入已经给了 4 条固定 id 的种子梦。这里只需先清一次应用数据，确认「空库 → 注入 4 条」这条路真的通。

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ev="D:\DreamKeeper\docs\evidence\plan1"
cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon
cd D:\DreamKeeper
& $adb shell pm clear com.dreamkeeper          # 清库，制造"首次启动"
& $adb logcat -c
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.dreamkeeper/.MainActivity
Start-Sleep 5                                    # 种子里有 2 次 insert + 关联，多等几秒
& $adb shell input tap 400 230                   # 底部"全部"入口，按实际截图调整
Start-Sleep 2
cmd /c "`"$adb`" exec-out screencap -p > $ev\plan1-task7-timeline.png"
$b=[System.IO.File]::ReadAllBytes("$ev\plan1-task7-timeline.png")[0..7]
if (($b -join ',') -ne '137,80,78,71,13,10,26,10') { throw "PNG 魔数校验失败: $($b -join ',')" }
```

Expected：
- `plan1-task7-timeline.png`：**4 条**（不是 3 条——种子在 Task 4 定的是 4 条），左侧竖线与圆点对齐，日期 10-20 / 10-24 / 10-27 / 10-28 倒序。
- 点分段控件切卡片后 `plan1-task7-cards.png`：卡片出现圆角与内边距，仍 4 条。
- 杀掉进程重进（`am force-stop` 再 `am start`），**仍是卡片视图**（证明偏好已落盘，不是只活在内存里）。
- 再跑一次 `pm clear` + 重启，列表回到 4 条而不是 8 条（幂等，证明 `count() == 0` 判断生效）。

```powershell
& $adb shell am force-stop com.dreamkeeper
& $adb shell am start -n com.dreamkeeper/.MainActivity
Start-Sleep 4
cmd /c "`"$adb`" exec-out screencap -p > $ev\plan1-task7-cards-after-restart.png"
$b=[System.IO.File]::ReadAllBytes("$ev\plan1-task7-cards-after-restart.png")[0..7]
if (($b -join ',') -ne '137,80,78,71,13,10,26,10') { throw "PNG 魔数校验失败: $($b -join ',')" }
& $adb logcat -d | Out-File -Encoding utf8 "$ev\task7-logcat.txt"
```

- [ ] **Step 7: 提交**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(ui): 全部梦境列表（时间轴/卡片流切换并记忆）"
git rev-parse --short HEAD
```

- [ ] **Step 8: 归档证据**

按 §2 写 `docs/evidence/plan1/task7.md`：short SHA、DataStore 依赖版本、三张截图路径 + SHA256、人工读图结论（4 条、日期顺序、卡片圆角、重启后仍是卡片）、`task7-logcat.txt`、`pm clear` 复现步骤（含证明幂等的第二次清库结果）。然后 `git add docs/evidence/plan1; git commit -m "docs(evidence): task7 证据归档"`。

---

## Task 8: 详情页 + 全文搜索

**Files:**
- Create: `.../ui/detail/DetailScreen.kt`, `.../ui/detail/DetailViewModel.kt`, `.../ui/search/SearchScreen.kt`, `.../ui/search/SearchViewModel.kt`
- Modify: `.../ui/DreamKeeperRoot.kt`

**Interfaces:**
- Consumes: `DreamRepository.observe(id)`、`DreamRepository.search(q)`、`DreamRepository.observeTagsForDream(dreamId)`（标签 tab 的唯一数据源）、`DreamRepository.observeMediaAssets(dreamId)`（素材 tab 的唯一数据源）。
- Produces:
  - `@Composable fun DetailScreen(dreamId: String, repo: DreamRepository, onBack: () -> Unit)`
  - `@Composable fun SearchScreen(repo: DreamRepository, onOpen: (String) -> Unit, onBack: () -> Unit)`
  - `class SearchViewModel(repo: DreamRepository) : ViewModel()`，`fun onQueryChange(q: String)`，`val results: StateFlow<List<Dream>>`（250ms 防抖）

- [ ] **Step 1: 写搜索防抖的失败测试**

```kotlin
@Test fun `typing quickly only runs the last query`() = runTest(dispatcher) {
    repo.save(Dream("1", LocalDate.of(2026,10,1), null, "电梯里有人", "电梯里有人", null,null,null,false))
    repo.save(Dream("2", LocalDate.of(2026,10,2), null, "教室里考试", "教室里考试", null,null,null,false))
    val vm = SearchViewModel(repo)
    vm.onQueryChange("电")
    vm.onQueryChange("电梯")
    advanceTimeBy(300)
    advanceUntilIdle()
    assertEquals(listOf("1"), vm.results.value.map { it.id })
}
```

- [ ] **Step 2: 确认失败 → [ ] **Step 3: 实现（`debounce(250)` + `flatMapLatest` + `flowOn(Dispatchers.IO)`）→ [ ] **Step 4: 确认通过**

- [ ] **Step 5: 写详情页**

必须做到：

- 顶部返回箭头 + 居中日期（如「10月29日 周三」）。
- **四个 tab**（设计 §11 原文就是四个，不是三个）：**原样** / **小说化·补写N处** / **标签** / **素材**。
- tab 内容：
  - 原样：`dream.originalText`，`bodyLarge`，**行高 30sp**，段落间距 12dp，可滚动。
  - 小说化：有 `novelText` 就显示正文；没有就显示占位文案「还没有生成。等接上大模型后，这里会有一篇读得通的版本。」
  - 标签：**不能读 `dream.tags`（`Dream` 没有这个字段，也不该有）**，必须走仓库的联表查询：`repo.observeTagsForDream(dreamId)`；空表显示「暂无标签」。每个标签一个只读胶囊（Plan 1 不在详情页增删标签）。
  - 素材：读 `repo.observeMediaAssets(dreamId)`；空表显示「还没有素材」，非空先只显示类型与状态（`VIDEO`/`IMAGE` + `PENDING`），Plan 3 才做真实的图/视频展示。
- tab 标题里的 N 是改动处数，从 `novelChangeLog` 里数出来：

```kotlin
/** 小说化 tab 的标题：「小说化·补写2处」；没生成过就只写「小说化」 */
private fun novelTabLabel(dream: Dream): String {
    val n = dream.novelChangeLog
        ?.let { Regex("补写 (\\d+) 处").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?: 0
    return if (n == 0) "小说化" else "小说化·补写${n}处"
}
```

- 底部常驻一条播放条（`NightRaised`，圆角 8dp）：播放三角 + 一条占位波形（用 40 根 1dp 竖线高度随机但**固定种子**，不要每帧抖动）+ 时长文字「--:--」。Plan 2 接真实音频。
- 右上角一个 `⋮` 菜单，目前只有「编辑」「删除」两项，点了给 `Snackbar` 提示「Plan 2 实现」。
- 两个 Flow 的初值给空表，不要 `!!`：

```kotlin
val tags by repo.observeTagsForDream(dreamId).collectAsState(initial = emptyList())
val assets by repo.observeMediaAssets(dreamId).collectAsState(initial = emptyList())
```

- [ ] **Step 6: 装到模拟器，用种子数据走一遍**

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ev="D:\DreamKeeper\docs\evidence\plan1"
cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon
cd D:\DreamKeeper
& $adb logcat -c
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am force-stop com.dreamkeeper
& $adb shell am start -n com.dreamkeeper/.MainActivity
Start-Sleep 5
```

Expected：
- 进详情 → **四个 tab** 可切，原样正文可滚动。
- 打开 `seed-dream-1`（电梯）：标签 tab 里有「坠落（情绪）」胶囊——这是 Task 4 种子里唯一带 `EMOTION` 标签且 `evidenceText` 非空的那条。
- 打开 `seed-dream-2`（教室）：小说化 tab 显示占位文案；**素材 tab 显示一条 `VIDEO` 占位**（种子里的 `MediaAsset(kind=VIDEO)`）。
- 打开 `seed-dream-3`：小说化 tab 标题是「小说化·补写2处」，正文非空。
- 打开 `seed-dream-4` 或任一没有素材的梦：素材 tab 显示「还没有素材」。
- 点播放条 → 无崩溃（Plan 1 不播放）。
- 搜索「电梯」→ 命中 1 条；搜索「电梯 六楼」→ 命中更少或 0；搜索 `"！` 之类标点 → 不崩溃。

四个 tab 各截一张（**第 4 张 `plan1-task8-tab4-media.png` 是 Task 9「预留口子核对」要引用的那一张，别漏**）：

```powershell
& $adb shell input tap 540 620            # 列表第一条，进详情
Start-Sleep 2
cmd /c "`"$adb`" exec-out screencap -p > $ev\plan1-task8-tab4-media.png"
$b=[System.IO.File]::ReadAllBytes("$ev\plan1-task8-tab4-media.png")[0..7]
if (($b -join ',') -ne '137,80,78,71,13,10,26,10') { throw "PNG 魔数校验失败: $($b -join ',')" }
& $adb logcat -d | Out-File -Encoding utf8 "$ev\task8-logcat.txt"
```

> 四个 tab 的 tab 点击坐标随布局变化，按截图实际位置调整；每切一个 tab 截一张，文件名 `plan1-task8-tab1..4.png`，四张都要 read_image。

- [ ] **Step 7: 提交**

```powershell
cd D:\DreamKeeper
git add -A; git commit -m "feat(ui): 详情页四 tab 与全文搜索（含防抖与转义）"
git rev-parse --short HEAD
```

- [ ] **Step 8: 归档证据**

按 §2 写 `docs/evidence/plan1/task8.md`：short SHA、四张 tab 截图路径 + SHA256、人工读图结论（写了哪个梦、哪个 tab、看到了什么文案/胶囊）、`task8-logcat.txt`、复现步骤。**特别写明素材 tab 的两态都截到了：一条 VIDEO 占位 + 空态「还没有素材」**——Task 9 会引用。然后 `git add docs/evidence/plan1; git commit -m "docs(evidence): task8 证据归档"`。

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
    db.tagDao().linkDreamTag(DreamTagEntity("dream-1", "t2"))   // Task 4 已提供：直接写关联表
    assertEquals(1, db.tagDao().countTagsOn("dream-1").count { it == "t2" })
    val vm = TagsViewModel(repo, db.tagDao())
    vm.merge(fromId = "t2", intoId = "t1")
    advanceUntilIdle()
    assertEquals(listOf("t1"), db.tagDao().countTagsOn("dream-1"))
    assertNull(db.tagDao().findById("t2"))
}
```

> 需要在 `TagDao` 补两个查询（`linkDreamTag` 与 `upsert` Task 4 已有，别重复定义）：
> `@Query("SELECT tagId FROM dream_tags WHERE dreamId = :dreamId") suspend fun countTagsOn(dreamId: String): List<String>` 与
> `@Query("SELECT * FROM tags WHERE id = :id") suspend fun findById(id: String): TagEntity?`。
> `countTagsOn` 故意返回 id 列表而不是 `COUNT(*)`——测试要断言「剩哪一个」，只给数字断不出来。

- [ ] **Step 2: 确认失败 → **Step 3: `merge` 实现为事务**（`db.withTransaction { dao.repointDreamTags(from, into); dao.delete(from) }`）→ **Step 4: 确认通过**

- [ ] **Step 5: 写标签页**

要求：按 `kind` 分组（人物/地点/物品/动作/情绪/其他），每组一个标题；每个标签是一个可点的胶囊；长按弹菜单：重命名、合并到…（弹一个搜索框选目标）、删除。重命名后若重名，提示「已有同名标签，要合并吗？」。

- [ ] **Step 6: Plan 1 库级验收（这一步是交付判据，逐条打勾）**

> **验收范围**：只验 Plan 1 交付的「库」——日历、列表、详情、搜索、标签、种子、深色主题与启动画面。
> **不验**：录音、转写、小说化生成、夜间入口、设置页、回收站（这些根本没有代码，见 ACCEPTANCE.md 的归属表）。
> 名字从「全链路验收」改成「库级验收」就是为此——不写没做过的验收。

```powershell
$adb="C:\Users\17383\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ev="D:\DreamKeeper\docs\evidence\plan1"
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --no-daemon
cd D:\DreamKeeper
& $adb logcat -c
& $adb shell pm clear com.dreamkeeper
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am force-stop com.dreamkeeper
& $adb shell am start -W -n com.dreamkeeper/.MainActivity
Start-Sleep 3
cmd /c "`"$adb`" exec-out screencap -p > $ev\plan1-final-home.png"
$b=[System.IO.File]::ReadAllBytes("$ev\plan1-final-home.png")[0..7]
if (($b -join ',') -ne '137,80,78,71,13,10,26,10') { throw "PNG 魔数校验失败: $($b -join ',')" }
```

**冷启动连拍 3 帧，不许出现白帧**（真机/模拟器冷启动最容易露白的地方是 Activity 第一帧与 Splash 交接）：

```powershell
& $adb shell am force-stop com.dreamkeeper
Start-Sleep 1
& $adb shell am start -n com.dreamkeeper/.MainActivity
cmd /c "`"$adb`" exec-out screencap -p > $ev\plan1-coldstart-0ms.png"
Start-Sleep -Milliseconds 150
cmd /c "`"$adb`" exec-out screencap -p > $ev\plan1-coldstart-150ms.png"
Start-Sleep -Milliseconds 150
cmd /c "`"$adb`" exec-out screencap -p > $ev\plan1-coldstart-300ms.png"
Get-ChildItem $ev\plan1-coldstart-*.png | ForEach-Object {
  $h=[System.IO.File]::ReadAllBytes($_.FullName)[0..7]
  if (($h -join ',') -ne '137,80,78,71,13,10,26,10') { throw "PNG 魔数校验失败: $($_.Name)" }
}
```

> 时间点只能是「近似 0/150/300ms」——adb 抓一张图本身要 100–300ms，做不到毫秒级对齐。判定标准是**三张里没有一张整屏接近纯白**（人工 read_image 看，背景应是 `#0B0E13` 或 Splash 同色）。三张都要放进证据。

逐条确认（每条都要截图或命令输出作证）：

- [ ] 冷启动 2 秒内看到日历（`adb shell am start -W -n com.dreamkeeper/.MainActivity` 的 `TotalTime` < 2000）
- [ ] 冷启动三帧（`plan1-coldstart-0ms/150ms/300ms.png`）**无白帧**，Splash 与首屏背景同色
- [ ] 日历上下切 12 个月，不闪白、不崩
- [ ] 全部列表两种视图切换正常，杀进程重进保留偏好
- [ ] 搜索「电」命中，搜索纯标点不崩
- [ ] 详情**四个 tab** 可切（原样 / 小说化·补写N处 / 标签 / 素材）、原样正文可滚动、素材 tab 空态文案「还没有素材」出现过
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
| §14.4 | 小说化 ≥ 8/10「像人写的」，编造 ≤ 10% | Plan 3（生成与评测集） | 未开始 |
| §14.5 | 1000 条记录日历/列表滚动不掉帧 | **待定**：Plan 1 末或 Plan 2 初，由 Lead 裁 | 未开始 |
| §14.6 | 飞行模式可录音/转写/入队，恢复网络自动完成 | Plan 3（队列与重试） | 未开始 |
| §14.7 | 抓包验证全程无音频外传 | Plan 2（隐私验收） | 未开始 |

> §14.3 与 §14.4 的评测集条数在设计文档里一个是 20 条、一个是 10 条（`≥ 8/10`）。**这是待 Lead 裁定的第三件事**，本表不替它决定，照抄原文。§14.5 放 Plan 1 还是 Plan 2 也待定。

表二：Plan 1 自己做到的（可勾的写 ✅ 并附证据文件）

| 项 | 证据 | 状态 |
|---|---|---|
| 深色主题 + 无白启动画面 | `task2-*.png`、`plan1-coldstart-*.png` | §14 之外的 Plan 1 目标 |
| 日历首屏 + 有梦红点 | `task6-*.png` | 同上 |
| 全部列表两视图 + 偏好持久化 | `task7-*.png` | 同上 |
| 详情四 tab + 全文搜索 | `task8-tab1..4.png` | 同上 |
| 标签重命名/合并/删除 | `task9-*.png` | 同上 |
| 种子幂等（`pm clear` 后仍 4 条） | `task7/task9` 复现步骤 + logcat | 同上 |

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
3. **类型一致性**：`Dream` 的 9 个字段在 Task 5 定义、Task 6/7/8 的测试里逐字使用；`DreamEntity` 与 `Dream` 的字段名差异（`dreamDate` vs `date`）只存在于 `toDomain`/`save` 两处，已明确。`DreamEntity` 另外有 5 个 Plan 1 不用的预留字段（`rawAudioPath`/`rawAudioMs`/`faithfulEditedByUser`/`novelEditedByUser`/`remoteId`），全部带默认值且只在末尾追加——因为 3 处 `Dream(...)` 位置参数调用不能错位。`LayoutPrefs` 在 Task 7 定义为接口 + `DataStoreLayoutPrefs` 生产实现，测试用 `FakeLayoutPrefs`。
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
| D2 | 视觉回归手段 | §13 测试行：`JUnit + Turbine + Robolectric；UI 用 Roborazzi 截图` | 只有 JUnit + Robolectric；**没有 Turbine、没有 Roborazzi、没有 golden 目录**，视觉回归靠人工读截图 + 证据文件（见 Global Constraints「测试策略」） | **pending — 待用户裁定**：要不要现在搭 Roborazzi 基线。搭的话 Task 1/2/6/7/8 都要补 golden 用例，Plan 1 工期增加 |
| D3 | 详情页 tab 数 | §11：原样 / 小说化·补写N处 / 标签 / 素材（占位）——**四个** | 原计划写「三个 tab」（漏了素材），已按设计改为四个（Task 8 Step 5） | **已消除**，保留此行为记录：设计原文就是四个，是计划写错了 |
| D4 | 设置页 / 回收站 | §11 底部导航含「设置」，表格含「设置」「标签管理」两行；§5 有 `deletedAt` 软删除 | Plan 1 只做标签管理；设置页与回收站均不做，归 Plan 4（`deletedAt` 字段已建但无 UI 无写入） | **已声明范围**，非缺陷；读者不应把「Plan 1 完成」理解成设计 §11 全做完 |
| D5 | 提示词存哪 | §5：`PromptVersion(id, kind, version, body, createdAt) // 提示词进库，便于 A/B 与回滚` | **不建 `PromptVersion` 表**。提示词以 `prompts/` 下的文件为唯一真源，`GenerationJob` 只记 `(promptId, promptVersion, promptHash)` 三个溯源字段 | **待 Lead 确认**——这是本次修订按指示引入的偏差：省一张表换来「提示词改动走 git diff 而不是 SQL」，代价是 A/B 与回滚只能靠版本文件 |
| D6 | 生成任务的枚举与字段 | §5：`kind = FAITHFUL_CLEANUP \| NOVEL \| TAGGING \| TITLE \| CHANGELOG`；`status = PENDING \| RUNNING \| OK \| FAILED \| CANCELLED`；字段 `provider, model, promptVersion, tokensIn, tokensOut, error, createdAt` | `GenKind = NOVELIZE \| TAGS \| STT`；`GenStatus = PENDING \| RUNNING \| DONE \| FAILED`；字段额外有 `promptId`、`promptHash`、`temperature` | **分阶段实现**：Plan 1 只需这三类任务，其余常量在 Plan 3 开工前补进 `GenKind`/`GenStatus`（只许新增常量，不许改名已有的，否则历史行读不出来） |
| D7 | 时间字段类型 | §5：`deletedAt: Instant?`、`updatedAt: Instant` | 全部用 `Long`（epoch millis）：`createdAt`/`deletedAt`/`updatedAt` | **计划为准**（Room 存 `Instant` 要额外 TypeConverter，`LocalDate` 已有一个 converter，不叠加）；语义等价，精度到毫秒 |
| D8 | 评测集条数 | §14.3 写「评测集 **20** 条」；§14.4 写「≥ **8/10** 条」——设计文档自相矛盾 | 计划不裁决，两处都照抄 | **pending — 待 Lead 裁定**（20 还是 10，影响 Plan 3 的评测工作量与通过线） |
| D9 | §14.5 千条性能验收归属 | §14.5：1000 条记录滚动不掉帧（模拟器 + 真机） | Plan 1 不验（只有 4 条种子，测不出滚动性能）；也没写进 Plan 2/3/4 | **pending — 待 Lead 裁定**：放 Plan 1 末尾补一轮造数压测，还是顺延到 Plan 2（有真实数据写入后） |

> 登记规则：以后每次发现计划与设计不一致，先在这里加一行，再决定改哪边。**不允许**只在代码里悄悄偏离。
