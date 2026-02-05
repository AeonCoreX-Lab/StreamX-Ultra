// Top-level build.gradle.kts
plugins {
    // Android Gradle Plugin ৮.৭.৩ (Android 15 এর জন্য রিকমেন্ডেড)
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    
    // Kotlin ২.০.২১ (লেটেস্ট কম্পোজ সাপোর্টসহ)
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false

    // *** FIX: Kotlin 2.0+ এর জন্য Compose Compiler প্লাগিন বাধ্যতামূলক ***
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    
    // Google Services আপডেট
    id("com.google.gms.google-services") version "4.4.2" apply false
    
    // Rust প্লাগইন
    id("org.mozilla.rust-android-gradle.rust-android") version "0.9.6" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
