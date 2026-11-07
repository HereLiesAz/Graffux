plugins {
    id("com.android.library")
}

android {
    namespace = "com.hereliesaz.graffitixr.ml"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    testOptions {
        unitTests {
            // The pure pipeline tests touch no android.* class; this only keeps a stray stub call
            // from throwing.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Host-side ML runtime for `onnx`-type assets (see ARCHITECTURE.md "Host ML runtime").
    // ONNX Runtime rather than LiteRT: the MiDaS listing ships an .onnx graph, and LiteRT cannot load one.
    implementation(libs.onnxruntime.android)
    // AssetContribution / ModelIo: the depth runtime is driven by the manifest's `io` block.
    implementation(project(":core:engine"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
