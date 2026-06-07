import javax.inject.Inject
import org.gradle.process.ExecOperations

// ═══════════════════════════════════════════════════════════════════
//  StreamX Ultra — app/build.gradle.kts
//
//  KEY CHANGES (Rust migration + vcpkg FFmpeg):
//    • TWO CargoBuild tasks:
//        cargoBuildDebug   → arm64-v8a only, `cargo build` (no --release)
//        cargoBuildRelease → all 4 ABIs,     `cargo build --release`
//    • Debug buildType  → cmake abiFilters = arm64-v8a + RUST_BUILD_TYPE=debug
//    • Release buildType→ cmake abiFilters = all 4 ABIs + RUST_BUILD_TYPE=release
//    • REMOVED hardcoded -DANDROID_ABI=arm64-v8a (was wrong — set by NDK toolchain)
//    • CMakeLists.txt reads RUST_BUILD_TYPE to pick debug/ or release/ .a path
// ═══════════════════════════════════════════════════════════════════
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
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "1.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        val tmdbApiKey   = System.getenv("TMDB_API_KEY")       ?: "api_key_not_found"
        val startappId   = System.getenv("STARTAPP_APP_ID")    ?: "0"
        val vercelUrl    = System.getenv("BACKEND_BASE_URL")   ?: ""

        buildConfigField("String", "TMDB_API_KEY",    "\"$tmdbApiKey\"")
        buildConfigField("String", "STARTAPP_APP_ID", "\"$startappId\"")
        buildConfigField("String", "BACKEND_BASE_URL","\"$vercelUrl\"")

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0")

                val rustBuildDir = layout.buildDirectory.dir("rust/targets").get().asFile.absolutePath

                // ── Shared CMake args (ABI-agnostic) ──────────────────────
                // -DANDROID_ABI is intentionally NOT set here:
                //   the NDK toolchain injects it per-ABI automatically.
                // -DRUST_BUILD_TYPE is set per buildType (see buildTypes below).
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-28",
                    "-D_FORTIFY_SOURCE=0",
                    "-DRUST_BUILD_DIR=$rustBuildDir",
                    "-DTMDB_API_KEY=$tmdbApiKey"
                )
                // abiFilters intentionally absent here — set per buildType below
            }
        }
    }

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            val sf = System.getenv("RELEASE_KEYSTORE_FILE")
                ?: project.findProperty("RELEASE_KEYSTORE_FILE") as? String
            val sp = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                ?: project.findProperty("RELEASE_KEYSTORE_PASSWORD") as? String
            val ka = System.getenv("RELEASE_KEY_ALIAS")
                ?: project.findProperty("RELEASE_KEY_ALIAS") as? String
            val kp = System.getenv("RELEASE_KEY_PASSWORD")
                ?: project.findProperty("RELEASE_KEY_PASSWORD") as? String
            if (sf != null && sp != null && ka != null && kp != null) {
                storeFile    = file(sf)
                storePassword = sp
                keyAlias     = ka
                keyPassword  = kp
            }
        }
    }

    buildTypes {
        // ── DEBUG ─────────────────────────────────────────────────────
        // arm64-v8a only → fast iteration, avoids the 4×release-cargo OOM
        // Rust: `cargo build` (debug profile — no LTO, no vendored-OOM)
        // CMakeLists.txt reads RUST_BUILD_TYPE=debug → target/.../debug/*.a
        debug {
            externalNativeBuild {
                cmake {
                    abiFilters("arm64-v8a")
                    arguments += "-DRUST_BUILD_TYPE=debug"
                }
            }
        }

        // ── RELEASE ───────────────────────────────────────────────────
        // All 4 ABIs, full optimised Rust release build
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            externalNativeBuild {
                cmake {
                    abiFilters("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
                    arguments += "-DRUST_BUILD_TYPE=release"
                }
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/services/org.slf4j.spi.SLF4JServiceProvider"
            )
        }
        jniLibs {
            pickFirsts += setOf(
                "**/libc++_shared.so", "**/libmpv.so",
                "**/libavcodec.so",   "**/libavdevice.so",
                "**/libavfilter.so",  "**/libavformat.so",
                "**/libavutil.so",    "**/libswresample.so",
                "**/libswscale.so"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.media3.common.util.UnstableApi",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
            )
        }
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Rust Build Task
//
//  Debug  task (cargoBuildDebug):
//    • arm64-v8a only
//    • `cargo ndk build`           ← no --release → uses debug profile
//    • No LTO, no codegen-units=1, no vendored-OpenSSL heavy pass
//    • ~2-3 min vs ~10+ min in release mode
//
//  Release task (cargoBuildRelease):
//    • All 4 ABIs
//    • `cargo ndk build --release` ← full optimised (lto, strip, etc.)
// ═══════════════════════════════════════════════════════════════════
abstract class CargoBuildTask @Inject constructor(
    private val execOps: ExecOperations
) : DefaultTask() {

    @get:Input abstract val tmdbApiKey:    Property<String>
    @get:Input abstract val rustRootPath:  Property<String>
    @get:Input abstract val isReleaseBuild: Property<Boolean>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val rustRoot    = File(rustRootPath.get())
        val release     = isReleaseBuild.get()
        val buildType   = if (release) "release" else "debug"

        val targets = if (release) {
            listOf(
                "arm64-v8a"   to "aarch64-linux-android",
                "armeabi-v7a" to "armv7-linux-androideabi",
                "x86_64"      to "x86_64-linux-android",
                "x86"         to "i686-linux-android"
            )
        } else {
            // Debug: arm64 only — matches cmake abiFilters("arm64-v8a") above
            listOf("arm64-v8a" to "aarch64-linux-android")
        }

        targets.forEach { (abi, triple) ->
            println("🦀 cargo ndk [$buildType] → $abi")
            execOps.exec {
                workingDir = rustRoot
                environment("TMDB_API_KEY", tmdbApiKey.get())
                commandLine(buildList {
                    addAll(listOf("cargo", "ndk", "-t", abi, "-o", "$rustRoot/jniLibs", "build"))
                    if (release) add("--release")
                })
            }
            // Copy the .a for CMakeLists.txt
            val src = File(rustRoot, "target/$triple/$buildType/libstreamx_core.a")
            val dst = File(outputDir.get().asFile, "$triple/$buildType")
            if (src.exists()) {
                dst.mkdirs()
                src.copyTo(File(dst, "libstreamx_core.a"), overwrite = true)
                println("   ✅ libstreamx_core.a → build/rust/targets/$triple/$buildType/")
            } else {
                throw GradleException("Rust build failed — .a not found: ${src.absolutePath}")
            }
        }
    }
}

