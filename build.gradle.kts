// Top-level build.gradle.kts
plugins {
    // Android Gradle Plugin 9.0.1 (compatible with Gradle 9.4.1)
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.2.0" apply false

    // AGP 9.0+ has built-in Kotlin — kotlin.android plugin must NOT be applied.
    // Only the Compose compiler plugin is needed separately.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false

    // Google Services
    id("com.google.gms.google-services") version "4.4.2" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}