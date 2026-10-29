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
- **测试策略（有意的取舍）**：业务逻辑与 DAO 用 JVM 单测（JUnit4 + Robolectric）；Compose 界面靠「构建 → 安装 → 截图」验收，不写 UI 单元测试。理由：Robolectric 下跑 Compose 断言收益低于维护成本，而截图回归已能抓住绝大数与视觉相关的回归。
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
│           │   │   ├── db\DreamDao.kt
│           │   │   ├── db\TagDao.kt
│           │   │   ├── db\SeedData.kt
│           │   │   └── DreamRepository.kt
│           │   ├── domain\
│           │   │   ├── Dream.kt
│           │   │   ├── Tag.kt
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
└── docs\superpowers\plans\            # 本文件所在
```

**职责一句话**：
- `data/db`：只做持久化，不含业务判断。
- `data/DreamRepository.kt`：领域 ⇄ 数据库的唯一翻译层，Upper 层只见 `Dream`/`Tag`。
- `domain`：纯 Kotlin，无 Android 依赖，**可 100% 单测**。
- `ui/*/XxxViewModel.kt`：状态与动作，无 Compose 依赖。
- `ui/*/XxxScreen.kt`：只负责画，输入状态、输出事件。

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Placeholder() }
    }
}

@Composable
private fun Placeholder() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("梦匣")
            }
        }
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
cd D:\DreamKeeper
adb devices
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.dreamkeeper/.MainActivity
Start-Sleep -Seconds 3
adb exec-out screencap -p > D:\DreamKeeper\.scratch\plan1-task1.png
```

Expected: 截图里出现居中的「梦匣」两个字，背景是近黑（`#0B0E13`），状态栏也是近黑。**用 read_image 打开截图确认**，不要凭安装成功就宣称成功。

- [ ] **Step 7: 提交**

```powershell
cd D:\DreamKeeper
git add -A
git commit -m "chore(android): Gradle 9.8.2 + AGP 9.5.0 工程骨架，可构建可安装"
```

---

## Task 2: 梦匣的色板与主题（深色为默认，且没有纯白）

**Files:**
- Create: `android/app/src/main/java/com/dreamkeeper/ui/theme/Color.kt`, `.../Theme.kt`, `.../Type.kt`
- Modify: `android/app/src/main/java/com/dreamkeeper/MainActivity.kt`

**Interfaces:**
- Produces: `@Composable fun DreamKeeperTheme(content: @Composable () -> Unit)`；颜色常量 `Night`、`Paper`、`Ink`、`InkDim`、`Ember`、`Line`（后续所有界面只用这套）。

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

- [ ] **Step 4: 接进 Activity**

`MainActivity.kt` 内把 `Placeholder()` 改成 `DreamKeeperTheme { Box(...) { Text("梦匣") } }`（`Surface` 可去掉，主题已给背景）。

- [ ] **Step 5: 构建 + 安装 + 截图**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon
cd D:\DreamKeeper; adb install -r android\app\build\outputs\apk\debug\app-debug.apk; adb shell am force-stop com.dreamkeeper; adb shell am start -n com.dreamkeeper/.MainActivity; Start-Sleep 3; adb exec-out screencap -p > .scratch\plan1-task2.png
```

Expected: 文字为浅灰（不是纯白），背景近黑。**read_image 确认**。

- [ ] **Step 6: 提交**

```powershell
git add -A; git commit -m "feat(ui): 梦匣深色色板与主题，禁止纯白"
```

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
git add -A; git commit -m "feat(domain): 日历月视图排布逻辑与测试"
```

---

## Task 4: 数据层 —— Room 实体、FTS 全文索引、DAO

**Files:**
- Create: `.../data/db/DreamEntity.kt`, `TagEntity.kt`, `DreamTagEntity.kt`, `DreamFtsEntity.kt`, `MediaAssetEntity.kt`, `DreamDao.kt`, `TagDao.kt`, `Converters.kt`, `DreamDatabase.kt`
- Test: `android/app/src/test/java/com/dreamkeeper/data/DreamDaoTest.kt`

