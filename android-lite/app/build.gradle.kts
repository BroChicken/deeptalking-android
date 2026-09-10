plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deeptalking.lite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deeptalking.lite"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.0.10"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            val keystorePath = rootProject.file("keystore/deeptalking-release.jks")
            if (keystorePath.exists()) {
                storeFile = keystorePath
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "CHANGE_ME"
                keyAlias = "deeptalking"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: "CHANGE_ME"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("ANDROID_KEYSTORE_PASSWORD") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

dependencies {
}
