plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.deeptalking.engine.cosyvoice"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

// Fail-closed guard: never let an APK be produced without the arm64 CosyVoice
// native libraries. Silently shipping a build that lacks them makes on-device
// TTS die at runtime with `library "libcosyvoice.so" not found`. The libraries
// are vendored under src/main/jniLibs and rebuilt by
// tools/build-cosyvoice-android.ps1.
val requiredNativeLibs = listOf(
    "libcosyvoice.so",
    "libggml.so",
    "libggml-cpu.so",
    "libggml-base.so",
    "libonnxruntime.so",
    "libomp.so",
    "libc++_shared.so",
)

val verifyCosyVoiceNativeLibs by tasks.registering {
    group = "verification"
    description = "Asserts the arm64 CosyVoice native libraries are present in jniLibs"
    val jniDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
    doLast {
        val missing = requiredNativeLibs.filter { name ->
            val f = jniDir.file(name).asFile
            !f.isFile || f.length() == 0L
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing/empty CosyVoice native libraries under ${jniDir.asFile}:\n" +
                    missing.joinToString("\n") { "  - $it" } + "\n" +
                    "Rebuild them with: powershell -File tools/build-cosyvoice-android.ps1\n" +
                    "or restore the vendored .so from version control.",
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyCosyVoiceNativeLibs)
}

dependencies {
    implementation(project(":engine:ondevice"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    testImplementation(libs.junit)
}
