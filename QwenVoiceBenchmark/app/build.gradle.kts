plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.qwenkeyboard.benchmark"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.qwenkeyboard.voicekeyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 228
        versionName = "4.8.0-flow-no-short-fragments"
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-v1.13.1.jar"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.2")
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}
