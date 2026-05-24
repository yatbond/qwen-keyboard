plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// Google Drive/WSL can reject some Gradle resource-copy operations.
// Keep generated build intermediates on the Linux filesystem, while source stays in the project folder.
allprojects {
    layout.buildDirectory.set(file("/tmp/qwen-voice-benchmark-build/${project.path.replace(':', '_')}"))
}
