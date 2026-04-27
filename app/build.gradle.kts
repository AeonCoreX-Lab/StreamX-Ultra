import javax.inject.Inject
import org.gradle.process.ExecOperations

// ═══════════════════════════════════════════════════════════════════
//  Gradle 9.4.1 Breaking Changes Fixed:
//  1. kotlinOptions {} → kotlin { compilerOptions {} }
//  2. freeCompilerArgs += → freeCompilerArgs.addAll()
//  3. exec {} in doLast → abstract class + @Inject ExecOperations
//  4. afterEvaluate+withType → withType().configureEach (lazy)
//  5. layout.buildDirectory.get() → layout.buildDirectory.dir().get()
//
//  AI (MediaPipe/Gemini) completely removed — not needed
//  AdMob/AppLovin replaced with Start.io
// ═══════════════════════════════════════════════════════════════════

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
<<<<<<< HEAD
    namespace = "com.aeoncorex.streamx"
=======
    namespace  = "com.aeoncorex.streamx"
>>>>>>> f89298d (fixed build errors)
    compileSdk = 36
    ndkVersion = "29.0.14206865"

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
<<<<<<< HEAD
                    "-DBOOST_ASIO_HAS_STD_ALIGNED_ALLOC=0"
                )

                val vcpkgRoot = System.getenv("VCPKG_ROOT") ?: ""
                val envNdk = System.getenv("ANDROID_NDK_HOME")
                val ndkPath = if (!envNdk.isNullOrBlank()) envNdk else android.ndkDirectory.absolutePath

                val rustBuildDir = File(project.layout.buildDirectory.get().asFile, "rust/targets").absolutePath

                // --- FIX: FETCH GITHUB SECRET AND PASS TO CMAKE/RUST ---
                val tmdbApiKey = System.getenv("TMDB_API_KEY") ?: "api_key_not_found"
                buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")

=======
                    "-DBOOST_ASIO_HAS_STD_ALIGNED_ALLOC=0")
                val vcpkgRoot    = System.getenv("VCPKG_ROOT") ?: ""
                val envNdk       = System.getenv("ANDROID_NDK_HOME")
                val ndkPath      = if (!envNdk.isNullOrBlank()) envNdk else android.ndkDirectory.absolutePath
                val rustBuildDir = layout.buildDirectory.dir("rust/targets").get().asFile.absolutePath
>>>>>>> f89298d (fixed build errors)
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

    // Gradle 9.4.1: kotlinOptions deprecated → kotlin { compilerOptions {} }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(  // Gradle 9: addAll(), not +=
                "-opt-in=androidx.media3.common.util.UnstableApi",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
            )
        }
    }

    buildFeatures { compose = true; buildConfig = true }
}

// Gradle 9: abstract class with @Inject ExecOperations
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
<<<<<<< HEAD
                // Pass the TMDB API KEY to cargo build
                val tmdbApiKey = System.getenv("TMDB_API_KEY") ?: "api_key_not_found"
                environment("TMDB_API_KEY", tmdbApiKey)
                commandLine("cargo", "ndk", "-t", androidAbi, "-o", "$rustRoot/jniLibs", "build", "--release")
            }

            val sourceFile = File(rustRoot, "target/$rustTarget/release/libstreamx_core.a")
            val destDir = File(buildDir, "rust/targets/$rustTarget/release")

            if (sourceFile.exists()) {
                destDir.mkdirs()
                sourceFile.copyTo(File(destDir, "libstreamx_core.a"), overwrite = true)
            } else {
                throw GradleException("❌ Rust build failed. File not found: ${sourceFile.absolutePath}")
=======
                environment("TMDB_API_KEY", tmdbApiKey.get())
                commandLine("cargo", "ndk", "-t", abi, "-o", "$rustRoot/jniLibs", "build", "--release")
>>>>>>> f89298d (fixed build errors)
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

// Gradle 9: no afterEvaluate — configureEach is lazy
tasks.withType<com.android.build.gradle.tasks.ExternalNativeBuildTask>().configureEach { dependsOn(cargoBuildTask) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.0")
<<<<<<< HEAD
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))

=======
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
>>>>>>> f89298d (fixed build errors)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
<<<<<<< HEAD

=======
>>>>>>> f89298d (fixed build errors)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.0") {
        exclude(group = "com.github.TeamNewPipe", module = "nanojson")
    }
    implementation("com.grack:nanojson:1.2")

<<<<<<< HEAD
    implementation("androidx.compose.foundation:foundation:1.11.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:34.11.0"))
=======
    // Firebase (auth + firestore for ad-free premium check)
    implementation(platform("com.google.firebase:firebase-bom:33.5.0"))
>>>>>>> f89298d (fixed build errors)
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx") {
        exclude(group = "com.google.firebase", module = "protolite-well-known-types")
        exclude(group = "com.google.protobuf",  module = "protobuf-lite")
    }
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

<<<<<<< HEAD
    // Media3 (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-common:1.10.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.0")
    implementation("androidx.media3:media3-ui:1.10.0")
    implementation("androidx.media3:media3-session:1.10.0")

    // UI Utilities
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("com.valentinilk.shimmer:compose-shimmer:1.3.0")

    implementation("androidx.compose.material:material-icons-extended:1.6.7")
}
=======
    // Media3 ExoPlayer — HLS/DASH instant streaming
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    // UI utilities
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
    implementation("com.valentinilk.shimmer:compose-shimmer:1.3.0")

    // Start.io ads — instant setup, no verification
    implementation("com.startapp:inapp-sdk:5.+")
}
>>>>>>> f89298d (fixed build errors)
