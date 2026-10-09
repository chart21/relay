import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.relay.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.relay.app"
        minSdk = 31
        targetSdk = 35
        // Every build gets a higher versionCode (minutes since 2026-01-01 UTC): the app updates itself from the Desktop.
        versionCode = ((System.currentTimeMillis() / 60_000L) - 29_453_760L).toInt()
        versionName = "2.0-" + SimpleDateFormat("yyyyMMdd-HHmm").format(Date())
        ndk { abiFilters += "arm64-v8a" } // the phone is arm64; keeps ONNX Runtime's native libraries to one ABI
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true // screenshot tests (Robolectric)
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*",
            "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1",
        )
    }
}

// Screenshot tests (src/test/.../shots, Robolectric + Roborazzi) only run with -Pshots; PNGs go to app/build/shots.
tasks.withType<Test>().configureEach {
    maxHeapSize = "1536m"
    if (!project.hasProperty("shots")) exclude("**/shots/**")
    systemProperty("roborazzi.test.record", "true")
    systemProperty("shots.dir", layout.buildDirectory.dir("shots").get().asFile.absolutePath)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.28.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha10") // phone bridge: steps, sleep, heart rate, weight
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.hierynomus:sshj:0.39.0")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.slf4j:slf4j-android:1.7.36")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0") // "Hey Jarvis" (openWakeWord models)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
