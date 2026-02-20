// Top-level build.gradle.kts
plugins {
    // Android Gradle Plugin 8.7.3 (Android 15 Ready)
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    
    // Kotlin 2.0.21 (Latest with K2 compiler)
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false

    // *** Kotlin 2.0+ Compose Compiler Plugin ***
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
    
    // Google Services
    id("com.google.gms.google-services") version "4.4.2" apply false
    
    // ❌ REMOVED: Mozilla Rust Plugin (We are using cargo-ndk instead)
    // id("org.mozilla.rust-android-gradle.rust-android") version "0.9.3" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
