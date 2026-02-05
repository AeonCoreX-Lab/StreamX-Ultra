plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("org.mozilla.rust-android-gradle.rust-android") version "0.9.3"
}

android {
    namespace = "com.aeoncorex.streamx"
    compileSdk = 35
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.aeoncorex.streamx"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0")

                // এনভায়রনমেন্ট ভেরিয়েবল হ্যান্ডলিং (VCPKG এবং অন্যান্য)
                val vcpkgRoot = System.getenv("VCPKG_ROOT") ?: ""
                val envNdk = System.getenv("ANDROID_NDK_HOME")
                val ndkPath = if (!envNdk.isNullOrBlank()) envNdk else android.ndkDirectory.absolutePath

                // FIX: Rust বিল্ড ডিরেক্টরি পাথ সঠিক করা হলো
                val rustBuildDir = "${project.layout.buildDirectory.get().asFile.absolutePath}/rust/targets"

                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_TOOLCHAIN_FILE=$vcpkgRoot/scripts/buildsystems/vcpkg.cmake",
                    "-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE=$ndkPath/build/cmake/android.toolchain.cmake",
                    "-DVCPKG_TARGET_TRIPLET=arm64-android",
                    "-DANDROID_ABI=arm64-v8a",
                    "-DANDROID_PLATFORM=android-26", // minSdk এর সাথে মিল রাখা ভালো
                    "-D_FORTIFY_SOURCE=0",
                    "-DWHISPER_NO_AVX=ON",
                    // CMake-কে Rust এর লাইব্রেরি পাথ চিনিয়ে দেওয়া
                    "-DRUST_BUILD_DIR=$rustBuildDir"
                )

                abiFilters("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }

        val tmdbApiKey = System.getenv("TMDB_API_KEY") ?: "\"\""
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
    }

    // Cargo কনফিগারেশন (Rust)
    // এটি defaultConfig এর বাইরে থাকা উচিত
    configure<org.mozilla.rust.android.gradle.RustAndroidExtension> {
        tools {
            cargo {
                module = "src/main/rust" // নিশ্চিত করুন এই ফোল্ডারে Cargo.toml আছে
                libname = "streamx_core"
                targets = listOf("arm64", "arm", "x86_64")
                profile = "release"
            }
        }
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
            isShrinkResources = true
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            pickFirsts += "lib/**/libc++_shared.so"
        }
    }
}

// FIX: Ensure Rust builds before C++ triggers
// Gradle tasks গ্রাফে cargoBuild কে externalNativeBuild এর আগে রান করানো হচ্ছে
afterEvaluate {
    tasks.withType(com.android.build.gradle.tasks.ExternalNativeBuildTask::class.java).configureEach {
        dependsOn("cargoBuild")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.04.00"))

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.25.1") {
        exclude(group = "com.github.TeamNewPipe", module = "nanojson")
    }
    implementation("com.grack:nanojson:1.2")

    implementation("androidx.compose.foundation:foundation:1.6.7")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    
    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.0.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx") {
        exclude(group = "com.google.firebase", module = "protolite-well-known-types")
        exclude(group = "com.google.protobuf", module = "protobuf-lite")
    }
    implementation("com.google.android.gms:play-services-auth:21.1.1")

    // Media3 (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.9.1")
    implementation("androidx.media3:media3-common:1.9.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.1")
    implementation("androidx.media3:media3-ui:1.9.1")
    implementation("androidx.media3:media3-session:1.9.1")

    // UI Utilities
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("com.valentinilk.shimmer:compose-shimmer:1.3.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.7")
}
