plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.aeoncorex.streamx"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

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

                val vcpkgRoot = System.getenv("VCPKG_ROOT") ?: ""
                val envNdk = System.getenv("ANDROID_NDK_HOME")
                val ndkPath = if (!envNdk.isNullOrBlank()) envNdk else android.ndkDirectory.absolutePath
                
                val rustBuildDir = File(project.layout.buildDirectory.get().asFile, "rust/targets").absolutePath
                
                // --- FIX: FETCH GITHUB SECRET AND PASS TO CMAKE/RUST ---
                val tmdbApiKey = System.getenv("TMDB_API_KEY") ?: "api_key_not_found"

                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_TOOLCHAIN_FILE=$vcpkgRoot/scripts/buildsystems/vcpkg.cmake",
                    "-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE=$ndkPath/build/cmake/android.toolchain.cmake",
                    "-DVCPKG_TARGET_TRIPLET=arm64-android",
                    "-DANDROID_ABI=arm64-v8a",
                    "-DANDROID_PLATFORM=android-26",
                    "-D_FORTIFY_SOURCE=0",
                    "-DRUST_BUILD_DIR=$rustBuildDir",
                    "-DTMDB_API_KEY=$tmdbApiKey" // <--- PASSING SECRET TO CMAKE
                )

                abiFilters("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets") // Vosk Model Folder
        }
    }

    packaging {
        resources {
             excludes += "/META-INF/{AL2.0,LGPL2.1}"
             excludes += "META-INF/DEPENDENCIES"
             excludes += "META-INF/INDEX.LIST"
        }
        jniLibs {
             // --- FIX: RESOLVE JNI vs CMAKE IMPORTED DUPLICATE CONFLICTS ---
             pickFirsts += setOf(
                 "**/libc++_shared.so",
                 "**/libonnxruntime.so",
                 "**/libsherpa-onnx-c-api.so"
             )
        }
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
}

// Custom Rust Build Task
tasks.register("cargoBuild") {
    description = "Builds the Rust library using cargo-ndk"
    val rustRoot = file("src/main/rust")
    val buildDir = layout.buildDirectory.get().asFile
    val targets = listOf(
        "arm64-v8a" to "aarch64-linux-android",
        "armeabi-v7a" to "armv7-linux-androideabi",
        "x86_64" to "x86_64-linux-android",
        "x86" to "i686-linux-android"
    )

    doLast {
        targets.forEach { (androidAbi, rustTarget) ->
            println("🔨 Building Rust for $androidAbi ($rustTarget)...")
            exec {
                workingDir = rustRoot
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
            }
        }
    }
}

afterEvaluate {
    tasks.withType<com.android.build.gradle.tasks.ExternalNativeBuildTask>().configureEach {
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
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-common:1.9.2")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.2")
    implementation("androidx.media3:media3-ui:1.9.2")
    implementation("androidx.media3:media3-session:1.9.2")

    // UI Utilities
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("com.valentinilk.shimmer:compose-shimmer:1.3.0")
    
    implementation("androidx.compose.material:material-icons-extended:1.6.7")
}
