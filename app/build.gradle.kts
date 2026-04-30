import javax.inject.Inject
import org.gradle.process.ExecOperations

// ═══════════════════════════════════════════════════════════════════
//  Gradle 9.4.1 Breaking Changes Fixed:
//  1. kotlinOptions {} → kotlin { compilerOptions {} }
//  2. freeCompilerArgs += → freeCompilerArgs.addAll()
//  3. exec {} in doLast → abstract class + @Inject ExecOperations
//  4. afterEvaluate+withType → withType().configureEach (lazy)
//  5. layout.buildDirectory.get() → layout.buildDirectory.dir().get()
//  6. android.ndkDirectory → REMOVED in AGP 9.0, use env var fallback
//
//  AI (MediaPipe/Gemini) completely removed — not needed
//  AdMob/AppLovin replaced with Start.io
// ═══════════════════════════════════════════════════════════════════

// Top-level NDK version constant — single source of truth for NDK path resolution.
// android.ndkDirectory was removed in AGP 9.0; we resolve the path manually.
val NDK_VERSION = "29.0.14206865"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.aeoncorex.streamx"
    compileSdk = 36
    ndkVersion = NDK_VERSION

    defaultConfig {
        applicationId = "com.aeoncorex.streamx"
        minSdk        = 28
        targetSdk     = 35
        versionCode   = 6
        versionName   = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        val tmdbApiKey  = System.getenv("TMDB_API_KEY")    ?: "api_key_not_found"
        val startappId  = System.getenv("STARTAPP_APP_ID") ?: "0"

        buildConfigField("String", "TMDB_API_KEY",    "\"$tmdbApiKey\"")
        buildConfigField("String", "STARTAPP_APP_ID", "\"$startappId\"")

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0",
                    "-DBOOST_ASIO_DISABLE_STD_ALIGNED_ALLOC",
                    "-DBOOST_ASIO_HAS_STD_ALIGNED_ALLOC=0")
                
                val vcpkgRoot    = System.getenv("VCPKG_ROOT") ?: ""
                val envNdk       = System.getenv("ANDROID_NDK_HOME")
                // android.ndkDirectory was removed in AGP 9.0 — resolve path from env vars only
                val ndkPath      = if (!envNdk.isNullOrBlank()) {
                    envNdk
                } else {
                    val androidHome = System.getenv("ANDROID_HOME") ?: ""
                    "$androidHome/ndk/$NDK_VERSION"
                }
                val rustBuildDir = layout.buildDirectory.dir("rust/targets").get().asFile.absolutePath
                
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_TOOLCHAIN_FILE=$vcpkgRoot/scripts/buildsystems/vcpkg.cmake",
                    "-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE=$ndkPath/build/cmake/android.toolchain.cmake",
                    "-DVCPKG_TARGET_TRIPLET=arm64-android", "-DANDROID_ABI=arm64-v8a",
                    "-DANDROID_PLATFORM=android-28", "-D_FORTIFY_SOURCE=0",
                    "-DRUST_BUILD_DIR=$rustBuildDir", "-DTMDB_API_KEY=$tmdbApiKey"
                )
                abiFilters("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            }
        }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    packaging {
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
        jniLibs {
            pickFirsts += setOf("**/libc++_shared.so", "**/libmpv.so", "**/libavcodec.so",
                "**/libavdevice.so", "**/libavfilter.so", "**/libavformat.so",
                "**/libavutil.so", "**/libswresample.so", "**/libswscale.so")
        }
    }

    signingConfigs {
        create("release") {
            val sf = System.getenv("RELEASE_KEYSTORE_FILE")     ?: project.findProperty("RELEASE_KEYSTORE_FILE") as? String
            val sp = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: project.findProperty("RELEASE_KEYSTORE_PASSWORD") as? String
            val ka = System.getenv("RELEASE_KEY_ALIAS")         ?: project.findProperty("RELEASE_KEY_ALIAS") as? String
            val kp = System.getenv("RELEASE_KEY_PASSWORD")      ?: project.findProperty("RELEASE_KEY_PASSWORD") as? String
            if (sf != null && sp != null && ka != null && kp != null) {
                storeFile = file(sf); storePassword = sp; keyAlias = ka; keyPassword = kp
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll( 
                "-opt-in=androidx.media3.common.util.UnstableApi",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
            )
        }
    }

    buildFeatures { compose = true; buildConfig = true }
}

abstract class CargoBuildTask @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:Input    abstract val tmdbApiKey:   Property<String>
    @get:Input    abstract val rustRootPath: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun build() {
        val rustRoot = File(rustRootPath.get())
        val targets  = listOf("arm64-v8a" to "aarch64-linux-android",
            "armeabi-v7a" to "armv7-linux-androideabi",
            "x86_64" to "x86_64-linux-android", "x86" to "i686-linux-android")
            
        targets.forEach { (abi, target) ->
            execOps.exec {
                workingDir = rustRoot
                environment("TMDB_API_KEY", tmdbApiKey.get())
                commandLine("cargo", "ndk", "-t", abi, "-o", "$rustRoot/jniLibs", "build", "--release")
            }
            val src = File(rustRoot, "target/$target/release/libstreamx_core.a")
            val dst = File(outputDir.get().asFile, "$target/release")
            if (src.exists()) { dst.mkdirs(); src.copyTo(File(dst, "libstreamx_core.a"), overwrite = true) }
            else throw GradleException("Rust build failed: ${src.absolutePath}")
        }
    }
}

val cargoBuildTask = tasks.register<CargoBuildTask>("cargoBuild") {
    group = "build"; description = "Builds Rust JNI (Gradle 9 compatible)"
    tmdbApiKey.set(System.getenv("TMDB_API_KEY") ?: "api_key_not_found")
    rustRootPath.set(file("src/main/rust").absolutePath)
    outputDir.set(layout.buildDirectory.dir("rust/targets"))
}

tasks.withType<com.android.build.gradle.tasks.ExternalNativeBuildTask>().configureEach { dependsOn(cargoBuildTask) }

// ── Exclude protolite-well-known-types globally (conflicts with protobuf-javalite) ──
configurations.all {
    exclude(group = "com.google.firebase", module = "protolite-well-known-types")
}

dependencies {
    implementation(project(":premium-core"))
    // Version Variables
    val media3Version = "1.10.0"
    val lifecycleVersion = "2.8.0"

    // Core & Compose
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.8")

    // Networking & Utilities
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.0") {
        exclude(group = "com.github.TeamNewPipe", module = "nanojson")
    }
    implementation("com.grack:nanojson:1.2")

    // Firebase (Auth + Firestore + FCM)
    implementation(platform("com.google.firebase:firebase-bom:34.12.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-firestore") {
        exclude(group = "com.google.firebase", module = "protolite-well-known-types")
        exclude(group = "com.google.protobuf",  module = "protobuf-lite")
    }
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Media3 ExoPlayer — HLS/DASH instant streaming
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")

    // UI Utilities
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("com.valentinilk.shimmer:compose-shimmer:1.3.0")

    // Start.io Ads
    implementation("com.startapp:inapp-sdk:5.2.6")
}
