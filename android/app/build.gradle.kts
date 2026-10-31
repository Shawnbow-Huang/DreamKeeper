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
        versionCode = 4
        versionName = "0.10.1"
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

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // 提供 MigrationTestHelper：Plan 2 给 dreams 加字段时就靠它验 migration
    testImplementation(libs.room.testing)
}

ksp {
    // Room schema 导出目录：产物必须提交入库（app/schemas/.../1.json），
    // 它是 "v1 的表结构长什么样" 的唯一机器可读记录。
    arg("room.schemaLocation", "$projectDir/schemas")
}

// D266/D273：单测会读仓库里的成本向量（scripts/check-cost.py 的同一份真源）。
// 不登记成 input 的话，Gradle 会认为 testDebugUnitTest 的输入没变而跳过——向量改了测试不重跑，
// 是「假绿」的经典来源。指向 evidence main 树里的文件，保证 CI 与本地跑的是同一份。
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("../docs/evidence/plan3/p3-cost-01-vectors.json"))
        .withPropertyName("costVectors")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("../docs/evidence/plan3/faithful-gate-vectors.json"))
        .withPropertyName("faithfulGateVectors")
}
