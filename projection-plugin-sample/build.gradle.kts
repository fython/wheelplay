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
dependencies { implementation(project(":projection-plugin-api")) }
