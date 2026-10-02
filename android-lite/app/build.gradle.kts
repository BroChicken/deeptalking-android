plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    outputs.file(hubRoot.resolve("hub.html"))
    outputs.file(project.file("src/main/assets/hub.html"))
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
        versionCode = 30
        versionName = "1.3.5"
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
