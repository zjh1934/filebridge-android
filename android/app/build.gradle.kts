plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.filebridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.filebridge"
        minSdk = 26
        targetSdk = 34
        // 版本规矩：每出一个包 versionName +0.1、versionCode +1
        versionCode = 5
        versionName = "1.4"
    }

    signingConfigs {
        create("fixed") {
            // 证书本体不进公开仓库：CI 从 Actions Secret 解码到 app/filebridge.jks
            val jks = file("filebridge.jks")
            if (jks.exists()) {
                storeFile = jks
                storePassword = "filebridge2026"
                keyAlias = "filebridge"
                keyPassword = "filebridge2026"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            // 6.5 MB 的大头是 Kotlin / Compose / OkHttp 运行时，光删冗余文件效果有限；
            // 启用 Android 官方 R8 压缩未使用代码与资源，规则见 proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro",
            )
            if (file("filebridge.jks").exists()) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

/**
 * v1.3 瘦身：删掉 1.1 遗留的本机文件管理器 + 云网关模式后，
 * Retrofit / Gson / converter-gson / lifecycle-viewmodel 全部不再需要。
 * GitHubStore 用的是原生 OkHttp + org.json，只保留真正会打进 APK 的依赖。
 */
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
