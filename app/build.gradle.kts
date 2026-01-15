plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// local.properties ফাইল লোড করার লজিক
val localProperties = java.util.Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(java.io.FileInputStream(localPropertiesFile))
}

android {
    namespace = "com.aeoncorex.streamx"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aeoncorex.streamx"
        minSdk = 24
        targetSdk = 34
        versionCode = 5 // Version bumped
        versionName = "1.3.0" 
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // --- SECURE API KEY INJECTION ---
        // এটি প্রথমে local.properties চেক করবে, না পেলে System Environment (GitHub Secrets) চেক করবে
        val tmdbApiKey = localProperties.getProperty("TMDB_API_KEY") 
            ?: System.getenv("TMDB_API_KEY") 
            ?: "\"\"" // ডিফল্ট খালি স্ট্রিং যাতে এরর না খায়

        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
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
        buildConfig = true // এটি অত্যন্ত গুরুত্বপূর্ণ BuildConfig ক্লাস জেনারেট করার জন্য
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
    
    // Retrofit & Gson
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

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

    // Coil (Image Loading)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // DataStore & Lifecycle
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Shimmer & Icons
    implementation("com.valentinilk.shimmer:compose-shimmer:1.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.7")
}
