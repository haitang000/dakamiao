import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 内置 Kotlin 支持，无需再单独应用 org.jetbrains.kotlin.android
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.haitang000.dakamiao"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.haitang000.dakamiao"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // 离线 OCR（中文+拉丁），模型打包进 APK，不联网
    implementation(libs.mlkit.text.recognition.chinese)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
