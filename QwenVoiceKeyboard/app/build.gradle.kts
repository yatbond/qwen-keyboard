plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.qwenkeyboard.voicekeyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.qwenkeyboard.voicekeyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "1.2.2-separate-keyboard"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    // No bundled ASR model/runtime here: this separate app is only an IME client for PC ASR.
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

