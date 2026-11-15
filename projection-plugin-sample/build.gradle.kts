plugins { id("com.android.application") }

android {
    namespace = "moe.feng.wheelplay.plugin.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "moe.feng.wheelplay.plugin.sample"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation(project(":projection-plugin-api"))
    implementation(libs.androidx.remote.creation.android)
    implementation(libs.androidx.remote.creation.core)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(libs.androidx.remote.player.core)
    testImplementation(libs.androidx.remote.player.view)
}

android.testOptions.unitTests.isIncludeAndroidResources = true

tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}