**Interfaces:**
- Produces（后续任务全部依赖这些精确签名）:
  - `DreamEntity(id: String, ownerId: String, dreamDate: LocalDate, createdAt: Long, title: String?, rawTranscript: String, faithfulText: String?, novelText: String?, novelVersion: Int, novelChangeLog: String?, mood: String?, isDraft: Boolean, deletedAt: Long?, updatedAt: Long)`
  - `MediaAssetEntity(id: String, dreamId: String, type: String, path: String?, prompt: String?, model: String?, status: String, createdAt: Long)`
  - `DreamDao.observeByMonth(fromIso: String, toIso: String): Flow<List<DreamEntity>>`
  - `DreamDao.observeAll(): Flow<List<DreamEntity>>`
  - `DreamDao.observeById(id: String): Flow<DreamEntity?>`
  - `DreamDao.upsert(dream: DreamEntity)`
  - `DreamDao.search(query: String): List<DreamEntity>`
  - `TagDao.observeAll(): Flow<List<TagEntity>>`、`TagDao.upsert(tag: TagEntity)`、`TagDao.rename(id, newName)`、`TagDao.merge(fromId, intoId)`、`TagDao.delete(id)`
  - `DreamDatabase.create(context: Context): DreamDatabase`

- [ ] **Step 1: 写失败的 DAO 测试**

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
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*DreamDaoTest*" --no-daemon
```

Expected: 编译失败（`Unresolved reference: DreamDatabase`）。

- [ ] **Step 3: 写实体与类型转换器**

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

- [ ] **Step 4: 写 DAO**

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

- [ ] **Step 5: 写 Database**

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
                MediaAssetEntity::class, DreamFtsEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class DreamDatabase : RoomDatabase() {
    abstract fun dreamDao(): DreamDao
    abstract fun tagDao(): TagDao
    abstract fun mediaAssetDao(): MediaAssetDao

    companion object {
        fun create(context: Context): DreamDatabase =
            Room.databaseBuilder(context, DreamDatabase::class.java, "dreamkeeper.db").build()
    }
}
```

- [ ] **Step 6: 跑测试确认通过**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*DreamDaoTest*" --no-daemon
```

Expected: `BUILD SUCCESSFUL`，5 个测试通过。
若 FTS 相关查询报 `no such column: dreams_fts.rowid`，把 `@Fts4` 改为 `@Fts4(contentEntity = DreamEntity::class)` 并**去掉** `order` 参数后重试。

- [ ] **Step 7: 提交**

```powershell
git add -A; git commit -m "feat(data): Room 实体、FTS4 全文索引与 DAO 测试"
```

---

## Task 5: 仓库层 —— 领域模型与数据库之间的唯一翻译

**Files:**
- Create: `.../domain/Dream.kt`, `.../domain/Tag.kt`, `.../data/DreamRepository.kt`, `.../di/AppContainer.kt`
- Modify: `.../DreamKeeperApp.kt`
- Test: `android/app/src/test/java/com/dreamkeeper/data/DreamRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 4 的 DAO 与实体。
- Produces:
  - `data class Dream(val id: String, val date: LocalDate, val title: String?, val rawTranscript: String, val faithfulText: String?, val novelText: String?, val novelChangeLog: String?, val mood: String?, val isDraft: Boolean)`
  - `data class Tag(val id: String, val name: String, val kind: String, val source: String, val evidenceText: String?)`
  - `class DreamRepository(private val db: DreamDatabase, private val clock: () -> Long = System::currentTimeMillis)`
    - `fun observeMonth(month: YearMonth): Flow<List<Dream>>`
    - `fun observeAll(): Flow<List<Dream>>`
    - `fun observe(id: String): Flow<Dream?>`
    - `suspend fun save(dream: Dream)`
    - `suspend fun search(rawQuery: String): List<Dream>`
    - `fun observeTags(): Flow<List<Tag>>`
  - `class AppContainer(context: Context) { val repository: DreamRepository }`

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

`domain/Dream.kt` 与 `domain/Tag.kt` 按 **Interfaces** 里的字段逐字写（纯 data class，无 Android import）。

- [ ] **Step 4: 写仓库**

`data/DreamRepository.kt` 的关键实现（FTS 转义的细节是这段代码的全部价值）：

```kotlin
package com.dreamkeeper.data

import com.dreamkeeper.data.db.*
import com.dreamkeeper.domain.Dream
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

    fun observeMonth(month: YearMonth): Flow<List<Dream>> {
        val from = month.atDay(1).toString()
        val to = month.atEndOfMonth().toString()
        return dreams.observeByMonth(from, to).map { list -> list.map(::toDomain) }
    }

    fun observeAll(): Flow<List<Dream>> = dreams.observeAll().map { list -> list.map(::toDomain) }
    fun observe(id: String): Flow<Dream?> = dreams.observeById(id).map { it?.let(::toDomain) }
    fun observeTags(): Flow<List<Tag>> = tags.observeAll().map { list -> list.map(::tagToDomain) }

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

`DreamKeeperApp.kt` 改为：

```kotlin
package com.dreamkeeper

import android.app.Application
import com.dreamkeeper.di.AppContainer

class DreamKeeperApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --tests "*DreamRepositoryTest*" --no-daemon
```

Expected: `BUILD SUCCESSFUL`，5 个测试通过（尤其 `search tolerates quotes and punctuation` 必须过）。

- [ ] **Step 6: 提交**

```powershell
git add -A; git commit -m "feat(data): 仓库层与 FTS 查询转义"
```

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
cd D:\DreamKeeper\android; .\gradlew.bat :app:assembleDebug --no-daemon
cd D:\DreamKeeper; adb install -r android\app\build\outputs\apk\debug\app-debug.apk; adb shell am force-stop com.dreamkeeper; adb shell am start -n com.dreamkeeper/.MainActivity; Start-Sleep 3; adb exec-out screencap -p > .scratch\plan1-task6-a.png
```

Expected：
- 静态图 `plan1-task6-a.png` 显示当月标题、星期表头、网格、底部「说梦」钮。
- 点右箭头后再截图 `plan1-task6-b.png`，标题变成下个月，**且网格行数随之变化**（有的月份 5 行、有的 6 行）。

```powershell
adb shell input tap 950 250   # 右箭头大致位置，按实际截图调整
Start-Sleep 1
adb exec-out screencap -p > .scratch\plan1-task6-b.png
```

**两张图都要 read_image 确认**，不能只看安装成功。

- [ ] **Step 7: 提交**

```powershell
git add -A; git commit -m "feat(ui): 日历首屏（月份切换、有梦红点、说梦大钮）"
```

---

## Task 7: 全部梦境列表（时间轴 / 卡片流切换）

**Files:**
- Create: `.../ui/library/LibraryViewModel.kt`, `.../ui/library/LibraryScreen.kt`
- Modify: `.../ui/DreamKeeperRoot.kt`
- Test: `android/app/src/test/java/com/dreamkeeper/ui/library/LibraryViewModelTest.kt`

**Interfaces:**
- Produces:
  - `enum class LibraryLayout { TIMELINE, CARDS }`
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

`LayoutPrefs` 接口放在 `ui/library/LayoutPrefs.kt`：

```kotlin
interface LayoutPrefs { var layout: LibraryLayout }
```

`LibraryViewModel` 组合 `repo.observeAll()` 与一个 `MutableStateFlow<LibraryLayout>`（初值取 `prefs.layout`），`setLayout` 同时写回 `prefs`。

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

先准备数据：临时在 `DreamKeeperApp.onCreate()` 里插入 3 条种子梦（`SeedData.kt`），截图确认后**保留种子代码但用 `BuildConfig.DEBUG` 包起来**，Plan 2 有了真实录入后再删。

Expected：
- `plan1-task7-timeline.png`：能看到 3 条，左侧竖线与圆点对齐。
- 点分段控件切卡片后 `plan1-task7-cards.png`：卡片出现圆角与内边距。
- 杀掉进程重进，**仍是卡片视图**（证明偏好已持久化）。

- [ ] **Step 7: 提交**

```powershell
git add -A; git commit -m "feat(ui): 全部梦境列表（时间轴/卡片流切换并记忆）"
```

---

## Task 8: 详情页 + 全文搜索

**Files:**
- Create: `.../ui/detail/DetailScreen.kt`, `.../ui/detail/DetailViewModel.kt`, `.../ui/search/SearchScreen.kt`, `.../ui/search/SearchViewModel.kt`
- Modify: `.../ui/DreamKeeperRoot.kt`

**Interfaces:**
- Consumes: `DreamRepository.observe(id)`、`DreamRepository.search(q)`。
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
- 三个 tab：**原样** / **小说化** / **标签**。Plan 1 里小说化 tab 显示占位文案「还没有生成。等接上大模型后，这里会有一篇读得通的版本。」，标签 tab 读 `dream.tags`（Plan 1 可为空，显示「暂无标签」）。
- 正文用 `bodyLarge`，**行高 30sp**，段落间距 12dp，可滚动。
- 底部常驻一条播放条（`NightRaised`，圆角 8dp）：播放三角 + 一条占位波形（用 40 根 1dp 竖线高度随机但**固定种子**，不要每帧抖动）+ 时长文字「--:--」。Plan 2 接真实音频。
- 右上角一个 `⋮` 菜单，目前只有「编辑」「删除」两项，点了给 `Snackbar` 提示「Plan 2 实现」。

- [ ] **Step 6: 装到模拟器，用种子数据走一遍**

Expected：
- 进详情 → 三个 tab 可切，原样正文可滚动。
- 点播放条 → 无崩溃（Plan 1 不播放）。
- 搜索「电梯」→ 命中 1 条；搜索「电梯 六楼」→ 命中更少或 0；搜索 `"！` 之类标点 → 不崩溃。

- [ ] **Step 7: 提交**

