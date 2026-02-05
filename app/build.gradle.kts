plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("org.mozilla.rust-android-gradle.rust-android") version "0.9.6"
}

android {
    namespace = "com.aeoncorex.streamx"
    // Updated for Android 15 (Vanilla Ice Cream)
    compileSdk = 35
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.aeoncorex.streamx"
        minSdk = 26
        // Updated Target SDK to 35
        targetSdk = 35
        versionCode = 5
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // --- C++ NATIVE CONFIG ---
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0")

                val vcpkgRoot = System.getenv("VCPKG_ROOT") ?: ""
                val envNdk = System.getenv("ANDROID_NDK_HOME")
                val ndkPath = if (!envNdk.isNullOrBlank()) envNdk else android.ndkDirectory.absolutePath

                // Path to where Rust plugin dumps the .so files
                val rustLibPath = "${project.layout.buildDirectory.get().asFile.absolutePath}/rustJniLibs/android"

                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_TOOLCHAIN_FILE=$vcpkgRoot/scripts/buildsystems/vcpkg.cmake",
                    "-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE=$ndkPath/build/cmake/android.toolchain.cmake",
                    "-DVCPKG_TARGET_TRIPLET=arm64-android",
                    "-DANDROID_ABI=arm64-v8a",
                    "-DANDROID_PLATFORM=android-24",
                    "-D_FORTIFY_SOURCE=0",
                    "-DWHISPER_NO_AVX=ON",
                    "-DRUST_LIB_PATH=$rustLibPath"
                )

                abiFilters("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }

        // --- RUST BUILD CONFIG ---
        cargo {
            module = "src/main/rust"
            libname = "streamx_core"
            // Broad device support: ARM64, ARMv7, x86_64
            targets = listOf("arm64", "arm", "x86_64")
            profile = "release"
        }

        val tmdbApiKey = System.getenv("TMDB_API_KEY") ?: "\"\""
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    aaptOptions {
        noCompress("bin")
    }

    signingConfigs {
        create("release") {
            val storeFileValue = System.getenv("RELEASE_KEYSTORE_FILE") ?: project.findProperty("RELEASE_KEYSTORE_FILE") as? String
            val storePasswordValue = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: project.findProperty("RELEASE_KEYSTORE_PASSWORD") as? String
            val keyAliasValue = System.getenv("RELEASE_KEY_ALIAS") ?: project.findProperty("RELEASE_KEY_ALIAS") as? String
            val keyPasswordValue = System.getenv("RELEASE_KEY_PASSWORD") ?: project.findProperty("RELEASE_KEY_PASSWORD") as? String

            if (storeFileValue != null && storePasswordValue != null && keyAliasValue != null && keyPasswordValue != null) {
                storeFile = file(storeFileValue)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true // Added for smaller APK size
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // FIX: composeOptions ব্লক ডিলিট করা হয়েছে কারণ Kotlin 2.0 তে এটি লাগে না

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            pickFirsts += "lib/**/libc++_shared.so"
            pickFirsts += "lib/**/libstreamx_core.so"
        }
    }
}

// FIX: Force Rust build to happen before CMake linkage
afterEvaluate {
    tasks.withType(com.android.build.gradle.tasks.ExternalNativeBuildTask::class.java).configureEach {
        dependsOn("cargoBuild")
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0") // Needed for EdgeToEdge
    implementation(platform("androidx.compose:compose-bom:2024.04.00")) // Stable BOM

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // NewPipe Extractor
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.25.1") {
        exclude(group = "com.github.TeamNewPipe", module = "nanojson")
    }
    implementation("com.grack:nanojson:1.2")

    // Navigation & Firebase
    implementation("androidx.compose.foundation:foundation:1.6.7")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation(platform("com.google.firebase:firebase-bom:33.0.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx") {
        exclude(group = "com.google.firebase", module = "protolite-well-known-types")
        exclude(group = "com.google.protobuf", module = "protobuf-lite")
    }
    implementation("com.google.android.gms:play-services-auth:21.1.1")

    // Media3 (ExoPlayer) - Updated to 1.3.1 (Stable)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1") // Good for background playback

    // UI Utilities
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("com.valentinilk.shimmer:compose-shimmer:1.3.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.7")
}
