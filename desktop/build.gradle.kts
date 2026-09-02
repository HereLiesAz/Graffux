import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// The real Graffux desktop app (Linux + Windows), distinct from `tools:hotreload-preview`'s throwaway
// scratch sandbox. Reuses the azphalt engine's pure math (BrushStamps, AzphaltBrush,
// BrushSensorDynamics, RoundStampCompositor, TileGrid/DirtyRegion) via core:engine's jvm("desktop")
// target — the same dab-placement and edge-falloff math the Android app uses, verified by the same
// commonTest suite on both targets. What's genuinely NEW here (not shared with Android): pointer
// input with pen pressure via Compose Multiplatform's PointerType.Stylus, and a tile-parallel
// compositor that spreads a stroke's dirty region across Dispatchers.Default workers to use the
// multiple CPU cores a Surface Pro ships with. See DESKTOP.md at the repo root for what's verified
// vs. deferred (a native GPU-accelerated engine is NOT part of this — see that doc for why).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:engine"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
}

compose.desktop {
    application {
        mainClass = "com.hereliesaz.graffux.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)
            packageName = "Graffux"
            packageVersion = "1.0.0"
            description = "Graffux — graffiti/mural design and AR preview"
            vendor = "HereLiesAz"

            linux {
                shortcut = true
            }
            windows {
                shortcut = true
                menu = true
                // Runs unsigned on Windows until a code-signing certificate is wired into CI —
                // installer will show an "unknown publisher" warning. Not exercised by this build:
                // Msi packaging needs WiX Toolset, only available on a Windows host/runner.
                perUserInstall = true
            }
        }
    }
}
