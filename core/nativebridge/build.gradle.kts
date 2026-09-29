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
// The wgpu stamp engine (core/wgpu-engine, Rust): cross-compiled with cargo for arm64-v8a and fed
// into this library's jniLibs, so libgraffux_wgpu.so lands in the APK beside libgraffitixr.so.
// WgpuStampEngine.cpp dlopen()s it on first use; it is the only GPU stamp engine.
//
// Optional by design: a build host without cargo or the aarch64-linux-android Rust target (or with
// -Pgraffux.wgpu.skip=true) builds exactly as before, minus the library -- every stroke then
// draws on the CPU path like a device with no usable GPU. Set -Pgraffux.wgpu.require=true to
// make a missing library a build failure instead. One-time host setup:
//     rustup target add aarch64-linux-android
// Only arm64-v8a is built: armeabi-v7a devices (the APK's other ABI) fall back the same way, and
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
val androidRustTarget = "aarch64-linux-android"
val androidRustAbi = "arm64-v8a"
val wgpuMinSdk = 26
val ndkHostTag = when {
    System.getProperty("os.name").startsWith("Windows") -> "windows-x86_64"
    System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val ndkDirectory = androidComponents.sdkComponents.ndkDirectory

val cargoBuildWgpuAndroid = tasks.register<Exec>("cargoBuildWgpuAndroid") {
    description = "Cross-compiles core/wgpu-engine for $androidRustAbi (skipped without cargo/target)."
    group = "build"
    val cargo = cargoExecutable
    enabled = !wgpuSkip && cargo != null
    inputs.dir(File(wgpuCrateDir, "src"))
    inputs.file(File(wgpuCrateDir, "Cargo.toml"))
    inputs.file(File(wgpuCrateDir, "Cargo.lock"))
    outputs.file(File(wgpuTargetDir, "$androidRustTarget/release/libgraffux_wgpu.so"))
    workingDir = wgpuCrateDir
    executable = cargo?.absolutePath ?: "cargo"
    args("build", "--release", "--locked", "--target", androidRustTarget, "--target-dir", wgpuTargetDir.absolutePath)
    // A missing Rust target must not fail the Android build; see the block comment above.
    isIgnoreExitValue = !wgpuRequire
    doFirst {
        val bin = ndkDirectory.get().asFile.resolve("toolchains/llvm/prebuilt/$ndkHostTag/bin")
        val suffix = if (ndkHostTag.startsWith("windows")) ".cmd" else ""
        val clang = bin.resolve("aarch64-linux-android$wgpuMinSdk-clang$suffix").absolutePath
        environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", clang)
        environment("CC_aarch64_linux_android", clang)
        environment("AR_aarch64_linux_android", bin.resolve("llvm-ar").absolutePath)
    }
}

/** Stages the cargo output as `<abi>/libgraffux_wgpu.so` in a generated jniLibs directory. */
abstract class StageWgpuJniLibs : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val library: ConfigurableFileCollection

    @get:Input
    abstract val abi: Property<String>

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
        val lib = library.files.firstOrNull { it.isFile }
        if (lib != null) {
            lib.copyTo(out.resolve("${abi.get()}/libgraffux_wgpu.so"), overwrite = true)
            return
        }
        val message = "libgraffux_wgpu.so was not built (cargo or the aarch64-linux-android Rust target is " +
            "missing -- `rustup target add aarch64-linux-android`). The wgpu GPU engine falls back to the CPU."
        if (require.get()) throw GradleException(message) else logger.warn("w: $message")
    }
}

val stageWgpuJniLibs = tasks.register<StageWgpuJniLibs>("stageWgpuJniLibs") {
    dependsOn(cargoBuildWgpuAndroid)
    library.from(File(wgpuTargetDir, "$androidRustTarget/release/libgraffux_wgpu.so"))
    abi.set(androidRustAbi)
    require.set(wgpuRequire)
    skip.set(wgpuSkip)
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(stageWgpuJniLibs, StageWgpuJniLibs::outputDir)
    }
}
