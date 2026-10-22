plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// KaTeX (formula rendering) ships as an asset of :feature:richtext, the only
// module that uses it; library assets merge into the APK, so RichTextWebView
// keeps loading it from file:///android_asset/katex/. No copy step needed.

android {
    namespace = "com.deeptalking.lite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deeptalking.lite"
        minSdk = 26
        targetSdk = 35
        versionCode = 76
        versionName = "1.7.1"
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

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:designsystem"))
    implementation(project(":engine:ondevice"))
    implementation(project(":engine:cosyvoice"))
    implementation(project(":engine:remote-tts"))
    implementation(project(":domain:agent"))
    implementation(project(":domain:memory"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:characters"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)
}
