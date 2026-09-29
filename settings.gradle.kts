pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Optional offline cache for this workstation; clean clones use official repositories.
        val localMaven = file(".local/maven")
        if (localMaven.isDirectory) maven { url = uri(localMaven) }
        google()
        mavenCentral()
    }
}

rootProject.name = "WheelPlay"
include(":common")
include(":mobile")

include(":shared")
