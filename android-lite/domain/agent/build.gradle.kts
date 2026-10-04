plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":engine:ondevice"))
    implementation(project(":domain:memory"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The differential oracle compares a local-time string byte-for-byte, so the
// test JVM must use a fixed timezone regardless of the CI runner's default.
tasks.withType<Test>().configureEach {
    systemProperty("user.timezone", "Asia/Shanghai")
    jvmArgs("-Duser.language=zh", "-Duser.country=CN")
}
