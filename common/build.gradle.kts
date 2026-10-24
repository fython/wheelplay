plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { targets += "wheelplay-rtc" } }
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":projection-plugin-api"))
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    implementation("io.github.g00fy2.quickie:quickie-bundled:1.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.material:material:1.13.0")
    androidTestImplementation(libs.bouncycastle)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    implementation(libs.jmdns)
    api(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.1")
    testImplementation(libs.junit)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("org.robolectric:robolectric:4.17")
}

// Android 16 Robolectric ApplicationSharedMemory uses the JDK FileDescriptor accessor.
tasks.withType<Test>().configureEach {
    // The full Robolectric suite loads several Android SDK resource tables. Gradle's
    // default 512 MiB test worker heap is insufficient; org.gradle.jvmargs only sizes the daemon.
    maxHeapSize = "2g"
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}
