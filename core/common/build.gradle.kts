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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

    // Provider Installer (Fixes SSLHandshakeException)
    implementation(libs.play.services.base)


    // Serialization (Fixes Unresolved reference 'serializer')
    // `api` for json: UiSchema/UiControl (azphalt/UiSchema.kt) expose JsonElement in their own
    // public API, so downstream modules (feature:editor) need it on their compile classpath too.
    api(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.timber)

    // Hilt / DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    // Op/BrushStroke CBOR round-trip tests, and Ed25519 keys for the .azp signature tests (the
    // signing code itself lives in core:engine, which carries Bouncy Castle).
    testImplementation(libs.kotlinx.serialization.cbor)
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.86")
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}