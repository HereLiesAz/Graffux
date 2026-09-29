plugins {
    id("com.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.hereliesaz.graffitixr.nativebridge"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Build only for ARM architectures (skip x86/x86_64 emulator builds)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                // Google Ink Stroke Modeler requires C++20; the existing native sources are valid
                // C++20 and keep the same ABI through the shared-library boundary.
                cppFlags("-std=c++20")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Consume the OpenCV Maven artifact's Prefab part from CMake (find_package(OpenCV)).
    buildFeatures {
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The androidTest APK pulls in both this module's own libc++_shared.so (from the NDK build
    // above) and the OpenCV AAR's copy of the same file, which MergeNativeLibsTask otherwise
    // rejects as a duplicate. app/build.gradle.kts has the identical fix for the same conflict in
    // the main app APK; this module's own androidTest variant needed it separately since it builds
    // its own APK.
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    // OpenCV from Maven Central. Its Prefab part exposes the native C++ world to CMake
    // (find_package(OpenCV) → OpenCV::opencv_java5) and auto-packages libopencv_java5.so.
    implementation(libs.opencv)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
}

// Pin Kotlin's JVM target to match Java (21). Without this, Kotlin defaults to a lower target
// than the Java sources, which AGP flags as an inconsistent JVM-target compatibility error.
// Uses the same task-based approach as :app (this module applies only AGP + KSP, so the
// `kotlin {}` extension is not registered).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// ---------------------------------------------------------------------------------------------
// The wgpu stamp engine (core/wgpu-engine, Rust): cross-compiled with cargo for both APK ABIs
// (arm64-v8a and armeabi-v7a) and fed into this library's jniLibs, so libgraffux_wgpu.so lands in
// the APK beside libgraffitixr.so. WgpuStampEngine.cpp dlopen()s it on first use; it is the only
// GPU stamp engine, so 32-bit devices need their own build to keep GPU painting.
//
// Optional by design: a build host without cargo or the Rust targets (or with
// -Pgraffux.wgpu.skip=true) builds exactly as before, minus the library -- every stroke then
// draws on the CPU path like a device with no usable GPU. Set -Pgraffux.wgpu.require=true to
// make a library missing for EITHER ABI a build failure instead. One-time host setup:
//     rustup target add aarch64-linux-android armv7-linux-androideabi
// x86_64 is not in abiFilters, so building it would add nothing to the APK.
// ---------------------------------------------------------------------------------------------
val wgpuCrateDir = rootProject.file("core/wgpu-engine")
val wgpuTargetDir = File(wgpuCrateDir, "target")
val wgpuSkip = providers.gradleProperty("graffux.wgpu.skip").map { it.toBoolean() }.getOrElse(false)
val wgpuRequire = providers.gradleProperty("graffux.wgpu.require").map { it.toBoolean() }.getOrElse(false)
val cargoExecutable: File? = (
    listOfNotNull(System.getenv("CARGO_HOME")?.let { File(it, "bin") }, File(System.getProperty("user.home"), ".cargo/bin")) +
        (System.getenv("PATH") ?: "").split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
    ).flatMap { listOf(File(it, "cargo"), File(it, "cargo.exe")) }.firstOrNull { it.canExecute() }

/** One Android ABI: its Rust target triple, jniLibs folder, and NDK clang wrapper prefix. */
data class WgpuAndroidTarget(val rustTarget: String, val abi: String, val clangPrefix: String) {
    val taskSuffix: String get() = abi.split('-', '_').joinToString("") { it.replaceFirstChar(Char::uppercase) }
    val envKey: String get() = rustTarget.replace('-', '_')
}

// Must match abiFilters above (and in app/build.gradle.kts). The armv7 clang wrapper is spelled
// armv7a-, unlike the Rust triple.
val wgpuAndroidTargets = listOf(
    WgpuAndroidTarget("aarch64-linux-android", "arm64-v8a", "aarch64-linux-android"),
    WgpuAndroidTarget("armv7-linux-androideabi", "armeabi-v7a", "armv7a-linux-androideabi"),
)
val wgpuMinSdk = 26
val ndkHostTag = when {
    System.getProperty("os.name").startsWith("Windows") -> "windows-x86_64"
    System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val ndkDirectory = androidComponents.sdkComponents.ndkDirectory

fun wgpuLibrary(target: WgpuAndroidTarget) = File(wgpuTargetDir, "${target.rustTarget}/release/libgraffux_wgpu.so")

val cargoBuildWgpuTasks = wgpuAndroidTargets.map { target ->
    tasks.register<Exec>("cargoBuildWgpuAndroid${target.taskSuffix}") {
        description = "Cross-compiles core/wgpu-engine for ${target.abi} (skipped without cargo/target)."
        group = "build"
        val cargo = cargoExecutable
        enabled = !wgpuSkip && cargo != null
        inputs.dir(File(wgpuCrateDir, "src"))
        inputs.file(File(wgpuCrateDir, "Cargo.toml"))
        inputs.file(File(wgpuCrateDir, "Cargo.lock"))
        outputs.file(wgpuLibrary(target))
        workingDir = wgpuCrateDir
        executable = cargo?.absolutePath ?: "cargo"
        args("build", "--release", "--locked", "--target", target.rustTarget, "--target-dir", wgpuTargetDir.absolutePath)
        // A missing Rust target must not fail the Android build; see the block comment above.
        isIgnoreExitValue = !wgpuRequire
        doFirst {
            val bin = ndkDirectory.get().asFile.resolve("toolchains/llvm/prebuilt/$ndkHostTag/bin")
            val suffix = if (ndkHostTag.startsWith("windows")) ".cmd" else ""
            val clang = bin.resolve("${target.clangPrefix}$wgpuMinSdk-clang$suffix").absolutePath
            environment("CARGO_TARGET_${target.envKey.uppercase()}_LINKER", clang)
            environment("CC_${target.envKey}", clang)
            environment("AR_${target.envKey}", bin.resolve("llvm-ar").absolutePath)
        }
    }
}
// Aggregate under the old name so existing invocations still work.
tasks.register("cargoBuildWgpuAndroid") {
    description = "Cross-compiles core/wgpu-engine for every APK ABI."
    group = "build"
    dependsOn(cargoBuildWgpuTasks)
}

/** Stages each ABI's cargo output as `<abi>/libgraffux_wgpu.so` in a generated jniLibs directory. */
abstract class StageWgpuJniLibs : DefaultTask() {
    /** ABI folder name -> path of the cargo-built library for that ABI. */
    @get:Input
    abstract val libraries: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraryFiles: ConfigurableFileCollection

    @get:Input
    abstract val require: Property<Boolean>

    @get:Input
    abstract val skip: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        // Skipped means skipped: never package a stale library left in the cargo target directory.
        if (skip.get()) return
        val missing = mutableListOf<String>()
        for ((abi, path) in libraries.get()) {
            val lib = File(path)
            if (lib.isFile) lib.copyTo(out.resolve("$abi/libgraffux_wgpu.so"), overwrite = true) else missing += abi
        }
        if (missing.isEmpty()) return
        val message = "libgraffux_wgpu.so was not built for ${missing.joinToString()} (cargo or a Rust target " +
            "is missing -- `rustup target add aarch64-linux-android armv7-linux-androideabi`). " +
            "The wgpu GPU engine falls back to the CPU on those ABIs."
        if (require.get()) throw GradleException(message) else logger.warn("w: $message")
    }
}

val stageWgpuJniLibs = tasks.register<StageWgpuJniLibs>("stageWgpuJniLibs") {
    dependsOn(cargoBuildWgpuTasks)
    wgpuAndroidTargets.forEach { target ->
        libraries.put(target.abi, wgpuLibrary(target).absolutePath)
        libraryFiles.from(wgpuLibrary(target))
    }
    require.set(wgpuRequire)
    skip.set(wgpuSkip)
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(stageWgpuJniLibs, StageWgpuJniLibs::outputDir)
    }
}