```powershell
git add -A; git commit -m "feat(ui): 详情页三 tab 与全文搜索（含防抖与转义）"
```

---

## Task 9: 标签管理页 + 全链路验收

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
    repo.saveDreamTagForTest("dream-1", "t2")   // 测试辅助：直接写 dream_tags
    assertEquals(1, db.tagDao().countTagsOn("dream-1").count { it == "t2" })
    val vm = TagsViewModel(repo, db.tagDao())
    vm.merge(fromId = "t2", intoId = "t1")
    advanceUntilIdle()
    assertEquals(listOf("t1"), db.tagDao().countTagsOn("dream-1"))
    assertNull(db.tagDao().findById("t2"))
}
```

> 需要在 `TagDao` 补两个查询：`@Query("SELECT tagId FROM dream_tags WHERE dreamId = :dreamId") suspend fun countTagsOn(dreamId: String): List<String>` 与 `@Query("SELECT * FROM tags WHERE id = :id") suspend fun findById(id: String): TagEntity?`。

- [ ] **Step 2: 确认失败 → **Step 3: `merge` 实现为事务**（`db.withTransaction { dao.repointDreamTags(from, into); dao.delete(from) }`）→ **Step 4: 确认通过**

- [ ] **Step 5: 写标签页**

要求：按 `kind` 分组（人物/地点/物品/动作/情绪/其他），每组一个标题；每个标签是一个可点的胶囊；长按弹菜单：重命名、合并到…（弹一个搜索框选目标）、删除。重命名后若重名，提示「已有同名标签，要合并吗？」。

- [ ] **Step 6: 全链路验收（这一步是交付判据，逐条打勾）**

```powershell
cd D:\DreamKeeper\android; .\gradlew.bat :app:testDebugUnitTest --no-daemon
cd D:\DreamKeeper; adb install -r android\app\build\outputs\apk\debug\app-debug.apk
adb shell am force-stop com.dreamkeeper; adb shell am start -n com.dreamkeeper/.MainActivity
Start-Sleep 3; adb exec-out screencap -p > .scratch\plan1-final-home.png
```

逐条确认（每条都要截图或命令输出作证）：
- [ ] 冷启动 2 秒内看到日历（`adb shell am start -W -n com.dreamkeeper/.MainActivity` 的 `TotalTime` < 2000）
- [ ] 日历上下切 12 个月，不闪白、不崩
- [ ] 全部列表两种视图切换正常，杀进程重进保留偏好
- [ ] 搜索「电」命中，搜索纯标点不崩
- [ ] 详情三 tab 可切、正文可滚动
- [ ] 标签页可重命名、可合并、合并后原标签消失
- [ ] `adb logcat -d | Select-String "FATAL"` 无输出

- [ ] **Step 7: 提交 + 打 tag**

```powershell
git add -A; git commit -m "feat(ui): 标签管理页（重命名/合并/删除）与全链路验收"
git tag plan1-done
```

---

## 自审记录（写完计划后自查并已修正）

1. **规格覆盖**：设计文档 §5 数据模型 → Task 4；§11 界面结构中的日历/全部/详情/搜索/标签 → Task 6-9；§13 技术选型 → Task 1-2。**未覆盖**（有意留给后续计划）：录音、STT、LLM、夜间特则、锁屏入口、回收站、设置页。
2. **占位符扫描**：Task 6/7/8 的界面步骤给的是「必须做到」的清单而非完整代码——这是**有意**的，因为 Compose 布局的逐行代码在这里会是 300 行噪声，而清单里的每一条都可被截图验证。其余步骤均含可直接粘贴的命令或代码。
3. **类型一致性**：`Dream` 的 9 个字段在 Task 5 定义、Task 6/7/8 的测试里逐字使用；`DreamEntity` 与 `Dream` 的字段名差异（`dreamDate` vs `date`）只存在于 `toDomain`/`save` 两处，已明确。`LayoutPrefs` 在 Task 7 定义、测试用 `FakeLayoutPrefs` 实现。

## 后续计划（不写进本文件）

| 计划 | 内容 | 依赖 |
|---|---|---|
| Plan 2 · 录音与转写 | 前台服务、AudioRecord、权限、本地 STT、原样稿生成与 diff 硬校验、播放条接真实音频 | Plan 1 |
| Plan 3 · 生成与提示词 | LlmClient 适配器、Key 加密存储、小说化、改动清单、自动标签、`prompts/` 接入、评测集 | Plan 2 |
| Plan 4 · 夜间与入口 | 锁屏磁贴/通知按钮、30 秒追问窗口、屏幕常亮、夜间特则打磨、设置页、回收站 | Plan 3 |
