pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

plugins {
    // Resolves a matching JetBrains Runtime toolchain for the hot-reload preview module when not
    // launched from an IDE that already bundles one (see tools/hotreload-preview/build.gradle.kts).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.HereLiesAz.AzNavRail")
            }
        }
    }
}
rootProject.name = "Graffux"
include(":app")
include(":core:common", ":core:domain", ":core:data", ":core:nativebridge", ":core:design", ":core:engine")
include(":feature:editor")
include(":tools:hotreload-preview")
include(":desktop")

// OpenCV (Java + native C++) is imported as a normal Maven Central dependency (org.opencv:opencv),
// not vendored. Since 4.9.0 that artifact ships a Prefab part, so `find_package(OpenCV)` in native
// code resolves against it too — nothing to host, fetch, or commit.
