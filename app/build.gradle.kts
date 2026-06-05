// app/build.gradle.kts  (relevant sections — merge into your existing file)
// ══════════════════════════════════════════════════════════════════════
//  Changes from previous version:
//    • Rust now builds torrent engine — librqbit needs network features
//    • REMOVED: libtorrent/vcpkg cmake args
//    • ADDED:   org.mozilla:rhino for JS addon execution
//    • Cargo build path is the same: app/src/main/rust
// ══════════════════════════════════════════════════════════════════════

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// ── Cargo build task ──────────────────────────────────────────────────
// Builds the Rust static library before CMake links it.
// Run:  ./gradlew cargoBuild  (or it runs automatically via preBuild dep)
tasks.register<Exec>("cargoBuild") {
    group       = "build"
    description = "Build Rust core (streamx_core.a) for all ABIs"

    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: "${System.getenv("ANDROID_SDK_ROOT")}/ndk-bundle"
    val rustDir = file("src/main/rust")

    workingDir = rustDir
    commandLine("bash", "-c",
        """
        # Install cargo-ndk if missing
        cargo ndk --version 2>/dev/null || cargo install cargo-ndk

        # Add targets if missing
        rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android 2>/dev/null

        # Build release for all ABIs
        ANDROID_NDK_HOME=$ndkHome TMDB_API_KEY=${project.findProperty("tmdbApiKey") ?: ""} \
        cargo ndk \
            -t arm64-v8a \
            -t armeabi-v7a \
            -t x86_64 \
            -o ../jniLibs \
            build --release
        """.trimIndent()
    )
}

// Auto-run cargo build before CMake
tasks.named("preBuild") { dependsOn("cargoBuild") }

android {
    namespace         = "com.aeoncorex.streamx"
    compileSdk        = 35

    defaultConfig {
        applicationId         = "com.aeoncorex.streamx"
        minSdk                = 26
        targetSdk             = 35
        versionCode           = 1
        versionName           = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments(
                    // Pass Rust build output dir to CMake
                    "-DRUST_BUILD_DIR=${project.projectDir}/src/main/rust/target",
                    // TMDB key for Rust compile-time embedding
                    "-DTMDB_API_KEY=${project.findProperty("tmdbApiKey") ?: ""}",
                    // REMOVED: -DVCPKG_ROOT, -DLIBTORRENT_ROOT (no longer needed)
                )
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable   = true
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Include native .so files from jniLibs
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // ── Compose + Android ─────────────────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    // ── Firebase ──────────────────────────────────────────────────────
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-crashlytics")

    // ── Networking ────────────────────────────────────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.code.gson:gson:2.14.0")

    // ── Ktor (for any remaining server needs) ─────────────────────────
    // TorrentStreamServer.kt is DELETED — but keep Ktor if used elsewhere
    // implementation("io.ktor:ktor-server-cio:2.3.12")

    // ── JS addon execution (Rhino) ────────────────────────────────────
    // Executes bundled Vega-style provider JS modules
    implementation("org.mozilla:rhino:1.9.1")

    // ── Ads ───────────────────────────────────────────────────────────
    implementation("com.google.android.gms:play-services-ads:23.4.0")

    // ── Other ─────────────────────────────────────────────────────────
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.36.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
