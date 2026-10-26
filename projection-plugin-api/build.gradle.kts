plugins { id("com.android.library") }

android {
    namespace = "moe.feng.wheelplay.plugin"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
