plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.etoo.watchbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.etoo.watchbridge"
        minSdk = 33          // Wear OS 4+ (OnePlus Watch 4 ma Wear OS 6)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Do użytku prywatnego: podpis kluczem debug wystarczy.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Celowo bez zależności AndroidX – tylko API frameworka (mniej rzeczy do zepsucia).
dependencies {}
