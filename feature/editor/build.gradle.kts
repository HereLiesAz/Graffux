plugins {
    id("com.android.library")
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.hereliesaz.graffitixr.feature.editor"
    compileSdk = 37

    // Fixtures shared by the JVM tests and the device tests (stroke rendering runs only on the GPU,
    // so tests that check painted pixels live in androidTest).
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/java")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/java")
    }

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
            freeCompilerArgs.add("-XXLanguage:+PropertyParamAnnotationDefaultTargetMode")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric needs the merged resources and manifest to inflate anything, and a Compose
            // test inflates a real host Activity. Without this the gesture tests below cannot run at
            // all — they fail at setContent, long before they reach an assertion.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:design"))

    implementation(project(":core:nativebridge"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation("androidx.input:input-motionprediction:1.0.0") // TEMPORARY: ranked predictor
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)


    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.mlkit.subject.segmentation)

    implementation(libs.hilt.android)
    // Jetpack Ink: the engine behind the Ink utensils in the brush list (Ink Pen, Marker, ...;
    // see InkUtensil). minSdk 23, below this module's 26, so no runtime gate is needed.
    implementation(libs.androidx.ink.authoring)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.geometry)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.strokes)
    ksp(libs.hilt.compiler)

    implementation(libs.az.nav.rail)
    implementation(libs.compose.ui.text.google.fonts)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // Compose UI testing, on the JVM via Robolectric. Needed to inject real multi-touch: the canvas
    // gestures — two-finger pan/zoom/rotate above all — are pure pointer-event arbitration between
    // stacked pointerInput layers, and nothing below the Compose runtime can exercise that. They had
    // no coverage at all until this was wired up.
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    // Stroke rendering tests run on a device: the strokes paint only on the GPU (wgpu), which the
    // JVM and Robolectric don't have.
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
