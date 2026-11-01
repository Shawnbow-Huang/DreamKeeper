import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 发布签名：keystore.properties 只在开发者本机存在（.gitignore 排除），
// 缺失时 release 不配签名 → 产出 unsigned 包，构建不会失败（Plan 5 T7 / D488）。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

android {
    namespace = "com.dreamkeeper"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dreamkeeper"
        minSdk = 26
        targetSdk = 37
        versionCode = 15
        versionName = "0.14.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // 种子数据开关：debug 才有，release 恒 false。
    // 2026-10-30 修正：原先写在 defaultConfig 里恒为 true，正式包首启也会灌 4 条假梦。
    buildTypes {
        debug {
            buildConfigField("boolean", "SEED_DEMO_DATA", "true")
        }
        release {
            buildConfigField("boolean", "SEED_DEMO_DATA", "false")
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        // AGP 9.5 的默认值未实测；BuildConfig 里有手写字段（SEED_DEMO_DATA），必须显式开
        buildConfig = true
    }

    // 内置 SenseVoice Small（离线语音转写，D563）：
    // · 只留三种真正会被用到的 ABI——x86 是 32 位模拟器，早已不是目标；
    //   每个 ABI 的 onnxruntime 十几 MB，四份全带等于白送 26MB。
    // · .onnx 必须 noCompress：sherpa 走 AAssetManager 直接读，压缩过的资产
    //   拿不到连续缓冲（200MB 模型也经不起每次解压）。
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    androidResources {
        noCompress += listOf("onnx", "txt")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Task 6（计划 §5 / §0.0 / D220）：提示词的真源是仓库根的 `prompts/*.md`，不在 android/ 里。
// 构建期用 Sync 把它们拷进一个**生成**资产目录（落点 assets/prompts/*.md），再挂成 assets 源目录：
//   · 直接 assets.srcDir("../../prompts") 会把 md 平铺到 assets 根，且把仓库根整个目录当资产；
//   · Sync 只清自己的目的目录，手写的 src/main/assets/pricing/deepseek.json 一个字节都不碰（§0.0）。
val promptAssetsDir = layout.buildDirectory.dir("generated/promptAssets")
val syncPromptAssets = tasks.register<Sync>("syncPromptAssets") {
    from(rootProject.file("../prompts")) {
        include("*.md")
        into("prompts")
    }
    destinationDir = promptAssetsDir.get().asFile
}
android {
    // AGP 9.5 不允许 sourceSet 里塞 Provider（它分不清生成/静态目录），这里显式取 File；
    // 先后顺序由下面的 preBuild 依赖负责。
    sourceSets.getByName("main").assets.srcDir(promptAssetsDir.get().asFile)
}
// 生成目录不是 Gradle 认得的 task 输出，必须显式排先后：否则 mergeAssets 可能先跑（空目录）再拷。
// 任何构建/测试路径都会经过 preBuild（compile 依赖它），单测的合并资产因此拿到三份提示词。
tasks.named("preBuild") { dependsOn(syncPromptAssets) }

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
    implementation(libs.androidx.navigation.compose)
    // 纯 JVM jar；钉 4.12.1 而非 5.x（5.5.1 的 .module 是 KMP root，见 D227）
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // D563：内置离线语音识别运行时（sherpa-onnx 2.0.1 官方 AAR，Apache-2.0）。
    // 官方没有 Maven 坐标，只有 GitHub Release 资产；49MB 的 AAR 直接进 libs/，
    // 换来的是「不靠系统语音包、不联网也能转写」。
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // 提供 MigrationTestHelper：Plan 2 给 dreams 加字段时就靠它验 migration
    testImplementation(libs.room.testing)

    // 内置语音模型的真机/模拟器验证：JVM 单测加载不了 .so 与 239MB 模型，
    // 只有 androidTest 能证明「真的能转出字」（D563）。
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

ksp {
    // Room schema 导出目录：产物必须提交入库（app/schemas/.../1.json），
    // 它是 "v1 的表结构长什么样" 的唯一机器可读记录。
    arg("room.schemaLocation", "$projectDir/schemas")
}

// D563：内置语音识别的两个大二进制（49MB AAR + 239MB int8 模型）不入库，
// 由 scripts/fetch-asr.sh 拉取并校验 SHA256。缺了就**在最早处**停下并给出可执行命令，
// 而不是等打包到一半报「文件不存在」。
val checkBundledAsr = tasks.register("checkBundledAsr") {
    val aar = file("libs/sherpa-onnx-2.0.1.aar")
    val model = file("src/main/assets/sensevoice/model.int8.onnx")
    val tokens = file("src/main/assets/sensevoice/tokens.txt")
    inputs.files(aar, model, tokens).withPropertyName("bundledAsr")
    doLast {
        val missing = listOf(aar, model, tokens).filterNot { it.exists() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "内置语音识别依赖缺失：" +
                    missing.joinToString { it.relativeTo(projectDir).path } +
                    "\n请先运行：bash scripts/fetch-asr.sh",
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(checkBundledAsr) }

// D266/D273：单测会读仓库里的成本向量（scripts/check-cost.py 的同一份真源）。
// 不登记成 input 的话，Gradle 会认为 testDebugUnitTest 的输入没变而跳过——向量改了测试不重跑，
// 是「假绿」的经典来源。指向 evidence main 树里的文件，保证 CI 与本地跑的是同一份。
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("../docs/evidence/plan3/p3-cost-01-vectors.json"))
        .withPropertyName("costVectors")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("../docs/evidence/plan7/faithful-gate-v3-vectors.json"))
        .withPropertyName("faithfulGateVectors")
}
