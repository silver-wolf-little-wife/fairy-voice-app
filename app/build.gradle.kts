plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fairyvoice.app"
    // M4-1.2：compileSdk 37 提供 Android 16 QPR1+ 的 Live Updates API
    // （Notification.Builder.setRequestPromotedOngoing / Manifest.permission.POST_PROMOTED_NOTIFICATIONS）
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fairyvoice.app"
        minSdk = 26
        // M4-1.2：targetSdk 36 = Android 16 行为；Live Updates 官方要求 targetSdk 36（QPR1 规范）
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // JVM 单测环境没有 Android 自带的 org.json，用 maven 等价实现
    testImplementation("org.json:json:20240303")
}
