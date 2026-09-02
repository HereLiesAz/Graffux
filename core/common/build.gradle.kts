import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlinx.serialization) // Required for @Serializable
}

android {
    namespace = "com.hereliesaz.graffitixr.common"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // The azphalt stamp-brush engine — pure Kotlin, shared with the desktop Compose Multiplatform
    // app via core:engine's jvm("desktop") target. `api` because UiSchema/AzphaltBrush/etc. leak
    // into this module's own public API (feature:editor sees them transitively today).
    api(project(":core:engine"))

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Location (Fixes Unresolved reference 'FusedLocationProviderClient')
    implementation(libs.play.services.location)

    // Provider Installer (Fixes SSLHandshakeException)
    implementation(libs.play.services.base)

    // OpenCV (Fixes Unresolved reference 'opencv', 'Mat', 'Imgproc') — imported from Maven Central,
    // not vendored. `api` so downstream modules (data, feature:editor) see org.opencv.* transitively.
    api(libs.opencv)

    // AzNavRail (Fixes NoClassDefFoundError for AzOrientation)
    api(libs.az.nav.rail)

    // Serialization (Fixes Unresolved reference 'serializer')
    // `api` for json: UiSchema/UiControl (azphalt/UiSchema.kt) expose JsonElement in their own
    // public API, so downstream modules (feature:editor) need it on their compile classpath too.
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)

    // Crypto: Ed25519 signature + trust verification for azphalt `.azp` packages
    // (spec/package-format.md § Signing). Android's built-in Ed25519 (java.security) is only API 33+,
    // but this app's minSdk is 26, so Bouncy Castle provides it everywhere. Version pinned to match
    // the root build's forced 1.85 (already on the app classpath transitively).
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")

    // Networking (Crash Reporting)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Logging
    implementation(libs.timber)

    // Hilt / DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}