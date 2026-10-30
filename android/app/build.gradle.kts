plugins {
    alias(libs.plugins.android.application)
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

    // 种子数据开关：debug 才有，release 恒 false。
    // 2026-10-30 修正：原先写在 defaultConfig 里恒为 true，正式包首启也会灌 4 条假梦。
    buildTypes {
        debug {
            buildConfigField("boolean", "SEED_DEMO_DATA", "true")
        }
        release {
            buildConfigField("boolean", "SEED_DEMO_DATA", "false")
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
