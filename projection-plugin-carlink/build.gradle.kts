import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.artifact.ArtifactTransformationRequest
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "moe.feng.wheelplay.plugin.carlink"
    compileSdk = 36
    defaultConfig {
        applicationId = "moe.feng.wheelplay.plugin.carlink"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        create("release") {
            storeFile = file(providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                .getOrElse("missing-release-keystore.jks"))
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Vendor classes are appended after AGP packaging; do not shrink their dependencies.
            optimization { enable = false }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// Source-only signatures, never packaged. Public tests do not need proprietary files.
val compileSdkStubs by tasks.registering(JavaCompile::class) {
    source(fileTree("sdk-stubs") { include("**/*.java") })
    classpath = files(androidComponents.sdkComponents.bootClasspath)
    destinationDirectory.set(layout.buildDirectory.dir("sdk-stubs/classes"))
    sourceCompatibility = "11"
    targetCompatibility = "11"
}
val sdkStubsJar by tasks.registering(Jar::class) {
    from(compileSdkStubs)
    archiveFileName.set("carlink-signatures.jar")
    destinationDirectory.set(layout.buildDirectory.dir("sdk-stubs"))
}
dependencies {
    implementation(project(":ui-theme"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(project(":projection-plugin-api"))
    implementation("androidx.room:room-runtime:2.4.3")
    // The UI may select a newer compatible Core; the vendor SDK uses the stable Core API.
    implementation("androidx.core:core:1.12.0") {
        // The pinned SDK already defines Guava's ListenableFuture.
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    implementation("androidx.collection:collection:1.5.0")
    compileOnly(files(sdkStubsJar))
    testImplementation(libs.junit)
    testImplementation(files(sdkStubsJar)) // Binder signatures for local adapter tests only.
    testImplementation("org.robolectric:robolectric:4.17")
}

abstract class PackageCarLink @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory abstract val inputApks: DirectoryProperty
    @get:OutputDirectory abstract val outputApks: DirectoryProperty
    @get:Input abstract val variantName: Property<String>
    @get:Input abstract val sdkPath: Property<String>
    @get:InputDirectory abstract val buildTools: DirectoryProperty
    @get:InputFile abstract val script: RegularFileProperty
    @get:InputFile abstract val lockFile: RegularFileProperty
    @get:Internal abstract val keyFile: RegularFileProperty
    @get:Internal abstract val transformation: Property<ArtifactTransformationRequest<PackageCarLink>>
    init { outputs.upToDateWhen { false } }
    @TaskAction fun packageApks() {
        exec.exec {
            commandLine("python3", script.get().asFile, "package", "--sdk", sdkPath.get(),
                "--lock", lockFile.get().asFile, "--input", inputApks.get().asFile,
                "--output", outputApks.get().asFile, "--build-tools", buildTools.get().asFile,
                "--keystore", keyFile.get().asFile, "--variant", variantName.get())
        }
        transformation.get().submit(this) { artifact -> outputApks.file(File(artifact.outputFile).name).get().asFile }
    }
}

androidComponents {
    onVariants { variant ->
        val stableIds = if (variant.buildType == "debug") "stable-ids-debug.txt" else "stable-ids.txt"
        variant.androidResources.aaptAdditionalParameters.addAll("--stable-ids", file(stableIds).absolutePath)
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val packageTask = tasks.register<PackageCarLink>("packageCarLink$suffix") {
            variantName.set(variant.name)
            sdkPath.set(providers.gradleProperty("carlinkSdkDir").orElse(rootProject.file(".local/carlink-sdk").absolutePath))
            script.set(rootProject.layout.projectDirectory.file("scripts/carlink_sdk.py"))
            lockFile.set(layout.projectDirectory.file("sdk-lock.json"))
            buildTools.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("build-tools/${android.buildToolsVersion}") })
            keyFile.set(if (variant.buildType == "release")
                file(providers.environmentVariable("ANDROID_KEYSTORE_PATH").getOrElse("missing-release-keystore.jks"))
            else file(System.getProperty("user.home") + "/.android/debug.keystore"))
        }
        val request = variant.artifacts.use(packageTask).wiredWithDirectories(PackageCarLink::inputApks, PackageCarLink::outputApks)
            .toTransformMany(SingleArtifact.APK)
        packageTask.configure { transformation.set(request) }
    }
}

configurations.configureEach { exclude(group = "com.google.guava", module = "listenablefuture") }