// ── Register debug task (arm64, debug mode) ───────────────────────
val cargoBuildDebugTask = tasks.register<CargoBuildTask>("cargoBuildDebug") {
    group       = "build"
    description = "Rust JNI — debug profile, arm64-v8a only (fast)"
    tmdbApiKey.set(System.getenv("TMDB_API_KEY") ?: "api_key_not_found")
    rustRootPath.set(file("src/main/rust").absolutePath)
    isReleaseBuild.set(false)
    outputDir.set(layout.buildDirectory.dir("rust/targets"))
}

// ── Register release task (all ABIs, release mode) ────────────────
val cargoBuildReleaseTask = tasks.register<CargoBuildTask>("cargoBuildRelease") {
    group       = "build"
    description = "Rust JNI — release profile, all 4 ABIs"
    tmdbApiKey.set(System.getenv("TMDB_API_KEY") ?: "api_key_not_found")
    rustRootPath.set(file("src/main/rust").absolutePath)
    isReleaseBuild.set(true)
    outputDir.set(layout.buildDirectory.dir("rust/targets"))
}

// ── Wire cargo tasks to CMake build tasks by build type ───────────
// ExternalNativeBuildTask names follow the pattern:
//   buildCMakeDebug[arm64-v8a], buildCMakeRelease[arm64-v8a], etc.
tasks.withType<com.android.build.gradle.tasks.ExternalNativeBuildTask>().configureEach {
    val isRelease = this.name.contains("Release", ignoreCase = true)
    dependsOn(if (isRelease) cargoBuildReleaseTask else cargoBuildDebugTask)
}

// ═══════════════════════════════════════════════════════════════════
//  Protobuf / Firestore version pinning
// ═══════════════════════════════════════════════════════════════════
configurations.all {
    resolutionStrategy {
        force("com.google.protobuf:protobuf-javalite:3.25.5")
        force("com.google.protobuf:protobuf-kotlin:3.25.5")
    }
    exclude(group = "com.google.protobuf", module = "protobuf-lite")
}

dependencies {
    implementation(project(":premium-core"))

    val media3Version     = "1.10.1"
    val lifecycleVersion  = "2.8.0"
    val protobufVersion   = "3.25.5"

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
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.retrofit2:converter-scalars:3.0.0")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    // NewPipeExtractor
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.1") {
        exclude(group = "com.github.TeamNewPipe", module = "nanojson")
    }
    implementation("com.grack:nanojson:1.10")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:34.11.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:protolite-well-known-types:18.0.1")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("com.google.protobuf:protobuf-javalite:$protobufVersion")

    // Media3 ExoPlayer
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
    implementation("com.valentinilk.shimmer:compose-shimmer:1.4.0")

    // Start.io Ads
    implementation("com.startapp:inapp-sdk:5.3.0")

    // JS addon execution
    implementation("org.mozilla:rhino:1.9.1")
}
