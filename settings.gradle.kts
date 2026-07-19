pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "GraffiXR"
include(":app")
include(":core:common", ":core:domain", ":core:data", ":core:nativebridge", ":core:design")
include(":feature:editor")

// OpenCV (Java + native C++) is imported as a normal Maven Central dependency (org.opencv:opencv),
// not vendored. Since 4.9.0 that artifact ships a Prefab part, so `find_package(OpenCV)` in native
// code resolves against it too — nothing to host, fetch, or commit.
