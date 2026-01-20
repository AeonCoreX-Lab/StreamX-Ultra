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
        versionName = "1.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // --- SECURE API KEY INJECTION ---
        val tmdbApiKey = System.getenv("TMDB_API_KEY") ?: "\"\""
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
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
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
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

    // --- এই অংশটি Stripping Error সমাধান করবে ---
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // jlibtorrent লাইব্রেরি স্ট্রিপ করা বন্ধ করবে
            keepDebugSymbols += "**/libjlibtorrent*.so"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Retrofit & Gson & XML
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.9.0") // ADDED FOR NYAA RSS (XML)

    // Jsoup for parsing HTML/XML
    implementation("org.jsoup:jsoup:1.17.2") // ADDED FOR NYAA PARSING

    // Foundation & Navigation
    implementation("androidx.compose.foundation:foundation:1.6.7")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    implementation("com.facebook.android:facebook-login:16.3.0")

    // Media3 (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // --- JLIBTORRENT ENGINE (LATEST 2.0.12.7) ---
    val jlibtorrentVersion = "2.0.12.7"
    implementation("com.frostwire:jlibtorrent:$jlibtorrentVersion")
    implementation("com.frostwire:jlibtorrent-android-arm:$jlibtorrentVersion")
    implementation("com.frostwire:jlibtorrent-android-arm64:$jlibtorrentVersion")
    implementation("com.frostwire:jlibtorrent-android-x86:$jlibtorrentVersion")
    implementation("com.frostwire:jlibtorrent-android-x86_64:$jlibtorrentVersion")

    // Coil (Image Loading)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // DataStore & Lifecycle
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Shimmer & Icons
    implementation("com.valentinilk.shimmer:compose-shimmer:1.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.7")
}