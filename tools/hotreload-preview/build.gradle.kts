// A plain Kotlin/JVM module, deliberately outside the Android build graph.
//
// Compose Hot Reload runs on the JetBrains Runtime and cannot attach to code running on an actual
// Android device or emulator, so it can't target :app, :feature:editor, or :core:design directly —
// those pull in CameraX/OpenCV/Hilt/native code (or, for :core:design, the Android-AAR-only
// az-nav-rail) that don't run on a plain JVM. This module is a desktop sandbox for iterating on
// Compose UI with instant reload; treat it as a scratch space to prototype a composable before
// porting the change back into the real Android module, not as a preview of the shipping app.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.hot.reload)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "com.hereliesaz.graffux.hotreload.MainKt"
    }
}
