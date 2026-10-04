plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val hubRoot = rootProject.projectDir.parentFile
val buildHubAssets by tasks.registering(Exec::class) {
    group = "build"
    description = "Generate WebView assets from modular frontend sources"
    workingDir(hubRoot)
    commandLine("node", "tools/build-hub.mjs")
    inputs.dir(hubRoot.resolve("src"))
    inputs.file(hubRoot.resolve("tools/build-hub.mjs"))
    inputs.file(project.file("build.gradle.kts"))
    outputs.file(project.file("src/main/assets/hub.html"))
    outputs.dir(project.file("src/main/assets/katex"))
}

tasks.named("preBuild") {
    dependsOn(buildHubAssets)
}

android {
    namespace = "com.deeptalking.lite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deeptalking.lite"
        minSdk = 26
        targetSdk = 35
        versionCode = 32
        versionName = "1.4.0"
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
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:notifications"))
    implementation(project(":core:designsystem"))
    implementation(project(":engine:ondevice"))
    implementation(project(":domain:agent"))
    implementation(project(":domain:memory"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:characters"))
    implementation(project(":feature:memory"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
}
