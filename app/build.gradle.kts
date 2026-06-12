import javax.inject.Inject
import org.gradle.process.ExecOperations

// ═══════════════════════════════════════════════════════════════════
//  StreamX Ultra — app/build.gradle.kts
//
//  KEY CHANGES (Rust migration + vcpkg OpenSSL):
//    • CargoBuild tasks now set OPENSSL_DIR for vcpkg integration
//    • OPENSSL_STATIC=0 → shared linking (avoids duplicate symbols with CMake)
//    • VCPKG_ROOT env var required (set by CI or local dev environment)
//    • FIX: CMake gets OPENSSL_ROOT_DIR per ABI so find_package works
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

                // ═══════════════════════════════════════════════════════════════
                //  FIX: Pass OPENSSL_ROOT_DIR per ABI to CMake so find_package
                //  can locate vcpkg's OpenSSL. Without this, CMake fails with:
                //  "Could NOT find OpenSSL (missing: OPENSSL_CRYPTO_LIBRARY)"
                // ═══════════════════════════════════════════════════════════════
                val vcpkgRoot = System.getenv("VCPKG_ROOT") ?: ""
                val abiToTriplet = mapOf(
                    "arm64-v8a"   to "arm64-android",
                    "armeabi-v7a" to "arm-android",
                    "x86_64"      to "x64-android",
                    "x86"         to "x86-android"
                )

                val opensslRootArgs = if (vcpkgRoot.isNotEmpty()) {
                    abiToTriplet.map { (abi, triplet) ->
                        "-DOPENSSL_ROOT_DIR_$abi=$vcpkgRoot/installed/$triplet"
                    }
                } else {
                    emptyList()
                }

                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-28",
                    "-D_FORTIFY_SOURCE=0",
                    "-DRUST_BUILD_DIR=$rustBuildDir",
                    "-DTMDB_API_KEY=$tmdbApiKey"
                ) + opensslRootArgs
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
        debug {
            externalNativeBuild {
                cmake {
                    abiFilters("arm64-v8a")
                    arguments += "-DRUST_BUILD_TYPE=debug"
                }
            }
        }

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
                "**/libswscale.so",
                "**/libssl.so",       "**/libcrypto.so"   // ← vcpkg OpenSSL .so files
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
//  Rust Build Task (with vcpkg OpenSSL support)
//
//  Environment variables required:
//    • VCPKG_ROOT  → path to vcpkg installation (e.g. /home/user/vcpkg)
//                    CI sets this automatically. For local builds:
//                    export VCPKG_ROOT=/path/to/vcpkg
//
//  The task sets per-ABI:
//    • OPENSSL_DIR     = $VCPKG_ROOT/installed/$triplet
//    • OPENSSL_STATIC  = 0  (shared linking — avoids duplicate symbols with CMake)
//
//  vcpkg triplets:
//    arm64-v8a    → arm64-android
//    armeabi-v7a  → arm-android
//    x86_64       → x64-android
//    x86          → x86-android
// ═══════════════════════════════════════════════════════════════════
abstract class CargoBuildTask @Inject constructor(
    private val execOps: ExecOperations
) : DefaultTask() {

    @get:Input abstract val tmdbApiKey:    Property<String>
    @get:Input abstract val rustRootPath:  Property<String>
    @get:Input abstract val releaseBuild:  Property<Boolean>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val rustRoot    = File(rustRootPath.get())
        val release     = releaseBuild.get()
        val buildType   = if (release) "release" else "debug"

        // ── vcpkg setup ─────────────────────────────────────────────────────
        val vcpkgRoot = System.getenv("VCPKG_ROOT")
            ?: throw GradleException("""
                ❌ VCPKG_ROOT environment variable not set!
                vcpkg is required to provide OpenSSL for the Rust build.
                For local builds:
                  export VCPKG_ROOT=/path/to/your/vcpkg

                Then install OpenSSL for Android:
                  ${'$'}VCPKG_ROOT/vcpkg install openssl:arm64-android
                  ${'$'}VCPKG_ROOT/vcpkg install openssl:arm-android
                  ${'$'}VCPKG_ROOT/vcpkg install openssl:x64-android
                  ${'$'}VCPKG_ROOT/vcpkg install openssl:x86-android

                For CI builds, this is handled automatically by the workflow.
            """.trimIndent())

        // Map Android ABI → vcpkg triplet
        val tripletMap = mapOf(
            "arm64-v8a"   to "arm64-android",
            "armeabi-v7a" to "arm-android",
            "x86_64"      to "x64-android",
            "x86"         to "x86-android"
        )

        val targets = if (release) {
            listOf(
                "arm64-v8a"   to "aarch64-linux-android",
                "armeabi-v7a" to "armv7-linux-androideabi",
                "x86_64"      to "x86_64-linux-android",
                "x86"         to "i686-linux-android"
            )
        } else {
            listOf("arm64-v8a" to "aarch64-linux-android")
        }

        targets.forEach { (abi, triple) ->
            val vcpkgTriplet = tripletMap[abi]
                ?: throw GradleException("Unknown ABI: $abi")
            val opensslDir = "$vcpkgRoot/installed/$vcpkgTriplet"

            // Verify vcpkg OpenSSL is installed
            val opensslLibDir = File(opensslDir, "lib")
            if (!opensslLibDir.exists()) {
                throw GradleException("""
                    ❌ vcpkg OpenSSL not found for $abi (triplet: $vcpkgTriplet)
                    Expected: $opensslDir/lib/

                    Install it with:
                      $vcpkgRoot/vcpkg install openssl:$vcpkgTriplet
                """.trimIndent())
            }

            println("🦀 cargo ndk [$buildType] → $abi")
            println("   OPENSSL_DIR=$opensslDir")
            println("   OPENSSL_STATIC=0 (shared linking)")

            execOps.exec {
                workingDir = rustRoot
                environment("TMDB_API_KEY", tmdbApiKey.get())
                environment("OPENSSL_DIR", opensslDir)
                environment("OPENSSL_STATIC", "0")  // ← SHARED: avoids duplicate symbols with CMake
                commandLine(buildList {
                    addAll(listOf("cargo", "ndk", "-t", abi, "-o", "$rustRoot/jniLibs", "build", "--jobs", "2"))
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
    description = "Rust JNI — debug profile, arm64-v8a only (fast). Requires VCPKG_ROOT env var."
    tmdbApiKey.set(System.getenv("TMDB_API_KEY") ?: "api_key_not_found")
    rustRootPath.set(file("src/main/rust").absolutePath)
    releaseBuild.set(false)
    outputDir.set(layout.buildDirectory.dir("rust/targets"))
}

// ── Register release task (all ABIs, release mode) ────────────────
val cargoBuildReleaseTask = tasks.register<CargoBuildTask>("cargoBuildRelease") {
    group       = "build"
    description = "Rust JNI — release profile, all 4 ABIs. Requires VCPKG_ROOT env var."
    tmdbApiKey.set(System.getenv("TMDB_API_KEY") ?: "api_key_not_found")
    rustRootPath.set(file("src/main/rust").absolutePath)
    releaseBuild.set(true)
    outputDir.set(layout.buildDirectory.dir("rust/targets"))
}

// ── Wire cargo tasks to CMake build tasks by build type ───────────
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

    val media3Version = "1.10.1"
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
}
