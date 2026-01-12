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

        // GitHub Secrets বা Environment ভেরিয়েবল হ্যান্ডলিং
        buildConfigField("String", "SUPABASE_URL", "\"${System.getenv("SUPABASE_URL") ?: ""}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${System.getenv("SUPABASE_KEY") ?: ""}\"")
        buildConfigField("String", "TMDB_KEY", "\"${System.getenv("TMDB_API_KEY") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            val storeFileValue = project.findProperty("RELEASE_KEYSTORE_FILE") as? String ?: System.getenv("RELEASE_KEYSTORE_FILE")
            val storePasswordValue = project.findProperty("RELEASE_KEYSTORE_PASSWORD") as? String ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
            val keyAliasValue = project.findProperty("RELEASE_KEY_ALIAS") as? String ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPasswordValue = project.findProperty("RELEASE_KEY_PASSWORD") as? String ?: System.getenv("RELEASE_KEY_PASSWORD")

            if (storeFileValue != null) {
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
        debug {
            buildConfigField("String", "SUPABASE_URL", "\"${System.getenv("SUPABASE_URL") ?: ""}\"")
            buildConfigField("String", "SUPABASE_KEY", "\"${System.getenv("SUPABASE_KEY") ?: ""}\"")
            buildConfigField("String", "TMDB_KEY", "\"${System.getenv("TMDB_API_KEY") ?: ""}\"")
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android & Compose
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // [FIX] Updated BOM to ensure Material3 1.2.0+ (Required for PullToRefresh & AutoMirrored Icons)
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // [FIX] Added Material Icons Extended (Fixes: Unresolved reference LiveTv, MusicNote, etc.)
    implementation("androidx.compose.material:material-icons-extended")

    // [FIX] Added Retrofit & Gson (Fixes: Unresolved reference retrofit2, Gson, SerializedName)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // [FIX] Added Shimmer (Fixes: Unresolved reference shimmer)
    implementation("com.valentinilk.shimmer:compose-shimmer:1.3.0")

    // Network & API (Supabase এর জন্য দরকারি)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Foundation Pager
    implementation("androidx.compose.foundation:foundation:1.6.7")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.android.gms:play-services-auth:21.0.0")

    // Facebook Login
    implementation("com.facebook.android:facebook-login:16.3.0")

    // Media & Player (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // Image Loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Utilities
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
