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
        // 版本规则：从 v1.1 起，每出一个包 versionName +0.1、versionCode +1
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("fixed") {
            // 证书本体不进公开仓库：CI 从 Actions Secret 解码到 app/filebridge.jks
            // 密码/别名固定，保密的是 jks 文件本身
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
            isMinifyEnabled = false
            // 有 jks 就用固定证书签名；本地没有时仍可构建（未签名）
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

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
