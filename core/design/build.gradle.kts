plugins {
    id("com.android.library")
    alias(libs.plugins.jetbrains.kotlin.compose)
}

android {
    namespace = "com.hereliesaz.graffitixr.design"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
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

    buildFeatures {
        compose = true
    }

}

dependencies {
    implementation(project(":core:common"))

    // UI Frameworks
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.az.nav.rail)

    // MISSING DEPENDENCY RESTORED:
    // Required for legacy vector drawables using ?attr/colorControlNormal
    implementation(libs.androidx.appcompat)
}
