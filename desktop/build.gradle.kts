import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

// The real Graffux desktop app (Linux + Windows), distinct from `tools:hotreload-preview`'s throwaway
// scratch sandbox. Reuses the azphalt engine's pure math (BrushStamps, AzphaltBrush,
// BrushSensorDynamics, RoundStampCompositor, TileGrid/DirtyRegion) via core:engine's jvm("desktop")
// target — the same dab-placement and edge-falloff math the Android app uses, verified by the same
// commonTest suite on both targets. What's genuinely NEW here (not shared with Android): pointer
// input with pen pressure via Compose Multiplatform's PointerType.Stylus, and a tile-parallel
// compositor that spreads a stroke's dirty region across Dispatchers.Default workers to use the
// multiple CPU cores a Surface Pro ships with. The canvas composites on the GPU through the wgpu
// stamp engine (core/wgpu-engine, built below with cargo) when an adapter exists, and falls back to
// that CPU compositor otherwise. See DESKTOP.md at the repo root for what's verified vs. deferred.
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

    // For ThemeColorSyncTest, which guards GraffuxColors' hand-copied literals (see Theme.kt's doc
    // comment) against drifting from core:design's Color.kt without either module depending on the
    // other — core:design is an Android library and can't be added as a dependency of this plain-JVM
    // desktop app.
    testImplementation(libs.junit)
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

// ---------------------------------------------------------------------------------------------
// The wgpu stamp engine (core/wgpu-engine, Rust), built for the host with cargo and bundled as a
// classpath resource at native/<os>-<arch>/, where WgpuLibrary (core:engine) extracts and loads it.
// Optional: without cargo (or with -Pgraffux.wgpu.skip=true) the app builds and runs on the CPU
// compositor alone; -Pgraffux.wgpu.require=true makes a missing library a build failure.
// ---------------------------------------------------------------------------------------------
val wgpuCrateDir = rootProject.file("core/wgpu-engine")
val wgpuTargetDir = File(wgpuCrateDir, "target")
val wgpuSkip = providers.gradleProperty("graffux.wgpu.skip").map { it.toBoolean() }.getOrElse(false)
val wgpuRequire = providers.gradleProperty("graffux.wgpu.require").map { it.toBoolean() }.getOrElse(false)
val cargoExecutable: File? = (
    listOfNotNull(System.getenv("CARGO_HOME")?.let { File(it, "bin") }, File(System.getProperty("user.home"), ".cargo/bin")) +
        (System.getenv("PATH") ?: "").split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
    ).flatMap { listOf(File(it, "cargo"), File(it, "cargo.exe")) }.firstOrNull { it.canExecute() }
val hostOs = System.getProperty("os.name").lowercase().let {
    when {
        it.startsWith("windows") -> "windows"
        it.startsWith("mac") -> "macos"
        else -> "linux"
    }
}
val hostArch = when (val a = System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    else -> a
}
val hostLibName = when (hostOs) {
    "windows" -> "graffux_wgpu.dll"
    "macos" -> "libgraffux_wgpu.dylib"
    else -> "libgraffux_wgpu.so"
}

val cargoBuildWgpuHost = tasks.register<Exec>("cargoBuildWgpuHost") {
    description = "Builds core/wgpu-engine for this host (skipped without cargo)."
    group = "build"
    val cargo = cargoExecutable
    enabled = !wgpuSkip && cargo != null
    inputs.dir(File(wgpuCrateDir, "src"))
    inputs.file(File(wgpuCrateDir, "Cargo.toml"))
    inputs.file(File(wgpuCrateDir, "Cargo.lock"))
    outputs.file(File(wgpuTargetDir, "release/$hostLibName"))
    workingDir = wgpuCrateDir
    executable = cargo?.absolutePath ?: "cargo"
    args("build", "--release", "--locked", "--target-dir", wgpuTargetDir.absolutePath)
    isIgnoreExitValue = !wgpuRequire
}

val wgpuResourcesDir = layout.buildDirectory.dir("generated/wgpuResources")
val stageWgpuHostLibrary = tasks.register<Sync>("stageWgpuHostLibrary") {
    dependsOn(cargoBuildWgpuHost)
    from(File(wgpuTargetDir, "release")) { include(hostLibName) }
    into(wgpuResourcesDir.map { it.dir("native/$hostOs-$hostArch") })
    val require = wgpuRequire
    val skip = wgpuSkip || cargoExecutable == null
    doLast {
        if (!destinationDir.resolve(hostLibName).isFile && !skip) {
            val message = "$hostLibName was not built; the desktop canvas will use the CPU compositor."
            if (require) throw GradleException(message) else logger.warn("w: $message")
        }
    }
}

sourceSets.main {
    resources.srcDir(files(wgpuResourcesDir).builtBy(stageWgpuHostLibrary))
}
