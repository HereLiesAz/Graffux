import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// The azphalt stamp-brush engine as a real Kotlin Multiplatform module: pure math and data classes
// (BrushStamps, AzphaltBrush, BrushSensorDynamics, TileGrid, DirtyRegion, ...) with zero Android
// dependency, shared verbatim between the Android app and the desktop (Linux/Windows) Compose
// Multiplatform app. `core:common` keeps depending on this module under the same package name, so
// nothing on the Android side needed to change its imports.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // No version here: AGP already put this plugin on the buildscript classpath (via
    // com.android.library elsewhere in the build) without a declared plugin-marker version, and
    // pinning one here conflicts with that. Its version tracks AGP's own (see `agp` in the
    // version catalog).
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlinx.serialization)
}

kotlin {
    jvmToolchain(21)

    // AGP 9's Kotlin-Multiplatform-native Android library plugin: replaces the classic
    // `com.android.library` + `androidTarget()` pairing, which AGP 9 no longer allows to combine
    // with the `org.jetbrains.kotlin.multiplatform` plugin.
    android {
        namespace = "com.hereliesaz.graffitixr.common.azphalt"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.cbor)
            // Ed25519 signature verification (spec/package-format.md § Signing). Pure-Java, so it
            // resolves identically on both JVM-flavored targets (androidLibrary and jvm("desktop")).
            implementation("org.bouncycastle:bcprov-jdk18on:1.86")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }

    // The default hierarchy plus one group: `jvmSharedMain`, JVM code shared by the Android and
    // desktop targets but not by commonMain -- the JNI wrapper of the Rust wgpu stamp engine
    // (core/wgpu-engine). `external` and System.load are JVM-only, and both targets load the very
    // same libgraffux_wgpu JNI surface.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }
}
