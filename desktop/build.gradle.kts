import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

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
    // The real AzNavRail UI (Compose Multiplatform port), so this app uses the same rail/tool
    // navigation as the Android app instead of a placeholder scaffold — see DESKTOP.md.
    implementation(libs.az.nav.rail.cmp)
}

// Read-only: unlike app/build.gradle.kts, this does NOT increment versionPatch/versionBuild — a
// desktop packaging pass has no business advancing the shared version counter the Android release
// pipeline owns. It just reports whatever MAJOR.MINOR.PATCH is currently committed, so a `.deb`'s
// `dpkg -s`/an `.msi`'s "Programs and Features" entry names the same release the APK does instead
// of a permanently-stale placeholder.
val versionProps = Properties().apply {
    val file = rootProject.file("version.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
// WiX (jpackage's Windows Msi backend) requires each of the three version fields to fit in 0..255;
// versionMajor/versionMinor/versionPatch already have to satisfy that for the Android versionName's
// own sake, so no separate range check is added here.
val desktopPackageVersion =
    "${versionProps.getProperty("versionMajor", "1")}.${versionProps.getProperty("versionMinor", "0")}.${versionProps.getProperty("versionPatch", "0")}"

compose.desktop {
    application {
        mainClass = "com.hereliesaz.graffux.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)
            packageName = "Graffux"
            packageVersion = desktopPackageVersion
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
