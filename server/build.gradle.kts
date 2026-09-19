plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lightphone.calls.server"
    compileSdk = 36

    defaultConfig { minSdk = 34 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

val buildRust by tasks.registering(Exec::class) {
    workingDir = rootProject.file("rust")
    commandLine("bash", "wa-bridge/build-android.sh")
    environment("ANDROID_HOME", System.getenv("ANDROID_HOME") ?: "${rootProject.projectDir}/../tools/android-sdk")
    inputs.dir(rootProject.file("rust/wa-bridge/src"))
    inputs.files(rootProject.file("rust/wa-bridge/Cargo.toml"), rootProject.file("rust/wa-bridge/Cargo.lock"), rootProject.file("rust/wa-bridge/build-android.sh"))
    outputs.file("src/main/java/com/lightphone/calls/server/bridge/wa_bridge.kt")
    outputs.files("src/main/jniLibs/arm64-v8a/libwa_bridge.so", "src/main/jniLibs/x86_64/libwa_bridge.so")
}
tasks.named("preBuild") { dependsOn(buildRust) }

dependencies {
    // UniFFI-generated Kotlin bindings (server/src/main/java/.../bridge) are
    // JNA-based; jna@aar is the Android-packaged JNA runtime they require
    // (@aar keeps the desktop jnidispatch natives out of the APK).
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation(libs.sdk.server) {
        exclude(group = "com.google.mlkit")
        exclude(group = "androidx.camera")
    }
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
