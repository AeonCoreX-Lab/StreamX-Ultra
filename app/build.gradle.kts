plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.aeoncorex.streamx"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aeoncorex.streamx"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.1" // Updated Version
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // --- C++ NATIVE CONFIG ---
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
        arguments("-DANDROID_STL=c++_shared")
        // GitHub Actions এ vcpkg ব্যবহারের জন্য এটি যোগ করা ভালো
              abiFilters("arm64-v8a", "armeabi-v7a")
    }
}

        // API Key Injection
        val tmdbApiKey = System.getenv("TMDB_API_KEY") ?: "\"\""
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
    }

    // --- C++ CMAKE LINKING ---
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
            isMinifyEnabled = true // Production optimization
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.9.0")
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
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx") {
        exclude(group = "com.google.firebase", module = "protolite-well-known-types")
        exclude(group = "com.google.protobuf", module = "protobuf-lite")
    }
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    implementation("com.facebook.android:facebook-login:16.3.0")

    // Media3 (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // NOTE: Libtorrent4j removed because we are using custom C++ Native Engine

    // UI Utilities
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("com.valentinilk.shimmer:compose-shimmer:1.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.7")
}
