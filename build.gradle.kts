// Top-level build.gradle.kts
plugins {
    // Android Gradle Plugin 9.0.1 (compatible with Gradle 9.4.1)
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.2.0" apply false

    // Kotlin Android plugin — required for all Android modules (app + library).
    // AGP does NOT provide built-in Kotlin compilation; KGP must be applied explicitly.
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false

    // Compose compiler plugin — separate from kotlin.android; both are required.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false

    // Google Services
    id("com.google.gms.google-services") version "4.4.2" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}