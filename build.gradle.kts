// Top-level build.gradle.kts
plugins {
    // Android Gradle Plugin 9.0.1 (compatible with Gradle 9.4.1)
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false

    // Kotlin 2.1.20 (latest stable, supports Gradle 9.x)
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false

    // Compose compiler plugin (same version as Kotlin)
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false

    // Google Services
    id("com.google.gms.google-services") version "4.4.2" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}