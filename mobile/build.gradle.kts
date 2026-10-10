import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Local provisioning stays out of version control. An environment override takes precedence.
val localAuthenticationAssets = providers.environmentVariable("WHEELPLAY_AUTH_ASSETS_DIR")
    .orElse(providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR"))
    .orNull?.let { file(it).canonicalFile }
    ?: rootProject.file(".local/auth-assets").takeIf { it.exists() }?.canonicalFile

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "moe.feng.wheelplay"
        minSdk = 28
        targetSdk = 36
        versionCode = 21
        versionName = "0.3.0"

    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-web-debug"
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Only the two provisioned local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Source-only builds remain useful for CI; device delivery must verify the actual ZIP.
tasks.register("assembleProvisionedDebug") {
    group = "build"
    description = "Build Debug APK and verify its locally provisioned authentication assets."
    dependsOn("assembleDebug")
    val assetDirectory = localAuthenticationAssets
    val apk = layout.buildDirectory.file("outputs/apk/debug/mobile-debug.apk")
    inputs.files(credentialAssets)
    inputs.file(apk)
    doLast {
        check(assetDirectory != null) {
            "Set WHEELPLAY_AUTH_ASSETS_DIR or provide .local/auth-assets before delivering this APK"
        }
        ZipFile(apk.get().asFile).use { archive ->
            for (name in listOf("identity.pk8", "certificate.p7b")) {
                val source = assetDirectory.resolve("offline-mfi/$name")
                val entry = archive.getEntry("assets/offline-mfi/$name")
                check(source.isFile && entry != null) { "Debug APK is missing a provisioned authentication asset" }
                val packaged = archive.getInputStream(entry).use { it.readBytes() }
                check(packaged.isNotEmpty() && packaged.contentEquals(source.readBytes())) {
                    "Debug APK authentication asset does not match local provisioning"
                }
            }
        }
        logger.lifecycle("Verified Debug APK runtime authentication assets.")
    }
}
