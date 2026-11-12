import java.util.zip.ZipFile
import org.gradle.work.DisableCachingByDefault

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Local provisioning stays out of version control. An environment override takes precedence.
val localAuthenticationAssets = providers.environmentVariable("WHEELPLAY_AUTH_ASSETS_DIR")
    .orElse(providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR"))
    .orNull?.let { file(it).canonicalFile }
    ?: rootProject.file(".local/auth-assets").takeIf { it.exists() }?.canonicalFile

// mitmproxy-style combined PEM: one unencrypted CA key, then an ordered certificate path.
// Only this explicit file is copied; the containing directory is never added to assets.
val localHttpsCa = providers.environmentVariable("WHEELPLAY_HTTPS_CA_PEM")
    .orNull?.let { rootProject.file(it).canonicalFile }
    ?: rootProject.file(".local/https-ca/emudhra-ca.pem").takeIf { it.exists() }?.canonicalFile

@DisableCachingByDefault(because = "Contains locally provisioned CA private key material")
abstract class BundleHttpsCa : DefaultTask() {
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourcePem: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val destination = outputDirectory.get().asFile
        check(!destination.exists() || destination.deleteRecursively()) {
            "Could not clear generated HTTPS CA assets"
        }
        destination.mkdirs()
        val source = sourcePem.orNull?.asFile ?: return
        check(source.length() in 1..(2 * 1024 * 1024).toLong()) {
            "HTTPS CA PEM must be nonempty and at most 2 MiB"
        }
        val bytes = source.readBytes()
        try {
            val text = bytes.toString(Charsets.UTF_8)
            check(Regex("-----BEGIN (?:RSA |EC )?PRIVATE KEY-----").findAll(text).count() == 1 &&
                "-----BEGIN CERTIFICATE-----" in text && "ENCRYPTED" !in text) {
                "HTTPS CA PEM must contain one unencrypted RSA/EC private key and a CA certificate chain"
            }
            destination.resolve("web-tls/emudhra-ca.pem").apply {
                parentFile.mkdirs()
                writeBytes(bytes)
            }
        } finally { bytes.fill(0) }
    }
}

val bundleHttpsCa by tasks.registering(BundleHttpsCa::class) {
    group = "build"
    description = "Bundle an optional locally provisioned HTTPS signing CA."
    localHttpsCa?.let { sourcePem.set(it) }
    outputDirectory.set(layout.buildDirectory.dir("generated/https-ca/assets"))
}

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(bundleHttpsCa, BundleHttpsCa::outputDirectory)
}

dependencies {
    androidTestImplementation(project(":projection-plugin-api"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Only explicitly provisioned authentication assets and the generated HTTPS CA are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
}, bundleHttpsCa.map { it.outputDirectory.asFileTree })
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowedAuthentication = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    val allowed = allowedAuthentication + if (localHttpsCa != null) setOf(
        bundleHttpsCa.get().outputDirectory.get().asFile.resolve("web-tls/emudhra-ca.pem").canonicalFile
    ) else emptySet()
    dependsOn(bundleHttpsCa)
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local runtime assets are incomplete" }
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
